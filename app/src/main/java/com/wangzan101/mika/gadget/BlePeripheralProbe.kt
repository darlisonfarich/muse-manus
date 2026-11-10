package com.wangzan101.mika.gadget

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Spike gate para "celular como gadget Muse".
 *
 * Um gadget Muse nativo precisa advertir como **periférico BLE** (GATT server)
 * durante o pareamento. Nem todo controlador Android consegue advertir. Esta sonda
 * responde isso SEM advertir e SEM precisar de permissão BLE em runtime, para rodar
 * antes de qualquer porte de protocolo. Se [BlePeripheralCapability.canAdvertise] for
 * falso no POCO X8 Pro, o pareamento on-device sai de cena e o caminho headless
 * (parear numa caixa Linux e copiar o estado de pareamento) passa a ser a única rota
 * — o que não inviabiliza o comunicador, já que o voice-call é só rede.
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
    /** Pré-requisito mínimo para pareamento BLE on-device. */
    val canAdvertise: Boolean
        get() = bleSupported && advertiserPresent && multipleAdvertisementSupported

    fun summary(): String = "ble=$bleSupported advertiser=$advertiserPresent " +
        "multiAdv=$multipleAdvertisementSupported le2M=$le2MPhySupported " +
        "extAdv=$extendedAdvertisingSupported canAdvertise=$canAdvertise"
}

object BlePeripheralProbe {
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
}
