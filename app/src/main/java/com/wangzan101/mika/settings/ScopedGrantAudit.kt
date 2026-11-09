package com.wangzan101.mika.settings

import com.wangzan101.mika.core.gate.ScopedGrant
import java.io.File
import java.io.FileOutputStream

/**
 * Private append-only fsync audit, never serializes account names, app labels,
 * screen contents, tokens or action arguments. Only random grant id, class
 * and issuance/revocation metadata are retained.
 */
internal class ScopedGrantAudit(private val file: File) {
    @Synchronized fun record(event: String, grant: ScopedGrant) {
        require(event in setOf("issued", "revoked", "expired"))
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { output ->
            val row = listOf(System.currentTimeMillis(), event, grant.id,
                grant.actionClass.name, grant.expiresAtMs - grant.issuedAtMs)
                .joinToString("|") + "\n"
            output.write(row.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }
}
