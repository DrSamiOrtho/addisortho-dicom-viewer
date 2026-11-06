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

## Lite edition

Addis Ortho Viewer Lite is a second, smaller installer built from the same source for viewing X-ray
and CT images in 2D: thumbnails, stack scrolling, window/level, zoom, rotation, measurements and
annotations, DICOM CD (DICOMDIR) loading and image export. It leaves out the 3D viewer and JOGL, the
MPR/MIP toolbar, RT, SR, ECG and audio, DICOM send, query/retrieve and receive, CD burning, the
non-DICOM image viewer, the Dicomizer and the translations (English only).

- `weasis-distributions/etc/config/lite.json` is a profile, like `dicomizer.json`, read through
  `-Dfelix.extended.config.properties=file:conf/lite.json`: it stops those bundles, hides the
  MPR/MIP, key object and launcher toolbars, and sets the name and the `addisortho-lite` profile.
- `weasis-distributions/script/prepare-lite.sh` deletes the unused bundle files, translations and
  profiles from the jpackage input, and makes Lite copies of the platform resources (icons renamed,
  no Dicomizer, own macOS bundle identifier, no `weasis-addisortho://` scheme).
- `launch-options.sh <platform> lite` gives the smaller JDK module list (no `jdk.localedata`,
  `jdk.jdwp.agent`, `jdk.crypto.cryptoki`; `java.compiler` must stay because JAXB imports
  `javax.lang.model`) and its own single-instance port, 17180, so a second launch never hands files
  to a running full viewer. The runtime is not compressed with jlink: that halves its size on disk
  but makes the installer about 14 MB larger.
- Build: `package-weasis.sh --lite`, or **Build installers** with `edition` set to `lite` or `both`
  (the default; a tag always builds both and the release holds two .msi and four .pkg).

Measured on Linux with JDK 25 against `ao-4.7.3-1`: 117 MB installed instead of 150 MB, 25 bundles
instead of 39; the runtime is 91 MB of it. MPR code still lives inside `weasis-dicom-viewer2d`
(shared with the full edition); Lite only hides its toolbar.

| | Lite value |
|---|---|
| Installed app and installer | Addis Ortho Viewer Lite |
| Name in the interface | Addis Ortho DICOM Viewer Lite |
| macOS bundle identifier | `com.addisortho.viewer.lite` (CFBundleName `AO Viewer Lite`, under 16 characters) |
| Windows upgrade codes | x64 `0cad2ecd-35e6-4aa7-9647-16f80dcc7a31`, arm64 `ef84b678-2032-4f0a-b9c4-b5807f045b9a` |
| Preference profile | `addisortho-lite` |
| URL scheme | none |

## Receive from modality

The full viewer can receive images pushed by an X-ray machine or another modality over the hospital
LAN, as a DICOM Storage SCP (`weasis-dicom/weasis-dicom-receive`). It answers C-STORE and C-ECHO, so
the machine's "test connection" works. It is **off by default**. There is no encryption: use it only
on the hospital LAN (TLS, forwarding to a PACS, worklist, storage commitment and auto-delete are not
built). The 2D Lite leaves the bundle out (`lite.json`, `prepare-lite.sh`).

Set up in **File, Preferences, Receive from modality**:

| Setting | Default | Note |
|---|---|---|
| Receive images | off | |
| AE title | `AOVIEWER` | 1 to 16 plain characters |
| Port | `11112` | |
| Listen address | empty (all network cards) | or one IP address of this computer |
| Allowed senders | empty, so **every device is refused** | AE title and IP address, both exact |
| Storage folder | `AddisOrthoReceived` in the home folder | deliberately not Documents, which is often synchronised to a cloud service |
| Maximum connections | 4 | more are refused for now, and the machine can retry |
| Maximum size of one image | 1024 MB | a larger one is refused |
| Patient name in names | on | turn off if the folders may leave the hospital |

On the X-ray machine, in its DICOM Send or PACS destination settings, enter the **AE title** and
**port** above and the **IP address of this computer**. Add the machine to Allowed senders with its
own (calling) AE title and IP address, and give it a fixed IP address: a changed address is refused.
Then press the machine's test connection (C-ECHO). A refused device shows a notice with the AE title
and address it used, which is the quickest way to find what to enter.

**Windows firewall:** Windows blocks incoming connections until they are allowed. The first time the
receiver listens Windows may ask: allow it on private or domain networks only. Without that prompt,
add an inbound rule as an administrator (change the port if you changed it):

    netsh advfirewall firewall add rule name="Addis Ortho Viewer DICOM" dir=in action=allow protocol=TCP localport=11112 profile=private,domain

**macOS** may ask once to allow incoming connections for the app.

What happens to an image: it is written to `<storage folder>/.incoming`, checked to be DICOM with a
SOP Instance UID that matches the request and with study and series UIDs, then moved to the folder of
its study. It is added to the explorer, whose study title counts "N images received". A notice with a
**Show** button offers to open it in a new tab. A new arrival never opens, closes or replaces a viewer,
and never changes the selected patient.

Names (`ReceiveNaming`), with the arrival date of the file on this computer. The DICOM study date stays
inside the file and is used for the age:

    ABEBE KEBEDE_45Y_MRN77_2026-11-06_<first 12 hex digits of SHA-256 of the Study Instance UID>/
        ABEBE KEBEDE_45Y_MRN77_2026-11-06_<SOP Instance UID>.dcm

- Age is from the birth date to the study date (`45Y`, or `18M`, `12D` under 2 years). Without a birth
  date it comes from the Patient's Age element. Without either it is left out.
- A missing name or MRN is left out, and the folder then also holds the Study Instance UID.
- Every part is sanitized: path separators, `..`, control and invisible characters, characters reserved
  on Windows, reserved device names and trailing dots or spaces. Amharic stays. A long name is shortened
  to keep the path within 250 characters.
- A later image of the same study, even on another day, goes to the folder made on the first day. Its
  own file name has its own arrival date.
- An image with a SOP Instance UID already stored is answered with success, and the first copy is kept.

Answers to the machine: `0xA700` (out of resources, the machine can resend) when less than 200 MB is
free, the folder cannot be written or the image is above the limit; `0xC000` (cannot understand) for
anything that is not valid DICOM. The user sees a plain notice for each, at most once a minute per
kind, and the status line of the Preferences page shows why it is not listening (port in use, folder
missing or not writable, wrong address).

Retention: studies stay until the user deletes them. The Preferences page shows the folder size and
the studies, and has Show (loads a kept study, for example after a restart), Open folder and Delete.
Nothing is deleted automatically, so watch the disk.

Patient names and MRNs are allowed in these file names by the owner's rule for clinical software, and
must never reach the application log: the receiver logs UIDs, counts, AE titles and IP addresses only,
never a storage path. Upstream's "Adding patient" lines in `LoadDicom` and `DicomModel`, which wrote the
name, were changed for this.

Not tested against a real X-ray machine.

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
