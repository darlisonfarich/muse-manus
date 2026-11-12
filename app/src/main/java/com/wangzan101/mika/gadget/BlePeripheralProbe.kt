package com.wangzan101.mika.gadget

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Spike gate para "celular como gadget Muse".
 *
 * Um gadget Muse nativo precisa advertir como **periférico BLE** (GATT server)
 * durante o pareamento. A primeira versão desta sonda só lia flags e exigia
 * `isMultipleAdvertisementSupported`, mas o Android só devolve true nessa flag
 * quando o controlador tem 5 ou mais instâncias de anúncio — false NÃO quer dizer
 * "não anuncia", e o pareamento precisa de uma só. Por isso o veredito agora vem de
 * um teste real: um anúncio conectável curto (≈1 s) e a abertura de um GATT server,
 * ambos desfeitos logo em seguida.
 *
 * O teste só roda com a permissão "Dispositivos próximos" concedida (Android 12+);
 * conceder é o consentimento. Sem ela o veredito é "not tested", nunca "cannot".
 *
 * Ver docs/gadget-link.md (bloqueios B1/B2).
 */
data class BlePeripheralCapability(
    val bleSupported: Boolean,
    val advertiserPresent: Boolean,
    val multipleAdvertisementSupported: Boolean,
    val le2MPhySupported: Boolean,
    val extendedAdvertisingSupported: Boolean,
) {
    fun summary(): String = "ble=$bleSupported advertiser=$advertiserPresent " +
        "multiAdv=$multipleAdvertisementSupported le2M=$le2MPhySupported " +
        "extAdv=$extendedAdvertisingSupported"
}

/** Resultado da tentativa real de anunciar. */
sealed interface AdvertiseTrial {
    data object Started : AdvertiseTrial
    data class Failed(val code: Int) : AdvertiseTrial
    data object TimedOut : AdvertiseTrial
    data class NotRun(val reason: String) : AdvertiseTrial
}

/** Resultado da tentativa de abrir (e fechar) um GATT server. */
enum class GattTrial { OPENED, FAILED, NOT_RUN }

object BlePeripheralProbe {
    /** Códigos de [AdvertiseCallback.onStartFailure], repetidos aqui para o veredito ser testável na JVM. */
    const val FAILED_DATA_TOO_LARGE = 1
    const val FAILED_TOO_MANY_ADVERTISERS = 2
    const val FAILED_ALREADY_STARTED = 3
    const val FAILED_INTERNAL_ERROR = 4
    const val FAILED_FEATURE_UNSUPPORTED = 5

    private const val TRIAL_TIMEOUT_MS = 3_000L
    private const val HOLD_MS = 1_000L

    /** UUID só desta sonda; não é serviço do Muse. Aparece num scan (ex.: nRF Connect) enquanto o teste roda. */
    private val PROBE_SERVICE: UUID = UUID.fromString("6d757365-0000-4000-8000-70726f626531")

    fun failureName(code: Int): String = when (code) {
        FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
        FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
        FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        else -> "code $code"
    }

    /**
     * Veredito de uma linha. Só "can advertise" quando o anúncio de fato começou; só
     * "cannot advertise" quando o sistema disse FEATURE_UNSUPPORTED. Qualquer outra
     * coisa é inconclusiva — inclusive as flags, que não decidem mais nada.
     */
    fun verdict(trial: AdvertiseTrial, gatt: GattTrial): String = when (trial) {
        AdvertiseTrial.Started -> "can advertise (tested); gatt server=${gatt.name.lowercase()}"
        is AdvertiseTrial.Failed ->
            if (trial.code == FAILED_FEATURE_UNSUPPORTED) "cannot advertise (tested: FEATURE_UNSUPPORTED)"
            else "inconclusive (tested: ${failureName(trial.code)})"
        AdvertiseTrial.TimedOut -> "inconclusive (no answer in ${TRIAL_TIMEOUT_MS} ms)"
        is AdvertiseTrial.NotRun -> "not tested (${trial.reason})"
    }

    /** Lê apenas capacidades do adaptador; não abre advertising nem toca no rádio. */
    fun inspect(context: Context): BlePeripheralCapability {
        val ble = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val advertiser = adapter?.bluetoothLeAdvertiser
        return BlePeripheralCapability(
            bleSupported = ble,
            advertiserPresent = advertiser != null,
            multipleAdvertisementSupported = adapter?.isMultipleAdvertisementSupported == true,
            le2MPhySupported = adapter?.isLe2MPhySupported == true,
            extendedAdvertisingSupported = adapter?.isLeExtendedAdvertisingSupported == true,
        )
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Tenta um anúncio conectável de verdade, segura ~1 s e para. Bloqueia até
     * [TRIAL_TIMEOUT_MS] esperando o callback, então NÃO chamar na thread principal
     * (o callback chega por ela).
     */
    @SuppressLint("MissingPermission") // checado logo abaixo, por versão do Android
    fun tryAdvertise(context: Context): AdvertiseTrial {
        if (Looper.myLooper() == Looper.getMainLooper()) return AdvertiseTrial.NotRun("main thread")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !granted(context, Manifest.permission.BLUETOOTH_ADVERTISE)
        ) return AdvertiseTrial.NotRun("grant Nearby devices permission to test")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return AdvertiseTrial.NotRun("no bluetooth adapter")
        if (!adapter.isEnabled) return AdvertiseTrial.NotRun("bluetooth is off")
        val advertiser = adapter.bluetoothLeAdvertiser ?: return AdvertiseTrial.NotRun("no advertiser")

        val done = CountDownLatch(1)
        val result = AtomicReference<AdvertiseTrial>(AdvertiseTrial.TimedOut)
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                result.set(AdvertiseTrial.Started)
                done.countDown()
            }

            override fun onStartFailure(errorCode: Int) {
                result.set(AdvertiseTrial.Failed(errorCode))
                done.countDown()
            }
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(PROBE_SERVICE))
            .build()
        return try {
            advertiser.startAdvertising(settings, data, callback)
            done.await(TRIAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (result.get() == AdvertiseTrial.Started) Thread.sleep(HOLD_MS)
            result.get()
        } catch (e: SecurityException) {
            AdvertiseTrial.NotRun("security: ${e.message}")
        } catch (e: RuntimeException) {
            AdvertiseTrial.NotRun("error: ${e.javaClass.simpleName}")
        } finally {
            runCatching { advertiser.stopAdvertising(callback) }
        }
    }

    /** Abre um GATT server vazio e fecha na hora: o papel de periférico existe de fato? */
    @SuppressLint("MissingPermission") // checado logo abaixo, por versão do Android
    fun tryGattServer(context: Context): GattTrial {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !granted(context, Manifest.permission.BLUETOOTH_CONNECT)
        ) return GattTrial.NOT_RUN
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return GattTrial.NOT_RUN
        if (manager.adapter?.isEnabled != true) return GattTrial.NOT_RUN
        val server = runCatching { manager.openGattServer(context, object : BluetoothGattServerCallback() {}) }
            .getOrNull() ?: return GattTrial.FAILED
        runCatching { server.close() }
        return GattTrial.OPENED
    }

    /** A linha do "Copy diagnostics": veredito do teste real, depois as flags só como informação. */
    fun diagnose(context: Context): String {
        val trial = tryAdvertise(context)
        val gatt = if (trial == AdvertiseTrial.Started) tryGattServer(context) else GattTrial.NOT_RUN
        return verdict(trial, gatt) + "; flags: " + inspect(context).summary()
    }
}
