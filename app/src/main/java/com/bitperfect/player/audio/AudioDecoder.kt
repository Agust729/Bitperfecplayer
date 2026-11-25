package com.bitperfect.player.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.bitperfect.player.model.AudioTrackItem
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedChunk(
    val data: ByteArray,
    val length: Int,
    val presentationTimeMs: Long
)

class AudioDecoder(private val context: Context) {

    fun decodeTrack(
        track: AudioTrackItem,
        seekToMs: Long,
        targetBitDepth: Int,
        isCancelled: () -> Boolean,
        isPaused: () -> Boolean,
        onFormatReady: (sampleRate: Int, bitDepth: Int, channels: Int) -> Unit,
        onChunk: (DecodedChunk) -> Unit
    ) {
        if (track.codec.equals("WAV", ignoreCase = true) &&
            streamWavDirect(track, seekToMs, isCancelled, isPaused, onFormatReady, onChunk)
        ) {
            return
        }

        decodeWithMediaCodec(track, seekToMs, targetBitDepth, isCancelled, isPaused, onFormatReady, onChunk)
    }

    private fun streamWavDirect(
        track: AudioTrackItem,
        seekToMs: Long,
        isCancelled: () -> Boolean,
        isPaused: () -> Boolean,
        onFormatReady: (Int, Int, Int) -> Unit,
        onChunk: (DecodedChunk) -> Unit
    ): Boolean {
        return try {
            context.contentResolver.openInputStream(track.uri)?.use { input ->
                val dataOffset = findWavDataChunk(input) ?: return false
                val bytesPerSample = (track.bitDepth / 8).coerceAtLeast(1)
                val bytesPerFrame = bytesPerSample * track.channels
                val bytesPerSecond = track.sampleRate * bytesPerFrame

                onFormatReady(track.sampleRate, track.bitDepth, track.channels)

                if (seekToMs > 0) {
                    val skipBytes = ((seekToMs * bytesPerSecond) / 1000L)
                        .let { it - (it % bytesPerFrame) }
                    skipFully(input, skipBytes)
                }

                val buffer = ByteArray(16384 - (16384 % bytesPerFrame))
                var bytesStreamed = if (seekToMs > 0) (seekToMs * bytesPerSecond) / 1000L else 0L

                while (!isCancelled()) {
                    if (isPaused()) {
                        Thread.sleep(20)
                        continue
                    }
                    val read = input.read(buffer)
                    if (read <= 0) break
                    bytesStreamed += read
                    val posMs = if (bytesPerSecond > 0) (bytesStreamed * 1000L) / bytesPerSecond else 0L
                    onChunk(DecodedChunk(buffer, read, posMs))
                }
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun findWavDataChunk(input: InputStream): Long? {
        val riffHeader = ByteArray(12)
        if (input.read(riffHeader) != 12) return null
        if (riffHeader[0] != 'R'.code.toByte() || riffHeader[8] != 'W'.code.toByte()) return null

        var offset = 12L
        val chunkHeader = ByteArray(8)
        while (input.read(chunkHeader) == 8) {
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            offset += 8
            if (id == "data") {
                return offset
            }
            skipFully(input, size)
            offset += size
        }
        return null
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun decodeWithMediaCodec(
        track: AudioTrackItem,
        seekToMs: Long,
        targetBitDepth: Int,
        isCancelled: () -> Boolean,
        isPaused: () -> Boolean,
        onFormatReady: (Int, Int, Int) -> Unit,
        onChunk: (DecodedChunk) -> Unit
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, track.uri, null)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || inputFormat == null) return

            extractor.selectTrack(audioTrackIndex)
            if (seekToMs > 0) {
                extractor.seekTo(seekToMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val desiredEnc = when (targetBitDepth) {
                    24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                    32 -> AudioFormat.ENCODING_PCM_32BIT
                    else -> AudioFormat.ENCODING_PCM_16BIT
                }
                inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, desiredEnc)
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var formatReported = false
            var outBufArray = ByteArray(32768)

            while (!outputDone && !isCancelled()) {
                if (isPaused()) {
                    Thread.sleep(20)
                    continue
                }

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime
                                codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 5000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        val sr = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val ch = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val pcmEnc = if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        val bps = when (pcmEnc) {
                            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                            AudioFormat.ENCODING_PCM_32BIT -> 32
                            AudioFormat.ENCODING_PCM_FLOAT -> 32
                            else -> 16
                        }
                        formatReported = true
                        onFormatReady(sr, bps, ch)
                    }
                    outIdx >= 0 -> {
                        if (!formatReported) {
                            formatReported = true
                            onFormatReady(track.sampleRate, track.bitDepth, track.channels)
                        }
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            if (outBufArray.size < info.size) {
                                outBufArray = ByteArray(info.size)
                            }
                            outBuf.get(outBufArray, 0, info.size)
                            onChunk(
                                DecodedChunk(
                                    data = outBufArray,
                                    length = info.size,
                                    presentationTimeMs = info.presentationTimeUs / 1000L
                                )
                            )
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Playback aborted or unsupported codec
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }
}
