#!/bin/sh
# t.sh <serial> tt "<text contains>" | hold "<text>" | texts | shot name | key K | type "words"
export MSYS_NO_PATHCONV=1
ADB="${ADB:-/c/Users/naika/AppData/Local/Android/Sdk/platform-tools/adb.exe}"; S="$1"; shift
SP="$(cd "$(dirname "$0")" && pwd)"
dump() { "$ADB" -s $S shell "uiautomator dump /sdcard/ui.xml >/dev/null; cat /sdcard/ui.xml"; }
guard() { "$ADB" -s $S shell dumpsys window | grep mCurrentFocus | grep -q "mu.proxima.app/" || { echo "ABORT: not foreground"; exit 3; }; }
center() { b=$(dump | grep -oE "text=\"[^\"]*$1[^\"]*\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 | grep -oE '[0-9]+' | tail -4); [ -z "$b" ] && { echo "not on screen: $1" >&2; exit 1; }; set -- $b; echo "$(( ($1+$3)/2 )) $(( ($2+$4)/2 ))"; }
case "$1" in
  tt) guard; c=$(center "$2") || exit 1; "$ADB" -s $S shell input tap $c ;;
  hold) guard; c=$(center "$2") || exit 1; "$ADB" -s $S shell input swipe $c $c 900 ;;
  texts) dump | grep -oE 'text="[^"]+"' | tr '\n' ' ' ;;
  shot) "$ADB" -s $S exec-out screencap -p > "$SP/shots/$2.png"; echo "$SP/shots/$2.png" ;;
  key) guard; "$ADB" -s $S shell input keyevent "$2" ;;
  type) guard; "$ADB" -s $S shell input text "$(printf %s "$2" | sed 's/ /%s/g')" ;;
esac
