# Product brief

## Problem & user (be specific: judges penalise "everyone in Mauritius")
**Hero user:** a parent with a young child on the Maha Shivaratri pilgrimage to Ganga Talao (Grand Bassin).
There are tens of thousands of people in the crowd, cell networks are congested or dead, and the child gets separated.
There is no way to alert the people nearby, and no way to find the child once someone has them.

**Same pain, year-round:** helpers who see medical emergencies or hazards (fire, fallen branches, crush risk) at
Ganesh Chaturthi, Père Laval, concerts, political rallies and university campuses, where they can't reach help or warn others.

## What Proxima does
1. **One-button SOS (voice or tap).** On-device AI turns panicked speech into a ~200-byte structured incident
   (type, severity, landmark). It travels phone to phone with no network, over Bluetooth/Wi-Fi in several hops.
2. **Guided response.** Vetted protocol steps (with cited sources) are read aloud, plus the **nearest real help point**
   (health centre, police, fire station) from the Mauritian dataset, with distance and direction.
3. **Lost & found with privacy, no extra hardware.** The guardian reports "lost child" with a short description
   (age, clothes, no name, no photo). Someone who is with the child taps "I'm with them" on that alert; their location
   goes **encrypted to the guardian's phone only**, and the guardian's radar points the way (UWB precision on flagships).
4. **Command Center** (tablet at a police or first-aid post): a live incident heatmap, the mesh topology, and
   merged incident counts ("Fire — 15 reports").

## Real dataset (rule: it must power a feature)
- **Mauritius help points:** OpenStreetMap (ODbL) health facilities, police, fire stations, pharmacies,
  water and toilets (OSM Mauritius has no defibrillators, assembly points or ambulance stations). Powers "nearest help"
  and the Command Center map.
- **Response protocols:** first-aid and safety steps transcribed from an authoritative source (e.g. IFRC
  International First Aid Guidelines / Red Cross), with the source stored on every card.

## Scope
| Core (must work end to end, live) | Stretch (only after M3) |
|---|---|
| Multi-hop mesh incident bus (hop limit, dedup, store-and-forward) | Anonymous crowd-density heatmap |
| SOS with voice, on-device AI, structured incident; tiered fallback | Acoustic chirp fallback (FSK) |
| Guided response: protocol card + nearest help point from the dataset | Hazard photo classification on device |
| Lost-child alert → I'm with them → sealed FoundNotice → guardian radar (UWB, GPS, RSSI) | Full Kreol/French voice input |
| Tablet Command Center: heatmap, topology, merged incidents | |

Cut on purpose: duress PIN and decoy calculator, cyber-warfare branding, LLM "SitRep" chatbot.

## Answers for judges ("Real-world impact: who runs it, what it costs, how it reaches users")
- **Who runs it:** event organisers and police / first-aid posts. Their tablets and volunteers' phones form the backbone.
- **Cost:** no servers and no data plan; it runs on phones people already carry.
- **Reach:** QR posters at transport hubs and entrances (where there's still signal), then phone-to-phone APK
  sharing with Quick Share inside the venue, so the app spreads the same way the mesh does.
- **Why on-device AI?** It cuts bandwidth (a 30-second voice note becomes a 200-byte packet over BLE) and keeps
  privacy (voice never leaves the phone).

## Demo script (≤ 6 min of the 10)
1. Every phone in airplane mode, held up. "Grand Bassin, Maha Shivaratri: no network."
2. The S24 Ultra holds SOS: "a man collapsed near the food stalls, he's not breathing". It becomes a CRITICAL card
   (by: on-device AI), hops A→B→C on the tablet's topology view, and CPR steps are read aloud with the nearest health centre.
3. The A56 (Tier 2) reports the same event, and the AI work is offloaded. The tablet shows "2 reports", merged.
4. Lost child: the guardian reports "girl, about 6, red dress". A "finder" who is with her opens the alert and taps
   "I'm with them"; the guardian's phone gets a sealed alert and a radar. Two UWB phones show the arrow and distance.
5. Tablet: heatmap plus incident list, then the closing line.
