package com.bitperfect.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitperfect.player.model.SignalPathInfo
import com.bitperfect.player.ui.theme.BitPerfectGold
import com.bitperfect.player.ui.theme.DirectUsbCyan
import com.bitperfect.player.ui.theme.SlateElevated
import com.bitperfect.player.ui.theme.SuccessGreen
import com.bitperfect.player.ui.theme.TextSecondary

@Composable
fun SignalPathDialog(
    signalPath: SignalPathInfo,
    onDismiss: () -> Unit
) {
    val accentColor = when {
        signalPath.status.isBitPerfect -> BitPerfectGold
        else -> DirectUsbCyan
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateElevated,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (signalPath.status.isBitPerfect) Icons.Default.CheckCircle else Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = accentColor
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Ruta de Señal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = signalPath.status.badgeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SignalStageRow("1. Archivo Fuente", signalPath.sourceFormat, accentColor)
                SignalStageRow("2. Decodificador", signalPath.decoderInfo, accentColor)
                SignalStageRow("3. Ruta de Salida", signalPath.outputRoute, accentColor)
                SignalStageRow(
                    "4. Hardware DAC",
                    "${signalPath.hardwareName} (${signalPath.activeBitDepth}-bit / ${signalPath.activeSampleRate} Hz)",
                    if (signalPath.status.isBitPerfect) SuccessGreen else accentColor
                )
                if (signalPath.gaplessReady) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF132A1C))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Gapless activo: la siguiente pista se enlazará sin pausas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SuccessGreen
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", color = accentColor)
            }
        }
    )
}

@Composable
private fun SignalStageRow(title: String, detail: String, dotColor: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
