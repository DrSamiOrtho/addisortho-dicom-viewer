#!/bin/sh
# Addis Ortho Trading PLC, 2026. Copies the templating page's viewer build into this bundle (14 Oct 2026).
# The page's source is the templating project (CENTER/EMR-POC/pacs-trial/templating). Its build_viewer.sh builds
# the viewer copy (the Addis Ortho ranges only since 23 Oct 2026, data-mode="viewer", the release in RELEASE.txt) into a
# new folder; this script then makes src/main/resources/templating-web/ exactly that build (the folder is tracked by git,
# so a change shows in git diff). The viewer checks every file against BUILD.txt, and library.json and index.html against
# RELEASE.txt, when it starts the page.
# Build-version check (23 Oct 2026): with --check nothing is copied; it builds the page afresh and fails unless the bundled
# copy is byte for byte that build, so a stale page is caught before an installer is built.
# Run: sh sync-templating-web.sh [--check] <release, e.g. r28> [templating project folder]
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK=
if [ "$1" = "--check" ]; then CHECK=1; shift; fi
REL="$1"
[ -n "$REL" ] || { echo "usage: sh sync-templating-web.sh [--check] <release, e.g. r28> [templating project folder]" >&2; exit 2; }
SRC="${2:-$HOME/Library/CloudStorage/GoogleDrive-samuel.hailu@aau.edu.et/My Drive/Documents/Addis Ortho Trading/CENTER/EMR-POC/pacs-trial/templating}"
[ -f "$SRC/build_viewer.sh" ] || { echo "FAIL: no build_viewer.sh in $SRC" >&2; exit 1; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
sh "$SRC/build_viewer.sh" "$TMP/site" "$REL"
DEST="$HERE/src/main/resources/templating-web"
if [ -n "$CHECK" ]; then
  if diff -r "$TMP/site" "$DEST" > "$TMP/diff.txt" 2>&1; then
    echo "templating-web: up to date, release $REL, build $(shasum -a 256 "$DEST/BUILD.txt" | cut -c1-12)"
    exit 0
  fi
  echo "FAIL: the bundled templating page is not release $REL as the templating project builds it now:" >&2
  head -20 "$TMP/diff.txt" >&2
  echo "run: sh sync-templating-web.sh $REL" >&2
  exit 1
fi
mkdir -p "$DEST"
rsync -a --delete --checksum "$TMP/site/" "$DEST/"
( cd "$DEST" && shasum -a 256 -c BUILD.txt --quiet ) || { echo "FAIL: the copy does not match BUILD.txt" >&2; exit 1; }
echo "templating-web: release $REL, build $(shasum -a 256 "$DEST/BUILD.txt" | cut -c1-12), $(wc -l < "$DEST/BUILD.txt" | tr -d ' ') files"
