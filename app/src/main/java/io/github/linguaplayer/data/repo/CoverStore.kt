package io.github.linguaplayer.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Обложки плейлистов. Выбранная картинка (PNG, JPG…) копируется во внутреннюю память
 * приложения и уменьшается до [MAX_SIDE] px: так обложка не пропадёт, если исходный файл
 * удалят, и не займёт много места.
 */
class CoverStore(context: Context) {
    private val dir = File(context.filesDir, "covers")

    /** Копирует изображение и возвращает путь к сохранённому файлу. */
    suspend fun import(context: Context, playlistId: Long, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE && bounds.outHeight / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("cannot decode")
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            decoded.scale((decoded.width * scale).toInt(), (decoded.height * scale).toInt())
        } else decoded
        dir.mkdirs()
        val file = File(dir, "playlist_${playlistId}_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        file.absolutePath
    }

    companion object {
        const val MAX_SIDE = 1024
    }
}
