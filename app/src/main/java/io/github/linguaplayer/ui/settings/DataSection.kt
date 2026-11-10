package io.github.linguaplayer.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linguaplayer.AppContainer
import io.github.linguaplayer.R
import io.github.linguaplayer.data.backup.BackupData
import io.github.linguaplayer.data.backup.BackupFormatException
import io.github.linguaplayer.data.db.EntryFilter
import io.github.linguaplayer.ui.components.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Результат операции с данными — для сообщения пользователю. */
sealed interface DataMessage {
    data class Exported(val count: Int) : DataMessage
    data object BackupCreated : DataMessage
    data class Restored(val entries: Int, val playlists: Int) : DataMessage
    data class Failed(val reason: Int) : DataMessage
}

data class DataUiState(
    val busy: Boolean = false,
    /** Прочитанная копия, ожидающая подтверждения восстановления. */
    val pending: BackupData? = null,
    val message: DataMessage? = null,
)

class DataViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(DataUiState())

    private fun failure(e: Exception) = DataMessage.Failed(
        when ((e as? BackupFormatException)?.reason) {
            BackupFormatException.Reason.NOT_A_BACKUP -> R.string.data_err_not_backup
            BackupFormatException.Reason.NEWER_VERSION -> R.string.data_err_newer
            BackupFormatException.Reason.CORRUPTED -> R.string.data_err_corrupted
            null -> R.string.data_err_io
        },
    )

    private fun run(block: suspend () -> DataMessage) = viewModelScope.launch {
        state.update { it.copy(busy = true) }
        val msg = try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e)
        }
        state.update { it.copy(busy = false, message = msg) }
    }

    suspend fun fileBaseName(): String {
        val pair = c.activePair.first()
        val pairPart = pair?.let { "${it.sourceLang}_${it.targetLang}" } ?: "library"
        return "lingua_player_${pairPart}_${LocalDate.now()}"
    }

    fun exportLibrary(context: Context, uri: Uri, xlsx: Boolean) = run {
        val pair = c.activePair.first() ?: return@run DataMessage.Failed(R.string.data_err_io)
        val rows = c.exporter.rows(EntryFilter(pairId = pair.id))
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                if (xlsx) c.exporter.writeXlsx("${pair.sourceLang} → ${pair.targetLang}", rows, out) else c.exporter.writeCsv(rows, out)
            }
        }
        DataMessage.Exported(rows.size - 1)
    }

    fun createBackup(context: Context, uri: Uri) = run {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { c.backup.export(it) }
        }
        DataMessage.BackupCreated
    }

    /** Шаг 1: прочитать и проверить файл, показать подтверждение. Данные не меняются. */
    fun inspectBackup(context: Context, uri: Uri) = viewModelScope.launch {
        state.update { it.copy(busy = true) }
        try {
            val data = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { c.backup.inspect(it) } }
            state.update { it.copy(busy = false, pending = data) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(busy = false, message = failure(e)) }
        }
    }

    /** Шаг 2: заменить данные содержимым копии. */
    fun confirmRestore() {
        val data = state.value.pending ?: return
        state.update { it.copy(pending = null) }
        run {
            val r = c.restoreBackup(data)
            DataMessage.Restored(r.entries, r.playlists)
        }
    }

    fun cancelRestore() = state.update { it.copy(pending = null) }
    fun consumeMessage() = state.update { it.copy(message = null) }
}

/** Раздел «Данные» в настройках. Сообщения показываются через [onMessage]. */
@Composable
fun DataSection(onMessage: (String) -> Unit) {
    val c = appContainer()
    val vm: DataViewModel = viewModel { DataViewModel(c) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var baseName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { baseName = vm.fileBaseName() }

    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportLibrary(context, it, xlsx = false) }
    }
    val exportXlsx = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri -> uri?.let { vm.exportLibrary(context, it, xlsx = true) } }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { vm.createBackup(context, it) }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.inspectBackup(context, it) }
    }

    val text = when (val m = s.message) {
        is DataMessage.Exported -> stringResource(R.string.data_exported, m.count)
        DataMessage.BackupCreated -> stringResource(R.string.data_backup_created)
        is DataMessage.Restored -> stringResource(R.string.data_restored, m.entries, m.playlists)
        is DataMessage.Failed -> stringResource(m.reason)
        null -> null
    }
    LaunchedEffect(text) { if (text != null) { onMessage(text); vm.consumeMessage() } }

    Column {
        ListItem(
            modifier = Modifier.clickable(enabled = !s.busy) { exportCsv.launch("${baseName.ifEmpty { "lingua_player" }}.csv") },
            leadingContent = { Icon(Icons.Outlined.Description, null) },
            headlineContent = { Text(stringResource(R.string.data_export_csv)) },
            supportingContent = { Text(stringResource(R.string.data_export_desc)) },
        )
        ListItem(
            modifier = Modifier.clickable(enabled = !s.busy) { exportXlsx.launch("${baseName.ifEmpty { "lingua_player" }}.xlsx") },
            leadingContent = { Icon(Icons.Outlined.GridOn, null) },
            headlineContent = { Text(stringResource(R.string.data_export_xlsx)) },
            supportingContent = { Text(stringResource(R.string.data_export_desc)) },
        )
        ListItem(
            modifier = Modifier.clickable(enabled = !s.busy) { backup.launch("lingua_player_backup_${LocalDate.now()}.lpbackup") },
            leadingContent = { Icon(Icons.Outlined.Backup, null) },
            headlineContent = { Text(stringResource(R.string.data_backup)) },
            supportingContent = { Text(stringResource(R.string.data_backup_desc)) },
            trailingContent = if (s.busy) { { CircularProgressIndicator() } } else null,
        )
        ListItem(
            modifier = Modifier.clickable(enabled = !s.busy) { restore.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
            leadingContent = { Icon(Icons.Outlined.Restore, null) },
            headlineContent = { Text(stringResource(R.string.data_restore)) },
            supportingContent = { Text(stringResource(R.string.data_restore_desc)) },
        )
    }

    s.pending?.let { data ->
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text(stringResource(R.string.data_restore_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.data_restore_confirm_text,
                        data.pairs.size, data.entries.size, data.playlists.size, data.tags.size,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmRestore) { Text(stringResource(R.string.data_restore_confirm)) } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
