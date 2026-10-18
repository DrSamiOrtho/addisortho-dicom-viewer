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
export const IMAGE_MAX = 40 * 1024 * 1024;          // contract 3: the original image
export const PIXELS_MAX = 60000000;                  // contract 3: 60 megapixels
export const PROJECT_MAX = 2 * 1024 * 1024;          // contract 3: project.json
export const ZIP_MAX = 45 * 1024 * 1024;             // contract 5: an .aotp expanded
export const FILE_MAX = ZIP_MAX + 1024 * 1024;       // an .aotp on disk (stored entries and their headers)
export const MIME_EXT = { 'application/dicom': 'dcm', 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp' };
export class ProjectError extends Error {}
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
const WFS = { hip: ['cupR', 'stemR', 'cupL', 'stemL'], kneeAP: ['femur', 'tibia'], kneeLat: ['femur', 'tibia'] };
const LM_KEY = /^(?:(?:td|lt|head|canal|ac)[RL]|hip|femShaft|knee|condMed|condLat|platMed|platLat|ankle|tibShaft|antCortex|postCond|platAnt|platPost|tibAxis)$/;
const ORIENT_FROM = ['header', 'marker', 'assumed', 'user'];
const SETTINGS = { cupRule: ['anatomic', 'teardrop', 'head', 'mirror'], legTarget: ['equal', 'keep'], mirrorRef: ['native', 'plan'], offsetTarget: ['auto', 'own', 'none'], stemRule: ['cup', 'offset'] };
const isObj = v => !!v && typeof v === 'object' && !Array.isArray(v);
const isNum = v => typeof v === 'number' && Number.isFinite(v);
const isStr = (v, max) => typeof v === 'string' && v.length <= max;
const ROT_LIM = 20 * Math.PI / 180 + 1e-6;   // the page's own limit for turning the film (app.js ROT_MAX)
const SCALE = [0.005, 5];                    // mm per pixel: a 0.005 mm pixel or a 5 mm one is not an X-ray

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
  if (patient && WFS[st.wf] && (st.wf === 'hip') !== (patient.procedure === 'hip')) bad('the plan and the patient disagree on hip or knee');
  bool(st.sideSet, 'side chosen'); bool(st.mirrored, 'mirrored'); bool(st.prepDone, 'preparation done'); bool(st.wlAuto, 'auto contrast');
  oneOf(st.scope, ['both', 'one'], 'hips on the film'); oneOf(st.ref, ['own', 'other'], 'reference hip'); oneOf(st.cranial, ['up', 'down', 'left', 'right'], 'head direction');
  if (st.orient !== undefined && (!isObj(st.orient) || !ORIENT_FROM.includes(st.orient.from) || (st.orient.prevFrom !== undefined && !ORIENT_FROM.includes(st.orient.prevFrom)))) bad('orientation is not valid');
  if (st.cal !== null && st.cal !== undefined) {
    const c = st.cal;
    if (!isObj(c)) bad('calibration is not valid');
    else {
      oneOf(c.method, ['ball', 'line', 'dicom'], 'calibration method', false);
      range(c.mmPerPx, SCALE[0], SCALE[1], 'scale (mm per pixel)', false);
      if (c.method === 'ball') { if (inside(c.ball, 'calibration ball')) range(c.ball.r, 0.5, L, 'calibration ball radius', false); }
      if (c.method === 'line') { pts(c.line, 2, 2, 'calibration line'); range(c.lengthMm, 0.1, 5000, 'calibration length', false); }
      bool(c.confirmed, 'calibration confirmed');
    }
  }
  range(st.ballD, 5, 100, 'ball diameter'); range(st.vca, 0, 15, 'femoral valgus');
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
      for (const [k, n] of [['head', 8], ['headKind', 20], ['neck', 16]]) if (t[k] !== undefined && !isStr(t[k], n)) bad(`${what} ${k} is not valid`);
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
  return out;
}
// What is wrong with a project's implants against the implant library the page has loaded (Astra's page re-review, 13 Oct,
// finding 4): a list of sentences (empty: nothing). Each implant whose family the library has must be of its place's kind
// (a cup in a cup's place), and, when the plan was made with this library (its libraryId) and the family's outline is the
// one it was made with (geo), one of the family's sizes; a stem's head system one it takes, its head diameter one that
// system has and its neck one that head has; a tibial tray's total thickness one of the tray's. A family the library does
// not have (a range not open to the account) is kept aside as it is (contract 7), and a plan from another build has its
// implants placed again (app.js applyPlan), so neither is held against this library. lib: library.json as loaded.
const KIND_OF = { cupR: 'cup', cupL: 'cup', stemR: 'stem', stemL: 'stem', femur: 'tka-femur', tibia: 'tka-tibia' };
const own = (o, k) => isObj(o) && Object.prototype.hasOwnProperty.call(o, k);
export function libraryProblems(st, lib) {
  if (!isObj(st) || !isObj(st.tplAll) || !isObj(lib) || !Array.isArray(lib.families)) return [];
  const out = [], bad = m => { if (out.length < 12) out.push(m); }, fams = {}, heads = isObj(lib.heads) ? lib.heads : {};
  for (const f of lib.families) if (isObj(f) && typeof f.id === 'string') fams[f.id] = f;
  const same = st.libraryId === lib.libraryId, geo = isObj(st.geo) ? st.geo : {};
  for (const [wf, o] of Object.entries(st.tplAll)) {
    if (!isObj(o)) continue;
    for (const [sl, t] of Object.entries(o)) {
      if (!isObj(t) || typeof t.fam !== 'string' || !own(fams, t.fam)) continue;
      const f = fams[t.fam], what = `implant ${wf} ${sl.slice(0, 12)}`, name = String(f.label || f.id).slice(0, 60);
      if (KIND_OF[sl] && f.kind !== KIND_OF[sl]) { bad(`${what}: ${name} is not a ${KIND_OF[sl].replace('tka-', 'knee ')}`); continue; }
      if (!same || (f.geomId && geo[t.fam] && geo[t.fam] !== f.geomId)) continue;
      if (!Array.isArray(f.sizes) || !f.sizes.some(z => isObj(z) && z.size === t.size)) { bad(`${what}: ${name} has no size "${String(t.size).slice(0, 16)}"`); continue; }
      if (f.kind === 'stem') {
        const kinds = (Array.isArray(f.headKinds) ? f.headKinds : ['ceramic', 'cocr']).filter(k => isObj(heads[k]) && isObj(heads[k].sizes));
        if (!kinds.includes(t.headKind)) bad(`${what}: head system "${String(t.headKind).slice(0, 20)}" is not one ${name} takes`);
        else if (!own(heads[t.headKind].sizes, t.head)) bad(`${what}: no ${String(t.head).slice(0, 8)} mm head in that head system`);
        else if (!own(heads[t.headKind].sizes[t.head], t.neck)) bad(`${what}: no neck "${String(t.neck).slice(0, 16)}" for that head`);
      }
      if (f.kind === 'tka-tibia' && Array.isArray(f.thicknesses) && t.thick !== undefined && !f.thicknesses.includes(t.thick)) bad(`${what}: ${name} has no ${String(t.thick).slice(0, 8)} mm total thickness`);
    }
  }
  return out;
}
// What is wrong with a whole project (contract 5), the image's own bytes aside: a list of sentences.
export function projectProblems(p) {
  if (!isObj(p)) return ['not a templating project'];
  if (p.format !== FORMAT) return ['not an Addis Ortho templating project (format)'];
  if (p.v !== 1) return [`a project of version ${String(p.v).slice(0, 10)}: this page reads version 1`];
  const out = [];
  if (!isStr(p.savedAt, 40)) out.push('the date it was saved is missing');
  if (!isStr(p.libraryId, 40) || !/^[A-Za-z0-9._-]{1,40}$/.test(p.libraryId)) out.push('the implant library it was made with is missing');
  const pt = p.patient;
  if (!isObj(pt) || !['R', 'L'].includes(pt.side) || !['hip', 'knee'].includes(pt.procedure)) out.push('patient side and procedure are missing');
  else if ((pt.name !== undefined && !isStr(pt.name, 120)) || (pt.recordNo !== undefined && !isStr(pt.recordNo, 40)) || (pt.studyUID !== undefined && !isStr(pt.studyUID, 64))) out.push('patient details are not valid');
  const im = p.image;
  if (!isObj(im) || !isStr(im.fileName, 255) || !MIME_EXT[im.mime] || !SHA_RE.test(String(im.sha256)) || !Number.isInteger(im.size) || !Number.isInteger(im.width) || !Number.isInteger(im.height)) return [...out, 'the image description is missing or not valid'];
  if (im.size < 1 || im.size > IMAGE_MAX) out.push(`the image is larger than ${MB(IMAGE_MAX)}`);
  if (im.width < 1 || im.height < 1 || im.width > 100000 || im.height > 100000 || im.width * im.height > PIXELS_MAX) out.push(`the image is larger than ${PIXELS_MAX / 1e6} megapixels`);
  if (out.length) return out;
  return stateProblems(p.state, im.width, im.height, pt);
}
// The project and its image's bytes, checked together; throws ProjectError with what is wrong.
export async function checkProject(p, bytes) {
  const pr = projectProblems(p);
  if (pr.length) throw new ProjectError(`This project cannot be opened: ${pr.slice(0, 4).join('; ')}${pr.length > 4 ? '; …' : ''}.`);
  if (!(bytes instanceof Uint8Array) || bytes.length !== p.image.size) throw new ProjectError('This project cannot be opened: its image is not the size the project states.');
  if (await sha256hex(bytes) !== p.image.sha256) throw new ProjectError('This project cannot be opened: its image does not match the project (sha256 differs). The file is damaged or was changed.');
  return true;
}
// The .aotp's parts (zip.js): project.json, image.<ext>, and case.json when it has a case on t.addisortho.com.
export function aotpParts(project, image, { caseRef = null, imageCrc } = {}) {
  const enc = new TextEncoder(), json = enc.encode(JSON.stringify(project));
  if (json.length > PROJECT_MAX) throw new ProjectError(`the plan is larger than ${MB(PROJECT_MAX)} (too many eraser strokes?)`);
  const entries = [{ name: 'project.json', data: json }, { name: 'image.' + MIME_EXT[project.image.mime], data: image, crc: imageCrc }];
  if (caseRef && ID_RE.test(caseRef.id)) entries.push({ name: 'case.json', data: enc.encode(JSON.stringify({ id: caseRef.id, rev: caseRef.rev, sha256: caseRef.sha256 || null })) });
  return zipParts(entries, new Date(project.savedAt));
}
// An .aotp read and checked whole (contract 5) → {project, image (Uint8Array), caseRef}. Nothing is changed on the page.
export async function readAotp(u8) {
  if (u8.length > FILE_MAX) throw new ProjectError(`This project file is larger than ${MB(FILE_MAX)}.`);
  let files;
  try { files = await unzip(u8, { maxEntries: 4, maxTotal: ZIP_MAX }); } catch (e) { throw new ProjectError(`This is not a project file this page can open: ${e instanceof ZipError ? e.message : 'unreadable'}.`); }
  const names = [...files.keys()], imgs = names.filter(n => /^image\.(dcm|png|jpg|webp)$/.test(n));
  const extra = names.filter(n => n !== 'project.json' && n !== 'case.json' && !imgs.includes(n));
  if (extra.length) throw new ProjectError(`This project file has an unexpected entry "${extra[0]}".`);
  if (!files.has('project.json') || imgs.length !== 1) throw new ProjectError('This project file must hold project.json and one image.');
  const pj = files.get('project.json');
  if (pj.length > PROJECT_MAX) throw new ProjectError(`This project's plan is larger than ${MB(PROJECT_MAX)}.`);
  let project;
  try { project = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(pj)); } catch (e) { throw new ProjectError('This project file is damaged: project.json is not readable.'); }
  if (isObj(project) && isObj(project.image) && MIME_EXT[project.image.mime] && 'image.' + MIME_EXT[project.image.mime] !== imgs[0]) throw new ProjectError('This project file is damaged: the image is not the type the project says.');
  const image = files.get(imgs[0]);
  await checkProject(project, image);
  let caseRef = null;
  if (files.has('case.json')) {
    let c = null; try { c = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(files.get('case.json'))); } catch (e) { c = null; }
    if (!isObj(c) || !ID_RE.test(String(c.id)) || !Number.isInteger(c.rev) || c.rev < 1 || (c.sha256 !== null && c.sha256 !== undefined && !SHA_RE.test(String(c.sha256)))) throw new ProjectError('This project file is damaged: case.json is not valid.');
    caseRef = { id: c.id, rev: c.rev, sha256: c.sha256 || null };
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
  if (s === 413) return 'the X-ray or the plan is too large for the patient list';
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
// ---- a new case's identity, per image ----
const CREATES = new Map();   // sha256 → { rid, body }: a new case's save not yet answered (this page)
const PEND_TAB = 'aoc-tpl-pending', PEND_LOCAL = sha => 'aoc-tpl:pending:' + sha;
function pendingRid(sha) {
  const c = CREATES.get(sha); if (c) return c.rid;
  let r = null;
  try { r = (JSON.parse(sessionStorage.getItem(PEND_TAB) || '{}') || {})[sha] || null; } catch (e) { r = null; }
  if (!r && H.S.set.keepLocal) { try { r = JSON.parse(localStorage.getItem(PEND_LOCAL(sha)) || 'null'); } catch (e) { r = null; } }
  return isObj(r) && /^[a-z0-9-]{16,32}$/.test(String(r.rid)) ? r.rid : null;
}
function setPending(sha, rid, body, film = null) {
  if (rid) CREATES.set(sha, { rid, body, film }); else CREATES.delete(sha);
  try {
    const m = JSON.parse(sessionStorage.getItem(PEND_TAB) || '{}') || {};
    if (rid) m[sha] = { rid, at: Date.now() }; else delete m[sha];
    sessionStorage.setItem(PEND_TAB, JSON.stringify(m));
  } catch (e) { /* storage off */ }
  try { if (rid && H.S.set.keepLocal) localStorage.setItem(PEND_LOCAL(sha), JSON.stringify({ rid, at: Date.now() })); else if (!rid) localStorage.removeItem(PEND_LOCAL(sha)); } catch (e) { /* storage off */ }
}
// the case a lost new-case save made, found by its requestId in the whole list (null: certainly not made). The list is
// read to its end (round 13b re-review): a search that could not finish throws, so the requestId is kept and the search
// is made again later; it is never taken for "not made".
async function caseByRequest(sha, rid) {
  let before = null;
  for (let page = 0; page < 2000; page++) {
    const body = { limit: 50 }; if (before) body.before = before;
    const r = await call('POST', '/projects/list', { json: body });
    for (const m of Array.isArray(r.items) ? r.items : []) if (m.lastRequestId === rid && isObj(m.image) && m.image.sha256 === sha && ID_RE.test(String(m.id))) return { id: m.id, rev: m.rev, sha256: sha };
    if (typeof r.next !== 'string') return null;   // the end of the list: not there
    if (r.next === before) break;
    before = r.next;
  }
  throw new ApiError(503, 'busy');   // not finished: tried again later, the requestId kept
}
// one save to the server: {id, rev}; throws ApiError
async function postSave(ctx, project, meta, ref) {
  const img = project.image, F = ctx.F;
  let uploadId = null;
  if (!ref || ref.sha256 !== img.sha256) {   // the image goes up only when the server does not hold it for this case
    if (!ctx.orig.bytes) throw new ProjectError('the X-ray itself is no longer on this page (open it again to save it)');
    uploadId = await sendImage({ bytes: ctx.orig.bytes, sha256: img.sha256, mime: img.mime }, (d, n) => { setServer(ctx, 'busy', progressText(d, n)); say(ctx, progressText(d, n)); });
  }
  setServer(ctx, 'busy', 'Saving the plan to the patient list…');
  const body = { requestId: ridOf(), meta, project };   // one requestId for this save and all its retries (contract 4)
  if (ref) { body.id = ref.id; body.baseRev = ref.rev; }
  if (uploadId) body.uploadId = uploadId;
  if (!ref) setPending(img.sha256, body.requestId, body, F);   // known before it is sent: a reopened film or a reload finds it
  let r;
  try { r = await retrying(() => call('POST', '/projects/save', { json: body, timeout: 90000 })); }
  catch (e) {   // no answer at all: the save may or may not have been made, which the next save finds out
    const unknown = e instanceof ApiError && (e.status === 0 || e.status >= 500);
    if (!ref) { if (!unknown) setPending(img.sha256, null); }
    else if (unknown) F.lost = [...(F.lost || []), body.requestId].slice(-8);   // kept through the next save's 409 (finding 4)
    throw e;
  }
  if (!ref) { setPending(img.sha256, null); if (ID_RE.test(String(r.id))) remember(img.sha256, { id: r.id, rev: r.rev, sha256: img.sha256 }); }   // every film of this image knows its case at once
  F.lost = [];
  if (uploadId) UPS.delete(img.sha256);   // used up by the save
  if (!ID_RE.test(String(r.id)) || !Number.isInteger(r.rev)) throw new ApiError(500, 'answer');
  return r;
}
// before a new case is made for an image: a save of that image still unanswered is sent again as it was, or (after a
// reload) found by its requestId → the case, or null when none was made
// → { ref, own } (own: made by this film's own earlier save) or null. Every new case of an image, from the film on screen
// or one in the background, goes through here first, so there is one case per image (round 13b re-review, finding 3).
async function pendingCase(sha, F = null) {
  const k = knownCase(sha);   // another film of this image has its case already
  if (k) return { ref: k, own: false };
  const c = CREATES.get(sha);
  if (c && c.body) {
    const own = !!F && c.film === F;
    try { const r = await retrying(() => call('POST', '/projects/save', { json: c.body, timeout: 90000 })); setPending(sha, null); UPS.delete(sha); const got = { id: r.id, rev: r.rev, sha256: sha }; remember(sha, got); return { ref: got, own }; }
    catch (e) { if (!(e instanceof ApiError) || e.status === 0 || e.status >= 500) throw e; setPending(sha, null); UPS.delete(sha); return null; }   // refused: none was made by it
  }
  const rid = pendingRid(sha);
  if (!rid) return null;
  const found = await caseByRequest(sha, rid);   // throws when the list cannot be read to its end: tried again later
  setPending(sha, null);
  if (found) remember(sha, found);
  return found ? { ref: found, own: false } : null;
}
// the patient list part of a save, with its choices (a newer version, a case gone): → {ok, text, reload?}; the case it
// ends with is ctx.ref. An automatic save (ctx.auto) never asks: a newer version or a case gone is answered as
// {ok: false, conflict} / {ok: false, gone}, and nothing is overwritten.
async function saveToServer(ctx, project, meta) {
  const F = ctx.F, sha = project.image.sha256;
  if (!ctx.ref && !ctx.copy) {   // a new case of this image may already exist (another film of it, a save whose answer was lost)
    const got = await pendingCase(sha, F);
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
      ctx.ref = { id: r.id, rev: r.rev, sha256: sha };
      return { ok: true, text: `Saved to the patient list${r.rev > 1 ? ` (version ${r.rev})` : ''}.` };
    } catch (e) {
      if (!(e instanceof ApiError)) throw e;
      if (e.status === 422 && (e.code === 'image' || e.code === 'upload') && tries === 0) { UPS.delete(sha); if (ref) ctx.ref = { ...ref, sha256: null }; continue; }   // send the image (again) after all
      if (e.status === 409 && e.code === 'stale' && ref) {
        // the newer version may be this page's own earlier save whose answer was lost: then save on top of it
        if (F.lost && F.lost.length) {
          let m = null; try { m = (await call('GET', `/projects/${ref.id}`)).meta; } catch (er) { m = null; }
          if (m && F.lost.includes(m.lastRequestId) && Number.isInteger(m.rev)) { F.lost = []; ctx.ref = { ...ref, rev: m.rev }; continue; }
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
  if (!S.orig || !S.orig.sha256) return H.prompt(S.origWhy || 'This X-ray cannot be saved as a project: its original file is not available here. Open the file again.');
  if (S.filling) return H.prompt('Wait a moment: a stroke is still being filled.');
  if (!S.set.share && !H.patientKnown()) { const ok = await askPatient(); if (!ok) return; }
  const F = FILM && live(FILM) ? FILM : { stub: true, lost: [] };
  let project, meta;
  try { project = H.projectNow(); meta = metaFor(F); aotpCheck(project); } catch (e) { return H.prompt(`The project could not be saved: ${e.message || e}.`); }
  const ctx = { F, img: S.img, orig: S.orig, ref: null, ref0: null, rev: H.revision(), at: Date.now(), uid: F.uid, name: H.fileName(), refAtPress: F.stub ? S.caseRef : refOf(F) };
  ctx.live = () => H.S.img === ctx.img && H.S.orig === ctx.orig;
  busy = true; syncButtons();
  OUT.file = null; OUT.server = null;
  let reload = null, file = null, server = null, r = null;
  try {
    if (H.STANDALONE) {
      setServer(ctx, 'busy', 'Saving to the patient list…'); say(ctx, 'Saving the project…');
      if (!F.stub) status(F, 'saving');
      try {
        r = await serial(() => {
          const ref = F.stub ? S.caseRef : refOf(F);
          if (ctx.live()) {   // the plan, its revision and the case version, taken together now (finding 2)
            try { project = H.projectNow(); meta = metaFor(F); aotpCheck(project); ctx.rev = H.revision(); ctx.at = Date.now(); ctx.uid = F.uid; } catch (e) { return { ok: false, text: `Not saved to the patient list: ${e.message || e}.` }; }
          } else if (!sameRef(ref, ctx.refAtPress)) {
            return { ok: false, stale: true, text: `Not saved to the patient list by this press: the plan was saved there meanwhile${ref ? ` (version ${ref.rev})` : ''}, after the moment you pressed.` };
          }
          ctx.ref = ctx.ref0 = ref;
          return saveToServer(ctx, project, meta);
        });
        server = { state: r.ok ? 'ok' : 'fail', text: r.text }; reload = r.reload || null;
        if (!F.stub) {
          if (r.ok) { F.conflict = false; F.gone = false; F.created = true; acked(F, ctx.rev, ctx.at, ctx.uid); status(F, 'saved'); }
          else if (r.conflict) { F.conflict = true; status(F, 'conflict'); }
          else if (r.gone) { F.gone = true; status(F, 'fail', 'Not saved: this case is no longer in your patient list', 'The case was hidden, or it is no longer shared with you. Save project offers to save it as your own copy.'); }
        }
      } catch (e) {
        const part = e instanceof ApiError && e.parts > 1 && e.status === 0 ? ` (${e.sent} of ${e.parts} parts of the X-ray sent; Save project again goes on from there)` : '';
        server = { state: 'fail', text: `Not saved to the patient list: ${why(e)}${part}.`, signin: e instanceof ApiError && e.status === 401 };
        if (!F.stub) failed(F, e);
      }
    }
    try {
      const o = ctx.orig; if (o.crc === undefined) o.crc = crc32(o.bytes);
      saveBlob(new Blob(aotpParts(project, o.bytes, { caseRef: H.STANDALONE ? ctx.ref : null, imageCrc: o.crc }), { type: 'application/zip' }), ctx.name);
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
// the page refuses to save a project it would refuse to open (contract 7), its implants against this library too
function aotpCheck(p) { const pr = [...projectProblems(p), ...libraryProblems(p.state, H.S.lib)]; if (pr.length) throw new Error(pr.slice(0, 3).join('; ')); }

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
function choose(text, options, more = '') {
  return dialog((box, done) => {
    box.appendChild(node('p', '', text)); if (more) box.appendChild(node('p', 'note', more));
    const r = node('div', 'row'); for (const [label, v, cls] of options) r.appendChild(button(label, () => done(v), cls)); box.appendChild(r);
  });
}
const clip = (s, n) => String(s || '').replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, n);
// a picture without the patient's details (a phone photo of the film): asked once for this X-ray, for the list and the
// file name; "Leave them out" is a choice too
function askPatient() {
  return dialog((box, done) => {
    box.appendChild(node('p', '', 'This X-ray carries no patient name or record number. Add them for the templated-patients list and the file name?'));
    const f = node('div', 'fields');
    const nm = node('input'); nm.type = 'text'; nm.maxLength = 120; nm.autocomplete = 'off'; nm.setAttribute('aria-label', 'Patient name');
    const rn = node('input'); rn.type = 'text'; rn.maxLength = 40; rn.autocomplete = 'off'; rn.setAttribute('aria-label', 'Record number');
    const l1 = node('label', '', 'Patient name '), l2 = node('label', '', 'Record number '); l1.appendChild(nm); l2.appendChild(rn); f.append(l1, l2); box.appendChild(f);
    const set = (name, recordNo) => { H.S.patientIn = { name: clip(name, 120), recordNo: clip(recordNo, 40), asked: true }; done(true); };
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
export async function openCase(id, { force = false, bytes: have = null, how = 'list' } = {}) {
  const S = H.S;
  if (!ID_RE.test(String(id))) return;
  // the film on screen is saved automatically before another opens (round 13), unless it cannot be
  if (!force && S.img && H.isDirty() && !autoCovers() && !confirm('The X-ray on screen has changes not saved in a project. Open the other case anyway?')) return false;
  const got = await call('GET', `/projects/${id}`);
  const meta = got.meta, project = got.project;
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

// ---------- who is signed in (13 Oct 2026): GET /api/me -> {uid, em, ca, rg} ----------
// Share and Hide are offered only where the server would allow them (contract 2 and 4: the case's owner, or someone who
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
const SLOTN = { cupR: 'Cup R', cupL: 'Cup L', stemR: 'Stem R', stemL: 'Stem L', headR: 'Head R', headL: 'Head L', femur: 'Femur', tibia: 'Tibia', insert: 'Insert' };
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
  const st = { q: '', next: null, seq: 0 };
  const say = (text, cls = '') => { msg.className = 'cmsg' + (cls ? ' ' + cls : ''); msg.textContent = text; };
  async function load(reset) {
    const seq = ++st.seq;
    if (reset) { st.next = null; }
    say(reset ? 'Loading…' : 'Loading more…'); more.disabled = true;
    try {
      const body = { limit: 20 }; if (st.q) body.q = st.q; if (!reset && st.next) body.before = st.next;
      const r = await call('POST', '/projects/list', { json: body });   // the search goes in the body, never the URL (contract 4)
      const viewer = await me();
      if (seq !== st.seq) return;
      if (reset) ul.textContent = '';
      for (const m of Array.isArray(r.items) ? r.items : []) ul.appendChild(row(m, viewer));
      st.next = typeof r.next === 'string' ? r.next : null;
      more.hidden = !st.next; more.disabled = false;
      say(ul.children.length ? '' : st.q ? `No saved case matches "${st.q}".` : 'No templated patients yet. A project saved here appears in this list.');
    } catch (e) {
      if (seq !== st.seq) return;
      more.hidden = true;
      if (e instanceof ApiError && e.status === 401) { say('', 'bad'); msg.appendChild(signinLink()); msg.append(' (your sign-in has ended; it opens in a new tab, then come back and press Try again) '); msg.appendChild(button('Try again', () => load(true))); return; }
      say(`Could not load the patient list: ${why(e)}. `, 'bad'); msg.appendChild(button('Try again', () => load(true)));
    }
  }
  function row(m, viewer = ME) {
    const li = node('li', 'case'); li.dataset.id = String(m.id || '');
    const pt = isObj(m.patient) ? m.patient : {}, who = pt.name || (pt.recordNo ? `Record ${pt.recordNo}` : 'Name left out');
    const side = `${m.side === 'L' ? 'Left' : 'Right'} ${m.procedure === 'knee' ? 'knee' : 'hip'}`;
    const open = node('button', 'copen'); open.type = 'button';
    const top = node('span', 'ctop'); top.append(node('b', 'cname', who), node('span', 'cside', side), node('span', 'cdate', fmtWhen(m.updatedAt)));
    const imp = node('span', 'cimp', implantsText(m.implants) || 'no implants placed');
    const by = node('span', 'cby', [pt.name && pt.recordNo ? `Record ${pt.recordNo}` : '', m.updatedBy && m.updatedBy.em ? `saved by ${m.updatedBy.em}` : '', Array.isArray(m.collaborators) && m.collaborators.length ? `shared with ${m.collaborators.length}` : ''].filter(Boolean).join(' · '));
    open.append(top, imp, by); open.setAttribute('aria-label', `Open ${who}, ${side}, saved ${fmtWhen(m.updatedAt)}`);
    open.onclick = async () => {
      say('Opening the case…'); for (const b of sec.querySelectorAll('button')) b.disabled = true;
      try { const done = await openCase(m.id); if (done === false) say(''); }
      catch (e) { say(`Could not open that case: ${e instanceof ApiError ? why(e) : e.message || e}.`, 'bad'); if (e instanceof ApiError && e.status === 401) msg.appendChild(signinLink()); }
      finally { for (const b of sec.querySelectorAll('button')) b.disabled = false; }
    };
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
          ed.hidden = true; li.replaceWith(row(m, viewer));
          say(m.collaborators.length ? `Shared with ${m.collaborators.join(', ')}.` : 'Not shared with anyone now.');
        } catch (e) { say(`Not shared: ${why(e)}.`, 'bad'); }
      }, 'primary');
      const r2 = node('div', 'row'); r2.append(go, button('Cancel', () => { ed.hidden = true; }));
      ed.append(lab, note, r2); inp.focus();
    }, '', 'Share this case with colleagues');
    share.setAttribute('aria-label', `Share ${who}`);
    const hide = button('Hide', async () => {
      if (!confirm(`Hide ${who} (${side}) from the templated-patients list? It is not deleted: the case stays on the server.`)) return;
      try {
        await call('POST', `/projects/${m.id}/hide`, { json: {} });
        if (H.S.caseRef && H.S.caseRef.id === m.id) caseOnScreen(null);   // saving the X-ray on screen again (Save project) starts a new case; it is not saved automatically any more
        li.remove(); say('Hidden from the list (kept on the server).');
        if (!ul.children.length && !st.next) say('Hidden from the list (kept on the server). No other templated patients here.');
      } catch (e) { say(`Not hidden: ${why(e)}.`, 'bad'); }
    }, '', 'Take this case off the list (it is kept on the server)');
    hide.setAttribute('aria-label', `Hide ${who}`);
    if (mayManage(m, viewer)) { act.append(share, hide); li.append(open, act, ed); }   // only where the server would allow them
    else li.append(open);
    return li;
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
let FILM = null;             // the film on screen: { img, orig, ref, how, eligible, wantCase, created, placeholder, conflict, gone, pend, snap, status, ... }
const AWAY = new Set();      // films no longer on screen with a save still to make (their snapshot) or to retry
let NEXT_PLACEHOLDER = null; // openCase → filmOpened: the list's placeholder name of the case being opened
let QUEUE = Promise.resolve();
// every write to the server, automatic or Save project, one after another: one upload at a time, and two saves of the same
// case never race on the same version
let JOBS = 0;   // writes queued or running
function serial(fn) { JOBS++; const p = QUEUE.then(fn).finally(() => { JOBS--; }); QUEUE = p.then(() => {}, () => {}); return p; }
const live = F => !!F && F === FILM && H.S.img === F.img;
const refOf = F => (live(F) ? H.S.caseRef : F.ref);
function setRef(F, r) {
  F.ref = r || null; if (live(F)) H.S.caseRef = r || null;
  if (F.orig && F.orig.sha256) remember(F.orig.sha256, r);
}
function caseOnScreen(r) { if (FILM && live(FILM)) { setRef(FILM, r); if (!r) { FILM.status = null; render(); } } else H.S.caseRef = r; }
// may the film on screen be left without asking? (its changes are saved automatically before the other opens)
const autoCovers = () => !!FILM && live(FILM) && FILM.eligible && !FILM.conflict && !FILM.gone && !!(refOf(FILM) || (FILM.wantCase && !FILM.created));
function metaFor(F) {
  const meta = H.metaNow();
  if (F && F.placeholder && !H.S.set.share && !meta.patient.name && !meta.patient.recordNo) meta.patient = { name: F.placeholder };
  return meta;
}
const hhmm = d => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
const placeholderNow = () => { const d = new Date(); return `${PLACEHOLDER}${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}/${d.getFullYear()} ${hhmm(d)}`; };

// ---------- which case a film is (this tab, and this browser when plans are kept here) ----------
// Only ids, versions and image hashes: no names. The tab's list ends with the tab; the browser's only when plans are kept
// in this browser (Auto plan settings), and "Forget plans on this computer" removes it with the plans (aoc-tpl:).
const TAB_KEY = 'aoc-tpl-cases', LOCAL_KEY = sha => 'aoc-tpl:case:' + sha;
function remember(sha, r) {
  if (!SHA_RE.test(String(sha))) return;
  const v = r && ID_RE.test(String(r.id)) ? { id: r.id, rev: r.rev, sha256: r.sha256 || null } : null;
  try {
    const m = JSON.parse(sessionStorage.getItem(TAB_KEY) || '{}') || {};
    if (v) { delete m[sha]; m[sha] = v; } else delete m[sha];
    const ks = Object.keys(m); for (const k of ks.slice(0, Math.max(0, ks.length - 50))) delete m[k];
    sessionStorage.setItem(TAB_KEY, JSON.stringify(m));
  } catch (e) { /* storage off */ }
  try { if (v && H.S.set.keepLocal) localStorage.setItem(LOCAL_KEY(sha), JSON.stringify(v)); else if (!v) localStorage.removeItem(LOCAL_KEY(sha)); } catch (e) { /* storage off */ }
}
export function knownCase(sha) {
  if (!H || !H.STANDALONE || !SHA_RE.test(String(sha))) return null;
  let r = null;
  try { r = (JSON.parse(sessionStorage.getItem(TAB_KEY) || '{}') || {})[sha] || null; } catch (e) { r = null; }
  if (!r && H.S.set.keepLocal) { try { r = JSON.parse(localStorage.getItem(LOCAL_KEY(sha)) || 'null'); } catch (e) { r = null; } }
  return isObj(r) && ID_RE.test(String(r.id)) ? r : null;
}
// A film opened again whose case is known: its case is opened from the list (the plan saved there, which the automatic
// saves keep current) instead of a new case being made. true: done. false: open it as a new film (the case is gone or
// hidden). A case that cannot be reached now (no connection) opens the film without making a case, so none is doubled.
export async function openKnown(ref, bytes, sha) {
  try { await openCase(ref.id, { force: true, bytes, how: 'known' }); return true; }
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
const validRec = r => isObj(r) && isObj(r.state) && SHA_RE.test(String(r.sha)) && Number.isFinite(r.v);
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
  const sha = F.sha, origin = F.uid;
  if (!SHA_RE.test(String(sha)) || !isObj(state)) return false;
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
function dropUnsent(F, takenAt, uid = F && F.uid) { if (F && F.sha && uid) dropKey(rkey(F.sha, uid), takenAt); }
const stateText = st => { const c = { ...st }; delete c.saved; delete c.pendingSend; return JSON.stringify(c); };
async function offerUnsent(F) {
  const sha = F.sha, found = sha && unsentOf(sha);
  if (!found || !live(F)) return;
  const rec = found.rec;
  let now = null; try { now = H.projectNow().state; } catch (e) { now = null; }
  if (now && stateText(now) === stateText(rec.state)) { dropKey(found.key, rec.at); return; }   // what was kept is what is here
  const when = hhmm(new Date(rec.at));
  const pick = await choose(`This X-ray has changes from ${when} that may not have reached the patient list (the page was closed, reloaded or had to let them go).`,
    [['Put them back', 'back', 'primary'], ['Discard them', 'drop', '']], 'Put them back: they replace the plan on the screen and are saved to the patient list. Discard: they are forgotten.');
  if (!live(F)) return;
  if (pick === 'back') {
    const pr = stateProblems(rec.state, H.S.img.cols, H.S.img.rows);
    if (pr.length) { dropKey(found.key, rec.at); return H.prompt(`The kept changes could not be put back: ${pr.slice(0, 2).join('; ')}.`); }
    H.applyState(rec.state); F.srvKey = null; F.first = 0;
    F.uid = rec.origin || F.uid;   // this opening carries those changes on: its answered save removes their record
    H.prompt(`The changes from ${when} are back on the screen; they are being saved to the patient list.`);
    flushSoon(F);
  } else if (pick === 'drop') dropKey(found.key, rec.at);
}

// ---------- the film's life on the page (app.js setImage) ----------
// F.srvKey: the revision (app.js stateKey) the patient list has for this film: what the server answered for, never what
// was only downloaded as a file (finding 1). srvDirty: the plan on screen is not that one.
const srvDirty = F => (live(F) ? H.revision() !== F.srvKey : !!F.snap);
const needsCase = F => !refOf(F) && F.wantCase && !F.created;
// a film is put on screen: how = 'file' (an image file), 'aotp' (a project file), 'list' or 'known' (a case from the list)
export function filmOpened(how) {
  if (!H || !H.STANDALONE) return;
  const S = H.S, ref = S.caseRef;
  const F = { uid: ridOf().slice(0, 12), sha: S.orig && S.orig.sha256, openedAt: Date.now(), img: S.img, orig: S.orig, ref, how, eligible: !!(S.orig && S.orig.sha256), created: !!ref, conflict: false, gone: false, lost: [], snap: null,
    srvKey: ref ? H.revision() : null, status: null, timer: 0, first: 0, retryN: 0, retryT: 0, nextTry: 0, waitUp: false, queued: false, step: null, placeholder: NEXT_PLACEHOLDER };
  const blocked = NO_CASE && F.eligible && NO_CASE.sha === S.orig.sha256 ? NO_CASE : null; NO_CASE = null;
  F.wantCase = F.eligible && !ref && !blocked && (how === 'file' || how === 'aotp');
  if (F.wantCase && !H.patientKnown() && !S.set.share) F.placeholder = placeholderNow();
  try { F.step = H.stepNow(); } catch (e) { F.step = null; }
  FILM = F;
  if (ref) remember(S.orig && S.orig.sha256, ref);
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
  const ref = H.S.caseRef, dirty = !!ref && H.revision() !== F.srvKey, need = !ref && F.wantCase && !F.created;
  F.label = labelOf(F);
  let state = null; const stateNow = () => { if (!state) state = H.projectNow().state; return state; };
  F.ref = ref; F.img = null;
  if (!F.eligible) { FILM = null; return; }
  if (F.conflict || F.gone) {   // never saved automatically: kept for the next opening, or on this page if it cannot be
    let ok = !dirty || !!F.replaced; try { if (!ok) ok = keepUnsent(F, stateNow()); } catch (e) { ok = false; }
    if (!ok) {
      try { F.snap = { project: H.projectNow(), meta: metaFor(F), rev: H.revision(), at: Date.now(), uid: F.uid, userDirty: true }; AWAY.add(F); H.prompt(`The X-ray open before (${F.label}) has changes not in the patient list that this browser could not keep (${KEEP_WHY}): they stay on this page; see the panel.`); }
      catch (e) { /* nothing more can be done */ }
    }
    FILM = null; return;
  }
  if (!need && !dirty) { FILM = null; return; }
  try { const project = H.projectNow(); aotpCheck(project); F.snap = { project, meta: metaFor(F), rev: H.revision(), at: Date.now(), uid: F.uid, userDirty: H.isDirty() || !!F.ref }; }
  catch (e) { F.snap = null; FILM = null; return; }
  FILM = null;
  if (F.ref && F.orig && F.orig.sha256 === F.ref.sha256) F.orig = { sha256: F.orig.sha256, mime: F.orig.mime, name: F.orig.name, bytes: null };   // the server has the image
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
  const side = m && m.side === 'L' ? 'Left' : 'Right', joint = m && m.procedure === 'knee' ? 'knee' : 'hip';
  return `${side} ${joint} X-ray opened ${hhmm(new Date(F.openedAt || Date.now()))}, image ${String((F.orig && F.orig.sha256) || '').slice(0, 6)}`;
}
// a film no longer on screen is let go: its unsaved plan (if any) goes to the kept-changes store when keep is true
// → false (and nothing let go) when keep is asked and the changes could not be written: the film stays on the page
function letGo(F, keep) {
  if (keep && F.snap && F.orig && !keepUnsent(F, F.snap.project.state)) return false;
  AWAY.delete(F); clearTimeout(F.retryT); F.retryT = 0; F.nextTry = 0;
  F.snap = null; F.orig = null; F.gone = true;   // nothing more is sent for it
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
  let project, meta, rev, base, at, uid;
  if (live(F)) {
    if (dragging()) { F.waitUp = true; return; }
    if (ref ? !srvDirty(F) : !needsCase(F)) { if (F.status && F.status.kind === 'offline') status(F, ref ? 'saved' : null); return; }
    if (!ref) {   // a new case: is there one already for this image (another film of it, a save whose answer was lost)?
      status(F, 'saving');
      const sha = F.orig.sha256;
      let got = null;
      try { got = await pendingCase(sha, F); } catch (e) { failed(F, e); return; }
      if (got && got.own && live(F)) { F.created = true; setRef(F, got.ref); }   // its own earlier save made it: saved on top below
      else if (got && live(F)) {
        const k = got.ref; remember(sha, k);
        if (!H.isDirty()) { F.wantCase = false; await openKnown(k, F.orig.bytes, sha); return; }   // nothing done here yet: its case, as saved
        F.created = true; F.wantCase = false; setRef(F, k); F.conflict = true; status(F, 'conflict'); return;   // both changed: never one over the other
      }
      if (!live(F)) { enqueue(F); return; }
      try { await sendImage({ bytes: F.orig.bytes, sha256: sha, mime: F.orig.mime }, () => {}); }   // the image first, then the plan as it is once it is there
      catch (e) { if (e instanceof ApiError && e.status === 422) UPS.delete(sha); failed(F, e); return; }
      for (let i = 0; i < 100 && live(F) && (H.S.auto.busy || H.S.filling); i++) await sleep(100);
      if (!live(F)) { enqueue(F); return; }   // another film opened meanwhile: its snapshot is saved in turn
      ref = refOf(F);
    }
    try { project = H.projectNow(); meta = metaFor(F); aotpCheck(project); rev = H.revision(); base = H.base(); at = Date.now(); uid = F.uid; }
    catch (e) { status(F, 'fail', `Not saved: ${e.message || e}`, 'The plan cannot be saved as it is: the page would refuse to open it. Change or undo the last step.'); return; }
  } else {
    if (!AWAY.has(F) || !F.snap) { AWAY.delete(F); return; }
    if (!ref && F.created) { letGo(F, false); H.panel(); return; }   // its case was hidden meanwhile: nothing is made again
    ({ project, meta, rev, at, uid } = F.snap);
  }
  status(F, 'saving');
  const ctx = { F, auto: true, img: F.img, orig: F.orig, ref, live: () => live(F) };
  try {
    const r = await saveToServer(ctx, project, meta);
    if (r.ok) {
      F.created = true; F.retryN = 0; F.nextTry = 0; clearTimeout(F.retryT); F.retryT = 0;
      const cur = refOf(F);   // the list may have raised the version meanwhile (Share): keep the higher
      setRef(F, cur && cur.id === ctx.ref.id && cur.rev > ctx.ref.rev ? { ...ctx.ref, rev: cur.rev } : ctx.ref);
      acked(F, rev, at, uid);
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
function acked(F, rev, at = Date.now(), uid = F.uid) {
  if (live(F)) F.srvKey = rev;
  if (!live(F) || !srvDirty(F)) F.storeFail = null;
  if (F.snap && F.snap.rev === rev) { F.snap = null; if (AWAY.delete(F)) H.panel(); }
  dropUnsent(F, at, uid);
}
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
  if (F && live(F) && F.eligible && (needsCase(F) || (refOf(F) && srvDirty(F)))) {   // also a film changed elsewhere or gone
    let ok = false; try { ok = keepUnsent(F, H.projectNow().state); } catch (e) { ok = false; }
    F.storeFail = ok ? null : { text: `Not saved: ${KEEP_WHY === 'storage full' ? 'storage full' : 'too many kept'}`, detail: `This browser could not keep the unsaved changes for later (${KEEP_WHY}). Keep this page open until the patient list has them, or use Save project to save the file.` };
    if (!ok) status(F, 'fail', F.storeFail.text, F.storeFail.detail);
    if (!F.conflict && !F.gone && refOf(F) && !H.S.filling) { clearTimeout(F.timer); F.timer = 0; F.first = 0; enqueue(F); }
  }
  for (const A of AWAY) if (A.snap && A.orig && !keepUnsent(A, A.snap.project.state)) A.keepFail = KEEP_WHY;
}
function shown() { HIDING = false; }

// ---------- films waiting for the patient list, in the Measurements and save segment (finding 6) ----------
export function awayEl() {
  const d = node('div', 'projaway');
  const kept = H && H.STANDALONE ? Object.entries(unsentAll()).filter(([k, r]) => ![...AWAY].some(F => rkey(F.sha, F.uid) === k) && !(FILM && rkey(FILM.sha, FILM.uid) === k)) : [];
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
      letGo(F, false); dropKey(rkey(F.sha, F.uid)); H.panel();
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
  b.title = `Patient list: ${st.text}${st.detail ? `. ${st.detail}` : ''}${tap ? ' (tap for details)' : unnamed ? ` as "${FILM.placeholder}" (tap to add the patient's name and record number)` : ''}`;
  const say = `Patient list: ${st.text}`;
  if (sr.textContent !== say) sr.textContent = say;
}
async function details() {
  const F = FILM; if (!F || !live(F) || !F.status) return;
  const st = F.status;
  if (st.kind === 'conflict' || (st.kind === 'fail' && F.gone)) return saveProject();   // its choices: Reload the newer version, Save as my copy
  if (st.kind === 'saved' && F.placeholder && !H.S.set.share && !H.patientKnown()) {   // a case made before its patient was named
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
  });
  if (pick === 'retry' && live(F)) { F.retryN = 0; if (!refOf(F) && !needsCase(F)) return saveProject(); enqueue(F, true); }
}
function flushSoon(F) { if (live(F)) setTimeout(() => tick(F), AUTO.step); }

// ---------- wiring ----------
// host: app.js's side. Makes the header's Save project button (and, on t.addisortho.com, its status and the list in the
// start card).
export function initProjects(host) {
  H = host;
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
export const _test = { call, sendImage, keepaliveOk, get UPS() { return UPS; } };
