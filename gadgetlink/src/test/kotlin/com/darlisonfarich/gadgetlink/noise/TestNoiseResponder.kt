package com.darlisonfarich.gadgetlink.noise

/**
 * Respondedor de teste, escrito a partir do padrão Noise XX (e, ee, s, es / s, se) e do
 * NoiseXXResponder do SDK (musegadget/noise/noise_xx.py). Só os testes o usam: serve de
 * "Muse" falso. Ele reaproveita o NoiseSymmetric de produção; a prova independente de que o
 * protocolo está certo são os vetores gerados pela implementação de referência do SDK.
 */
class TestNoiseResponder(private val payload: ByteArray = ByteArray(0)) {
    private val ss = NoiseSymmetric()
    private var e: X25519KeyPair? = null
    private var re: ByteArray? = null

    fun readMessage1AndWriteMessage2(msg1: ByteArray): ByteArray {
        require(msg1.size >= Noise.DH_KEY_LEN)
        val remoteEphemeral = msg1.copyOfRange(0, Noise.DH_KEY_LEN)
        re = remoteEphemeral
        ss.mixHash(remoteEphemeral)
        ss.decryptAndHash(msg1.copyOfRange(Noise.DH_KEY_LEN, msg1.size))
        val ephemeral = X25519.generate().also { e = it }
        ss.mixHash(ephemeral.publicKey)
        ss.mixKey(X25519.dh(ephemeral.privateKey, remoteEphemeral))
        val s = X25519.generate()
        val encS = ss.encryptAndHash(s.publicKey)
        ss.mixKey(X25519.dh(s.privateKey, remoteEphemeral))
        val encPayload = ss.encryptAndHash(payload)
        return ephemeral.publicKey + encS + encPayload
    }

    fun readMessage3(msg3: ByteArray) {
        val rs = ss.decryptAndHash(msg3.copyOfRange(0, Noise.DH_KEY_LEN + Noise.TAG_LEN))
        ss.mixKey(X25519.dh(e!!.privateKey, rs))
        ss.decryptAndHash(msg3.copyOfRange(Noise.DH_KEY_LEN + Noise.TAG_LEN, msg3.size))
    }

    /** (envio, recepção) do respondedor. */
    fun split(): Pair<CipherState, CipherState> {
        val (c1, c2) = ss.split()
        return c2 to c1
    }

    fun handshakeHash(): ByteArray = ss.handshakeHash()
}
