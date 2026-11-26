package com.meowmeow.filemanager.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeNavigationTest {
    private fun target(
        index: Int = 1, total: Int = 4,
        dx: Float = -150f, dy: Float = 0f,
        zoomed: Boolean = false, multiTouch: Boolean = false
    ) = swipeTargetIndex(index, total, dx, dy, 70f, zoomed, multiTouch)

    @Test fun leftToRightMovesToPreviousAndRightToLeftMovesToNext() {
        assertEquals(2, target(dx = -150f))
        assertEquals(0, target(dx = 150f))
    }

    @Test fun boundariesDoNotWrap() {
        assertEquals(0, target(index = 0, dx = 150f))
        assertEquals(3, target(index = 3, dx = -150f))
        assertEquals(0, target(index = 0, total = 1))
    }

    @Test fun smallVerticalAndDiagonalDragsDoNotNavigate() {
        assertEquals(1, target(dx = -65f))
        assertEquals(1, target(dx = -120f, dy = 200f))
        assertEquals(1, target(dx = 125f, dy = 110f))
    }

    @Test fun zoomAndPinchCannotNavigate() {
        assertEquals(1, target(zoomed = true))
        assertEquals(1, target(multiTouch = true))
    }

    @Test fun badInputsDoNotCauseNavigation() {
        assertEquals(0, target(index = 0, total = 0))
        assertEquals(1, target(dx = Float.NaN))
    }
}
