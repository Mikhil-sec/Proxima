# Proxima — team rulebook (read automatically every session)

Offline P2P safety mesh for big crowds in Mauritius (Maha Shivaratri at Ganga Talao, Ganesh Chaturthi,
concerts, campuses). Lost child → wristband QR → encrypted "found" → guardian radar. SOS (medical / hazard)
→ on-device AI triage → multi-hop mesh → guided response + nearest help point from a real Mauritian dataset.
Hackathon "Life Powered by Tech", theme **Powered to Protect**. Product brief: `docs/PRODUCT.md`.

**DEADLINE: submit by 12:30 Sat 14 Nov. Feature freeze 08:00 Sat. Late = not judged.**

## 0. Session start (every session, every member)
1. Who am I? Read `CLAUDE.local.md` (gitignored, personal). If missing → this is a fresh clone: run
   `docs/ONBOARDING.md` checklist first, then create `CLAUDE.local.md` with the member's name + lane.
2. Read my lane file `docs/lanes/<LANE>.md` (mission, tasks, status & handoff block).
3. Check the bus: `gh issue list --label lane:<lane> --state open` and `gh issue view 1 --comments | tail -n 40`.
4. Resume from the lane file's **NEXT_SESSION_HANDOFF**.

## 1. Lanes & ownership (edit ONLY your lane's paths)
| Lane | Owner | Paths you own |
|---|---|---|
| UI | Member 1 (Honor X8b + Tab S6 Lite) | `app/`, `docs/STATUS.md`, `docs/lanes/UI.md` |
| MESH | see `docs/TEAM_ROLES.md` | `mesh/`, `docs/lanes/MESH.md`, `core/.../fake/FakeMeshEngine` |
| AI | see `docs/TEAM_ROLES.md` | `ai/`, `docs/lanes/AI.md`, `core/.../pipeline/RuleTriageEngine.kt` |
| LOCATE | see `docs/TEAM_ROLES.md` | `locate/`, `docs/lanes/LOCATE.md`, `core/.../fake/FakeLocateEngine` |
| TRUST | see `docs/TEAM_ROLES.md` | `trust/`, `scripts/fetch_help_points.sh`, `docs/lanes/TRUST.md` |

- `core/` is the shared contract (`Engines.kt`, models, `IncidentPipeline`). **Never change it silently**:
  open an `interface-change` issue first (see §5), wait for 👍 from affected lanes, then a small dedicated PR.
- `gradle/libs.versions.toml` is pre-filled. Need a new lib? Append at the end of a section + note it in your lane issue.
- Each lane swaps fakes → real **inside its own `*Module.create()`**. Nobody edits `app/ProximaGraph.kt` except UI.

## 2. Build & device commands
- JDK: use Android Studio's JBR (`JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`). `local.properties` holds `sdk.dir` (gitignored).
- Build: `./gradlew assembleDebug` · Install: `./gradlew installDebug` · Tests: `./gradlew test`
- Only your module: `./gradlew :mesh:assembleDebug` (faster).
- Logcat — NEVER unfiltered: `adb logcat -d -s Proxima:* AndroidRuntime:E | tail -n 60`. Log tag is always `Proxima`.
- Never read `build/`, `.gradle/`, `.idea/`, `*.apk`, model files (`*.task`, `*.litertlm`, `*.tflite`).

## 3. Context7 (mandatory)
Always use the Context7 MCP server (`use context7`) whenever querying or writing Jetpack Compose,
Google Nearby Connections, Android UWB (`androidx.core.uwb`), Room, CameraX/ML Kit, Tink, LiteRT-LM/MediaPipe,
SpeechRecognizer/TextToSpeech or Gradle/AGP APIs — so no deprecated or hallucinated methods are generated.

## 4. Product rules (judges check these)
- **Privacy:** never identify, profile or track individuals. Lost-person alerts = minimal description, no names,
  no photos. Child location only travels **sealed** to the guardian's key. Density = anonymous grid cells only.
- **AI selects, never authors medical advice.** It picks a vetted `ProtocolCard` (cited source) and fills fields.
  Every AI path has a rules fallback (`RuleTriageEngine`). Show `triagedBy` in the UI.
- **Real data only in the demo.** Simulation fakes are dev-only; UI shows a "SIM" badge; final demo = simulation OFF.
- **One feature, many uses:** everything is an `Incident` on one pipeline. No parallel message systems.
- **UI anti-slop:** see `docs/lanes/UI.md` design system (OLED black, phosphor green / amber / crimson,
  monospace telemetry, custom Canvas, haptics, bottom sheets — no default Material cards, no AlertDialogs, no lorem ipsum).
  Guardian/finder screens must be usable by a panicking parent without help: big targets, one action per screen.

## 5. GitHub Issues = the team bus (agents talk here, not in chat)
- `#1 📡 Global Status Board` — milestone summaries from every lane. Lane issues: `[LANE:UI]` … `[LANE:TRUST]`.
- **Ask another lane:** `gh issue create --title "[FROM→TO] <ask>" --label "request,lane:<to>" --body "<what/why/by when>"`.
- **Change the contract:** label `interface-change` + affected lanes; paste the exact Kotlin diff in the body.
- **Blocked:** add label `blocker` and comment on #1. **Decision worth logging:** label `decision`.
- Check the bus at session start, before each new task, and in the saving protocol. Never close another lane's issue.
- Comments are short: what changed, what's next, what you need. No secrets, no keys, no personal data.

## 6. Git workflow
- Work on `lane/<lane>` (e.g. `lane/mesh`). Never commit to or force-push `main`.
- Commit after every working step: `git add <your paths> && git commit -m "feat(<lane>): <summary>"`.
  Small and frequent — judges read the history. Commit with YOUR own git identity.
- Integrate at least every ~3h: `git fetch && git merge origin/main`, build passes, then
  `gh pr create --base main --fill` and `gh pr merge --merge` (merge commit, keeps history).
- AI disclosure: if Claude wrote most of a commit, add `Co-Authored-By` trailer as Claude Code does.

## 7. Decision log (required deliverable: "5–10 key decisions, with times")
Log EVERY meaningful decision the moment it is made, in your lane file under `## Decision log`:
`| HH:MM Day | Decision | Why | Alternatives rejected |`. Team-wide decisions go in `docs/DECISIONS.md`.
At ~10:00 Sat the team picks the best 5–10 for the README. Log generously — selection happens later.

## 8. SAVING PROTOCOL (exact trigger)
When the member says **"initiate saving protocol to resume smoothly on next fresh session to save context window"**
— or a milestone is finished, or context approaches ~80k tokens — immediately:
- (a) Update your lane file: `NEXT_SESSION_HANDOFF` (what works, what's broken/mocked, exact next step,
  files touched), `## Progress log` (timestamped line), `## Decision log` (any new decisions).
  If you changed `core/` interfaces, update `docs/ARCHITECTURE.md`. UI lane also refreshes `docs/STATUS.md`.
- (b) Commit + push your lane branch (`git add <your paths> docs/lanes/<LANE>.md && git commit && git push`).
- (c) `gh issue comment <your lane issue> --body "<≤8-line progress + interface changes + next step>"`
  and a one-line summary on `#1`.
- (d) Confirm, then tell the member exactly: *"Saving protocol complete and synced to GitHub. Run `/clear` now,
  then start your next session by saying: Read CLAUDE.md and docs/STATUS.md and resume."*

## 9. Security
Never commit or post API keys, keystores (`.jks`), private keys, or personal data. Local config → `local.properties`.
Models are sideloaded with `adb push`, never committed.
