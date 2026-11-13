#!/usr/bin/env bash
# Run ONCE (Member 1) after the first push: creates labels + the 6 bus issues.
# Requires: gh installed and `gh auth login` done. Usage: bash scripts/bootstrap_github.sh
set -euo pipefail
REPO="Mikhil-sec/Proxima"

label() { gh label create "$1" --repo "$REPO" --color "$2" --description "$3" --force >/dev/null && echo "label $1"; }
label "lane:ui"          "00FF9D" "UI + Command Center lane"
label "lane:mesh"        "1F6FEB" "MESH lane"
label "lane:ai"          "A371F7" "AI lane"
label "lane:locate"      "FFB800" "LOCATE lane"
label "lane:trust"       "3FB950" "TRUST lane"
label "request"          "D4C5F9" "One lane asks another for something"
label "interface-change" "FF2A55" "Change to core/ contract, needs approval"
label "blocker"          "B60205" "Someone is blocked"
label "decision"         "0E8A16" "Decision for the decision log"
label "status"           "C5DEF5" "Status updates"
label "surprise-card"    "FBCA04" "Surprise card constraint"

issue() { gh issue create --repo "$REPO" --title "$1" --label "$2" --body "$3"; }

issue "📡 Global Status Board" "status" "Every lane posts milestone summaries and saving-protocol one-liners here.
Rules: CLAUDE.md §5. Dashboard: docs/STATUS.md. Milestones: docs/TEAM_ROLES.md.
Format: \`[LANE] HH:MM — done / next / need\`"

for L in UI MESH AI LOCATE TRUST; do
  l=$(echo "$L" | tr '[:upper:]' '[:lower:]')
  issue "[LANE:$L] lane tracker" "lane:$l,status" "Lane brief, tasks, handoff and decision log: docs/lanes/$L.md
Branch: lane/$l
Post here: progress (saving protocol), questions to you from other lanes go as separate issues labelled \`lane:$l\`.
First comment from the owner: 'Online: <name>, <phone>, checklist passed.'"
done

echo "Pin the status board: gh issue pin 1 --repo $REPO"
gh issue pin 1 --repo "$REPO" || true
gh issue list --repo "$REPO"
