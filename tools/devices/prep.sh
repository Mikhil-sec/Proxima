#!/bin/sh
# prep.sh [serial]: one-build install for M2 (#53). Keeps sideloaded models, grants perms, simulation OFF, launches.
export MSYS_NO_PATHCONV=1
ADB="${ADB:-/c/Users/naika/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
SP="$(cd "$(dirname "$0")" && pwd)"
S="${1:-$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}' | head -1)}"
[ -z "$S" ] && { echo "no device"; exit 1; }
"$ADB" devices | grep -q "^$S[[:space:]]*device$" || { echo "device $S not connected"; exit 1; }
A() { "$ADB" -s "$S" "$@"; }
PKG=mu.proxima.app; DATA=/sdcard/Android/data/$PKG/files; KEEP=/sdcard/Download/proxima-models-keep
echo "== $S $(A shell getprop ro.product.model | tr -d '\r')  Android $(A shell getprop ro.build.version.release | tr -d '\r')"
A shell dumpsys window | grep mCurrentFocus | sed 's/^ */focus: /'
if [ -z "$FINISH" ]; then
# 1. keep sideloaded models (uninstall deletes Android/data/<pkg>)
if A shell "[ -d $DATA/models ] && ls $DATA/models | head -1" | grep -q .; then
  A shell "rm -rf $KEEP; mv $DATA/models $KEEP" && echo "models moved aside: $(A shell ls $KEEP | tr '\r\n' '  ')"
fi
# 2. uninstall + install the one build
A uninstall $PKG >/dev/null 2>&1 && echo "old build uninstalled" || echo "no old build"
cd "$SP/../.." && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ANDROID_SERIAL="$S" ./gradlew :app:installDebug -q 2>&1 | grep -v "^w:" | grep -iE "installed|fail|error"
A shell pm path $PKG >/dev/null || { echo "INSTALL FAILED"; exit 2; }
fi
# 3. models back, copied by the APP uid: a shell-owned models/ dir is unreadable to the running app
#    (S24U: "no *.litertlm"). First launch creates the app external dir, then run-as copies in.
if A shell "[ -d $KEEP ]" ; then
  A shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4; A shell am force-stop $PKG
  # app uid cannot read /sdcard/Download (scoped storage): stage inside its own data dir, then copy as the app
  A shell "mkdir -p $DATA/incoming && mv $KEEP/* $DATA/incoming/" && A shell "run-as $PKG sh -c 'mkdir -p $DATA/models && cp $DATA/incoming/* $DATA/models/'" && A shell "rm -rf $DATA/incoming $KEEP" && echo "models restored, app-owned:" && A shell ls -ln $DATA/models
fi
# 4. runtime permissions
for p in $(A shell dumpsys package $PKG | sed -n '/requested permissions:/,/install permissions:/p' | grep -o 'android.permission.[A-Z_]*' | sort -u); do
  A shell pm grant $PKG $p >/dev/null 2>&1 && g="$g ${p#android.permission.}"
done; echo "granted:$g"
# 5. simulation OFF (debug build: write the prefs file before first launch)
A push "$SP/proxima-prefs.xml" /data/local/tmp/proxima-prefs.xml >/dev/null && A shell chmod 644 /data/local/tmp/proxima-prefs.xml
A shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat /data/local/tmp/proxima-prefs.xml > shared_prefs/proxima.xml'" && echo "simulation OFF set"
# 6. launch + verify
A shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
A shell uiautomator dump /sdcard/ui.xml >/dev/null; UI=$(A shell cat /sdcard/ui.xml)
if ! echo "$UI" | grep -q 'mu.proxima.app'; then echo "WARN: could not read the Proxima screen (device gone?)"
elif echo "$UI" | grep -q 'text="Simulation"'; then echo "WARN: Simulation badge visible"; else echo "OK: no Simulation badge"; fi
echo "$UI" | grep -oE 'text="[0-9]+ phones? nearby"|text="Looking for phones[^"]*"|text="Allow access"' | head -2
A exec-out screencap -p > "$SP/shots/prep-$S.png"; echo "shot: $SP/shots/prep-$S.png"
