#!/bin/sh
# chain.sh set|reset|status : demo relay chain A56 -> S24 Ultra -> tablet, set on all three at once (#3, #75 T2)
export MSYS_NO_PATHCONV=1
ADB="${ADB:-/c/Users/naika/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
A56=R5GL106BDCB; S24=R5CXA2VDYMY; TAB=R52W70DGYEA
for d in $A56 $S24 $TAB; do "$ADB" devices | grep -q "^$d[[:space:]]*device$" || { echo "not on a cable: $d"; exit 1; }; done
selfid() { "$ADB" -s $1 logcat -d -s Proxima:I | grep -oE "mesh start node=[0-9a-f]+" | tail -1 | cut -d= -f2; }
seenas() { "$ADB" -s $1 logcat -d -s Proxima:I | grep -oE "(found|connected) [0-9a-f]{8} \($2\)" | tail -1 | grep -oE "[0-9a-f]{8}"; }
allow() { "$ADB" -s $1 shell "am broadcast -a mu.proxima.mesh.ALLOW -p mu.proxima.app --es nodes '$2'" >/dev/null && echo "  $1 allow=$2"; }
case "$1" in
  set)
    A=$(selfid $A56); B=$(selfid $S24); C=$(selfid $TAB)
    [ -z "$C" ] && C=$(seenas $S24 COMMAND_CENTER)
    [ -z "$A" ] && A=$(seenas $S24 TIER2_SCOUT)
    echo "A56=$A S24U=$B TAB=$C"
    { [ -z "$A" ] || [ -z "$B" ] || [ -z "$C" ]; } && { echo "missing an id: restart Proxima on that device and retry"; exit 2; }
    allow $A56 "$B"; allow $S24 "$A,$C"; allow $TAB "$B"
    echo "wait ~30 s, then check: sh chain.sh status" ;;
  reset) for d in $A56 $S24 $TAB; do allow $d '*'; done ;;
  status) for d in $A56 $S24 $TAB; do echo "--- $d"; "$ADB" -s $d logcat -d -s Proxima:I | grep -E "allow-list|connected|dropping" | sed -E 's/^.{31}//' | tail -4; done ;;
  *) echo "usage: chain.sh set|reset|status" ;;
esac
