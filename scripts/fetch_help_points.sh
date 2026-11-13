#!/usr/bin/env bash
# TRUST lane: fetch the real Mauritius help-point dataset from OpenStreetMap (ODbL) via Overpass.
# Usage: bash scripts/fetch_help_points.sh     Output: trust/src/main/assets/data/osm_help_points_raw.json
set -uo pipefail
OUT="trust/src/main/assets/data/osm_help_points_raw.json"
mkdir -p "$(dirname "$OUT")"
BBOX="-20.56,57.28,-19.95,57.83"   # Mauritius main island (S,W,N,E)
Q="[out:json][timeout:120];(
nwr[\"amenity\"~\"^(hospital|clinic|doctors|pharmacy|police|fire_station|drinking_water|toilets)$\"]($BBOX);
nwr[\"emergency\"~\"^(ambulance_station|assembly_point|defibrillator)$\"]($BBOX);
nwr[\"healthcare\"~\"^(hospital|clinic|centre)$\"]($BBOX);
);out center tags;"

for url in https://overpass-api.de/api/interpreter \
           https://overpass.private.coffee/api/interpreter \
           https://maps.mail.ru/osm/tools/overpass/api/interpreter \
           https://overpass.kumi.systems/api/interpreter; do
  for attempt in 1 2 3; do
    echo "→ $url (attempt $attempt)"
    curl -s -m 180 -A "Proxima-hackathon/0.1" -H "Accept: application/json" --data-urlencode "data=$Q" "$url" -o "$OUT.tmp"
    if head -c 50 "$OUT.tmp" | grep -q '"version"'; then
      mv "$OUT.tmp" "$OUT"
      echo "✔ saved $OUT ($(wc -c < "$OUT") bytes), fetched $(date '+%Y-%m-%d %H:%M')"
      grep -o '"amenity": "[a-z_]*"' "$OUT" | sort | uniq -c
      exit 0
    fi
    sleep $((attempt * 10))
  done
done
rm -f "$OUT.tmp"
echo "✘ all Overpass mirrors failed. Fallbacks: https://overpass-turbo.eu (run the query manually, export JSON),"
echo "  or a Geofabrik Mauritius extract, or data.govmu.org health facility / police station lists."
exit 1
