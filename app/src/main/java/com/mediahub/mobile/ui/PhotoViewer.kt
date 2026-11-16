package com.mediahub.mobile.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.media.PhotoPreviewLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PhotoViewer(
    photos: List<CloudNode>,
    initialPhotoId: String,
    loader: PhotoPreviewLoader,
    onClose: () -> Unit,
    onLoadFailure: (Exception) -> Unit = {},
    actions: @Composable RowScope.(CloudNode) -> Unit
) {
    val initialPage = remember(photos, initialPhotoId) { photos.indexOfFirst { it.id == initialPhotoId } }
    if (initialPage < 0) return
    val pager = rememberPagerState(initialPage = initialPage) { photos.size }
    val scope = rememberCoroutineScope()
    val page = pager.settledPage.coerceIn(photos.indices)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MediaHubTheme(darkTheme = true) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color.Black).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        photos[page].name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    TextButton(onClick = onClose) { Text("Закрыть") }
                }
                HorizontalPager(
                    state = pager,
                    key = { photos[it].id },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) { index ->
                    PhotoViewerPage(photos[index], loader, index == page, onLoadFailure)
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            enabled = page > 0,
                            onClick = { scope.launch { pager.animateScrollToPage(page - 1) } }
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Предыдущее фото") }
                        Text("${page + 1} / ${photos.size}", color = Color.White,
                            style = MaterialTheme.typography.labelMedium)
                        IconButton(
                            enabled = page < photos.lastIndex,
                            onClick = { scope.launch { pager.animateScrollToPage(page + 1) } }
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Следующее фото") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { actions(photos[page]) }
                }
            }
        }
    }
}

@Composable
private fun PhotoViewerPage(
    file: CloudNode,
    loader: PhotoPreviewLoader,
    selected: Boolean,
    onLoadFailure: (Exception) -> Unit
) {
    var attempt by remember(file, loader) { mutableIntStateOf(0) }
    var bitmap by remember(file, loader, attempt) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file, loader, attempt) { mutableStateOf<String?>(null) }
    val thumbnail = remember(file, loader) { loader.cachedThumbnail(file) }
    LaunchedEffect(file, loader, selected, attempt) {
        if (!selected || bitmap != null || error != null) return@LaunchedEffect
        try {
            bitmap = loader.preview(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage("Не удалось открыть фотографию.")
            onLoadFailure(e)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val failure = error
        if (failure != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(failure, color = Color.White, textAlign = TextAlign.Center)
                TextButton(onClick = { attempt++ }) { Text("Повторить") }
            }
        } else {
            val displayBitmap = bitmap ?: thumbnail
            if (displayBitmap != null) {
                Image(displayBitmap.asImageBitmap(), file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            if (bitmap == null && selected) CircularProgressIndicator()
        }
    }
}
