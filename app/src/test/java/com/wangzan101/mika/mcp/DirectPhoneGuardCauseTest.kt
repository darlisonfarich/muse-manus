package com.wangzan101.mika.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectPhoneGuardCauseTest {
    @Test fun journalReferenceHashesOnlyStructuredCauseAndLegacyResultIsAccepted() {
        val result = Json.parseToJsonElement(
            """{"ok":false,"error":"guard_stopped","guard":[{"code":"no_progress","severity":"stop","evidence":{"unchanged":12},"cause":{"code":"guard_no_progress","guard":"no_progress","counters":{"unchanged":12},"thresholds":{"stop":12},"clear_when":"real_progress"}}]}"""
        ).jsonObject
        val digest = directPhoneGuardCauseDigest(result) { "sha256($it)" }
        assertEquals("sha256([{\"code\":\"guard_no_progress\",\"guard\":\"no_progress\",\"counters\":{\"unchanged\":12},\"thresholds\":{\"stop\":12},\"clear_when\":\"real_progress\"}])", digest)
        assertNull(directPhoneGuardCauseDigest(Json.parseToJsonElement("""{"ok":false,"error":"legacy"}""").jsonObject) { error("legacy result must not be hashed") })
    }
}
