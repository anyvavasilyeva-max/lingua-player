package io.github.linguaplayer.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.github.linguaplayer.data.db.PlaylistEntity
import io.github.linguaplayer.data.db.TagEntity

/**
 * Выбор нескольких тегов. [onCreateTag] — если задан, можно сразу создать новый тег
 * (функция возвращает id созданного тега или null при ошибке).
 */
@Composable
fun TagPickerDialog(
    title: String,
    tags: List<TagEntity>,
    initial: Set<Long> = emptySet(),
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit,
    onCreateTag: ((String, (Long) -> Unit) -> Unit)? = null,
) {
    var selected by remember { mutableStateOf(initial) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                if (onCreateTag != null) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.tag_new)) },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                enabled = newName.isNotBlank(),
                                onClick = { onCreateTag(newName) { id -> selected = selected + id; newName = "" } },
                            ) { Icon(Icons.Outlined.Add, stringResource(R.string.action_create)) }
                        }
                    }
                }
                if (tags.isEmpty()) item { Text(stringResource(R.string.tags_empty)) }
                items(tags, key = { it.id }) { tag ->
                    val checked = tag.id in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(checked, role = Role.Checkbox) { selected = if (it) selected + tag.id else selected - tag.id }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(tag.name, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun PlaylistPickerDialog(
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onPick: (PlaylistEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_playlist)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                if (playlists.isEmpty()) {
                    item { Text(stringResource(R.string.entry_no_playlists), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(playlists, key = { it.id }) { p ->
                    Text(
                        p.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                            .selectable(false, role = Role.Button) { onPick(p) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
