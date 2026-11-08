package io.github.linguaplayer.ui.importer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linguaplayer.R
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.repo.displayName
import io.github.linguaplayer.importer.DuplicatePolicy
import io.github.linguaplayer.importer.ImportFileException
import io.github.linguaplayer.importer.LoadedFile
import io.github.linguaplayer.importer.PlannedRow
import io.github.linguaplayer.importer.RowAction
import io.github.linguaplayer.importer.RowError
import io.github.linguaplayer.importer.SkipReason
import io.github.linguaplayer.ui.components.DropdownField
import io.github.linguaplayer.ui.components.appContainer

/** Типы файлов для системного диалога. Некоторые файловые менеджеры отдают CSV как text/plain. */
private val MIME_TYPES = arrayOf(
    "text/csv",
    "text/comma-separated-values",
    "text/plain",
    "text/tab-separated-values",
    "application/csv",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "application/octet-stream",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit, onFinished: () -> Unit) {
    val c = appContainer()
    val vm: ImportViewModel = viewModel { ImportViewModel(c) }
    val s by vm.state.collectAsStateWithLifecycle()
    val running = s.step == ImportStep.RUNNING

    // Во время импорта «Назад» не закрывает экран, чтобы не потерять отчёт.
    BackHandler(enabled = running) {}
    BackHandler(enabled = s.step == ImportStep.PREVIEW) { vm.backToConfigure() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !running) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val mod = Modifier.padding(padding).fillMaxSize()
        when (s.step) {
            ImportStep.PICK -> PickStep(s, vm, mod)
            ImportStep.CONFIGURE -> ConfigureStep(s, vm, mod)
            ImportStep.PREVIEW -> PreviewStep(s, vm, mod)
            ImportStep.RUNNING -> RunningStep(s, mod)
            ImportStep.DONE -> DoneStep(s, vm, onFinished, mod)
        }
    }
}

@Composable
private fun PickStep(s: ImportUiState, vm: ImportViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onFilePicked(context, uri)
    }
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.FileOpen, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.import_pick_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.import_pick_text),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (s.loading) {
            CircularProgressIndicator()
            Text(stringResource(R.string.import_loading), Modifier.padding(top = 8.dp))
        } else {
            Button(onClick = { launcher.launch(MIME_TYPES) }) { Text(stringResource(R.string.import_pick_button)) }
        }
        s.fileError?.let {
            Spacer(Modifier.height(16.dp))
            Text(fileErrorText(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun fileErrorText(r: ImportFileException.Reason) = stringResource(
    when (r) {
        ImportFileException.Reason.TOO_LARGE -> R.string.import_err_too_large
        ImportFileException.Reason.OLD_XLS -> R.string.import_err_old_xls
        ImportFileException.Reason.UNREADABLE -> R.string.import_err_unreadable
        ImportFileException.Reason.EMPTY -> R.string.import_err_empty
    },
)

/** A, B, …, Z, AA, AB… */
private fun columnLetter(i: Int): String {
    var n = i + 1
    val sb = StringBuilder()
    while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
    return sb.reverse().toString()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigureStep(s: ImportUiState, vm: ImportViewModel, modifier: Modifier) {
    val file = s.file ?: return
    val table = s.table
    val rows = table?.rows.orEmpty()
    val columnCount = maxOf(table?.columnCount ?: 0, 2)
    val header = rows.firstOrNull().orEmpty()
    val sample = rows.getOrNull(if (s.hasHeader) 1 else 0).orEmpty()
    val colNames = (0 until columnCount).map { i ->
        val title = if (s.hasHeader) header.getOrNull(i)?.takeIf { it.isNotBlank() } else null
        val example = sample.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { " · «${it.take(20)}»" }.orEmpty()
        stringResource(R.string.import_column, columnLetter(i)) + (title?.let { " — $it" } ?: example)
    }
    val none = stringResource(R.string.import_column_none)

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.import_file, file.name), style = MaterialTheme.typography.titleMedium)
            if (file.notUtf8) Warning(stringResource(R.string.import_warn_encoding))
            if (file.unterminatedQuote) Warning(stringResource(R.string.import_warn_quote))
            if (rows.isEmpty()) Warning(stringResource(R.string.import_err_no_rows))
        }
        if (file.kind == LoadedFile.Kind.XLSX && file.tables.size > 1) {
            item {
                DropdownField(
                    label = stringResource(R.string.import_sheet),
                    options = file.tables.map { stringResource(R.string.import_sheet_rows, it.name, it.rows.count { r -> r.any(String::isNotBlank) }) },
                    selectedIndex = s.sheetIndex,
                    onSelect = vm::selectSheet,
                )
            }
        }
        if (file.kind == LoadedFile.Kind.CSV) {
            item {
                Text(stringResource(R.string.import_delimiter), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(',' to R.string.import_delim_comma, ';' to R.string.import_delim_semicolon, '\t' to R.string.import_delim_tab)
                        .forEach { (ch, label) ->
                            FilterChip(file.csvDelimiter == ch, { vm.setDelimiter(ch) }, { Text(stringResource(label)) })
                        }
                }
            }
        }
        item {
            val pairIndex = s.pairs.indexOfFirst { it.id == s.pairId }
            DropdownField(
                label = stringResource(R.string.import_pair),
                options = s.pairs.map { it.displayName() },
                selectedIndex = pairIndex,
                onSelect = { vm.setPair(s.pairs[it].id) },
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().selectable(s.hasHeader, role = Role.Switch) { vm.setHasHeader(!s.hasHeader) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.import_has_header), Modifier.weight(1f))
                Switch(checked = s.hasHeader, onCheckedChange = null)
            }
        }
        item {
            Text(stringResource(R.string.import_mapping), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val m = s.mapping
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField(stringResource(R.string.import_field_source), colNames, m.source, { vm.setMapping(m.copy(source = it)) }, isError = s.sameColumn)
                DropdownField(stringResource(R.string.import_field_translation), colNames, m.translation, { vm.setMapping(m.copy(translation = it)) }, isError = s.sameColumn)
                if (s.sameColumn) Text(stringResource(R.string.import_same_column), color = MaterialTheme.colorScheme.error)
                DropdownField(
                    stringResource(R.string.import_field_type), listOf(none) + colNames, (m.type ?: -1) + 1,
                    { vm.setMapping(m.copy(type = if (it == 0) null else it - 1)) },
                )
                DropdownField(
                    stringResource(R.string.import_field_tags), listOf(none) + colNames, (m.tags ?: -1) + 1,
                    { vm.setMapping(m.copy(tags = if (it == 0) null else it - 1)) },
                )
            }
        }
        item {
            val noColumn = s.mapping.type == null
            Text(
                stringResource(if (noColumn) R.string.import_type_all else R.string.import_type_empty),
                style = MaterialTheme.typography.titleSmall,
            )
            if (s.typeMissing) Text(stringResource(R.string.import_type_required), color = MaterialTheme.colorScheme.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.defaultType == EntryType.WORD, { vm.setDefaultType(EntryType.WORD) }, { Text(stringResource(R.string.type_word)) })
                FilterChip(s.defaultType == EntryType.PHRASE, { vm.setDefaultType(EntryType.PHRASE) }, { Text(stringResource(R.string.type_phrase)) })
                if (!noColumn) {
                    FilterChip(s.defaultType == null, { vm.setDefaultType(null) }, { Text(stringResource(R.string.import_type_unset)) })
                }
            }
        }
        item {
            Text(stringResource(R.string.import_duplicates), style = MaterialTheme.typography.titleSmall)
            listOf(
                Triple(DuplicatePolicy.SKIP, R.string.import_dup_skip, R.string.import_dup_skip_desc),
                Triple(DuplicatePolicy.UPDATE, R.string.import_dup_update, R.string.import_dup_update_desc),
                Triple(DuplicatePolicy.IMPORT_AS_NEW, R.string.import_dup_new, R.string.import_dup_new_desc),
            ).forEach { (policy, title, desc) ->
                Row(
                    Modifier.fillMaxWidth().selectable(s.policy == policy, role = Role.RadioButton) { vm.setPolicy(policy) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.policy == policy, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(stringResource(title))
                        Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Button(onClick = vm::preview, enabled = s.canPreview && !s.loading, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.import_next))
            }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary)
        Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatsRow(vararg stats: Pair<Int, Int>) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            stats.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        value.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (label == R.string.import_errors && value > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun rowStatus(row: PlannedRow): Pair<String, Boolean> = when (val a = row.action) {
    RowAction.New -> stringResource(R.string.row_new) to false
    is RowAction.Update -> stringResource(R.string.row_update) to false
    is RowAction.Skip -> stringResource(
        when (a.reason) {
            SkipReason.DUPLICATE_IN_LIBRARY -> R.string.row_skip_library
            SkipReason.DUPLICATE_IN_FILE -> R.string.row_skip_file
            SkipReason.MERGED_IN_FILE -> R.string.row_merged
        },
    ) to false
    is RowAction.Error -> when (a.error) {
        RowError.EMPTY_SOURCE -> stringResource(R.string.row_err_empty_source)
        RowError.EMPTY_TRANSLATION -> stringResource(R.string.row_err_empty_translation)
        RowError.UNKNOWN_TYPE -> stringResource(R.string.row_err_unknown_type, a.value)
        RowError.NO_TYPE -> stringResource(R.string.row_err_no_type)
        RowError.DATABASE -> stringResource(R.string.row_err_database, a.value)
    } to true
}

@Composable
private fun PlannedRowItem(row: PlannedRow) {
    val (status, isError) = rowStatus(row)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.import_line, row.line),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(Modifier.weight(1f)) {
            val d = row.data
            if (d != null) {
                Text(d.source, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    d.translation + " · " + stringResource(if (d.type == EntryType.PHRASE) R.string.type_phrase else R.string.type_word) +
                        if (d.tags.isNotEmpty()) " · " + d.tags.joinToString(" ") { "#$it" } else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(status, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

private fun LazyListScope.errorSection(errors: List<PlannedRow>, limit: Int = 100) {
    if (errors.isEmpty()) return
    item {
        Text(
            androidx.compose.ui.res.stringResource(R.string.import_error_rows),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    items(errors.take(limit)) { PlannedRowItem(it) }
    if (errors.size > limit) item { Text(androidx.compose.ui.res.stringResource(R.string.import_more_errors, errors.size - limit)) }
}

@Composable
private fun PreviewStep(s: ImportUiState, vm: ImportViewModel, modifier: Modifier) {
    val plan = s.plan ?: return
    val valid = plan.rows.filter { it.action !is RowAction.Error }
    val errors = plan.rows.filter { it.action is RowAction.Error }
    val toWrite = plan.newCount + plan.updateCount
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp)) {
        item {
            StatsRow(
                R.string.import_found to plan.found,
                R.string.import_new to plan.newCount,
                R.string.import_to_update to plan.updateCount,
                R.string.import_skipped to plan.skipCount,
                R.string.import_errors to plan.errorCount,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = vm::startImport, enabled = toWrite > 0, modifier = Modifier.fillMaxWidth()) {
                Text(if (toWrite > 0) stringResource(R.string.import_confirm) + " ($toWrite)" else stringResource(R.string.import_nothing))
            }
            OutlinedButton(onClick = vm::backToConfigure, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.import_back_settings))
            }
            Text(
                stringResource(R.string.import_preview_first),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        items(valid.take(30)) { PlannedRowItem(it); HorizontalDivider() }
        errorSection(errors)
    }
}

@Composable
private fun RunningStep(s: ImportUiState, modifier: Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val total = s.progressTotal.coerceAtLeast(1)
        LinearProgressIndicator(progress = { s.progressDone.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.import_running, s.progressDone, s.progressTotal))
    }
}

@Composable
private fun DoneStep(s: ImportUiState, vm: ImportViewModel, onFinished: () -> Unit, modifier: Modifier) {
    val r = s.result
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (r != null) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber, null,
                    tint = if (r != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                )
                Text(
                    if (r != null) stringResource(R.string.import_done) else stringResource(R.string.import_failed, s.failure.orEmpty()),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (r != null) {
                StatsRow(
                    R.string.import_found to r.found,
                    R.string.import_new to r.imported,
                    R.string.import_updated to r.updated,
                    R.string.import_skipped to r.skipped,
                    R.string.import_errors to r.errorCount,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_finish)) }
            OutlinedButton(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_again)) }
        }
        if (r != null) errorSection(r.errors)
    }
}
