package com.bitperfect.player.model

import android.net.Uri

data class AudioTrackItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val codec: String,
    val albumArtUri: Uri?,
    val fileSizeBytes: Long
) {
    val formatBadge: String
        get() {
            val khz = if (sampleRate % 1000 == 0) {
                "${sampleRate / 1000}"
            } else {
                String.format("%.1f", sampleRate / 1000.0)
            }
            return "$codec • ${bitDepth}-bit / ${khz} kHz"
        }
}
