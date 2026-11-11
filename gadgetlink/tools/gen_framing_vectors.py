"""Gera vetores do framing do túnel Hatch (musegadget/noise/framing.py + _proto.py) com a
implementação de REFERÊNCIA do SDK, para o port em Kotlin ser conferido byte a byte.

Rodar com: python -I gen_framing_vectors.py <sdk_src> <saida.json>
(a partir de uma pasta que NÃO seja a do SDK; o SDK entra só pelo sys.path).
"""
import json
import sys

sdk_src, out_path = sys.argv[1], sys.argv[2]
sys.path.insert(0, sdk_src)

from musegadget.noise import framing as fr  # noqa: E402
from musegadget.noise import _proto as pr  # noqa: E402


def payload(n):
    return bytes((i * 7 + 3) % 256 for i in range(n))


def frame(chunk_id, index, total, data=b""):
    return fr.encode_noise_frame(fr.NoiseTransportFrame(chunk_id, index, total, data))


# ---- 1) codificação: payload + chunk_id -> lista de frames (hash dos grandes) -----------
import hashlib  # noqa: E402


def sha(b):
    return hashlib.sha256(b).hexdigest()


encode_cases = []
M = fr.MAX_CHUNK_PAYLOAD
for size, cid in [(0, 5), (1, -5), (100, 123456789012345), (M, 1), (M + 1, -9223372036854775808),
                  (2 * M + 10, 9223372036854775807), (3 * M, 77), (10, 0)]:
    frames = fr.encode_noise_frames(payload(size), cid)
    encode_cases.append({"size": size, "chunk_id": cid,
                         "frames": [{"len": len(f), "sha256": sha(f), **({"hex": f.hex()} if len(f) < 300 else {})}
                                    for f in frames]})

# ---- 2) decode_noise_frame de bytes crus (inclui lixo) -----------------------------------
raw_frames = {
    "empty": b"",
    "only_payload": pr.bytes_field(4, b"abc"),
    "unknown_varint_skipped": pr.varint_field(9, 300) + pr.bytes_field(4, b"x"),
    "unknown_fixed64_skipped": pr.encode_key(9, pr.WIRE_FIXED64) + bytes(8) + pr.bytes_field(4, b"x"),
    "unknown_fixed32_skipped": pr.encode_key(9, pr.WIRE_FIXED32) + bytes(4) + pr.bytes_field(4, b"x"),
    "unknown_delimited_skipped": pr.bytes_field(9, b"zzz") + pr.bytes_field(4, b"x"),
    "total_absent_defaults_to_one": pr.int64_field(1, 5) + pr.bytes_field(4, b"q"),
    "total_explicit_zero": bytes([0x18, 0x00]),
    "chunk_id_negative": pr.int64_field(1, -1),
    "chunk_id_wrong_wire": pr.bytes_field(1, b"a"),
    "index_wrong_wire": pr.bytes_field(2, b"a"),
    "total_wrong_wire": pr.bytes_field(3, b"a"),
    "payload_wrong_wire": pr.varint_field(4, 1),
    "truncated_varint": bytes([0x08, 0x80]),
    "overlong_varint": bytes([0x08]) + bytes([0xFF] * 9) + bytes([0x02]),
    "eleven_byte_varint": bytes([0x08]) + bytes([0x80] * 10) + bytes([0x00]),
    "truncated_delimited": bytes([0x22, 0x05, 0x61]),
    "field_zero": bytes([0x00, 0x01]),
    "reserved_field_19500": pr.encode_varint((19500 << 3) | 0) + pr.encode_varint(1),
    "field_max_ok": pr.encode_varint(((1 << 29) - 1) << 3 | 0) + pr.encode_varint(1),
    "field_too_big": pr.encode_varint((1 << 29) << 3 | 0) + pr.encode_varint(1),
    "wire_type_3": bytes([0x0B]),
    "index_uint32_overflow": pr.encode_key(2, 0) + pr.encode_varint(1 << 32),
    "truncated_fixed64": pr.encode_key(9, pr.WIRE_FIXED64) + bytes(3),
    "garbage": bytes([0xFF, 0xFF]),
}
decode_cases = []
for name, raw in raw_frames.items():
    try:
        f = fr.decode_noise_frame(raw)
        decode_cases.append({"name": name, "raw": raw.hex(), "ok": True, "chunk_id": f.chunk_id,
                             "chunk_index": f.chunk_index, "total_chunks": f.total_chunks,
                             "payload": f.payload.hex()})
    except Exception:
        decode_cases.append({"name": name, "raw": raw.hex(), "ok": False})

# ---- 3) cenários de remontagem: sequência de frames -> resultado de cada passo ------------
# Cada passo é uma "spec" que o teste em Kotlin materializa do mesmo jeito:
#   {"gen": [size, chunk_id, index]}  -> frame `index` de encode_noise_frames(payload(size), chunk_id)
#   {"frame": [chunk_id, index, total, data_len]} -> NoiseTransportFrame com payload de `data_len` zeros-ou-padrão
#   {"hex": "..."} -> bytes crus
def gen(size, cid, i):
    return {"gen": [size, cid, i]}


def fspec(cid, index, total, n=1):
    return {"frame": [cid, index, total, n]}


def materialize(spec):
    if "gen" in spec:
        size, cid, i = spec["gen"]
        return fr.encode_noise_frames(payload(size), cid)[i]
    if "frame" in spec:
        cid, index, total, n = spec["frame"]
        return frame(cid, index, total, bytes((j * 5 + 1) % 256 for j in range(n)))
    return bytes.fromhex(spec["hex"])


def run(specs):
    dec = fr.NoiseFrameDecoder()
    out = []
    for sp in specs:
        try:
            r = dec.decode(materialize(sp))
            out.append("none" if r is None else {"len": len(r), "sha256": sha(r)})
        except RuntimeError:
            out.append("poisoned")
        except Exception:
            out.append("error")
    return out


def seq(size, cid):
    n = max(1, -(-size // M))
    return [gen(size, cid, i) for i in range(n)]


scenarios = {}
c3 = seq(2 * M + 10, 7)
scenarios["in_order_three_chunks"] = c3
scenarios["out_of_order"] = [c3[2], c3[0], c3[1]]
scenarios["single_chunk"] = seq(50, 1)
scenarios["empty_payload"] = seq(0, 3)
scenarios["duplicate_index_poisons"] = [c3[0], c3[0], c3[1]]
scenarios["inconsistent_total"] = [fspec(9, 0, 3), fspec(9, 1, 4)]
scenarios["index_out_of_range"] = [fspec(1, 2, 2)]
scenarios["total_zero_raw"] = [{"hex": "1800"}]
scenarios["total_257"] = [fspec(1, 0, 257)]
scenarios["total_256_ok_first"] = [fspec(1, 0, 256)]
scenarios["payload_too_big"] = [fspec(1, 0, 1, M + 1)]
scenarios["payload_max_ok"] = [fspec(1, 0, 1, M)]
scenarios["too_many_pending"] = [fspec(100 + i, 0, 2) for i in range(17)]
scenarios["sixteen_pending_ok"] = [fspec(100 + i, 0, 2) for i in range(16)]
a, b = seq(M + 5, 11), seq(M + 9, 12)
scenarios["interleaved_two_ids"] = [a[0], b[0], b[1], a[1]]
scenarios["id_reusable_after_done"] = seq(10, 21) + seq(11, 21)
scenarios["garbage_poisons"] = [{"hex": "ffff"}, fspec(1, 0, 1)]
scenarios["negative_chunk_id"] = seq(M + 1, -42)

scenario_cases = [{"name": k, "steps": v, "expected": run(v)} for k, v in scenarios.items()]

out = {"max_chunk_payload": M, "encode": encode_cases, "decode": decode_cases, "scenarios": scenario_cases}
with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    json.dump(out, f, indent=1)
    f.write("\n")
print("ok", len(encode_cases), len(decode_cases), len(scenario_cases))
