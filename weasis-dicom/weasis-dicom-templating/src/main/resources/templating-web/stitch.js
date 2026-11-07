// Joining two to four overlapping exposures of a standing pelvis-to-ankle series into one long-leg film (4 Nov 2026).
// DESIGN-2026-11-04-stitching.md with Fable's review (REVIEW-FABLE-2026-11-04-stitching.md, handled in its section 13).
// Pure: no DOM, no page state. Loaded by both pages only when someone joins films; tests/stitch_test.mjs runs it in Node.
//
// What it does, in the order the sheet (stitchui.js) calls it:
//   keepCopy      a decoded film reduced at once to about STITCH.keepPx (the full decode can then be released), grey 0..1, bone bright
//   toGrid        that copy resampled to the joined film's pixel (STITCH.outMm) by the film's OWN scale (ball, known length) or,
//                 for a film with neither, by a provisional scale that the join then corrects ("carried", always flagged)
//   matchPair     one join: four hypotheses (as is, mirrored, turned, both), coarse to fine normalised correlation on
//                 high-pass features, a bounded turn and size search, per-axis uncertainty in mm, a status in words
//   compose       the joined film as 16-bit grey, row by row, with a short blend at each seam, a text band and seam ticks
//                 burned into the pixels (the warning survives any copy: Fable F3)
//   stitchedDicom the joined film as a derived DICOM (the project image: Fable F2) with the recipe in its header
//   checkSet      refusals before anything is joined: one patient, one study, no duplicates, no CT, same side and orientation
// Scales are never averaged: each film keeps its own; the joined film's mm per pixel is STITCH.outMm by construction.
import { STITCH, STITCH_TAG, SHARED_REV, validateStitch, stitchHash, stitchCal } from './shared.js';
import { el, concat, IMPLEMENTATION_UID } from './dicomwrite.js';
import { zipBytes, unzip } from './zip.js';

const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);

// ---------- grids ----------
function boxReduce(data, cols, rows, f) {
  const nc = Math.max(1, Math.floor(cols / f)), nr = Math.max(1, Math.floor(rows / f)), out = new Float32Array(nc * nr), k = 1 / (f * f);
  for (let y = 0; y < nr; y++) for (let x = 0; x < nc; x++) {
    let s = 0;
    for (let dy = 0; dy < f; dy++) { const r = (y * f + dy) * cols + x * f; for (let dx = 0; dx < f; dx++) s += data[r + dx]; }
    out[y * nc + x] = s * k;
  }
  return { cols: nc, rows: nr, data: out };
}
const bil = (g, x, y) => {   // bilinear sample, NaN outside
  if (!(x >= 0 && y >= 0 && x <= g.cols - 1 && y <= g.rows - 1)) return NaN;
  const x0 = Math.min(g.cols - 2, Math.floor(x)), y0 = Math.min(g.rows - 2, Math.floor(y)), fx = x - x0, fy = y - y0, i = y0 * g.cols + x0, d = g.data;
  if (g.cols < 2 || g.rows < 2) return d[0];
  return (d[i] * (1 - fx) + d[i + 1] * fx) * (1 - fy) + (d[i + g.cols] * (1 - fx) + d[i + g.cols + 1] * fx) * fy;
};
// resample g by k (output pixel = k input pixels), box first when shrinking by 2 or more
function resample(g, k) {
  let src = g, kk = k;
  const f = Math.floor(k); if (f >= 2) { src = boxReduce(g.data, g.cols, g.rows, f); kk = k / f; }
  const nc = Math.max(1, Math.floor(src.cols / kk)), nr = Math.max(1, Math.floor(src.rows / kk)), out = new Float32Array(nc * nr);
  for (let y = 0; y < nr; y++) for (let x = 0; x < nc; x++) { const v = bil(src, Math.min(src.cols - 1, (x + 0.5) * kk - 0.5), Math.min(src.rows - 1, (y + 0.5) * kk - 0.5)); out[y * nc + x] = Number.isFinite(v) ? v : 0; }
  return { cols: nc, rows: nr, data: out };
}

// A decoded film (dicom.js / imageio.js shape) -> {cols, rows, data 0..1 bone bright, f: the integer reduction, srcCols, srcRows}.
export function keepCopy(img, keepPx = STITCH.keepPx) {
  const f = Math.max(1, Math.round(Math.sqrt(img.cols * img.rows / keepPx)));
  const r = f > 1 ? boxReduce(img.data, img.cols, img.rows, f) : { cols: img.cols, rows: img.rows, data: Float32Array.from(img.data) };
  const step = Math.max(1, Math.floor(r.data.length / 200000)), smp = [];
  for (let i = 0; i < r.data.length; i += step) smp.push(r.data[i]);
  smp.sort((a, b) => a - b);
  const lo = smp[Math.floor(smp.length * 0.01)], hi = smp[Math.floor(smp.length * 0.99)], w = Math.max(1e-6, hi - lo);
  for (let i = 0; i < r.data.length; i++) { const v = (r.data[i] - lo) / w; r.data[i] = img.invert ? 1 - v : v; }
  return { ...r, f, srcCols: img.cols, srcRows: img.rows };
}
// the kept copy at the joined film's pixel: mmKeep = mm per pixel of the KEPT copy (film's own scale times f)
export function toGrid(keep, mmKeep, outMm = STITCH.outMm, turned = false) {
  const g = resample(keep, outMm / mmKeep);
  if (turned) g.data.reverse();
  return g;
}

// ---------- features: high-pass, normalised by the local spread (comparable across exposures) ----------
function boxBlur(src, cols, rows, r) {
  const tmp = new Float32Array(src.length), out = new Float32Array(src.length);
  for (let y = 0; y < rows; y++) {
    let s = 0, n = 0; const o = y * cols;
    for (let x = -r; x < cols; x++) {
      const a = x + r, b = x - r - 1;
      if (a < cols) { s += src[o + a]; n++; }
      if (b >= 0) { s -= src[o + b]; n--; }
      if (x >= 0) tmp[o + x] = s / n;
    }
  }
  for (let x = 0; x < cols; x++) {
    let s = 0, n = 0;
    for (let y = -r; y < rows; y++) {
      const a = y + r, b = y - r - 1;
      if (a < rows) { s += tmp[a * cols + x]; n++; }
      if (b >= 0) { s -= tmp[b * cols + x]; n--; }
      if (y >= 0) out[y * cols + x] = s / n;
    }
  }
  return out;
}
function features(g, mmPx) {
  const r = Math.max(1, Math.round(5 / mmPx)), m = boxBlur(g.data, g.cols, g.rows, r), d = new Float32Array(g.data.length);
  for (let i = 0; i < d.length; i++) d[i] = g.data[i] - m[i];
  const sq = new Float32Array(d.length); for (let i = 0; i < d.length; i++) sq[i] = d[i] * d[i];
  const v = boxBlur(sq, g.cols, g.rows, r), f = new Float32Array(d.length), sd = new Float32Array(d.length);
  for (let i = 0; i < d.length; i++) { sd[i] = Math.sqrt(Math.max(0, v[i])); f[i] = d[i] / (sd[i] + 0.02); }   // a running sum can leave -1e-9: no NaN
  return { cols: g.cols, rows: g.rows, data: f, sd, raw: g.data };
}
const flipX = g => { const o = new Float32Array(g.data.length); for (let y = 0; y < g.rows; y++) for (let x = 0; x < g.cols; x++) o[y * g.cols + x] = g.data[y * g.cols + g.cols - 1 - x]; return { cols: g.cols, rows: g.rows, data: o }; };
const flipXY = g => ({ cols: g.cols, rows: g.rows, data: Float32Array.from(g.data).reverse() });

// NCC of A against B placed with B's pixel (0,0) at A's (dx, dy), integer shift, over the intersection
function nccShift(A, B, dx, dy) {
  const x0 = Math.max(0, dx), x1 = Math.min(A.cols, dx + B.cols), y0 = Math.max(0, dy), y1 = Math.min(A.rows, dy + B.rows);
  if (x1 - x0 < 4 || y1 - y0 < 2) return { ncc: NaN, n: 0 };
  let sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0, n = 0;
  for (let y = y0; y < y1; y++) {
    const oa = y * A.cols, ob = (y - dy) * B.cols - dx;
    for (let x = x0; x < x1; x++) { const a = A.data[oa + x], b = B.data[ob + x]; sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b; n++; }
  }
  const va = saa - sa * sa / n, vb = sbb - sb * sb / n;
  return { ncc: va > 1e-9 && vb > 1e-9 ? (sab - sa * sb / n) / Math.sqrt(va * vb) : 0, n };
}
// NCC with B turned by rot (deg) and scaled by s about the point cB (B px), cB landing on cA (A px)
function nccAffine(A, B, cA, cB, rot, s, stride = 1) {
  const t = rot * Math.PI / 180, c = Math.cos(t), sn = Math.sin(t);
  let sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0, n = 0;
  const yr0 = Math.max(0, Math.floor(cA.y - cB.y * s - 2)), yr1 = Math.min(A.rows, Math.ceil(cA.y + (B.rows - cB.y) * s + 2));
  for (let y = yr0; y < yr1; y += stride) for (let x = 0; x < A.cols; x += stride) {
    const ux = (x - cA.x) / s, uy = (y - cA.y) / s, bx = cB.x + c * ux + sn * uy, by = cB.y - sn * ux + c * uy;
    const b = bil(B, bx, by); if (!Number.isFinite(b)) continue;
    const a = A.data[y * A.cols + x]; sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b; n++;
  }
  if (n < 50) return -1;
  const va = saa - sa * sa / n, vb = sbb - sb * sb / n;
  return va > 1e-9 && vb > 1e-9 ? (sab - sa * sb / n) / Math.sqrt(va * vb) : 0;
}

// coarse search of every overlap height and sideways shift: {best, second (>= marginMm away), worst (most negative)}
function coarseSearch(A, B, mmPx) {
  const hmin = Math.max(2, Math.ceil(STITCH.minOverlapMm / mmPx)), hmax = Math.max(hmin, Math.floor(STITCH.maxOverlapFrac * Math.min(A.rows, B.rows)));
  const cx = Math.round((A.cols - B.cols) / 2), sx = Math.round(STITCH.maxShiftFrac * A.cols), cand = [];
  let worst = 1;
  for (let h = hmin; h <= hmax; h++) for (let dx = cx - sx; dx <= cx + sx; dx++) {
    const { ncc } = nccShift(A, B, dx, A.rows - h);
    if (!Number.isFinite(ncc)) continue;
    cand.push({ dx, dy: A.rows - h, ncc }); if (ncc < worst) worst = ncc;
  }
  cand.sort((a, b) => b.ncc - a.ncc);
  const best = cand[0] || null, sep = STITCH.marginMm / mmPx;
  const second = best ? cand.find(c => Math.hypot(c.dx - best.dx, c.dy - best.dy) >= sep) || null : null;
  return { best, second, worst };
}
const refineInt = (A, B, p, rad) => {
  let best = { ...p, ncc: -2 };
  for (let dy = p.dy - rad; dy <= p.dy + rad; dy++) for (let dx = p.dx - rad; dx <= p.dx + rad; dx++) { const { ncc } = nccShift(A, B, dx, dy); if (ncc > best.ncc) best = { dx, dy, ncc }; }
  return best;
};
// half-width (px) of the correlation peak along one axis: how far the score stays within 0.05 of the peak (Fable F7: a ridge)
function halfWidth(A, B, p, axis, maxPx) {
  let w = 0;
  for (const sgn of [1, -1]) for (let k = 1; k <= maxPx; k++) {
    const q = axis === 'x' ? nccShift(A, B, p.dx + sgn * k, p.dy) : nccShift(A, B, p.dx, p.dy + sgn * k);
    if (!(q.ncc >= p.ncc - 0.05)) { w = Math.max(w, k - 1); break; }
    if (k === maxPx) w = Math.max(w, k);
  }
  return w;
}
const pyr = (g, from, to) => (to / from >= 2 ? boxReduce(g.data, g.cols, g.rows, Math.round(to / from)) : g);

// One join. A above B, both grids at outMm. opt.carried: B has no scale of its own (its size is searched, ±STITCH.carryScale).
// Returns {status, why, ncc, margin, uxMm, uyMm, texture, rot, s, dSize, flip, inverted, dx, dy (B px (0,0) in A px, before
// rot/s about cB), cA, cB} where the placement is: p_A = cA + R(rot) * s * (p_B - cB).
// Blank rows at a film's edge (collimation, a label burned on black): rows far emptier than the film's typical row. Measured against
// the film itself, so a narrow limb in a lot of air (an ankle film) is not called blank. Scans from the edge and stops at the first
// lasting content; at most a quarter of the film. Returns the number of rows.
export function blankRows(g, fromBottom) {
  const occ = new Float32Array(g.rows);
  for (let y = 0; y < g.rows; y++) { let n = 0; const o = y * g.cols; for (let x = 0; x < g.cols; x++) { const v = g.data[o + x]; if (v > 0.03 && v < 1.1) n++; } occ[y] = n / g.cols; }
  const med = Float32Array.from(occ).sort()[g.rows >> 1], lim = Math.min(0.5, 0.35 * med), cap = g.rows >> 2;
  let last = 0, run = 0;
  for (let k = 0; k < cap; k++) { if (occ[fromBottom ? g.rows - 1 - k : k] < lim) { last = k + 1; run = 0; } else if (++run >= 8) break; }
  return last;
}
export function matchPair(A, B, opt = {}) {
  const outMm = opt.outMm || STITCH.outMm, minTrim = Math.round(2 / outMm), ta = blankRows(A, true), tb = blankRows(B, false), trimA = ta >= minTrim ? ta : 0, trimB = tb >= minTrim ? tb : 0;
  const A1 = trimA ? { cols: A.cols, rows: A.rows - trimA, data: A.data.subarray(0, A.cols * (A.rows - trimA)) } : A;
  const B1 = trimB ? { cols: B.cols, rows: B.rows - trimB, data: B.data.subarray(B.cols * trimB) } : B;
  const r = matchCore(A1, B1, opt);
  if (r.cB) r.cB = { x: r.cB.x, y: r.cB.y + trimB };   // back in B's own pixels; A was cut at its foot, so its pixels are unchanged
  return { ...r, trimA, trimB };
}
function matchCore(A, B, { outMm = STITCH.outMm, carried = false } = {}) {
  const lv = { c: STITCH.coarseMm, f: STITCH.fineMm };
  const Ac = features(pyr(A, outMm, lv.c), lv.c), Af = features(pyr(A, outMm, lv.f), lv.f);
  const hyp = { up: B, mirror: flipX(B), turned: flipXY(B), both: flipX(flipXY(B)) };
  const scores = {};
  for (const [k, g] of Object.entries(hyp)) scores[k] = coarseSearch(Ac, features(pyr(g, outMm, lv.c), lv.c), lv.c);
  const up = scores.up, flipBest = Object.entries(scores).filter(([k]) => k !== 'up').map(([k, v]) => ({ hyp: k, ncc: v.best ? v.best.ncc : -1 })).sort((a, b) => b.ncc - a.ncc)[0];
  const out = { ncc: up.best ? up.best.ncc : NaN, flip: flipBest, inverted: false };
  if (!up.best) return { ...out, status: 'failed', why: 'the films do not overlap enough to compare' };
  if (-up.worst > Math.max(STITCH.nccOk, up.best.ncc + STITCH.flipMargin)) return { ...out, status: 'inverted', inverted: true, why: 'one film looks inverted (bone dark) against the other: check its grey' };
  if (flipBest.ncc > up.best.ncc + STITCH.flipMargin && flipBest.ncc >= 0.6) return { ...out, status: 'flipped', why: `the lower film fits better ${flipBest.hyp === 'mirror' ? 'mirrored' : flipBest.hyp === 'turned' ? 'turned upside down' : 'mirrored and turned'}: it may be exported the wrong way, or the films may be in the wrong order; check the order and its R/L marker` };
  // fine level (1 mm): every overlap height again, sideways near the coarse answer, ranked by a lower confidence bound
  // (a short overlap correlates well by chance; a bare shaft correlates at every height: Fable F7)
  const Bf = features(pyr(B, outMm, lv.f), lv.f), k = lv.c / lv.f;
  const hmin = Math.ceil(STITCH.minOverlapMm / lv.f), hmax = Math.floor(STITCH.maxOverlapFrac * Math.min(Af.rows, Bf.rows)), dxc = Math.round(up.best.dx * k);
  const lcb = (ncc, n) => ncc - 2 / Math.sqrt(Math.max(1, n / 25));
  const prof = [];   // best over dx for every height
  let p = null;
  for (let h = hmin; h <= hmax; h++) {
    let bh = null;
    for (let dx = dxc - Math.ceil(k) - 2; dx <= dxc + Math.ceil(k) + 2; dx++) { const q = nccShift(Af, Bf, dx, Af.rows - h); if (!Number.isFinite(q.ncc)) continue; const sc = lcb(q.ncc, q.n); if (!bh || sc > bh.sc) bh = { dx, dy: Af.rows - h, ncc: q.ncc, sc }; }
    if (bh) { prof.push(bh); if (!p || bh.sc > p.sc) p = bh; }
  }
  if (!p) return { ...out, status: 'failed', why: 'the films do not overlap enough to compare' };
  const sep = STITCH.marginMm / lv.f, second = prof.filter(q => Math.abs(q.dy - p.dy) >= sep).sort((a, b) => b.sc - a.sc)[0];
  const margin = second ? p.ncc - second.ncc : 1;
  const uxMm = halfWidth(Af, Bf, p, 'x', 20) * lv.f, uyMm = halfWidth(Af, Bf, p, 'y', 20) * lv.f;
  // turn and size, bounded, at the fine level
  const ovRows = Af.rows - p.dy, cB = { x: Bf.cols / 2, y: Math.max(1, ovRows / 2) }, cA0 = { x: p.dx + cB.x, y: p.dy + cB.y };
  const rotLim = STITCH.maxTurnDeg, sLim = STITCH.carryScale;
  const descend = (G, H, c0, cBx, rot0, s0, steps, free, stride) => {
    let cur = { cA: { ...c0 }, rot: rot0, s: s0, ncc: nccAffine(G, H, c0, cBx, rot0, s0, stride) };
    for (const st of steps) {
      let moved = true, guard = 0;
      while (moved && guard++ < 40) {
        moved = false;
        const tries = [[0, 0, st.m, 0], [0, 0, -st.m, 0], [0, 0, 0, st.m], [0, 0, 0, -st.m]];
        if (free.rot) tries.push([st.t, 0, 0, 0], [-st.t, 0, 0, 0]);
        if (free.s) tries.push([0, st.s, 0, 0], [0, -st.s, 0, 0]);
        for (const [dr, ds, mx, my] of tries) {
          const rot = cur.rot + dr, sc = cur.s + ds; if (Math.abs(rot) > rotLim + 1e-9 || Math.abs(sc - 1) > sLim + 1e-9) continue;
          const cA = { x: cur.cA.x + mx, y: cur.cA.y + my }, v = nccAffine(G, H, cA, cBx, rot, sc, stride);
          if (v > cur.ncc + 1e-6) { cur = { cA, rot, s: sc, ncc: v }; moved = true; }
        }
      }
    }
    return cur;
  };
  const cur = descend(Af, Bf, cA0, cB, 0, 1, [{ t: 0.5, s: 0.01, m: 1 }, { t: 0.25, s: 0.005, m: 0.5 }, { t: 0.1, s: 0.0025, m: 0.25 }], { rot: true, s: true }, 1);
  const plain = nccAffine(Af, Bf, cur.cA, cB, 0, carried ? cur.s : 1);
  const withTurn = nccAffine(Af, Bf, cur.cA, cB, cur.rot, carried ? cur.s : 1);
  const rot1 = withTurn - plain >= STITCH.turnGain ? cur.rot : 0;
  // the joined film's own pixel: placement (turn kept or 0; size free only for a carried film), and the size the overlap
  // would like, measured separately (for a film with its own scale it is reported as the disagreement, never applied)
  const A0 = features(A, outMm), B0 = features(B, outMm), kf = lv.f / outMm, cB0 = { x: cB.x * kf, y: cB.y * kf }, c0 = { x: cur.cA.x * kf, y: cur.cA.y * kf };
  // a turn noise alone would suggest is not kept: it must also gain at the film's own pixel (Fable, 6 Nov 2026)
  const s0 = carried ? cur.s : 1, keepRot = rot1 && nccAffine(A0, B0, c0, cB0, rot1, s0, 2) - nccAffine(A0, B0, c0, cB0, 0, s0, 2) >= STITCH.turnGain / 2 ? rot1 : 0;
  const judged = keepRot ? withTurn : plain;   // the score the status is judged on: 1 mm, the placement actually kept. At 0.25 mm mottle dominates (best, below, is for information)
  const fineSteps = [{ t: 0.05, s: 0.002, m: 2 }, { t: 0.05, s: 0.001, m: 1 }, { t: 0.05, s: 0.0005, m: 0.5 }, { t: 0.05, s: 0.00025, m: 0.25 }];
  const fin = descend(A0, B0, c0, cB0, keepRot, carried ? cur.s : 1, fineSteps, { rot: false, s: carried }, 2);
  const meas = carried ? fin : descend(A0, B0, fin.cA, cB0, keepRot, cur.s, fineSteps, { rot: false, s: true }, 2);
  const keepS = carried ? fin.s : 1, best = fin.ncc, cA = fin.cA, dSize = meas.s - 1;
  // texture: the strongest local spread in the overlap (95th percentile) against the film's own noise, so a film whose grey range
  // is squeezed by a saturated area is judged like any other; a flat band of soft tissue or a blank film has none
  const sds = [];
  for (let y = Math.max(0, p.dy); y < Af.rows; y += 2) for (let x = Math.max(0, p.dx); x < Math.min(Af.cols, p.dx + Bf.cols); x += 2) sds.push(Af.sd[y * Af.cols + x]);
  sds.sort((a, b) => a - b);
  const dif = []; for (let i = 0; i + 1 < Af.data.length; i += 7) if ((i + 1) % Af.cols) dif.push(Math.abs(Af.raw[i + 1] - Af.raw[i]));
  dif.sort((a, b) => a - b);
  const noise = Math.max(1e-3, (dif.length ? dif[Math.floor(dif.length / 2)] : 0) / (0.6745 * Math.SQRT2));
  const texture = sds.length ? sds[Math.floor(sds.length * 0.95)] / noise : 0;
  const res = { ...out, ncc: judged, nccFine: best, margin, uxMm, uyMm, texture, rot: keepRot, s: keepS, dSize: carried ? null : dSize, wanted: { rot: cur.rot, s: meas.s }, cA, cB: cB0, overlapMm: (A.rows - (cA.y - cB0.y * keepS)) * outMm };
  if (carried && Math.abs(fin.s - 1) > sLim - 0.002) return { ...res, status: 'failed', sizeOff: true, why: `the film's size differs from its neighbour's by ${Math.round(Math.abs(fin.s - 1) * 1000) / 10} % or more: give it its own ball or a known length` };
  if (texture < STITCH.minTexture) return { ...res, status: 'failed', why: 'the overlap shows no bone detail to match' };
  if (!(judged >= STITCH.nccWeak)) return { ...res, status: 'failed', why: 'no convincing match in the overlap' };
  const weak = [];
  if (judged < STITCH.nccOk) weak.push('the match score is low');
  if (margin < STITCH.marginMin) weak.push('another position fits almost as well');
  if (uyMm > STITCH.ridgeMm) weak.push(`the match could slide ${uyMm} mm along the bone (no feature across it in the overlap)`);
  if (uxMm > STITCH.ridgeMm) weak.push(`the match could slide ${uxMm} mm sideways`);
  return weak.length ? { ...res, status: 'weak', why: weak.join('; ') } : { ...res, status: 'matched', why: '' };
}
// a join placed by hand: the score at that place, for information
export function scoreAt(A, B, cA, cB, rot, s, outMm = STITCH.outMm) { return nccAffine(features(A, outMm), features(B, outMm), cA, cB, rot, s, 2); }

// ---------- compose ----------
// placement of B in A's pixels -> affine [a, b, c, d, e, f]: x' = a x + b y + c, y' = d x + e y + f
const affOf = (cA, cB, rot, s) => { const t = rot * Math.PI / 180, c = Math.cos(t) * s, sn = Math.sin(t) * s; return [c, -sn, cA.x - c * cB.x + sn * cB.y, sn, c, cA.y - sn * cB.x - c * cB.y]; };
const affMul = (P, Q) => [P[0] * Q[0] + P[1] * Q[3], P[0] * Q[1] + P[1] * Q[4], P[0] * Q[2] + P[1] * Q[5] + P[2], P[3] * Q[0] + P[4] * Q[3], P[3] * Q[1] + P[4] * Q[4], P[3] * Q[2] + P[4] * Q[5] + P[5]];
const affInv = A => { const det = A[0] * A[4] - A[1] * A[3], a = A[4] / det, b = -A[1] / det, d = -A[3] / det, e = A[0] / det; return [a, b, -(a * A[2] + b * A[5]), d, e, -(d * A[2] + e * A[5])]; };
const apply = (A, x, y) => ({ x: A[0] * x + A[1] * y + A[2], y: A[3] * x + A[4] * y + A[5] });
const Q_OFF = -0.25, Q_SPAN = 1.5;   // grey 0..1 (with headroom) to 0..65535
const q16 = v => clamp(Math.round((v - Q_OFF) / Q_SPAN * 65535), 0, 65535);

// grids: [{cols, rows, data}] top to bottom at outMm; joins: [{cA, cB, rot, s}] (B in A's pixels). Returns the joined film.
export function compose(grids, joins, { outMm = STITCH.outMm, label = '' } = {}) {
  let T = [[1, 0, 0, 0, 1, 0]];
  for (let i = 0; i < joins.length; i++) T.push(affMul(T[i], affOf(joins[i].cA, joins[i].cB, joins[i].rot || 0, joins[i].s || 1)));
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  grids.forEach((g, i) => { for (const [x, y] of [[0, 0], [g.cols, 0], [0, g.rows], [g.cols, g.rows]]) { const p = apply(T[i], x, y); x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y); } });
  const band = Math.round(STITCH.bandMm / outMm), cols = Math.ceil(x1 - x0), rows = Math.ceil(y1 - y0) + band;
  T = T.map(A => [A[0], A[1], A[2] - x0, A[3], A[4], A[5] - y0 + band]);
  const inv = T.map(affInv);
  // seams: halfway through each overlap, measured down the middle of the film
  const seams = joins.map((j, i) => { const mid = grids[i].cols / 2, bot = apply(T[i], mid, grids[i].rows - (j.trimA || 0)).y, top = apply(T[i + 1], grids[i + 1].cols / 2, j.trimB || 0).y; return (bot + top) / 2; });
  // grey: each film matched to the one above it on their overlap (gain and offset), chained from the top film
  const gain = [{ g: 1, o: 0 }];
  for (let i = 0; i < joins.length; i++) {
    let n = 0, sa = 0, sb = 0, sab = 0, sbb = 0;
    const yA = Math.max(0, Math.floor(apply(T[i + 1], 0, joins[i].trimB || 0).y)), yB = Math.min(rows, Math.ceil(apply(T[i], 0, grids[i].rows - (joins[i].trimA || 0)).y));
    for (let y = yA; y < yB; y += 2) for (let x = 0; x < cols; x += 2) {
      const pa = apply(inv[i], x, y), pb = apply(inv[i + 1], x, y), a = bil(grids[i], pa.x, pa.y), b = bil(grids[i + 1], pb.x, pb.y);
      if (!Number.isFinite(a) || !Number.isFinite(b)) continue;
      const av = gain[i].g * a + gain[i].o; n++; sa += av; sb += b; sab += av * b; sbb += b * b;
    }
    const den = n * sbb - sb * sb, g = n > 50 && den > 1e-9 ? clamp((n * sab - sa * sb) / den, 0.25, 4) : 1, o = n > 50 ? (sa - g * sb) / n : 0;
    gain.push({ g, o });
  }
  const u16 = new Uint16Array(cols * rows), half = STITCH.blendMm / outMm / 2;
  for (let y = band; y < rows; y++) {
    let k = 0; while (k < seams.length && y > seams[k] + half) k++;          // the film this row belongs to below its seam band
    for (let x = 0; x < cols; x++) {
      const val = i => { const p = apply(inv[i], x, y), v = bil(grids[i], p.x, p.y); return Number.isFinite(v) ? gain[i].g * v + gain[i].o : NaN; };
      let v;
      if (k < seams.length && y >= seams[k] - half) {   // inside a seam band: a short blend between the two films
        const a = val(k), b = val(k + 1), w = clamp((y - (seams[k] - half)) / (2 * half), 0, 1);
        v = Number.isFinite(a) && Number.isFinite(b) ? a * (1 - w) + b * w : Number.isFinite(a) ? a : b;
      } else { v = val(k); if (!Number.isFinite(v)) { for (let i = 0; i < grids.length && !Number.isFinite(v); i++) v = val(i); } }
      u16[y * cols + x] = Number.isFinite(v) ? q16(v) : 0;
    }
  }
  burnBand(u16, cols, band, outMm, label);
  const tick = Math.round(STITCH.tickMm / outMm), th = Math.max(2, Math.round(0.6 / outMm));
  for (const s of seams) for (let dy = -th; dy <= th; dy++) { const y = Math.round(s) + dy; if (y < band || y >= rows) continue; for (let x = 0; x < tick && x < cols; x++) { u16[y * cols + x] = 65535; u16[y * cols + cols - 1 - x] = 65535; } }
  return { cols, rows, u16, seams, band, T, gain, q: { off: Q_OFF, span: Q_SPAN } };
}

// ---------- the text band: a 5 x 7 font, so the words are pixels of the film (Fable F3) ----------
const FONT = { A: '0E11111F111111', B: '1E11111E11111E', C: '0E11101010110E', D: '1E11111111111E', E: '1F10101E10101F', F: '1F10101E101010', G: '0E11101711110F', H: '1111111F111111', I: '0E04040404040E', J: '0702020202120C', K: '11121418141211', L: '1010101010101F', M: '111B1515111111', N: '11191513111111', O: '0E11111111110E', P: '1E11111E101010', Q: '0E111111151209', R: '1E11111E141211', S: '0F10100E01011E', T: '1F040404040404', U: '1111111111110E', V: '11111111110A04', W: '11111115151B11', X: '11110A040A1111', Y: '11110A04040404', Z: '1F01020408101F', 0: '0E11131519110E', 1: '040C040404040E', 2: '0E11010204081F', 3: '1F02040201110E', 4: '02060A121F0202', 5: '1F101E0101110E', 6: '0608101E11110E', 7: '1F010204080808', 8: '0E11110E11110E', 9: '0E11110F01020C', '.': '00000000000C0C', ',': '00000000000C04', ':': '000C0C000C0C00', '-': '0000001F000000', '(': '02040808080402', ')': '08040202020408', '%': '18190204081303', '/': '00010204081000', "'": '0C040800000000', ' ': '00000000000000' };
function burnBand(u16, cols, band, outMm, text) {
  for (let i = 0; i < band * cols; i++) u16[i] = 0;
  const px = Math.max(1, Math.round(0.4 / outMm)), cw = 6 * px, per = Math.max(10, Math.floor((cols - 4 * px) / cw));
  const words = String(text || '').toUpperCase().split(/\s+/), lines = [''];
  for (const w of words) { const l = lines[lines.length - 1]; if ((l ? l + ' ' + w : w).length > per && l) lines.push(w); else lines[lines.length - 1] = l ? l + ' ' + w : w; }
  lines.slice(0, Math.max(1, Math.floor(band / (9 * px)))).forEach((line, li) => {
    [...line].forEach((ch, ci) => {
      const g = FONT[ch] || FONT[' '];
      for (let r = 0; r < 7; r++) { const bits = parseInt(g.slice(2 * r, 2 * r + 2), 16); for (let c = 0; c < 5; c++) if (bits & (16 >> c)) for (let yy = 0; yy < px; yy++) for (let xx = 0; xx < px; xx++) { const x = 2 * px + ci * cw + c * px + xx, y = px + li * 9 * px + r * px + yy; if (x < cols && y < band) u16[y * cols + x] = 61000; } }
    });
  });
}
export const bandText = r => `${r.segs.map((g, i) => (g.how === 'assumed' ? `Film ${i + 1} scale assumed from film ${g.from + 1}, not measured. ` : '')).join('')}Stitched from ${r.n} films. Joins at ${r.joins.map(j => Math.round((j.seamY - (r.band || 0)) * r.outMm)).join(', ')} mm from the top. Alignment and lengths across a join are not validated. Addis Ortho templating, draft.`;

// ---------- refusals before joining (Fable F5, F14, F22) ----------
// list: [{meta, sha, kind}] in order. Returns {refuse: [reasons], warn: [], compared: [field names]}.
export function checkSet(list) {
  const refuse = [], warn = [], compared = new Set();
  if (list.length < STITCH.minSeg || list.length > STITCH.maxSeg) refuse.push(`join ${STITCH.minSeg} to ${STITCH.maxSeg} films`);
  const shas = list.map(s => s.sha).filter(Boolean);
  if (new Set(shas).size !== shas.length) refuse.push('the same file is in the set twice');
  for (const [i, s] of list.entries()) if (String((s.meta || {}).Modality || '').toUpperCase() === 'CT') refuse.push(`film ${i + 1} is a CT image: a CT scout is already one image`);
  for (const [i, s] of list.entries()) if (String((s.meta || {}).ImageComments || '').startsWith(STITCH_TAG) || /^STITCHED LONG LEG/.test(String((s.meta || {}).SeriesDescription || ''))) refuse.push(`film ${i + 1} is already a joined film: join the original exposures`);
  const field = (k, what, hard = true) => {
    const vals = list.map(s => String((s.meta || {})[k] || '').trim().toUpperCase()).filter(Boolean);
    if (vals.length >= 2) { compared.add(k); if (new Set(vals).size > 1) (hard ? refuse : warn).push(`the films differ in ${what}`); }
  };
  field('PatientID', 'record number (patient ID)');
  if (!compared.has('PatientID')) field('PatientName', 'patient name');
  field('IssuerOfPatientID', 'record number issuer');
  field('StudyInstanceUID', 'study (they are not from one examination)');
  field('ImageLaterality', 'side'); field('Laterality', 'side');
  field('PatientOrientation', 'orientation');
  field('SeriesInstanceUID', 'series', false);
  field('StudyDate', 'study date');
  if (list.some(s => !s.meta || !s.meta.PatientID)) warn.push('at least one film carries no patient details: only your confirmation says these films belong together');
  return { refuse: [...new Set(refuse)], warn: [...new Set(warn)], compared: [...compared] };
}

// ---------- memory: what a set needs at its peak, counted before decoding (Fable F8) ----------
// seg: [{cols, rows, kind}] as read from headers; outPx: the joined film's pixels. Bytes.
export function budgetBytes(seg, outPx, keptFileBytes = 0) {
  const big = Math.max(...seg.map(s => s.cols * s.rows * (s.kind === 'dicom' ? 4 + 2 : 4 + 4 + 4)));   // one full decode at a time, with its transient
  const kept = seg.reduce((a, s) => a + Math.min(s.cols * s.rows, STITCH.keepPx * 1.3) * 4, 0);
  const grids = 2 * outPx * 4 / Math.max(1, seg.length - 1);   // two films' grids at the joined pixel while one join is matched
  const feat = 7 * 4 * outPx * 2 / Math.max(1, seg.length);   // matchPair's feature arrays for two films at the joined pixel
  return Math.round(big + kept + grids + feat + keptFileBytes + outPx * (2 + 2 + 4 + 4 + 4));   // the 16-bit film, its DICOM, decoded Float32, ImageData, canvas
}

// ---------- the joined film as a derived DICOM: the project's image (Fable F2) ----------
const ds = v => { const s = String(Number(Number(v).toPrecision(10))); return s.length <= 16 ? s : Number(v).toExponential(8); };
export async function sha256hex(u8) { const d = new Uint8Array(await crypto.subtle.digest('SHA-256', u8)); return [...d].map(b => b.toString(16).padStart(2, '0')).join(''); }
async function uidFrom(hex, what) {
  const h = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${hex}:${what}`)));
  let v = 0n; for (let i = 0; i < 16; i++) v = (v << 8n) | BigInt(h[i]);
  return '2.25.' + v.toString();
}
async function deflateRaw(u8) {
  const s = new Blob([u8]).stream().pipeThrough(new CompressionStream('deflate-raw'));
  return new Uint8Array(await new Response(s).arrayBuffer());
}
const SC = '1.2.840.10008.5.1.4.1.1.7', DEFLATED = '1.2.840.10008.1.2.1.99', EXPLICIT = '1.2.840.10008.1.2.1';
// film: compose()'s result; recipe: the checked record; meta: the first DICOM film's attributes ({} when none); sources: [{cls, uid}]
export async function stitchedDicom(film, recipe, meta = {}, sources = [], { deflate = true } = {}) {
  const why = validateStitch(recipe); if (why) throw new Error('The stitch record is not usable: ' + why);
  const json = JSON.stringify(recipe), key = stitchHash(recipe) + recipe.segs.map(s => s.sha || '').join('');
  const sop = await uidFrom(key, 'stitched'), series = await uidFrom(key, 'stitched-series');
  const opt = (tag, vr, v) => (v !== undefined && v !== null && v !== '' ? el(tag, vr, v) : null);
  const a = recipe.segs[recipe.anchor];
  const how = `Joined from ${recipe.n} exposures by Addis Ortho templating (${SHARED_REV}); each film scaled by its own ball or known length, or carried through a join where it has none; scale shown for film ${recipe.anchor + 1}. Not an acquisition. Alignment and lengths across a join are not validated.`;
  const px = new Uint8Array(film.u16.buffer.slice(film.u16.byteOffset, film.u16.byteOffset + film.u16.byteLength));
  let lo = 0, hi = 65535; { const smp = []; const st = Math.max(1, Math.floor(film.u16.length / 200000)); for (let i = film.band * film.cols; i < film.u16.length; i += st) smp.push(film.u16[i]); smp.sort((x, y) => x - y); if (smp.length) { lo = smp[Math.floor(smp.length * 0.01)]; hi = smp[Math.floor(smp.length * 0.99)]; } }
  const body = concat([
    opt('00080005', 'CS', meta.SpecificCharacterSet),
    el('00080008', 'CS', 'DERIVED\\SECONDARY\\STITCHED'), el('00080016', 'UI', SC), el('00080018', 'UI', sop),
    el('00080023', 'DA', ''), el('00080033', 'TM', ''), el('00080070', 'LO', 'Addis Ortho'),
    opt('00080020', 'DA', meta.StudyDate), opt('00080030', 'TM', meta.StudyTime), opt('00080050', 'SH', meta.AccessionNumber),
    el('00080060', 'CS', meta.Modality && meta.Modality !== 'picture' ? meta.Modality : 'OT'), el('00080064', 'CS', 'WSD'), opt('00080080', 'LO', meta.InstitutionName),
    opt('00081030', 'LO', meta.StudyDescription), el('0008103E', 'LO', `STITCHED LONG LEG (${recipe.n} exposures)`),
    el('00082111', 'ST', how),
    sources.length ? el('00082112', 'SQ', sources.map(s => [el('00081150', 'UI', s.cls), el('00081155', 'UI', s.uid)])) : null,
    opt('00100010', 'PN', meta.PatientName), opt('00100020', 'LO', meta.PatientID), opt('00100021', 'LO', meta.IssuerOfPatientID),
    opt('00100030', 'DA', meta.PatientBirthDate), opt('00100040', 'CS', meta.PatientSex), opt('00101010', 'AS', meta.PatientAge),
    el('00180015', 'CS', 'LOWER LIMB'), el('00185101', 'CS', 'AP'),
    opt('0020000D', 'UI', meta.StudyInstanceUID), el('0020000E', 'UI', series), opt('00200010', 'SH', meta.StudyID), el('00200011', 'IS', '991'), el('00200013', 'IS', '1'),
    opt('00200020', 'CS', meta.PatientOrientation), opt('00200060', 'CS', meta.Laterality), opt('00200062', 'CS', meta.ImageLaterality),
    el('00204000', 'LT', STITCH_TAG + json),
    el('00280002', 'US', 1), el('00280004', 'CS', 'MONOCHROME2'),
    el('00280010', 'US', film.rows), el('00280011', 'US', film.cols), el('00280030', 'DS', `${ds(recipe.outMm)}\\${ds(recipe.outMm)}`),
    el('00280100', 'US', 16), el('00280101', 'US', 16), el('00280102', 'US', 15), el('00280103', 'US', 0),
    el('00280A02', 'CS', 'FIDUCIAL'), el('00280A04', 'LO', `stitched; film ${recipe.anchor + 1} ${a.how === 'ball' ? `${a.d || 25} mm ball` : 'known length'}`.slice(0, 64)),
    el('00281050', 'DS', ds((lo + hi) / 2)), el('00281051', 'DS', ds(Math.max(1, hi - lo))),
    el('00281052', 'DS', '0'), el('00281053', 'DS', '1'),
    el('7FE00010', 'OW', px),
  ].filter(Boolean));
  const metaEls = [el('00020001', 'OB', new Uint8Array([0, 1])), el('00020002', 'UI', SC), el('00020003', 'UI', sop),
    el('00020010', 'UI', deflate ? DEFLATED : EXPLICIT), el('00020012', 'UI', IMPLEMENTATION_UID), el('00020013', 'SH', 'AOC_TPL_1')];
  let packed = body, ts = EXPLICIT;
  if (deflate) { try { packed = await deflateRaw(body); ts = DEFLATED; } catch (e) { packed = body; ts = EXPLICIT; } }   // an older browser without deflate-raw: written uncompressed
  metaEls[3] = el('00020010', 'UI', ts);
  const group = concat(metaEls);
  return concat([new Uint8Array(128), new TextEncoder().encode('DICM'), el('00020000', 'UL', group.length), group, packed]);
}

// the calibration a page gives a joined film lives in shared.js (stitchCal), so a page can read a joined film without this module
export const calOf = stitchCal;

// ---------- the film set: the original files byte for byte, plus the recipe (Sami, 4 Nov: originals kept, outside the project) ----------
export const SET_FORMAT = 'aoc-stitch-set';
export function filmSet(files, recipe) {   // files: [{bytes, ext}] in order
  const entries = files.map((f, i) => ({ name: `film-${i + 1}.${f.ext}`, data: f.bytes }));
  entries.push({ name: 'recipe.json', data: new TextEncoder().encode(JSON.stringify({ format: SET_FORMAT, v: 1, recipe })) });
  return zipBytes(entries);
}
export async function openFilmSet(u8) {
  const ents = await unzip(u8, { maxEntries: STITCH.maxSeg + 1, maxTotal: STITCH.maxSeg * 81 * 1024 * 1024 });
  const rj = ents.get('recipe.json'); if (!rj) throw new Error('This is not a film set: it has no recipe.');
  let o; try { o = JSON.parse(new TextDecoder().decode(rj)); } catch (e) { throw new Error('The film set\'s recipe cannot be read.'); }
  if (!o || o.format !== SET_FORMAT || o.v !== 1) throw new Error('This is not a film set of this page.');
  const r = o.recipe, files = [];
  if (validateStitch(r)) throw new Error('The film set\'s recipe is not usable: ' + validateStitch(r));
  for (let i = 0; i < r.n; i++) {
    const name = [...ents.keys()].find(x => new RegExp(`^film-${i + 1}\\.(dcm|png|jpg|webp)$`).test(x));
    if (!name) throw new Error(`The film set lacks film ${i + 1}.`);
    const e = { name, data: ents.get(name) }, sha = await sha256hex(e.data);
    if (r.segs[i].sha && r.segs[i].sha !== sha) throw new Error(`Film ${i + 1} in the set is not the file the join was made on.`);
    files.push({ bytes: e.data, name: e.name, sha });
  }
  return { recipe: r, files };
}

// ---------- the whole set: scales (own, or carried through a join), every join, the recipe ----------
// seg: {keep (keepCopy), how: 'ball' | 'line' | 'carried', ball {x, y, r} / line [p, q] + len (ORIGINAL file pixels), d (ball mm),
//       turned (180 degrees), sha, ips (detector pitch, for a carried film's first guess only)}
export function scaleOf(seg) {   // mm per pixel of the ORIGINAL file, from the film's own ball or length; null for carried
  if (seg.how === 'ball' && seg.ball && seg.ball.r > 0) return (seg.d || 25) / (2 * seg.ball.r);
  if (seg.how === 'line' && seg.line && seg.len > 0) { const L = Math.hypot(seg.line[1].x - seg.line[0].x, seg.line[1].y - seg.line[0].y); return L > 0 ? seg.len / L : null; }
  return null;
}
export const defaultAnchor = segs => { const m = segs.map((s, i) => (scaleOf(s) ? i : -1)).filter(i => i >= 0); const want = (segs.length - 1) * 0.6; return m.length ? m.reduce((a, b) => (Math.abs(b - want) < Math.abs(a - want) ? b : a)) : -1; };
const tick = () => new Promise(r => setTimeout(r, 0));
const orient = (A, B, outMm, carried) => matchPair(A, B, { outMm, carried });

// Returns {grids, joins, mm (per original px, per film), from, errors}. joins[i]: matchPair's result for films i and i+1.
export async function joinAll(segs, { outMm = STITCH.outMm, onStep = () => {} } = {}) {
  const n = segs.length, mm = segs.map(scaleOf), from = segs.map(() => null), assumed = segs.map(() => null), errors = [], warnings = [];
  if (!mm.some(Boolean)) return { errors: ['no film has a ball or a known length: at least one must, to give the joined film a scale'] };
  const grid = i => toGrid(segs[i].keep, mm[i] * segs[i].keep.f, outMm, !!segs[i].turned);
  // carried films, nearest to a measured one first: first guess from the neighbour (same original pixel, or the detector pitches' ratio)
  const order = [...Array(n).keys()].filter(i => !mm[i]).sort((a, b) => dist(a) - dist(b));
  function dist(i) { let d = Infinity; mm.forEach((v, j) => { if (v) d = Math.min(d, Math.abs(i - j)); }); return d; }
  for (const c of order) {
    const nb = [c - 1, c + 1].filter(j => j >= 0 && j < n && mm[j]).sort((a, b) => a - b)[0];
    if (nb === undefined) { errors.push(`film ${c + 1} has no scale and no neighbour with one`); continue; }
    const ipsC = segs[c].ips, ipsN = segs[nb].ips;
    mm[c] = mm[nb] * (ipsC && ipsN ? ipsC / ipsN : 1);
    onStep(`film ${c + 1}: carrying the scale from film ${nb + 1}`); await tick();
    const up = Math.min(c, nb), lo = Math.max(c, nb), r = orient(grid(up), grid(lo), outMm, true);
    if (r.status !== 'matched' && r.status !== 'weak') {
      // Fable, 6 Nov 2026: no dead end. Its neighbour's scale is defensible only on the same detector pitch or with files of the same pixel size (checkSet has already held them to one study);
      // a film whose size clearly differs is refused as before. Marked red in the recipe, the band and the status; never the anchor.
      const same = (ipsC && ipsN) || (segs[c].keep.srcCols === segs[nb].keep.srcCols && segs[c].keep.srcRows === segs[nb].keep.srcRows);
      if (r.sizeOff || !same) { errors.push(`film ${c + 1} has no scale of its own and its join with film ${nb + 1} did not match (${r.why}): give it a ball or a known length`); mm[c] = null; continue; }
      assumed[c] = nb; warnings.push(`Film ${c + 1} has no ball or known length and its join with film ${nb + 1} did not match by itself (${r.why}). Its scale is assumed from film ${nb + 1}, not measured: place the join by hand and check a known length, or give it a ball.`);
      continue;
    }
    mm[c] = c > nb ? mm[c] * r.s : mm[c] / r.s;   // p_upper = ... s * p_lower: the lower film's pixel is s upper pixels
    from[c] = nb;
  }
  if (errors.length) return { errors };
  const grids = segs.map((s, i) => grid(i)), joins = [];
  for (let i = 0; i < n - 1; i++) {
    onStep(`join ${i + 1} of ${n - 1}`); await tick();
    const r = orient(grids[i], grids[i + 1], outMm, false);
    if (from[i] !== null || from[i + 1] !== null) r.dSize = null;   // a carried scale agrees by construction: no reassurance from it
    joins.push(r);
  }
  return { grids, joins, mm, from, assumed, warnings, errors };
}
// original file pixel -> grid pixel, exactly as keepCopy (box by f), resample (box by F, then bilinear) and the turn place it
function origToGrid(keep, mmKeep, outMm, grid, turned) {
  const f = keep.f, kk = outMm / mmKeep, F = Math.floor(kk) >= 2 ? Math.floor(kk) : 1, k2 = kk / F;
  // x_k = (x_o - (f-1)/2) / f;  x_b = (x_k - (F-1)/2) / F;  x_g = (x_b + 0.5) / k2 - 0.5
  const a = 1 / (f * F * k2), c = ((-(f - 1) / 2) / f - (F - 1) / 2) / F / k2 + 0.5 / k2 - 0.5;
  return turned ? [-a, 0, grid.cols - 1 - c, 0, -a, grid.rows - 1 - c] : [a, 0, c, 0, a, c];
}
const joinStatus = j => (j.how === 'hand' ? 'hand' : j.status === 'matched' ? 'matched' : j.status === 'weak' && j.accepted ? 'weak' : null);
// A join is usable when matched, weak and accepted by eye, or placed by hand; refusals (flipped, inverted, failed) only by hand.
export const joinUsable = j => !!joinStatus(j);
// compose and record. joins may carry the surgeon's changes: {how: 'hand', cA, rot, accepted}. Hand turn and size are bounded.
export function finish(segs, J, { outMm = STITCH.outMm, anchor = defaultAnchor(segs), tickBox = false, film: given = null } = {}) {
  const joins = J.joins.map(j => ({ ...j, rot: clamp(j.rot || 0, -STITCH.handTurnDeg, STITCH.handTurnDeg) }));
  if (!joins.every(joinUsable)) throw new Error('every join must be matched, accepted by eye, or placed by hand');
  if (!(anchor >= 0) || !scaleOf(segs[anchor])) throw new Error('the scale must be shown for a film with its own ball or known length');
  const film = given || compose(J.grids, joins, { outMm, label: '' });   // the sheet passes the film it already composed from the same joins
  const recipe = {
    v: 1, rev: SHARED_REV, n: segs.length, outMm, anchor, cols: film.cols, rows: film.rows, band: film.band, q: film.q, tick: tickBox === true,
    segs: segs.map((s, i) => {
      const P = origToGrid(s.keep, J.mm[i] * s.keep.f, outMm, J.grids[i], !!s.turned);
      const A = affMul(film.T[i], P).map(v => Math.round(v * 1e6) / 1e6);
      const asm = J.assumed && J.assumed[i] !== null && J.assumed[i] !== undefined;
      const o = { how: asm ? 'assumed' : J.from[i] !== null ? 'carried' : s.how, mm: Math.round(J.mm[i] * 1e7) / 1e7, w: s.keep.srcCols, h: s.keep.srcRows, A, turned: !!s.turned };
      if (s.sha) o.sha = s.sha;
      if (o.how === 'carried') o.from = J.from[i];
      if (asm) { o.from = J.assumed[i]; o.checked = false; }
      if (o.how === 'ball') { o.d = s.d || 25; o.ball = { x: s.ball.x, y: s.ball.y, r: s.ball.r }; }
      if (o.how === 'line') { o.line = s.line; o.len = s.len; }
      return o;
    }),
    joins: joins.map((j, i) => ({ st: joinStatus(j), ncc: Math.round(j.ncc * 1000) / 1000, margin: Number.isFinite(j.margin) ? Math.round(j.margin * 1000) / 1000 : null, uxMm: j.uxMm ?? null, uyMm: j.uyMm ?? null,
      rot: Math.round((j.rot || 0) * 1000) / 1000, s: j.s || 1, dSize: Number.isFinite(j.dSize) ? Math.round(j.dSize * 1e4) / 1e4 : null, seamY: Math.round(film.seams[i] * 10) / 10,
      cA: { x: Math.round(j.cA.x * 1000) / 1000, y: Math.round(j.cA.y * 1000) / 1000 }, cB: { x: j.cB.x, y: j.cB.y }, trimA: j.trimA || 0, trimB: j.trimB || 0, why: String(j.why || '').slice(0, 160) })),
  };
  burnBand(film.u16, film.cols, film.band, outMm, bandText(recipe));
  return { film, recipe };
}

// a saved join applied again (a film set reopened): the same grids from the recorded scales and the recorded placements, so the
// joined film is the same, pixel for pixel; nothing is matched again
export function applySaved(segs, recipe) {
  const outMm = recipe.outMm, mm = recipe.segs.map(s => s.mm), from = recipe.segs.map(s => (s.how === 'carried' ? s.from : null)), assumed = recipe.segs.map(s => (s.how === 'assumed' ? s.from : null));
  const grids = segs.map((s, i) => toGrid(s.keep, mm[i] * s.keep.f, outMm, !!recipe.segs[i].turned));
  const joins = recipe.joins.map(j => ({ trimA: j.trimA || 0, trimB: j.trimB || 0, status: j.st === 'matched' ? 'matched' : 'weak', how: j.st === 'hand' ? 'hand' : undefined, accepted: false, /* a weak join is looked at again */ cA: { ...j.cA }, cB: { ...j.cB }, rot: j.rot, s: j.s, ncc: j.ncc, margin: j.margin, uxMm: j.uxMm, uyMm: j.uyMm, dSize: j.dSize, why: j.why }));
  return { grids, joins, mm, from, assumed, warnings: [], errors: [] };
}
