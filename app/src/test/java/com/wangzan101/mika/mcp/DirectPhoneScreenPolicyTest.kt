package com.wangzan101.mika.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPhoneScreenPolicyTest {
    @Test fun virtualDisplayReadsDoNotRequirePhysicalDisplayInteractive() {
        for (read in listOf("phone_observe", "phone_list_apps", "phone_screenshot")) {
            assertTrue("Missing read classification: $read", read in DirectPhoneScreenPolicy.reads)
            assertFalse("Read must work with physical screen off: $read",
                DirectPhoneScreenPolicy.requiresInteractivePhysicalScreen(read))
        }
    }

    @Test fun everyExistingDirectActionKeepsPhysicalReadyGate() {
        for (write in listOf(
            "phone_launch_app", "phone_tap", "phone_type", "phone_scroll",
            "phone_swipe", "phone_gesture", "phone_key", "phone_back", "phone_run",
        )) {
            assertTrue("Write must retain ready() gate: $write",
                DirectPhoneScreenPolicy.requiresInteractivePhysicalScreen(write))
        }
    }
}
