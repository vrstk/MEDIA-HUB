package com.mediahub.mobile.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.media.MediaFileKinds
import com.mediahub.mobile.media.PhotoPreviewLoader
import kotlinx.coroutines.CancellationException

@Composable
internal fun StorageGridTile(
    file: CloudNode,
    loader: PhotoPreviewLoader,
    onOpen: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    copyDescription: String,
    copyLabel: String
) {
    val isPhoto = MediaFileKinds.isPhoto(file)
    val modifier = Modifier.fillMaxWidth()
    Card(if (onOpen != null) modifier.clickable(role = Role.Button, onClick = onOpen) else modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            if (isPhoto) {
                var thumbnail by remember(file, loader) { mutableStateOf<Bitmap?>(loader.cachedThumbnail(file)) }
                LaunchedEffect(file, loader) {
                    if (thumbnail == null) {
                        thumbnail = try {
                            loader.thumbnail(file)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                val photo = thumbnail
                if (photo == null) Text("▧", style = MaterialTheme.typography.headlineLarge)
                else Image(photo.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (file.isFolder) "📁" else "📄", style = MaterialTheme.typography.headlineLarge)
                    Text(if (file.isFolder) "Папка" else "Файл", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                file.name,
                modifier = Modifier.weight(1f).padding(8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
            if (onCopy != null) {
                TextButton(onClick = onCopy) { Text(copyLabel, maxLines = 1) }
            }
        }
    }
}
