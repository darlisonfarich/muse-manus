package com.wangzan101.mika.cap.phone.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No raw, unredacted virtual-display PNG accessor may be exposed. */
class AgentDisplayApiSafetyTest {
    @Test fun rawPngAccessorRemovedWhileBitmapPathRemains() {
        val methods = AgentDisplay::class.java.declaredMethods.map { it.name }
        assertFalse(methods.contains("latestFramePng"))
        assertTrue(methods.contains("latestFrameBitmap"))
    }
}
