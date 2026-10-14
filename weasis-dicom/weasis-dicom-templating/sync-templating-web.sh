#!/bin/sh
# Addis Ortho Trading PLC, 2026. Copies the templating page's viewer build into this bundle (14 Oct 2026).
# The page's source is the templating project (CENTER/EMR-POC/pacs-trial/templating). Its build_viewer.sh builds
# the viewer copy (Addis Ortho primary hip and knee ranges, data-mode="viewer") into a new folder; this script then
# makes src/main/resources/templating-web/ exactly that build (the folder is tracked by git, so a change shows in
# git diff). The viewer checks every file against BUILD.txt when it starts the page.
# Run: sh sync-templating-web.sh [templating project folder]
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="${1:-$HOME/Library/CloudStorage/GoogleDrive-samuel.hailu@aau.edu.et/My Drive/Documents/Addis Ortho Trading/CENTER/EMR-POC/pacs-trial/templating}"
[ -f "$SRC/build_viewer.sh" ] || { echo "FAIL: no build_viewer.sh in $SRC" >&2; exit 1; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
sh "$SRC/build_viewer.sh" "$TMP/site"
DEST="$HERE/src/main/resources/templating-web"
mkdir -p "$DEST"
rsync -a --delete --checksum "$TMP/site/" "$DEST/"
( cd "$DEST" && shasum -a 256 -c BUILD.txt --quiet ) || { echo "FAIL: the copy does not match BUILD.txt" >&2; exit 1; }
echo "templating-web: build $(shasum -a 256 "$DEST/BUILD.txt" | cut -c1-12), $(wc -l < "$DEST/BUILD.txt" | tr -d ' ') files"
