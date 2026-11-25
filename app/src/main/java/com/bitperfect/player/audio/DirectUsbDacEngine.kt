package com.bitperfect.player.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import com.bitperfect.player.model.UsbDacInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DirectUsbDacEngine(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.bitperfect.player.USB_PERMISSION"

        init {
            System.loadLibrary("bitperfect_usb")
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var activeDevice: UsbDevice? = null
    private var isOpen = false

    private val _connectedDacs = MutableStateFlow<List<UsbDacInfo>>(emptyList())
    val connectedDacs: StateFlow<List<UsbDacInfo>> = _connectedDacs.asStateFlow()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION,
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshDevices()
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(usbReceiver, filter)
        }
        refreshDevices()
    }

    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        close()
    }

    fun refreshDevices() {
        val list = usbManager.deviceList.values
            .filter { isUsbAudioDevice(it) }
            .map { dev ->
                UsbDacInfo(
                    deviceId = dev.deviceId,
                    productName = dev.productName ?: "USB Audio DAC",
                    manufacturerName = dev.manufacturerName ?: "USB",
                    vendorId = dev.vendorId,
                    productId = dev.productId,
                    hasPermission = usbManager.hasPermission(dev),
                    isHighSpeed = true
                )
            }
        _connectedDacs.value = list
    }

    fun requestPermission(deviceId: Int) {
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, pi)
    }

    fun openFirstPermittedDac(): UsbDevice? {
        val device = usbManager.deviceList.values.firstOrNull {
            isUsbAudioDevice(it) && usbManager.hasPermission(it)
        } ?: return null

        if (isOpen && activeDevice?.deviceId == device.deviceId) {
            return device
        }

        close()
        val conn = usbManager.openDevice(device) ?: return null
        val rawDesc = conn.rawDescriptors ?: run {
            conn.close()
            return null
        }

        val ok = nativeOpen(conn.fileDescriptor, rawDesc, true)
        if (!ok) {
            conn.close()
            return null
        }

        connection = conn
        activeDevice = device
        isOpen = true
        return device
    }

    fun configureAndStart(sampleRate: Int, bitDepth: Int, channels: Int): Boolean {
        if (!isOpen) return false
        if (!nativeConfigure(sampleRate, bitDepth, channels)) return false
        return nativeStart()
    }

    fun writePcmBlocking(pcm: ByteArray, offset: Int, length: Int, isCancelled: () -> Boolean): Int {
        var writtenTotal = 0
        while (writtenTotal < length && !isCancelled()) {
            val wrote = nativeWritePcm(pcm, offset + writtenTotal, length - writtenTotal)
            if (wrote > 0) {
                writtenTotal += wrote
            } else {
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return writtenTotal
    }

    fun getBufferFillPercent(): Int = if (isOpen) nativeGetBufferFillPercent() else 0

    fun getDeviceSummary(): String = if (isOpen) nativeGetDeviceSummary() else "USB DAC no abierto"

    fun stop() {
        if (isOpen) {
            nativeStop()
        }
    }

    fun close() {
        if (isOpen) {
            nativeClose()
            isOpen = false
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
        activeDevice = null
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }

    private external fun nativeOpen(fd: Int, rawDescriptors: ByteArray, isHighSpeed: Boolean): Boolean
    private external fun nativeConfigure(sampleRate: Int, bitDepth: Int, channels: Int): Boolean
    private external fun nativeStart(): Boolean
    private external fun nativeWritePcm(pcmData: ByteArray, offset: Int, length: Int): Int
    private external fun nativeStop()
    private external fun nativeClose()
    private external fun nativeGetBufferFillPercent(): Int
    private external fun nativeGetDeviceSummary(): String
}
