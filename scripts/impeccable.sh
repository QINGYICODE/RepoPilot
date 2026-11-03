#!/usr/bin/env bash
# Keep the design engine cache inside this writable workspace, never in a protected home directory.
set -euo pipefail
repopilot_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export IMPECCABLE_HOME="${IMPECCABLE_HOME:-${repopilot_root}/.local/impeccable}"
repopilot_skill_dir="${IMPECCABLE_SKILL_DIR:-${repopilot_root}/.local/design-skills/impeccable}"
repopilot_launcher="${repopilot_skill_dir}/scripts/impeccable"
if [[ ! -f "${repopilot_launcher}" ]]; then
  echo "Impeccable skill is not cached. Set IMPECCABLE_SKILL_DIR to the directory containing its SKILL.md, or cache plugin/skills/impeccable under .local/design-skills/impeccable." >&2
  exit 2
fi
cd "${repopilot_root}"
if [[ $# -eq 0 ]]; then set -- context; fi
exec bash "${repopilot_launcher}" "$@"
