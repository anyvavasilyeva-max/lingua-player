package io.github.linguaplayer.playback

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Сохранённая очередь: что играло, в каком порядке и на какой записи остановились. */
data class SavedPlayback(
    val title: String,
    val playlistId: Long?,
    /** Записи источника в исходном порядке (для перестроения очереди при смене настроек). */
    val sourceIds: List<Long>,
    /** Очередь: id записи + «сначала изучаемый язык» (сохраняет перемешивание и случайное направление). */
    val order: List<Pair<Long, Boolean>>,
    val index: Int,
)

/**
 * Очередь и позиция хранятся в файле во внутренней памяти (JSON, версия формата 1),
 * чтобы после закрытия приложения или перезагрузки телефона продолжить с того же места.
 * Запись атомарная (через временный файл), поэтому файл не повреждается при сбое.
 */
class PlaybackStateStore(private val file: File) {

    fun save(saved: SavedPlayback?) {
        if (saved == null || saved.order.isEmpty()) {
            file.delete()
            return
        }
        val json = JSONObject()
            .put("version", 1)
            .put("title", saved.title)
            .put("playlistId", saved.playlistId ?: JSONObject.NULL)
            .put("source", JSONArray(saved.sourceIds))
            .put("queue", JSONArray(saved.order.map { it.first }))
            .put("directions", saved.order.joinToString("") { if (it.second) "1" else "0" })
            .put("index", saved.index)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun load(): SavedPlayback? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        if (json.optInt("version") != 1) return null
        val queue = json.getJSONArray("queue")
        val dirs = json.getString("directions")
        val source = json.getJSONArray("source")
        SavedPlayback(
            title = json.getString("title"),
            playlistId = if (json.isNull("playlistId")) null else json.getLong("playlistId"),
            sourceIds = List(source.length()) { source.getLong(it) },
            order = List(queue.length()) { queue.getLong(it) to (dirs.getOrNull(it) != '0') },
            index = json.getInt("index"),
        )
    }.getOrNull()
}
