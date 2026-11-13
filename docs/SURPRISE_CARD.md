# Surprise card

Rule: adapt the existing app (don't restart), build the constraint in **visibly**, keep solving the original
problem, and tell the judges what changed and how we adapted. Bolting it on at the last minute is a listed classic mistake.

## When the card is drawn (10-minute drill)
1. Whoever draws it opens an issue: `gh issue create --title "🃏 Surprise card: <text>" --label "surprise-card,decision"`.
2. All five agents read it at their next bus check. Each lane comments within 10 minutes: "impact on my lane: none / small / big".
3. The team picks the smallest change that makes the constraint **visible in the demo**, and logs it in `docs/DECISIONS.md` with the time.
4. The owning lane implements it. The UI makes it visible. Add a README section and a pitch slide line.

## Card
- **Drawn at:** ~01:15 Sat 14 Nov (IET challenge card, "Spare: the scale test").
- **Text:** *Scale up.* "Your app suddenly has thousands of users at once. Identify one thing that would break, and
  adapt your design to handle it." Rule: "Show how your design handles a surge in users."
- **What breaks:** the **relay storm**. Every phone forwarded every packet instantly to every neighbour. In a crowd
  crush hundreds of people press SOS within seconds: radio links saturate, an unrelated cardiac arrest queues behind
  hundreds of copies of the same crush, and the 200-packet store-and-forward buffer evicted the *oldest* packet, so
  a flood of LOW traffic could push out a CRITICAL.
- **Our adaptation** (`mesh/.../SurgeControl.kt`, wired into `NearbyMeshEngine`):
  1. **One hazard is one event.** A relay forwards the first 3 reports of the same hazard per 25 m square per
     2 minutes, then counts the rest locally (the Command Center still merges them into one ticket).
     **People are never collapsed:** medical and lost-person reports always travel.
  2. **Rate cap + priority buffer.** Relaying is capped (30 burst, 10 broadcasts/s). Over the cap, packets wait:
     most severe first, and at equal severity a person beats a known hazard. When the buffer (200) is full, relayed
     beacons are shed first, then LOW. Your own SOS never waits.
  3. **Severity-aware store-and-forward:** evict the least severe packet, not the oldest.

  With normal traffic none of these limits is reached, so demo behaviour is unchanged.
- **Where it's visible:** the "Relay: ..." line under the Command Center plan, and the dev sheet's "Surge control"
  row. They say how many relays wait and how many repeat hazard reports were counted but not re-sent.
- **Evidence:** JVM tests in `SurgeControlTest`, including a **surge model** that runs the real rule classes:
  1,000 phones spread over a 500 x 500 m ground plus 400 packed within 30 m of a gate, each linked to its 6 nearest
  within 60 m, every phone beaconing, all 400 reporting the crush within 10 s, and an unrelated cardiac report at t = 8 s.

  | Radio speed per phone (assumed) | Design | Unrelated critical reaches the post | Radio sends | Worst backlog at one phone |
  |---|---|---|---|---|
  | 60 payloads/s | plain flooding | 0.4 s | 1.20 M | 113 |
  | 60 payloads/s | **surge rules** | **0.4 s** | **0.77 M (−36%)** | **27** |
  | 30 payloads/s | plain flooding | 0.7 s | 1.06 M | 137 |
  | 30 payloads/s | **surge rules** | **0.4 s** | **0.77 M (−27%)** | **42** |
  | 15 payloads/s | both | **not within 32 s** | | |

  **It's a model, not a measurement.** We have not measured Nearby's real throughput for many small payloads, or
  any crowd larger than 6 phones.
- **Limitation the model found:** at 15 payloads/s neither design delivers. Dedup keeps the *first* copy of a
  packet; under uneven delays that is often a copy that took a detour and has fewer hops left, so the hop budget
  runs out before the post. Next step: re-forward a duplicate that arrives with more hops left (needs a change to
  MESH's seen-store, deliberately not rushed before the freeze). The model also showed that a crush lengthens routes
  through it (7 hops to 12), because packed phones take each other's links.

## Slide text (one of the 6 slides)
**Scale up: thousands of phones, one crowd crush**
- What breaks: the relay storm. 400 people report the same crush, every phone re-sends every copy, and a cardiac
  arrest queues behind them.
- Our change: one hazard is one event per 25 m square (people are never merged away), a rate cap with a priority
  buffer, and your own SOS never waits.
- Model of 1,400 phones: **36% fewer radio sends, a 4x smaller backlog, and the unrelated critical still arrives in 0.4 s.**
- Honest limit: on very slow links neither design delivers. We found why, and the fix is next.
- Visible live: the "Relay" line on the Command Center.

**Optional simulation slide (clearly labelled):** a Command Center screenshot in simulation mode, with the yellow
"Simulation" badge visible, 10+ tickets and crowd-risk areas. Caption: *"Simulated crowd, to show what a busy post
sees. The live demo runs with simulation off."* Put it next to the real 4-phone Command Center screenshot.
