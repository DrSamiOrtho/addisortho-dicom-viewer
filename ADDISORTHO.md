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

It is not a certified medical device. It is for image review and surgical planning support only.
The release notes say so. There is no first-launch disclaimer: the owner turned it off on
2026-10-23 (`weasis.show.disclaimer` is `false` in `weasis-distributions/etc/config/base.json`), and
the smoke test checks that no "First Time Launch" window opens. Upstream's dialog code and text are
unchanged, so turning the setting back on shows upstream's wording.

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

## Receive from modality

The full viewer can receive images pushed by an X-ray machine or another modality over the hospital
LAN, as a DICOM Storage SCP (`weasis-dicom/weasis-dicom-receive`). It answers C-STORE and C-ECHO, so
the machine's "test connection" works. It is **off by default**. There is no encryption: use it only
on the hospital LAN (TLS, forwarding to a PACS, worklist, storage commitment and auto-delete are not
built). The 4 GB Lite on this branch includes it, off by default, as the full viewer does. The 2D Lite on
`main` leaves it out (`lite.json`, `prepare-lite.sh`).

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
never a storage path. Upstream log lines that wrote the name or a file path were changed for this
(`LoadDicom`, `DicomModel`, `DicomMediaIO`, `DicomImageElement`). The deflated transfer syntax is not
accepted; every sender must offer an uncompressed one.

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
5. Open. Smoke test both installers next to a real Weasis install: no first-launch dialog, DICOMDIR from a CD,
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
callback page and the Resource Monitor report.
Non-English translations come from `weasis-i18n-dist` and still say Weasis.

### Look

- Theme: the default dark theme, "Core Dark - Flat Addis Ortho", is `org.weasis.launcher.FlatWeasisTheme`
  (the class name is the stored `weasis.theme` value, keep it). It extends FlatDarkLaf; every colour and
  shape is in `weasis-launcher/src/main/resources/org/weasis/theme/FlatWeasisTheme.properties`. Surfaces
  are near-black with a `#034537` cast, selection is white on `#034537`, and `#A8FF30` is only for small
  accents (focus ring, tab underline, progress, slider). `controlDkShadow` and `windowBorder` are set
  there because DockingFrames draws its tab outlines with them. Upstream's Monokai `Weasis.theme.json`
  is removed. The light theme is upstream's FlatIntelliJ.
- Start screen: `WelcomeLayerUI` (weasis-base-ui) is a `JLayer` around the docking area that paints the
  wordmark (`svg/logo/AddisOrthoWordmark.svg`, the About logo without its black plate), "DICOM Viewer"
  and a hint while the main working area holds no viewer. It only paints, so drops and clicks still
  reach the working area. Image panes are never painted on: they stay neutral black.
- Mark: on macOS the AO mark (`BrandMark`, `LogoIcon.SMALL`) sits at the left of the toolbar, as tall
  as the toolbar buttons (it follows the button row, with or without text labels), because the menu
  bar is in the screen and the title bar is native. Clicking it goes back to the start screen: after
  a confirmation it closes every open viewer the way "Close All" does. On Windows FlatLaf shows the
  frame icon in its title bar, so nothing is added there.

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
  - **Bones** (rebuilt again 23 Oct 2026: find all, click to select). A CT opens in 3D as the plain isolated bone;
    Find the bones (or Show the bones, once found) finds every bone at once (`grow/AutoSplit`, run by `FindWorker`): bone at 200 HU closed and marrow-filled, dense bone at 300 HU, a Euclidean distance
    transform in mm, and a watershed that keeps two parts apart only where the neck is under 0.45 of the thinner part's
    depth, at least 1 mm shallower, with 0.2 cm³ above it; pieces under 1 cm³ join the bone they touch most or lie
    within 10 mm of. A CT over 16 M voxels in the crop is worked on a 2x (or coarser) grid. The 3D view then shows
    Bones in colour (`SegmentationTool.Type.BONES`: the CT itself, only inside visible segments, tinted with the
    segment colour, `segTint` in `vrFunctions.glsl`). A click on a bone in 3D, on an MPR or plain 2D slice (`SeedPicker.fromSliceClick`: the slice's own
    geometry, as the cross-hair) or in the list selects it
    (Shift, Ctrl or Cmd adds); Hide, Show only, Show all, Join, Split, Name and Delete act on the selection. Hidden and
    selected are colour-table styles (`View3d.setSegmentStyles`, static, keyed by SEG instance, so a rebuilt 3D view
    keeps them), not cuts and not a SEG rewrite. Split takes two clicks on one bone and floods it from both, brightest CT
    first (`AutoSplit.splitBetween` with the CT), so the cut follows the darkest surface between them: the fracture
    line or joint space, even where the parts press together over a broad face. Every step is an `EditHistory`
    entry of the 3D view, so Ctrl+Z walks through bone steps, cuts and crops together. Bones are immutable (`Bone`,
    `grow/BoxMask`); the SEG (series 902, segments numbered in list order) is rewritten in the background only when
    voxels, names or colours change (`SegWriteWorker`, one at a time). More tools keeps the region grow (New bone, with
    the region, bone and side picker of `BoneNames`), Erase piece (`grow/BoneSplit`) and the grow settings. Keep only
    this and Remove this left the 3D tool bar (still in the mouse button menus). Natural look (a Bones panel switch)
    shows the bones as the CT shows bone (`SEG_MASK_INCLUDE`), hidden ones still hidden; untick for the colours.
  - **Make 3D bones**: runs the owner's TotalSegmentator pipeline (`planning/make_3d.sh`, free model: hip, shoulder,
    spine) where it is installed, and opens its results in the study.
- `viewer3d`: Smooth surfaces for segments, a crop box, and "Save 3D picture to the study" / "Copy 3D picture".
  A CT opened on the bone is also isolated (22 Oct 2026, `vr/BoneIsolation`, one undoable cut after the crop; the
  crop panel's Isolate bone runs it on demand): a plaster cast, the table, the pad and the soft tissue go because
  they touch the outside air (found slice by slice in 2D) and bone does not. What touches no air is sorted by real
  size (anisotropic voxels, mm3): a piece of 1 cm3 or more is bone, one under 5 mm3 is noise, one in between stays
  only when the gap to a kept piece is under 4 mm, edge to edge in true mm (exact distance transform on the piece
  grown by one voxel), spreading piece to piece for at most 4 links (fragments, sesamoids, carpals and phalanges stay;
  the specks in a pad, a table board and a side marker go; a fragment displaced over 4 mm or a paediatric ossification
  centre is lost, Undo or Whole volume restores it). Each kept piece is hole-filled on its own, so two bones never
  enclose pad between them; metal stays whole only when it touches a bone before the 2-voxel widening and has no air
  contact, a fixator pin only inside the bone (23 Oct 2026, after GPT-6 Astra's review).
- `viewer3d` 3D tools bar (14 Oct, evening): the mouse tools rotate, roll, pan, zoom, window/level, scalpel, keep only
  this, remove this, length and arrow; the six anatomical orientations (keys 1 to 6); fit (Ctrl+0) and reset scene
  (Ctrl+R); shading (Ctrl+S); copy (Ctrl+C), JPEG (Ctrl+E), quick movie (F2) and view-state snapshots (K, [ and ]).
  The scalpel (S, or Alt + left drag with any tool) draws a closed area; Delete cuts inside it and Enter outside it,
  through the whole depth, with Ctrl+Z / Ctrl+Y undo and redo and Ctrl+Alt+0 restoring the volume. Keep only this and
  remove this pick the structure under a click (CPU ray march of the renderer's own transfer function) and grow it over
  the shown voxels above the visible threshold, with a light separation option. The cuts live in `KeptMask`, one bit
  per texture voxel on the CPU (`isKeptVolumeVoxel` is the API for the bones bundle) and one byte per voxel on the GPU
  (unit 7), which every rendering mode and the segment overlays sample. Rotation and zoom centre on the crop box when
  it is on. Keys of the 3D view are listed in `Shortcuts3d`; T is the roll (M pans), unlike the 2D viewer.

## Brand footer and Help links (18 Oct 2026)

- Footer: view pictures saved or copied from the screenshot dialog (view mode) and 3D pictures (copied or saved
  to the study) get a thin strip below the picture, white on `#034537`, naming `weasis.name`. The templating
  page's saved picture (PNG, DICOM, saved to the viewer) says "Templated with Addis Ortho Viewer" at the foot of
  its report column. One switch turns all of them off: Preferences, General (`addisortho.picture.footer`,
  default on; the viewer adds `footer=0` to the templating page's address when it is off). Image mode (raw
  pixels) and the explorer's image export are not touched. The templating page's "leave out the patient's
  name" switch is unchanged.
- `app.js` in `templating-web` was patched here (BUILD.txt updated), so the change must also go into the
  templating project's `web/app.js` before the next `sync-templating-web.sh`, or the sync drops it.
- Help, Download page (`addisortho.help.download`) and Help, Support (Telegram) (`addisortho.help.support`) in
  `weasis-distributions/etc/config/base.json`; each is hidden when empty.

## Long-leg stitching (27 Oct 2026, phase 1, draft)

Design: `DESIGN-2026-10-27-long-leg-stitching.md` (reviewed by Fable and GPT-6 Astra). All outputs say draft,
automatic, semi-automatic or manual, and not reviewed.

- `weasis-dicom/weasis-dicom-stitching`: the **Stitch** button (2D viewer toolbar) opens a window for the selected
  image's study. Radiographers often give every piece the same series description, so each row shows a picture
  (`PiecePicture`, loaded in the background, same orientation and polarity as the match, never used for matching),
  the file name unless it is only digits and dots, and the series, time, body part, view, side and comments the file
  states; clicking a row shows that image large on the right. The user ticks two to four CR/DX/DR/RG pieces of one acquisition (none ticked at first), orders them
  head to foot, and confirms one acquisition, projection, position, target leg and the part of the image to match on
(whole width, or the left or right half as shown, for a film of both legs); none has a default. **Line up** matches each
  seam (`SeamMatcher`, pure Java, no OpenCV keypoints needed): a coarse correlation of gradient magnitude on a 2 mm grid,
  textured patches matched at 0.5 mm and a RANSAC rigid fit (scale locked, only checked), then the gates: peak uniqueness
  (ruler ticks, repeats), edge direction over the bright part of the overlap (shaft-only overlaps), inliers spread across
  the overlap, residual, rotation and its uncertainty, and independent held-back patches (at least two, the worst must
  agree within 2 mm). Nothing
  is ever "good": a seam is "check" or "failed". **Inspect seam** shows a checkerboard or difference of the overlap, arrow
  keys nudge 0.1 mm (Shift 1 mm), [ and ] turn 0.1 degree, and matching points can be picked; three or more pairs get a
  left-out check (spread at least 30 mm). A failed seam stays failed when only nudged. Changing the pieces, their order
  or the band discards the seams, and a job started before the change is dropped. **Save to the study** writes a 16-bit Secondary Capture
  (series 903, Modality of the pieces, "Long leg stitched (draft, ..., not reviewed)", DCM 113090 Image stitching,
  SourceImageSequence with SpatialLocationsPreserved NO, seam data as JSON in private block "ADDIS ORTHO STITCH 1",
  (0071,1001)) with the label burned into its own strip above the image and seam ticks at the edges, streamed strip by strip, and loads and opens it.
- Scale: each piece is measured exactly as the viewer measures it (`Spacing.weasis` repeats
  `DicomImageElement.initPixelConfiguration`); where the templating page would scale it differently the window says so.
  Pieces on different spacing bases give an output without PixelSpacing ("No scale" in the label); pieces with and
  without spacing cannot be joined. A piece without spacing is worked at 0.15 mm per pixel (never written out) with its
  PixelAspectRatio kept. A piece with a VOI LUT gets a note: the stitch uses a straight window.
- Phase 1 gaps (in the design, not built): no anatomical classifiers, so order is the user's; ruler and skin are not
  excluded from the match automatically (a brightness filter stands in for "on bone"); no seam-aware uncertainty in the
  templating page; no Lite memory and time benchmark on a real 4 GB PC yet; the VOI LUT curve is not reproduced. Not tested inside the running viewer yet:
  the window was driven from code with synthetic phantom pieces.

## Shortcuts (6 Nov 2026)

`SHORTCUTS.md` is the one list for the 2D viewer, the 3D view and both templating pages (RadiAnt's keys). Change a key there and in
every place it names. The templating pages are release r44d (`dist/released-2026-11-06-bipolar-keys` in the templating project, with the viewer's hand-over added to Quick by build_viewer.sh): the full page as r44b, and the Quick page live since 5 Nov 2026 (bipolar hip, Image panel, plan files, Join films) with these keys on top. On Quick the live page's B, K, O, plain arrows, `[ ]` and plain 1, 2, 3 are gone here because the list gives them other meanings; the Image panel and the `/` command box stay. t.addisortho.com does not have these keys yet.
