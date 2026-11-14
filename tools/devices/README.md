# Device tools (lead's laptop, Git Bash)

Demo devices only get builds from ONE laptop: each laptop signs debug builds with its own key (#53).
Set `ADB=/path/to/adb.exe` if it isn't the lead's default.

| Script | What it does |
|---|---|
| `prep.sh [serial]` | Full one-build install: keeps a sideloaded Gemma model (copied back **as the app**, a shell-owned folder is unreadable to it), uninstalls, installs, grants every runtime permission, sets **simulation OFF**, launches, checks the screen. `FINISH=1 prep.sh <serial>` completes an interrupted run without reinstalling. |
| `chain.sh set\|reset\|status` | Demo relay chain **A56 → S24 Ultra → tablet**, set on all three at once over adb (setting it phone by phone in the dev sheet can deadlock, #3). |
| `t.sh <serial> tt "<text>" \| hold "<text>" \| type "<words>" \| key K \| texts \| shot name` | Tap / long-press an element by its visible text, type, read the screen, screenshot. Refuses input unless Proxima is in the foreground. |

Update a device **in place** (keeps data, pairing, models): `ANDROID_SERIAL=<serial> ./gradlew :app:installDebug`.

**If a Samsung doesn't show in `adb devices`:** turn off Settings → Security and privacy → **Auto Blocker**, turn on USB debugging, and quit **spacedesk** on the laptop (it grabs Samsung phones into accessory mode).
