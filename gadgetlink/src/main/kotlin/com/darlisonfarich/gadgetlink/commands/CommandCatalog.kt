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

    /** Quantos stubs (não implementados) entram neste commands_v2 — a contagem que a Diagnostics mostra. */
    val announcedStubs: Int get() = announced.count { !it.implemented }

    /** O objeto commands_v2 do link.register. */
    fun commandsV2(): JsonObject = JsonObject(announced.associateTo(LinkedHashMap()) { it.name to it.wire() })

    companion object {
        /** Catálogo da Etapa 1, usado para medir os stubs sem o host Android. */
        fun museManus(includeStubs: Boolean = false, clock: () -> Long = System::currentTimeMillis): CommandCatalog =
            museManus(emptyList(), includeStubs, clock)

        /** Substitui no catálogo somente as duas leituras da sessão Android. */
        fun museManus(
            readCommands: List<CommandSpec>,
            includeStubs: Boolean = false,
            clock: () -> Long = System::currentTimeMillis,
            controlCommands: List<CommandSpec> = emptyList(),
        ): CommandCatalog {
            val reads = readCommands.associateBy { it.name }
            require(reads.size == readCommands.size)
            require(readCommands.all { it.name in PHONE_READS && it.implemented && !it.writes })
            val replacements = (readCommands + controlCommands).associateBy { it.name }
            require(replacements.size == readCommands.size + controlCommands.size)
            val stubs = PhoneCatalog.stubs()
            require(controlCommands.all { c -> c.implemented && stubs.any { it.name == c.name && it.writes == c.writes } })
            return CommandCatalog(LinkTestCommands.all(clock) + stubs.map { replacements[it.name] ?: it }, includeStubs)
        }

        private val PHONE_READS = setOf("phone.get_tree", "phone.get_screenshot")

        /** Bytes do link.register serializado como sai no fio (prefixo de tamanho incluído) — o que conta no teto de 256 KiB. */
        fun registerBytes(catalog: CommandCatalog, nodeId: String, displayName: String, version: String): Int =
            LinkWire.encodeMessage(
                com.darlisonfarich.gadgetlink.noise.DeviceDescription(nodeId, displayName, version, catalog.commandsV2())
                    .registerMessage("00000000-0000-0000-0000-000000000000"),
            ).size
    }
}
