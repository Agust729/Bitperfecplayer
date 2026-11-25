package com.bitperfect.player.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitperfect.player.model.OutputMode
import com.bitperfect.player.model.UsbDacInfo
import com.bitperfect.player.ui.theme.BitPerfectGold
import com.bitperfect.player.ui.theme.DirectUsbCyan
import com.bitperfect.player.ui.theme.SlateElevated
import com.bitperfect.player.ui.theme.SlateSurface
import com.bitperfect.player.ui.theme.SuccessGreen
import com.bitperfect.player.ui.theme.TextSecondary

@Composable
fun DacSettingsScreen(
    outputMode: OutputMode,
    connectedDacs: List<UsbDacInfo>,
    onSelectMode: (OutputMode) -> Unit,
    onRequestUsbPermission: (Int) -> Unit,
    onRefreshUsb: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Motor Bit-Perfect & DAC",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // Android 14+ Capability Banner
        val supportsNativeBitPerfect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SlateSurface)
                .padding(14.dp)
        ) {
            Text(
                text = if (supportsNativeBitPerfect) {
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) — API Bit-Perfect Nativa Disponible"
                } else {
                    "Android ${Build.VERSION.RELEASE} — Se recomienda USB DAC Directo para Bit-Perfect"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (supportsNativeBitPerfect) SuccessGreen else BitPerfectGold,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "En modo Bit-Perfect se omiten el mezclador del sistema, el re-muestreo y el ecualizador.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        // Output Mode Selector
        Text(
            text = "Modo de Salida de Audio",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutputMode.entries.forEach { mode ->
                val selected = mode == outputMode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) SlateElevated else SlateSurface)
                        .clickable { onSelectMode(mode) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onSelectMode(mode) },
                        colors = RadioButtonDefaults.colors(selectedColor = BitPerfectGold)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // Connected USB DACs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DACs USB Detectados",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = onRefreshUsb) {
                Icon(Icons.Default.Refresh, contentDescription = "Actualizar USB")
            }
        }

        if (connectedDacs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateSurface)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Usb, contentDescription = null, tint = TextSecondary)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Conecta un DAC USB-C / OTG para activar el driver directo UAC 1.0 / 2.0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        } else {
            connectedDacs.forEach { dac ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SlateSurface)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = dac.productName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${dac.manufacturerName} (VID:${dac.vendorId} PID:${dac.productId})",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Text(
                            text = if (dac.hasPermission) "Permiso USB concedido" else "Requiere permiso USB",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (dac.hasPermission) SuccessGreen else BitPerfectGold
                        )
                    }
                    if (!dac.hasPermission) {
                        Button(
                            onClick = { onRequestUsbPermission(dac.deviceId) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DirectUsbCyan,
                                contentColor = Color.Black
                            )
                        ) {
                            Text("Autorizar")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}
