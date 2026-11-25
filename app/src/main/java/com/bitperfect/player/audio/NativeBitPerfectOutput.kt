package com.bitperfect.player.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import com.bitperfect.player.model.BitPerfectStatus

data class NativeOutputResult(
    val configured: Boolean,
    val status: BitPerfectStatus,
    val hardwareName: String,
    val outputRoute: String,
    val actualSampleRate: Int,
    val actualBitDepth: Int
)

/**
 * Uses Android 14+ (API 34+) AudioManager.setPreferredMixerAttributes with
 * AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT when a USB DAC / supported
 * Hi-Res device is connected, and falls back to direct AudioTrack output.
 */
class NativeBitPerfectOutput(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = 0
    private var currentBitDepth = 0
    private var currentChannels = 0
    private var currentDeviceId = -1

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun findBestOutputDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        } ?: devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
    }

    fun configure(sampleRate: Int, bitDepth: Int, channels: Int): NativeOutputResult {
        val targetDevice = findBestOutputDevice()
        val deviceName = targetDevice?.productName?.toString()?.ifBlank { "Salida de Audio" }
            ?: "DAC Interno"

        // Reuse active AudioTrack for gapless playback if format & device are identical
        if (audioTrack != null &&
            currentSampleRate == sampleRate &&
            currentBitDepth == bitDepth &&
            currentChannels == channels &&
            currentDeviceId == (targetDevice?.id ?: -1)
        ) {
            val isUsb = targetDevice?.let {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } == true
            val bitPerfectGranted = isBitPerfectMixerActive(targetDevice)
            return NativeOutputResult(
                configured = true,
                status = if (bitPerfectGranted) BitPerfectStatus.BIT_PERFECT_ANDROID_NATIVE
                else if (isUsb) BitPerfectStatus.DIRECT_HI_RES
                else BitPerfectStatus.ANDROID_MIXER_RESAMPLED,
                hardwareName = deviceName,
                outputRoute = if (bitPerfectGranted) "Android 14+ Bit-Perfect Mixer" else "AudioTrack PCM",
                actualSampleRate = sampleRate,
                actualBitDepth = bitDepth
            )
        }

        release()

        val encoding = when (bitDepth) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            } else {
                AudioFormat.ENCODING_PCM_FLOAT
            }
            32 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioFormat.ENCODING_PCM_32BIT
            } else {
                AudioFormat.ENCODING_PCM_FLOAT
            }
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        var bitPerfectGranted = false
        var routeDesc = "AudioTrack PCM Estándar"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && targetDevice != null) {
            bitPerfectGranted = tryEnableAndroid14BitPerfect(
                device = targetDevice,
                sampleRate = sampleRate,
                encoding = encoding,
                channelMask = channelMask
            )
            if (bitPerfectGranted) {
                routeDesc = "Android 14+ MIXER_BEHAVIOR_BIT_PERFECT"
            }
        }

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .setChannelMask(channelMask)
            .build()

        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            .coerceAtLeast(16384) * 4

        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (targetDevice != null) {
            track.setPreferredDevice(targetDevice)
        }

        track.play()
        audioTrack = track
        currentSampleRate = sampleRate
        currentBitDepth = bitDepth
        currentChannels = channels
        currentDeviceId = targetDevice?.id ?: -1

        val isUsb = targetDevice?.let {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } == true

        val status = when {
            bitPerfectGranted -> BitPerfectStatus.BIT_PERFECT_ANDROID_NATIVE
            isUsb || sampleRate > 48000 -> BitPerfectStatus.DIRECT_HI_RES
            else -> BitPerfectStatus.ANDROID_MIXER_RESAMPLED
        }

        return NativeOutputResult(
            configured = true,
            status = status,
            hardwareName = deviceName,
            outputRoute = routeDesc,
            actualSampleRate = sampleRate,
            actualBitDepth = bitDepth
        )
    }

    private fun tryEnableAndroid14BitPerfect(
        device: AudioDeviceInfo,
        sampleRate: Int,
        encoding: Int,
        channelMask: Int
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return try {
            val supported = audioManager.getSupportedMixerAttributes(device)
            val match = supported.firstOrNull { attrs ->
                attrs.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    attrs.format.sampleRate == sampleRate &&
                    attrs.format.encoding == encoding &&
                    attrs.format.channelMask == channelMask
            } ?: supported.firstOrNull { attrs ->
                attrs.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    attrs.format.sampleRate == sampleRate
            }

            if (match != null) {
                audioManager.setPreferredMixerAttributes(audioAttributes, device, match)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isBitPerfectMixerActive(device: AudioDeviceInfo?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || device == null) {
            return false
        }
        return try {
            val preferred = audioManager.getPreferredMixerAttributes(audioAttributes, device)
            preferred?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        } catch (_: Exception) {
            false
        }
    }

    fun writePcm(data: ByteArray, offset: Int, length: Int): Int {
        val track = audioTrack ?: return 0
        return track.write(data, offset, length, AudioTrack.WRITE_BLOCKING)
    }

    fun pause() {
        audioTrack?.pause()
    }

    fun resume() {
        audioTrack?.play()
    }

    fun flush() {
        audioTrack?.flush()
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            findBestOutputDevice()?.let { dev ->
                try {
                    audioManager.clearPreferredMixerAttributes(audioAttributes, dev)
                } catch (_: Exception) {
                }
            }
        }
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        currentSampleRate = 0
        currentBitDepth = 0
        currentChannels = 0
        currentDeviceId = -1
    }
}
