package io.github.linguaplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linguaplayer.AppContainer
import io.github.linguaplayer.R
import io.github.linguaplayer.data.repo.displayName
import io.github.linguaplayer.ui.components.appContainer
import io.github.linguaplayer.ui.components.errorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val pairs = c.languagePairs.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val active = c.activePair.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val error = MutableStateFlow<Throwable?>(null)

    fun select(id: Long) = viewModelScope.launch { c.settings.setActivePair(id) }

    fun add(source: String, target: String, onOk: () -> Unit) = viewModelScope.launch {
        runCatching { c.languagePairs.add(source, target) }
            .onSuccess { error.value = null; onOk() }
            .onFailure { error.value = it }
    }

    fun delete(id: Long) = viewModelScope.launch {
        runCatching { c.languagePairs.delete(id) }.onFailure { error.value = it }
    }

    fun clearError() { error.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onManageTags: () -> Unit, onOpenVoices: () -> Unit) {
    val c = appContainer()
    val vm: SettingsViewModel = viewModel { SettingsViewModel(c) }
    val pairs by vm.pairs.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val errMsg = error?.let { errorText(it) }
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    LaunchedEffect(errMsg, showAdd) {
        if (errMsg != null && !showAdd) { snackbar.showSnackbar(errMsg); vm.clearError() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionHeader(stringResource(R.string.settings_pairs)) }
            items(pairs, key = { it.id }) { pair ->
                val selected = pair.id == active?.id
                ListItem(
                    modifier = Modifier.selectable(selected, role = Role.RadioButton) { vm.select(pair.id) },
                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                    headlineContent = { Text(pair.displayName()) },
                    supportingContent = { Text("${pair.sourceLang} → ${pair.targetLang}") },
                    trailingContent = {
                        if (pairs.size > 1) {
                            IconButton(onClick = { vm.delete(pair.id) }) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete))
                            }
                        }
                    },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { showAdd = true },
                    leadingContent = { Icon(Icons.Outlined.Add, null) },
                    headlineContent = { Text(stringResource(R.string.settings_add_pair)) },
                )
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable(onClick = onOpenVoices),
                    leadingContent = { Icon(Icons.Outlined.RecordVoiceOver, null) },
                    headlineContent = { Text(stringResource(R.string.settings_voices)) },
                    supportingContent = { Text(stringResource(R.string.settings_voices_desc)) },
                )
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable(onClick = onManageTags),
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.Label, null) },
                    headlineContent = { Text(stringResource(R.string.settings_manage_tags)) },
                )
                HorizontalDivider()
                SectionHeader(stringResource(R.string.settings_about))
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Info, null) },
                    headlineContent = { Text(stringResource(R.string.app_name)) },
                    supportingContent = { Text(stringResource(R.string.settings_version, version)) },
                )
            }
        }
    }

    if (showAdd) {
        AddPairDialog(
            error = errMsg,
            onDismiss = { showAdd = false; vm.clearError() },
            onConfirm = { s, t -> vm.add(s, t) { showAdd = false } },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AddPairDialog(error: String?, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var source by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("ru-RU") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_add_pair)) },
        text = {
            Column {
                OutlinedTextField(
                    value = source, onValueChange = { source = it }, singleLine = true,
                    label = { Text(stringResource(R.string.settings_pair_source)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = target, onValueChange = { target = it }, singleLine = true,
                    label = { Text(stringResource(R.string.settings_pair_target)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    error ?: stringResource(R.string.settings_pair_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(source, target) }, enabled = source.isNotBlank() && target.isNotBlank()) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
