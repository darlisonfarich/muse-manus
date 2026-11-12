#!/usr/bin/env python3
"""Central BLE de teste para o pareamento v5 do gadget-link (docs/gadget-link.md, passo 3).

Faz o papel do app Muse no PC, só até a confirmação do pareamento:
  achar MuseGadgetXXXXXX -> conectar -> get_device_info -> pairing_client_hello ->
  pairing_ready -> pairing_client_finished (cifrado) -> pairing_confirmed -> wifi_scan.
NÃO faz o provisionamento (isso exige as credenciais da conta, que só o app Muse tem),
então não grava nada no gadget. Serve para provar GATT, MTU, notificações, remontagem de
pedaços e a criptografia no celular de verdade, sem precisar de um segundo celular.

É uma implementação INDEPENDENTE do lado do celular, escrita a partir do contrato do
protocolo (não do código Kotlin do gadget): se os dois lados concordam, não é coincidência.

Uso (no venv criado com bleak e cryptography):
  python pc_central.py --selftest            # confere a criptografia contra os vetores oficiais, sem Bluetooth
  python pc_central.py                       # procura e pareia até pairing_confirmed
  python pc_central.py --replay              # depois repete um registro e confere que o gadget derruba a sessão
  python pc_central.py --address AA:BB:...   # pula a busca
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import json
import os
import sys
import time
from pathlib import Path

# O gadget derruba em ~0,3 s (cancelConnection) ou, no pior caso, ~0,9 s (queda forçada);
# o resto da folga é a latência do aviso de desconexão no lado do PC.
REPLAY_DROP_DEADLINE_S = 1.5

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF, HKDFExpand

SERVICE_UUID = "7fdd3d1c-38ea-46cf-8b46-314ecf5f240c"
RX_UUID = "4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01"  # o central escreve aqui
TX_UUID = "d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c"  # o gadget notifica aqui
NAME_PREFIX = "MuseGadget"

RECORD_LABEL = "hatch-link ble setup v1"
SESSION_ID_LABEL = b"hatch-link session id v1"
MAGIC = 0xFE
HEADER = 3
_HERE = Path(__file__).resolve().parent
# Ao lado do script (cópia avulsa) ou no lugar dele dentro do repositório.
VECTORS = next(
    (p for p in (_HERE / "link_pairing_v5.json", _HERE.parent / "src" / "test" / "resources" / "link_pairing_v5.json") if p.is_file()),
    _HERE / "link_pairing_v5.json",
)


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


# --- Camada de criptografia (contrato do protocolo) -------------------------------------


def transcript(f: dict) -> str:
    return "\n".join([
        "hatch-link-pairing-v5", "version=5", "initiator_role=mobile", "responder_role=link",
        f"device_id={f['device_id']}", f"node_id={f['node_id']}", f"mac={f['mac']}", "model=hatch_link",
        f"firmware_version={f['firmware_version']}", "selected_cipher_suite=p256-hkdf-sha256-aes-gcm-v1",
        "pairing_auth=none", "pairing_auth_epoch=0", "pairing_policy=confirm_app", "confirm_timeout_seconds=0",
        f"mobile_pub={f['mobile_pub']}", f"device_pub={f['device_pub']}",
        f"mobile_nonce={f['mobile_nonce']}", f"device_nonce={f['device_nonce']}",
    ])


def derive(ecdh: bytes, mobile_nonce: bytes, device_nonce: bytes, transcript_hash: bytes):
    salt = hashlib.sha256(mobile_nonce + device_nonce + transcript_hash).digest()
    secret = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=RECORD_LABEL.encode()).derive(ecdh)
    to_device = HKDFExpand(algorithm=hashes.SHA256(), length=32, info=b"mobile->device").derive(secret)
    from_device = HKDFExpand(algorithm=hashes.SHA256(), length=32, info=b"device->mobile").derive(secret)
    session_id = hashlib.sha256(SESSION_ID_LABEL + transcript_hash + ecdh).digest()[:16]
    return to_device, from_device, session_id


def nonce(direction: int, counter: int) -> bytes:
    return bytes([direction, 0, 0, 0]) + counter.to_bytes(8, "big")


def aad(session_id: str, arrow: str, counter: int) -> bytes:
    return f"{RECORD_LABEL}|{session_id}|{arrow}|{counter}".encode()


class Session:
    def __init__(self, to_device: bytes, from_device: bytes, session_id: str) -> None:
        self.tx, self.rx, self.session_id = AESGCM(to_device), AESGCM(from_device), session_id
        self.tx_counter = 0
        self.rx_counter = 0

    def seal(self, command: dict, counter: int | None = None) -> dict:
        if counter is None:
            counter, self.tx_counter = self.tx_counter, self.tx_counter + 1
        sealed = self.tx.encrypt(nonce(0, counter), json.dumps(command, separators=(",", ":")).encode(),
                                 aad(self.session_id, "m2d", counter))
        return {"action": "pairing_encrypted", "session_id": self.session_id, "counter": str(counter),
                "ciphertext": b64(sealed[:-16]), "tag": b64(sealed[-16:])}

    def open(self, envelope: dict) -> dict:
        if envelope.get("type") != "pairing_encrypted" or envelope.get("session_id") != self.session_id:
            raise ValueError(f"unexpected envelope: {envelope.get('type')}")
        counter = int(envelope["counter"])
        if counter != self.rx_counter:
            raise ValueError(f"device counter {counter}, expected {self.rx_counter}")
        self.rx_counter += 1
        plain = self.rx.decrypt(nonce(1, counter), unb64(envelope["ciphertext"]) + unb64(envelope["tag"]),
                                aad(self.session_id, "d2m", counter))
        return json.loads(plain)


# --- Autoteste contra os vetores oficiais (sem Bluetooth) -------------------------------


def selftest() -> int:
    vectors = json.loads(VECTORS.read_text())["vectors"]
    for v in vectors:
        if not v["community"]:
            continue
        mobile = ec.derive_private_key(int(v["mobile_private_scalar_hex"], 16), ec.SECP256R1())
        device_pub = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), unb64(v["device_pub"]))
        ecdh = mobile.exchange(ec.ECDH(), device_pub)
        assert ecdh.hex() == v["ecdh_secret_hex"], f"{v['name']}: ecdh"
        if v["pairing_policy"] != "confirm_app":
            continue  # o vetor confirm_press usa outra política e o central só fala confirm_app
        assert hashlib.sha256(transcript(v).encode()).digest() == unb64(v["transcript_hash"]), f"{v['name']}: transcript"
        to_device, from_device, sid = derive(ecdh, unb64(v["mobile_nonce"]), unb64(v["device_nonce"]), unb64(v["transcript_hash"]))
        assert to_device.hex() == v["mobile_tx_key_hex"] and from_device.hex() == v["mobile_rx_key_hex"], f"{v['name']}: keys"
        assert b64(sid) == v["session_id"], f"{v['name']}: session id"
        sess = Session(to_device, from_device, b64(sid))
        env = sess.seal({"action": "pairing_client_finished"})
        assert env["ciphertext"] == v["client_finished_ciphertext"] and env["tag"] == v["client_finished_tag"], \
            f"{v['name']}: client-finished record"
        print(f"ok  {v['name']}: ecdh, transcript, keys, session id e registro client-finished batem com o vetor")
    return 0


# --- Framing ----------------------------------------------------------------------------


def chunks(data: bytes, payload: int) -> list[bytes]:
    if len(data) <= payload and (not data or data[0] != MAGIC):
        return [data]  # cabe em um write e não começa com 0xFE: mensagem completa
    room = payload - HEADER
    frags = [data[i:i + room] for i in range(0, len(data), room)] or [b""]
    assert len(frags) <= 255
    return [bytes([MAGIC, i, len(frags)]) + frag for i, frag in enumerate(frags)]


class Assembler:
    def __init__(self) -> None:
        self.buf, self.total, self.next = bytearray(), 0, 0

    def feed(self, packet: bytes) -> bytes | None:
        if len(packet) < HEADER or packet[0] != MAGIC:
            return bytes(packet)
        index, total, frag = packet[1], packet[2], packet[HEADER:]
        if total == 0:
            return None
        if index == 0 or total != self.total:
            self.buf, self.total, self.next = bytearray(), total, 0
        if index != self.next or index >= self.total:
            self.buf, self.total, self.next = bytearray(), 0, 0
            return None
        self.buf += frag
        self.next += 1
        if self.next < self.total:
            return None
        out = bytes(self.buf)
        self.buf, self.total, self.next = bytearray(), 0, 0
        return out


# --- Central BLE ------------------------------------------------------------------------


class Gadget:
    def __init__(self, client) -> None:
        self.client = client
        self.assembler = Assembler()
        self.inbox: asyncio.Queue = asyncio.Queue()

    def on_notify(self, _char, data: bytearray) -> None:
        message = self.assembler.feed(bytes(data))
        if message is not None:
            self.inbox.put_nowait(message)

    async def send(self, obj: dict) -> None:
        payload = max(self.client.mtu_size - 3, 20)
        for packet in chunks(json.dumps(obj, separators=(",", ":")).encode(), payload):
            await self.client.write_gatt_char(RX_UUID, packet, response=True)
            await asyncio.sleep(0.05)

    async def receive(self, timeout: float = 10.0) -> dict | str:
        raw = await asyncio.wait_for(self.inbox.get(), timeout)
        try:
            return json.loads(raw)
        except ValueError:
            return raw.decode(errors="replace")  # status em texto puro (ex.: error_pairing_decrypt)


def step(ok: bool, text: str) -> None:
    print(("ok   " if ok else "FALHA ") + text)
    if not ok:
        raise SystemExit(1)


async def find_device(address: str | None, timeout: float):
    from bleak import BleakScanner

    if address:
        return await BleakScanner.find_device_by_address(address, timeout=timeout)

    def match(device, adv) -> bool:
        return SERVICE_UUID in [u.lower() for u in (adv.service_uuids or [])] or \
            (adv.local_name or device.name or "").startswith(NAME_PREFIX)

    return await BleakScanner.find_device_by_filter(match, timeout=timeout)


async def run(args) -> int:
    from bleak import BleakClient

    print(f"Procurando o gadget por até {args.scan} s (abra o pareamento na Diagnostics do celular)...")
    device = await find_device(args.address, args.scan)
    step(device is not None, "gadget encontrado" + (f": {device.name} {device.address}" if device else
                                                    " (Bluetooth ligado aqui? pareamento aberto no celular?)"))
    loop = asyncio.get_running_loop()
    gone = asyncio.Event()

    def on_disconnect(_client) -> None:
        loop.call_soon_threadsafe(gone.set)

    async with BleakClient(device, timeout=20.0, disconnected_callback=on_disconnect) as client:
        uuids = {s.uuid.lower() for s in client.services}
        step(SERVICE_UUID in uuids, "serviço do Muse Gadget presente no GATT")
        gadget = Gadget(client)
        await client.start_notify(TX_UUID, gadget.on_notify)
        print(f"     MTU negociado: {client.mtu_size}")

        await gadget.send({"action": "get_device_info"})
        info = await gadget.receive()
        step(isinstance(info, dict) and info.get("type") == "device_info" and info.get("pairing_protocol") == 5
             and info.get("pairing_policy") == "confirm_app" and info.get("pairing_auth") == "none",
             f"get_device_info: {info.get('node_id')} v{info.get('version')} model={info.get('model')}")

        mobile = ec.generate_private_key(ec.SECP256R1())
        mobile_pub = mobile.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
        mobile_nonce = os.urandom(16)
        await gadget.send({"action": "pairing_client_hello", "version": 5, "pairing_auth": "none",
                           "pairing_policy": "confirm_app", "mobile_pub": b64(mobile_pub), "mobile_nonce": b64(mobile_nonce)})
        ready = await gadget.receive()
        step(isinstance(ready, dict) and ready.get("type") == "pairing_ready", "pairing_ready recebido")

        device_pub = unb64(ready["device_pub"])
        step(len(device_pub) == 65 and device_pub[0] == 4, "chave pública do gadget é um ponto P-256 não comprimido")
        fields = {**ready, "mobile_pub": b64(mobile_pub), "mobile_nonce": b64(mobile_nonce)}
        thash = hashlib.sha256(transcript(fields).encode()).digest()
        step(b64(thash) == ready["transcript_hash"], "transcript_hash do gadget confere com o meu cálculo")
        ecdh = mobile.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), device_pub))
        to_device, from_device, sid = derive(ecdh, mobile_nonce, unb64(ready["device_nonce"]), thash)
        step(b64(sid) == ready["session_id"], "session_id do gadget confere (ECDH e HKDF batem dos dois lados)")

        session = Session(to_device, from_device, b64(sid))
        first = session.seal({"action": "pairing_client_finished"})
        await gadget.send(first)
        status = session.open(await gadget.receive())
        step(status.get("type") == "status" and status.get("status") == "pairing_confirmed", "pairing_confirmed (cifrado)")
        token = status.get("sdk_token")
        print(f"     sdk_token no pairing_confirmed: {'sim (' + token[:12] + '...)' if token else 'não (token do SDK não colado)'}")

        await gadget.send(session.seal({"action": "wifi_scan"}))
        scan = session.open(await gadget.receive())
        step(scan.get("type") == "wifi_scan_result", f"wifi_scan_result cifrado: {len(scan.get('networks', []))} rede(s)")

        if args.replay:
            print("Teste de repetição: reenviando um registro já usado (o gadget deve derrubar a sessão)...")
            sent = time.monotonic()
            try:
                await gadget.send(first)
            except Exception as e:  # a queda pode chegar no meio do envio
                if not gone.is_set():
                    raise
                print(f"     (conexão caiu durante o envio: {type(e).__name__})")
            try:
                await asyncio.wait_for(gone.wait(), REPLAY_DROP_DEADLINE_S + 5.0)
                elapsed = time.monotonic() - sent
            except asyncio.TimeoutError:
                elapsed = None
            step(elapsed is not None, f"registro repetido derrubou a conexão (esperou {REPLAY_DROP_DEADLINE_S + 5.0:.1f} s)")
            step(
                elapsed <= REPLAY_DROP_DEADLINE_S,
                f"queda em {elapsed:.2f} s depois do registro repetido (limite {REPLAY_DROP_DEADLINE_S} s; o SDK derruba em ~0,3 s)",
            )
    print("\nHandshake v5 completo contra o gadget. Veja a seção 'Gadget link (spike)' na Diagnostics do celular:")
    print("deve mostrar HELLO_OK, CONFIRMED e depois DISCONNECTED nos eventos.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--selftest", action="store_true", help="só confere a criptografia contra os vetores oficiais")
    parser.add_argument("--address", help="endereço BLE do gadget (pula a busca)")
    parser.add_argument("--scan", type=float, default=30.0, help="segundos procurando o gadget")
    parser.add_argument("--replay", action="store_true", help="depois do pareamento, testa a proteção contra repetição")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    try:
        return asyncio.run(run(args))
    except asyncio.TimeoutError:
        print("FALHA: o gadget não respondeu a tempo")
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
