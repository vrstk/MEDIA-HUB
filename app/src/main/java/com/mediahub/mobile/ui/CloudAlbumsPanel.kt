package com.mediahub.mobile.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CloudProvider
import com.mediahub.mobile.media.MediaFileKinds
import com.mediahub.mobile.media.PhotoPreviewLoader
import com.mediahub.mobile.media.readPhotoBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * A unified, read-only gallery browser for the already connected cloud providers.
 * Each page is fetched on demand; no mass gallery scan or persistent image cache.
 */
@Composable
internal fun CloudAlbumsPanel(
    mail: CloudProvider?,
    drive: CloudProvider?,
    mailIdentity: String?,
    driveIdentity: String?,
    onOpenMail: () -> Unit,
    onOpenDrive: () -> Unit,
    onDriveExpired: () -> Unit,
    modifier: Modifier = Modifier
) {
    var source by rememberSaveable { mutableIntStateOf(if (mail != null) 0 else 1) }
    val provider = if (source == 0) mail else drive
    var query by rememberSaveable(source) { mutableStateOf("") }
    var filter by rememberSaveable(source) { mutableIntStateOf(0) }
    var photoId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val bookmarksStore = remember(context) { AlbumBookmarkStore(context) }
    var bookmarks by remember(bookmarksStore) { mutableStateOf(bookmarksStore.load()) }
    val coverStore = remember(context) { AlbumCoverStore(context) }
    var chosenCovers by remember(coverStore) { mutableStateOf(coverStore.load()) }
    val identity = if (source == 0) mailIdentity else driveIdentity
    val sourceKey = remember(source, identity) {
        albumBookmarkScope(if (source == 0) "mail" else "drive", identity.orEmpty())
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = source == 0, onClick = {
                source = 0
                photoId = null
            }, label = { Text("Mail") })
            FilterChip(selected = source == 1, onClick = {
                source = 1
                photoId = null
            }, label = { Text("Google Drive") })
        }
        if (provider == null) {
            InfoCard("Хранилище не подключено",
                if (source == 0) "Подключите Mail, чтобы просматривать его альбомы."
                else "Сначала выберите папку Google Drive через системный доступ Android.")
            Button(
                onClick = if (source == 0) onOpenMail else onOpenDrive,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (source == 0) "Открыть Mail" else "Открыть Google Drive") }
            return@Column
        }

        // State is intentionally specific to a provider. Switching accounts resets the trail.
        val trail = remember(provider) { mutableStateListOf(provider.root) }
        val folder = trail.last()
        var refresh by remember(provider) { mutableIntStateOf(0) }
        val contents = rememberFolderContents(
            provider, folder, refresh,
            if (source == 1) onDriveExpired else ({})
        )
        val scope = rememberCoroutineScope()
        val gridState = rememberLazyGridState()
        val previewLoader = remember(provider) {
            PhotoPreviewLoader { file, maxBytes ->
                provider.openRead(file).use { it.readPhotoBytes(maxBytes) }
            }
        }
        // Metadata only: never store downloaded photo bytes for every album.
        val covers = remember(provider, refresh) { mutableMapOf<String, List<CloudNode>>() }
        val coverRequests = remember(provider) { Semaphore(2) }

        fun back() {
            if (trail.size > 1) {
                trail.removeAt(trail.lastIndex)
                photoId = null
                query = ""
            }
        }
        BackHandler(enabled = trail.size > 1 && photoId == null, onBack = ::back)
        LaunchedEffect(folder.id, filter, query) { gridState.scrollToItem(0) }
        val savedAlbums = remember(bookmarks, sourceKey, provider.root.id) {
            bookmarks.filter { it.scope == sourceKey && it.trail.firstOrNull()?.id == provider.root.id }
        }
        val selectedFolderCover = chosenCovers.firstOrNull {
            it.scope == sourceKey && it.folderId == folder.id
        }
        fun chooseCover(photo: CloudNode?) {
            val next = setAlbumCover(chosenCovers, sourceKey, folder.id, photo)
            coverStore.save(next)
            chosenCovers = next
        }

        if (savedAlbums.isNotEmpty()) {
            Text("Избранные альбомы", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                savedAlbums.forEach { album ->
                    FilterChip(
                        selected = folder.id == album.id,
                        onClick = {
                            trail.clear()
                            trail.addAll(album.trail)
                            photoId = null
                            query = ""
                        },
                        label = {
                            Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    )
                }
            }
        } else {
            Text(
                "Отмечайте папки звездой, чтобы быстро открывать любимые альбомы.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (trail.size > 1) {
                TextButton(onClick = ::back) { Text("Назад") }
            }
            Text(
                folder.name.ifBlank { "Корневая папка" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { refresh++ }, enabled = !contents.loading) {
                Text("Обновить")
            }
        }
        if (trail.size > 1 && selectedFolderCover != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Своя обложка: ${selectedFolderCover.photo.name}",
                    modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { chooseCover(null) }) { Text("Авто") }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(160) },
            singleLine = true,
            label = { Text("Поиск по названиям в текущей папке") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Всё", "Папки", "Фото").forEachIndexed { index, title ->
                FilterChip(selected = filter == index, onClick = { filter = index },
                    label = { Text(title) })
            }
        }
        val nodes = remember(contents.nodes, query, filter) {
            albumBrowseItems(
                contents.nodes,
                query,
                AlbumContentFilter.entries[filter.coerceIn(0, 2)]
            )
        }
        Text(
            "Папок: ${nodes.count { it.isFolder }} · фото: ${nodes.count { !it.isFolder }}" +
                if (contents.nextPage != null) " · Есть ещё" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (contents.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        contents.error?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { refresh++ }) { Text("Повторить") }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = gridState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (nodes.isEmpty() && !contents.loading && contents.error == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (query.isNotBlank()) "Совпадений нет." else "Нет альбомов или фотографий.")
                }
            }
            items(nodes, key = { it.id }) { node ->
                AlbumBrowserTile(
                    node = node,
                    provider = provider,
                    loader = previewLoader,
                    coverCache = covers,
                    semaphore = coverRequests,
                    manualCover = chosenCovers.firstOrNull {
                        it.scope == sourceKey && it.folderId == node.id
                    }?.photo,
                    refresh = refresh,
                    saved = node.isFolder && savedAlbums.any { it.id == node.id },
                    onToggleSaved = if (node.isFolder) ({
                        val next = toggleAlbumBookmark(
                            bookmarks,
                            AlbumBookmark(sourceKey, trail.toList() + node)
                        )
                        bookmarksStore.save(next)
                        bookmarks = next
                    }) else null,
                    onOpen = {
                        if (node.isFolder) {
                            trail.add(node)
                            photoId = null
                            query = ""
                        } else photoId = node.id
                    }
                )
            }
            if (contents.nextPage != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(
                        onClick = {
                            scope.launch { contents.load(provider, folder, true,
                                if (source == 1) onDriveExpired else ({}) ) }
                        },
                        enabled = !contents.loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Показать ещё") }
                }
            }
        }
        val photos = remember(nodes) { nodes.filter(MediaFileKinds::isPhoto) }
        photoId?.let { id ->
            PhotoViewer(
                photos = photos,
                initialPhotoId = id,
                loader = previewLoader,
                onClose = { photoId = null },
                onLoadFailure = {}
            ) { file ->
                if (trail.size > 1) {
                    val isCover = selectedFolderCover?.photo?.id == file.id
                    TextButton(onClick = { chooseCover(file) }) {
                        Text(if (isCover) "Обложка ✓" else "На обложку")
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumBrowserTile(
    node: CloudNode,
    provider: CloudProvider,
    loader: PhotoPreviewLoader,
    coverCache: MutableMap<String, List<CloudNode>>,
    semaphore: Semaphore,
    manualCover: CloudNode?,
    refresh: Int,
    saved: Boolean,
    onToggleSaved: (() -> Unit)?,
    onOpen: () -> Unit
) {
    val cover by produceState<Bitmap?>(
        null, node.id, provider, manualCover, refresh
    ) {
        value = semaphore.withPermit {
            suspend fun tryThumbnail(file: CloudNode): Bitmap? = try {
                loader.thumbnail(file)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }

            // Manual cover is always attempted first, but stale links do not hide the album.
            val selected = manualCover?.takeIf(MediaFileKinds::isPhoto)?.let { tryThumbnail(it) }
            if (selected != null) return@withPermit selected
            if (!node.isFolder) return@withPermit tryThumbnail(node)
            val candidates = try {
                coverCache[node.id] ?: runInterruptible(Dispatchers.IO) {
                    autoCoverCandidates(provider.list(node.id).nodes)
                }.also { coverCache[node.id] = it }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                emptyList()
            }
            for (candidate in candidates) {
                if (candidate.id == manualCover?.id) continue
                val bitmap = tryThumbnail(candidate)
                if (bitmap != null) return@withPermit bitmap
            }
            null
        }
    }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.25f),
                contentAlignment = Alignment.Center
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover!!.asImageBitmap(),
                        contentDescription = if (node.isFolder) "Обложка альбома" else node.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(if (node.isFolder) "📁" else "▧",
                        style = MaterialTheme.typography.headlineLarge)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = node.name,
                    modifier = Modifier.weight(1f).padding(10.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                if (onToggleSaved != null) {
                    TextButton(
                        modifier = Modifier.semantics {
                            contentDescription = if (saved) "Убрать альбом из избранного"
                                else "Добавить альбом в избранное"
                        },
                        onClick = onToggleSaved
                    ) { Text(if (saved) "★" else "☆", style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }
}
