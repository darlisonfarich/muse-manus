package com.wangzan101.mika.gadget

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import com.darlisonfarich.gadgetlink.BleFraming
import com.darlisonfarich.gadgetlink.DisconnectWatchdog
import com.darlisonfarich.gadgetlink.SetupTransport
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * O periférico GATT do setup Muse Gadget no Android — o equivalente do
 * musegadget/ble_server.py do SDK (BlueZ): um serviço com RX (o celular escreve
 * comandos) e TX (o device notifica respostas), e um anúncio com o UUID do serviço,
 * a flag de pareado no manufacturer data e o nome MuseGadgetXXXXXX.
 *
 * Diferenças do Android que este arquivo resolve:
 * - o Android não deixa escolher o nome no anúncio: ele usa o nome do adaptador, então
 *   o nome é trocado durante o setup e devolvido depois ([AdapterName]);
 * - só pode haver um notify em voo: cada pacote espera onNotificationSent;
 * - o MTU real vem de onMtuChanged; até lá vale 23 (o Python presume 163, o que
 *   cortaria notificações se o celular não negociar);
 * - writes longos (prepare/execute) são remontados aqui;
 * - um cliente por vez; os outros recebem falha.
 *
 * Todas as chamadas que exigem BLUETOOTH_CONNECT/ADVERTISE só acontecem depois que
 * [GadgetLink] conferiu as permissões.
 */
@SuppressLint("MissingPermission")
class GattTransport(
    private val context: Context,
    private val bleName: String,
    private val onWrite: (ByteArray) -> Unit,
    private val onDisconnect: () -> Unit,
    private val log: (String) -> Unit,
    /** A queda forçada fechou o servidor e não deu para reabrir: a janela de setup morreu. */
    private val onReopenFailed: (String) -> Unit = {},
) : SetupTransport {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c")
        val RX_UUID: UUID = UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01")
        val TX_UUID: UUID = UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Manufacturer data que os apps leem como "já pareado"; 0xFFFF é o company id não atribuído. */
        const val PAIRED_FLAG_COMPANY_ID = 0xFFFF
        const val DEFAULT_ATT_MTU = 23
        private const val NOTIFY_TIMEOUT_MS = 2_000L
        private const val SERVICE_ADD_TIMEOUT_MS = 5_000L
        private const val ADVERTISE_TIMEOUT_MS = 5_000L
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val thread = HandlerThread("gadget-gatt").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var server: BluetoothGattServer? = null
    @Volatile private var tx: BluetoothGattCharacteristic? = null
    @Volatile private var client: BluetoothDevice? = null
    @Volatile private var subscribed = false
    private val currentMtu = AtomicInteger(DEFAULT_ATT_MTU)

    /** Um notify em voo: a trava e o status DESTE pacote. O status nasce FALHA —
     *  acordar a trava por fechamento ou desconexão não é confirmação de entrega. */
    private class PendingNotify {
        val latch = CountDownLatch(1)
        @Volatile var status: Int = BluetoothGatt.GATT_FAILURE
    }
    private val pendingNotify = AtomicReference<PendingNotify?>(null)
    private val prepared = ByteArrayOutputStream()
    private var advertising: AdvertiseCallback? = null
    @Volatile private var serviceAdded: CountDownLatch? = null
    @Volatile private var serviceAddStatus = BluetoothGatt.GATT_FAILURE
    @Volatile private var advertiseDone: CountDownLatch? = null
    /** Abrir, fechar e a queda forçada não se cruzam. */
    private val life = Any()
    @Volatile private var stopped = false

    /**
     * Medição da queda: quando ela foi pedida (uptime, ms) e para qual cliente. O Android só derruba o
     * enlace depois do próprio tempo de ociosidade do GATT, que fica fora do nosso controle; estes dois
     * campos deixam o log dizer quanto ele leva de verdade, em vez de supor.
     */
    @Volatile private var dropRequestedAtMs = 0L
    @Volatile private var dropRequestedFor: String? = null

    private fun sinceDropRequest(): String {
        val at = dropRequestedAtMs
        return if (at == 0L) "" else " (${SystemClock.elapsedRealtime() - at} ms after the drop request)"
    }

    /** O evento de desconexão do ACL é o instante em que o enlace de rádio cai, o mesmo que o notebook enxerga. */
    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            if (device.address == dropRequestedFor) {
                log("ACL link ${device.address} dropped${sinceDropRequest()}")
                dropRequestedAtMs = 0L
            } else {
                log("ACL link ${device.address} dropped")
            }
        }
    }
    private var aclReceiverRegistered = false

    private fun registerAclReceiver() {
        if (aclReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(aclReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(aclReceiver, filter)
            }
            aclReceiverRegistered = true
        }.onFailure { log("could not watch the ACL link (${it.javaClass.simpleName})") }
    }

    private fun unregisterAclReceiver() {
        if (!aclReceiverRegistered) return
        aclReceiverRegistered = false
        runCatching { context.unregisterReceiver(aclReceiver) }
    }

    /** O celular que está no setup agora (endereço), para o diagnóstico. */
    val clientAddress: String? get() = client?.address

    /**
     * Abre o servidor GATT e começa a anunciar. Bloqueia alguns segundos (não chamar
     * na thread principal). Devolve null se tudo subiu, ou o motivo de não ter subido.
     */
    fun start(): String? = synchronized(life) {
        if (stopped) return "setup closed"
        registerAclReceiver()
        openServerAndAdvertise()
    }

    private fun openServerAndAdvertise(): String? {
        val adapter = manager?.adapter ?: return "no bluetooth adapter"
        if (!adapter.isEnabled) return "bluetooth is off"
        val advertiser = adapter.bluetoothLeAdvertiser ?: return "no BLE advertiser"

        val gatt = manager.openGattServer(context, callback) ?: return "could not open GATT server"
        server = gatt
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        val txChar = BluetoothGattCharacteristic(
            TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        txChar.addDescriptor(
            BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        service.addCharacteristic(txChar)
        tx = txChar
        // Numa reabertura o status da vez anterior ainda estaria aqui: só vale o desta.
        serviceAddStatus = BluetoothGatt.GATT_FAILURE
        val added = CountDownLatch(1).also { serviceAdded = it }
        if (!gatt.addService(service)) return "addService refused"
        if (!added.await(SERVICE_ADD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return "GATT service add timed out"
        if (stopped) return "setup closed"
        if (serviceAddStatus != BluetoothGatt.GATT_SUCCESS) return "GATT service add failed ($serviceAddStatus)"
        log("GATT service up")

        return startAdvertising(advertiser, withName = true)
            // Nome do adaptador grande demais para a scan response: anuncia sem nome (o UUID basta para achar).
            ?.let { error -> if (error.contains("DATA_TOO_LARGE")) startAdvertising(advertiser, withName = false) else error }
    }

    private fun startAdvertising(advertiser: android.bluetooth.le.BluetoothLeAdvertiser, withName: Boolean): String? {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // 3 (flags) + 18 (UUID de 128 bits) + 5 (manufacturer) = 26 de 31 bytes.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addManufacturerData(PAIRED_FLAG_COMPANY_ID, byteArrayOf(0))
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(withName).build()
        val done = CountDownLatch(1).also { advertiseDone = it }
        val result = AtomicReference<String?>("advertising did not answer")
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                result.set(null)
                done.countDown()
            }

            override fun onStartFailure(errorCode: Int) {
                result.set("advertising failed: ${advertiseError(errorCode)}")
                done.countDown()
            }
        }
        advertiser.startAdvertising(settings, data, scanResponse, cb)
        done.await(ADVERTISE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (stopped) {
            runCatching { advertiser.stopAdvertising(cb) }
            return "setup closed"
        }
        val error = result.get()
        if (error == null) {
            advertising = cb
            log(if (withName) "advertising as $bleName" else "advertising (service UUID only; name too long)")
        } else {
            runCatching { advertiser.stopAdvertising(cb) }
        }
        return error
    }

    private fun advertiseError(code: Int) = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        else -> "code $code"
    }

    /** Para o anúncio, derruba o cliente e fecha o servidor. Pode ser chamado mais de uma vez. */
    fun stop() {
        stopped = true
        // Quem está abrindo (start ou reabertura da queda forçada) segura `life` e espera
        // por estas travas por até ~10 s; solta as duas para o stop — que roda na thread
        // principal, no onDestroy do serviço — não ficar preso atrás dele.
        serviceAdded?.countDown()
        advertiseDone?.countDown()
        synchronized(life) { closeServer() }
        unregisterAclReceiver()
        thread.quitSafely()
    }

    private fun closeServer() {
        advertising?.let { cb ->
            runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
            advertising = null
        }
        server?.let { gatt ->
            client?.let { runCatching { gatt.cancelConnection(it) } }
            runCatching { gatt.clearServices() }
            runCatching { gatt.close() }
        }
        server = null
        synchronized(this) {
            client = null
            subscribed = false
            currentMtu.set(DEFAULT_ATT_MTU)
        }
        synchronized(prepared) { prepared.reset() }
        pendingNotify.getAndSet(null)?.latch?.countDown()
    }

    /**
     * Queda garantida (o [watchdog] chama quando o cancelConnection não derrubou):
     * fecha o servidor GATT — com ele some a conexão deste app — limpa a sessão como
     * numa desconexão e, se a janela de setup ainda está aberta, reabre serviço e anúncio.
     */
    private fun forceOff(device: BluetoothDevice) {
        synchronized(life) {
            if (stopped) return
            closeServer()
            watchdog.connectionChanged()
            log("client disconnected ${device.address} (forced: GATT server closed)${sinceDropRequest()}")
            onDisconnect()
            if (stopped) return
            val error = openServerAndAdvertise()
            if (error == null) log("setup reopened after forced disconnect") else {
                log("could not reopen setup: $error")
                onReopenFailed(error)
            }
        }
    }

    // -- SetupTransport --------------------------------------------------------------------

    override fun mtu(): Int = currentMtu.get()

    override fun sendPackets(packets: List<ByteArray>) {
        packets.forEachIndexed { i, packet ->
            if (i > 0) Thread.sleep(BleFraming.CHUNK_STAGGER_MS)
            if (!notify(packet)) return
        }
    }

    override fun disconnect(delaySeconds: Double) {
        dropRequestedFor = client?.address
        dropRequestedAtMs = SystemClock.elapsedRealtime()
        watchdog.request((delaySeconds * 1000).toLong())
    }

    /** Faz a queda pedida pelo setup acontecer de verdade (ver [DisconnectWatchdog]). */
    private val watchdog = DisconnectWatchdog(
        link = object : DisconnectWatchdog.Link<BluetoothDevice> {
            override fun current(): BluetoothDevice? = client
            override fun describe(client: BluetoothDevice): String = client.address
            override fun cancel(client: BluetoothDevice) {
                val gatt = server ?: error("GATT server closed")
                // O cancelConnection do servidor só age numa conexão que o servidor "conhece":
                // connect(…, false) registra a conexão já aberta pelo central antes de cancelar.
                gatt.connect(client, false)
                gatt.cancelConnection(client)
            }
            override fun forceOff(client: BluetoothDevice) = this@GattTransport.forceOff(client)
        },
        schedule = { delayMs, task -> handler.postDelayed(task, delayMs) },
        log = log,
    )

    /** Um notify por vez; espera onNotificationSent. Falso se não deu para mandar. */
    private fun notify(packet: ByteArray): Boolean {
        val gatt = server ?: return false
        val device = client ?: return false
        val characteristic = tx ?: return false
        if (!subscribed) {
            log("dropped ${packet.size}-byte notify: client not subscribed to TX")
            return false
        }
        val pending = PendingNotify()
        pendingNotify.set(pending)
        // Fora de runCatching, uma SecurityException (permissão revogada no meio da
        // sessão) subiria pelo sendPackets e emudeceria todos os envios seguintes.
        val status = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.notifyCharacteristicChanged(device, characteristic, false, packet)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = packet
                @Suppress("DEPRECATION")
                if (gatt.notifyCharacteristicChanged(device, characteristic, false)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            }
        }.getOrElse {
            pendingNotify.compareAndSet(pending, null)
            log("notify failed: ${it.javaClass.simpleName}")
            return false
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            pendingNotify.compareAndSet(pending, null)
            log("notify refused ($status)")
            return false
        }
        if (!pending.latch.await(NOTIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            // Limpa o pendente: a confirmação atrasada deste pacote não pode
            // derrubar a trava do pacote seguinte nem emprestar o status a ele.
            pendingNotify.compareAndSet(pending, null)
            log("notify not confirmed in ${NOTIFY_TIMEOUT_MS} ms")
            return false
        }
        return pending.status == BluetoothGatt.GATT_SUCCESS
    }

    // -- Callbacks do GATT (threads de binder) -----------------------------------------

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            serviceAddStatus = status
            serviceAdded?.countDown()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized(this@GattTransport) {
                    if (client == null) {
                        client = device
                        subscribed = false
                        currentMtu.set(DEFAULT_ATT_MTU)
                        synchronized(prepared) { prepared.reset() }
                        watchdog.connectionChanged()
                        log("client connected ${device.address}")
                    } else if (client?.address != device.address) {
                        log("second client ${device.address} ignored")
                    } else {
                        // CONNECTED de novo sem DISCONNECTED antes (o callback se
                        // perdeu): a conexão nova começa do zero — MTU 23 e nenhum
                        // long write parcial da conexão anterior.
                        currentMtu.set(DEFAULT_ATT_MTU)
                        synchronized(prepared) { prepared.reset() }
                        log("client reconnected ${device.address} (no prior disconnect)")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val ours = synchronized(this@GattTransport) {
                    (client?.address == device.address).also {
                        if (it) {
                            client = null
                            subscribed = false
                            currentMtu.set(DEFAULT_ATT_MTU)
                            synchronized(prepared) { prepared.reset() }
                            watchdog.connectionChanged()
                        }
                    }
                }
                if (ours) {
                    pendingNotify.getAndSet(null)?.latch?.countDown()
                    log("client disconnected ${device.address} (status $status)${sinceDropRequest()}")
                    onDisconnect()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (device.address == client?.address) {
                currentMtu.set(mtu)
                log("ATT MTU $mtu")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val gatt = server ?: return
            val ours = adopt(device, characteristic.uuid == RX_UUID)
            val ok = ours && characteristic.uuid == RX_UUID && value != null
            if (responseNeeded) {
                gatt.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, offset, null)
            }
            if (!ok) return
            if (preparedWrite) {
                val v = value!!
                synchronized(prepared) {
                    when {
                        // Recomeço legítimo: descarta o parcial e guarda o pedaço novo.
                        offset == 0 -> {
                            prepared.reset()
                            if (v.size <= BleFraming.MAX_MESSAGE_BYTES) prepared.write(v) else Unit
                        }
                        // Fora de ordem: descarta o parcial E o pedaço — anexar o
                        // pedaço velho como início remontaria a mensagem corrompida.
                        offset != prepared.size() -> prepared.reset()
                        prepared.size() + v.size <= BleFraming.MAX_MESSAGE_BYTES -> prepared.write(v)
                        else -> prepared.reset()
                    }
                }
            } else {
                onWrite(value!!)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            // Só o cliente drena o buffer: um segundo aparelho conectado (o
            // "ignored") não pode apagar o long write em andamento do cliente.
            val ours = device.address == client?.address
            val bytes = if (ours) {
                synchronized(prepared) { prepared.toByteArray().also { prepared.reset() } }
            } else {
                ByteArray(0)
            }
            server?.sendResponse(device, requestId, if (ours) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            if (execute && bytes.isNotEmpty()) onWrite(bytes)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val ours = adopt(device, descriptor.uuid == CCCD_UUID && descriptor.characteristic?.uuid == TX_UUID)
            val ok = ours && descriptor.uuid == CCCD_UUID && value != null && value.isNotEmpty()
            if (ok) {
                subscribed = (value!![0].toInt() and 0x03) != 0
                log(if (subscribed) "client subscribed to TX" else "client unsubscribed from TX")
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value.copyOfRange(minOf(offset, value.size), value.size))
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            // O TX só tem leitura por paridade com o SDK; nada de útil para ler fora das notificações.
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            pendingNotify.getAndSet(null)?.let { pending ->
                pending.status = status
                pending.latch.countDown()
            }
        }
    }

    /**
     * Alguns centrais escrevem antes do callback de conexão chegar: adota se não há
     * cliente — mas só pelos canais do setup (RX ou o CCCD do TX). Escrita em outro
     * lugar não adota: senão qualquer aparelho próximo que escreva 1 byte vira "o
     * cliente" e o app Muse legítimo é ignorado pelo resto da janela.
     */
    private fun adopt(device: BluetoothDevice, onSetupChannel: Boolean): Boolean = synchronized(this) {
        if (client == null && onSetupChannel) {
            client = device
            currentMtu.set(DEFAULT_ATT_MTU)
            watchdog.connectionChanged()
            log("client adopted on first write ${device.address}")
        }
        client?.address == device.address
    }
}
