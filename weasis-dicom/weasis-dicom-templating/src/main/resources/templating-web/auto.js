// Automatic helpers for the templating page (11 Oct 2026): find the calibration ball on the whole film, read the R/L
// marker, and find the femoral canal's inner cortical edges. Pure functions on the decoded image (no DOM), so
// test.mjs can check them in Node. Image: {cols, rows, data: Float32Array, invert}. Pixels, x right, y down.
// Every result is a proposal the surgeon sees and can overrule; nothing here decides on its own.
import { P, add, sub, mul, dot, unit, perp, dist, sample, detectBall, touchesUnknown } from './geom.js';

// ---------- shared ----------
// Block-mean copy, sign set so that dense (white on a standard display) is high, whatever the photometry.
function shrink(img, f) {
  const s = img.invert ? -1 : 1, W = Math.floor(img.cols / f), H = Math.floor(img.rows / f), d = new Float32Array(W * H);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    let acc = 0;
    for (let j = 0; j < f; j++) { const row = (y * f + j) * img.cols + x * f; for (let i = 0; i < f; i++) acc += img.data[row + i]; }
    d[y * W + x] = s * acc / (f * f);
  }
  return { W, H, d, f };
}
// Thresholds to try: shares of the 1st-99.9th percentile range (a saturated marker or ball) and high percentiles (a
// ball or letter only as bright as the densest bone, on a film where something else saturates). Highest first.
function thresholds(d, fracs, qs) {
  const step = Math.max(1, Math.floor(d.length / 200000)), v = [];
  for (let i = 0; i < d.length; i += step) v.push(d[i]);
  v.sort((a, b) => a - b);
  const q = x => v[Math.min(v.length - 1, Math.floor(v.length * x))], lo = q(0.01), hi = q(0.999);
  if (!(hi > lo)) return [];
  const t = [...fracs.map(f => lo + f * (hi - lo)), ...qs.map(q)].sort((a, b) => b - a);
  return t.filter((x, i) => i === 0 || t[i - 1] - x > 0.01 * (hi - lo));
}
// 8-connected components of a mask, with area, centroid, second moments and bounding box.
function components(mask, W, H, minArea, maxArea) {
  const lab = new Int32Array(W * H), stack = new Int32Array(W * H), out = [];
  let n = 0;
  for (let i = 0; i < W * H; i++) {
    if (!mask[i] || lab[i]) continue;
    n++; let sp = 0; stack[sp++] = i; lab[i] = n;
    let area = 0, sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0, x0 = W, x1 = 0, y0 = H, y1 = 0;
    while (sp) {
      const p = stack[--sp], x = p % W, y = (p - x) / W;
      area++; sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y;
      if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        const xx = x + dx, yy = y + dy;
        if ((dx || dy) && xx >= 0 && yy >= 0 && xx < W && yy < H) { const q = yy * W + xx; if (mask[q] && !lab[q]) { lab[q] = n; stack[sp++] = q; } }
      }
    }
    if (area < minArea || area > maxArea) continue;
    const cx = sx / area, cy = sy / area, vxx = sxx / area - cx * cx, vyy = syy / area - cy * cy, vxy = sxy / area - cx * cy;
    const m = (vxx + vyy) / 2, q = Math.sqrt(((vxx - vyy) / 2) ** 2 + vxy * vxy);
    out.push({ id: n, area, cx, cy, x0, x1, y0, y1, elong: Math.sqrt((m + q) / Math.max(1e-9, m - q)) });
  }
  return { lab, comps: out };
}

// ---------- calibration ball, anywhere on the film ----------
// Bright, round, solid blobs of a plausible size (from the machine's pixel spacing when known: magnification 0.95 to
// 1.6), each checked by the same edge finder and quality gate as a click (geom.js detectBall). Best first.
// mmPerPx: the machine's estimate (detector spacing) or null.
// unknown: (x, y) => true under an existing implant the surgeon confirmed: no candidate is taken there (a prosthetic head is
// round and bright too)
export function findBalls(img, { mmPerPx = null, ballMm = 25, maxTries = 5, unknown = null } = {}) {
  const f = Math.max(1, Math.round(Math.max(img.cols, img.rows) / 900)), s = shrink(img, f);
  let rMin, rMax;
  if (mmPerPx > 0) { const r0 = ballMm / 2 / mmPerPx / f; rMin = 0.85 * r0; rMax = 1.7 * r0; }
  else { const L = Math.max(s.W, s.H); rMin = 0.005 * L; rMax = 0.08 * L; }
  rMin = Math.max(rMin, 4);
  const cands = [];
  for (const th of thresholds(s.d, [0.9, 0.78, 0.66, 0.54], [0.999, 0.996, 0.99, 0.98, 0.96, 0.93])) {
    const mask = new Uint8Array(s.W * s.H);
    for (let i = 0; i < mask.length; i++) mask[i] = s.d[i] >= th ? 1 : 0;
    const { comps } = components(mask, s.W, s.H, 0.8 * Math.PI * rMin * rMin, 1.2 * Math.PI * rMax * rMax);
    for (const c of comps) {
      if (c.x0 === 0 || c.y0 === 0 || c.x1 === s.W - 1 || c.y1 === s.H - 1) continue;   // a ball cut by the film edge: no
      const bw = c.x1 - c.x0 + 1, bh = c.y1 - c.y0 + 1, fill = c.area / (bw * bh);
      if (c.elong > 1.25 || fill < 0.66 || fill > 0.9 || bw / bh > 1.3 || bh / bw > 1.3) continue;
      const r = Math.sqrt(c.area / Math.PI) * f, x = (c.cx + 0.5) * f - 0.5, y = (c.cy + 0.5) * f - 0.5;
      if (cands.some(o => Math.hypot(o.x - x, o.y - y) < 0.5 * Math.max(o.r, r))) continue;
      cands.push({ x, y, r, round: c.elong + 3 * Math.abs(fill - Math.PI / 4) });
    }
  }
  cands.sort((a, b) => a.round - b.round);
  const found = [];
  const im = unknown ? { ...img, unknown } : img;   // the edges are found on film only (Astra, 16 Oct, P1 2)
  for (const c of cands.filter(c => !(unknown && unknown(c.x, c.y))).slice(0, maxTries)) {
    const b = detectBall(im, P(c.x, c.y), Math.min(900, 1.8 * c.r));
    if (!b || b.rejected || Math.abs(b.r / c.r - 1) > 0.35) continue;
    let score = b.quality.coverage - 5 * b.quality.rmsPx / b.r - (b.warnings.length ? 0.3 : 0);
    if (mmPerPx > 0) {   // the ball's shadow is magnified 0-50 % against the detector's own spacing
      b.magnification = 2 * b.r * mmPerPx / ballMm;
      if (b.magnification < 0.95 || b.magnification > 1.6) score -= 1;
    }
    found.push({ ...b, score });
  }
  return found.sort((a, b) => b.score - a.score);
}

// ---------- R / L marker ----------
// Letter shapes: the mean of Arial Bold, Helvetica Bold, DIN Alternate Bold and Arial Black, 24 x 24, stretched to
// the letter's box. A film's marker is matched in all 8 turns and mirror images.
const N = 24;
const GLYPH = {
  R: ['111111111111111111000000', '111111111111111111110000', '111111111111111111111000', '111111111111111111111100', '111111000000000111111100', '111111000000000011111100', '111111000000000001111100', '111111000000000001111100', '111111000000000001111100', '111111000000000011111100', '111111111111111111111000', '111111111111111111110000', '111111111111111111100000', '111111111111111110000000', '111111000011111111000000', '111111000000111111100000', '111111000000011111100000', '111111000000011111110000', '111111000000001111111000', '111111000000000111111000', '111111000000000111111100', '111111000000000011111110', '111111000000000001111110', '111111000000000001111111'],
  L: ['111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111100000000000000000', '111111111111111111111111', '111111111111111111111111', '111111111111111111111111', '111111111111111111111111'],
};
// transforms of an N x N grid: k quarter turns clockwise, then a left-right mirror
function turned(g, k, mirror) {
  const o = new Uint8Array(N * N);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    let sx = x, sy = y;
    for (let i = 0; i < k; i++) { const t = sx; sx = sy; sy = N - 1 - t; }   // inverse of a clockwise quarter turn
    if (mirror) sx = N - 1 - sx;
    o[y * N + x] = g[sy * N + sx];
  }
  return o;
}
// all variants, keyed: a film letter matching variant (k, mirror) reads turned k quarters clockwise and mirrored if set
const VARIANTS = Object.fromEntries(Object.entries(GLYPH).map(([ch, rows]) => {
  const g = new Uint8Array(N * N); rows.forEach((r, y) => { for (let x = 0; x < N; x++) g[y * N + x] = r[x] === '1' ? 1 : 0; });
  const v = []; for (let k = 0; k < 4; k++) for (const m of [false, true]) v.push({ k, mirror: m, g: turned(g, k, m) });
  return [ch, v];
}));
function holes(lab, id, W, x0, y0, bw, bh) {   // background regions inside the box not touching its edge (4-connected)
  const seen = new Uint8Array(bw * bh), inside = (x, y) => lab[(y0 + y) * W + x0 + x] === id, sizes = [];
  for (let y = 0; y < bh; y++) for (let x = 0; x < bw; x++) {
    const i = y * bw + x; if (seen[i] || inside(x, y)) continue;
    let edge = false, area = 0; const st = [i]; seen[i] = 1;
    while (st.length) {
      const p = st.pop(), px = p % bw, py = (p - px) / bw; area++;
      if (px === 0 || py === 0 || px === bw - 1 || py === bh - 1) edge = true;
      for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        const xx = px + dx, yy = py + dy; if (xx < 0 || yy < 0 || xx >= bw || yy >= bh) continue;
        const q = yy * bw + xx; if (!seen[q] && !inside(xx, yy)) { seen[q] = 1; st.push(q); }
      }
    }
    if (!edge) sizes.push(area);
  }
  return sizes.filter(a => a >= 0.015 * bw * bh).length;
}
// Bright letter-sized shapes that read as R or L, standing alone (not a letter of a word). Returns [{letter, x, y, w, h, iou, turns, mirrored}], best first.
// mmPerPx: the machine's estimate or null (letters 6-40 mm tall; without a scale 1-10 % of the film's long side).
export function findMarkers(img, { mmPerPx = null, unknown = null } = {}) {
  const f = Math.max(1, Math.round(Math.max(img.cols, img.rows) / 1200)), s = shrink(img, f);
  const L = Math.max(s.W, s.H);
  const hMin = mmPerPx > 0 ? 6 / mmPerPx / f : 0.01 * L, hMax = mmPerPx > 0 ? 40 / mmPerPx / f : 0.1 * L;
  const found = [];
  for (const th of thresholds(s.d, [0.9, 0.75, 0.6], [0.9995, 0.998, 0.995, 0.99])) {
    const mask = new Uint8Array(s.W * s.H);
    for (let i = 0; i < mask.length; i++) mask[i] = s.d[i] >= th ? 1 : 0;
    const { lab, comps } = components(mask, s.W, s.H, Math.max(12, 0.12 * hMin * hMin), 0.9 * hMax * hMax);
    for (const c of comps) {
      const bw = c.x1 - c.x0 + 1, bh = c.y1 - c.y0 + 1, lng = Math.max(bw, bh), sht = Math.min(bw, bh), fill = c.area / (bw * bh);
      if (lng < hMin || lng > hMax || sht / lng < 0.4 || fill < 0.25 || fill > 0.8) continue;
      // a letter inside a word of burned-in text (PELVIS, LT HIP) is not a marker: another letter-sized shape beside it
      const inWord = comps.some(o => o !== c && (() => { const oh = o.y1 - o.y0 + 1, ow = o.x1 - o.x0 + 1, ol = Math.max(oh, ow);
        const overlapY = Math.min(c.y1, o.y1) - Math.max(c.y0, o.y0), overlapX = Math.min(c.x1, o.x1) - Math.max(c.x0, o.x0);
        const gapX = Math.max(o.x0 - c.x1, c.x0 - o.x1), gapY = Math.max(o.y0 - c.y1, c.y0 - o.y1);
        return ol > 0.5 * lng && ol < 1.6 * lng && ((overlapY > 0.5 * Math.min(bh, oh) && gapX < lng) || (overlapX > 0.5 * Math.min(bw, ow) && gapY < lng)); })());
      if (inWord) continue;
      const g = new Uint8Array(N * N);   // the shape stretched to 24 x 24 (3 x 3 samples per cell, majority)
      for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
        let on = 0;
        for (let j = 0; j < 3; j++) for (let i = 0; i < 3; i++) {
          const px = c.x0 + Math.min(bw - 1, Math.floor((x + (i + 0.5) / 3) * bw / N)), py = c.y0 + Math.min(bh - 1, Math.floor((y + (j + 0.5) / 3) * bh / N));
          if (lab[py * s.W + px] === c.id) on++;
        }
        g[y * N + x] = on >= 5 ? 1 : 0;
      }
      const best = {};
      for (const [ch, vs] of Object.entries(VARIANTS)) for (const v of vs) {
        let a = 0, u = 0; for (let i = 0; i < N * N; i++) { a += g[i] & v.g[i]; u += g[i] | v.g[i]; }
        const iou = u ? a / u : 0; if (!best[ch] || iou > best[ch].iou) best[ch] = { iou, k: v.k, mirror: v.mirror };
      }
      const nh = holes(lab, c.id, s.W, c.x0, c.y0, bw, bh);
      const ch = best.R.iou >= best.L.iou ? 'R' : 'L', other = ch === 'R' ? 'L' : 'R', b = best[ch];
      if (b.iou < 0.6 || b.iou - best[other].iou < 0.1 || (ch === 'R' ? nh !== 1 : nh !== 0)) continue;
      const x = (c.x0 + bw / 2) * f, y = (c.y0 + bh / 2) * f;
      // on a confirmed existing implant: any excluded pixel in the letter's box (what the shape and its recognition read; Astra,
      // 16 Oct 2026, round 2, P2 3), not only its centre
      if (unknown && (() => { for (let py = c.y0 * f; py < (c.y1 + 1) * f; py++) for (let px = c.x0 * f; px < (c.x1 + 1) * f; px++) if (unknown(px, py)) return true; return false; })()) continue;
      const dup = found.findIndex(o => Math.hypot(o.x - x, o.y - y) < 0.5 * Math.max(o.h, lng * f));
      const m = { letter: ch, x, y, w: bw * f, h: bh * f, iou: Math.round(b.iou * 1000) / 1000, turns: b.k, mirrored: b.mirror };
      if (dup < 0) found.push(m); else if (found[dup].iou < m.iou) found[dup] = m;
    }
  }
  return found.sort((a, b) => b.iou - a.iou);
}

// ---------- femoral canal ----------
// Along the canal line the surgeon clicked (a high, b low), every 2 mm: a brightness profile across the shaft
// (averaged over 1.5 mm along it). The canal shows as a darker band between the two cortices: going outwards from it the
// brightness rises at each inner cortical edge and falls again at each outer edge. Every such dark-between-bright
// pattern 4-30 mm wide is a candidate; the one nearest the clicked line and with the clearest edges is taken, a straight
// centre line is fitted through the chosen levels (outliers dropped), then each level is chosen again nearest that
// line. So a click a few millimetres off the canal still finds it. Returns {levels: [{s, mid, l, r, w, tl, tr}],
// axis: {a, b} (the fitted centre line at the clicked ends' levels), n, tried, rmsMm, spanMm} or null.
// unknown (14 Oct 2026, revision and conversion; DESIGN-2026-10-14-revision-conversion.md, amendment 2): (x, y) => true for
// a pixel under an existing implant the surgeon confirmed (exclusionRaster). Such a pixel is unknown, never cortex: its
// profile sample is missing (NaN), so no smoothing, gradient or mean is made across it (NaN spreads), a mask edge makes no
// edge, and a cortical pair counts only when everything from 1.5 mm outside one outer edge to 1.5 mm outside the other is
// film. Nothing is interpolated across unknown pixels. Also returned: masked (levels whose profile met an unknown pixel),
// stepMm, and observed: the runs of consecutive traced levels ([{from, to}] along the trace), so the page can draw what was
// seen apart from what is extrapolated. Without unknown the result is as before.
export function traceCanal(img, a, b, mmPerPx, { stepMm = 2, halfMm = 25, aboveMm = 0, beyondMm = 60, unknown = null } = {}) {
  const sign = img.invert ? -1 : 1, px = v => v / mmPerPx, Lab = dist(a, b);
  if (!(mmPerPx > 0) || Lab < px(20)) return null;
  const d = unit(sub(b, a)), n = perp(d), ds = 0.5, K = Math.round(px(halfMm) / ds), sk = Math.max(1, Math.round(px(0.35) / ds));
  const kmm = mm => Math.round(px(mm) / ds), levels = [];
  let tried = 0, masked = 0;
  for (let s = -px(aboveMm); s <= Lab + px(beyondMm); s += px(stepMm)) {
    const c = add(a, mul(d, s)), prof = new Float64Array(2 * K + 1);
    let off = false, unk = 0;
    for (let k = -K; k <= K && !off; k++) {
      let acc = 0, hole = false;
      for (const t of [-0.75, -0.375, 0, 0.375, 0.75]) { const q = add(c, add(mul(n, k * ds), mul(d, px(t)))); if (unknown && touchesUnknown(unknown, q.x, q.y)) { hole = true; continue; } const v = sample(img, q.x, q.y); if (Number.isNaN(v)) { off = true; break; } acc += v; }
      prof[k + K] = hole ? NaN : sign * acc / 5;   // under a confirmed implant (any of the four pixels a sample reads; Astra, 16 Oct, P1 1): unknown, not a value
      if (hole) unk++;
    }
    if (off) continue;
    tried++; if (unk) masked++;
    const M = prof.length, sm = new Float64Array(M), g = new Float64Array(M);
    for (let i = 0; i < M; i++) { let acc = 0, cnt = 0; for (let j = i - sk; j <= i + sk; j++) if (j >= 0 && j < M) { acc += prof[j]; cnt++; } sm[i] = acc / cnt; }
    for (let i = 1; i < M - 1; i++) g[i] = (sm[i + 1] - sm[i - 1]) / 2;
    const ga = [...g].map(Math.abs).filter(Number.isFinite).sort((x, y) => x - y), noise = ga[ga.length >> 1] || 1e-9;
    const up = [], dn = [];   // rising (brightness grows with k) and falling edges
    for (let i = 2; i < M - 2; i++) { if (g[i] > 3 * noise && g[i] >= g[i - 1] && g[i] > g[i + 1]) up.push(i); if (g[i] < -3 * noise && g[i] <= g[i - 1] && g[i] < g[i + 1]) dn.push(i); }
    const mean = (i0, i1) => { let acc = 0, cnt = 0; for (let i = Math.max(0, Math.min(i0, i1)); i <= Math.min(M - 1, Math.max(i0, i1)); i++) { acc += sm[i]; cnt++; } return cnt ? acc / cnt : NaN; };
    const peak = i => { const den = g[i - 1] - 2 * g[i] + g[i + 1]; return i + (Math.abs(den) > 1e-12 ? Math.max(-1, Math.min(1, 0.5 * (g[i - 1] - g[i + 1]) / den)) : 0); };
    const cands = [];
    for (const il of dn) for (const ir of up) {   // canal from the left inner edge (falling into it) to the right one (rising out)
      const w = (ir - il) * ds * mmPerPx; if (w < 4 || w > 30) continue;
      let ol = -1, orr = -1;   // outer edges: rising into the left cortex from outside, falling out of the right one
      for (let i = il - kmm(0.8); i >= Math.max(1, il - kmm(12)); i--) if (g[i] > 2 * noise && (ol < 0 || g[i] > g[ol])) ol = i;
      for (let i = ir + kmm(0.8); i <= Math.min(M - 2, ir + kmm(12)); i++) if (g[i] < -2 * noise && (orr < 0 || g[i] < g[orr])) orr = i;
      if (ol < 0 || orr < 0) continue;
      const q1 = Math.round(il + 0.25 * (ir - il)), q3 = Math.round(il + 0.75 * (ir - il));
      const canal = mean(q1, q3), cl = mean(ol + 1, il - 1), cr = mean(ir + 1, orr - 1), xl = mean(ol - kmm(1.5), ol - 1), xr = mean(orr + 1, orr + kmm(1.5));
      if (!(cl - canal > 0.15 * Math.abs(cl - xl) && cr - canal > 0.15 * Math.abs(cr - xr))) continue;
      const kl = (peak(il) - K) * ds, kr = (peak(ir) - K) * ds;
      cands.push({ kl, kr, mid: (kl + kr) / 2, strength: Math.min(-g[il], g[ir]) / noise, w, tl: (il - ol) * ds * mmPerPx, tr: (orr - ir) * ds * mmPerPx });
    }
    if (cands.length) levels.push({ s, c, cands });
  }
  const pick = (lv, centre, lim) => {   // clearest edges among the candidates near the expected centre (px along n)
    let best = null, bs = -Infinity;
    for (const k of lv.cands) {
      const off = Math.abs(k.mid - centre) * mmPerPx; if (off > lim) continue;
      const sc = k.strength - 0.4 * off; if (sc > bs) { bs = sc; best = k; }
    }
    return best;
  };
  const fitLine = chosen => {   // offset of each level's middle from the clicked line, straight in s; drop outliers
    let keep = chosen, fit = null;
    for (let it = 0; it < 6 && keep.length >= 4; it++) {
      const X = keep.map(o => o.s), Y = keep.map(o => o.k.mid), m = X.length;
      const mx = X.reduce((p, q) => p + q, 0) / m, my = Y.reduce((p, q) => p + q, 0) / m;
      let sxy = 0, sxx = 0; for (let i = 0; i < m; i++) { sxy += (X[i] - mx) * (Y[i] - my); sxx += (X[i] - mx) ** 2; }
      const beta = sxx > 0 ? sxy / sxx : 0, alpha = my - beta * mx, res = X.map((x, i) => Y[i] - alpha - beta * x);
      const mad = [...res].map(Math.abs).sort((p, q) => p - q)[res.length >> 1];
      fit = { alpha, beta, rms: Math.sqrt(res.reduce((p, q) => p + q * q, 0) / m) * mmPerPx };
      const lim = Math.max(px(0.6), 3 * 1.4826 * mad), next = keep.filter((_, i) => Math.abs(res[i]) <= lim);
      if (next.length === keep.length) break;
      keep = next;
    }
    return { keep, fit };
  };
  // pass 1: nearest the clicked line; pass 2 and 3: nearest the fitted centre line
  let chosen = levels.map(lv => ({ s: lv.s, c: lv.c, k: pick(lv, 0, 15) })).filter(o => o.k), res = fitLine(chosen);
  for (let pass = 0; pass < 2 && res.fit; pass++) {
    const F = res.fit; chosen = levels.map(lv => ({ s: lv.s, c: lv.c, k: pick(lv, F.alpha + F.beta * lv.s, 2.5) })).filter(o => o.k);
    // widths that jump against their neighbours are a wrong edge
    chosen = chosen.filter((o, i) => { const nb = chosen.slice(Math.max(0, i - 3), i + 4).map(q => q.k.w).sort((x, y) => x - y), med = nb[nb.length >> 1]; return Math.abs(o.k.w - med) <= 0.25 * med; });
    res = fitLine(chosen);
  }
  const keep = res.keep.map(o => ({ s: o.s, l: add(o.c, mul(n, o.k.kl)), r: add(o.c, mul(n, o.k.kr)), mid: add(o.c, mul(n, o.k.mid)), w: o.k.w, tl: o.k.tl, tr: o.k.tr }));
  const fit = res.fit;
  // the stretches actually seen: consecutive levels no further apart than 1.6 steps (a gap is a masked or untraced part)
  const observed = [];
  for (const l of [...keep].sort((p, q) => p.s - q.s)) { const r = observed[observed.length - 1]; if (r && l.s - r.to <= 1.6 * px(stepMm)) r.to = l.s; else observed.push({ from: l.s, to: l.s }); }
  const more = { masked, stepMm, observed };
  if (!fit || keep.length < 6) return { levels: keep, axis: null, n: keep.length, tried, rmsMm: fit && fit.rms, spanMm: 0, d, nrm: n, origin: a, ...more };
  const span = (Math.max(...keep.map(l => l.s)) - Math.min(...keep.map(l => l.s))) * mmPerPx;
  const A = add(a, mul(n, fit.alpha)), B = add(add(a, mul(d, Lab)), mul(n, fit.alpha + fit.beta * Lab));
  return { levels: keep, axis: span >= 30 ? { a: A, b: B } : null, n: keep.length, tried, rmsMm: fit.rms, spanMm: span, d, nrm: n, origin: a, ...more };
}

// ---------- how a stem sits in the traced canal ----------
// poly: the stem outline in image px; tr: traceCanal's result; mmPerPx. At every level where the canal's inner edges
// were found and the stem crosses: how far the outline reaches past the inner cortex (positive) on either side, and
// the fill. Also how much of the stem the evidence covers (coveredMm, from the first to the last such level) and the
// longest stretch without a level (maxGapMm): a fit judged on a short or broken trace is not a fit (Astra, 11 Oct).
// where the stem outline crosses one traced level: how far it reaches past the inner cortex on either side (mm, + past it)
// and the fill; null when the outline does not cross that level
function crossAt(poly, lv, n, mmPerPx) {
  const p = lv.mid, xs = [];
  for (let i = 0; i < poly.length; i++) {
    const a = poly[i], e = sub(poly[(i + 1) % poly.length], a), den = n.x * e.y - n.y * e.x; if (Math.abs(den) < 1e-12) continue;
    const w = sub(a, p), s = (w.x * e.y - w.y * e.x) / den, u = (w.x * n.y - w.y * n.x) / den; if (u >= 0 && u < 1) xs.push(s);
  }
  if (xs.length < 2) return null;
  const s0 = Math.min(...xs), s1 = Math.max(...xs), kl = dot(sub(lv.l, p), n), kr = dot(sub(lv.r, p), n);
  return { s: lv.s, inL: (kl - s0) * mmPerPx, inR: (s1 - kr) * mmPerPx, fill: (s1 - s0) / (kr - kl) };
}
export function canalFit(poly, tr, mmPerPx, region) {
  const out = [], n = tr.nrm;
  for (const lv of tr.levels) { const x = crossAt(poly, lv, n, mmPerPx); if (x) out.push(x); }
  if (out.length < 3) return null;
  out.sort((a, b) => a.s - b.s);
  const worst = out.reduce((b, o) => Math.max(o.inL, o.inR) > Math.max(b.inL, b.inR) ? o : b);
  const distal = out.slice(-Math.max(1, Math.round(out.length / 3)));
  let gap = 0; for (let i = 1; i < out.length; i++) gap = Math.max(gap, out[i].s - out[i - 1].s);
  // region: the stem's stretch along the trace ({from, to}, trace units); the unchecked ends count as gaps (Astra, 11 Oct)
  if (region) gap = Math.max(gap, out[0].s - region.from, region.to - out[out.length - 1].s);
  return { n: out.length, maxIn: Math.max(worst.inL, worst.inR), fillDistal: distal.reduce((a, o) => a + o.fill, 0) / distal.length,
    coveredMm: (out[out.length - 1].s - out[0].s) * mmPerPx, maxGapMm: gap * mmPerPx };
}
// Enough evidence to size a stem: the traced canal has a fitted axis, and the fit covers at least 70 % of the part of
// the stem that lies along the traced canal (stemMm: from where tracing starts, the first canal click below the lesser
// trochanter, down to the stem's tip; at least 30 mm), with no stretch longer than 12 mm unchecked, its two ends
// included (canalFit with the stem's region).
export const fitIsEvidence = (fit, tr, stemMm) => !!(fit && tr && tr.axis && stemMm >= 30 && fit.coveredMm >= 0.7 * stemMm && fit.maxGapMm <= 12);
// The stem's stretch along the trace: tip and top (neck cut) in image px, projected on the trace's direction, from
// where tracing starts. {from, to} in trace units, lenMm in mm.
export function stemRegion(tip, top, tr, mmPerPx) {
  const sTip = dot(sub(tip, tr.origin), tr.d), sTop = dot(sub(top, tr.origin), tr.d), from = Math.max(0, Math.min(sTip, sTop));
  return { from, to: sTip, lenMm: Math.max(0, (sTip - from) * mmPerPx) };
}
export const stemAlongTrace = (tip, top, tr, mmPerPx) => stemRegion(tip, top, tr, mmPerPx).lenMm;

// ======================= existing implants: revision and conversion (14-15 Oct 2026) =======================
// Sami, 14 Oct: "existing implants at the surgical site must not interfere with templating for hip or knee conversion and
// revision"; DESIGN-2026-10-14-revision-conversion.md with its binding amendments after Astra (REVIEW-ASTRA-2026-10-14-
// revision-design.md). What is here only proposes and measures: the surgeon confirms every region before anything uses it,
// and the masks are kept apart from the film cleaner (clean.js): an exclusion never fills a pixel, and a filled (cleaned)
// pixel is never read here (the page passes the original film).
// A region (contract section 13): {id, cls, kind: 'poly' | 'stroke', pts: [{x, y}], r (a stroke's half-width, px), src:
// 'detected' | 'user', conf: true | false}, in image pixels (x right, y down, pixel centres at whole numbers). cls:
// 'hardware' and 'cement' (exclusions once confirmed), 'unknown' (a proposal: "possible hardware / opaque region"), 'ball'
// and 'marker' (the calibration ball and the side marker: never an exclusion, so masking cannot remove them), 'clear' (the
// "Not an implant" eraser: takes its area out of the exclusions).
export const EXCL_CLASSES = ['hardware', 'cement'];
export const isExclusion = r => !!r && r.conf === true && EXCL_CLASSES.includes(r.cls);
export const isUnresolved = r => !!r && r.cls === 'unknown' && r.conf !== true;

// The outer boundary of one component (label id in lab, W x H) as cell positions, traced counterclockwise from its topmost,
// then leftmost cell (Freeman directions, 8-connected); holes are left inside (a conservative mask).
const FD = [[1, 0], [1, -1], [0, -1], [-1, -1], [-1, 0], [-1, 1], [0, 1], [1, 1]];
function boundary(lab, id, W, H, x0, y0) {
  let sx = x0; while (lab[y0 * W + sx] !== id) sx++;
  const inC = (x, y) => x >= 0 && y >= 0 && x < W && y < H && lab[y * W + x] === id, out = [[sx, y0]];
  let cx = sx, cy = y0, dir = 7, second = null;
  for (let n = 0; n < 4 * (W + H) * 64; n++) {
    const start = dir % 2 === 0 ? (dir + 7) % 8 : (dir + 6) % 8;
    let found = -1;
    for (let i = 0; i < 8; i++) { const d = (start + i) % 8; if (inC(cx + FD[d][0], cy + FD[d][1])) { found = d; break; } }
    if (found < 0) break;   // a single cell
    const nx = cx + FD[found][0], ny = cy + FD[found][1];
    if (second && cx === sx && cy === y0 && nx === second[0] && ny === second[1]) break;   // round once (Jacob's stop)
    if (!second) second = [nx, ny];
    cx = nx; cy = ny; dir = found;
    if (!(cx === sx && cy === y0)) out.push([cx, cy]);
  }
  return out;
}
// Douglas-Peucker on a closed outline, the tolerance raised until it has at most maxPts points
function simplifyClosed(pts, tol, maxPts) {
  const rdp = (a, t) => {
    if (a.length < 3) return a;
    let dm = -1, im = 0; const A = a[0], B = a[a.length - 1], L = dist(A, B) || 1e-9;
    for (let i = 1; i < a.length - 1; i++) { const dd = Math.abs((B.x - A.x) * (A.y - a[i].y) - (A.x - a[i].x) * (B.y - A.y)) / L; if (dd > dm) { dm = dd; im = i; } }
    return dm > t ? [...rdp(a.slice(0, im + 1), t).slice(0, -1), ...rdp(a.slice(im), t)] : [A, B];
  };
  if (pts.length <= 3) return pts;
  let far = 1; for (let i = 1; i < pts.length; i++) if (dist(pts[0], pts[i]) > dist(pts[0], pts[far])) far = i;   // split at the farthest point
  let t = tol, out = pts;
  for (let k = 0; k < 30; k++) { out = [...rdp(pts.slice(0, far + 1), t).slice(0, -1), ...rdp([...pts.slice(far), pts[0]], t).slice(0, -1)]; if (out.length <= maxPts) break; t *= 1.5; }
  return out;
}
// Possible hardware or opaque regions on a film (the proposal only; amendment 1): connected areas clearly brighter than the
// film's bone band (its 80th percentile; the brightest band at least 30 % of the film's range above it, the threshold in the
// top 15 % of the range), with the polarity normalised (a MONOCHROME1 film is read the same way as any other, as for the ball
// and the marker). Brightness alone does not tell metal from cement, cortex or contrast, so every region is "unknown" until the
// surgeon says what it is; one on the calibration ball (balls: [{x, y, r}]) is 'ball' and one on a read side marker
// (markers: [{x, y, w, h}]) is 'marker': never offered as an exclusion. Returns [{cls, pts, areaPx}], largest first: pts the
// outline in image pixels (at most maxPts points).
export function findOpaque(img, { mmPerPx = null, balls = [], markers = [], minAreaMm2 = 30, maxRegions = 12, maxPts = 160 } = {}) {
  const f = Math.max(1, Math.round(Math.max(img.cols, img.rows) / 700)), s = shrink(img, f);
  const step = Math.max(1, Math.floor(s.d.length / 200000)), v = [];
  for (let i = 0; i < s.d.length; i += step) v.push(s.d[i]);
  v.sort((a, b) => a - b);
  const q = x => v[Math.min(v.length - 1, Math.floor(v.length * x))], lo = q(0.01), bone = q(0.8), top = q(0.9995);
  if (!(top - lo > 0) || top - bone < 0.3 * (top - lo)) return [];   // nothing clearly brighter than the bone band (at least 30 % of the film's range above it)
  const th = Math.max(bone + 0.6 * (top - bone), lo + 0.85 * (top - lo)), mask = new Uint8Array(s.W * s.H);   // and in the top 15 % of the range (dense cortex stays out)
  for (let i = 0; i < mask.length; i++) mask[i] = s.d[i] >= th ? 1 : 0;
  const minA = mmPerPx > 0 ? minAreaMm2 / (mmPerPx * f) ** 2 : 0.0004 * s.W * s.H;
  const { lab, comps } = components(mask, s.W, s.H, Math.max(4, minA), s.W * s.H);
  const out = [];
  for (const c of comps.sort((a, b) => b.area - a.area).slice(0, maxRegions)) {
    const cx = (c.cx + 0.5) * f - 0.5, cy = (c.cy + 0.5) * f - 0.5, diag = Math.hypot(c.x1 - c.x0 + 1, c.y1 - c.y0 + 1) * f;
    let cls = 'unknown';
    if (balls.some(b => b && Math.hypot(b.x - cx, b.y - cy) < 1.3 * b.r && diag < 3.5 * b.r)) cls = 'ball';
    else if (markers.some(m => m && Math.abs(m.x - cx) < 0.65 * m.w + f && Math.abs(m.y - cy) < 0.65 * m.h + f)) cls = 'marker';
    const ring = boundary(lab, c.id, s.W, s.H, c.x0, c.y0).map(([x, y]) => P((x + 0.5) * f - 0.5, (y + 0.5) * f - 0.5));
    const pts = (ring.length >= 3 ? simplifyClosed(ring, 0.75 * f, maxPts) : [P(c.x0 * f, c.y0 * f), P((c.x1 + 1) * f - 1, c.y0 * f), P((c.x1 + 1) * f - 1, (c.y1 + 1) * f - 1), P(c.x0 * f, (c.y1 + 1) * f - 1)])
      .map(p => ({ x: Math.round(p.x * 10) / 10, y: Math.round(p.y * 10) / 10 }));
    out.push({ cls, pts, areaPx: c.area * f * f });
  }
  return out;
}

// The confirmed exclusions of a film as a raster (amendment 2): cells of f x f image pixels (the long side at most maxSide
// cells), set under every confirmed hardware or cement region, grown by marginPx (an implant's edge blooms; about 1 mm),
// then the "Not an implant" areas taken out. null when nothing is excluded. rasterHas(R, x, y): is that image point
// excluded. Never used to change a pixel: only to say which pixels are unknown.
export function exclusionRaster(ex, cols, rows, { marginPx = 0, maxSide = 2048 } = {}) {
  const regs = ex && Array.isArray(ex.regions) ? ex.regions : [], on = regs.filter(isExclusion);
  if (!on.length || !(cols > 0) || !(rows > 0)) return null;
  const f = Math.max(1, Math.ceil(Math.max(cols, rows) / maxSide)), W = Math.ceil(cols / f), H = Math.ceil(rows / f), m = new Uint8Array(W * H);
  const cc = i => i * f + (f - 1) / 2;   // a cell's centre in image pixels
  // Conservative (Astra, 16 Oct 2026, P1 3): an exclusion sets every cell its outline or stroke touches, so a region
  // narrower than a cell is never lost on a large film; a "Not an implant" area clears only cells it covers wholly.
  // Cell (i, j) covers image x from i f - 0.5 to (i + 1) f - 0.5 (pixel centres at whole numbers).
  const set = (i, j, val) => { if (i >= 0 && j >= 0 && i < W && j < H) m[j * W + i] = val; };
  const walk = (a, b, val) => {   // every cell the segment a-b passes through (grid traversal)
    let u = (a.x + 0.5) / f, v = (a.y + 0.5) / f; const u1 = (b.x + 0.5) / f, v1 = (b.y + 0.5) / f, du = u1 - u, dv = v1 - v;
    let i = Math.floor(u), j = Math.floor(v); const ie = Math.floor(u1), je = Math.floor(v1), si = Math.sign(du), sj = Math.sign(dv);
    const tdu = si ? Math.abs(1 / du) : Infinity, tdv = sj ? Math.abs(1 / dv) : Infinity;
    let tu = si > 0 ? (i + 1 - u) * tdu : si < 0 ? (u - i) * tdu : Infinity, tv = sj > 0 ? (j + 1 - v) * tdv : sj < 0 ? (v - j) * tdv : Infinity;
    set(i, j, val);
    for (let n = 0; n < 4 * (W + H) && (i !== ie || j !== je); n++) { if (tu < tv) { i += si; tu += tdu; } else { j += sj; tv += tdv; } set(i, j, val); }
  };
  const half = f * Math.SQRT1_2;   // a cell's centre to its corner
  // Clearing ("Not an implant"; Astra, 16 Oct 2026, round 2, P1 1): a cell is cleared only when it lies wholly inside the
  // area: for an outline, its centre and four corners inside and no edge of the outline meeting the cell (so a sloping or
  // concave outline cannot clear a cell it only partly covers); for a stroke, its centre and four corners within the
  // stroke's half-width of one and the same segment (a capsule is convex, so then the whole cell is inside it).
  const cellPts = (i, j) => { const x0 = i * f - 0.5, y0 = j * f - 0.5, x1 = x0 + f, y1 = y0 + f; return [P(x0, y0), P(x1, y0), P(x1, y1), P(x0, y1), P((x0 + x1) / 2, (y0 + y1) / 2)]; };
  const inPoly = (pts, q) => { let c = false; for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) if ((pts[i].y > q.y) !== (pts[j].y > q.y) && q.x < (pts[j].x - pts[i].x) * (q.y - pts[i].y) / (pts[j].y - pts[i].y) + pts[i].x) c = !c; return c; };
  const meetsRect = (a, b, x0, y0, x1, y1) => {   // does segment a-b meet the closed rectangle (Liang-Barsky)
    let t0 = 0, t1 = 1; const dx = b.x - a.x, dy = b.y - a.y;
    for (const [pp, qq] of [[-dx, a.x - x0], [dx, x1 - a.x], [-dy, a.y - y0], [dy, y1 - a.y]]) {
      if (pp === 0) { if (qq < 0) return false; continue; }
      const t = qq / pp; if (pp < 0) { if (t > t1) return false; if (t > t0) t0 = t; } else { if (t < t0) return false; if (t < t1) t1 = t; }
    }
    return true;
  };
  const clearArea = (r, pts) => {
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    const rr = r.kind === 'poly' ? 0 : Math.max(0.5, +r.r || 0);
    for (const q of pts) { x0 = Math.min(x0, q.x - rr); y0 = Math.min(y0, q.y - rr); x1 = Math.max(x1, q.x + rr); y1 = Math.max(y1, q.y + rr); }
    const i0 = Math.max(0, Math.floor((x0 + 0.5) / f)), i1 = Math.min(W - 1, Math.floor((x1 + 0.5) / f)), j0 = Math.max(0, Math.floor((y0 + 0.5) / f)), j1 = Math.min(H - 1, Math.floor((y1 + 0.5) / f));
    const segs = pts.length === 1 ? [[pts[0], pts[0]]] : r.kind === 'poly' ? pts.map((q, i) => [q, pts[(i + 1) % pts.length]]) : pts.slice(1).map((q, i) => [pts[i], q]);
    const segD = (q, a, b) => { const dx = b.x - a.x, dy = b.y - a.y, L2 = dx * dx + dy * dy, t = L2 > 0 ? Math.max(0, Math.min(1, ((q.x - a.x) * dx + (q.y - a.y) * dy) / L2)) : 0; return Math.hypot(q.x - a.x - t * dx, q.y - a.y - t * dy); };
    for (let j = j0; j <= j1; j++) for (let i = i0; i <= i1; i++) {
      const cp = cellPts(i, j);
      const inside = r.kind === 'poly' && pts.length >= 3
        ? cp.every(q => inPoly(pts, q)) && !segs.some(([a, b]) => meetsRect(a, b, cp[0].x, cp[0].y, cp[2].x, cp[2].y))
        : segs.some(([a, b]) => cp.every(q => segD(q, a, b) <= rr));
      if (inside) m[j * W + i] = 0;
    }
  };
  const paint = (r, val, cover) => {   // cover: set every touched cell (an exclusion); else only cells wholly inside (clearing)
    const pts = Array.isArray(r.pts) ? r.pts.filter(p => p && Number.isFinite(p.x) && Number.isFinite(p.y)) : [];
    if (!pts.length) return;
    if (!cover) return clearArea(r, pts);
    if (r.kind === 'poly' && pts.length >= 3) {   // scanline: cells whose centre is inside; with cover, also every cell an edge crosses
      for (let j = 0; j < H; j++) {
        const y = cc(j), xs = [];
        for (let i = 0; i < pts.length; i++) { const a = pts[i], b = pts[(i + 1) % pts.length]; if ((a.y <= y) !== (b.y <= y)) xs.push(a.x + (y - a.y) * (b.x - a.x) / (b.y - a.y)); }
        xs.sort((p, q) => p - q);
        for (let k = 0; k + 1 < xs.length; k += 2) {
          const i0 = Math.max(0, Math.ceil((xs[k] - (f - 1) / 2) / f) + (cover ? 0 : 1)), i1 = Math.min(W - 1, Math.floor((xs[k + 1] - (f - 1) / 2) / f) - (cover ? 0 : 1));
          for (let i = i0; i <= i1; i++) m[j * W + i] = val;
        }
      }
      if (cover) for (let i = 0; i < pts.length; i++) walk(pts[i], pts[(i + 1) % pts.length], val);
      return;
    }
    const rr = Math.max(0.5, +r.r || 0), reach = cover ? rr + half : rr - half, segs = pts.length === 1 ? [[pts[0], pts[0]]] : pts.slice(1).map((p, i) => [pts[i], p]);
    if (reach <= 0) return;
    for (const [a, b] of segs) {   // a stroke: cells within r of the polyline (with cover, any cell it reaches; else wholly within)
      const i0 = Math.max(0, Math.floor((Math.min(a.x, b.x) - reach) / f) - 1), i1 = Math.min(W - 1, Math.ceil((Math.max(a.x, b.x) + reach) / f) + 1);
      const j0 = Math.max(0, Math.floor((Math.min(a.y, b.y) - reach) / f) - 1), j1 = Math.min(H - 1, Math.ceil((Math.max(a.y, b.y) + reach) / f) + 1);
      const dx = b.x - a.x, dy = b.y - a.y, L2 = dx * dx + dy * dy;
      for (let j = j0; j <= j1; j++) for (let i = i0; i <= i1; i++) {
        const x = cc(i), y = cc(j); let t = L2 > 0 ? ((x - a.x) * dx + (y - a.y) * dy) / L2 : 0; t = t < 0 ? 0 : t > 1 ? 1 : t;
        if ((x - a.x - t * dx) ** 2 + (y - a.y - t * dy) ** 2 <= reach * reach) m[j * W + i] = val;
      }
    }
  };
  for (const r of on) paint(r, 1, true);
  const g = Math.ceil(Math.max(0, marginPx) / f);
  if (g > 0) {   // grow by g cells (a square), rows then columns, with running counts
    const tmp = new Uint8Array(W * H);
    for (let j = 0; j < H; j++) { let c = 0; for (let i = 0; i < Math.min(W, g); i++) c += m[j * W + i]; for (let i = 0; i < W; i++) { if (i + g < W) c += m[j * W + i + g]; if (i - g - 1 >= 0) c -= m[j * W + i - g - 1]; tmp[j * W + i] = c > 0 ? 1 : 0; } }
    for (let i = 0; i < W; i++) { let c = 0; for (let j = 0; j < Math.min(H, g); j++) c += tmp[j * W + i]; for (let j = 0; j < H; j++) { if (j + g < H) c += tmp[(j + g) * W + i]; if (j - g - 1 >= 0) c -= tmp[(j - g - 1) * W + i]; m[j * W + i] = c > 0 ? 1 : 0; } }
  }
  for (const r of regs) if (r && r.cls === 'clear') paint(r, 0, false);
  let n = 0; for (let i = 0; i < m.length; i++) n += m[i];
  return n ? { f, W, H, m, n } : null;
}
export const rasterHas = (R, x, y) => {
  if (!R) return false;
  const i = Math.floor((x + 0.5) / R.f), j = Math.floor((y + 0.5) / R.f);
  return i >= 0 && j >= 0 && i < R.W && j < R.H && R.m[j * R.W + i] === 1;
};

// Projected potential contact in the fixation zone the surgeon chose (amendment 3; AP film only), over the traced levels
// inside the zone ({from, to} along the trace). Astra, 16 Oct 2026, P1 6: a level counts as potential contact only when an
// edge of the stem outline lies within tolMm of an inner cortex (either side) without passing it, and the outline overlaps
// the canal; a level where the outline reaches more than tolMm past the inner cortex is cortical penetration, and one where
// it lies wholly beside the canal is outside it: both are reported apart, never as contact. A projection on one film, not
// engagement: the levels are only where the cortex was seen (the marked implant and untraced stretches are left out and
// reported as not observed). → {zoneMm, observedMm, contactMm, penetrationMm, outsideMm, levels, contactLevels,
// penetrationLevels, outsideLevels}
export function contactInZone(poly, tr, mmPerPx, zone, tolMm = 1) {
  const z0 = Math.min(zone.from, zone.to), z1 = Math.max(zone.from, zone.to), stepMm = tr.stepMm || 2, zoneMm = (z1 - z0) * mmPerPx;
  let obs = 0, touch = 0, pen = 0, out = 0;
  for (const lv of tr.levels) {
    if (lv.s < z0 || lv.s > z1) continue;
    obs++;
    const x = crossAt(poly, lv, tr.nrm, mmPerPx); if (!x) continue;
    const w = dist(lv.l, lv.r) * mmPerPx;
    if (x.inL < -w || x.inR < -w) { out++; continue; }   // the outline lies wholly beside the canal
    if (Math.max(x.inL, x.inR) > tolMm) { pen++; continue; }   // past the inner cortex
    if (Math.abs(x.inL) <= tolMm || Math.abs(x.inR) <= tolMm) touch++;
  }
  const mmOf = n => Math.min(zoneMm, n * stepMm);
  return { zoneMm, observedMm: mmOf(obs), contactMm: mmOf(touch), penetrationMm: mmOf(pen), outsideMm: mmOf(out), levels: obs, contactLevels: touch, penetrationLevels: pen, outsideLevels: out };
}
// Does a film's mask record belong to this image (amendment 5)? '' when it does; else why not: another image (its sha256),
// or another decoded size. A record of another image is never applied (film replacement invalidates it).
export function existFits(ex, sha, w, h) {
  if (!ex) return '';
  if (ex.sha && sha && ex.sha !== sha) return 'another image';
  if ((ex.w !== undefined && ex.w !== w) || (ex.h !== undefined && ex.h !== h)) return 'another image size';
  return '';
}
