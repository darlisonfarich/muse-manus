package com.wangzan101.mika.mcp

import com.darlisonfarich.gadgetlink.commands.CommandException
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import com.wangzan101.mika.cap.phone.a11y.VirtualScreenshot
import com.wangzan101.mika.cap.phone.shell.VirtualWindowProtection
import com.wangzan101.mika.core.shell.HelperlessRouting
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * B1: the verdict the shell reports with no helper (derived from the accessibility windows, in
 * [HelperlessRouting.appWindowVerdict]) crossed with the rule that reads and actions obey
 * ([DirectPhoneReads]). The earlier code reported "unknown", which this rule turns into `executor_error` for every
 * read and every action once the app's display existed; no test crossed the two, so nothing caught it.
 *
 * What the host does with the shell's answer is `VirtualWindowProtection.fromCode(shell.virtualWindowProtection(id))`,
 * so the helper-less shell is modelled by [verdict] = the same pure function the real [InAppShell] calls.
 */
class HelperlessReadsTest {
    private val bounds = listOf(0, 0, 30, 30)
    private val label = UiElement("@e1", "TextView", "hello", 2, 3, bounds, null, false, false, false)

    private fun appWindow(secure: Boolean = false) =
        ScreenSnapshot("s1", "test.app", listOf(label), 100, 200, protected = secure)

    private val noWindow get() = ScreenSnapshot("s0", null, emptyList(), 100, 200, hasWindow = false)

    /** The shell's answer when the app sees [windows] windows on the display and the accessibility service is [a11yUp]. */
    private fun verdict(windows: Int, a11yUp: Boolean = true) =
        VirtualWindowProtection.fromCode(HelperlessRouting.appWindowVerdict(a11yUp, windows))

    private fun reads(
        snapshot: ScreenSnapshot,
        protection: VirtualWindowProtection,
        capture: VirtualScreenshot = VirtualScreenshot.Png("png".toByteArray()),
    ): DirectPhoneReads {
        val screen = Proxy.newProxyInstance(ScreenPort::class.java.classLoader, arrayOf(ScreenPort::class.java)) { _, method, _ ->
            when (method.name) {
                "peek" -> snapshot
                "isProtected" -> false
                else -> throw AssertionError("unexpected screen operation ${method.name}")
            }
        } as ScreenPort
        return DirectPhoneReads(
            screen, { 1L }, { protection }, { false }, { capture },
            { data, limit -> encodePhoneScreenshot(data, limit) { _, _ -> null } },
        )
    }

    private fun code(block: suspend () -> Unit): String? = try {
        runBlocking { block() }
        null
    } catch (e: CommandException) {
        e.code.toString()
    }

    @Test fun theDerivedVerdictsAreTheCodesTheHostDecodes() {
        assertEquals(VirtualWindowProtection.Clear, verdict(windows = 1))
        assertEquals(VirtualWindowProtection.Empty, verdict(windows = 0))
        assertEquals(VirtualWindowProtection.Unknown, verdict(windows = 1, a11yUp = false))
    }

    @Test fun withoutAHelperAnOpenAppIsReadableAndActionsPassTheSurfaceCheck() {
        val phone = reads(appWindow(), verdict(windows = 1))
        val tree = runBlocking { phone.tree(full = false) }
        assertNotNull(tree)
        assertNotNull(runBlocking { phone.screenshot() })
        // Every gadget action goes through this check first; with the old "unknown" verdict it refused all of them.
        assertEquals(null, code { phone.checkActionSurface() })
    }

    @Test fun anEmptyDisplayWithoutAHelperIsReadableToo() {
        val phone = reads(noWindow, verdict(windows = 0))
        assertEquals(null, code { phone.checkActionSurface() })
    }

    @Test fun theOldUnknownVerdictIsWhatBrokeEveryReadAndAction_andStaysFailClosedWithoutAccessibility() {
        // Regression guard for B1: a shell that answers "unknown" while windows exist can only fail closed.
        for (call in listOf<suspend (DirectPhoneReads) -> Unit>({ it.tree(false) }, { it.screenshot() }, { it.checkActionSurface() })) {
            val phone = reads(appWindow(), verdict(windows = 1, a11yUp = false))
            assertEquals(ErrorCode.EXECUTOR_ERROR.toString(), code { call(phone) })
        }
    }

    @Test fun aWindowThatTheAppSeesButTheVerdictCalledEmptyIsStaleNotAnAnswer() {
        // Windows appeared between the verdict and the read: the read must not accept a half-built picture.
        val phone = reads(appWindow(), VirtualWindowProtection.Empty)
        assertEquals(ErrorCode.STALE_SNAPSHOT.toString(), code { phone.checkActionSurface() })
    }

    @Test fun aProtectedWindowIsStillRefusedWhenTheVerdictIsDerived() {
        // The derived verdict cannot see FLAG_SECURE; the snapshot's own protected flag still stops the read.
        val phone = reads(appWindow(secure = true), verdict(windows = 1))
        try {
            runBlocking { phone.tree(false) }
            fail("a protected window must not be readable")
        } catch (e: CommandException) {
            assertEquals(ErrorCode.LOCAL_GATE_DENIED, e.code)
            assertTrue(e.message.orEmpty().contains("needs_user") || e.detail == "needs_user")
        }
    }
}
