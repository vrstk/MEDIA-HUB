package com.mediahub.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.mediahub.mobile.mail.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MailPanel() {
    val context = LocalContext.current
    val store = remember { SecureMailAccountStore(context) }
    var saved by remember { mutableStateOf(store.load()) }
    var email by remember { mutableStateOf(saved?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<DavEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<DavEntry?>(null) }
    val scope = rememberCoroutineScope()

    fun client(): MailWebDavClient {
        val account = saved ?: throw IllegalStateException("Не подключено.")
        return MailWebDavClient(account.email, account.password)
    }

    fun refresh() {
        scope.launch {
            busy = true
            error = null
            try {
                entries = withContext(Dispatchers.IO) { client().list(path) }
            } catch (e: Exception) { error = e.message ?: "Ошибка подключения." }
            finally { busy = false }
        }
    }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            error = null
            try {
                withContext(Dispatchers.IO) {
                    val file = DocumentFile.fromSingleUri(context, uri)
                        ?: throw IllegalArgumentException("Файл недоступен.")
                    val name = file.name ?: throw IllegalArgumentException("Имя файла неизвестно.")
                    client().upload(path, name, file.length().takeIf { it >= 0 },
                        file.type) {
                        context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Нет доступа к файлу.")
                    }
                }
                refresh()
            } catch (e: Exception) { error = e.message ?: "Ошибка загрузки." }
            finally { busy = false }
        }
    }

    val download = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val file = selected
        selected = null
        if (uri != null && file != null) scope.launch {
            busy = true
            error = null
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "w")
                        ?: throw IllegalStateException("Нет доступа для записи.")
                    output.use { client().download(file.path, it) }
                }
            } catch (e: Exception) { error = e.message ?: "Ошибка скачивания." }
            finally { busy = false }
        }
    }

    if (saved == null) {
        InfoCard("Облако Mail", "Для подключения используется пароль внешнего приложения Mail, а не обычный пароль от почты. HTTPS обязателен.")
        OutlinedTextField(value = email, onValueChange = { email = it },
            label = { Text("Адрес почты") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it },
            label = { Text("Пароль внешнего приложения") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = email.contains("@") && password.isNotBlank() && !busy,
            onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val account = MailAccount(email.trim(), password)
                        val listing = withContext(Dispatchers.IO) {
                            MailWebDavClient(account.email, account.password).list("/")
                        }
                        store.save(account.email, account.password)
                        saved = account
                        password = ""
                        path = "/"
                        entries = listing
                    } catch (e: Exception) { error = e.message ?: "Ошибка входа." }
                    finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth()
        ) { Text("Проверить и подключить") }
    } else {
        Text("Mail: " + saved?.email, style = MaterialTheme.typography.titleMedium)
        Text("Папка: " + path)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { path = DavPath.parent(path); refresh() },
                enabled = path != "/" && !busy) { Text("Назад") }
            OutlinedButton(onClick = { refresh() }, enabled = !busy) { Text("Обновить") }
            Button(onClick = { showCreate = true }, enabled = !busy) { Text("Новая папка") }
        }
        OutlinedButton(onClick = { upload.launch(arrayOf("*/*")) }, enabled = !busy) {
            Text("Загрузить файл в Mail")
        }
        if (entries.isEmpty() && !busy) Text("Файлов нет.")
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries, key = { it.path }) { item ->
                OutlinedButton(onClick = {
                    if (item.isDirectory) { path = item.path; refresh() }
                    else { selected = item; download.launch(item.name) }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text((if (item.isDirectory) "▣ " else "◻ ") + item.name,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
        TextButton(onClick = {
            store.clear()
            saved = null
            entries = emptyList()
            path = "/"
            password = ""
        }) { Text("Отключить Mail на устройстве") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (showCreate) AlertDialog(
        onDismissRequest = { showCreate = false },
        title = { Text("Новая папка") },
        text = { OutlinedTextField(value = newFolder, onValueChange = { newFolder = it },
            label = { Text("Название") }) },
        confirmButton = {
            TextButton(onClick = {
                val name = newFolder.trim()
                showCreate = false
                newFolder = ""
                if (name.isNotEmpty()) scope.launch {
                    busy = true
                    error = null
                    try {
                        withContext(Dispatchers.IO) { client().createFolder(path, name) }
                        refresh()
                    } catch (e: Exception) { error = e.message ?: "Ошибка." }
                    finally { busy = false }
                }
            }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Отмена") } }
    )
}