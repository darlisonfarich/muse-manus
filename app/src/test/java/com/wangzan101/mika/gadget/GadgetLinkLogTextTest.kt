package com.wangzan101.mika.gadget

import org.junit.Assert.assertEquals
import org.junit.Test

class GadgetLinkLogTextTest {
    @Test
    fun copiedLogIsAHeaderThenOneKeyValueLineEach() {
        val text = GadgetLink.logText(
            "Muse Manus 0.1.41 (42, abc1234)",
            listOf("helper" to "running", "link event" to "07:20:01 link: phone session ready"),
        )
        assertEquals(
            "Muse Manus 0.1.41 (42, abc1234)\nhelper: running\nlink event: 07:20:01 link: phone session ready",
            text,
        )
    }
}
