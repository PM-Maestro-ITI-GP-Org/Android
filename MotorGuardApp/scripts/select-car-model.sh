#!/usr/bin/env bash
#
# Pick which 3D vehicle model the Diagnostics screen renders.
#
# Scans the model library for .glb / .gltf files, lets you choose one, and copies it to
# app/src/main/assets/car_model.glb — the single path Car3dRenderer loads. Also writes
# car_model.source.txt recording where the model came from and its licence, because the
# Porsche Mission E model is CC-BY-4.0 and attribution is a legal requirement, not a nicety.
#
#   ./scripts/select-car-model.sh          # interactive menu
#   ./scripts/select-car-model.sh --list   # just show what's available
#   ./scripts/select-car-model.sh 2        # non-interactive: pick entry 2
#
# Component models under motor_battery_models/ are inputs to tools/prep_car.py, not
# vehicles in their own right, so they are not offered here.
#
# Model library is found automatically: vehicle3dModel/ beside MotorGuardApp (in-repo) or
# one level above it (outside the repo). Override with:
#   CAR_MODEL_DIR=/path/to/models ./scripts/select-car-model.sh
#
set -euo pipefail

APP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS_DIR="$APP_ROOT/app/src/main/assets"
TARGET="$ASSETS_DIR/car_model.glb"
SOURCE_NOTE="$ASSETS_DIR/car_model.source.txt"

bold=$'\033[1m'; dim=$'\033[2m'; grn=$'\033[32m'; ylw=$'\033[33m'; red=$'\033[31m'; off=$'\033[0m'

die() { printf '%s\n' "${red}error:${off} $*" >&2; exit 1; }

# First existing candidate wins. In-repo location is preferred so a fresh clone works.
if [[ -n "${CAR_MODEL_DIR:-}" ]]; then
  MODEL_DIR="$CAR_MODEL_DIR"
else
  for candidate in "$APP_ROOT/.." "$APP_ROOT/../.." "$APP_ROOT"; do
    if [[ -d "$candidate/vehicle3dModel" ]]; then
      MODEL_DIR="$(cd "$candidate/vehicle3dModel" && pwd)"
      break
    fi
  done
fi

[[ -n "${MODEL_DIR:-}" && -d "$MODEL_DIR" ]] || die "no vehicle3dModel/ directory found next to or above $APP_ROOT
       Put your .glb files in one, or set CAR_MODEL_DIR=/path/to/models"

# Collect candidates. -print0 so paths with spaces survive.
models=()
while IFS= read -r -d '' f; do models+=("$f"); done \
  < <(find "$MODEL_DIR" -type f \( -iname '*.glb' -o -iname '*.gltf' \) \
        -not -path '*/motor_battery_models/*' -print0 | sort -z)

(( ${#models[@]} )) || die "no .glb or .gltf files under $MODEL_DIR"

# ---------------------------------------------------------------- glTF introspection
# Reads the JSON chunk of a .glb (or the file itself for .gltf) and reports what the
# Diagnostics screen actually cares about: vertex count (performance) and the renderable
# part names (HotspotGeometry resolves the 8 anchors from these).
describe_model() {
  python3 - "$1" <<'PY' 2>/dev/null || true
import json, re, struct, sys
path = sys.argv[1]
with open(path, 'rb') as f:
    if f.read(4) == b'glTF':
        f.read(8)                                   # version, total length
        chunk_len, _chunk_type = struct.unpack('<II', f.read(8))
        gltf = json.loads(f.read(chunk_len))
    else:
        f.seek(0)
        gltf = json.load(f)

verts = sum(
    gltf['accessors'][p['attributes']['POSITION']]['count']
    for m in gltf.get('meshes', []) for p in m['primitives']
)
print(f"      {verts:,} verts · {len(gltf.get('meshes', []))} meshes · "
      f"{len(gltf.get('materials', []))} materials · {len(gltf.get('images', []))} textures")

extras = gltf.get('asset', {}).get('extras', {})
if extras.get('title') or extras.get('license'):
    print(f"      {extras.get('title', '?')} — {extras.get('license', 'licence not declared')}")

# The part names HotspotGeometry looks for. Missing ones fall back to a bbox anchor table.
names = [n.get('name', '') for n in gltf.get('nodes', [])]
wanted = {'tire': r'tire|tyre|wheel', 'brakes': r'brake', 'doors': r'door',
          'battery': r'batt|charging', 'motor': r'motor|engine'}
found = {k: sum(1 for n in names if re.search(v, n, re.I)) for k, v in wanted.items()}
print("      hotspot parts: " + " · ".join(f"{k}={v}" for k, v in found.items()))
PY
}

list_models() {
  local i=1
  for m in "${models[@]}"; do
    printf '  %s%2d)%s %s  %s(%s)%s\n' "$bold" "$i" "$off" \
      "${m#"$MODEL_DIR"/}" "$dim" "$(du -h "$m" | cut -f1)" "$off"
    printf '%s' "$dim"; describe_model "$m"; printf '%s' "$off"
    i=$((i + 1))
  done
}

printf '\n%sVehicle models in%s %s\n\n' "$bold" "$off" "$MODEL_DIR"
list_models
printf '\n'

[[ "${1:-}" == "--list" ]] && exit 0

# ---------------------------------------------------------------- choose
if [[ -n "${1:-}" ]]; then
  choice="$1"
else
  current=""
  [[ -f "$SOURCE_NOTE" ]] && current="$(head -1 "$SOURCE_NOTE" | sed 's/^model: //')"
  [[ -n "$current" ]] && printf '%scurrently installed:%s %s\n' "$dim" "$off" "$current"
  read -rp "Select model [1-${#models[@]}]: " choice
fi

[[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= ${#models[@]} )) \
  || die "'$choice' is not a valid selection (expected 1-${#models[@]})"

picked="${models[$((choice - 1))]}"

# ---------------------------------------------------------------- install
# Car3dRenderer always loads car_model.glb. A .gltf pick is copied under that name too;
# it will only load if it is self-contained (embedded buffers), so warn rather than fail.
if [[ "${picked,,}" == *.gltf ]]; then
  printf '%swarning:%s .gltf files reference external .bin/texture files that will NOT be\n' "$ylw" "$off"
  printf '         copied into assets. Export as .glb (embedded) if the model fails to load.\n\n'
fi

mkdir -p "$ASSETS_DIR"
cp "$picked" "$TARGET"

{
  printf 'model: %s\n' "${picked#"$MODEL_DIR"/}"
  printf 'installed: %s\n' "$(date -Iseconds)"
  printf 'size: %s\n' "$(du -h "$TARGET" | cut -f1)"
  python3 - "$picked" <<'PY' 2>/dev/null || true
import json, struct, sys
with open(sys.argv[1], 'rb') as f:
    if f.read(4) == b'glTF':
        f.read(8)
        n, _ = struct.unpack('<II', f.read(8))
        gltf = json.loads(f.read(n))
    else:
        f.seek(0); gltf = json.load(f)
for k, v in gltf.get('asset', {}).get('extras', {}).items():
    print(f'{k}: {v}')
PY
} > "$SOURCE_NOTE"

printf '%s✓%s installed %s%s%s\n' "$grn" "$off" "$bold" "${picked#"$MODEL_DIR"/}" "$off"
printf '  → %s\n' "${TARGET#"$APP_ROOT"/}"
printf '  → %s %s(attribution record)%s\n\n' "${SOURCE_NOTE#"$APP_ROOT"/}" "$dim" "$off"

if grep -qi 'CC-BY' "$SOURCE_NOTE" 2>/dev/null; then
  printf '%sCC-BY licence — attribution is required in the shipped app.%s\n' "$ylw" "$off"
  grep -iE '^(author|title|source|license)' "$SOURCE_NOTE" | sed 's/^/  /'
  printf '\n'
fi

printf 'Rebuild to pick it up:  ./gradlew :app:assembleDebug\n\n'
