# Design: long-leg X-ray stitching in the Addis Ortho DICOM Viewer (27 Oct 2026)

Draft for Sami's review. Phase 1 is built on branch `addisortho/long-leg-stitching` (27 Oct 2026, not yet tested in
the running viewer or on real CDs; see section 14). Reviewed by Claude Fable and by GPT-6 Astra in Codex (section 12);
Astra's verdict was "revise before building", and its changes are folded in below. Every output of this feature says draft, and automatic, semi-automatic or manual, and not reviewed.

## 1. What Sami asked for

Stitch long-leg X-rays that arrive on CDs as two to four separate CR or DX pictures: standing or supine, pelvis to
ankle, or only part of that span (hip to knee, knee to ankle). Line the pieces up by recognising the bones, with a
manual fallback. The result must keep its scale so the viewer's length and angle tools and the X-ray templating page
still work. The viewer runs offline on Windows hospital PCs, some with 4 GB of RAM (Lite edition: 1 GB Java heap),
and on Macs.

## 2. What already exists

- Upstream Weasis 4.7.3 has no stitching, panorama or mosaic code. The only "panoramic" code is the curved MPR for
  dental CT (`weasis-dicom-viewer2d/.../mpr/cmpr`), which samples a volume along a curve and does not help here.
  A web search found no Weasis plug-in for it either.
- The OpenCV build the viewer ships (`opencv_java` 5.0.0-dcm, in `weasis-core-img` and the `weasis-opencv-core-*`
  natives) contains only the `core`, `imgproc`, `imgcodecs` and `geometry` modules. Checked in the macOS arm64 dylib
  and the Windows x64 DLL on 27 Oct:
  - present: `Imgproc.matchTemplate`, `phaseCorrelate`, `createCLAHE`, `Canny`, `HoughCircles`, `distanceTransform`,
    `warpAffine`, `createHanningWindow`; `Geometry.estimateAffinePartial2D` (RANSAC similarity), `estimateAffine2D`,
    `findHomography`;
  - absent: `features2d` (no ORB, AKAZE or SIFT keypoints and descriptors), `dnn` (no neural network inference),
    `video` (no ECC alignment), `stitching`, and even `goodFeaturesToTrack`;
  - also present (Fable checked): `estimateTranslation2D`, `cornerEigenValsAndVecs`, `cornerMinEigenVal`,
    `cornerHarris`, `Sobel`, `Scharr`, the line segment detector, generalised Hough and `putText`, so the structure
    tensor and the aperture test below need no new native code. Astra also found `img_hash`.
  So keypoint-descriptor stitching as in a phone panorama is not available without changing the native build, which
  nroduit maintains for all platforms. This design does not need it.
- The X-ray templating page (`weasis-dicom-templating`, resources `templating-web`) already measures a long-leg film:
  `autoplan.js longLegAngles` gives HKA (with varus or valgus and the mechanical axis deviation), mLDFA, MPTA, JLCA and
  the anatomic-to-mechanical angles from landmarks the surgeon places (femoral head rim, knee centre, talus centre,
  condyles, plateaus, shaft points). In the knee case it guesses a film's role as "long" when the film is more than
  twice as tall as wide or its description says LONG, LOWER LIMB, LEG LENGTH, SCANOGRAM or STANDING (`app.js`
  `kcGuessRole`, around line 5313); that is a role guess for the knee case, not a general long-film mode. It also finds
  and fits the calibration ball, and accepts an image of up to 60 megapixels (`projects.js` `PIXELS_MAX`).
- Weasis measures a CR or DX piece without PixelSpacing at ImagerPixelSpacing divided by
  EstimatedRadiographicMagnificationFactor, or by SID over SOD, and labels it "Magnified"
  (`DicomImageElement.getMagnifiedPixelSpacing`, lines 183 to 203). The templating page does the same
  (`app.js dicomEstimate`, lines 516 to 525). So the per-piece scale a user already sees is often magnification
  corrected, not detector spacing. The two policies are not identical (Astra): the page sometimes prefers
  ImagerPixelSpacing even when PixelSpacing exists, accepts a magnification factor only between 0.9 and 2, and has no
  SID over SOD fallback. The page's ball calibration sets one scale for the whole film (`app.js setBall`, line 3792).
- The Template button accepts an image by Modality only (CR, DX, DR, RG; `TemplatingService.MODALITIES`), and a
  derived picture goes back into the study through `LoadLocalDicom` (`TemplatingService.intoExplorer`). The stitch
  can reuse both patterns.

## 3. Inputs

Two to four single-frame CR or DX images, normally of one study (same StudyInstanceUID), sometimes in separate series
or with different series numbers, in any order. They overlap by a few centimetres. They may differ in exposure, in
processing (each CR plate is windowed and equalised on its own), in pixel spacing and in magnification. Some show a
radiopaque ruler or a calibration ball. Some are MONOCHROME1.

The tool first checks each candidate:

- Single frame, CR, DX, DR or RG, with pixel data the viewer can decode.
- Spacing: exactly the spacing Weasis uses for that piece's own length tool: PixelSpacing, else ImagerPixelSpacing
  divided by the magnification factor or SID over SOD when present, else ImagerPixelSpacing at the detector, else none
  (Fable, P1: otherwise the stitched image would measure 5 to 15 percent longer than its own pieces). Pieces whose
  spacing bases differ (one magnification corrected, one at the detector) are flagged before matching. Where Weasis's
  and the page's policies would give a piece different scales, the tool shows both and does not call either the
  patient-plane truth (Astra, P2); the build adds tests for conflicting, missing and anisotropic spacing. Without any
  spacing the stitch can still be made but is labelled "no scale" and length tools show pixels.
- Already stitched by the modality and also present as pieces on the same CD: the modality's own image is preferred
  and the tool says so.
- Already long: an image whose ImageType or DerivationDescription says it was stitched by the modality is not a piece.
  An image over 600 mm tall only raises a warning (Astra, P3), since a long cassette can still need a short addition.
- Orientation: PatientOrientation and the R/L marker are read where present. Pieces are shown head up; a piece that
  appears upside down or mirrored compared with the others is flagged for the user, never flipped silently.

## 4. User flow

1. In the 2D viewer, with any piece selected, press **Stitch** (toolbar, next to Template). The button is enabled when
   the study holds at least two eligible images.
2. A stitch window opens with the eligible images of the study as thumbnails, none ticked (Astra, P1: preselecting all
   invites a repeat exposure or a lateral into the stitch). The user ticks the pieces of one acquisition and confirms
   four things the tool cannot know: that they are one acquisition, the projection (AP or PA), standing or supine, and
   the target leg. There is no default leg. Order is proposed (section 5.1); the user can drag to change it.
3. **Line up** runs the automatic match (a few seconds). Each seam shows its method, a confidence word (good, check,
   failed) and the residual in mm.
4. Each seam can be inspected in a close-up: a difference view or a checkerboard of the two pieces in the overlap, with
   arrow-key nudges (0.1 mm), a small rotation, and **Pick matching points** (section 5.4). A seam marked failed must
   be fixed by hand or the piece removed before saving.
5. **Save to the study** writes the derived image (section 7) and opens it (the templating plan's `LoadLocalDicom`
   call uses `OpeningViewer.NONE`, which does not open anything, so the stitch must pass an opening option). The
   pieces are never altered.

## 5. Registration

All work happens on copies resampled to a common grid in mm, so pieces with different spacing meet at the same scale.
Each piece: decode, apply the modality LUT, invert MONOCHROME1 first, then take the logarithm only when
PixelIntensityRelationship says LIN (and leave LOG data alone), flatten a VOI LUT sequence the same way for every
piece, clip to the 1st to 99th percentile (after removing padding), CLAHE, and mask what must not drive the match: collimator shutters (flat border regions), burned-in
text and the R/L lead marker (small saturated blobs near edges). A pyramid at 2 mm, 1 mm and 0.5 mm per pixel serves
the coarse-to-fine search.

These normalised images are disposable and used only for matching. The output's grey values come from a separate
pipeline (section 6) that follows the DICOM modality, VOI and presentation rules (Astra, P2: PixelIntensityRelationship
does not define a display transform).

The transform between neighbours is rigid: shift and rotation up to 3 degrees, with scale locked to the spacing of
section 3 (Astra, P1). A fitted scale would silently change every length inside the warped piece, and chained seams
would add it up. The matcher still estimates scale, but only as a check: a difference over 1 percent makes the seam
"check" and is reported, and the user may choose to apply it, which the output then records. When the pieces offer no
defensible common scale, the stitch is saved without PixelSpacing ("no scale"), so calibrated templating is not offered
on it. No perspective homography: with a few centimetres of overlap it would bend the limb to fit noise.

### 5.1 Order and what each piece shows

A simple classifier on the 2 mm image labels each piece pelvis/hip, femur shaft, knee, tibia shaft or ankle:

- femoral head: `HoughCircles` on the edge map for a circle of 18 to 30 mm radius below the acetabular roof, with the
  ball-fit quality gate the templating page already uses (edge coverage, ovality);
- knee joint line: in the profile of row means taken along the limb, a dark band between two wide bony ends;
- ankle: the talar dome arc and the narrow distal tibia and fibula, with the foot below.

Order follows the labels head to foot; where labels are unsure, the pairwise match scores decide (the order with the
best total score wins). Acquisition time and instance number are weak tiebreakers only: on CR they are often missing
or identical. The user can always reorder.

### 5.2 Automatic overlap match (primary)

For each neighbouring pair:

1. Coarse: the bottom 25 percent of the upper piece against the top 25 percent of the lower one, on gradient
   magnitude (not raw intensity, so exposure and processing differences matter less), with `matchTemplate`
   (normalised correlation) over vertical shifts covering 0 to 120 mm of overlap and horizontal shifts of plus or
   minus 40 mm, at a few scales around the spacing ratio. `phaseCorrelate` with a Hanning window gives an independent
   shift estimate.
2. Fine: in the overlap found, 20 to 60 small patches (about 15 mm) placed where the structure tensor shows edges in
   two directions (corners of cortex, trabecular texture, the ruler's ticks, skin folds). Each patch is matched with
   `matchTemplate` at 0.5 mm per pixel and refined to sub-pixel. The pairs go to
   `Geometry.estimateAffinePartial2D` with RANSAC.
3. Confidence: inlier count and spread, residual in mm, the correlation after alignment, and agreement between step 1,
   step 2 and the landmark check (5.3). Two further gates (Fable, P1): peak uniqueness, the coarse correlation's best
   peak against the best peak outside its basin, so a match that could equally sit one ruler tick or one trabecular
   period away is "failed" whatever its residual; and the rotation uncertainty of the seam, from the spread of the
   RANSAC inliers, because a seam's rotation error adds straight into HKA when the hip, knee and ankle lie in different
   pieces. Thresholds give good, check or failed. Matches on the ruler or skin count for nothing in the anatomical
   confidence (Astra, P1: the ruler sits at another depth), and phase correlation and template matching on the same
   texture count as one piece of evidence, not two. Correspondences must be on bone and spread across the overlap; one
   patch per region is held back and used only to check the fit. Until the thresholds are measured on our
   own cases (section 10), nothing is "good": every seam opens as "check".

The known weak case is an overlap that holds only shaft. Two straight parallel cortices fix the side-to-side position
and the rotation but not the shift along the bone (the aperture problem). The structure tensor detects it: when the
overlap has edges in one direction only, the tool does not guess. It asks for matching points on something that is
not a straight line (a ruler numeral, a nutrient foramen, the other leg, a skin crease), and says why. The ruler's
evenly spaced ticks are not used to fix that shift: they repeat, so they are exactly the ambiguity (Fable, P1). Only
its numerals or another non-repeating mark count.

Bilateral standing films: the other leg gives a valid but different match, because the two legs are at different
depths and positions. The match is computed on the target leg the user confirmed in step 2 (Astra overruled Fable's
"leg nearer the centre" default), and the stitch says which leg it was built on.

### 5.3 Bone landmarks (check and anchor)

The same detectors as 5.1, at full resolution in the overlap: femoral head centre, knee joint line (the lowest points
of the condyles and the tibial plateau), ankle mortise. When the same landmark appears in both neighbours (often the
knee in a three-piece protocol), it anchors the match and checks the overlap result: if the two disagree by more than
3 mm the seam is "check". Landmarks alone never decide a seam; they label pieces, order them and catch a wrong match.

### 5.4 Manual fallback

Pick matching points: the user clicks a point in the upper piece and the same point in the lower one, as many pairs as
wanted. One pair gives a shift, two or more give the rigid fit by least squares, and each pair shows its residual.
Two pairs fit perfectly whether right or wrong (Astra), so a manual seam stays "check" until at least three pairs on
bone, spread across the overlap, agree, with one left out of the fit and checked against it. Manual adjustment never
clears an unresolved warning by itself. Arrow-key nudges and a rotation slider remain for small corrections. A seam
set this way is "manual"; one that started automatic and was adjusted is "semi-automatic".

## 6. Blending

1. Exposure: in the overlap, fit a robust linear mapping (median-based, in the log domain) from the lower piece to the
   upper piece, and chain it down so the whole limb shares one grey scale. Do not equalise further.
2. Seam: phase 1 uses a straight cut across the middle of the overlap with a narrow feathered band (10 mm). Later, a
   seam path chosen by dynamic programming on the difference between the two pieces, so the cut avoids crossing cortex
   where they disagree. Wide blending is avoided because it doubles the bone edges (a ghost) whenever the match is off
   by a millimetre.
3. Output grid: the finest corrected spacing among the pieces, but never finer than 0.15 mm, and at most about
   32 megapixels (Fable, P2: the templating page refuses more than 60 megapixels). Astra counted the page's own
   copies (floats in `dicom.js` line 198, a full RGBA canvas in `app.js` lines 795 to 801): about 448 MB at
   32 megapixels before browser overhead. So the final cap is set by a benchmark on a 4 GB PC with the viewer and the
   Template page open together; 20 megapixels may be the honest limit for Lite. The full image is built in horizontal strips, so the Java heap never holds
   more than one strip of floats.
4. The chained grey mapping is clipped into 16 bits, with one value reserved outside the valid range for the empty
   corners, declared as PixelPaddingValue so automatic windowing ignores them.

## 7. Output: a derived DICOM image in the same study

- One new instance in a new series in the same patient and study: Secondary Capture Image Storage
  (1.2.840.10008.5.1.4.1.1.7), MONOCHROME2, 16 bits stored, single frame. Modality kept from the pieces (CR or DX) so
  the Template button accepts it; ConversionType WSD.
- ImageType DERIVED\SECONDARY. SeriesDescription "Long leg stitched (draft, automatic, not reviewed)", or semi-automatic
  or manual as the seams were made. The description contains LONG so the templating page opens it as a long film.
- New series and instance UIDs.
- SourceImageSequence lists every piece by SOP Class and Instance UID, with SpatialLocationsPreserved NO (the pieces
  were warped), and a coded derivation (from CID 7203, chosen and checked at build time). DerivationDescription states
  the algorithm version, the target leg and, per seam, the method, shift, rotation, scale check, residual, registration
  uncertainty and confidence, and the spacing basis of each piece.
- Machine-readable seam and source-region data (rows each piece contributes, seam positions, per-seam uncertainty) in
  a private block, so the templating page and later reports can tell which piece a landmark lies in. Required before
  planning measurements on a stitch are enabled (Astra, P1).
- Secondary Capture stays the choice (Fable, P2): DX For Presentation would require ImagerPixelSpacing and many other
  Type 1 attributes the pieces may lack, while Weasis reads PixelSpacing from any image first
  (`DicomImageElement` line 88) and the Template button checks only Modality (`TemplatingService` lines 37 and 69).
- PixelSpacing set to the output grid, which is built from the corrected per-piece spacing (section 3).
  PixelSpacingCalibrationType GEOMETRY when a magnification factor or SID over SOD was applied to the pieces, empty
  otherwise. PixelSpacingCalibrationDescription says which, for example "detector spacing divided by the machine's
  magnification factor; not calibrated to the patient plane at the knee; stitched draft". ImagerPixelSpacing is not
  copied, because the output is not a detector image; with it absent, the templating page falls through to
  PixelSpacing (`app.js` line 525), and with GEOMETRY it reports the scale as calibrated by the machine, which is what
  the pieces claimed. The page's ball calibration still overrides this at the hip.
- Also: PresentationLUTShape IDENTITY; WindowCenter and WindowWidth computed after the exposure mapping;
  PatientOrientation on the output, so R/L sense survives; Laterality and BodyPartExamined only when every piece
  agrees; a fixed high SeriesNumber (903, beside the bones SEG's 902).
- A burned-in label in a corner outside the limb carrying the full status (Astra, P1): for example "Stitched draft,
  semi-automatic, not reviewed. Left leg. Lengths and angles across seams unverified." Short tick marks at each seam
  on the image edge so anyone measuring across a seam can see it. BurnedInAnnotation is about identifying text, not this
  label (Astra, P2): it is YES only when the source pixels already carry burned-in patient text, which the tool
  inspects and copies from the pieces' own attribute.
- Patient name and record number as in the source, per Sami's 11 Oct rule; the existing export options for leaving the
  name out apply.
- The DICOM writer streams the pixel data strip by strip (no byte array of the whole image on the heap). The stitch
  decodes its own copies of the pieces and releases those; it never releases a Mat owned by Weasis's image cache
  (`ImageElement` lines 57 to 71 and 273 to 281 own and pin them). Buffer ownership, cancellation and a combined
  process-memory limit are specified in the build plan.
- Written to the viewer's temporary folder and loaded with `LoadLocalDicom`, exactly as a templating plan. Sending it
  to PACS stays the user's choice through Export, DICOM send.

To check during the build: that a hospital PACS stores and shows the 16-bit Secondary Capture with its PixelSpacing,
and that a length measured across a seam in Weasis and in the templating page agrees with the sum of the same lengths
measured on the pieces.

## 8. Hip-knee-ankle and mechanical axis (follow-up)

The templating page already computes HKA, mLDFA, MPTA and JLCA from landmarks on a long film. So the follow-up is
small: open the stitched image with Template, and later pass the stitch's own landmark guesses (femoral head centre,
knee centre, talus centre) to the page as suggestions the surgeon confirms or moves. No second angle engine in the
viewer.

But `longLegAngles` today takes any coordinates and reports angles without uncertainty, so the seam-aware part comes
before, not after, the templating hand-over (Astra, P1). Using the seam data of section 7, the page must say when
landmarks lie in different pieces and show a registration uncertainty propagated through the actual geometry
(translation, rotation and scale of each seam plus landmark placement), named as such and stated to exclude
acquisition error that was not measured. Seam rotation alone is not the HKA uncertainty: a 5 to 10 mm sideways error
at one end of a 400 mm axis turns it by about 0.7 to 1.4 degrees. The ball calibration in the page applies at the hip
only, and the page must say so on a stitched film.

## 9. Offline on a 4 GB PC (Lite edition)

- Memory: a CR piece at 0.1 to 0.15 mm is up to about 3,500 by 4,300 pixels, 30 MB as 16-bit. Registration runs at
  2, 1 and 0.5 mm per pixel, a few MB per piece, and full resolution only for small patches. Blending is done in strips
  (section 6). OpenCV Mats live outside the Java heap, so each must be released promptly; the 4 GB machine has little
  room outside the 1 GB heap either.
- Time: the matching alone may take seconds, but with CD decoding the whole job may take tens of seconds or minutes on
  an old PC (Astra: "seconds" is low confidence). To be measured on the Lite edition on a real 4 GB PC, with the
  Template page open, before release. The job runs in the background and can be cancelled.
- Bone recognition, honestly: the classical detectors above (circle fit, profiles, arcs) run anywhere, but they are
  brittle. Expect the femoral head to be found on most adult AP films and missed or misplaced with a hip prosthesis,
  a dysplastic or collapsed head, a child, heavy soft tissue or a cropped pelvis; the knee joint line to be found on most
  AP knees and confused by an arthroplasty, severe arthritis or flexion; the ankle mortise to be the least reliable.
  That is why landmarks only order, anchor and check, and never decide a seam alone.
- A learned landmark model (a small heatmap network, a few MB, 512-pixel input) would be far more robust. It needs a
  runtime the viewer does not have (no `dnn` in the shipped OpenCV; ONNX Runtime for Java adds roughly 10 to 30 MB of
  natives per platform), training data with a licence that allows it, and validation on Ethiopian hospital films. It
  would run on CPU in about a second within a few hundred MB. Not before the classical version has been measured.

## 10. Validation before it is offered

- Synthetic: cut long images into overlapping pieces with known shift, rotation, scale, exposure change and added noise;
  the tests check the recovered transform within 0.5 mm and 0.2 degrees. JUnit, as in the bones module.
- Phantom: a known-geometry phantom with markers at several depths, X-rayed in pieces on the hospital's own machines,
  gives true lengths and angles. Manual stitching is a comparator, not ground truth (Astra, P1): it repeats the same
  projection errors.
- Real: at least 30 anonymised CD cases with separate pieces, stitched by hand by Sami or a registrar as the
  comparator, plus a CT scanogram where one exists. Measure seam misalignment at the cortex, femoral and tibial length
  and HKA. Include deliberate failures: a repeat exposure, a moved patient, the other leg, a piece from another day.
  Keep some cases held back from threshold tuning. Thirty cases with no failure still leave a failure rate of up to
  about 10 percent possible, so they set thresholds; they do not prove safety.
- Acceptance: surgeons agree, before release, what length and angle errors are acceptable for osteotomy and for
  arthroplasty planning, and the tool is offered for that use only if the measurements meet them.
- Lite: memory and time on a real 4 GB Windows PC with four pieces.

## 11. Risks

1. Calibration across pieces. Each piece is magnified by its own geometry (source distance, patient to detector
   distance, which differ from pelvis to ankle). Even the machine's magnification factor is one number per piece, not
   per depth, and the ruler lies in a different plane from the bone. Using a different spacing basis from the viewer's
   own (detector spacing where Weasis shows "Magnified") would add a further 5 to 15 percent error; section 3 avoids
   that. Astra's engineering expectation, not a validated range and held with low to moderate confidence: 2 to 5
   percent length error even in cases that look satisfactory (16 to 40 mm over 800 mm), and 1 to 3 degrees on HKA
   without any visible seam fault. A wrong match has no useful bound. The validation in section 10 replaces these
   guesses with our own numbers. Lengths across seams can be off by several mm. Mitigation: the stitch
   reports the scale it estimated per seam; a ruler or ball check is offered; the DICOM says "not calibrated to the
   patient plane".
2. Parallax. Separate exposures from different tube positions project bone, ruler and skin differently at the seam; no
   single transform aligns all of them. The match is built on bone, so the ruler may look doubled at the seam. That is
   expected and should be explained on screen.
3. Patient movement between exposures (standing patients shift weight). No transform fixes it. A large residual or
   disagreeing patches mark the seam failed, but movement outside the overlap leaves the residual small (Astra), which
   is why the user confirms one acquisition and the output says lengths and angles across seams are unverified.
4. Wrong match that looks right: a shaft overlap shifted by one ruler tick or along the cortex, or built on the other
   leg. Mitigation: the aperture check, the peak-uniqueness gate, the landmark cross-check, the per-leg match, and every
   seam opening as "check" until thresholds are validated.
5. A stitched image mistaken for an original. Mitigation: SeriesDescription, burned-in label, seam ticks, derivation
   text, and no "reviewed" state in this version.
6. Mixed images: a piece from another day, the other leg or another patient. The tool accepts only one study by default
   and asks before mixing studies; it never mixes patients.
7. Memory on Lite (section 9).
8. Regulatory: the viewer is not a certified medical device. A stitched long-leg image feeds osteotomy and
   arthroplasty decisions, so the draft labels are not cosmetic and must not be switched off by a preference.

## 12. Fable's review (27 Oct 2026)

Claude Fable reviewed the first draft read-only, with the repository files and no conclusions handed to it, and
checked claims against the code. Its main finding was confirmed in the code before it was folded in
(`DicomImageElement` lines 85 to 102 and 183 to 203, `app.js` lines 512 to 527).

| | Finding | Change made |
|---|---|---|
| P1 | The draft built the output on detector spacing, but Weasis and the page measure a piece at detector spacing divided by the magnification factor when the file has one, so the stitch would measure 5 to 15 % longer than its pieces. The draft's claim that `dicom.js` sets the spacing priority was wrong: `dicom.js` only parses; `app.js dicomEstimate` decides. | Sections 2, 3, 7, risk 1: grid from the viewer's own per-piece spacing; GEOMETRY calibration type only when a factor was applied; wrong claim removed. |
| P1 | Using the ruler to fix a shaft-only overlap contradicts the "one tick off" risk: repeating ticks are the ambiguity. Seam rotation error adds straight into HKA. | 5.2: ruler numerals only; peak-uniqueness gate; rotation uncertainty as a gate; section 8 reports it. |
| P2 | 12,000 rows at 0.1 mm can reach the page's 60 MP limit and 240 MB of browser memory. | Section 6: floor 0.15 mm, cap about 32 MP. |
| P2 | Writing 100 MB of pixels in a 1 GB heap needs a streaming writer and prompt release of piece Mats. | Section 7. |
| P2 | Keep Secondary Capture; DX For Presentation needs attributes the pieces may lack. SC needs PresentationLUTShape, window, PixelPaddingValue, PatientOrientation, agreed Laterality and BodyPartExamined, a fixed series number, 16-bit clipping. | Sections 6 and 7. |
| P2 | OpenCV list right but incomplete: corner and structure-tensor functions, Sobel, Scharr, line segments and generalised Hough exist. | Section 2. |
| P2 | Log only when PixelIntensityRelationship is LIN, after MONOCHROME1 inversion; flatten VOI LUT sequences consistently. | Section 5. |
| P3 | The "long film" rule is the knee case's role guess, not a general mode. | Section 2. |
| P3 | Knee profile wording; DP seam and three-way agreement are more than phase 1 needs. | 5.1; section 6 uses a straight feathered cut in phase 1. |
| P3 | Missing risks: bilateral films, CD with both the modality's stitch and the pieces, unreliable CR acquisition times. | Sections 3, 5.1, 5.2, risk 4. |

## 12b. GPT-6 Astra's review (27 Oct 2026)

Run from this session with `codex exec -m gpt-6-astra -s read-only` on the prompt in
`DESIGN-2026-10-27-long-leg-stitching-astra-prompt.md`; it changed no files. Verdict: "revise before building", feasible
as a draft compositor, but the measurement assurances exceeded the evidence. Two of its code claims were checked before
folding in (`app.js setBall` line 3792 sets one calibration for the film; `app.js` lines 795 to 801 build a full-size
canvas copy).

| | Finding | Change made |
|---|---|---|
| P1 | A common grid is not a common anatomical scale; a fitted ±5 % scale changes lengths and accumulates; GEOMETRY applies at one depth; a hip ball cannot validate knee or ankle scale. | Section 5: rigid fit, scale locked and only checked; "no scale" output when no defensible scale; section 8: ball applies at the hip only. |
| P1 | "Several mm" understates the error; seam rotation is not HKA uncertainty. Engineering guess 2 to 5 % length, 1 to 3 degrees HKA. | Risk 1 and section 8: guess quoted as unvalidated; registration uncertainty propagated through the geometry. |
| P1 | Gates can pass a convincing wrong acquisition (movement outside the overlap, a repeat, the ruler at another depth, two perfect manual pairs). | Section 4: nothing preselected, user confirms acquisition, projection, weight bearing and target leg; 5.2: ruler and skin excluded, held-back patches; 5.4: three spread pairs with a left-out check. Fable's "leg nearer the centre" default dropped. |
| P1 | Seam-aware restrictions must come before templating; 30 manual stitches are not ground truth. | Section 7: full status, leg and limitation burned in, machine-readable seam data; section 8 reordered; section 10: phantom, deliberate failures, held-back cases, surgeon acceptance criteria. |
| P2 | Fable's spacing equivalence was too strong: the page has no SID/SOD fallback and a 0.9 to 2 range. | Sections 2 and 3. |
| P2 | Registration preprocessing and output presentation must be separate. | Section 5. |
| P2 | SC is fine but needs source SOP pairs, SpatialLocationsPreserved NO, coded derivation, algorithm version; `OpeningViewer.NONE` does not open; BurnedInAnnotation is about identifying text. | Sections 4 and 7. |
| P2 | Browser copies reach about 448 MB at 32 MP; native Mats are owned by the image cache; time may be minutes. | Sections 6, 7 and 9. |
| P3 | `img_hash` present; defer anatomical classifiers in phase 1; 600 mm exclusion should be a warning. | Sections 2, 3 and 13. |

Where Astra disagreed with Fable (spacing equivalence, logarithmic preprocessing, rotation as the HKA uncertainty, the
default leg), the draft now follows Astra.

## 13. Build plan, once Sami agrees

New bundle `weasis-dicom/weasis-dicom-stitching`, package `com.addisortho.viewer.stitching`, Apache-2.0 headers naming
Addis Ortho Trading PLC, registered in both `base.json` files and the distribution POM, like the templating bundle.

- Phase 1: the window with explicit piece selection, order, acquisition and leg confirmation; manual and
  semi-automatic matching (coarse correlation, patch RANSAC on bone, the aperture and uniqueness gates, and 5.4; no
  anatomical classifiers); a straight feathered seam; the DICOM output with full labels and seam data; synthetic tests;
  the Lite memory and time benchmark. Usable by hand from the start, as a draft image only: lengths and angles across
  seams stay labelled unverified.
- Phase 2: the seam-aware uncertainty in the templating page (section 8), the phantom and the 30-case study, and
  surgeon acceptance criteria. Only after this may a stitch be used for planning measurements.
- Phase 3: landmark detection for order, anchoring and checks (5.1, 5.3) and landmark suggestions handed to the
  templating page; a learned model only if the classical detectors are not good enough.

Work branch to be created from `main`, not from `addisortho/cd-speed-export-names`.

## 14. Phase 1 as built (27 Oct 2026)

Bundle `weasis-dicom/weasis-dicom-stitching`; `ADDISORTHO.md` lists the classes. Built as section 13 says, with these
differences, all to be closed before the tool is offered:

- Order: Move up and Move down buttons, not drag; the first order is acquisition time, then instance number.
- "On bone": patches must be brighter than the overlap's Otsu threshold and textured in two directions. Ruler and skin
  are not yet excluded; every automatic seam says so.
- The label has its own black strip above the image (after Astra's code review), so it never covers the limb; the text
  shrinks and wraps to fit a narrow film.
- "Match on": whole width, left half or right half of the image as shown. A simple stand-in for a drawn target-leg
  region; no default.
- A piece with a VOI LUT gets a note; the stitch uses a straight window (the curve is not reproduced).
- Output cap 32 megapixels, floor 0.15 mm; the Lite benchmark is still to do.
- Tests (22, JUnit): synthetic phantom pieces with known shift, rotation (up to 1.3 degrees), spacing (0.15 and 0.175 mm)
  and exposure (gain 1.6) are recovered within 0.02 mm and 0.01 degree; shaft-only and repeating-dot overlaps fail;
  manual seams need three agreeing pairs; a full write and read-back checks the DICOM and that one linear map fits phantom
  to output (correlation above 0.97). These are synthetic: they say nothing yet about real CR films.
- Not yet run inside the viewer (OSGi start, toolbar, loading the saved series). The window itself was driven from code
  with phantom pieces: confirmations gate Line up, seams come back "check", a hand nudge makes a seam semi-automatic.

### 14.1 GPT-6 Astra's code review (27 Oct 2026)

Run read-only from this session (`codex exec -m gpt-6-astra -s read-only`). It found the transform maths, pose chain and
pixel-centre mapping consistent, and three P1 and seven P2 problems. Each was checked in the code and fixed:

| | Finding | Fix |
|---|---|---|
| P1 | The chosen leg did not limit the match: the other leg or a ruler could drive it. | "Match on" band (whole, left half, right half) used by the coarse search, the edge test and the patches; changing it discards the seams. |
| P1 | Clustered or missing evidence could pass: six patches round one feature, no gate on rotation uncertainty, missing held-back patches only a warning, the median hid one bad region, background edges could pass the edge test. | Inliers must span 40 % of the overlap and two thirds of it; rotation uncertainty at most 0.15 degree; at least two held-back patches, the worst within 2 mm; held-back patches no longer overlap fitted ones; the edge test uses the bright part of the band only; manual pairs must spread 30 mm. |
| P1 | Reordering during Line up restored stale seams. | Order, ticks and band are locked while work runs; each job carries a generation number and stale results are dropped. |
| P2 | A piece without spacing was worked on a doubled grid (heap). | 0.15 mm working size for unscaled pieces. |
| P2 | Unscaled pieces lost their pixel aspect. | PixelAspectRatio kept as the viewer does. |
| P2 | VOI LUT not reproduced. | Note on the piece; reproducing the curve stays open. |
| P2 | The label could be clipped and could cover anatomy. | Own strip above the image; shrink then wrap. |
| P2 | The seam data's row ranges did not describe blended rows or gap filling. | JSON now gives each piece's pose and size, the output origin, blend rows per seam, and says a piece may fill gaps. |
| P2 | Cancel did not stop the work, and "Automatic match again" ran on the screen thread. | Loops check cancellation, the window stays busy until the work exits, the editor matches in the background. |
| P2 | PatientOrientation (Type 2C) and Type 2 patient and study attributes could be missing. | Written empty when unknown. |

Astra could not run the tests inside its read-only sandbox; they were run here after the fixes (22 pass).

