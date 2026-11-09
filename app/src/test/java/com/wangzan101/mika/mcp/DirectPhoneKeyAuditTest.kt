package com.wangzan101.mika.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectPhoneKeyAuditTest {
    @Test fun confirmedEffectHasDispatchedCompletionAndReadbacks() {
        val result = buildJsonObject {
            put("ok", true); put("keyDispatch", "dispatched")
            put("keyBefore", "current=4,max=15,muted=false")
            put("keyAfter", "current=5,max=15,muted=false")
        }
        assertEquals("dispatched|dispatch=dispatched|before=current=4,max=15,muted=false|after=current=5,max=15,muted=false",
            DirectPhoneKeyAudit.completion(result))
    }

    @Test fun unchangedAndUnreadableEffectsNeverClaimDispatched() {
        for (status in listOf("not_applied", "effect_unavailable")) {
            val result = buildJsonObject {
                put("ok", false); put("keyDispatch", status)
                put("keyBefore", "unavailable"); put("keyAfter", "unavailable")
            }
            assertEquals("not_applied|dispatch=$status|before=unavailable|after=unavailable",
                DirectPhoneKeyAudit.completion(result))
        }
    }

    @Test fun untrustedStatusAndAnUnconfirmedSuccessCannotClaimDispatched() {
        assertNull(DirectPhoneKeyAudit.completion(buildJsonObject { put("ok", true) }))
        assertNull(DirectPhoneKeyAudit.completion(buildJsonObject { put("keyDispatch", "forged") }))
        assertEquals("not_applied|dispatch=dispatched|before=unavailable|after=unavailable",
            DirectPhoneKeyAudit.completion(buildJsonObject { put("keyDispatch", "dispatched"); put("ok", false) }))
    }
}
