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
ai/     src/main/java/mu/proxima/ai/      AiModule + tiered TriageEngine, TriageServer, VoiceIo
locate/ src/main/java/mu/proxima/locate/  LocateModule + AndroidLocateEngine, UWB session, venue map data
trust/  src/main/java/mu/proxima/trust/   TrustModule + TinkTrustEngine, QR gen/scan, Asset repositories
        src/main/assets/data/             help_points_mu.json (OSM), protocols.json (cited)
app/    src/main/java/mu/proxima/app/     ProximaApp, ProximaGraph, ui/ (theme, screens, canvas)
```

## Wire format (the `MeshPacket` JSON envelope)
```json
{ "packetId":"uuid", "kind":"INCIDENT|SEALED|TRIAGE_REQUEST|TRIAGE_RESULT|BEACON|CONTROL",
  "originNodeId":"…", "originKey":"base64 Ed25519 pub", "createdAtMs":0, "severity":"LOW|MEDIUM|HIGH|CRITICAL",
  "ttl":7, "hopCount":0, "path":["nodeA","nodeB"], "payload":"<Incident JSON | sealed b64 | …>",
  "recipientKey":null, "channel":null, "signature":"base64" }
```
- Relays change only `ttl`, `hopCount` and `path`. The signature covers `MeshPacket.signingBytes()`.
- Duplicates are dropped by `packetId` (MESH keeps a Room table of seen ids). TTL starts at `Severity.hopBudget` (3/5/7/10).
- Serialisation always goes through `ProximaJson`.

## Incident model
`Incident{id, type, severity, subtype, summary≤140, location, landmark, peopleCount, createdAtMs, reporterNodeId,
protocolId, relatedIncidentId, reportCount, triagedBy, confidence}`
Types: `LOST_PERSON, SIGHTING, FOUND, MEDICAL, HAZARD, ALL_CLEAR`. `IncidentMerger` merges MEDICAL and HAZARD
reports of the same event (same type, within 75 m, within 10 min) and increases `reportCount`.
ALL_CLEAR and FOUND close the incident named in `relatedIncidentId`.

## Data flows
1. **SOS:** UI → `pipeline.report(RawReport)` → `TriageEngine` (LLM → offload → rules) → `Incident` →
   `PacketFactory` signs it → `mesh.send` → relays → each node's `pipeline.incidents` → lists, heatmap, Command Center.
2. **Guided response:** `incident.protocolId` → `ProtocolRepository.byId` → steps read aloud by `VoiceIo.speak`;
   `HelpPointRepository.nearest(location, type.helpCategories)` gives distance and bearing to real facilities.
3. **Lost & found:** the guardian mints a `WristbandTag` (QR: `PRX1:tagId:guardianKey:ts`, no personal data). The finder scans it,
   then `pipeline.reportFound(tag)` / `shareLocationWithGuardian(tag)` sends a FoundNotice sealed to `guardianKey`,
   so relays can't read it. The guardian gets `pipeline.foundNotices`, then `pipeline.trackFinder(notice)` gives a
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
