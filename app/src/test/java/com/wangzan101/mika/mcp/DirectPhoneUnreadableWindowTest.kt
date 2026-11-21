package com.wangzan101.mika.mcp

import com.wangzan101.mika.cap.phone.shell.VirtualWindowProtection
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPhoneUnreadableWindowTest {
    private val element = UiElement("@e1", "button", "OK", 500, 500, listOf(0, 0, 10, 10), null, clickable = true, editable = false, scrollable = false)

    private fun snapshot(
        packageName: String? = null, elements: List<UiElement> = emptyList(),
        hasWindow: Boolean = true, unreadableApp: String? = null,
    ) = ScreenSnapshot("s", packageName, elements, 1080, 2400, hasWindow = hasWindow, unreadableApp = unreadableApp)

    @Test fun anAppWindowWhoseContentCannotBeReadIsNotAnEmptySuccess() {
        // Developer options: the window manager shows the app, accessibility yields an app window without a readable root.
        assertTrue(DirectPhoneReads.unreadableWindow(snapshot(unreadableApp = "com.android.settings"), VirtualWindowProtection.Clear))
    }

    @Test fun readableScreensAndGenuinelyEmptyDisplaysStayOk() {
        assertFalse(DirectPhoneReads.unreadableWindow(snapshot(packageName = "com.whatsapp", elements = listOf(element)), VirtualWindowProtection.Clear))
        // A readable app window without labelled nodes (a game canvas) is still an answer, not a reader failure.
        assertFalse(DirectPhoneReads.unreadableWindow(snapshot(packageName = "com.game"), VirtualWindowProtection.Clear))
        // Nothing on the display at all.
        assertFalse(DirectPhoneReads.unreadableWindow(snapshot(hasWindow = false), VirtualWindowProtection.Empty))
        assertFalse(DirectPhoneReads.unreadableWindow(snapshot(hasWindow = false), VirtualWindowProtection.Clear))
    }

    @Test fun onlyAClearWindowVerdictCountsAsProofThatSomethingIsThere() {
        assertFalse(DirectPhoneReads.unreadableWindow(snapshot(unreadableApp = "com.android.settings"), VirtualWindowProtection.Empty))
        assertFalse(DirectPhoneReads.unreadableWindow(snapshot(unreadableApp = "com.android.settings"), VirtualWindowProtection.Secure))
        assertFalse(DirectPhoneReads.unreadableWindow(snapshot(unreadableApp = "com.android.settings"), VirtualWindowProtection.Unknown))
    }
}
