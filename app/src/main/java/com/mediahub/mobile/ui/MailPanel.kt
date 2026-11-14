package com.mediahub.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.mediahub.mobile.mail.DavBreadcrumb
import com.mediahub.mobile.mail.DavEntry
import com.mediahub.mobile.mail.DavPath
import com.mediahub.mobile.mail.MailAccount
import com.mediahub.mobile.mail.MailWebDavClient
import com.mediahub.mobile.mail.MailThumbnailLoader
import com.mediahub.mobile.mail.MediaFileKinds
import com.mediahub.mobile.mail.SecureMailAccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Self-contained screen: no unconstrained children in the parent Box. */
@Composable
fun MailPanel() {
    val context = LocalContext.current
    val store = remember(context) { SecureMailAccountStore(context) }
    var account by remember { mutableStateOf(store.load()) }
    var email by remember { mutableStateOf(account?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<DavEntry>>(emptyList()) }
    var loadedPath by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var gridView by rememberSaveable { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf("") }
    var createFolderDialog by remember { mutableStateOf(false) }
    var selectedForDownload by remember { mutableStateOf<DavEntry?>(null) }
    var selectedPreview by remember { mutableStateOf<DavEntry?>(null) }
    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val thumbnailLoader = remember(account) { account?.let { MailThumbnailLoader(MailWebDavClient(it.email, it.password)) } }

    fun newClient(): MailWebDavClient {
        val saved = account ?: throw IllegalStateException("Mail не подключён.")
        return MailWebDavClient(saved.email, saved.password)
    }

    fun browse(target: String) {
        if (busy) return
        scope.launch {
            busy = true
            message = null
            try {
                val result = withContext(Dispatchers.IO) { newClient().list(target) }
                path = target
                entries = result
                loadedPath = target
                search = ""
            } catch (e: Exception) {
                message = e.message ?: "Не удалось открыть папку."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(account?.email) {
        if (account != null && loadedPath == null) {
            browse("/")
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
            } catch (e: Exception) {
                message = e.message ?: "Не удалось загрузить файл."
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
            } catch (e: Exception) {
                message = e.message ?: "Не удалось скачать файл."
            } finally {
                busy = false
            }
        }
    }

    fun preview(file: DavEntry) {
        if (busy) return
        selectedPreview = file
        previewBitmap = null
        previewLoading = true
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
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
            } catch (e: Exception) {
                message = e.message ?: "Ошибка предпросмотра."
                selectedPreview = null
            } finally {
                previewLoading = false
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

    val filtered = remember(entries, search) {
        if (search.isBlank()) entries
        else entries.filter { it.name.contains(search.trim(), ignoreCase = true) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Mail", style = MaterialTheme.typography.headlineMedium)

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
                                account = credentials
                            } catch (e: Exception) {
                                message = e.message ?: "Не удалось подключиться."
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
            Text(
                "Подключено: " + account?.email.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DavPath.breadcrumbs(path).forEachIndexed { index, crumb ->
                    if (index != 0) Text(" / ")
                    TextButton(
                        enabled = !busy && crumb.path != path,
                        onClick = { browse(crumb.path) }
                    ) { Text(crumb.title, maxLines = 1) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = !busy && path != "/",
                    onClick = { browse(DavPath.parent(path)) },
                    modifier = Modifier.weight(1f)
                ) { Text("Назад") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { browse(path) },
                    modifier = Modifier.weight(1f)
                ) { Text("Обновить") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = !busy, onClick = { createFolderDialog = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Новая папка") }
                OutlinedButton(
                    enabled = !busy, onClick = { uploadPicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) { Text("Загрузить") }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Поиск в папке") }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Объектов: " + filtered.size,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                TextButton(onClick = {
                    store.clear()
                    account = null
                    path = "/"
                    entries = emptyList()
                    password = ""
                    loadedPath = null
                }) { Text("Отключить") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !gridView, onClick = { gridView = false },
                    label = { Text("Список") }
                )
                FilterChip(
                    selected = gridView, onClick = { gridView = true },
                    label = { Text("Плитка") }
                )
            }
            if (gridView) {
                // Grid is lazy: preview requests start only for currently composed tiles.
                // Do not run a gallery-wide background download.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    if (filtered.isEmpty() && !busy) {
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
                            onOpen = { openFile(file) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    if (filtered.isEmpty() && !busy) {
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
                                if (imageFile) TextButton(onClick = {
                                    selectedForDownload = file
                                    savePicker.launch(file.name)
                                }) { Text("Скачать") }
                            }
                        }
                    }
                }
            }
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (selectedPreview != null) AlertDialog(
        onDismissRequest = { selectedPreview = null; previewBitmap = null },
        title = { Text(selectedPreview?.name.orEmpty(), maxLines = 2) },
        text = {
            if (previewLoading) {
                CircularProgressIndicator()
            } else {
                previewBitmap?.let {
                    Image(
                        bitmap = it, contentDescription = "Предпросмотр фотографии",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        contentScale = ContentScale.Fit
                    )
                } ?: Text("Изображение недоступно.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val file = selectedPreview
                selectedPreview = null
                previewBitmap = null
                if (file != null) {
                    selectedForDownload = file
                    savePicker.launch(file.name)
                }
            }) { Text("Скачать") }
        },
        dismissButton = {
            TextButton(onClick = {
                selectedPreview = null
                previewBitmap = null
            }) { Text("Закрыть") }
        }
    )

    if (createFolderDialog) AlertDialog(
        onDismissRequest = { createFolderDialog = false },
        title = { Text("Новая папка") },
        text = {
            OutlinedTextField(
                value = newFolder, onValueChange = { newFolder = it },
                singleLine = true, label = { Text("Название") }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val name = newFolder.trim()
                newFolder = ""
                createFolderDialog = false
                if (name.isNotEmpty() && name != "." && name != "..") scope.launch {
                    busy = true
                    message = null
                    try {
                        withContext(Dispatchers.IO) {
                            newClient().createFolder(path, name)
                        }
                        busy = false
                        browse(path)
                    } catch (e: Exception) {
                        message = e.message ?: "Не удалось создать папку."
                        busy = false
                    }
                }
            }) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = { createFolderDialog = false }) { Text("Отмена") }
        }
    )
}


@Composable
private fun MailGridTile(
    file: DavEntry,
    loader: MailThumbnailLoader?,
    enabled: Boolean,
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
                        initialValue = null, key1 = file.path, key2 = loader
                    ) {
                        value = try {
                            loader?.load(file.path)
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
                    Text(if (file.isDirectory) "▣" else "▤",
                        style = MaterialTheme.typography.headlineLarge)
                }
            }
            Text(
                text = file.name,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
