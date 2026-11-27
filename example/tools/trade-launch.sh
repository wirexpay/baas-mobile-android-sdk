#!/usr/bin/env bash
# Opens a debug build of the example on the Trade tab, with its config from env or example/trade.local.env.
# FOR TESTING ONLY. It passes values by variable and prints only key names. CI never runs it.
set -euo pipefail

if [[ -n "${CI:-}" || -n "${TEAMCITY_VERSION:-}" ]]; then
  echo "trade-launch.sh: refused on CI, a key never reaches a CI build" >&2
  exit 1
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL="${HERE}/trade.local.env"
if [[ -f "${LOCAL}" ]]; then
  # shellcheck disable=SC1090
  set -a; source "${LOCAL}"; set +a
fi

COMPONENT="com.wirexapp.baas.example.android/com.wirexapp.baas.example.MainActivity"
args=(-S -n "${COMPONENT}" -e screen trade)
set_keys=()
add() {
  local key="$1" value="${2:-}"
  if [[ -n "${value}" ]]; then
    args+=(-e "${key}" "${value}")
    set_keys+=("${key}")
  fi
}
add environment "${WIREX_ENVIRONMENT:-}"
add chainId "${WIREX_CHAIN_ID:-}"
add companyId "${WIREX_COMPANY_ID:-}"
add ownerKey "${WIREX_OWNER_KEY:-}"
add bearer "${WIREX_BEARER:-}"
add baasApiUrl "${WIREX_BAAS_API_URL:-}"

echo "trade-launch.sh: launching with ${set_keys[*]:-no values}"
adb shell am start "${args[@]}" >/dev/null
