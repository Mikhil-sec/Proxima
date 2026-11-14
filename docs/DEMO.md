# Live demo script (≈ 3 min, simulation OFF)

Everything here passed on real phones on Sat 14 Nov, 01:30–02:15 (#75). Simulation is OFF on every device.
Each step names the device, who holds it, what to tap, what the judges should see, and what to do if it doesn't happen.

## Cast and devices
| Device | Holder | Role in the demo |
|---|---|---|
| Tab S6 Lite (on the lead's laptop cable) | Mikhil, presenter | Command Center, faces the judges |
| Galaxy A56 (cable) | Diya | Reporter: the alert that hops |
| S24 Ultra (cable) | Seeya | Relay phone, then on-device AI |
| S23+ | Yorvan | Guardian (UWB radar) |
| S26+ | Chanum | Finder |
| Honor X8b | anyone, on the table | Extra phone in the crowd (or "Medic tent 2" if #89 passed) |

## Pre-flight (T−5 min)
1. All devices charged; Bluetooth, Wi-Fi and Location ON; media volume up. Optional: airplane mode ON with Bluetooth + Wi-Fi switched back on (that is the point: no network).
2. Open Proxima on **every** device. Wait until phones show "N phones nearby" (up to ~40 s; Nearby retries once).
3. Lead (laptop): set the relay chain **A56 → S24 Ultra → tablet**: `sh chain.sh set` (all three at once; never phone by phone in the dev sheet, #3).
4. Tablet on the Command Center, "Nobody waiting". Clear old tickets with "Cleared" if any.

## 1. One SOS, no network, two hops (≈ 40 s)
- **Diya (A56):** Home → **Medical** → (if "Listening": **Type instead**) → type `man collapsed near the gate, not breathing` → **Check alert**, then hands off: it sends after 3 s → "Alert sent".
- **Mikhil (tablet):** ticket **"Immediate · Not breathing"** appears. Tap it: **Travelled 2 hops · Signature Verified · Sorted by** and the cited CPR card.
- **Say:** "No network. The alert went from her phone through Seeya's phone to this post, signed, so nobody can fake or alter it. It picked a cited IFRC card. The AI never writes medical advice."
- **If the ticket doesn't come within 20 s:** show the other phones' "1 alert near you". Lead runs `sh chain.sh reset` and the A56 sends again directly.
- **Lead then:** `sh chain.sh reset` (back to a normal mesh).

## 2. On-device AI (≈ 30 s)
- **Seeya (S24 Ultra):** hold the red **SOS** ring and say *"A woman is bleeding heavily from her leg near the stage"* (or **Medical → Type instead** and type it).
- **On "This is what we'll send":** read out **Sorted by: On-device AI**: Gemma, on the phone, ~1.4 s. It sends after 3 s.
- **Then:** turn on **Hands-free** under the bleeding card: it reads step 1 aloud. The tablet ticket says **On-device AI** too: what was sent is what was shown.
- **If voice fails:** "Type instead". If the AI is slow, the keyword rules still classify it; say so.

## 3. Lost child, found, privately (≈ 60 s)
- **Yorvan (S23+, guardian):** **Lost person** → type `girl about 6, red dress, near the gate` → send. No name, no photo.
- **Chanum (S26+, finder):** **I'm with someone who is lost** (bottom of Home) → the "girl about 6" alert → **Yes, I'm with them**.
- **Yorvan:** "Someone is with your child" pops up → **Show me the way** → radar: **UWB**, with the arrow pointing at Chanum. Chanum steps a few metres away: the distance follows.
- **Say:** "Her location went sealed to the guardian's key. Every phone in between relayed it and none could read it. We checked on three phones last night."
- **If the radar says "Getting a fix" / "step outside":** that's GPS indoors. Stand closer for UWB (S23+ ↔ S26+ both have it).
- **End:** Chanum taps **Their guardian is here**, Yorvan taps **We're together**.

## 4. The post sees the crowd (≈ 20 s)
- **Mikhil (tablet):** the plan shows the phones in anonymous 25 m squares (density), any **crowd risk** area, and the line **"Relay: Surge control on, nothing waiting."**
- **Say:** "Our surprise card was *Scale up*. What breaks with thousands of users is the relay storm. Each phone now forwards the first three reports of the same hazard per square, never merges people, and puts the most urgent first." (Slide has the model numbers.)
- **Optional, if #89 passed:** the green ring **"Medic tent 2"**, a medic post verified by the organiser, also first in "Nearest help" on any phone.

## 5. Optional, if time: My people (≈ 15 s)
- S24 Ultra and A56 are already paired: My people shows "Brother" / "sister", **"Phone heard just now"**. Pairing = the same six digits on both screens; nobody broadcasts a search.

## Never say
- That anything simulated is real. (Everything above is simulation OFF.)
- That we measured crowds of thousands: the surge numbers are a **model**.
- That the radar is precise indoors on GPS: UWB is the indoor answer.
