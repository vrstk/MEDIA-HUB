package com.mediahub.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.mail.DavEntry
import com.mediahub.mobile.mail.DavPath
import com.mediahub.mobile.mail.MailAccount
import com.mediahub.mobile.mail.MailWebDavClient
import com.mediahub.mobile.mail.SecureMailAccountStore
import com.mediahub.mobile.mail.toCloudNode
import com.mediahub.mobile.media.MediaFileKinds
import com.mediahub.mobile.media.PhotoPreviewLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Self-contained screen: no unconstrained children in the parent Box. */
@Composable
internal fun MailPanel(
    account: MailAccount?,
    store: SecureMailAccountStore,
    onAccountChanged: (MailAccount?) -> Unit,
    driveConnected: Boolean,
    copying: Boolean,
    requestedFolder: String?,
    onFolderRequestHandled: () -> Unit,
    onCopy: (MailWebDavClient, CloudNode) -> Unit
) {
    val context = LocalContext.current
    var email by remember { mutableStateOf(account?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<DavEntry>>(emptyList()) }
    var loadedPath by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var gridView by rememberSaveable { mutableStateOf(false) }
    var toolsExpanded by remember { mutableStateOf(false) }
    var pathExpanded by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var disconnectDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var createFolderDialog by remember { mutableStateOf(false) }
    var selectedForDownload by remember { mutableStateOf<CloudNode?>(null) }
    var selectedPhotoId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val client = remember(account) { account?.let { MailWebDavClient(it.email, it.password) } }
    fun newClient() = client ?: error("Mail не подключён.")
    val photoLoader = remember(client, entries) {
        PhotoPreviewLoader { file, maxBytes -> newClient().previewImage(file.id, maxBytes) }
    }

    fun browse(target: String) {
        if (busy) return
        busy = true
        scope.launch {
            message = null
            try {
                val result = withContext(Dispatchers.IO) { newClient().list(target) }
                if (path != target) search = ""
                path = target
                entries = result
                loadedPath = target
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.userMessage("Не удалось открыть папку.")
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(account?.email, requestedFolder) {
        if (account == null) return@LaunchedEffect
        if (requestedFolder != null) {
            path = requestedFolder
            entries = emptyList()
            loadedPath = null
            search = ""
            searchOpen = false
            browse(requestedFolder)
            onFolderRequestHandled()
        } else if (loadedPath == null) {
            browse(path)
        }
    }

    BackHandler(enabled = account != null && path != "/" && !busy && selectedPhotoId == null) {
        browse(DavPath.parent(path))
    }

    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = null
            try {
                withContext(Dispatchers.IO) {
                    val doc = DocumentFile.fromSingleUri(context, uri)
                        ?: throw IllegalArgumentException("Файл недоступен.")
                    val fileName = doc.name
                        ?: throw IllegalArgumentException("Не удалось получить имя файла.")
                    newClient().upload(path, fileName, doc.length().takeIf { it > 0L }, doc.type) {
                        context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Нет доступа к файлу.")
                    }
                }
                // Call browse after busy reset to avoid ignored refresh.
                busy = false
                browse(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.userMessage("Не удалось загрузить файл.")
                busy = false
            }
        }
    }

    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val file = selectedForDownload
        selectedForDownload = null
        if (uri != null && file != null) scope.launch {
            busy = true
            message = null
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "w")
                        ?: throw IllegalStateException("Нет доступа для сохранения.")
                    output.use { newClient().download(file.id, it) }
                }
                message = "Файл сохранён."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.userMessage("Не удалось скачать файл.")
            } finally {
                busy = false
            }
        }
    }

    fun download(file: CloudNode) {
        selectedForDownload = file
        savePicker.launch(file.name)
    }

    fun openFile(file: CloudNode) {
        when {
            file.isFolder -> browse(file.id)
            MediaFileKinds.isPhoto(file) -> selectedPhotoId = file.id
            else -> download(file)
        }
    }

    LaunchedEffect(path, search, gridView) {
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }

    val filtered = remember(entries, search) {
        val query = search.trim()
        filterAndSortCloudNodes(entries.map(DavEntry::toCloudNode), query)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (account == null) Text("Mail", style = MaterialTheme.typography.titleLarge)

        if (account == null) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                InfoCard(
                    "Облако Mail",
                    "Для подключения используется пароль внешнего приложения Mail. Данные передаются через HTTPS."
                )
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Адрес почты") }
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Пароль внешнего приложения") }
                )
                Button(
                    enabled = email.contains("@") && password.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            busy = true
                            message = null
                            try {
                                val credentials = MailAccount(email.trim(), password)
                                val list = withContext(Dispatchers.IO) {
                                    MailWebDavClient(credentials.email, credentials.password).list("/")
                                }
                                store.save(credentials.email, credentials.password)
                                path = "/"
                                entries = list
                                loadedPath = "/"
                                password = ""
                                onAccountChanged(credentials)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = e.userMessage("Не удалось подключиться.")
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Проверить и подключить") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    val breadcrumbs = remember(path) { DavPath.breadcrumbs(path) }
                    TextButton(
                        onClick = { pathExpanded = true },
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                    ) {
                        Text(
                            breadcrumbs.last().title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(expanded = pathExpanded, onDismissRequest = { pathExpanded = false }) {
                        breadcrumbs.asReversed().forEach { crumb ->
                            DropdownMenuItem(
                                text = { Text(crumb.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                enabled = crumb.path != path,
                                onClick = { pathExpanded = false; browse(crumb.path) }
                            )
                        }
                    }
                }
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) search = "" }) {
                    Icon(Icons.Default.Search, contentDescription = "Поиск")
                }
                Box {
                    IconButton(onClick = { toolsExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Действия")
                    }
                    DropdownMenu(expanded = toolsExpanded, onDismissRequest = { toolsExpanded = false }) {
                        DropdownMenuItem(text = { Text("Новая папка") }, enabled = !busy,
                            onClick = { toolsExpanded = false; createFolderDialog = true })
                        DropdownMenuItem(text = { Text("Загрузить файл") }, enabled = !busy,
                            onClick = { toolsExpanded = false; uploadPicker.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text("Отключить Mail") }, enabled = !busy && !copying,
                            onClick = { toolsExpanded = false; disconnectDialog = true })
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (path != "/") {
                    TextButton(
                        enabled = !busy, onClick = { browse(DavPath.parent(path)) },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) { Text("Назад") }
                }
                Text(
                    if (search.isBlank()) filtered.size.toString() else "${filtered.size}/${entries.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                IconButton(enabled = !busy, onClick = { browse(path) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                }
                FilterChip(selected = !gridView, onClick = { gridView = false }, label = { Text("Список") })
                FilterChip(selected = gridView, onClick = { gridView = true }, label = { Text("Плитка") })
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = search, onValueChange = { search = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Поиск в текущей папке") }
                )
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (gridView) {
                // Grid is lazy: preview requests start only for currently composed tiles.
                // Do not run a gallery-wide background download.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    if (filtered.isEmpty() && !busy && loadedPath == path) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(if (search.isEmpty()) "Папка пуста." else "Совпадений нет.")
                        }
                    }
                    items(filtered.size, key = { filtered[it].id }) { index ->
                        val file = filtered[index]
                        StorageGridTile(
                            file = file,
                            loader = photoLoader,
                            onOpen = if (busy) null else ({ openFile(file) }),
                            onCopy = if (busy || !driveConnected || copying) null else ({ onCopy(newClient(), file) }),
                            copyDescription = "Копировать в Google Drive",
                            copyLabel = "В Drive"
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    if (filtered.isEmpty() && !busy && loadedPath == path) {
                        item { Text(if (search.isEmpty()) "Папка пуста." else "Совпадений нет.") }
                    }
                    items(filtered, key = { it.id }) { file ->
                        val imageFile = MediaFileKinds.isPhoto(file)
                        Card(
                            onClick = { openFile(file) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (file.isFolder) "▣" else if (imageFile) "▧" else "▤")
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    file.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (file.isFolder) {
                                    if (driveConnected) TextButton(
                                        enabled = !busy && !copying,
                                        onClick = { onCopy(newClient(), file) }
                                    ) { Text("В Drive") }
                                } else {
                                    MailFileActions(
                                        enabled = !busy,
                                        copyEnabled = driveConnected && !copying,
                                        onDownload = { download(file) },
                                        onCopy = { onCopy(newClient(), file) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            message?.let {
                Text(it, color = if (it == "Файл сохранён.") MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.error)
            }
        }
    }

    selectedPhotoId?.let { id ->
        val photos = remember(filtered) { filtered.filter(MediaFileKinds::isPhoto) }
        PhotoViewer(photos, id, photoLoader, onClose = { selectedPhotoId = null }) { file ->
            if (driveConnected && !copying) {
                TextButton(onClick = {
                    selectedPhotoId = null
                    onCopy(newClient(), file)
                }) { Text("В Drive") }
            }
            TextButton(onClick = { selectedPhotoId = null; download(file) }) { Text("Скачать") }
        }
    }

    if (createFolderDialog) {
        CloudFolderNameDialog(onDismiss = { createFolderDialog = false }) { name ->
            createFolderDialog = false
            scope.launch {
                busy = true
                message = null
                try {
                    withContext(Dispatchers.IO) { newClient().createFolder(path, name) }
                    busy = false
                    browse(path)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = e.userMessage("Не удалось создать папку.")
                    busy = false
                }
            }
        }
    }
    if (disconnectDialog) {
        AlertDialog(
            onDismissRequest = { disconnectDialog = false },
            title = { Text("Отключить Mail на устройстве?") },
            text = { Text("Для нового подключения понадобится пароль внешнего приложения.") },
            confirmButton = {
                TextButton(onClick = {
                    disconnectDialog = false
                    store.clear()
                    path = "/"
                    entries = emptyList()
                    search = ""
                    password = ""
                    loadedPath = null
                    onAccountChanged(null)
                }) { Text("Отключить") }
            },
            dismissButton = { TextButton(onClick = { disconnectDialog = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun MailFileActions(enabled: Boolean, copyEnabled: Boolean, onDownload: () -> Unit, onCopy: () -> Unit) {
    if (!copyEnabled) {
        TextButton(onClick = onDownload, enabled = enabled) { Text("Скачать") }
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Default.MoreVert, contentDescription = "Действия с файлом")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Скачать") }, onClick = { expanded = false; onDownload() })
            DropdownMenuItem(text = { Text("Копировать в Google Drive") },
                onClick = { expanded = false; onCopy() })
        }
    }
}
