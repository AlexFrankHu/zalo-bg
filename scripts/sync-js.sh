#!/bin/bash
# Sync chunk-f6588e50.efaa59d3.js from the private zalo-website repo to
# /opt/zalo-bg/files/ so nginx can serve it via
# https://zalo.qxh77.com/files/chunk-f6588e50.efaa59d3.js
#
# Installed at /opt/zalo-bg/bin/sync-js.sh, runs every 2 minutes via cron.
# Only writes the destination file when the upstream MD5 actually changed,
# so the mtime / Last-Modified header is a useful "last update" signal.
#
# Required:
#   /opt/zalo-bg/bin/.pat   mode 0600, single line containing a fine-grained
#                            PAT with contents:read on AlexFrankHu/zalo-website
#
# Env overrides:
#   BRANCH     upstream branch to track (default: devin/1777044500-collect-upload)
#
# Cron entry (ubuntu user):
#   */2 * * * * /opt/zalo-bg/bin/sync-js.sh >> /var/log/zalo-bg-sync.log 2>&1

set -euo pipefail

PAT_FILE=/opt/zalo-bg/bin/.pat
REPO_URL=https://github.com/AlexFrankHu/zalo-website.git
REPO_DIR=/opt/zalo-bg/src/zalo-website
BRANCH="${BRANCH:-devin/1777044500-collect-upload}"
SRC_FILE=zalo.xone.cc/static/js/chunk-f6588e50.efaa59d3.js
DST_DIR=/opt/zalo-bg/files
DST_FILE="$DST_DIR/$(basename "$SRC_FILE")"
LOCK=/tmp/zalo-bg-sync.lock

exec 9> "$LOCK"
if ! flock -n 9; then
    exit 0
fi

if [ ! -r "$PAT_FILE" ]; then
    echo "$(date -Iseconds) FATAL: PAT file unreadable: $PAT_FILE" >&2
    exit 1
fi
GH_PAT=$(< "$PAT_FILE")

AUTH_B64=$(printf 'x-access-token:%s' "$GH_PAT" | base64 -w0)
GIT_HDR="http.https://github.com/.extraheader=Authorization: Basic $AUTH_B64"

mkdir -p "$(dirname "$REPO_DIR")" "$DST_DIR"

if [ ! -d "$REPO_DIR/.git" ]; then
    git -c "$GIT_HDR" clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$REPO_DIR"
else
    git -C "$REPO_DIR" -c "$GIT_HDR" fetch --depth 1 --prune origin "$BRANCH" || {
        echo "$(date -Iseconds) ERROR: git fetch failed" >&2
        exit 2
    }
    git -C "$REPO_DIR" -c advice.detachedHead=false checkout -B "$BRANCH" "origin/$BRANCH" >/dev/null
    git -C "$REPO_DIR" reset --hard "origin/$BRANCH" >/dev/null
fi

SRC_PATH="$REPO_DIR/$SRC_FILE"
if [ ! -f "$SRC_PATH" ]; then
    echo "$(date -Iseconds) ERROR: upstream file missing: $SRC_PATH" >&2
    exit 3
fi

NEW_MD5=$(md5sum "$SRC_PATH" | awk '{print $1}')
OLD_MD5=$(md5sum "$DST_FILE" 2>/dev/null | awk '{print $1}' || true)

if [ "$NEW_MD5" != "$OLD_MD5" ]; then
    install -m 0644 "$SRC_PATH" "$DST_FILE"
    echo "$(date -Iseconds) UPDATED $(basename "$SRC_FILE") $OLD_MD5 -> $NEW_MD5 (branch=$BRANCH)"
fi
