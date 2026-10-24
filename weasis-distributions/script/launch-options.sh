#!/bin/bash
# Modified by Addis Ortho Trading PLC, 2026.
# Single source of truth for the jpackage JDK modules and launch options. It is shared by
# package-weasis.sh (local builds and the Linux deb/rpm built in Docker) and by
# .github/workflows/build-installer.yml (macOS and Windows builds), so that every installer
# ships launchers with the same configuration.
#
# Usage: . launch-options.sh <macosx|windows|linux> [lite]
# Defines: JDK_MODULES, customOptions[], commonOptions[]
#
# "lite" gives the options of the Lite edition, for PCs with 4 GB of RAM: a fixed 1 GB heap
# instead of a share of the RAM (the OS, the graphics driver and the image cache need the rest),
# a 256 MB image cache, a small 3D upload buffer, the serial collector (least memory of its own,
# gives memory back), smaller 3D textures and no smooth segment surfaces (weasis.lite), and its
# own shell port so that it can run beside the full edition.

# jdk.localedata => other locale (en_us) data are included in the jdk.localedata
# jdk.jdwp.agent => package for debugging agent
# jdk.httpserver => the local server of the templating page (weasis-dicom-templating, 127.0.0.1 only)
# Base modules for all platforms
JDK_MODULES_BASE="java.base,java.compiler,java.datatransfer,java.net.http,java.desktop,java.logging,java.management,jdk.management,java.prefs,java.xml,jdk.localedata,jdk.charsets,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jdwp.agent,java.sql,jdk.httpserver"

case "$1" in
  macosx)
    JDK_MODULES="$JDK_MODULES_BASE"
    # apple.awt.application.name is the name in the application menu ("About ...", "Hide ...",
    # "Quit ..."); without it macOS shows the main class, "AppLauncher". jpackage splits a
    # --java-options value at spaces, so the embedded double quotes keep the name in one option.
    customOptions=("--java-options" "-splash:\$APPDIR/resources/images/about-round.png" \
    "--java-options" "-Dapple.laf.useScreenMenuBar=true" \
    "--java-options" "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua" \
    "--java-options" "-Dapple.awt.application.name=\"Addis Ortho DICOM Viewer\"" \
    "--java-options" "-XX:MaxRAMPercentage=25")
    ;;
  windows)
    JDK_MODULES="$JDK_MODULES_BASE,jdk.crypto.mscapi"
    # Hospital Windows PCs often have 8 GB of RAM: a quarter of it (2 GB) is too little for a CT
    # series with 3D and segmentation open, so the viewer may use half of the memory there.
    customOptions=("--java-options" "-splash:\$APPDIR\resources\images\about-round.png" \
    "--java-options" "-XX:MaxRAMPercentage=50")
    ;;
  linux)
    JDK_MODULES="$JDK_MODULES_BASE"
    # sun.awt.disablegrab works around unreliable X11 pointer grabs on XWayland (GNOME/mutter),
    # which can leave Swing popup menus invisible until the window regains focus (see issue #819).
    customOptions=("--java-options" "-splash:\$APPDIR/resources/images/about-round.png" \
    "--java-options" "-Dsun.awt.disablegrab=true" \
    "--java-options" "-XX:MaxRAMPercentage=25")
    ;;
  *)
    echo -e "ERROR: launch-options.sh expects a platform (macosx, windows or linux), got '$1'" >&2
    exit 1
    ;;
esac

LITE=no
[ "$2" = "lite" ] && LITE=yes
if [ "$LITE" = "yes" ] ; then
  # Replaces the share of the RAM set above by a fixed heap.
  liteOptions=()
  for ((i = 0; i < ${#customOptions[@]}; i++)); do
    if [[ "${customOptions[$i]}" == -XX:MaxRAMPercentage=* ]] ; then
      unset 'liteOptions[${#liteOptions[@]}-1]' # drops the "--java-options" before it
      liteOptions=("${liteOptions[@]}")
      continue
    fi
    liteOptions+=("${customOptions[$i]}")
  done
  customOptions=("${liteOptions[@]}" \
  "--java-options" "-Xmx1g" \
  "--java-options" "-XX:+UseSerialGC" \
  "--java-options" "-Dweasis.lite=true" \
  "--java-options" "-Dweasis.native.memory=268435456" \
  "--java-options" "-Dweasis.volume.staging.memory=67108864")
fi

# Options applied to the main Weasis launcher on every platform (the heap limit, MaxRAMPercentage,
# is set per platform above). The Dicomizer launcher does not
# inherit them: jpackage drops the command line --java-options as soon as an --add-launcher
# properties file defines java-options, so they are repeated in
# resources/<platform>/dicomizer-launcher.properties.
GOSH_PORT=17179
[ "$LITE" = "yes" ] && GOSH_PORT=17189
commonOptions=("--java-options" "-Dgosh.port=${GOSH_PORT}" \
"--java-options" "--enable-native-access=ALL-UNNAMED" \
"--java-options" "-XX:+UseStringDeduplication" \
"--java-options" "-Djavax.accessibility.assistive_technologies=org.weasis.launcher.EmptyAccessibilityProvider" \
"--java-options" "-Djavax.accessibility.screen_magnifier_present=false")

# Optional deployment options (configuration service URL, institutional defaults). The file is not
# part of the public distribution: when it is absent, only the options above are applied.
launchOptionsDir=$(dirname "${BASH_SOURCE[0]}")
if [[ -f "${launchOptionsDir}/launch-options-site.sh" ]] ; then
  source "${launchOptionsDir}/launch-options-site.sh"
fi