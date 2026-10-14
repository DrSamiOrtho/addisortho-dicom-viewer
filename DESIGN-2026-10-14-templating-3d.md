# Design: templating and 3D bones in the Addis Ortho DICOM Viewer (14 Oct 2026)

Draft for Sami's review, updated after his decisions of 14 Oct. Nothing here is built yet. Work branch `addisortho/templating-3d`.

Sami, 14 Oct: "start with the templating module and better 3D reconstruction with segmentation modules. add the
templating module we worked on to this software. we want BE2, BE, cup and Knee template". Then: "use the system we
already built on center folder in samuel.hailu@aau.edu.et gdrive as well as locally" and "what we built was on
t.addisortho.com".

So this design reuses two systems that already exist and are tested, and adds only the joins and the missing viewer
pieces:

- the X-ray templating page that runs on t.addisortho.com and in the hospital sandbox (CENTER/EMR-POC/pacs-trial/
  templating), with its implant library;
- the 3D bone pipeline on this Mac (CENTER/EMR-POC/pacs-trial/planning/make_3d.py, run by `make_3d.sh` in
  `~/aojc-planning/.venv-ts`), which segments bones with TotalSegmentator, stores a DICOM SEG, cleaned CT copies, STL
  models and rendered 3D views in the trial PACS (dcm4chee-arc on 127.0.0.1:8280).

The viewer stays "not a certified medical device; planning support only". Every new output says draft or not reviewed.

## Part A. Templating offline inside the viewer, Addis Ortho primary hip and knee only

Sami, 14 Oct: "what about doing templating offline for addis ortho primary hip and knee cases only? and continue
keeping the yes keep patients name in both the template as well as the 3d recon".

### What the surgeon sees

1. In the viewer, open the X-ray (from a CD, a folder or PACS) and press **Template** on the toolbar.
2. The browser (Edge or Chrome) opens the templating page with that X-ray already loaded. No internet and no sign-in:
   the page and the X-ray come from the viewer on the same computer.
3. Templating works as on t.addisortho.com, with the Addis Ortho primary ranges only: `ao-be2` (BE2 133S, 133H, 127S,
   127H), `ao-be` (BE), `ao-cup` (cementless cup, 40 to 72) and `ao-knee` (femur 1 to 8, tibia 1, 2 and 4 to 8). The 130
   and 160 stems and every other maker stay off.
4. **Save in the viewer** (the hospital page's "Send to PACS", renamed) asks the surgeon to confirm the patient, then
   puts the plan picture into the viewer as a DICOM Secondary Capture in the X-ray's own patient and study, series
   "Templating plan right hip (draft)". From there the viewer's Export, DICOM send can store it in the hospital PACS.
   Save picture (PNG), Save DICOM and Save project (.aotp) work as in the hospital page.
5. File names and the DICOM carry the patient's name, record number and operated side, as decided on 11 Oct; ticking
   "sharing outside the hospital" leaves them out, as now.

### How it works

The hospital copy of the page (`web/`, data-mode unset) already opens an X-ray from `../dicomweb` and `../wado` and
stores its plan through `store`. The viewer plays that archive on 127.0.0.1, so the page needs only wording changes.

Templating side (CENTER/EMR-POC/pacs-trial/templating):

- `web/app.js`: a third mode, `data-mode="viewer"`. It changes words only: "Save in the viewer" for "Send to PACS",
  "the viewer" for "PACS" or "the archive" in its messages, and a plain message for an expired link ("press Template in
  the viewer again") instead of the EMR sign-in message. No maths changes.
- `build_viewer.sh` (new, like `build_standalone.sh`): copies the page into `dist/templating-viewer/` with
  data-mode="viewer" and a `library.json` holding only the four ranges, cut from `web/library.json` by
  `build_ranges.py` so geometry and `libraryId` stay identical.
- `test.mjs`: checks that the viewer library has exactly those four ranges and their 8 families and 100 sizes, and that
  viewer mode shows no PACS or sign-in wording.

Viewer side, new bundle `weasis-dicom/weasis-dicom-templating`:

- **Template** toolbar button, enabled when the selected image is a single-frame CR, DX, DR or RG image (the page's own
  rule); the X-ray's DICOM file comes from the image's media reader.
- A small web server (`com.sun.net.httpserver`, part of Java) bound to 127.0.0.1 only, on port 47210 when free so the
  page's in-browser plan memory survives between sessions (it is kept per address), else any free port. Each press of
  Template makes a random 128-bit token, valid 12 hours, and every address sits under `/t/<token>/`:
  - `templating/...`: the page's files, from the bundle;
  - `dicomweb/studies/<study>/instances`: the one X-ray (modality, series, instance, class), or the plans received, for
    the page's "is it already there" check;
  - `wado?...`: the X-ray's original file, byte for byte;
  - `templating/store` (POST, at most 30 MB): the plan.
- Checks: `Host` must be `127.0.0.1:<port>`; a POST must carry the page's own `Origin`; an unknown or expired token gets
  404. A plan must parse as DICOM, be a Secondary Capture (1.2.840.10008.5.1.4.1.1.7) with a new SOPInstanceUID and the
  X-ray's StudyInstanceUID, PatientID and IssuerOfPatientID, or it is refused with the reason (422). The same plan sent
  twice is stored once (409 with its SOPInstanceUID, which the page already treats as success).
- An accepted plan is written to the viewer's temporary folder and loaded into the explorer like a local file.
- The page's files are copied from `dist/templating-viewer/` into the bundle by a script that records the source's
  sha256, so the viewer always says which page build it carries.
- Registered in both `base.json` files and the distribution POM; Apache-2.0 headers naming Addis Ortho Trading PLC
  (module-level Spotless setting, upstream files untouched); JUnit tests for the token, host, origin and plan checks.

### Weaknesses

- The four ranges' outlines and the page's code become public: they are inside the installer and, under GPLv3, in the
  viewer's public GitHub source. The J&J and Zimmer Biomet ranges stay only on t.addisortho.com.
- All outlines are still `verified: false` (not yet confirmed by Chunli); the page keeps saying so.
- A plan made in the viewer is not on t.addisortho.com's templated-patients list. Its .aotp project file can be opened
  there later.
- A page fix needs a new viewer release to reach the hospitals (t.addisortho.com gets it on upload).
- Safari is not checked; Edge and Chrome are the supported browsers.

## Part B. 3D bones from CT

### What exists

The viewer already renders CT in 3D on the graphics card (bone presets, shading, MPR cut) and shows a DICOM SEG in
slices, MPR and 3D, with per-bone visibility, colour and opacity, and a "Mask include" mode that renders real CT voxels
inside the chosen bones only. It cannot create or edit a segmentation, has no crop box, no snapshot and no STL export,
and a SEG looks stepped in 3D because labels are drawn as whole voxels.

make_3d.py already does the hard part on this Mac: TotalSegmentator labels the bones, each bone is refined against the
CT (grow 2 voxels, keep 180 HU and above plus the label's core, close, fill holes, largest piece), and the result is
stored as a SEG with "CT table removed", "CT bones only", STL per bone and rendered views.

### What this round adds to the viewer

1. **Make 3D bones** (3D viewer and explorer menus): on a computer where `make_3d.sh` is configured (today: this Mac),
   exports the selected CT series to a temporary folder, runs `make_3d.sh <folder> --label <code> --region
   hip|knee|shoulder|spine`, shows its progress, and when it ends opens the case's SEG and cleaned CT in the 3D view with
   Mask include on. Elsewhere the menu item is hidden. The result is stored in the trial PACS as make_3d.py does now.
2. **Smooth bone surfaces**: the visible segments are also uploaded as a smoothed occupancy texture and the 3D view
   draws its half-way surface, so a 3 mm CT no longer looks like stacked blocks. Viewer code only (shader and
   `SegVolumeTexture`).
3. **Crop box** with handles in the 3D view (the cropping class is an empty stub upstream).
4. **3D snapshot**: the current 3D picture saved as a Secondary Capture in the patient's study, marked draft.
5. **Export bone as STL** from the segment list, for 3D printing: marching cubes on the CT inside the grown bone label,
   largest piece, smoothing, written in millimetres in DICOM patient coordinates, the same chain as `hip_segment.py`.
   Pure Java in the new code (no VTK in the viewer).

Later round, not now: a threshold and region-grow tool with brush and eraser, saving a new SEG from the viewer, and
measuring in the 3D view. That tool matters for knees (see below).

### Weaknesses and checks

- **Knee bones need a licence.** In the installed TotalSegmentator, the tibia, fibula and patella exist only in the
  `appendicular_bones` model, which is listed as commercial (checked 14 Oct in `~/aojc-planning/.venv-ts`). The free
  `total` model covers the femurs, pelvis, sacrum and spine. So `--region knee` cannot run as written without a
  licence. Until Sami decides, knee 3D uses Mask include on the CT and, later, the region-grow tool.
- Names (Sami, 14 Oct: keep the patient's name in the 3D reconstruction): the viewer runs make_3d.py with
  `--keep-identity`, so the SEG and cleaned CTs sit in the patient's own study; the case folder still uses a code. A
  "remove the name (sharing outside the hospital)" tick runs it without `--keep-identity`, as today.
- make_3d.py stores its results only in the trial PACS. A new `--out-dicom <folder>` option also writes them to a folder
  the viewer opens directly, so the viewer does not need a PACS connection to show them.
- The pipeline runs only where TotalSegmentator is installed (gigabytes, Python, a strong CPU or GPU). Hospital Windows
  PCs get the viewer improvements (2 to 5) for any SEG already in PACS, but not item 1, until the pipeline runs on a
  server.
- STL output for printed models moves the viewer toward medical-device territory; every STL and SEG says automatic and
  not reviewed, and a patient-specific guide is out of scope.

## Order of work and tests

1. Templating page viewer mode, `build_viewer.sh` and their tests.
2. Viewer templating bundle and its tests; an end-to-end check in Edge or Chrome with a test X-ray.
3. 3D items 2, 3 and 4 (viewer only), then 5, then 1.
4. Installers built on GitHub Actions, then the smoke test, then a pre-release `ao-4.7.3-2`.

Building on this Mac needs a JDK 25 or newer and Maven, neither installed (`brew install openjdk maven` would do it).
Without them the Java side is built and tested only on GitHub Actions, which means pushing the work branch.

## Decisions for Sami

1. Decided 14 Oct: templating offline, Addis Ortho primary hip and knee only; names kept in templating and 3D.
2. Knee 3D: buy the TotalSegmentator licence for the knee bones, or wait for the region-grow tool.
3. Building: install JDK and Maven on this Mac with Homebrew, or push the work branch to GitHub and let Actions build.
