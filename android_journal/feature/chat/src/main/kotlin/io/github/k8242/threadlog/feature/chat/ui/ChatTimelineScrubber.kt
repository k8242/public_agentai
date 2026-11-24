// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.chat.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SCRUBBER_MIN_ITEMS = 15
private const val SCRUBBER_IDLE_HIDE_DELAY_MS = 1_500L
private const val SCRUBBER_SCROLL_THROTTLE_MS = 40L

/**
 * Google Photos-style right-edge dated fast scrubber for reverse-layout chat feeds.
 *
 * Maps visual `0f` (top) -> `totalCount - 1` (oldest / Beginning) and `1f` (bottom) -> `0` (newest / End),
 * auto-fades after idle time, and displays a floating date pill beside the thumb while dragging.
 */
@Composable
fun ChatTimelineScrubber(
    listState: LazyListState,
    timelineIndex: ChatTimelineIndex,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    val effectiveTotal = if (totalCount > 0) totalCount else timelineIndex.totalCount
    if (effectiveTotal < SCRUBBER_MIN_ITEMS) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(1f) }
    var isVisible by remember { mutableStateOf(false) }
    var lastScrollDispatchMs by remember { mutableLongStateOf(0L) }

    val isScrolling = listState.isScrollInProgress
    LaunchedEffect(isScrolling, isDragging) {
        if (isScrolling || isDragging) {
            isVisible = true
        } else {
            delay(SCRUBBER_IDLE_HIDE_DELAY_MS)
            isVisible = false
        }
    }

    val listFraction by remember(effectiveTotal) {
        derivedStateOf {
            ChatTimelineIndex.visualFractionForIndex(
                index = listState.firstVisibleItemIndex,
                totalCount = effectiveTotal,
            )
        }
    }

    val activeFraction = if (isDragging) dragFraction else listFraction
    val scrubberAlpha by animateFloatAsState(
        targetValue = if (isVisible || isDragging) 1f else 0f,
        label = "scrubberAlpha",
    )
    val thumbWidth by animateDpAsState(
        targetValue = if (isDragging) 10.dp else 6.dp,
        label = "scrubberThumbWidth",
    )

    val activeIndex = ChatTimelineIndex.indexForVisualFraction(activeFraction, effectiveTotal)
    val activeDateLabel = remember(activeIndex, timelineIndex) {
        val lookupIdx = if (timelineIndex.totalCount > 0) {
            activeIndex.coerceIn(0, timelineIndex.totalCount - 1)
        } else {
            activeIndex
        }
        timelineIndex.dateForIndex(lookupIdx)?.let(ChatTimelineIndex::formatScrubberDateLabel)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .padding(top = 48.dp, bottom = 68.dp),
    ) {
        val thumbHeightDp = 44.dp
        val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
        val trackHeightPx = (constraints.maxHeight.toFloat() - thumbHeightPx).coerceAtLeast(1f)
        val thumbOffsetY = (activeFraction * trackHeightPx).roundToInt()

        // Floating date pill beside the thumb while actively dragging
        if (isDragging && activeDateLabel != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp,
                tonalElevation = 3.dp,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 36.dp)
                    .offset { IntOffset(0, thumbOffsetY) },
            ) {
                Text(
                    text = activeDateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // Right-edge touch hit target & draggable thumb
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(28.dp)
                .pointerInput(effectiveTotal, trackHeightPx) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val nextFraction = ((offset.y - thumbHeightPx / 2f) / trackHeightPx).coerceIn(0f, 1f)
                            dragFraction = nextFraction
                            val targetIdx = ChatTimelineIndex.indexForVisualFraction(nextFraction, effectiveTotal)
                            lastScrollDispatchMs = System.currentTimeMillis()
                            scope.launch {
                                listState.scrollToItem(targetIdx)
                            }
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val nextFraction = ((change.position.y - thumbHeightPx / 2f) / trackHeightPx).coerceIn(0f, 1f)
                            dragFraction = nextFraction
                            val now = System.currentTimeMillis()
                            if (now - lastScrollDispatchMs >= SCRUBBER_SCROLL_THROTTLE_MS) {
                                lastScrollDispatchMs = now
                                val targetIdx = ChatTimelineIndex.indexForVisualFraction(nextFraction, effectiveTotal)
                                scope.launch {
                                    listState.scrollToItem(targetIdx)
                                }
                            }
                        },
                        onDragEnd = {
                            val finalIdx = ChatTimelineIndex.indexForVisualFraction(dragFraction, effectiveTotal)
                            isDragging = false
                            scope.launch {
                                listState.scrollToItem(finalIdx)
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                    )
                },
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .offset { IntOffset(0, thumbOffsetY) }
                    .alpha(scrubberAlpha),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(thumbWidth)
                        .height(thumbHeightDp)
                        .background(
                            color = if (isDragging) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}
