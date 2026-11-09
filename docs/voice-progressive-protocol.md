# A2 progressive audio extension (v1.1)

Status: **proposal + isolated JVM reference implementation**. This document does not enable progressive audio in the production hub, transport, or screen.

## 1. Goals and compatibility boundary

A2 v1 sends one complete MP3 for a turn. The VM therefore waits for the full synthesis before sending audio. This additive extension lets the VM send complete speech segments as soon as each phrase is synthesized, so a future app player can begin playback while later phrases are still being generated.

The base protocol remains **protocol_version = 1**. “v1.1” names the optional extension; it is not a new mandatory protocol version. The extension identifier is `audio_segments_v1`.

Progressive audio is **never inferred** from a peer version, extra fields, or repeated messages. Both peers must negotiate the exact extension:

```json
{"type":"HELLO","protocol_version":1,"extensions":["audio_segments_v1"]}
{"type":"READY","protocol_version":1,"extensions":["audio_segments_v1"]}
```

- `HELLO.extensions` is the VM's list of extensions it can use.
- `READY.extensions` is the hub's selected intersection, not merely its supported list.
- Missing, empty, malformed, or non-matching `READY.extensions` means **legacy v1 only**.
- A new VM connected to the current v1 hub sees a READY without the extension and must send one legacy MP3.
- A v1 VM does not advertise the extension; a future hub must answer without selecting it and must continue to accept/send the legacy single-MP3 flow.
- Keep `protocol_version: 1` so the current hub's version check continues to pass. Do not send `1.1` as `protocol_version`.
- A peer must not emit `MV11` frames or repeated segment cycles unless the extension was selected in READY for that connection.

The current hub is intentionally unchanged in this task. It ignores unknown HELLO fields and does not echo extensions, which safely causes a new VM to fall back to v1.

## 2. Wire format

The extension reuses the existing control message names but adds mandatory segment metadata. A segment is a complete independently decodable MP3 payload (for example, one synthesized phrase). Segments are sent serially; there is never more than one open segment.

### Control sequence

For each segment, send exactly one `AUDIO_START`, its binary frames, then exactly one `AUDIO_END`. Repeat for the next segment:

```json
{"type":"AUDIO_START","turn_id":"…","generation_id":"…","format":"mp3","segment_index":0,"last_segment":false,"total_bytes":18432}
```

Then send binary frames for segment 0, followed by:

```json
{"type":"AUDIO_END","turn_id":"…","generation_id":"…","kind":"mp3","segment_index":0}
```

The final segment has `last_segment:true`. Its `AUDIO_END` must arrive before `TURN_END`:

```json
{"type":"AUDIO_START","turn_id":"…","generation_id":"…","format":"mp3","segment_index":1,"last_segment":true,"total_bytes":9216}
{"type":"AUDIO_END","turn_id":"…","generation_id":"…","kind":"mp3","segment_index":1}
{"type":"TURN_END","turn_id":"…","generation_id":"…","segment_count":2}
```

Field rules:

- `segment_index` is a zero-based, contiguous integer sequence: 0, 1, 2…; it never resets within a turn.
- `last_segment` is a required JSON boolean on every `AUDIO_START`. Once true is declared, no further segment may start.
- `total_bytes` is the exact byte count for this segment, not the whole turn. It must be positive and within both the segment and remaining turn budgets.
- `AUDIO_END.segment_index` must match the open segment and is a barrier: all of that segment's binary frames must already have arrived.
- `TURN_END.segment_count` is mandatory and must equal the number of fully ended segments. The last completed segment must be the one marked `last_segment:true`.
- No empty segments, concurrent segments, implicit segment completion, or index gaps are permitted.

### Progressive binary frame

The legacy `MV01` header has only turn ID and chunk sequence. It cannot distinguish an old generation or a segment. Negotiated v1.1 therefore uses a separate `MV11` frame; v1 encoding is unchanged.

All integers are unsigned in meaning and encoded big-endian (the JVM implementation rejects values that exceed signed non-negative Int bounds). Header layout:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | ASCII magic `MV11` |
| 4 | 1 | kind: `2` = MP3 |
| 5 | 16 | turn UUID |
| 21 | 16 | generation UUID |
| 37 | 4 | segment_index |
| 41 | 4 | chunk_seq |
| 45 | variable | MP3 payload, 1–65,536 bytes |

`chunk_seq` starts at zero for every segment and increases by one. Every frame repeats both IDs and the segment index. This prevents a delayed frame from a prior generation or segment from being appended to the active one. WebSocket ordering is still required; this metadata is validation, not a reordering buffer.

## 3. Text and turn completion

- `TRANSCRIPT` remains optional and is sent at most once, before the first audio segment. It represents the recognized user utterance, not segment text.
- `REPLY` remains one full, final text response for the turn. It is not repeated per segment and is not a stream of sentence fragments. It may arrive before, during, or after segment delivery, but must arrive before `TURN_END`.
- Segment boundaries do not create new turns and do not change `turn_id` or `generation_id`.
- `TURN_END` is the only successful completion marker. A completed final segment alone does not complete the turn. The receiver accepts TURN_END only after every segment 0…N-1 has ended, the final marker is satisfied, and the full REPLY has arrived.
- The receiver may hand a completed segment to a future player immediately after its matching AUDIO_END; it must still keep the logical turn open until TURN_END.

## 4. Ordering, loss, cancellation, and errors

- Control and binary messages share the ordered WebSocket stream. A new AUDIO_START is legal only after the preceding AUDIO_END.
- A duplicate segment index, skipped segment index, wrong segment index, duplicate/out-of-order chunk sequence, byte-count mismatch, frame from the wrong active generation, or TURN_END with an incomplete segment set is not repaired by guessing. A matching-generation protocol violation aborts the turn and discards its current segment buffer.
- A frame/control message carrying a different `turn_id` or `generation_id` is stale and is ignored. The receiver must clear the prior generation when a new turn/generation is installed. MV11 carries generation ID even in binary frames; this is important because MV01 cannot provide that guarantee.
- WebSocket transport is reliable and ordered while connected. If a connection closes or a chunk is missing, the receiver aborts the turn; there is no retransmission, skip, or resume within v1.1. A retry uses a new turn/generation identity and starts at segment zero.
- `CANCEL` from either peer is terminal for the matching turn: stop synthesis/sending, discard the open segment and queued-but-not-started playback, and ignore late frames. Already audible sound cannot be recalled. No TURN_END follows CANCEL.
- `ERROR` is also terminal: discard the open segment and queued playback, report the error, and do not send later segments or TURN_END.
- A CANCEL or ERROR for an old identity must not cancel a newer generation.

## 5. Memory and resource limits

The existing v1 MP3 turn ceiling remains **12 MiB**. The extension enforces both:

1. each segment is at most 12 MiB; and
2. the sum of all segment `total_bytes` values in the turn is at most 12 MiB.

The aggregate limit is authoritative, so splitting an oversized answer into many segments cannot bypass the existing turn budget. The reference assembler buffers only the currently open segment and emits a completed byte array at AUDIO_END; it does not retain all completed segments. A future playback queue must remain bounded by the same per-turn budget, avoid unnecessary copies, and discard queued bytes on CANCEL/ERROR. The implementation also caps a turn at 128 segments and each binary payload at 64 KiB.

A future player should enqueue completed segments in index order and play them serially. It must not start segment N+1 before N has completed playback. If playback falls behind, the queue remains bounded; the receiver must cancel/fail the turn rather than allow unbounded memory growth. No PAUSE/RESUME or retransmission extension is defined here.

## 6. v1 fallback matrix

| VM | Hub | Negotiated extension | Required behavior |
|---|---|---|---|
| v1 | v1 | No | Existing one-MP3 v1 flow |
| v1.1-capable | v1 | No (hub omits it) | VM falls back to one-MP3 v1 flow |
| v1 | v1.1-capable | No (VM did not request it) | Hub uses one-MP3 v1 flow |
| v1.1-capable | v1.1-capable | Yes | Repeated AUDIO_START/AUDIO_END + MV11 frames |

A peer that did not negotiate the extension must never receive the progressive wire format.

## 7. JVM reference implementation boundary

`core.remote.progressive.VoiceProgressiveProtocol` and `VoiceProgressiveAssembler` are deliberately isolated from `VoiceA2Hub`, the app transport, and the screen. They are a testable protocol reference only. No runtime code imports or instantiates them in this change. The existing v1 codec, hub state machine, and UI behavior remain untouched.

The assembler emits each `SegmentReady` event immediately after a complete AUDIO_END, then emits `TurnComplete` only after a valid TURN_END. Stale identities return no events. A matching-generation malformed sequence fails closed and clears the active turn.

## 8. Acceptance and latency measurement plan

Do not accept the feature based only on synthesis completion or bytes received. Measure the user-visible first sound:

- **T0:** monotonic timestamp on the phone when capture ends and the voice turn is submitted.
- **T1:** monotonic timestamp on the phone when the audio output callback/AudioTrack reports the first audible sample from segment 0. Receiving AUDIO_START or the first network byte is not T1.
- **First-sound latency:** T1 − T0. Also record total turn completion, segment count, total bytes, cancellation/error rate, and playback underruns.

Run the legacy v1 path and negotiated v1.1 path on the same POCO/device, network, model, voice, and fixed prompt set. Separate cold and warm runs; collect at least 30 paired turns per condition and report p50/p90, not only averages. Use phone-side monotonic time for both endpoints so VM/phone clock skew cannot contaminate the result.

Proposed acceptance gates:

- p50 first-sound latency improves by at least 30% and p90 by at least 20% versus the v1 baseline;
- no increase in incomplete-turn, decode, or playback-underrun rates;
- every accepted TURN_END has a complete contiguous segment set and full REPLY;
- cancellation/error during synthesis or playback leaves no active assembler buffer or queued audio;
- legacy fallback remains byte-for-byte compatible with the current v1 path.

These are target gates, not measured results. The screen/player integration and real-device latency experiment are intentionally deferred to a later task.
