package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.GadgetState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** O pairing.json que o Provisioner grava, lido e girado pelo supervisor (regras de config.pairing_problem do SDK). */
class GadgetPairingStoreTest {
    private fun fresh(): Pair<GadgetState, GadgetPairingStore> {
        val dir = Files.createTempDirectory("pairing-store").toFile().also { it.deleteOnExit() }
        val state = GadgetState(dir)
        return state to GadgetPairingStore(state)
    }

    private fun element(v: Any?): JsonElement = when (v) {
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        else -> JsonNull
    }

    private fun write(state: GadgetState, vararg pairs: Pair<String, Any?>) =
        state.saveJson(GadgetState.PAIRING_FILE, JsonObject(pairs.associate { (k, v) -> k to element(v) }))

    private fun complete(extra: Map<String, Any?> = emptyMap()): Array<Pair<String, Any?>> = (mapOf(
        "access_token" to "acc", "refresh_token" to "ref", "token_type" to "device", "username" to "alguem",
        "api_url" to "https://legacy", "api_url_v2" to "https://api.example", "noise_host" to "noise.example.org",
        "access_token_saved_at" to 1_700_000_000, "sdk_token_hint" to "mgst_abc123",
    ) + extra).map { it.key to it.value }.toTypedArray()

    @Test
    fun noFileAnEmptyObjectOrGarbageMeansNotPaired() {
        val (state, store) = fresh()
        assertEquals(PairingLoad.NotPaired, store.load())
        write(state)
        assertEquals("objeto vazio", PairingLoad.NotPaired, store.load())
        val garbage = Files.createTempDirectory("pairing-garbage").toFile().also { it.deleteOnExit() }
        File(garbage, GadgetState.PAIRING_FILE).writeText("{not json")
        assertEquals(PairingLoad.NotPaired, GadgetPairingStore(GadgetState(garbage)).load())
    }

    @Test
    fun aCompletePairingIsReadWithEveryField() {
        val (state, store) = fresh()
        write(state, *complete())
        val loaded = store.load() as PairingLoad.Paired
        assertEquals(PairingRecord("acc", "ref", "https://api.example", "noise.example.org", 1_700_000_000, "mgst_abc123"), loaded.record)
    }

    @Test
    fun optionalFieldsDefaultLikeAnOldRecord() {
        val (state, store) = fresh()
        write(state, "access_token" to "acc", "refresh_token" to "ref")
        val record = (store.load() as PairingLoad.Paired).record
        assertEquals("", record.apiUrlV2)
        assertEquals("", record.noiseHost)
        assertEquals("sem timestamp: renova no primeiro uso", 0L, record.accessTokenSavedAt)
        assertEquals(null, record.sdkTokenHint)
    }

    @Test
    fun missingBlankOrNonStringTokensAreAnInvalidPairingWithoutLeakingValues() {
        for (extra in listOf(
            mapOf("access_token" to null), mapOf("access_token" to ""), mapOf("access_token" to "   "),
            mapOf("refresh_token" to null), mapOf("refresh_token" to ""), mapOf("access_token" to 5),
        )) {
            val (state, store) = fresh()
            write(state, *complete(extra))
            val invalid = store.load() as PairingLoad.Invalid
            assertEquals("Saved pairing lacks device tokens.", invalid.problem)
            assertFalse(invalid.problem.contains("acc"))
        }
    }

    @Test
    fun anInvalidTimestampIsAnInvalidPairing() {
        for (stamp in listOf<Any?>("1700000000", true, -1, null)) {
            val (state, store) = fresh()
            write(state, *complete(mapOf("access_token_saved_at" to stamp)))
            assertEquals("stamp=$stamp", "Saved pairing has an invalid token timestamp.", (store.load() as PairingLoad.Invalid).problem)
        }
        val (state, store) = fresh()
        write(state, *complete(mapOf("access_token_saved_at" to 0)))
        assertTrue("0 e valido", store.load() is PairingLoad.Paired)
    }

    @Test
    fun savingRotatesTheTokensAndKeepsEveryOtherField() {
        val (state, store) = fresh()
        write(state, *complete())
        val record = (store.load() as PairingLoad.Paired).record
        store.save(record.copy(accessToken = "new-acc", refreshToken = "new-ref", accessTokenSavedAt = 1_800_000_000))
        val raw = state.loadJson(GadgetState.PAIRING_FILE)!!
        assertEquals("new-acc", raw["access_token"]!!.jsonPrimitive.content)
        assertEquals("new-ref", raw["refresh_token"]!!.jsonPrimitive.content)
        assertEquals(1_800_000_000L, raw["access_token_saved_at"]!!.jsonPrimitive.content.toLong())
        // o que o supervisor não gira continua igual
        assertEquals("device", raw["token_type"]!!.jsonPrimitive.content)
        assertEquals("alguem", raw["username"]!!.jsonPrimitive.content)
        assertEquals("https://legacy", raw["api_url"]!!.jsonPrimitive.content)
        assertEquals("https://api.example", raw["api_url_v2"]!!.jsonPrimitive.content)
        assertEquals("noise.example.org", raw["noise_host"]!!.jsonPrimitive.content)
        assertEquals("mgst_abc123", raw["sdk_token_hint"]!!.jsonPrimitive.content)
        assertEquals(PairingRecord("new-acc", "new-ref", "https://api.example", "noise.example.org", 1_800_000_000, "mgst_abc123"), (store.load() as PairingLoad.Paired).record)
    }

    @Test
    fun deletingForgetsThePairing() {
        val (state, store) = fresh()
        write(state, *complete())
        store.delete()
        assertEquals(PairingLoad.NotPaired, store.load())
        store.delete() // apagar de novo não falha
    }
}
