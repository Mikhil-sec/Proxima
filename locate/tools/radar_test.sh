#!/usr/bin/env bash
# LOCATE lane: real radar test (simulation OFF) on 2 USB phones, driven by the debug-only adb trigger
# (locate/src/main/java/mu/proxima/locate/LocateDebugTrigger.kt). No UI screen needed.
#
#   bash locate/tools/radar_test.sh setup                  # build, install, grant (incl. UWB), sim OFF, launch on every phone
#   bash locate/tools/radar_test.sh status                 # self GPS / heading / UWB / mesh peers per phone
#   bash locate/tools/radar_test.sh track GUARD TARGET     # GUARD tracks TARGET's node id (UWB → GPS → RSSI), prints fixes
#   bash locate/tools/radar_test.sh track GUARD TARGET gps # same, but also feeds TARGET's own GPS fix as the target position
#   bash locate/tools/radar_test.sh fixes [n]              # last n "locate fix" lines from every phone
#   bash locate/tools/radar_test.sh stop                   # stop tracking on every phone
#   bash locate/tools/radar_test.sh simon                  # put simulation back ON (teammates expect SIM by default)
#
# GUARD/TARGET are adb serials (`adb devices`). Demo guardian = S23+ (it has UWB angle-of-arrival).
# Needs: screens UNLOCKED, UWB ON (Settings → Connections → More connection settings → Ultra-Wideband), airplane OFF,
# Bluetooth + Wi-Fi + Location ON. Installs a side-by-side build `mu.proxima.app.locatetest` (locatetest.init.gradle):
# teammates' own mu.proxima.app is only force-stopped during the test, never replaced or uninstalled.
#   bash locate/tools/radar_test.sh cleanup                # uninstall the test build from every phone
set -euo pipefail
cd "$(dirname "$0")/../.."

PKG="${PKG:-mu.proxima.app.locatetest}"
TEAM_PKG=mu.proxima.app
ADB="${ADB:-/c/Users/$USERNAME/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB=adb
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"

serials() { "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'; }
sh_() { "$ADB" -s "$1" shell "${@:2}" 2>/dev/null | tr -d '\r'; }
model() { sh_ "$1" getprop ro.product.model; }
bc() { "$ADB" -s "$1" shell am broadcast -a "mu.proxima.locate.$2" -p "$PKG" "${@:3}" >/dev/null; }
node_of() { { "$ADB" -s "$1" logcat -d -s Proxima:I | grep -o 'mesh start node=[^ ]*' || true; } | tail -1 | cut -d= -f2 | tr -d '\r'; }
set_sim() {  # set_sim <serial> true|false
  local prefs="<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><boolean name=\"simulation\" value=\"$2\" /></map>"
  sh_ "$1" am force-stop "$PKG"
  echo "$prefs" | "$ADB" -s "$1" shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/proxima.xml'"
}

case "${1:-}" in
  setup)
    [ -n "$(serials)" ] || { echo "No phone visible to adb (USB debugging on? prompt accepted?)"; exit 1; }
    # PKG=mu.proxima.app → the team's normal build (installs over / replaces it); default → side-by-side test build.
    if [ "$PKG" = "$TEAM_PKG" ]; then ./gradlew :app:assembleDebug --console=plain -q
    else ./gradlew -I locate/tools/locatetest.init.gradle :app:assembleDebug --console=plain -q; fi
    for s in $(serials); do
      echo "== $s ($(model "$s"))"
      "$ADB" -s "$s" install -r --user 0 "$APK" | tr -d '\r' | tail -1
      # Samsung Device Care ("sleeping apps") suspends idle debug apps → launch shows a "paused" screen instead.
      sh_ "$s" pm unsuspend --user 0 "$PKG" >/dev/null || true
      sh_ "$s" am force-stop "$TEAM_PKG" || true   # their own build must not join this test mesh (not uninstalled)
      for p in BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT NEARBY_WIFI_DEVICES POST_NOTIFICATIONS \
               ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION UWB_RANGING RECORD_AUDIO; do
        "$ADB" -s "$s" shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true
      done
      set_sim "$s" false
      "$ADB" -s "$s" logcat -c
      sh_ "$s" monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
      echo "   launched with simulation=false"
    done
    echo "Wait ~15 s for the mesh to connect, then: $0 status"
    ;;
  status)
    for s in $(serials); do
      echo "== $s ($(model "$s")) node=$(node_of "$s")"
      bc "$s" STATUS
    done
    sleep 1
    for s in $(serials); do
      echo "-- $s"
      { "$ADB" -s "$s" logcat -d -s Proxima:* | grep -E "locate (status|debug)|uwb:" || true; } | tail -n 8 | tr -d '\r'
    done
    ;;
  track)
    g="${2:?usage: track <guardian-serial> <target-serial> [gps]}"; t="${3:?usage: track <guardian-serial> <target-serial> [gps]}"
    node=$(node_of "$t"); [ -n "$node" ] || { echo "No 'mesh start node=' line on $t (app running with sim OFF? run setup)"; exit 1; }
    extra=()
    if [ "${4:-}" = "gps" ]; then
      bc "$t" STATUS; sleep 1
      ll=$({ "$ADB" -s "$t" logcat -d -s Proxima:I | grep -o 'locate status self=[-0-9.]*,[-0-9.]*' || true; } | tail -1 | cut -d= -f2)
      [ -n "$ll" ] || { echo "$t has no GPS fix yet (go outdoors / wait)"; exit 1; }
      extra=(--es lat "${ll%,*}" --es lon "${ll#*,}")
      echo "target GPS = $ll (one-shot; re-run to refresh)"
    fi
    "$ADB" -s "$g" logcat -c
    bc "$g" TRACK --es node "$node" "${extra[@]}"
    echo "$g ($(model "$g")) tracking $node on $t ($(model "$t")). Ctrl-C to stop watching (tracking keeps running; '$0 stop')."
    "$ADB" -s "$g" logcat -s Proxima:* AndroidRuntime:E | grep --line-buffered -E "locate|uwb:|AndroidRuntime" | tr -d '\r'
    ;;
  fixes)
    for s in $(serials); do
      echo "== $s ($(model "$s"))"
      { "$ADB" -s "$s" logcat -d -s Proxima:* AndroidRuntime:E | grep -E "locate|uwb:|AndroidRuntime" || true; } | tail -n "${2:-20}" | tr -d '\r'
    done
    ;;
  stop)
    for s in $(serials); do bc "$s" STOP; echo "stop sent to $s"; done
    ;;
  simon)
    for s in $(serials); do set_sim "$s" true; echo "$s: simulation back ON (app stopped; reopen it)"; done
    ;;
  cleanup)
    for s in $(serials); do
      sh_ "$s" am force-stop "$PKG" || true
      "$ADB" -s "$s" uninstall --user 0 "$PKG" 2>/dev/null | tr -d '\r' | sed "s/^/$s: uninstall test build: /" || true
    done
    ;;
  *) echo "usage: $0 setup|status|track <guard> <target> [gps]|fixes [n]|stop|simon|cleanup"; exit 2 ;;
esac
