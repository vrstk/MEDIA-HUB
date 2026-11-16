package com.mediahub.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CloudProvider
import kotlinx.coroutines.launch

/**
 * An overview of root-level folders from connected accounts.
 * Never recursively crawls the library or copies files; individual sources remain authoritative.
 */
@Composable
internal fun CombinedCloudAlbumsOverview(
    mail: CloudProvider?,
    drive: CloudProvider?,
    mailSession: AlbumThumbnailSession?,
    driveSession: AlbumThumbnailSession?,
    loadCovers: Boolean,
    compact: Boolean,
    descending: Boolean,
    mailScope: String,
    driveScope: String,
    bookmarks: List<AlbumBookmark>,
    chosenCovers: List<AlbumCover>,
    onToggleBookmark: (AlbumBookmark) -> Unit,
    onRenameBookmark: (AlbumBookmark, String?) -> Unit,
    onRemoveSelectedBookmarks: (Set<FavoriteSelectionKey>) -> Unit,
    onOpenAlbum: (Int, List<CloudNode>) -> Unit,
    onOpenMail: () -> Unit,
    onOpenDrive: () -> Unit,
    onDriveExpired: () -> Unit
) {
    var search by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AlbumBookmark?>(null) }
    var editedName by rememberSaveable { mutableStateOf("") }
    var selectionMode by remember(favoritesOnly, mailScope, driveScope,
        mail?.root?.id, drive?.root?.id, search) { mutableStateOf(false) }
    var selectedKeys by remember(favoritesOnly, mailScope, driveScope,
        mail?.root?.id, drive?.root?.id, search) {
        mutableStateOf<Set<FavoriteSelectionKey>>(emptySet())
    }
    var confirmBulkRemoval by remember(favoritesOnly, mailScope, driveScope,
        mail?.root?.id, drive?.root?.id, search) { mutableStateOf(false) }
    var mailRefresh by remember { mutableIntStateOf(0) }
    var driveRefresh by remember { mutableIntStateOf(0) }
    // Favorites are local metadata. Do not enumerate cloud roots in this mode.
    val mailContents = if (shouldListCombinedRoot(favoritesOnly, mail != null)) {
        mail?.let { rememberFolderContents(it, it.root, mailRefresh, {}) }
    } else null
    val driveContents = if (shouldListCombinedRoot(favoritesOnly, drive != null)) {
        drive?.let { rememberFolderContents(it, it.root, driveRefresh, onDriveExpired) }
    } else null
    val scope = rememberCoroutineScope()
    val state = rememberLazyGridState()
    LaunchedEffect(search, compact, descending, favoritesOnly) { state.scrollToItem(0) }
    val all = remember(mailContents?.nodes, driveContents?.nodes, search, descending) {
        mergeRootAlbums(
            mailContents?.nodes.orEmpty(), driveContents?.nodes.orEmpty(),
            search, descending = descending
        )
    }
    val allFavorites = remember(bookmarks, mailScope, driveScope, mail?.root?.id, drive?.root?.id) {
        visibleFavoriteAlbums(bookmarks, mailScope, mail?.root?.id,
            driveScope, drive?.root?.id, "")
    }
    val favorites = remember(bookmarks, mailScope, driveScope, mail?.root?.id,
        drive?.root?.id, search, descending) {
        visibleFavoriteAlbums(bookmarks, mailScope, mail?.root?.id,
            driveScope, drive?.root?.id, search, descending)
    }
    // Never act on hidden bookmarks or on entries from disconnected accounts.
    val visibleKeys = remember(favorites) {
        favorites.map { favoriteSelectionKey(it.bookmark) }.toSet()
    }
    val selectedVisible = selectedKeys.intersect(visibleKeys)
    fun toggleSelection(key: FavoriteSelectionKey) {
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (mail == null) TextButton(onClick = onOpenMail) { Text("Подключить Mail") }
        if (drive == null) TextButton(onClick = onOpenDrive) { Text("Подключить Google Drive") }
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !favoritesOnly,
                onClick = { favoritesOnly = false }, label = { Text("Все папки") })
            FilterChip(selected = favoritesOnly,
                onClick = { favoritesOnly = true },
                label = { Text("Избранное (${allFavorites.size})") })
        }
        if (favoritesOnly) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextButton(onClick = {
                    selectionMode = !selectionMode
                    selectedKeys = emptySet()
                    confirmBulkRemoval = false
                }) { Text(if (selectionMode) "Готово" else "Выбрать") }
                if (selectionMode) {
                    TextButton(
                        onClick = { selectedKeys = visibleKeys },
                        enabled = visibleKeys.isNotEmpty()
                    ) { Text("Выбрать все найденные") }
                    TextButton(
                        onClick = { selectedKeys = emptySet() },
                        enabled = selectedVisible.isNotEmpty()
                    ) { Text("Снять выбор") }
                    TextButton(
                        onClick = { confirmBulkRemoval = true },
                        enabled = selectedVisible.isNotEmpty()
                    ) { Text("Убрать из избранного (${selectedVisible.size})") }
                }
            }
        }
        OutlinedTextField(
            value = search, onValueChange = { search = it.take(160) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (favoritesOnly) "Поиск по избранному" else
                "Поиск по папкам") },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    TextButton(onClick = { search = "" }) { Text("Очистить") }
                }
            }
        )
        val mailCount = mailContents?.nodes?.count { it.isFolder } ?: 0
        val driveCount = driveContents?.nodes?.count { it.isFolder } ?: 0
        Text(
            if (favoritesOnly) {
                if (selectionMode) "Выбрано: ${selectedVisible.size} из ${favorites.size}"
                else "Избранное: ${allFavorites.size} · Найдено: ${favorites.size}"
            } else "Mail: $mailCount · Drive: $driveCount" +
                if (mailContents?.nextPage != null || driveContents?.nextPage != null)
                    " · Есть ещё страницы" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (mailContents?.loading == true || driveContents?.loading == true) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        mailContents?.error?.let { Text("Mail: $it", color = MaterialTheme.colorScheme.error) }
        driveContents?.error?.let { Text("Drive: $it", color = MaterialTheme.colorScheme.error) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (compact) 112.dp else 150.dp),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (favoritesOnly) {
                if (favorites.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(if (allFavorites.isEmpty())
                            "Избранных альбомов пока нет. Отметьте папку звездой в любом облаке."
                        else "Совпадений в избранном нет.")
                    }
                }
                items(favorites, key = { "favorite:${it.key}" }) { favorite ->
                    val isMail = favorite.source == 0
                    val provider = if (isMail) mail else drive
                    val session = if (isMail) mailSession else driveSession
                    val bookmark = favorite.bookmark
                    if (provider != null && session != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(if (isMail) "Mail" else "Google Drive",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                            AlbumBrowserTile(
                                node = bookmark.trail.last().copy(name = bookmark.title),
                                session = session,
                                loadCover = loadCovers,
                                manualCover = chosenCovers.firstOrNull {
                                    it.scope == bookmark.scope && it.folderId == bookmark.id
                                }?.photo,
                                refresh = if (isMail) mailRefresh else driveRefresh,
                                saved = true,
                                onToggleSaved = if (selectionMode) null else ({
                                    onToggleBookmark(bookmark)
                                }),
                                onOpen = {
                                    if (selectionMode) toggleSelection(favoriteSelectionKey(bookmark))
                                    else onOpenAlbum(favorite.source, bookmark.trail)
                                }
                            )
                            if (selectionMode) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val key = favoriteSelectionKey(bookmark)
                                    Checkbox(
                                        checked = key in selectedVisible,
                                        onCheckedChange = { toggleSelection(key) }
                                    )
                                    Text(
                                        if (key in selectedVisible) "Выбрано" else "Выбрать",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                            Text(bookmark.trail.dropLast(1).joinToString(" / ") { it.name },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall)
                            if (!selectionMode) {
                                TextButton(onClick = {
                                    editing = bookmark
                                    editedName = bookmark.label ?: bookmark.trail.last().name
                                }) { Text("Название") }
                            }
                        }
                    }
                }
            } else {
            if (all.isEmpty() && mailContents?.loading != true && driveContents?.loading != true) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (mail == null && drive == null) "Сначала подключите облако."
                        else if (search.isNotBlank()) "Совпадений среди загруженных альбомов нет."
                        else "Папок-альбомов на загруженных страницах нет.")
                }
            }
            items(all, key = { it.key }) { album ->
                val isMail = album.source == 0
                val provider = if (isMail) mail else drive
                val session = if (isMail) mailSession else driveSession
                val sourceKey = if (isMail) mailScope else driveScope
                if (provider != null && session != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (isMail) "Mail" else "Google Drive",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        AlbumBrowserTile(
                            node = album.node,
                            session = session,
                            loadCover = loadCovers,
                            manualCover = chosenCovers.firstOrNull {
                                it.scope == sourceKey && it.folderId == album.node.id
                            }?.photo,
                            refresh = if (isMail) mailRefresh else driveRefresh,
                            saved = relevantBookmarks.any {
                                it.scope == sourceKey && it.id == album.node.id
                            },
                            onToggleSaved = {
                                onToggleBookmark(AlbumBookmark(sourceKey, listOf(provider.root, album.node)))
                            },
                            onOpen = { onOpenAlbum(album.source, listOf(provider.root, album.node)) }
                        )
                    }
                }
            }
            if (mail != null && mailContents != null && mailContents.nextPage != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !mailContents.loading,
                        onClick = { scope.launch { mailContents.load(mail, mail.root, true, {}) } }
                    ) { Text("Показать ещё из Mail") }
                }
            }
            if (drive != null && driveContents != null && driveContents.nextPage != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !driveContents.loading,
                        onClick = { scope.launch { driveContents.load(drive, drive.root, true, onDriveExpired) } }
                    ) { Text("Показать ещё из Google Drive") }
                }
            }
            if (mailContents?.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { mailSession?.invalidateCandidates(); mailRefresh++ }) { Text("Повторить Mail") }
                }
            }
            if (driveContents?.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { driveSession?.invalidateCandidates(); driveRefresh++ }) { Text("Повторить Google Drive") }
                }
            }
            }
        }
        if (confirmBulkRemoval && selectedVisible.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { confirmBulkRemoval = false },
                title = { Text("Убрать ${selectedVisible.size} из избранного?") },
                text = {
                    Text("Будут удалены только отметки избранного в MEDIA HUB. " +
                        "Файлы и папки в Mail и Google Drive останутся на месте.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        onRemoveSelectedBookmarks(selectedVisible)
                        selectedKeys = emptySet()
                        selectionMode = false
                        confirmBulkRemoval = false
                    }) { Text("Убрать из избранного") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmBulkRemoval = false }) {
                        Text("Отмена")
                    }
                }
            )
        }
        editing?.let { bookmark ->
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("Название избранного альбома") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        Text("Меняется только подпись в MEDIA HUB, не название папки в облаке.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = editedName,
                            onValueChange = { editedName = it.take(80) },
                            label = { Text("Название (до 80 символов)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onRenameBookmark(bookmark, editedName)
                            editing = null
                        }
                    ) { Text("Сохранить") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            onRenameBookmark(bookmark, null)
                            editing = null
                        }) { Text("Сбросить") }
                        TextButton(onClick = { editing = null }) { Text("Отмена") }
                    }
                }
            )
        }
    }
}
