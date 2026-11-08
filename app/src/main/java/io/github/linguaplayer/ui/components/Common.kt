package io.github.linguaplayer.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.linguaplayer.AppContainer
import io.github.linguaplayer.LinguaApp
import io.github.linguaplayer.R
import io.github.linguaplayer.data.repo.UserFacingException

/** Доступ к зависимостям приложения из Compose. */
@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as LinguaApp).container

@StringRes
fun UserFacingException.Reason.messageRes(): Int = when (this) {
    UserFacingException.Reason.EMPTY_FIELD -> R.string.error_empty_field
    UserFacingException.Reason.TAG_EXISTS -> R.string.error_tag_exists
    UserFacingException.Reason.SYSTEM_TAG -> R.string.error_system_tag
    UserFacingException.Reason.PAIR_EXISTS -> R.string.error_pair_exists
    UserFacingException.Reason.PAIR_INVALID -> R.string.error_pair_invalid
    UserFacingException.Reason.PAIR_IN_USE -> R.string.error_pair_in_use
    UserFacingException.Reason.NOT_FOUND -> R.string.error_not_found
}

/** Текст ошибки для показа пользователю. */
@Composable
fun errorText(error: Throwable): String = when (error) {
    is UserFacingException -> stringResource(error.reason.messageRes())
    else -> stringResource(R.string.error_unknown, error.message ?: error.javaClass.simpleName)
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Диалог ввода одной строки (создание / переименование). */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String = stringResource(R.string.field_name),
    confirmText: String = stringResource(R.string.action_save),
    error: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = stringResource(R.string.action_delete),
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
