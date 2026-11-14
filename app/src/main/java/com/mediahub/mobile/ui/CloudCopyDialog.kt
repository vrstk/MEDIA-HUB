package com.mediahub.mobile.ui

import android.text.format.Formatter
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CopyPhase
import kotlinx.coroutines.launch

@Composable
internal fun CloudCopyDialog(
    selection: CopySelection,
    onDismiss: () -> Unit,
    onExpired: () -> Unit,
    onCopy: (CloudNode) -> Unit
) {
    val context = LocalContext.current
    val path = remember(selection) { mutableStateListOf(selection.target.root) }
    val folder = path.last()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val state = rememberFolderContents(selection.target, folder, refresh, onExpired)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Куда скопировать?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${selection.site.label} → ${selection.site.other.label}")
                Text(selection.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                selection.file.sizeBytes?.let { Text(Formatter.formatFileSize(context, it)) }
                Text(path.joinToString(" / ") { it.name }, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row {
                    if (path.size > 1) {
                        TextButton(onClick = { path.removeAt(path.lastIndex) }) { Text("Назад") }
                    }
                    if (state.error != null) {
                        TextButton(onClick = { refresh++ }) { Text("Повторить") }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 184.dp)) {
                    items(state.nodes.filter { it.isFolder }, key = { it.id }) { child ->
                        TextButton(onClick = { path.add(child) }, modifier = Modifier.fillMaxWidth()) {
                            Text(child.name, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (state.nextPage != null) {
                        item {
                            TextButton(
                                onClick = { scope.launch { state.load(selection.target, folder, more = true, onExpired) } },
                                enabled = !state.loading
                            ) { Text("Показать ещё") }
                        }
                    }
                }
                Text("При совпадении имени копирование остановится.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy(folder) }, enabled = !state.loading && state.error == null) {
                Text("Копировать сюда")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
internal fun CloudCopyTask(state: CopyState?, onCancel: () -> Unit) {
    val context = LocalContext.current
    when (state) {
        null -> InfoCard("Передачи", "Выберите файл в Mail или Google Drive, чтобы начать копирование.")
        is CopyState.Running -> {
            val progress = state.progress
            Text(state.name, style = MaterialTheme.typography.titleMedium)
            Text(when {
                state.cancelling -> "Остановка…"
                progress?.phase == CopyPhase.VERIFYING -> "Проверка копии"
                progress != null -> "Копирование"
                else -> "Подготовка передачи"
            })
            if (progress == null || progress.total == 0L) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { (progress.done.toDouble() / progress.total).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("${Formatter.formatFileSize(context, progress.done)} из ${Formatter.formatFileSize(context, progress.total)}")
            }
            TextButton(onClick = onCancel, enabled = !state.cancelling) { Text("Отменить") }
        }
        is CopyState.Verified -> InfoCard(state.name,
            "Содержимое копии проверено по SHA-256 · ${Formatter.formatFileSize(context, state.size)}. Для папок через Android синхронизацию с Google Drive проверьте отдельно.")
        is CopyState.Failed -> InfoCard(state.name, state.message)
        is CopyState.Cancelled -> InfoCard(state.name, "Передача остановлена. Проверьте папку назначения перед повтором.")
    }
}
