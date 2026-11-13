# Proxima

**Offline, phone-to-phone safety network for big crowds in Mauritius.** When tens of thousands gather at
Ganga Talao for Maha Shivaratri, at Ganesh Chaturthi, concerts or on campus, mobile networks collapse.
Proxima lets people raise and route emergencies, find lost children and get guided help with **no network at all**.

> Hackathon *Life Powered by Tech*, 13–14 Nov 2026 · Theme 1: **Powered to Protect**

## Problem & user
<!-- TRUST assembles. Source: docs/PRODUCT.md -->

## How it works
<!-- one diagram + 5 bullets. Source: docs/ARCHITECTURE.md -->

## Data sources
<!-- TRUST: dataset name, URL, licence, fetch date, how it powers a feature -->

## Tech stack
Kotlin · Jetpack Compose · Google Nearby Connections · Room · androidx UWB · Fused Location · Tink ·
CameraX + ML Kit · on-device LLM (runtime: TBD by AI lane) · Android SpeechRecognizer / TextToSpeech

## AI tools used (disclosure)
| Tool | What we used it for |
|---|---|
| Claude Code (Anthropic) | Project scaffolding, shared interfaces, docs; each lane lists further use below |
<!-- each lane: add a row for how YOU used AI -->

## Surprise card
<!-- see docs/SURPRISE_CARD.md -->

## Key decisions (5–10, with times)
<!-- final shortlist from docs/DECISIONS.md + lane decision logs -->

## How to run
1. Install the APK from Releases (or build: `./gradlew assembleDebug`, JDK 17+, Android SDK 37).
2. Allow Nearby devices, Location, Microphone, Camera.
3. Airplane mode is fine. Keep Bluetooth + Wi-Fi + Location ON.

## Team
See `docs/TEAM_ROLES.md`.
