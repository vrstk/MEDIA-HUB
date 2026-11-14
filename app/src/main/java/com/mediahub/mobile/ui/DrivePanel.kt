package com.mediahub.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CloudProvider
import com.mediahub.mobile.drive.DriveAuthorizationRequired
import com.mediahub.mobile.drive.DriveProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal class FolderContents {
    var nodes by mutableStateOf<List<CloudNode>>(emptyList())
        private set
    var nextPage by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val seenPages = HashSet<String>()

    suspend fun load(provider: CloudProvider, folder: CloudNode, more: Boolean, onExpired: () -> Unit) {
        if (more && loading) return
        val token = if (more) nextPage ?: return else null
        loading = true
        error = null
        try {
            val page = runInterruptible(Dispatchers.IO) { provider.list(folder.id, token) }
            nodes = ((if (more) nodes else emptyList()) + page.nodes).distinctBy { it.id }
            nextPage = page.nextPageToken?.takeIf(seenPages::add)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveAuthorizationRequired) {
            onExpired()
        } catch (e: Exception) {
            error = e.userMessage("Не удалось открыть папку.")
        } finally {
            loading = false
        }
    }
}

@Composable
internal fun rememberFolderContents(
    provider: CloudProvider,
    folder: CloudNode,
    refresh: Int,
    onExpired: () -> Unit
): FolderContents {
    val state = remember(provider, folder.id, refresh) { FolderContents() }
    LaunchedEffect(state) { state.load(provider, folder, more = false, onExpired) }
    return state
}

@Composable
internal fun DrivePanel(
    drive: DriveProvider?,
    driveError: String?,
    authorizing: Boolean,
    mailConnected: Boolean,
    copying: Boolean,
    actions: DriveActions,
    onExpired: () -> Unit,
    onCopy: (CloudNode) -> Unit
) {
    if (drive == null) {
        InfoCard("Google Drive", "Media Hub видит созданные им файлы и объекты, к которым Google предоставил приложению доступ.")
        driveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (authorizing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = actions.connect, enabled = !authorizing && !copying, modifier = Modifier.fillMaxWidth()) {
            Text("Подключить Google Drive")
        }
        return
    }

    val path = remember(drive) { mutableStateListOf(drive.root) }
    val folder = path.last()
    var refresh by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var directoryDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val state = rememberFolderContents(drive, folder, refresh, onExpired)
    val scope = rememberCoroutineScope()

    fun goBack() {
        if (path.size > 1) {
            path.removeAt(path.lastIndex)
            error = null
        }
    }
    BackHandler(enabled = path.size > 1 && !creating, onBack = ::goBack)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Доступ к файлам Media Hub", style = MaterialTheme.typography.labelMedium)
        Text(path.joinToString(" / ") { it.name }, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (path.size > 1) TextButton(onClick = ::goBack, enabled = !creating) { Text("Назад") }
            TextButton(onClick = { refresh++; error = null }, enabled = !state.loading && !creating) {
                Text("Обновить")
            }
            TextButton(onClick = { directoryDialog = true }, enabled = !creating && !state.loading) {
                Text("Новая папка")
            }
        }
        if (!mailConnected) Text("Подключите Mail, чтобы копировать файлы между облаками.")
        if (state.loading || creating) LinearProgressIndicator(Modifier.fillMaxWidth())
        (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (state.nodes.isEmpty() && !state.loading && state.error == null && state.nextPage == null) {
                item { Text("Здесь пока нет файлов с доступом для Media Hub.") }
            }
            items(state.nodes, key = { it.id }) { node ->
                if (node.isFolder) {
                    OutlinedButton(
                        onClick = { path.add(node); error = null },
                        enabled = !creating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(node.name, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (!node.canReadBytes) {
                                    Text("Документ, ярлык или скачивание ограничено", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (node.canReadBytes && mailConnected) {
                                TextButton(onClick = { onCopy(node) }, enabled = !copying) { Text("В Mail") }
                            }
                        }
                    }
                }
            }
            if (state.nextPage != null) {
                item {
                    TextButton(
                        onClick = { scope.launch { state.load(drive, folder, more = true, onExpired) } },
                        enabled = !state.loading
                    ) { Text("Показать ещё") }
                }
            }
        }
        TextButton(onClick = actions.disconnect, enabled = !copying && !creating) {
            Text("Отключить Google Drive на устройстве")
        }
    }

    if (directoryDialog) {
        CloudFolderNameDialog(onDismiss = { directoryDialog = false }) { name ->
            directoryDialog = false
            creating = true
            error = null
            scope.launch {
                try {
                    runInterruptible(Dispatchers.IO) { drive.createFolder(folder.id, name) }
                    refresh++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: DriveAuthorizationRequired) {
                    onExpired()
                } catch (e: Exception) {
                    error = e.userMessage("Не удалось создать папку.")
                } finally {
                    creating = false
                }
            }
        }
    }
}

@Composable
internal fun CloudFolderNameDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая папка") },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Название") })
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank() && name.trim() !in listOf(".", "..") &&
                    '/' !in name && '\\' !in name
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
