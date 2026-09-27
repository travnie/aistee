#!/usr/bin/env bash
# SessionStart, cloud sessions only. Never fails the session.
#  - ANDROID_HOME fallback to the SDK from the environment setup script
#  - npm ci for every committed package-lock.json (skipped when node_modules
#    is already newer than the lockfile, e.g. on resume)
[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

if [ -z "${ANDROID_HOME:-}" ] && [ -d /opt/android-sdk ] && [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo 'export ANDROID_HOME=/opt/android-sdk' >> "$CLAUDE_ENV_FILE"
fi

# Android skills from the Android CLI, user-level so the repo stays clean.
# Installed in parallel (~11 s on a fresh VM, 0 s once present) and waited
# for, so the first turn already has them.
android_cli=$(command -v android || echo "${ANDROID_HOME:-/opt/android-sdk}/cmdline-tools/latest/bin/android")
if [ -x "$android_cli" ]; then
  skill_pids=()
  skill_names=()
  for skill in android-cli testing-setup edge-to-edge r8-analyzer android-intent-security android-permissions-security media3-cast-integration navigation-3; do
    [ -d "$HOME/.claude/skills/$skill" ] && continue
    "$android_cli" skills add --agent=claude-code "$skill" >/dev/null 2>&1 &
    skill_pids+=("$!")
    skill_names+=("$skill")
  done
  for i in "${!skill_pids[@]}"; do
    if ! wait "${skill_pids[$i]}"; then
      echo "android skill failed: ${skill_names[$i]}" >&2
    fi
  done
fi

# Gemini API skill from google-gemini/gemini-skills: current model names and
# API usage for the Gemini provider. Pinned to a reviewed commit (bump the
# SHA deliberately); staged in a temp dir and renamed into place, with the
# pinned SHA as the completion marker, so a partial install is retried.
gemini_ref=6fee1bec62d6a0ca92c1d0e34d62ff11f70c498a
gemini_skill="$HOME/.claude/skills/gemini-api-dev"
if [ "$(cat "$gemini_skill/.ref" 2>/dev/null)" != "$gemini_ref" ]; then
  tmp=$(mktemp -d)
  if git -C "$tmp" init -q \
      && git -C "$tmp" remote add origin https://github.com/google-gemini/gemini-skills \
      && git -C "$tmp" sparse-checkout set skills/gemini-api-dev \
      && git -C "$tmp" fetch -q --depth 1 --filter=blob:none origin "$gemini_ref" \
      && git -C "$tmp" checkout -q FETCH_HEAD \
      && [ -f "$tmp/skills/gemini-api-dev/SKILL.md" ]; then
    mkdir -p "$HOME/.claude/skills"
    rm -rf "$gemini_skill"
    # /tmp may be another filesystem, so mv can degrade to copy: the marker
    # is written last, only after the whole tree has landed.
    mv "$tmp/skills/gemini-api-dev" "$gemini_skill" \
      && echo "$gemini_ref" > "$gemini_skill/.ref"
  else
    echo "gemini-api-dev skill: install failed" >&2
  fi
  rm -rf "$tmp"
fi

git ls-files '*package-lock.json' | while read -r lock; do
  dir=$(dirname "$lock")
  [ "$dir/node_modules/.package-lock.json" -nt "$lock" ] && continue
  if (cd "$dir" && npm ci --no-audit --no-fund --no-update-notifier --loglevel=error >/dev/null); then
    echo "npm ci: $dir"
  else
    echo "npm ci failed: $dir" >&2
  fi
done

exit 0
