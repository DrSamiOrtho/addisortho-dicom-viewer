# Addis Ortho DICOM Viewer

A rebranded build of [Weasis](https://github.com/nroduit/Weasis) for macOS (Apple Silicon and Intel)
and Windows x64, distributed by Addis Ortho Trading PLC. See `NOTICE` for what changed. The upstream
`README.md` is kept as is and describes the viewer itself.

## Licence and source

This repository is public. Each release tag `ao-<weasis version>-<n>` is the complete corresponding
source code of that release. Addis Ortho Trading PLC uses Weasis under the Apache License 2.0 and
distributes the program as a whole under the GNU General Public License version 3, because it
includes CheckboxTree (GPL version 2 or later). The Addis Ortho name and logo are trademarks that no
licence grants and modified versions must not use (GPLv3 section 7(e)). The same text is in `NOTICE`,
in the installer licence page (`weasis-distributions/src/main/resources-native/Licence.txt`) and in
the third-party notice template; the About box states "GNU GPL version 3" (`weasis.copyrights` in
`weasis-distributions/etc/config/base.json`). Keep all of them in step.

It is not a certified medical device. It is for image review and surgical planning support only;
the first-launch disclaimer must be accepted before the viewer opens.

## Branches and upstream

- `main`: the Addis Ortho build line. A release is a tag `ao-<weasis version>-<n>`, for example
  `ao-4.7.3-1` (see "Releasing" below).
- `addisortho/*`: work branches.
- `upstream` remote: `https://github.com/nroduit/weasis`. Merge each upstream release tag
  (`git fetch upstream --tags && git merge v4.x.y`), never upstream `master`. Keep upstream's
  Java packages, bundle names and preference keys unchanged so merges stay small.

## Build

JDK 25 or later and Maven 3.8.1 or later. Dependencies come from Maven Central, the dcm4che
repository and `nroduit/mvn-repo` on GitHub.

    mvn -B -ntp install
    mvn -B -ntp -P compressXZ -f weasis-distributions clean package

The first command also writes `target/generated-resources/legal/THIRD-PARTY-NOTICES.txt` (see
"Third-party notice" below); the second fails if that file is missing, so run them in this order.

Installers are built on GitHub Actions: run **Build installers (Addis Ortho DICOM Viewer)**
(`.github/workflows/build-installer.yml`) from the Actions tab, or push a tag `ao-*`. It produces:

- macOS: `Addis Ortho Viewer-<version>-<arch>.pkg`, signed and notarised when the Apple secrets
  below exist, otherwise `...-unsigned.pkg` for internal testing (macOS asks the user to allow it
  under System Settings, Privacy and Security).
- Windows: `Addis Ortho Viewer-<version>-x86-64.msi`, unsigned (Windows SmartScreen warns until a
  code signing step is added).

Apple signing secrets (repository secrets): `MACOS_CERTIFICATE_DEVELOPMENT` and
`MACOS_CERTIFICATE_INSTALLER` (base64 .p12 of the Developer ID Application and Developer ID
Installer certificates), `MACOS_CERTIFICATE_PWD`, `APPLE_DEVELOPER_AC_USERNAME`, `APPLE_TEAM_ID`,
and `WEASIS_PWD` (an app-specific password for notarisation).

## Releasing

1. On the work branch, run **Build installers** (platforms `all`), then **Smoke test installers**
   with that run's id (`.github/workflows/smoke-test.yml`). It installs both installers on real
   macOS and Windows runners and prints every check as `CHECK PASS|FAIL`; read the screenshots too.
2. Merge to `main` and push a tag `ao-<weasis version>-<n>` (the version must be `revision` of
   `weasis-parent/pom.xml`; the workflow refuses a tag that does not match). The tag builds all
   three installers and the `release` job creates a GitHub **pre-release** with the two `.pkg`, the
   `.msi` and their `.sha256` files (unsigned installers: the notes say how to open them). The
   smoke test then runs by itself on that build, once `smoke-test.yml` is on `main`
   (`workflow_run`). If it fails, delete the release and the tag, fix and tag again.
   Where tags cannot be pushed (some automated sessions), run **Build installers** on `main` with
   `release_tag` set to the tag name instead: the `release` job creates the tag on the built commit.
3. Remove `--prerelease` from the `release` job when both platforms are signed.

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

1. Done. Third-party notice shipped in the app (see below).
2. Done. Help menu and leftover Weasis wording (see below).
3. Done. `weasis-i18n-dist` is vendored (see below).
4. Done. "Modified by Addis Ortho Trading PLC" lines (see below).
5. Open. Smoke test both installers next to a real Weasis install: disclaimer, DICOMDIR from a CD,
   length and angle, About box (third-party link opens `THIRD-PARTY-NOTICES.txt`), Help menu (no
   update check, no bug report, no release notes), URL scheme, uninstall leaves Weasis untouched.
   `smoke-test.yml` automates most of it (not length and angle, nor a real Weasis next to it).
6. Done. Decision by the owner, 2026-10-14: CheckboxTree (GPL-2.0-or-later) stays, the complete
   source is public and the program as a whole is distributed under GPLv3 (see "Licence and source").

### Third-party notice

- `resources/legal/THIRD-PARTY-NOTICES.txt` is built by `license-maven-plugin` 2.7.1
  (`aggregate-add-third-party`, root `pom.xml`) from the dependencies of every module except tests,
  so it follows upstream merges. The root project is built after the modules, which is why the
  goal sees the whole reactor.
- The text is `weasis-distributions/src/license/third-party-notices.ftl`: a hand-written part for
  what Maven cannot see (Eclipse Temurin runtime, OpenCV and its codecs, the Independent JPEG Group
  statement, JOGL natives, JetBrains and Icons8 icons) followed by the generated list grouped by
  licence. Edit that file for the hand-written part.
- A dependency whose POM declares no licence fails the build (`failOnMissing`). Add its licence to
  `weasis-distributions/src/license/THIRD-PARTY.properties` (`groupId--artifactId--version=name`).
  New spellings of an existing licence go into `licenseMerges` in the root `pom.xml`.
- Both assemblies (`resources.zip` and the native package) copy the file to `resources/legal/`
  with the licence texts in `resources/legal/licenses/` (Apache-2.0 and EPL-2.0 from the repository
  root; LGPL-2.1, LGPL-3.0, GPL-3.0 and GPL-2.0 with the Classpath Exception from
  `weasis-distributions/resources/legal/licenses`).
  `weasis-distributions` fails early (`require-third-party-notice`) if the file was not generated.
- The About box (`WeasisAboutBox`) opens `legal/THIRD-PARTY-NOTICES.txt` through `ResourceUtil`
  (the `weasis.resources.path` folder) and falls back to upstream's page when it is absent. The
  installer licence text (`Licence.txt`) points to the same file.

### Help menu

`weasis-distributions/etc/config/base.json` now controls the Help menu. A link is hidden when its
value is empty:

| Property | Controls | Value here |
|---|---|---|
| `weasis.update.url` | startup update check and Help, Check for updates (release feed) | empty |
| `weasis.help.report` | Help, Submit bug report | empty |
| `weasis.releasenotes` | About box link and the message shown after an upgrade | empty |
| `weasis.help.url` | Help, User's Manual | not set |
| `weasis.help.online` | Help, Online Help (and the help buttons) | upstream tutorials, kept: accurate and they credit Weasis |

`weasis-launcher/conf/base.json` still has upstream's values because its tests expect them. User
visible strings now use the configured name (`weasis.name`): the shortcuts page title, the OAuth
callback page and the Resource Monitor report. The dark theme is listed as "Flat Addis Ortho".
Non-English translations come from `weasis-i18n-dist` and still say Weasis.

### Translations

Upstream only publishes `org.weasis:weasis-i18n-dist` as `4.0.0-SNAPSHOT` (no release, and the
repository keeps one overwritten file instead of timestamped builds, so a snapshot cannot be
pinned). A copy fetched on 2026-10-14 from
`https://raw.githubusercontent.com/nroduit/mvn-repo/master/org/weasis/weasis-i18n-dist/4.0.0-SNAPSHOT/`
(zip sha1 `059760f40c56e6f7c668eabae15ec23c50e1926b`) is kept as version `4.0.0-ao.20261014` in
`weasis-distributions/vendor/maven-repo`, declared as the `ao-vendored` repository of
`weasis-distributions`; `i18n.version` in `weasis-parent/pom.xml` points to it. To take newer
translations, copy the new zip and pom under a new version directory (with `.sha1` files) and change
`i18n.version`.

### Modified-file notices

Apache-2.0 section 4(b): every text file changed relative to `v4.7.3` carries the line
`Modified by Addis Ortho Trading PLC, 2026.` in its own comment syntax. In Java files it sits directly
below the licence header; `weasis-parent/pom.xml` tells Spotless that the header ends at that line
(`<delimiter>` of `licenseHeader`), so `mvn spotless:check` still enforces the exact header. Files
that cannot carry a comment are listed in `NOTICE`. After a change or an upstream merge, list files
that lack the line:

    git diff --name-only v4.7.3 --diff-filter=AMR | while IFS= read -r f; do grep -qI "Modified by Addis Ortho" "$f" || echo "$f"; done

The output should only hold the files `NOTICE` lists (JSON, images, `Licence.txt`) and files that Addis
Ortho wrote entirely. Use the tag you merged last instead of `v4.7.3`.

## Templating and 3D bones (14 Oct 2026)

Design: `DESIGN-2026-10-14-templating-3d.md`. All outputs say draft, semi-automatic or automatic, not reviewed.

- `weasis-dicom/weasis-dicom-templating`: the **Template** button (2D viewer toolbar) opens the selected CR/DX/DR/RG
  X-ray in the X-ray templating page, offline: a local server on 127.0.0.1 (port 47210 when free) with a one-time
  token per X-ray serves the page's viewer build (Addis Ortho BE2, BE, cementless cup and total knee only) and takes
  back the plan picture, which loads into the X-ray's study. The page lives in the templating project
  (CENTER/EMR-POC/pacs-trial/templating, `build_viewer.sh`); copy it in with `sync-templating-web.sh`, which checks every
  file against `BUILD.txt`. The protocol and its browser test: the templating project's `workflow/28o_templating_viewer.py`.
  Needs `jdk.httpserver` in the installer runtime (`launch-options.sh`) and `com.sun.net.httpserver` in both `base.json`.
- `weasis-dicom/weasis-dicom-bones` (3D view tool panels):
  - **Bone STL**: one segment of a shown SEG as a binary STL, mm, DICOM LPS.
  - **Bone select (region grow)**: seeded HU region grow (default 200-3000 HU) with closing, hole filling and optional
    separation, written as a SEG (series 902) in the study; for knee bones and fragments without a licence.
  - **Make 3D bones**: runs the owner's TotalSegmentator pipeline (`planning/make_3d.sh`, free model: hip, shoulder,
    spine) where it is installed, and opens its results in the study.
- `viewer3d`: Smooth surfaces for segments, a crop box, and "Save 3D picture to the study" / "Copy 3D picture".
