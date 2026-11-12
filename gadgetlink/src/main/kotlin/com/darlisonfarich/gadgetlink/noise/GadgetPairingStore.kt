// O pairing.json do gadget (o que o Provisioner grava) visto pelo supervisor. As regras de validação são as
// de config.pairing_problem de musegadget/config.py do facebookincubator/muse-gadget-sdk (Copyright (c)
// Meta Platforms, Inc. and affiliates; Apache License 2.0); a gravação é atômica (GadgetState) e só troca
// o que o supervisor gira, preservando o resto do arquivo.
package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.GadgetState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

class GadgetPairingStore(private val state: GadgetState) : PairingStore {
    override fun load(): PairingLoad {
        val json = state.loadJson(GadgetState.PAIRING_FILE)
        if (json == null || json.isEmpty()) return PairingLoad.NotPaired
        val access = text(json["access_token"])
        val refresh = text(json["refresh_token"])
        if (access == null || access.isBlank() || refresh == null || refresh.isBlank()) {
            return PairingLoad.Invalid("Saved pairing lacks device tokens.")
        }
        // Registro antigo sem o carimbo vale 0 (renova no primeiro uso); carimbo que não é número é inválido.
        val stamp = json["access_token_saved_at"]?.let { number(it) ?: return PairingLoad.Invalid("Saved pairing has an invalid token timestamp.") } ?: 0.0
        if (stamp < 0 || stamp.isInfinite() || stamp.isNaN()) return PairingLoad.Invalid("Saved pairing has an invalid token timestamp.")
        return PairingLoad.Paired(
            PairingRecord(
                accessToken = access,
                refreshToken = refresh,
                apiUrlV2 = text(json["api_url_v2"]) ?: "",
                noiseHost = text(json["noise_host"]) ?: "",
                accessTokenSavedAt = stamp.toLong(),
                sdkTokenHint = text(json["sdk_token_hint"]),
            ),
        )
    }

    override fun save(record: PairingRecord) {
        val base = state.loadJson(GadgetState.PAIRING_FILE) ?: JsonObject(emptyMap())
        val merged = LinkedHashMap(base)
        merged["access_token"] = JsonPrimitive(record.accessToken)
        merged["refresh_token"] = JsonPrimitive(record.refreshToken)
        merged["access_token_saved_at"] = JsonPrimitive(record.accessTokenSavedAt)
        state.saveJson(GadgetState.PAIRING_FILE, JsonObject(merged))
    }

    override fun delete() = state.delete(GadgetState.PAIRING_FILE)

    private fun text(element: kotlinx.serialization.json.JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Só número de verdade: string e booleano não contam (como o `type(stamp) in (int, float)` do Python). */
    private fun number(element: kotlinx.serialization.json.JsonElement): Double? {
        val p = element as? JsonPrimitive ?: return null
        if (p.isString || p.booleanOrNull != null) return null
        return p.doubleOrNull
    }
}
