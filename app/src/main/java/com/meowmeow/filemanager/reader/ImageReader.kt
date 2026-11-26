package com.meowmeow.filemanager.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.platform.LocalContext
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
    onExternal: () -> Unit,
    galleryPosition: Int? = null,
    galleryCount: Int? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val repository = remember(context) { ImageReaderRepository(context.applicationContext) }
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
            if (galleryPosition != null && galleryCount != null && galleryCount > 1) {
                GalleryControls(galleryPosition, galleryCount, onPrevious, onNext)
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
                if (galleryPosition != null && galleryCount != null && galleryCount > 1) {
                    GalleryControls(galleryPosition, galleryCount, onPrevious, onNext)
                }
                Box(
                    Modifier.fillMaxSize()
                        .clipToBounds()
                        .onSizeChanged {
                            viewport = it
                            pan = boundPan(pan, scale, viewport, bitmap)
                        }
                        .pointerInput(uri, bitmap, onPrevious != null, onNext != null) {
                            val threshold = 70.dp.toPx()
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val startedZoomed = scale > 1.01f
                                var hadMultipleFingers = false
                                var horizontal = 0f
                                var vertical = 0f
                                var anyPressed: Boolean
                                do {
                                    val event = awaitPointerEvent()
                                    val activeFingers = event.changes.count { it.pressed }
                                    if (activeFingers > 1) hadMultipleFingers = true
                                    val panChange = event.calculatePan()
                                    val zoomChange = event.calculateZoom()

                                    if (hadMultipleFingers || startedZoomed || scale > 1.01f) {
                                        // Two fingers pinch/zoom; one finger pans only when zoomed.
                                        scale = (scale * zoomChange).coerceIn(1f, 5f)
                                        pan = boundPan(pan + panChange, scale, viewport, bitmap)
                                        event.changes.forEach { it.consume() }
                                    } else {
                                        horizontal += panChange.x
                                        vertical += panChange.y
                                    }
                                    anyPressed = event.changes.any { it.pressed }
                                } while (anyPressed)

                                // Horizontal, deliberate one-finger swipe at fit-to-screen.
                                // A pinch or a zoomed image must never change the photo.
                                if (galleryPosition != null && galleryCount != null) {
                                    val destination = swipeTargetIndex(
                                        currentIndex = galleryPosition - 1,
                                        total = galleryCount,
                                        dragX = horizontal,
                                        dragY = vertical,
                                        minimumDistance = threshold,
                                        zoomed = startedZoomed || scale > 1.01f,
                                        multiTouch = hadMultipleFingers
                                    )
                                    if (destination > galleryPosition - 1) onNext?.invoke()
                                    else if (destination < galleryPosition - 1) onPrevious?.invoke()
                                }
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

@Composable
private fun GalleryControls(
    position: Int,
    total: Int,
    previous: (() -> Unit)?,
    next: (() -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { previous?.invoke() }, enabled = previous != null) {
            Text("‹ Previous")
        }
        Text("$position / $total", style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { next?.invoke() }, enabled = next != null) {
            Text("Next ›")
        }
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
