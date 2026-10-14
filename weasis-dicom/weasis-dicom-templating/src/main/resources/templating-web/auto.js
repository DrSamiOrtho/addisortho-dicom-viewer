// Automatic helpers for the templating page (11 Oct 2026): find the calibration ball on the whole film, read the R/L
// marker, and find the femoral canal's inner cortical edges. Pure functions on the decoded image (no DOM), so
// test.mjs can check them in Node. Image: {cols, rows, data: Float32Array, invert}. Pixels, x right, y down.
// Every result is a proposal the surgeon sees and can overrule; nothing here decides on its own.
import { P, add, sub, mul, dot, unit, perp, dist, sample, detectBall } from './geom.js';

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
export function findBalls(img, { mmPerPx = null, ballMm = 25, maxTries = 5 } = {}) {
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
  for (const c of cands.slice(0, maxTries)) {
    const b = detectBall(img, P(c.x, c.y), Math.min(900, 1.8 * c.r));
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
export function findMarkers(img, { mmPerPx = null } = {}) {
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
export function traceCanal(img, a, b, mmPerPx, { stepMm = 2, halfMm = 25, aboveMm = 0, beyondMm = 60 } = {}) {
  const sign = img.invert ? -1 : 1, px = v => v / mmPerPx, Lab = dist(a, b);
  if (!(mmPerPx > 0) || Lab < px(20)) return null;
  const d = unit(sub(b, a)), n = perp(d), ds = 0.5, K = Math.round(px(halfMm) / ds), sk = Math.max(1, Math.round(px(0.35) / ds));
  const kmm = mm => Math.round(px(mm) / ds), levels = [];
  let tried = 0;
  for (let s = -px(aboveMm); s <= Lab + px(beyondMm); s += px(stepMm)) {
    const c = add(a, mul(d, s)), prof = new Float64Array(2 * K + 1);
    let off = false;
    for (let k = -K; k <= K && !off; k++) {
      let acc = 0;
      for (const t of [-0.75, -0.375, 0, 0.375, 0.75]) { const q = add(c, add(mul(n, k * ds), mul(d, px(t)))), v = sample(img, q.x, q.y); if (Number.isNaN(v)) { off = true; break; } acc += v; }
      prof[k + K] = sign * acc / 5;
    }
    if (off) continue;
    tried++;
    const M = prof.length, sm = new Float64Array(M), g = new Float64Array(M);
    for (let i = 0; i < M; i++) { let acc = 0, cnt = 0; for (let j = i - sk; j <= i + sk; j++) if (j >= 0 && j < M) { acc += prof[j]; cnt++; } sm[i] = acc / cnt; }
    for (let i = 1; i < M - 1; i++) g[i] = (sm[i + 1] - sm[i - 1]) / 2;
    const noise = [...g].map(Math.abs).sort((x, y) => x - y)[M >> 1] || 1e-9;
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
  if (!fit || keep.length < 6) return { levels: keep, axis: null, n: keep.length, tried, rmsMm: fit && fit.rms, spanMm: 0, d, nrm: n, origin: a };
  const span = (Math.max(...keep.map(l => l.s)) - Math.min(...keep.map(l => l.s))) * mmPerPx;
  const A = add(a, mul(n, fit.alpha)), B = add(add(a, mul(d, Lab)), mul(n, fit.alpha + fit.beta * Lab));
  return { levels: keep, axis: span >= 30 ? { a: A, b: B } : null, n: keep.length, tried, rmsMm: fit.rms, spanMm: span, d, nrm: n, origin: a };
}

// ---------- how a stem sits in the traced canal ----------
// poly: the stem outline in image px; tr: traceCanal's result; mmPerPx. At every level where the canal's inner edges
// were found and the stem crosses: how far the outline reaches past the inner cortex (positive) on either side, and
// the fill. Also how much of the stem the evidence covers (coveredMm, from the first to the last such level) and the
// longest stretch without a level (maxGapMm): a fit judged on a short or broken trace is not a fit (Astra, 11 Oct).
export function canalFit(poly, tr, mmPerPx, region) {
  const out = [], n = tr.nrm;
  for (const lv of tr.levels) {
    const p = lv.mid, xs = [];
    for (let i = 0; i < poly.length; i++) {
      const a = poly[i], e = sub(poly[(i + 1) % poly.length], a), den = n.x * e.y - n.y * e.x; if (Math.abs(den) < 1e-12) continue;
      const w = sub(a, p), s = (w.x * e.y - w.y * e.x) / den, u = (w.x * n.y - w.y * n.x) / den; if (u >= 0 && u < 1) xs.push(s);
    }
    if (xs.length < 2) continue;
    const s0 = Math.min(...xs), s1 = Math.max(...xs), kl = dot(sub(lv.l, p), n), kr = dot(sub(lv.r, p), n);
    out.push({ s: lv.s, inL: (kl - s0) * mmPerPx, inR: (s1 - kr) * mmPerPx, fill: (s1 - s0) / (kr - kl) });
  }
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
