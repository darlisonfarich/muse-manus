// O WebSocket real do link (passo 4): liga o LinkSession a uma conexão OkHttp com o Muse. Equivale ao
// _open e à parte de conexão de LinkSession.run em musegadget/link_client.py do
// facebookincubator/muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates; Apache
// License 2.0). Mudanças: OkHttp no lugar do pacote websockets do Python; ping de 20 s como o SDK;
// o desfecho de um upgrade recusado (401/403) vem do status HTTP do onFailure.
package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.MuseApi
import com.darlisonfarich.gadgetlink.Vm
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

class OkHttpLinkLauncher(
    private val client: OkHttpClient,
    private val device: (PairingRecord) -> DeviceDescription,
    private val clock: LinkClock,
    /** wss em produção; os testes usam ws contra um servidor local. */
    private val secure: Boolean = true,
    private val log: (String) -> Unit = {},
    private val userAgent: String = MuseApi.USER_AGENT,
) : SessionLauncher {
    companion object {
        const val PING_INTERVAL_S = 20L
        const val HANDSHAKE_TIMEOUT_S = 20L
    }

    private class Done : SessionLauncher.Handle {
        override fun stop() {}
    }

    override fun launch(vm: Vm, pairing: PairingRecord, events: SessionLauncher.Events): SessionLauncher.Handle {
        val host = pairing.noiseHost.ifEmpty { LinkWire.DEFAULT_NOISE_HOST }
        val vmId = vm.vmId.ifEmpty { vm.vmName }
        val url = try {
            LinkWire.noiseUrl(host, vmId).let { if (secure) it else it.replaceFirst("wss://", "ws://") }
        } catch (e: IllegalArgumentException) {
            // O noise_host vem do celular que pareou: só host:porta puro vira URL (nada de caminho, usuário, consulta).
            log("invalid noise host; not connecting")
            events.onEnded(LinkOutcome.CLOSED, 0)
            return Done()
        }

        var registeredAtMs: Long? = null
        lateinit var ws: WebSocket
        val socket = object : LinkSocket {
            override fun sendBinary(data: ByteArray): Boolean = ws.send(data.toByteString())
            override fun close() {
                ws.close(1000, "done")
            }
        }
        val session = LinkSession(
            device = device(pairing),
            socket = socket,
            listener = object : LinkListener {
                override fun onRegistered() {
                    registeredAtMs = clock.nowMs()
                    events.onRegistered()
                }

                override fun onOutcome(outcome: LinkOutcome) {
                    val lasted = registeredAtMs?.let { clock.nowMs() - it } ?: 0L
                    events.onEnded(outcome, lasted)
                }

                override fun log(line: String) = this@OkHttpLinkLauncher.log(line)
            },
        )

        // O bearer da VM vai no cabeçalho do upgrade, nunca na URL nem no log.
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${vm.vmAuthToken}")
            .header("User-Agent", userAgent)
            .build()
        val http = client.newBuilder()
            .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
            .connectTimeout(HANDSHAKE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // o ping de 20 s é quem acusa uma conexão morta
            .build()
        log("connecting to ${vm.vmName.ifEmpty { vm.vmId }}")
        ws = http.newWebSocket(request, object : WebSocketListener() {
            private var opened = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                session.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = session.onBinary(bytes.toByteArray())

            override fun onMessage(webSocket: WebSocket, text: String) = session.onText()

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                session.onClosed("closing $code")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = session.onClosed("closed $code")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Antes de abrir, uma resposta HTTP é o upgrade recusado; depois, é a conexão que caiu.
                if (!opened && response != null) session.onUpgradeRejected(response.code) else session.onClosed(t.javaClass.simpleName)
            }
        })
        return object : SessionLauncher.Handle {
            override fun stop() = session.stop()
        }
    }
}
