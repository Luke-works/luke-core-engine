#!/usr/bin/env bash
#
# build.sh — build, commit, and deploy the Luke Core Engine.
#
# Deploy model: Render watches the `main` branch (see render.yaml, branch: main)
# and rebuilds the Dockerfile + redeploys automatically on every push. So the
# "deploy" step here is the push to main. We run a local Maven build first as a
# pre-flight gate, so we never push a commit that Render would fail to build.
#
# Usage:
#   ./build.sh                      # build, commit all changes, push to main
#   ./build.sh "your commit message"
#   SKIP_BUILD=1 ./build.sh "msg"   # skip the local Maven build (push only)
#   SKIP_PUSH=1  ./build.sh "msg"   # build + commit locally, do not push/deploy
#
set -euo pipefail

# Always run from the repo root (this script's directory).
cd "$(dirname "$0")"

BRANCH="main"
COMMIT_MSG="${1:-chore: build and deploy $(date -u +%Y-%m-%dT%H:%M:%SZ)}"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 0. Sanity checks
# ---------------------------------------------------------------------------
command -v git >/dev/null 2>&1 || fail "git is not installed."
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "Not inside a git repository."

# ---------------------------------------------------------------------------
# 1. Build & verify (pre-flight gate — Render will build the Dockerfile itself)
# ---------------------------------------------------------------------------
if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
  log "SKIP_BUILD=1 — skipping local Maven build."
else
  log "Building with Maven wrapper (clean package)..."
  ./mvnw -B clean package
  log "Build succeeded: $(ls -1 target/*.jar | head -n1)"
fi

# ---------------------------------------------------------------------------
# 2. Commit
# ---------------------------------------------------------------------------
# Make sure we land on the deploy branch.
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$CURRENT_BRANCH" != "$BRANCH" ]]; then
  log "Switching from '$CURRENT_BRANCH' to '$BRANCH'..."
  git checkout "$BRANCH"
fi

if git diff --quiet && git diff --cached --quiet; then
  log "No changes to commit — working tree is clean."
else
  log "Staging and committing changes..."
  git add -A
  git commit -m "$COMMIT_MSG"
  log "Committed: $(git rev-parse --short HEAD)  \"$COMMIT_MSG\""
fi

# ---------------------------------------------------------------------------
# 3. Push to main → triggers Render auto-deploy
# ---------------------------------------------------------------------------
if [[ "${SKIP_PUSH:-0}" == "1" ]]; then
  log "SKIP_PUSH=1 — not pushing. Nothing deployed."
  exit 0
fi

log "Pushing to origin/$BRANCH (this triggers the Render deploy)..."
git push origin "$BRANCH"

log "Done. Render is now building & deploying from branch '$BRANCH'."
cat <<'EOF'

Next steps:
  - Watch the build:   Render dashboard -> luke-core-engine -> Logs
  - Health check:      curl https://<your-service>.onrender.com/actuator/health
EOF
