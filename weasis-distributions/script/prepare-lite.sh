#!/bin/bash
# Written by Addis Ortho Trading PLC, 2026.
# Turns the weasis-native input directory and the jpackage resources of one platform into the
# Addis Ortho Viewer Lite edition: a 2D DICOM viewer for X-ray and CT, without 3D, MPR/MIP toolbar,
# RT, SR, ECG, audio, DICOM send, query/retrieve, CD burning, Dicomizer or translations.
# conf/lite.json switches off the matching bundles and toolbars; this script removes their files.
#
# Usage: prepare-lite.sh <weasis input dir> <resource dir of the platform> <output resource dir>
#   <weasis input dir>  the "weasis" folder of bin-dist (holds weasis-launcher.jar, bundle/, conf/)
#   <resource dir>      script/resources/<macosx|windows|linux> of the full edition
#   <output dir>        receives the Lite resources (icons renamed, no Dicomizer, no URL scheme)

set -euo pipefail

INPUT_DIR="$1"
RES_SRC="$2"
RES_OUT="$3"

FULL_NAME="Addis Ortho Viewer"
LITE_NAME="Addis Ortho Viewer Lite"

[[ -f "$INPUT_DIR/weasis-launcher.jar" ]] || { echo "ERROR: $INPUT_DIR is not a weasis input directory" >&2; exit 1; }
[[ -f "$INPUT_DIR/conf/lite.json" ]] || { echo "ERROR: $INPUT_DIR/conf/lite.json is missing" >&2; exit 1; }
[[ -d "$RES_SRC" ]] || { echo "ERROR: resource directory $RES_SRC does not exist" >&2; exit 1; }

# Bundles that the Lite profile does not start
removed=(
  weasis-dicom-viewer3d jogamp
  weasis-dicom-rt weasis-dicom-sr weasis-dicom-au weasis-dicom-wave
  weasis-dicom-send weasis-dicom-qr weasis-dicom-isowriter
  weasis-base-viewer2d weasis-base-explorer weasis-acquire-explorer weasis-acquire-editor
  org.apache.felix.gogo.shell
)
for prefix in "${removed[@]}"; do
  find "$INPUT_DIR/bundle" -maxdepth 1 -type f -name "${prefix}-*" -print -delete
done

# Translations (Lite is English only), Dicomizer and other profiles, CD burning resources
rm -rf "$INPUT_DIR/bundle-i18n" "$INPUT_DIR/resources/isowriter"
rm -f "$INPUT_DIR/conf/dicomizer.json" "$INPUT_DIR/conf/non-dicom-explorer.json" "$INPUT_DIR/conf/base-shell.json"

# jpackage resources: rename the launcher files to the Lite name, drop the Dicomizer launcher and
# the custom MSI template (it registers the weasis-addisortho:// scheme with a fixed component GUID
# that belongs to the full edition).
rm -rf "$RES_OUT"
mkdir -p "$RES_OUT"
for f in "$RES_SRC"/*; do
  [[ -f "$f" ]] || continue
  base=$(basename "$f")
  case "$base" in
    Dicomizer.* | dicomizer-launcher.properties) continue ;;
    "$FULL_NAME".*) cp "$f" "$RES_OUT/$LITE_NAME.${base#"$FULL_NAME".}" ;;
    *) cp "$f" "$RES_OUT/$base" ;;
  esac
done

# macOS: own bundle identifier, no URL scheme (the full edition owns weasis-addisortho://)
if [[ -f "$RES_OUT/Info.plist" ]]; then
  sed -i.bak \
    -e 's#<string>com.addisortho.viewer</string>#<string>com.addisortho.viewer.lite</string>#' \
    -e '/<key>CFBundleURLTypes<\/key>/,/^    <\/array>/d' \
    "$RES_OUT/Info.plist"
  rm -f "$RES_OUT/Info.plist.bak"
  if grep -q "weasis-addisortho" "$RES_OUT/Info.plist"; then
    echo "ERROR: the URL scheme is still in the Lite Info.plist" >&2
    exit 1
  fi
fi

echo "Lite bundles:"
ls "$INPUT_DIR/bundle"
echo "Lite resources:"
ls "$RES_OUT"
