# Post-clone onboarding checklist (for each member's Claude)

Claude: run this top to bottom on a fresh clone. Fix what you can and report the rest in one short table.
Do not change shared files to "fix" a problem on your machine. Machine-specific fixes go in gitignored
files (`local.properties`, `CLAUDE.local.md`, `.claude/settings.local.json`).

## A. Identity
- [ ] Ask the member: name, member number, lane (UI / MESH / AI / LOCATE / TRUST), phone model.
- [ ] Create `CLAUDE.local.md` in the repo root (it is gitignored):
  ```
  # Me
  Member N — <name> — lane <LANE> — phone <model> — GitHub @<handle>
  My lane file: docs/lanes/<LANE>.md · My lane issue: [LANE:<LANE>] (gh issue list --search "LANE:<LANE> in:title")
  My branch: lane/<lane>
  ```
- [ ] `git config user.name` and `git config user.email` are the member's own. Judges read commit authors.

## B. Tooling (run `bash scripts/doctor.sh` for the mechanical checks)
- [ ] `gh --version` works. If missing: `winget install --id GitHub.cli`, then `gh auth login`.
- [ ] `gh repo view Mikhil-sec/Proxima` works, and the member has push access (Member 1 adds collaborators).
- [ ] JDK 17+ for Gradle. Use Android Studio's JBR: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`
      (a JRE 8 in `JAVA_HOME` will break the build).
- [ ] `local.properties` exists with `sdk.dir=...` pointing to the Android SDK (Android Studio creates it on first open).
- [ ] `adb devices` lists the phone (Developer options → USB debugging ON).
- [ ] Context7 MCP is available in this session (the tools `resolve-library-id` and `query-docs` exist).
      If missing: `claude mcp add --transport http context7 https://mcp.context7.com/mcp`, then restart Claude Code.

## C. Settings conflicts after clone
- [ ] The repo ships only `.claude/settings.json` (shared deny rules for build folders). Personal settings
      (idle compaction off, model choice, extra MCP servers) belong in the USER settings
      (`~/.claude/settings.json`) or `.claude/settings.local.json`, never in the shared file.
- [ ] If Claude Code prompts to approve project settings or MCP servers, approve the Context7 one.
- [ ] `git status` is clean after setup. If personal files show up, add them to `.git/info/exclude`
      (local only), not to `.gitignore`.

## D. Build & run
- [ ] `./gradlew assembleDebug` → BUILD SUCCESSFUL (the first run downloads Gradle 9.9.1, about 2–5 min).
- [ ] `./gradlew installDebug` → the app opens and shows "Proxima wiring check — sim=true" with peers and incidents ticking.

## E. Join the bus
- [ ] `git switch -c lane/<lane> && git push -u origin lane/<lane>`
- [ ] Fill in your row in `docs/TEAM_ROLES.md` (commit on your lane branch).
- [ ] `gh issue comment <lane issue #> --body "Online: <name>, <phone>, checklist passed. Starting: <first task>"`
- [ ] Read `docs/lanes/<LANE>.md` and `docs/ARCHITECTURE.md`.

## F. Confirm the rules
- [ ] Claude states back, in 3 lines: (1) which paths it may edit, (2) the exact saving-protocol trigger
      phrase and steps (`CLAUDE.md` §8), (3) where decisions get logged (`CLAUDE.md` §7).

Report format: `| check | ✅/❌ | fix applied or action needed |`
