package com.wangzan101.mika.settings

import com.wangzan101.mika.core.action.ApprovalMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Explicit three-level approval setting. Legacy {"autonomous":true} migrates to Autonomous. */
class ApprovalStore(private val file: File) {
    private val _mode: MutableStateFlow<ApprovalMode>
    private val _autonomous: MutableStateFlow<Boolean>

    init {
        val json = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        val name = (json?.get("mode") as? JsonPrimitive)?.contentOrNull
        val parsed = ApprovalMode.entries.firstOrNull { it.name == name }
            ?: if ((json?.get("autonomous") as? JsonPrimitive)?.booleanOrNull == true) ApprovalMode.Autonomous else ApprovalMode.Ask
        _mode = MutableStateFlow(parsed)
        _autonomous = MutableStateFlow(parsed == ApprovalMode.Autonomous)
    }

    val mode: StateFlow<ApprovalMode> = _mode.asStateFlow()
    /** Compatibility for the existing simple switch; Strict is NOT Autonomous. */
    val autonomous: StateFlow<Boolean> = _autonomous.asStateFlow()

    @Synchronized fun setMode(value: ApprovalMode) {
        _mode.value = value
        _autonomous.value = value == ApprovalMode.Autonomous
        save()
    }

    @Synchronized fun setAutonomous(on: Boolean) = setMode(if (on) ApprovalMode.Autonomous else ApprovalMode.Ask)

    @Synchronized private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(buildJsonObject { put("mode", _mode.value.name) }.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
