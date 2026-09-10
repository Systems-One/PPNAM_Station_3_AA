# PPNAM Station 3 - Scanner MQTT Contract

| Item | Value |
|---|---|
| Contract version | 1.0.0 |
| Status | **Base layer normative; workflow layer not yet contracted** |
| Last updated | 2026-09-10 |
| Target client | PPNAM Station 3 (Master Batch) Android handheld scanners |
| Station namespace | `PPNAM/station_3` |
| Topic structure | Fleet-wide namespaced structure per `C:\Dev\Clients\PPNAM\Andriod\MQTT_BASE_README.md` |
| Authentication schema | `"4.1"` (shared Station 2 authority) |
| Workflow QoS | `1`, retain `false` |
| Presence | Retained `online`/`offline` + Last Will on base topic nodes, QoS 2 |

This contract covers what a Station 3 Android scanner exchanges over MQTT. It is
deliberately **two documents in one**:

- **Sections 1-5** are normative and implemented today. They are the base layer of the
  fleet MQTT standard, bound to the `PPNAM/station_3` namespace.
- **Section 6** is the workflow layer, and is **empty on purpose**. Station 3's
  Master Batch workflow has not been contracted. Section 6 records the slots a future
  workflow must fill and the rules it inherits, so that specifying it is a matter of
  filling in message types rather than rediscovering the frame.

Everything here derives from `Andriod/MQTT_BASE_README.md` (the fleet base standard).
Where this document and the base standard disagree, **the base standard wins** and this
document is corrected to match. Station 1's `docs/Station1_MQTT_Contract_v3.md` remains
the worked example of a fully contracted station.

---

## 0. Current state — read this first

> **There is no Station 3 MQTT participant yet.**
>
> The fleet topic document (`C:\Dev\Clients\PPNAM\MQTT_TOPIC_STRUCTURE.md`) records that
> the Station 3 **Windows** app does not use MQTT. Nothing currently subscribes to
> `PPNAM/station_3/+/req/+` and nothing publishes retained presence on `PPNAM/station_3`.
>
> **Consequence:** the Android app's base layer is complete and correct, but it cannot
> complete a login against the live fleet today — `scram_start_requested` goes
> unanswered and the scanner surfaces its 10-second "Station did not respond" timeout.
> The app correctly shows "station offline" (distinct from "broker disconnected") in
> this state.
>
> Standing up a Station 3 backend — or deciding the Android app talks to something other
> than the Windows Master Batch app — is a prerequisite for any workflow work.

The Windows Master Batch app's own behaviour (Master Batch Type, gram weights, Code 128
`MB3-{yyyyMMdd}-{id}` barcodes, label printing, `dbo.station3_master_batch_transactions`)
is specified in `C:\Dev\Clients\PPNAM\Windows\PPNAM-Station-3\AGENTS.md`. That document
describes a **desktop, database-backed** application and defines no MQTT messages. It is
the correct starting reference for Section 6, but none of it is an MQTT contract yet.

---

## 1. Topic structure, subscriptions, and transport

Station 3 uses the five fleet topic shapes, with the station segment fixed to `station_3`.
The app never publishes or acts outside this subtree. Implemented in `MqttTopics.kt`.

```text
PPNAM/station_3                              station presence (retained online/offline + LWT)
PPNAM/station_3/{deviceId}                   scanner presence (retained online/offline + LWT)
PPNAM/station_3/{deviceId}/req/{type}        scanner → station request
PPNAM/station_3/{deviceId}/res/{type}        station → scanner response
PPNAM/station_3/res/{type}                   station broadcast to all scanners (reserved)
```

`res` directly under the station node is a **reserved segment** and can never be a device
id. The app tolerates and ignores unknown messages there. There is no `/status`
sub-topic — presence is the retained payload on the base node itself (Section 3).

A topic segment must never contain `/`, `+`, or `#`. `MqttTopics.validateSegment` rejects
such values loudly rather than letting them reshape a topic or become a wildcard
subscription.

### Subscriptions

| Participant | Subscribes to | Purpose |
|---|---|---|
| Station 3 backend (when one exists) | `PPNAM/station_3/+` | scanner presence |
| Station 3 backend (when one exists) | `PPNAM/station_3/+/req/+` | all scanner requests |
| Scanner | `PPNAM/station_3` | station presence |
| Scanner | `PPNAM/station_3/{ownDeviceId}/res/+` | all of its responses |

The scanner subscribes to the full `res/+` wildcard, never only the success suffixes —
rejections arrive on `res/request_rejected`. As permitted by the base standard, the app
takes one broad subscription (`MqttTopics.stationWildcard()` = `PPNAM/station_3/#`) and
filters locally, but acts only on messages matching the canonical filters above.

### Transport

| Class | QoS | Retain |
|---|---|---|
| Presence base nodes (incl. Last Will) | 2 | yes |
| `…/req/{type}` | 1 | no |
| `…/res/{type}` (responses and broadcasts) | 1 | no |

Connection settings (`MqttManager.kt`): keep-alive 15 s, clean session, MQTT 3.
Broker host, port, WebSocket, TLS, username and password are configured in app Settings;
the password is held in `SecureCredentialStore` (Android Keystore, alias
`ppnam_station3_mqtt_credential` — station-scoped so it cannot collide with another
station app's credential on the same scanner). Broker credentials are transport-only and
never appear in a JSON payload.

One UTF-8 JSON object per message, presence excepted (raw `online`/`offline`). Property
names are lower-camel case. Timestamps are UTC ISO 8601 ending in `Z`; authentication
timestamps carry exactly six fractional digits (`Schema41.TIMESTAMP_FORMAT`). Optional
fields are omitted when unused, never sent as `null`.

A QoS 1 PUBACK is transport-only and is never shown as business success — success is the
station's response message.

## 2. Device identity

`deviceId` is a derived unique id, generated on the device — never a configured scanner
number and never a role. Implemented in `DeviceIdentity.kt`.

```text
scanner_ + first 12 hex chars of SHA-256(per-device identifier)
e.g. scanner_5c64df8d86a8
```

1. Identifier source, in order: the `wlan0` MAC (lowercase, colon-separated); falling back
   to `Settings.Secure.ANDROID_ID` where Android withholds the MAC (Android 11+, and the
   anonymized `02:00:00:00:00:00` counts as withheld).
2. Derived **once**, persisted in app preferences, reused forever. Clearing app data
   re-derives it.
3. Shown in Settings → Diagnostics so it can be read off the device for enrolment.
4. Treated by the station as an opaque, case-sensitive string with a `scanner_` prefix.
5. The topic `{deviceId}` segment and the payload `deviceId` match exactly on every request.
6. The MQTT **client id** is a separate transport identity: `ScannerApp_` + 8 random
   chars, unique per connection. The `deviceId` is never reused as the client id.

## 3. Presence and Last Will

Presence is retained raw text `online` / `offline` on the base node itself — not JSON.

| Participant | Topic | On connect | Last Will |
|---|---|---|---|
| Station 3 backend | `PPNAM/station_3` | retained `online`, QoS 2 | retained `offline`, same topic |
| Scanner | `PPNAM/station_3/{deviceId}` | retained `online`, QoS 2 | retained `offline`, same topic |

The app registers the Last Will at connect time, publishes retained `online` immediately
after connecting (clearing any stale LWT `offline`), and on graceful shutdown publishes
retained `offline` itself and waits for that publish to complete before disconnecting.

**Self-heal** (`PresenceSelfHeal.kt`, `MqttManager.kt`): after a quick restart the previous
connection's Last Will can land after the new connection's retained `online`, sticking
presence at `offline` while actually connected. The app subscribes to its own presence node
and republishes retained `online` whenever it reads `offline` while connected.

The app tracks the station's presence from the retained payload on `PPNAM/station_3` and
surfaces "MAIN STATION OFFLINE" in the UI distinctly from "broker disconnected".

## 4. Sessions and permissions

The login response carries `operatorSessionId`, `operatorId`, `displayName`, `role`,
`allowedActions` and `allowedTabs` (`OperatorSession.kt`).

- `role` is **display and audit only**. Authorization is never branched on it.
- `allowedTabs` lists the workflows this operator may use. The app enables **exactly** the
  listed workflows; a missing or empty list means **no workflows enabled** (fail closed).
- Scanner-side gating is UX, not security — the station also authorizes every request
  server-side and answers `ACTION_NOT_ALLOWED` when it must.
- The session is held in memory only (`OperatorSessionHolder`). On process death the
  operator logs in again.
- A response that accepts the login but issues no `operatorSessionId`, or that reports
  `sessionState: "closed"`, is refused — accepting it would strand the operator in a UI
  that rejects every action.

**Station 3 defines no tab wire values yet.** `StationTab` is an empty object; when
Section 6 is written, its workflow tab constants go there, mirroring Station 1's
`StationTab.TAG_ASSIGNMENT` / `StationTab.OFFLOAD`. Until then the fail-closed gating has
nothing to enable and every operator correctly sees no workflows.

## 5. Schema 4.1 authentication

Station 3 uses the shared schema 4.1 contract (authority: Station 2's
`RFID_MQTT_CONTRACT.md`; full SCRAM detail: Station 1's contract §4.3) on its own
namespaced topics. Implemented in `AuthClient.kt`, `Schema41.kt`, `ScramCrypto.kt`.

| Request `req/{type}` | Response `res/{type}` | Purpose |
|---|---|---|
| `scram_start_requested` | `scram_challenge` | Start SCRAM-SHA-256 password login |
| `scram_proof_requested` | `scram_proof_result` | Prove password knowledge |
| `login_requested` | `operator_context` | Badge login |
| `reader_logout_requested` | `operator_context` | Close this device's session |

### Envelope

Every auth request carries:

```json
{
  "messageId": "auth-start-<uuid>",
  "schemaVersion": "4.1",
  "deviceId": "scanner_5c64df8d86a8",
  "timestampUtc": "2026-09-10T06:00:00.000000Z"
}
```

`messageId` identifies one **logical operation**, not a delivery attempt; duplicate
identity is `(deviceId, requestType, messageId)`. A retry resends the identical raw UTF-8
body on the same topic — never reserialized, reordered, re-timestamped, or reformatted.
Responses carry `inResponseToMessageId`, `accepted`, a machine-readable `errorCode`, and a
free-text `reason` that is **displayed, never parsed**.

### Round-trip rules

`AuthClient.request` implements one round trip: it subscribes to both the success topic and
`res/request_rejected`, correlates strictly on `inResponseToMessageId`, times out after
**10 seconds**, and fires its callback exactly once on the main thread. A message that is
not ours (wrong device, wrong `messageId`, or unparseable) is ignored rather than failing
the request — the timeout covers silence.

### SCRAM specifics enforced by the scanner

- Passwords are **never** sent over MQTT — only the SCRAM proof (NFKC-normalize password,
  PBKDF2-HMAC-SHA-256 with the returned salt/iterations, client/server keys, XOR proof).
- Per RFC 5802 the combined nonce **must extend** the `clientNonce` this device sent. A
  challenge that does not is refused rather than proved against.
- The response `serverSignature` is verified in **constant time** before any session is
  accepted; on mismatch the response and the session are discarded. Without this check
  anything able to publish on the response topic could hand the scanner a session it never
  proved for.
- A challenge with a blank `challengeId`/`serverFirstMessage`, or `iterations <= 0`, is
  refused. A malformed salt surfaces as a generic message — challenge material is never
  echoed into user-facing text.

Logout clears the local session regardless of outcome: stranding an operator logged in
because the network blipped is worse than a server-side session that expires on its own.

Authentication error codes are `lowercase_snake_case` (e.g. `scram_proof_invalid`,
`message_id_reused`, `timestamp_stale`) and compared exactly.

## 6. Workflow layer — NOT YET CONTRACTED

**Nothing in this section is implemented.** Station 3 currently ships no workflow: there is
no `WorkflowClient.kt`, no `WorkflowMessages.kt`, and `MainActivity` shows "No workflows
yet". This section defines the frame a Master Batch workflow must fit, not the workflow.

Before this section can be written, two questions need answers:

1. **What does the handheld do?** The Windows app already creates Master Batch labels and
   prints them. Whether the Android scanner is a mobile label-creation terminal, a
   verification/lookup tool for printed `MB3-…` barcodes, or something else, is undecided.
2. **What backend answers it?** See Section 0 — no `station_3` MQTT participant exists.

### Slots a Station 3 workflow must fill

| Slot | Requirement |
|---|---|
| Message types | One `req/{type}` → `res/{type}` pair per operation, on the topics in Section 1 |
| Tab wire values | Constants in `StationTab`, matching the strings the station sends in `allowedTabs` |
| Error codes | `UPPERCASE_SNAKE_CASE`, station-specific, on top of the four common codes below |
| Screens | One activity per workflow, gated on `OperatorSession.canShow` |

### Rules the workflow inherits (non-negotiable)

The **workflow envelope** is deliberately lighter than the auth envelope — it carries no
`messageId` and no `schemaVersion`:

```json
{ "ts": "…Z", "deviceId": "scanner_…", "operatorSessionId": "…", "…workflow fields…": "" }
```

Responses carry `ts`, `deviceId`, and echo the correlating fields so the scanner can match
them. QoS 1, retain false.

- Every workflow request carries the `operatorSessionId` returned by login.
- The scanner shows a pending state until the result arrives, and surfaces a **timeout after
  10 seconds** without one.
- Workflows are **stateless request/response** — no state resynchronization on reconnect.
- These four common session/permission error codes must be handled by any Station 3
  workflow: `INVALID_PAYLOAD`, `AUTHENTICATION_REQUIRED`, `OPERATOR_SESSION_INVALID`,
  `ACTION_NOT_ALLOWED`.
- Workflow tiles gate on `OperatorSession.canShow(tab)` exactly as Station 1's do, and fail
  closed when `allowedTabs` is absent or empty.

## 7. Reconnect behaviour

On reconnect the app restores its subscriptions (`PPNAM/station_3` and
`…/{ownDeviceId}/res/+`), republishes retained `online` on its own base node, and
re-authenticates if the operator session is closed or expired. There is no workflow state
to resynchronize.

## 8. Known gaps against the base standard

These are clauses of `MQTT_BASE_README.md` that the Station 3 app does **not** implement.
All three are inherited from Station 1's reference implementation, which does not implement
them either — they are **fleet-wide gaps, not defects in the Station 3 port**. Fixing them
in Station 3 alone would make it diverge from the implementation every station app is copied
from, so they are recorded here rather than patched locally.

| Base standard clause | Requirement | Status |
|---|---|---|
| §4 Transport rules | Duplicate property names (case-insensitive, any depth) are rejected | **Not implemented** — no duplicate-key check exists on the decode path |
| §4 Transport rules | `password` / `managerPassword` anywhere in a JSON tree is rejected before dispatch | **Not implemented** — no pre-dispatch plaintext-credential guard |
| §7 Logging and redaction | Recursively redact `password`, `managerPassword`, `clientProof`, `serverSignature`, `authorizationToken`, SCRAM verifier keys and broker secrets before any diagnostic write | **Partially implemented** — the app avoids logging credential material and never echoes challenge material into user-facing strings, but there is no explicit recursive redaction helper applied to diagnostic writes |

**Recommended disposition:** address all three as one fleet-wide change across Stations 1-5,
starting in Station 1 as the reference implementation, then re-porting the base layer.

Mitigating context for the second and third rows: `AuthClient` never places a password in a
payload (only the SCRAM proof), and broker credentials are transport-only and held in the
Android Keystore. The gap is the absence of a defensive guard, not a known leak.

## 9. Implementation map

| Concern | File |
|---|---|
| Topic construction and segment validation | `MqttTopics.kt` |
| Derived device id | `DeviceIdentity.kt` |
| Connection, presence, LWT, subscriptions | `MqttManager.kt` |
| Presence self-heal | `PresenceSelfHeal.kt` |
| Schema 4.1 envelope | `Schema41.kt` |
| SCRAM-SHA-256 | `ScramCrypto.kt` |
| Auth round trips | `AuthClient.kt` |
| Session and tab gating | `OperatorSession.kt` |
| Broker settings and validation | `BrokerSettings.kt`, `SettingsRepository.kt` |
| Keystore-backed broker password | `SecureCredentialStore.kt` |
| Login / Settings / Home UI | `LoginActivity.kt`, `SettingsActivity.kt`, `MainActivity.kt` |
| Workflow layer | *(none — Section 6)* |

## 10. Revision history

| Version | Date | Change |
|---|---|---|
| 1.0.0 | 2026-09-10 | First Station 3 contract. Records the base layer as implemented (ported from Station 1 in `d19512c`), documents the absent Station 3 MQTT backend (§0), reserves the workflow layer (§6), and flags three fleet-wide gaps against the base standard (§8). |
