// Comandos de prova da Etapa 1 (linktest.*): reais e sem efeito nenhum no aparelho. Servem para o
// Ship medir o caminho Gadget Link de ponta a ponta — ida (echo_verify, o aparelho confere o hash do
// que recebeu) e volta (response_size_probe, o Ship confere o hash do que chegou).
package com.darlisonfarich.gadgetlink.commands

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

object LinkTestCommands {
    const val PING = "linktest.ping"
    const val ECHO_VERIFY = "linktest.echo_verify"
    const val RESPONSE_SIZE_PROBE = "linktest.response_size_probe"

    /** Teto do que o probe aceita gerar: acima do orçamento de propósito (para provar o too_large), mas finito. */
    const val MAX_PROBE_BYTES = 1024 * 1024
    const val DEFAULT_SEED = "muse-gadget-link"

    /** Maior seed aceito (caracteres): cada bloco do probe hasheia o seed inteiro, então ele precisa de teto. */
    const val MAX_SEED_CHARS = 256

    /** De quantos em quantos caracteres a geração do probe confere se foi interrompida (prazo vencido). */
    const val PROBE_CHUNK_CHARS = 4096

    fun all(clock: () -> Long = System::currentTimeMillis): List<CommandSpec> = listOf(ping(clock), echoVerify(), responseSizeProbe())

    fun ping(clock: () -> Long) = CommandSpec(
        name = PING,
        description = "Gadget Link test: replies pong with the phone's wall-clock time. No effect on the phone.",
        timeoutMs = 5_000,
        writes = false,
        implemented = true,
    ) {
        val now = clock()
        CommandResult.Ok(
            JsonObject(
                linkedMapOf(
                    "reply" to JsonPrimitive("pong"),
                    "device_time_ms" to JsonPrimitive(now),
                    "device_time" to JsonPrimitive(Instant.ofEpochMilli(now).toString()),
                ),
            ),
        )
    }

    fun echoVerify() = CommandSpec(
        name = ECHO_VERIFY,
        description = "Gadget Link test: receives a payload and returns only its size and SHA-256 (never the payload). " +
            "With expected_sha256, status says whether the phone received exactly that.",
        required = mapOf("data" to ParamSpec(ParamType.STRING, "Payload to verify (UTF-8 text, or base64 with encoding=base64).")),
        optional = mapOf(
            "encoding" to ParamSpec(ParamType.STRING, "utf8 (default) or base64: how data turns into the bytes that are hashed."),
            "expected_sha256" to ParamSpec(ParamType.STRING, "Lowercase hex SHA-256 the sender computed; status becomes match or mismatch."),
        ),
        timeoutMs = 10_000,
        writes = false,
        implemented = true,
    ) { params ->
        val data = (params["data"] as JsonPrimitive).content
        val bytes = when (val encoding = (params["encoding"] as? JsonPrimitive)?.content ?: "utf8") {
            "utf8" -> data.toByteArray(Charsets.UTF_8)
            "base64" -> try {
                Base64.getDecoder().decode(data)
            } catch (e: IllegalArgumentException) {
                return@CommandSpec CommandResult.Err(ErrorCode.EXECUTOR_ERROR, "data is not valid base64")
            }
            else -> return@CommandSpec CommandResult.Err(ErrorCode.EXECUTOR_ERROR, "encoding must be utf8 or base64, not ${encoding.take(16)}")
        }
        val sha = sha256Hex(bytes)
        val expected = (params["expected_sha256"] as? JsonPrimitive)?.content?.lowercase()
        val status = when (expected) {
            null -> "received"
            sha -> "match"
            else -> "mismatch"
        }
        CommandResult.Ok(
            JsonObject(linkedMapOf("bytes" to JsonPrimitive(bytes.size), "sha256" to JsonPrimitive(sha), "status" to JsonPrimitive(status))),
        )
    }

    fun responseSizeProbe() = CommandSpec(
        name = RESPONSE_SIZE_PROBE,
        description = "Gadget Link test: returns a deterministic ASCII payload of exactly the requested size, with its SHA-256, " +
            "so the receiver can check what arrived. Results over the 240 KiB budget fail with too_large instead of truncating.",
        required = mapOf("bytes" to ParamSpec(ParamType.INTEGER, "Payload size in bytes, 0 to $MAX_PROBE_BYTES.")),
        optional = mapOf("seed" to ParamSpec(ParamType.STRING, "Seed of the deterministic payload (default $DEFAULT_SEED, at most $MAX_SEED_CHARS characters).")),
        timeoutMs = 10_000,
        writes = false,
        implemented = true,
    ) { params ->
        val size = (params["bytes"] as JsonPrimitive).long
        if (size !in 0..MAX_PROBE_BYTES.toLong()) {
            return@CommandSpec CommandResult.Err(ErrorCode.EXECUTOR_ERROR, "bytes must be from 0 to $MAX_PROBE_BYTES")
        }
        val seed = (params["seed"] as? JsonPrimitive)?.content ?: DEFAULT_SEED
        if (seed.length > MAX_SEED_CHARS) {
            return@CommandSpec CommandResult.Err(ErrorCode.EXECUTOR_ERROR, "seed must be at most $MAX_SEED_CHARS characters")
        }
        // Interrompível: o prazo do despachante interrompe a thread e a geração para no próximo bloco.
        val data = probePayload(seed, size.toInt())
        CommandResult.Ok(
            JsonObject(
                linkedMapOf(
                    "bytes" to JsonPrimitive(data.length),
                    "sha256" to JsonPrimitive(sha256Hex(data.toByteArray(Charsets.US_ASCII))),
                    "seed" to JsonPrimitive(seed),
                    "data" to JsonPrimitive(data),
                ),
            ),
        )
    }

    /**
     * [size] caracteres hexadecimais minúsculos (ASCII, sem nada que o JSON precise escapar):
     * SHA-256(seed ‖ ":" ‖ contador decimal) para contador = 0, 1, 2…, concatenados e cortados.
     * Reprodutível em qualquer linguagem — o Ship regenera e compara.
     * A cada [PROBE_CHUNK_CHARS] caracteres a geração confere a interrupção da thread (o timeout do
     * despachante) e para com [InterruptedException] em vez de gerar o resto; [onChunk] (testes) é chamado
     * nesse mesmo ponto, com o número do bloco.
     */
    fun probePayload(seed: String, size: Int, onChunk: (Int) -> Unit = {}): String {
        val out = StringBuilder(size)
        val md = MessageDigest.getInstance("SHA-256")
        var counter = 0L
        var chunk = 0
        var nextCheck = 0
        while (out.length < size) {
            if (out.length >= nextCheck) {
                onChunk(chunk++)
                if (Thread.currentThread().isInterrupted) throw InterruptedException("probe generation interrupted")
                nextCheck += PROBE_CHUNK_CHARS
            }
            val block = sha256Hex(md, "$seed:$counter".toByteArray(Charsets.UTF_8))
            out.append(block, 0, minOf(block.length, size - out.length))
            counter++
        }
        return out.toString()
    }

    fun sha256Hex(bytes: ByteArray): String = sha256Hex(MessageDigest.getInstance("SHA-256"), bytes)

    private fun sha256Hex(md: MessageDigest, bytes: ByteArray): String {
        md.reset()
        val digest = md.digest(bytes)
        val hex = StringBuilder(64)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return hex.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
