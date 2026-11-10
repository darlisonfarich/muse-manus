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
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import com.darlisonfarich.gadgetlink.BleFraming
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
    private val pendingNotify = AtomicReference<CountDownLatch?>(null)
    private val notifyStatus = AtomicInteger(BluetoothGatt.GATT_SUCCESS)
    private val prepared = ByteArrayOutputStream()
    private var advertising: AdvertiseCallback? = null
    private var serviceAdded: CountDownLatch? = null
    @Volatile private var serviceAddStatus = BluetoothGatt.GATT_FAILURE

    /** O celular que está no setup agora (endereço), para o diagnóstico. */
    val clientAddress: String? get() = client?.address

    /**
     * Abre o servidor GATT e começa a anunciar. Bloqueia alguns segundos (não chamar
     * na thread principal). Devolve null se tudo subiu, ou o motivo de não ter subido.
     */
    fun start(): String? {
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
        val added = CountDownLatch(1).also { serviceAdded = it }
        if (!gatt.addService(service)) return "addService refused"
        if (!added.await(SERVICE_ADD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return "GATT service add timed out"
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
        val done = CountDownLatch(1)
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
        client = null
        subscribed = false
        pendingNotify.getAndSet(null)?.countDown()
        thread.quitSafely()
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
        handler.postDelayed({
            val device = client ?: return@postDelayed
            runCatching { server?.cancelConnection(device) }
        }, (delaySeconds * 1000).toLong())
    }

    /** Um notify por vez; espera onNotificationSent. Falso se não deu para mandar. */
    private fun notify(packet: ByteArray): Boolean {
        val gatt = server ?: return false
        val device = client ?: return false
        val characteristic = tx ?: return false
        if (!subscribed) {
            log("dropped ${packet.size}-byte notify: client not subscribed to TX")
            return false
        }
        val latch = CountDownLatch(1)
        pendingNotify.set(latch)
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.notifyCharacteristicChanged(device, characteristic, false, packet)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = packet
            @Suppress("DEPRECATION")
            if (gatt.notifyCharacteristicChanged(device, characteristic, false)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            pendingNotify.set(null)
            log("notify refused ($status)")
            return false
        }
        if (!latch.await(NOTIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            log("notify not confirmed in ${NOTIFY_TIMEOUT_MS} ms")
            return false
        }
        return notifyStatus.get() == BluetoothGatt.GATT_SUCCESS
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
                        prepared.reset()
                        log("client connected ${device.address}")
                    } else if (client?.address != device.address) {
                        log("second client ${device.address} ignored")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val ours = synchronized(this@GattTransport) {
                    (client?.address == device.address).also {
                        if (it) {
                            client = null
                            subscribed = false
                            currentMtu.set(DEFAULT_ATT_MTU)
                            prepared.reset()
                        }
                    }
                }
                if (ours) {
                    pendingNotify.getAndSet(null)?.countDown()
                    log("client disconnected ${device.address} (status $status)")
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
            val ours = adopt(device)
            val ok = ours && characteristic.uuid == RX_UUID && value != null
            if (responseNeeded) {
                gatt.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, offset, null)
            }
            if (!ok) return
            if (preparedWrite) {
                synchronized(prepared) {
                    if (offset != prepared.size()) prepared.reset() // fora de ordem: começa de novo
                    if (prepared.size() + value!!.size <= BleFraming.MAX_MESSAGE_BYTES) prepared.write(value) else prepared.reset()
                }
            } else {
                onWrite(value!!)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val bytes = synchronized(prepared) { prepared.toByteArray().also { prepared.reset() } }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (execute && bytes.isNotEmpty() && device.address == client?.address) onWrite(bytes)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val ours = adopt(device)
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
            notifyStatus.set(status)
            pendingNotify.getAndSet(null)?.countDown()
        }
    }

    /** Alguns centrais escrevem antes do callback de conexão chegar: adota se não há cliente. */
    private fun adopt(device: BluetoothDevice): Boolean = synchronized(this) {
        if (client == null) {
            client = device
            currentMtu.set(DEFAULT_ATT_MTU)
            log("client adopted on first write ${device.address}")
        }
        client?.address == device.address
    }
}
