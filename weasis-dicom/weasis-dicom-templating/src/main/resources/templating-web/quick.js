// Quick templating for phones (q2, 30 Oct 2026; first release r44 "Quick", same day). Sami: "i want the hip and knee template
// to be as simple as possible ... on their phone ... one simple as bonesetter type simple add implant mirror and tell them the
// measurments and sizes they need for both sides"; q2: "use medstrat and bonesetter details and good things make adding
// implants simple and adjustment easier as well ... dont say rep ... for knee case may be build it for all the 4 views and
// template for any of the suitable ones, when it is CT scout recognize the need not to template" ("i meant to say the need
// not to caliberate"); "Yes, confirmed only" (sizing only on a confirmed scale: a ball, a known length, or a CT scout's own
// scanner scale, r43/r44's rule). Design: DESIGN-2026-10-30-quick2.md with Astra's amendments. Draft for admin review.
// A hip case is one film. A knee case holds up to four films (AP, lateral, skyline, long leg), one on screen at a time: the AP
// and lateral X-rays and a CT scout take implants and share each side's femur and tray (range and size, never position, turn or
// scale); the skyline and a CT not marked as a scout are reference views with no sizing. A standing long-leg X-ray is sized
// on its ball (Sami, 31 Oct 2026: "also enable caliberating xrays of long leg standing xrays and differentiate when it is done
// using CT scout, where caliberation is not neccasary"), and a long film takes a four-tap valgus cut angle (no scale needed). Implants are true-size outlines from the same
// library.json as the full planner (only the ranges open to this account). No landmarks, no rules, no leg-length or offset
// plan, nothing saved to the patient list: every result is an estimate for the surgeon to confirm. The full planner
// (index.html, app.js) is not changed by this page.
// q6 (1 Nov 2026): a PFNA case (Sami: "template pfna from chunli pfna supplies for ti products and nebula for ss pfna"; then
// "use the ones from bonesetter and medstrat from other brands to design something what chunli and nebula have"). The nail is
// a GENERIC outline (build_pfna_generic.py: the makers' sizes and angle, the proximal shape of a DePuy Synthes TFN), said on the
// Sizes card. The nail goes in at the trochanter tip and turns about it; its blade slides along the nail's blade axis (drag the
// blade) so its outer end sits at the lateral cortex; three taps on the femoral head give the tip-apex distance on this film
// (the AP part only: the lateral film's part is not measured here). Design: DESIGN-2026-11-01-pfna.md.
// q7 (1 Nov 2026): Sami: "it should have option of saying fracture on R implant on L which is sometimes the side we prefer to
// measure and hence use it as template to mirror it to the injured side. sometimes we want to template both sides as well". The
// PFNA card asks the fracture side and where to template (the fracture side, the normal side then Copy, or both); Copy works for
// a nail (a long nail becomes the other side's nail of the same size); each side has its own femoral head; the Sizes card names
// the fracture and the normal side.
import { decodeAny, decodeBitmap } from './dicom.js';
import { decodePNG } from './imageio.js';
import * as G from './geom.js';
import { findBalls, findMarkers } from './auto.js';
import { kneePairCheck, insertGroupOf } from './autoplan.js';

const $ = id => document.getElementById(id);
const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const SIDE = { R: 'right', L: 'left' }, other = s => (s === 'R' ? 'L' : 'R');
const KIND = { cup: 'Cup', stem: 'Stem', 'tka-femur': 'Femur', 'tka-tibia': 'Tray', 'humeral-body': 'Humerus', nail: 'Nail' };
const SLOTS = { hip: ['cup', 'stem'], knee: ['tka-femur', 'tka-tibia'], pfna: ['nail'] };
const DEFAULT = { cup: 'chunli-cup', stem: 'chunli-be2-133s', 'tka-femur': 'chunli-tka-femur', 'tka-tibia': 'chunli-tka-tibia', nail: 'ao-pfna-short' };
const JOINT_WORD = { hip: 'hip', knee: 'knee', tumour: 'tumour', pfna: 'PFNA' };
const VIEW = { hip: 'Hip', ap: 'AP', lat: 'Lateral', sky: 'Skyline', long: 'Long leg', tum: 'Tumour' };
const IMAGE_MAX = 80 * 1024 * 1024, PIXELS_MAX = 60000000;   // as the full planner (projects.js)
const CUP_START_DEG = 40, CUP_START_MM = 50, INSERT_START_MM = 10, NUDGE_MM = 0.5;
const BALL_KEY = 'aoc-quick-ball';   // the ball size last used on this phone (a convenience; the card shows it every time)
const ballSaved = () => { try { const v = parseFloat(localStorage.getItem(BALL_KEY)); return v >= 5 && v <= 60 ? v : 25; } catch (e) { return 25; } };
// Sami, 31 Oct 2026: "the sphere used for each xray might be different, example 32 head for long leg standing, 25 for lateral or
// AP and scout without the sphere". Each view keeps the size last used for it on this phone (hip, AP, lateral, long leg); a view
// never used starts at 25, never another view's size.
const ballFor = fv => { try { const v = parseFloat(localStorage.getItem(`${BALL_KEY}-${fv}`)); return v >= 5 && v <= 60 ? v : 25; } catch (e) { return 25; } };
const STAMP = 'Quick estimate made on a phone (Quick templating). The surgeon confirms the plan and the sizes.';

// one film: its picture, its own scale, zoom and implants. Q.img, Q.items ... are the film on screen (accessors below).
let filmSeq = 0, ballDef = ballSaved();   // ballDef: the size a new film starts with (the last one typed on this phone)   // each film's identity: Undo restores a snapshot only onto the film it was taken from (Astra q2 P1)
const blankFilm = () => ({ id: ++filmSeq, ballD: ballDef, img: null, off: null, name: '', cal: null, items: [], rul: [], lld: [], hd: { R: [], L: [] }, vg: [], vgSide: '', view: { s: 1, tx: 0, ty: 0 }, mirrored: false, orient: '', orientHint: false, fv: 'hip', src: '', scout: false });
const Q = { lib: null, fam: {}, joint: 'hip', side: 'R', sel: -1, tool: null, tmp: [], choice: {}, hist: [], label: '', films: [blankFilm()], cur: 0, pend: null };
// Q.ballD is the film's own ball size: the field, the ball finder and every recalibration of that film use it (Astra q2 recheck P1)
for (const k of ['ballD', 'img', 'off', 'name', 'cal', 'items', 'rul', 'lld', 'hd', 'vg', 'vgSide', 'tp', 'dia', 'view', 'mirrored', 'orient', 'orientHint'])
  Object.defineProperty(Q, k, { get: () => Q.films[Q.cur][k], set: v => { Q.films[Q.cur][k] = v; } });
const F = () => Q.films[Q.cur];
const cv = $('cv'), g = cv.getContext('2d');
let dpr = 1, raf = 0;

// which films take implants: a hip, knee AP, knee lateral or standing long-leg X-ray on its ball or a known length, and a CT
// scout (hip, AP, lateral or the whole leg) on the scanner's scale. A skyline and a CT not marked as a scout never do.
const sizable = (f = F()) => !!f.img && (f.src === 'xray' ? ['hip', 'ap', 'lat', 'long', 'tum'].includes(f.fv) : f.src === 'ct' && f.scout && ['hip', 'ap', 'lat', 'long', 'tum'].includes(f.fv));
function refText(f = F()) {
  if (!f.img || sizable(f)) return '';
  if (f.src === 'ct') return f.scout ? 'CT scout skyline: reference view, no implant sizing.' : 'CT image not marked as a scout (no LOCALIZER, TOPOGRAM or SCOUT in its header): Quick does not size implants on it.';
  if (f.src === 'unsure') return 'Not sure whether this is an X-ray: shown for reference only, no implant sizing.';
  if (f.fv === 'sky') return 'Skyline: reference view, no implant sizing.';
  return 'Reference view, no implant sizing.';
}

// ---------------------------------------------------------------- the library: only what a quick plan can take
// A primary hip (cup + stem) or a primary knee (femur + tray). Tumour bodies, the PFR and PHR, the bipolar shell and the
// stem extensions are planned in the full planner only.
const tumourish = f => !!f.tumour || /-(ptr|dfr)-/.test(f.id) || f.sizes.some(z => 'reconstructionMm' in z || 'hingePost' in z || 'bodySize' in z);
const famsOf = kind => (Q.lib ? Q.lib.families.filter(f => f.kind === kind && f.sizes && f.sizes.length && !tumourish(f)) : []);
function sizesOf(f, side) {   // half sizes are not stocked; a long nail (q6) is a left or a right one
  const w = f.sizes.filter(z => !/\.5$/.test(String(z.size)) && (!side || !z.side || z.side === side)); return w.length ? w : f.sizes;
}
const sizeRec = it => { const f = Q.fam[it.fam]; return f && f.sizes.find(z => z.size === it.size); };
const sizeWord = (f, z) => String(f.kind === 'cup' || (z && Number.isFinite(z.reconstructionMm)) ? z.size : z.label || z.size);   // a tumour body by its code (2-L60): the label is on the card
const kindOf = it => Q.fam[it.fam].kind;
function startFam(kind) {
  const list = famsOf(kind), c = Q.choice[kind] && Q.fam[Q.choice[kind]];
  if (c && list.includes(c)) return c;
  return list.find(f => f.id === DEFAULT[kind]) || list[0] || null;
}
function trayFamFor(femFam) {   // the tray of the femur's own system (same range), else the start one
  const list = famsOf('tka-tibia'), c = Q.choice['tka-tibia'] && Q.fam[Q.choice['tka-tibia']];
  if (c && list.includes(c) && c.range === femFam.range) return c;
  return list.find(f => f.range === femFam.range) || startFam('tka-tibia');
}

// ---------------------------------------------------------------- geometry (the full planner's conventions: app.js geomOf, pivotOf, xf)
// Outlines are in mm, x right, y down, drawn for a right limb on a standard film. A cup turns about its centre, a stem about
// its head centre, a femur about its joint line, a tray about its cut. A knee lateral film takes the LAT outlines.
function geomOf(it) {
  const f = Q.fam[it.fam], z = sizeRec(it); if (!f || !z) return null;
  const v = F().fv === 'lat' ? 'LAT' : 'AP';
  if (f.kind === 'tka-femur') { const sd = z.sides && (z.sides[it.side] || z.sides.R || z.sides.L); return sd && sd.views ? sd.views[v] || null : null; }
  return z.views ? z.views[v] || null : null;
}
function pivotOf(it, gm) {
  const k = kindOf(it);
  if (k === 'cup') return [0, 0];
  if (k === 'stem') return gm.headM;
  if (k === 'tka-femur') return [0, gm.jointLineY || 0];
  if (k === 'tka-tibia') return [0, gm.cutY || 0];
  return [0, 0];
}
// sizes only on a confirmed scale (Sami, 30 Oct 2026: "confirmed only"): a ball or a known length, on a film that takes implants
const mmpp = () => (sizable() && Q.cal ? Q.cal.mmPerPx : null);
function xf(it) {   // local mm -> image px
  const gm = geomOf(it), pv = pivotOf(it, gm), s = 1 / mmpp(), c = Math.cos(it.angle), sn = Math.sin(it.angle), fx = it.flip ? -1 : 1;
  return l => { const lx = fx * (l[0] - pv[0]) * s, ly = (l[1] - pv[1]) * s; return G.P(it.x + c * lx - sn * ly, it.y + sn * lx + c * ly); };
}
// the hip's lateral side on the image: a standard film has the patient's right on the image left
const latLeft = side => (side === 'R') !== Q.mirrored;
const hipFlip = side => !latLeft(side);
const kneeFlip = () => (F().fv === 'lat' ? false : Q.mirrored);   // a lateral faces the way the user sets with Flip
// the cup face against the reference line: the teardrop line when measured, else the film's horizontal
// the teardrop line, only when its two marks are far enough apart to define one (Fable q4 P1: a dragged mark can land on the
// other): 40 mm with a scale (the taps' own rule), 40 px without; else null and the film's horizontal is used
function tdLine() {
  if (Q.lld.length < 2) return null; const d = G.dist(Q.lld[0], Q.lld[1]);
  return (mmpp() ? d * mmpp() >= 40 : d >= 40) ? G.sub(Q.lld[1], Q.lld[0]) : null;
}
function cupIncl(it) {
  const X = xf(it), a = X([-10, 0]), b = X([10, 0]), u = G.sub(b, a);
  const ref = tdLine() || G.P(1, 0);
  let d = G.angleDeg(u, ref); if (d > 90) d = 180 - d; return d;
}

// ---------------------------------------------------------------- view
const toS = p => G.P(p.x * Q.view.s + Q.view.tx, p.y * Q.view.s + Q.view.ty);
const toI = p => G.P((p.x - Q.view.tx) / Q.view.s, (p.y - Q.view.ty) / Q.view.s);
function resize() {
  dpr = window.devicePixelRatio || 1; const r = cv.getBoundingClientRect();
  cv.width = Math.max(1, Math.round(r.width * dpr)); cv.height = Math.max(1, Math.round(r.height * dpr)); draw();
}
function fit() {
  if (!Q.img) return; const r = cv.getBoundingClientRect(), s = Math.min(r.width / Q.img.cols, r.height / Q.img.rows);
  Q.view = { s, tx: (r.width - Q.img.cols * s) / 2, ty: (r.height - Q.img.rows * s) / 2 }; draw();
}
function zoomAt(sp, k) {
  const r = cv.getBoundingClientRect(), base = Q.img ? Math.min(r.width / Q.img.cols, r.height / Q.img.rows) : 1;
  const s = Math.min(base * 40, Math.max(base * 0.5, Q.view.s * k)), ip = toI(sp);
  Q.view.s = s; Q.view.tx = sp.x - ip.x * s; Q.view.ty = sp.y - ip.y * s; draw();
}
// the turn handle of the picked implant, on screen, just outside the outline along one of its own directions (up for a cup,
// femur or tray; out to the side of the shoulder for a stem, whose "up" is the cup), so a drag on it turns the implant about
// its pivot (Bonesetter-like); the ⟲ ⟳ buttons stay for 1-degree steps
function handleDir(it) { const fx = it.flip ? -1 : 1; return kindOf(it) === 'stem' || kindOf(it) === 'nail' ? [-fx, 0] : [0, -1]; }   // local, after the flip
function handleOf(it) {
  const gm = geomOf(it); if (!gm || !gm.rings || !mmpp()) return null;
  const pv = pivotOf(it, gm), [ux, uy] = handleDir(it), fx = it.flip ? -1 : 1; let ext = 0;
  for (const p of gm.rings[0]) ext = Math.max(ext, fx * (p[0] - pv[0]) * ux + (p[1] - pv[1]) * uy);
  const c = toS(G.P(it.x, it.y)), d = ext / mmpp() * Q.view.s + 34, cs = Math.cos(it.angle), sn = Math.sin(it.angle);
  return { c, h: G.P(c.x + d * (cs * ux - sn * uy), c.y + d * (sn * ux + cs * uy)) };
}

// ---------------------------------------------------------------- drawing (the screen and the saved picture share it)
const draw = () => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; drawNow(); }); };
function drawNow() {
  g.setTransform(1, 0, 0, 1, 0, 0); g.fillStyle = '#000'; g.fillRect(0, 0, cv.width, cv.height);
  if (!Q.img) return;
  g.setTransform(dpr * Q.view.s, 0, 0, dpr * Q.view.s, dpr * Q.view.tx, dpr * Q.view.ty);
  g.imageSmoothingEnabled = true; g.drawImage(Q.off, 0, 0);
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  scene(g, toS, 1, true);
  const it = Q.items[Q.sel], H = it && !Q.tool && handleOf(it);
  if (H) {
    g.strokeStyle = '#A8FF30'; g.lineWidth = 1.4; g.setLineDash([4, 4]); g.beginPath(); g.moveTo(H.c.x, H.c.y); g.lineTo(H.h.x, H.h.y); g.stroke(); g.setLineDash([]);
    g.beginPath(); g.arc(H.h.x, H.h.y, 13, 0, 7); g.fillStyle = '#034537'; g.fill(); g.lineWidth = 2; g.stroke();
    g.fillStyle = '#fff'; g.font = '600 15px system-ui, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText('⟳', H.h.x, H.h.y + 1);
  }
}
function scene(c, T, k, live) {
  const line = (a, b) => { const p = T(a), q = T(b); c.beginPath(); c.moveTo(p.x, p.y); c.lineTo(q.x, q.y); c.stroke(); };
  const dot = (p, r, col) => { const s = T(p); c.beginPath(); c.arc(s.x, s.y, r * k, 0, 7); c.fillStyle = col; c.fill(); };
  const tag = (p, text, col, big) => {
    const s = T(p); c.font = `${big ? 700 : 600} ${(big ? 15 : 13) * k}px system-ui, sans-serif`; const w = c.measureText(text).width + 8 * k, h = (big ? 21 : 18) * k;
    c.fillStyle = 'rgba(0,0,0,.78)'; c.fillRect(s.x - w / 2, s.y - h / 2, w, h); c.fillStyle = col; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(text, s.x, s.y);
  };
  // the ball
  if (Q.cal && Q.cal.ball) {
    const b = Q.cal.ball, s = T(G.P(b.x, b.y)), r = T(G.P(b.x + b.r, b.y)).x - s.x;
    c.lineWidth = 2 * k; c.strokeStyle = '#A8FF30'; c.setLineDash([]); c.beginPath(); c.arc(s.x, s.y, r, 0, 7); c.stroke();
  }
  // the implants
  if (mmpp()) Q.items.forEach((it, i) => {
    const gm = geomOf(it); if (!gm || !gm.rings) return;
    const X = xf(it), sel = live && i === Q.sel, col = it.recheck ? '#ffb84d' : sel ? '#A8FF30' : '#7fd4ff', f = Q.fam[it.fam];
    c.beginPath();
    for (const r of gm.rings) { r.forEach((p, j) => { const s = T(X(p)); j ? c.lineTo(s.x, s.y) : c.moveTo(s.x, s.y); }); c.closePath(); }
    const sr = stemRingsQ(it, gm) || bladeRingsQ(it);   // the stem extension at the part's junction; a nail's blade (q6)
    if (sr) for (const r of sr) { r.forEach((p, j) => { const s = T(X(p)); j ? c.lineTo(s.x, s.y) : c.moveTo(s.x, s.y); }); c.closePath(); }
    if (sel) { c.fillStyle = 'rgba(168,255,48,.10)'; c.fill('evenodd'); }
    c.lineWidth = (sel ? 2.4 : 1.8) * k; c.strokeStyle = col; c.setLineDash(it.recheck ? [7 * k, 4 * k] : []); c.stroke(); c.setLineDash([]);
    if (f.kind === 'stem' && gm.headM) {   // the head drawn on the sheet's head, size as drawn
      const hd = parseFloat((f.sheetHead && f.sheetHead.diameter) || 32), h = T(X(gm.headM)), r = (hd / 2) / mmpp() * (T(G.P(1, 0)).x - T(G.P(0, 0)).x);
      c.beginPath(); c.arc(h.x, h.y, r, 0, 7); c.lineWidth = 1.4 * k; c.stroke();
      if (gm.neckCut) { c.setLineDash([6 * k, 4 * k]); line(X(gm.neckCut.lateral), X(gm.neckCut.medial)); c.setLineDash([]); }
    }
    if (f.kind === 'cup') { const o = X([0, 0]); dot(o, 2.5, col); }
    if (f.kind === 'nail') nailScene(c, T, k, it, gm, X, col, line);
    const lab = f.kind === 'nail' ? X([0, -12]) : f.kind === 'cup' ? X([0, -(sizeRec(it).outerDiameter || 50) / 2 - 7]) : f.kind === 'stem' ? X(gm.tip || gm.rings[0][0]) : X([0, f.kind === 'tka-femur' ? -30 : 40]);
    // the picked implant's label in larger type (Medstrat labels each placed part with its size; the range is on the Sizes card)
    const word = `${Q.joint === 'tumour' || isTumBody(f) ? partName(it) : KIND[f.kind]} ${it.side} ${sizeRec(it).size}${stemRec(it) ? ' + stem' : ''}${bladeRec(it) ? ` + blade ${bladeRec(it).lengthMm}` : ''}${f.kind === 'cup' ? ` · ${cupIncl(it).toFixed(0)}°` : ''}${it.recheck ? ' · recheck fit' : ''}`;
    tag(lab, word, col, sel);
  });
  if (Q.joint === 'tumour') { tumScene(c, T, k, line, tag); const pp = q => { if (live) { dot(q, 7, 'rgba(0,0,0,.55)'); dot(q, 5, '#ff9de2'); } else dot(q, 3.5, '#ff9de2'); };
    Q.items.forEach(it => { if (it.gt) pp(it.gt); }); if (Q.dia) ['ref', 'a1', 'a2'].forEach(e => Q.dia[e] && pp(Q.dia[e])); }
  // the measurements
  c.setLineDash([]); c.lineWidth = 2 * k; c.strokeStyle = '#ffd34d';
  const pt = (p, col) => { if (live) { dot(p, 7, 'rgba(0,0,0,.55)'); dot(p, 5, col); } else dot(p, 3.5, col); };   // on screen a point big enough to grab and drag
  for (const m of Q.rul) { line(m.a, m.b); pt(m.a, '#ffd34d'); pt(m.b, '#ffd34d'); if (mmpp()) tag(G.mid(m.a, m.b), `${(G.dist(m.a, m.b) * mmpp()).toFixed(0)} mm`, '#ffd34d'); }
  if (Q.lld.length) {
    Q.lld.forEach(p => pt(p, '#ff9de2'));
    if (tdLine()) { c.strokeStyle = '#ff9de2'; c.setLineDash([8 * k, 5 * k]); const u = G.unit(G.sub(Q.lld[1], Q.lld[0])), e = 0.25 * G.dist(Q.lld[0], Q.lld[1]); line(G.sub(Q.lld[0], G.mul(u, e)), G.add(Q.lld[1], G.mul(u, e))); c.setLineDash([]); }
    if (Q.lld.length === 4) for (const j of [2, 3]) line(Q.lld[j], G.project(Q.lld[j], Q.lld[0], G.sub(Q.lld[1], Q.lld[0])));
  }
  for (const hs of ['R', 'L']) if (Q.hd && Q.hd[hs] && Q.hd[hs].length) {   // q6/q7: each side's femoral head, three taps and its circle
    Q.hd[hs].forEach(p => pt(p, '#ff9de2'));
    const hc = headC(hs); if (hc) { const s = T(G.P(hc.x, hc.y)), r = T(G.P(hc.x + hc.r, hc.y)).x - s.x; c.strokeStyle = '#ff9de2'; c.lineWidth = 1.6 * k; c.setLineDash([6 * k, 4 * k]); c.beginPath(); c.arc(s.x, s.y, Math.abs(r), 0, 7); c.stroke(); c.setLineDash([]); }
  }
  if (Q.vg.length) {   // the valgus cut angle: hip centre to knee centre (mechanical), the canal line (anatomical) carried to the knee
    c.lineWidth = 2 * k; c.strokeStyle = '#ff9de2'; Q.vg.forEach(p => pt(p, '#ff9de2'));
    if (Q.vg.length >= 2) line(Q.vg[0], Q.vg[1]);
    if (Q.vg.length === 4) {
      const [, kn, c1, c2] = Q.vg, u = G.unit(G.sub(c2, c1)), t = G.dot(G.sub(kn, c1), u);
      c.strokeStyle = '#ffd34d'; c.setLineDash([8 * k, 5 * k]); line(G.add(c1, G.mul(u, t)), G.add(c2, G.mul(u, 0.4 * G.dist(c1, c2)))); c.setLineDash([]);
      const sf = T(G.P(1, 0)).x - T(G.P(0, 0)).x || 1;   // screen px per image px
      const va = vgAngle(F()); tag(G.add(kn, G.P(0, 40 * k / sf)), va === null ? 'Valgus: points too close together' : `Valgus ${va.toFixed(1)}°`, '#ff9de2', true);
    }
  }
  if (live) for (const p of Q.tmp) dot(p, 4, '#ffd34d');
}

// ---------------------------------------------------------------- the films
function sniff(u, name) {
  if (u.length > 132 && u[128] === 0x44 && u[129] === 0x49 && u[130] === 0x43 && u[131] === 0x4d) return 'dicom';
  if (u[0] === 0x89 && u[1] === 0x50 && u[2] === 0x4e && u[3] === 0x47) return 'png';
  if (u[0] === 0xff && u[1] === 0xd8) return 'jpeg';
  if (u[0] === 0x52 && u[1] === 0x49 && u[2] === 0x46 && u[3] === 0x46 && u[8] === 0x57 && u[9] === 0x45) return 'webp';
  if (/\.(dcm|dicom)$/i.test(name)) return 'dicom';
  return null;
}
// Q.pend: the knee view the user asked for (a tab), or null (the open card, the menu)
async function openFile(file) {
  $('openErr').textContent = '';
  if (copyPending()) { Q.pend = null; return; }
  const showErr = t => { msg(''); if (Q.img) msg(t); else { $('openErr').textContent = t; $('open').hidden = false; } };
  try {
    if (file.size > IMAGE_MAX) throw new Error(`This file is ${(file.size / 1048576).toFixed(0)} MB; the page opens X-rays up to 80 MB.`);
    msg('Opening the film…');
    const buf = await file.arrayBuffer(), u = new Uint8Array(buf), kind = sniff(u, file.name);
    if (!kind) throw new Error('Not a DICOM, JPEG, PNG or WebP picture.');
    const bitmap = async () => decodeBitmap(await createImageBitmap(new Blob([u], { type: 'image/' + kind })));
    const img = kind === 'dicom' ? await decodeAny(buf) : kind === 'png' ? (await decodePNG(buf)) || await bitmap() : await bitmap();
    if (img.cols * img.rows > PIXELS_MAX) throw new Error('This picture is larger than 60 megapixels; save a smaller copy.');
    filmCard(img, file.name, kind === 'dicom');
  } catch (e) { showErr((e && e.message) || String(e)); }
}
// what the DICOM says: a CT, a scout when its header says so (as r44 app.js ctScoutOf: Image Type LOCALIZER, or TOPOGRAM,
// SCOUT, SURVIEW, SCANOGRAM, LOCALIZER in its image type or series description), else an X-ray; a picture says nothing
const CT_SOP = ['1.2.840.10008.5.1.4.1.1.2', '1.2.840.10008.5.1.4.1.1.2.1'];
function dicomSource(img) {
  const m = img.meta || {};
  if (String(m.Modality || '').toUpperCase().trim() !== 'CT' && !CT_SOP.includes(String(m.SOPClassUID || '').trim())) return { src: 'xray', scout: false };
  const it = String(m.ImageType || '').toUpperCase(), d = String(m.SeriesDescription || '').toUpperCase();
  return { src: 'ct', scout: /LOCALI[SZ]ER/.test(it) || /TOPOGRAM|SCOUT|SURVIEW|SCANOGRAM|LOCALI[SZ]ER/.test(it + ' ' + d) };
}
// Sami, 29 and 30 Oct 2026: "CT scout should not need calibration with sphere as it is already a CT scan". A scout's Pixel
// Spacing is the scanner's scale at the patient (r43/r44 ctScale): used at once, no ball, no magnification. Imager Pixel
// Spacing (the detector's pitch, far from the patient on a CT: sizes about 1.8 times too large) is never a scale.
function ctScanScale(f) {
  const m = (f.img && f.img.meta) || {}, ps = m.pixelSpacing;
  if (f.src !== 'ct' || !f.scout || !(ps && ps[0] > 0.01 && ps[0] < 5)) return null;
  return { method: 'ct', mmPerPx: ps[0] };
}
function ctNoScaleWhy(f) {
  const m = (f.img && f.img.meta) || {}, ps = m.pixelSpacing;
  if (m.Modality === 'picture' || !m.Modality) return 'A picture of a CT scout carries no scanner scale: mark a known length on it (the scale button), or tap a ball if one is on it.';
  if (ps && ps[0] > 0) return `This CT scout's Pixel Spacing (${ps[0]} mm) is outside 0.01-5 mm, so it is not believed: mark a known length (the scale button).`;
  if (m.imagerPixelSpacing && m.imagerPixelSpacing[0] > 0) return "This CT scout gives only the detector's pixel spacing, not the scanner's scale at the patient (sizes would come out about 1.8 times too large): mark a known length (the scale button).";
  return 'This CT scout has no pixel spacing: mark a known length (the scale button).';
}
function guessView(img) {   // the knee film's view from its DICOM words; the user confirms it on the card
  const m = img.meta || {}, w = `${m.SeriesDescription || ''} ${m.StudyDescription || ''}`.toUpperCase(), vp = String(m.ViewPosition || '').toUpperCase();
  if (/SKY|SUNRISE|MERCHANT|PATELL|AXIAL/.test(w)) return 'sky';
  if (/LONG|STANDING LEG|SCANOGRAM|WHOLE LEG|FULL LEG/.test(w) || /LEG|LOWER LIMB|EXTREMITY/.test(String(m.BodyPartExamined || '').toUpperCase()) || img.rows > 2.2 * img.cols) return 'long';
  if (/^(LAT|LL|RL|LLO|RLO)$/.test(vp) || /\bLAT/.test(w)) return 'lat';
  return 'ap';
}
// After a film opens: what it is. A knee film needs its view; a picture (no DICOM) needs the user to say X-ray or CT scout
// (Astra, 30 Oct 2026: never promise to recognise a scout in a JPEG). A DICOM hip X-ray needs nothing.
function filmCard(img, name, isDicom) {
  const knee = Q.joint === 'knee', ds = isDicom ? dicomSource(img) : null;
  let fv = knee ? Q.pend || guessView(img) : Q.joint === 'tumour' ? 'tum' : 'hip', src = ds ? ds.src : '', wb = null, bd = null;   // bd: the sphere typed on this card   // wb: a long X-ray taken standing (weight-bearing), said by the user
  const needWb = () => fv === 'long' && src === 'xray';
  const needBall = () => src === 'xray' && fv !== 'sky';   // an X-ray that takes implants is calibrated by its sphere
  if (!knee && ds) return commitFilm(img, name, fv, ds.src, ds.scout);
  const draw1 = () => sheet(`<h2>${knee ? 'Which knee film is this?' : 'What is this film?'}</h2>
    ${knee ? `<div class="choice wrap">${['ap', 'lat', 'sky', 'long'].map(v => `<button data-v="${v}" aria-pressed="${fv === v}">${VIEW[v]}</button>`).join('')}</div>` : ''}
    ${ds ? `<p class="note">${ds.src === 'ct' ? (ds.scout ? "CT scout (from the file): sized on the scanner's own scale, no ball needed." : 'CT image not marked as a scout (from the file): shown without sizing.') : 'X-ray (from the file).'}</p>`
      : `<label class="f">The picture is</label><div class="choice"><button data-s="xray" aria-pressed="${src === 'xray'}">An X-ray</button><button data-s="ct" aria-pressed="${src === 'ct'}">A CT scout</button><button data-s="unsure" aria-pressed="${src === 'unsure'}">Not sure</button></div>
        <p class="note">An X-ray is sized on its ball (or a known length). A picture of a CT scout has lost the scanner's scale: it needs a known length. "Not sure" is shown without sizing.</p>`}
    ${needBall() ? `<label class="f" for="fBall">Sphere on this film (mm)</label><input type="number" inputmode="decimal" id="fBall" min="5" max="60" step="0.5" value="${bd || ballFor(fv)}">
      <p class="note">Each film keeps its own sphere (for example 32 mm on a long leg, 25 mm on the AP and lateral); this view's last size is filled in.</p>`
      : src === 'ct' && ds && ds.scout ? '<p class="note">No sphere needed: the CT scanner sets the scale.</p>' : ''}
    ${needWb() ? `<label class="f">This long-leg X-ray was taken</label><div class="choice"><button data-w="1" aria-pressed="${wb === true}">Standing</button><button data-w="0" aria-pressed="${wb === false}">Lying, or not sure</button></div>` : ''}
    ${knee ? '<p class="note">AP, lateral and long leg take the implants: an X-ray on its ball (on a long leg, the ball at knee level), a CT scout on the scanner\'s scale with no ball. The skyline is for reference. Measure on a long leg gives the valgus cut angle.</p>' : ''}
    <button class="go" id="fOk"${src && (!needWb() || wb !== null) ? '' : ' disabled'}>Use this film</button>${F().fv === 'pending' ? '' : '<button class="go alt" id="fNo">Cancel</button>'}`, card => {
    card.querySelectorAll('[data-v]').forEach(b => b.onclick = () => { fv = b.dataset.v; bd = null; draw1(); $('sheet').dataset.modal = '1'; });
    card.querySelectorAll('[data-s]').forEach(b => b.onclick = () => { src = b.dataset.s; draw1(); $('sheet').dataset.modal = '1'; });
    card.querySelectorAll('[data-w]').forEach(b => b.onclick = () => { wb = b.dataset.w === '1'; draw1(); $('sheet').dataset.modal = '1'; });
    const fb = card.querySelector('#fBall'); if (fb) fb.oninput = () => { const v = parseFloat(fb.value); bd = v >= 5 && v <= 60 ? v : null; };
    card.querySelector('#fOk').onclick = () => {
      let ball = null;
      if (needBall()) { ball = parseFloat(fb.value); if (!(ball >= 5 && ball <= 60)) { fb.focus(); return; } try { localStorage.setItem(`${BALL_KEY}-${fv}`, String(ball)); localStorage.setItem(BALL_KEY, String(ball)); } catch (e) { /* kept for this film only */ } }
      closeSheet(); commitFilm(img, name, fv, src, ds ? ds.scout : src === 'ct', needWb() && wb === true, ball);
    };
    const no = card.querySelector('#fNo'); if (no) no.onclick = () => { closeSheet(); Q.pend = null; msg(Q.img ? refText() : ''); if (!Q.img) $('open').hidden = false; };
  });
  msg(''); draw1(); $('sheet').dataset.modal = '1';
}
// the film into the case: a hip case has one film; a knee case one film per view (a second AP replaces the first, asked)
function commitFilm(img, name, fv, src, scout, wb = false, ball = null) {
  Q.pend = null;
  if (copyPending()) return;   // a film dropped on the page while copies wait
  let i = Q.joint === 'knee' ? Q.films.findIndex(f => f.img && f.fv === fv) : 0;
  if (Q.joint === 'knee' && i >= 0 && Q.films[i].items.length && !confirm(`Replace the ${VIEW[fv]} film? Its implants are removed.`)) { msg(refText()); return; }
  if (Q.joint !== 'knee' && F().img && Q.items.length && !confirm('Open this film instead? The implants on the film open now are removed.')) { msg(refText()); return; }
  const f = blankFilm(); Object.assign(f, { img, name, fv, src, scout: !!scout, wb: !!wb, ballD: ball || ballFor(fv) });
  if (Q.joint !== 'knee') { Q.films = [f]; Q.cur = 0; Q.hist = []; }   // a new hip or tumour film is a new case
  else {
    if (i < 0) i = Q.films.findIndex(x => !x.img || x.fv === 'pending');
    if (i < 0) { Q.films.push(f); i = Q.films.length - 1; } else Q.films[i] = f;
    Q.cur = i;
  }
  Q.sel = -1; Q.tmp = []; Q.tool = null;
  let lo = img.wc - img.ww / 2, w = img.ww;
  if (img.ww >= 0.95 * (img.max - img.min)) {   // a window over the whole range shows a flat grey film: 1st to 99th percentile
    const d = img.data, step = Math.max(1, Math.floor(d.length / 200000)), smp = [];
    for (let j = 0; j < d.length; j += step) smp.push(d[j]);
    smp.sort((a, b) => a - b); const a = smp[Math.floor(smp.length * 0.01)], b = smp[Math.floor(smp.length * 0.99)]; lo = a; w = Math.max(1, b - a);
  }
  Q.dragTool = null; f.win0 = { lo, w }; f.win = { lo, w }; f.inv = false; paintFilm(f);
  $('open').hidden = true; resize(); fit(); syncUI();
  if (!sizable()) { msg(refText()); return; }
  if (f.src === 'ct') {   // a scout: the scanner's scale at once, or why there is none (no ball search on a CT)
    f.cal = ctScanScale(f);
    if (f.cal) { msg("Scale from the CT scanner (the scout's Pixel Spacing): no ball needed."); syncUI(); draw(); startSheet(); }
    else { Q.tool = null; msg(ctNoScaleWhy(f)); syncUI(); }
    return;
  }
  msg('Looking for the ball…');
  setTimeout(findScale, 30);
}
// copies waiting for their tap: no other film until they are placed or undone (Astra q2 P2)
// Q.pendCopy: the side whose copies still wait for their tap (kept apart from the tool, saved and restored by Undo; Astra q2
// recheck P2); while it is set no other film, tool, scale change or Sizes card
// 6 Nov 2026: the film's picture from its window (W, 0) and invert (F11), the viewer's keys; display only
let wlRaf = 0;
function paintFilm(f) {
  const img = f.img, off = f.off || document.createElement('canvas'); off.width = img.cols; off.height = img.rows;
  const oc = off.getContext('2d'), id = oc.createImageData(img.cols, img.rows), px = id.data, k = 255 / f.win.w;
  for (let j = 0, n = img.cols * img.rows; j < n; j++) { let v = (img.data[j] - f.win.lo) * k; v = v < 0 ? 0 : v > 255 ? 255 : v; if (!!img.invert !== f.inv) v = 255 - v; px[4 * j] = px[4 * j + 1] = px[4 * j + 2] = v; px[4 * j + 3] = 255; }
  oc.putImageData(id, 0, 0); f.off = off;
}
function copyPending() { if (!Q.pendCopy) return false; Q.tool = 'mirrorTo'; msg(`First tap the ${SIDE[Q.pendCopy]} ${Q.joint} to put the copies there, or Undo.`); syncUI(); return true; }
function switchFilm(i) {
  if (i === Q.cur || !Q.films[i] || !Q.films[i].img) return;
  if (copyPending()) return;
  Q.tool = null; Q.tmp = []; Q.cur = i; Q.sel = Q.items.length ? 0 : -1; hidePad();
  resize(); syncUI();
  const re = Q.items.filter(it => it.recheck);
  msg(refText() || (re.length ? `Sizes were changed on another film: check the fit of ${re.map(it => `${KIND[kindOf(it)].toLowerCase()} ${it.side}`).join(' and ')} here (orange).`
    : !Q.cal ? (F().src === 'ct' ? ctNoScaleWhy(F()) : `Tap the ${Q.ballD} mm ball on this film first.`) : Q.items.length ? '' : `Tap Add to put the implants on the ${VIEW[F().fv]}.`));
}
// the machine's own scale from a DICOM header: only a hint of the ball's size in pixels for the ball finder, never the
// scale (Sami, 30 Oct 2026: sizes on a confirmed ball or known length only)
function machineHint() {
  const m = (Q.img && Q.img.meta) || {};
  if (F().src === 'ct') { const c = ctScanScale(F()); return c ? c.mmPerPx : null; }
  if (F().src !== 'xray') return null;
  if (m.pixelSpacing && m.pixelSpacing[0] > 0) return m.pixelSpacing[0];
  if (m.imagerPixelSpacing && m.imagerPixelSpacing[0] > 0) return m.imagerPixelSpacing[0] / (m.magnification > 0.9 && m.magnification < 2 ? m.magnification : 1);
  return null;
}
function findScale() {
  const img = Q.img; if (!img || !sizable()) return;
  const est = machineHint();
  let balls = [], marks = [];
  try { balls = findBalls(img, { mmPerPx: est, ballMm: Q.ballD }); } catch (e) { balls = []; }
  try { marks = findMarkers(img, { mmPerPx: est }); } catch (e) { marks = []; }
  readMarkers(marks);
  if (balls.length) { setBall(balls[0], 'found'); return; }
  Q.tool = 'ball'; msg(`Tap the ${Q.ballD} mm ball on the X-ray (zoom in with two fingers if it is small). No ball on this film? Tap the scale button at the bottom for a known length.`); syncUI();
}
// an R or L marker on a film of both hips: which way the film faces (app.js applyMarker, the both-hips case)
function readMarkers(ms) {
  if ((Q.joint !== 'hip' && Q.joint !== 'pfna') || !ms || !ms.length) { Q.orient = ''; return; }
  let vote = 0; const said = [];
  for (const mk of ms.slice(0, 2)) {
    const pos = (mk.x - (Q.img.cols - 1) / 2) / Math.max(Q.img.cols, Q.img.rows);
    if (Math.abs(pos) < 0.08) continue;
    vote += (mk.letter === 'L' ? 1 : -1) * Math.sign(pos); said.push(`${mk.letter} on the image ${pos > 0 ? 'right' : 'left'}`);
  }
  if (!vote) return;
  // the image's middle is the pelvic midline only on a film of both hips (r43 applyMarker decides only when the surgeon says so):
  // the page never turns the film by itself; a marker that reads as mirrored is put to the user on the start card
  Q.orient = `marker ${said.join(', ')}`;
  Q.orientHint = vote < 0;
}
// Sami, 30 Oct 2026: "THE 25MM SIZE SHOULD BE EDITABLE AS SOME SURGEONS USE DIFFERNENT SPHERE SIZE". A new size rescales the
// film on screen at once (the ball found keeps its place); the implants keep their sizes and are redrawn true size. Each film
// keeps the ball size it was set with.
function setBallD(raw) {
  const v = parseFloat(raw); if (!(v >= 5 && v <= 60)) { msg('Ball size: a number from 5 to 60 mm.'); return false; }
  if (v !== Q.ballD || (Q.cal && Q.cal.ball && Q.cal.ballD !== v)) snap();   // before either changes: Undo brings back size and scale together (Astra q2 recheck 2)
  Q.ballD = v; ballDef = v; try { localStorage.setItem(BALL_KEY, String(v)); localStorage.setItem(`${BALL_KEY}-${F().fv}`, String(v)); } catch (e) { /* this phone keeps nothing: the card still shows it */ }
  if (Q.cal && Q.cal.ball && Q.cal.ballD !== v) Q.cal = { ...Q.cal, ballD: v, mmPerPx: v / (2 * Q.cal.ball.r) };
  syncUI(); draw(); return true;
}
const ballField = id => `<label class="f" for="${id}">Ball size on this film (mm)</label><input type="number" inputmode="decimal" id="${id}" min="5" max="60" step="0.5" value="${Q.ballD}">`;
function setBall(b, how) {
  if (Q.cal) snap();   // a new scale on a film that had one: Undo brings the old one back
  Q.cal = { method: 'ball', ball: { x: b.x, y: b.y, r: b.r }, how, ballD: Q.ballD, mmPerPx: Q.ballD / (2 * b.r) };
  Q.tool = null; Q.tmp = [];
  msg((how === 'found' ? `Ball found: check the green ring sits on the ${Q.ballD} mm ball (if not, tap the scale button below).` : how === 'edges' ? `Scale set from the ${Q.ballD} mm ball's width: check the green ring matches the ball.` : 'Scale set from the ball.')
    + (F().fv === 'long' ? ' Long leg: the ball must be at knee level; the sizes are right at the knee, a few per cent off at the hip and ankle.' : ''));
  syncUI(); draw(); if (!Q.items.length) startSheet();
}
function tapBall(ip) {
  const est = machineHint(), rMax = Math.min(600, Math.max(30, est ? 1.8 * (Q.ballD / 2) / est : 200));
  const b = G.detectBall(Q.img, ip, rMax);
  if (!b || b.rejected) { armBall2(`${b ? 'The ball edge is not clear there (' + b.reasons.join('; ') + ')' : 'No ball edge found there'}. `); return; }
  setBall(b, 'tapped');
}
// no ball on the film: two taps on the ends of something of known length on it (a ruler laid on the film), then its length
function armLen() { Q.tool = 'len'; Q.tmp = []; syncUI(); draw(); msg('Zoom in, then tap the two ends of the ruler (or other object of known length) on the film.'); }
function tapLen(ip) {
  Q.tmp.push(ip); draw();
  if (Q.tmp.length < 2) { msg('Now tap the other end.'); return; }
  const [a, b] = Q.tmp, d = G.dist(a, b); Q.tmp = [];
  if (d < 10) { msg('The two taps are too close: zoom in and tap the two ends again.'); return; }
  sheet(`<h2>Known length</h2><label class="f" for="lenMm">Length between the two taps (mm)</label><input type="number" inputmode="decimal" id="lenMm" min="5" max="1000" step="1">
    <p class="note">Use an object at the level of the bone: a ruler lying on the film is magnified less than the bone above it, so it makes the implants look larger than they are.</p>
    <button class="go" id="lenOk">Set the scale</button><button class="go alt" id="lenNo">Cancel</button>`, card => {
    card.querySelector('#lenOk').onclick = () => {
      const v = parseFloat(card.querySelector('#lenMm').value); if (!(v >= 5 && v <= 1000)) { card.querySelector('#lenMm').focus(); return; }
      if (Q.cal) snap();
      Q.cal = { method: 'length', mmPerPx: v / d, note: `a known length of ${v} mm`, len: v, a, b }; Q.tool = null; closeSheet();
      msg(`Scale set from a known length of ${v} mm.`); syncUI(); draw(); if (!Q.items.length) startSheet();
    };
    card.querySelector('#lenNo').onclick = () => { closeSheet(); Q.tool = null; syncUI(); };
    setTimeout(() => card.querySelector('#lenMm').focus(), 50);
  });
}
// by hand, when the edge finder cannot: two taps on opposite edges of the ball (its width), after zooming in on it
function armBall2(why = '') {
  Q.tool = 'ball2'; Q.tmp = []; syncUI(); draw();
  msg(`${why}Zoom in on the ball with two fingers, then tap its left edge and its right edge.`);
}
function tapBall2(ip) {
  Q.tmp.push(ip); draw();
  if (Q.tmp.length < 2) { msg('Now tap the opposite edge of the ball.'); return; }
  const [a, b] = Q.tmp, d = G.dist(a, b);
  if (d < 4) { Q.tmp = []; msg('The two taps are too close: zoom in more and tap the two edges of the ball.'); return; }
  setBall({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, r: d / 2 }, 'edges');
}

// ---------------------------------------------------------------- the plan
// Undo covers every film of the case at once (a size shared by the AP and the lateral comes back on both) and each film's scale.
function snap() {
  Q.hist.push(JSON.stringify({ pendCopy: Q.pendCopy || null, cur: F().id, sel: Q.sel, joint: Q.joint, side: Q.side, frac: Q.frac || null, pOn: Q.pOn || null, films: Q.films.map(f => ({ id: f.id, ballD: f.ballD, vg: f.vg, vgSide: f.vgSide, tp: f.tp || null, dia: f.dia || null, items: f.items, rul: f.rul, lld: f.lld, hd: f.hd || { R: [], L: [] }, cal: f.cal, mirrored: f.mirrored, orient: f.orient, orientHint: f.orientHint })) }));
  if (Q.hist.length > 60) Q.hist.shift(); $('undoB').disabled = false;
}
function undo() {
  const s = Q.hist.pop(); if (!s) return; const o = JSON.parse(s);
  // by identity: a film replaced since the snapshot keeps its own picture, scale and implants (Astra q2 P1)
  for (const fo of o.films) {
    const f = Q.films.find(x => x.id === fo.id); if (!f) continue;
    f.items = fo.items; f.lld = fo.lld.map(p => G.P(p.x, p.y)); f.hd = { R: ((fo.hd && fo.hd.R) || []).map(p => G.P(p.x, p.y)), L: ((fo.hd && fo.hd.L) || []).map(p => G.P(p.x, p.y)) }; f.rul = fo.rul.map(m => ({ a: G.P(m.a.x, m.a.y), b: G.P(m.b.x, m.b.y) }));
    f.vg = (fo.vg || []).map(p => G.P(p.x, p.y)); f.vgSide = fo.vgSide || ''; f.tp = fo.tp || null; f.dia = fo.dia || null;
    f.cal = fo.cal; if (fo.ballD) f.ballD = fo.ballD; f.mirrored = fo.mirrored; f.orient = fo.orient || ''; f.orientHint = !!fo.orientHint;
  }
  Q.joint = o.joint || Q.joint; Q.side = o.side || Q.side; Q.frac = o.frac || null; Q.pOn = o.pOn || null;
  const at = Q.films.findIndex(x => x.id === o.cur && x.img), moved = at >= 0 && at !== Q.cur;
  if (moved) { Q.cur = at; resize(); }
  Q.pendCopy = o.pendCopy || null; Q.tool = Q.pendCopy ? 'mirrorTo' : null; Q.tmp = []; hidePad();
  msg(Q.pendCopy ? `Undone. The ${SIDE[Q.pendCopy]} copies still wait: tap the ${SIDE[Q.pendCopy]} ${Q.joint} to put them there.` : moved ? `Undone (on the ${VIEW[F().fv]}).` : 'Undone.');
  Q.sel = Math.min(o.sel, Q.items.length - 1); syncUI(); draw();
}
// a nail (q6): − + and "have ready" step the diameter at the same length (canal fill); the length is chosen in the list
// a nail starts at the middle stocked length and the diameter nearest 10 mm (the user sizes it on the film)
function midNail(list) { const ls = [...new Set(list.map(q => q.lengthMm))].sort((x, y) => x - y), L = ls[Math.floor((ls.length - 1) / 2)], at = list.filter(q => q.lengthMm === L);
  return at.reduce((a, q) => (Math.abs(q.diameterMm - 10) < Math.abs(a.diameterMm - 10) ? q : a)); }
const stepList = (f, side, size) => { const l = sizesOf(f, side), z = l.find(q => q.size === size); return f.kind === 'nail' && z ? l.filter(q => q.lengthMm === z.lengthMm) : l; };
function newItem(f, side, at, size) {
  const list = sizesOf(f, side), z = (size && list.find(q => q.size === size)) || (f.kind === 'nail' ? midNail(list) : list[Math.floor((list.length - 1) / 2)]);
  return { fam: f.id, size: z.size, side, x: at.x, y: at.y, angle: 0, flip: false };
}
// the knee's other implant films (AP, lateral, a CT scout), where the same side's femur and tray share range and size
const pairFilms = () => (Q.joint === 'knee' && sizable() ? Q.films.filter(f => f !== F() && sizable(f)) : []);
function shareSize(it) {
  it.recheck = false;   // set or looked at here: this film's fit is the user's
  for (const o of pairFilms()) {
    const t = o.items.find(x => x.side === it.side && kindOf(x) === kindOf(it));
    if (t && (t.fam !== it.fam || t.size !== it.size || (t.stem || null) !== (it.stem || null))) { t.fam = it.fam; t.size = it.size; t.stem = it.stem || null; t.recheck = true; }
  }
}
// the parts on the other films for this side: a new placement here starts from them (the lateral from the AP's sizes)
function pairKeep(side) { const k = {}; for (const o of pairFilms().reverse()) for (const it of o.items) if (it.side === side) k[kindOf(it)] = it; return k; }
const fromOther = it => !!it && pairFilms().some(o => o.items.includes(it));
function placeAt(ip) {
  const kinds = Q.placeKinds || SLOTS[Q.joint];
  snap();
  const side = Q.side, keep = pairKeep(side);
  for (const it of Q.items) if (it.side === side) keep[kindOf(it)] = it;
  Q.items = Q.items.filter(it => !(it.side === side && kinds.includes(kindOf(it))));
  const fromPair = kinds.map(k => keep[k]).find(fromOther), pairName = fromPair && VIEW[pairFilms().find(o => o.items.includes(fromPair)).fv];
  const placed = [];
  if (Q.joint === 'pfna') {
    const nf = startFam('nail');
    if (nf) {
      const n = newItem(nf, side, ip, keep.nail && keep.nail.fam === nf.id ? keep.nail.size : null), bend = sizeRec(n).bendDeg || 6, bf = bladeFamOf(n);
      n.flip = hipFlip(side); n.angle = (n.flip ? 1 : -1) * G.rad(bend);   // the shaft below the bend starts straight down the film
      if (bf) { const bl = sizesOf(bf); n.blade = (keep.nail && keep.nail.blade && bl.some(z => z.size === keep.nail.blade)) ? keep.nail.blade : bl[Math.floor((bl.length - 1) / 2)].size; }
      n.slide = keep.nail && Number.isFinite(keep.nail.slide) ? keep.nail.slide : defSlide(n);
      placed.push(n);
    }
    const nextStep = Q.pOn === 'both' ? (!Q.items.some(it => it.side === other(side)) ? ` Then the ${SIDE[other(side)]} button and Add for the other side.` : '')
      : Q.frac && side !== Q.frac ? ` When it fits, Copy ⇄ puts it on the ${SIDE[Q.frac]} fracture side.` : '';
    msg(`${wideWarn(Q.img.cols > 1.3 * Q.img.rows, ip.x < Q.img.cols / 2, side)}${nextStep ? `${SIDE[side][0].toUpperCase() + SIDE[side].slice(1)} hip (${fracWord(side)}). ` : ''}Turn the nail (green ⟳ handle) so its shaft runs down the middle of the canal; drag the blade along its line so its outer end is at the lateral cortex. − + nail size; Add: blade length and the femoral head (tip-apex distance).${nextStep}`);
  } else if (Q.joint === 'hip') {
    const cf = kinds.includes('cup') && startFam('cup'), sf = kinds.includes('stem') && startFam('stem'), fl = hipFlip(side);
    // a start only, the same on every film (a femoral head's edge on a phone picture is too faint to size it from): the user sizes it
    const c50 = cf ? sizesOf(cf).reduce((a, z) => (Math.abs(parseFloat(z.size) - CUP_START_MM) < Math.abs(parseFloat(a.size) - CUP_START_MM) ? z : a)).size : null;
    if (cf) { const c = newItem(cf, side, ip, keep.cup && keep.cup.fam === cf.id ? keep.cup.size : c50); c.flip = fl; c.angle = (fl ? -1 : 1) * G.rad(CUP_START_DEG); placed.push(c); }   // as r43 autoCup: the face opens down and out, the dome up and in
    if (sf) { const s = newItem(sf, side, ip, keep.stem && keep.stem.fam === sf.id ? keep.stem.size : null); s.flip = fl; placed.push(s); }
    const wide = Q.img.cols > 1.3 * Q.img.rows, onLeft = ip.x < Q.img.cols / 2;
    msg(`${wideWarn(wide, onLeft, side)}${cf && sf ? 'Drag the cup onto the socket and the stem down the canal.' : `Drag the ${cf ? 'cup onto the socket' : 'stem down the canal'}.`} Tap an implant to pick it; − + size, the green ⟳ handle turns it.`);
  } else {
    const keepF = keep['tka-femur'], ff = kinds.includes('tka-femur') ? (fromOther(keepF) ? Q.fam[keepF.fam] : startFam('tka-femur')) : null;
    const femNow = ff || (Q.items.find(it => it.side === side && kindOf(it) === 'tka-femur') && Q.fam[Q.items.find(it => it.side === side && kindOf(it) === 'tka-femur').fam]);
    const keepT = keep['tka-tibia'], tf = kinds.includes('tka-tibia') ? (fromOther(keepT) ? Q.fam[keepT.fam] : femNow ? trayFamFor(femNow) : startFam('tka-tibia')) : null;
    // on a lateral the outlines face the way the other implants on this side face (Flip turns them)
    const face = F().fv === 'lat' ? !!(Q.items.find(it => it.side === side) || {}).flip : kneeFlip();
    if (ff) { const fe = newItem(ff, side, ip, keepF && keepF.fam === ff.id ? keepF.size : null); fe.flip = face; placed.push(fe); }
    if (tf) {
      let size = keepT && keepT.fam === tf.id ? keepT.size : null;
      const fe = placed.find(it => kindOf(it) === 'tka-femur') || Q.items.find(it => it.side === side && kindOf(it) === 'tka-femur');
      if (!size && fe) { const c = kneePairCheck(fe.fam, fe.size, tf.id, sizesOf(tf)[0].size); if (c && c.green.length) size = c.green.find(z => tf.sizes.some(q => q.size === z)) || null; }
      const ti = newItem(tf, side, ff ? G.P(ip.x, ip.y + INSERT_START_MM / mmpp()) : ip, size); ti.flip = face; placed.push(ti);
    }
    const what = ff && tf ? 'Drag the femur onto the condyles and the tray onto the tibia.' : ff ? 'Drag the femur onto the condyles.' : 'Drag the tray onto the tibia.';
    msg(`${fromPair ? `Same sizes as the ${pairName}. ` : ''}${what}${F().fv === 'lat' ? ' Flip turns them to face the front of the knee.' : ''} − + size, the green ⟳ handle turns the one picked.`);
  }
  Q.items.push(...placed);
  for (const it of placed) shareSize(it);
  Q.sel = placed.length ? Q.items.indexOf(placed[0]) : Q.items.findIndex(it => it.side === side);
  Q.tool = null; Q.placeKinds = null; syncUI(); draw();
}
function wideWarn(wide, onLeft, side) {
  if (!wide || onLeft === latLeft(side)) return '';
  return `Check the side: on a ${Q.mirrored ? 'mirrored' : 'standard'} film the ${SIDE[side]} hip is on the image ${latLeft(side) ? 'left' : 'right'}. `;
}
function stepSize(d) {
  const it = Q.items[Q.sel]; if (!it) return; const list = stepList(Q.fam[it.fam], it.side, it.size), i = list.findIndex(z => z.size === it.size), j = i + d;
  if (i < 0 || j < 0 || j >= list.length) { msg(Q.fam[it.fam].kind === 'nail' ? `No ${d < 0 ? 'thinner' : 'thicker'} nail at this length; other lengths are in the list.` : d < 0 ? 'This is the smallest size in this range.' : 'This is the largest size in this range.'); return; }
  snap(); it.size = list[j].size; afterSize(it);
}
function setSize(v) { const it = Q.items[Q.sel]; if (!it || it.size === v) return; snap(); it.size = v; afterSize(it); }
function afterSize(it) {
  let lost = '';
  if (it.stem && !stemRec(it)) { lost = `The stem was removed: size ${it.size} does not take it on the maker's sheet. `; it.stem = null; }   // (Fable q5 P3)
  shareSize(it); msg(lost);
  const re = pairFilms().filter(o => o.items.some(x => x.recheck));
  if (re.length) msg(`${lost}Same size on the ${re.map(o => VIEW[o.fv]).join(' and ')}: check its fit there.`);
  pairNote(it); syncUI(); draw();
}
function pairNote(it) {   // the XN knee: Chunli's chart for this femur and tray; never changes the tray by itself
  if (Q.joint !== 'knee') return;
  const p = kneePairOf(it.side); if (!p) return;
  if (p.grade === 'gray') msg(`Femur ${p.fe} with tray ${p.ti} is not a pair on Chunli's chart; trays that match femur ${p.fe}: ${p.green.join(', ')}.`);
  else if (p.grade === 'yellow') msg(`Femur ${p.fe} with tray ${p.ti}: allowed on Chunli's chart, not the closest match (${p.green.join(', ')}).`);
}
function kneePairOf(side, items = Q.items) {
  const fe = items.find(it => it.side === side && kindOf(it) === 'tka-femur'), ti = items.find(it => it.side === side && kindOf(it) === 'tka-tibia');
  if (!fe || !ti) return null; const c = kneePairCheck(fe.fam, fe.size, ti.fam, ti.size); return c ? { ...c, fe: fe.size, ti: ti.size } : { grade: 'none', fe: fe.size, ti: ti.size };
}
function turn(deg) { const it = Q.items[Q.sel]; if (!it) return; it.angle += G.rad(deg); it.recheck = false; syncUI(); draw(); }   // + turns clockwise on screen
function nudge(dx, dy) { const it = Q.items[Q.sel]; if (!it || !mmpp()) return; it.x += dx * NUDGE_MM / mmpp(); it.y += dy * NUDGE_MM / mmpp(); it.recheck = false; draw(); }
// Copy to the other side: the side's implants copied, reflected about the middle of the pelvis (the teardrops' midpoint when
// measured, else the middle of the film); then one tap on the other joint puts the copies there (a patient is often not in
// the middle of the film: Astra review recheck, 30 Oct 2026, case 2 landed 5 cm off), keeping their spacing. A side that
// already has implants is replaced only when the user says so (Astra, q2 design input).
function mirror() {
  if (copyPending()) return;
  const it0 = Q.items[Q.sel], from = it0 ? it0.side : Q.side, to = other(from), src = Q.items.filter(it => it.side === from); if (!src.length) return;
  if (Q.items.some(it => it.side === to) && !confirm(`Replace the ${SIDE[to]} implants on this film with a copy of the ${SIDE[from]} ones?`)) return;
  snap();
  const mx = tdLine() ? (Q.lld[0].x + Q.lld[1].x) / 2 : (Q.img.cols - 1) / 2;
  Q.items = Q.items.filter(it => it.side !== to);
  const made = [], swapNote = [];
  for (const it of src) {
    const k = kindOf(it), c = { ...it, side: to, x: 2 * mx - it.x, angle: -it.angle, recheck: false };
    if (k === 'cup' || k === 'stem' || k === 'nail') c.flip = !it.flip;   // a knee femur takes the other side's own outline; the tray is the same both sides
    if (k === 'nail') { const sw = sideNail(Q.fam[it.fam], sizeRec(it), to); if (sw.size !== it.size) { c.size = sw.size; if (sw.note) swapNote.push(sw.note); } }
    Q.items.push(c); made.push(c);
  }
  for (const c of made) shareSize(c);
  Q.side = to; Q.sel = Q.items.findIndex(it => it.side === to);
  Q.tool = 'mirrorTo'; Q.tmp = []; Q.pendCopy = to;   // the copies' side, whatever side button is pressed before the tap
  msg(`Copied to the ${SIDE[to]} side, same sizes. ${swapNote.join(' ')}Now tap the ${Q.joint === 'hip' ? `centre of the ${SIDE[to]} femoral head` : Q.joint === 'pfna' ? `tip of the ${SIDE[to]} greater trochanter` : `middle of the ${SIDE[to]} knee joint`} to put ${src.length > 1 ? 'them' : 'it'} there.`);
  syncUI(); draw();
}
// the mirrored copies moved together so their anchor (the cup's centre, else the stem's head; the femur's joint line) is on the tap
function mirrorTo(ip) {
  const to = Q.pendCopy || Q.side, its = Q.items.filter(it => it.side === to); if (!its.length) { Q.tool = null; Q.pendCopy = null; syncUI(); return; }
  const a = its.find(it => kindOf(it) === 'cup' || kindOf(it) === 'tka-femur') || its[0], dx = ip.x - a.x, dy = ip.y - a.y;
  for (const it of its) { it.x += dx; it.y += dy; }
  Q.side = to; Q.tool = null; Q.pendCopy = null; msg(`Placed on the ${SIDE[to]} ${Q.joint === 'pfna' ? 'hip' : Q.joint}. Drag to fine-tune; − + change the size of the one picked.${Q.joint === 'pfna' ? ' Add: this side\'s femoral head for its tip-apex distance.' : ''}`); syncUI(); draw();
}
// a knee lateral: the side's femur and tray turned to face the other way (the front of the knee on the other side of the image)
function flipLat() {
  if (copyPending()) return;
  const it0 = Q.items[Q.sel]; if (!it0) return; snap();
  for (const it of Q.items) if (it.side === it0.side) { it.flip = !it.flip; it.angle = -it.angle; it.recheck = false; }
  syncUI(); draw();
}
// the film's way round changed: each implant faces the new way (a cup keeps its inclination, mirrored about the vertical)
function reflip() {
  if (F().fv === 'lat') return;
  for (const it of Q.items) {
    const k = kindOf(it), f = k === 'cup' || k === 'stem' || k === 'nail' ? hipFlip(it.side) : kneeFlip();
    if (f !== it.flip) { it.flip = f; it.angle = -it.angle; }
  }
}
function removeSel() { if (Q.sel < 0) return; snap(); Q.items.splice(Q.sel, 1); Q.sel = Q.items.length ? 0 : -1; syncUI(); draw(); }

// ---------------------------------------------------------------- touch: drag an implant, turn it by its handle, pan, pinch, tap for a tool
const ptrs = new Map(); let gest = null;
function hitItem(ip) {
  const order = Q.items.map((_, i) => i).sort((a, b) => (b === Q.sel) - (a === Q.sel));
  const tol = 14 / Q.view.s;
  for (const i of order) {
    const it = Q.items[i], gm = geomOf(it); if (!gm || !gm.rings) continue; const X = xf(it), poly = gm.rings[0].map(X);
    if (kindOf(it) === 'cup') { const r = (sizeRec(it).outerDiameter || 50) / 2 / mmpp(); if (G.dist(ip, X([0, 0])) <= r + tol) return i; continue; }
    if (inPoly(ip, poly) || nearPoly(ip, poly, tol)) return i;
    if (kindOf(it) === 'stem' && gm.headM && G.dist(ip, X(gm.headM)) <= 16 / mmpp() + tol) return i;
  }
  return -1;
}
function inPoly(p, poly) { let c = false; for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) { const a = poly[i], b = poly[j]; if ((a.y > p.y) !== (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c; } return c; }
function nearPoly(p, poly, tol) { for (let i = 0; i < poly.length; i += 3) if (G.dist(p, poly[i]) < tol) return true; return false; }
const sp = e => { const r = cv.getBoundingClientRect(); return G.P(e.clientX - r.left, e.clientY - r.top); };
cv.addEventListener('pointerdown', e => {
  if (!Q.img) return; cv.setPointerCapture(e.pointerId); ptrs.set(e.pointerId, sp(e));
  if (ptrs.size === 2) { const [a, b] = [...ptrs.values()]; gest = { mode: 'pinch', d0: G.dist(a, b) || 1, s0: Q.view.s, m0: G.mid(a, b), ip0: toI(G.mid(a, b)) }; return; }
  const p = sp(e), ip = toI(p);
  if (Q.tool) { gest = { mode: 'tap', p0: p, last: p }; return; }
  const sel = Q.items[Q.sel], H = sel && handleOf(sel);
  if (H && G.dist(p, H.h) <= 24) { gest = { mode: 'turn', p0: p, snapped: false }; return; }
  const mp = hitPoint(p); if (mp) { gest = { mode: 'point', p0: p, ref: mp, snapped: false }; return; }
  const bh = mmpp() ? bladeHit(ip) : -1;   // q6: a blade slides along the nail's blade axis
  if (bh >= 0) { Q.sel = bh; Q.side = Q.items[bh].side; const it = Q.items[bh]; gest = { mode: 'blade', p0: p, ip0: ip, s0: slideOf(it), snapped: false }; syncUI(); draw(); return; }
  const h = mmpp() ? hitItem(ip) : -1;
  if (h >= 0) { Q.sel = h; Q.side = Q.items[h].side; const it = Q.items[h]; gest = { mode: 'drag', p0: p, off: G.sub(ip, G.P(it.x, it.y)), snapped: false }; syncUI(); draw(); return; }
  gest = { mode: Q.dragTool || 'pan', p0: p, last: p, win: F().win ? { ...F().win } : null };
});
cv.addEventListener('pointermove', e => {
  if (!ptrs.has(e.pointerId) || !gest) return; ptrs.set(e.pointerId, sp(e)); const p = sp(e);
  if (gest.mode === 'pinch' && ptrs.size >= 2) {
    const [a, b] = [...ptrs.values()], m = G.mid(a, b), r = cv.getBoundingClientRect(), base = Math.min(r.width / Q.img.cols, r.height / Q.img.rows);
    const s = Math.min(base * 40, Math.max(base * 0.5, gest.s0 * G.dist(a, b) / gest.d0));
    Q.view = { s, tx: m.x - gest.ip0.x * s, ty: m.y - gest.ip0.y * s }; draw(); return;
  }
  if (gest.mode === 'tap' && G.dist(p, gest.p0) > 10) gest.mode = 'pan';
  if (gest.mode === 'wl') { const f = F(), w0 = gest.win; if (w0) { const w = Math.max(1, w0.w * Math.exp((p.x - gest.p0.x) / 200)), c = w0.lo + w0.w / 2 - (p.y - gest.p0.y) * w0.w / 400; f.win = { lo: c - w / 2, w }; if (!wlRaf) wlRaf = requestAnimationFrame(() => { wlRaf = 0; paintFilm(f); draw(); }); } return; }
  if (gest.mode === 'zoom') { zoomAt(gest.p0, Math.exp((gest.last.y - p.y) / 150)); gest.last = p; return; }
  if (gest.mode === 'pan') { Q.view.tx += p.x - gest.last.x; Q.view.ty += p.y - gest.last.y; gest.last = p; draw(); return; }
  if (gest.mode === 'turn') {
    const it = Q.items[Q.sel]; if (!it) return;
    if (!gest.snapped && G.dist(p, gest.p0) > 3) { snap(); gest.snapped = true; }
    if (gest.snapped) { const c = toS(G.P(it.x, it.y)), [ux, uy] = handleDir(it); it.angle = Math.atan2(p.y - c.y, p.x - c.x) - Math.atan2(uy, ux); it.recheck = false; draw(); }
    return;
  }
  if (gest.mode === 'point') {   // a measurement point moved: the length or angle follows
    if (!gest.snapped && G.dist(p, gest.p0) > 3) { snap(); gest.snapped = true; }
    if (gest.snapped) { setPoint(gest.ref, toI(p)); draw(); }
    return;
  }
  if (gest.mode === 'blade') {
    if (!gest.snapped && G.dist(p, gest.p0) > 3) { snap(); gest.snapped = true; }
    if (gest.snapped) { const it = Q.items[Q.sel], u = bladeU(it); if (u) { it.slide = Math.max(SLIDE_MIN, Math.min(SLIDE_MAX, gest.s0 + G.dot(G.sub(toI(p), gest.ip0), u) * mmpp())); draw(); } }
    return;
  }
  if (gest.mode === 'drag') {
    if (!gest.snapped && G.dist(p, gest.p0) > 3) { snap(); gest.snapped = true; }
    if (gest.snapped) { const it = Q.items[Q.sel], ip = G.sub(toI(p), gest.off); it.x = ip.x; it.y = ip.y; it.recheck = false; draw(); }
  }
});
const endPtr = e => {
  if (!ptrs.has(e.pointerId)) return; ptrs.delete(e.pointerId);
  if (gest && gest.mode === 'pinch') { gest = ptrs.size === 1 ? { mode: 'pan', p0: [...ptrs.values()][0], last: [...ptrs.values()][0] } : null; return; }
  if (gest && gest.mode === 'tap' && e.type === 'pointerup') toolTap(toI(sp(e)));
  if (gest && gest.mode === 'turn' && gest.snapped) syncUI();
  if (gest && gest.mode === 'point' && gest.snapped) msg(pointText(gest.ref));
  if (gest && (gest.mode === 'blade' || gest.mode === 'drag' || gest.mode === 'turn') && gest.snapped && Q.joint === 'pfna') msg(pfnaText(Q.items[Q.sel]));
  if (!ptrs.size) gest = null;
};
cv.addEventListener('pointerup', endPtr); cv.addEventListener('pointercancel', endPtr);
cv.addEventListener('wheel', e => { e.preventDefault(); zoomAt(sp(e), Math.exp(-e.deltaY * 0.0015)); }, { passive: false });
// Sami, 31 Oct 2026: "adjusting measurements should be easy": every point of a ruler, the leg-length marks and the valgus
// taps is dragged where it belongs; the length or angle is redone at once (Undo brings the old point back)
function hitPoint(p) {
  let best = null, bd = 22;
  const near = (q, ref) => { const d = G.dist(p, toS(q)); if (d < bd) { bd = d; best = ref; } };
  Q.rul.forEach((m, i) => { near(m.a, { k: 'rul', i, e: 'a' }); near(m.b, { k: 'rul', i, e: 'b' }); });
  Q.lld.forEach((q, i) => near(q, { k: 'lld', i }));
  Q.vg.forEach((q, i) => near(q, { k: 'vg', i }));
  for (const s of ['R', 'L']) ((Q.hd && Q.hd[s]) || []).forEach((q, i) => near(q, { k: 'hd', s, i }));
  Q.items.forEach((it, i) => { if (it.gt) near(it.gt, { k: 'gt', i }); });
  if (Q.dia) for (const e of ['ref', 'a1', 'a2']) if (Q.dia[e]) near(Q.dia[e], { k: 'dia', e });
  return best;
}
function setPoint(r, ip) { if (r.k === 'hd') Q.hd[r.s][r.i] = ip; else if (r.k === 'rul') Q.rul[r.i][r.e] = ip; else if (r.k === 'gt') Q.items[r.i].gt = ip; else if (r.k === 'dia') Q.dia[r.e] = ip; else Q[r.k][r.i] = ip; }
function pointText(r) {
  if (r.k === 'gt' || r.k === 'dia') return tumSummary();
  if (r.k === 'hd') return headC(r.s) ? pfnaText(Q.items.find(it => kindOf(it) === 'nail' && it.side === r.s)) || `${SIDE[r.s]} femoral head ${(2 * headC(r.s).r * mmpp()).toFixed(0)} mm.` : 'These three points do not make a femoral head (in a line, or under 30 or over 80 mm across): drag them round the head.';
  if (r.k === 'rul') { const m = Q.rul[r.i]; return mmpp() ? `Length ${r.i + 1}: ${(G.dist(m.a, m.b) * mmpp()).toFixed(0)} mm.` : ''; }
  if (r.k === 'lld') return Q.lld.length === 4 ? (lldText() || 'The two teardrops are too close together: drag them apart.') : '';
  const a = vgAngle(F()); return a === null ? 'Points too close together: drag them apart to get the angle.' : `Valgus cut angle ${a.toFixed(1)}° (${longWord(F())}).${a < 3 || a > 10 ? ' Outside the usual 5-7°: check the four points and that the patella faces forward.' : ''}`;
}
function toolTap(ip) {
  const t = Q.tool;
  if (t === 'ball') return tapBall(ip);
  if (t === 'ball2') return tapBall2(ip);
  if (t === 'len') return tapLen(ip);
  if (t === 'place') return placeAt(ip);
  if (t === 'mirrorTo') return mirrorTo(ip);
  if (t === 'tum') return tumTap(ip);
  if (t === 'head') {   // q6: three points round the femoral head's edge
    Q.tmp.push(ip);
    if (Q.tmp.length < 3) { msg(HEAD_STEPS[Q.tmp.length]); draw(); return; }
    const hc = G.circle3(...Q.tmp);
    if (!hc || hc.r * mmpp() < 15 || hc.r * mmpp() > 40) { Q.tmp = []; msg(`That is not a femoral head (${hc ? (2 * hc.r * mmpp()).toFixed(0) + ' mm across' : 'points in a line'}). ${HEAD_STEPS[0]}`); draw(); return; }
    snap(); Q.hd = { ...Q.hd, [Q.side]: Q.tmp }; Q.tmp = []; Q.tool = null;
    const it = Q.items.find(x => kindOf(x) === 'nail' && x.side === Q.side); msg(it ? pfnaText(it) : `Femoral head ${(2 * hc.r * mmpp()).toFixed(0)} mm. Add the nail to get the tip-apex distance.`); syncUI(); draw(); return;
  }
  if (t === 'ruler') { Q.tmp.push(ip); if (Q.tmp.length === 2) { snap(); Q.rul.push({ a: Q.tmp[0], b: Q.tmp[1] }); Q.tmp = []; Q.tool = null; msg(`Measured ${(G.dist(Q.rul.at(-1).a, Q.rul.at(-1).b) * mmpp()).toFixed(0)} mm.`); } else msg('Tap the other end.'); syncUI(); draw(); return; }
  if (t === 'vg') {
    Q.tmp.push(ip); const n = Q.tmp.length;
    if (n < 4) { msg(VG_STEPS[n]); draw(); return; }
    if (G.dist(Q.tmp[2], Q.tmp[3]) < 20 || G.dist(Q.tmp[0], Q.tmp[1]) < 40) { Q.tmp = []; msg('The points are too close together. ' + VG_STEPS[0]); draw(); return; }
    snap(); Q.vg = Q.tmp; Q.vgSide = Q.side; Q.tmp = []; Q.tool = null;
    const a = vgAngle(F());
    msg(`Valgus cut angle ${a.toFixed(1)}° (femoral shaft axis to mechanical axis), ${SIDE[Q.side]} leg, ${longWord(F())}.${a < 3 || a > 10 ? ' Outside the usual 5-7°: check the four points and that the patella faces forward.' : ''} Shown on the Sizes card.`);
    syncUI(); draw(); return;
  }
  if (t === 'lld') {
    Q.tmp.push(ip); const n = Q.tmp.length;
    if (n === 2 && G.dist(Q.tmp[0], Q.tmp[1]) * mmpp() < 40) { Q.tmp = []; msg('The two teardrops must be on the two sides of the pelvis. ' + LLD_STEPS[0]); draw(); return; }
    if (n < 4) { msg(LLD_STEPS[n]); draw(); return; }
    snap(); Q.lld = Q.tmp; Q.tmp = []; Q.tool = null; msg(lldText() + ' Shown on the Sizes card.'); syncUI(); draw();
  }
}
// The valgus cut angle (the femoral shaft axis to the mechanical axis: what is set on the intramedullary guide). Angles need
// no scale, so it works on a long film before calibration and on a CT scout.
const VG_STEPS = ['Tap the centre of the femoral head (the middle of the ball of the hip).', 'Tap the centre of the knee: the top of the notch between the condyles.', 'Tap the middle of the femoral canal about 10 cm above the knee.', 'Tap the middle of the femoral canal about 20 cm above the knee (the straight part of the shaft).'];
// no angle from two points on top of each other (a dragged point can land there): null, said, never NaN
const vgAngle = f => (f.vg && f.vg.length === 4 && G.dist(f.vg[0], f.vg[1]) >= 40 && G.dist(f.vg[2], f.vg[3]) >= 20 ? G.angleDeg(G.sub(f.vg[0], f.vg[1]), G.sub(f.vg[3], f.vg[2])) : null);
const vgOk = (f = F()) => !!f.img && f.fv === 'long' && (f.src === 'xray' || (f.src === 'ct' && f.scout));   // a CT not marked as a scout, or "not sure": no angle (Astra q3)
const LLD_STEPS = ['Tap the lowest point of the right teardrop.', 'Tap the lowest point of the left teardrop.', 'Tap the tip of the right lesser trochanter.', 'Tap the tip of the left lesser trochanter.'];
// leg length: each lesser trochanter's distance below the teardrop line; the longer leg's trochanter sits lower
function lldMm() {
  if (Q.lld.length < 4 || !mmpp()) return null; const [tR, tL, lR, lL] = Q.lld, d = G.sub(tL, tR);
  if (!tdLine()) return null;   // the teardrops dragged together: no line, no leg length
  let n = G.unit(G.perp(d)); if (n.y < 0) n = G.mul(n, -1);   // towards the feet: the film's lower edge (head up)
  const below = p => G.dot(G.sub(p, tR), n);
  return { R: below(lR) * mmpp(), L: below(lL) * mmpp() };
}
function lldText() {
  const m = lldMm(); if (!m) return ''; const diff = m.R - m.L;
  return Math.abs(diff) < 1 ? 'Leg lengths equal (within 1 mm).' : `${diff > 0 ? 'Right' : 'Left'} leg longer by ${Math.abs(diff).toFixed(0)} mm (lesser trochanters against the teardrop line).`;
}

// ---------------------------------------------------------------- the fine-move pad: 0.5 mm a tap, held it keeps moving
function hidePad() { $('pad').hidden = true; $('moveB').classList.remove('on'); $('zoom').hidden = !Q.img; }
for (const b of document.querySelectorAll('#pad [data-d]')) {
  const [dx, dy] = b.dataset.d.split(',').map(Number); let t0 = 0, t1 = 0;
  const stop = () => { clearTimeout(t0); clearInterval(t1); t0 = t1 = 0; };
  b.addEventListener('pointerdown', e => { e.preventDefault(); if (!Q.items[Q.sel]) return; snap(); nudge(dx, dy); t0 = setTimeout(() => { t1 = setInterval(() => nudge(dx, dy), 80); }, 380); });
  for (const ev of ['pointerup', 'pointerleave', 'pointercancel']) b.addEventListener(ev, stop);
}
$('padX').onclick = hidePad;

// ---------------------------------------------------------------- PFNA (q6): the nail's blade, the head, the tip-apex distance
// The blade lies on the nail's blade axis (bladeAxis: p the hole centre on the nail axis, dir up and medial, both in the nail's own
// frame), its outer end slide mm along that axis from p (negative: lateral of the hole). It starts 8 mm outside the nail's lateral
// wall; the user drags it to the lateral cortex. The tip-apex distance on this film: blade tip to the apex, the point where a line
// through the head's centre parallel to the blade meets the head's edge (Baumgaertner's apex, with the blade standing in for the
// neck axis). It is the AP part of the TAD only; the lateral film's part is not measured on this page.
const SLIDE_MIN = -60, SLIDE_MAX = 0, TAD_AIM_MM = 10;
const HEAD_STEPS = ['Tap a point on the edge of the femoral head.', 'Tap a second point on the head\'s edge, a quarter of the way round.', 'Tap a third point on the head\'s edge.'];
const bladeFamOf = it => { const f = Q.fam[it.fam]; return f && f.blades ? Q.fam[f.blades] || null : null; };
const bladeRec = it => { const b = it && it.blade ? bladeFamOf(it) : null; return b ? b.sizes.find(z => z.size === it.blade) || null : null; };
function defSlide(it) {   // the outer end 8 mm (along the blade) outside the nail's lateral wall
  const z = sizeRec(it), gm = geomOf(it), ax = gm && gm.bladeAxis; if (!z || !ax) return -18;
  return -((z.proxDiaMm || 16) / 2 / Math.abs(ax.dir[0]) + 8);   // along the blade from the hole to the wall (Fable q6 P1)
}
const slideOf = it => (Number.isFinite(it.slide) ? it.slide : defSlide(it));
function bladeFrame(it) {
  const gm = geomOf(it), ax = gm && gm.bladeAxis; if (!ax || !bladeRec(it)) return null;
  const d = ax.dir, s = slideOf(it); return { o: [ax.p[0] + d[0] * s, ax.p[1] + d[1] * s], d, n: [-d[1], d[0]] };
}
function bladeRingsQ(it) {
  const b = bladeRec(it), fr = b && bladeFrame(it), v = b && b.views && b.views.AP; if (!fr || !v) return null;
  return v.rings.map(r => r.map(([x, y]) => [fr.o[0] + x * fr.n[0] + y * fr.d[0], fr.o[1] + x * fr.n[1] + y * fr.d[1]]));
}
function bladeTipL(it) { const b = bladeRec(it), fr = b && bladeFrame(it); return fr ? [fr.o[0] + fr.d[0] * b.lengthMm, fr.o[1] + fr.d[1] * b.lengthMm] : null; }
function bladeU(it) {   // the blade's direction on the image (unit, towards its tip)
  const fr = bladeFrame(it); if (!fr || !mmpp()) return null; const X = xf(it);
  return G.unit(G.sub(X([fr.o[0] + fr.d[0] * 10, fr.o[1] + fr.d[1] * 10]), X(fr.o)));
}
function bladeHit(ip) {
  const tol = 10 / Q.view.s;
  for (const i of Q.items.map((_, i) => i).sort((a, b) => (b === Q.sel) - (a === Q.sel))) {
    const it = Q.items[i], br = kindOf(it) === 'nail' && bladeRingsQ(it); if (!br) continue;
    const X = xf(it), poly = br[0].map(X);
    // the blade where it lies outside the nail: a tap on the nail's own body still drags the nail
    const gm = geomOf(it), np = gm.rings[0].map(X);
    if ((inPoly(ip, poly) || nearPoly(ip, poly, tol)) && !inPoly(ip, np)) return i;
  }
  return -1;
}
// a dragged point can make any circle: only a femoral head (15-40 mm radius) counts (Fable q6 P2)
const headC = side => { const h = Q.hd && Q.hd[side], c = h && h.length === 3 && mmpp() ? G.circle3(h[0], h[1], h[2]) : null; return c && c.r * mmpp() >= 15 && c.r * mmpp() <= 40 ? c : null; };
const angleWord = f => (f.angleAssumed ? `angle assumed (${f.maker} gives none)` : `angle from ${f.maker}'s catalogue`);
function armHead() { if (!mmpp()) return; Q.tool = 'head'; Q.tmp = []; msg(`The ${SIDE[Q.side]} femoral head (the side button at the top). ${HEAD_STEPS[0]}`); syncUI(); draw(); }
// the blade on this film against the head: tip, apex, the tip-apex distance (mm), the tip's third of the head, in the head or not
function pfnaInfo(it) {
  const tl = it && bladeTipL(it); if (!tl || !mmpp()) return null;
  const X = xf(it), tip = X(tl), u = bladeU(it), out = { tip, u };
  const hc = headC(it.side); if (!hc) return out;
  const C = G.P(hc.x, hc.y), apex = G.add(C, G.mul(u, hc.r)); let n = G.perp(u); if (n.y > 0) n = G.mul(n, -1);   // n: towards the top of the film
  const off = G.dot(G.sub(tip, C), n) / hc.r;
  return { ...out, C, r: hc.r, apex, tad: G.dist(tip, apex) * mmpp(), inHead: G.dist(tip, C) <= hc.r, past: G.dot(G.sub(tip, apex), u) > 0,
    pos: off > 1 / 3 ? 'upper third' : off < -1 / 3 ? 'lower third' : 'middle third', headMm: 2 * hc.r * mmpp() };
}
// the longest blade that leaves at least TAD_AIM_MM from its tip to the apex on this film, the outer end where it is now
function fitBlade(it) {
  const bf = bladeFamOf(it); if (!bf || !headC(it.side)) return null; const keep = it.blade; let best = null;
  for (const z of sizesOf(bf)) { it.blade = z.size; const i = pfnaInfo(it); if (i && i.inHead && !i.past && i.tad >= TAD_AIM_MM) best = z.size; }
  it.blade = keep; return best;
}
// a long nail copied to the other side: that side's nail of the same diameter and length, else the nearest diameter that side
// has at that length (Chunli lists 11 mm long left only and 12 mm right only), said in the message
function sideNail(f, z, to) {
  if (!z || !z.side) return { size: z ? z.size : '' };
  const same = f.sizes.find(q => q.side === to && q.diameterMm === z.diameterMm && q.lengthMm === z.lengthMm); if (same) return { size: same.size };
  const at = f.sizes.filter(q => q.side === to && q.lengthMm === z.lengthMm); if (!at.length) return { size: z.size, note: `${f.label} has no ${SIDE[to]} long nail of ${z.lengthMm} mm. ` };
  const dd = q => Math.abs(q.diameterMm - z.diameterMm), q = at.reduce((a, b) => (dd(b) < dd(a) || (dd(b) === dd(a) && b.diameterMm < a.diameterMm) ? b : a));   // a tie: the thinner
  return { size: q.size, note: `No ${z.diameterMm} mm ${SIDE[to]} long nail in ${f.maker}'s list: ${q.diameterMm} mm put instead. ` };
}
const fracWord = s => (Q.joint === 'pfna' && Q.frac ? (s === Q.frac ? 'fracture' : 'normal side') : '');
function pfnaText(it) {
  if (!it || kindOf(it) !== 'nail') return '';
  const b = bladeRec(it), i = pfnaInfo(it), z = sizeRec(it);
  const head = `Nail ${z.label}, ${z.ccdDeg}°${Q.fam[it.fam].angleAssumed ? ' (assumed)' : ''}${b ? `, blade ${b.lengthMm} mm` : ''}.`;
  if (!i || !i.apex) return `${head} Tap the femoral head (Add, or Measure) for the tip-apex distance.`;
  if (i.past || !i.inHead) return `${head} The blade tip is outside the head on this film: shorter blade, or move it.`;
  return `${head} Tip-apex on this film ${i.tad.toFixed(0)} mm (aim: the AP and lateral parts together under 25 mm); tip in the ${i.pos} of the head.`;
}
function nailScene(c, T, k, it, gm, X, col, line) {
  const ax = gm.bladeAxis, s1 = T(G.P(0, 0)), s2 = T(G.P(1, 0)), pxs = (s2.x - s1.x) / mmpp();   // screen px per mm
  for (const h of gm.distalHoles || []) { const s = T(X(h.c)); c.beginPath(); c.arc(s.x, s.y, h.d / 2 * pxs, 0, 7); c.lineWidth = 1.2 * k; c.strokeStyle = col; c.stroke(); }
  if (ax) { c.strokeStyle = col; c.lineWidth = 1 * k; c.setLineDash([3 * k, 4 * k]); line(X([ax.p[0] - ax.dir[0] * 30, ax.p[1] - ax.dir[1] * 30]), X([ax.p[0] + ax.dir[0] * 130, ax.p[1] + ax.dir[1] * 130])); c.setLineDash([]); }
  const i = pfnaInfo(it); if (!i || !i.apex) return;
  c.strokeStyle = '#ff9de2'; c.lineWidth = 1.6 * k; line(i.tip, i.apex);
  const a = T(i.apex); c.beginPath(); c.arc(a.x, a.y, 3.5 * k, 0, 7); c.fillStyle = '#ff9de2'; c.fill();
}
function pfnaSheet() {
  const nf = startFam('nail');
  if (!nf) { msg('No nail range is open to your account.'); return; }
  const fr = Q.frac || Q.side, on = Q.pOn || 'frac';
  sheet(`<h2>PFNA: what are we templating?</h2>
    <label class="f">Fracture side</label><div class="choice"><button data-fr="R" aria-pressed="${fr === 'R'}">Right</button><button data-fr="L" aria-pressed="${fr === 'L'}">Left</button></div>
    <label class="f">Template on</label><div class="choice wrap"><button data-on="frac" aria-pressed="${on === 'frac'}">The fracture side</button><button data-on="other" aria-pressed="${on === 'other'}">The normal side (${SIDE[other(fr)]}), then copy</button><button data-on="both" aria-pressed="${on === 'both'}">Both sides</button></div>
    <p class="note">${on === 'other' ? `Size the nail on the normal ${SIDE[other(fr)]} hip, then Copy ⇄ puts the same nail (a long nail: the ${SIDE[fr]} one of that size, or the nearest ${SIDE[fr]} size the maker lists) and blade on the ${SIDE[fr]} fracture side.` : on === 'both' ? `Template the ${SIDE[fr]} fracture side first; then the ${SIDE[other(fr)]} button at the top and Add, or Copy ⇄.` : `Template the ${SIDE[fr]} fracture side.`}</p>
    <label class="f">Nail</label><select id="c1">${opts('nail', nf.id)}</select>
    <p class="warn">${esc(nf.label)}: a generic outline, not ${esc(nf.maker)}'s drawing (shape of the top from a DePuy Synthes TFN template; sizes from ${esc(nf.maker)}'s list; ${esc(angleWord(nf))}). Check it against the real nail.</p>
    ${Q.orientHint && !Q.mirrored ? `<p class="warn">The ${esc(Q.orient)} suggest${/, /.test(Q.orient) ? '' : 's'} this film is mirrored (the patient's right on the image right), if it shows both hips. <button class="go alt" id="setMir">Set the film as mirrored</button></p>` : Q.orient ? `<p class="note">Film: ${esc(Q.orient)}; ${Q.mirrored ? 'set as mirrored' : 'standard (the patient\'s right on the image left)'}.</p>` : ''}
    ${Q.cal && Q.cal.method === 'ct' ? '<p class="note">Scale: the CT scanner\'s, from the scout\'s header (no ball).</p>' : ballField('sBallD')}
    <p class="note">A long nail needs a film of the whole femur, calibrated by its ball.</p>
    <button class="go" id="goPlace">Tap the trochanter tip on the X-ray</button>`, card => {
    card.querySelectorAll('[data-fr]').forEach(b => b.onclick = () => { Q.frac = b.dataset.fr; Q.pOn = on; pfnaSheet(); });
    card.querySelectorAll('[data-on]').forEach(b => b.onclick = () => { Q.frac = fr; Q.pOn = b.dataset.on; pfnaSheet(); });
    card.querySelector('#c1').onchange = e => { Q.choice.nail = e.target.value; Q.frac = fr; Q.pOn = on; pfnaSheet(); };
    const sm = card.querySelector('#setMir'); if (sm) sm.onclick = () => { if (Q.items.length) snap(); Q.mirrored = true; Q.orient = 'set by hand: mirrored film'; Q.orientHint = false; reflip(); syncUI(); draw(); pfnaSheet(); };
    const bd = card.querySelector('#sBallD'); if (bd) bd.onchange = e => setBallD(e.target.value);
    card.querySelector('#goPlace').onclick = () => { Q.choice.nail = card.querySelector('#c1').value; Q.frac = fr; Q.pOn = on;
      // both sides: the first nail on the fracture side, a later one on the side picked at the top
      Q.side = on === 'other' ? other(fr) : on === 'both' && Q.items.some(it => kindOf(it) === 'nail') ? Q.side : fr; syncUI(); closeSheet(); armPlace(); };
  });
}
function pfnaAddSheet() {
  const it = Q.items.find(x => x.side === Q.side && kindOf(x) === 'nail') || Q.items[Q.sel], bf = it && bladeFamOf(it), fit = it && fitBlade(it);
  const fitTad = fit ? (() => { const keep = it.blade; it.blade = fit; const t = pfnaInfo(it).tad; it.blade = keep; return t; })() : 0;
  sheet(`<h2>PFNA ${SIDE[Q.side]}</h2>
    ${bf ? `<label class="f">Blade length</label><div class="menu">${sizesOf(bf).map(z => `<button data-b="${esc(z.size)}"${z.size === it.blade ? ' aria-pressed="true"' : ''}>${esc(z.label)}${z.size === it.blade ? ' ✓' : ''}${z.size === fit ? ` (fits: ${fitTad.toFixed(0)} mm from the apex)` : ''}</button>`).join('')}</div>` : ''}
    <div class="menu"><button id="pHead">${headC(Q.side) ? `Tap the ${SIDE[Q.side]} femoral head again` : `${SIDE[Q.side]} femoral head: three points round its edge (tip-apex distance)`}</button>
    ${fit ? `<button id="pFit">Blade ${esc(fit)} mm: the longest that leaves at least 10 mm to the apex here (${fitTad.toFixed(0)} mm)</button>` : ''}
    <button id="pNew">Place the nail again</button><button id="aSys">Change the nail or the side</button></div>
    <p class="note">The blade's outer end stays where you dragged it; a new length moves its tip. The tip-apex distance here is this film's part only.</p>
    <button class="go alt" id="aClose">Close</button>`, card => {
    card.querySelectorAll('[data-b]').forEach(x => x.onclick = () => { snap(); it.blade = x.dataset.b; closeSheet(); msg(pfnaText(it)); syncUI(); draw(); });
    card.querySelector('#pHead').onclick = () => { closeSheet(); armHead(); };
    const pf = card.querySelector('#pFit'); if (pf) pf.onclick = () => { snap(); it.blade = fit; closeSheet(); msg(pfnaText(it)); syncUI(); draw(); };
    card.querySelector('#pNew').onclick = () => { closeSheet(); armPlace(['nail']); };
    card.querySelector('#aSys').onclick = startSheet;
    card.querySelector('#aClose').onclick = closeSheet;
  });
}
function pfnaRows(sides, per, rows, systems, ready, warn) {
  const its = sides.map(s => per[s].nail); if (!its.some(Boolean)) return;
  if (Q.frac) rows.unshift(['Side', sides.map(s => (s === Q.frac ? 'fracture' : 'normal'))]);
  if (Q.frac && !(per[Q.frac] && per[Q.frac].nail)) warn.push(`No nail on the fracture side (${SIDE[Q.frac]}) yet: Copy ⇄ puts the normal side's nail and blade there.`);
  if (Q.frac && per.R && per.L && per.R.nail && per.L.nail && (per.R.nail.size.replace(/[RL]$/, '') !== per.L.nail.size.replace(/[RL]$/, '') || per.R.nail.blade !== per.L.nail.blade)) warn.push(`The two sides differ: the plan is the fracture side's (${SIDE[Q.frac]}).`);
  rows.push(['Angle', its.map(it => (it ? `${sizeRec(it).ccdDeg}°${Q.fam[it.fam].angleAssumed ? ' (assumed)' : ''}` : '-'))]);
  rows.push(['Blade', its.map(it => (it && bladeRec(it) ? `${bladeRec(it).lengthMm} mm` : '-'))]);
  const info = its.map(it => (it ? pfnaInfo(it) : null));
  if (info.some(i => i && i.apex)) {
    rows.push(['Tip-apex, this film', info.map(i => (!i || !i.apex ? '-' : i.past || !i.inHead ? 'tip outside the head' : `${i.tad.toFixed(0)} mm`))]);
    rows.push(['Tip in the head', info.map(i => (!i || !i.apex || i.past || !i.inHead ? '-' : i.pos))]);
    if (info.some(i => i && i.apex && (i.past || !i.inHead))) warn.push('The blade tip is outside the femoral head on this film: choose a shorter blade or move it.');
  }
  // have ready: the plan side's only, the fracture side once it has a nail (the normal side is the template), else the side templated
  for (let i = ready.length - 1; i >= 0; i--) if (/^Nail\b/.test(ready[i])) ready.splice(i, 1);
  const ps = Q.frac && per[Q.frac] && per[Q.frac].nail ? Q.frac : sides.find(s => per[s] && per[s].nail), pit = ps && per[ps].nail;
  const nrm = Q.frac && sides.find(s => s !== Q.frac && per[s] && per[s].nail), nit = nrm && per[nrm].nail;
  const sw = nit ? sideNail(Q.fam[nit.fam], sizeRec(nit), Q.frac) : null;   // the normal side's nail as the fracture side's
  if (sw && sw.note && (ps !== Q.frac || pit.size === sw.size)) warn.push(`${sw.note.trim()} (Copy from the ${SIDE[nrm]} side.)`);
  if (pit) {
    // the plan is the fracture side's: its own nail, else the normal side's nail turned into the fracture side's (a long nail's side)
    const rs = Q.frac && ps !== Q.frac && sw ? sw.size : pit.size, rside = Q.frac || ps;
    ready.push(`Nail${Q.frac ? ` (${SIDE[Q.frac]}, fracture${ps !== Q.frac ? `, from the ${SIDE[ps]} template` : ''})` : ''} ${near(Q.fam[pit.fam], rs, rside).join(', ')}`);
    const bf = bladeFamOf(pit), b = bladeRec(pit);
    if (bf && b) { const l = sizesOf(bf), j = l.findIndex(z => z.size === b.size); ready.push(`Blade ${l.slice(Math.max(0, j - 1), j + 2).map(z => z.size).join(', ')} mm`); }
  }
  for (const id of [...new Set(its.filter(Boolean).map(it => (bladeFamOf(it) || {}).id).filter(Boolean))]) systems.push(`Blade: ${Q.fam[id].label}`);
  const fams = [...new Set(its.filter(Boolean).map(it => Q.fam[it.fam]))];
  for (const f of fams) if (f.verified === false) warn.push(`${f.label}: generic outline, not ${f.maker}'s drawing (the top's shape from a DePuy Synthes TFN template; sizes from ${f.maker}'s list; ${angleWord(f)}). Check it against the real nail; measure the canal with the Length tool.`);
  warn.push('Tip-apex distance: this film\'s part only. The full TAD adds the lateral film\'s part.');
}

// ---------------------------------------------------------------- stems (q5): CCK, hinge and tumour parts
// Sami, 31 Oct 2026: "I do not see a way to add stems in CCK, HK". A stem extension hangs from its part's stem port (the full
// planner's stemRings: stem x across, y along its axis from the junction). Only the stems the maker's sheet draws for that
// part size (stemSizes) are offered; none is chosen for the user. A view without a stem port draws no stem (said on the card).
const stemFamOf = it => { const f = Q.fam[it.fam]; return f && f.stems ? Q.fam[f.stems] || null : null; };
const stemList = it => { const sf = stemFamOf(it), z = sizeRec(it); return sf && z && Array.isArray(z.stemSizes) ? sf.sizes.filter(q => z.stemSizes.includes(q.size)) : []; };
const stemRec = it => (it.stem ? stemList(it).find(q => q.size === it.stem) || null : null);
function stemRingsQ(it, gm) {
  const s = stemRec(it); if (!s || !gm || !gm.stemPort) return null;
  const v = (s.views && (s.views[F().fv === 'lat' ? 'LAT' : 'AP'] || s.views.AP)); if (!v || !v.rings) return null;
  const p = gm.stemPort.p, d = gm.stemPort.dir, n = [-d[1], d[0]];
  return v.rings.map(r => r.map(([x, y]) => [p[0] + x * n[0] + y * d[0], p[1] + x * n[1] + y * d[1]]));
}
const stemWord = s => `${s.label || s.size}`;
function stemSheet() {
  const it = Q.items[Q.sel]; if (!it) return;
  const list = stemList(it), f = Q.fam[it.fam], c = Number.isFinite(it.c) ? it.c : null;
  const shown = c === null ? list : list.filter(q => q.collarMm === c);   // a tumour body's chosen length fixes the collar
  sheet(`<h2>Stem on the ${esc(partName(it))} ${it.side}</h2>
    ${list.length ? '' : `<p class="warn">${esc(f.label)} size ${esc(it.size)}: no stem drawn for it on the maker's sheet.</p>`}
    ${c !== null ? `<p class="note">Stems with a ${c} mm collar (the length chosen); choose another length to see the others.</p>` : ''}
    <div class="menu"><button data-s="">No stem</button>${shown.map(q => `<button data-s="${esc(q.size)}"${q.size === it.stem ? ' aria-pressed="true"' : ''}>${esc(stemWord(q))}${q.size === it.stem ? ' ✓' : ''}</button>`).join('')}</div>
    <p class="note">Check the diameter against the canal with the Length tool. The stem is drawn at the part's junction on this film${geomOf(it) && geomOf(it).stemPort ? '' : ' (this view has no junction drawn: the stem is listed, not drawn)'}.</p>
    <button class="go alt" id="sClose">Close</button>`, card => {
    card.querySelectorAll('[data-s]').forEach(b => b.onclick = () => {
      snap(); it.stem = b.dataset.s || null; const s = stemRec(it); if (s && Number.isFinite(s.collarMm) && isTumBody(f)) it.c = s.collarMm;
      shareSize(it); closeSheet(); msg(it.stem ? `Stem ${stemWord(stemRec(it))} on the ${partName(it)}.` : `No stem on the ${partName(it)}.`); syncUI(); draw();
    });
    card.querySelector('#sClose').onclick = closeSheet;
  });
}

// ---------------------------------------------------------------- knee types (q5, as the full planner's r43 KNEE_TYPES)
const KNEE_TYPES = [
  { id: 'primary', label: 'Primary' },
  { id: 'cck', label: 'CCK (constrained condylar)', fem: 'chunli-cck-femur', tib: 'chunli-cck-tibia' },
  { id: 'ldk', label: 'Hinge (LDK XR)', fem: 'ldk-xr-femur', tib: 'ldk-xr-tibia' }];
const KT_PAIR = new Set(KNEE_TYPES.flatMap(k => (k.fem ? [k.fem, k.tib] : [])));
const ktHeld = k => (k.fem ? !!(Q.fam[k.fem] && Q.fam[k.tib]) : true);
const ktNow = () => { const id = Q.choice['tka-femur']; return (KNEE_TYPES.find(k => k.fem === id) || KNEE_TYPES[0]).id; };

// ---------------------------------------------------------------- tumour sets (q5)
// Sami, 31 Oct 2026: "ADD TUMORS", "All tumour sets". The full planner's rules (r22-r44), ported: the resection comes only from
// the surgeon's MRI or CT extent and margin; the stack is reconstructionMm (body + 2.25 mm gap at each taper junction +
// extension) + the stem's collar, measured from the joint line (DFR, PTR) or from the 0 head centre's level on the body axis
// (PFR, PHR; tumOrigin), against the GT / tuberosity tip tapped on the film. Every body length and collar within 5 mm of the
// plan is listed, closest first; none is chosen for the surgeon. Placed, not sized. Diaphyseal: measurement only (r39).
const TUM_SETS = {
  dfr: { label: 'Distal femur (DFR)', body: 'chunli-dfr-femur', partner: 'chunli-dfr-tibia', ref: 'joint', steps: ['joint'] },
  ptr: { label: 'Proximal tibia (PTR)', body: 'chunli-ptr-tibia', partner: 'chunli-ptr-femur', ref: 'joint', steps: ['joint'] },
  pfr: { label: 'Proximal femur (PFR)', body: 'chunli-pfr', ref: 'gt', steps: ['head', 'gt'] },
  phr: { label: 'Proximal humerus (PHR)', body: 'chunli-phr', ref: 'gt', steps: ['head', 'gt'] },
  tf: { label: 'Total femur (PFR + DFR)', body: 'chunli-pfr', body2: 'chunli-dfr-femur', partner: 'chunli-dfr-tibia', ref: 'gt', steps: ['head', 'gt', 'joint'] },
  dia: { label: 'Diaphyseal (segmental defect)', steps: ['ref', 'ax1', 'ax2'] },
};
const tumSetOk = k => { const s = TUM_SETS[k]; return k === 'dia' ? Object.values(Q.fam).some(f => f.range === 'ao-tumour' || f.rangeOwn === 'ao-tumour') : [s.body, s.body2, s.partner].filter(Boolean).every(id => Q.fam[id]); };
const isTumBody = f => !!(f && f.sizes && f.sizes[0] && Number.isFinite(f.sizes[0].reconstructionMm));
const isGtBody = f => !!(f && (f.tumour === 'pfr' || f.tumour === 'phr'));
const tumBodyLen = z => (Number.isFinite(z.bodyLengthMm) ? z.bodyLengthMm : z.reconstructionMm - (z.extensionMm || 0));
const fmm = x => (Number.isFinite(x) ? String(Math.round(x * 100) / 100) : '-');
const tumParts = (z, c) => { const g = Number.isFinite(z.junctionGapMm) ? ` + gap ${z.junctionGapMm}` : '';
  return `${fmm(tumBodyLen(z))}${g}${z.extensionMm ? ` + extension ${z.extensionMm}${g}` : ''} + collar ${c === null ? 'c' : c}`; };
const collarsOf = it => { const sf = stemFamOf(it); return sf ? [...new Set(sf.sizes.map(q => q.collarMm).filter(Number.isFinite))].sort((a, b) => a - b) : []; };
const tumCollar = it => { const s = stemRec(it); return s && Number.isFinite(s.collarMm) ? s.collarMm : Number.isFinite(it.c) ? it.c : null; };
const bodyNo = size => String(size).split('-')[0];
function partName(it) {
  const f = Q.fam[it.fam]; if (!f) return '';
  if (f.tumour === 'pfr') return 'PFR'; if (f.tumour === 'phr') return 'PHR';
  if (f.id === 'chunli-dfr-femur') return 'DFR'; if (f.id === 'chunli-ptr-tibia') return 'PTR body';
  if (f.id === 'chunli-dfr-tibia') return 'hinge tibia'; if (f.id === 'chunli-ptr-femur') return 'hinged femur';
  return (KIND[f.kind] || f.kind).toLowerCase();
}
// the plan typed on the tumour card (F().tp): {set, src, ext, mg, ext2, mg2, near, far, body, head}; role 'p' the proximal
// (or only) body, 'd' the distal body of a total femur
function tumPlanQ(role) {
  const t = F().tp || {}, src = t.src === 'MRI' || t.src === 'CT' ? t.src : '', ex = Number(role === 'd' ? t.ext2 : t.ext), mg = Number(role === 'd' ? t.mg2 : t.mg);
  if (!src) return { ok: false, why: 'enter where the tumour extent comes from (MRI or CT): the plain film does not show it' };
  if (!(Number.isFinite(ex) && ex > 0 && ex <= 400)) return { ok: false, src, why: 'enter the tumour extent (mm) measured on the ' + src };
  if (!(Number.isFinite(mg) && mg >= 0 && mg <= 100)) return { ok: false, src, why: 'enter your margin (mm)' };
  return { ok: true, src, extent: ex, margin: mg, resection: ex + mg };
}
// the body's frame on the film: o where lengths start (the joint line on the body axis, or the 0 head centre's level), d along
// the axis into the bone (proximal for a DFR, distal for the others); lengths in mm = image px x mmpp
function tumFrame(it) {
  const gm = geomOf(it), z = sizeRec(it); if (!gm || !gm.stemPort || !z || !Number.isFinite(z.reconstructionMm) || !mmpp()) return null;
  const sp = gm.stemPort, ol = gm.tumOrigin || [sp.p[0] - sp.dir[0] * z.reconstructionMm, sp.p[1] - sp.dir[1] * z.reconstructionMm];
  const X = xf(it), o = X(ol), d = G.unit(G.sub(X([ol[0] + sp.dir[0] * 10, ol[1] + sp.dir[1] * 10]), o));
  return { o, d, at: s => G.add(o, G.mul(d, s / mmpp())) };
}
// mm along the axis: sRef the reference (0 at the joint line; the GT / tuberosity tip for a PFR, PHR), sPlan the planned
// osteotomy, sStack where this stack ends; null when not known
function tumLevels(it, role) {
  const fr = tumFrame(it); if (!fr) return null;
  const f = Q.fam[it.fam], tp = tumPlanQ(role), gt = isGtBody(f) ? it.gt : null, c = tumCollar(it), z = sizeRec(it);
  const sRef = isGtBody(f) ? (gt ? G.dot(G.sub(gt, fr.o), fr.d) * mmpp() : null) : 0;
  return { fr, tp, sRef, sPlan: tp.ok && sRef !== null ? sRef + tp.resection : null, sExt: tp.ok && sRef !== null ? sRef + tp.extent : null, sStack: c !== null ? z.reconstructionMm + c : null, c };
}
function tumOptionsQ(it, sPlan) {   // every length (same body number for a knee body) x collar within 5 mm, closest first
  const f = Q.fam[it.fam], out = [];
  for (const z of f.sizes.filter(q => isGtBody(f) || bodyNo(q.size) === bodyNo(it.size))) for (const c of collarsOf(it)) {
    const tot = z.reconstructionMm + c; if (Math.abs(tot - sPlan) <= 5) out.push({ z, c, tot });
  }
  return out.sort((a, b) => Math.abs(a.tot - sPlan) - Math.abs(b.tot - sPlan) || (a.z.extensionMm || 0) - (b.z.extensionMm || 0));
}
const tumBodies = () => Q.items.filter(it => isTumBody(Q.fam[it.fam]));
const roleOf = it => it.role || 'p';
function tumSheet() {
  const t = F().tp || (F().tp = { set: '', src: '', ext: '', mg: '', ext2: '', mg2: '', near: '', far: '', body: '2', head: '' });
  const sets = Object.keys(TUM_SETS).filter(tumSetOk), set = TUM_SETS[t.set] ? t.set : '';
  if (!sets.length) { msg('No Addis Ortho tumour range is open to your account. Ask Addis Ortho to open it.'); return; }
  const knee = set === 'dfr' || set === 'ptr' || set === 'tf', bodyF = set && TUM_SETS[set].body2 ? Q.fam[TUM_SETS[set].body2] : set && TUM_SETS[set].body ? Q.fam[TUM_SETS[set].body] : null;
  const nos = knee && bodyF ? [...new Set(bodyF.sizes.map(z => bodyNo(z.size)))] : [];
  const num = (id, v, lab) => `<label class="f" for="${id}">${lab}</label><input type="number" inputmode="decimal" id="${id}" min="0" max="400" step="0.5" value="${esc(v)}">`;
  const refWord = set === 'pfr' || set === 'tf' ? 'below the tip of the greater trochanter' : set === 'phr' ? 'below the tip of the greater tuberosity' : set === 'dfr' ? 'above the joint line' : 'below the joint line';
  sheet(`<h2>Tumour</h2>
    <div class="choice wrap">${sets.map(k => `<button data-set="${k}" aria-pressed="${set === k}">${esc(TUM_SETS[k].label)}</button>`).join('')}</div>
    <label class="f">Side</label><div class="choice"><button data-s="R" aria-pressed="${Q.side === 'R'}">Right</button><button data-s="L" aria-pressed="${Q.side === 'L'}">Left</button></div>
    ${nos.length ? `<label class="f">Body size</label><div class="choice">${nos.map(n => `<button data-b="${n}" aria-pressed="${t.body === n}">${n}#</button>`).join('')}</div>` : ''}
    ${set ? `<label class="f">Tumour extent measured on</label><div class="choice"><button data-src="MRI" aria-pressed="${t.src === 'MRI'}">MRI</button><button data-src="CT" aria-pressed="${t.src === 'CT'}">CT</button></div>
      ${set === 'dia' ? num('tNear', t.near, 'Tumour near end (mm from the joint line you will tap)') + num('tFar', t.far, 'Tumour far end (mm from the same joint line)') + num('tMg', t.mg, 'Margin each end (mm)')
        : set === 'tf' ? num('tExt', t.ext, 'Proximal tumour extent (mm below the tip of the greater trochanter)') + num('tMg', t.mg, 'Proximal margin (mm)') + num('tExt2', t.ext2, 'Distal tumour extent (mm above the knee joint line)') + num('tMg2', t.mg2, 'Distal margin (mm)')
        : num('tExt', t.ext, `Tumour extent (mm ${refWord})`) + num('tMg', t.mg, 'Margin (mm)')}
      <p class="note">The plain film does not show the tumour: the extent comes from your MRI or CT only. Quick lists the lengths that fit; it never picks one.</p>` : '<p class="note">Choose the set.</p>'}
    ${set ? `<button class="go" id="tGo">${tumBodies().length || (F().dia && F().dia.ref) ? 'Save the plan values' : `Tap the ${set === 'dia' ? 'joint line' : TUM_SETS[set].steps[0] === 'head' ? (set === 'phr' ? 'humeral head centre' : 'femoral head centre') : 'knee joint line'} on the X-ray`}</button>` : ''}
    ${tumBodies().length || (F().dia && F().dia.ref) ? '<button class="go alt" id="tNew">Place again (new taps)</button>' : ''}
    <button class="go alt" id="tClose">Close</button>`, card => {
    const read = () => { for (const [id, k] of [['tExt', 'ext'], ['tMg', 'mg'], ['tExt2', 'ext2'], ['tMg2', 'mg2'], ['tNear', 'near'], ['tFar', 'far']]) { const e = card.querySelector('#' + id); if (e) t[k] = e.value; } };
    const placed = () => tumBodies().length || !!(F().dia && F().dia.ref);
    card.querySelectorAll('[data-set]').forEach(b => b.onclick = () => {
      if (t.set === b.dataset.set) return;
      if (placed() && !confirm('Change the tumour set? The parts and taps placed are removed.')) return;
      read(); snap(); Q.items = []; Q.sel = -1; F().dia = null; Q.tool = null; Q.tmp = []; t.set = b.dataset.set; syncUI(); draw(); tumSheet();   // taps half done for the old set are dropped (Fable q5 recheck P2)
    });
    card.querySelectorAll('[data-s]').forEach(b => b.onclick = () => { if (placed()) { msg('The side is fixed once parts are placed: Place again for the other side.'); return; } read(); Q.side = b.dataset.s; syncUI(); tumSheet(); });
    card.querySelectorAll('[data-b]').forEach(b => b.onclick = () => { read(); t.body = b.dataset.b; tumSheet(); });
    card.querySelectorAll('[data-src]').forEach(b => b.onclick = () => { read(); t.src = b.dataset.src; tumSheet(); });
    const go = card.querySelector('#tGo'); if (go) go.onclick = () => { snap(); read(); closeSheet(); if (tumBodies().length || (F().dia && F().dia.ref)) { msg(tumSummary()); syncUI(); draw(); } else armTum(); };
    const nw = card.querySelector('#tNew'); if (nw) nw.onclick = () => { read(); closeSheet(); armTum(); };
    card.querySelector('#tClose').onclick = () => { const before = JSON.stringify(t), copy = JSON.parse(before); read(); if (JSON.stringify(t) !== before) { Object.assign(F().tp, copy); snap(); read(); } closeSheet(); syncUI(); draw(); };   // edits kept, Undo brings the old values back
  });
}
const TUM_STEP = { joint: 'Tap the middle of the knee joint line.', head: 'Tap the centre of the head (femoral head for a PFR, humeral head for a PHR).',
  gt: 'Tap the tip of the greater trochanter (PFR) or of the greater tuberosity (PHR): the resection is measured from it.',
  ref: 'Tap the middle of the joint line the MRI/CT extents were measured from.', ax1: 'Tap the middle of the shaft near that joint line.', ax2: 'Tap the middle of the shaft further along the bone (the axis the levels run along).' };
function armTum() {
  if (!sizable() || !mmpp()) { msg(refText() || 'Set the scale first: the tumour lengths are true size.'); return; }
  const set = (F().tp || {}).set; if (!TUM_SETS[set]) { tumSheet(); return; }
  Q.tool = 'tum'; Q.tmp = []; fit(); msg(TUM_STEP[TUM_SETS[set].steps[0]]); syncUI();
}
function tumTap(ip) {
  const t = F().tp, S_ = TUM_SETS[t.set], steps = S_.steps; Q.tmp.push(ip); const n = Q.tmp.length;
  if (n < steps.length) { msg(TUM_STEP[steps[n]]); draw(); return; }
  snap(); const P = Object.fromEntries(steps.map((k, i) => [k, Q.tmp[i]])), side = Q.side; Q.tmp = []; Q.tool = null;
  if (t.set === 'dia') {
    if (G.dist(P.ax1, P.ax2) < 20) { msg('The two shaft points are too close together. ' + TUM_STEP.ref); Q.tool = 'tum'; return; }
    F().dia = { ref: P.ref, a1: P.ax1, a2: P.ax2 }; Q.items = []; msg(tumSummary()); syncUI(); draw(); return;
  }
  Q.items = []; F().dia = null; const out = [];
  const body = (id, at, role) => {   // a tumour body at its reference: knee bodies by their joint line, PFR/PHR by their head centre
    const f = Q.fam[id], list = sizesOf(f), first = isGtBody(f) ? list[0] : list.find(z => bodyNo(z.size) === (t.body || '2')) || list[0];
    const it = newItem(f, side, at, first.size); it.role = role; it.flip = isGtBody(f) ? hipFlip(side) : kneeFlip(); out.push(it); return it;
  };
  if (t.set === 'dfr' || t.set === 'ptr') { body(S_.body, P.joint, 'p'); }
  if (t.set === 'pfr' || t.set === 'phr' || t.set === 'tf') { const b = body(S_.body, P.head, 'p'); b.gt = P.gt; }
  if (t.set === 'tf') body(S_.body2, P.joint, 'd');
  if (S_.partner) {   // the hinge partner at the same joint line (the DFR's tibia a little below it, as a knee tray)
    const pf = Q.fam[S_.partner], at = pf.kind === 'tka-tibia' ? G.P(P.joint.x, P.joint.y + INSERT_START_MM / mmpp()) : P.joint;
    const no = t.body || '2', z = sizesOf(pf).find(q => bodyNo(q.size) === no) || sizesOf(pf)[0];
    const p = newItem(pf, side, at, z.size); p.flip = kneeFlip(); p.role = 'partner'; out.push(p);
  }
  Q.items = out; Q.sel = 0; syncUI(); draw();
  msg('Placed, not sized: turn each body along the shaft with its green ⟳ handle, then choose its length (Add, then Length).');
  tumPartsSheet();
}
// the tumour parts card: length options against the plan, the stem, the PHR head
function tumPartsSheet() {
  const bodies = tumBodies(); if (!bodies.length) { tumSheet(); return; }
  const rows = bodies.map(it => {
    const L = tumLevels(it, roleOf(it)), f = Q.fam[it.fam];
    const plan = !L ? 'scale or junction missing' : !L.tp.ok ? `resection not planned: ${L.tp.why}` : L.sPlan === null ? 'tap the tip of the greater trochanter / tuberosity first' : null;
    const opts = L && L.sPlan !== null ? tumOptionsQ(it, L.sPlan) : [];
    return `<h3>${esc(partName(it))} ${it.side}: ${esc(sizeRec(it).label || it.size)}${L && L.c !== null ? ` + collar ${L.c}` : ''}</h3>
      ${plan ? `<p class="warn">${esc(plan)}</p>` : `<p class="note">Planned osteotomy ${fmm(L.sPlan)} mm from ${isGtBody(f) ? 'this body\'s head centre level' : 'the joint line'}; every length and collar within 5 mm, closest first:</p>`}
      ${opts.length ? `<div class="menu">${opts.map(o => `<button data-it="${Q.items.indexOf(it)}" data-z="${esc(o.z.size)}" data-c="${o.c}">${esc(o.z.label || o.z.size)} + collar ${o.c} = ${fmm(o.tot)} mm (${o.tot - L.sPlan >= 0 ? '+' : ''}${fmm(o.tot - L.sPlan)})</button>`).join('')}</div>` : plan ? '' : '<p class="warn">No length of this body within 5 mm of the plan: check the extent and margin, or the body size.</p>'}
      <button class="go alt" data-stem="${Q.items.indexOf(it)}">Stem${stemRec(it) ? `: ${esc(stemWord(stemRec(it)))}` : ' (choose)'}</button>
      ${f.humHeads ? `<label class="f">Head (mm)</label><div class="choice">${f.humHeads.map(h => `<button data-head="${h.d}" aria-pressed="${String(it.head) === String(h.d)}">${h.d}</button>`).join('')}</div>` : ''}`;
  }).join('');
  sheet(`<h2>Tumour parts</h2>${rows}<button class="go alt" id="pPlan">Plan values (extent, margin)</button><button class="go alt" id="pClose">Close</button>`, card => {
    card.querySelectorAll('[data-z]').forEach(b => b.onclick = () => {
      const it = Q.items[+b.dataset.it]; snap(); it.size = b.dataset.z; it.c = +b.dataset.c; if (stemRec(it) && stemRec(it).collarMm !== it.c) it.stem = null; else if (it.stem && !stemRec(it)) it.stem = null;
      syncUI(); draw(); tumPartsSheet();
    });
    card.querySelectorAll('[data-stem]').forEach(b => b.onclick = () => { Q.sel = +b.dataset.stem; Q.side = Q.items[Q.sel].side; stemSheet(); });
    card.querySelectorAll('[data-head]').forEach(b => b.onclick = () => { const it = Q.items.find(x => Q.fam[x.fam].humHeads); snap(); it.head = b.dataset.head; tumPartsSheet(); });
    card.querySelector('#pPlan').onclick = tumSheet;
    card.querySelector('#pClose').onclick = () => { closeSheet(); msg(tumSummary()); };
  });
}
function diaLevels() {
  const D = F().dia, t = F().tp || {}; if (!D || !D.ref || !mmpp()) return null;
  let u = G.unit(G.sub(D.a2, D.a1)); if (G.dist(D.a2, D.ref) < G.dist(D.a1, D.ref)) u = G.mul(u, -1);   // away from the joint line (Fable q5 P2)
  const near = Number(t.near), far = Number(t.far), mg = Number(t.mg), src = t.src === 'MRI' || t.src === 'CT';
  const ok = src && Number.isFinite(near) && Number.isFinite(far) && far > near && near >= 0 && Number.isFinite(mg) && mg >= 0;
  const at = s => G.add(D.ref, G.mul(u, s / mmpp()));
  return { u, at, ok, near, far, mg, s1: ok ? near - mg : null, s2: ok ? far + mg : null };
}
const DIA_TYPES = { III: [40, 165], VI: [50, 310] };   // Chunli, Segmental Bone Defect Prosthesis, slides 18-19 (as app.js DIA_TYPES)
function tumRowsQ() {
  const R = [], t = F().tp || {}, set = TUM_SETS[t.set]; if (!set) return R;
  const b0 = tumBodies()[0]; R.push([set.label, `${SIDE[b0 ? b0.side : Q.side]} side`]);   // the placed parts' side, never the header button's (Fable q5 recheck P3)
  if (t.set === 'dia') {
    const L = diaLevels();
    if (!L) { R.push(['Levels', 'not measured: tap the joint line and the shaft axis', 'warn']); return R; }
    if (!L.ok) { R.push(['Resection', 'not planned: enter MRI or CT, the near and far ends (far beyond near) and the margin', 'warn']); return R; }
    const D = L.s2 - L.s1, ty = Object.entries(DIA_TYPES).filter(([, r]) => D >= r[0] && D <= r[1]).map(([k]) => 'Type ' + k);
    R.push(['Tumour', `${fmm(L.near)} to ${fmm(L.far)} mm from the joint line tapped, from the ${t.src} (entered by the surgeon)`]);
    R.push(['Osteotomies', `${fmm(L.s1)} and ${fmm(L.s2)} mm from that joint line, along the shaft axis tapped (margin ${fmm(L.mg)} mm each end)`, L.s1 < 0 ? 'warn' : '']);
    R.push(['Defect length', `${fmm(D)} mm`]);
    R.push(['Chunli range', ty.length ? `${ty.join(' and ')} (Type III 40-165 mm, Type VI 50-310 mm)` : 'outside both Chunli ranges (Type III 40-165 mm, Type VI 50-310 mm): ask Chunli', ty.length ? '' : 'warn']);
    R.push(['Not a plan of parts', 'Measurements for a Chunli order request: the page does not choose the stems, the cement mantle or the rounding; canal widths come from the CT. The full planner measures the bone left each side and the stem tips.', 'warn']);
    return R;
  }
  for (const it of tumBodies()) {
    const f = Q.fam[it.fam], z = sizeRec(it), L = tumLevels(it, roleOf(it)), from = isGtBody(f) ? (f.tumour === 'phr' ? 'the tip of the greater tuberosity' : 'the tip of the greater trochanter') : 'the joint line';
    const dir = f.id === 'chunli-dfr-femur' ? 'above' : 'below';
    R.push([`${partName(it)} ${it.side}`, `${f.label}, ${z.label || z.size}`]);
    if (!L) { R.push(['Lengths', 'not measured (no scale or no junction on this film)', 'warn']); continue; }
    if (!L.tp.ok) R.push(['Resection', `not planned: ${L.tp.why}`, 'warn']);
    else { R.push(['Tumour extent', `${fmm(L.tp.extent)} mm ${dir} ${from}, from the ${L.tp.src} (entered by the surgeon)`]); R.push(['Margin', `${fmm(L.tp.margin)} mm (your choice)`]); R.push(['Resection length', `${fmm(L.tp.resection)} mm ${dir} ${from}`]); }
    if (isGtBody(f)) R.push(['Reference tip', L.sRef === null ? `not tapped: tap ${from}` : `${fmm(Math.abs(L.sRef))} mm ${L.sRef >= 0 ? 'below' : 'above'} this body's head centre level, along its axis (as placed)`, L.sRef === null ? 'warn' : '']);
    if (L.c === null) R.push(['Reconstruction', `${fmm(z.reconstructionMm)} mm without the stem collar: choose a length option or a stem (${tumParts(z, null)})`, 'warn']);
    else {
      const d = L.sPlan !== null ? L.sStack - L.sPlan : null;
      R.push(['Reconstruction', `${tumParts(z, L.c)} = ${fmm(L.sStack)} mm from ${isGtBody(f) ? 'its head centre level' : 'the joint line'}${d !== null ? ` (${d >= 0 ? '+' : ''}${fmm(d)} mm against the plan)` : ''}`, d !== null && Math.abs(d) > 5 ? 'warn' : '']);
      if (isGtBody(f) && L.sRef !== null) R.push(['Osteotomy of this stack', `${fmm(L.sStack - L.sRef)} mm below ${from}`]);
    }
    const s = stemRec(it); R.push(['Stem', s ? `${stemWord(s)}: ${fmm(s.diameterMm)} mm, ${fmm((s.drawnLengthMm || 0) - (L.c || 0))} mm beyond its collar as drawn; check the canal with the Length tool` : 'not chosen: choose the universal stem', s ? '' : 'warn']);
    if (f.humHeads) R.push(['Head', it.head ? `${it.head} mm humeral head (which fits the tumour upper end is not confirmed by Chunli)` : 'not chosen', 'warn']);
    if (Number.isFinite(z.junctionGapMm)) R.push(['Taper junctions', `${z.junctions} junction${z.junctions > 1 ? 's' : ''} taken as ${z.junctionGapMm} mm open each after impaction${f.tumour === 'phr' ? " (Chunli's Ø24 figure, taken for this Ø22 system: not confirmed)" : ' (Chunli)'}: the stack can sit up to about ${fmm(z.junctions * 0.25)} mm longer or shorter; check with the trial`, 'warn']);
    for (const a of f.assumptions || []) R.push(['Assumed (not confirmed by Chunli)', a, 'warn']);
  }
  const pt = Q.items.find(it => it.role === 'partner'), bd = tumBodies().find(it => !isGtBody(Q.fam[it.fam]));
  if (pt) { const n1 = parseInt(pt.size, 10), n2 = bd ? parseInt(bd.size, 10) : n1; R.push([partName(pt), `${Q.fam[pt.fam].label}, ${sizeRec(pt).label || pt.size}${bd && n1 !== n2 ? ': a different size number from the body; Chunli lists them in pairs, check with Chunli' : ''}`, bd && n1 !== n2 ? 'warn' : '']); }
  if (t.set === 'tf') {
    const P = tumBodies().find(it => roleOf(it) === 'p'), Dd = tumBodies().find(it => roleOf(it) === 'd'), LP = P && tumLevels(P, 'p'), LD = Dd && tumLevels(Dd, 'd');
    // as the full planner (llTfRows, Fable q5 P1): the body outlines only, never a stem or a collar (no stem enters bone at the
    // connection); up from the DFR's joint line to the PFR's head centre
    if (P && Dd && LP && LD) {
      const hc = xf(P)(geomOf(P).headM || [0, 0]), jl = LD.fr.o, up = G.unit(G.sub(hc, jl));
      R.push(['Reconstructed femoral length', `${fmm(G.dist(hc, jl) * mmpp())} mm, the PFR's head centre to the DFR's joint line (this film, as placed)`]);
      const pts = it => geomOf(it).rings.flat().map(xf(it)), ext = (ps, hi) => ps.reduce((b, q) => (b === null || (hi ? G.dot(q, up) > G.dot(b, up) : G.dot(q, up) < G.dot(b, up)) ? q : b), null);
      const pb = ext(pts(P), false), db = ext(pts(Dd), true), g = G.dot(G.sub(pb, db), up) * mmpp();
      R.push(['Length the connection must fill', g <= 0 ? `none: the PFR body and the DFR body overlap by ${fmm(-g)} mm (as drawn): shorter parts are needed` : `${fmm(g)} mm between the PFR body's end and the DFR body's top (measured on this film)`, 'warn']);
    }
    R.push(['Connection', 'connection part not confirmed by Chunli: ask for the total femur sheet (no part is drawn or sized for it)', 'warn']);
  }
  R.push(['Placement', 'placed, not sized: drag and turn each part to the bone; the stack lengths are the maker\'s, the levels are projected on this film', 'warn']);
  R.push(['Scale', 'the resection level lies away from the sphere, where magnification may differ (not validated). Remaining bone and stem-tip clearance: the full planner.', 'warn']);
  return R;
}
function tumSummary() {
  const it = tumBodies()[0], L = it && tumLevels(it, roleOf(it));
  if ((F().tp || {}).set === 'dia') { const D = diaLevels(); return D && D.ok ? `Defect ${fmm(D.s2 - D.s1)} mm: osteotomies at ${fmm(D.s1)} and ${fmm(D.s2)} mm from the joint line. Sizes shows Chunli's range.` : 'Enter MRI or CT, the near and far ends and the margin on the Tumour card (Add).'; }
  if (!L) return '';
  if (!L.tp.ok) return `Resection not planned: ${L.tp.why} (Add, then Plan values).`;
  if (L.sPlan === null) return 'Tap the tip of the greater trochanter / tuberosity (Add, then Place again).';
  return L.c === null ? `Planned osteotomy ${fmm(L.sPlan)} mm: choose a length (Add, then Length).` : `Stack ${fmm(L.sStack)} mm against the planned ${fmm(L.sPlan)} mm (${L.sStack - L.sPlan >= 0 ? '+' : ''}${fmm(L.sStack - L.sPlan)}).`;
}
// drawn on the film: the planned osteotomy (red dashes), the tumour extent (orange dots) and the stack's end (solid)
function tumScene(c, T, k, line, tag) {
  const bar = (p, d, half, col, dash, text) => { const n = G.P(-d.y, d.x), a = G.add(p, G.mul(n, half)), b = G.sub(p, G.mul(n, half)); c.strokeStyle = col; c.lineWidth = 2 * k; c.setLineDash(dash.map(x => x * k)); line(a, b); c.setLineDash([]); if (text) tag(G.add(a, G.mul(n, 0)), text, col); };
  if (!mmpp()) return;
  const half = 28 / mmpp();
  if ((F().tp || {}).set === 'dia') {
    const L = diaLevels(); if (!L) return;
    c.strokeStyle = '#ff9de2'; c.lineWidth = 1.5 * k; c.setLineDash([4 * k, 4 * k]); line(F().dia.a1, F().dia.a2); c.setLineDash([]);
    if (L.ok) { bar(L.at(L.s1), L.u, half, '#ff5a5a', [8, 5], `${fmm(L.s1)} mm`); bar(L.at(L.s2), L.u, half, '#ff5a5a', [8, 5], `${fmm(L.s2)} mm`); bar(L.at(L.near), L.u, half * 0.7, '#ffb84d', [2, 4], ''); bar(L.at(L.far), L.u, half * 0.7, '#ffb84d', [2, 4], ''); }
    return;
  }
  for (const it of tumBodies()) {
    const L = tumLevels(it, roleOf(it)); if (!L) continue;
    if (L.sExt !== null) bar(L.fr.at(L.sExt), L.fr.d, half * 0.7, '#ffb84d', [2, 4], '');
    if (L.sPlan !== null) bar(L.fr.at(L.sPlan), L.fr.d, half, '#ff5a5a', [8, 5], `plan ${fmm(L.sPlan - (L.sRef || 0))} mm`);
    if (L.sStack !== null) bar(L.fr.at(L.sStack), L.fr.d, half * 0.8, '#A8FF30', [], '');
  }
}

// ---------------------------------------------------------------- the panel and the sheets
function msg(t) { $('msg').textContent = t || ''; }
function syncUI() {
  for (const [id, j] of [['jHip', 'hip'], ['jKnee', 'knee'], ['jTum', 'tumour'], ['jPfna', 'pfna']]) $(id).setAttribute('aria-pressed', String(Q.joint === j));
  for (const [id, s] of [['sR', 'R'], ['sL', 'L']]) $(id).setAttribute('aria-pressed', String(Q.side === s));
  // the knee's films: AP, Lateral, Other (skyline, long leg); one on screen at a time
  const tabs = $('tabs'), knee = Q.joint === 'knee' && Q.films.some(f => f.img); tabs.hidden = !knee;
  if (knee) for (const b of tabs.querySelectorAll('button')) {
    const v = b.dataset.v, has = v === 'other' ? Q.films.filter(f => f.img && (f.fv === 'sky' || f.fv === 'long')) : Q.films.filter(f => f.img && f.fv === v), on = has.includes(F());
    b.setAttribute('aria-pressed', String(on));
    b.textContent = v === 'other' ? (on ? `${VIEW[F().fv]} ▾` : `Other${has.length ? ` (${has.length})` : ''} ▾`) : `${VIEW[v]}${!has.length ? ' +' : !sizable(has[0]) ? ' ·' : has[0].items.some(it => it.recheck) ? ' !' : ' ✓'}`;   // · a reference-only film (a CT not marked as a scout, "not sure")
  }
  const it = Q.items[Q.sel], f = it && Q.fam[it.fam], ok = !!it && !!mmpp();
  $('pickName').textContent = it ? `${Q.joint === 'tumour' ? partName(it) : KIND[f.kind]} ${it.side}` : 'No implant';
  const sel = $('sizeSel'); sel.innerHTML = ''; sel.disabled = !ok;
  if (it) for (const z of sizesOf(f, it.side)) { const o = document.createElement('option'); o.value = z.size; o.textContent = sizeWord(f, z); o.selected = z.size === it.size; sel.appendChild(o); }
  for (const id of ['minus', 'plus', 'rotL', 'rotR']) $(id).disabled = !ok;
  $('placeB').disabled = !mmpp(); $('placeB').classList.toggle('on', Q.tool === 'place');
  const lat = F().fv === 'lat';
  $('mirrorB').textContent = lat ? 'Flip' : 'Copy ⇄';
  $('mirrorB').disabled = lat ? !ok : !Q.items.length || !mmpp() || Q.joint === 'tumour';
  $('moveB').disabled = !ok; if (!ok) hidePad();
  $('measB').disabled = !mmpp() && !vgOk(); $('measB').classList.toggle('on', Q.tool === 'ruler' || Q.tool === 'lld' || Q.tool === 'vg' || Q.tool === 'head');
  $('doneB').disabled = !caseItems().length && !caseAngles().length && !(Q.joint === 'tumour' && Q.dia && Q.dia.ref); $('undoB').disabled = !Q.hist.length;
  const sc = $('scale');
  sc.textContent = !sizable() ? '' : !Q.cal ? `Scale not set: ball ${Q.ballD} mm ✎` : Q.cal.method === 'ball' ? `Ball ${Q.cal.ballD} mm, ${Q.cal.how === 'found' ? 'found' : 'tapped'} ✎` : Q.cal.method === 'ct' ? 'CT scanner scale ✎' : `Known length ${Q.cal.len} mm ✎`;
  sc.hidden = !sizable(); sc.classList.toggle('bad', sizable() && !Q.cal);
  $('zoom').hidden = !Q.img || !$('pad').hidden;
  if (sizable() && !Q.cal && F().src === 'ct') sc.textContent = 'CT scout: no scanner scale ✎';
}
function sheet(html, bind) { $('sheetCard').innerHTML = html; $('sheet').hidden = false; $('sheet').dataset.modal = ''; if (bind) bind($('sheetCard')); }
const closeSheet = () => { $('sheet').hidden = true; };
$('sheet').addEventListener('click', e => { if (e.target === $('sheet') && !$('sheet').dataset.modal) closeSheet(); });   // the film card is answered, not dismissed
const opts = (kind, cur, range) => famsOf(kind).filter(f => !range || f.range === range).filter(f => Q.joint !== 'knee' || (ktNow() === 'primary' ? !KT_PAIR.has(f.id) : KT_PAIR.has(f.id) && KNEE_TYPES.find(k => k.id === ktNow())[kind === 'tka-femur' ? 'fem' : 'tib'] === f.id)).map(f => `<option value="${esc(f.id)}"${f.id === cur ? ' selected' : ''}>${esc(f.label)}</option>`).join('');
function startSheet() {
  if (!sizable()) { msg(refText()); return; }
  if (Q.joint === 'tumour') { tumSheet(); return; }
  if (Q.joint === 'pfna') { pfnaSheet(); return; }
  const hip = Q.joint === 'hip', k1 = hip ? 'cup' : 'tka-femur', k2 = hip ? 'stem' : 'tka-tibia', f1 = startFam(k1), f2 = hip ? startFam(k2) : f1 ? trayFamFor(f1) : startFam(k2);
  if (!f1 && !f2) { msg(`No ${hip ? 'hip' : 'knee'} implant range is open to your account.`); return; }
  sheet(`<h2>What are we templating?</h2>
    <label class="f">Side of surgery</label><div class="choice"><button data-s="R" aria-pressed="${Q.side === 'R'}">Right</button><button data-s="L" aria-pressed="${Q.side === 'L'}">Left</button></div>
    ${hip ? '' : `<label class="f">Knee type</label><div class="choice wrap">${KNEE_TYPES.filter(ktHeld).map(k => `<button data-kt="${k.id}" aria-pressed="${ktNow() === k.id}">${esc(k.label)}</button>`).join('')}</div>
      <p class="note">CCK and the hinge take stems: pick the part, then Add, then Stem. Tumour knees (DFR, PTR): Tumour at the top.</p>`}
    <label class="f">${hip ? 'Cup' : 'Femur'}</label><select id="c1">${opts(k1, f1 && f1.id)}</select>
    <label class="f">${hip ? 'Stem' : 'Tray'}</label><select id="c2">${opts(k2, f2 && f2.id, hip || !f1 ? null : f1.range)}</select>
    ${Q.cal && Q.cal.method === 'ct' ? '<p class="note">Scale: the CT scanner\'s, from the scout\'s header (no ball).</p>' : ballField('sBallD')}
    ${Q.orientHint && !Q.mirrored ? `<p class="warn">The ${esc(Q.orient)} suggest${/, /.test(Q.orient) ? '' : 's'} this film is mirrored (the patient's right on the image right), if it shows both hips. <button class="go alt" id="setMir">Set the film as mirrored</button></p>` : Q.orient ? `<p class="note">Film: ${esc(Q.orient)}; ${Q.mirrored ? 'set as mirrored' : 'standard (the patient\'s right on the image left)'}.</p>` : ''}
    <button class="go" id="goPlace">Tap the ${hip ? 'hip' : 'knee'} on the X-ray</button>`, card => {
    card.querySelectorAll('[data-s]').forEach(b => b.onclick = () => { Q.side = b.dataset.s; syncUI(); startSheet(); });
    card.querySelectorAll('[data-kt]').forEach(b => b.onclick = () => {   // a type sets the femur and the tray as one pair
      const kt = KNEE_TYPES.find(k => k.id === b.dataset.kt);
      if (kt.fem) { Q.choice['tka-femur'] = kt.fem; Q.choice['tka-tibia'] = kt.tib; }
      else { const p = famsOf('tka-femur').find(f => !KT_PAIR.has(f.id)); if (p) Q.choice['tka-femur'] = p.id; delete Q.choice['tka-tibia']; }
      startSheet();
    });
    card.querySelector('#c1').onchange = e => { Q.choice[k1] = e.target.value; if (!hip) delete Q.choice['tka-tibia']; startSheet(); };
    card.querySelector('#c2').onchange = e => { Q.choice[k2] = e.target.value; };
    const sm = card.querySelector('#setMir'); if (sm) sm.onclick = () => { if (Q.items.length) snap(); Q.mirrored = true; Q.orient = 'set by hand: mirrored film'; Q.orientHint = false; reflip(); syncUI(); draw(); startSheet(); };
    const bd = card.querySelector('#sBallD'); if (bd) bd.onchange = e => setBallD(e.target.value);
    card.querySelector('#goPlace').onclick = () => { Q.choice[k1] = card.querySelector('#c1').value; Q.choice[k2] = card.querySelector('#c2').value; closeSheet(); armPlace(); };
  });
}
// Add: the whole set, or one part alone (Medstrat and Bonesetter add parts one at a time); a part already on this side is replaced
function addSheet() {
  if (Q.joint === 'tumour') { if (!mmpp()) { armPlace(); return; } if (tumBodies().length) tumPartsSheet(); else tumSheet(); return; }
  if (!mmpp()) { armPlace(); return; }
  if (!Q.items.some(it => it.side === Q.side)) { startSheet(); return; }
  if (Q.joint === 'pfna') { pfnaAddSheet(); return; }
  const [a, b] = SLOTS[Q.joint], has = k => Q.items.some(it => it.side === Q.side && kindOf(it) === k);
  sheet(`<h2>Add on the ${SIDE[Q.side]} ${Q.joint}</h2><div class="menu">
    <button data-k="${a},${b}">${KIND[a]} and ${KIND[b]}</button>
    <button data-k="${a}">${KIND[a]} only${has(a) ? ' (replaces this one)' : ''}</button>
    <button data-k="${b}">${KIND[b]} only${has(b) ? ' (replaces this one)' : ''}</button>
    ${Q.items[Q.sel] && stemList(Q.items[Q.sel]).length ? `<button id="aStem">Stem on the picked ${esc(partName(Q.items[Q.sel]))} ${Q.items[Q.sel].side}${stemRec(Q.items[Q.sel]) ? ` (${esc(stemWord(stemRec(Q.items[Q.sel])))})` : ''}</button>` : ''}
    <button id="aSys">Change the implant system or the side</button></div>
    <p class="note">Then tap the ${Q.joint === 'hip' ? 'femoral head (the cup centre, the stem head)' : 'knee joint line'} on the X-ray.</p>
    <button class="go alt" id="aClose">Close</button>`, card => {
    card.querySelectorAll('[data-k]').forEach(x => x.onclick = () => { closeSheet(); armPlace(x.dataset.k.split(',')); });
    card.querySelector('#aSys').onclick = startSheet;
    const as = card.querySelector('#aStem'); if (as) as.onclick = stemSheet;
    card.querySelector('#aClose').onclick = closeSheet;
  });
}
function armPlace(kinds = null) {
  if (!sizable()) { msg(refText()); return; }
  if (Q.joint === 'tumour' && mmpp()) { armTum(); return; }
  if (!mmpp()) { Q.tool = 'ball'; msg(`Set the scale first (tap the ${Q.ballD} mm ball, or the scale button for a known length): the implants are drawn true size.`); syncUI(); return; }
  Q.tool = 'place'; Q.placeKinds = kinds; Q.tmp = []; fit();   // the whole film, to find the joint
  const one = kinds && kinds.length === 1 ? KIND[kinds[0]].toLowerCase() : null;
  if (Q.joint === 'pfna') { msg(`Tap the tip of the ${SIDE[Q.side]} greater trochanter (the nail's entry point${fracWord(Q.side) ? `, ${fracWord(Q.side)}` : ''}).`); syncUI(); return; }
  msg(Q.joint === 'hip' ? `Tap the centre of the ${SIDE[Q.side]} femoral head${one ? ` for the ${one}` : ''}.` : `Tap the middle of the ${SIDE[Q.side]} knee joint${one ? ` for the ${one}` : ''}.`); syncUI();
}
// a new joint is a new case: the film on screen is kept (it was often opened under the wrong joint), the others and every
// implant go, and Undo starts again (Astra: "Add view" and "New case" kept apart)
function setJoint(j) {
  if (j === Q.joint) return;
  const any = Q.films.some(f => f.items.length), more = Q.films.filter(f => f.img).length > 1;
  if ((any || more) && !confirm(`Start a ${JOINT_WORD[j]} case? ${more ? 'The other films and all' : 'All'} the implants are removed; the film on screen stays.`)) return;
  const f = F(); Q.joint = j; Q.frac = null; Q.pOn = null; Q.films = [f]; Q.cur = 0; Q.hist = []; Q.sel = -1; Q.tool = null; Q.tmp = []; Q.pendCopy = null;
  f.items = []; f.rul = []; f.lld = []; f.hd = { R: [], L: [] }; f.vg = []; f.vgSide = ''; if (j !== 'hip' && j !== 'pfna') f.orient = '';
  msg('');
  if (f.img) {
    if (j === 'hip' || j === 'tumour' || j === 'pfna') { f.fv = j === 'tumour' ? 'tum' : 'hip'; f.tp = null; f.dia = null; }
    else { f.fv = 'pending'; f.cal = null; syncUI(); draw(); filmCard(f.img, f.name, !!(f.img.meta && f.img.meta.Modality !== 'picture')); return; }   // the knee view asked again
  }
  syncUI(); draw();
  if (f.img && sizable() && Q.cal) startSheet(); else if (f.img) msg(refText() || (!Q.cal ? `Tap the ${Q.ballD} mm ball on the X-ray.` : ''));
}
function newCase() {
  if (Q.films.some(f => f.items.length) && !confirm('Start a new case? Every film and implant open now is removed.')) return;
  Q.films = [blankFilm()]; Q.cur = 0; Q.hist = []; Q.sel = -1; Q.tool = null; Q.tmp = []; Q.label = ''; Q.pendCopy = null; hidePad();
  $('open').hidden = false; msg(''); syncUI(); draw();
}
function pickFile(slot) { if (copyPending()) return; Q.pend = slot; $('file').value = ''; $('file').click(); }
function otherViews() {
  const row = v => { const i = Q.films.findIndex(f => f.img && f.fv === v); return `<button data-v="${v}" data-i="${i}">${VIEW[v]}${i >= 0 ? (Q.films[i].src === 'ct' ? ' (CT scout): show' : ': show') : ': open a film'}</button>`; };
  sheet(`<h2>Other knee views</h2><div class="menu">${row('sky')}${row('long')}</div>
    <p class="note">A skyline is a reference view (no implant sizing). A long-leg X-ray is calibrated by its ball (at knee level) or a known length; a CT scout of the whole leg needs no calibration (the scanner's scale). On either, Measure gives the valgus cut angle.</p><button class="go alt" id="oClose">Close</button>`, card => {
    card.querySelectorAll('[data-v]').forEach(b => b.onclick = () => { closeSheet(); const i = +b.dataset.i; if (i >= 0) switchFilm(i); else pickFile(b.dataset.v); });
    card.querySelector('#oClose').onclick = closeSheet;
  });
}
// zoom from the buttons and the keyboard: about the middle of the screen
function zoomBy(k) { if (!Q.img) return; const r = cv.getBoundingClientRect(); zoomAt(G.P(r.width / 2, r.height / 2), k); }
const SHORTCUTS = [
  ['L', 'Length (same key in the viewer)'], ['W  /  M  /  Z', 'Dragging on the film sets W/L / pans / zooms (same keys in the viewer)'],
  ['0', "The film's own W/L"], ['F11', 'Invert'], ['F', 'Full screen'],
  ['Ctrl+0  /  Ctrl+1', 'Fit the film / actual size (⌘ on a Mac)'], ['Ctrl and +  or  −', 'Zoom (also + and − alone, two fingers, or the mouse wheel)'], ['Ctrl+Shift+\\', 'Reset the view'], ['Ctrl+arrows', 'Move the film'],
  ['I', 'Add an implant (the whole set or one part)'], ['N  or  Tab', 'Pick the next implant'], ['Shift+R  /  Shift+L', 'Right / left side'],
  ['↑  or  Page Up', 'Next size up'], ['↓  or  Page Down', 'Next size down'], [',  /  .', 'Turn 0.5° (with Shift 2°)'],
  ['Shift+arrows', 'Move the picked implant 0.5 mm (with Alt too, 2 mm)'],
  ['C', 'Copy to the other side (on a lateral: Flip)'], ['V', 'Valgus cut angle (long leg)'], ['S', 'Sizes card'],
  ['Alt+1  /  2  /  3', 'Knee: AP / Lateral / other views'], ['Delete', 'Remove the picked implant'],
  ['Ctrl+Z  or  ⌘Z', 'Undo'], ['Esc', 'Close a card, or stop the tap you started'], ['?', 'This list'],
];
function helpSheet() {
  sheet(`<h2>Shortcuts and help</h2>
    <p class="note">On a phone: two fingers zoom and move the film; drag an implant to move it, the green ⟳ handle to turn it; drag any measurement point to adjust it. On a computer the keys below do the same.</p>
    <table class="res keys">${SHORTCUTS.map(([k, v]) => `<tr><td class="k"><kbd>${esc(k)}</kbd></td><td>${esc(v)}</td></tr>`).join('')}</table>
    <p class="note">Words used: AP front-to-back film; Lat lateral; CT scout the CT scanner's planning picture (scanner scale, no sphere); valgus cut angle the femoral shaft axis to the mechanical axis (hip centre to knee centre), set on the intramedullary guide.</p>
    <button class="go alt" id="hClose">Close</button>`, card => { card.querySelector('#hClose').onclick = closeSheet; });
}
const press = id => { const b = $(id); if (b && !b.disabled && !b.hidden) b.click(); };
document.addEventListener('keydown', e => {
  const t = e.target, typing = t && (t.tagName === 'INPUT' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA');
  if (e.key === 'Escape') {
    if (typing) { t.blur(); return; }   // the first Esc commits a typed sphere size (Safari keeps a hidden field unblurred), a second closes the card
    if (!$('sheet').hidden) { if (!$('sheet').dataset.modal) closeSheet(); return; }
    if (Q.tool && !Q.pendCopy) { Q.tool = null; Q.tmp = []; Q.placeKinds = null; msg('Stopped.'); syncUI(); draw(); }
    return;
  }
  if (typing) return;
  const mod = e.ctrlKey || e.metaKey, code = e.code || '';
  if (mod && !e.altKey && e.key.toLowerCase() === 'z') { e.preventDefault(); if ($('sheet').hidden) press('undoB'); return; }
  if (e.key === '?' && !mod) { e.preventDefault(); if ($('sheet').hidden) helpSheet(); return; }   // never over an open card (the film card must be answered)
  if (!$('sheet').hidden || !Q.img) return;
  const k = e.key, it = Q.items[Q.sel], big = e.shiftKey, arrow = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[k];
  // Sami, 6 Nov 2026: the same keys as the viewer and the full page (SHORTCUTS.md)
  if (mod) {
    if (e.altKey) return;
    const f = F();
    const act = code === 'Backslash' && big ? () => { Q.dragTool = null; if (f.win0) { f.win = { ...f.win0 }; f.inv = false; paintFilm(f); } fit(); draw(); }
      : k === '0' || code === 'Numpad0' ? fit
      : k === '1' || code === 'Numpad1' ? () => { const r = cv.getBoundingClientRect(); zoomAt(G.P(r.width / 2, r.height / 2), 1 / Q.view.s); }
      : k === '+' || k === '=' || code === 'NumpadAdd' ? () => zoomBy(1.25)
      : k === '-' || k === '_' || code === 'NumpadSubtract' ? () => zoomBy(0.8)
      : arrow ? () => { Q.view.tx -= arrow[0] * 40; Q.view.ty -= arrow[1] * 40; draw(); } : null;
    if (act) { e.preventDefault(); act(); }
    return;
  }
  if (arrow && big) {
    e.preventDefault();
    if (it && mmpp() && !Q.tool) { if (!e.repeat) snap(); const n = e.altKey ? 4 : 1; nudge(arrow[0] * n, arrow[1] * n); }
    return;
  }
  if (e.altKey) {
    const v = { Digit1: 'ap', Digit2: 'lat', Digit3: 'other' }[code];
    if (v && !$('tabs').hidden) { e.preventDefault(); document.querySelector(`#tabs [data-v=${v}]`).click(); }
    return;
  }
  const tool = t => () => { Q.dragTool = t; msg({ wl: 'Dragging on the film now sets W/L (brightness and contrast). M goes back to pan.', pan: 'Dragging on the film now pans.', zoom: 'Dragging on the film now zooms. M goes back to pan.' }[t]); };
  const low = k.length === 1 ? k.toLowerCase() : k;
  const act = {
    '+': () => zoomBy(1.4), '=': () => zoomBy(1.4), '-': () => zoomBy(1 / 1.4), '_': () => zoomBy(1 / 1.4),
    f: () => { if (document.fullscreenElement) document.exitFullscreen(); else if (document.documentElement.requestFullscreen) document.documentElement.requestFullscreen().catch(() => {}); },
    w: tool('wl'), m: tool('pan'), z: tool('zoom'),
    0: () => { const f = F(); if (f.win0) { f.win = { ...f.win0 }; paintFilm(f); draw(); } },
    F11: () => { const f = F(); if (f.win) { f.inv = !f.inv; paintFilm(f); draw(); } },
    l: () => { if (big) press('sL'); else if (mmpp() && !copyPending()) { Q.tool = 'ruler'; Q.tmp = []; msg('Tap one end.'); syncUI(); } },
    r: () => { if (big) press('sR'); },
    i: () => press('placeB'), c: () => press('mirrorB'), s: () => press('doneB'),
    v: () => { if (!copyPending() && vgOk()) { Q.tool = 'vg'; Q.tmp = []; fit(); msg(`The ${SIDE[Q.side]} leg (the side button at the top). ${VG_STEPS[0]}`); syncUI(); } },
    ArrowUp: () => press('plus'), PageUp: () => press('plus'), ArrowDown: () => press('minus'), PageDown: () => press('minus'),
    ',': () => { if (it && !$('rotL').disabled) { if (!e.repeat) snap(); turn(-0.5); } }, '<': () => { if (it && !$('rotL').disabled) { if (!e.repeat) snap(); turn(-2); } },
    '.': () => { if (it && !$('rotR').disabled) { if (!e.repeat) snap(); turn(0.5); } }, '>': () => { if (it && !$('rotR').disabled) { if (!e.repeat) snap(); turn(2); } },
    Delete: () => { if (it && !Q.pendCopy) removeSel(); }, Backspace: () => { if (it && !Q.pendCopy) removeSel(); },
    n: () => { if (Q.items.length && !Q.tool) { Q.sel = (Q.sel + 1) % Q.items.length; Q.side = Q.items[Q.sel].side; syncUI(); draw(); } },
    Tab: () => { if (Q.items.length && !Q.tool) { e.preventDefault(); Q.sel = (Q.sel + (big ? Q.items.length - 1 : 1)) % Q.items.length; Q.side = Q.items[Q.sel].side; syncUI(); draw(); } },
  }[low];
  if (act) { if (k !== 'Tab') e.preventDefault(); act(); }
});
function menu() {
  if (copyPending()) return;
  const it = Q.items[Q.sel], knee = Q.joint === 'knee';
  sheet(`<h2>Menu</h2><div class="menu">
    ${knee ? `<button id="mAdd"${Q.img ? '' : ' disabled'}>Add a knee view (AP, lateral, skyline, long leg)</button>` : '<button id="mOpen">Open another X-ray</button>'}
    <button id="mNew">New case (clears every film)</button>
    <button id="mSys"${sizable() && Q.cal ? '' : ' disabled'}>Implant system and side</button>
    <button id="mBall"${sizable() ? '' : ' disabled'}>Tap the ball again</button>
    ${F().fv === 'lat' ? '' : `<button id="mMir"${sizable() ? '' : ' disabled'}>Film is mirrored: ${Q.mirrored ? 'yes' : 'no'} (change)</button>`}
    <button id="mDel"${it ? '' : ' disabled'}>Remove the selected implant</button>
    <button id="mClr"${Q.rul.length || Q.lld.length || Q.hd.R.length || Q.hd.L.length || Q.vg.length ? '' : ' disabled'}>Clear the measurements</button>
    <button id="mFit"${Q.img ? '' : ' disabled'}>Fit the film to the screen</button>
    <button id="mHelp">Shortcuts and help (?)</button>
    </div>
    ${sizable() ? ballField('mBallD') : ''}
    <p class="note"><a href="./">Full planner</a>: landmarks, leg length and offset plan, knee cuts and alignment, saved to the patient list.</p>
    <button class="go alt" id="mClose">Close</button>`, card => {
    const on = (id, fn) => { const b = card.querySelector(id); if (b) b.onclick = fn; };
    on('#mOpen', () => { closeSheet(); pickFile(null); });
    on('#mAdd', () => { closeSheet(); pickFile(null); });
    on('#mNew', () => { closeSheet(); newCase(); });
    on('#mSys', () => startSheet());
    on('#mBall', () => { closeSheet(); Q.tool = 'ball'; msg(`Tap the middle of the ${Q.ballD} mm ball.`); syncUI(); });
    on('#mMir', () => { if (Q.items.length) snap(); Q.mirrored = !Q.mirrored; Q.orient = `set by hand: ${Q.mirrored ? 'mirrored film' : 'standard film'}`; Q.orientHint = false; reflip(); closeSheet(); msg(`Film set as ${Q.mirrored ? 'mirrored' : 'standard'}: the implants were turned to match. Check them on the X-ray.`); syncUI(); draw(); });
    on('#mDel', () => { closeSheet(); removeSel(); });
    on('#mClr', () => { snap(); Q.rul = []; Q.lld = []; Q.hd = { R: [], L: [] }; Q.vg = []; closeSheet(); syncUI(); draw(); });
    on('#mFit', () => { closeSheet(); fit(); });
    on('#mHelp', () => helpSheet());
    const bd = card.querySelector('#mBallD'); if (bd) bd.onchange = e => setBallD(e.target.value);
    on('#mClose', closeSheet);
  });
}
function measMenu() {
  if (copyPending()) return;
  sheet(`<h2>Measure</h2><div class="menu">
    <button id="mVg"${vgOk() ? '' : ' disabled'}>Valgus cut angle (long leg): hip centre, knee centre, two points down the femoral canal</button>
    <button id="mRul"${mmpp() ? '' : ' disabled'}>Length: tap two points</button>
    <button id="mLld"${Q.joint === 'hip' ? '' : ' disabled'}>Leg length: both teardrops, then both lesser trochanters</button>
    ${Q.joint === 'pfna' ? `<button id="mHead"${mmpp() ? '' : ' disabled'}>Femoral head: three points round its edge (tip-apex distance)</button>` : ''}
    </div><button class="go alt" id="mClose">Close</button>`, card => {
    card.querySelector('#mVg').onclick = () => { closeSheet(); Q.tool = 'vg'; Q.tmp = []; fit(); msg(`The ${SIDE[Q.side]} leg (the side button at the top). ${VG_STEPS[0]}`); syncUI(); };
    card.querySelector('#mRul').onclick = () => { closeSheet(); Q.tool = 'ruler'; Q.tmp = []; msg('Tap one end.'); syncUI(); };
    card.querySelector('#mLld').onclick = () => { closeSheet(); Q.tool = 'lld'; Q.tmp = []; msg(LLD_STEPS[0]); syncUI(); };
    const mh = card.querySelector('#mHead'); if (mh) mh.onclick = () => { closeSheet(); armHead(); };
    card.querySelector('#mClose').onclick = closeSheet;
  });
}

// ---------------------------------------------------------------- the sizes: card, text and picture
// The sizes for the case: one column per side, the systems named once, and the sizes to have ready (one size either side of
// each planned size, both sides together). A knee's sizes are the AP's and the lateral's (shared); each film's fit is listed.
const near = (f, size, side) => { const l = stepList(f, side, size), i = l.findIndex(z => z.size === size); return i < 0 ? [] : l.slice(Math.max(0, i - 1), i + 2).map(z => z.size); };
const tplFilms = () => Q.films.filter(f => sizable(f) && f.cal && f.items.length).sort((a, b) => ['hip', 'ap', 'lat', 'long'].indexOf(a.fv) - ['hip', 'ap', 'lat', 'long'].indexOf(b.fv));
function caseItems() {
  const out = [], seen = new Set();
  for (const f of tplFilms()) for (const it of f.items) { const key = it.side + kindOf(it); if (!seen.has(key)) { seen.add(key); out.push(it); } }
  return out;
}
function sideOf(side, items) {
  const o = {}; for (const it of items) if (it.side === side) o[kindOf(it)] = it; return o;
}
function resultGrid() {
  const items = caseItems(), sides = ['R', 'L'].filter(s => items.some(it => it.side === s)), per = Object.fromEntries(sides.map(s => [s, sideOf(s, items)]));
  const kinds = SLOTS[Q.joint], rows = [], systems = [], ready = [], warn = [];
  for (const k of kinds) {
    const its = sides.map(s => per[s][k]); if (!its.some(Boolean)) continue;
    rows.push([KIND[k], its.map(it => (it ? String(sizeRec(it).label || sizeRec(it).size).replace(/ mm$/, '') : '-'))]);
    const fams = [...new Set(its.filter(Boolean).map(it => it.fam))];
    for (const id of fams) {
      const f = Q.fam[id], on = sides.filter((s, i) => its[i] && its[i].fam === id);
      systems.push(`${KIND[k]}${fams.length > 1 ? ' ' + on.map(s => SIDE[s]).join(' and ') : ''}: ${f.label}`);
      const want = new Set(its.filter(it => it && it.fam === id).flatMap(it => {
        const n = near(f, it.size, it.side), fe = k === 'tka-tibia' && per[it.side]['tka-femur'], c = fe && kneePairCheck(fe.fam, fe.size, it.fam, it.size);
        if (!c) return n;
        const ok = n.filter(z => c.green.includes(z) || c.yellow.includes(z));   // the XN knee: only trays Chunli's chart allows with the femur
        return ok.length ? ok : c.green.filter(z => f.sizes.some(q => q.size === z));   // a tray that is no pair: the chart's own
      }));
      ready.push(`${KIND[k]}${fams.length > 1 ? ' (' + f.label + ')' : ''} ${sizesOf(f, on.length === 1 ? on[0] : null).map(z => z.size).filter(z => want.has(z)).join(', ')}`);
    }
  }
  if (Q.joint === 'pfna') pfnaRows(sides, per, rows, systems, ready, warn);
  if (Q.joint === 'hip') { const its = sides.map(s => per[s].cup); if (its.some(Boolean)) rows.push([`Cup angle${tdLine() ? ' (teardrops)' : ''}`, its.map(it => (it ? `${cupIncl(it).toFixed(0)}°` : '-'))]); }
  if (Q.joint === 'knee') {
    const tf = sides.map(s => per[s]['tka-tibia']);
    if (tf.some(it => it && Array.isArray(Q.fam[it.fam].thicknesses))) rows.push(['Inserts', tf.map(it => { const f = it && Q.fam[it.fam], th = f && f.thicknesses; const g = f && f.id === 'chunli-tka-tibia' ? insertGroupOf(it.size) : null; return th && th.length ? `${th[0]}-${th.at(-1)} mm${g ? ` (group ${g.join('/')})` : ''}` : '-'; })]);
    for (const k of ['tka-femur', 'tka-tibia']) { const its = sides.map(s => per[s][k]); if (its.some(it => it && stemList(it).length)) rows.push([`${KIND[k]} stem`, its.map(it => (!it ? '-' : stemRec(it) ? stemWord(stemRec(it)) : stemList(it).length ? 'none' : '-'))]); }
    const pairs = sides.map(s => kneePairOf(s, items));
    if (pairs.some(Boolean)) rows.push(['Pair', pairs.map(p => (!p ? '-' : p.grade === 'none' ? 'not verified (no maker pairing rule here)' : p.grade === 'green' ? 'closest match (Chunli chart)' : p.grade === 'yellow' ? `allowed (best ${p.green.join('/')})` : `NOT a pair: tray ${p.green.join(' or ')}`))]);
    // the fit, film by film: placed and looked at, or changed elsewhere and still to check
    const films = tplFilms();
    if (films.length) rows.push(['Fit set on', sides.map(s => films.filter(f => f.items.some(it => it.side === s)).map(f => `${filmName(f)}${f.items.some(it => it.side === s && it.recheck) ? ' (recheck)' : ''}`).join(', ') || '-')]);
    if (films.some(f => f.items.some(it => it.recheck))) warn.push('A size was changed on one film after the other was fitted: check the orange outlines on that film before you rely on the sizes.');
  }
  return { sides, rows, systems: [...new Set(systems)], ready, warn };
}
function measureRows() {
  const out = [];
  if (Q.lld.length === 4 && lldText()) out.push(['Leg length', lldText()]);
  Q.rul.forEach((m, i) => out.push([`Length ${i + 1}`, `${(G.dist(m.a, m.b) * mmpp()).toFixed(0)} mm`]));
  return out;
}
function caseAngles() {
  return Q.films.filter(f => vgOk(f) && vgAngle(f) !== null).map(f => [`Valgus cut angle${f.vgSide ? ', ' + SIDE[f.vgSide] : ''}: ${filmName(f)}`,
    `${vgAngle(f).toFixed(1)}° femoral shaft axis to mechanical axis; ${longWord(f)}`]);
}
function scaleOf(f) {
  const c = f.cal; if (!c) return 'not set';
  if (c.method === 'ct') return `CT scanner scale, ${c.mmPerPx} mm per pixel from the scout's header (no ball, no magnification)`;
  const over = ctScanScale(f) ? `, replacing the CT scanner's ${ctScanScale(f).mmPerPx} mm per pixel (${((c.mmPerPx / ctScanScale(f).mmPerPx - 1) * 100).toFixed(1)} % from it)` : '';
  if (c.method === 'length') return `known length ${c.len} mm tapped on the film (no ball)${over}`;
  return `${c.ballD} mm ball (${c.how === 'found' ? 'found by the page' : c.how === 'edges' ? 'its two edges tapped' : 'tapped'})${over}`;
}
const longWord = f => (f.src === 'ct' ? 'supine CT scout, not weight-bearing' : f.wb ? 'standing film (weight-bearing)' : 'X-ray, standing not confirmed');
const filmName = f => `${VIEW[f.fv]}${f.src === 'ct' ? ' (CT scout)' : f.fv === 'long' ? (f.wb ? ' (standing X-ray)' : ' (X-ray, standing not confirmed)') : ''}`;
const scaleText = () => (!caseItems().length && caseAngles().length ? 'none needed for an angle' : Q.joint === 'knee' ? tplFilms().map(f => `${filmName(f)} ${scaleOf(f)}`).join('; ') || scaleOf(F()) : scaleOf(F()));
function textLines() {
  if (Q.joint === 'tumour') return [`Quick templating, tumour ${new Date().toISOString().slice(0, 10)}${Q.label ? ' · ' + Q.label : ''}`, ...tumRowsQ().map(([k, v, w]) => (v === undefined ? k : `${w ? '! ' : ''}${k}: ${v}`)), `Scale: ${scaleOf(F())}`, STAMP];
  const r = resultGrid(), L = [`Quick templating, ${JOINT_WORD[Q.joint]} ${new Date().toISOString().slice(0, 10)}${Q.label ? ' · ' + Q.label : ''}${Q.joint === 'knee' ? ` · picture: ${VIEW[F().fv]} film` : ''}`];
  if (r.sides.length) L.push(r.sides.map(s => SIDE[s].toUpperCase() + (fracWord(s) ? ` (${fracWord(s)})` : '')).join(' | ') + (r.sides.length > 1 ? '' : ' side'));
  for (const [k, v] of r.rows) L.push(`${k}: ${v.join(' | ')}`);
  if (r.ready.length) L.push(`Have ready: ${r.ready.join('; ')}`);
  for (const sy of r.systems) L.push(sy);
  for (const w of r.warn) L.push(w);
  for (const [k, v] of [...(mmpp() ? measureRows() : []), ...caseAngles()]) L.push(`${k}: ${v}`);
  L.push(`Scale: ${scaleText()}`); L.push(STAMP);
  return L;
}
function tumDone() {
  const rows = tumRowsQ(), share = !!(navigator.canShare && navigator.share), pic = !!(sizable() && Q.cal && (Q.items.length || F().dia));
  sheet(`<h2>Tumour plan</h2><table class="res">${rows.map(([k, v, w]) => v === undefined ? `<tr><th colspan="2">${esc(k)}</th></tr>` : `<tr${w ? ' class="w"' : ''}><td class="k">${esc(k)}</td><td>${esc(v)}</td></tr>`).join('')}</table>
    <p class="note">Scale: ${esc(scaleOf(F()))}.</p><p class="stamp">${esc(STAMP)}</p>
    <label class="f">Label on the picture (optional)</label><input type="text" id="lab" maxlength="60" value="${esc(Q.label)}" placeholder="e.g. record number; leave empty to share outside the hospital">
    <p class="note">Text the X-ray machine burned into the film itself (often the patient's name at the top) stays on the picture: crop it off before sharing outside the hospital.</p>
    ${pic ? `<button class="go" id="savePic">${share ? 'Share picture' : 'Save picture'}</button>${share ? '<button class="go alt" id="dlPic">Save picture</button>' : ''}` : ''}
    <button class="go alt" id="copyTxt">Copy as text</button><button class="go alt" id="back">Back to the X-ray</button>`, card => {
    card.querySelector('#lab').oninput = e => { Q.label = e.target.value.trim(); };
    if (pic) { card.querySelector('#savePic').onclick = () => (share ? sharePic() : savePic()); if (share) card.querySelector('#dlPic').onclick = savePic; }
    card.querySelector('#copyTxt').onclick = async () => { try { await navigator.clipboard.writeText(textLines().join('\n')); card.querySelector('#copyTxt').textContent = 'Copied'; } catch (e) { card.querySelector('#copyTxt').textContent = 'Copy failed: select the table instead'; } };
    card.querySelector('#back').onclick = closeSheet;
  });
}
function done() {
  if (copyPending()) return;   // unfinished copies are not a plan
  if (Q.joint === 'tumour') return tumDone();
  const r = resultGrid(), meas = [...(mmpp() ? measureRows() : []), ...caseAngles()], cap = s => s[0].toUpperCase() + s.slice(1);
  const head = `<tr><th></th>${r.sides.map(s => `<th>${cap(SIDE[s])}${fracWord(s) ? `<br><small>${fracWord(s)}</small>` : ''}</th>`).join('')}</tr>`;
  const body = r.rows.map(([k, v]) => `<tr><td class="k">${esc(k)}</td>${v.map(x => `<td class="v${x.length > 12 ? ' sm' : ''}">${esc(x)}</td>`).join('')}</tr>`).join('');
  const mb = meas.map(([k, v]) => `<tr><td class="k">${esc(k)}</td><td colspan="${Math.max(1, r.sides.length)}">${esc(v)}</td></tr>`).join('');
  const share = !!(navigator.canShare && navigator.share), pic = !!(sizable() && Q.cal && Q.items.length);
  sheet(`<h2>Sizes</h2><table class="res">${head}${body}${mb}</table>
    ${r.ready.length ? `<p class="ready"><b>Have ready:</b> ${r.ready.map(esc).join('<br>')}</p>` : ''}
    <p class="note">${r.systems.map(esc).join('<br>')}</p>
    ${r.warn.map(w => `<p class="warn">${esc(w)}</p>`).join('')}
    ${tplFilms().some(f => f.fv === 'long' && f.src === 'xray') ? '<p class="note">Long-leg X-ray: calibrated by its ball, which must be at knee level; sizes are right at the knee and a few per cent off at the hip and ankle.</p>' : ''}
    ${tplFilms().some(f => f.src === 'ct') ? "<p class=\"note\">CT scout: the patient lies supine. Lengths along the limb are true; widths are true at the scanner's centre line and a few per cent off for a limb lying well above or below it.</p>" : ''}
    ${tplFilms().some(f => f.cal.method === 'length') ? '<p class="warn">No ball on a film: its scale is a length tapped by hand. Check the sizes against the surgeon\'s plan.</p>' : ''}
    <p class="note">Scale: ${esc(scaleText())}.</p>
    <p class="stamp">${esc(STAMP)}</p>
    <label class="f">Label on the picture (optional)</label><input type="text" id="lab" maxlength="60" value="${esc(Q.label)}" placeholder="e.g. record number; leave empty to share outside the hospital">
    <p class="note">Text the X-ray machine burned into the film itself (often the patient's name at the top) stays on the picture: crop it off before sharing outside the hospital.${Q.joint === 'knee' ? ' The picture is of the film on screen; the sizes under it are the whole case.' : ''}</p>
    ${pic ? `<button class="go" id="savePic">${share ? 'Share picture' : 'Save picture'}</button>${share ? '<button class="go alt" id="dlPic">Save picture</button>' : ''}` : '<p class="note">Open the AP or lateral film to save a picture with the implants on it.</p>'}
    <button class="go alt" id="copyTxt">Copy the sizes as text</button>
    <button class="go alt" id="back">Back to the X-ray</button>`, card => {
    card.querySelector('#lab').oninput = e => { Q.label = e.target.value.trim(); };
    if (pic) { card.querySelector('#savePic').onclick = () => (share ? sharePic() : savePic()); if (share) card.querySelector('#dlPic').onclick = savePic; }
    card.querySelector('#copyTxt').onclick = async () => { try { await navigator.clipboard.writeText(textLines().join('\n')); card.querySelector('#copyTxt').textContent = 'Copied'; } catch (e) { card.querySelector('#copyTxt').textContent = 'Copy failed: select the table instead'; } };
    card.querySelector('#back').onclick = closeSheet;
  });
}
function picture() {
  const img = Q.img, f = Math.min(1, 3000 / Math.max(img.cols, img.rows)), W = Math.round(img.cols * f), H = Math.round(img.rows * f), k = Math.max(1, Math.max(W, H) / 1100);
  const lines = textLines(), lh = 22 * k, pad = 14 * k, panelH = Math.round(pad * 2 + lines.length * lh);
  const c = document.createElement('canvas'); c.width = W; c.height = H + panelH; const x = c.getContext('2d');
  x.drawImage(Q.off, 0, 0, W, H);
  scene(x, p => G.P(p.x * f, p.y * f), k, false);
  x.fillStyle = '#fff'; x.fillRect(0, H, W, panelH); x.fillStyle = '#034537'; x.fillRect(0, H, W, 4 * k);
  x.textAlign = 'left'; x.textBaseline = 'top';
  lines.forEach((t, i) => { x.font = `${i <= 1 ? '700' : '400'} ${15 * k}px system-ui, sans-serif`; x.fillStyle = i === lines.length - 1 ? '#034537' : '#000'; x.fillText(t, pad, H + pad + i * lh, W - 2 * pad); });
  return c;
}
const picName = () => `quick-${Q.joint}${Q.joint === 'knee' ? '-' + F().fv : ''}-${[...new Set(caseItems().map(it => SIDE[it.side]))].join('-')}-${new Date().toISOString().slice(0, 10)}${Q.label ? '-' + Q.label.replace(/[^A-Za-z0-9_-]+/g, '_').slice(0, 30) : ''}.jpg`;
function savePic() {
  picture().toBlob(b => { const a = document.createElement('a'), u = URL.createObjectURL(b); a.href = u; a.download = picName(); document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(u), 4000); }, 'image/jpeg', 0.9);
}
function sharePic() {
  picture().toBlob(async b => {
    const file = new File([b], picName(), { type: 'image/jpeg' });
    try { if (navigator.canShare({ files: [file] })) await navigator.share({ files: [file], text: textLines().join('\n') }); else savePic(); }
    catch (e) { if (e && e.name !== 'AbortError') savePic(); }
  }, 'image/jpeg', 0.9);
}

// ---------------------------------------------------------------- buttons
$('file').addEventListener('change', e => { const f = e.target.files && e.target.files[0]; if (f) openFile(f); else Q.pend = null; });
$('jHip').onclick = () => setJoint('hip');
$('jKnee').onclick = () => setJoint('knee');
$('jTum').onclick = () => setJoint('tumour');
$('jPfna').onclick = () => setJoint('pfna');
$('sR').onclick = () => { Q.side = 'R'; const i = Q.items.findIndex(it => it.side === 'R'); if (i >= 0) Q.sel = i; syncUI(); draw(); };
$('sL').onclick = () => { Q.side = 'L'; const i = Q.items.findIndex(it => it.side === 'L'); if (i >= 0) Q.sel = i; syncUI(); draw(); };
for (const b of $('tabs').querySelectorAll('button')) b.onclick = () => {
  const v = b.dataset.v; if (v === 'other') return otherViews();
  const i = Q.films.findIndex(f => f.img && f.fv === v); if (i >= 0) switchFilm(i); else pickFile(v);
};
$('scale').onclick = () => { if (copyPending()) return; if (sizable()) sheet(`<h2>Scale on this film</h2><p>${esc(scaleOf(F()))}.</p>${ballField('cBallD')}<button class="go" id="cAgain">Tap the ball again</button><button class="go alt" id="cEdges">Tap the ball's two edges</button><button class="go alt" id="cLen">No ball: a known length instead</button>${ctScanScale(F()) && Q.cal && Q.cal.method !== 'ct' ? '<button class="go alt" id="cCt">Back to the CT scanner scale</button>' : ''}<button class="go alt" id="cClose">Close</button>`, card => {
  const ct = card.querySelector('#cCt'); if (ct) ct.onclick = () => { snap(); Q.cal = ctScanScale(F()); closeSheet(); msg('Scale from the CT scanner again.'); syncUI(); draw(); };
  card.querySelector('#cBallD').onchange = e => { if (setBallD(e.target.value)) card.querySelector('p').textContent = scaleOf(F()) + '.'; };
  card.querySelector('#cAgain').onclick = () => { closeSheet(); Q.tool = 'ball'; msg(`Tap the middle of the ${Q.ballD} mm ball.`); syncUI(); };
  card.querySelector('#cEdges').onclick = () => { closeSheet(); armBall2(); };
  card.querySelector('#cLen').onclick = () => { closeSheet(); armLen(); };
  card.querySelector('#cClose').onclick = closeSheet;
}); };
$('undoB').onclick = undo; $('menuB').onclick = menu;
$('zIn').onclick = () => zoomBy(1.4); $('zOut').onclick = () => zoomBy(1 / 1.4); $('zFit').onclick = fit;
$('minus').onclick = () => stepSize(-1); $('plus').onclick = () => stepSize(1);
$('sizeSel').onchange = e => setSize(e.target.value);
$('placeB').onclick = () => (Q.tool === 'mirrorTo' ? msg(`Tap the ${Q.joint === 'hip' ? `centre of the ${SIDE[Q.pendCopy]} femoral head` : `middle of the ${SIDE[Q.pendCopy]} knee joint`} to put the copies there.`) : addSheet());   // a pending copy keeps its alignment (Astra, 30 Oct)
$('mirrorB').onclick = () => (F().fv === 'lat' ? flipLat() : mirror());
$('moveB').onclick = () => { const p = $('pad'); p.hidden = !p.hidden; $('moveB').classList.toggle('on', !p.hidden); $('zoom').hidden = !Q.img || !p.hidden; };   // never over the pad (Fable q4 P3)
$('measB').onclick = measMenu; $('doneB').onclick = done;
for (const [id, d] of [['rotL', -1], ['rotR', 1]]) {   // 1 degree a tap; held, it keeps turning
  const b = $(id); let t0 = 0, t1 = 0, snapped = false;
  const stop = () => { clearTimeout(t0); clearInterval(t1); t0 = t1 = 0; };
  b.addEventListener('pointerdown', e => { e.preventDefault(); if (!Q.items[Q.sel]) return; if (!snapped) snap(); snapped = true; turn(d); t0 = setTimeout(() => { t1 = setInterval(() => turn(d), 70); }, 380); });
  b.addEventListener('pointerup', () => { stop(); snapped = false; }); b.addEventListener('pointerleave', () => { stop(); snapped = false; }); b.addEventListener('pointercancel', () => { stop(); snapped = false; });
}
window.addEventListener('beforeunload', e => { if (Q.films.some(f => f.items.length)) { e.preventDefault(); e.returnValue = ''; } });
new ResizeObserver(() => { const had = !!Q.img; resize(); if (had && !Q.items.length) fit(); }).observe($('stage'));
document.addEventListener('dragover', e => e.preventDefault());
document.addEventListener('drop', e => { e.preventDefault(); const f = e.dataTransfer.files && e.dataTransfer.files[0]; if (f) openFile(f); });

(async function boot() {
  try {
    const r = await fetch('library.json', { credentials: 'same-origin' });
    if (r.status === 401 || r.status === 403) { $('openErr').textContent = 'Your templating session has ended. Reload the page to sign in through a.addisortho.com.'; return; }
    Q.lib = await r.json(); Q.lib.families = Array.isArray(Q.lib.families) ? Q.lib.families : [];
    for (const f of Q.lib.families) Q.fam[f.id] = f;
    if (!famsOf('cup').length && !famsOf('stem').length && famsOf('tka-femur').length) Q.joint = 'knee';
    else if (!famsOf('cup').length && !famsOf('stem').length && !famsOf('tka-femur').length && famsOf('nail').length) Q.joint = 'pfna';
    $('jPfna').hidden = !famsOf('nail').length;   // q6: shown only to an account that holds a nail range
    if (!famsOf('cup').length && !famsOf('stem').length && !famsOf('nail').length && famsOf('tka-femur').length === 0 && famsOf('tka-tibia').length === 0) $('openErr').textContent = 'No hip or knee implant range is open to your account yet. Ask Addis Ortho to open one.';
  } catch (e) { $('openErr').textContent = 'The implant library did not load: ' + ((e && e.message) || e); }
  syncUI(); resize();
})();
