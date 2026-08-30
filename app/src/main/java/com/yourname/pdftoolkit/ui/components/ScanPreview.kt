package com.yourname.pdftoolkit.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yourname.pdftoolkit.R
import com.yourname.pdftoolkit.domain.operations.ScanAdjustments
import com.yourname.pdftoolkit.domain.operations.ScanEnhancer
import com.yourname.pdftoolkit.util.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Longest edge of the bitmap used for the preview — small enough to re-filter on every change. */
private const val PREVIEW_MAX_DIMENSION = 1000

/** Debounce before re-filtering, so dragging a slider does not queue up work. */
private const val PREVIEW_DEBOUNCE_MS = 120L

/** How far the preview can be pinched in. */
private const val MAX_PREVIEW_ZOOM = 6f

/** Fraction of the page width a swipe has to cover to actually turn the page. */
private const val SWIPE_PAGE_FRACTION = 0.25f

/** Resistance when swiping past the first or the last page. */
private const val SWIPE_EDGE_RESISTANCE = 0.25f

/**
 * Shows what a page will look like in the produced PDF and lets the user tune it.
 *
 * The page is drawn on the target sheet (turned sideways for a landscape photo, the way the PDF
 * will), can be pinched to inspect the fine print, and a compare button swaps back to the
 * untouched photo. Settings edited here apply to the page on screen, or to every page while
 * "apply to all pages" is on.
 *
 * @param images the pages to preview, in output order
 * @param adjustmentsFor current settings of a page
 * @param onAdjustmentsChange the user changed the settings of the page on screen
 * @param pageAspectRatio width/height of the target page, or null to fit the image itself
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanPreviewCard(
    images: List<Uri>,
    adjustmentsFor: (Uri) -> ScanAdjustments,
    onAdjustmentsChange: (Uri, ScanAdjustments) -> Unit,
    applyToAll: Boolean,
    onApplyToAllChange: (Boolean) -> Unit,
    pageAspectRatio: Float?,
    modifier: Modifier = Modifier
) {
    if (images.isEmpty()) return

    val context = LocalContext.current

    var pageIndex by remember(images.size) { mutableStateOf(0) }
    // Keep the index valid when pages are removed.
    val safeIndex = pageIndex.coerceIn(0, images.lastIndex)
    val currentUri = images[safeIndex]
    val adjustments = adjustmentsFor(currentUri)

    var original by remember { mutableStateOf<Bitmap?>(null) }
    var processed by remember { mutableStateOf<Bitmap?>(null) }
    var isRendering by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    // How far the page has been dragged sideways, also used for the slide-in of the next page.
    var swipeOffset by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentUri) {
        isRendering = true
        failed = false
        processed = null
        scale = 1f
        offset = Offset.Zero
        original = withContext(Dispatchers.IO) {
            ImageProcessor.loadForPreview(context, currentUri, PREVIEW_MAX_DIMENSION)
        }
        failed = original == null
    }

    LaunchedEffect(original, adjustments) {
        val source = original ?: return@LaunchedEffect
        isRendering = true
        delay(PREVIEW_DEBOUNCE_MS)
        processed = withContext(Dispatchers.Default) {
            ScanEnhancer.enhance(source, adjustments, recycleSource = false)
        }
        isRendering = false
    }

    val shown = if (showOriginal) original else processed ?: original

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.preview_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.weight(1f))

                if (images.size > 1) {
                    IconButton(
                        onClick = { pageIndex = safeIndex - 1 },
                        enabled = safeIndex > 0,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.ChevronLeft,
                            contentDescription = stringResource(R.string.preview_previous_page)
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.preview_page_indicator,
                            safeIndex + 1,
                            images.size
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )
                    IconButton(
                        onClick = { pageIndex = safeIndex + 1 },
                        enabled = safeIndex < images.lastIndex,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = stringResource(R.string.preview_next_page)
                        )
                    }
                }

                IconToggleButton(
                    checked = showOriginal,
                    onCheckedChange = { showOriginal = it },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Compare,
                        contentDescription = stringResource(
                            if (showOriginal) R.string.preview_show_result
                            else R.string.preview_show_original
                        ),
                        tint = if (showOriginal) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // A hairline that says "the preview is catching up with your settings".
            Box(modifier = Modifier.fillMaxWidth().height(3.dp)) {
                if (isRendering) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                }
            }

            // The sheet follows the photo's orientation, as the generated page will.
            val imageIsLandscape = shown?.let { it.width > it.height } ?: false
            val aspect = when {
                pageAspectRatio == null -> shown?.let { it.width.toFloat() / it.height } ?: 1f
                imageIsLandscape != (pageAspectRatio > 1f) -> 1f / pageAspectRatio
                else -> pageAspectRatio
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .clipToBounds()
                    .onSizeChanged { viewportSize = it }
                    .pointerInput(images.size, safeIndex) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var totalX = 0f
                            var totalY = 0f
                            var swiping = false
                            var scrolling = false
                            var event: PointerEvent
                            do {
                                event = awaitPointerEvent()
                                val twoFingers = event.changes.count { it.pressed } >= 2
                                val pan = event.calculatePan()

                                if (twoFingers || scale > 1f) {
                                    // Pinch, and one-finger panning once zoomed in.
                                    val zoom = if (twoFingers) event.calculateZoom() else 1f
                                    scale = (scale * zoom).coerceIn(1f, MAX_PREVIEW_ZOOM)
                                    offset = clampOffset(offset + pan, scale, viewportSize)
                                    if (zoom != 1f || pan != Offset.Zero) {
                                        event.changes.forEach { it.consume() }
                                    }
                                } else if (images.size > 1 && !scrolling) {
                                    // Zoomed out: a sideways drag turns the page, while a
                                    // vertical one is left to the screen behind us to scroll.
                                    totalX += pan.x
                                    totalY += pan.y
                                    if (!swiping) {
                                        if (abs(totalY) > slop && abs(totalY) > abs(totalX)) {
                                            scrolling = true
                                        } else if (abs(totalX) > slop) {
                                            swiping = true
                                        }
                                    }
                                    if (swiping) {
                                        val atEdge = (totalX > 0 && safeIndex == 0) ||
                                            (totalX < 0 && safeIndex == images.lastIndex)
                                        swipeOffset =
                                            if (atEdge) totalX * SWIPE_EDGE_RESISTANCE else totalX
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })

                            if (swiping) {
                                val width = viewportSize.width.toFloat().coerceAtLeast(1f)
                                val threshold = width * SWIPE_PAGE_FRACTION
                                val target = when {
                                    swipeOffset <= -threshold && safeIndex < images.lastIndex ->
                                        safeIndex + 1
                                    swipeOffset >= threshold && safeIndex > 0 -> safeIndex - 1
                                    else -> safeIndex
                                }
                                if (target != safeIndex) {
                                    pageIndex = target
                                    // The page that just arrived slides in from the same side.
                                    swipeOffset = if (target > safeIndex) width else -width
                                }
                                val from = swipeOffset
                                scope.launch {
                                    animate(from, 0f) { value, _ -> swipeOffset = value }
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    shown != null -> Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = stringResource(R.string.preview_title),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x + swipeOffset,
                                translationY = offset.y
                            )
                    )
                    failed -> Text(
                        text = stringResource(R.string.preview_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }

                if (showOriginal && shown != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.preview_original_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (scale > 1f) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "${"%.1f".format(scale)}×",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Text(
                text = stringResource(
                    if (images.size > 1) R.string.preview_swipe_hint
                    else R.string.preview_zoom_hint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider()

            ScanAdjustmentControls(
                adjustments = adjustments,
                onAdjustmentsChange = { onAdjustmentsChange(currentUri, it) }
            )

            if (images.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.preview_apply_to_all),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(
                                if (applyToAll) R.string.preview_apply_to_all_on
                                else R.string.preview_apply_to_all_off
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = applyToAll,
                        onCheckedChange = onApplyToAllChange
                    )
                }
            }
        }
    }
}

/** Keep the zoomed image from being dragged past its own edges. */
private fun clampOffset(offset: Offset, scale: Float, size: IntSize): Offset {
    if (scale <= 1f || size == IntSize.Zero) return Offset.Zero
    val maxX = (scale - 1f) * size.width / 2f
    val maxY = (scale - 1f) * size.height / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/**
 * The three knobs that decide how much the page looks like a scan rather than a photo.
 *
 * No list of preset modes: whitening at zero leaves the photo as shot, at full it bleaches the
 * paper white, and the black point decides where ink turns black — which on an unwhitened photo
 * is simply contrast.
 */
@Composable
fun ScanAdjustmentControls(
    adjustments: ScanAdjustments,
    onAdjustmentsChange: (ScanAdjustments) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.scan_black_and_white),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.scan_black_and_white_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = adjustments.blackAndWhite,
                onCheckedChange = { onAdjustmentsChange(adjustments.copy(blackAndWhite = it)) }
            )
        }

        LabelledSlider(
            label = stringResource(R.string.scan_whiten_strength),
            value = adjustments.whitenStrength,
            onValueChange = { onAdjustmentsChange(adjustments.copy(whitenStrength = it)) },
            minLabel = stringResource(R.string.scan_whiten_off),
            maxLabel = stringResource(R.string.scan_whiten_max)
        )

        LabelledSlider(
            label = stringResource(R.string.scan_black_point),
            value = adjustments.blackPoint,
            onValueChange = { onAdjustmentsChange(adjustments.copy(blackPoint = it)) },
            minLabel = stringResource(R.string.scan_black_point_soft),
            maxLabel = stringResource(R.string.scan_black_point_hard)
        )
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    minLabel: String,
    maxLabel: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            steps = 19
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = minLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = maxLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
