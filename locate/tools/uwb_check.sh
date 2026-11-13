#!/usr/bin/env bash
# LOCATE P0 device check: UWB ranging between two phones (distance + azimuth?).
# Usage:  bash locate/tools/uwb_check.sh                 (auto-picks the 2 connected phones)
#         bash locate/tools/uwb_check.sh SERIAL_A SERIAL_B   (A = controller, B = controlee)
# Needs: both phones on USB with debugging allowed, debug APK installed (./gradlew installDebug),
#        UWB ON on both (Settings → Connections → More connection settings → Ultra-Wideband), airplane mode OFF.
set -euo pipefail

ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null || ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
ACT="mu.proxima.app/mu.proxima.locate.debug.UwbCheckActivity"
PKG="mu.proxima.app"

if [ $# -ge 2 ]; then A="$1"; B="$2"; else
  mapfile -t DEVS < <("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
  [ "${#DEVS[@]}" -ge 2 ] || { echo "Need 2 phones on adb, found ${#DEVS[@]}."; exit 1; }
  A="${DEVS[0]}"; B="${DEVS[1]}"
fi
echo "controller=$A  controlee=$B"

local_line() {  # wait up to 15 s for the LOCAL line on device $1
  for _ in $(seq 1 30); do
    line=$("$ADB" -s "$1" logcat -d -s Proxima:I | grep "uwbcheck LOCAL" | tail -n 1 || true)
    [ -n "$line" ] && { echo "$line"; return 0; }
    fail=$("$ADB" -s "$1" logcat -d -s Proxima:E | grep "uwbcheck FAIL" | tail -n 1 || true)
    [ -n "$fail" ] && { echo "$1: $fail" >&2; return 1; }
    sleep 0.5
  done
  echo "$1: no LOCAL line after 15 s (is the screen unlocked?)" >&2; return 1
}
field() { sed -n "s/.* $1=\([^ ]*\).*/\1/p" <<<"$2"; }

for d in "$A" "$B"; do
  "$ADB" -s "$d" shell am force-stop "$PKG" >/dev/null
  "$ADB" -s "$d" shell pm grant --user 0 "$PKG" android.permission.UWB_RANGING 2>/dev/null || true
  "$ADB" -s "$d" logcat -c
done
"$ADB" -s "$A" shell am start -n "$ACT" --es role controller >/dev/null
"$ADB" -s "$B" shell am start -n "$ACT" --es role controlee >/dev/null

LA=$(local_line "$A"); LB=$(local_line "$B")
echo "A: $LA"; echo "B: $LB"
ADDR_A=$(field addr "$LA"); ADDR_B=$(field addr "$LB")
CH=$(field channel "$LA"); PRE=$(field preamble "$LA")
SESSION=$(( (RANDOM << 15 | RANDOM) & 0x7FFFFFFF ))

"$ADB" -s "$A" shell am start -n "$ACT" --es peer "$ADDR_B" --ei session "$SESSION" >/dev/null
"$ADB" -s "$B" shell am start -n "$ACT" --es peer "$ADDR_A" --ei session "$SESSION" --ei channel "$CH" --ei preamble "$PRE" >/dev/null
echo "Ranging started (session $SESSION, channel $CH, preamble $PRE). Point the phones' backs at each other."
echo "Live from controller ($A) — Ctrl+C to stop:"
"$ADB" -s "$A" logcat -s Proxima:* | grep --line-buffered "uwbcheck"
