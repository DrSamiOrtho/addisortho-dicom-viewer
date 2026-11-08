# Long-leg stitch quality: case LL-001, seam by seam (7 Nov 2026)

Phase 0 and 1 of `FABLE-HANDOFF-2026-11-07-long-leg-stitching.md`. Read-only: no production code changed. Code line
`addisortho/stitch-seams` at `0c04773ae`, worked on in the worktree `.worktrees/stitch-quality` (branch
`addisortho/stitch-quality-fable`). The only file added is the development harness
`weasis-dicom-stitching/src/test/.../RealCaseHarnessTest.java`.

## The case

Bilateral standing long leg on a DRGEM GXR wall stand: four DX pieces of 3072 x 3072 (the ankle piece 3036 wide),
0.139 mm, MONOCHROME1, PixelIntensityRelationship LIN, PixelSpacing = ImagerPixelSpacing, no magnification factor.
Pelvis 78 kVp, femur 70, knee 68, ankle 58; the machine's processing differs per body part ("GB" parameters in
AcquisitionDeviceProcessingDescription). A calibration ball sits at the top left of every piece at the same pixel
position: it is fixed to the stand, not to the patient. Instance 5 is the machine's own stitch, 7880 x 2966 at the same
spacing, ImageType ORIGINAL\PRIMARY and SeriesDescription "ANKLE", so the viewer does not recognise it as a stitch.

The opened result was made with target leg Both on the whole width and saved as UNVERIFIED: all three seams failed.
Output 3197 x 8136 at 0.139 mm (no scale written), label strip of 244 rows. Everything is kept outside Git in
`~/Documents/Claude/Projects/ao-viewer-cases/LL-001/` (pieces, machine stitch, our stitch, seam crops at 100 % and
200 %, checkerboards, harness log, analysis scripts). File names carry no patient name; the DICOM headers do.

## Method

1. Our seam transforms from the private seam data of the saved DICOM; the harness reproduces them exactly and
   deterministically (0.3 to 0.7 s per seam after a 0.4 s preparation on this Mac).
2. The machine's geometry: each piece, and each half of each piece, registered to the machine stitch on a 0.97 mm
   grid (masked normalised cross-correlation on gradient magnitude, rotation sweep to 0.05 deg). Correlation 0.84 to
   0.93; the two halves of every piece agree with each other within 0.1 deg and 1 mm, so the machine placed each piece
   rigidly and its placement is a fair comparator, not ground truth.
3. Residual bone displacement per leg and per height inside each overlap, under our transform and under the
   machine's, by local correlation of 150 x 44 mm windows on a 0.56 mm grid.
4. Grey step across each cut: mean of the 150 rows above the blend band against the 150 rows below, per column.

## Seam by seam

| | Seam 1: pelvis to femur | Seam 2: femur to knee | Seam 3: knee to ankle |
|---|---|---|---|
| Overlap | 151 mm, femoral shafts only | 121 mm, knee joints | 343 mm, whole tibiae |
| Ours (lower to upper) | shift 10.0, 276.0 mm, rotation 0 (the rough 2 mm-grid position; the fit had 5 points) | shift -1.2, 310.3 mm, rotation -1.17 deg, 32 points | shift 3.6, 83.8 mm, rotation +0.34 deg, 59 points |
| Machine-implied | shift 11.6, 276.3 mm, rotation +0.05 deg | shift 0.7, 307.2 mm, rotation +0.05 deg | shift 2.3, 84.7 mm, rotation +0.30 deg |
| Difference | 1.6 mm sideways, 0.3 mm along | 1.2 deg; 3 mm along the bone at the piece's left edge | 0.04 deg, 0.4 mm |
| Why it failed | uniqueness 1.08; shaft only (edge ratio below 0.08); 5 points, all on the gluteal skin fold, and they slid 47 mm along the shaft; held-back median 5.9 mm | 16 held-back points disagree, median 3.2 mm; both halves alone also failed (left -1.11 deg, right +1.31 deg, hold-out 2.4 and 2.8 mm, rival positions) | whole width passed (check, hold-out median 1.0 mm); Both requires each half to pass on its own and the right half failed its hold-out (median 2.3 mm, rotation 0.60 deg) |
| Visible at 100 % | lateral cortex step of about 1 to 1.5 mm on both femora; strong texture and grey band (pelvis exposure above, femur below) | joint-line step of 2 to 3 mm on both knees; opposite directions on the two knees | no visible step; slight tone change |
| Grey step below minus above (of 65000) | median -1762, 10th percentile -5616 | median -629, spread -2086 to +2155 | median -151 |
| Cut | 129 mm below the overlap's top, feathered 10 mm | 101 mm below the top: the proximal tibia, 16 mm above the bottom of piece 2 | 52 mm below the top |

## Classification

**Seam 1: cut and exposure, with a 1.6 mm registration error from grid quantisation.** The overlap holds only two
straight shafts; the fine match has nothing to fix the shift along the bone and the five points it found lie on skin.
The gates are right to fail it. The position used is the rough 2 mm correlation peak, and that peak is good: within
0.3 mm of the machine along the bone (the thigh outlines carry that) but 1.6 mm off sideways, which is the 2 mm grid
without sub-cell refinement. The dominant visible defect is the exposure band: a single gain and offset cannot map the
pelvis processing onto the femur processing (soft tissue and bone step by different amounts), and the pelvis piece's
sharper texture meets the smoother femur piece at a hard line inside a 10 mm feather.

**Seam 2: registration, caused by parallax absorbed as rotation.** Between the femur and the knee exposure the tube
travelled about 310 mm, so structures of the knee at different depths, and the two knees at different depths, shift by
different amounts. The rigid fit buys a lower residual by tilting 1.17 deg; each knee alone tilts about 1.2 deg in
opposite directions (the medial and lateral sides of a knee are at different depths), which no detector on a wall
stand does. The machine keeps the pieces level and accepts about 1 to 3 mm of disagreement per leg. Our tilt is
chained into pieces 3 and 4: the tibiae and ankles are turned by about 1 deg relative to the femora, roughly 8 mm
sideways at the ankle, and the image looks less upright than the machine's. The hold-out gate caught it (failed),
but the failed fit's rotation was still used for the saved picture.

**Seam 3: policy, not registration.** The whole-width fit agrees with the machine to 0.04 deg and 0.4 mm and its
hold-out passes. The right leg alone drifted to a 0.60 deg solution with a 2.3 mm hold-out, and the Both rule fails
the seam when either half fails. Correct by design (Kimi's second review); nothing to change here now.

**Cut placement across the three overlapping pieces (seams 2 and 3): cut.** The cut cost is pure bone straightness
anywhere in the allowed rows, so seam 2 went to the bottom of its overlap and seam 3 near the top of its very long
overlap. The knee piece contributes 35 mm; the knee joint comes from the femur exposure and the whole tibia from the
ankle exposure (58 kVp, the darkest and noisiest piece), hence the tone change and the loss of the knee piece's
better-exposed joint.

**Feathering:** no double edges beyond what the registration errors above explain. Not a cause here.

**Output scale:** the output is on the pieces' own 0.139 mm grid; no softening. Not a cause.

**Parallax and movement:** present at seams 2 and 3 (1 to 3 mm between legs, and between the sides of one knee).
It limits any rigid stitch of this acquisition; the right response is to keep the pieces level and say so, not to
tilt them.

## Smallest code change recommended (not yet made)

1. `SeamMatcher.judge`: a failed automatic seam keeps a translation-only position, never the failed fit's rotation.
   Use the rough position refined to sub-cell on the 1 mm intensity (a translation-only correlation within 2 mm of the
   rough peak), or the inliers' translation-only fit when there are enough of them. Reasons say the rotation was not
   applied and why. Effect on this case: seam 2 becomes level, seam 1 moves 1.6 mm sideways onto the cortex.
2. `Cut.find`: among clean candidate rows prefer the middle of the overlap (a mild distance penalty), so three
   overlapping pieces share the limb; the ball exclusion and the straightness test stay.
3. `SeamEditor`: a resizable window with the aligned view taking the space (Sami's request of 7 Nov), so manual
   correction can be judged on a large screen.

Later, only after 1 and 2 are checked on this case: a monotone (quantile) grey mapping fitted on the rows around the
cut instead of one global gain and offset, for the seam 1 band.

## Effect on lengths, angles and calibration

Change 1 touches only seams that are already failed, which are saved unverified and without scale, so it cannot change
any measurable output. It makes the unverified picture more truthful (level pieces). Change 2 moves cut rows, not
transforms: lengths and angles are unaffected; it changes which piece's pixels show. Change 3 is UI only. None of
them relaxes a gate, adds scale fitting, warping or a homography, or enables Template on an unverified stitch.

## Before and after test

- Harness on LL-001 before and after: per seam, transform, status, reasons, points; checkerboards; the output DICOM.
  Pass: seam 2 rotation 0 and shift within 1 mm of the machine's; seam 1 sideways within 0.5 mm of the machine's;
  seam 3 unchanged; all three still failed under Both; the saved picture still says UNVERIFIED and carries no scale.
- Synthetic: existing `SeamMatcherTest` unchanged plus a new test where a failed seam's transform has no rotation,
  and a `CutTest` for three overlapping pieces (the middle one keeps a fair share).
- Visual: seam crops at 100 % and 200 % at identical window on the new output; the knee joint from the knee piece.
- Not covered by these: whether the single-leg path (target leg Left, left half) can pass seam 2 on this case. Today
  it fails (hold-out 2.4 mm from within-knee parallax). That needs a decision on whether a knee overlap of this kind
  should pass with a translation-only fit and a stated parallax, or stay a manual seam.

## Remaining limitations of the acquisition

Tube travel of about 310 mm between femur and knee exposures makes a millimetre-level rigid match of the knee
impossible in principle; the machine does not achieve it either (its own stitch has 1 to 3.7 mm of residual per leg at
seam 2). A standing bilateral film adds a second depth. The pelvis-to-femur overlap holds no structure that fixes the
shift along the bone, so seam 1 will always need either the thigh outline (as the rough match uses) or manual points.
The ankle exposure at 58 kVp is the darkest piece; wherever it supplies the tibia the tone will differ.

## After the three changes (stitch 0.4, same day)

Changed: `SeamMatcher` (a failed automatic seam is placed at the rough position refined by shift only on the 1 mm
gradient, `levelShift`, never at the failed fit; the reason says so and the fit's residual and uncertainty are not
reported for a transform that was not fitted; a hand-set seam that fails stays where the hand put it), `Cut`
(`MIDDLE_PULL` 0.3 added to the straightness score in proportion to the distance from the middle of the allowed
rows), `SeamEditor` (90 % of the screen, resizable), `StitchWriter` (version string 0.4), `MatchImage`
(`GRADIENT_CLIP` shared). Tests: `SeamMatcherTest.aFailedSeamKeepsNoRotation`, `aRefusedFitLeavesNoTilt`,
`CutTest.cutsALongStraightOverlapNearItsMiddle`; `cutsTheShaftNotTheBallOrTheCondyle` now expects the lowest shaft
row outside the ball's margin. `RealCaseHarnessTest` also writes the Both-legs stitch.

Commands run, all from `.worktrees/stitch-quality`:
`mvn -o -B -ntp -pl weasis-dicom/weasis-dicom-stitching test` (42 tests, 0 failures, 1 skipped: the harness without
its folder); `STITCH_CASES=~/Downloads STITCH_OUT=<scratch> mvn -o -B -ntp -pl weasis-dicom/weasis-dicom-stitching test
-Dtest=RealCaseHarnessTest -Dsurefire.failIfNoSpecifiedTests=false`; `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -o
-pl weasis-dicom/weasis-dicom-stitching spotless:apply` and `spotless:check`.

| | Seam 1 | Seam 2 | Seam 3 |
|---|---|---|---|
| Before (0.3e) | 10.0, 276.0 mm, 0 deg | -1.2, 310.3 mm, -1.17 deg | 3.6, 83.8 mm, +0.34 deg |
| After (0.4) | 10.1, 276.1 mm, 0 deg | -0.4, 306.0 mm, 0 deg | unchanged |
| Machine | 11.6, 276.3, +0.05 | 0.7, 307.2, +0.05 | 2.3, 84.7, +0.30 |
| Residual per leg after | shaft only, not measurable | left leg within 1 mm at three heights; right leg 0.6, 2.5, 2.6 mm lower (parallax, no tilt) | unchanged |
| Cut, mm below the overlap's top | 85 (was 129) | 105 (was 101) | 171 (was 52): mid-tibia |
| Status | failed, unverified, no scale | failed, unverified, no scale | failed under Both (right half) |

Output 3145 x 8125 at 0.139 mm, written in 1.5 s; the legs stand as in the machine's stitch. A direct per-femur
correlation at seam 1 gives 10.1 and 9.8 mm sideways, so the earlier "1.6 mm off" was the machine's choice, not an
error of ours; the visible cortex step at seam 1 is the 1.6 % scale difference between the pelvis and femur
exposures, which the stitch does not apply by design. The knee piece now supplies 150 mm of proximal tibia instead
of 35 mm. The tone change moved with the cut to mid-tibia (knee exposure above, 58 kVp ankle exposure below): the
exposure mapping is the next item, untouched here.

Not verified: the larger seam editor in the running viewer (needs an app build), the single-leg path on this case
(still fails seam 2 on hold-out), PACS, Template gating and the 4 GB PC. Nothing is committed.

## Stitch 0.5: the grey step at the cuts (same evening)

Measured first on the 0.4 output: with one gain and offset per piece the overlap still differed by up to 8 % of the
range at the cuts, soft tissue and bone by different amounts, because the machine processes each exposure with its own
curve. A Python prototype of a two-band blend (tone from the 2 mm copy blurred by 8 mm, feathered over 60 mm; detail
over the existing 10 mm) removed the band at seam 3 on both legs and softened seam 1, with the cortex detail unchanged.

Implemented in `Compositor` (`TONE_MM` 60, `TONE_SIGMA_MM` 8; the tone feather is clamped to what the overlap allows on
either side of the cut, so seam 2, cut 16 mm above the bottom of the femur piece, blends its tone over 15 mm) and
`Gray.blurred` (NaN-aware separable Gaussian). The preview uses the same blend. Seam data carries `toneRows` beside
`blendRows`. Version string 0.5. Test `CompositorTest`: two exposures whose curves differ so that no linear map joins
them (soft tissue up, bone down) meet with a step at the cut under 0.3 of the difference 40 mm away.

| Local step across the cut, 3 to 6 mm either side, median of the width (p90) | Seam 1 | Seam 2 | Seam 3 |
|---|---|---|---|
| 0.4 | 1444 (3323) | 937 (2154) | 729 (1725) |
| 0.5 | 976 (2728) | 544 (2126) | 322 (1741) |

The transforms and cut rows are those of 0.4; nothing moved. 43 tests pass, Spotless passes. Not verified in the
running app yet: the app copy in `~/Applications` carries the 0.5 bundle and a new build stamp so its plug-in cache
refreshes on the next start. Still open: the texture difference at seam 1 (pelvis processing sharper than femur), which
no tone blend touches; a finer rendering in the seam editor.
