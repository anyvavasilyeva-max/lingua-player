package io.github.linguaplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.linguaplayer.R
import io.github.linguaplayer.ui.theme.SeedScheme
import java.util.Locale

/** "#00B2D1" ↔ ARGB. */
object HexColor {
    fun format(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    fun parse(text: String): Int? {
        val t = text.trim().removePrefix("#")
        if (t.length != 6 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return (0xFF000000.toInt()) or t.toInt(16)
    }
}

private fun hsvToColor(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

private fun colorToHsv(argb: Int): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }

/**
 * Выбор любого цвета: квадрат «насыщенность × яркость», полоса оттенка (спектр) и ввод HEX.
 */
@Composable
fun ColorPickerDialog(
    initial: Int,
    defaultColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val start = remember { colorToHsv(initial) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val color = hsvToColor(hue, sat, value)
    var hexText by remember { mutableStateOf(HexColor.format(initial)) }

    fun setFrom(argb: Int, updateText: Boolean = true) {
        val hsv = colorToHsv(argb)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
        if (updateText) hexText = HexColor.format(argb)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Насыщенность (→) и яркость (↓) для выбранного оттенка.
                Canvas(
                    Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(12.dp))
                        .semantics { contentDescription = "saturation-value" }
                        .pointerInput(Unit) {
                            fun upd(o: Offset) {
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                                hexText = HexColor.format(hsvToColor(hue, sat, value).toArgb())
                            }
                            detectTapGestures { upd(it) }
                        }
                        .pointerInput(Unit) {
                            fun upd(o: Offset) {
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                                hexText = HexColor.format(hsvToColor(hue, sat, value).toArgb())
                            }
                            detectDragGestures(onDragStart = { upd(it) }) { change, _ -> upd(change.position) }
                        },
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hsvToColor(hue, 1f, 1f))))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val c = Offset(sat * size.width, (1f - value) * size.height)
                    drawCircle(Color.White, radius = 11.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.Black.copy(alpha = 0.4f), radius = 13.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
                }
                // Спектр оттенков.
                val hues = remember { (0..6).map { hsvToColor(it * 60f, 1f, 1f) } }
                Canvas(
                    Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp))
                        .semantics { contentDescription = "hue" }
                        .pointerInput(Unit) {
                            detectTapGestures {
                                hue = (it.x / size.width).coerceIn(0f, 1f) * 360f
                                hexText = HexColor.format(hsvToColor(hue, sat, value).toArgb())
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(onDragStart = {
                                hue = (it.x / size.width).coerceIn(0f, 1f) * 360f
                            }) { change, _ ->
                                hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                                hexText = HexColor.format(hsvToColor(hue, sat, value).toArgb())
                            }
                        },
                ) {
                    drawRect(Brush.horizontalGradient(hues))
                    val x = hue / 360f * size.width
                    drawCircle(Color.White, radius = size.height / 2 - 2.dp.toPx(), center = Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Предпросмотр: обложка плейлиста и кнопка в этом цвете.
                    Box(
                        Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(color),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Aa", color = SeedScheme.onCover(color), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                    }
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { t ->
                            hexText = t
                            HexColor.parse(t)?.let { setFrom(it, updateText = false) }
                        },
                        singleLine = true,
                        isError = HexColor.parse(hexText) == null,
                        label = { Text("HEX") },
                        modifier = Modifier.padding(start = 12.dp).weight(1f),
                    )
                }
                TextButton(onClick = { setFrom(defaultColor) }) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(Color(defaultColor)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    Text(stringResource(R.string.theme_reset, HexColor.format(defaultColor)), Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(color.toArgb()) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
