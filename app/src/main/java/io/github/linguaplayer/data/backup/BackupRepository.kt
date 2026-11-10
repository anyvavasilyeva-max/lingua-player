package io.github.linguaplayer.data.backup

import androidx.room.withTransaction
import io.github.linguaplayer.data.db.AppDatabase
import io.github.linguaplayer.data.db.EntryEntity
import io.github.linguaplayer.data.db.EntryTagCrossRef
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.db.LanguagePairEntity
import io.github.linguaplayer.data.db.PlaylistEntity
import io.github.linguaplayer.data.db.PlaylistEntryCrossRef
import io.github.linguaplayer.data.db.PlaylistKind
import io.github.linguaplayer.data.db.SmartPlaylistTagCrossRef
import io.github.linguaplayer.data.db.TagEntity
import io.github.linguaplayer.data.db.TagMatchMode
import io.github.linguaplayer.data.db.TypeFilter
import io.github.linguaplayer.data.repo.SettingsRepository
import io.github.linguaplayer.util.TextKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

data class RestoreResult(val pairs: Int, val entries: Int, val tags: Int, val playlists: Int)

/**
 * Полная резервная копия и восстановление.
 *
 * Восстановление заменяет все данные. Порядок действий защищает от потери данных:
 * 1) файл целиком читается и проверяется (формат, версия) — до любых изменений;
 * 2) обложки распаковываются в новые файлы;
 * 3) замена данных — одна транзакция Room: при любой ошибке всё откатывается,
 *    новые файлы обложек удаляются, текущие данные остаются как были;
 * 4) только после успешной транзакции удаляются старые обложки и применяются настройки.
 */
class BackupRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val coversDir: File,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.backupDao()

    suspend fun export(out: OutputStream) {
        val data = db.withTransaction {
            val tagsByEntry = dao.entryTags().groupBy({ it.entryId }, { it.tagId })
            val entriesByPlaylist = dao.playlistEntries().groupBy({ it.playlistId }, { it.entryId })
            val smartByPlaylist = dao.smartTags().groupBy({ it.playlistId }, { it.tagId })
            val covers = HashMap<String, ByteArray>()
            val playlists = dao.playlists().map { p ->
                val coverName = p.coverPath?.let { path ->
                    val f = File(path)
                    if (f.exists()) "playlist_${p.id}.jpg".also { covers[it] = f.readBytes() } else null
                }
                BPlaylist(
                    p.id, p.name, p.kind.code, p.pairId, p.smartType.code, p.smartTagMode.code,
                    p.createdAt, p.updatedAt, p.lastOpenedAt, coverName,
                    entriesByPlaylist[p.id].orEmpty(), smartByPlaylist[p.id].orEmpty(),
                )
            }
            BackupData(
                version = BackupCodec.VERSION,
                createdAt = clock(),
                appVersion = appVersion,
                pairs = dao.pairs().map { BPair(it.id, it.sourceLang, it.targetLang, it.createdAt) },
                tags = dao.tags().map { BTag(it.id, it.name, it.systemKey, it.createdAt) },
                entries = dao.entries().map {
                    BEntry(it.id, it.pairId, it.source, it.translation, it.type.code, it.createdAt, it.updatedAt, tagsByEntry[it.id].orEmpty())
                },
                playlists = playlists,
                settings = BSettings(
                    activePairId = settings.activePairId.first(),
                    seedColor = settings.seedColor.first(),
                    playlistsGrid = settings.playlistsGrid.first(),
                    playback = settings.playback.first(),
                ),
                covers = covers,
            )
        }
        withContext(Dispatchers.IO) { BackupCodec.write(data, out) }
    }

    /** Читает файл без изменений в БД — для подтверждения («в копии N записей…»). */
    suspend fun inspect(input: InputStream): BackupData = withContext(Dispatchers.IO) { BackupCodec.read(input) }

    suspend fun restore(data: BackupData): RestoreResult {
        val now = clock()
        val pairIds = data.pairs.map { it.id }.toSet()
        val tagIds = data.tags.map { it.id }.toSet()
        val entries = data.entries.filter { it.pairId in pairIds }
        val entryIds = entries.map { it.id }.toSet()
        val entryPair = entries.associate { it.id to it.pairId }

        // Обложки — в новые файлы (старые удалим только после успешной транзакции).
        val oldCovers = dao.playlists().mapNotNull { it.coverPath }
        val newCovers = HashMap<Long, String>()
        withContext(Dispatchers.IO) {
            coversDir.mkdirs()
            for (p in data.playlists) {
                val bytes = p.cover?.let { data.covers[it] } ?: continue
                val f = File(coversDir, "restored_${p.id}_$now.jpg")
                f.writeBytes(bytes)
                newCovers[p.id] = f.absolutePath
            }
        }

        try {
            db.withTransaction {
                dao.clearSmartTags(); dao.clearPlaylistEntries(); dao.clearEntryTags()
                dao.clearPlaylists(); dao.clearEntries(); dao.clearTags(); dao.clearPairs()

                dao.insertPairs(data.pairs.map { LanguagePairEntity(it.id, it.sourceLang, it.targetLang, it.createdAt) })
                // Имена тегов уникальны без учёта регистра — повторы в повреждённом файле пропускаются.
                val seenTagKeys = HashSet<String>()
                val tags = data.tags.filter { seenTagKeys.add(TextKeys.key(it.name)) }
                dao.insertTags(tags.map { TagEntity(it.id, TextKeys.clean(it.name), TextKeys.key(it.name), it.systemKey, it.createdAt) })
                val keptTags = tags.map { it.id }.toSet()
                for (chunk in entries.chunked(500)) {
                    dao.insertEntries(
                        chunk.map {
                            EntryEntity(
                                it.id, it.pairId, it.source, it.translation, EntryType.fromCode(it.type) ?: EntryType.WORD,
                                TextKeys.key(it.source), TextKeys.key(it.translation), it.createdAt, it.updatedAt,
                            )
                        },
                    )
                }
                dao.insertEntryTags(entries.flatMap { e -> e.tagIds.filter { it in keptTags }.map { EntryTagCrossRef(e.id, it) } })
                val playlists = data.playlists.map { p ->
                    val pairId = p.pairId?.takeIf { it in pairIds } ?: data.pairs.firstOrNull()?.id
                    PlaylistEntity(
                        p.id, p.name, PlaylistKind.fromCode(p.kind), pairId, TypeFilter.fromCode(p.smartType),
                        TagMatchMode.fromCode(p.smartTagMode), p.createdAt, p.updatedAt, p.lastOpenedAt, newCovers[p.id],
                    )
                }
                dao.insertPlaylists(playlists)
                val pairOfPlaylist = playlists.associate { it.id to it.pairId }
                dao.insertPlaylistEntries(
                    data.playlists.flatMap { p ->
                        p.entryIds.filter { it in entryIds && entryPair[it] == pairOfPlaylist[p.id] }.distinct()
                            .mapIndexed { pos, id -> PlaylistEntryCrossRef(p.id, id, pos) }
                    },
                )
                dao.insertSmartTags(data.playlists.flatMap { p -> p.smartTagIds.filter { it in keptTags }.map { SmartPlaylistTagCrossRef(p.id, it) } })

                // Системный тег «Не помню» должен существовать всегда.
                if (tags.none { it.systemKey == TagEntity.SYSTEM_DONT_REMEMBER }) {
                    val name = "Не помню"
                    val key = TextKeys.key(name)
                    val finalName = if (tags.any { TextKeys.key(it.name) == key }) "$name (системный)" else name
                    db.tagDao().insert(TagEntity(name = finalName, nameKey = TextKeys.key(finalName), systemKey = TagEntity.SYSTEM_DONT_REMEMBER, createdAt = now))
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { newCovers.values.forEach { File(it).delete() } }
            throw e
        }

        withContext(Dispatchers.IO) { oldCovers.forEach { File(it).delete() } }
        data.settings.playback?.let { p -> settings.updatePlayback { p } }
        data.settings.seedColor?.let { settings.setSeedColor(it) }
        data.settings.playlistsGrid?.let { settings.setPlaylistsGrid(it) }
        (data.settings.activePairId?.takeIf { it in pairIds } ?: data.pairs.firstOrNull()?.id)?.let { settings.setActivePair(it) }
        return RestoreResult(data.pairs.size, entries.size, data.tags.size, data.playlists.size)
    }
}
