package com.jotsarup.nowish.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jotsarup.nowish.sync.SyncState

const val WEB_APP_URL = "https://gitjotsarup.github.io/nowish/"
private const val NEW_TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"

fun syncSummary(state: SyncState): String = when {
    !state.configured -> "Not set up"
    state.syncing -> "Syncing…"
    state.error != null -> state.error
    state.lastSyncAt == 0L -> "Waiting for first sync"
    else -> "Synced " + DateUtils.getRelativeTimeSpanString(
        state.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    )
}

@Composable
fun SyncDialog(
    initialRepo: String,
    initialToken: String,
    state: SyncState,
    onSave: (String, String) -> Unit,
    onSyncNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uri = LocalUriHandler.current
    var repo by remember { mutableStateOf(initialRepo) }
    var token by remember { mutableStateOf(initialToken) }
    var showToken by remember { mutableStateOf(false) }
    val changed = repo.trim() != initialRepo || token.trim() != initialToken

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync with laptop") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Your tasks are kept in a private GitHub repo, and the web app at " +
                        "gitjotsarup.github.io/nowish reads the same file.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Make a fine-grained token with access to only that repo and " +
                        "Contents: Read and write, then paste it here and on the website.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { uri.openUri(NEW_TOKEN_URL) }) { Text("Create a token on GitHub") }
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text("Data repo (owner/name)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Access token") },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                if (showToken) "Hide token" else "Show token",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    syncSummary(state),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { uri.openUri(WEB_APP_URL) }) { Text("Open the web app") }
            }
        },
        confirmButton = {
            if (changed || !state.configured) {
                TextButton(
                    onClick = { onSave(repo, token) },
                    enabled = token.isNotBlank() && repo.contains('/'),
                ) { Text("Save & sync") }
            } else {
                TextButton(onClick = onSyncNow, enabled = !state.syncing) { Text("Sync now") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
