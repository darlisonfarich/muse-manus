"""Gera vetores do fio do link (musegadget/link_client.py) com a implementação de REFERÊNCIA do SDK:
mensagem de controle (u32 LE + JSON compacto), decodificador dessas mensagens, URL do Noise,
validação do app id e a mensagem link.register.

Rodar com: python -I gen_link_vectors.py <sdk_src> <saida.json>
(a partir de uma pasta que NÃO seja a do SDK; o SDK entra só pelo sys.path).
"""
import json
import struct
import sys

sdk_src, out_path = sys.argv[1], sys.argv[2]
sys.path.insert(0, sdk_src)

from musegadget import link_client as lc  # noqa: E402

# ---- 1) encode_message ----------------------------------------------------------------------
messages = [
    {"type": "req", "id": "abc", "method": "link.register"},
    {"method": "link.result", "id": "x1", "ok": False, "error": "unsupported command: file.read"},
    {"a": 1, "b": [1, 2, {"c": None}], "d": True, "e": False, "f": "texto"},
    {"s": "acentuação é ü ç ñ"},
    {"s": "emoji \U0001F680 e   e é"},
    {"s": "controle \x01 \x1f \x7f tab\t nl\n cr\r bs\b ff\f aspas\" barra\\ fatia/"},
    {"n": 0, "neg": -5, "big": 9007199254740991, "bigger": 1234567890123456789},
    {},
    {"nested": {"x": {"y": {"z": []}}}},
]
encode_cases = [{"msg": m, "hex": lc.encode_message(m).hex()} for m in messages]

# ---- 2) MessageDecoder: sequências de feeds -------------------------------------------------
def framed(obj):
    return lc.encode_message(obj)


def raw_frame(payload: bytes):
    return struct.pack("<I", len(payload)) + payload


a, b = framed({"id": "1"}), framed({"id": "2", "event": "x"})
decoder_scenarios = {
    "two_messages_in_one_feed": [a + b],
    "message_split_across_feeds": [a[:3], a[3:6], a[6:], b],
    "split_inside_length_prefix": [a[:1], a[1:]],
    "byte_by_byte": [bytes([x]) for x in a],
    "keepalive_empty_message_skipped": [raw_frame(b"") + a],
    "malformed_json_dropped": [raw_frame(b"{not json") + a],
    "invalid_utf8_dropped": [raw_frame(b"\xff\xfe") + a],
    "array_json_dropped": [raw_frame(b"[1,2]") + a],
    "string_json_dropped": [raw_frame(b'"x"') + a],
    "number_json_dropped": [raw_frame(b"12") + a],
    "null_json_dropped": [raw_frame(b"null") + a],
    "length_over_limit_is_error": [struct.pack("<I", lc.MAX_INBOUND_MESSAGE + 1)],
    "length_at_limit_waits_for_more": [struct.pack("<I", lc.MAX_INBOUND_MESSAGE) + b"{"],
    "nothing_until_complete": [struct.pack("<I", 50) + b"{}"],
    "duplicate_keys_last_wins": [raw_frame(b'{"a":1,"a":2}')],
    "unicode_escapes": [raw_frame(b'{"s":"\\u00e9\\ud83d\\ude80"}')],
}
decoder_cases = []
for name, feeds in decoder_scenarios.items():
    dec = lc.MessageDecoder()
    out = []
    for f in feeds:
        try:
            out.append(dec.feed(f))
        except Exception:
            out.append("error")
            break
    decoder_cases.append({"name": name, "feeds": [f.hex() for f in feeds], "expected": out})

# ---- 3) noise_url e validate_app_id ---------------------------------------------------------
url_inputs = [
    ("hatch.metaaivm.com", "vm-1", None),
    ("hatch.metaaivm.com", "a b", None),
    ("hatch.metaaivm.com", "é", None),
    ("hatch.metaaivm.com", "a/b?c=d&e#f", None),
    ("hatch.metaaivm.com", "x'y()*!~_.-", None),
    ("hatch.metaaivm.com", "\U0001F680", None),
    ("hatch.metaaivm.com", "vm", "hatch-web"),
    ("hatch.metaaivm.com", "vm", "12345"),
    ("hatch.metaaivm.com", "vm", "0012345"),
    ("hatch.metaaivm.com", "vm", "9223372036854775807"),
    ("hatch.metaaivm.com", "vm", "9223372036854775808"),
    ("hatch.metaaivm.com", "vm", "0"),
    ("hatch.metaaivm.com", "vm", ""),
    ("hatch.metaaivm.com", "vm", "-1"),
    ("hatch.metaaivm.com", "vm", "12 3"),
    ("hatch.metaaivm.com", "vm", "١٢٣"),
    ("hatch.metaaivm.com", "vm", "12345678901234567890"),
    ("noise.example.org:8443", "vm", None),
]
url_cases = []
for host, vm, app in url_inputs:
    try:
        url_cases.append({"host": host, "vm_id": vm, "app_id": app, "url": lc.noise_url(host, vm, app)})
    except Exception:
        url_cases.append({"host": host, "vm_id": vm, "app_id": app, "url": None})

# ---- 4) link.register ------------------------------------------------------------------------
reg_inputs = [
    ("homelink-abc123", "Celular", "0.1.7", {}),
    ("homelink-abc123", "Celular do Darlison é ü", "0.1.7", {}),
    ("n", "d", "v", {"device.health": {"description": "health", "required": {}, "optional": {}, "timeout_ms": 5000}}),
]
register_cases = []
for node, name, version, commands in reg_inputs:
    dev = lc.DeviceDescription(node, name, version, commands)
    register_cases.append({
        "node_id": node, "display_name": name, "version": version, "commands": commands,
        "id": "11111111-2222-3333-4444-555555555555",
        "hex": lc.encode_message(dev.register_message("11111111-2222-3333-4444-555555555555")).hex(),
    })

out = {
    "max_inbound_message": lc.MAX_INBOUND_MESSAGE,
    "max_control_message_bytes": lc.MAX_CONTROL_MESSAGE_BYTES,
    "encode": encode_cases,
    "decoder": decoder_cases,
    "urls": url_cases,
    "register": register_cases,
}
with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    json.dump(out, f, indent=1, ensure_ascii=False)
    f.write("\n")
print("ok", len(encode_cases), len(decoder_cases), len(url_cases), len(register_cases))
