package com.wangzan101.mika.core.uistate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRegionsTest {
    private fun field(bounds: List<Int>, secret: Boolean = true) = UiElement(
        "@e1", "EditText", "", 200, 200, bounds, null, false, true, false, secret = secret,
    )

    @Test fun collectsSecretNodesFromNestedTreeEvenWithoutAnElementRef() {
        val child = UiTreeNode("Password", listOf(200, 300, 500, 350), secret = true)
        val parent = UiTreeNode("Layout", listOf(0, 0, 999, 999), children = listOf(child))
        val window = UiWindowTree(1, "application", 1, null, "app", true, true, listOf(parent))
        assertEquals(listOf(SecretRegions.Box(200, 300, 500, 350)),
            SecretRegions.collect(emptyList(), listOf(window)))
    }

    @Test fun removesDuplicatesAndKeepsNonSecretElements() {
        val element = field(listOf(100, 100, 300, 200))
        val node = UiTreeNode("EditText", element.bounds, secret = true)
        val window = UiWindowTree(1, "application", 1, null, "app", true, true, listOf(node))
        assertEquals(listOf(SecretRegions.Box(100, 100, 300, 200)),
            SecretRegions.collect(listOf(element, field(listOf(500, 500, 600, 600), false)), listOf(window)))
    }

    @Test fun invalidSecretGeometryBlocksImageAndOcrPlanning() {
        assertNull(SecretRegions.collect(listOf(field(listOf(0, 0, 0, 0))), emptyList()))
        assertNull(SecretRegions.collect(listOf(field(listOf(-1, 0, 10, 10))), emptyList()))
        assertNull(SecretRegions.collect(listOf(field(listOf(1, 2, 3))), emptyList()))
        assertNull(SecretRegions.pixels(emptyList(), 0, 100))
    }

    @Test fun tinyIntersectionRemovesWholeOcrLineAndOtherTextSurvives() {
        val region = SecretRegions.Box(100, 100, 300, 200)
        val lines = listOf(
            VisibleTextLine("PIN 9381", listOf(299, 195, 500, 240)),
            VisibleTextLine("ordinary note", listOf(400, 300, 600, 350)),
            VisibleTextLine("touching edge", listOf(300, 120, 450, 150)),
        )
        assertEquals(listOf("ordinary note"),
            SecretRegions.filterOcr(lines, listOf(region)).map { it.text })
    }

    @Test fun pixelBoxesCoverNormalizedEdgesWithMarginAndStayInsideFrame() {
        val boxes = SecretRegions.pixels(listOf(
            SecretRegions.Box(0, 0, 100, 100),
            SecretRegions.Box(700, 900, 999, 999),
        ), 1080, 2400)!!
        assertEquals(0, boxes.first().left)
        assertEquals(0, boxes.first().top)
        assertTrue(boxes.first().right > 108)
        assertTrue(boxes.first().bottom > 240)
        assertEquals(1080, boxes.last().right)
        assertEquals(2400, boxes.last().bottom)
        assertFalse(boxes.any { it.left < 0 || it.top < 0 || it.right > 1080 || it.bottom > 2400 })
    }

    @Test fun visibleTextRejectsEvenSlightOverlapWithSecretTreeNode() {
        val node = UiTreeNode("Password", listOf(100, 100, 300, 200), secret = true)
        val window = UiWindowTree(1, "application", 1, null, "app", true, true, listOf(node))
        val screen = ScreenSnapshot("s", "app", emptyList(), 1080, 2400, windows = listOf(window))
        val text = VisibleText.missing(screen, listOf(
            VisibleTextLine("PIN 9381", listOf(295, 190, 550, 250)),
            VisibleTextLine("public", listOf(400, 450, 600, 500)),
        ))
        assertEquals(listOf("public"), text.map { it.text })
    }

    @Test fun malformedSecretTreeCoordinatesSuppressAllOcr() {
        val node = UiTreeNode("Password", listOf(0, 0, 0, 0), secret = true)
        val window = UiWindowTree(1, "application", 1, null, "app", true, true, listOf(node))
        val screen = ScreenSnapshot("s", "app", emptyList(), 1080, 2400, windows = listOf(window))
        assertTrue(VisibleText.missing(screen, listOf(VisibleTextLine("secret", listOf(400, 450, 600, 500)))).isEmpty())
    }
}
