package com.mediahub.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CloudProvider
import com.mediahub.mobile.drive.DriveAuthorizationRequired
import com.mediahub.mobile.media.MediaFileKinds
import com.mediahub.mobile.media.PhotoPreviewLoader
import com.mediahub.mobile.media.readPhotoBytes
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
    drive: CloudProvider?,
    safMode: Boolean,
    driveError: String?,
    authorizing: Boolean,
    mailConnected: Boolean,
    copying: Boolean,
    actions: DriveActions,
    onExpired: () -> Unit,
    onSelectFolder: () -> Unit,
    onDisconnectFolder: () -> Unit,
    requestedTrail: List<CloudNode>?,
    onFolderRequestHandled: () -> Unit,
    onCopy: (CloudNode) -> Unit
) {
    if (drive == null) {
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        val diagnostics = remember(context) { DriveOAuthDiagnostics.read(context) }
        var expanded by remember { mutableStateOf(false) }
        InfoCard("Google Drive через Android", "Выберите папку через системное приложение «Файлы». Если Google Drive отображается среди расположений, выберите папку в нём. Отдельная OAuth-настройка Media Hub не требуется.")
        driveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (authorizing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = onSelectFolder, enabled = !copying, modifier = Modifier.fillMaxWidth()) {
            Text("Выбрать папку Google Drive / Android")
        }
        Text("Доступ зависит от системного провайдера. Google Drive может не разрешать выбор целой папки на некоторых устройствах.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = actions.connect, enabled = !authorizing && !copying, modifier = Modifier.fillMaxWidth()) {
            Text("Альтернатива: подключить через OAuth")
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Скрыть диагностику OAuth" else "Диагностика подключения Google Drive")
        }
        if (expanded) {
            InfoCard("Данные установленной APK", "Google Cloud OAuth требует точного совпадения пакета и SHA-1 сертификата. В Preview другое имя пакета, а debug-сертификат GitHub может меняться.")
            SelectionContainer {
                Text(diagnostics.summary(), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(diagnostics.summary()))
            }) { Text("Скопировать пакет и SHA-1") }
            Text("Проверьте: Google Drive API включён, OAuth-клиент типа Android настроен, ваш аккаунт добавлен в тестировщики (при тестовом режиме). Не присылайте пароли или токены.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    val path = remember(drive) {
        mutableStateListOf<CloudNode>().apply { addAll(requestedTrail ?: listOf(drive.root)) }
    }
    LaunchedEffect(requestedTrail) {
        if (requestedTrail != null) onFolderRequestHandled()
    }
    val folder = path.last()
    var refresh by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var directoryDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var gridView by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable(folder.id) { mutableStateOf("") }
    var searchOpen by rememberSaveable(folder.id) { mutableStateOf(false) }
    var pathExpanded by remember { mutableStateOf(false) }
    var toolsExpanded by remember { mutableStateOf(false) }
    var selectedPhotoId by remember(drive, folder.id) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val state = rememberFolderContents(drive, folder, refresh, onExpired)
    val scope = rememberCoroutineScope()
    val photoLoader = remember(drive, refresh) {
        PhotoPreviewLoader { file, maxBytes -> drive.openRead(file).use { it.readPhotoBytes(maxBytes) } }
    }

    LaunchedEffect(folder.id, gridView, search) {
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }

    fun goBack() {
        if (path.size > 1) {
            path.removeAt(path.lastIndex)
            error = null
        }
    }
    BackHandler(enabled = path.size > 1 && !creating && selectedPhotoId == null, onBack = ::goBack)

    fun openNode(node: CloudNode) {
        if (node.isFolder) {
            path.add(node)
            error = null
        } else if (!state.loading && MediaFileKinds.isPhoto(node)) {
            selectedPhotoId = node.id
        }
    }

    fun loadMore() {
        scope.launch { state.load(drive, folder, more = true, onExpired) }
    }

    val visibleNodes = remember(state.nodes, search) { filterAndSortCloudNodes(state.nodes, search) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(
                    onClick = { pathExpanded = true },
                    enabled = !creating,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                }
                DropdownMenu(expanded = pathExpanded, onDismissRequest = { pathExpanded = false }) {
                    path.asReversed().forEach { crumb ->
                        DropdownMenuItem(
                            text = { Text(crumb.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            enabled = crumb.id != folder.id && !creating,
                            onClick = {
                                while (path.size > 1 && path.last().id != crumb.id) {
                                    path.removeAt(path.lastIndex)
                                }
                                pathExpanded = false
                                error = null
                            }
                        )
                    }
                }
            }
            IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) search = "" }) {
                Icon(Icons.Default.Search, contentDescription = "Поиск в папке")
            }
            Box {
                IconButton(onClick = { toolsExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Действия с папкой")
                }
                DropdownMenu(expanded = toolsExpanded, onDismissRequest = { toolsExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Обновить") },
                        enabled = !state.loading && !creating,
                        onClick = { toolsExpanded = false; refresh++; error = null }
                    )
                    DropdownMenuItem(
                        text = { Text("Новая папка") },
                        enabled = !creating && !state.loading,
                        onClick = { toolsExpanded = false; directoryDialog = true }
                    )
                    DropdownMenuItem(
                        text = { Text(if (safMode) "Отвязать папку" else "Отключить Google Drive") },
                        enabled = !copying && !creating,
                        onClick = {
                            toolsExpanded = false
                            if (safMode) onDisconnectFolder() else actions.disconnect()
                        }
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (path.size > 1) TextButton(onClick = ::goBack, enabled = !creating) { Text("Назад") }
            Text(
                if (search.isBlank()) state.nodes.size.toString()
                else "${visibleNodes.size}/${state.nodes.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            FilterChip(selected = !gridView, onClick = { gridView = false }, label = { Text("Список") })
            FilterChip(selected = gridView, onClick = { gridView = true }, label = { Text("Плитка") })
        }
        if (searchOpen) OutlinedTextField(
            value = search, onValueChange = { search = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("Поиск в текущей папке") }
        )
        if (safMode) Text(
            "Проверка через Android не подтверждает завершение синхронизации с Google.",
            style = MaterialTheme.typography.labelSmall
        )
        if (!mailConnected) Text("Подключите Mail для копирования между облаками.",
            style = MaterialTheme.typography.bodySmall)
        if (state.loading || creating) LinearProgressIndicator(Modifier.fillMaxWidth())
        (error ?: state.error ?: driveError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val empty = state.nodes.isEmpty() && !state.loading && state.error == null && state.nextPage == null
        if (empty) {
            Text("Здесь пока нет файлов с доступом для Media Hub.", modifier = Modifier.weight(1f))
        } else if (gridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                state = gridState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visibleNodes.size, key = { visibleNodes[it].id }) { index ->
                    val node = visibleNodes[index]
                    val canOpen = !creating && (node.isFolder || !state.loading && MediaFileKinds.isPhoto(node))
                    val canCopy = !copying && !creating && mailConnected && (node.isFolder || node.canReadBytes)
                    StorageGridTile(
                        file = node,
                        loader = photoLoader,
                        onOpen = if (canOpen) ({ openNode(node) }) else null,
                        onCopy = if (canCopy) ({ onCopy(node) }) else null,
                        copyDescription = "Копировать в Mail",
                        copyLabel = "В Mail"
                    )
                }
                if (state.nextPage != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(onClick = ::loadMore, enabled = !state.loading) { Text("Показать ещё") }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(visibleNodes, key = { it.id }) { node ->
                    DriveFileRow(node, creating, mailConnected, copying, { openNode(node) }, { onCopy(node) })
                }
                if (state.nextPage != null) {
                    item { TextButton(onClick = ::loadMore, enabled = !state.loading) { Text("Показать ещё") } }
                }
            }
        }
    }

    selectedPhotoId?.let { id ->
        val photos = remember(visibleNodes) { visibleNodes.filter(MediaFileKinds::isPhoto) }
        PhotoViewer(
            photos, id, photoLoader,
            onClose = { selectedPhotoId = null },
            onLoadFailure = { if (it is DriveAuthorizationRequired) onExpired() }
        ) { file ->
            if (mailConnected && !copying) TextButton(onClick = {
                selectedPhotoId = null
                onCopy(file)
            }) { Text("В Mail") }
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
private fun DriveFileRow(
    node: CloudNode,
    creating: Boolean,
    mailConnected: Boolean,
    copying: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit
) {
    if (node.isFolder) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onOpen, enabled = !creating, modifier = Modifier.weight(1f)) {
                Text(node.name, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (mailConnected) TextButton(onClick = onCopy, enabled = !copying && !creating) { Text("В Mail") }
        }
    } else {
        val modifier = Modifier.fillMaxWidth()
        val isPhoto = MediaFileKinds.isPhoto(node)
        Card(if (isPhoto) modifier.clickable(enabled = !creating, role = Role.Button, onClick = onOpen) else modifier) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!node.canReadBytes) {
                        Text("Документ, ярлык или скачивание ограничено", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (node.canReadBytes && mailConnected) TextButton(onClick = onCopy, enabled = !copying) { Text("В Mail") }
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
