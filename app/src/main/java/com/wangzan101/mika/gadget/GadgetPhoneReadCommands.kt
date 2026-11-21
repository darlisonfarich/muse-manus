package com.wangzan101.mika.gadget

import com.darlisonfarich.gadgetlink.commands.CommandException
import com.darlisonfarich.gadgetlink.commands.CommandResult
import com.darlisonfarich.gadgetlink.commands.CommandSpec
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import com.darlisonfarich.gadgetlink.commands.PhoneCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object GadgetPhoneReadCommands {
    fun specs(
        replayScope: () -> String?,
        read: suspend (screenshot: Boolean, full: Boolean) -> JsonObject,
    ) = listOf(
        CommandSpec(
            name = "phone.get_tree",
            description = "Passively read sanitized accessibility elements on the existing phone session's virtual display.",
            optional = mapOf("detail" to PhoneCatalog.TREE_DETAIL),
            timeoutMs = 30_000,
            writes = false,
            implemented = true,
            replayScope = replayScope,
        ) { params ->
            val full = when (params["detail"]?.jsonPrimitive?.content ?: "elements") {
                "elements" -> false
                "full" -> true
                else -> throw CommandException(ErrorCode.EXECUTOR_ERROR, "detail must be elements or full")
            }
            CommandResult.Ok(runBlocking { read(false, full) })
        },
        CommandSpec(
            name = "phone.get_screenshot",
            description = "Capture a privacy-checked frame of the existing phone session's virtual display.",
            timeoutMs = 30_000,
            writes = false,
            implemented = true,
            replayScope = replayScope,
        ) {
            CommandResult.Ok(runBlocking { read(true, false) })
        },
    )
}
