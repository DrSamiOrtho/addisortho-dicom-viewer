// Saved projects and the templated-patients list (round 6, 13 Oct 2026). Sami, 13 Oct: "we should be able to save the
// project and upload project for easy adjustment" and "also record patients templated and keep list". Binding contract:
// CONTRACT-2026-10-13-projects.md (sections 2, 4 and 5 for the page; 6 is the server as built, 7 the page as built), after
// Astra's review (REVIEW-ASTRA-2026-10-13-design.md).
//   The project file (.aotp): a ZIP (zip.js) of project.json and the original image, byte for byte, plus case.json (the
//   case on t.addisortho.com it was saved as, so reopening the file and saving again updates that case instead of making a
//   second one). The whole file is checked before anything on the page changes: format, version, sizes, the image's
//   sha256, every enum and count, every coordinate inside the image (with a margin), the scale's range. The file is
//   confidential: it carries the original X-ray with its DICOM details even when the name is left out; the flattened
//   picture (Save picture) is what is shared outside.
//   t.addisortho.com only (the standalone build): every save also goes to the patient list through the gate's /api/ (the
//   image sent once per case in 2 MB parts, resumed after a dropped connection, then the project with the revision it was
//   based on), and the start card lists the templated patients (search, newest first, More), each opening its case.
// The plain parts (file format and checks) touch no page or network: test.mjs runs them in Node. The page parts start with
// initProjects(host), host being app.js's own functions and state.
import { zipParts, unzip, crc32, ZipError } from './zip.js';

export const FORMAT = 'addis-ortho-templating-project';
export const IMAGE_MAX = 80 * 1024 * 1024;          // contract 3: the original image (80 MB from r24: a stitched long-leg DICOM is 40-50 MB uncompressed)
export const PIXELS_MAX = 60000000;                  // contract 3: 60 megapixels
export const PROJECT_MAX = 2 * 1024 * 1024;          // contract 3: project.json
export const ZIP_MAX = 85 * 1024 * 1024;             // contract 5: an .aotp expanded
export const FILE_MAX = ZIP_MAX + 1024 * 1024;       // an .aotp on disk (stored entries and their headers)
export const MIME_EXT = { 'application/dicom': 'dcm', 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp' };
export class ProjectError extends Error {}
// A knee case (release 1, 14 Oct 2026; DESIGN-2026-10-14-knee-case.md with its binding amendments, contract section 12):
// one patient, one knee, up to four films, each its own image, calibration and landmarks, and one shared plan. Project
// format version 2: project.json {…, v: 2, case: {cid, kind: "knee"}, films: {role: {image, state, qc}}, active, shared}
// and one ZIP entry per film (image-<role>.<ext>), at most 6 entries. Version 1 (one image) is read as before.
export const ROLES = ['ap', 'lat', 'sky', 'long'];
export const ROLE_NAME = { ap: 'AP', lat: 'Lateral', sky: 'Skyline', long: 'Long leg' };
export const ROLE_WF = { ap: 'kneeAP', lat: 'kneeLat', sky: 'kneeSky', long: 'kneeLong' };
export const CASE_IMAGES_MAX = 200 * 1024 * 1024;   // a case's images together (each at most IMAGE_MAX)
export const CASE_PIXELS_MAX = 160000000;            // and their pixels together (each at most PIXELS_MAX)
export const CASE_ZIP_MAX = CASE_IMAGES_MAX + PROJECT_MAX + 1024 * 1024;   // a version 2 .aotp expanded
export const CASE_FILE_MAX = CASE_ZIP_MAX + 1024 * 1024;                   // and on disk
const CID_RE = /^[0-9a-f]{16}$/;
// the case key (contract section 12): what the automatic save, the kept changes and the tab's case map call a case by.
// A knee case: "kc" and its own draft id, made when the case starts and kept in its project ever after (never an image's
// hash: one case has several images). A film of one image: its image's sha256, as before.
export const caseKey = cid => 'kc' + cid;
const CK_RE = /^(?:[0-9a-f]{64}|kc[0-9a-f]{16})$/;
// The library id of a page with no template file at all (a pending-only account on t.addisortho.com; contract 9, Astra's
// page re-review, finding 7): sso_lib.php serves it as library.json's libraryId (TPL_NO_LIBRARY), and a project saved there
// carries it (or the id of the plan it opened). It passes the libraryId check below like any build's id.
export const NO_LIBRARY = 'no-templates';
const ID_RE = /^[0-9a-f]{16}$/, SHA_RE = /^[0-9a-f]{64}$/;
const MB = n => `${Math.round(n / 1048576)} MB`;

export async function sha256hex(u8) {
  const d = new Uint8Array(await crypto.subtle.digest('SHA-256', u8));
  let s = ''; for (const b of d) s += b.toString(16).padStart(2, '0');
  return s;
}

// ---------- the image: its kind and, where its header says so cheaply, its size in pixels (before decoding) ----------
function jpegSize(u) {
  let i = 2;
  while (i + 9 < u.length) {
    if (u[i] !== 0xFF) return {};
    const m = u[i + 1];
    if (m === 0xFF) { i++; continue; }
    if (m === 0xD8 || m === 0x01 || (m >= 0xD0 && m <= 0xD7)) { i += 2; continue; }
    if (m >= 0xC0 && m <= 0xCF && m !== 0xC4 && m !== 0xC8 && m !== 0xCC) return { height: (u[i + 5] << 8) | u[i + 6], width: (u[i + 7] << 8) | u[i + 8] };
    i += 2 + ((u[i + 2] << 8) | u[i + 3]);
  }
  return {};
}
function webpSize(u) {
  const tag = String.fromCharCode(...u.subarray(12, 16));
  if (tag === 'VP8X') return { width: 1 + (u[24] | (u[25] << 8) | (u[26] << 16)), height: 1 + (u[27] | (u[28] << 8) | (u[29] << 16)) };
  if (tag === 'VP8 ' && u.length > 30) return { width: (u[26] | (u[27] << 8)) & 0x3FFF, height: (u[28] | (u[29] << 8)) & 0x3FFF };
  if (tag === 'VP8L' && u.length > 25) return { width: 1 + (u[21] | ((u[22] & 0x3F) << 8)), height: 1 + ((u[22] >> 6) | (u[23] << 2) | ((u[24] & 0x0F) << 10)) };
  return {};
}
// {kind: 'dicom' | 'png' | 'jpeg' | 'webp' | null, mime, width?, height?}. A file without the DICM preamble is DICOM when
// its name or the stated type says so (an archive file named by its UID, a .dcm from a CD).
export function sniffImage(u, name = '', mime = '') {
  const s = (o, n) => String.fromCharCode(...u.subarray(o, o + n));
  if (u.length > 132 && s(128, 4) === 'DICM') return { kind: 'dicom', mime: 'application/dicom' };
  if (u.length > 24 && u[0] === 0x89 && s(1, 3) === 'PNG') return { kind: 'png', mime: 'image/png', width: ((u[16] << 24) | (u[17] << 16) | (u[18] << 8) | u[19]) >>> 0, height: ((u[20] << 24) | (u[21] << 16) | (u[22] << 8) | u[23]) >>> 0 };
  if (u.length > 3 && u[0] === 0xFF && u[1] === 0xD8 && u[2] === 0xFF) return { kind: 'jpeg', mime: 'image/jpeg', ...jpegSize(u) };
  if (u.length > 30 && s(0, 4) === 'RIFF' && s(8, 4) === 'WEBP') return { kind: 'webp', mime: 'image/webp', ...webpSize(u) };
  if (mime === 'application/dicom' || /\.(dcm|dicom)$/i.test(name) || /^[^.]+$/.test(name)) return { kind: 'dicom', mime: 'application/dicom' };
  return { kind: null, mime: '' };
}
export const isZip = u => u.length > 4 && u[0] === 0x50 && u[1] === 0x4B && u[2] === 0x03 && u[3] === 0x04;
export const tooManyPixels = (w, h) => `This image is ${w} × ${h} pixels (${(w * h / 1e6).toFixed(0)} megapixels); the page takes up to ${PIXELS_MAX / 1e6}. Save a smaller copy of the film and open that.`;

// ---------- checking a project (contract 5): everything, before the film on screen is replaced ----------
// the knee case's long leg and skyline films (contract section 12) have no implants; their landmarks are the case's own
// (condW, platW: the femoral condylar and tibial plateau widths; patThick: patella thickness; patW, trochAnt: the skyline's
// patella and anterior condyle lines)
const WFS = { hip: ['cupR', 'stemR', 'cupL', 'stemL'], kneeAP: ['femur', 'tibia'], kneeLat: ['femur', 'tibia'], kneeLong: [], kneeSky: [], shoulder: ['humerus'], diaph: [] };   // r33: the shoulder; r39: the diaphyseal plan (no implant placed)
// r39: the procedure a plan type is saved under (the patient list and projects_lib.php), and its word in the list
export const procOf = wf => (wf === 'hip' ? 'hip' : wf === 'shoulder' ? 'shoulder' : wf === 'diaph' ? 'diaphysis' : 'knee');
const jointOf = p => (p === 'knee' ? 'knee' : p === 'shoulder' ? 'shoulder' : p === 'diaphysis' ? 'diaphysis' : 'hip');
// r39: the diaphyseal plan's typed values (app.js diaIn reads them field by field): choices, and numbers in mm or null
export const DIA_ENUM = { bone: ['', 'femur', 'tibia'], view: ['', 'AP', 'LAT'], src: ['', 'MRI', 'CT'], refEnd: ['', 'prox', 'dist'] };
export const DIA_NUM = { ext1: [0, 600], ext2: [0, 600], margin: [0, 100], dN: [1, 60], lN: [1, 400], dF: [1, 60], lF: [1, 400], dBody: [1, 80], other: [10, 900] };
export const diaOk = v => isObj(v) && Object.keys(v).every(k => Object.prototype.hasOwnProperty.call(DIA_ENUM, k) || Object.prototype.hasOwnProperty.call(DIA_NUM, k))
  && Object.entries(DIA_ENUM).every(([k, l]) => v[k] === undefined || l.includes(v[k]))
  && Object.entries(DIA_NUM).every(([k, [lo, hi]]) => v[k] === undefined || v[k] === null || (typeof v[k] === 'number' && Number.isFinite(v[k]) && v[k] >= lo && v[k] <= hi));
const LM_KEY = /^(?:(?:td|lt|head|canal|ac|gt)[RL]|hip|femShaft|knee|condMed|condLat|platMed|platLat|ankle|tibShaft|antCortex|postCond|platAnt|platPost|tibAxis|condW|platW|patThick|patW|trochAnt|sulcus|patApex|postCondSky|humHead|gtub|humShaft|diaAxis|diaRef|diaFar|diaPhys[NF]|diaCan[NF][1-3]|otherKnee)$/;   // r33: the shoulder's; r39: the diaphyseal plan's; r44: the other femur's condyles (total femur)
const ORIENT_FROM = ['header', 'marker', 'assumed', 'user'];
const SETTINGS = { cupRule: ['anatomic', 'teardrop', 'head', 'mirror'], legTarget: ['equal', 'keep'], mirrorRef: ['native', 'plan'], offsetTarget: ['auto', 'own', 'none'], stemRule: ['cup', 'offset'] };
const isObj = v => !!v && typeof v === 'object' && !Array.isArray(v);
const isNum = v => typeof v === 'number' && Number.isFinite(v);
const isStr = (v, max) => typeof v === 'string' && v.length <= max;
const ROT_LIM = 20 * Math.PI / 180 + 1e-6;   // the page's own limit for turning the film (app.js ROT_MAX)
// Revision and conversion (14-15 Oct 2026; DESIGN-2026-10-14-revision-conversion.md with its binding amendments, contract
// section 13): the case type of the operative plan, and per film the existing-implant regions (bounded vector polygons and
// strokes, with the image they belong to) and the revision record (how each canal line was made, the old implant's tip,
// holes or defects, the osteotomy, the fixation zone, the revision cup's target, each reference's label, the knee's
// joint-line target). Limits keep a film's record small inside the 2 MB project.
export const CASE_TYPES = ['primary', 'conversion', 'revision'];
export const EXIST_MAX = { regions: 64, pts: 400, total: 6000 };
const EXIST_CLS = ['hardware', 'cement', 'unknown', 'ball', 'marker', 'clear'];
const REF_LABELS = ['native', 'implant', 'obscured'];
const SCALE = [0.005, 5];                    // mm per pixel: a 0.005 mm pixel or a 5 mm one is not an X-ray

// The plan values' provenance (16 Oct 2026, DESIGN-2026-10-16-knee-defaults.md; contract section 12c): per value
// {o: 'assumed' | 'surgeon' | 'unknown', rv: boolean, v?: number} (origin and review kept apart), and asked: the films the
// plan-values question was answered for (at most 80 characters). Missing provenance on a stored value is read as unknown.
const TIB_REFS = ['less', 'medial', 'worn'], PV_KEYS = ['femDistal', 'tibial', 'slope', 'vca', 'rot'];
function provProblems(prov, asked) {
  const out = [];
  if (asked !== undefined && asked !== null && !(typeof asked === 'string' && asked.length <= 80)) out.push('the plan values\' question record is not valid');
  if (prov === undefined || prov === null) return out;
  if (!isObj(prov)) return [...out, 'the plan values\' provenance is not valid'];
  for (const [k, p] of Object.entries(prov)) if (!PV_KEYS.includes(k) || !isObj(p) || !['assumed', 'surgeon', 'unknown'].includes(p.o) || typeof p.rv !== 'boolean' || (p.v !== undefined && p.v !== null && !isNum(p.v))) out.push(`the provenance of plan value ${String(k).slice(0, 12)} is not valid`);
  return out;
}
const provNorm = prov => (isObj(prov) ? Object.fromEntries(Object.entries(prov).filter(([k, p]) => PV_KEYS.includes(k) && isObj(p) && ['assumed', 'surgeon', 'unknown'].includes(p.o)).map(([k, p]) => [k, { o: p.o, rv: p.rv === true, ...(isNum(p.v) ? { v: p.v } : {}) }])) : {});


// What is wrong with a project's state for an image of W x H pixels: a list of short sentences (empty: nothing).
// Coordinates may lie a little outside the image (a point clicked or a ruler dragged onto the black beside the film): up
// to half its longer side.
export function stateProblems(st, W, H, patient = null) {
  const out = [], bad = m => { if (out.length < 12) out.push(m); };
  if (!isObj(st)) return ['the plan is missing'];
  // the whole state first: depth, sizes and the kinds of value JSON can carry
  let nodes = 0;
  const walk = (v, d) => {
    if (++nodes > 400000) throw new ProjectError('the plan is too large');
    if (d > 12) throw new ProjectError('the plan is nested too deeply');
    if (typeof v === 'string') { if (v.length > 4000) throw new ProjectError('the plan has a text that is too long'); return; }
    if (Array.isArray(v)) { if (v.length > 50000) throw new ProjectError('the plan has a list that is too long'); for (const x of v) walk(x, d + 1); return; }
    if (isObj(v)) { for (const k of Object.keys(v)) { if (k.length > 80) throw new ProjectError('the plan has a name that is too long'); walk(v[k], d + 1); } }
  };
  try { walk(st, 0); } catch (e) { return [e.message]; }
  const L = Math.max(W, H), M = 0.5 * L;
  const inside = (p, what) => { if (!isObj(p) || !isNum(p.x) || !isNum(p.y) || p.x < -M || p.x > W + M || p.y < -M || p.y > H + M) { bad(`${what}: a point outside the image`); return false; } return true; };
  const pts = (a, n0, n1, what) => { if (!Array.isArray(a) || a.length < n0 || a.length > n1) { bad(`${what}: ${n0 === n1 ? n0 : `${n0} to ${n1}`} points expected`); return; } a.every(p => inside(p, what)); };
  const oneOf = (v, list, what, optional = true) => { if ((v === undefined || v === null) && optional) return; if (!list.includes(v)) bad(`${what}: "${String(v).slice(0, 20)}" is not one of ${list.join(', ')}`); };
  const bool = (v, what) => { if (v !== undefined && typeof v !== 'boolean') bad(`${what} is not yes or no`); };
  const range = (v, lo, hi, what, optional = true) => { if (v === undefined && optional) return; if (!isNum(v) || v < lo || v > hi) bad(`${what} out of range (${lo} to ${hi})`); };
  if (st.v !== 1) bad('the plan is not version 1');
  oneOf(st.wf, Object.keys(WFS), 'plan type', false);
  oneOf(st.side, ['R', 'L'], 'surgery side', false);
  if (patient && st.side !== patient.side) bad('the plan and the patient disagree on the side');
  if (patient && WFS[st.wf] && procOf(st.wf) !== patient.procedure) bad('the plan and the patient disagree on hip, knee, shoulder or diaphysis');   // r33; r39
  if (st.dia !== undefined && st.dia !== null && !diaOk(st.dia)) bad('the diaphyseal plan values are not valid');   // r39
  bool(st.sideSet, 'side chosen'); bool(st.mirrored, 'mirrored'); bool(st.prepDone, 'preparation done'); bool(st.wlAuto, 'auto contrast');
  if (st.hemi !== undefined && st.hemi !== null && (!isObj(st.hemi) || !Object.entries(st.hemi).every(([k, v]) => (k === 'R' || k === 'L') && v === true))) bad('the bipolar choice is not valid');   // r38: {R?: true, L?: true}
  else if (isObj(st.hemi) && isObj(st.tplAll) && isObj(st.tplAll.hip) && !(st.ref === 'other' && st.scope !== 'one')) for (const sd of ['R', 'L']) if (st.hemi[sd] === true && st.tplAll.hip['cup' + sd] !== undefined) bad(`the ${sd === 'R' ? 'right' : 'left'} hip is planned as a bipolar but has a cup`);   // r38 (Fable P2-1)
  oneOf(st.scope, ['both', 'one'], 'hips on the film'); oneOf(st.ref, ['own', 'other'], 'reference hip'); oneOf(st.cranial, ['up', 'down', 'left', 'right'], 'head direction');
  if (st.orient !== undefined && (!isObj(st.orient) || !ORIENT_FROM.includes(st.orient.from) || (st.orient.prevFrom !== undefined && !ORIENT_FROM.includes(st.orient.prevFrom)))) bad('orientation is not valid');
  if (st.cal !== null && st.cal !== undefined) {
    const c = st.cal;
    if (!isObj(c)) bad('calibration is not valid');
    else {
      oneOf(c.method, ['ball', 'line', 'dicom', 'xfer'], 'calibration method', false);
      range(c.mmPerPx, SCALE[0], SCALE[1], 'scale (mm per pixel)', false);
      if (c.method === 'xfer') {   // a knee case's long leg scaled from the AP (contract section 12): where from, and its inputs
        if (c.from !== 'ap' || (c.apSha !== undefined && !SHA_RE.test(String(c.apSha)))) bad('the scale taken from the AP is not valid');
        for (const k of ['apMmPerPx', 'apWidthPx', 'widthPx']) range(c[k], 1e-6, 1e6, `scale from the AP (${k})`);
      }
      if (c.method === 'ball') { if (inside(c.ball, 'calibration ball')) range(c.ball.r, 0.5, L, 'calibration ball radius', false); }
      if (c.method === 'line') { pts(c.line, 2, 2, 'calibration line'); range(c.lengthMm, 0.1, 5000, 'calibration length', false); }
      bool(c.confirmed, 'calibration confirmed');
    }
  }
  range(st.ballD, 5, 100, 'ball diameter'); range(st.vca, 0, 15, 'femoral valgus');
  // a single knee film's own plan values (15 Oct 2026; Sami: old single-film plans show the standard defaults until these are
  // entered): each a number in range or null (the standard default), the tibial reference plateau, whether the valgus was set
  if (st.kres !== undefined && st.kres !== null) {
    const k = st.kres, nn = (v, lo, hi, what) => { if (v !== undefined && v !== null && (!isNum(v) || v < lo || v > hi)) bad(`${what} out of range (${lo} to ${hi})`); };
    if (!isObj(k)) bad('the plan values are not valid');
    else { nn(k.femDistal, 0, 25, 'distal femoral resection'); nn(k.tibial, 0, 25, 'tibial resection'); nn(k.slope, -5, 15, 'planned cut slope'); nn(k.rot, 0, 10, 'planned femoral external rotation'); oneOf(k.tibRef, TIB_REFS, 'tibial reference plateau'); bool(k.vcaSet, 'femoral valgus set'); for (const m of provProblems(k.prov, k.asked)) bad(m); }
  }
  if (!isObj(st.lm)) bad('landmarks missing');
  else {
    const ks = Object.keys(st.lm);
    if (ks.length > 30) bad('too many landmarks');
    for (const k of ks) { if (!LM_KEY.test(k)) { bad(`unknown landmark "${k.slice(0, 20)}"`); continue; } pts(st.lm[k], 1, 3, `landmark ${k}`); }
  }
  if (st.lmSeq !== undefined && (!Array.isArray(st.lmSeq) || st.lmSeq.length > 40 || !st.lmSeq.every(k => typeof k === 'string' && LM_KEY.test(k)))) bad('landmark order is not valid');
  if (st.prep !== undefined && st.prep !== null && (!isObj(st.prep) || (st.prep.rot !== undefined && (!isNum(st.prep.rot) || Math.abs(st.prep.rot) > ROT_LIM)))) bad('film turn out of range (20° either way)');
  if (!isObj(st.tplAll)) bad('implants missing');
  else for (const [wf, o] of Object.entries(st.tplAll)) {
    if (!WFS[wf]) { bad(`unknown plan type "${wf.slice(0, 20)}"`); continue; }
    if (!isObj(o)) { bad(`implants of ${wf} are not valid`); continue; }
    for (const [sl, t] of Object.entries(o)) {
      const what = `implant ${wf} ${sl.slice(0, 12)}`;
      if (!WFS[wf].includes(sl)) { bad(`${what}: unknown place`); continue; }
      if (!isObj(t) || !isStr(t.fam, 80) || !/^[A-Za-z0-9._-]+$/.test(t.fam) || !(isStr(t.size, 16) || isNum(t.size)) || !isNum(t.angle) || Math.abs(t.angle) > 100) { bad(`${what} is not valid`); continue; }
      inside(t, what); bool(t.flip, `${what} mirrored`); bool(t.on, `${what} shown`);
      if (t.thick !== undefined) range(t.thick, 0, 60, `${what} thickness`);
      if (t.tref !== undefined && t.tref !== null && (!isObj(t.tref) || !['medial', 'lateral', null, undefined].includes(t.tref.plat) || (t.tref.mode !== undefined && !TIB_REFS.includes(t.tref.mode)) || (t.tref.mm !== undefined && !(isNum(t.tref.mm) && t.tref.mm >= 0 && t.tref.mm <= 25)) || (t.tref.p !== undefined && t.tref.p !== null && !(isObj(t.tref.p) && isNum(t.tref.p.x) && isNum(t.tref.p.y))))) bad(`${what}: the tibial resection reference is not valid`);
      for (const [k, n] of [['head', 8], ['headKind', 20], ['neck', 16]]) if (t[k] !== undefined && !isStr(t[k], n)) bad(`${what} ${k} is not valid`);
      if (t.stem !== undefined && !stemOk(t.stem)) bad(`${what}: the stem extension is not valid`);   // r20: {size} on a knee component
      if (t.tum !== undefined && t.tum !== null && !tumOk(t.tum)) bad(`${what}: the tumour resection values are not valid`);   // r22: {src, extent, margin}
      if (t.bip !== undefined && (!(wf === 'hip' && /^stem[RL]$/.test(sl)) || !bipOk(t.bip))) bad(`${what}: the bipolar shell is not valid`);   // r38: {fam, size, userSize?} on a hip stem
      if (t.liner !== undefined && (!(wf === 'hip' && /^cup[RL]$/.test(sl)) || !isStr(t.liner, 16) || !/^[a-z0-9-]+$/.test(t.liner))) bad(`${what}: the liner is not valid`);   // r38b: a liner type id on a hip cup
      bool(t.userLiner, `${what} liner set by hand`);
    }
  }
  if (st.geo !== undefined && (!isObj(st.geo) || Object.keys(st.geo).length > 40 || !Object.values(st.geo).every(g => isStr(g, 80)))) bad('implant outline versions are not valid');
  if (st.rulers !== undefined) { if (!Array.isArray(st.rulers) || st.rulers.length > 100) bad('too many rulers'); else st.rulers.forEach((r, i) => pts(r, 2, 2, `ruler ${i + 1}`)); }
  if (st.masks !== undefined && st.masks !== null) { if (!Array.isArray(st.masks) || st.masks.length > 100) bad('too many black-outs'); else st.masks.forEach((m, i) => { if (!isObj(m)) bad(`black-out ${i + 1} is not valid`); else { inside(m.a, `black-out ${i + 1}`); inside(m.b, `black-out ${i + 1}`); } }); }
  if (st.clean !== undefined) {
    const c = st.clean;
    if (!isObj(c) || !Array.isArray(c.strokes) || c.strokes.length > 500) bad('the eraser strokes are not valid');
    else {
      let n = 0;
      c.strokes.forEach((s, i) => { if (!isObj(s) || !isNum(s.r) || s.r <= 0 || s.r > L) { bad(`eraser stroke ${i + 1} is not valid`); return; } n += Array.isArray(s.pts) ? s.pts.length : 0; pts(s.pts, 1, 20000, `eraser stroke ${i + 1}`); });
      if (n > 200000) bad('the eraser strokes have too many points');
      const k = c.crop;
      if (k !== null && k !== undefined && (!isObj(k) || ![k.x0, k.y0, k.x1, k.y1].every(isNum) || k.x0 < 0 || k.y0 < 0 || k.x1 > W || k.y1 > H || k.x1 <= k.x0 || k.y1 <= k.y0)) bad('the crop is outside the image');
    }
  }
  if (st.wl !== undefined && (!isObj(st.wl) || !isNum(st.wl.c) || !isNum(st.wl.w) || st.wl.w <= 0)) bad('brightness and contrast are not valid');
  if (st.settings !== undefined) {
    if (!isObj(st.settings)) bad('the plan\'s rules are not valid');
    else { const s = st.settings; for (const [k, list] of Object.entries(SETTINGS)) oneOf(s[k], list, `rule ${k}`); range(s.cupIncl, 30, 55, 'cup inclination'); range(s.tdLatMm, 0, 15, 'cup distance from the teardrop'); }
  }
  if (st.display !== undefined) {
    const d = st.display;
    if (!isObj(d)) bad('display choices are not valid');
    else { for (const k of ['invert', 'labels', 'showOriginal', 'cleanView']) bool(d[k], `display ${k}`); oneOf(d.picture, ['clean', 'full'], 'picture style'); }
  }
  if (st.notes !== undefined && (!isObj(st.notes) || Object.keys(st.notes).length > 100)) bad('notes are not valid');
  if (st.mirror !== undefined && st.mirror !== null && (!isObj(st.mirror) || !isStr(st.mirror.note, 2000))) bad('the mirroring note is not valid');
  if (st.markers !== undefined) { if (!Array.isArray(st.markers) || st.markers.length > 8) bad('too many R/L markers'); else st.markers.forEach((m, i) => { if (inside(m, `R/L marker ${i + 1}`)) oneOf(m.letter, ['R', 'L'], `R/L marker ${i + 1}`, false); }); }
  // revision and conversion (contract section 13)
  oneOf(st.caseType, CASE_TYPES, 'case type');
  if (st.exist !== undefined && st.exist !== null) {
    const e = st.exist, ip = (n, lo, hi) => Number.isInteger(n) && n >= lo && n <= hi;
    if (!isObj(e) || e.v !== 1 || e.coord !== 'image-px' || (e.sha !== null && e.sha !== undefined && !SHA_RE.test(String(e.sha))) || !ip(e.rev, 0, 1e9) || !Array.isArray(e.regions)) bad('the existing implant marks are not valid');
    else if (e.w !== W || e.h !== H) bad('the existing implant marks were made on an image of another size');
    else if (e.regions.length > EXIST_MAX.regions) bad(`too many existing implant regions (at most ${EXIST_MAX.regions})`);
    else {
      bool(e.none, 'no existing implant on this film');
      let n = 0;
      e.regions.forEach((r, i) => {
        const what = `existing implant region ${i + 1}`;
        if (!isObj(r) || !ip(r.id, 1, 1e6) || !EXIST_CLS.includes(r.cls) || !['poly', 'stroke'].includes(r.kind) || !['detected', 'user'].includes(r.src) || typeof r.conf !== 'boolean') { bad(`${what} is not valid`); return; }
        if (r.kind === 'stroke') range(r.r, 0.5, L, `${what} width`, false);
        n += Array.isArray(r.pts) ? r.pts.length : 0;
        pts(r.pts, r.kind === 'poly' ? 3 : 1, EXIST_MAX.pts, what);
      });
      if (n > EXIST_MAX.total) bad(`the existing implant regions have too many points (at most ${EXIST_MAX.total})`);
      if (new Set(e.regions.filter(isObj).map(r => r.id)).size !== e.regions.length) bad('two existing implant regions have the same number');
    }
  }
  if (st.rv !== undefined && st.rv !== null) {
    const v = st.rv, pt1 = (p, what) => { if (p !== null && p !== undefined) inside(p, what); }, two = (a, what) => { if (a !== null && a !== undefined) pts(a, 2, 2, what); };
    if (!isObj(v)) bad('the revision record is not valid');
    else {
      if (v.canal !== undefined && v.canal !== null) {
        if (!isObj(v.canal)) bad('the revision canal record is not valid');
        else for (const [sd, c] of Object.entries(v.canal)) if (!['R', 'L'].includes(sd) || (c !== null && (!isObj(c) || !['snap', 'pairs', 'hand'].includes(c.how) || !Number.isInteger(c.mrev) || c.mrev < 0 || c.mrev > 1e9))) bad('the revision canal record is not valid');
      }
      pt1(v.tip, 'the old implant\'s tip');
      if (v.holes !== undefined && v.holes !== null) { if (!Array.isArray(v.holes) || v.holes.length > 20) bad('too many holes or defects (at most 20)'); else v.holes.forEach((p, i) => inside(p, `hole or defect ${i + 1}`)); }
      two(v.osteo, 'the osteotomy'); two(v.fix, 'the fixation zone');
      if (v.cupT !== undefined && v.cupT !== null && (!isObj(v.cupT) || !['mirror', 'point'].includes(v.cupT.how) || !inside(v.cupT.p, 'the revision cup target'))) bad('the revision cup target is not valid');
      if (v.refs !== undefined && v.refs !== null) {
        if (!isObj(v.refs) || Object.keys(v.refs).length > 30) bad('the reference labels are not valid');
        else for (const [k, l] of Object.entries(v.refs)) if (!LM_KEY.test(k) || !REF_LABELS.includes(l)) bad(`the label of reference "${k.slice(0, 20)}" is not valid`);
      }
      if (v.jl !== undefined && v.jl !== null) {
        const j = v.jl;
        if (!isObj(j) || (j.src !== null && j.src !== undefined && !['contra', 'refs'].includes(j.src))) bad('the joint-line target is not valid');
        else { two(j.line, 'the joint-line target'); two(j.epi, 'the epicondylar line'); pt1(j.fib, 'the fibular head'); bool(j.suit, 'contralateral knee suitable'); bool(j.agree, 'references agree'); }
      }
    }
  }
  return out;
}
// What is wrong with a project's implants against the implant library the page has loaded (Astra's page re-review, 13 Oct,
// finding 4): a list of sentences (empty: nothing). Each implant whose family the library has must be of its place's kind
// (a cup in a cup's place), and, when the plan was made with this library (its libraryId) and the family's outline is the
// one it was made with (geo), one of the family's sizes; a stem's head system one it takes, its head diameter one that
// system has and its neck one that head has; a tibial tray's total thickness one of the tray's. A family the library does
// not have (a range not open to the account) is kept aside as it is (contract 7), and a plan from another build has its
// implants placed again (app.js applyPlan), so neither is held against this library. lib: library.json as loaded.
const KIND_OF = { cupR: 'cup', cupL: 'cup', stemR: 'stem', stemL: 'stem', femur: 'tka-femur', tibia: 'tka-tibia', humerus: 'humeral-body' };   // r33
// a tumour body's resection values (r22): the extent's source ('' until chosen, MRI or CT), extent and margin in mm or null
export const tumOk = v => isObj(v) && ['', 'MRI', 'CT'].includes(v.src) && [[v.extent, 400], [v.margin, 100], [v.c, 100]].every(([x, hi]) => x === null || x === undefined || (typeof x === 'number' && Number.isFinite(x) && x >= 0 && x <= hi))
  && Object.keys(v).every(k => ['src', 'extent', 'margin', 'c'].includes(k));   // c: the stem collar picked with an option
// a knee component's stem extension (r20, SCHEMA-2026-10-17-r20.md): {size}, the size a knee-stem family lists
// r38: a hip stem's bipolar shell: {fam, size} and whether its size was set by hand
export const bipOk = v => isObj(v) && typeof v.fam === 'string' && v.fam.length <= 80 && /^[A-Za-z0-9._-]+$/.test(v.fam) && typeof v.size === 'string' && v.size.length <= 16
  && (v.userSize === undefined || typeof v.userSize === 'boolean') && Object.keys(v).every(k => ['fam', 'size', 'userSize'].includes(k));
export const stemOk = v => isObj(v) && typeof v.size === 'string' && v.size.length <= 16 && /^[A-Za-z0-9._-]+$/.test(v.size) && (v.geo === undefined || (typeof v.geo === 'string' && /^[0-9a-f]{6,64}$/.test(v.geo)));
const own = (o, k) => isObj(o) && Object.prototype.hasOwnProperty.call(o, k);
// r38b (Fable, 29 Oct 2026, P2-1): an earlier libraryId whose library this one only ADDS sizes to (checked by diffing the two
// library files before an id goes in here): its plans are plans of this library. 656ef0f7cb39 (live to r37/r38) -> 7fa03c4b90ab
// (r38b: tibial tray 3# added; every other size, family and head unchanged).
export const LIB_SAME = Object.freeze({ '656ef0f7cb39': '7fa03c4b90ab' });
export const libSame = (a, b) => typeof a === 'string' && typeof b === 'string' && (a === b || (Object.prototype.hasOwnProperty.call(LIB_SAME, a) && LIB_SAME[a] === b));
// r40 (29 Oct 2026, Kevin's DWG): every Addis Ortho family carries famGeo, its outline version (build_ranges.py), and a plan
// saves it in geo like a vendor geomId. For a plan of another library id the page keeps an implant whose family's outline is
// the one the plan was made with and places again only the changed families. LIB_GEO: the famGeo each Addis Ortho family had
// in a library from before famGeo (computed by stage_r40.py from the live range files of 7fa03c4b90ab); a plan of 656ef0f7cb39
// goes through LIB_SAME to 7fa03c4b90ab's. 7fa03c4b90ab -> ac4667dfef91: chunli-130-long, chunli-160-200 and chunli-160-250 changed.
export const LIB_GEO = Object.freeze({ '7fa03c4b90ab': Object.freeze({ 'chunli-130': 'e18f0ae471d2', 'chunli-130-long': 'd81546527298', 'chunli-160-150': 'f23030c0a1f0', 'chunli-160-200': 'eef0ab0790df', 'chunli-160-250': 'a8b72997dd25', 'chunli-be': '4414293cf6b2', 'chunli-be2-127h': '262363120312', 'chunli-be2-127s': 'e28559615b65', 'chunli-be2-133h': 'd1b24c45cd93', 'chunli-be2-133s': 'abf7eafc45d5', 'chunli-cup': '593ed445da31', 'chunli-tka-femur': '5e9051e9f5fb', 'chunli-tka-tibia': 'd5110524208f' }) });
export const libBase = id => (typeof id === 'string' && Object.prototype.hasOwnProperty.call(LIB_SAME, id) ? LIB_SAME[id] : id);
// the outline versions a plan of another library was made with: LIB_GEO of its library (if any) and its own geo over it
export const planGeo = (id, geo) => ({ ...(typeof id === 'string' && Object.prototype.hasOwnProperty.call(LIB_GEO, libBase(id)) ? LIB_GEO[libBase(id)] : {}), ...(isObj(geo) ? geo : {}) });
export const famGeoOf = f => (isObj(f) && typeof (f.geomId || f.famGeo) === 'string' ? f.geomId || f.famGeo : null);
export function libraryProblems(st, lib) {
  if (!isObj(st) || !isObj(st.tplAll) || !isObj(lib) || !Array.isArray(lib.families)) return [];
  const out = [], bad = m => { if (out.length < 12) out.push(m); }, fams = {}, heads = isObj(lib.heads) ? lib.heads : {};
  for (const f of lib.families) if (isObj(f) && typeof f.id === 'string') fams[f.id] = f;
  const same = libSame(st.libraryId, lib.libraryId), geo = same ? (isObj(st.geo) ? st.geo : {}) : planGeo(st.libraryId, st.geo);   // r38b: or an id this library only adds sizes to; r40: else the outlines it was made with
  for (const [wf, o] of Object.entries(st.tplAll)) {
    if (!isObj(o)) continue;
    for (const [sl, t] of Object.entries(o)) {
      if (!isObj(t) || typeof t.fam !== 'string' || !own(fams, t.fam)) continue;
      const f = fams[t.fam], what = `implant ${wf} ${sl.slice(0, 12)}`, name = String(f.label || f.id).slice(0, 60);
      if (KIND_OF[sl] && f.kind !== KIND_OF[sl]) { bad(`${what}: ${name} is not a ${KIND_OF[sl].replace('tka-', 'knee ')}`); continue; }
      if (same ? (famGeoOf(f) && geo[t.fam] && geo[t.fam] !== famGeoOf(f)) : (!famGeoOf(f) || geo[t.fam] !== famGeoOf(f))) continue;   // r40: a family whose outline is unchanged is checked (applyPlan keeps it)
      if (!Array.isArray(f.sizes) || !f.sizes.some(z => isObj(z) && z.size === t.size)) { bad(`${what}: ${name} has no size "${String(t.size).slice(0, 16)}"`); continue; }
      if (f.kind === 'stem') {
        const kinds = (Array.isArray(f.headKinds) ? f.headKinds : ['ceramic', 'cocr']).filter(k => isObj(heads[k]) && isObj(heads[k].sizes));
        if (!kinds.includes(t.headKind)) bad(`${what}: head system "${String(t.headKind).slice(0, 20)}" is not one ${name} takes`);
        else if (!own(heads[t.headKind].sizes, t.head)) bad(`${what}: no ${String(t.head).slice(0, 8)} mm head in that head system`);
        else if (!own(heads[t.headKind].sizes[t.head], t.neck)) bad(`${what}: no neck "${String(t.neck).slice(0, 16)}" for that head`);
        if (isObj(t.bip) && own(fams, t.bip.fam)) {   // r38: a shell family, one of its sizes (unless its outline changed), on the head it takes
          const bf = fams[t.bip.fam], ih = isObj(bf.innerHead) ? bf.innerHead : {}, bg = famGeoOf(bf);
          const bz = Array.isArray(bf.sizes) ? bf.sizes.find(z => isObj(z) && z.size === t.bip.size) : null, bd = isObj(bz) && Number.isFinite(bz.innerHead) ? bz.innerHead : ih.diameter;   // r40: the inner head per shell size
          const moved = same ? !!(bg && geo[bf.id] && geo[bf.id] !== bg) : !(bg && geo[bf.id] === bg);   // its outline changed since: applyPlan takes it (and its stem) off
          if (bf.kind !== 'bipolar') bad(`${what}: ${String(bf.label || bf.id).slice(0, 60)} is not a bipolar shell`);
          else if (!moved && !bz) bad(`${what}: the bipolar shell has no size "${String(t.bip.size).slice(0, 16)}"`);
          else if (!moved && (t.headKind !== ih.kind || String(t.head) !== String(bd))) bad(`${what}: the bipolar shell takes the ${String(bd).slice(0, 8)} mm head, not this one`);
        }
      }
      if (t.liner !== undefined && f.kind === 'cup') {   // r38b: a type the family names and this size offers (Pinnacle liners)
        const lt = isObj(f.linerTypes) ? f.linerTypes : {}, z = f.sizes.find(q => isObj(q) && q.size === t.size), L = z && Array.isArray(z.liners) ? z.liners : [];
        if (!own(lt, t.liner)) bad(`${what}: ${name} has no liner type "${String(t.liner).slice(0, 16)}"`);
        else if (!L.some(l => isObj(l) && l.type === t.liner)) bad(`${what}: ${name} size ${String(t.size).slice(0, 8)} has no ${String(lt[t.liner].label || t.liner).slice(0, 20)} liner`);
      }
      if (f.kind === 'humeral-body' && t.head !== undefined && !(Array.isArray(f.humHeads) && f.humHeads.some(q => isObj(q) && String(q.d) === String(t.head)))) bad(`${what}: ${name} has no ${String(t.head).slice(0, 8)} mm head`);   // r33
      if (f.kind === 'tka-tibia' && Array.isArray(f.thicknesses) && t.thick !== undefined && !f.thicknesses.includes(t.thick)) bad(`${what}: ${name} has no ${String(t.thick).slice(0, 8)} mm total thickness`);
      if (t.stem !== undefined) {   // only a stem the maker's sheet draws for that component size (r20)
        const z = f.sizes.find(q => isObj(q) && q.size === t.size), sf = typeof f.stems === 'string' && own(fams, f.stems) ? fams[f.stems] : null;
        if (!f.stems) bad(`${what}: ${name} takes no stem extension`);
        else if (sf && !(z && Array.isArray(z.stemSizes) && z.stemSizes.includes(isObj(t.stem) ? t.stem.size : null))) bad(`${what}: stem ${String(isObj(t.stem) ? t.stem.size : '').slice(0, 16)} is not drawn for ${name} size ${String(t.size).slice(0, 8)}`);
      }
    }
  }
  return out;
}
// ---------- the patient's identity (Sami, 15 Oct 2026; contract section 12, "Patient identity") ----------
// Sami: "use their full name along with hospital name's initial and age, MRN and name for record, use the hospital initial
// e.g SSC-MRN for each hospital, so we have clear database for each hospital. then under the specific patient add all the
// other images as a sub menu". A case carries the hospital's code (its initials), the full name, the age in years and the
// record number, stored and shown with the hospital's code in front ("SSC-12345", never "SSC-SSC-12345"). The patient is
// the hospital and the record number together: the same name at two hospitals, or without a record number, is never one
// patient. HOSPITALS is the one list of hospitals (edit it here: code, full name, and how the X-ray's InstitutionName
// names it); a hospital not on it can still be typed as its initials (2 to 6 capital letters).
export const HOSPITALS = [
  { code: 'SSC', name: 'Samaritan Surgical Center', dicom: /\bsamaritan\b/i },
];
export const HOSP_RE = /^[A-Z]{2,6}$/;
export const AGE_MAX = 130;
export const hospitalName = code => ((HOSPITALS.find(h => h.code === code) || {}).name || '');
// the hospital an X-ray's InstitutionName names, from the list ('' when it maps to none: the surgeon is asked)
export function hospitalFromDicom(inst) {
  const t = String(inst || '').trim(); if (!t) return '';
  const h = HOSPITALS.find(x => x.dicom.test(t) || x.name.toLowerCase() === t.toLowerCase() || x.code === t.toUpperCase());
  return h ? h.code : '';
}
// a record number as typed or read, without any hospital code already in front of it (SSC-123, SSC 123, ssc123 → 123 for
// SSC; another code in front is kept: it is not this hospital's)
export function mrnCore(code, raw) {
  let t = String(raw || '').replace(/[\u0000-\u001f\u007f]+/g, ' ').trim();
  if (code && HOSP_RE.test(code)) for (let i = 0; i < 4; i++) { const m = t.match(new RegExp(`^${code}(?:[\\s:_/-]+|(?=\\d))(.+)$`, 'i')); if (!m) break; t = m[1].trim(); }   // "SSC-SSC-5" too
  return t;
}
// the record number as stored and shown: the hospital's code in front, once ("SSC-12345"); without a hospital as it is.
// Never shortened (Astra, 15 Oct, round 2, P1 1: cutting two long record numbers to one length made them one patient): a
// stored form over MRN_MAX characters is refused by identProblem here and by the server alike (mrnOk says so first).
export const MRN_MAX = 40, NAME_MAX = 120;
export function normMrn(code, raw) {
  const core = mrnCore(code, raw);
  return !core ? '' : code && HOSP_RE.test(code) ? `${code}-${core}` : core;
}
export const mrnOk = (code, raw) => normMrn(code, raw).length <= MRN_MAX;
// age in whole years from DICOM: the birth date and the study date when both are there (exact), else PatientAge with its
// unit converted (045Y → 45, 024M → 2, 104W → 1, 400D → 1; Astra, round 2, P2 5: months, weeks and days were all taken as
// 0); null when neither says
export function ageOf(m) {
  const b = String((m && m.PatientBirthDate) || ''), d = String((m && m.StudyDate) || '');
  if (!(/^\d{8}$/.test(b) && /^\d{8}$/.test(d))) {
    const a = String((m && m.PatientAge) || '').trim().toUpperCase(), am = a.match(/^(\d{1,3})([YMWD])$/);
    if (!am) return null;
    const n = parseInt(am[1], 10), y = Math.floor(n / ({ Y: 1, M: 12, W: 52.1775, D: 365.25 })[am[2]] + 1e-9);
    return y <= AGE_MAX ? y : null;
  }
  if (/^\d{8}$/.test(b) && /^\d{8}$/.test(d)) {
    const yb = +b.slice(0, 4), yd = +d.slice(0, 4), md = d.slice(4) < b.slice(4) ? 1 : 0, n = yd - yb - md;
    return n >= 0 && n <= AGE_MAX ? n : null;
  }
  return null;
}
const normName = v => String(v || '').toLowerCase().replace(/[^a-z\u00c0-\u024f\u1200-\u137f]+/g, ' ').trim().split(' ').filter(Boolean).sort().join(' ');
const mrnKey = (code, raw) => mrnCore(code, raw).toUpperCase().replace(/\s+/g, '');
// a film's patient against the identities already established for a case (the case's own and each of its films'): ''
// when they agree (or one side says nothing), else why, in words. Hospitals that differ, record numbers that differ (with
// the hospital's code aside), or names that differ all refuse (Astra, 15 Oct, P1 3: compared with every film's identity,
// so a record number learned from one film binds the next).
export function identClash(known, inc) {
  const i = inc || {};
  for (const k of (Array.isArray(known) ? known : [known]).filter(Boolean)) {
    if (k.hospital && i.hospital && k.hospital !== i.hospital) return `the X-ray is from ${i.hospital}, the case's patient from ${k.hospital}`;
    const code = i.hospital || k.hospital || '';
    const r1 = mrnKey(k.hospital || code, k.recordNo), r2 = mrnKey(i.hospital || code, i.recordNo);
    if (r1 && r2 && r1 !== r2) return `the X-ray's record number is ${String(i.recordNo).slice(0, 40)}, the case's ${String(k.recordNo).slice(0, 40)}`;
    const n1 = normName(k.name), n2 = normName(i.name);
    if (n1 && n2 && n1 !== n2) return "the patient's name on the X-ray is not the case's";
  }
  return '';
}
// the case's identity with what a compatible film adds: every field the case lacks taken from the film (Astra, 15 Oct,
// P1 3: a case learns a record number, hospital or age that a later film carries); the case's own never overwritten
// an identity field cleaned (control characters and runs of spaces) but never shortened (Astra, round 3, P1: a cut record
// number made two patients one): a value too long is refused where it is checked (identProblem, and the server)
export const cleanId = v => String(v == null ? '' : v).replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]+/g, ' ').replace(/\s+/g, ' ').trim();
export function identFill(pt, inc) {
  const o = { name: '', recordNo: '', hospital: '', age: null, ...(isObj(pt) ? pt : {}) }, i = inc || {};
  if (!o.name && i.name) o.name = cleanId(i.name);
  if (!o.hospital && i.hospital && HOSP_RE.test(i.hospital)) o.hospital = i.hospital;
  if (!o.recordNo && i.recordNo) o.recordNo = cleanId(i.recordNo);
  if (!Number.isInteger(o.age) && Number.isInteger(i.age)) o.age = i.age;
  return o;
}
// what is wrong with a project's patient details ('' when nothing): name ≤ 120, record number ≤ 40, study ≤ 64, hospital
// a code of 2 to 6 capital letters, age a whole number of years 0-130
export function identProblem(pt) {
  if ((pt.name !== undefined && !isStr(pt.name, NAME_MAX)) || (pt.recordNo !== undefined && !isStr(pt.recordNo, MRN_MAX)) || (pt.studyUID !== undefined && !isStr(pt.studyUID, 64))) return 'patient details are not valid';
  if (pt.hospital !== undefined && !(typeof pt.hospital === 'string' && HOSP_RE.test(pt.hospital))) return 'the hospital code is not valid';
  if (pt.age !== undefined && !(Number.isInteger(pt.age) && pt.age >= 0 && pt.age <= AGE_MAX)) return 'the age is not valid';
  return '';
}
// one line for a patient in the list: "Full name · SSC · 45 y · SSC-MRN" (each part only when known)
export function identLine(pt) {
  const p = isObj(pt) ? pt : {};
  return [p.name || '', p.hospital || '', Number.isInteger(p.age) ? `${p.age} y` : '', p.recordNo || ''].filter(Boolean).join(' · ');
}
// What is wrong with a whole project (contract 5), the image's own bytes aside: a list of sentences. Version 2 is a knee
// case (contract section 12: caseProblems).
export function projectProblems(p) {
  if (!isObj(p)) return ['not a templating project'];
  if (p.format !== FORMAT) return ['not an Addis Ortho templating project (format)'];
  if (p.v === 2) return caseProblems(p);
  if (p.v !== 1) return [`a project of version ${String(p.v).slice(0, 10)}: this page reads versions 1 and 2`];
  const out = [];
  if (!isStr(p.savedAt, 40)) out.push('the date it was saved is missing');
  if (!isStr(p.libraryId, 40) || !/^[A-Za-z0-9._-]{1,40}$/.test(p.libraryId)) out.push('the implant library it was made with is missing');
  const pt = p.patient;
  if (!isObj(pt) || !['R', 'L'].includes(pt.side) || !['hip', 'knee', 'shoulder', 'diaphysis'].includes(pt.procedure)) out.push('patient side and procedure are missing');   // r39: diaphysis
  else if (identProblem(pt)) out.push('patient details are not valid');
  const im = p.image;
  if (!isObj(im) || !isStr(im.fileName, 255) || !MIME_EXT[im.mime] || !SHA_RE.test(String(im.sha256)) || !Number.isInteger(im.size) || !Number.isInteger(im.width) || !Number.isInteger(im.height)) return [...out, 'the image description is missing or not valid'];
  if (im.size < 1 || im.size > IMAGE_MAX) out.push(`the image is larger than ${MB(IMAGE_MAX)}`);
  if (im.width < 1 || im.height < 1 || im.width > 100000 || im.height > 100000 || im.width * im.height > PIXELS_MAX) out.push(`the image is larger than ${PIXELS_MAX / 1e6} megapixels`);
  if (out.length) return out;
  return stateProblems(p.state, im.width, im.height, pt);
}
// one film's image description (version 2): the same rules as version 1's image
function imageProblems(im, what) {
  if (!isObj(im) || !isStr(im.fileName, 255) || !MIME_EXT[im.mime] || !SHA_RE.test(String(im.sha256)) || !Number.isInteger(im.size) || !Number.isInteger(im.width) || !Number.isInteger(im.height)) return [`${what}: the image description is missing or not valid`];
  const out = [];
  if (im.size < 1 || im.size > IMAGE_MAX) out.push(`${what}: the image is larger than ${MB(IMAGE_MAX)}`);
  if (im.width < 1 || im.height < 1 || im.width > 100000 || im.height > 100000 || im.width * im.height > PIXELS_MAX) out.push(`${what}: the image is larger than ${PIXELS_MAX / 1e6} megapixels`);
  return out;
}
const PF = { grade: ['', 'none', 'mild', 'moderate', 'severe'], facet: ['', 'no', 'yes'], dysplasia: ['', 'none', 'suspected', 'present'], tracking: ['', 'central', 'tilted', 'subluxed'] };
// The knee case's shared plan (contract section 12): the one femoral and one tibial component (family and size), the
// surgeon's own entries (distal femoral and tibial resection depths, planned posterior slope, target HKA: null until
// entered, never a default), the patella decision (undecided until the surgeon decides), the recorded patellofemoral
// assessment from the skyline and whether the long leg is a standing film or a supine CT scout.
export function sharedProblems(sh) {
  const out = [], bad = m => { if (out.length < 12) out.push(m); };
  if (!isObj(sh)) return ['the shared plan is missing'];
  const num = (v, lo, hi, what) => { if (v !== null && v !== undefined && (!isNum(v) || v < lo || v > hi)) bad(`${what} out of range (${lo} to ${hi})`); };
  if (sh.size !== undefined) {
    if (!isObj(sh.size)) bad('the shared sizes are not valid');
    else for (const [k, v] of Object.entries(sh.size)) if (!['femur', 'tibia'].includes(k) || (v !== null && (!isObj(v) || !isStr(v.fam, 80) || !/^[A-Za-z0-9._-]+$/.test(v.fam) || !(isStr(v.size, 16) || isNum(v.size)) || (v.stem !== undefined && !stemOk(v.stem))))) bad(`shared size ${k.slice(0, 12)} is not valid`);
  }
  if (sh.res !== undefined) {
    if (!isObj(sh.res)) bad('the resection depths are not valid');
    else {
      num(sh.res.femDistal, 0, 25, 'distal femoral resection'); num(sh.res.tibial, 0, 25, 'tibial resection'); num(sh.res.slope, -5, 15, 'planned posterior slope'); num(sh.res.vca, 0, 15, 'femoral valgus');
      if (sh.res.tibRef !== undefined && sh.res.tibRef !== null && !TIB_REFS.includes(sh.res.tibRef)) bad('the tibial resection\'s reference plateau is not valid');
      for (const m of provProblems(sh.res.prov, sh.res.asked)) bad(m);
    }
  }
  num(sh.hkaTarget, 165, 195, 'target HKA'); num(sh.rot, 0, 10, 'planned femoral external rotation');
  if (sh.patella !== undefined && !['undecided', 'resurface', 'retain'].includes(sh.patella)) bad('the patella decision is not valid');
  if (sh.longSrc !== undefined && sh.longSrc !== null && !['standing', 'ctscout'].includes(sh.longSrc)) bad('the long leg film\'s kind is not valid');
  if (sh.caseType !== undefined && sh.caseType !== null && !CASE_TYPES.includes(sh.caseType)) bad('the case type is not valid');
  if (sh.pf !== undefined) {
    if (!isObj(sh.pf)) bad('the patellofemoral assessment is not valid');
    else { for (const [k, list] of Object.entries(PF)) if (sh.pf[k] !== undefined && !list.includes(sh.pf[k])) bad(`patellofemoral ${k} is not valid`); if (sh.pf.note !== undefined && !isStr(sh.pf.note, 300)) bad('the patellofemoral note is too long'); }
  }
  return out;
}
// The shared plan as the page keeps it (Astra, 15 Oct, P2 5): every part present, every value either a finite number in
// range or null ("not set"), never undefined, so a shared plan saved with a part missing ({res: {}}) can never reach the
// placement as an undefined value. Checked first by sharedProblems (anything of the wrong kind is refused there); this
// only fills what is absent. A copy: the caller's object is not changed.
export function normShared(sh) {
  const o = isObj(sh) ? sh : {}, n = (v, lo, hi) => (isNum(v) && v >= lo && v <= hi ? v : null);
  const sz = isObj(o.size) ? o.size : {}, res = isObj(o.res) ? o.res : {}, pf = isObj(o.pf) ? o.pf : {};
  const size1 = v => (isObj(v) && isStr(v.fam, 80) && (isStr(v.size, 16) || isNum(v.size)) ? { fam: v.fam, size: String(v.size), byHand: v.byHand === true, ...(stemOk(v.stem) ? { stem: { size: v.stem.size, ...(v.stem.geo ? { geo: v.stem.geo } : {}) } } : {}) } : null);
  return {
    size: { femur: size1(sz.femur), tibia: size1(sz.tibia) },
    res: { femDistal: n(res.femDistal, 0, 25), tibial: n(res.tibial, 0, 25), slope: n(res.slope, -5, 15), tibRef: TIB_REFS.includes(res.tibRef) ? res.tibRef : 'less', vca: n(res.vca, 0, 15), prov: provNorm(res.prov), asked: typeof res.asked === 'string' && res.asked.length <= 80 ? res.asked : null },
    rot: n(o.rot, 0, 10), hkaTarget: n(o.hkaTarget, 165, 195),
    patella: ['undecided', 'resurface', 'retain'].includes(o.patella) ? o.patella : 'undecided',
    pf: Object.fromEntries([...Object.entries(PF).map(([k, list]) => [k, list.includes(pf[k]) ? pf[k] : '']), ['note', isStr(pf.note, 300) ? pf.note : '']]),
    longSrc: o.longSrc === 'standing' || o.longSrc === 'ctscout' ? o.longSrc : null,
    caseType: CASE_TYPES.includes(o.caseType) ? o.caseType : 'primary',   // revision and conversion (contract section 13)
  };
}
// What is wrong with a knee case (project version 2, contract section 12), the images' own bytes aside
export function caseProblems(p) {
  const out = [];
  if (!isObj(p.films)) return ['a project of version 2 without its films'];
  if (!isStr(p.savedAt, 40)) out.push('the date it was saved is missing');
  if (!isStr(p.libraryId, 40) || !/^[A-Za-z0-9._-]{1,40}$/.test(p.libraryId)) out.push('the implant library it was made with is missing');
  if (!isObj(p.case) || !CID_RE.test(String(p.case.cid)) || p.case.kind !== 'knee') out.push('the knee case\'s own id is missing');
  const pt = p.patient;
  if (!isObj(pt) || !['R', 'L'].includes(pt.side) || pt.procedure !== 'knee') out.push('patient side and procedure are missing (a knee case is a knee)');
  else if (identProblem(pt)) out.push('patient details are not valid');
  const roles = Object.keys(p.films);
  if (!roles.length || roles.length > ROLES.length || roles.some(r => !ROLES.includes(r))) return [...out, `the films must be some of ${ROLES.map(r => ROLE_NAME[r]).join(', ')}`];
  let bytes = 0, px = 0; const shas = new Set();
  for (const r of roles) {
    const f = p.films[r], what = `${ROLE_NAME[r]} film`;
    if (!isObj(f)) { out.push(`${what} is not valid`); continue; }
    const ip = imageProblems(f.image, what); if (ip.length) { out.push(...ip); continue; }
    if (shas.has(f.image.sha256)) out.push(`${what}: the same image is another film of this case`);
    shas.add(f.image.sha256); bytes += f.image.size; px += f.image.width * f.image.height;
    if (f.qc !== undefined && (!isObj(f.qc) || ['side', 'quality', 'ptSame'].some(k => f.qc[k] !== undefined && typeof f.qc[k] !== 'boolean'))) out.push(`${what}: its checks are not valid`);
    if (isObj(pt) && isObj(f.state) && f.state.wf !== ROLE_WF[r]) out.push(`${what}: its plan is not a ${what.toLowerCase()} plan`);
    else out.push(...stateProblems(f.state, f.image.width, f.image.height, isObj(pt) ? pt : null).map(m => `${what}: ${m}`));
  }
  if (bytes > CASE_IMAGES_MAX) out.push(`the films are larger than ${MB(CASE_IMAGES_MAX)} together`);
  if (px > CASE_PIXELS_MAX) out.push(`the films are larger than ${CASE_PIXELS_MAX / 1e6} megapixels together`);
  if (p.active !== undefined && !roles.includes(p.active)) out.push('the film shown is not one of the case\'s films');
  if (p.shared !== undefined) out.push(...sharedProblems(p.shared));
  if (p.notices !== undefined && (!Array.isArray(p.notices) || p.notices.length > 20 || !p.notices.every(n => isStr(n, 300)))) out.push('the case\'s notices are not valid');
  return out.slice(0, 12);
}
// The project and its image's bytes, checked together; throws ProjectError with what is wrong. A knee case: bytes is
// {role: Uint8Array}, every film's checked.
export async function checkProject(p, bytes) {
  const pr = projectProblems(p);
  if (pr.length) throw new ProjectError(`This project cannot be opened: ${pr.slice(0, 4).join('; ')}${pr.length > 4 ? '; …' : ''}.`);
  if (p.v === 2) {
    for (const [r, f] of Object.entries(p.films)) {
      const b = isObj(bytes) ? bytes[r] : null;
      if (!(b instanceof Uint8Array) || b.length !== f.image.size) throw new ProjectError(`This project cannot be opened: the ${ROLE_NAME[r]} film's image is not the size the project states.`);
      if (await sha256hex(b) !== f.image.sha256) throw new ProjectError(`This project cannot be opened: the ${ROLE_NAME[r]} film's image does not match the project (sha256 differs). The file is damaged or was changed.`);
    }
    return true;
  }
  if (!(bytes instanceof Uint8Array) || bytes.length !== p.image.size) throw new ProjectError('This project cannot be opened: its image is not the size the project states.');
  if (await sha256hex(bytes) !== p.image.sha256) throw new ProjectError('This project cannot be opened: its image does not match the project (sha256 differs). The file is damaged or was changed.');
  return true;
}
// The .aotp's parts (zip.js): project.json, image.<ext>, and case.json when it has a case on t.addisortho.com. A knee case
// (version 2): one image-<role>.<ext> per film; image is {role: Uint8Array} and imageCrc {role: crc}; case.json carries
// the films' images the server holds ({id, rev, shas: {role: sha256}}).
export function aotpParts(project, image, { caseRef = null, imageCrc } = {}) {
  const enc = new TextEncoder(), json = enc.encode(JSON.stringify(project));
  if (json.length > PROJECT_MAX) throw new ProjectError(`the plan is larger than ${MB(PROJECT_MAX)} (too many eraser strokes?)`);
  const entries = [{ name: 'project.json', data: json }];
  const kc = project.v === 2 && isObj(project.films);
  if (kc) for (const r of ROLES) { const f = project.films[r]; if (f) entries.push({ name: `image-${r}.${MIME_EXT[f.image.mime]}`, data: image[r], crc: imageCrc && imageCrc[r] }); }
  else entries.push({ name: 'image.' + MIME_EXT[project.image.mime], data: image, crc: imageCrc });
  if (caseRef && ID_RE.test(caseRef.id)) entries.push({ name: 'case.json', data: enc.encode(JSON.stringify(kc ? { id: caseRef.id, rev: caseRef.rev, sha256: null, shas: shasOf(caseRef) } : { id: caseRef.id, rev: caseRef.rev, sha256: caseRef.sha256 || null })) });
  return zipParts(entries, new Date(project.savedAt));
}
const shasOf = ref => { const o = {}; if (ref && isObj(ref.shas)) for (const r of ROLES) if (SHA_RE.test(String(ref.shas[r]))) o[r] = ref.shas[r]; return o; };
// An .aotp read and checked whole (contract 5; a knee case: section 12) → {project, image (Uint8Array, or {role:
// Uint8Array} for a knee case), caseRef}. Nothing is changed on the page.
export async function readAotp(u8) {
  if (u8.length > CASE_FILE_MAX) throw new ProjectError(`This project file is larger than ${MB(CASE_FILE_MAX)}.`);
  let files;
  try { files = await unzip(u8, { maxEntries: 6, maxTotal: CASE_ZIP_MAX }); } catch (e) { throw new ProjectError(`This is not a project file this page can open: ${e instanceof ZipError ? e.message : 'unreadable'}.`); }
  const names = [...files.keys()];
  const pj = files.get('project.json');
  if (pj && pj.length > PROJECT_MAX) throw new ProjectError(`This project's plan is larger than ${MB(PROJECT_MAX)}.`);
  let project = null;
  if (pj) { try { project = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(pj)); } catch (e) { project = undefined; } }
  let image;
  if (isObj(project) && project.v === 2) {   // a knee case: one image entry per film, nothing else
    { const pr = projectProblems(project); if (pr.length) throw new ProjectError(`This project cannot be opened: ${pr.slice(0, 4).join('; ')}${pr.length > 4 ? '; …' : ''}.`); }
    const imgs = names.filter(n => /^image-(ap|lat|sky|long)\.(dcm|png|jpg|webp)$/.test(n));
    const extra = names.filter(n => n !== 'project.json' && n !== 'case.json' && !imgs.includes(n));
    if (extra.length) throw new ProjectError(`This project file has an unexpected entry "${extra[0]}".`);
    const films = isObj(project.films) ? project.films : {};
    image = {};
    for (const n of imgs) {
      const role = n.slice(6, n.indexOf('.')), f = films[role];
      if (!isObj(f) || !isObj(f.image) || n !== `image-${role}.${MIME_EXT[f.image.mime]}`) throw new ProjectError(`This project file is damaged: the entry "${n}" is not one of its films.`);
      image[role] = files.get(n);
    }
    for (const r of Object.keys(films)) if (!image[r]) throw new ProjectError(`This project file is damaged: the ${ROLE_NAME[r] || 'unknown'} film's image is missing.`);
  } else {
    const imgs = names.filter(n => /^image\.(dcm|png|jpg|webp)$/.test(n));
    const extra = names.filter(n => n !== 'project.json' && n !== 'case.json' && !imgs.includes(n));
    if (extra.length) throw new ProjectError(`This project file has an unexpected entry "${extra[0]}".`);
    if (!pj || imgs.length !== 1) throw new ProjectError('This project file must hold project.json and one image.');
    if (u8.length > FILE_MAX || names.reduce((n, k) => n + files.get(k).length, 0) > ZIP_MAX) throw new ProjectError(`This project file expands to more than ${MB(ZIP_MAX)}.`);
    if (project === undefined) throw new ProjectError('This project file is damaged: project.json is not readable.');
    if (isObj(project) && isObj(project.image) && MIME_EXT[project.image.mime] && 'image.' + MIME_EXT[project.image.mime] !== imgs[0]) throw new ProjectError('This project file is damaged: the image is not the type the project says.');
    image = files.get(imgs[0]);
  }
  if (project === undefined || project === null) throw new ProjectError('This project file is damaged: project.json is not readable.');
  await checkProject(project, image);
  let caseRef = null;
  if (files.has('case.json')) {
    let c = null; try { c = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(files.get('case.json'))); } catch (e) { c = null; }
    if (!isObj(c) || !ID_RE.test(String(c.id)) || !Number.isInteger(c.rev) || c.rev < 1 || (c.sha256 !== null && c.sha256 !== undefined && !SHA_RE.test(String(c.sha256)))
      || (c.shas !== undefined && (!isObj(c.shas) || Object.entries(c.shas).some(([r, s]) => !ROLES.includes(r) || !SHA_RE.test(String(s)))))) throw new ProjectError('This project file is damaged: case.json is not valid.');
    caseRef = project.v === 2 ? { id: c.id, rev: c.rev, shas: shasOf(c) } : { id: c.id, rev: c.rev, sha256: c.sha256 || null };
  }
  return { project, image, caseRef };
}
// Collaborators typed by hand: "melesse, abebe@example.com" → ["melesse@addisortho.com", "abebe@example.com"]; null with
// the first one that is not an email (contract 2: a bare name is completed to @addisortho.com before sending)
const EMAIL_RE = /^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/;
export function parseCollaborators(text) {
  const list = [...new Set(String(text || '').toLowerCase().split(/[\s,;]+/).filter(Boolean).map(x => (x.includes('@') ? x : x + '@addisortho.com')))];
  const badOne = list.find(x => !EMAIL_RE.test(x) || x.length > 254);
  return badOne ? { bad: badOne } : list.length > 10 ? { bad: 'more than 10 people' } : { list };
}

// ======================= the page =======================
let H0 = null;
// the host (app.js), with what a host from before the knee case lacks filled in: the unit open is the X-ray itself, its
// case key its image's sha256 (test.mjs drives the save with such a stand-in)
const H_DEFAULTS = {
  unit() { return H0.S.img; }, isCase() { return false; }, caseKeyNow() { return (H0.S.orig && H0.S.orig.sha256) || null; },
  images() { const o = H0.S.orig; return o && o.bytes ? { [o.sha256]: { bytes: o.bytes, mime: o.mime, holder: o } } : {}; },
  keptNow() { return H0.projectNow().state; }, applyCaseState() { return []; }, caseWhy() { return ''; }, unsentRoles() { return []; },
  identNow() { const p = H0.S.patientIn || {}; return { name: p.name || '', recordNo: p.recordNo || '', hospital: p.hospital || '', age: Number.isInteger(p.age) ? p.age : null }; },
  setIdent(o) { H0.S.patientIn = { ...(H0.S.patientIn || {}), ...o, asked: true }; }, hospitalKnown() { return true; }, patientPending() { return false; }, async preparePatient() {},
  privacyKey() { return JSON.stringify([!!H0.S.set.share, H0.S.orig && H0.S.orig.sha256]); },
};
let H = null;                       // app.js: { S, STANDALONE, prompt, panel, projectNow, metaNow, fileName, loadProject, ... }
const OUT = { file: null, server: null };   // the last save's two outcomes: this device, the patient list
let busy = false;
const $ = id => document.getElementById(id);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ridOf = () => { const a = new Uint8Array(12); crypto.getRandomValues(a); return [...a].map(b => b.toString(16).padStart(2, '0')).join(''); };   // 24 of [a-z0-9]
function node(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; }
function button(text, fn, cls = '', title = '') { const b = node('button', cls, text); b.type = 'button'; b.onclick = fn; if (title) b.title = title; return b; }
function saveBlob(blob, name) {
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 30000);   // the object URL is let go once the download has its copy
}

// ---------- the API (contract 4): JSON, X-AOT on every change, a time limit, retries with growing waits ----------
export class ApiError extends Error { constructor(status, code, extra = {}) { super(code); this.status = status; this.code = code; this.extra = extra; } }
const BACKOFF = [400, 1000, 2500, 5000];   // ms before each retry of a dropped or busy request
// HIDING: the page is hidden or being closed (set on visibilitychange hidden and pagehide, cleared only when it is shown
// again), so every save sent meanwhile, also one that waited in the queue, goes with keepalive when it may: a JSON body of
// at most KEEPALIVE_MAX bytes as sent (UTF-8, not characters; the browser's keepalive budget is 64 kB for all of them).
// An image part never goes with keepalive, so a film still being sent, or a case not yet made, is never assumed to finish
// after the page is gone: what is unsent is kept for the next load instead (round 13b, Astra finding 5).
let HIDING = false;
export const KEEPALIVE_MAX = 60000;
export const keepaliveOk = (body, hiding = HIDING) => hiding && typeof body === 'string' && new TextEncoder().encode(body).length <= KEEPALIVE_MAX;
async function call(method, path, { json, body, timeout = 30000, raw = false } = {}) {
  const ac = new AbortController(), timer = setTimeout(() => ac.abort(), timeout), headers = {};
  if (method !== 'GET') headers['X-AOT'] = '1';   // CSRF (contract 4): every POST and PUT
  if (json !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(json); }
  else if (body !== undefined) headers['Content-Type'] = 'application/octet-stream';
  try {
    let r;
    const keepalive = keepaliveOk(body);   // never for raw image parts (a Uint8Array body)
    try { r = await fetch('/api' + path, { method, headers, body, credentials: 'same-origin', cache: 'no-store', signal: ac.signal, ...(keepalive ? { keepalive } : {}) }); }
    catch (e) { throw new ApiError(0, 'network'); }
    if (raw && r.ok) { try { return new Uint8Array(await r.arrayBuffer()); } catch (e) { throw new ApiError(0, 'network'); } }
    let js = null; try { js = await r.json(); } catch (e) { js = null; }
    if (!r.ok) throw new ApiError(r.status, (js && typeof js.error === 'string' && js.error) || 'http', js || {});
    if (!js || typeof js !== 'object') throw new ApiError(r.status, 'answer');
    return js;
  } finally { clearTimeout(timer); }
}
const retryable = e => e instanceof ApiError && (e.status === 0 || e.status === 500 || e.status === 502 || e.status === 503 || e.status === 504);
async function retrying(fn) {
  for (let i = 0; ; i++) {
    try { return await fn(); } catch (e) { if (!retryable(e) || i >= BACKOFF.length) throw e; await sleep(BACKOFF[i] * (0.8 + 0.4 * Math.random())); }
  }
}
// what a refused or failed request means, for the surgeon
function why(e) {
  if (!(e instanceof ApiError)) return String((e && e.message) || e);
  const s = e.status, c = e.code;
  if (s === 0) return 'no connection to t.addisortho.com';
  if (s === 401) return 'your sign-in has ended';
  if (s === 403) return c === 'forbidden' ? 'only the person who first saved this case, or someone allowed to see every templated patient, can do that' : 'the server refused the request (security check)';
  if (s === 404) return 'this case is not in your patient list (hidden, or not shared with you)';
  if (s === 413) return c === 'images' ? `the films are larger than ${MB(CASE_IMAGES_MAX)} together` : 'the X-ray or the plan is too large for the patient list';
  if (s === 422 && c === 'films') return 'this case now holds several films (a knee case): reload the page and open it from the list';
  if (s === 422) return c === 'pixels' ? `the image is larger than ${PIXELS_MAX / 1e6} megapixels` : 'the X-ray did not arrive complete; save again to send it again';
  if (s === 429) return 'too many unfinished uploads from your account; wait 15 minutes and save again';
  if (s === 503) return 'the patient list is busy; save again in a moment';
  if (s === 507) return 'the server is out of space (tell the administrator)';
  return `the server answered ${s}${c && c !== 'http' ? ` (${c})` : ''}`;
}

// ---------- the image sent to the server (contract 4): once per case, 2 MB parts, resumed ----------
// UPS: the uploads in progress, one per image (sha256), kept after a dropped connection so the next save of that image
// sends only the parts not yet stored. Uploads run one at a time (the save queue below; round 13: phones).
const UPS = new Map();
async function sendImage(o, progress) {
  for (let round = 0; round < 4; round++) {
    if (!UPS.has(o.sha256)) {
      const r = await retrying(() => call('POST', '/uploads', { json: { sha256: o.sha256, size: o.bytes.length, mime: o.mime } }));
      if (!ID_RE.test(String(r.uploadId)) || !(r.chunkSize > 0)) throw new ApiError(500, 'answer');
      UPS.set(o.sha256, { sha: o.sha256, uploadId: r.uploadId, chunkSize: r.chunkSize, n: Math.ceil(o.bytes.length / r.chunkSize), sent: new Set(), finished: false });
    }
    const up = UPS.get(o.sha256);
    if (up.finished) return up.uploadId;
    try {
      for (let i = 0; i < up.n; i++) {
        if (up.sent.has(i)) continue;
        progress(up.sent.size, up.n);
        const part = o.bytes.subarray(i * up.chunkSize, Math.min(o.bytes.length, (i + 1) * up.chunkSize));
        await retrying(() => call('PUT', `/uploads/${up.uploadId}/${i}`, { body: part, timeout: 120000 }));
        up.sent.add(i);
      }
      progress(up.n, up.n);
      await retrying(() => call('POST', `/uploads/${up.uploadId}/finish`, { json: {} }));
      up.finished = true; return up.uploadId;
    } catch (e) {
      if (!(e instanceof ApiError)) throw e;
      if (e.status === 404) { UPS.delete(o.sha256); continue; }                                    // gone (a day old, or replaced): start again
      if (e.status === 409 && e.code === 'finished') { up.finished = true; return up.uploadId; }
      if (e.status === 422 && e.code === 'incomplete' && Array.isArray(e.extra.missing)) { for (const m of e.extra.missing) up.sent.delete(m); continue; }
      if (e.status === 422) { UPS.delete(o.sha256); throw e; }                                    // the server has none of it now
      e.sent = up.sent.size; e.parts = up.n; throw e;                                  // dropped: kept for the next save
    }
  }
  throw new ApiError(422, 'incomplete');
}

// ---------- saving: the patient list first (t.addisortho.com), then the file on this device ----------
// A save is of what was on the page when its turn came (Astra's page re-review, 13 Oct, finding 5, and round 13b finding
// 2): the project, its revision and the case version it is saved on top of are taken together, inside the save queue, so
// a save never sends an older plan against a newer version. Another film opened while the save waits for the server is
// never mixed into it, and changes made meanwhile stay unsaved (only the revision saved is marked clean).
// Request identity (round 13b, findings 3 and 4): a new case's save is tied to its image (sha256), not to one film on the
// page: while its answer is unknown it is sent again exactly as it was (same requestId) by whichever film of that image
// saves next, and its requestId is kept for this tab (and this browser when plans are kept here), so after a reload a case
// the server made but whose answer was lost is found again by that requestId (the list's lastRequestId) instead of being
// made twice. A save of an existing case whose answer was lost keeps its requestId on the film until a later save has been
// answered, so the 409 it causes is recognised as this page's own and the next save goes on top.
function setServer(ctx, state, text, extra = {}) { if (!ctx.live() || ctx.auto) return; OUT.server = { state, text, ...extra }; renderOut(); }
function say(ctx, text) { if (ctx.live() && !ctx.auto) H.prompt(text); }
function progressText(done, n) { return n > 1 ? `Sending the X-ray to the patient list: part ${Math.min(done + 1, n)} of ${n}…` : 'Sending the X-ray to the patient list…'; }
// ---- a new case's identity, per case key (contract section 12: a knee case's own draft id, a single film's image) ----
const CREATES = new Map();   // case key → { rid, body }: a new case's save not yet answered (this page)
const PEND_TAB = 'aoc-tpl-pending', PEND_LOCAL = ck => 'aoc-tpl:pending:' + ck;
function pendingRid(ck) {
  const c = CREATES.get(ck); if (c) return c.rid;
  let r = null;
  try { r = (JSON.parse(sessionStorage.getItem(PEND_TAB) || '{}') || {})[ck] || null; } catch (e) { r = null; }
  if (!r && H.S.set.keepLocal) { try { r = JSON.parse(localStorage.getItem(PEND_LOCAL(ck)) || 'null'); } catch (e) { r = null; } }
  return isObj(r) && /^[a-z0-9-]{16,32}$/.test(String(r.rid)) ? r.rid : null;
}
// share: sharing mode when the body was built (a body built outside sharing mode is never replayed once it is on: round 5).
// Fail closed (round 6): a body stored without saying is never replayed (null matches no sharing state).
function setPending(ck, rid, body, film = null, share = null) {
  if (rid) CREATES.set(ck, { rid, body, film, share }); else CREATES.delete(ck);
  try {
    const m = JSON.parse(sessionStorage.getItem(PEND_TAB) || '{}') || {};
    if (rid) m[ck] = { rid, at: Date.now() }; else delete m[ck];
    sessionStorage.setItem(PEND_TAB, JSON.stringify(m));
  } catch (e) { /* storage off */ }
  try { if (rid && H.S.set.keepLocal) localStorage.setItem(PEND_LOCAL(ck), JSON.stringify({ rid, at: Date.now() })); else if (!rid) localStorage.removeItem(PEND_LOCAL(ck)); } catch (e) { /* storage off */ }
}
// a cached new-case body that must not be sent again (built under another sharing state): its requestId stays, so the case
// an earlier attempt may have made is found by it (caseByRequest) and saved over, never made twice
function noReplay(ck) { const c = CREATES.get(ck); if (c) c.body = null; }
// the case a meta names, as the page keeps it: a single film's {id, rev, sha256}; a knee case's {id, rev, shas: {role:
// sha256}} (the films' images the server holds)
function refFromMeta(m, ck) {
  if (isObj(m.images)) { const shas = {}; for (const r of ROLES) if (isObj(m.images[r]) && SHA_RE.test(String(m.images[r].sha256))) shas[r] = m.images[r].sha256; return { id: m.id, rev: m.rev, shas }; }
  return { id: m.id, rev: m.rev, sha256: (isObj(m.image) && m.image.sha256) || (SHA_RE.test(String(ck)) ? ck : null) };
}
// the case a lost new-case save made, found by its requestId in the whole list (null: certainly not made). The list is
// read to its end (round 13b re-review): a search that could not finish throws, so the requestId is kept and the search
// is made again later; it is never taken for "not made". A single film's case must also hold its image; a knee case's
// requestId (24 random hex) is enough.
async function caseByRequest(ck, rid) {
  let before = null;
  const kc = String(ck).startsWith('kc');
  for (let page = 0; page < 2000; page++) {
    const body = { limit: 50 }; if (before) body.before = before;
    const r = await call('POST', '/projects/list', { json: body });
    for (const m of Array.isArray(r.items) ? r.items : []) if (m.lastRequestId === rid && ID_RE.test(String(m.id)) && (kc || (isObj(m.image) && m.image.sha256 === ck))) return refFromMeta(m, ck);
    if (typeof r.next !== 'string') return null;   // the end of the list: not there
    if (r.next === before) break;
    before = r.next;
  }
  throw new ApiError(503, 'busy');   // not finished: tried again later, the requestId kept
}
// Sharing mode is checked again just before a plan leaves the page for the patient list (Astra, round 4 sweep): the plan on
// screen, taken before sharing mode changed, is not sent (it is taken again as it is now); a film no longer on screen is
// sent without the patient's name, age and record number when sharing mode was turned on since it was taken
// Checked before every transmission, each retry included (Astra, round 5): a body built under another sharing state is never
// sent. The attempt is abandoned (its requestId kept to reconcile an earlier attempt that may have reached the server, as
// section 11 says) and the save is made again from the plan as it is now; a film no longer on screen has the patient's
// name, age and record number taken out of its snapshot first when sharing mode was turned on.
const shareErr = () => { const e = new ProjectError('sharing mode changed while it was being saved; it is saved again as it is now'); e.shareChanged = true; return e; };
function shareGuard(ctx) {
  if (!!H.S.set.share === !!ctx.share0) return;
  if (!ctx.live() && !H.S.set.share) return;   // a film no longer on screen, its snapshot without identity: safe to send as it is
  const F = ctx.F;
  if (!ctx.live() && H.S.set.share && F && F.snap) { scrubForSharing(F.snap.project, F.snap.meta); F.snap.share = true; }
  throw shareErr();
}
// The one sharing-mode scrub (Fable, round 7): what a project and its list entry may carry in sharing mode, made so in place
// (the page's projectNow uses it too): the patient's hospital code only (no name, record number, age or study UID), and
// every image named only by its role ("image.<ext>", a knee film "image-<role>.<ext>": the page names files after the
// patient). → the project (and meta) given
export function scrubForSharing(project, meta = null) {
  const keep = o => { if (!isObj(o)) return; for (const k of Object.keys(o)) if (!['hospital', 'side', 'procedure'].includes(k)) delete o[k]; };
  if (isObj(project)) {
    keep(project.patient);
    if (isObj(project.image) && MIME_EXT[project.image.mime]) project.image.fileName = 'image.' + MIME_EXT[project.image.mime];
    if (isObj(project.films)) for (const [r, f] of Object.entries(project.films)) if (isObj(f) && isObj(f.image) && MIME_EXT[f.image.mime]) f.image.fileName = `image-${r}.${MIME_EXT[f.image.mime]}`;
  }
  if (isObj(meta)) keep(meta.patient);
  return project;
}
// one POST of a body, retried on a dropped connection or a busy server, with the sharing check before every attempt;
// uncertain(): an attempt may have reached the server (no answer, or 5xx)
async function guardedPost(ctx, body) {
  let uncertain = false;
  try { return await retrying(() => { shareGuard(ctx); return call('POST', '/projects/save', { json: body, timeout: 90000 }).catch(e => { if (retryable(e)) uncertain = true; throw e; }); }); }
  catch (e) { if (e.shareChanged) e.uncertain = uncertain; throw e; }
}
// one save to the server: {id, rev}; throws ApiError
async function postSave(ctx, project, meta, ref) {
  if (project.v === 2) return postCase(ctx, project, meta, ref);
  const img = project.image, F = ctx.F;
  let uploadId = null;
  if (!ref || ref.sha256 !== img.sha256) {   // the image goes up only when the server does not hold it for this case
    if (!ctx.orig.bytes) throw new ProjectError('the X-ray itself is no longer on this page (open it again to save it)');
    uploadId = await sendImage({ bytes: ctx.orig.bytes, sha256: img.sha256, mime: img.mime }, (d, n) => { setServer(ctx, 'busy', progressText(d, n)); say(ctx, progressText(d, n)); });
  }
  setServer(ctx, 'busy', 'Saving the plan to the patient list…');
  shareGuard(ctx);   // before the body is built and before every attempt to send it (Astra, rounds 4 and 5)
  const body = { requestId: ridOf(), meta, project };   // one requestId for this save and all its retries (contract 4)
  if (ref) { body.id = ref.id; body.baseRev = ref.rev; }
  if (uploadId) body.uploadId = uploadId;
  if (!ref) setPending(img.sha256, body.requestId, body, F, !!ctx.share0);   // known before it is sent: a reopened film or a reload finds it
  let r;
  try { r = await guardedPost(ctx, body); }
  catch (e) {   // no answer at all: the save may or may not have been made, which the next save finds out
    const unknown = (e instanceof ApiError && (e.status === 0 || e.status >= 500)) || (e.shareChanged && e.uncertain);
    if (!ref) { if (!unknown) setPending(img.sha256, null); else if (e.shareChanged) noReplay(img.sha256); }
    else if (unknown) F.lost = [...(F.lost || []), body.requestId].slice(-8);   // kept through the next save's 409 (finding 4)
    throw e;
  }
  if (!ref) { setPending(img.sha256, null); if (ID_RE.test(String(r.id))) remember(img.sha256, { id: r.id, rev: r.rev, sha256: img.sha256 }); }   // every film of this image knows its case at once
  F.lost = [];
  if (uploadId) UPS.delete(img.sha256);   // used up by the save
  if (!ID_RE.test(String(r.id)) || !Number.isInteger(r.rev)) throw new ApiError(500, 'answer');
  return r;
}
// A knee case's save (contract section 12): the films whose images the case does not hold yet go up one at a time, each
// with its own save (one upload open at a time, well within the server's three), every save one whole revision of the
// films the case then holds and the shared plan; the last one holds them all. A save of a case not yet made carries the
// case key's requestId (kept before it is sent, as for a single film); a lost answer is recognised as for a single film.
// → the last answer {id, rev}; ctx.ref follows every answered step, so a failure half-way goes on from there next time.
async function postCase(ctx, project, meta, ref) {
  const F = ctx.F, imgs = ctx.images || {};
  let cur = ref, r = null;
  for (let step = 0; step < ROLES.length + 1; step++) {
    const held = new Set(Object.values(cur ? shasOf(cur) : {}));
    const missing = ROLES.filter(rl => project.films[rl] && !held.has(project.films[rl].image.sha256)), add = missing[0] || null;
    const films = {}; for (const rl of ROLES) { const f = project.films[rl]; if (f && (held.has(f.image.sha256) || rl === add)) films[rl] = f; }
    const body0 = { ...project, films, active: films[project.active] ? project.active : Object.keys(films)[0] };
    let uploads = null;
    if (add) {
      const im = project.films[add].image, src = imgs[im.sha256];
      if (!src || !src.bytes) throw new ProjectError(`the ${ROLE_NAME[add]} film's X-ray itself is no longer on this page (open it again to save it)`);
      const what = `Sending the ${ROLE_NAME[add]} film to the patient list`;
      const upId = await sendImage({ bytes: src.bytes, sha256: im.sha256, mime: im.mime }, (d, n) => { const t = n > 1 ? `${what}: part ${Math.min(d + 1, n)} of ${n}…` : `${what}…`; setServer(ctx, 'busy', t); say(ctx, t); });
      uploads = { [add]: upId };
    }
    setServer(ctx, 'busy', 'Saving the plan to the patient list…');
    shareGuard(ctx);
    const body = { requestId: ridOf(), meta, project: body0 };
    if (cur) { body.id = cur.id; body.baseRev = cur.rev; }
    if (uploads) body.uploads = uploads;
    if (!cur) setPending(ctx.ck, body.requestId, body, F, !!ctx.share0);
    try { r = await guardedPost(ctx, body); }
    catch (e) {
      const unknown = (e instanceof ApiError && (e.status === 0 || e.status >= 500)) || (e.shareChanged && e.uncertain);
      if (!cur) { if (!unknown) setPending(ctx.ck, null); else if (e.shareChanged) noReplay(ctx.ck); }
      else if (unknown) F.lost = [...(F.lost || []), body.requestId].slice(-8);
      throw e;
    }
    if (!ID_RE.test(String(r.id)) || !Number.isInteger(r.rev)) throw new ApiError(500, 'answer');
    if (!cur) setPending(ctx.ck, null);
    F.lost = [];
    if (uploads) UPS.delete(project.films[add].image.sha256);   // used up by the save
    const shas = {}; for (const [rl, f] of Object.entries(films)) shas[rl] = f.image.sha256;
    cur = { id: r.id, rev: r.rev, shas };
    ctx.ref = cur; remember(ctx.ck, cur); rememberFilms(ctx.ck, cur);   // every opening of the case and of its films knows it at once
    if (missing.length <= (add ? 1 : 0)) break;   // this save held every film
  }
  return r;
}
// before a new case is made for an image: a save of that image still unanswered is sent again as it was, or (after a
// reload) found by its requestId → the case, or null when none was made
// → { ref, own } (own: made by this film's own earlier save) or null. Every new case of an image, from the film on screen
// or one in the background, goes through here first, so there is one case per image (round 13b re-review, finding 3).
async function pendingCase(ck, F = null) {
  const k = knownCase(ck);   // another opening of this X-ray (or of this knee case) has its case already
  if (k) return { ref: k, own: false };
  const c = CREATES.get(ck);
  if (c && c.body && c.share !== !!H.S.set.share) noReplay(ck);   // built under another sharing state: never replayed (round 5)
  if (c && c.body) {
    const own = !!F && c.film === F;
    try {
      const r = await retrying(() => { if (c.share !== !!H.S.set.share) throw shareErr(); return call('POST', '/projects/save', { json: c.body, timeout: 90000 }); }); setPending(ck, null);
      let got;
      if (c.body.project && c.body.project.v === 2) {   // a knee case: the films that save held
        const shas = {}; for (const [rl, f] of Object.entries(c.body.project.films || {})) shas[rl] = f.image.sha256;
        for (const s of Object.values(shas)) UPS.delete(s);
        got = { id: r.id, rev: r.rev, shas }; rememberFilms(ck, got);
      } else if (String(ck).startsWith('kc')) {   // a single film's create, then made a knee case (toCase): that film is the case's first
        const s0 = c.body.project.image.sha256; UPS.delete(s0); got = { id: r.id, rev: r.rev, shas: { [c.role || 'ap']: s0 } }; rememberFilms(ck, got);
      } else { UPS.delete(ck); got = { id: r.id, rev: r.rev, sha256: ck }; }
      remember(ck, got); return { ref: got, own };
    } catch (e) { if (e.shareChanged) { noReplay(ck); throw e; } if (!(e instanceof ApiError) || e.status === 0 || e.status >= 500) throw e; setPending(ck, null); if (SHA_RE.test(ck)) UPS.delete(ck); return null; }   // refused: none was made by it
  }
  const rid = pendingRid(ck), mine = !!F && !!c && c.film === F;
  if (!rid) return null;
  const found = await caseByRequest(ck, rid);   // throws when the list cannot be read to its end: tried again later
  setPending(ck, null);
  if (found) { remember(ck, found); if (found.shas) rememberFilms(ck, found); }
  return found ? { ref: found, own: mine } : null;   // (its own earlier attempt, found by its requestId: saved on top, so the entry is rewritten as it is now)
}
// the patient list part of a save, with its choices (a newer version, a case gone): → {ok, text, reload?}; the case it
// ends with is ctx.ref. An automatic save (ctx.auto) never asks: a newer version or a case gone is answered as
// {ok: false, conflict} / {ok: false, gone}, and nothing is overwritten.
async function saveToServer(ctx, project, meta) {
  const F = ctx.F, sha = project.v === 2 ? null : project.image.sha256;
  if (!ctx.ck) ctx.ck = project.v === 2 ? caseKey(project.case.cid) : sha;
  if (!ctx.ref && !ctx.copy) {   // a new case of this image (or knee case) may already exist (another opening of it, a save whose answer was lost)
    const got = await pendingCase(ctx.ck, F);
    if (got && got.own) ctx.ref = got.ref;   // this film's own: its save goes on top
    else if (got) {   // another opening of this X-ray made it: never one plan over the other
      if (ctx.auto) return { ok: false, other: got.ref };
      if (!ctx.live()) return { ok: false, text: 'Not saved to the patient list: this X-ray is already a case there (from another opening of it), and another X-ray was opened meanwhile.' };
      const pick = await choose('This X-ray is already a case in your patient list, saved from another opening of it.', [['Save as my copy', 'copy', 'primary'], ['Cancel', null, '']],
        'Save as my copy: this plan becomes a new case. To go on with the other, open it from the list.');
      if (pick !== 'copy') return { ok: false, text: 'Not saved to the patient list: this X-ray is already a case there; open it from the list.' };
      ctx.copy = true;
    }
  }
  for (let tries = 0; tries < 3; tries++) {
    const ref = ctx.ref;
    try {
      const r = await postSave(ctx, project, meta, ref);
      if (project.v !== 2) ctx.ref = { id: r.id, rev: r.rev, sha256: sha };   // (a knee case's: set by postCase, with its films)
      return { ok: true, text: `Saved to the patient list${r.rev > 1 ? ` (version ${r.rev})` : ''}.` };
    } catch (e) {
      if (!(e instanceof ApiError)) throw e;
      const ref = ctx.ref;   // a knee case's save may have made a step or two before this answer
      if (e.status === 422 && (e.code === 'image' || e.code === 'upload') && tries === 0) {   // send the image(s) (again) after all
        if (project.v === 2) { for (const f of Object.values(project.films)) UPS.delete(f.image.sha256); if (ref) ctx.ref = { ...ref, shas: {} }; }
        else { UPS.delete(sha); if (ref) ctx.ref = { ...ref, sha256: null }; }
        continue;
      }
      if (e.status === 409 && e.code === 'stale' && ref) {
        // the newer version may be this page's own earlier save whose answer was lost: then save on top of it
        if (F.lost && F.lost.length) {
          let m = null; try { m = (await call('GET', `/projects/${ref.id}`)).meta; } catch (er) { m = null; }
          if (m && F.lost.includes(m.lastRequestId) && Number.isInteger(m.rev)) { F.lost = []; ctx.ref = project.v === 2 ? refFromMeta(m, ctx.ck) : { ...ref, rev: m.rev }; continue; }
        }
        if (ctx.auto) return { ok: false, conflict: true, rev: e.extra.rev };
        if (!ctx.live()) return { ok: false, conflict: true, text: `Not saved to the patient list: a newer version is there (version ${e.extra.rev}), and another X-ray was opened meanwhile.` };
        const pick = await choose(`Someone saved a newer version of this case (version ${e.extra.rev}) after you opened it.`,
          [['Reload the newer version', 'reload', 'primary'], ['Save as my copy', 'copy', ''], ['Cancel', null, '']],
          'Reload: the newer version replaces yours on the screen (yours is still saved to this device). Save as my copy: yours becomes a new case in the list, the other stays as it is.');
        if (pick === 'reload') return { ok: false, conflict: true, text: `Not saved to the patient list: a newer version is there (version ${e.extra.rev}); it is opened now.`, reload: ref.id };
        if (pick === 'copy') { ctx.ref = null; continue; }
        return { ok: false, conflict: true, text: `Not saved to the patient list: a newer version is there (version ${e.extra.rev}).` };
      }
      if (e.status === 404 && ref) {
        if (ctx.auto) return { ok: false, gone: true };
        if (!ctx.live()) return { ok: false, gone: true, text: 'Not saved to the patient list: the case is no longer in your list, and another X-ray was opened meanwhile.' };
        const pick = await choose('This case is no longer in your patient list (it was hidden, or it is not shared with you).', [['Save as my copy', 'copy', 'primary'], ['Cancel', null, '']]);
        if (pick === 'copy') { ctx.ref = null; continue; }
        return { ok: false, gone: true, text: 'Not saved to the patient list: the case is no longer in your list.' };
      }
      throw e;
    }
  }
  return { ok: false, text: 'Not saved to the patient list: it changed again meanwhile. Save again.' };
}
const sameRef = (a, b) => (!a && !b) || (!!a && !!b && a.id === b.id && a.rev === b.rev);

// Save project (the header button, and the Measurements and save segment): t.addisortho.com first saves to the patient
// list, then the file goes to this device either way, with the two outcomes shown apart (Astra, 13 Oct). Since round 13
// the list is also saved automatically (below); Save project waits for a save under way, then saves the plan as it is at
// that moment (the film still on screen) and downloads that. If another film was opened meanwhile, the plan as it was
// when pressed is saved only when no save of that film was answered since (else it is older than what the list has).
// The file saved to this device is never taken for the patient list: the film stays "not saved" there until the server
// answers (round 13b, finding 1).
export async function saveProject() {
  const S = H.S;
  if (busy || !S.img || document.querySelector('.dlg')) return;   // one save at a time (a second tap while it runs or asks does nothing)
  const pk0 = H.privacyKey();   // the file is written only if the X-ray, the case and sharing mode are as they were (Astra, round 4, P2)
  if (!S.orig || !S.orig.sha256) return H.prompt(S.origWhy || 'This X-ray cannot be saved as a project: its original file is not available here. Open the file again.');
  { const cw = H.caseWhy ? H.caseWhy() : ''; if (cw) return H.prompt(cw); }   // a knee case with a film whose original is not on this page
  if (S.filling) return H.prompt('Wait a moment: a stroke is still being filled.');
  if (!S.set.share && !H.patientKnown()) { const ok = await askPatient(); if (!ok) return; }   // (15 Oct: the question has the hospital and the age too)
  const F = FILM && live(FILM) ? FILM : { stub: true, lost: [] };
  let project, meta;
  try { project = H.projectNow(); meta = metaFor(F); aotpCheck(project); } catch (e) { return H.prompt(`The project could not be saved: ${e.message || e}.`); }
  const ctx = { F, unit: H.unit(), img: S.img, orig: S.orig, images: H.images(), ck: H.caseKeyNow(), ref: null, ref0: null, rev: H.revision(), at: Date.now(), uid: F.uid, name: H.fileName(), refAtPress: F.stub ? S.caseRef : refOf(F), share0: !!S.set.share };
  ctx.live = () => H.unit() === ctx.unit;
  busy = true; syncButtons();
  OUT.file = null; OUT.server = null;
  let reload = null, file = null, server = null, r = null;
  try {
    if (H.STANDALONE) {
      setServer(ctx, 'busy', 'Saving to the patient list…'); say(ctx, 'Saving the project…');
      if (!F.stub) status(F, 'saving');
      try {
        r = await serial(async () => {
          const ref = F.stub ? S.caseRef : refOf(F);
          if (ctx.live()) {   // the plan, its revision and the case version, taken together now (finding 2)
            try { await H.preparePatient(); } catch (e) { return { ok: false, prepErr: e, text: `Not saved to the patient list: ${why(e)} (the case's patient is read from the list first); it is tried again.` }; }
            if (!ctx.live()) return { ok: false, stale: true, text: 'Not saved to the patient list by this press: another X-ray was opened meanwhile.' };
            try { project = H.projectNow(); meta = metaFor(F); aotpCheck(project); ctx.rev = H.revision(); ctx.at = Date.now(); ctx.uid = F.uid; ctx.images = H.images(); ctx.partial = unsentNow(F); } catch (e) { return { ok: false, text: `Not saved to the patient list: ${e.message || e}.` }; }
          } else if (!sameRef(ref, ctx.refAtPress)) {
            return { ok: false, stale: true, text: `Not saved to the patient list by this press: the plan was saved there meanwhile${ref ? ` (version ${ref.rev})` : ''}, after the moment you pressed.` };
          }
          ctx.ref = ctx.ref0 = ref;
          return saveToServer(ctx, project, meta);
        });
        server = { state: r.ok ? 'ok' : 'fail', text: r.text + (r.ok && !S.set.share && !H.hospitalKnown() ? ' Hospital not set: pick it under Patient (Measurements and save), so the case is listed under its hospital.' : '') }; reload = r.reload || null;
        if (!F.stub) {
          if (r.ok) { F.conflict = false; F.gone = false; F.created = true; acked(F, ctx.rev, ctx.at, ctx.uid, !!ctx.partial); status(F, 'saved'); }
          else if (r.conflict) { F.conflict = true; status(F, 'conflict'); }
          else if (r.gone) { F.gone = true; status(F, 'fail', 'Not saved: this case is no longer in your patient list', 'The case was hidden, or it is no longer shared with you. Save project offers to save it as your own copy.'); }
          else if (r.prepErr) failed(F, r.prepErr);   // the patient could not be read: offline handling and a retry, as an automatic save (Fable, round 9)
        }
      } catch (e) {
        const part = e instanceof ApiError && e.parts > 1 && e.status === 0 ? ` (${e.sent} of ${e.parts} parts of the X-ray sent; Save project again goes on from there)` : '';
        server = { state: 'fail', text: `Not saved to the patient list: ${why(e)}${part}.`, signin: e instanceof ApiError && e.status === 401 };
        if (!F.stub) failed(F, e);
      }
    }
    // sharing mode (Astra, round 2): no project file on this device. It would carry the original X-ray, and a DICOM header can
    // name the patient; blanking its tags reliably is beyond the page's small DICOM reader (sequences, private and
    // compressed files), so the file is not written. The saved picture (PNG) is the copy for sharing.
    if (S.set.share) file = { ok: false, skipped: true, text: 'Sharing mode: no project file saved to this device (it would hold the original X-ray, whose DICOM header can name the patient). Use Save picture for sharing.' };
    else if (H.privacyKey() !== pk0) file = { ok: false, skipped: true, text: 'No project file saved to this device: the X-ray on screen, the case or sharing mode changed while it was being saved. Save project again.' };
    else try {
      saveBlob(new Blob(fileParts(ctx, project), { type: 'application/zip' }), ctx.name);
      file = { ok: true, text: `File saved to this device: ${ctx.name}${reload ? ' (your version)' : ''}.` };
    } catch (e) { file = { ok: false, text: `File not saved to this device: ${e.message || e}.` }; }
    const saved = file.ok || (server && server.state === 'ok');
    // the case the film belongs to, unless the list changed it meanwhile (Share raised its revision, Hide forgot it): then
    // the list's word stands, and the next save sorts it out. A film no longer on screen keeps it too, for its own saves.
    if (H.STANDALONE && !(r && r.stale)) {
      const cur = F.stub ? S.caseRef : refOf(F);
      const next = cur === ctx.ref0 ? ctx.ref : cur && ctx.ref && cur.id === ctx.ref.id ? { ...ctx.ref, rev: Math.max(ctx.ref.rev, cur.rev) } : cur;
      if (F.stub) { if (ctx.live()) S.caseRef = next; } else setRef(F, next);
    }
    if (ctx.live()) {
      OUT.file = file; OUT.server = server;
      if (saved) H.markSaved(ctx.rev);   // the page's own "unsaved" mark (the file has it); the patient list's is acked() above
      H.prompt([file.text, server ? server.text : ''].filter(Boolean).join(' '));
    } else {   // another X-ray was opened meanwhile: its own outcomes are not these
      reload = null;
      H.prompt(`The project of the X-ray open before was saved meanwhile: ${[file.text, server ? server.text : ''].filter(Boolean).join(' ')}`);
    }
  } finally { busy = false; H.panel(); syncButtons(); renderOut(); render(); }   // the panel: the name typed, the outcomes
  if (reload && ctx.live()) {   // "Reload the newer version": it replaces the plan on the screen; this one is in the file just saved
    const keep = OUT.file;
    F.replaced = true;   // chosen: the plan on screen is not kept to be offered back
    try { await openCase(reload, { force: true }); OUT.server = { state: 'ok', text: 'The newer version from the patient list is on the screen now.' }; }
    catch (e) { OUT.server = { state: 'fail', text: `The newer version could not be opened: ${e instanceof ApiError ? why(e) : e.message || e}.` }; }
    OUT.file = keep; renderOut(); H.prompt([OUT.file && OUT.file.text, OUT.server.text].filter(Boolean).join(' '));
  }
}
// the page refuses to save a project it would refuse to open (contract 7), its implants against this library too (a knee
// case: every film's)
function aotpCheck(p) {
  const lib = p.v === 2 ? Object.values(p.films || {}).flatMap(f => libraryProblems(f.state, H.S.lib)) : libraryProblems(p.state, H.S.lib);
  const pr = [...projectProblems(p), ...lib]; if (pr.length) throw new Error(pr.slice(0, 3).join('; '));
}
// the .aotp of a save: one image, or a knee case's films (each image's CRC worked out once and kept with its bytes)
function fileParts(ctx, project) {
  const crcOf = o => { if (o.crc === undefined) o.crc = crc32(o.bytes); return o.crc; };
  const caseRef = H.STANDALONE ? ctx.ref : null;
  if (project.v !== 2) { const o = ctx.orig; return aotpParts(project, o.bytes, { caseRef, imageCrc: crcOf(o) }); }
  const by = {}, crc = {};
  for (const [r, f] of Object.entries(project.films)) {
    const o = ctx.images && ctx.images[f.image.sha256];
    if (!o || !o.bytes) throw new ProjectError(`the ${ROLE_NAME[r]} film's X-ray itself is not on this page (open it again)`);
    by[r] = o.bytes; crc[r] = crcOf(o.holder || o);
  }
  return aotpParts(project, by, { caseRef, imageCrc: crc });
}

// the two outcomes, in the Measurements and save segment (rebuilt with the panel, updated in place while saving)
export function outcomeEl() {
  const d = node('div', 'projout'); d.id = 'projOut'; d.setAttribute('role', 'status'); fillOut(d); return d;
}
function fillOut(d) {
  d.textContent = '';
  const line = (o, cls) => { const p = node('div', cls, o.text); d.appendChild(p); return p; };
  if (OUT.file) line(OUT.file, OUT.file.ok ? 'ok' : 'bad');
  if (OUT.server) {
    const p = line(OUT.server, { ok: 'ok', busy: 'busy', fail: 'bad' }[OUT.server.state] || '');
    if (OUT.server.signin) { p.append(' '); p.appendChild(signinLink()); }
  }
  d.hidden = !OUT.file && !OUT.server;
}
function renderOut() { const d = $('projOut'); if (d) fillOut(d); }
function signinLink() {
  const a = node('a', 'signin', 'Please sign in again'); a.href = '/'; a.target = '_blank'; a.rel = 'noopener';
  a.title = 'Opens the sign-in in a new tab; then come back to this tab and try again (nothing on this page is lost)';
  return a;
}
function syncButtons() {
  for (const b of document.querySelectorAll('.projsave')) { b.disabled = busy || !H.S.img; b.classList.toggle('busy', busy); }
}

// ---------- small dialogs (inside .pv: the page's keys stay off while one is open) ----------
function dialog(build) {
  return new Promise(res => {
    const prev = document.activeElement, d = node('div', 'pv dlg'), box = node('div', 'pvbox'); d.setAttribute('role', 'dialog'); d.setAttribute('aria-modal', 'true');
    d.appendChild(box); document.body.appendChild(d);
    const done = v => { d.remove(); if (prev && prev.focus) try { prev.focus({ preventScroll: true }); } catch (e) { /* gone */ } res(v); };
    d.addEventListener('keydown', e => { if (e.key === 'Escape') { e.stopPropagation(); done(null); } });
    build(box, done);
    const f = box.querySelector('input, button.primary, button'); if (f) f.focus();
  });
}
// more: a note under the question, or a list of short lines (the list's permanent delete: what would be lost). The first
// option is the one focused (dialog focuses the first input or button in the box), so Cancel goes first where it is the default.
function choose(text, options, more = '') {
  return dialog((box, done) => {
    box.appendChild(node('p', '', text));
    if (Array.isArray(more)) { const l = node('ul', 'dlgfacts'); for (const t of more) l.appendChild(node('li', '', t)); box.appendChild(l); }
    else if (more) box.appendChild(node('p', 'note', more));
    const r = node('div', 'row'); for (const [label, v, cls] of options) r.appendChild(button(label, () => done(v), cls)); box.appendChild(r);
  });
}
const clip = (s, n) => String(s || '').replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, n);
// a picture without the patient's details (a phone photo of the film), or a case whose hospital is not known: asked once
// for this X-ray or case, for the list and the file name; "Leave them out" is a choice too. The hospital is picked from the
// list (HOSPITALS) or typed as its initials; the record number is stored with the hospital's code in front (Sami, 15 Oct).
function askPatient() {
  const id = H.identNow();
  return dialog((box, done) => {
    box.appendChild(node('p', '', !id.name && !id.recordNo ? 'This X-ray carries no patient name or record number. Add them for the templated-patients list and the file name?' : 'Which hospital is this patient from? The patient list keeps each hospital\'s patients apart (the record number is stored as, for example, SSC-12345).'));
    const f = node('div', 'fields');
    const hs = node('select'); hs.setAttribute('aria-label', 'Hospital'); hs.appendChild(new Option('(not set)', ''));
    for (const h of HOSPITALS) hs.appendChild(new Option(`${h.code} · ${h.name}`, h.code));
    if (id.hospital && !HOSPITALS.some(h => h.code === id.hospital)) hs.appendChild(new Option(id.hospital, id.hospital));
    hs.value = id.hospital || '';
    const ho = node('input'); ho.type = 'text'; ho.autocomplete = 'off';   // no maxLength on identity: checked whole, never cut (Astra, round 4)
    ho.placeholder = 'or type its initials'; ho.setAttribute('aria-label', 'Hospital initials');
    const nm = node('input'); nm.type = 'text'; nm.autocomplete = 'off'; nm.setAttribute('aria-label', 'Patient name'); nm.value = id.name || '';
    const ag = node('input'); ag.type = 'number'; ag.min = '0'; ag.max = String(AGE_MAX); ag.step = '1'; ag.setAttribute('aria-label', 'Age in years'); ag.value = Number.isInteger(id.age) ? String(id.age) : '';
    const rn = node('input'); rn.type = 'text'; rn.autocomplete = 'off'; rn.setAttribute('aria-label', 'Record number'); rn.value = mrnCore(id.hospital, id.recordNo);
    const lab = (t, ...els) => { const l = node('label', '', t + ' '); l.append(...els); return l; };
    f.append(lab('Patient name', nm), lab('Record number', rn), lab('Age', ag), lab('Hospital', hs, ho)); box.appendChild(f);
    const msg = node('p', 'note'); box.appendChild(msg);
    const set = (name, recordNo) => {
      const typed = ho.value.trim().toUpperCase(), hosp = typed || hs.value;
      if (hosp && !HOSP_RE.test(hosp)) { msg.textContent = 'Hospital initials: 2 to 6 capital letters (for example SSC).'; return; }
      const a = ag.value.trim() === '' ? null : Number(ag.value);
      if (a !== null && !(Number.isInteger(a) && a >= 0 && a <= AGE_MAX)) { msg.textContent = `Age: a whole number of years from 0 to ${AGE_MAX}.`; return; }
      if (cleanId(name).length > NAME_MAX) { msg.textContent = `Name too long: at most ${NAME_MAX} characters (it is never cut automatically).`; return; }
      if (!mrnOk(hosp, cleanId(recordNo))) { msg.textContent = `Record number too long: with the hospital's code at most ${MRN_MAX} characters (it is never cut automatically).`; return; }
      H.setIdent({ name: cleanId(name), recordNo: cleanId(recordNo), hospital: hosp, age: name || recordNo ? a : null }); done(true);
    };
    const r = node('div', 'row');
    r.append(button('Save with these', () => set(nm.value, rn.value), 'primary'), button('Leave them out', () => set('', '')), button('Cancel', () => done(false)));
    box.appendChild(r);
    box.addEventListener('keydown', e => { if (e.key === 'Enter' && e.target.tagName === 'INPUT') { e.preventDefault(); set(nm.value, rn.value); } });
  });
}

// ---------- opening: a project file, or a case from the patient list ----------
// an .aotp from this device: read and checked whole before the film on screen is replaced
export async function openAotp(u8, name) {
  const { project, image, caseRef } = await readAotp(u8);
  await H.loadProject(project, image, { caseRef, how: 'file', name });
}
// a case from the patient list: its project and image, checked like a file (the image is not fetched again when it is the
// one on screen, e.g. "Reload the newer version")
// bytes: the image's bytes when the page already has them (a film opened again whose case is known; round 13)
export async function openCase(id, { force = false, bytes: have = null, how = 'list', sha: haveSha = null, role = null } = {}) {
  const S = H.S;
  if (!ID_RE.test(String(id))) return;
  // the film on screen is saved automatically before another opens (round 13), unless it cannot be
  if (!force && S.img && H.isDirty() && !autoCovers() && !confirm('The X-ray on screen has changes not saved in a project. Open the other case anyway?')) return false;
  const got = await call('GET', `/projects/${id}`);
  const meta = got.meta, project = got.project;
  if (isObj(meta) && isObj(project) && project.v === 2) return openKneeCase(id, meta, project, { have, haveSha, how, role });
  if (!isObj(meta) || !isObj(project) || !isObj(project.image)) throw new ProjectError('The patient list sent a case this page cannot read.');
  const pr = projectProblems(project); if (pr.length) throw new ProjectError(`This case cannot be opened: ${pr.slice(0, 3).join('; ')}.`);
  let bytes = have && have.length === project.image.size ? have : S.orig && S.orig.sha256 === project.image.sha256 ? S.orig.bytes : null;
  if (!bytes) { if (project.image.size > IMAGE_MAX) throw new ProjectError('This case\'s X-ray is too large.'); bytes = await call('GET', `/projects/${id}/image`, { raw: true, timeout: 180000 }); }
  await checkProject(project, bytes);
  // a case saved before its patient was named keeps its placeholder in the list (round 13) until a name is given
  const mp = isObj(meta.patient) ? meta.patient : {}, pp = isObj(project.patient) ? project.patient : {};
  NEXT_PLACEHOLDER = !pp.name && !pp.recordNo && typeof mp.name === 'string' && mp.name.startsWith(PLACEHOLDER) ? mp.name.slice(0, 120) : null;
  try { await H.loadProject(project, bytes, { caseRef: { id, rev: meta.rev, sha256: (meta.image && meta.image.sha256) || project.image.sha256 }, how }); }
  finally { NEXT_PLACEHOLDER = null; }
  return true;
}
// the patient a case in the list holds (its meta's: name, record number, hospital, age as stored); throws when it cannot be
// read (Fable, round 7: a knee case restored from kept changes takes its patient from here before its first save)
export async function casePatient(id) {
  if (!ID_RE.test(String(id))) throw new ProjectError('not a case id');
  const got = await call('GET', `/projects/${id}`);
  const p = isObj(got.meta) && isObj(got.meta.patient) ? got.meta.patient : {};
  const o = {}; for (const k of ['name', 'recordNo', 'hospital']) if (typeof p[k] === 'string' && p[k] && !p[k].startsWith(PLACEHOLDER)) o[k] = p[k]; if (Number.isInteger(p.age)) o.age = p.age;
  return o;
}
// a knee case from the patient list (contract section 12): every film's image fetched by its role (one at a time; an
// image already on this page, or the file just opened, is not fetched again), the whole case checked, then opened with
// the film just opened (haveSha) or the one last shown in front
async function openKneeCase(id, meta, project, { have = null, haveSha = null, how = 'list', role = null } = {}) {
  const pr = projectProblems(project); if (pr.length) throw new ProjectError(`This case cannot be opened: ${pr.slice(0, 3).join('; ')}.`);
  const imgs = {}, local = H.images();
  for (const [r, f] of Object.entries(project.films)) {
    const sha = f.image.sha256;
    let b = have && haveSha === sha && have.length === f.image.size ? have : local[sha] && local[sha].bytes && local[sha].bytes.length === f.image.size ? local[sha].bytes : null;
    if (!b) b = await call('GET', `/projects/${id}/image/${r}`, { raw: true, timeout: 180000 });
    imgs[r] = b;
  }
  await checkProject(project, imgs);
  const mp = isObj(meta.patient) ? meta.patient : {}, pp = isObj(project.patient) ? project.patient : {};
  NEXT_PLACEHOLDER = !pp.name && !pp.recordNo && typeof mp.name === 'string' && mp.name.startsWith(PLACEHOLDER) ? mp.name.slice(0, 120) : null;
  const front = haveSha ? Object.keys(project.films).find(r => project.films[r].image.sha256 === haveSha) : role && project.films[role] ? role : null;   // (a film's own button in the list: that film in front)
  try { await H.loadProject(project, imgs, { caseRef: refFromMeta(meta, caseKey(project.case.cid)), how, role: front || null }); }
  finally { NEXT_PLACEHOLDER = null; }
  return true;
}

// ---------- who is signed in (13 Oct 2026): GET /api/me -> {uid, em, ca, rg} ----------
// Share, Hide and Delete (permanent) are offered only where the server would allow them (contract 2 and 4: the case's owner, or someone who
// may see every templated patient); the server still decides. Asked once per page; if the answer does not come, both
// are offered as before and a refusal is explained.
let ME;   // undefined: not asked yet; null: no answer
async function me() {
  if (ME !== undefined) return ME;
  try { const r = await call('GET', '/me'); ME = r && Number.isInteger(r.uid) && (r.ca === 'all' || r.ca === 'own') ? { uid: r.uid, ca: r.ca } : null; }
  catch (e) { ME = null; }
  return ME;
}
const mayManage = (m, viewer) => !viewer || viewer.ca === 'all' || (isObj(m.owner) && m.owner.uid === viewer.uid);

// ---------- the templated-patients list (t.addisortho.com only) ----------
const SLOTN = { cupR: 'Cup R', cupL: 'Cup L', stemR: 'Stem R', stemL: 'Stem L', headR: 'Head R', headL: 'Head L', bipR: 'Bipolar R', bipL: 'Bipolar L', linerR: 'Liner R', linerL: 'Liner L', diaDefect: 'Defect', diaStemN: 'Stem, ref. side', diaStemF: 'Stem, far side', diaBody: 'Body', femur: 'Femur', tibia: 'Tibia', insert: 'Insert' };
const shortLabel = l => String(l || '').replace(/^Addis Ortho\s+/, '').replace(/^(cementless cup|total knee .*|.* head, neck .*|tray and insert.*)$/i, '');
const fmtWhen = iso => { const d = new Date(iso); return Number.isFinite(d.getTime()) ? `${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}/${d.getFullYear()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}` : ''; };
export function implantsText(list) {
  return (Array.isArray(list) ? list : []).map(i => [SLOTN[i.slot] || i.slot, shortLabel(i.label), i.size].filter(Boolean).join(' ')).join(' · ');
}
// The list in a container (the start card's #cases). Every text from the server goes in as text, never as HTML.
export function showList(box) {
  if (!H || !H.STANDALONE || !box) return;
  box.textContent = '';
  const sec = node('section', 'cases'); sec.setAttribute('aria-label', 'Templated patients');
  const h = node('h2', '', 'Templated patients');
  const q = node('input', 'csearch'); q.type = 'search'; q.maxLength = 60; q.placeholder = 'Search name or record number'; q.setAttribute('aria-label', 'Search templated patients'); q.autocomplete = 'off';
  const msg = node('div', 'cmsg'); msg.setAttribute('role', 'status');
  const ul = node('ul', 'clist'), more = button('More', () => load(false), 'cmore'); more.hidden = true;
  sec.append(h, q, msg, ul, more); box.appendChild(sec);
  // hosp: hospital code → its patients' <ul>; groups: the patient groups on the page ({li, hd, sub, cases, hcode, hul, who}),
  // kept in step when a case leaves (deleted or hidden), so a "More" page adds to the right hospital and nothing stale is kept
  const st = { q: '', next: null, seq: 0, hosp: new Map(), groups: [], gn: 0 };
  const say = (text, cls = '') => { msg.className = 'cmsg' + (cls ? ' ' + cls : ''); msg.textContent = text; };
  // a patient group starts closed (22 Oct 2026, Sami: duplicates are found and deleted case by case); its header says what
  // is in it: "N cases, N films", the latest date, the sides and joints ("R hip, L knee"), and "possible duplicate" when two
  // of its cases share an X-ray (sha256) or the same side and joint (a mark only; nothing is done about it here)
  const filmsOf = m => (isObj(m.images) ? Object.values(m.images).filter(isObj).length : 1);
  const shasOf = m => { const s = new Set(); if (isObj(m.image) && typeof m.image.sha256 === 'string' && m.image.sha256) s.add(m.image.sha256); if (isObj(m.images)) for (const f of Object.values(m.images)) if (isObj(f) && typeof f.sha256 === 'string' && f.sha256) s.add(f.sha256); return s; };
  const sjOf = m => `${m.side === 'L' ? 'L' : 'R'} ${jointOf(m.procedure)}`;
  function maybeDup(cs) {
    const sj = new Set(), sh = new Set();
    for (const m of cs) {
      const k = sjOf(m), own = shasOf(m);
      if (sj.has(k) || [...own].some(s => sh.has(s))) return true;
      sj.add(k); for (const s of own) sh.add(s);
    }
    return false;
  }
  function paint(G) {
    const cs = G.cases, nf = cs.reduce((n, m) => n + filmsOf(m), 0);
    const last = cs.map(m => String(m.updatedAt || '')).sort().pop() || '';
    G.hd.textContent = '';
    G.hd.append(node('b', 'pname', G.who), node('span', 'pcount', `${cs.length} case${cs.length === 1 ? '' : 's'}, ${nf} film${nf === 1 ? '' : 's'}`), node('span', 'psum', [...new Set(cs.map(sjOf))].join(', ')), node('span', 'pdate', fmtWhen(last)));
    if (maybeDup(cs)) { const b = node('span', 'pdup', 'possible duplicate'); b.title = 'Two cases of this patient share an X-ray, or have the same side and joint. Open them to check before deleting one.'; G.hd.appendChild(b); }
  }
  function setOpen(G, on) { G.sub.hidden = !on; G.hd.setAttribute('aria-expanded', String(!!on)); }
  // a case gone from the list (deleted for good, or hidden): its row, then its patient group when empty, then its hospital
  function dropCase(li, G, m) {
    li.remove();
    if (!G) return;
    const i = G.cases.indexOf(m); if (i >= 0) G.cases.splice(i, 1);
    if (G.cases.length) { paint(G); return; }
    G.li.remove(); st.groups = st.groups.filter(x => x !== G);
    if (!G.hul.children.length) { const hli = G.hul.parentNode; if (hli) hli.remove(); if (st.hosp.get(G.hcode) === G.hul) st.hosp.delete(G.hcode); }
  }
  const emptyNote = () => (!ul.children.length && !st.next ? (st.q ? ` No other saved case matches "${st.q}".` : ' No other templated patients here.') : '');
  // may this case be deleted from the list now? Not the case on screen (its film would be saved again, or the page started
  // over with work not sent), not one with changes still waiting to be sent, and not while any save is on its way
  function listBusy(id) {
    const ref = FILM ? refOf(FILM) : null;
    if ((H.S.caseRef && H.S.caseRef.id === id) || (ref && ref.id === id)) return 'Not deleted: this case is open on screen. Close it first (open another X-ray), or delete it from the case itself.';
    for (const A of AWAY) if (A.ref && A.ref.id === id) return 'Not deleted: this case has changes still waiting to be sent (see the panel). Let them be sent or discard them first.';
    if (JOBS > 0) return 'Not deleted: a save is still being sent. Try again in a moment.';
    return '';
  }
  // note: a message kept once the list is loaded again (a refused delete says why the list was reloaded)
  async function load(reset, note = '') {
    const seq = ++st.seq;
    if (reset) { st.next = null; }
    say(reset ? 'Loading…' : 'Loading more…'); more.disabled = true;
    try {
      // by patient (contract section 12; Sami, 14 Oct: "keep the images for one patient under the name of that patient"):
      // 20 patients a page, each with every case of theirs and each case's films
      const body = { limit: 20, group: true }; if (st.q) body.q = st.q; if (!reset && st.next) body.before = st.next;
      const r = await call('POST', '/projects/list', { json: body });   // the search goes in the body, never the URL (contract 4)
      const viewer = await me();
      if (seq !== st.seq) return;
      if (reset) { ul.textContent = ''; st.hosp = new Map(); st.groups = []; }
      const now = H.S.caseRef && H.S.caseRef.id ? H.S.caseRef.id : null, mark = now || LAST_CASE, added = [];
      // under hospital headings (Sami, 15 Oct: "so we have clear database for each hospital"), one row per patient labelled
      // "Full name · SSC · 45 y · SSC-MRN", and under it that patient's cases with their films (the "sub menu"); a case with
      // no hospital is under "Hospital not set" until a hospital is picked for it
      for (const g of Array.isArray(r.groups) ? r.groups : []) {
        const cs = Array.isArray(g.cases) ? g.cases.filter(isObj) : [];
        if (!cs.length) continue;
        const pt = isObj(g.patient) ? g.patient : {}, hcode = typeof g.hospital === 'string' && HOSP_RE.test(g.hospital) ? g.hospital : '';
        let hul = st.hosp.get(hcode);
        if (!hul) {
          const hli = node('li', 'hgrp'), hn = hospitalName(hcode);
          hli.dataset.hospital = hcode || '-';
          hli.appendChild(node('h3', 'hhead', hcode ? (hn ? `${hn} (${hcode})` : hcode) : 'Hospital not set'));
          hul = node('ul', 'hpats'); hul.setAttribute('aria-label', hcode ? `Patients of ${hn || hcode}` : 'Patients whose hospital is not set');
          hli.appendChild(hul); ul.appendChild(hli); st.hosp.set(hcode, hul);
        }
        const hideId = !!H.S.set.share;   // sharing mode: no name, age or record number on the screen either (Astra, round 2, P1 3)
        const li = node('li', 'pgrp'), who = hideId ? `${hcode ? hcode + ' · ' : ''}patient details hidden (sharing mode)` : identLine(pt) || (pt.recordNo ? `Record ${pt.recordNo}` : 'Name left out');
        const hd = node('button', 'phead'); hd.type = 'button';   // a button: Enter and Space open and close it
        const sub = node('ul', 'pcases'); sub.setAttribute('aria-label', `Cases of ${who}`);
        sub.id = `pcases-${++st.gn}`; hd.setAttribute('aria-controls', sub.id);
        const G = { li, hd, sub, cases: cs, hcode, hul, who };
        hd.onclick = () => setOpen(G, sub.hidden);
        for (const m of cs) sub.appendChild(row(m, viewer, true, G, mark && m.id === mark ? (m.id === now ? 'on screen' : 'last opened') : ''));
        paint(G); setOpen(G, !!st.q || (!!mark && cs.some(m => m.id === mark)));   // closed, unless searched for or holding the case on screen / just opened
        li.append(hd, sub); hul.appendChild(li); st.groups.push(G); added.push(G);
      }
      st.next = typeof r.next === 'string' ? r.next : null;
      if (reset && !st.next && st.groups.length <= 3) for (const G of added) setOpen(G, true);   // a short list: all open
      more.hidden = !st.next; more.disabled = false;
      if (note) say(note, 'bad'); else say(ul.children.length ? '' : st.q ? `No saved case matches "${st.q}".` : 'No templated patients yet. A project saved here appears in this list.');
    } catch (e) {
      if (seq !== st.seq) return;
      more.hidden = true;
      if (e instanceof ApiError && e.status === 401) { say('', 'bad'); msg.appendChild(signinLink()); msg.append(' (your sign-in has ended; it opens in a new tab, then come back and press Try again) '); msg.appendChild(button('Try again', () => load(true))); return; }
      say(`Could not load the patient list: ${why(e)}. `, 'bad'); msg.appendChild(button('Try again', () => load(true)));
    }
  }
  // an error that says the case itself cannot be opened (not: no connection, a busy server, not signed in, not yours, over quota)
  const brokenOpen = e => !(e instanceof ApiError) || e.status === 500 || e.status === 422 || (e.status === 503 && e.code !== 'busy');
  // G: the patient group the row is in; markTxt: "on screen" or "last opened" for the case this page has open or opened last
  function row(m, viewer = ME, inGroup = false, G = null, markTxt = '') {
    const li = node('li', 'case' + (inGroup ? ' sub' : '') + (markTxt ? ' cmark' : '')); li.dataset.id = String(m.id || '');
    const pt0 = isObj(m.patient) ? m.patient : {}, pt = H.S.set.share ? { hospital: pt0.hospital } : pt0;   // sharing mode: identity hidden
    const who = H.S.set.share ? 'Patient details hidden (sharing mode)' : pt.name || (pt.recordNo ? `Record ${pt.recordNo}` : 'Name left out');
    const kc = isObj(m.images);   // a knee case (contract section 12): its films under it (a button each)
    const side = `${m.side === 'L' ? 'Left' : 'Right'} ${jointOf(m.procedure)}${kc ? ' case' : ''}`;
    const open = node('button', 'copen'); open.type = 'button';
    const top = node('span', 'ctop'); top.append(node('b', 'cname' + (inGroup ? ' sr-only' : ''), who), node('span', 'cside', side), node('span', 'cdate', fmtWhen(m.updatedAt)));
    const imp = node('span', 'cimp', implantsText(m.implants) || 'no implants placed');
    const by = node('span', 'cby', [pt.name && pt.recordNo && !inGroup ? `Record ${pt.recordNo}` : '', !kc ? '1 film' : '', m.updatedBy && m.updatedBy.em ? `saved by ${m.updatedBy.em}` : '', Array.isArray(m.collaborators) && m.collaborators.length ? `shared with ${m.collaborators.length}` : ''].filter(Boolean).join(' · '));
    if (markTxt) top.appendChild(node('span', 'cnow', markTxt));
    open.append(top);
    let films = null;
    if (kc) {   // AP ✓ · Lateral ✓ · Skyline – · Long leg ✓: each saved film opens the case with that film in front
      const fl = node('span', 'cfilms'), have = ROLES.filter(r => isObj(m.images[r]));
      for (const r of ROLES) {
        const on = have.includes(r), c = on ? node('button', 'cfilm on', `${ROLE_NAME[r]} ✓`) : node('span', 'cfilm', `${ROLE_NAME[r]} –`);
        c.title = on ? `Open the case with its ${ROLE_NAME[r].toLowerCase()} film in front` : `no ${ROLE_NAME[r].toLowerCase()} film yet`;
        if (on) { c.type = 'button'; c.setAttribute('aria-label', `Open the ${ROLE_NAME[r]} film of ${who}, ${side}`); c.onclick = () => go(r); }
        fl.appendChild(c);
      }
      fl.setAttribute('aria-label', `Films: ${have.map(r => ROLE_NAME[r]).join(', ') || 'none'}; missing: ${ROLES.filter(r => !have.includes(r)).map(r => ROLE_NAME[r]).join(', ') || 'none'}`);
      films = fl;
    }
    open.append(imp, by); open.setAttribute('aria-label', `Open ${who}, ${side}, saved ${fmtWhen(m.updatedAt)}${kc ? `, ${ROLES.filter(r => isObj(m.images[r])).length} of 4 films` : ''}`);
    const go = async role => {
      say('Opening the case…'); for (const b of sec.querySelectorAll('button')) b.disabled = true;
      try { const done = await openCase(m.id, { role }); if (done === false) say(''); }
      catch (e) {
        say(`Could not open that case: ${e instanceof ApiError ? why(e) : e.message || e}.`, 'bad'); if (e instanceof ApiError && e.status === 401) msg.appendChild(signinLink());
        // a case that cannot be opened (its files unreadable, refused by the store, not what the page can read) can be removed for good (21 Oct 2026)
        if (brokenOpen(e) && mayManage(m, viewer) && !li.querySelector('.cpurge')) {
          const pg = button('Delete (permanent)', () => { const no = listBusy(String(m.id)); if (no) { say(no, 'bad'); return; } return runDelete('case', { id: m.id, what: H.S.set.share ? '' : who, report: t => say(t, 'bad'), onDone: () => { dropCase(li, G, m); say('Deleted permanently.' + emptyNote()); } }); }, 'cdel cpurge', 'This case cannot be opened. Remove it from the server for good (not only from the list)');
          pg.setAttribute('aria-label', `Delete ${who} permanently`);
          (act.isConnected ? act : li).appendChild(pg); li.classList.add('broken');
        }
      }
      finally { for (const b of sec.querySelectorAll('button')) b.disabled = false; }
    };
    open.onclick = () => go(null);
    const act = node('div', 'cact');
    const ed = node('div', 'cshare'); ed.hidden = true;
    const share = button('Share', () => {
      ed.hidden = !ed.hidden; if (ed.hidden) return;
      ed.textContent = '';
      const inp = node('input'); inp.type = 'text'; inp.maxLength = 600; inp.autocomplete = 'off'; inp.value = (m.collaborators || []).join(', '); inp.setAttribute('aria-label', 'Shared with (names or emails, separated by commas)');
      const lab = node('label', '', 'Shared with '); lab.appendChild(inp);
      const note = node('p', 'note', 'Names without @ get @addisortho.com. They see this case in their list and can save changes to it.');
      const go = button('Save sharing', async () => {
        const p = parseCollaborators(inp.value);
        if (p.bad) return say(`Not shared: "${p.bad}" is not an email address.`, 'bad');
        try {
          const before = [...(m.collaborators || [])].sort().join(','), r = await call('POST', `/projects/${m.id}/collaborators`, { json: { collaborators: p.list } });
          m.collaborators = Array.isArray(r.collaborators) ? r.collaborators : p.list;
          if (H.S.caseRef && H.S.caseRef.id === m.id && [...m.collaborators].sort().join(',') !== before) caseOnScreen({ ...H.S.caseRef, rev: H.S.caseRef.rev + 1 });   // the server counts a change of sharing as a version
          ed.hidden = true; li.replaceWith(row(m, viewer, inGroup, G, markTxt));
          say(m.collaborators.length ? `Shared with ${m.collaborators.join(', ')}.` : 'Not shared with anyone now.');
        } catch (e) { say(`Not shared: ${why(e)}.`, 'bad'); }
      }, 'primary');
      const r2 = node('div', 'row'); r2.append(go, button('Cancel', () => { ed.hidden = true; }));
      ed.append(lab, note, r2); inp.focus();
    }, '', 'Share this case with colleagues');
    share.setAttribute('aria-label', `Share ${who}`);
    const hide = button('Hide (keeps files)', async () => {
      if (!confirm(`Hide ${who} (${side}) from the templated-patients list? It is not deleted: the case stays on the server.`)) return;
      try {
        await call('POST', `/projects/${m.id}/hide`, { json: {} });
        if (H.S.caseRef && H.S.caseRef.id === m.id) caseOnScreen(null);   // saving the X-ray on screen again (Save project) starts a new case; it is not saved automatically any more
        dropCase(li, G, m); say('Hidden from the list (kept on the server).' + emptyNote());
      } catch (e) { say(`Not hidden: ${why(e)}.`, 'bad'); }
    }, '', 'Take this case off the list (it is kept on the server)');
    hide.setAttribute('aria-label', `Hide ${who}, ${side} (keeps files)`);
    // the case removed for good (22 Oct 2026; POST /projects/<id>/purge, contract section 14): only where Hide is offered,
    // after the case is read again (a version saved since the list was loaded is not deleted unseen) and the page's own
    // dialog says what is lost, Cancel first and focused
    const del = button('Delete (permanent)', () => listDelete(m, li, G, who, side, del), 'cdel', 'Remove this case and all its X-rays from the server for good');
    del.setAttribute('aria-label', `Delete ${who}, ${side}, permanently`);
    if (mayManage(m, viewer)) { act.append(share, hide, del); li.append(open, act); if (films) li.appendChild(films); li.append(ed); }   // only where the server would allow them
    else { li.append(open); if (films) li.appendChild(films); }
    return li;
  }
  async function listDelete(m, li, G, who, side, btn) {
    const id = String(m.id || ''); if (!ID_RE.test(id)) return;
    let no = listBusy(id); if (no) { say(no, 'bad'); return; }
    btn.disabled = true;
    try {
      let got;
      try { got = await call('GET', `/projects/${id}`); }
      catch (e) {
        if (gone404(e)) load(true, 'Not deleted: this case is no longer in your patient list (deleted or hidden elsewhere). The list was reloaded.');
        else say(`Not deleted: ${why(e)}.`, 'bad');
        return;
      }
      const cur = isObj(got.meta) ? got.meta : {};
      if (cur.rev !== m.rev || String(cur.updatedAt || '') !== String(m.updatedAt || '')) {
        load(true, 'Not deleted: this case was saved again after the list was loaded (here or elsewhere). The list was reloaded: look at it again before deleting.'); return;
      }
      const share = !!H.S.set.share, pt = isObj(m.patient) ? m.patient : {}, kc = isObj(m.images);
      const roles = kc ? ROLES.filter(r => isObj(m.images[r])) : [], nf = kc ? roles.length : 1;
      const collab = Array.isArray(m.collaborators) ? m.collaborators.length : 0;
      const ownerEm = isObj(m.owner) && typeof m.owner.em === 'string' ? m.owner.em : '';
      const notMine = !ME || !isObj(m.owner) || m.owner.uid !== ME.uid;
      const facts = [
        share ? `${side}, saved ${fmtWhen(m.updatedAt)} (patient details hidden: sharing mode)` : `${who}${pt.name && pt.recordNo ? ` (record ${pt.recordNo})` : ''}: ${side}, saved ${fmtWhen(m.updatedAt)}`,
        `${nf} film${nf === 1 ? '' : 's'}${kc ? `: ${roles.map(r => ROLE_NAME[r]).join(', ') || 'none'}` : ''}`,
        `Implants: ${implantsText(m.implants) || 'none placed'}`,
        m.updatedBy && m.updatedBy.em ? `Saved by ${m.updatedBy.em}` : '',
        collab ? `Shared with ${collab} ${collab === 1 ? 'person' : 'people'}; they lose it too` : 'Not shared with anyone',
        notMine && ownerEm ? `Owner: ${ownerEm} (not you)` : '',
      ].filter(Boolean);
      const pick = await choose('Delete this patient case PERMANENTLY? Every X-ray, plan and note in it is removed from the server for good and cannot be brought back. (Hide only takes it off the list and keeps the files.)',
        [['Cancel', null, ''], ['Delete permanently', 'del', 'cdel']], facts);
      if (pick !== 'del') return;
      no = listBusy(id); if (no) { say(no, 'bad'); return; }   // opened or saved meanwhile
      try { await purgeCall(`/projects/${id}/purge`); }
      catch (e) { say(`Not deleted: ${why(e)}.`, 'bad'); return; }
      if (LAST_CASE === id) LAST_CASE = null;
      dropCase(li, G, m); say('Deleted permanently.' + emptyNote());
    } finally { btn.disabled = false; }
  }
  let t = 0;
  q.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => { const v = q.value.trim(); if (v !== st.q) { st.q = v; load(true); } }, 350); });
  q.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); clearTimeout(t); st.q = q.value.trim(); load(true); } });
  load(true);
}

// ======================= saving to the patient list automatically (round 13, 13 Oct 2026) =======================
// Sami, 13 Oct: "when uploading the images it doesn't upload it automatically". On t.addisortho.com a film opened from a
// file (chooser, drop, a phone's photo or file picker) is sent to the server at once, in the background (2 MB parts, one
// upload at a time, resumed after a dropped connection, never sent again when the server holds it: sha256), and its case is
// made as soon as the image is there: named from the DICOM or as typed, else "Unnamed film, <date time>" in the list until a
// name is given. The plan is then saved on its own: about 20 s after the last change, at every step change, when the page
// is hidden or closed, and before another film opens, with the case's version (baseRev) and a requestId per save, exactly
// as Save project does. A newer version saved elsewhere is never overwritten: the plan stays on this page and the header
// says "Changed elsewhere: reload or save as my copy" (Save project then offers both). A film opened from the list is
// saved only once it is changed; one opened again in the same tab (also after a reload) reopens its case instead of
// making a second one. Nothing is saved while a drag is under way (after the finger or button is lifted). The hospital
// copy does none of this. What is stored is what Save project stores (sharing mode leaves the name out, as before).
export const PLACEHOLDER = 'Unnamed film, ';
const AUTO = { delay: 20000, maxWait: 60000, step: 400, retry: [5000, 15000, 30000, 60000, 120000], paused: false };
// A knee case (contract section 12) is one "film" here: one case key (its own draft id, not an image's hash), one queue of
// saves, one status, one set of kept changes, whichever of its films is on screen; adding a film is a change like any
// other. unit: what the page has open (app.js H.unit(): the knee case, else the X-ray), so switching between a case's
// films never closes it.
let FILM = null;             // the film on screen: { ck, kc, unit, orig, ref, how, eligible, wantCase, created, placeholder, conflict, gone, pend, snap, status, ... }
const AWAY = new Set();      // films no longer on screen with a save still to make (their snapshot) or to retry
let NEXT_PLACEHOLDER = null; // openCase → filmOpened: the list's placeholder name of the case being opened
let LAST_CASE = null;        // the id of the case last opened or saved on this page (the list opens its patient group)
let QUEUE = Promise.resolve();
// every write to the server, automatic or Save project, one after another: one upload at a time, and two saves of the same
// case never race on the same version
let JOBS = 0;   // writes queued or running
function serial(fn) { JOBS++; const p = QUEUE.then(fn).finally(() => { JOBS--; }); QUEUE = p.then(() => {}, () => {}); return p; }
const live = F => !!F && F === FILM && !!F.unit && H.unit() === F.unit;
const refOf = F => (live(F) ? H.S.caseRef : F.ref);
function setRef(F, r) {
  F.ref = r || null; if (live(F)) H.S.caseRef = r || null;
  if (r && r.id) LAST_CASE = r.id;   // the list opens this case's patient and marks the row (22 Oct 2026)
  if (F.ck) { remember(F.ck, r); if (F.kc && r) rememberFilms(F.ck, r); }
}
function caseOnScreen(r) { if (FILM && live(FILM)) { setRef(FILM, r); if (!r) { FILM.status = null; render(); } } else H.S.caseRef = r; }
// may the film on screen be left without asking? (its changes are saved automatically before the other opens)
const autoCovers = () => !!FILM && live(FILM) && FILM.eligible && !FILM.conflict && !FILM.gone && !!(refOf(FILM) || (FILM.wantCase && !FILM.created));
function metaFor(F) { return withPlaceholder(H.metaNow(), F && F.placeholder, H.S.set.share); }
// the list's "Unnamed film, <date>" for a case with neither a name nor a record number, its hospital and age kept (Astra,
// round 2, P2 4: the whole patient was replaced); none in sharing mode
export function withPlaceholder(meta, placeholder, share) {
  if (placeholder && !share && !meta.patient.name && !meta.patient.recordNo) meta.patient = { ...meta.patient, name: placeholder };
  return meta;
}
const hhmm = d => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
const placeholderNow = () => { const d = new Date(); return `${PLACEHOLDER}${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}/${d.getFullYear()} ${hhmm(d)}`; };

// ---------- which case a film is (this tab, and this browser when plans are kept here) ----------
// Only ids, versions and image hashes: no names. The tab's list ends with the tab; the browser's only when plans are kept
// in this browser (Auto plan settings), and "Forget plans on this computer" removes it with the plans (aoc-tpl:).
// Keyed by case key (contract section 12): a single film's image sha256, or a knee case's "kc<draft id>"; each film of a
// knee case is also remembered by its image's sha256, with the case key (ck), so opening that X-ray again opens its case.
const TAB_KEY = 'aoc-tpl-cases', LOCAL_KEY = sha => 'aoc-tpl:case:' + sha;
function remember(sha, r) {
  if (!CK_RE.test(String(sha))) return;
  const v = r && ID_RE.test(String(r.id)) ? { id: r.id, rev: r.rev, sha256: r.sha256 || null, ...(isObj(r.shas) ? { shas: shasOf(r) } : {}), ...(typeof r.ck === 'string' && CK_RE.test(r.ck) ? { ck: r.ck } : {}) } : null;
  try {
    const m = JSON.parse(sessionStorage.getItem(TAB_KEY) || '{}') || {};
    if (v) { delete m[sha]; m[sha] = v; } else delete m[sha];
    const ks = Object.keys(m); for (const k of ks.slice(0, Math.max(0, ks.length - 50))) delete m[k];
    sessionStorage.setItem(TAB_KEY, JSON.stringify(m));
  } catch (e) { /* storage off */ }
  try { if (v && H.S.set.keepLocal) localStorage.setItem(LOCAL_KEY(sha), JSON.stringify(v)); else if (!v) localStorage.removeItem(LOCAL_KEY(sha)); } catch (e) { /* storage off */ }
}
function rememberFilms(ck, r) { if (r && isObj(r.shas)) for (const s of Object.values(shasOf(r))) remember(s, { id: r.id, rev: r.rev, sha256: null, ck }); }
export function knownCase(sha) {
  if (!H || !H.STANDALONE || !CK_RE.test(String(sha))) return null;
  let r = null;
  try { r = (JSON.parse(sessionStorage.getItem(TAB_KEY) || '{}') || {})[sha] || null; } catch (e) { r = null; }
  if (!r && H.S.set.keepLocal) { try { r = JSON.parse(localStorage.getItem(LOCAL_KEY(sha)) || 'null'); } catch (e) { r = null; } }
  return isObj(r) && ID_RE.test(String(r.id)) ? r : null;
}
// A film opened again whose case is known: its case is opened from the list (the plan saved there, which the automatic
// saves keep current) instead of a new case being made. true: done. false: open it as a new film (the case is gone or
// hidden). A case that cannot be reached now (no connection) opens the film without making a case, so none is doubled.
export async function openKnown(ref, bytes, sha) {
  try { await openCase(ref.id, { force: true, bytes, sha, how: 'known' }); return true; }
  catch (e) {
    if (e instanceof ApiError && e.status === 404) { remember(sha, null); return false; }
    if (e instanceof ApiError) { NO_CASE = { sha, text: `Not saved: this X-ray is already in the patient list, which cannot be reached now (${why(e)}); open it from the list later` }; return false; }
    remember(sha, null); return false;   // a case this page cannot open: the film opens as new, with a case of its own
  }
}
let NO_CASE = null;   // openKnown → filmOpened: this film has a case that could not be opened; make no other

// ---------- what did not reach the patient list, kept for the next load (round 13b, findings 5 and 6) ----------
// The plan of a film whose changes the server has not answered for, written when the page is hidden or closed and when
// an earlier film is let go to keep memory bounded: the plan's state only (no image, no patient details typed), per image
// sha256, for this tab (and this browser when plans are kept here). Removed once a save of that film taken after it is
// answered, or when the surgeon discards it. When the same X-ray is opened again and its plan differs, the page offers to
// put the changes back.
// Each record belongs to the opening of the X-ray that wrote it (origin: a random id per film on the page; Astra's third
// review, A) and carries a version v that only grows (B). One record per image and origin, written to the tab's store and,
// when plans are kept here, to the browser's; whichever copy is newest (highest v) is the one used, so a copy that could not
// be updated never hides a newer one. A record is removed only for its own origin: when a save of that opening taken after
// it is answered (its later plan grew from it), or when that record itself is discarded or put back and saved; another
// opening's save of the same image never removes it.
const UNSENT_TAB = 'aoc-tpl-unsent', UNSENT_LOCAL = 'aoc-tpl:unsent:';
const rkey = (sha, origin) => `${sha}:${origin || '-'}`;
let VSEQ = 0;
const nextV = () => { VSEQ = Math.max(VSEQ + 1, Date.now() * 1000); return VSEQ; };
const validRec = r => isObj(r) && isObj(r.state) && CK_RE.test(String(r.sha)) && Number.isFinite(r.v);   // sha: the case key (an image's sha256, or a knee case's)
function tabRecs() { try { const m = JSON.parse(sessionStorage.getItem(UNSENT_TAB) || '{}'); return isObj(m) ? m : {}; } catch (e) { return {}; } }
function localRecs() {
  const out = {};
  try { for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); if (k && k.startsWith(UNSENT_LOCAL)) { try { const r = JSON.parse(localStorage.getItem(k)); const kk = k.slice(UNSENT_LOCAL.length); if (validRec(r)) out[kk] = r; else if (isLegacyRec(kk, r)) { const n = legacyFix(kk, r); out[rkey(kk, n.origin)] = n; } } catch (e) { /* unreadable copy */ } } } } catch (e) { /* off */ }   // an old copy that could not be rewritten is still offered
  return out;
}
// Records of the earlier form {state, at, label} (keyed by the image's sha256 alone; never in a released build, migrated
// anyway: Astra's fourth review): given the sha256 from the key, a fresh origin, version 1 and a label without names.
// version from the old record's own time (ms; a newer old copy gets the higher version, every old one below any version
// written since: those are microsecond counters; Astra's fifth review)
const legacyFix = (sha, r) => ({ sha, origin: 'old' + sha.slice(0, 9), v: Math.max(1, Number.isFinite(r.at) ? Math.floor(r.at) : 1), at: Number.isFinite(r.at) ? r.at : 0, label: `Earlier kept changes, image ${sha.slice(0, 6)}`, state: r.state });
function isLegacy(k, r) { return isLegacyRec(k, r); }
const isLegacyRec = (k, r) => SHA_RE.test(k) && isObj(r) && isObj(r.state) && !validRec(r);
function migrateUnsent() {
  const fix = legacyFix;
  try {
    const m = tabRecs(); let changed = false;
    for (const [k, r] of Object.entries(m)) if (SHA_RE.test(k) && isObj(r) && isObj(r.state) && !validRec(r)) { const n = fix(k, r); delete m[k]; m[rkey(k, n.origin)] = n; changed = true; }
    if (changed) sessionStorage.setItem(UNSENT_TAB, JSON.stringify(m));
  } catch (e) { /* off */ }
  try {
    const old = [];
    for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); if (k && k.startsWith(UNSENT_LOCAL) && SHA_RE.test(k.slice(UNSENT_LOCAL.length))) old.push(k); }
    for (const k of old) {
      let r = null; try { r = JSON.parse(localStorage.getItem(k)); } catch (e) { r = null; }
      if (isObj(r) && isObj(r.state) && !validRec(r)) {   // the old copy goes only once the new one is written
        try { const n = fix(k.slice(UNSENT_LOCAL.length), r); localStorage.setItem(UNSENT_LOCAL + rkey(n.sha, n.origin), JSON.stringify(n)); localStorage.removeItem(k); } catch (e) { /* kept as it was */ }
      }
    }
  } catch (e) { /* off */ }
}
// every record, the newest copy of each (key → record)
function unsentAll() {
  migrateUnsent();
  const out = {};
  for (const [k, r] of Object.entries(tabRecs())) {
    if (validRec(r)) out[k] = r;
    else if (isLegacy(k, r)) { const n = legacyFix(k, r); out[rkey(k, n.origin)] = n; }   // could not be rewritten: still offered
  }
  for (const [k, r] of Object.entries(localRecs())) if (!out[k] || r.v > out[k].v) out[k] = r;
  return out;
}
// the newest record of an image (any opening) → { key, rec } or null
function unsentOf(sha) {
  let best = null;
  for (const [k, r] of Object.entries(unsentAll())) if (r.sha === sha && (!best || r.v > best.rec.v)) best = { key: k, rec: r };
  return best;
}
// → true when written; false when it could not be (KEEP_WHY says why: storage full or off, or UNSENT_MAX records already
// kept). Nothing kept before is ever pushed out to make room (round 13b re-review): the caller keeps the film on the page
// and says so.
export const UNSENT_MAX = 6;
let KEEP_WHY = '';
function keepUnsent(F, state) {
  KEEP_WHY = 'storage full';
  const sha = F.ck, origin = F.uid;
  if (!CK_RE.test(String(sha)) || !isObj(state)) return false;
  const key = rkey(sha, origin), rec = { sha, origin, v: nextV(), at: Date.now(), label: clip(F.label || labelOf(F), 100), state };
  const all = unsentAll();
  if (!all[key] && Object.keys(all).length >= UNSENT_MAX) { KEEP_WHY = `${UNSENT_MAX} X-rays with unsaved changes are already kept in this tab`; return false; }
  let ok = false;
  try { const m = tabRecs(); m[key] = rec; sessionStorage.setItem(UNSENT_TAB, JSON.stringify(m)); ok = true; } catch (e) { /* full or off */ }
  try { if (H.S.set.keepLocal) { localStorage.setItem(UNSENT_LOCAL + key, JSON.stringify(rec)); ok = true; } } catch (e) { /* full or off */ }
  if (ok) KEEP_WHY = '';
  return ok;
}
// remove the copies of one record (key) that are not newer than upTo: a newer copy (written after the save that was
// answered was taken) always stays
function dropKey(key, upTo = Infinity) {
  const [sha, origin] = key.split(':');
  if (origin && origin.startsWith('old') && SHA_RE.test(sha)) {   // an old-form copy that could not be rewritten
    try { const m = tabRecs(); if (isLegacyRec(sha, m[sha]) && !(m[sha].at > upTo)) { delete m[sha]; sessionStorage.setItem(UNSENT_TAB, JSON.stringify(m)); } } catch (e) { /* off */ }
    try { const r = JSON.parse(localStorage.getItem(UNSENT_LOCAL + sha) || 'null'); if (isLegacyRec(sha, r) && !(r.at > upTo)) localStorage.removeItem(UNSENT_LOCAL + sha); } catch (e) { /* off */ }
  }
  try { const m = tabRecs(); if (m[key] && !(m[key].at > upTo)) { delete m[key]; sessionStorage.setItem(UNSENT_TAB, JSON.stringify(m)); } } catch (e) { /* off */ }
  try { const r = JSON.parse(localStorage.getItem(UNSENT_LOCAL + key) || 'null'); if (r && !(r.at > upTo)) localStorage.removeItem(UNSENT_LOCAL + key); } catch (e) { /* off */ }
}
// film F's own save, taken at time takenAt, was answered: its own records written before that are done with
function dropUnsent(F, takenAt, uid = F && F.uid) { if (F && F.ck && uid) dropKey(rkey(F.ck, uid), takenAt); }
const stateText = st => { const c = { ...st }; delete c.saved; delete c.pendingSend; return JSON.stringify(c); };
// What the kept-changes store holds of a project: a single film's state; a knee case's films (each image described
// without its file name, its state without the save time) and the shared plan, never an image or a typed patient detail
export function keptStateOf(p) {
  if (!isObj(p) || p.v !== 2) return isObj(p) ? p.state : null;
  const films = {};
  for (const [r, f] of Object.entries(p.films || {})) {
    const st = { ...f.state }; delete st.saved; st.pendingSend = null;
    films[r] = { image: { mime: f.image.mime, sha256: f.image.sha256, size: f.image.size, width: f.image.width, height: f.image.height }, state: st, qc: f.qc || {} };
  }
  return { kc: true, cid: p.case.cid, side: p.patient.side, active: p.active, films, shared: p.shared || {}, notices: p.notices || [] };
}
// a knee case's kept record checked as a project would be (its films' images described without a file name)
export function keptCaseProblems(st, side) {
  if (!isObj(st) || !st.kc || !isObj(st.films)) return ['not a knee case'];
  const films = {};
  for (const [r, f] of Object.entries(st.films)) films[r] = isObj(f) ? { ...f, image: { ...(f.image || {}), fileName: 'image' } } : f;
  return caseProblems({ format: FORMAT, v: 2, savedAt: 'kept', libraryId: NO_LIBRARY, patient: { side, procedure: 'knee' }, case: { cid: st.cid, kind: 'knee' }, films, active: st.active, shared: st.shared });
}
async function offerUnsent(F) {
  const sha = F.ck, found = sha && unsentOf(sha);
  if (!found || !live(F) || F.adopted) return;
  const rec = found.rec;
  let now = null; try { now = H.keptNow(); } catch (e) { now = null; }
  if (now && stateText(now) === stateText(rec.state)) { dropKey(found.key, rec.at); return; }   // what was kept is what is here
  const when = hhmm(new Date(rec.at));
  const pick = await choose(`This ${F.kc ? 'knee case' : 'X-ray'} has changes from ${when} that may not have reached the patient list (the page was closed, reloaded or had to let them go).`,
    [['Put them back', 'back', 'primary'], ['Discard them', 'drop', '']], 'Put them back: they replace the plan on the screen and are saved to the patient list. Discard: they are forgotten.');
  if (!live(F)) return;
  if (pick === 'back') {
    const pr = F.kc ? keptCaseProblems(rec.state, H.S.side) : stateProblems(rec.state, H.S.img.cols, H.S.img.rows);
    if (pr.length) { dropKey(found.key, rec.at); return H.prompt(`The kept changes could not be put back: ${pr.slice(0, 2).join('; ')}.`); }
    // a knee case: films whose original X-ray is not on this page are named, to be opened again (contract section 12)
    const missing = F.kc ? H.applyCaseState(rec.state) : (H.applyState(rec.state), []);
    F.srvKey = null; F.first = 0;
    F.uid = rec.origin || F.uid;   // this opening carries those changes on: its answered save removes their record
    H.prompt(`The changes from ${when} are back on the screen; they are being saved to the patient list.${missing.length ? ` Open these X-rays again to restore their films: ${missing.join('; ')}.` : ''}`);
    flushSoon(F);
  } else if (pick === 'drop') dropKey(found.key, rec.at);
}
// A knee case whose changes were kept (the page closed before the patient list had them, its case perhaps never made):
// the newest kept record of a knee case holding the X-ray with this sha256, offered when that X-ray is opened again
// (contract section 12: "recovery says which original images must be reopened") → { key, rec } or null
export function keptCaseFor(sha) {
  if (!H || !H.STANDALONE || !SHA_RE.test(String(sha))) return null;
  let best = null;
  for (const [k, r] of Object.entries(unsentAll())) if (r.state && r.state.kc && isObj(r.state.films) && Object.values(r.state.films).some(f => isObj(f) && isObj(f.image) && f.image.sha256 === sha) && (!best || r.v > best.rec.v)) best = { key: k, rec: r };
  return best;
}
export function dropKept(key) { dropKey(key); }
// a knee case just restored from its kept record (app.js kcRestoreFrom): this opening carries those changes on, so the record
// is not offered again and is removed only when a save of this opening is answered
export function adoptKept(kept) { const F = FILM; if (F && live(F) && kept && kept.rec) { F.adopted = true; F.uid = kept.rec.origin || F.uid; F.srvKey = null; } }

// ---------- the film's life on the page (app.js setImage) ----------
// F.srvKey: the revision (app.js stateKey) the patient list has for this film: what the server answered for, never what
// was only downloaded as a file (finding 1). srvDirty: the plan on screen is not that one.
const srvDirty = F => (live(F) ? H.revision() !== F.srvKey : !!F.snap);
const needsCase = F => !refOf(F) && F.wantCase && !F.created;
// a film is put on screen: how = 'file' (an image file), 'aotp' (a project file), 'list' or 'known' (a case from the list),
// 'case' (a knee case started on this page). A knee case is one film here whichever of its films is shown.
export function filmOpened(how) {
  if (!H || !H.STANDALONE) return;
  const S = H.S, ref = S.caseRef, kc = H.isCase(), ck = H.caseKeyNow();
  const F = { uid: ridOf().slice(0, 12), ck, kc, sha: kc ? null : S.orig && S.orig.sha256, openedAt: Date.now(), unit: H.unit(), orig: kc ? null : S.orig, ref, how, eligible: kc ? !!ck : !!(S.orig && S.orig.sha256), created: !!ref, conflict: false, gone: false, lost: [], snap: null,
    srvKey: ref ? H.revision() : null, status: null, timer: 0, first: 0, retryN: 0, retryT: 0, nextTry: 0, waitUp: false, queued: false, step: null, placeholder: NEXT_PLACEHOLDER };
  const blocked = NO_CASE && F.eligible && !kc && NO_CASE.sha === S.orig.sha256 ? NO_CASE : null; NO_CASE = null;
  F.wantCase = F.eligible && !ref && !blocked && (how === 'file' || how === 'aotp' || how === 'case');
  if (F.wantCase && !H.patientKnown() && !S.set.share) F.placeholder = placeholderNow();
  try { F.step = H.stepNow(); } catch (e) { F.step = null; }
  FILM = F;
  if (ref) { remember(ck, ref); if (kc) rememberFilms(ck, ref); }
  if (blocked) status(F, 'fail', blocked.text, 'The X-ray is already a case in your patient list. To go on with it, open it from the list (Open) once the connection is back; this copy is not saved to the list, so no second case is made.');
  else if (F.wantCase) { status(F, 'saving'); enqueue(F); }
  else render();
  if (F.eligible) setTimeout(() => offerUnsent(F), 0);
}
// the film on screen is about to be replaced (app.js setImage, before anything changes): what it still needs saving is
// fixed now and saved in the background, after anything already under way. Memory stays bounded (finding 6): the decoded
// picture is let go at once, the original bytes are kept only while the server does not hold them, and at most AWAY_MAX
// films wait; an older one is let go into the kept-changes store (offered back when that X-ray is opened again).
const AWAY_MAX = 2;
export function filmClosing() {
  const F = FILM;
  if (!F || !live(F)) return;
  clearTimeout(F.timer); F.timer = 0; F.first = 0; F.waitUp = false;
  const ref = H.S.caseRef, dirty = !!ref && H.revision() !== F.srvKey, need = !ref && F.wantCase && !F.created, partial = unsentNow(F);
  F.label = labelOf(F);
  let state = null; const stateNow = () => { if (!state) state = H.keptNow(); return state; };
  // a knee case with films whose plan never reached the server (their X-ray not opened again): kept for the next opening
  // whatever else happens below, so closing the case never loses them (Astra, 15 Oct, P1 2)
  let keptPartial = false;
  if (partial && F.eligible) { try { keptPartial = keepUnsent(F, stateNow()); } catch (e) { keptPartial = false; } }
  F.ref = ref; F.unit = null;
  if (F.kc) F.snapImgs = H.images();   // a knee case's films: their bytes, kept below only while the server lacks them
  if (!F.eligible) { FILM = null; return; }
  if (H.patientPending()) {   // a restored case whose patient was never read: its changes stay in the kept store (offered again), nothing sent
    let ok = false; try { ok = keepUnsent(F, stateNow()); } catch (e) { ok = false; }
    if (!ok) {   // could not be kept in this browser: the case stays on this page (as section 11c), tried again (Fable, round 9)
      try { F.snap = { project: null, meta: null, rev: null, at: Date.now(), uid: F.uid, userDirty: true, keepOnly: true, kept: stateNow(), share: !!H.S.set.share }; F.keepFail = KEEP_WHY; AWAY.add(F); H.prompt(`The knee case open before (${F.label}) could not be saved yet (its patient is still to be read from the list) and this browser could not keep its changes (${KEEP_WHY}): they stay on this page; see the panel.`); }
      catch (e) { /* nothing more can be done */ }
    }
    FILM = null; return;
  }
  if (F.conflict || F.gone) {   // never saved automatically: kept for the next opening, or on this page if it cannot be
    let ok = !dirty || !!F.replaced; try { if (!ok) ok = keepUnsent(F, stateNow()); } catch (e) { ok = false; }
    if (!ok) {
      try { F.snap = { project: H.projectNow(), meta: metaFor(F), rev: H.revision(), at: Date.now(), uid: F.uid, userDirty: true }; AWAY.add(F); H.prompt(`The X-ray open before (${F.label}) has changes not in the patient list that this browser could not keep (${KEEP_WHY}): they stay on this page; see the panel.`); }
      catch (e) { /* nothing more can be done */ }
    }
    FILM = null; return;
  }
  if (partial && !keptPartial && !need && !dirty) {   // could not be kept in this browser: the case stays on this page
    try { F.snap = { project: H.projectNow(), meta: metaFor(F), rev: H.revision(), at: Date.now(), uid: F.uid, userDirty: true, partial: true, keepOnly: true, kept: stateNow(), share: !!H.S.set.share }; AWAY.add(F); H.prompt(`The knee case open before (${F.label}) has films whose plans are not in the patient list (their X-rays were not opened again), and this browser could not keep them (${KEEP_WHY}): they stay on this page; see the panel.`); }
    catch (e) { /* nothing more can be done */ }
    FILM = null; return;
  }
  if (!need && !dirty) { FILM = null; return; }
  try { const project = H.projectNow(); aotpCheck(project); F.snap = { project, meta: metaFor(F), rev: H.revision(), at: Date.now(), uid: F.uid, userDirty: H.isDirty() || !!F.ref, partial, kept: partial ? stateNow() : null, share: !!H.S.set.share }; }
  catch (e) { F.snap = null; FILM = null; return; }
  FILM = null;
  if (F.ref && F.orig && F.orig.sha256 === F.ref.sha256) F.orig = { sha256: F.orig.sha256, mime: F.orig.mime, name: F.orig.name, bytes: null };   // the server has the image
  if (F.kc) { const held = new Set(Object.values(F.ref ? shasOf(F.ref) : {})), keep = {}; for (const [s2, o] of Object.entries(F.snapImgs || {})) if (!held.has(s2)) keep[s2] = o; F.snapImgs = keep; }
  AWAY.add(F);
  for (const old of [...AWAY]) {   // the oldest first, never the one just closed
    if (AWAY.size <= AWAY_MAX || old === F) break;
    if (letGo(old, true)) H.prompt(`An earlier X-ray (${old.label}) had changes not yet in the patient list; they are kept in this browser tab and offered back when you open that X-ray again (at most ${AWAY_MAX} wait on the page).`);
    else { old.keepFail = KEEP_WHY; H.prompt(`An earlier X-ray (${old.label}) has changes not yet in the patient list that this browser could not keep (${KEEP_WHY}): they stay on this page (${AWAY.size} X-rays waiting; see the panel to try again or discard).`); break; }
  }
  enqueue(F); H.panel();
}
// a film's label for the panel and the kept-changes store: side, joint, when it was opened and the start of the image's
// hash; never the patient's name or record number (round 13b re-review: the store is in the browser)
function labelOf(F) {
  const m = F.snap ? F.snap.meta : (() => { try { return H.metaNow(); } catch (e) { return null; } })();
  const side = m && m.side === 'L' ? 'Left' : 'Right', joint = jointOf(m && m.procedure);
  if (F.kc) { let n = 0; try { n = Object.keys((F.snap ? F.snap.project : H.projectNow()).films || {}).length; } catch (e) { n = 0; } return `${side} knee case opened ${hhmm(new Date(F.openedAt || Date.now()))}, ${n} film${n === 1 ? '' : 's'}, case ${String(F.ck).slice(2, 8)}`; }
  return `${side} ${joint} X-ray opened ${hhmm(new Date(F.openedAt || Date.now()))}, image ${String((F.orig && F.orig.sha256) || '').slice(0, 6)}`;
}
// a film no longer on screen is let go: its unsaved plan (if any) goes to the kept-changes store when keep is true
// → false (and nothing let go) when keep is asked and the changes could not be written: the film stays on the page
function letGo(F, keep) {
  if (keep && F.snap && (F.orig || F.kc) && !keepUnsent(F, F.snap.kept || keptStateOf(F.snap.project))) return false;   // (kept: a knee case's films left out of its saves too)
  AWAY.delete(F); clearTimeout(F.retryT); F.retryT = 0; F.nextTry = 0;
  F.snap = null; F.orig = null; F.snapImgs = null; F.gone = true;   // nothing more is sent for it
  return true;
}
// something on the page changed (app.js save(), the panel, a pointer lifted): the plan is saved about 20 s after the
// last change (at most a minute after the first one, however busy the page is)
function notify() {
  const F = FILM;
  if (!F || !live(F) || !F.eligible || F.conflict || F.gone) return;
  const now = Date.now(); if (!F.first) F.first = now;
  clearTimeout(F.timer);
  F.timer = setTimeout(() => tick(F), Math.max(0, Math.min(AUTO.delay, F.first + AUTO.maxWait - now)));
}
// the guided step changed: saved now (when there is a change to save)
export function stepChanged() {
  const F = FILM;
  if (!F || !live(F) || !F.eligible) return;
  let st = null; try { st = H.stepNow(); } catch (e) { st = null; }
  if (st === F.step) return;
  F.step = st;
  setTimeout(() => tick(F), AUTO.step);
}
const DOWN = new Map();   // pointers pressed on the page (id → when): nothing is saved while a drag is under way
function dragging() {
  for (const [id, t] of DOWN) if (Date.now() - t > 120000) DOWN.delete(id);   // a lift the page never heard of
  return DOWN.size > 0 || !!H.S.drag || !!H.S.filling;
}
function tick(F) {
  if (!live(F) || AUTO.paused) return;
  clearTimeout(F.timer); F.timer = 0;
  if (!F.eligible || F.conflict || F.gone || !refOf(F)) { F.first = 0; return; }
  if (!srvDirty(F)) { F.first = 0; return; }
  if (dragging()) { F.waitUp = true; return; }
  F.first = 0; enqueue(F);
}
// force: the retry timer, the browser back online, or the surgeon's "Try now". Anything else waits for the film's next
// retry time once a save has failed (finding 7: ordinary activity never shortens the wait).
function enqueue(F, force = false) {
  if (!force && F.nextTry && Date.now() < F.nextTry) return;
  if (force) F.forceNext = true;
  if (F.queued) return;
  F.queued = true;
  serial(() => runJob(F)).catch(() => { /* runJob reports its own failures */ });
}
// one automatic save of film F: its case made (image first) or its plan saved on top of the version it has. The plan,
// its revision and the case version are taken together here, when its turn has come (finding 2).
async function runJob(F, force = false) {
  F.queued = false;
  if (F.forceNext) { force = true; F.forceNext = false; }
  if (!force && F.nextTry && Date.now() < F.nextTry) return;   // a save failed since this one was queued: it waits its turn (finding 7)
  if (force) { clearTimeout(F.retryT); F.retryT = 0; F.nextTry = 0; }
  if (F.conflict || F.gone) return;
  let ref = refOf(F);
  let project, meta, rev, base, at, uid, partial = false;
  if (live(F)) {
    if (dragging()) { F.waitUp = true; return; }
    if (ref ? !srvDirty(F) : !needsCase(F)) { if (F.status && F.status.kind === 'offline') status(F, ref ? 'saved' : null); return; }
    if (!ref) {   // a new case: is there one already for this image or knee case (another opening of it, a save whose answer was lost)?
      status(F, 'saving');
      const sha = F.ck;
      let got = null;
      try { got = await pendingCase(sha, F); } catch (e) { failed(F, e); return; }
      if (got && got.own && live(F)) { F.created = true; setRef(F, got.ref); }   // its own earlier save made it: saved on top below
      else if (got && live(F)) {
        const k = got.ref; remember(sha, k);
        if (!H.isDirty()) { F.wantCase = false; await openKnown(k, F.kc ? null : F.orig.bytes, F.kc ? null : sha); return; }   // nothing done here yet: its case, as saved
        F.created = true; F.wantCase = false; setRef(F, k); F.conflict = true; status(F, 'conflict'); return;   // both changed: never one over the other
      }
      if (!live(F)) { enqueue(F); return; }
      // a single film: its image first, then the plan as it is once it is there; a knee case sends its films one by one
      // with its saves (postCase)
      if (!F.kc) {
        try { await sendImage({ bytes: F.orig.bytes, sha256: sha, mime: F.orig.mime }, () => {}); }
        catch (e) { if (e instanceof ApiError && e.status === 422) UPS.delete(sha); failed(F, e); return; }
      }
      for (let i = 0; i < 100 && live(F) && (H.S.auto.busy || H.S.filling); i++) await sleep(100);
      if (!live(F)) { enqueue(F); return; }   // another film opened meanwhile: its snapshot is saved in turn
      ref = refOf(F);
    }
    try { await H.preparePatient(); } catch (e) { failed(F, e); return; }   // a restored case's patient, read from the list first (Fable, round 8)
    if (!live(F)) { enqueue(F); return; }
    try { project = H.projectNow(); meta = metaFor(F); aotpCheck(project); rev = H.revision(); base = H.base(); at = Date.now(); uid = F.uid; partial = unsentNow(F); }
    catch (e) { status(F, 'fail', `Not saved: ${e.message || e}`, 'The plan cannot be saved as it is: the page would refuse to open it. Change or undo the last step.'); return; }
  } else {
    if (!AWAY.has(F) || !F.snap) { AWAY.delete(F); return; }
    if (F.snap.keepOnly) {   // nothing to save, only films the browser could not keep: try to keep them again
      if (keepUnsent(F, F.snap.kept)) { F.snap = null; AWAY.delete(F); F.keepFail = ''; H.panel(); } else { F.keepFail = KEEP_WHY; H.panel(); }
      return;
    }
    if (!ref && F.created) { letGo(F, false); H.panel(); return; }   // its case was hidden meanwhile: nothing is made again
    ({ project, meta, rev, at, uid } = F.snap); partial = !!F.snap.partial;
  }
  status(F, 'saving');
  const ctx = { F, auto: true, orig: F.orig, ck: F.ck, images: live(F) ? H.images() : F.snapImgs || {}, ref, live: () => live(F), share0: live(F) ? !!H.S.set.share : !!(F.snap && F.snap.share) };
  try {
    const r = await saveToServer(ctx, project, meta);
    if (r.ok) {
      F.created = true; F.retryN = 0; F.nextTry = 0; clearTimeout(F.retryT); F.retryT = 0;
      const cur = refOf(F);   // the list may have raised the version meanwhile (Share): keep the higher
      setRef(F, cur && cur.id === ctx.ref.id && cur.rev > ctx.ref.rev ? { ...ctx.ref, rev: cur.rev } : ctx.ref);
      acked(F, rev, at, uid, partial);
      // the page's own "unsaved" mark: cleared only for the revision saved; if the page set a new starting point meanwhile
      // (its automatic search done), that stands unless the page is now exactly what was saved
      if (live(F) && (H.base() === base || H.revision() === rev)) H.markSaved(rev);
      status(F, 'saved');
      if (live(F) && srvDirty(F)) notify();
    } else if (r.other) {   // another opening of this X-ray made its case meanwhile
      if (live(F)) { F.created = true; F.wantCase = false; setRef(F, r.other); F.conflict = true; status(F, 'conflict'); }
      else if (!F.snap || !F.snap.userDirty) { letGo(F, false); H.panel(); }   // nothing of the surgeon's here: the other has it all
      else { F.created = true; F.ref = r.other; F.conflict = true; status(F, 'conflict'); parked(F); }
    } else if (r.conflict) { F.conflict = true; status(F, 'conflict'); parked(F); }
    else if (r.gone) { F.gone = true; status(F, 'fail', 'Not saved: this case is no longer in your patient list', 'The case was hidden, or it is no longer shared with you. Your plan is still on this page: Save project offers to save it as your own copy.'); parked(F); }
    else { status(F, 'fail', 'Not saved: it changed again meanwhile', r.text); retryLater(F); }
  } catch (e) { failed(F, e); }
}
// the server has answered for revision rev of film F (taken at time at)
// uid: the opening the saved plan was taken under (a plan put back later carries another one; Astra's fourth review)
// partial: the save left out films whose X-ray is not on the page (a knee case restored from kept changes; Astra, 15 Oct,
// P1 2): what the server answered for is not the whole case, so the kept changes stay (they hold those films' plans) until
// a save that holds every film is answered
function acked(F, rev, at = Date.now(), uid = F.uid, partial = false) {
  if (live(F)) F.srvKey = rev;
  if (!live(F) || !srvDirty(F)) F.storeFail = null;
  if (F.snap && F.snap.rev === rev) {
    // a case no longer on screen whose films left out could not be kept in this browser: it stays on the page until they are
    if (partial && F.snap.kept && !keepUnsent(F, F.snap.kept)) { F.snap = { ...F.snap, keepOnly: true }; F.keepFail = KEEP_WHY; H.panel(); }
    else { F.snap = null; if (AWAY.delete(F)) H.panel(); }
  }
  F.partial = !!partial;
  if (!partial) dropUnsent(F, at, uid);
}
// the film on screen leaves films out of its saves (their kept changes must stay)
const unsentNow = F => !!F && F.kc && live(F) && H.unsentRoles().length > 0;
// a film no longer on screen that cannot be saved automatically (a newer version elsewhere, the case gone): its plan goes
// to the kept-changes store and it is let go; opening that X-ray again offers it back (finding 6)
function parked(F) {
  if (live(F) || !AWAY.has(F)) return;
  const why = F.conflict ? 'a newer version is there' : 'its case is no longer in your list';
  if (letGo(F, true)) { H.panel(); H.prompt(`An earlier X-ray (${F.label}) could not be saved to the patient list (${why}); its changes are kept in this tab and offered back when you open it again.`); }
  else { H.panel(); H.prompt(`An earlier X-ray (${F.label}) could not be saved to the patient list (${why}), and its changes could not be kept in this browser (${KEEP_WHY}): they stay on this page; see the panel.`); }
}
// a save that failed: no connection (or the server busy) is tried again later and when the connection is back
function failed(F, e) {
  if (e && e.shareChanged) { status(F, 'saving'); setTimeout(() => { if ((live(F) && (needsCase(F) || refOf(F))) || AWAY.has(F)) enqueue(F, true); }, AUTO.step); return; }   // made again as it is now (a new case too)
  if (e instanceof ApiError && (e.status === 0 || e.status >= 500)) {
    const part = e.parts > 1 ? ` ${e.sent} of ${e.parts} parts of the X-ray are on the server; the rest follow.` : '';
    status(F, 'offline', 'Offline: will retry', `No connection to t.addisortho.com (or the server is busy). The plan stays on this page and is saved when the connection is back.${part}`);
    retryLater(F);
  } else if (e instanceof ApiError && e.status === 401) status(F, 'fail', 'Not saved: your sign-in has ended', 'Sign in again in a new tab, then come back: nothing on this page is lost, and the next change (or Save project) saves it.', true);
  else status(F, 'fail', `Not saved: ${e instanceof ApiError ? why(e) : (e && e.message) || e}`, 'The next change, or Save project, tries again.');
}
function retryLater(F) {
  clearTimeout(F.retryT);
  const d = AUTO.retry[Math.min(F.retryN, AUTO.retry.length - 1)] * (0.8 + 0.4 * Math.random());
  F.retryN++; F.nextTry = Date.now() + d;
  F.retryT = setTimeout(() => { F.retryT = 0; if (live(F) || AWAY.has(F)) enqueue(F, true); }, d);
}
function retryAll() { for (const F of [FILM, ...AWAY]) if (F && F.status && F.status.kind === 'offline' && (live(F) || AWAY.has(F))) { F.retryN = 0; enqueue(F, true); } }
// page hidden or closed: what is waiting is sent now (a small save with keepalive), and whatever has not been answered is
// kept for the next load (finding 5); shown again: nothing kept is needed while the page itself still has it
function hiding() {
  HIDING = true;
  const F = FILM;
  if (F && live(F) && F.eligible && (needsCase(F) || (refOf(F) && srvDirty(F)) || unsentNow(F))) {   // also a film changed elsewhere or gone, and a knee case whose saves leave films out
    let ok = false; try { ok = keepUnsent(F, H.keptNow()); } catch (e) { ok = false; }
    F.storeFail = ok ? null : { text: `Not saved: ${KEEP_WHY === 'storage full' ? 'storage full' : 'too many kept'}`, detail: `This browser could not keep the unsaved changes for later (${KEEP_WHY}). Keep this page open until the patient list has them, or use Save project to save the file.` };
    if (!ok) status(F, 'fail', F.storeFail.text, F.storeFail.detail);
    if (!F.conflict && !F.gone && refOf(F) && !H.S.filling) { clearTimeout(F.timer); F.timer = 0; F.first = 0; enqueue(F); }
  }
  for (const A of AWAY) if (A.snap && (A.orig || A.kc) && !keepUnsent(A, A.snap.kept || keptStateOf(A.snap.project))) A.keepFail = KEEP_WHY;
}
function shown() { HIDING = false; }

// ---------- films waiting for the patient list, in the Measurements and save segment (finding 6) ----------
export function awayEl() {
  const d = node('div', 'projaway');
  const kept = H && H.STANDALONE ? Object.entries(unsentAll()).filter(([k, r]) => ![...AWAY].some(F => rkey(F.ck, F.uid) === k) && !(FILM && rkey(FILM.ck, FILM.uid) === k)) : [];
  if (!H || !H.STANDALONE || (!AWAY.size && !kept.length)) { d.hidden = true; return d; }
  if (kept.length) {   // changes kept in this tab (offered back when that X-ray is opened again)
    d.appendChild(node('div', 'pah', `Changes kept in this tab, offered back when the X-ray is opened again (${kept.length} of at most ${UNSENT_MAX}):`));
    for (const [key, rec] of kept) {
      const row = node('div', 'par kept'); row.appendChild(node('span', 'pan', String(rec.label || 'X-ray')));
      row.appendChild(button('Discard', () => { if (!confirm('Discard these kept changes? They cannot be brought back.')) return; dropKey(key); H.panel(); }, '', 'Forget these kept changes'));
      d.appendChild(row);
    }
  }
  if (!AWAY.size) return d;
  d.appendChild(node('div', 'pah', `Earlier X-rays not yet in the patient list (${AWAY.size}):`));
  for (const F of AWAY) {
    const row = node('div', 'par');
    row.appendChild(node('span', 'pan', `${F.label}: ${F.status ? F.status.text : 'waiting'}${F.keepFail ? ` (could not be kept in this browser: ${F.keepFail}; it stays on this page)` : ''}`));
    row.appendChild(button('Try now', () => { F.retryN = 0; enqueue(F, true); }, '', 'Send it to the patient list now'));
    row.appendChild(button('Discard', () => {
      if (!confirm(`Discard the changes of ${F.label} that are not in the patient list? They cannot be brought back.`)) return;
      letGo(F, false); dropKey(rkey(F.ck, F.uid)); H.panel();
    }, '', 'Forget these changes'));
    d.appendChild(row);
  }
  return d;
}

// ---------- the header's status (t.addisortho.com) ----------
// kind: saving | saved | offline | fail | conflict. A button with its words as its name (the details on a tap) and a
// polite live region for screen readers; on a phone a dot on Save project (the header has no room for words there).
function status(F, kind, text = '', detail = '', signin = false) {
  // the browser could not keep unsent changes: that stays said until the server has them (round 13b re-review)
  if (F.storeFail && (kind === 'saving' || kind === 'offline')) { text = F.storeFail.text; detail = F.storeFail.detail + (kind === 'offline' ? ' No connection now: it is tried again.' : ''); kind = 'fail'; }
  if (!kind) F.status = null;
  else {
    const t = new Date();
    const words = { saving: 'Saving…', saved: `Saved ✓ ${hhmm(t)}`, offline: 'Offline: will retry', conflict: 'Changed elsewhere: reload or save as my copy' }[kind] || text || 'Not saved';
    F.status = { kind, text: kind === 'fail' ? (text || 'Not saved') : words, detail, signin, at: t };
  }
  if (F === FILM) {
    render();   // a phone has only the dot: a failure is said in the prompt line too
    if ((kind === 'fail' || kind === 'conflict') && matchMedia('(max-width: 700px), (pointer: coarse) and (orientation: portrait)').matches) H.prompt(`${F.status.text}.${F.status.detail ? ' ' + F.status.detail : ''}`);
  } else if (AWAY.has(F)) { const d = document.querySelector('.projaway'); if (d) d.replaceWith(awayEl()); }
}
function render() {
  const b = $('autoStat'), sr = $('autoStatSr'), sv = $('projSaveBtn');
  if (!b) return;
  const st = FILM && live(FILM) ? FILM.status : null;
  b.hidden = !st;
  b.className = 'autostat' + (st ? ' st-' + st.kind : '');
  if (sv) { if (st) sv.dataset.st = st.kind; else delete sv.dataset.st; }
  if (!st) { b.textContent = ''; if (sr.textContent) sr.textContent = ''; return; }
  const tap = st.kind === 'fail' || st.kind === 'conflict' || st.kind === 'offline';
  b.textContent = st.text;
  b.setAttribute('aria-label', `Patient list: ${st.text}${tap ? ' (details)' : ''}`);
  const unnamed = st.kind === 'saved' && FILM.placeholder && !H.S.set.share && !H.patientKnown();
  const noHosp = st.kind === 'saved' && !unnamed && !H.S.set.share && !H.hospitalKnown();   // (15 Oct: listed under "Hospital not set" until picked)
  b.title = `Patient list: ${st.text}${st.detail ? `. ${st.detail}` : ''}${tap ? ' (tap for details)' : unnamed ? ` as "${FILM.placeholder}" (tap to add the patient's name and record number)` : noHosp ? ' under "Hospital not set" (tap to pick the hospital)' : ''}`;
  const say = `Patient list: ${st.text}`;
  if (sr.textContent !== say) sr.textContent = say;
}
async function details() {
  const F = FILM; if (!F || !live(F) || !F.status) return;
  const st = F.status;
  if (st.kind === 'conflict' || (st.kind === 'fail' && F.gone)) return saveProject();   // its choices: Reload the newer version, Save as my copy
  if (st.kind === 'saved' && !H.S.set.share && ((F.placeholder && !H.patientKnown()) || !H.hospitalKnown())) {   // a case made before its patient was named, or its hospital picked
    if (await askPatient()) { H.panel(); flushSoon(F); }
    return;
  }
  const lines = { saving: 'Saving to the templated-patients list…', saved: `Saved to the templated-patients list at ${hhmm(st.at)}${refOf(F) ? ` (version ${refOf(F).rev})` : ''}.`, offline: st.detail, fail: `${st.text}. ${st.detail || ''}` };
  const pick = await dialog((box, done) => {
    const p = node('p', '', lines[st.kind] || st.text); box.appendChild(p);
    if (st.signin) { p.append(' '); p.appendChild(signinLink()); }
    const r = node('div', 'row');
    if (st.kind === 'offline' || st.kind === 'fail') r.appendChild(button('Try now', () => done('retry'), 'primary'));
    r.appendChild(button('Close', () => done(null), st.kind === 'offline' || st.kind === 'fail' ? '' : 'primary')); box.appendChild(r);
    if (st.kind === 'fail' && openSubject()) {   // a save that cannot be made: the X-ray or the case can be removed for good (21 Oct 2026)
      const d = node('div', 'row');
      d.append(button('Delete this X-ray (permanent)', () => { done(null); runDelete('xray'); }, 'cdel'), button('Delete this patient case (permanent)', () => { done(null); runDelete('case'); }, 'cdel'));
      box.appendChild(d);
    }
  });
  if (pick === 'retry' && live(F)) { F.retryN = 0; if (!refOf(F) && !needsCase(F)) return saveProject(); enqueue(F, true); }
}
function flushSoon(F) { if (live(F)) setTimeout(() => tick(F), AUTO.step); }

// ---------- wiring ----------
// host: app.js's side. Makes the header's Save project button (and, on t.addisortho.com, its status and the list in the
// start card).
export function initProjects(host) {
  H0 = host; H = { ...H_DEFAULTS, ...host };
  const slot = $('projSlot');
  if (slot && !slot.querySelector('.projsave')) {
    if (H.STANDALONE) {
      const st = node('button', 'autostat'); st.type = 'button'; st.id = 'autoStat'; st.hidden = true; st.onclick = () => details();
      const sr = node('span', 'sr-only'); sr.id = 'autoStatSr'; sr.setAttribute('role', 'status'); sr.setAttribute('aria-live', 'polite');
      slot.append(sr, st);
    }
    const b = node('button', 'icon projsave'); b.type = 'button'; b.id = 'projSaveBtn'; b.setAttribute('aria-label', 'Save project'); b.disabled = true;
    b.title = H.STANDALONE ? 'Save project: the X-ray and the whole plan, to this device and to the templated-patients list (confidential: not for sharing outside). The list is also saved automatically.' : 'Save project: the X-ray and the whole plan in one file, to reopen and adjust later (confidential: not for sharing outside)';
    if (H.STANDALONE) b.setAttribute('aria-describedby', 'autoStatSr');
    b.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"><path d="M5 3h11l4 4v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a1 1 0 0 1 1-1z"/><path d="M8 3v5h7V3"/><rect x="7.5" y="13" width="9" height="6" rx="1"/></svg><span class="lg">Save project</span>';
    b.onclick = () => saveProject();
    slot.appendChild(b);
  }
  if (H.STANDALONE && typeof addEventListener === 'function') {   // (not in Node, where test.mjs runs the save with a stand-in page)
    addEventListener('pointerdown', e => { DOWN.set(e.pointerId, Date.now()); }, true);
    const up = e => { DOWN.delete(e.pointerId); if (DOWN.size) return; const F = FILM; if (F && F.waitUp && live(F)) { F.waitUp = false; setTimeout(() => tick(F), 50); } else notify(); };
    addEventListener('pointerup', up, true); addEventListener('pointercancel', up, true);
    addEventListener('blur', () => { DOWN.clear(); });
    for (const t of ['change', 'input', 'keyup']) addEventListener(t, () => notify(), true);
    addEventListener('online', retryAll);
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') hiding(); else shown(); });
    addEventListener('pagehide', hiding); addEventListener('pageshow', shown);
  }
  syncButtons();
}
export const sync = () => { if (H) { syncButtons(); if (H.STANDALONE) notify(); } };
// A single film made into a knee case on this page (app.js kcStart; contract section 12): the same case on the server,
// its key now the knee case's own; a new-case save of that film not yet answered goes on under the new key (the same
// requestId and body), so the case is still made once.
export function toCase(ck, role) {
  const F = FILM;
  if (!H || !H.STANDALONE || !F || F.kc || !F.unit) return;
  const old = F.ck, c = CREATES.get(old);
  if (c) { setPending(old, null); setPending(ck, c.rid, c.body, F, c.share); const e = CREATES.get(ck); if (e) e.role = role; }   // the body's sharing state moves with it (Astra, round 6)
  else { const rid = pendingRid(old); if (rid) { setPending(old, null); setPending(ck, rid, null, F); } }
  F.ck = ck; F.kc = true; F.sha = null; F.orig = null; F.unit = H.unit();
  if (H.S.caseRef) setRef(F, H.S.caseRef);
  notify();
}
// a knee case's films changed (one added, replaced, moved or taken out): saved like any change
export function caseChanged() { const F = FILM; if (F && live(F)) { if (needsCase(F)) enqueue(F); else notify(); } }
export function forget() { OUT.file = null; OUT.server = null; }   // a different film: the last save's outcomes no longer apply
// for the automated UI tests (28l): the wait, the status, whether the queue is idle, the films waiting
export const autoTest = {
  delay(ms, maxWait) { AUTO.delay = ms; if (maxWait) AUTO.maxWait = maxWait; },
  retry(list) { AUTO.retry = list; },
  pause(on) { AUTO.paused = !!on; },
  status: () => (FILM && FILM.status ? { kind: FILM.status.kind, text: FILM.status.text } : null),
  busy: () => JOBS > 0,
  away: () => [...AWAY].map(F => ({ label: F.label, img: !!F.img, bytes: !!(F.orig && F.orig.bytes), kind: F.status ? F.status.kind : null })),
  nextTry: () => (FILM && FILM.nextTry ? FILM.nextTry - Date.now() : 0),
  hiding: () => HIDING,
};
// ---------- permanent delete (Sami, 21 Oct 2026; contract section 14: "no permanent deletes" no longer holds) ----------
// Offered on the errors of an open X-ray or case (a failed save, a refused film, an X-ray that cannot be read) and on a list
// entry that cannot be opened. (Each list row has its own "Delete (permanent)" since 22 Oct 2026; Hide keeps the files.) These remove for good:
// kind "case": the whole patient case (POST /projects/<id>/purge); kind "xray": the X-ray the error is about, i.e. its film of
// a knee case (POST /projects/<id>/film/<role>; the case's last film means the whole case, asked once more), or, for a case of
// one X-ray, the case. A subject is {id (server case, or null: nothing saved yet), role (knee film), what (a few words for the
// question), open (it is what is on screen), onDone, report}. Sent with a requestId, retried as every write is; a 404 means it
// is already gone. Nothing identifying goes into the request (ids only; the server logs and audits ids and uids only).
const gone404 = e => e instanceof ApiError && e.status === 404;
async function purgeCall(path) {
  const q = ridOf();
  try { await retrying(() => call('POST', path, { json: { requestId: q } })); } catch (e) { if (!gone404(e)) throw e; }
}
// what is on screen as a subject (null when nothing is open on t.addisortho.com)
export function openSubject(extra = {}) {
  if (!H || !H.STANDALONE || !(H.S.img || H.isCase())) return null;
  const F = FILM, ref = F && live(F) ? refOf(F) : H.S.caseRef, kc = H.isCase() && H.S.kc;
  return { id: ref ? ref.id : null, role: kc && ROLES.includes(kc.active) ? kc.active : null, open: true, ...extra };
}
// the page after what it showed is gone for good: nothing of it is kept or sent again, and the page starts over
function resetAfterDelete() {
  const F = FILM;
  if (F) { letGo(F, false); dropKey(rkey(F.ck, F.uid)); }
  FILM = null; H.S.caseRef = null;
  if (H.afterDelete) H.afterDelete(); else if (typeof location !== 'undefined') location.reload();
}
export async function runDelete(kind, subj = null) {
  subj = subj || openSubject();
  if (!subj || !H) return false;
  const wt = clip(subj.what, 120), open = !!subj.open || (!!subj.id && !!H.S.caseRef && H.S.caseRef.id === subj.id), w = wt ? ` (${wt})` : '';   // no "()" when there is no name
  const hideNote = ' (Hide in the patient list only hides a case and keeps its files; this does not.)';
  let q;
  if (subj.caseOnly && kind === 'xray') kind = 'case';
  if (!subj.id && !open) return false;
  if (!subj.id) q = 'Nothing of this is saved in the patient list yet. Remove it from this page?';
  else if (kind === 'xray' && subj.role) q = `Delete the ${ROLE_NAME[subj.role]} X-ray of this knee case${w} PERMANENTLY? It is removed from the server for good and cannot be brought back. The case's other X-rays stay.`;
  else if (kind === 'xray') q = `Delete this X-ray${w} PERMANENTLY? It is the only X-ray of its patient case, so the whole case is removed from the server for good and cannot be brought back.${hideNote}`;
  else q = `Delete this patient case${w} PERMANENTLY? Every X-ray, plan and note in it is removed from the server for good and cannot be brought back.${hideNote}`;
  if (!confirm(q)) return false;
  const F = open ? FILM : null, g0 = F ? F.gone : false;
  if (F) { F.gone = true; clearTimeout(F.timer); F.timer = 0; }   // nothing more is saved for it
  if (open) { try { await QUEUE; } catch (e) { /* its own failure is shown elsewhere */ } }   // a save on its way ends first, so it cannot make the case again after this
  let id = subj.id;
  if (open && !id) { const r = F ? refOf(F) : H.S.caseRef; id = r ? r.id : null; }   // made by the save that was on its way
  const report = subj.report || (t => H.prompt(t));
  try {
    if (id) {
      if (kind === 'xray' && subj.role) {
        try { await purgeCall(`/projects/${id}/film/${subj.role}`); }
        catch (e) {
          if (!(e instanceof ApiError && e.status === 422 && e.code === 'last_film')) throw e;
          if (!confirm(`This is the only X-ray left in the case, so the whole patient case${w} would be deleted PERMANENTLY. Continue?`)) { if (F) F.gone = g0; return false; }
          await purgeCall(`/projects/${id}/purge`);
        }
      } else await purgeCall(`/projects/${id}/purge`);
    }
  } catch (e) {
    if (F) { F.gone = g0; if (live(F)) notify(); }
    report(`Not deleted: ${why(e)}.`);
    return false;
  }
  if (subj.onDone) subj.onDone();
  if (open) resetAfterDelete();
  return true;
}
// the two choices for an error message that lists links: [[text, fn], …]
export function deleteItems(subj) {
  if (!subj || !H || !H.STANDALONE) return [];
  const c = ['Delete this patient case (permanent)', () => runDelete('case', subj)];
  return subj.caseOnly ? [c] : [['Delete this X-ray (permanent)', () => runDelete('xray', subj)], c];   // caseOnly: a knee case's film whose role is not known here
}
export const _test = { call, sendImage, keepaliveOk, get UPS() { return UPS; }, setPending, pendingCase, CREATES, shareGuard, setFilm(F) { FILM = F; } };
export { choose, dialog };   // the page's own small dialog, for app.js (the knee case: a film of another patient)
