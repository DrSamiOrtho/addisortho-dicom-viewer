// Geometry for the templating page (10 Oct 2026). Image coordinates: x to the right, y down, in pixels.
// Pure functions, no DOM, so test.mjs can check them in Node.

export const P = (x, y) => ({ x, y });
export const add = (a, b) => P(a.x + b.x, a.y + b.y);
export const sub = (a, b) => P(a.x - b.x, a.y - b.y);
export const mul = (a, k) => P(a.x * k, a.y * k);
export const dot = (a, b) => a.x * b.x + a.y * b.y;
export const cross = (a, b) => a.x * b.y - a.y * b.x;
export const len = a => Math.hypot(a.x, a.y);
export const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
export const unit = a => { const l = len(a) || 1; return P(a.x / l, a.y / l); };
export const perp = a => P(-a.y, a.x);
export const mid = (a, b) => P((a.x + b.x) / 2, (a.y + b.y) / 2);
export const rot = (a, t) => P(a.x * Math.cos(t) - a.y * Math.sin(t), a.x * Math.sin(t) + a.y * Math.cos(t));
export const deg = r => r * 180 / Math.PI;
export const rad = d => d * Math.PI / 180;

// Unsigned angle between two directions, 0..180 degrees.
export const angleDeg = (u, v) => (len(u) < 1e-9 || len(v) < 1e-9 ? NaN : deg(Math.acos(Math.max(-1, Math.min(1, dot(unit(u), unit(v)))))));
// Signed angle to turn u onto v, radians, -pi..pi (positive = clockwise on screen, since y points down).
export const turn = (u, v) => Math.atan2(cross(u, v), dot(u, v));

// Distance from p to the infinite line through a and b (always >= 0).
export const lineDist = (p, a, b) => Math.abs(cross(sub(b, a), sub(p, a))) / (dist(a, b) || 1);
// Foot of the perpendicular from p onto the line through a with direction d.
export const project = (p, a, d) => { const u = unit(d); return add(a, mul(u, dot(sub(p, a), u))); };

// Circle through three points, or null if they are (nearly) in a line.
export function circle3(a, b, c) {
  const d = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y));
  const side = Math.max(dist(a, b), dist(b, c), dist(c, a));
  if (Math.abs(d) < 1e-9 || Math.abs(d) / 4 < 0.02 * side * side) return null;   // nearly in a line: no usable circle
  const a2 = a.x * a.x + a.y * a.y, b2 = b.x * b.x + b.y * b.y, c2 = c.x * c.x + c.y * c.y;
  const x = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d;
  const y = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d;
  return { x, y, r: Math.hypot(a.x - x, a.y - y) };
}

// Angle (degrees) the points cover around centre c: 360 minus the largest gap between them.
export function arcSpan(pts, c) {
  const a = pts.map(p => Math.atan2(p.y - c.y, p.x - c.x)).sort((x, y) => x - y);
  let gap = 0; for (let i = 0; i < a.length; i++) gap = Math.max(gap, (i + 1 < a.length ? a[i + 1] : a[0] + 2 * Math.PI) - a[i]);
  return deg(2 * Math.PI - gap);
}

// Least-squares circle (Kasa, then a few Gauss-Newton steps on the geometric error). pts: [{x,y}], at least 3.
export function fitCircle(pts) {
  const n = pts.length; if (n < 3) return null;
  let mx = 0, my = 0; for (const p of pts) { mx += p.x; my += p.y; } mx /= n; my /= n;
  let suu = 0, svv = 0, suv = 0, suuu = 0, svvv = 0, suvv = 0, svuu = 0;
  for (const p of pts) {
    const u = p.x - mx, v = p.y - my;
    suu += u * u; svv += v * v; suv += u * v; suuu += u * u * u; svvv += v * v * v; suvv += u * v * v; svuu += v * u * u;
  }
  const det = suu * svv - suv * suv; if (Math.abs(det) < 1e-12) return null;
  const bu = 0.5 * (suuu + suvv), bv = 0.5 * (svvv + svuu);
  let cx = mx + (bu * svv - bv * suv) / det, cy = my + (bv * suu - bu * suv) / det;
  let r = 0; for (const p of pts) r += Math.hypot(p.x - cx, p.y - cy); r /= n;
  for (let it = 0; it < 10; it++) {             // Gauss-Newton on (cx, cy, r)
    let JTJ = [[0, 0, 0], [0, 0, 0], [0, 0, 0]], JTr = [0, 0, 0];
    for (const p of pts) {
      const dx = p.x - cx, dy = p.y - cy, d = Math.hypot(dx, dy) || 1e-9, res = d - r;
      const J = [-dx / d, -dy / d, -1];
      for (let i = 0; i < 3; i++) { JTr[i] += J[i] * res; for (let j = 0; j < 3; j++) JTJ[i][j] += J[i] * J[j]; }
    }
    const s = solve3(JTJ, JTr.map(v => -v)); if (!s) break;
    cx += s[0]; cy += s[1]; r += s[2];
    if (Math.hypot(s[0], s[1], s[2]) < 1e-6) break;
  }
  let ss = 0; for (const p of pts) ss += (Math.hypot(p.x - cx, p.y - cy) - r) ** 2;
  return { x: cx, y: cy, r, rms: Math.sqrt(ss / n) };
}

function solve3(A, b) {
  const m = A.map((row, i) => [...row, b[i]]);
  for (let c = 0; c < 3; c++) {
    let piv = c; for (let r = c + 1; r < 3; r++) if (Math.abs(m[r][c]) > Math.abs(m[piv][c])) piv = r;
    if (Math.abs(m[piv][c]) < 1e-12) return null;
    [m[c], m[piv]] = [m[piv], m[c]];
    for (let r = 0; r < 3; r++) if (r !== c) { const f = m[r][c] / m[c][c]; for (let k = c; k < 4; k++) m[r][k] -= f * m[c][k]; }
  }
  return [m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2]];
}

// Bilinear sample of a Float32Array image.
export function sample(img, x, y) {
  const { cols, rows, data } = img;
  if (x < 0 || y < 0 || x > cols - 1.001 || y > rows - 1.001) return NaN;
  const x0 = Math.floor(x), y0 = Math.floor(y), fx = x - x0, fy = y - y0, i = y0 * cols + x0;
  return (data[i] * (1 - fx) + data[i + 1] * fx) * (1 - fy) + (data[i + cols] * (1 - fx) + data[i + cols + 1] * fx) * fy;
}

// Find the calibration ball around a click: along 90 rays from the seed, the edge is where brightness falls fastest
// (the ball is the most opaque thing nearby). Robust circle fit to those edge points, dropping outliers.
// img: {cols, rows, data, invert}; seed: rough centre (px); rMax: search radius (px).
// Returns {x, y, r, rms, used, rays} or null.
export function detectBall(img, seed, rMax, pass = 0) {
  const sign = img.invert ? -1 : 1, step = 0.5, nRays = 90, edges = [];
  for (let k = 0; k < nRays; k++) {
    const t = 2 * Math.PI * k / nRays, dx = Math.cos(t), dy = Math.sin(t), prof = [];
    for (let r = 0; r <= rMax; r += step) prof.push(sign * sample(img, seed.x + dx * r, seed.y + dy * r));
    if (prof.some(Number.isNaN)) continue;
    const sm = prof.map((_, i) => { let s = 0, c = 0; for (let j = i - 2; j <= i + 2; j++) if (prof[j] !== undefined) { s += prof[j]; c++; } return s / c; });
    let best = 0, bi = -1;
    for (let i = 4; i < sm.length - 3; i++) { const g = sm[i + 1] - sm[i - 1]; if (g < best) { best = g; bi = i; } }
    if (bi < 0) continue;
    const gm = sm[bi] - sm[bi - 2], g0 = sm[bi + 1] - sm[bi - 1], gp = sm[bi + 2] - sm[bi];   // sub-sample peak
    const den = gm - 2 * g0 + gp, off = Math.abs(den) > 1e-9 ? 0.5 * (gm - gp) / den : 0;
    const r = (bi + Math.max(-1, Math.min(1, off))) * step;
    edges.push({ x: seed.x + dx * r, y: seed.y + dy * r, g: -best });
  }
  if (edges.length < 12) return null;
  let pts = edges, c = null;
  for (let it = 0; it < 4; it++) {
    c = fitCircle(pts); if (!c) return null;
    const res = pts.map(p => Math.abs(Math.hypot(p.x - c.x, p.y - c.y) - c.r));
    const med = [...res].sort((a, b) => a - b)[Math.floor(res.length / 2)];
    const keep = pts.filter((_, i) => res[i] <= Math.max(0.75, 3 * 1.4826 * med));
    if (keep.length === pts.length || keep.length < 12) break;
    pts = keep;
  }
  if (!(c && c.r > 3 && c.r < rMax * 0.97)) return null;
  // second pass from the fitted centre: the rays then cross the rim square on, so the result does not depend on where
  // inside the ball the surgeon clicked (Fable review: up to 0.7 % before)
  if (pass === 0 && Math.hypot(c.x - seed.x, c.y - seed.y) > 0.5) { const again = detectBall(img, { x: c.x, y: c.y }, rMax, 1); if (again) return again; }
  // Quality gate (Astra review, 10 Oct 2026): a ball has a clear edge all round and fits a circle closely.
  const coverage = pts.length / nRays, reasons = [], warnings = [];
  if (coverage < 0.6) reasons.push(`edge found on only ${Math.round(coverage * 100)} % of the rim`);
  if (c.rms > Math.max(1.0, 0.02 * c.r)) reasons.push(`edge does not follow a circle (scatter ${c.rms.toFixed(1)} px)`);
  const edgeStep = ballStep(img, c);
  if (edgeStep !== null && edgeStep < 0.2) reasons.push(`too faint for a steel ball (its edge step is ${Math.round(edgeStep * 100)} % of the film's grey range)`);
  if (reasons.length) return { rejected: true, reasons, x: c.x, y: c.y, r: c.r };
  if (coverage < 0.8) warnings.push(`edge found on ${Math.round(coverage * 100)} % of the rim only (ball partly hidden or off the film?)`);
  // A ball away from the central ray casts a slightly oval shadow: stretched along the line to the beam centre, true
  // to scale across it. So size it sector by sector (12 x 30 deg) from its shading, then fit radius(angle) with a
  // 1st harmonic (centre slightly off) and a 2nd harmonic (oval): the short half-width carries the scale.
  const edgeR = c.r; let g = sphereRadius(img, c, c.r); const g0 = g;
  if (g && g.rise > 0.06) warnings.push('soft edge (not a solid steel ball, or strong image processing): the scale may be off by several %; check it against a known length or mark the rim by hand');
  if (g && Math.abs(g.r / edgeR - 1) > 0.03) {   // the shading does not look like a ball (image processing): use the edge
    warnings.push(`shading model disagreed with the edge by ${(100 * (g.r / edgeR - 1)).toFixed(1)} %; edge used`); g = null;
  }
  let rad = [];
  if (g) for (let k = 0; k < 12; k++) { const t0 = (k + 0.5) * Math.PI / 6, f = sphereRadius(img, c, g.r, { t0, half: Math.PI / 12, k: g.k, s: g.s }); if (f) rad.push([t0, f.r]); }
  if (rad.length < 8) rad = pts.map(p => [Math.atan2(p.y - c.y, p.x - c.x), Math.hypot(p.x - c.x, p.y - c.y) * (g ? g.r / edgeR : 1)]);
  const h = lsq(rad.map(([t]) => [1, Math.cos(t), Math.sin(t), Math.cos(2 * t), Math.sin(2 * t)]), rad.map(([, r]) => r));
  const amp = h ? Math.hypot(h[3], h[4]) : 0, r0 = h ? h[0] : c.r, ovality = (r0 + amp) / (r0 - amp);
  if (ovality > 1.15) return { rejected: true, reasons: [`shape is ${((ovality - 1) * 100).toFixed(0)} % oval, not a ball`], x: c.x, y: c.y, r: c.r };
  if (ovality > 1.07) warnings.push(`shadow ${((ovality - 1) * 100).toFixed(1)} % oval: check it is the ball`);
  return { ...c, r: r0 - amp, rMajor: r0 + amp, edgeR, blur: g && g.s, clip: g && g.k, model: g ? g.model : 'edge', sectors: rad.length,
    used: pts.length, rays: nRays, quality: { coverage, rmsPx: c.rms, ovality, rise: g0 ? g0.rise : null }, warnings };
}

// Brightness step from just outside the rim to just inside it, as a share of the film's 1st-99th percentile range.
function ballStep(img, c) {
  const sign = img.invert ? -1 : 1, at = (r) => { const v = []; for (let k = 0; k < 72; k++) { const t = k * Math.PI / 36, s = sample(img, c.x + r * Math.cos(t), c.y + r * Math.sin(t)); if (!Number.isNaN(s)) v.push(sign * s); } v.sort((a, b) => a - b); return v.length ? v[v.length >> 1] : NaN; };
  const inside = at(0.6 * c.r), outside = at(1.3 * c.r);
  const n = img.data.length, stepN = Math.max(1, Math.floor(n / 50000)), smp = [];
  for (let i = 0; i < n; i += stepN) smp.push(sign * img.data[i]);
  smp.sort((a, b) => a - b);
  const range = smp[Math.floor(smp.length * 0.99)] - smp[Math.floor(smp.length * 0.01)];
  return Number.isFinite(inside) && Number.isFinite(outside) && range > 0 ? (inside - outside) / range : null;
}

// Least squares for a small dense system (normal equations), A: rows of length n.
function lsq(A, b) {
  const n = A[0].length, M = Array.from({ length: n }, () => new Array(n + 1).fill(0));
  for (let i = 0; i < A.length; i++) for (let j = 0; j < n; j++) { M[j][n] += A[i][j] * b[i]; for (let k = 0; k < n; k++) M[j][k] += A[i][j] * A[i][k]; }
  for (let col = 0; col < n; col++) {
    let piv = col; for (let r = col + 1; r < n; r++) if (Math.abs(M[r][col]) > Math.abs(M[piv][col])) piv = r;
    if (Math.abs(M[piv][col]) < 1e-12) return null;
    [M[col], M[piv]] = [M[piv], M[col]];
    for (let r = 0; r < n; r++) if (r !== col) { const fct = M[r][col] / M[col][col]; for (let k = col; k <= n; k++) M[r][k] -= fct * M[col][k]; }
  }
  return M.map((row, i) => row[n] / row[i]);
}

// Size the ball from its shading, not from its steepest edge. Through a sphere the path length is the chord
// 2*sqrt(R^2 - r^2); a steel ball usually saturates the detector a little inside the rim, so the brightness follows
// the chord clipped at some level, then the detector blurs it. Model: B + A * blur_s(min(chord(r), k * R)).
// k = 2 is a pure sphere, k near 0 a flat disc (a drawn marker), anything between a saturated sphere. Fitted to the
// median radial profile around the found centre: R, k and s by grid search (coarse, then fine), A and B by least
// squares. Without this, the steepest edge reads 1-3 % small. Returns {r, s, k, model} or null (null when the best
// radius sits on the edge of the search range; then the plain edge radius is used).
export function sphereRadius(img, c, r0, opt = {}) {
  // opt: {t0, half} only the directions within half of angle t0; {k, s} fixed clip and blur (fit the radius only)
  const sign = img.invert ? -1 : 1, dr = 0.25, r1 = 0.6 * r0, r2 = 1.4 * r0, rs = [], prof = [];
  const angles = []; if (opt.t0 === undefined) for (let k = 0; k < 180; k++) angles.push(2 * Math.PI * k / 180);
  else for (let d = -opt.half; d <= opt.half + 1e-9; d += Math.PI / 90) angles.push(opt.t0 + d);
  for (let r = r1; r <= r2; r += dr) {
    const v = [];
    for (const t of angles) { const s = sample(img, c.x + r * Math.cos(t), c.y + r * Math.sin(t)); if (!Number.isNaN(s)) v.push(sign * s); }
    if (v.length < angles.length / 2) return null;
    v.sort((a, b) => a - b); rs.push(r); prof.push(v[v.length >> 1]);
  }
  const n = rs.length, kernels = new Map();
  const kernel = s => {
    const key = Math.round(s * 100); if (kernels.has(key)) return kernels.get(key);
    const half = Math.ceil(3 * s / dr), w = []; for (let i = -half; i <= half; i++) w.push(Math.exp(-0.5 * (i * dr / s) ** 2));
    const sum = w.reduce((a, b) => a + b, 0), kv = { half, w: w.map(x => x / sum) }; kernels.set(key, kv); return kv;
  };
  const err = (R, s, k) => {
    const { half, w } = kernel(s), clip = k * R, ch = rs.map(r => (r < R ? Math.min(2 * Math.sqrt(R * R - r * r), clip) : 0));
    let sm = 0, sp = 0, smm = 0, smp = 0; const m = new Array(n);
    for (let i = 0; i < n; i++) { let acc = 0; for (let j = -half; j <= half; j++) acc += w[j + half] * ch[Math.min(n - 1, Math.max(0, i + j))]; m[i] = acc; sm += acc; sp += prof[i]; smm += acc * acc; smp += acc * prof[i]; }
    const den = n * smm - sm * sm; if (Math.abs(den) < 1e-12) return Infinity;
    const A = (n * smp - sm * sp) / den, B = (sp - A * sm) / n; if (A <= 0) return Infinity;
    let e = 0; for (let i = 0; i < n; i++) e += (prof[i] - B - A * m[i]) ** 2; return e;
  };
  const span = Math.max(3, 0.06 * r0), ks = [0.01, 0.03, 0.06, 0.1, 0.15, 0.22, 0.32, 0.45, 0.65, 0.9, 1.3, 2.0];
  let best = { e: Infinity };
  if (opt.k !== undefined) {   // radius only, clip and blur from the whole-ball fit
    for (let R = r0 - span; R <= r0 + span + 1e-9; R += 0.02) { const e = err(R, opt.s, opt.k); if (e < best.e) best = { e, r: R, s: opt.s, k: opt.k }; }
  } else {
    for (const k of ks) for (let s = 0.2; s <= 8.0 + 1e-9; s += s < 4 ? 0.2 : 0.5) for (let R = r0 - span; R <= r0 + span + 1e-9; R += 0.25) {
      const e = err(R, s, k); if (e < best.e) best = { e, r: R, s, k };
    }
    const ki = ks.indexOf(best.k), kf = [ks[Math.max(0, ki - 1)], best.k, ks[Math.min(ks.length - 1, ki + 1)]];
    for (const k of [...new Set([...kf, (kf[0] + best.k) / 2, (best.k + kf[2]) / 2])])
      for (let s = Math.max(0.1, best.s - 0.2); s <= best.s + 0.2 + 1e-9; s += 0.05)
        for (let R = best.r - 0.3; R <= best.r + 0.3 + 1e-9; R += 0.02) { const e = err(R, s, k); if (e < best.e) best = { e, r: R, s, k }; }
  }
  if (!Number.isFinite(best.e) || Math.abs(best.r - r0) > span - 0.1) return null;
  // edge sharpness: distance over which the median profile climbs from 25 % to 75 % of the step, as a share of the radius
  const inner = prof.filter((_, i) => rs[i] < 0.75 * r0), outer = prof.filter((_, i) => rs[i] > 1.2 * r0);
  const med = a => { const b = [...a].sort((x, y) => x - y); return b[b.length >> 1]; };
  const lo = med(outer), hi = med(inner), at = f => { const v = lo + f * (hi - lo); for (let i = n - 1; i > 0; i--) if ((prof[i] - v) * (prof[i - 1] - v) <= 0) return rs[i]; return NaN; };
  best.rise = Math.abs(at(0.25) - at(0.75)) / r0;
  return { ...best, model: best.k >= 1.9 ? 'sphere' : best.k <= 0.05 ? 'flat' : 'saturated sphere' };
}
