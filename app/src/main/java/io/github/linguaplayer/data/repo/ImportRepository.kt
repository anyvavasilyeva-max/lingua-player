package io.github.linguaplayer.data.repo

import androidx.room.withTransaction
import io.github.linguaplayer.data.db.AppDatabase
import io.github.linguaplayer.data.db.EntryEntity
import io.github.linguaplayer.data.db.EntryTagCrossRef
import io.github.linguaplayer.importer.ImportOptions
import io.github.linguaplayer.importer.ImportPlan
import io.github.linguaplayer.importer.ImportPlanner
import io.github.linguaplayer.importer.ImportResult
import io.github.linguaplayer.importer.PlannedRow
import io.github.linguaplayer.importer.RowAction
import io.github.linguaplayer.importer.RowError
import io.github.linguaplayer.util.TextKeys
import kotlin.coroutines.cancellation.CancellationException

/**
 * Применяет план импорта к базе. Запись идёт порциями по [BATCH] строк, каждая порция —
 * отдельная транзакция: интерфейс не блокируется, а сбой в одной порции не отменяет остальные
 * (строки неудавшейся порции попадают в ошибки).
 */
class ImportRepository(
    private val db: AppDatabase,
    private val tags: TagRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entryDao = db.entryDao()

    /** Существующие записи пары: нормализованное слово → id первой такой записи. */
    suspend fun existingKeys(pairId: Long): Map<String, Long> {
        val map = HashMap<String, Long>()
        for (k in entryDao.getKeys(pairId)) map.putIfAbsent(k.sourceKey, k.id)
        return map
    }

    suspend fun plan(table: List<List<String>>, options: ImportOptions): ImportPlan =
        ImportPlanner.plan(table, options, existingKeys(options.pairId))

    suspend fun import(
        table: List<List<String>>,
        options: ImportOptions,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult {
        // План строится заново прямо перед записью — на случай изменений после предпросмотра.
        val plan = plan(table, options)
        val errors = plan.rows.filter { it.action is RowAction.Error }.toMutableList()
        val work = plan.rows.filter { it.action is RowAction.New || it.action is RowAction.Update }
        val total = plan.rows.size
        var done = plan.rows.size - work.size
        onProgress(done, total)

        // Все теги файла создаются один раз заранее.
        val tagIds = HashMap<String, Long>()
        db.withTransaction {
            for (name in work.flatMap { it.data!!.tags }) {
                val key = TextKeys.key(name)
                if (key !in tagIds) tags.getOrCreate(name)?.let { tagIds[key] = it.id }
            }
        }

        var imported = 0
        var updated = 0
        for (batch in work.chunked(BATCH)) {
            try {
                val (n, u) = db.withTransaction { writeBatch(batch, options.pairId, tagIds) }
                imported += n
                updated += u
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                batch.forEach { errors += it.copy(action = RowAction.Error(RowError.DATABASE, e.message.orEmpty())) }
            }
            done += batch.size
            onProgress(done, total)
        }
        return ImportResult(
            found = plan.found,
            imported = imported,
            updated = updated,
            skipped = plan.skipCount,
            errors = errors.sortedBy { it.line },
        )
    }

    private suspend fun writeBatch(batch: List<PlannedRow>, pairId: Long, tagIds: Map<String, Long>): Pair<Int, Int> {
        val now = clock()
        val links = ArrayList<EntryTagCrossRef>()
        fun linksFor(entryId: Long, row: PlannedRow) =
            row.data!!.tags.mapNotNullTo(links) { name -> tagIds[TextKeys.key(name)]?.let { EntryTagCrossRef(entryId, it) } }

        val newRows = batch.filter { it.action is RowAction.New }
        val ids = entryDao.insertAll(
            newRows.map { r ->
                val d = r.data!!
                EntryEntity(
                    pairId = pairId,
                    source = d.source,
                    translation = d.translation,
                    type = d.type,
                    sourceKey = TextKeys.key(d.source),
                    translationKey = TextKeys.key(d.translation),
                    createdAt = now,
                    updatedAt = now,
                )
            },
        )
        newRows.forEachIndexed { i, r -> linksFor(ids[i], r) }

        val updates = batch.filter { it.action is RowAction.Update }
        val existing = entryDao.getByIds(updates.map { (it.action as RowAction.Update).entryId }).associateBy { it.id }
        var updated = 0
        for (r in updates) {
            val id = (r.action as RowAction.Update).entryId
            val old = existing[id] ?: continue
            val d = r.data!!
            // Запись обновляется на месте: id не меняется, поэтому связи с плейлистами сохраняются.
            entryDao.update(
                old.copy(
                    translation = d.translation,
                    translationKey = TextKeys.key(d.translation),
                    type = if (d.typeFromFile) d.type else old.type,
                    updatedAt = now,
                ),
            )
            linksFor(id, r) // IGNORE при конфликте → старые теги остаются, новые добавляются
            updated++
        }
        entryDao.insertTagLinks(links)
        return newRows.size to updated
    }

    companion object {
        const val BATCH = 500
    }
}
