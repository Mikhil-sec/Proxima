#!/usr/bin/env bash
# MESH lane: prepare every USB-connected phone for a real (simulation OFF) mesh test.
#   bash mesh/tools/mesh_device_test.sh          # install + grant + sim off + launch on all phones
#   bash mesh/tools/mesh_device_test.sh ping S1  # make phone with serial S1 send a test packet
#   bash mesh/tools/mesh_device_test.sh logs     # last mesh log lines from every phone
set -euo pipefail
cd "$(dirname "$0")/../.."

PKG=mu.proxima.app
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB=adb
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"

serials() { "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'; }

case "${1:-setup}" in
  setup)
    [ -n "$(serials)" ] || { echo "No phone visible to adb (USB debugging on? data cable? prompt accepted?)"; exit 1; }
    ./gradlew installDebug --console=plain -q
    PREFS='<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="simulation" value="false" /></map>'
    for s in $(serials); do
      echo "== $s ($("$ADB" -s "$s" shell getprop ro.product.model | tr -d '\r'))"
      for p in BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT NEARBY_WIFI_DEVICES POST_NOTIFICATIONS \
               ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
        "$ADB" -s "$s" shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true
      done
      "$ADB" -s "$s" shell am force-stop "$PKG"
      echo "$PREFS" | "$ADB" -s "$s" shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/proxima.xml'"
      "$ADB" -s "$s" logcat -c
      "$ADB" -s "$s" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
      echo "   launched with simulation=false"
    done
    echo "Wait ~15 s, then: bash mesh/tools/mesh_device_test.sh logs"
    ;;
  ping)
    s="${2:?usage: ping <serial>}"
    "$ADB" -s "$s" shell am broadcast -a mu.proxima.mesh.PING -p "$PKG" >/dev/null
    echo "ping sent from $s"
    ;;
  logs)
    for s in $(serials); do
      echo "== $s"
      "$ADB" -s "$s" logcat -d -s Proxima:* AndroidRuntime:E | tail -n "${2:-25}"
    done
    ;;
  *) echo "usage: $0 [setup|ping <serial>|logs [n]]"; exit 2 ;;
esac
