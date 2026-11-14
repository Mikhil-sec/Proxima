# Presentation script: show → tell → touch

**Format** (lead, 06:10 Sat): a short **sketch** with real phones, then **6 slides**, then **5 minutes hands-on** with
the judges holding the tablet. Simulation is OFF on every device. No jargon on stage: the slides carry the detail.

Everything below passed on real phones on Sat 14 Nov, 01:30–02:15 (#75).

## Cast and devices
| Person | Plays | Device |
|---|---|---|
| Mikhil | Narrator, then hands the tablet to the judges | Tab S6 Lite: Command Center ("the first-aid post") |
| Yorvan | The parent | S23+ (UWB, radar arrow) |
| Diya | The child | no phone |
| Chanum | The stranger who finds her | S26+ (UWB) |
| Seeya | Slides clicker; in the hands-on, hands the judge a phone | S24 Ultra |
| (on the table) | The rest of the crowd | Galaxy A56, Honor X8b |

## Pre-flight (T−5 min)
1. All devices charged, media volume up. **Airplane mode ON, then Bluetooth + Wi-Fi + Location back ON.** (No network is the point.)
2. Open Proxima on **every** device. Wait until the phones show "N phones nearby" (up to ~40 s).
3. Tablet on the Command Center. If old tickets are there, tap **Cleared** on each.
4. Yorvan and Chanum: both on the Proxima **Home** screen ("Need help?").
5. **Backup ready:** Chanum's video (#91) on the laptop, in case the mesh misbehaves.

## 1. The sketch (~90 s)
| # | Who | Says / does | Screen |
|---|---|---|---|
| 1 | Mikhil | *"Maha Shivaratri. Ganga Talao. Tens of thousands of people, and no signal."* | Holds up a phone: airplane mode |
| 2 | Yorvan + Diya | Walk together. Diya drifts away into the "crowd" and stops, upset. | — |
| 3 | Yorvan | Notices, panics: *"Where is she?"* Taps **Lost person** → (if "Listening": **Type instead**) → types `girl about 6, red dress` → **Check alert** → hands off; it sends after 3 s. | "Alert sent" |
| 4 | Mikhil | *"No name. No photo. Just what a stranger needs to recognise her."* | — |
| 5 | Chanum | Finds Diya: *"Are you lost?"* Opens **I'm with someone who is lost** (bottom of Home) → taps the **girl about 6, red dress** alert → **Yes, I'm with them**. | "Sharing your location with their guardian" |
| 6 | Yorvan | His phone: **"Someone is with your child"**. Taps **Show me the way**. Follows the **arrow** to Chanum and Diya. | Radar: UWB arrow, distance counting down |
| 7 | Yorvan | Reunited. Taps **We're together**. | — |
| 8 | Mikhil | *"Her location went only to her parent. Every phone in between carried it, and none of them could read it."* Then: *"Here's why we built it."* | Slides start |

**If something goes wrong in the sketch:**
- **No "Someone is with your child" after ~30 s:** Chanum checks he tapped "Yes, I'm with them". Keep the story going ("help is on the way"). If it still doesn't come, play the backup video.
- **Radar says "Getting a fix" or "step outside":** Yorvan and Chanum stand within a few metres. UWB takes over (both phones have it).
- **"Listening" or a download pop-up:** **Type instead** or **Cancel**.

## 2. Slides (max 6, owner Yorvan, #92)
Problem → Solution → Why it works with no network → Advantages proven on phones → Wild card "Scale up" →
Privacy, limits, next. One idea per slide, plain words.

## 3. Hands-on (5 min): let the judges do it
1. **Mikhil hands a judge the tablet**: *"You're the first-aid post."* It shows the lost-child alert from the sketch, and the crowd as anonymous squares.
2. **Seeya hands another judge a phone (A56 or S24 Ultra)**: *"Something's wrong: press Medical and tell it what you see."* (Type, or hold SOS and speak.)
3. The judge with the tablet sees **their colleague's alert arrive**: severity, **Verified**, and a **cited first-aid card**. Tap it to show the steps and **"Nearest help"**.
4. On the S24 Ultra: the review screen says **"Sorted by: On-device AI"** (decided on the phone in about a second), and **Hands-free** reads the first step aloud.
5. Point at the tablet line **"Relay: Surge control on, nothing waiting"**: the wild card, live. Tell more only if asked.
6. If time: **My people** on the S24 Ultra and A56 (already paired): *"two friends, the same six digits on both screens, and nobody broadcasts a search."*

## Never say
- That anything simulated is real. Everything live is simulation OFF.
- That we measured crowds of thousands: the surge numbers are a **model**.
- That the friend radar works indoors: it needs GPS. The lost-child radar uses UWB up close.

## Lead's laptop (optional, technical)
Forced relay chain for questions about multi-hop: `sh tools/devices/chain.sh set` (A56 → S24 Ultra → tablet, all at once on the
cables), then `sh tools/devices/chain.sh reset`. Never set it phone by phone in the dev sheet (#3).
