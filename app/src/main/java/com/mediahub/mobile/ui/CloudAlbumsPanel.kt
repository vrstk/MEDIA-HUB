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
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
    var source by rememberSaveable { mutableIntStateOf(2) }
    var loadCovers by rememberSaveable { mutableStateOf(true) }
    val mailSession = remember(mail) { mail?.let(::AlbumThumbnailSession) }
    val driveSession = remember(drive) { drive?.let(::AlbumThumbnailSession) }
    val provider = if (source == 0) mail else drive
    var query by rememberSaveable(source) { mutableStateOf("") }
    var filter by rememberSaveable(source) { mutableIntStateOf(0) }
    var photoId by remember { mutableStateOf<String?>(null) }
    var requestedTrail by remember { mutableStateOf<Pair<Int, List<CloudNode>>?>(null) }
    val context = LocalContext.current
    val bookmarksStore = remember(context) { AlbumBookmarkStore(context) }
    var bookmarks by remember(bookmarksStore) { mutableStateOf(bookmarksStore.load()) }
    val coverStore = remember(context) { AlbumCoverStore(context) }
    var chosenCovers by remember(coverStore) { mutableStateOf(coverStore.load()) }
    val navigationStore = remember(context) { AlbumNavigationStore(context) }
    var navigation by remember(navigationStore) { mutableStateOf(navigationStore.load()) }
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
            FilterChip(selected = source == 2, onClick = {
                source = 2
                photoId = null
                requestedTrail = null
            }, label = { Text("Все") })
            FilterChip(selected = source == 0, onClick = {
                source = 0
                photoId = null
            }, label = { Text("Mail") })
            FilterChip(selected = source == 1, onClick = {
                source = 1
                photoId = null
            }, label = { Text("Google Drive") })
        }
        FilterChip(
            selected = loadCovers,
            onClick = { loadCovers = !loadCovers },
            label = { Text(if (loadCovers) "Обложки включены" else "Обложки отключены") }
        )
        if (source == 2) {
            CombinedCloudAlbumsOverview(
                mail = mail,
                drive = drive,
                mailSession = mailSession,
                driveSession = driveSession,
                loadCovers = loadCovers,
                mailScope = albumBookmarkScope("mail", mailIdentity.orEmpty()),
                driveScope = albumBookmarkScope("drive", driveIdentity.orEmpty()),
                bookmarks = bookmarks,
                chosenCovers = chosenCovers,
                onToggleBookmark = { bookmark ->
                    val updated = toggleAlbumBookmark(bookmarks, bookmark)
                    bookmarksStore.save(updated)
                    bookmarks = updated
                },
                onOpenAlbum = { index, trail ->
                    requestedTrail = index to trail
                    source = index
                    photoId = null
                },
                onOpenMail = onOpenMail,
                onOpenDrive = onOpenDrive,
                onDriveExpired = onDriveExpired
            )
            return@Column
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

        // Only restore the path belonging to the current account and root document.
        val trail = remember(provider, sourceKey) {
            mutableStateListOf<CloudNode>().apply {
                addAll(restoredAlbumTrail(navigation, sourceKey, provider.root))
            }
        }
        fun persistTrail() {
            val updated = saveAlbumTrail(navigation, sourceKey, trail.toList())
            navigationStore.save(updated)
            navigation = updated
        }
        LaunchedEffect(provider, requestedTrail) {
            val pending = requestedTrail
            if (pending != null && pending.first == source &&
                pending.second.firstOrNull()?.id == provider.root.id) {
                trail.clear()
                trail.addAll(pending.second)
                persistTrail()
                requestedTrail = null
                query = ""
            }
        }
        val folder = trail.last()
        var refresh by remember(provider) { mutableIntStateOf(0) }
        val contents = rememberFolderContents(
            provider, folder, refresh,
            if (source == 1) onDriveExpired else ({})
        )
        val scope = rememberCoroutineScope()
        // A separate grid state per folder restores the last viewed row.
        val restoredPosition = remember(provider, sourceKey, folder.id) {
            savedAlbumPosition(navigation, sourceKey, folder.id)
        }
        val gridState = remember(provider, sourceKey, folder.id) { LazyGridState() }
        var positionReady by remember(provider, sourceKey, folder.id) { mutableStateOf(false) }
        LaunchedEffect(provider, folder.id, contents.loading, contents.nodes.size, query, filter) {
            if (!positionReady && !contents.loading) {
                if (contents.nodes.isNotEmpty() && query.isBlank() && filter == 0) {
                    val target = restoredPosition?.index?.coerceIn(0, contents.nodes.lastIndex) ?: 0
                    gridState.scrollToItem(target, if (target == restoredPosition?.index)
                        restoredPosition.offset else 0)
                }
                positionReady = true
            }
        }
        LaunchedEffect(provider, sourceKey, folder.id, gridState,
            positionReady, query, filter, contents.loading) {
            if (!positionReady || contents.loading || query.isNotBlank() || filter != 0 ||
                contents.nodes.isEmpty()) return@LaunchedEffect
            snapshotFlow {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            }.distinctUntilChanged().collectLatest { (index, offset) ->
                delay(450)
                val updated = saveAlbumPosition(
                    navigation, sourceKey, provider.root, folder.id, index, offset)
                if (updated != navigation) {
                    navigationStore.save(updated)
                    navigation = updated
                }
            }
        }
        val session = (if (source == 0) mailSession else driveSession)
            ?: error("Не найдено подключённое хранилище")
        val previewLoader = session.loader

        fun back() {
            if (trail.size > 1) {
                trail.removeAt(trail.lastIndex)
                persistTrail()
                photoId = null
                query = ""
            }
        }
        BackHandler(enabled = trail.size > 1 && photoId == null, onBack = ::back)
        LaunchedEffect(query, filter) {
            if (query.isNotBlank() || filter != 0) gridState.scrollToItem(0)
        }
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
                            persistTrail()
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
            TextButton(onClick = { session.invalidateCandidates(); refresh++ }, enabled = !contents.loading) {
                Text("Обновить")
            }
        }
        // Current folder first: long nested paths remain usable without extra screens.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            trail.indices.reversed().forEach { level ->
                TextButton(onClick = {
                    if (level < trail.lastIndex) {
                        while (trail.lastIndex > level) trail.removeAt(trail.lastIndex)
                        persistTrail()
                        photoId = null
                        query = ""
                    }
                }, enabled = level != trail.lastIndex) {
                    Text(trail[level].name.ifBlank { "Корень" }, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
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
                    session = session,
                    loadCover = loadCovers,
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
                            persistTrail()
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
internal fun AlbumBrowserTile(
    node: CloudNode,
    session: AlbumThumbnailSession,
    loadCover: Boolean,
    manualCover: CloudNode?,
    refresh: Int,
    saved: Boolean,
    onToggleSaved: (() -> Unit)?,
    onOpen: () -> Unit
) {
    val cover by produceState<Bitmap?>(
        null, node.id, session, manualCover, refresh, loadCover
    ) {
        if (!loadCover) return@produceState
        value = session.semaphore.withPermit {
            suspend fun tryThumbnail(file: CloudNode): Bitmap? = try {
                session.loader.thumbnail(file)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }

            val selected = manualCover?.takeIf(MediaFileKinds::isPhoto)?.let { tryThumbnail(it) }
            if (selected != null) return@withPermit selected
            if (!node.isFolder) return@withPermit tryThumbnail(node)

            val candidates = try {
                session.folderCandidates(node.id)
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
