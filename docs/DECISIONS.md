# Decision log: team-wide

Required deliverable: **5–10 key decisions, with times** (goes into the README).
- Team-wide decisions → this file (append at the end; one line each).
- Lane decisions → `docs/lanes/<LANE>.md` → `## Decision log`.
- At **~10:00 Sat** the AI lane compiles all logs into a shortlist, and the team picks the best 5–10 for the README.

Log generously. Correct times if they're off; all are Fri 13 Nov unless marked.

| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
| 12:30 | Repo `Mikhil-sec/Proxima` created public and empty before kickoff | Rule 2: commits only from kickoff | Pre-seeding with boilerplate |
| ~13:00 | Theme 1 **Powered to Protect** | Offline P2P safety fits "emergency alerts, trusted-contact safety, people at risk" | Themes 2/3 (weaker fit for a mesh) |
| ~13:05 | Hero user = parent at Maha Shivaratri (Ganga Talao); generalise to campuses/concerts | Specific Mauritian user with real network congestion; avoids a "vague user" | "Everyone at events" |
| ~13:10 | Cut duress PIN, decoy calculator, cyber-warfare branding, LLM SitRep | "Too many features, none finished"; didn't serve the user | Keeping all 5 original pillars |
| ~13:15 | Unify everything as one `Incident` pipeline (lost/sighting/found/medical/hazard/all-clear) | Reads as one strong feature; one bus to build and test | Separate subsystems per feature |
| ~13:20 | Wristband QR with **no personal data**; finder → location sealed to the guardian's key | Rule: never identify individuals; relays can't read child location | Broadcasting child name/photo |
| ~13:25 | Dataset = Mauritian help points (OSM / data.govmu) + cited protocol cards; AI **selects** cards, never writes medical advice | Protocols alone may not count as a "real local dataset"; avoids AI hallucinating medical advice | Protocols only; free-form LLM advice |
| ~13:25 | On-device AI as a pipeline (speech → structured 200-byte incident, severity → hop budget, dedup), not a chatbot | Bandwidth + privacy justification; "no ML needed" honoured via rules fallback | Cloud LLM; chatbot UI |
| ~13:30 | Radar = GPS + compass on all phones, UWB precision on S26+/S24U/S23+, RSSI fallback | GPS works without signal; UWB works indoors (demo room) | UWB-only |
| ~13:35 | Kotlin + Compose, 6 Gradle modules (one per lane), manual DI, fakes behind factories | Zero merge conflicts for 5 parallel devs; UI starts immediately | Single module; Hilt |
| 13:40 | AGP 9.5.1 (built-in Kotlin), Kotlin 2.4.21, Gradle 9.9.1, minSdk 26 / UWB API 31+ | Latest stable at kickoff; Tab S6 Lite compatible | Older AGP 8.x |
| 13:40 | Release APK signed with the debug key | Judges must be able to sideload the APK | Play signing (no time) |
