package com.meowmeow.filemanager.reader

import kotlin.math.abs

/**
 * A horizontal swipe moves in reading order: left -> next, right -> previous.
 * A deliberate distance and horizontal dominance prevent accidental navigation.
 */
internal fun swipeTargetIndex(
    currentIndex: Int,
    total: Int,
    dragX: Float,
    dragY: Float,
    minimumDistance: Float,
    zoomed: Boolean,
    multiTouch: Boolean
): Int {
    if (total <= 0 || currentIndex !in 0 until total) return currentIndex
    if (zoomed || multiTouch || !dragX.isFinite() || !dragY.isFinite() ||
        minimumDistance <= 0f || abs(dragX) < minimumDistance ||
        abs(dragX) <= abs(dragY) * 1.3f
    ) return currentIndex
    return if (dragX < 0f) (currentIndex + 1).coerceAtMost(total - 1)
    else (currentIndex - 1).coerceAtLeast(0)
}
