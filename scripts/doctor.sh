#!/usr/bin/env bash
# Post-clone machine check. Usage: bash scripts/doctor.sh   (Git Bash on Windows is fine)
ok()   { printf '  \033[32m✔\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✘\033[0m %s\n     → %s\n' "$1" "$2"; }

echo "Proxima doctor"
command -v git >/dev/null && ok "git $(git --version | cut -d' ' -f3)" || bad "git missing" "install Git"
[ -n "$(git config user.name)" ] && ok "git user: $(git config user.name) <$(git config user.email)>" \
  || bad "git identity not set" "git config --global user.name 'Your Name'; git config --global user.email you@x"

if command -v gh >/dev/null; then
  gh auth status >/dev/null 2>&1 && ok "gh authenticated" || bad "gh not logged in" "gh auth login"
  gh repo view Mikhil-sec/Proxima >/dev/null 2>&1 && ok "repo reachable" || bad "repo not reachable" "ask Member 1 to add you as collaborator"
else
  bad "gh missing" "winget install --id GitHub.cli   (then gh auth login)"
fi

JBR="/c/Program Files/Android/Android Studio/jbr"
if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE 'version "(1[7-9]|2[0-9])'; then
  ok "JAVA_HOME ok: $JAVA_HOME"
elif [ -x "$JBR/bin/java" ]; then
  bad "JAVA_HOME not JDK17+ (${JAVA_HOME:-unset})" "export JAVA_HOME=\"$JBR\"   (add to ~/.bashrc)"
else
  bad "no JDK 17+ found" "install Android Studio (bundles JBR)"
fi

[ -f local.properties ] && grep -q '^sdk.dir=' local.properties && ok "local.properties sdk.dir set" \
  || bad "local.properties missing" "open project once in Android Studio, or write: sdk.dir=C\\:\\\\Users\\\\<you>\\\\AppData\\\\Local\\\\Android\\\\Sdk"

if command -v adb >/dev/null; then
  n=$(adb devices | grep -c 'device$'); [ "$n" -gt 0 ] && ok "adb: $n device(s)" || bad "adb: no device" "enable USB debugging, accept the prompt"
else
  bad "adb not on PATH" "add <sdk>/platform-tools to PATH"
fi

[ -f CLAUDE.local.md ] && ok "CLAUDE.local.md present ($(head -n 2 CLAUDE.local.md | tail -n 1))" \
  || bad "CLAUDE.local.md missing" "tell Claude: 'I am Member N, lane X' (docs/ONBOARDING.md §A)"

b=$(git branch --show-current); [[ "$b" == lane/* ]] && ok "on branch $b" || bad "on branch $b" "git switch -c lane/<lane>"
echo "Context7 MCP: check inside Claude Code (tools resolve-library-id / query-docs must exist)."
