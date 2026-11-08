// A knee case's long-leg film kept at a coarser pixel (21 Oct 2026; Sami: "yes" to the reduced copy). A stitched long
// leg is 40-50 MB uncompressed, so the case reopened slowly: the whole film came down and was decoded each time. The
// long film only gives angles (hip-knee-ankle, anatomic-mechanical) and lengths scaled from the AP, so it is kept at
// about 0.3 mm a pixel: an angle over a metre of leg moves well under 0.1 degree. The full film stays in PACS.
// The copy is a DICOM (deflated explicit VR little endian, which dicom.js reads), not a picture, so the film keeps its
// pixel spacing (times the factor), its grey scale (MONOCHROME1, window, rescale) and its patient and study details.
// Same film in, same bytes out (the UIDs come from the original's sha256, no clock), so adding the same film again is
// recognised by its sha256 as before. Pure: test.mjs reads the result back with dicom.js.
import { el, concat, IMPLEMENTATION_UID } from './dicomwrite.js';

export const REDUCE_OVER = 20 * 1024 * 1024;   // a long-leg DICOM larger than this is reduced
const TARGET_PX = 6000000;                      // about 6 megapixels after reduction (12 MB before deflate)
const MAX_MM = 0.4;                             // no factor above 2 that goes past this (2 itself may, on a coarse film: Fable P3)
const SC = '1.2.840.10008.5.1.4.1.1.7', DEFLATED = '1.2.840.10008.1.2.1.99';

// the whole-number factor: at least 2, enough to reach about TARGET_PX, never past MAX_MM a pixel
export function reduceFactor(cols, rows, mm) {
  let f = Math.max(2, Math.ceil(Math.sqrt(cols * rows / TARGET_PX)));
  while (f > 2 && mm > 0 && mm * f > MAX_MM) f--;
  return f;
}
// f x f box average (each output pixel the mean of the f x f block it covers; a ragged edge is dropped)
export function boxReduce(data, cols, rows, f) {
  const nc = Math.floor(cols / f), nr = Math.floor(rows / f), out = new Float32Array(nc * nr), k = 1 / (f * f);
  for (let y = 0; y < nr; y++) for (let x = 0; x < nc; x++) {
    let s = 0;
    for (let dy = 0; dy < f; dy++) { const r = (y * f + dy) * cols + x * f; for (let dx = 0; dx < f; dx++) s += data[r + dx]; }
    out[y * nc + x] = s * k;
  }
  return { cols: nc, rows: nr, data: out };
}
const ds = v => { const s = String(Number(Number(v).toPrecision(10))); return s.length <= 16 ? s : Number(v).toExponential(8); };
const dsPair = a => `${ds(a[0])}\\${ds(a[1])}`;
async function uidFrom(hex, what) {   // 2.25 + 128 bits of sha256(original sha256 + what)
  const h = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${hex}:${what}`)));
  let v = 0n; for (let i = 0; i < 16; i++) v = (v << 8n) | BigInt(h[i]);
  return '2.25.' + v.toString();
}
async function deflateRaw(u8) {
  const s = new Blob([u8]).stream().pipeThrough(new CompressionStream('deflate-raw'));
  return new Uint8Array(await new Response(s).arrayBuffer());
}

// img: a decoded DICOM (dicom.js: data after rescale, square pixels, meta). sha: the original file's sha256 (hex).
// Returns {bytes, factor, mmFrom, mmTo, cols, rows, fromCols, fromRows} or null when the film is too small to reduce.
export async function reduceFilm(img, sha) {
  const m = img.meta || {}, sp = m.pixelSpacing || m.imagerPixelSpacing, mm = sp && sp[0] > 0 ? sp[0] : null;
  const f = reduceFactor(img.cols, img.rows, mm || 0);
  const r = boxReduce(img.data, img.cols, img.rows, f);
  if (r.cols < 64 || r.rows < 64) return null;
  let lo = Infinity, hi = -Infinity;
  for (let i = 0; i < r.data.length; i++) { const v = r.data[i]; if (v < lo) lo = v; if (v > hi) hi = v; }
  const icpt = Math.floor(lo), slope = hi - icpt <= 65535 ? 1 : (hi - icpt) / 65535;
  const px = new Uint8Array(r.data.length * 2), dv = new DataView(px.buffer);
  for (let i = 0; i < r.data.length; i++) dv.setUint16(2 * i, Math.max(0, Math.min(65535, Math.round((r.data[i] - icpt) / slope))), true);
  const sop = await uidFrom(sha, 'reduced'), series = await uidFrom(sha, 'reduced-series');
  const scaled = a => (a && a[0] > 0 && a[1] > 0 ? dsPair([a[0] * f, a[1] * f]) : null);
  const how = `Long-leg film reduced ${f} x ${f} (box average) for the knee case${mm ? `: ${ds(mm)} mm to ${ds(mm * f)} mm a pixel` : ''}; the full film is the source image (in PACS). Not for diagnosis.`;
  const opt = (tag, vr, v) => (v !== undefined && v !== null && v !== '' ? el(tag, vr, v) : null);
  const src = m.SOPInstanceUID && m.SOPClassUID ? [[el('00081150', 'UI', m.SOPClassUID), el('00081155', 'UI', m.SOPInstanceUID)]] : null;
  const body = concat([
    opt('00080005', 'CS', m.SpecificCharacterSet),
    el('00080008', 'CS', 'DERIVED\\SECONDARY'), el('00080016', 'UI', SC), el('00080018', 'UI', sop),
    opt('00080020', 'DA', m.StudyDate), opt('00080030', 'TM', m.StudyTime), opt('00080050', 'SH', m.AccessionNumber),
    el('00080060', 'CS', m.Modality || 'OT'), el('00080064', 'CS', 'WSD'), opt('00080080', 'LO', m.InstitutionName),
    opt('00081030', 'LO', m.StudyDescription), el('0008103E', 'LO', `${m.SeriesDescription ? String(m.SeriesDescription).trim().slice(0, 40) + ' ' : ''}(reduced ${f}x)`),
    el('00082111', 'ST', how), src ? el('00082112', 'SQ', src) : null,
    opt('00100010', 'PN', m.PatientName), opt('00100020', 'LO', m.PatientID), opt('00100021', 'LO', m.IssuerOfPatientID),
    opt('00100030', 'DA', m.PatientBirthDate), opt('00100040', 'CS', m.PatientSex), opt('00101010', 'AS', m.PatientAge),
    opt('00180015', 'CS', m.BodyPartExamined), opt('00181110', 'DS', m.DistanceSourceToDetector), opt('00181111', 'DS', m.DistanceSourceToPatient),
    opt('00181114', 'DS', m.EstimatedRadiographicMagnificationFactor), opt('00181164', 'DS', scaled(m.imagerPixelSpacing)), opt('00185101', 'CS', m.ViewPosition),
    opt('0020000D', 'UI', m.StudyInstanceUID), el('0020000E', 'UI', series), opt('00200010', 'SH', m.StudyID), el('00200013', 'IS', '1'),
    opt('00200020', 'CS', m.PatientOrientation), opt('00200060', 'CS', m.Laterality), opt('00200062', 'CS', m.ImageLaterality),
    el('00204000', 'LT', how),
    el('00280002', 'US', 1), el('00280004', 'CS', img.invert ? 'MONOCHROME1' : 'MONOCHROME2'),
    el('00280010', 'US', r.rows), el('00280011', 'US', r.cols), opt('00280030', 'DS', scaled(m.pixelSpacing)),
    el('00280100', 'US', 16), el('00280101', 'US', 16), el('00280102', 'US', 15), el('00280103', 'US', 0),
    opt('00280A02', 'CS', m.PixelSpacingCalibrationType), opt('00280A04', 'LO', m.PixelSpacingCalibrationDescription),
    opt('00281050', 'DS', m.WindowCenter), opt('00281051', 'DS', m.WindowWidth),
    el('00281052', 'DS', ds(icpt)), el('00281053', 'DS', ds(slope)),
    el('7FE00010', 'OW', px),
  ].filter(Boolean));
  const metaEls = [el('00020001', 'OB', new Uint8Array([0, 1])), el('00020002', 'UI', SC), el('00020003', 'UI', sop),
    el('00020010', 'UI', DEFLATED), el('00020012', 'UI', IMPLEMENTATION_UID), el('00020013', 'SH', 'AOC_TPL_1')];
  const group = concat(metaEls);
  const bytes = concat([new Uint8Array(128), new TextEncoder().encode('DICM'), el('00020000', 'UL', group.length), group, await deflateRaw(body)]);
  return { bytes, factor: f, mmFrom: mm, mmTo: mm ? mm * f : null, cols: r.cols, rows: r.rows, fromCols: img.cols, fromRows: img.rows };
}
