package com.bitperfect.player.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.player.audio.BitPerfectPlayerEngine
import com.bitperfect.player.data.MusicRepository
import com.bitperfect.player.model.AudioTrackItem
import com.bitperfect.player.model.OutputMode
import com.bitperfect.player.model.SignalPathInfo
import com.bitperfect.player.model.UsbDacInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private var engine: BitPerfectPlayerEngine? = null

    private val _library = MutableStateFlow<List<AudioTrackItem>>(emptyList())
    val library: StateFlow<List<AudioTrackItem>> = _library.asStateFlow()

    private val _isLoadingLibrary = MutableStateFlow(false)
    val isLoadingLibrary: StateFlow<Boolean> = _isLoadingLibrary.asStateFlow()

    private val _currentTrack = MutableStateFlow<AudioTrackItem?>(null)
    val currentTrack: StateFlow<AudioTrackItem?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _signalPath = MutableStateFlow(SignalPathInfo())
    val signalPath: StateFlow<SignalPathInfo> = _signalPath.asStateFlow()

    private val _outputMode = MutableStateFlow(OutputMode.AUTO_HYBRID)
    val outputMode: StateFlow<OutputMode> = _outputMode.asStateFlow()

    private val _connectedDacs = MutableStateFlow<List<UsbDacInfo>>(emptyList())
    val connectedDacs: StateFlow<List<UsbDacInfo>> = _connectedDacs.asStateFlow()

    private val _currentArtwork = MutableStateFlow<Bitmap?>(null)
    val currentArtwork: StateFlow<Bitmap?> = _currentArtwork.asStateFlow()

    fun attachEngine(playerEngine: BitPerfectPlayerEngine) {
        if (engine === playerEngine) return
        engine = playerEngine

        viewModelScope.launch { playerEngine.currentTrack.collect { track ->
            _currentTrack.value = track
            loadArtwork(track?.albumArtUri)
        } }
        viewModelScope.launch { playerEngine.isPlaying.collect { _isPlaying.value = it } }
        viewModelScope.launch { playerEngine.positionMs.collect { _positionMs.value = it } }
        viewModelScope.launch { playerEngine.signalPath.collect { _signalPath.value = it } }
        viewModelScope.launch { playerEngine.outputMode.collect { _outputMode.value = it } }
        viewModelScope.launch { playerEngine.connectedUsbDacs.collect { _connectedDacs.value = it } }
    }

    fun scanLibrary() {
        viewModelScope.launch {
            _isLoadingLibrary.value = true
            _library.value = repository.loadAudioLibrary()
            _isLoadingLibrary.value = false
        }
    }

    fun playTrack(track: AudioTrackItem) {
        val list = _library.value
        val index = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        engine?.setQueueAndPlay(list, index)
    }

    fun togglePlayPause() = engine?.togglePlayPause()
    fun nextTrack() = engine?.nextTrack()
    fun previousTrack() = engine?.previousTrack()
    fun seekTo(ms: Long) = engine?.seekTo(ms)
    fun setOutputMode(mode: OutputMode) = engine?.setOutputMode(mode)
    fun requestUsbPermission(deviceId: Int) = engine?.requestUsbPermission(deviceId)
    fun refreshUsbDacs() = engine?.directUsbEngine?.refreshDevices()

    private fun loadArtwork(uri: Uri?) {
        if (uri == null) {
            _currentArtwork.value = null
            return
        }
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } catch (_: Exception) {
                    null
                }
            }
            _currentArtwork.value = bmp
        }
    }
}
