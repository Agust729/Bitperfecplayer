package com.bitperfect.player.audio

import android.content.Context
import com.bitperfect.player.model.AudioTrackItem
import com.bitperfect.player.model.BitPerfectStatus
import com.bitperfect.player.model.OutputMode
import com.bitperfect.player.model.SignalPathInfo
import com.bitperfect.player.model.UsbDacInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class BitPerfectPlayerEngine(private val context: Context) {

    val directUsbEngine = DirectUsbDacEngine(context)
    private val nativeOutput = NativeBitPerfectOutput(context)
    private val decoder = AudioDecoder(context)

    private val _queue = MutableStateFlow<List<AudioTrackItem>>(emptyList())
    val queue: StateFlow<List<AudioTrackItem>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentTrack = MutableStateFlow<AudioTrackItem?>(null)
    val currentTrack: StateFlow<AudioTrackItem?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _outputMode = MutableStateFlow(OutputMode.AUTO_HYBRID)
    val outputMode: StateFlow<OutputMode> = _outputMode.asStateFlow()

    private val _signalPath = MutableStateFlow(SignalPathInfo())
    val signalPath: StateFlow<SignalPathInfo> = _signalPath.asStateFlow()

    val connectedUsbDacs: StateFlow<List<UsbDacInfo>> = directUsbEngine.connectedDacs

    private val isPausedFlag = AtomicBoolean(false)
    private val cancelPlaybackFlag = AtomicBoolean(false)
    private val pendingSeekMs = AtomicLong(-1L)
    private var playbackThread: Thread? = null

    private var usingDirectUsb = false
    private var activeSampleRate = 0
    private var activeBitDepth = 0
    private var activeChannels = 0

    fun initialize() {
        directUsbEngine.register()
    }

    fun release() {
        stopPlaybackThread()
        nativeOutput.release()
        directUsbEngine.unregister()
    }

    fun setOutputMode(mode: OutputMode) {
        _outputMode.value = mode
        val track = _currentTrack.value
        if (track != null && _isPlaying.value) {
            val pos = _positionMs.value
            playTrackAtIndex(_currentIndex.value, pos)
        }
    }

    fun requestUsbPermission(deviceId: Int) {
        directUsbEngine.requestPermission(deviceId)
    }

    fun setQueueAndPlay(tracks: List<AudioTrackItem>, startIndex: Int) {
        _queue.value = tracks
        playTrackAtIndex(startIndex, 0L)
    }

    fun playTrackAtIndex(index: Int, startPositionMs: Long = 0L) {
        val list = _queue.value
        if (index !in list.indices) return

        stopPlaybackThread()
        _currentIndex.value = index
        val track = list[index]
        _currentTrack.value = track
        _positionMs.value = startPositionMs
        isPausedFlag.set(false)
        _isPlaying.value = true
        cancelPlaybackFlag.set(false)
        pendingSeekMs.set(-1L)

        playbackThread = Thread({
            runPlaybackLoop(index, startPositionMs)
        }, "BitPerfectPlaybackThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun togglePlayPause() {
        if (_currentTrack.value == null) return
        val newPaused = !isPausedFlag.get()
        isPausedFlag.set(newPaused)
        _isPlaying.value = !newPaused
        if (!usingDirectUsb) {
            if (newPaused) nativeOutput.pause() else nativeOutput.resume()
        }
    }

    fun seekTo(ms: Long) {
        val idx = _currentIndex.value
        if (idx !in _queue.value.indices) return
        _positionMs.value = ms
        if (!usingDirectUsb) nativeOutput.flush()
        playTrackAtIndex(idx, ms)
    }

    fun nextTrack() {
        val list = _queue.value
        if (list.isEmpty()) return
        val next = (_currentIndex.value + 1) % list.size
        playTrackAtIndex(next, 0L)
    }

    fun previousTrack() {
        val list = _queue.value
        if (list.isEmpty()) return
        if (_positionMs.value > 3000L) {
            seekTo(0L)
            return
        }
        val prev = if (_currentIndex.value - 1 < 0) list.lastIndex else _currentIndex.value - 1
        playTrackAtIndex(prev, 0L)
    }

    private fun stopPlaybackThread() {
        cancelPlaybackFlag.set(true)
        isPausedFlag.set(false)
        try {
            playbackThread?.join(500)
        } catch (_: InterruptedException) {
        }
        playbackThread = null
        directUsbEngine.stop()
        nativeOutput.release()
        usingDirectUsb = false
        activeSampleRate = 0
        activeBitDepth = 0
        activeChannels = 0
    }

    private fun runPlaybackLoop(startIndex: Int, initialSeekMs: Long) {
        var idx = startIndex
        var seekMs = initialSeekMs

        while (!cancelPlaybackFlag.get() && idx in _queue.value.indices) {
            val track = _queue.value[idx]
            _currentIndex.value = idx
            _currentTrack.value = track
            _positionMs.value = seekMs

            val hasNextGapless = (idx + 1) < _queue.value.size

            decoder.decodeTrack(
                track = track,
                seekToMs = seekMs,
                targetBitDepth = track.bitDepth,
                isCancelled = { cancelPlaybackFlag.get() },
                isPaused = { isPausedFlag.get() },
                onFormatReady = { sr, bps, ch ->
                    configureOutputForFormat(track, sr, bps, ch, hasNextGapless)
                },
                onChunk = { chunk ->
                    _positionMs.value = chunk.presentationTimeMs
                    if (usingDirectUsb) {
                        directUsbEngine.writePcmBlocking(
                            chunk.data, 0, chunk.length,
                            isCancelled = { cancelPlaybackFlag.get() }
                        )
                    } else {
                        nativeOutput.writePcm(chunk.data, 0, chunk.length)
                    }
                }
            )

            if (cancelPlaybackFlag.get()) break

            // Advance gaplessly to next track
            idx++
            seekMs = 0L
        }

        if (!cancelPlaybackFlag.get()) {
            _isPlaying.value = false
            _signalPath.value = _signalPath.value.copy(
                status = BitPerfectStatus.IDLE,
                gaplessReady = false
            )
        }
    }

    private fun configureOutputForFormat(
        track: AudioTrackItem,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        gaplessReady: Boolean
    ) {
        val formatUnchanged = (activeSampleRate == sampleRate &&
            activeBitDepth == bitDepth &&
            activeChannels == channels)

        if (formatUnchanged && (usingDirectUsb || activeSampleRate != 0)) {
            // Gapless continuation on identical hardware configuration!
            _signalPath.value = _signalPath.value.copy(
                sourceFormat = track.formatBadge,
                gaplessReady = gaplessReady,
                bufferFillPercent = if (usingDirectUsb) directUsbEngine.getBufferFillPercent() else 100
            )
            return
        }

        val mode = _outputMode.value
        var usbConfigured = false

        if (mode == OutputMode.DIRECT_USB_DAC || mode == OutputMode.AUTO_HYBRID) {
            val usbDev = directUsbEngine.openFirstPermittedDac()
            if (usbDev != null) {
                nativeOutput.release()
                if (directUsbEngine.configureAndStart(sampleRate, bitDepth, channels)) {
                    usbConfigured = true
                    usingDirectUsb = true
                    activeSampleRate = sampleRate
                    activeBitDepth = bitDepth
                    activeChannels = channels

                    _signalPath.value = SignalPathInfo(
                        status = BitPerfectStatus.BIT_PERFECT_DIRECT_USB,
                        sourceFormat = track.formatBadge,
                        decoderInfo = "PCM Bit-Exact (0 DSP / Sin Re-muestreo)",
                        outputRoute = "Driver USB Directo (usbdevfs UAC1/2)",
                        hardwareName = usbDev.productName ?: "USB Audio DAC",
                        activeSampleRate = sampleRate,
                        activeBitDepth = bitDepth,
                        activeChannels = channels,
                        bufferFillPercent = directUsbEngine.getBufferFillPercent(),
                        gaplessReady = gaplessReady
                    )
                }
            }
        }

        if (!usbConfigured && mode != OutputMode.DIRECT_USB_DAC) {
            directUsbEngine.stop()
            usingDirectUsb = false
            val res = nativeOutput.configure(sampleRate, bitDepth, channels)
            activeSampleRate = res.actualSampleRate
            activeBitDepth = res.actualBitDepth
            activeChannels = channels

            _signalPath.value = SignalPathInfo(
                status = res.status,
                sourceFormat = track.formatBadge,
                decoderInfo = "PCM Directo (0 DSP / Volumen Digital 100%)",
                outputRoute = res.outputRoute,
                hardwareName = res.hardwareName,
                activeSampleRate = res.actualSampleRate,
                activeBitDepth = res.actualBitDepth,
                activeChannels = channels,
                bufferFillPercent = 100,
                gaplessReady = gaplessReady
            )
        } else if (!usbConfigured && mode == OutputMode.DIRECT_USB_DAC) {
            _signalPath.value = SignalPathInfo(
                status = BitPerfectStatus.IDLE,
                sourceFormat = track.formatBadge,
                decoderInfo = "Esperando permiso o conexión de USB DAC",
                outputRoute = "USB DAC Directo requerido",
                hardwareName = "Ningún USB DAC autorizado",
                activeSampleRate = 0,
                activeBitDepth = 0,
                activeChannels = channels,
                bufferFillPercent = 0,
                gaplessReady = false
            )
        }
    }
}
