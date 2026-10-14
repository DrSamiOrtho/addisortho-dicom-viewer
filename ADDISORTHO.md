# Addis Ortho DICOM Viewer

A rebranded build of [Weasis](https://github.com/nroduit/Weasis) for macOS (Apple Silicon and Intel)
and Windows x64, distributed by Addis Ortho Trading PLC under the Apache License 2.0. See `NOTICE`
for what changed. The upstream `README.md` is kept as is and describes the viewer itself.

It is not a certified medical device. It is for image review and surgical planning support only;
the first-launch disclaimer must be accepted before the viewer opens.

## Branches and upstream

- `main`: the Addis Ortho build line. A release is a tag `ao-<weasis version>-<n>`, for example
  `ao-4.7.3-1`, made only after both installers pass the smoke test below.
- `addisortho/*`: work branches.
- `upstream` remote: `https://github.com/nroduit/weasis`. Merge each upstream release tag
  (`git fetch upstream --tags && git merge v4.x.y`), never upstream `master`. Keep upstream's
  Java packages, bundle names and preference keys unchanged so merges stay small.

## Build

JDK 25 or later and Maven 3.8.1 or later. Dependencies come from Maven Central, the dcm4che
repository and `nroduit/mvn-repo` on GitHub.

    mvn -B -ntp install
    mvn -B -ntp -P compressXZ -f weasis-distributions clean package

Installers are built on GitHub Actions: run **Build installers (Addis Ortho DICOM Viewer)**
(`.github/workflows/build-installer.yml`) from the Actions tab. It produces:

- macOS: `Addis Ortho Viewer-<version>-<arch>.pkg`, signed and notarised when the Apple secrets
  below exist, otherwise `...-unsigned.pkg` for internal testing (macOS asks the user to allow it
  under System Settings, Privacy and Security).
- Windows: `Addis Ortho Viewer-<version>-x86-64.msi`, unsigned (Windows SmartScreen warns until a
  code signing step is added).

Apple signing secrets (repository secrets): `MACOS_CERTIFICATE_DEVELOPMENT` and
`MACOS_CERTIFICATE_INSTALLER` (base64 .p12 of the Developer ID Application and Developer ID
Installer certificates), `MACOS_CERTIFICATE_PWD`, `APPLE_DEVELOPER_AC_USERNAME`, `APPLE_TEAM_ID`,
and `WEASIS_PWD` (an app-specific password for notarisation).

## Identity

| | Value |
|---|---|
| Name in the interface | Addis Ortho DICOM Viewer |
| Installed app and installer | Addis Ortho Viewer |
| macOS bundle identifier | `com.addisortho.viewer` |
| URL scheme | `weasis-addisortho://` (upstream's launcher accepts any `weasis-*://` scheme) |
| Windows upgrade codes | x64 `4179fa98-ca01-4739-b89e-92aff0702b48`, arm64 `1d34dbc0-804b-491f-b9b7-cddec4b42771` |
| Preference profiles | `addisortho`, `addisortho-dicomizer`, `addisortho-explorer` |

The profiles separate preferences only (`~/.weasis/preferences/<user>/<profile>`). The plug-in cache,
data and logs stay under `~/.weasis`; upstream's per-source cache suffix keeps them from colliding
with an installed Weasis.

Never reuse Weasis's upgrade codes or identifiers: the installer would then upgrade or remove a
real Weasis on the same computer. Linux packaging is not maintained.

## Before the first release (from Fable's review, 2026-10-14)

1. Ship a complete third-party licence file inside the app and link it from the About box instead
   of upstream's GitHub page: DockingFrames and VectorGraphics2D (LGPL), dcm4che, javaisotools,
   logback, parsson and jakarta.json, JOGL, OpenCV, the Temurin runtime (GPLv2 with Classpath
   Exception), and the Independent JPEG Group credit. Generate it in CI with the Maven licence
   plugin, since every dependency must resolve.
2. Help menu: "Check for updates" still reads upstream's release feed, "Submit bug report" opens
   upstream's tracker, release notes and help links point to upstream; "Weasis Keyboard Shortcuts"
   and the "Flat Weasis" theme name are still visible. Repoint or hide them.
3. Vendor `weasis-i18n-dist` (4.0.0-SNAPSHOT from `nroduit/mvn-repo`) so a rebuild of the same tag
   gives the same translations.
4. Add a "Modified by Addis Ortho Trading PLC" line to each changed source file, below the licence
   header that Spotless enforces.
5. Smoke test both installers next to a real Weasis install: disclaimer, DICOMDIR from a CD, length
   and angle, About box, URL scheme, uninstall leaves Weasis untouched.
