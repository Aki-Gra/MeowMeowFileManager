package com.meowmeow.filemanager.reader

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmeow.filemanager.diagnostics.ErrorOperation
import com.meowmeow.filemanager.ui.resultUnlessCancelled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface Preview {
    data object Loading : Preview
    data class TextData(val text: String) : Preview
    data class PdfData(val page: ReaderRepository.PdfPage) : Preview
    data class Failure(val reason: String) : Preview
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    title: String,
    format: ReaderFormat,
    uri: Uri,
    repository: ReaderRepository,
    onClose: () -> Unit,
    onExternal: () -> Unit,
    onReaderFailure: (Throwable, ErrorOperation) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("MeowMeow reader · Offline",
                            style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close reader")
                    }
                },
                actions = {
                    TextButton(onClick = onExternal) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Text("Open with…")
                    }
                }
            )
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when (format) {
                ReaderFormat.UNSUPPORTED -> ReaderMessage(
                    readerUnsupportedMessage(format),
                    "MeowMeow will not launch another app unless you choose to.",
                    onExternal
                )
                ReaderFormat.PDF -> PdfReader(uri, repository, onReaderFailure, onExternal)
                else -> TextReader(uri, format, repository, onReaderFailure, onExternal)
            }
        }
    }
}

@Composable
private fun TextReader(
    uri: Uri, format: ReaderFormat, repository: ReaderRepository,
    onFailure: (Throwable, ErrorOperation) -> Unit, onExternal: () -> Unit
) {
    var fontSize by rememberSaveable { mutableIntStateOf(16) }
    var retry by rememberSaveable { mutableIntStateOf(0) }
    val preview by produceState<Preview>(Preview.Loading, uri, format, retry) {
        value = Preview.Loading
        val result = resultUnlessCancelled {
            withContext(Dispatchers.IO) { repository.loadText(uri, format) }
        }
        value = result.fold(
            { Preview.TextData(it) },
            {
                if (it !is ReaderLimitException) onFailure(it, ErrorOperation.READER_OPEN)
                Preview.Failure(
                    if (it is ReaderLimitException) it.message.orEmpty()
                    else "This document could not be read. It may be damaged or unsupported."
                )
            }
        )
    }
    when (val state = preview) {
        Preview.Loading -> CenteredProgress()
        is Preview.Failure -> ReaderMessage(state.reason, "The original is unchanged.",
            onExternal, onRetry = { retry++ })
        is Preview.TextData -> Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (format == ReaderFormat.TEXT) "Text preview" else "Extracted text · Read only",
                    style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { fontSize = (fontSize - 2).coerceAtLeast(12) },
                        enabled = fontSize > 12) { Text("A−") }
                    Text(fontSize.toString() + " sp", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { fontSize = (fontSize + 2).coerceAtMost(28) },
                        enabled = fontSize < 28) { Text("A+") }
                }
            }
            SelectionContainer {
                Text(
                    text = state.text,
                    fontSize = fontSize.sp,
                    fontFamily = if (format == ReaderFormat.TEXT) FontFamily.Monospace else FontFamily.Default,
                    lineHeight = (fontSize * 1.5).sp,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
        is Preview.PdfData -> Unit
    }
}

@Composable
private fun PdfReader(
    uri: Uri, repository: ReaderRepository,
    onFailure: (Throwable, ErrorOperation) -> Unit, onExternal: () -> Unit
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var retry by rememberSaveable { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val widthDp = LocalConfiguration.current.screenWidthDp.dp
    val pixels = with(density) { (widthDp.toPx() * zoom).toInt() }
    val preview by produceState<Preview>(Preview.Loading, uri, pageIndex, pixels, retry) {
        value = Preview.Loading
        val result = resultUnlessCancelled {
            withContext(Dispatchers.IO) { repository.renderPdfPage(uri, pageIndex, pixels) }
        }
        value = result.fold(
            { Preview.PdfData(it) },
            {
                if (it !is ReaderLimitException) onFailure(it, ErrorOperation.READER_RENDER)
                Preview.Failure(
                    if (it is ReaderLimitException) it.message.orEmpty()
                    else "Cannot display this PDF. Password-protected or damaged PDFs may not be supported."
                )
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        val loaded = preview as? Preview.PdfData
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = { pageIndex-- }, enabled = pageIndex > 0) { Text("Previous") }
            Text("Page " + (pageIndex + 1) + " / " + (loaded?.page?.totalPages ?: "…"),
                style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = { pageIndex++ },
                enabled = loaded != null && pageIndex + 1 < loaded.page.totalPages) { Text("Next") }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = { zoom = (zoom - 0.25f).coerceAtLeast(0.75f) },
                enabled = zoom > 0.75f) { Text("Zoom −") }
            Text(((zoom * 100).toInt()).toString() + "%",
                style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(2f) },
                enabled = zoom < 2f) { Text("Zoom +") }
        }
        when (val state = preview) {
            Preview.Loading -> CenteredProgress()
            is Preview.Failure -> ReaderMessage(state.reason, "Your original PDF is unchanged.",
                onExternal, onRetry = { retry++ })
            is Preview.PdfData -> {
                val bitmapWidthDp = with(density) { state.page.bitmap.width.toDp() }
                Box(
                    Modifier.fillMaxSize().horizontalScroll(rememberScrollState())
                        .verticalScroll(rememberScrollState())
                ) {
                    Image(
                        bitmap = state.page.bitmap.asImageBitmap(),
                        contentDescription = "PDF page " + (pageIndex + 1) +
                            " of " + state.page.totalPages,
                        modifier = Modifier.width(bitmapWidthDp).padding(6.dp)
                    )
                }
            }
            is Preview.TextData -> Unit
        }
    }
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ReaderMessage(
    message: String, hint: String, onExternal: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        if (onRetry != null) Button(onClick = onRetry) { Text("Retry") }
        OutlinedButton(onClick = onExternal) { Text("Open with another app") }
    }
}
