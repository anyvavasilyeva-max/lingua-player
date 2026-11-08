package io.github.linguaplayer.ui.importer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.linguaplayer.AppContainer
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.db.LanguagePairEntity
import io.github.linguaplayer.importer.ColumnGuesser
import io.github.linguaplayer.importer.ColumnMapping
import io.github.linguaplayer.importer.DuplicatePolicy
import io.github.linguaplayer.importer.FileLoader
import io.github.linguaplayer.importer.ImportFileException
import io.github.linguaplayer.importer.ImportOptions
import io.github.linguaplayer.importer.ImportPlan
import io.github.linguaplayer.importer.ImportResult
import io.github.linguaplayer.importer.LoadedFile
import io.github.linguaplayer.importer.SourceTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportStep { PICK, CONFIGURE, PREVIEW, RUNNING, DONE }

data class ImportUiState(
    val step: ImportStep = ImportStep.PICK,
    val loading: Boolean = false,
    val fileError: ImportFileException.Reason? = null,
    val file: LoadedFile? = null,
    val sheetIndex: Int = 0,
    val pairs: List<LanguagePairEntity> = emptyList(),
    val pairId: Long? = null,
    val hasHeader: Boolean = true,
    val mapping: ColumnMapping = ColumnMapping(0, 1),
    val defaultType: EntryType? = null,
    val policy: DuplicatePolicy = DuplicatePolicy.SKIP,
    val plan: ImportPlan? = null,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val result: ImportResult? = null,
    val failure: String? = null,
) {
    val table: SourceTable? get() = file?.tables?.getOrNull(sheetIndex)
    val sameColumn: Boolean get() = mapping.source == mapping.translation
    val typeMissing: Boolean get() = mapping.type == null && defaultType == null
    val canPreview: Boolean
        get() = table != null && table!!.rows.isNotEmpty() && pairId != null && !sameColumn && !typeMissing

    fun options() = ImportOptions(pairId ?: -1, mapping, defaultType, policy, hasHeader)
}

class ImportViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(ImportUiState())

    init {
        viewModelScope.launch {
            val pairs = c.languagePairs.getAll()
            val active = c.activePair.first()
            state.update { it.copy(pairs = pairs, pairId = active?.id ?: pairs.firstOrNull()?.id) }
        }
    }

    fun onFilePicked(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        state.update { it.copy(loading = true, fileError = null) }
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val (name, bytes) = FileLoader.readUri(appContext, uri)
                    FileLoader.load(name, bytes)
                }
                val sheet = file.tables.indexOfFirst { it.rows.isNotEmpty() }.coerceAtLeast(0)
                state.update {
                    it.copy(loading = false, file = file, sheetIndex = sheet, step = ImportStep.CONFIGURE)
                        .withGuessedMapping()
                }
            } catch (e: ImportFileException) {
                state.update { it.copy(loading = false, fileError = e.reason) }
            } catch (e: Exception) {
                state.update { it.copy(loading = false, fileError = ImportFileException.Reason.UNREADABLE) }
            }
        }
    }

    private fun ImportUiState.withGuessedMapping(): ImportUiState {
        val header = table?.rows?.firstOrNull().orEmpty()
        val m = if (hasHeader) ColumnGuesser.guess(header) else ColumnMapping(0, 1)
        return copy(mapping = m, defaultType = if (m.type == null) null else defaultType)
    }

    fun selectSheet(i: Int) = state.update { it.copy(sheetIndex = i).withGuessedMapping() }

    fun setDelimiter(d: Char?) = state.update { s ->
        val f = s.file ?: return@update s
        val text = f.csvText ?: return@update s
        s.copy(file = FileLoader.csv(f.name, text, d, f.encoding, f.notUtf8)).withGuessedMapping()
    }

    fun setHasHeader(v: Boolean) = state.update { it.copy(hasHeader = v).withGuessedMapping() }
    fun setPair(id: Long) = state.update { it.copy(pairId = id) }
    fun setMapping(m: ColumnMapping) = state.update { it.copy(mapping = m) }
    fun setDefaultType(t: EntryType?) = state.update { it.copy(defaultType = t) }
    fun setPolicy(p: DuplicatePolicy) = state.update { it.copy(policy = p) }

    fun preview() {
        val s = state.value
        val table = s.table ?: return
        if (!s.canPreview) return
        state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val plan = withContext(Dispatchers.Default) { c.importer.plan(table.rows, s.options()) }
            state.update { it.copy(loading = false, plan = plan, step = ImportStep.PREVIEW) }
        }
    }

    fun backToConfigure() = state.update { it.copy(step = ImportStep.CONFIGURE, plan = null) }

    fun startImport() {
        val s = state.value
        val table = s.table ?: return
        state.update { it.copy(step = ImportStep.RUNNING, progressDone = 0, progressTotal = s.plan?.found ?: 0) }
        // Импорт идёт в области приложения: он не прервётся, если экран будет закрыт.
        c.appScope.launch {
            try {
                val result = c.importer.import(table.rows, s.options()) { done, total ->
                    state.update { it.copy(progressDone = done, progressTotal = total) }
                }
                state.update { it.copy(step = ImportStep.DONE, result = result) }
            } catch (e: Exception) {
                state.update { it.copy(step = ImportStep.DONE, failure = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun reset() = state.update {
        ImportUiState(pairs = it.pairs, pairId = it.pairId)
    }
}
