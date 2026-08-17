package to.eyed.conquest.code.ui.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import java.io.File
import java.util.Locale

/** How far a pinch may take an image, either way. */
private const val MIN_ZOOM = 0.2f
private const val MAX_ZOOM = 12f

/**
 * A file that is not text: shown, not parsed.
 *
 * Opening a PNG into a text buffer was never right — the engine would hold a
 * megabyte of mojibake and the editor would try to highlight it — and it is
 * the one case where "everything is a buffer" has to give. So a media tab has
 * no buffer at all: nothing to save, nothing to be dirty, and closing it
 * cannot lose work.
 *
 * Zoom and pan are the whole interaction, and they are a pinch and a drag,
 * which is also what a mouse wheel and a drag produce.
 */
@Composable
fun MediaPane(
    absolutePath: String,
    kind: MediaKind,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("editor.background")),
        contentAlignment = Alignment.Center,
    ) {
        if (kind == MediaKind.Image) ImageView(absolutePath) else Unplayable(absolutePath, kind)
    }
}

@Composable
private fun ImageView(absolutePath: String) {
    val theme = LocalZedTheme.current
    // Decoded off the main thread, and only when the path changes: a photo is
    // tens of megabytes of pixels and the decode is not free.
    val bitmap by produceState<ImageBitmap?>(initialValue = null, absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(absolutePath)?.asImageBitmap() }.getOrNull()
        }
    }
    val image = bitmap
    if (image == null) {
        Message(
            title = File(absolutePath).name,
            detail = "Reading…",
        )
        return
    }
    var zoom by remember(absolutePath) { mutableFloatStateOf(1f) }
    var pan by remember(absolutePath) { mutableStateOf(Offset.Zero) }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(absolutePath) {
                    detectTransformGestures { _, dragged, zoomed, _ ->
                        zoom = (zoom * zoomed).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        pan += dragged
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = File(absolutePath).name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .graphicsLayer(
                        scaleX = zoom,
                        scaleY = zoom,
                        translationX = pan.x,
                        translationY = pan.y,
                    ),
            )
        }
        Text(
            text = "${image.width} × ${image.height}",
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

/**
 * Sound and video, named but not played.
 *
 * An IDE that ships a media player is an IDE maintaining a media player, and
 * the thing a developer wants from an `.mp4` in a repository is to know it is
 * there and how big it is. Handing it to whatever the device *does* have is
 * the honest move, and is a share intent away — noted rather than built,
 * because it needs a FileProvider and that is its own change.
 */
@Composable
private fun Unplayable(absolutePath: String, kind: MediaKind) {
    val file = File(absolutePath)
    Message(
        title = file.name,
        detail = "${kind.name.lowercase(Locale.ROOT)} · ${humanSize(file.length())}",
    )
}

@Composable
private fun Message(title: String, detail: String) {
    val theme = LocalZedTheme.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text"),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
        )
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes bytes"
}
