package com.wangzan101.mika.mcp

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.wangzan101.mika.core.gate.ChatApprovalTokens
import com.wangzan101.mika.core.gate.ConsumedApprovalIds
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Android binding for chat approval signing and the durable, replay-safe ID ledger. */
internal class ChatApprovalTokenStore(private val directory: File) {
    private val ids = FileConsumedApprovalIds(File(directory, "chat-approval-consumed.log"))
    val tokens = ChatApprovalTokens(::key, ids)

    private fun key(): SecretKey {
        val alias = "mika-chat-approval-hmac"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).build())
            generateKey()
        }
    }

    private class FileConsumedApprovalIds(private val file: File) : ConsumedApprovalIds {
        private var loaded = false
        private var broken = false
        private val consumed = HashSet<String>()

        @Synchronized override fun consume(id: String): Boolean {
            load()
            if (broken || !ID.matches(id) || id in consumed) return false
            return try {
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { out ->
                    out.write((id + "\n").toByteArray(Charsets.US_ASCII))
                    out.fd.sync()
                }
                consumed += id
                true
            } catch (_: Exception) { false }
        }

        private fun load() {
            if (loaded) return
            loaded = true
            if (!file.exists()) return
            try {
                file.forEachLine(Charsets.US_ASCII) { line ->
                    if (!ID.matches(line) || !consumed.add(line)) broken = true
                }
            } catch (_: Exception) { broken = true }
        }

        companion object { private val ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") }
    }
}
