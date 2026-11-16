package com.mediahub.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediahub.mobile.catalog.CatalogViewModel
import com.mediahub.mobile.catalog.ProfileFolderAccess
import com.mediahub.mobile.catalog.ProfileStorageKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProfileFolderDialog(
    model: CatalogViewModel,
    profileId: String,
    storage: List<ProfileFolderAccess>,
    onConnect: (ProfileStorageKind) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val source = storage.firstOrNull { it.kind.name == selected }
    if (source == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Выберите хранилище") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileStorageKind.entries.forEach { kind ->
                        val connected = storage.any { it.kind == kind }
                        OutlinedButton(
                            onClick = {
                                if (connected) selected = kind.name else onConnect(kind)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (connected) kind.title else kind.connectLabel) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
        )
        return
    }

    val provider = source.provider
    val path = remember(source) { mutableStateListOf(provider.root) }
    val folder = path.last()
    var refresh by remember { mutableIntStateOf(0) }
    val contents = rememberFolderContents(provider, folder, refresh, onExpired = {})
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val folders = contents.nodes.filter { node -> node.isFolder && path.none { it.id == node.id } }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Привязать папку") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(source.kind.title, style = MaterialTheme.typography.labelMedium)
                Text(path.joinToString(" / ") { it.name }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row {
                    TextButton(enabled = !saving, onClick = {
                        error = null
                        if (path.size == 1) selected = null else path.removeAt(path.lastIndex)
                    }) { Text("Назад") }
                    if (contents.error != null) {
                        TextButton(enabled = !saving, onClick = { refresh++ }) { Text("Повторить") }
                    }
                }
                if (contents.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                (contents.error ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(folders, key = { it.id }) { node ->
                        TextButton(onClick = { path.add(node); error = null }, enabled = !saving,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(node.name, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (contents.nextPage != null) {
                        item {
                            TextButton(
                                onClick = { scope.launch { contents.load(provider, folder, more = true, onExpired = {}) } },
                                enabled = !contents.loading && !saving
                            ) { Text("Показать ещё") }
                        }
                    }
                }
                if (folders.isEmpty() && !contents.loading && contents.error == null) {
                    Text("Здесь нет вложенных папок.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && !contents.loading && contents.error == null,
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            model.saveFolder(source.link(profileId, path.toList()))
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = "Не удалось сохранить привязку."
                        } finally {
                            saving = false
                        }
                    }
                }
            ) { Text("Привязать эту папку") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Отмена") } }
    )
}
