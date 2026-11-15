package com.mediahub.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mediahub.mobile.catalog.CatalogProfile
import com.mediahub.mobile.catalog.CatalogViewModel
import com.mediahub.mobile.catalog.ProfileFolder
import com.mediahub.mobile.catalog.ProfileFolderAccess
import com.mediahub.mobile.catalog.ProfileStorageKind
import com.mediahub.mobile.catalog.ProfileRow
import com.mediahub.mobile.catalog.TaxonomyTerm
import com.mediahub.mobile.catalog.isCatalogWebUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CatalogProfilePanel(
    model: CatalogViewModel,
    profileId: String,
    storage: List<ProfileFolderAccess>,
    onConnect: (ProfileStorageKind) -> Unit,
    onOpenFolder: (ProfileFolder) -> Unit,
    onBack: () -> Unit,
    onTerm: (TaxonomyTerm) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by model.revision.collectAsState()
    var profile by remember(profileId) { mutableStateOf<CatalogProfile?>(null) }
    var loading by remember(profileId) { mutableStateOf(true) }
    var error by remember(profileId) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var editing by rememberSaveable(profileId) { mutableStateOf(false) }
    var choosingFolder by rememberSaveable(profileId) { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ProfileFolder?>(null) }
    var removingPending by remember { mutableStateOf(false) }

    LaunchedEffect(profileId, revision, attempt) {
        try {
            profile = model.profileDetails(profileId)
            error = if (profile == null) "Профиль недоступен." else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "Не удалось открыть профиль."
        } finally {
            loading = false
        }
    }

    BackHandler(onBack = onBack)
    val current = profile
    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К списку профилей")
            }
            Text(
                current?.row?.title ?: "Профиль",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (current != null) TextButton(onClick = { editing = true }) { Text("Своё имя") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (current == null) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else TextButton(onClick = { error = null; loading = true; attempt++ }) { Text("Повторить") }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Папки профиля", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { choosingFolder = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Привязать папку")
                }
                if (current.folders.isEmpty()) {
                    Text("Папок пока нет.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(
                current.folders,
                key = { "folder:${it.storage}:${it.scopeKey}:${it.folderId}" }
            ) { folder ->
                val available = storage.any { it.resolve(folder) != null }
                ProfileFolderRow(
                    folder, available,
                    onOpen = { onOpenFolder(folder) },
                    onRemove = { removing = folder }
                )
            }
            item {
                val row = current.row
                InfoCard("Источник", buildString {
                    append(row.sourceName)
                    row.sourceSnapshot?.let { append("\nСнимок от ").append(it) }
                    if (row.localName != null && row.archiveName != null) append("\nВ архиве — ").append(row.archiveName)
                    row.aliases?.takeIf(String::isNotBlank)?.let { append("\nПсевдонимы — ").append(it) }
                    append("\n").append(row.sourceUrl)
                })
                TextButton(enabled = isCatalogWebUrl(row.sourceUrl), onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, row.sourceUrl.toUri()))
                    } catch (_: ActivityNotFoundException) {
                        error = "Не найден браузер для открытия ссылки."
                    }
                }) { Text("Открыть источник") }
            }
            if (current.terms.isNotEmpty()) {
                item { Text("Категории и теги", style = MaterialTheme.typography.titleMedium) }
                items(current.terms, key = { "term:${it.id}" }) { term ->
                    TextButton(onClick = { onTerm(term) }, modifier = Modifier.fillMaxWidth()) {
                        Text(term.labelRu, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    if (editing && current != null) {
        ProfileNameDialog(model, current.row, onDismiss = { editing = false })
    }

    if (choosingFolder && current != null) {
        ProfileFolderDialog(
            model, profileId, storage,
            onConnect = { choosingFolder = false; onConnect(it) },
            onDismiss = { choosingFolder = false }
        )
    }

    removing?.let { folder ->
        AlertDialog(
            onDismissRequest = { if (!removingPending) removing = null },
            title = { Text("Убрать привязку?") },
            text = { Text("Папка «${folder.title}» исчезнет из карточки. Файлы останутся в хранилище.") },
            confirmButton = {
                TextButton(enabled = !removingPending, onClick = {
                    removingPending = true
                    scope.launch {
                        try {
                            model.removeFolder(folder)
                            error = null
                            removing = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = "Не удалось убрать привязку."
                            removing = null
                        } finally {
                            removingPending = false
                        }
                    }
                }) { Text("Отвязать папку") }
            },
            dismissButton = {
                TextButton(enabled = !removingPending, onClick = { removing = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun ProfileFolderRow(
    folder: ProfileFolder,
    available: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(folder.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(folder.kind?.title ?: "Хранилище", style = MaterialTheme.typography.labelMedium)
                Text(remember(folder) { folder.displayPath() }, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (available) TextButton(onClick = onOpen) { Text("Открыть папку") }
                else Text("Требуется исходное подключение", style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Действия с привязкой")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Отвязать папку") },
                        onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

@Composable
private fun ProfileNameDialog(model: CatalogViewModel, row: ProfileRow, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var value by rememberSaveable { mutableStateOf(row.localName ?: row.archiveName.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    fun save(name: String?) {
        saving = true
        error = false
        scope.launch {
            try {
                model.saveName(row.id, name)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = true
            } finally {
                saving = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Имя в медиатеке") },
        text = {
            Column {
                OutlinedTextField(value, { if (it.length <= 120) value = it },
                    singleLine = true, label = { Text("Имя") }, enabled = !saving)
                if (row.localName != null) {
                    TextButton(onClick = { save(null) }, enabled = !saving) { Text("Вернуть имя из архива") }
                }
                if (error) Text("Не удалось сохранить имя.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { save(value) }, enabled = !saving && value.isNotBlank()) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Отмена") } }
    )
}
