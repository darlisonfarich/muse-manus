package com.wangzan101.mika.voice.wake

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Download once from the model author's HTTPS endpoint; hash pin prevents substitution.
 * No speech, recordings, identifiers, or microphone samples are uploaded.
 */
internal object WakeModelInstaller {
    const val NAME = "vosk-model-small-pt-0.3"
    const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"
    const val SHA256 = "6e1ce909032e1afa7a88e68a3d628ecafff302bdf195befab308826c395e93b7"
    private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 180L * 1024 * 1024
    private const val MAX_ENTRIES = 3_000

    private fun root(context: Context) = File(context.filesDir, "voice-wake-model")
    private fun installed(context: Context) = File(root(context), NAME)

    fun isInstalled(context: Context): Boolean {
        val root = installed(context)
        return File(root, ".verified-sha256").takeIf { it.isFile }?.readText()?.trim() == SHA256 &&
            File(root, "am/final.mdl").isFile && File(root, "conf/model.conf").isFile
    }

    suspend fun prepare(context: Context, progress: (Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        if (isInstalled(context)) return@withContext installed(context)
        val dir = root(context)
        dir.mkdirs()
        val archive = File(dir, NAME + ".zip.part")
        val staging = File(dir, NAME + ".unpacking")
        val destination = installed(context)
        archive.delete()
        staging.deleteRecursively()
        try {
            val connection = (URL(URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK)
                    throw IOException("HTTP do modelo: " + connection.responseCode)
                val advertised = connection.contentLengthLong
                if (advertised > MAX_DOWNLOAD_BYTES) throw IOException("Modelo acima do limite")
                val digest = MessageDigest.getInstance("SHA-256")
                connection.inputStream.use { input ->
                    archive.outputStream().buffered().use { output ->
                        val chunk = ByteArray(16_384)
                        var received = 0L
                        var reportedMb = -1L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(chunk)
                            if (count < 0) break
                            if (count == 0) continue
                            received += count
                            if (received > MAX_DOWNLOAD_BYTES) throw IOException("Modelo acima do limite")
                            digest.update(chunk, 0, count)
                            output.write(chunk, 0, count)
                            val mb = received / (1024 * 1024)
                            if (mb != reportedMb) { progress(received); reportedMb = mb }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (actual != SHA256) throw IOException("SHA-256 do modelo não confere")
            } finally {
                connection.disconnect()
            }

            staging.mkdirs()
            var expanded = 0L
            var entryCount = 0
            val stageCanonical = staging.canonicalPath + File.separator
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRIES) throw IOException("ZIP contém arquivos demais")
                    if (!entry.name.startsWith(NAME + "/") || entry.name.contains('\\'))
                        throw IOException("Caminho inválido no modelo")
                    val dest = File(staging, entry.name).canonicalFile
                    if (!dest.path.startsWith(stageCanonical)) throw IOException("ZIP fora do diretório")
                    if (entry.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        dest.outputStream().buffered().use { output ->
                            val bytes = ByteArray(16_384)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = zip.read(bytes)
                                if (n < 0) break
                                expanded += n
                                if (expanded > MAX_EXPANDED_BYTES) throw IOException("ZIP excedeu limite")
                                output.write(bytes, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val extracted = File(staging, NAME)
            if (!File(extracted, "am/final.mdl").isFile || !File(extracted, "conf/model.conf").isFile)
                throw IOException("Modelo Vosk incompleto")
            File(extracted, ".verified-sha256").writeText(SHA256)
            if (destination.exists() && !destination.deleteRecursively()) throw IOException("Modelo antigo não removido")
            if (!extracted.renameTo(destination)) throw IOException("Não foi possível instalar o modelo")
            destination
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }
}
