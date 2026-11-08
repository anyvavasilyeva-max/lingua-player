package io.github.linguaplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.linguaplayer.data.db.EntryFilter
import io.github.linguaplayer.data.db.TagEntity
import io.github.linguaplayer.data.db.TagMatchMode
import io.github.linguaplayer.data.db.TypeFilter
import io.github.linguaplayer.data.repo.EntryWithTags
import io.github.linguaplayer.ui.components.EmptyState
import io.github.linguaplayer.ui.components.EntryRow
import io.github.linguaplayer.ui.components.appContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class LibraryFilterState(
    val text: String = "",
    val type: TypeFilter = TypeFilter.ALL,
    val tagIds: Set<Long> = emptySet(),
    val tagMode: TagMatchMode = TagMatchMode.ANY,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(LibraryFilterState())

    val tags = c.tags.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** null — данные ещё загружаются. */
    val entries = combine(c.activePair, filter.debounce(150)) { pair, f -> pair to f }
        .flatMapLatest { (pair, f) ->
            c.entries.observeEntries(
                EntryFilter(pairId = pair?.id ?: -1, text = f.text, type = f.type, tagIds = f.tagIds, tagMode = f.tagMode),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setText(t: String) = filter.update { it.copy(text = t) }
    fun setType(t: TypeFilter) = filter.update { it.copy(type = t) }
    fun toggleTag(id: Long) = filter.update { it.copy(tagIds = if (id in it.tagIds) it.tagIds - id else it.tagIds + id) }
    fun toggleMode() = filter.update {
        it.copy(tagMode = if (it.tagMode == TagMatchMode.ANY) TagMatchMode.ALL else TagMatchMode.ANY)
    }
    fun clearTags() = filter.update { it.copy(tagIds = emptySet()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenEntry: (Long) -> Unit,
    onAddEntry: () -> Unit,
    onManageTags: () -> Unit,
) {
    val c = appContainer()
    val vm: LibraryViewModel = viewModel { LibraryViewModel(c) }
    val filter by vm.filter.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_library)) },
                actions = {
                    IconButton(onClick = onManageTags) {
                        Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = stringResource(R.string.library_tags))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddEntry,
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(stringResource(R.string.library_add)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = filter.text,
                onValueChange = vm::setText,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.library_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (filter.text.isNotEmpty()) {
                        IconButton(onClick = { vm.setText("") }) { Icon(Icons.Outlined.Clear, null) }
                    }
                },
                singleLine = true,
            )
            FilterBar(filter, tags, vm)
            HorizontalDivider()
            val list = entries
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(stringResource(R.string.library_empty))
                else -> EntryList(list, onOpenEntry)
            }
        }
    }
}

@Composable
private fun FilterBar(filter: LibraryFilterState, tags: List<TagEntity>, vm: LibraryViewModel) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(filter.type == TypeFilter.ALL, { vm.setType(TypeFilter.ALL) }, { Text(stringResource(R.string.filter_all)) })
        }
        item {
            FilterChip(filter.type == TypeFilter.WORDS, { vm.setType(TypeFilter.WORDS) }, { Text(stringResource(R.string.filter_words)) })
        }
        item {
            FilterChip(filter.type == TypeFilter.PHRASES, { vm.setType(TypeFilter.PHRASES) }, { Text(stringResource(R.string.filter_phrases)) })
        }
        if (filter.tagIds.size >= 2) {
            item {
                FilterChip(
                    selected = true,
                    onClick = vm::toggleMode,
                    label = {
                        Text(stringResource(if (filter.tagMode == TagMatchMode.ANY) R.string.filter_tag_any else R.string.filter_tag_all))
                    },
                )
            }
        }
        items(tags, key = { it.id }) { tag ->
            FilterChip(tag.id in filter.tagIds, { vm.toggleTag(tag.id) }, { Text("#" + tag.name) })
        }
        if (filter.tagIds.isNotEmpty()) {
            item { FilterChip(false, vm::clearTags, { Text(stringResource(R.string.filter_clear)) }) }
        }
    }
}

@Composable
private fun EntryList(list: List<EntryWithTags>, onOpenEntry: (Long) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        item {
            Text(
                pluralStringResource(R.plurals.entries_count, list.size, list.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        items(list, key = { it.entry.id }) { item ->
            EntryRow(item, onClick = { onOpenEntry(item.entry.id) })
        }
    }
}
