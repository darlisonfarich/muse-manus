"""Gera vetores dos envelopes de serviço do túnel Hatch (musegadget/noise/envelope.py) com a
implementação de REFERÊNCIA do SDK, para o port em Kotlin ser conferido byte a byte.

Rodar com: python -I gen_envelope_vectors.py <sdk_src> <saida.json>
(a partir de uma pasta que NÃO seja a do SDK; o SDK entra só pelo sys.path).

Formato das "specs" (iguais nos dois lados):
  frame            {"stream_id": n, "kind": "request|response|body_chunk|reset|null", "value": {...}}
  request          {"verb","path","headers":[[k,v]...],"body":hex,"end_body":bool}
  response         {"status","headers","body":hex,"end_body"}
  body_chunk       {"data":hex,"end_body"}
  reset            {"code":int,"reason"}
  service_request  {"service":int,"payload":hex}
  service_response {"payload":hex}
"""
import json
import sys

sdk_src, out_path = sys.argv[1], sys.argv[2]
sys.path.insert(0, sdk_src)

from musegadget.noise import _proto as pr  # noqa: E402
from musegadget.noise import envelope as ev  # noqa: E402


def hexb(b):
    return bytes(b).hex()


def unhex(h):
    return bytes.fromhex(h)


# ---- spec <-> objetos da referência ---------------------------------------------------------
def build(kind, spec):
    if kind == "frame":
        k = spec["kind"]
        v = spec.get("value")
        value = None
        if k == "request":
            value = ev.ApplicationRequest(spec["value"]["verb"], spec["value"]["path"],
                                          [ev.Header(a, b) for a, b in spec["value"]["headers"]],
                                          unhex(spec["value"]["body"]), spec["value"]["end_body"])
        elif k == "response":
            value = ev.ApplicationResponse(v["status"], [ev.Header(a, b) for a, b in v["headers"]],
                                           unhex(v["body"]), v["end_body"])
        elif k == "body_chunk":
            value = ev.BodyChunk(unhex(v["data"]), v["end_body"])
        elif k == "reset":
            value = ev.Reset(ev.ResetCode(v["code"]), v["reason"])
        return ev.ServiceFrame(spec["stream_id"], k, value)
    if kind == "service_request":
        return ev.ServiceRequest(ev.ServiceType(spec["service"]), unhex(spec["payload"]))
    if kind == "service_response":
        return ev.ServiceResponse(unhex(spec["payload"]))
    raise ValueError(kind)


def to_spec(kind, obj):
    if kind == "frame":
        v = obj.value
        if obj.kind == "request":
            val = {"verb": v.verb, "path": v.path, "headers": [[h.key, h.value] for h in v.headers],
                   "body": hexb(v.body), "end_body": v.end_body}
        elif obj.kind == "response":
            val = {"status": v.status, "headers": [[h.key, h.value] for h in v.headers],
                   "body": hexb(v.body), "end_body": v.end_body}
        elif obj.kind == "body_chunk":
            val = {"data": hexb(v.data), "end_body": v.end_body}
        elif obj.kind == "reset":
            val = {"code": int(v.code), "reason": v.reason}
        else:
            val = None
        return {"stream_id": obj.stream_id, "kind": obj.kind, "value": val}
    if kind == "service_request":
        return {"service": int(obj.service), "payload": hexb(obj.payload)}
    return {"payload": hexb(obj.payload)}


ENC = {"frame": ev.encode_service_frame, "service_request": ev.encode_service_request,
       "service_response": ev.encode_service_response}
DEC = {"frame": ev.decode_service_frame, "service_request": ev.decode_service_request,
       "service_response": ev.decode_service_response}

pattern = bytes((i * 11 + 2) % 256 for i in range(300))

# ---- 1) codificação/ida e volta -------------------------------------------------------------
encode_specs = [
    ("req_basic", "frame", {"stream_id": 1, "kind": "request", "value": {
        "verb": "POST", "path": "/link-control",
        "headers": [["content-type", "application/json"], ["", ""], ["k", ""], ["", "v"]],
        "body": hexb(b"{}"), "end_body": False}}),
    ("req_big_stream_end", "frame", {"stream_id": 1 << 40, "kind": "request", "value": {
        "verb": "GET", "path": "/", "headers": [], "body": "", "end_body": True}}),
    ("req_empty", "frame", {"stream_id": 3, "kind": "request", "value": {
        "verb": "", "path": "", "headers": [], "body": "", "end_body": False}}),
    ("resp_ok", "frame", {"stream_id": 4, "kind": "response", "value": {
        "status": 200, "headers": [["a", "b"]], "body": hexb(b"ok"), "end_body": True}}),
    ("resp_negative_status", "frame", {"stream_id": 4, "kind": "response", "value": {
        "status": -1, "headers": [], "body": "", "end_body": False}}),
    ("resp_zero_status", "frame", {"stream_id": 4, "kind": "response", "value": {
        "status": 0, "headers": [], "body": "", "end_body": False}}),
    ("chunk_negative_stream", "frame", {"stream_id": -7, "kind": "body_chunk", "value": {
        "data": hexb(pattern), "end_body": True}}),
    ("chunk_empty", "frame", {"stream_id": 9, "kind": "body_chunk", "value": {"data": "", "end_body": False}}),
    ("reset_timeout_unicode", "frame", {"stream_id": 5, "kind": "reset", "value": {
        "code": 2, "reason": "tempo é ouro \U0001F680"}}),
    ("reset_unspecified_empty", "frame", {"stream_id": 6, "kind": "reset", "value": {"code": 0, "reason": ""}}),
    ("reset_service_unavailable", "frame", {"stream_id": 6, "kind": "reset", "value": {"code": 6, "reason": "x"}}),
    ("frame_no_kind", "frame", {"stream_id": 5, "kind": None, "value": None}),
    ("frame_nothing", "frame", {"stream_id": 0, "kind": None, "value": None}),
    ("sreq_daemon_empty", "service_request", {"service": 0, "payload": ""}),
    ("sreq_sentinel", "service_request", {"service": 1, "payload": hexb(b"abc")}),
    ("sreq_authd", "service_request", {"service": 3, "payload": hexb(pattern[:10])}),
    ("sresp_empty", "service_response", {"payload": ""}),
    ("sresp_payload", "service_response", {"payload": hexb(b"abc")}),
]
encode_cases = []
for name, kind, spec in encode_specs:
    raw = ENC[kind](build(kind, spec))
    back = to_spec(kind, DEC[kind](raw))
    encode_cases.append({"name": name, "kind": kind, "spec": spec, "hex": hexb(raw), "decoded": back})

# ---- 2) decodificação de bytes crus (inclui malformados) ------------------------------------
V, D = pr.WIRE_VARINT, pr.WIRE_DELIMITED
req_ok = ev.encode_application_request(ev.ApplicationRequest("GET", "/x", [ev.Header("h", "1")], b"b", True))
chunk_ok = ev.encode_body_chunk(ev.BodyChunk(b"zz", True))
resp_ok = ev.encode_application_response(ev.ApplicationResponse(204, [ev.Header("k", "v")], b"", False))
raw = []


def add(name, kind, data):
    raw.append((name, kind, bytes(data)))


add("frame_unknown_fields_skipped", "frame",
    pr.varint_field(9, 7) + pr.bytes_field(9, b"zzz") + pr.int64_field(1, 3) + pr.delimited_field(4, chunk_ok))
add("frame_last_kind_wins", "frame", pr.delimited_field(2, req_ok) + pr.delimited_field(4, chunk_ok))
add("frame_same_kind_twice_last_wins", "frame", pr.delimited_field(4, chunk_ok) + pr.delimited_field(4, ev.encode_body_chunk(ev.BodyChunk(b"q", False))))
add("frame_stream_wrong_wire", "frame", pr.bytes_field(1, b"a"))
add("frame_request_wrong_wire", "frame", pr.varint_field(2, 1))
add("frame_response_wrong_wire", "frame", pr.varint_field(3, 1))
add("frame_chunk_wrong_wire", "frame", pr.varint_field(4, 1))
add("frame_reset_wrong_wire", "frame", pr.varint_field(5, 1))
add("frame_empty_request", "frame", pr.delimited_field(2, b""))
add("frame_empty_response", "frame", pr.delimited_field(3, b""))
add("frame_empty_reset", "frame", pr.delimited_field(5, b""))
add("frame_negative_stream", "frame", pr.int64_field(1, -1) + pr.delimited_field(3, resp_ok))
add("frame_response_status_overflow", "frame", pr.delimited_field(3, pr.varint_field(1, 1 << 31)))
add("frame_response_status_min", "frame", pr.delimited_field(3, pr.int32_field(1, -(1 << 31))))
add("frame_response_status_negative", "frame", pr.delimited_field(3, pr.int32_field(1, -5)))
add("frame_response_status_too_negative", "frame", pr.delimited_field(3, pr.varint_field(1, (1 << 64) - (1 << 31) - 1)))
add("frame_response_end_body_two", "frame", pr.delimited_field(3, pr.varint_field(4, 2)))
add("frame_request_end_body_two", "frame", pr.delimited_field(2, pr.varint_field(5, 2)))
add("frame_request_verb_varint", "frame", pr.delimited_field(2, pr.varint_field(1, 1)))
add("frame_request_header_wrong_wire", "frame", pr.delimited_field(2, pr.varint_field(3, 1)))
add("frame_header_bad_utf8", "frame", pr.delimited_field(2, pr.delimited_field(3, bytes([0x0A, 0x01, 0xFF]))))
add("frame_header_key_wrong_wire", "frame", pr.delimited_field(2, pr.delimited_field(3, pr.varint_field(1, 1))))
add("frame_header_value_wrong_wire", "frame", pr.delimited_field(2, pr.delimited_field(3, pr.varint_field(2, 1))))
add("frame_request_bad_utf8_path", "frame", pr.delimited_field(2, bytes([0x12, 0x02, 0xC3, 0x28])))
add("frame_request_unknown_inner_skipped", "frame", pr.delimited_field(2, pr.varint_field(9, 1) + pr.string_field(1, "GET")))
add("frame_reset_code_unknown", "frame", pr.delimited_field(5, pr.varint_field(1, 99)))
add("frame_reset_code_negative", "frame", pr.delimited_field(5, pr.int32_field(1, -1)))
add("frame_reset_code_ok", "frame", pr.delimited_field(5, pr.varint_field(1, 3) + pr.string_field(2, "bad")))
add("frame_reset_reason_bad_utf8", "frame", pr.delimited_field(5, bytes([0x12, 0x01, 0x80])))
add("frame_reset_code_wrong_wire", "frame", pr.delimited_field(5, pr.bytes_field(1, b"a")))
add("frame_chunk_data_wrong_wire", "frame", pr.delimited_field(4, pr.varint_field(1, 1)))
add("frame_chunk_end_wrong_wire", "frame", pr.delimited_field(4, pr.bytes_field(2, b"a")))
add("frame_truncated_inner", "frame", bytes([0x22, 0x05, 0x0A]))
add("frame_garbage", "frame", bytes([0xFF, 0xFF]))
add("frame_empty", "frame", b"")
add("sreq_unknown_service_9", "service_request", pr.varint_field(1, 9))
add("sreq_unknown_service_huge", "service_request", pr.varint_field(1, 1 << 63))
add("sreq_service_wrong_wire", "service_request", pr.bytes_field(1, b"a"))
add("sreq_payload_wrong_wire", "service_request", pr.varint_field(2, 1))
add("sreq_ok_vault", "service_request", pr.varint_field(1, 2) + pr.bytes_field(2, b"pp"))
add("sreq_unknown_skipped", "service_request", pr.varint_field(7, 1) + pr.bytes_field(2, b"pp"))
add("sreq_default", "service_request", b"")
add("sreq_truncated", "service_request", bytes([0x12, 0x09, 0x01]))
add("sresp_ok", "service_response", pr.bytes_field(1, b"payload"))
add("sresp_wrong_wire", "service_response", pr.varint_field(1, 1))
add("sresp_unknown_skipped", "service_response", pr.varint_field(5, 1) + pr.bytes_field(1, b"p"))
add("sresp_last_wins", "service_response", pr.bytes_field(1, b"one") + pr.bytes_field(1, b"two"))
add("sresp_empty", "service_response", b"")

decode_cases = []
for name, kind, data in raw:
    try:
        spec = to_spec(kind, DEC[kind](data))
        decode_cases.append({"name": name, "kind": kind, "hex": hexb(data), "ok": True, "spec": spec})
    except Exception:
        decode_cases.append({"name": name, "kind": kind, "hex": hexb(data), "ok": False})

# ---- 3) a codificação recusa o que o SDK recusa ---------------------------------------------
refused = []
try:
    ev.encode_reset(ev.Reset(99, "x"))
    refused.append({"name": "reset_code_99", "refused": False})
except Exception:
    refused.append({"name": "reset_code_99", "refused": True})
try:
    ev.encode_service_request(ev.ServiceRequest(9, b""))
    refused.append({"name": "service_9", "refused": False})
except Exception:
    refused.append({"name": "service_9", "refused": True})
try:
    ev.encode_application_response(ev.ApplicationResponse(1 << 31, [], b"", False))
    refused.append({"name": "status_2_pow_31", "refused": False})
except Exception:
    refused.append({"name": "status_2_pow_31", "refused": True})

out = {"encode": encode_cases, "decode": decode_cases, "refused": refused}
with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    json.dump(out, f, indent=1, ensure_ascii=False)
    f.write("\n")
print("ok", len(encode_cases), len(decode_cases), refused)
