package io.github.linguaplayer.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.linguaplayer.data.db.PlaylistEntity
import io.github.linguaplayer.data.db.PlaylistKind
import io.github.linguaplayer.ui.theme.LocalBrandColor
import io.github.linguaplayer.ui.theme.SeedScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Загружает файл обложки в фоне. null — нет файла или не удалось прочитать. */
@Composable
private fun rememberCover(path: String?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    return bitmap
}

/**
 * Обложка плейлиста: картинка пользователя или (по умолчанию) крупное название на фоне
 * выбранного цвета оформления.
 */
@Composable
fun PlaylistCover(
    playlist: PlaylistEntity,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    fontSize: Int = 20,
) {
    val image = rememberCover(playlist.coverPath)
    val brand = LocalBrandColor.current
    val onBrand = SeedScheme.onCover(brand)
    // Длинное слово не должно рваться посередине: уменьшаем шрифт под самое длинное слово.
    val longest = playlist.name.split(' ', '-').maxOfOrNull { it.length } ?: 0
    val size = if (longest > 9) (fontSize * 9f / longest).coerceAtLeast(11f) else fontSize.toFloat()
    Box(modifier.clip(RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // Затемнение, чтобы название читалось на любой картинке.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(0.15f), Color.Black.copy(0.45f)))))
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(brand, lerp(brand, Color.Black, 0.18f)))))
        }
        Text(
            playlist.name,
            color = if (image != null) Color.White else onBrand,
            style = TextStyle(fontSize = size.sp, fontWeight = FontWeight.Black, letterSpacing = 0.2.sp, lineHeight = (size * 1.15f).sp),
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp),
        )
        if (playlist.kind == PlaylistKind.SMART) {
            Icon(
                Icons.Outlined.AutoAwesome, null,
                tint = if (image != null) Color.White else onBrand,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(18.dp),
            )
        }
    }
}

/** Проверка, что файл обложки существует (для пункта «Убрать обложку»). */
fun hasCoverFile(path: String?) = path != null && File(path).exists()
