package com.meowmeow.filemanager.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.meowmeow.filemanager.diagnostics.ErrorOperation
import com.meowmeow.filemanager.ui.resultUnlessCancelled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private sealed interface ImagePreviewState {
    data object Loading : ImagePreviewState
    data class Ready(val bitmap: Bitmap) : ImagePreviewState
    data class Failed(val message: String) : ImagePreviewState
}

/** Fit, pinch-to-zoom and pan; the original image is never edited or saved. */
@Composable
internal fun ImageReader(
    uri: Uri,
    fileName: String,
    onFailure: (Throwable, ErrorOperation) -> Unit,
    onExternal: () -> Unit
) {
    val repository = remember { ImageReaderRepositoryForScreen.create() }
    var retry by rememberSaveable(uri.toString()) { mutableIntStateOf(0) }
    val preview by produceState<ImagePreviewState>(
        initialValue = ImagePreviewState.Loading, uri, retry
    ) {
        value = ImagePreviewState.Loading
        val result = resultUnlessCancelled {
            withContext(Dispatchers.IO) { repository.load(uri) }
        }
        value = result.fold(
            onSuccess = { ImagePreviewState.Ready(it) },
            onFailure = { failure ->
                if (failure !is ReaderLimitException) {
                    onFailure(failure, ErrorOperation.READER_IMAGE)
                }
                ImagePreviewState.Failed(
                    if (failure is ReaderLimitException) failure.message.orEmpty()
                    else "This image could not be displayed. The format may be unsupported or the image damaged."
                )
            }
        )
    }

    when (val loaded = preview) {
        ImagePreviewState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is ImagePreviewState.Failed -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(loaded.message, style = MaterialTheme.typography.titleMedium)
            Text("The original file is unchanged.", modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 20.dp)) {
                OutlinedButton(onClick = { retry++ }) { Text("Retry") }
                OutlinedButton(onClick = onExternal) { Text("Open with another app") }
            }
        }
        is ImagePreviewState.Ready -> {
            val bitmap = loaded.bitmap
            var scale by rememberSaveable(uri.toString()) { mutableFloatStateOf(1f) }
            var pan by remember(uri) { mutableStateOf(Offset.Zero) }
            var viewport by remember { mutableStateOf(IntSize.Zero) }

            fun setZoom(newZoom: Float) {
                scale = newZoom.coerceIn(1f, 5f)
                pan = boundPan(pan, scale, viewport, bitmap)
            }
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (fileName.endsWith(".gif", ignoreCase = true))
                            "Image · GIF first frame" else "Image · Read only",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { setZoom(scale - 0.5f) },
                            enabled = scale > 1f
                        ) { Text("−") }
                        Text(((scale * 100f).roundToInt()).toString() + "%",
                            style = MaterialTheme.typography.labelSmall)
                        TextButton(
                            onClick = { setZoom(scale + 0.5f) },
                            enabled = scale < 5f
                        ) { Text("+") }
                        TextButton(onClick = { scale = 1f; pan = Offset.Zero },
                            enabled = scale > 1f) { Text("Fit") }
                    }
                }
                Box(
                    Modifier.fillMaxSize()
                        .clipToBounds()
                        .onSizeChanged {
                            viewport = it
                            pan = boundPan(pan, scale, viewport, bitmap)
                        }
                        .pointerInput(uri, bitmap) {
                            detectTransformGestures { _, gesturePan, gestureZoom, _ ->
                                scale = (scale * gestureZoom).coerceIn(1f, 5f)
                                pan = boundPan(pan + gesturePan, scale, viewport, bitmap)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "In-app image preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = pan.x
                            translationY = pan.y
                        }
                    )
                }
            }
        }
    }
}

/** Shared decoder instance is scoped to composition and never retains document content. */
private object ImageReaderRepositoryForScreen {
    @Composable
    fun create(): ImageReaderRepository {
        val context = androidx.compose.ui.platform.LocalContext.current
        return remember(context) { ImageReaderRepository(context.applicationContext) }
    }
}

/** Bound panning to the zoomed image, avoiding dragging into empty margins. */
private fun boundPan(offset: Offset, scale: Float, viewport: IntSize, bitmap: Bitmap): Offset {
    if (scale <= 1f || viewport.width <= 0 || viewport.height <= 0) return Offset.Zero
    val ratio = min(
        viewport.width.toFloat() / bitmap.width,
        viewport.height.toFloat() / bitmap.height
    )
    val shownWidth = bitmap.width * ratio
    val shownHeight = bitmap.height * ratio
    val limitX = ((shownWidth * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val limitY = ((shownHeight * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(
        offset.x.coerceIn(-limitX, limitX),
        offset.y.coerceIn(-limitY, limitY)
    )
}
