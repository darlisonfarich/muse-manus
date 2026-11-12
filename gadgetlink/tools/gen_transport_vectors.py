"""Gera vetores do transporte Noise do túnel Hatch (musegadget/noise/transport.py) com a
implementação de REFERÊNCIA do SDK: o que o cliente envia (registros cifrados) e como ele
interpreta o que recebe do servidor.

Rodar com: python -I gen_transport_vectors.py <sdk_src> <saida.json>
(a partir de uma pasta que NÃO seja a do SDK; o SDK entra só pelo sys.path).
As chaves do handshake e os chunk_id são fixos, para o resultado ser reproduzível.
"""
import hashlib
import itertools
import json
import sys

sdk_src, out_path = sys.argv[1], sys.argv[2]
sys.path.insert(0, sdk_src)

from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import x25519  # noqa: E402

from musegadget.noise import envelope as ev  # noqa: E402
from musegadget.noise import framing as fr  # noqa: E402
from musegadget.noise import noise_xx as nx  # noqa: E402
from musegadget.noise import transport as tp  # noqa: E402

KEYS = [bytes(range(1, 33)), bytes(range(65, 97)), bytes(range(97, 129)), bytes(range(33, 65))]
FIRST_CHUNK_ID = 1000


def sha(b):
    return hashlib.sha256(b).hexdigest()


def new_session():
    """Handshake completo com chaves fixas -> (transporte do cliente, cifras do servidor)."""
    queue = list(KEYS)  # e do iniciador, e e s do respondedor, s do iniciador

    def fixed_pair():
        priv = x25519.X25519PrivateKey.from_private_bytes(queue.pop(0))
        pub = priv.public_key().public_bytes(encoding=serialization.Encoding.Raw, format=serialization.PublicFormat.Raw)
        return nx._X25519KeyPair(private_key=priv, public_key_bytes=pub)

    nx._generate_x25519_key_pair = fixed_pair
    ids = itertools.count(FIRST_CHUNK_ID)
    fr._random_int64 = lambda: next(ids)
    ini = nx.NoiseXXInitiator()
    ini.initialize()
    res = nx.NoiseXXResponder(b"hatch-hello-payload")
    res.initialize()
    msg2 = res.read_message1_and_write_message2(ini.write_message1())
    ini.read_message2(msg2)
    res.read_message3(ini.write_message3())
    i_send, i_recv = ini.split()
    r_send, r_recv = res.split()
    return tp.NoiseTransport(i_send, i_recv), r_send, r_recv


def frames_json(frames):
    out = []
    for f in frames:
        item = {"len": len(f), "sha256": sha(f)}
        if len(f) < 400:
            item["hex"] = f.hex()
        out.append(item)
    return out


pattern70k = bytes((i * 13 + 5) % 256 for i in range(70000))

# ---- A) o que o cliente envia ---------------------------------------------------------------
t, _, _ = new_session()
send_ops = []


def op(name, args, result):
    frames = result.frames if hasattr(result, "frames") else result
    entry = {"name": name, "args": args, "frames": frames_json(frames)}
    if hasattr(result, "stream_id"):
        entry["stream_id"] = result.stream_id
    send_ops.append(entry)


op("start_control_stream", {"op": "start_stream_request", "method": "POST", "path": "/link-control"},
   t.start_stream_request("POST", "/link-control"))
op("register_body", {"op": "body_chunk", "stream": 1, "data": b"hello-register".hex(), "end": False},
   t.encrypt_body_chunk(1, b"hello-register"))
op("http_request_sentinel", {"op": "http_request", "method": "GET", "path": "/x", "body": b"abc".hex(),
                              "headers": [["k", "v"]], "service": "sentinel"},
   t.encrypt_http_request("GET", "/x", b"abc", "sentinel", [ev.Header("k", "v")]))
op("big_body_two_records", {"op": "body_chunk", "stream": 1, "data": "pattern70k", "end": False},
   t.encrypt_body_chunk(1, pattern70k))
op("reset_timeout", {"op": "reset", "stream": 1, "reason": "bye", "code": 2, "service": "daemon"},
   t.encrypt_reset(1, "daemon", "bye", ev.ResetCode.TIMEOUT))
op("end_body_empty", {"op": "body_chunk", "stream": 2, "data": "", "end": True, "service": "vault"},
   t.encrypt_body_chunk(2, b"", "vault", True))

# ---- B) o que o cliente recebe --------------------------------------------------------------
def spec_of_decrypted(d):
    v = d.value
    if d.kind == "response":
        val = {"status": v.status, "headers": [[h.key, h.value] for h in v.headers], "body": v.body.hex(), "end_body": v.end_body}
    elif d.kind == "body_chunk":
        val = {"data": v.data.hex() if len(v.data) < 400 else {"len": len(v.data), "sha256": sha(v.data)}, "end_body": v.end_body}
    else:
        val = {"code": int(v.code), "reason": v.reason}
    return {"kind": d.kind, "stream_id": d.stream_id, "value": val}


def server_records(r_send, frame):
    env = tp.encode_response_envelope(frame)
    return [r_send.encrypt_with_ad(b"", f) for f in fr.encode_noise_frames(env)]


def raw_records(r_send, service_response_bytes):
    return [r_send.encrypt_with_ad(b"", f) for f in fr.encode_noise_frames(service_response_bytes)]


def run_client(t, records):
    out = []
    for rec in records:
        try:
            d = t.decrypt_frame(rec)
            out.append("none" if d is None else spec_of_decrypted(d))
        except Exception:
            out.append("error")
    return out


def scenario(name, build):
    t, r_send, _ = new_session()
    records = build(r_send)
    return {"name": name, "records": [r.hex() if len(r) < 400 else {"len": len(r), "sha256": sha(r)} for r in records],
            "expected": [e if isinstance(e, str) else e for e in run_client(t, records)]}


def response_frame(sid, status, body=b"", end=False, headers=()):
    return ev.ServiceFrame.response(sid, ev.ApplicationResponse(status, [ev.Header(*h) for h in headers], body, end))


scenarios = []
scenarios.append(scenario("response_then_chunks_then_reset", lambda s: (
    server_records(s, response_frame(1, 200, b"ok", False, [("a", "b")]))
    + server_records(s, ev.ServiceFrame.body_chunk(1, ev.BodyChunk(b"m1", False)))
    + server_records(s, ev.ServiceFrame.body_chunk(1, ev.BodyChunk(b"m2", True)))
    + server_records(s, ev.ServiceFrame.reset(1, ev.Reset(ev.ResetCode.TIMEOUT, "t"))))))
scenarios.append(scenario("big_chunk_two_records", lambda s: server_records(
    s, ev.ServiceFrame.body_chunk(7, ev.BodyChunk(pattern70k, True)))))
scenarios.append(scenario("frame_without_kind_is_ignored", lambda s: (
    server_records(s, ev.ServiceFrame(3, None))
    + server_records(s, response_frame(3, 204)))))
scenarios.append(scenario("request_from_server_is_an_error", lambda s: (
    server_records(s, response_frame(1, 200))
    + server_records(s, ev.ServiceFrame.request(2, ev.ApplicationRequest("GET", "/")))
    + server_records(s, response_frame(1, 200)))))
scenarios.append(scenario("empty_service_response_payload_is_an_error", lambda s: (
    raw_records(s, b"") + server_records(s, response_frame(1, 200)))))
scenarios.append(scenario("garbage_service_response_is_an_error", lambda s: (
    raw_records(s, b"\xff\xff") + server_records(s, response_frame(1, 200)))))
scenarios.append(scenario("tampered_record_kills_the_transport", lambda s: (
    [bytes([r[0] ^ 1]) + r[1:] for r in server_records(s, response_frame(1, 200))]
    + server_records(s, response_frame(1, 200)))))
scenarios.append(scenario("replayed_record_is_an_error", lambda s: (
    lambda recs: recs + recs)(server_records(s, response_frame(1, 200)))))

out = {"first_chunk_id": FIRST_CHUNK_ID, "send": send_ops, "receive": scenarios}
with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    json.dump(out, f, indent=1)
    f.write("\n")
print("ok", len(send_ops), [(s["name"], [e if isinstance(e, str) else e["kind"] for e in s["expected"]]) for s in scenarios])
