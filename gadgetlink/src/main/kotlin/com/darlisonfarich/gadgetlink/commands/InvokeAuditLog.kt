// A auditoria local das invocações do Gadget Link (especificação §2.5): uma linha por invocação, no
// mesmo padrão do direct-phone-audit do app (campos separados por |, append + fsync, nada de parâmetros
// nem payload). Quando passa de [maxBytes] o arquivo atual vira .1 (só uma geração guardada).
package com.darlisonfarich.gadgetlink.commands

import java.io.File
import java.io.FileOutputStream

class InvokeAuditLog(private val file: File, private val maxBytes: Long = 512 * 1024) {
    @Synchronized fun append(entry: InvokeAuditEntry) {
        file.parentFile?.mkdirs()
        if (file.length() > maxBytes) {
            val old = File(file.path + ".1")
            old.delete()
            file.renameTo(old)
        }
        FileOutputStream(file, true).use { out ->
            out.write((entry.line() + "\n").toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /** As últimas [n] linhas (para a Diagnostics). */
    @Synchronized fun tail(n: Int): List<String> = if (file.isFile) file.readLines(Charsets.UTF_8).takeLast(n) else emptyList()
}
