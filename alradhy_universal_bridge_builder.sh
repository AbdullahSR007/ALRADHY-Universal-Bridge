#!/data/data/com.termux/files/usr/bin/bash
# ALRADHY Universal Bridge v0.1.0 — one-command Termux CI/build/download
# This script NEVER overwrites main; it builds the committed project on an isolated feature branch.
set -Eeuo pipefail
IFS=$'\n\t'
REPO="AbdullahSR007/ALRADHY-Universal-Bridge"
BRANCH="feature/v0.1-gpt6-bridge"
WORKFLOW="android-ci.yml"
ARTIFACT="ALRADHY-Universal-Bridge-v0.1.0"
WORKDIR="$HOME/ALRADHY-Universal-Bridge"
REPORT_NAME="ALRADHY-Universal-Bridge-build-report.json"
LOGFILE="$HOME/alradhy-universal-bridge-builder.log"
STATEFILE="$HOME/.alradhy-universal-bridge-last-run"
trap 'rc=$?; echo "[$(date)] Error at line $LINENO; exit=$rc" | tee -a "$LOGFILE"; exit "$rc"' ERR
say(){ printf '\n\033[1;36m[ALRADHY]\033[0m %s\n' "$*" | tee -a "$LOGFILE"; }
die(){ printf '\nERROR: %s\n' "$*" >&2; exit 1; }
command -v pkg >/dev/null 2>&1 || die "Run this in Termux, not in a standard Linux shell."
say "Install/verify dependencies: git, gh, jq, unzip, coreutils, curl"
pkg install -y git gh jq unzip coreutils curl ca-certificates >/dev/null
for x in git gh jq sha256sum unzip; do command -v "$x" >/dev/null || die "Missing tool: $x"; done
say "Check network and GitHub authentication"
curl -fsSIL --connect-timeout 15 https://github.com >/dev/null || die "Cannot reach GitHub."
if ! gh auth status >/dev/null 2>&1; then
  echo "GitHub CLI is not authenticated. Run: gh auth login"
  die "Authenticate GitHub, then re-run the same script."
fi
gh auth setup-git >/dev/null
gh repo view "$REPO" --json name >/dev/null || die "Repository inaccessible. Check gh auth scope."
[[ "$BRANCH" == feature/* ]] || die "Refusing to write to main/stable."

if [[ -d "$WORKDIR/.git" ]]; then
  say "Existing checkout found; preserve local files"
  cd "$WORKDIR"
  git fetch --prune origin "$BRANCH"
  CURRENT="$(git branch --show-current)"
  if [[ -n "$(git status --porcelain)" && "$CURRENT" != "$BRANCH" ]]; then
    die "You have local edits on branch $CURRENT. Move them safely before switching."
  fi
  git checkout "$BRANCH"
  if [[ -z "$(git status --porcelain)" ]]; then
    git pull --ff-only origin "$BRANCH"
  else
    say "Local changes detected; keeping them. Will attempt fast-forward later only if safe."
  fi
else
  say "Clone the actual source from GitHub"
  git clone --branch "$BRANCH" --single-branch "https://github.com/$REPO.git" "$WORKDIR"
  cd "$WORKDIR"
fi
[[ -f app/build.gradle && -f app/src/main/AndroidManifest.xml ]] || die "Android source missing"
[[ -f .github/workflows/android-ci.yml ]] || die "Workflow missing"
[[ -f app/src/main/java/com/alradhy/universalbridge/BridgeCore.kt ]] || die "Bridge backend missing"
[[ -f app/src/main/assets/web/index.html ]] || die "Web dashboard missing"
git diff --check
say "Source validation complete"
git status --short
if [[ -n "$(git status --porcelain)" ]]; then
  say "Stage project changes (never commit known local secrets)"
  git add -A
  if git diff --cached --name-only | grep -Ei '(^|/)(\.env($|\.)|.*\.jks$|.*\.keystore$|id_rsa|.*\.pem$|.*secret.*)' >/dev/null; then
    die "Refusing to commit potentially sensitive file. Unstage/remove it, then re-run."
  fi
  git diff --cached --check
  if ! git diff --cached --quiet; then
    git -c user.name="ALRADHY Termux Builder" -c user.email="alradhy-builder@users.noreply.github.com" \
      commit -m "build(v0.1): validate and sync Universal Bridge changes"
  fi
fi
say "Push only feature branch"
git push -u origin "$BRANCH"
SHA="$(git rev-parse HEAD)"
REMOTE_SHA="$(git ls-remote origin "refs/heads/$BRANCH" | cut -f1)"
[[ "$REMOTE_SHA" == "$SHA" ]] || die "Remote commit differs; refusing to associate another build."
say "Branch: $BRANCH"
say "Commit SHA: $SHA"
say "Find an Actions run for this exact SHA"
RUN_ID="$(gh run list -R "$REPO" --workflow "$WORKFLOW" --branch "$BRANCH" --limit 40 \
  --json databaseId,headSha,status,conclusion --jq '.[] | select(.headSha == "'"$SHA"'") | .databaseId' | head -n 1 || true)"
if [[ -z "$RUN_ID" ]]; then
  say "No run yet. Trigger workflow_dispatch explicitly."
  gh workflow run "$WORKFLOW" -R "$REPO" --ref "$BRANCH" || die "Could not dispatch workflow."
  for attempt in $(seq 1 24); do
    sleep 5
    RUN_ID="$(gh run list -R "$REPO" --workflow "$WORKFLOW" --branch "$BRANCH" --limit 40 \
      --json databaseId,headSha,status,conclusion --jq '.[] | select(.headSha == "'"$SHA"'") | .databaseId' | head -n 1 || true)"
    [[ -n "$RUN_ID" ]] && break
  done
fi
[[ "$RUN_ID" =~ ^[0-9]+$ ]] || die "Run ID could not be confirmed. Open GitHub Actions."
RUN_URL="https://github.com/$REPO/actions/runs/$RUN_ID"
printf 'branch=%s\nsha=%s\nrun_id=%s\nrun_url=%s\nartifact=%s\n' \
  "$BRANCH" "$SHA" "$RUN_ID" "$RUN_URL" "$ARTIFACT" > "$STATEFILE"
say "Run ID: $RUN_ID"
say "Workflow: $RUN_URL"
say "Watch actual CI result. This may download Android SDK and Gradle."
gh run watch "$RUN_ID" -R "$REPO" --exit-status --interval 5 || {
  say "Build failed. See detailed logs at: $RUN_URL"
  exit 1
}
RUN_SHA="$(gh run view "$RUN_ID" -R "$REPO" --json headSha --jq .headSha)"
RESULT="$(gh run view "$RUN_ID" -R "$REPO" --json conclusion --jq .conclusion)"
[[ "$RUN_SHA" == "$SHA" && "$RESULT" == "success" ]] || die "CI run SHA/result mismatch; no APK delivered."
say "CI succeeded for exact commit"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
gh run download "$RUN_ID" -R "$REPO" -n "$ARTIFACT" -D "$STAGE"
for f in "ALRADHY-Universal-Bridge-Mobile-v0.1.0.apk" \
         "ALRADHY-Universal-Bridge-TV-v0.1.0.apk" "SHA256SUMS.txt" "build-info.json"; do
  [[ -s "$STAGE/$f" ]] || die "Missing/empty output: $f"
done
say "Verify artifacts against original CI hashes"
(cd "$STAGE" && sha256sum -c SHA256SUMS.txt) || die "Artifact SHA256 mismatch."
jq -e --arg sha "$SHA" --arg run "$RUN_ID" \
  '.commitSha==$sha and .runId==$run' "$STAGE/build-info.json" >/dev/null ||
  die "Embedded build metadata does not match exact CI run."

DOWNLOAD_DIR="$HOME/storage/downloads"
if [[ ! -d "$DOWNLOAD_DIR" || ! -w "$DOWNLOAD_DIR" ]]; then
  DOWNLOAD_DIR="/storage/emulated/0/Download"
fi
if [[ ! -d "$DOWNLOAD_DIR" || ! -w "$DOWNLOAD_DIR" ]]; then
  echo "Termux storage permission is required. Run: termux-setup-storage"
  die "Grant file access in the Android permission dialog, then rerun."
fi
say "Copy verified files into $DOWNLOAD_DIR"
cp -f "$STAGE/"*.apk "$STAGE/SHA256SUMS.txt" "$STAGE/build-info.json" "$DOWNLOAD_DIR/"
(cd "$DOWNLOAD_DIR" && sha256sum -c SHA256SUMS.txt) || die "Download copy checksum mismatch."
jq -n --arg repository "$REPO" --arg branch "$BRANCH" --arg sha "$SHA" \
  --arg run "$RUN_ID" --arg runUrl "$RUN_URL" --arg artifact "$ARTIFACT" \
  --arg downloadedTo "$DOWNLOAD_DIR" --arg at "$(date -Iseconds)" \
  '{repository:$repository,branch:$branch,commitSha:$sha,runId:$run,workflowUrl:$runUrl,
    artifact:$artifact,downloadedTo:$downloadedTo,verified:true,time:$at,
    note:"Android debug-signed. Updating later releases may require stable signing."}' \
  > "$DOWNLOAD_DIR/$REPORT_NAME"
say "==========================================="
say "SUCCESS — actual APKs built and SHA-256 checked"
say "Branch : $BRANCH"
say "SHA    : $SHA"
say "Run ID : $RUN_ID"
say "Artifact: $ARTIFACT"
say "Files  : $DOWNLOAD_DIR"
(cd "$DOWNLOAD_DIR" && sha256sum "ALRADHY-Universal-Bridge-Mobile-v0.1.0.apk" "ALRADHY-Universal-Bridge-TV-v0.1.0.apk")
say "Report : $DOWNLOAD_DIR/$REPORT_NAME"
say "NOTE: CI debug signing is NOT stable for future updates. Set up a permanent release keystore before adopting a production baseline."
