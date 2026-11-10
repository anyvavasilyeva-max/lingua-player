package io.github.linguaplayer.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.linguaplayer.R
import io.github.linguaplayer.data.db.PlaylistKind
import io.github.linguaplayer.data.db.TagEntity
import io.github.linguaplayer.data.db.TagMatchMode
import io.github.linguaplayer.data.db.TypeFilter
import io.github.linguaplayer.data.repo.SmartRules

/**
 * Создание плейлиста (обычного или умного) и редактирование условий умного плейлиста.
 * [kindEditable] = false — тип плейлиста уже выбран (редактирование).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistEditorDialog(
    title: String,
    /** Название языковой пары плейлиста (только для показа). */
    pairName: String,
    tags: List<TagEntity>,
    initialName: String = "",
    initialKind: PlaylistKind = PlaylistKind.NORMAL,
    initialRules: SmartRules? = null,
    kindEditable: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (name: String, kind: PlaylistKind, rules: SmartRules?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var kind by remember { mutableStateOf(initialKind) }
    var type by remember { mutableStateOf(initialRules?.type ?: TypeFilter.ALL) }
    var tagIds by remember { mutableStateOf(initialRules?.tagIds ?: emptySet()) }
    var mode by remember { mutableStateOf(initialRules?.tagMode ?: TagMatchMode.ANY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.field_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kindEditable) {
                    KindOption(kind == PlaylistKind.NORMAL, R.string.playlist_kind_normal, R.string.playlist_kind_normal_desc) {
                        kind = PlaylistKind.NORMAL
                    }
                    KindOption(kind == PlaylistKind.SMART, R.string.playlist_smart, R.string.playlist_kind_smart_desc) {
                        kind = PlaylistKind.SMART
                    }
                }
                if (kind == PlaylistKind.SMART) {
                    Text(stringResource(R.string.smart_rules), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.entry_pair, pairName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            TypeFilter.ALL to R.string.filter_all,
                            TypeFilter.WORDS to R.string.filter_words,
                            TypeFilter.PHRASES to R.string.filter_phrases,
                        ).forEach { (t, label) -> FilterChip(type == t, { type = t }, { Text(stringResource(label)) }) }
                    }
                    Text(
                        if (tagIds.isEmpty()) stringResource(R.string.smart_tags_none) else stringResource(R.string.entry_tags),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { tag ->
                            FilterChip(tag.id in tagIds, { tagIds = if (tag.id in tagIds) tagIds - tag.id else tagIds + tag.id }, { Text("#" + tag.name) })
                        }
                    }
                    if (tagIds.size >= 2) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(mode == TagMatchMode.ANY, { mode = TagMatchMode.ANY }, { Text(stringResource(R.string.filter_tag_any)) })
                            FilterChip(mode == TagMatchMode.ALL, { mode = TagMatchMode.ALL }, { Text(stringResource(R.string.filter_tag_all)) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val rules = if (kind == PlaylistKind.SMART) SmartRules(type, tagIds, mode) else null
                    onConfirm(name, kind, rules)
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun KindOption(selected: Boolean, title: Int, desc: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(stringResource(title))
            Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
