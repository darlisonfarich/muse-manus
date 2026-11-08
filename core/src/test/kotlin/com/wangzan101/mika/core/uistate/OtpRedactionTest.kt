package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpRedactionTest {
    private fun sample(password: Boolean) = ScreenSnapshot(
        snapshotId = "p1", packageName = "com.example",
        elements = listOf(
            UiElement("@e1", "TextView", "Código: 123456. Validade 02:30", 350, 100,
                listOf(50, 60, 700, 160), null, false, false, false),
            UiElement("@e2", "EditText", "", 500, 900,
                listOf(100, 800, 900, 950), null, false, true, false, secret = password),
        ), width = 1080, height = 2400,
        windows = listOf(UiWindowTree(1, "application", 0, null, "com.example", true, true, listOf(
            UiTreeNode("layout", listOf(0, 0, 999, 999), children = listOf(
                UiTreeNode("text", listOf(40, 20, 700, 100), text = "Seu código 654321 chegou", description = "OTP: 4321."),
                UiTreeNode("edit", listOf(100, 800, 900, 950), secret = password),
            )),
        ))),
    )

    @Test fun masksLiveUiTreeAndElementTextOnlyWhenPasswordFieldIsPresent() {
        val safe = OtpRedaction.snapshot(sample(true))
        assertFalse(safe.elements[0].name.contains("123456"))
        assertTrue(safe.elements[0].name.contains("[OTP REDACTED]"))
        val nodes = UiTreeJson.windows(safe).toString()
        assertFalse(nodes.contains("654321"))
        assertFalse(nodes.contains("4321"))
        assertTrue(nodes.contains("[OTP REDACTED]"))
        assertTrue(safe.elements[1].secret)
        assertEquals("@e1", safe.elements[0].ref)
    }

    @Test fun doesNotModifyOrdinaryNumbersWithoutPasswordField() {
        val s = sample(false)
        assertEquals(s, OtpRedaction.snapshot(s))
        val lines = listOf(VisibleTextLine("Pedido 123456", listOf(0, 0, 100, 40)))
        assertEquals(lines, OtpRedaction.ocr(s, lines))
    }

    @Test fun masksCodesEmbeddedInRunningTextButNotPartsOfLongNumbersOrWords() {
        val text = "Seu código é 543210, use em 2 min. ref A123456B, ID 123456789, 12:34."
        val masked = OtpRedaction.text(text, true)
        assertTrue(masked.contains("[OTP REDACTED]"))
        assertFalse(masked.contains("543210"))
        assertTrue(masked.contains("A123456B"))
        assertTrue(masked.contains("123456789"))
        assertEquals(text, OtpRedaction.text(text, false))
    }

    @Test fun passwordInNestedTreeIsSufficientToMaskOcr() {
        val s = sample(true).copy(elements = sample(false).elements)
        assertTrue(OtpRedaction.hasPasswordField(s))
        val lines = listOf(VisibleTextLine("Código para acesso: 789012", listOf(10, 200, 550, 245)))
        val result = OtpRedaction.ocr(s, lines)
        assertEquals("Código para acesso: [OTP REDACTED]", result.single().text)
    }

    @Test fun phoneObserveNeverReturnsNearbyOtpInElementsTreeOrTextEvidence() = runTest {
        val raw = sample(true)
        val phone = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot = raw
            override suspend fun readVisibleText(): List<VisibleTextLine> = listOf(
                VisibleTextLine("Código: 999999 enviado", listOf(120, 400, 760, 450))
            )
        }
        val r = toolDispatcher(phone).executeRead(Part.ToolCall("otp", "phone_observe", "{}"))
        val result = r.content.toString()
        assertFalse(result.contains("123456"))
        assertFalse(result.contains("654321"))
        assertFalse(result.contains("4321"))
        assertFalse(result.contains("999999"))
    }
}
