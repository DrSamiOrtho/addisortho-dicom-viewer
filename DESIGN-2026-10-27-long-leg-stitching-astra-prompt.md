# Prompt for GPT-6 Astra in Codex: second read of the long-leg stitching design

Paste everything below the line into Codex with GPT-6 Astra selected, working directory
`~/Documents/Claude/Projects/addisortho-dicom-viewer`.

---

You are giving an independent second read of a design draft. It was written by Claude and reviewed once by Claude
Fable; I want your own judgement, not agreement with theirs. Read-only: do not edit, create, stage or commit any file,
and do not switch branches.

Repository: `~/Documents/Claude/Projects/addisortho-dicom-viewer`, a fork of the Weasis 4.7.3 DICOM viewer (Java 25,
OSGi, Swing, OpenCV through `weasis-core-img` and `weasis-opencv`). Addis Ortho Trading PLC distributes it to
Ethiopian hospitals. It runs offline on Windows PCs, some with 4 GB of RAM (Lite edition, 1 GB Java heap), and on
Macs. It is not a certified medical device; every output of a new feature must say draft and automatic,
semi-automatic or manual, and not reviewed.

The draft: `DESIGN-2026-10-27-long-leg-stitching.md` at the repository root. It proposes stitching two to four
overlapping CR or DX long-leg pieces from patient CDs into one derived DICOM image in the same study, with automatic
registration (overlap matching plus bone landmark recognition), a manual fallback, and an output whose pixel spacing
lets the viewer's length and angle tools and the X-ray templating page work. Section 12 holds Fable's review and what
was changed because of it.

Read first: `CLAUDE.md`, `ADDISORTHO.md`. Then check the draft's claims against the code yourself:

- `weasis-dicom/weasis-dicom-templating/src/main/java/com/addisortho/viewer/templating/` (Template button rule,
  how a derived DICOM is loaded back into the study);
- `weasis-dicom/weasis-dicom-templating/src/main/resources/templating-web/` `dicom.js` (spacing), `autoplan.js`
  (`longLegAngles`), `app.js` (long-film detection, calibration ball);
- how Weasis reads pixel spacing and calibration for measurements (`weasis-dicom-codec`, `weasis-core`,
  `DicomImageElement` and related), including for Secondary Capture and for ImagerPixelSpacing;
- which OpenCV functions really ship: the natives under `~/.m2/repository/org/weasis/thirdparty/org/opencv/` and the
  wrappers in `~/.m2/repository/org/weasis/core/weasis-core-img/5.0.0/weasis-core-img-5.0.0.jar`.

Focus on what a second reader is most likely to catch:

1. Measurement correctness: magnification differing between pieces, parallax at the seams, what PixelSpacing and the
   calibration attributes of the output should honestly claim, and whether a length or angle measured across a seam
   can mislead. Give the expected error ranges you believe are realistic and say how sure you are.
2. Registration failure modes that produce a wrong but convincing image (shaft-only overlap, repeated ruler ticks,
   patient movement, a piece from another acquisition), and whether the proposed confidence gates catch them.
3. DICOM: SOP class choice (Secondary Capture versus a derived DX or CR), Modality, ImageType, SourceImageSequence,
   DerivationDescription or a derivation code, burned-in annotation, and whether hospital PACS and other viewers will
   show and measure it correctly.
4. Memory and native-memory behaviour on the 4 GB Lite edition, and CPU time on an old PC.
5. Clinical safety and labelling: is the draft labelling enough, and is anything missing before a surgeon could use a
   stitched image to plan an osteotomy or a knee replacement.
6. Scope: anything over-engineered for a first version, or anything missing that would block it.

Report a prioritised list (P1 must change before building, P2 should change, P3 minor), each item with its evidence
(file and line, or the command you ran and what it showed) and a concrete change to the design. Say plainly where you
disagree with the draft or with Fable's review. Under about 1,000 words, plain prose, no em dashes.
