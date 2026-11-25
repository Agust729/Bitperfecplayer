package com.bitperfect.player.model

enum class OutputMode(val label: String, val description: String) {
    AUTO_HYBRID(
        "Híbrido Automático",
        "Usa DAC USB Directo (C++/JNI) si hay permiso USB, o API Bit-Perfect nativa de Android 14+."
    ),
    ANDROID_NATIVE_BIT_PERFECT(
        "Android 14+ Bit-Perfect",
        "Usa AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT del sistema."
    ),
    DIRECT_USB_DAC(
        "USB DAC Directo (UAC 1/2)",
        "Driver propio en espacio de usuario (usbdevfs) sin pasar por el mezclador de Android."
    )
}

enum class BitPerfectStatus(val badgeText: String, val isBitPerfect: Boolean) {
    BIT_PERFECT_DIRECT_USB("BIT-PERFECT • USB DIRECTO", true),
    BIT_PERFECT_ANDROID_NATIVE("BIT-PERFECT • NATIVO", true),
    DIRECT_HI_RES("HI-RES DIRECTO", false),
    ANDROID_MIXER_RESAMPLED("MEZCLADOR ANDROID", false),
    IDLE("EN ESPERA", false)
}

data class UsbDacInfo(
    val deviceId: Int,
    val productName: String,
    val manufacturerName: String,
    val vendorId: Int,
    val productId: Int,
    val hasPermission: Boolean,
    val isHighSpeed: Boolean
)

data class SignalPathInfo(
    val status: BitPerfectStatus = BitPerfectStatus.IDLE,
    val sourceFormat: String = "—",
    val decoderInfo: String = "Sin DSP / Sin Ecualizador",
    val outputRoute: String = "Ninguna",
    val hardwareName: String = "Altavoz / DAC Interno",
    val activeSampleRate: Int = 0,
    val activeBitDepth: Int = 0,
    val activeChannels: Int = 2,
    val bufferFillPercent: Int = 0,
    val gaplessReady: Boolean = false
)
