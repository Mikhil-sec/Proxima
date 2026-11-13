#!/usr/bin/env bash
# MESH lane: real (simulation OFF) mesh tests on USB-connected phones.
#
# Installs a side-by-side test build (mu.proxima.app.meshtest) so teammates' Proxima builds on shared phones are
# never replaced or deleted. The -Pmeshtest suffix comes from a machine-local Gradle init script
# (~/.gradle/init.d/); without it, set PKG=mu.proxima.app and drop -Pmeshtest.
#
#   bash mesh/tools/mesh_device_test.sh health        # battery / temperature / radios / storage per phone
#   bash mesh/tools/mesh_device_test.sh setup         # build, install, grant, sim off, launch on every phone
#   bash mesh/tools/mesh_device_test.sh ping S1       # phone S1 sends a test packet
#   bash mesh/tools/mesh_device_test.sh logs [n]      # last mesh log lines from every phone
#   bash mesh/tools/mesh_device_test.sh cleanup       # stop + uninstall the test build (teammates' app untouched)
set -euo pipefail
cd "$(dirname "$0")/../.."

PKG="${PKG:-mu.proxima.app.meshtest}"
TEAM_PKG=mu.proxima.app
ADB="${ADB:-/c/Users/$USERNAME/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB=adb
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"

serials() { "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'; }
sh_() { "$ADB" -s "$1" shell "${@:2}" 2>/dev/null | tr -d '\r'; }

case "${1:-setup}" in
  health)
    for s in $(serials); do
      echo "== $s $(sh_ "$s" getprop ro.product.model) API $(sh_ "$s" getprop ro.build.version.sdk)"
      sh_ "$s" 'dumpsys battery | grep -E "^  (level|temperature):" | tr "\n" " "; echo;
        echo "bt=$(settings get global bluetooth_on) wifi=$(settings get global wifi_on) loc=$(settings get secure location_mode) airplane=$(settings get global airplane_mode_on)";
        df -h /data | tail -1 | awk "{print \"storage free \" \$4 \" (\" \$5 \" used)\"}"'
      sh_ "$s" "pm list packages --user 0 mu.proxima" | sed 's/^/   /'
    done
    "$ADB" devices | awk 'NR>1 && $2!="device" && NF {print "!! " $1 " is " $2 " (unlock phone + Allow USB debugging)"}'
    ;;
  setup)
    [ -n "$(serials)" ] || { echo "No phone visible to adb (USB debugging on? data cable? prompt accepted?)"; exit 1; }
    ./gradlew :app:assembleDebug -Pmeshtest --console=plain -q
    APK=/c/dev/proxima-build/_app_meshtest/outputs/apk/debug/app-debug.apk
    PREFS='<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="simulation" value="false" /></map>'
    for s in $(serials); do
      echo "== $s ($(sh_ "$s" getprop ro.product.model))"
      # --user 0: Samsung Secure Folder adds extra users that break multi-user installs.
      "$ADB" -s "$s" install -r --user 0 "$APK" | tr -d '\r' | tail -1
      sh_ "$s" am force-stop "$TEAM_PKG" || true   # teammate's build must not join this test mesh (not uninstalled)
      for p in BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT NEARBY_WIFI_DEVICES POST_NOTIFICATIONS \
               ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
        "$ADB" -s "$s" shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true
      done
      sh_ "$s" am force-stop "$PKG"
      echo "$PREFS" | "$ADB" -s "$s" shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/proxima.xml'"
      "$ADB" -s "$s" logcat -c
      sh_ "$s" monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
      echo "   launched $PKG with simulation=false"
    done
    echo "Keep screens UNLOCKED (location is foreground-only). Wait ~15 s, then: $0 logs"
    ;;
  ping)
    s="${2:?usage: ping <serial>}"
    "$ADB" -s "$s" shell am broadcast -a mu.proxima.mesh.PING -p "$PKG" >/dev/null
    echo "ping sent from $s"
    ;;
  allow)   # allow <serial> "SELF-AAAA,SELF-BBBB"   or   allow <serial> "*"
    s="${2:?usage: allow <serial> <nodes|*>}"
    "$ADB" -s "$s" shell am broadcast -a mu.proxima.mesh.ALLOW -p "$PKG" --es nodes "'${3:-*}'" >/dev/null
    echo "allow-list on $s = ${3:-*}"
    ;;
  ids)
    for s in $(serials); do
      echo "$s $({ "$ADB" -s "$s" logcat -d -s Proxima:I | grep -o 'mesh start node=[^ ]*' || true; } | tail -1 | cut -d= -f2)"
    done
    ;;
  logs)
    for s in $(serials); do
      echo "== $s"
      { "$ADB" -s "$s" logcat -d -s Proxima:* AndroidRuntime:E | grep -v "beginning of" || true; } | tail -n "${2:-25}" | tr -d '\r'
    done
    ;;
  cleanup)
    for s in $(serials); do
      sh_ "$s" am force-stop "$PKG" || true
      "$ADB" -s "$s" uninstall --user 0 "$PKG" 2>/dev/null | tr -d '\r' | sed "s/^/$s: uninstall test build: /" || true
    done
    ;;
  *) echo "usage: $0 [health|setup|ping <serial>|logs [n]|cleanup]"; exit 2 ;;
esac
