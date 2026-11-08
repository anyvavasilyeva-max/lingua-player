package io.github.linguaplayer.ui.tags

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linguaplayer.AppContainer
import io.github.linguaplayer.R
import io.github.linguaplayer.data.db.TagEntity
import io.github.linguaplayer.ui.components.ConfirmDialog
import io.github.linguaplayer.ui.components.EmptyState
import io.github.linguaplayer.ui.components.TextInputDialog
import io.github.linguaplayer.ui.components.appContainer
import io.github.linguaplayer.ui.components.errorText
import io.github.linguaplayer.ui.playlists.PlaylistMenu
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TagsViewModel(private val c: AppContainer) : ViewModel() {
    val tags = c.tags.observeWithCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val error = MutableStateFlow<Throwable?>(null)

    private fun run(onOk: () -> Unit = {}, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onSuccess { error.value = null; onOk() }.onFailure { error.value = it }
    }

    fun create(name: String, onOk: () -> Unit) = run(onOk) { c.tags.create(name) }
    fun rename(id: Long, name: String, onOk: () -> Unit) = run(onOk) { c.tags.rename(id, name) }
    fun delete(id: Long) = run { c.tags.delete(id) }
    fun clearError() { error.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val vm: TagsViewModel = viewModel { TagsViewModel(c) }
    val tags by vm.tags.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TagEntity?>(null) }
    var deleting by remember { mutableStateOf<TagEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val errMsg = error?.let { errorText(it) }
    val dialogOpen = showCreate || renaming != null

    LaunchedEffect(errMsg, dialogOpen) {
        if (errMsg != null && !dialogOpen) { snackbar.showSnackbar(errMsg); vm.clearError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tags_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(stringResource(R.string.tag_new)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = tags
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(stringResource(R.string.tags_empty), Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(list, key = { it.tag.id }) { t ->
                    ListItem(
                        leadingContent = if (t.tag.isSystem) { { Icon(Icons.Outlined.Lock, stringResource(R.string.tag_system)) } } else null,
                        headlineContent = { Text(t.tag.name) },
                        supportingContent = {
                            val count = pluralStringResource(R.plurals.entries_count, t.usageCount, t.usageCount)
                            Text(if (t.tag.isSystem) "$count · ${stringResource(R.string.tag_system)}" else count)
                        },
                        trailingContent = {
                            PlaylistMenu(
                                onRename = { renaming = t.tag },
                                onDelete = { deleting = t.tag },
                            )
                        },
                    )
                }
            }
        }
    }

    if (showCreate) {
        TextInputDialog(
            title = stringResource(R.string.tag_new),
            confirmText = stringResource(R.string.action_create),
            error = errMsg,
            onDismiss = { showCreate = false; vm.clearError() },
            onConfirm = { vm.create(it) { showCreate = false } },
        )
    }
    renaming?.let { tag ->
        TextInputDialog(
            title = stringResource(R.string.action_rename),
            initial = tag.name,
            error = errMsg,
            onDismiss = { renaming = null; vm.clearError() },
            onConfirm = { vm.rename(tag.id, it) { renaming = null } },
        )
    }
    deleting?.let { tag ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title),
            text = stringResource(R.string.tag_delete_confirm, tag.name),
            onDismiss = { deleting = null },
            onConfirm = { vm.delete(tag.id); deleting = null },
        )
    }
}
