package com.wangzan101.mika.core.uistate

import kotlin.math.ceil
import kotlin.math.floor

/**
 * A single conservative source for screen-image and OCR redaction. Coordinates in the
 * accessibility tree are normalized to 0..999; Android pixel drawing belongs to :app.
 *
 * Returning null means at least one secret node could not be located. Callers MUST
 * suppress the image/OCR, rather than silently shipping an unmasked frame.
 */
object SecretRegions {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Pixels(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private const val MAX = 999
    private const val GUARD_PIXELS = 8
    private const val GUARD_OCR = 3

    fun collect(elements: List<UiElement>, windows: List<UiWindowTree>): List<Box>? {
        val raw = mutableListOf<List<Int>>()
        elements.filter { it.secret }.forEach { raw += it.bounds }
        fun visit(node: UiTreeNode) {
            if (node.secret) raw += node.bounds
            node.children.forEach(::visit)
        }
        windows.forEach { window -> window.nodes.forEach(::visit) }
        val boxes = ArrayList<Box>(raw.size)
        for (bounds in raw) {
            if (bounds.size != 4 || bounds.any { it !in 0..MAX }) return null
            val (l, t, r, b) = bounds
            if (l >= r || t >= b) return null
            boxes += Box(l, t, r, b)
        }
        return boxes.distinct()
    }

    /** A single-pixel intersection is enough to suppress the *whole* OCR line. */
    fun filterOcr(lines: List<VisibleTextLine>, areas: List<Box>): List<VisibleTextLine> =
        lines.filter { line ->
            val b = line.bounds
            b.size == 4 && b[0] < b[2] && b[1] < b[3] &&
                areas.none { secret ->
                    b[0] <= secret.right + GUARD_OCR && b[2] >= secret.left - GUARD_OCR &&
                        b[1] <= secret.bottom + GUARD_OCR && b[3] >= secret.top - GUARD_OCR
                }
        }

    /** Integer pixel rectangles with an added guard band to cover rounding/antialiasing. */
    fun pixels(areas: List<Box>, width: Int, height: Int): List<Pixels>? {
        if (width <= 0 || height <= 0) return null
        fun begin(value: Int, size: Int) = (floor(value.toDouble() * (size - 1) / MAX).toInt() - GUARD_PIXELS).coerceIn(0, size)
        fun end(value: Int, size: Int) = (ceil(value.toDouble() * (size - 1) / MAX).toInt() + 1 + GUARD_PIXELS).coerceIn(0, size)
        return areas.map { Pixels(begin(it.left, width), begin(it.top, height),
            end(it.right, width), end(it.bottom, height)) }
    }
}
