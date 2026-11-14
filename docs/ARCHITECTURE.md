# Architecture

## Modules (one per lane)

```
                 ┌──────────────────────── :app (UI lane) ────────────────────────┐
                 │ Compose screens · Command Center · ProximaGraph (manual DI)    │
                 └───────┬───────────┬────────────┬────────────┬─────────────────┘
                         │           │            │            │
               :mesh (MESH)   :ai (AI)   :locate (LOCATE)   :trust (TRUST)
               Nearby+Room    LLM/STT/TTS GPS/compass/UWB   Tink/QR/dataset
                         └───────────┴─────┬──────┴────────────┘
                                         :core  (pure Kotlin, shared contract)
                          models · Engines.kt · IncidentPipeline · RuleTriageEngine · fakes
```

- `:core` has no Android imports, so it can be unit-tested on the JVM.
- Each engine module exposes exactly one factory object (`MeshModule`, `AiModule`, `LocateModule`,
  `TrustModule`). The factory returns the fake until the lane's real engine works, and the swap happens inside it.
- `app/ProximaGraph.kt` calls the factories once. Lanes never edit it.
- Permissions: each engine lists `requiredPermissions`, and each module declares them in its own `AndroidManifest.xml`,
  which the build merges automatically. The UI requests `graph.requiredPermissions` once.

## Package layout
```
core/src/main/kotlin/mu/proxima/core/
  model/     Geo.kt (GeoPoint, GeoMath) · Incident.kt · Mesh.kt (MeshPacket, Peer, HopEvent, Beacon) · Domain.kt
  engine/    Engines.kt  ← THE CONTRACT
  pipeline/  IncidentPipeline · IncidentMerger · RuleTriageEngine · Packets (ProximaJson, PacketFactory)
  fake/      Simulation.kt (dev-only fakes + honest empty repositories)
mesh/   src/main/java/mu/proxima/mesh/    MeshModule + NearbyMeshEngine, Room dedup store, foreground service
ai/     src/main/java/mu/proxima/ai/      AiModule + tiered TriageEngine, TriageServer, VoiceIo, HandsFreeGuide, CrowdRiskMonitor
locate/ src/main/java/mu/proxima/locate/  LocateModule + AndroidLocateEngine, UWB session, venue map data
trust/  src/main/java/mu/proxima/trust/   TrustModule + TinkTrustEngine, QR gen/scan, Asset repositories
        src/main/assets/data/             help_points_mu.json (OSM), protocols.json (cited)
app/    src/main/java/mu/proxima/app/     ProximaApp, ProximaGraph, ui/ (theme, screens, canvas)
```

## Wire format (the `MeshPacket` JSON envelope)
```json
{ "packetId":"uuid", "kind":"INCIDENT|SEALED|TRIAGE_REQUEST|TRIAGE_RESULT|BEACON|CONTROL",
  "originNodeId":"…", "originKey":"<sig pub keyset b64>.<enc pub keyset b64>", "createdAtMs":0, "severity":"LOW|MEDIUM|HIGH|CRITICAL",
  "ttl":7, "hopCount":0, "path":["nodeA","nodeB"], "payload":"<Incident JSON | sealed b64 | …>",
  "recipientKey":null, "channel":null, "signature":"base64" }
```
- Relays change only `ttl`, `hopCount` and `path`. The signature covers `MeshPacket.signingBytes()`.
- **Identity (TRUST, #38):** `originKey` / `NodeIdentity.publicKey` = Ed25519 + HPKE-X25519 public keysets (~317 chars);
  `nodeId = NodeIds.derive(publicKey)` (8 hex), and `verify()` rejects a packet whose `originNodeId` does not match.
  A **fresh identity per app session**, so a node id cannot follow one phone around; the previous 24 h of keys are
  kept, and the pipeline asks `trust.ownsKey()` / `ownsNodeId()` (never `== identity.*`), so a restart mid-search
  does not lose found-notices. Role phones (medic tent, steward) use a stable identity.
- Duplicates are dropped by `packetId` (MESH keeps a Room table of seen ids). TTL starts at `Severity.hopBudget` (3/5/7/10).
- Serialisation always goes through `ProximaJson`.

## Incident model
`Incident{id, type, severity, subtype, summary≤140, location, landmark, peopleCount, createdAtMs, reporterNodeId,
protocolId, relatedIncidentId, reportCount, triagedBy, confidence}`
Types: `LOST_PERSON, SIGHTING, FOUND, MEDICAL, HAZARD, ALL_CLEAR`. `IncidentMerger` merges MEDICAL and HAZARD
reports of the same event (same type, within 75 m, within 10 min) and increases `reportCount`.
ALL_CLEAR and FOUND close the incident named in `relatedIncidentId`.

## Data flows
1. **SOS:** UI triages for the review screen, then `pipeline.report(RawReport, triaged = thatResult)` (#10: what is
   sent is what was shown; without `triaged` the pipeline calls `TriageEngine` itself: LLM → offload → rules) → `Incident` →
   `PacketFactory` signs it → `mesh.send` → relays → each node's `pipeline.incidents` → lists, heatmap, Command Center.
2. **Guided response:** `incident.protocolId` → `ProtocolRepository.byId` → steps read aloud by `VoiceIo.speak`;
   `HelpPointRepository.nearest(location, type.helpCategories)` gives distance and bearing to real facilities.
3. **Lost & found (no hardware):** the guardian reports a `LOST_PERSON` incident (description only). Every
   `IncidentRecord` carries the reporter's `originKey`. A finder who is with the person picks that alert and calls
   `pipeline.reportFound(record)` / `shareLocationWithGuardian(record)`, which sends a FoundNotice (`tagId` = the lost
   incident id) sealed to `originKey`, so relays can't read it. (`WristbandTag` overloads still exist but are unused.) The guardian gets `pipeline.foundNotices`, then `pipeline.trackFinder(notice)` gives a
   `TrackTarget`, then `locate.track()` gives `RadarFix` (UWB → GPS+compass → RSSI).
4. **Tier 2 AI offload:** the AI engine sends `TRIAGE_REQUEST` to a Tier 1 peer (`Peer.llmReady`), which answers with `TRIAGE_RESULT`.
   On timeout it falls back to rules. This happens entirely inside `:ai`.
5. **UWB:** LOCATE engines exchange session parameters with `CONTROL` packets (`channel="uwb"`) over the mesh.

## Device tiers (`AndroidDeviceCapabilityProvider`)
- Command Center: smallest width ≥ 600dp (Tab S6 Lite).
- Tier 1: RAM ≥ 7 GB (S26+, S24 Ultra, S23+): on-device LLM, UWB, relay.
- Tier 2: everything else (A56, Honor X8b): rules triage plus offload, RSSI "warmer/colder" finding.

## Interface change log
| Time | Lane | Change | Issue |
|---|---|---|---|
| 13:40 Fri | setup | Initial contract | — |
| 15:40 Fri | UI | `IncidentRecord.originKey`; `reportFound(IncidentRecord)` + `shareLocationWithGuardian(IncidentRecord)`; guardian accepts notices for its own LOST_PERSON reports; SIM finder in FakeMeshEngine | see PR |
| 19:05 Fri | AI | Additive: `GuideState` + `GuideEngine` (hands-free guided first aid) and `CrowdRiskLevel` + `CrowdRisk` + `CrowdRiskEngine` (crowd-crush early warning) moved from `ai/` into `Domain.kt` / `Engines.kt`. Deprecated typealiases at the old `mu.proxima.ai.guide/crowd` names keep `app/` compiling until its imports switch | #30 |
| 20:17 Fri | MESH | Additive: `Beacon.role: RolePass?` (role phones only) and `Peer.role` / `Peer.roleLabel`, set only when `trust.verifyRoleClaim(pass, beaconPacket, now)` passes on the receiving phone. Old builds ignore the field; ordinary beacons are unchanged on the wire | #69 (#55) |
| 20:20 Fri | MESH | Additive (#61, on top of #62): `Beacon.friendTag` (trust.beaconTag), `Peer.publicKey` (signer of its beacon, for pairing) and `Peer.friendLabel` (local trust.matchFriend result, never sent) | #61 |
