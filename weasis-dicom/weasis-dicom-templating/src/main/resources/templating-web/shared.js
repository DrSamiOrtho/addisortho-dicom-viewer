// Shared facts for the full planner (app.js) and Quick (quick.js): ONE copy, 4 Nov 2026 (DESIGN-2026-11-04-quick-sync.md,
// SYNC.md). Pure: no DOM, no page state, no storage. Anything that is the same fact on both pages lives here and nowhere else;
// tests/sync_test.mjs fails if either page defines one of these names again (SHARED_NAMES below).
//
// To change a shared fact: edit it HERE, bump SHARED_REV, run `node tests/sync_test.mjs`, rebuild (stage_quick_sync.py or
// build_standalone.sh): both pages pick it up with a new ?v= stamp, so a phone never mixes an old page with a new shared.js.

export const SHARED_REV = '2026-11-05.2';

// ---- sides, scanner classes, makers' default families --------------------------------------------------------------------
export const SIDE = { R: 'right', L: 'left' };
export const other = s => (s === 'R' ? 'L' : 'R');
export const CT_SOP = ['1.2.840.10008.5.1.4.1.1.2', '1.2.840.10008.5.1.4.1.1.2.1'];   // CT Image Storage, Enhanced CT
export const MAKER_DEFAULT = { cup: 'chunli-cup', stem: 'chunli-be2-133s', 'tka-femur': 'chunli-tka-femur', 'tka-tibia': 'chunli-tka-tibia' };

// ---- knee types (the full planner's r43 table). Quick shows the primary, CCK and hinge sets in its knee card; the tumour pairs
// (PTR, DFR) are Quick's separate "Tumour" case, so it filters this table with `tumour` instead of keeping a shorter copy.
export const KNEE_TYPES = [
  { id: 'primary', label: 'Primary' },
  { id: 'cck', label: 'CCK (constrained condylar)', fem: 'chunli-cck-femur', tib: 'chunli-cck-tibia' },
  { id: 'ldk', label: 'Hinge (LDK XR)', fem: 'ldk-xr-femur', tib: 'ldk-xr-tibia' },
  { id: 'ptr', label: 'Proximal tibial replacement (tumour)', fem: 'chunli-ptr-femur', tib: 'chunli-ptr-tibia', tumour: true },
  { id: 'dfr', label: 'Distal femoral replacement (tumour)', fem: 'chunli-dfr-femur', tib: 'chunli-dfr-tibia', tumour: true }];
export const ktPairOf = types => new Set(types.flatMap(k => (k.fem ? [k.fem, k.tib] : [])));   // the family ids that belong to a non-primary knee type
export const KT_PAIR = ktPairOf(KNEE_TYPES);

// ---- diaphyseal (segmental) tumour types: Chunli, Segmental Bone Defect Prosthesis, slides 18-19 ---------------------------
export const DIA_TYPES = {
  III: { defect: [40, 165], fix: 'split body joined by screws, no cross-lock', A: { model: 'SZ-A(III)', L1: [20, 200], phi: [9, 40] }, B: { model: 'SZ-B(I)', L1: [10, 130], phi: [9, 40] } },
  VI: { defect: [50, 310], fix: 'tapered press-fit intercalary segments (JGD(I)), stem B cross-locked (LD(I) screw)', A: { model: 'SZ-A(IV)', L1: [20, 200], phi: [9, 39] }, B: { model: 'SZ-B(VI)', L1: [10, 130], phi: null } },
};

// ---- tumour lengths, to 0.01 mm with no trailing zeros (91.75, 134); the same words on both pages ----------------------------
export const fmm = x => (Number.isFinite(x) ? String(Math.round(x * 100) / 100) : '–');
export const tumBodyLen = z => (Number.isFinite(z.bodyLengthMm) ? z.bodyLengthMm : z.reconstructionMm - (z.extensionMm || 0));
export const tumParts = (z, c) => {
  const g = Number.isFinite(z.junctionGapMm) ? ` + gap ${z.junctionGapMm}` : '';
  return `${fmm(tumBodyLen(z))}${g}${z.extensionMm ? ` + extension ${z.extensionMm}${g}` : ''} + collar ${c === null || c === undefined ? 'c' : c}`;
};
// the stem collars a stem family offers, ascending; and the collar of one stem size (its own, else the plan's typed one, else null)
export const collarList = stemFam => (stemFam && stemFam.sizes ? [...new Set(stemFam.sizes.map(q => q.collarMm).filter(Number.isFinite))].sort((a, b) => a - b) : []);
export const collarMm = (stemSize, fallback) => (stemSize && Number.isFinite(stemSize.collarMm) ? stemSize.collarMm : Number.isFinite(fallback) ? fallback : null);

// ---- grey levels: the full planner's window, brightness and contrast, exactly (app.js 11 Oct 2026, Fable) -------------------
// A window is {c, w}: centre and width in the film's own grey units (modality values for a DICOM, 0-255 for a picture).
export function percentileWindow(data) {   // 1st to 99th percentile of the pixel values
  const step = Math.max(1, Math.floor(data.length / 200000)), smp = [];
  for (let i = 0; i < data.length; i += step) smp.push(data[i]);
  smp.sort((a, b) => a - b);
  const lo = smp[Math.floor(smp.length * 0.01)], hi = smp[Math.floor(smp.length * 0.99)];
  return { c: (lo + hi) / 2, w: Math.max(1, hi - lo) };
}
// Slider scales: brightness across the film's 1st-99th percentile range (half a range either side), contrast from 4 times that
// range (flat) to a tenth of it (hard), the auto window in the middle (50, 50). ref = percentileWindow(data).
export function wlFromSliders(ref, b, c) {
  const w = c <= 50 ? ref.w * Math.pow(4, (50 - c) / 50) : ref.w * Math.pow(10, -(c - 50) / 50);
  return { c: ref.c + ref.w * (0.5 - b / 100) * 2, w };
}
export function slidersFromWl(ref, wl) {
  const lr = Math.log10(wl.w / ref.w), cl = x => Math.round(Math.max(0, Math.min(100, x)));
  return { b: cl(100 * (0.5 - (wl.c - ref.c) / (2 * ref.w))), c: cl(lr >= 0 ? 50 - 50 * lr / Math.log10(4) : 50 - 50 * lr) };
}
// right-drag (or Shift-drag) on the film: up/down moves the level, left/right the width. wl0: the window when the drag began.
export const wlDrag = (wl0, dx, dy) => ({ c: wl0.c - dy * wl0.w / 400, w: Math.max(1, wl0.w * Math.exp(dx / 200)) });
// grey data -> RGBA pixels (px: Uint8ClampedArray of 4*n), through the window; invert flips the grey
export function fillGray(px, data, n, wl, invert) {
  const lo = wl.c - wl.w / 2, k = 255 / wl.w;
  for (let i = 0; i < n; i++) { let v = (data[i] - lo) * k; v = v < 0 ? 0 : v > 255 ? 255 : v; if (invert) v = 255 - v; px[4 * i] = px[4 * i + 1] = px[4 * i + 2] = v; px[4 * i + 3] = 255; }
}

// ---- the one-line image command (Quick): "b60 c70 i", "w 40 400", "z2 p10,-5", "r" -----------------------------------------
// One letter per option; a whole word works too. A number after a letter is absolute; with + or - it is relative.
export const IMAGE_HELP = [
  ['b60', 'brightness 60 on the 0-100 scale; b+10 / b-10 brighter / darker'],
  ['c70', 'contrast 70 on the 0-100 scale; c+10 / c-10'],
  ['i', 'invert (i1 on, i0 off)'],
  ['a', 'auto contrast: stretch the 1st to 99th percentile (n does the same)'],
  ['o', 'as in the file (the stored window; DICOM window centre and width)'],
  ['w 40 400', 'window centre and width in the film’s own grey units'],
  ['z2', 'zoom 2 times the fitted film; z+ / z- step in or out'],
  ['p10,-5', 'pan by percent of the screen: right 10, up 5'],
  ['f', 'fit the film to the screen'],
  ['r', 'reset: levels as the film opened, not inverted, film fitted'],
];
const WORD = { n: 'a', brightness: 'b', bright: 'b', contrast: 'c', invert: 'i', auto: 'a', original: 'o', file: 'o', window: 'w', level: 'w', zoom: 'z', pan: 'p', fit: 'f', reset: 'r' };
const NUM = '[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)';
export function parseImageCommand(text) {
  const s = String(text || '').trim().toLowerCase(), bad = w => ({ ops: [], error: `Not understood: "${w}". Try b60 c70 i (? lists them).` });
  if (!s) return { ops: [], error: 'Type a command, for example b60 c70 i (? lists them).' };
  const re = new RegExp(`\\s*([a-z]+)\\s*(${NUM}(?:\\s*,\\s*${NUM})?(?:\\s+${NUM})?|[+-])?`, 'y');
  const ops = []; let at = 0;
  while (at < s.length) {
    re.lastIndex = at; const m = re.exec(s);
    if (!m) return bad(s.slice(at).trim().slice(0, 20));
    at = re.lastIndex;
    const word = m[1], arg = m[2];
    let keys = WORD[word] ? [WORD[word]] : word.length === 1 ? [word] : arg ? [] : [...word];   // "ci" is c then i; a number needs a single letter or a word
    if (!keys.length || keys.some(k => !'bciaowzpfr'.includes(k))) return bad(word);
    for (const key of keys) {
      const nums = arg && arg !== '+' && arg !== '-' ? arg.split(/[\s,]+/).filter(Boolean).map(Number) : [];
      const rel = arg ? /^[+-]/.test(arg) : false, step = arg === '+' ? 1 : arg === '-' ? -1 : 0, op = { op: key };
      if (key === 'b' || key === 'c') { if (!nums.length) return { ops: [], error: `${key === 'b' ? 'Brightness' : 'Contrast'} needs a number, like ${key}60 or ${key}+10.` }; op.value = nums[0]; op.rel = rel; }
      else if (key === 'i') { if (nums.length) op.value = nums[0] ? 1 : 0; }
      else if (key === 'w') { if (nums.length !== 2 || !(nums[1] > 0)) return { ops: [], error: 'Window needs a centre and a positive width, like w 40 400.' }; op.c = nums[0]; op.w = nums[1]; }
      else if (key === 'z') { if (step) op.step = step; else if (nums.length === 1 && nums[0] > 0 && !rel) op.value = nums[0]; else return { ops: [], error: 'Zoom needs a number above 0 (z2) or + / -.' }; }
      else if (key === 'p') { if (nums.length !== 2) return { ops: [], error: 'Pan needs two numbers, like p10,-5.' }; op.x = nums[0]; op.y = nums[1]; }
      else if (nums.length || step) return { ops: [], error: `"${key}" takes no number.` };
      ops.push(op);
    }
  }
  return { ops, error: null };
}

// ---- Quick's saved plan file (4 Nov 2026) ---------------------------------------------------------------------------------
// The file holds implants, measurements, scale and view. It never holds pixels, patient details or the film's file name.
export const PLAN_FORMAT = 'aoc-quick-plan', PLAN_VERSION = 1, PLAN_MAX_BYTES = 2 * 1024 * 1024;
const FILM_VIEWS = ['hip', 'ap', 'lat', 'sky', 'long', 'tum', 'pending'];
export const planFileName = (joint, side, when = new Date()) => {
  const p = n => String(n).padStart(2, '0');
  return `AO-quick-plan_${String(joint).replace(/[^a-z]/gi, '').slice(0, 10) || 'case'}_${side === 'L' ? 'L' : 'R'}_${when.getFullYear()}-${p(when.getMonth() + 1)}-${p(when.getDate())}.json`;
};
const finite = v => typeof v === 'number' && Number.isFinite(v);
export function validatePlanFile(o) {   // null when usable, else the reason in plain words
  if (!o || typeof o !== 'object' || Array.isArray(o)) return 'This is not a Quick plan file.';
  if (o.format !== PLAN_FORMAT) return 'This is not a Quick plan file.';
  if (o.v !== PLAN_VERSION) return `This plan file is version ${String(o.v).slice(0, 8)}; this page reads version ${PLAN_VERSION}.`;
  if (!['hip', 'knee', 'tumour', 'pfna'].includes(o.joint)) return 'The plan file names no known joint.';
  if (!Array.isArray(o.films) || !o.films.length || o.films.length > 4) return 'The plan file has no films, or too many.';
  for (const f of o.films) {
    if (!f || typeof f !== 'object' || !FILM_VIEWS.includes(f.fv)) return 'A film in the plan file has no known view.';
    if (!(Number.isInteger(f.cols) && f.cols > 0 && Number.isInteger(f.rows) && f.rows > 0)) return 'A film in the plan file has no size.';
    if (!Array.isArray(f.items) || f.items.length > 40) return 'A film in the plan file has too many implants.';
    for (const it of f.items) if (!it || typeof it.fam !== 'string' || !finite(it.x) || !finite(it.y) || !finite(it.angle) || (it.size !== undefined && typeof it.size !== 'string' && !finite(it.size))) return 'An implant in the plan file is malformed.';
    if (f.cal && !(finite(f.cal.mmPerPx) && f.cal.mmPerPx > 0 && f.cal.mmPerPx < 5)) return 'The scale in the plan file is not usable.';
    if (f.cal && f.cal.ball && !(finite(f.cal.ball.x) && finite(f.cal.ball.y) && finite(f.cal.ball.r) && f.cal.ball.r > 0)) return 'The scale in the plan file is not usable.';
    if (f.fid !== undefined && !(typeof f.fid === 'string' && /^[0-9a-f]{16}$/.test(f.fid))) return 'A film in the plan file has a broken identity.';
    if (f.ballD !== undefined && f.ballD !== null && !(finite(f.ballD) && f.ballD >= 5 && f.ballD <= 60)) return 'The ball size in the plan file is not usable.';
    // Fable (5 Nov 2026, low): every point list bounded and every point a pair of finite numbers
    const pt = q => q && finite(q.x) && finite(q.y);
    const pts = (a, n) => a === undefined || (Array.isArray(a) && a.length <= n && a.every(pt));
    if (!pts(f.lld, 8) || !pts(f.vg, 8) || !(f.rul === undefined || (Array.isArray(f.rul) && f.rul.length <= 40 && f.rul.every(m => m && pt(m.a) && pt(m.b))))) return 'A measurement in the plan file is malformed.';
    if (f.hd !== undefined && !(f.hd && typeof f.hd === 'object' && pts(f.hd.R || [], 8) && pts(f.hd.L || [], 8))) return 'A measurement in the plan file is malformed.';
    for (const it of f.items) if (it.slide !== undefined && !(finite(it.slide) && Math.abs(it.slide) < 200)) return 'An implant in the plan file is malformed.';
    for (const k of ['tp', 'dia']) if (f[k] != null && !(typeof f[k] === 'object' && !Array.isArray(f[k]) && JSON.stringify(f[k]).length <= 2000)) return 'The tumour plan in the plan file is malformed.';
  }
  return null;
}
// a film's identity for plan files (Fable, 5 Nov 2026: a plan opened on any film of the same size and took over its scale). FNV-1a
// over the size and up to 4096 grey values on a grid: the same file gives the same 16 hex digits; it cannot give back the picture
// and names no one, so it may sit in the plan file.
export function filmId(img) {
  const d = img && img.data, cols = img && img.cols, rows = img && img.rows;
  if (!d || !(cols > 0 && rows > 0)) return '';
  let a = 0x811c9dc5, b = 0x01000193 ^ 0x5bd1e995;
  const mix = v => { a = Math.imul(a ^ (v & 0xffff), 16777619) >>> 0; b = Math.imul(b ^ ((v >>> 16) & 0xffff), 2246822519) >>> 0; b = Math.imul(b ^ (v & 0xffff), 2246822519) >>> 0; };
  mix(cols); mix(rows);
  const n = 64, total = Math.min(d.length, cols * rows);
  for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) {
    const x = Math.floor((i + 0.5) * cols / n), y = Math.floor((j + 0.5) * rows / n), k = y * cols + x;
    mix(k < total ? Math.round(d[k]) | 0 : 0);
  }
  return a.toString(16).padStart(8, '0') + b.toString(16).padStart(8, '0');
}

// ---- joining two to four exposures into one long-leg image (DESIGN-2026-11-04-stitching.md, after Fable's review) -------------
// The words, limits and checks both pages must agree on. The mathematics is in stitch.js (loaded only when a film set is joined).
// A stitched film is never "green": its status is amber at best, red when a join is weak, placed by hand or the films disagree.
export const STITCH = Object.freeze({
  minSeg: 2, maxSeg: 4,
  outMm: 0.25,              // the joined film's pixel (mm); half size doubles it. reduce.js: an angle over a metre moves well under 0.1 deg
  keepPx: 4e6,              // each film is kept at about this many pixels once decoded (the full decode is released at once)
  coarseMm: 4, fineMm: 1,   // matching levels
  minOverlapMm: 20, maxOverlapFrac: 0.6, maxShiftFrac: 0.15,
  nccOk: 0.45, nccWeak: 0.30, marginMin: 0.05, marginMm: 6, ridgeMm: 3, flipMargin: 0.05, minTexture: 4,   // texture: 95th percentile of local spread / the film's noise
  maxTurnDeg: 2, turnGain: 0.03, carryScale: 0.04, handScale: 0.03, handTurnDeg: 3,
  blendMm: 8, bandMm: 14, tickMm: 8,
  boneMm: 400,              // the bone length a size disagreement is turned into millimetres over
  sizeAmber: 0.01, sizeRed: 0.03, lenAmberMm: 2, lenRedMm: 5,
  budgetPhone: 250e6, budgetDesk: 900e6, recipeMax: 8000,
});
export const STITCH_TAG = 'AOC-STITCH v1 ';   // Image Comments of a joined film start with this, then the recipe as JSON
const r1 = x => Math.round(x * 10) / 10, r2 = x => Math.round(x * 100) / 100;
const JOIN_HOW = { matched: 'matched on the overlap', weak: 'weak match, accepted by eye', hand: 'placed by hand' };
const SCALE_HOW = { ball: 'its own ball', line: 'a known length on it', carried: 'carried through the join' };
// {level: 'amber' | 'red', reasons: [...], lenErrMm}: amber at best (a stitched film is never trusted like one exposure)
export function stitchStatus(r) {
  const reasons = []; let red = false, len = 0;
  (r.joins || []).forEach((j, i) => {
    const n = i + 1;
    if (j.st === 'weak' || j.st === 'hand') { red = true; reasons.push(`join ${n} ${JOIN_HOW[j.st]}`); }
    if (Math.abs(j.rot || 0) > 0.5) reasons.push(`join ${n} turned ${r1(j.rot)}°`);
    const d = Number.isFinite(j.dSize) ? Math.abs(j.dSize) : 0, le = Math.max(d * STITCH.boneMm, j.uyMm || 0);
    len = Math.max(len, le);
    if (d > STITCH.sizeRed || le > STITCH.lenRedMm) { red = true; reasons.push(`join ${n}: lengths across it may be ${r1(le)} mm off`); }
    else if (d > STITCH.sizeAmber || le > STITCH.lenAmberMm) reasons.push(`join ${n}: lengths across it may be ${r1(le)} mm off`);
  });
  (r.segs || []).forEach((s, i) => { if (s.how === 'carried') reasons.push(`film ${i + 1}'s scale carried through a join, not measured on it`); });
  return { level: red ? 'red' : 'amber', reasons, lenErrMm: r1(len) };
}
// the one warning both pages show on a joined film, built from the recipe (never fixed text: Fable F23)
export function stitchWarning(r) {
  const js = r.joins || [], turned = js.some(j => Math.abs(j.rot || 0) > 1e-6);
  const how = js.every(j => j.st === 'matched') ? `joins matched on the overlap (${turned ? 'with a small turn' : 'shift only'})` : js.map((j, i) => `join ${i + 1} ${JOIN_HOW[j.st] || j.st}`).join(', ');
  return `Stitched image: ${r.n} exposures, ${how}. Alignment angles (HKA, mLDFA, MPTA) and lengths measured across a join depend on the join and can be wrong. Check them against a single-exposure long-leg film.`;
}
export function stitchScaleLine(r) {
  const segs = r.segs || [], a = segs[r.anchor] || {};
  const who = segs.map((s, i) => `film ${i + 1} by ${s.how === 'ball' ? `its ${s.d || 25} mm ball` : SCALE_HOW[s.how] || s.how}${s.how === 'carried' && Number.isInteger(s.from) ? ` from film ${s.from + 1}` : ''}`).join(', ');
  const dis = (r.joins || []).map((j, i) => (Number.isFinite(j.dSize) ? `${r2(Math.abs(j.dSize) * 100)} % (${r1(Math.abs(j.dSize) * STITCH.boneMm)} mm over ${STITCH.boneMm} mm) at join ${i + 1}` : null)).filter(Boolean);
  return `Scale ${r.outMm} mm per pixel, shown for film ${r.anchor + 1} (${a.how === 'ball' ? `${a.d || 25} mm ball` : 'known length'}); ${who}.${dis.length ? ` Films disagree by ${dis.join(', ')}.` : ''} Good at the depth of each ball, not for the whole limb.`;
}
// which film a row of the joined image belongs to, and whether points lie in more than one film (their result is marked)
export const stitchSegOf = (r, y) => { let k = 0; for (const j of r.joins || []) { if (y > j.seamY) k++; } return k; };
export const stitchCrosses = (r, ys) => new Set(ys.filter(Number.isFinite).map(y => stitchSegOf(r, y))).size > 1;
const fin = (v, lo, hi) => typeof v === 'number' && Number.isFinite(v) && v >= lo && v <= hi;
export function validateStitch(r) {   // null when usable, else the reason in words
  if (!r || typeof r !== 'object' || Array.isArray(r)) return 'no stitch record';
  if (r.v !== 1) return 'the stitch record is not version 1';
  let len = 0; try { len = JSON.stringify(r).length; } catch (e) { return 'the stitch record cannot be read'; }
  if (len > STITCH.recipeMax) return 'the stitch record is too long';
  if (!Number.isInteger(r.n) || r.n < STITCH.minSeg || r.n > STITCH.maxSeg) return 'the stitch record names no 2 to 4 films';
  if (!Array.isArray(r.segs) || r.segs.length !== r.n || !Array.isArray(r.joins) || r.joins.length !== r.n - 1) return 'the stitch record\'s films and joins do not match';
  if (!fin(r.outMm, 0.05, 2) || !Number.isInteger(r.cols) || !Number.isInteger(r.rows) || r.cols < 1 || r.rows < 1 || r.cols * r.rows > 60e6) return 'the stitch record\'s size or scale is not usable';
  if (!Number.isInteger(r.anchor) || !r.segs[r.anchor] || !['ball', 'line'].includes(r.segs[r.anchor].how)) return 'the stitch record has no film measured with a ball or a known length';
  for (const [i, s] of r.segs.entries()) {
    if (!s || !['ball', 'line', 'carried'].includes(s.how) || !fin(s.mm, 0.005, 5)) return `film ${i + 1} in the stitch record has no usable scale`;
    if (s.how === 'carried' && !(Number.isInteger(s.from) && Math.abs(s.from - i) === 1 && r.segs[s.from])) return `film ${i + 1}'s scale is carried from no neighbour`;
    if (!Array.isArray(s.A) || s.A.length !== 6 || !s.A.every(v => fin(v, -1e7, 1e7))) return `film ${i + 1}'s placement is not valid`;
    if (s.sha !== undefined && !/^[0-9a-f]{64}$/.test(String(s.sha))) return `film ${i + 1}'s fingerprint is not valid`;
    if (!(Number.isInteger(s.w) && Number.isInteger(s.h) && s.w > 0 && s.h > 0)) return `film ${i + 1}'s size is not valid`;
    const L = Math.max(s.w, s.h), pt = q => q && fin(q.x, -L, 2 * L) && fin(q.y, -L, 2 * L);
    if (s.how === 'ball' && !(pt(s.ball) && fin(s.ball.r, 0.5, L) && fin(s.d, 5, 60))) return `film ${i + 1}'s ball is not valid`;
    if (s.how === 'line' && !(Array.isArray(s.line) && s.line.length === 2 && s.line.every(pt) && Math.hypot(s.line[1].x - s.line[0].x, s.line[1].y - s.line[0].y) >= 10 && fin(s.len, 5, 1500))) return `film ${i + 1}'s known length is not valid`;
  }
  for (const [i, j] of r.joins.entries()) {
    if (!j || !['matched', 'weak', 'hand'].includes(j.st)) return `join ${i + 1} was not accepted`;
    if (!fin(j.seamY, 0, r.rows) || !fin(j.rot || 0, -STITCH.handTurnDeg, STITCH.handTurnDeg)) return `join ${i + 1} is not valid`;
    if (j.dSize !== null && j.dSize !== undefined && !fin(j.dSize, -0.5, 0.5)) return `join ${i + 1}'s size disagreement is not valid`;
    if (!fin(j.s === undefined ? 1 : j.s, 0.9, 1.1) || !(j.cA && fin(j.cA.x, -1e6, 1e6) && fin(j.cA.y, -1e6, 1e6)) || !(j.cB && fin(j.cB.x, -1e6, 1e6) && fin(j.cB.y, -1e6, 1e6))) return `join ${i + 1}'s placement is not valid`;
  }
  if (r.tick !== true) return 'the stitch record lacks the "one patient, one standing series" confirmation';
  return null;
}
export function stitchFromMeta(meta) {   // a joined film recognised from its own header, by any path it arrives (Fable F2, F3)
  const c = meta && typeof meta.ImageComments === 'string' ? meta.ImageComments : '';
  if (!c.startsWith(STITCH_TAG)) return null;
  let r = null; try { r = JSON.parse(c.slice(STITCH_TAG.length)); } catch (e) { return null; }
  return validateStitch(r) ? null : r;
}
// a short fingerprint of a recipe (FNV-1a twice), so Quick refuses a plan made on a different join of the same size (Fable F18)
export function stitchHash(r) {
  const s = JSON.stringify({ n: r.n, outMm: r.outMm, cols: r.cols, rows: r.rows, segs: (r.segs || []).map(q => [q.sha || '', q.mm, q.A]), joins: (r.joins || []).map(j => [j.st, j.seamY]) });
  let a = 0x811c9dc5, b = 0x01000193 ^ 0x5bd1e995;
  for (let i = 0; i < s.length; i++) { const c = s.charCodeAt(i); a = Math.imul(a ^ c, 16777619) >>> 0; b = Math.imul(b ^ c, 2246822519) >>> 0; }
  return a.toString(16).padStart(8, '0') + b.toString(16).padStart(8, '0');
}

// the calibration a page gives a joined film: the anchor's OWN method (ball or known length), carried into the joined film's
// pixels, so the page's own rule (ball diameter / 2r) gives the film's mm per pixel and older pages read it as a ball (Fable F1)
export function stitchCal(r) {
  const a = r && r.segs && r.segs[r.anchor]; if (!a || !Array.isArray(a.A)) return null;
  const A = a.A, p = (x, y) => ({ x: A[0] * x + A[1] * y + A[2], y: A[3] * x + A[4] * y + A[5] }), sc = Math.hypot(A[0], A[3]);
  const stitch = { hash: stitchHash(r), n: r.n, anchor: r.anchor };
  let c = null;
  if (a.how === 'ball' && a.ball) { const q = p(a.ball.x, a.ball.y); c = { method: 'ball', ball: { x: q.x, y: q.y, r: a.ball.r * sc }, how: 'stitched', confirmed: true, ballD: a.d || 25, mmPerPx: (a.d || 25) / (2 * a.ball.r * sc), stitch }; }
  if (a.how === 'line' && a.line) { const l = a.line.map(q => p(q.x, q.y)); c = { method: 'line', line: l, lengthMm: a.len, how: 'stitched', mmPerPx: a.len / Math.hypot(l[1].x - l[0].x, l[1].y - l[0].y), confirmed: true, stitch }; }
  // by construction the joined film's scale is its pixel, outMm: anything else is a damaged record, and no scale is given (the page
  // falls back to its machine estimate and the warning still shows)
  return c && Number.isFinite(c.mmPerPx) && Math.abs(c.mmPerPx / r.outMm - 1) <= 0.005 ? c : null;
}

// ---- the bipolar hemiarthroplasty shell (5 Nov 2026, Sami: "i want bipolar in both" Quick and the full planner) --------------------
// Chunli's shell family (kind 'bipolar') carries its head system in innerHead and, per size, its own head (22 mm on 38 by the
// DWG, 28 mm on the rest: the data says, nothing is typed here). The full planner (app.js bipHead, bipHeadText) still holds its
// own copy of these rules; Quick reads these. Moving the planner onto them is listed as open in SYNC.md.
export const bipInnerHead = (f, z) => ({ kind: f.innerHead.kind, diameter: z && Number.isFinite(z.innerHead) ? z.innerHead : f.innerHead.diameter });
// "38 mm on the 22 mm head, 40-55 mm on the 28 mm head", from the data
export function bipHeadSpan(f) {
  const g = [];
  for (const z of f.sizes) { const d = bipInnerHead(f, z).diameter, l = g[g.length - 1]; if (l && l.d === d) l.b = z.size; else g.push({ a: z.size, b: z.size, d }); }
  return g.map(x => `${x.a === x.b ? x.a : `${x.a}-${x.b}`} mm on the ${x.d} mm head`).join(', ');
}
// a size the library cannot draw a head for is not offered (the planner's bipHeadOk)
export const bipHeadHeld = (lib, f, z) => { const ih = bipInnerHead(f, z); return !!(((lib && lib.heads && lib.heads[ih.kind]) || {}).sizes || {})[String(ih.diameter)]; };
// the shell size whose outside diameter is nearest d (mm); midway between two goes to the smaller (the planner's bipPick)
export const bipNearest = (sizes, d) => sizes.reduce((b, z) => (Math.abs(z.outerDiameter - d) < Math.abs(b.outerDiameter - d) - 1e-9 ? z : b));

// the names that must be defined here only (tests/sync_test.mjs)
export const SHARED_NAMES = ['SIDE', 'other', 'CT_SOP', 'MAKER_DEFAULT', 'bipInnerHead', 'bipHeadSpan', 'bipHeadHeld', 'bipNearest', 'KNEE_TYPES', 'KT_PAIR', 'ktPairOf', 'DIA_TYPES', 'fmm', 'tumBodyLen', 'tumParts', 'collarList', 'collarMm',
  'percentileWindow', 'wlFromSliders', 'slidersFromWl', 'wlDrag', 'fillGray',
  'STITCH', 'STITCH_TAG', 'stitchStatus', 'stitchWarning', 'stitchScaleLine', 'stitchSegOf', 'stitchCrosses', 'validateStitch', 'stitchFromMeta', 'stitchHash', 'stitchCal'];
