// Film clean-up for the templating page (11 Oct 2026, Sami: "clear unnecessary labelling and garbage", e.g. ECG leads,
// clips and wires on an AP pelvis). Brush strokes are filled from the film around them, so the saved picture is easier
// to read. Only the displayed and saved picture change: every measurement and automatic finder keeps using the
// original pixels, and the saved report says how many areas were filled. Pure functions (no DOM), checked by test.mjs.
// Image data: Float32Array, row-major, cols x rows. A stroke: { pts: [{x, y}, ...], r } in image pixels.

// Pixels within r of the stroke's polyline, as a mask over the stroke's bounding box (plus `margin` all round).
export function strokeMask(cols, rows, stroke, margin = 0) {
  const { pts, r } = stroke; if (!pts || !pts.length || !(r > 0)) return null;
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const p of pts) { x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y); }
  const e = r + margin;
  x0 = Math.max(0, Math.floor(x0 - e)); y0 = Math.max(0, Math.floor(y0 - e));
  x1 = Math.min(cols - 1, Math.ceil(x1 + e)); y1 = Math.min(rows - 1, Math.ceil(y1 + e));
  if (x1 < x0 || y1 < y0) return null;
  const w = x1 - x0 + 1, h = y1 - y0 + 1, m = new Uint8Array(w * h), r2 = r * r;
  const segs = pts.length === 1 ? [[pts[0], pts[0]]] : pts.slice(1).map((p, i) => [pts[i], p]);
  for (const [a, b] of segs) {
    const sx0 = Math.max(x0, Math.floor(Math.min(a.x, b.x) - r)), sx1 = Math.min(x1, Math.ceil(Math.max(a.x, b.x) + r));
    const sy0 = Math.max(y0, Math.floor(Math.min(a.y, b.y) - r)), sy1 = Math.min(y1, Math.ceil(Math.max(a.y, b.y) + r));
    const dx = b.x - a.x, dy = b.y - a.y, L2 = dx * dx + dy * dy;
    for (let y = sy0; y <= sy1; y++) for (let x = sx0; x <= sx1; x++) {
      let t = L2 > 0 ? ((x - a.x) * dx + (y - a.y) * dy) / L2 : 0; t = t < 0 ? 0 : t > 1 ? 1 : t;
      const ex = x - (a.x + t * dx), ey = y - (a.y + t * dy);
      if (ex * ex + ey * ey <= r2) m[(y - y0) * w + (x - x0)] = 1;
    }
  }
  return { x0, y0, w, h, m };
}

// Fill the masked pixels from the known pixels around them: a push-pull pyramid gives every hole a smooth first guess,
// Gauss-Seidel relaxation makes it a membrane that meets the edge without a seam, and a little noise matched to the
// film's own grain keeps it from looking smeared. Deterministic (seeded), changes `data` in place.
export function inpaint(data, cols, rows, mk, seed = 1) {
  const { x0, y0, w, h, m } = mk, n = w * h;
  let holes = 0; for (let i = 0; i < n; i++) holes += m[i];
  if (!holes || holes === n) return 0;
  // the region, known pixels weighted 1
  let lv = [{ w, h, v: new Float64Array(n), k: new Float64Array(n) }];
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const i = y * w + x; if (!m[i]) { lv[0].v[i] = data[(y0 + y) * cols + x0 + x]; lv[0].k[i] = 1; } }
  while (lv[lv.length - 1].w > 1 || lv[lv.length - 1].h > 1) {   // push: sums over 2x2 blocks
    const a = lv[lv.length - 1], W = Math.ceil(a.w / 2), H = Math.ceil(a.h / 2), v = new Float64Array(W * H), k = new Float64Array(W * H);
    for (let y = 0; y < a.h; y++) for (let x = 0; x < a.w; x++) { const j = (y >> 1) * W + (x >> 1), i = y * a.w + x; v[j] += a.v[i]; k[j] += a.k[i]; }
    lv.push({ w: W, h: H, v, k });
  }
  // a cell with no known pixel under it is a hole at that level
  for (const l of lv) { l.hole = new Uint8Array(l.v.length); for (let i = 0; i < l.v.length; i++) { if (l.k[i] > 0) l.v[i] /= l.k[i]; else l.hole[i] = 1; } }
  // relaxation of a level's holes towards the mean of their 4 neighbours (known cells stay fixed)
  const relax = (l, sweeps) => {
    const { w: W, h: H, v, hole } = l, idx = [];
    for (let i = 0; i < v.length; i++) if (hole[i]) idx.push(i);
    for (let it = 0; it < sweeps; it++) for (const i of idx) {
      const x = i % W, y = (i - x) / W; let s = 0, c = 0;
      if (x > 0) { s += v[i - 1]; c++; } if (x < W - 1) { s += v[i + 1]; c++; }
      if (y > 0) { s += v[i - W]; c++; } if (y < H - 1) { s += v[i + W]; c++; }
      if (c) v[i] = s / c;
    }
  };
  // pull, coarse to fine: a hole takes its parent's value, then a few sweeps smooth it at that level (multigrid-like, so
  // wide strokes cost little more than narrow ones; Astra, 11 Oct: 3.6 s for a wide stroke before)
  for (let s = lv.length - 2; s >= 0; s--) {
    const a = lv[s], p = lv[s + 1];
    for (let y = 0; y < a.h; y++) for (let x = 0; x < a.w; x++) { const i = y * a.w + x; if (a.hole[i]) a.v[i] = p.v[(y >> 1) * p.w + (x >> 1)]; }
    relax(a, s === 0 ? 24 : 8);
  }
  const f = lv[0].v; lv = null;
  const idx = []; for (let i = 0; i < n; i++) if (m[i]) idx.push(i);
  // grain: spread of (pixel - mean of its 4 neighbours) over known pixels next to a known ring
  let s2 = 0, cnt = 0;
  for (let y = 1; y < h - 1; y++) for (let x = 1; x < w - 1; x++) {
    const i = y * w + x; if (m[i] || m[i - 1] || m[i + 1] || m[i - w] || m[i + w]) continue;
    const d = f[i] - (f[i - 1] + f[i + 1] + f[i - w] + f[i + w]) / 4; s2 += d * d; cnt++;
  }
  const grain = cnt > 20 ? 0.8 * Math.sqrt(s2 / cnt) : 0;
  let st = (seed * 2654435761) >>> 0 || 1;
  const rnd = () => { st ^= st << 13; st >>>= 0; st ^= st >>> 17; st ^= st << 5; st >>>= 0; return st / 4294967296; };
  for (const i of idx) {
    const g = grain ? Math.sqrt(-2 * Math.log(rnd() + 1e-12)) * Math.cos(2 * Math.PI * rnd()) * grain : 0;
    const x = i % w, y = (i - x) / w, v = f[i] + g;
    data[(y0 + y) * cols + x0 + x] = Number.isFinite(v) ? v : data[(y0 + y) * cols + x0 + x];
  }
  return holes;
}

// The cleaned copy of a film: every stroke filled in turn (later strokes see the earlier fills). null when no strokes.
export function cleanCopy(data, cols, rows, strokes) {
  if (!strokes || !strokes.length) return null;
  const out = data.slice();
  strokes.forEach((s, i) => addStroke(out, cols, rows, s, i));
  return out;
}
// One more stroke on an existing cleaned copy (the same fill cleanCopy gives it as stroke number i, from 0).
export function addStroke(out, cols, rows, s, i) {
  const mk = strokeMask(cols, rows, s, Math.max(6, Math.round(s.r * 0.6))); return mk ? inpaint(out, cols, rows, mk, i + 1) : 0;
}
