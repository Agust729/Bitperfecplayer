package com.bitperfect.player.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioSpec(
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val codec: String
)

object AudioHeaderParser {

    fun parse(context: Context, uri: Uri, mimeType: String?): AudioSpec {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(64)
                val read = stream.read(header)
                if (read >= 36 &&
                    header[0] == 'f'.code.toByte() &&
                    header[1] == 'L'.code.toByte() &&
                    header[2] == 'a'.code.toByte() &&
                    header[3] == 'C'.code.toByte()
                ) {
                    parseFlacStreamInfo(header)?.let { return it }
                }

                if (read >= 44 &&
                    header[0] == 'R'.code.toByte() &&
                    header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() &&
                    header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() &&
                    header[9] == 'A'.code.toByte() &&
                    header[10] == 'V'.code.toByte() &&
                    header[11] == 'E'.code.toByte()
                ) {
                    parseWavHeader(header, stream)?.let { return it }
                }
            }
        } catch (_: Exception) {
            // Fall through to MediaExtractor
        }

        return parseWithMediaExtractor(context, uri, mimeType)
    }

    private fun parseFlacStreamInfo(header: ByteArray): AudioSpec? {
        // Metadata block header starts at byte 4; STREAMINFO is block type 0 (34 bytes)
        val blockType = header[4].toInt() and 0x7F
        if (blockType != 0) return null

        // STREAMINFO payload starts at offset 8; bytes 10..13 (offset 18..21) hold SR/Ch/BPS:
        // 20 bits sampleRate | 3 bits (channels - 1) | 5 bits (bps - 1) | 36 bits totalSamples
        val b0 = header[18].toInt() and 0xFF
        val b1 = header[19].toInt() and 0xFF
        val b2 = header[20].toInt() and 0xFF
        val b3 = header[21].toInt() and 0xFF

        val sampleRate = (b0 shl 12) or (b1 shl 4) or (b2 ushr 4)
        val channels = ((b2 ushr 1) and 0x07) + 1
        val bitDepth = (((b2 and 0x01) shl 4) or (b3 ushr 4)) + 1

        if (sampleRate <= 0 || channels <= 0 || bitDepth <= 0) return null
        return AudioSpec(sampleRate, bitDepth, channels, "FLAC")
    }

    private fun parseWavHeader(initialHeader: ByteArray, stream: InputStream): AudioSpec? {
        // Check if "fmt " chunk is directly at offset 12
        val buf = ByteBuffer.wrap(initialHeader).order(ByteOrder.LITTLE_ENDIAN)
        if (initialHeader[12] == 'f'.code.toByte() &&
            initialHeader[13] == 'm'.code.toByte() &&
            initialHeader[14] == 't'.code.toByte() &&
            initialHeader[15] == ' '.code.toByte()
        ) {
            val channels = buf.getShort(22).toInt() and 0xFFFF
            val sampleRate = buf.getInt(24)
            val bitDepth = buf.getShort(34).toInt() and 0xFFFF
            if (sampleRate > 0 && channels > 0 && bitDepth > 0) {
                return AudioSpec(sampleRate, bitDepth, channels, "WAV")
            }
        }
        return null
    }

    private fun parseWithMediaExtractor(context: Context, uri: Uri, mimeType: String?): AudioSpec {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else 44100

                    val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else 2

                    val bitDepth = if (format.containsKey("bits-per-sample")) {
                        format.getInteger("bits-per-sample")
                    } else 16

                    val codecLabel = when {
                        mime.contains("flac", true) -> "FLAC"
                        mime.contains("alac", true) -> "ALAC"
                        mime.contains("opus", true) -> "OPUS"
                        mime.contains("mpeg", true) -> "MP3"
                        mime.contains("mp4a", true) || mime.contains("aac", true) -> "AAC"
                        mime.contains("vorbis", true) -> "OGG"
                        mime.contains("raw", true) -> "WAV"
                        else -> (mimeType?.substringAfter("/") ?: "AUDIO").uppercase()
                    }
                    return AudioSpec(sampleRate, bitDepth, channels, codecLabel)
                }
            }
        } catch (_: Exception) {
            // Fallback defaults
        } finally {
            extractor.release()
        }
        return AudioSpec(44100, 16, 2, "AUDIO")
    }
}
