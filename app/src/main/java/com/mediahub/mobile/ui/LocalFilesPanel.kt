package com.mediahub.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LocalFilesPanel() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mediahub_public_settings", 0) }
    var rootUri by remember { mutableStateOf(prefs.getString("saf_root", null)) }
    val folders = remember { mutableStateListOf<DocumentFile>() }
    var documents by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFolderName by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { selected ->
        if (selected != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    selected,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit().putString("saf_root", selected.toString()).apply()
                rootUri = selected.toString()
                error = null
            } catch (_: SecurityException) {
                error = "Не удалось сохранить доступ к папке."
            }
        }
    }
    LaunchedEffect(rootUri) {
        folders.clear()
        documents = emptyList()
        rootUri?.let { value ->
            try {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(value))
                if (root?.isDirectory == true) folders.add(root)
                else error = "Папка недоступна. Выберите её заново."
            } catch (_: Exception) {
                error = "Не удалось открыть ранее выбранную папку."
            }
        }
    }
    val current = folders.lastOrNull()
    LaunchedEffect(current?.uri, refreshKey) {
        documents = emptyList()
        if (current != null) {
            loading = true
            try {
                documents = withContext(Dispatchers.IO) {
                    current.listFiles().sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() })
                }
                error = null
            } catch (_: Exception) {
                error = "Нет доступа к содержимому папки."
            } finally { loading = false }
        }
    }
    Button(onClick = { launcher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (rootUri == null) "Выбрать папку на устройстве" else "Сменить папку")
    }
    if (current == null) {
        InfoCard("Локальное хранилище", "Выберите папку через системный диалог Android. Приложение не получит доступ к остальным папкам.")
    } else {
        Text(current.name ?: "Выбранная папка", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (folders.size > 1) folders.removeAt(folders.lastIndex) }, enabled = folders.size > 1) {
                Text("Назад")
            }
            OutlinedButton(onClick = { refreshKey++ }) { Text("Обновить") }
            Button(onClick = { showCreateDialog = true }) { Text("Новая папка") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && documents.isEmpty()) Text("Папка пуста.")
        documents.forEach { doc ->
            val name = doc.name ?: "(без названия)"
            OutlinedButton(
                onClick = {
                    if (doc.isDirectory) folders.add(doc)
                    else {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(doc.uri, doc.type ?: "*/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            error = "Для этого файла не найдено приложение просмотра."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text((if (doc.isDirectory) "▣ " else "◻ ") + name, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (showCreateDialog && current != null) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Новая папка") },
            text = {
                OutlinedTextField(newFolderName, onValueChange = { newFolderName = it }, singleLine = true, label = { Text("Название") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newFolderName.trim()
                    showCreateDialog = false
                    newFolderName = ""
                    if (name.isNotEmpty() && !name.contains('/')) {
                        scope.launch {
                            val created = withContext(Dispatchers.IO) { current.createDirectory(name) }
                            if (created == null) error = "Не удалось создать папку."
                            else refreshKey++
                        }
                    } else error = "Укажите корректное название."
                }) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Отмена") }
            }
        )
    }
}
