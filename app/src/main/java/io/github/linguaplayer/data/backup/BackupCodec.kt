package io.github.linguaplayer.data.backup

import io.github.linguaplayer.data.db.TypeFilter
import io.github.linguaplayer.player.Direction
import io.github.linguaplayer.player.PlayOrder
import io.github.linguaplayer.player.PlaybackSettings
import io.github.linguaplayer.player.RepeatMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * Формат резервной копии Lingua Player — см. docs/BACKUP_FORMAT.md.
 * Файл *.lpbackup — ZIP:
 *   backup.json           — данные (format = "lingua-player-backup", version = 1)
 *   covers/<имя>.jpg      — обложки плейлистов
 */

data class BPair(val id: Long, val sourceLang: String, val targetLang: String, val createdAt: Long)
data class BTag(val id: Long, val name: String, val systemKey: String?, val createdAt: Long)
data class BEntry(
    val id: Long, val pairId: Long, val source: String, val translation: String, val type: String,
    val createdAt: Long, val updatedAt: Long, val tagIds: List<Long>,
)
data class BPlaylist(
    val id: Long, val name: String, val kind: String, val pairId: Long?, val smartType: String, val smartTagMode: String,
    val createdAt: Long, val updatedAt: Long, val lastOpenedAt: Long?, val cover: String?,
    val entryIds: List<Long>, val smartTagIds: List<Long>,
)
data class BSettings(
    val activePairId: Long?, val seedColor: Int?, val playlistsGrid: Boolean?, val playback: PlaybackSettings?,
)

data class BackupData(
    val version: Int,
    val createdAt: Long,
    val appVersion: String,
    val pairs: List<BPair>,
    val tags: List<BTag>,
    val entries: List<BEntry>,
    val playlists: List<BPlaylist>,
    val settings: BSettings,
    /** Имя файла обложки в архиве → содержимое. */
    val covers: Map<String, ByteArray> = emptyMap(),
)

class BackupFormatException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { NOT_A_BACKUP, NEWER_VERSION, CORRUPTED }
}

object BackupCodec {
    const val FORMAT = "lingua-player-backup"
    const val VERSION = 1
    const val JSON_NAME = "backup.json"
    const val COVERS_DIR = "covers/"
    private const val MAX_ENTRY_BYTES = 100L * 1024 * 1024

    fun write(data: BackupData, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_NAME))
            zip.write(toJson(data).toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((name, bytes) in data.covers) {
                zip.putNextEntry(ZipEntry(COVERS_DIR + name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /** Читает и полностью проверяет файл. Ничего не меняет — только разбор. */
    fun read(input: InputStream): BackupData {
        var json: String? = null
        val covers = HashMap<String, ByteArray>()
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name
                    val isJson = name == JSON_NAME
                    val isCover = name.startsWith(COVERS_DIR) && !name.contains("..") && name.length > COVERS_DIR.length
                    if (!isJson && !isCover) continue
                    val bytes = readLimited(zip)
                    if (isJson) json = String(bytes, Charsets.UTF_8) else covers[name.removePrefix(COVERS_DIR)] = bytes
                }
            }
        } catch (e: IOException) {
            throw BackupFormatException(BackupFormatException.Reason.NOT_A_BACKUP)
        }
        val text = json ?: throw BackupFormatException(BackupFormatException.Reason.NOT_A_BACKUP)
        return fromJson(text).copy(covers = covers)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            total += r
            if (total > MAX_ENTRY_BYTES) throw BackupFormatException(BackupFormatException.Reason.CORRUPTED)
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    // --- JSON ---

    private fun ids(list: List<Long>) = JSONArray().apply { list.forEach { put(it) } }
    private fun JSONArray?.longs(): List<Long> = if (this == null) emptyList() else List(length()) { getLong(it) }
    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    fun toJson(d: BackupData): JSONObject = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("createdAt", d.createdAt)
        .put("appVersion", d.appVersion)
        .put("languagePairs", JSONArray().apply {
            d.pairs.forEach { put(JSONObject().put("id", it.id).put("sourceLang", it.sourceLang).put("targetLang", it.targetLang).put("createdAt", it.createdAt)) }
        })
        .put("tags", JSONArray().apply {
            d.tags.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("systemKey", it.systemKey ?: JSONObject.NULL).put("createdAt", it.createdAt)) }
        })
        .put("entries", JSONArray().apply {
            d.entries.forEach {
                put(
                    JSONObject().put("id", it.id).put("pairId", it.pairId).put("source", it.source).put("translation", it.translation)
                        .put("type", it.type).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt).put("tagIds", ids(it.tagIds)),
                )
            }
        })
        .put("playlists", JSONArray().apply {
            d.playlists.forEach {
                put(
                    JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind).put("pairId", it.pairId ?: JSONObject.NULL)
                        .put("smartType", it.smartType).put("smartTagMode", it.smartTagMode)
                        .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt).put("lastOpenedAt", it.lastOpenedAt ?: JSONObject.NULL)
                        .put("cover", it.cover ?: JSONObject.NULL).put("entryIds", ids(it.entryIds)).put("smartTagIds", ids(it.smartTagIds)),
                )
            }
        })
        .put("settings", settingsJson(d.settings))

    private fun settingsJson(s: BSettings) = JSONObject().apply {
        put("activePairId", s.activePairId ?: JSONObject.NULL)
        put("seedColor", s.seedColor?.let { String.format("#%08X", it) } ?: JSONObject.NULL)
        put("playlistsGrid", s.playlistsGrid ?: JSONObject.NULL)
        s.playback?.let { p ->
            put(
                "playback",
                JSONObject().put("direction", p.direction.code).put("typeFilter", p.typeFilter.code).put("order", p.order.code)
                    .put("pauseSeconds", p.pauseSeconds).put("repeats", p.repeats).put("sourceRate", p.sourceRate.toDouble())
                    .put("targetRate", p.targetRate.toDouble()).put("translationEnabled", p.translationEnabled)
                    .put("repeatMode", p.repeatMode.code).put("slowRepeat", p.slowRepeat).put("hideAnswer", p.hideAnswer),
            )
        }
    }

    fun fromJson(text: String): BackupData {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BackupFormatException(BackupFormatException.Reason.CORRUPTED)
        }
        if (root.optString("format") != FORMAT) throw BackupFormatException(BackupFormatException.Reason.NOT_A_BACKUP)
        val version = root.optInt("version", -1)
        if (version > VERSION) throw BackupFormatException(BackupFormatException.Reason.NEWER_VERSION)
        if (version < 1) throw BackupFormatException(BackupFormatException.Reason.CORRUPTED)
        try {
            val pairs = root.getJSONArray("languagePairs").let { a ->
                List(a.length()) { a.getJSONObject(it).run { BPair(getLong("id"), getString("sourceLang"), getString("targetLang"), optLong("createdAt")) } }
            }
            val tags = root.getJSONArray("tags").let { a ->
                List(a.length()) { a.getJSONObject(it).run { BTag(getLong("id"), getString("name"), optStringOrNull("systemKey"), optLong("createdAt")) } }
            }
            val entries = root.getJSONArray("entries").let { a ->
                List(a.length()) {
                    a.getJSONObject(it).run {
                        BEntry(
                            getLong("id"), getLong("pairId"), getString("source"), getString("translation"), optString("type", "word"),
                            optLong("createdAt"), optLong("updatedAt"), optJSONArray("tagIds").longs(),
                        )
                    }
                }
            }
            val playlists = root.getJSONArray("playlists").let { a ->
                List(a.length()) {
                    a.getJSONObject(it).run {
                        BPlaylist(
                            getLong("id"), getString("name"), optString("kind", "normal"), optLongOrNull("pairId"),
                            optString("smartType", "all"), optString("smartTagMode", "any"),
                            optLong("createdAt"), optLong("updatedAt"), optLongOrNull("lastOpenedAt"), optStringOrNull("cover"),
                            optJSONArray("entryIds").longs(), optJSONArray("smartTagIds").longs(),
                        )
                    }
                }
            }
            val s = root.optJSONObject("settings") ?: JSONObject()
            val playback = s.optJSONObject("playback")?.run {
                PlaybackSettings(
                    direction = Direction.fromCode(optString("direction")),
                    typeFilter = TypeFilter.fromCode(optString("typeFilter")),
                    order = PlayOrder.fromCode(optString("order")),
                    pauseSeconds = optInt("pauseSeconds", 2).takeIf { it in PlaybackSettings.PAUSES } ?: 2,
                    repeats = optInt("repeats", 1).takeIf { it in PlaybackSettings.REPEATS } ?: 1,
                    sourceRate = optDouble("sourceRate", 1.0).toFloat().takeIf { it in PlaybackSettings.RATES } ?: 1f,
                    targetRate = optDouble("targetRate", 1.0).toFloat().takeIf { it in PlaybackSettings.RATES } ?: 1f,
                    translationEnabled = optBoolean("translationEnabled", true),
                    repeatMode = RepeatMode.fromCode(optString("repeatMode")),
                    slowRepeat = optBoolean("slowRepeat", false),
                    hideAnswer = optBoolean("hideAnswer", false),
                )
            }
            val settings = BSettings(
                activePairId = s.optLongOrNull("activePairId"),
                seedColor = s.optStringOrNull("seedColor")?.removePrefix("#")?.toLongOrNull(16)?.toInt(),
                playlistsGrid = if (s.has("playlistsGrid") && !s.isNull("playlistsGrid")) s.getBoolean("playlistsGrid") else null,
                playback = playback,
            )
            return BackupData(version, root.optLong("createdAt"), root.optString("appVersion"), pairs, tags, entries, playlists, settings)
        } catch (e: BackupFormatException) {
            throw e
        } catch (e: Exception) {
            throw BackupFormatException(BackupFormatException.Reason.CORRUPTED)
        }
    }
}
