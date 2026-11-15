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
import androidx.compose.material3.Button
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
import com.mediahub.mobile.media.PhotoPreviewLoader
import com.mediahub.mobile.media.readPhotoBytes
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * An overview of root-level folders from connected accounts.
 * Never recursively crawls the library or copies files; individual sources remain authoritative.
 */
@Composable
internal fun CombinedCloudAlbumsOverview(
    mail: CloudProvider?,
    drive: CloudProvider?,
    mailScope: String,
    driveScope: String,
    bookmarks: List<AlbumBookmark>,
    chosenCovers: List<AlbumCover>,
    onToggleBookmark: (AlbumBookmark) -> Unit,
    onOpenAlbum: (Int, List<CloudNode>) -> Unit,
    onOpenMail: () -> Unit,
    onOpenDrive: () -> Unit,
    onDriveExpired: () -> Unit
) {
    var search by rememberSaveable { mutableStateOf("") }
    var mailRefresh by remember { mutableIntStateOf(0) }
    var driveRefresh by remember { mutableIntStateOf(0) }
    val mailContents = mail?.let { rememberFolderContents(it, it.root, mailRefresh, {}) }
    val driveContents = drive?.let { rememberFolderContents(it, it.root, driveRefresh, onDriveExpired) }
    val scope = rememberCoroutineScope()
    val state = rememberLazyGridState()
    LaunchedEffect(search) { state.scrollToItem(0) }
    val mailLoader = remember(mail) {
        mail?.let { provider ->
            PhotoPreviewLoader { photo, max ->
                provider.openRead(photo).use { it.readPhotoBytes(max) }
            }
        }
    }
    val driveLoader = remember(drive) {
        drive?.let { provider ->
            PhotoPreviewLoader { photo, max ->
                provider.openRead(photo).use { it.readPhotoBytes(max) }
            }
        }
    }
    val mailCache = remember(mail, mailRefresh) { mutableMapOf<String, List<CloudNode>>() }
    val driveCache = remember(drive, driveRefresh) { mutableMapOf<String, List<CloudNode>>() }
    val coverSemaphore = remember { Semaphore(2) }
    val all = remember(mailContents?.nodes, driveContents?.nodes, search) {
        mergeRootAlbums(mailContents?.nodes.orEmpty(), driveContents?.nodes.orEmpty(), search)
    }
    val relevantBookmarks = remember(bookmarks, mailScope, driveScope, mail?.root?.id, drive?.root?.id) {
        bookmarks.filter { bookmark ->
            when (bookmark.scope) {
                mailScope -> mail != null && bookmark.trail.firstOrNull()?.id == mail.root.id
                driveScope -> drive != null && bookmark.trail.firstOrNull()?.id == drive.root.id
                else -> false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Все облака", style = MaterialTheme.typography.titleMedium)
        Text("Альбомы из подключённых хранилищ. Файлы не перемещаются и не объединяются физически.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (mail == null) TextButton(onClick = onOpenMail) { Text("Подключить Mail") }
        if (drive == null) TextButton(onClick = onOpenDrive) { Text("Подключить Google Drive") }
        if (relevantBookmarks.isNotEmpty()) {
            Text("Избранные альбомы", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                relevantBookmarks.forEach { bookmark ->
                    val index = if (bookmark.scope == mailScope) 0 else 1
                    FilterChip(selected = false,
                        onClick = { onOpenAlbum(index, bookmark.trail) },
                        label = {
                            Text((if (index == 0) "Mail · " else "Drive · ") + bookmark.title,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        })
                }
            }
        }
        OutlinedTextField(
            value = search, onValueChange = { search = it.take(160) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Поиск альбомов в обоих облаках") }
        )
        val mailCount = mailContents?.nodes?.count { it.isFolder } ?: 0
        val driveCount = driveContents?.nodes?.count { it.isFolder } ?: 0
        Text(
            "Mail: $mailCount · Google Drive: $driveCount" +
                if (mailContents?.nextPage != null || driveContents?.nextPage != null) " · Есть ещё" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (mailContents?.loading == true || driveContents?.loading == true) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        mailContents?.error?.let { Text("Mail: $it", color = MaterialTheme.colorScheme.error) }
        driveContents?.error?.let { Text("Drive: $it", color = MaterialTheme.colorScheme.error) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                val loader = if (isMail) mailLoader else driveLoader
                val sourceKey = if (isMail) mailScope else driveScope
                if (provider != null && loader != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (isMail) "Mail" else "Google Drive",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        AlbumBrowserTile(
                            node = album.node,
                            provider = provider,
                            loader = loader,
                            coverCache = if (isMail) mailCache else driveCache,
                            semaphore = coverSemaphore,
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
                    TextButton(onClick = { mailRefresh++ }) { Text("Повторить Mail") }
                }
            }
            if (driveContents?.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { driveRefresh++ }) { Text("Повторить Google Drive") }
                }
        }
    }
}
