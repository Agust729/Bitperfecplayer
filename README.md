# BitPerfect Player (Android)

Reproductor de música Hi-Res para Android con arquitectura híbrida **Bit-Perfect** (API nativa de Android 14+ y driver USB DAC directo en C++/JNI).

## Características Principales

- **Modo Híbrido Bit-Perfect**:
  - **Driver USB DAC Directo (C++ / `usbdevfs`)**: Implementación en espacio de usuario para USB Audio Class 1.0 y 2.0 (`app/src/main/cpp/usb_audio_driver.cpp`) que toma control exclusivo del DAC USB mediante `UsbDeviceConnection.getFileDescriptor()`, configura el *Clock Source* / frecuencia exacta y transmite paquetes isócronos desde un *Ring Buffer* lock-free sin pasar por el mezclador de Android (`AudioFlinger`).
  - **API Nativa Android 14+ (`MIXER_BEHAVIOR_BIT_PERFECT`)**: En Android 14/15/16+ (`API 34+`), consulta `AudioManager.getSupportedMixerAttributes()` y activa `setPreferredMixerAttributes()` con `MIXER_BEHAVIOR_BIT_PERFECT` cuando hay un DAC USB compatible.
- **Reproducción Gapless (Sin Pausas)**: Enlaza automáticamente la siguiente pista en la cola manteniendo el flujo PCM continuo si comparte frecuencia de muestreo y profundidad de bits, o reconfigura el DAC limpiamente si cambia el formato.
- **Lector de Cabeceras Reales (`AudioHeaderParser`)**: Extrae la profundidad de bits (`16-bit`, `24-bit`, `32-bit`) y frecuencia (`44.1 kHz` hasta `384 kHz`) directamente de bloques `STREAMINFO` (FLAC) y `fmt ` (WAV), además de soportar ALAC, MP3, Opus y AAC vía `MediaCodec`.
- **Ruta de Señal en Tiempo Real**: Muestra en la pantalla de reproducción el estado exacto (`BIT-PERFECT • USB DIRECTO`, `BIT-PERFECT • NATIVO`, `HI-RES DIRECTO` o `MEZCLADOR ANDROID`) y el desglose de cada etapa.

## Cómo compilar el APK

1. **Android Studio**:
   - Abre la carpeta `BitPerfectPlayer` en Android Studio (Ladybug / Meerkat o superior).
   - Asegúrate de tener instalado el **NDK** y **CMake 3.22.1** desde *SDK Manager > SDK Tools*.
   - Ejecuta **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

2. **GitHub Actions (automático)**:
   - Sube este proyecto a un repositorio de GitHub; el workflow `.github/workflows/build.yml` compilará automáticamente `app-debug.apk` y lo dejará disponible en *Actions > Artifacts*.
