package com.wangzan101.mika.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectPhoneBatchStepsTest {
    private fun commands(text: String): JsonArray = Json.parseToJsonElement(text).jsonArray

    private val gadgetToInternal = mapOf(
        "phone.open_app" to "phone_launch_app",
        "phone.tap" to "phone_tap",
        "phone.type_text" to "phone_type",
    )
    private val internal = setOf("phone_launch_app", "phone_tap", "phone_type")

    @Test fun gadgetStepNamesBecomeInternalNamesWithArgumentsAndOrderKept() {
        val translated = DirectPhoneBatchSteps.toInternal(
            commands("""[{"name":"phone.open_app","arguments":{"package":"a.b","cold":true}},{"name":"phone.tap","arguments":{"x":1,"y":2}}]"""),
            gadgetToInternal,
        )
        assertEquals(
            commands("""[{"name":"phone_launch_app","arguments":{"package":"a.b","cold":true}},{"name":"phone_tap","arguments":{"x":1,"y":2}}]"""),
            translated,
        )
    }

    @Test fun internalUnknownAndMalformedStepsAreLeftForTheHostToJudge() {
        val raw = commands("""[{"name":"phone_tap","arguments":{}},{"name":"phone.run_batch"},{"name":"nope"},{"arguments":{}},"text",7]""")
        assertEquals(raw, DirectPhoneBatchSteps.toInternal(raw, gadgetToInternal))
    }

    @Test fun theWholeBatchIsValidatedBeforeAnythingRuns() {
        assertNull(DirectPhoneBatchSteps.firstInvalid(commands("""[{"name":"phone_tap"},{"name":"phone_type","arguments":{}}]"""), internal))
        assertEquals(2, DirectPhoneBatchSteps.firstInvalid(
            commands("""[{"name":"phone_launch_app"},{"name":"phone_tap"},{"name":"phone.tap"},{"name":"phone_type"}]"""), internal))
        assertEquals(0, DirectPhoneBatchSteps.firstInvalid(commands("""["phone_tap"]"""), internal))
        assertEquals(1, DirectPhoneBatchSteps.firstInvalid(commands("""[{"name":"phone_tap"},{"arguments":{}}]"""), internal))
        assertEquals(0, DirectPhoneBatchSteps.firstInvalid(commands("""[{"name":"phone_run"}]"""), internal))
    }
}
