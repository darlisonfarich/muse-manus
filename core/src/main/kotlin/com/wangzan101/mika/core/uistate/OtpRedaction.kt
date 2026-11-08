package com.wangzan101.mika.core.uistate

/**
 * Redacts standalone 4–8 digit one-time codes when any password field is visible.
 * Sanitization happens before live observations, OCR, evidence, or MCP serialization.
 * No credentials are persisted, and references/geometry are kept unchanged.
 */
internal object OtpRedaction {
    private val code = Regex("(?<![\\p{L}\\p{N}])[0-9]{4,8}(?![\\p{L}\\p{N}])")
    private const val MASK = "[OTP REDACTED]"

    fun hasPasswordField(s: ScreenSnapshot): Boolean =
        s.elements.any { it.secret } || s.windows.any { w -> w.nodes.any(::hasSecretNode) }

    private fun hasSecretNode(node: UiTreeNode): Boolean =
        node.secret || node.children.any(::hasSecretNode)

    fun text(value: String, passwordPresent: Boolean): String =
        if (passwordPresent) code.replace(value, MASK) else value

    fun snapshot(s: ScreenSnapshot): ScreenSnapshot {
        if (!hasPasswordField(s)) return s
        return s.copy(
            elements = s.elements.map { e ->
                e.copy(name = text(e.name, true), placeholder = e.placeholder?.let { text(it, true) })
            },
            windows = s.windows.map { w ->
                w.copy(title = w.title?.let { text(it, true) }, nodes = w.nodes.map(::node))
            },
        )
    }

    private fun node(n: UiTreeNode): UiTreeNode = n.copy(
        text = n.text?.let { text(it, true) },
        description = n.description?.let { text(it, true) },
        hintText = n.hintText?.let { text(it, true) },
        children = n.children.map(::node),
    )

    fun ocr(s: ScreenSnapshot, lines: List<VisibleTextLine>): List<VisibleTextLine> {
        if (!hasPasswordField(s)) return lines
        return lines.map { it.copy(text = text(it.text, true)) }
    }
}
