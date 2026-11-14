package com.mediahub.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.mediahub.mobile.mail.MailThumbnailLoader
import com.mediahub.mobile.mail.MediaFileKinds
import com.mediahub.mobile.mail.SecureMailAccountStore
import com.mediahub.mobile.mail.toCloudNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Self-contained screen: no unconstrained children in the parent Box. */
@Composable
internal fun MailPanel(
    account: MailAccount?,
    store: SecureMailAccountStore,
    onAccountChanged: (MailAccount?) -> Unit,
    driveConnected: Boolean,
    copying: Boolean,
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
    var selectedForDownload by remember { mutableStateOf<DavEntry?>(null) }
    var selectedPreview by remember { mutableStateOf<DavEntry?>(null) }
    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var previewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val client = remember(account) { account?.let { MailWebDavClient(it.email, it.password) } }
    val thumbnailLoader = remember(client) { client?.let(::MailThumbnailLoader) }

    fun newClient() = client ?: error("Mail не подключён.")

    fun browse(target: String) {
        if (busy) return
        scope.launch {
            busy = true
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

    LaunchedEffect(account?.email) {
        if (account != null && loadedPath == null) {
            browse(path)
        }
    }

    BackHandler(enabled = account != null && path != "/" && !busy && selectedPreview == null) {
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
                    output.use { newClient().download(file.path, it) }
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

    fun closePreview() {
        previewJob?.cancel()
        selectedPreview = null
        previewBitmap = null
    }

    fun preview(file: DavEntry) {
        if (busy) return
        previewJob?.cancel()
        selectedPreview = file
        previewBitmap = null
        previewError = null
        previewLoading = true
        previewJob = scope.launch {
            try {
                val bitmap = runInterruptible(Dispatchers.IO) {
                    val bytes = newClient().previewImage(file.path)
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    var sample = 1
                    while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) {
                        sample *= 2
                    }
                    val decoded = BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample }
                    ) ?: throw IllegalArgumentException("Не удалось прочитать изображение.")
                    decoded.asImageBitmap()
                }
                if (selectedPreview?.path == file.path) previewBitmap = bitmap
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (selectedPreview?.path == file.path) {
                    previewError = e.userMessage("Ошибка предпросмотра.")
                }
            } finally {
                if (selectedPreview?.path == file.path && currentCoroutineContext().isActive) {
                    previewLoading = false
                }
            }
        }
    }

    fun openFile(file: DavEntry) {
        when {
            file.isDirectory -> browse(file.path)
            MediaFileKinds.isImage(file.name) -> preview(file)
            else -> {
                selectedForDownload = file
                savePicker.launch(file.name)
            }
        }
    }

    LaunchedEffect(path, search, gridView) {
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }

    val filtered = remember(entries, search) {
        if (search.isBlank()) entries
        else entries.filter { it.name.contains(search.trim(), ignoreCase = true) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Mail", style = MaterialTheme.typography.titleLarge)

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
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                breadcrumbs.last().title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("Путь к папке", style = MaterialTheme.typography.labelSmall)
                        }
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
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                            Text(if (search.isEmpty()) "Папка пуста." else "Совпадений нет.")
                        }
                    }
                    items(filtered.size, key = { filtered[it].path }) { index ->
                        val file = filtered[index]
                        MailGridTile(
                            file = file,
                            loader = thumbnailLoader,
                            enabled = !busy,
                            copyEnabled = driveConnected && !copying && !file.isDirectory,
                            onCopy = { onCopy(newClient(), file.toCloudNode()) },
                            onOpen = { openFile(file) }
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
                    items(filtered, key = { it.path }) { file ->
                        val imageFile = !file.isDirectory && MediaFileKinds.isImage(file.name)
                        Card(
                            onClick = { openFile(file) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (file.isDirectory) "▣" else if (imageFile) "▧" else "▤")
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    file.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!file.isDirectory) {
                                    MailFileActions(
                                        enabled = !busy,
                                        copyEnabled = driveConnected && !copying,
                                        onDownload = { selectedForDownload = file; savePicker.launch(file.name) },
                                        onCopy = { onCopy(newClient(), file.toCloudNode()) }
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

    selectedPreview?.let { file ->
        AlertDialog(
            onDismissRequest = ::closePreview,
            title = { Text(file.name, maxLines = 2) },
            text = {
                if (previewLoading) CircularProgressIndicator()
                else previewBitmap?.let {
                    Image(
                        bitmap = it, contentDescription = "Предпросмотр фотографии",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        contentScale = ContentScale.Fit
                    )
                } ?: Text(previewError ?: "Изображение недоступно.")
            },
            confirmButton = {
                Row {
                    if (driveConnected && !copying) {
                        TextButton(onClick = { closePreview(); onCopy(newClient(), file.toCloudNode()) }) {
                            Text("В Drive")
                        }
                    }
                    TextButton(onClick = {
                        closePreview()
                        selectedForDownload = file
                        savePicker.launch(file.name)
                    }) { Text("Скачать") }
                }
            },
            dismissButton = { TextButton(onClick = ::closePreview) { Text("Закрыть") } }
        )
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

@Composable
private fun MailGridTile(
    file: DavEntry,
    loader: MailThumbnailLoader?,
    enabled: Boolean,
    copyEnabled: Boolean,
    onCopy: () -> Unit,
    onOpen: () -> Unit
) {
    val isImage = !file.isDirectory && MediaFileKinds.isImage(file.name)
    Card(onClick = onOpen, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                if (isImage) {
                    val thumb by produceState<Bitmap?>(
                        initialValue = null, key1 = file, key2 = loader
                    ) {
                        value = try {
                            loader?.load(file)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (thumb != null) {
                        Image(
                            bitmap = thumb!!.asImageBitmap(),
                            contentDescription = file.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("▧", style = MaterialTheme.typography.headlineLarge)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (file.isDirectory) "📁" else "📄",
                            style = MaterialTheme.typography.headlineLarge)
                        Text(if (file.isDirectory) "Папка" else "Файл",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = file.name,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                if (copyEnabled) {
                    IconButton(onClick = onCopy, enabled = enabled) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Копировать в Google Drive")
                    }
                }
            }
        }
    }
}
