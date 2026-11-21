package com.wangzan101.mika.mcp

import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.core.access.AppAccessState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectPhoneMainLaunchTest {
    private fun args(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun withoutATargetTheAppOpensWhereItAlwaysDid() {
        assertEquals(DirectPhoneMainLaunch.Target.Virtual, DirectPhoneMainLaunch.target(args("""{"package":"a.b"}""")))
        assertEquals(DirectPhoneMainLaunch.Target.Virtual, DirectPhoneMainLaunch.target(args("""{"target":"virtual"}""")))
        assertEquals(DirectPhoneMainLaunch.Target.Main, DirectPhoneMainLaunch.target(args("""{"target":"main"}""")))
    }

    @Test fun anythingElseAsATargetIsRefusedInsteadOfGuessed() {
        for (bad in listOf("""{"target":"MAIN"}""", """{"target":"both"}""", """{"target":""}""", """{"target":1}""", """{"target":null}""", """{"target":true}"""))
            assertNull(bad, DirectPhoneMainLaunch.target(args(bad)))
    }

    private fun denial(
        stopped: Boolean = false, enabled: Boolean = true, writes: Boolean = true,
        access: AppAccessState = AppAccessState(), pkg: String? = "com.whatsapp",
    ) = DirectPhoneMainLaunch.denial(stopped, enabled, writes, access, pkg, ownPackage = "com.darlisonfarich.musemanus")

    @Test fun theLocalAuthorizationAppliesToTheMainScreenToo() {
        assertNull(denial())
        assertEquals("app_stopped", denial(stopped = true))
        assertEquals("remote_access_disabled", denial(enabled = false))
        assertEquals("write_disabled", denial(writes = false))
        assertEquals("app_blocked", denial(access = AppAccessState(blocked = setOf("com.whatsapp"))))
        assertEquals("app_access_unavailable", denial(access = AppAccessState(available = false)))
        assertEquals("own_app", denial(pkg = "com.darlisonfarich.musemanus"))
    }

    @Test fun theStoppedStateWinsOverEveryOtherReason() {
        assertEquals("app_stopped", denial(stopped = true, enabled = false, writes = false, access = AppAccessState(blocked = setOf("com.whatsapp"))))
    }

    @Test fun everyNonOkShellStartCodeHasAnIdentifiableResult() {
        assertEquals("app_open_on_virtual_display", DirectPhoneMainLaunch.startFailure(ShellService.START_IN_USE_BY_OTHER))
        assertEquals("app_not_found", DirectPhoneMainLaunch.startFailure(ShellService.START_NOT_FOUND))
        assertEquals("main_launch_failed", DirectPhoneMainLaunch.startFailure(ShellService.START_ERROR))
        assertEquals("main_launch_failed", DirectPhoneMainLaunch.startFailure(99))
    }
}
