// O catálogo que o app anuncia em commands_v2. Em registro normal só entram os comandos
// implementados; os stubs phone.* (Etapa 1, medição do orçamento do registro) só com includeStubs.
package com.darlisonfarich.gadgetlink.commands

import com.darlisonfarich.gadgetlink.noise.LinkWire
import kotlinx.serialization.json.JsonObject

class CommandCatalog(commands: List<CommandSpec>, val includeStubs: Boolean = false) {
    private val all: Map<String, CommandSpec> = LinkedHashMap<String, CommandSpec>().apply {
        for (c in commands) require(put(c.name, c) == null) { "duplicate command: ${c.name}" }
    }

    /** Os comandos anunciados (e portanto despacháveis) neste registro. */
    val announced: List<CommandSpec> = all.values.filter { it.implemented || includeStubs }

    fun find(name: String): CommandSpec? = all[name]?.takeIf { it.implemented || includeStubs }

    /** O objeto commands_v2 do link.register. */
    fun commandsV2(): JsonObject = JsonObject(announced.associateTo(LinkedHashMap()) { it.name to it.wire() })

    companion object {
        /** O catálogo do Muse Manus: linktest.* (implementados) + os 29 phone.* (stubs, fora do registro normal). */
        fun museManus(includeStubs: Boolean = false, clock: () -> Long = System::currentTimeMillis): CommandCatalog =
            CommandCatalog(LinkTestCommands.all(clock) + PhoneCatalog.stubs(), includeStubs)

        /** Bytes do link.register serializado como sai no fio (prefixo de tamanho incluído) — o que conta no teto de 256 KiB. */
        fun registerBytes(catalog: CommandCatalog, nodeId: String, displayName: String, version: String): Int =
            LinkWire.encodeMessage(
                com.darlisonfarich.gadgetlink.noise.DeviceDescription(nodeId, displayName, version, catalog.commandsV2())
                    .registerMessage("00000000-0000-0000-0000-000000000000"),
            ).size
    }
}
