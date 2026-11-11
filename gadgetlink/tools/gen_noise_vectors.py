"""Gera vetores do Noise_XX_25519_AESGCM_SHA256 com a implementação de REFERÊNCIA do SDK.

Rodar com: venv\\Scripts\\python.exe -I gen_noise_vectors.py <sdk_src> <saida.json>
(fora da pasta do SDK; o SDK entra só pelo sys.path, nunca como diretório atual).
As chaves privadas são fixas para o resultado ser reproduzível; o SDK sorteia as suas.
"""
import json
import sys

sdk_src, out_path = sys.argv[1], sys.argv[2]
sys.path.insert(0, sdk_src)

from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import x25519  # noqa: E402

from musegadget.noise import noise_xx as m  # noqa: E402

KEYS = {
    "initiator_e": bytes(range(1, 33)),
    "responder_e": bytes(range(65, 97)),
    "responder_s": bytes(range(97, 129)),
    "initiator_s": bytes(range(33, 65)),
}
# Ordem em que o driver abaixo pede chaves ao SDK.
queue = [KEYS["initiator_e"], KEYS["responder_e"], KEYS["responder_s"], KEYS["initiator_s"]]


def fixed_pair():
    priv = x25519.X25519PrivateKey.from_private_bytes(queue.pop(0))
    pub = priv.public_key().public_bytes(
        encoding=serialization.Encoding.Raw, format=serialization.PublicFormat.Raw
    )
    return m._X25519KeyPair(private_key=priv, public_key_bytes=pub)


m._generate_x25519_key_pair = fixed_pair


def pub_of(raw):
    return x25519.X25519PrivateKey.from_private_bytes(raw).public_key().public_bytes(
        encoding=serialization.Encoding.Raw, format=serialization.PublicFormat.Raw
    )


payload = b"hatch-hello-payload"
ini = m.NoiseXXInitiator()
ini.initialize()
res = m.NoiseXXResponder(payload)
res.initialize()

msg1 = ini.write_message1()
msg2 = res.read_message1_and_write_message2(msg1)
got_payload = ini.read_message2(msg2)
assert got_payload == payload
msg3 = ini.write_message3()
res.read_message3(msg3)
assert ini.handshake_hash() == res.handshake_hash()
hh = ini.handshake_hash()
remote_static = ini.remote_static_public_key()
i_send, i_recv = ini.split()
r_send, r_recv = res.split()

# Transporte: iniciador -> respondedor e de volta, com e sem AD.
i2r = []
for pt, ad in [(b"first", b""), (b"", b""), (b"third message", b"assoc"), (bytes(range(200)), b"")]:
    ct = i_send.encrypt_with_ad(ad, pt)
    assert r_recv.decrypt_with_ad(ad, ct) == pt
    i2r.append({"ad": ad.hex(), "pt": pt.hex(), "ct": ct.hex()})
r2i = []
for pt, ad in [(b"reply one", b""), (b"reply two", b"x")]:
    ct = r_send.encrypt_with_ad(ad, pt)
    assert i_recv.decrypt_with_ad(ad, ct) == pt
    r2i.append({"ad": ad.hex(), "pt": pt.hex(), "ct": ct.hex()})

vec = {
    "protocol": m.PROTOCOL_NAME.decode(),
    "initiator_e_priv": KEYS["initiator_e"].hex(),
    "initiator_s_priv": KEYS["initiator_s"].hex(),
    "responder_e_priv": KEYS["responder_e"].hex(),
    "responder_s_priv": KEYS["responder_s"].hex(),
    "responder_s_pub": pub_of(KEYS["responder_s"]).hex(),
    "payload": payload.hex(),
    "msg1": msg1.hex(),
    "msg2": msg2.hex(),
    "msg3": msg3.hex(),
    "handshake_hash": hh.hex(),
    "remote_static": remote_static.hex(),
    "initiator_to_responder": i2r,
    "responder_to_initiator": r2i,
}
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(vec, f, indent=2)
    f.write("\n")
print("ok", len(msg1), len(msg2), len(msg3), hh.hex()[:16])
