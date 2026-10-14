// The hip's automatic plan rules (13 Oct 2026; DESIGN-2026-10-13-autotemplate.md with Astra's binding amendments): the
// parts that are plain arithmetic, kept out of app.js so test.mjs can check them without a page. app.js lays the
// outlines on the film and measures; these decide. Every result is a starting position for the surgeon, not a decision:
// passing tests does not make the page ready for clinical use (it keeps its trial wording).
import * as G from './geom.js';

// Teardrop technique (amendment 1: a starting position the surgeon judges): the inferomedial end of the cup's face on
// the teardrop line, latPx lateral of the teardrop's lowest point, the face at inclDeg to the teardrop line.
// T: the teardrop; u: unit vector along the teardrop line towards this hip's lateral side; v: unit vector, cranial;
// R: the cup's outer radius; all in image pixels. Returns the cup centre.
export function teardropCentre(T, u, v, latPx, inclDeg, R) {
  const i = G.rad(inclDeg), fd = G.add(G.mul(u, Math.cos(i)), G.mul(v, Math.sin(i)));
  return G.add(G.add(T, G.mul(u, latPx)), G.mul(fd, R));
}

// Native centre height, medial edge at the teardrop (14 Oct 2026, the default cup rule; Sami on case 2: the teardrop rule
// put the cup centre 8.8 mm medial and 7.6 mm above the native head centre, the dome up in the ilium; he wants the cup in
// the native acetabulum, the centre of rotation restored, slightly medialised). N: the native head centre of this hip (for
// a deformed side the reference hip's, mirrored). The cup keeps N's height along the pelvic vertical v and moves medially
// along u from N until its most medial outer point (C - u R) is latPx lateral of the teardrop, but never more than
// maxMedPx medial of N and never lateral of N (a native centre already medial of that position is kept: protrusio-like).
// T, u, v, R as teardropCentre; all in image pixels. Returns {C, medPx (how far medial of N), capped, kept}.
export function anatomicCentre(N, T, u, latPx, R, maxMedPx) {
  const want = G.dot(G.sub(N, T), u) - (latPx + R);   // how far N is lateral of the position that puts the medial edge latPx from T
  const kept = !(want > 0), capped = !kept && want > maxMedPx, medPx = kept ? 0 : Math.min(want, maxMedPx);
  return { C: G.sub(N, G.mul(u, medPx)), medPx, capped, kept };
}

// Roof sizing with the anatomic centre (14 Oct 2026, Astra's review of the cup rule, finding 3): the size from the roof edge
// (reach / cos i) assumes the teardrop placement; with the centre at the native height the cup sits further lateral, so
// size and centre are solved together: from the size the roof formula wants (startIdx in sizes, ascending) the size is
// stepped down until the superolateral rim (C + R(u cos i + v sin i)) is no further lateral than the roof edge (tolPx
// allowed), each size placed by anatomicCentre (so the kept and the capped cases move with it). roofLatPx: the roof edge
// along u from T. outerPx(z): a size's outer radius in px. Returns {idx, C, a, overPx (rim past the roof, > 0 only when even
// the smallest size overshoots), stepped (sizes stepped down)}.
export function anatomicRoofFit(sizes, startIdx, outerPx, N, T, u, latPx, maxMedPx, inclDeg, roofLatPx, tolPx = 0) {
  const ci = Math.cos(G.rad(inclDeg));
  let out = null;
  for (let i = Math.min(startIdx, sizes.length - 1); i >= 0; i--) {
    const R = outerPx(sizes[i]), a = anatomicCentre(N, T, u, latPx, R, maxMedPx), rim = G.dot(G.sub(a.C, T), u) + R * ci;
    out = { idx: i, C: a.C, a, overPx: rim - roofLatPx, stepped: Math.min(startIdx, sizes.length - 1) - i };
    if (rim <= roofLatPx + tolPx) return { ...out, overPx: 0 };
  }
  return out;
}

// Where a cup sits against the teardrop (mm; lat + lateral, up + cranial), for the surgeon to judge medialisation and
// coverage (Astra: the rim's end is not the shell's most medial point): the inferomedial end of the face, the shell's
// most medial point (it bulges past the face's end by R(1 - cos i)) and its lowest point (the face's inferomedial end for
// any inclination from 0 to 90 degrees). C: cup centre, R: outer radius (px), k: mm per pixel.
export function cupVsTeardrop(C, R, inclDeg, T, u, v, k) {
  const i = G.rad(inclDeg), fd = G.add(G.mul(u, Math.cos(i)), G.mul(v, Math.sin(i)));
  const im = G.sub(C, G.mul(fd, R)), med = G.sub(C, G.mul(u, R)), at = p => ({ lat: G.dot(G.sub(p, T), u) * k, up: G.dot(G.sub(p, T), v) * k });
  return { inferomedial: at(im), medial: at(med), lowest: at(im) };
}

// The cup size for a wanted outside diameter (mm): the smallest whose outside (catalogue outside where given) is at
// least want - 1 mm, as before. Beyond the range nothing is chosen silently (amendment 1): fail "above" (the largest is
// shown, and the page says so in red) or "below" (the smallest is more than 2 mm larger than wanted; said in amber).
export function cupSizeFor(sizes, want, outer = z => z.catalogueOuter || z.outerDiameter) {
  if (!sizes.length || !(want > 0)) return null;
  const i = sizes.findIndex(z => outer(z) >= want - 1);
  if (i < 0) { const z = sizes[sizes.length - 1]; return { size: z.size, outer: outer(z), fail: 'above', want, limit: outer(z) }; }
  const z = sizes[i];
  return { size: z.size, outer: outer(z), fail: i === 0 && outer(z) - want > 2 ? 'below' : null, want, limit: i === 0 ? outer(z) : null };
}

// Standard or high offset, for each family among one stem's versions (amendment 3 limits high offset). A family may say
// so itself (offsetKind); otherwise the versions are grouped by neck-shaft angle (within 2.5 degrees: BE2 133 and 127),
// and in each group the version with the least offset (median offsetToAxis over its sizes) is standard and any with at
// least 3 mm more is high. Without numbers a family is standard. fams: library family objects.
export function offsetClasses(fams) {
  const med = a => { const s = a.filter(Number.isFinite).sort((x, y) => x - y); return s.length ? s[Math.floor(s.length / 2)] : NaN; };
  const info = fams.map(f => ({ id: f.id, kind: f.offsetKind, nsa: med((f.sizes || []).map(z => z.neckShaftAngle)), off: med((f.sizes || []).map(z => z.offsetToAxis)) }));
  const out = {};
  for (const a of info) {
    if (a.kind === 'high' || a.kind === 'standard') { out[a.id] = a.kind; continue; }
    const grp = info.filter(b => !(b.kind === 'high' || b.kind === 'standard') && Number.isFinite(a.nsa) && Number.isFinite(b.nsa) && Math.abs(a.nsa - b.nsa) <= 2.5 && Number.isFinite(b.off));
    const low = Math.min(...grp.map(b => b.off));
    out[a.id] = Number.isFinite(a.off) && grp.length > 1 && a.off - low >= 3 ? 'high' : 'standard';
  }
  return out;
}

// The choice among stem placements (amendment 3 as revised 13 Oct 2026 after Astra's page re-review: canal fill sets the
// size, the centre of rotation chooses the version and neck). Each candidate is one version, size and neck placed with
// its own geometry on the canal at the planned height: {fam, size, sizeIdx (in the sizes offered), neck, missH (head
// centre minus cup centre along the pelvic horizontal, mm, + lateral), missV, ev (the canal's inner cortex traced along
// enough of the stem), maxIn (mm past the inner cortex at the tightest level), fill, comb (combined offset minus the
// native's, mm, or null when not measured), cls ('standard' | 'high')}.
//   - eligible: a placement with canal evidence that stays within 0.5 mm of the inner cortex;
//   - each version's fill size: its largest size with an eligible placement (a cementless stem is sized by canal fill;
//     a smaller, looser size is never taken to reach the cup); its eligible necks at that size are its placements;
//   - high offset is left out when it would restore combined offset by more than limitMm over the native (said why);
//     when nothing else is left there is no suitable match: a left-out placement is never chosen;
//   - of the versions' fill-size placements, the smallest horizontal miss to the cup centre wins (equal misses: the
//     fuller fill, then the smaller combined offset change).
// handSize: the size was set by the surgeon: every neck of it counts, whatever the fit (the page reports the fit).
// handFam: the version was chosen by the surgeon: it is not left out for its combined offset (the page warns instead).
// Returns {best, bestStd, bestHigh, excluded, possible, none, why, overLimit, overHand, fillSize: {fam: size}}.
export function chooseStem(cands, { limitMm = 5, handSize = false, handFam = false } = {}) {
  const abs = c => Math.abs(c.missH);
  const ok = c => c.ev && c.maxIn !== null && c.maxIn <= 0.5;
  const fillSize = {};
  let pool;
  if (handSize) pool = cands.slice();
  else {
    for (const c of cands) if (ok(c) && (fillSize[c.fam] === undefined || c.sizeIdx > fillSize[c.fam].sizeIdx)) fillSize[c.fam] = { sizeIdx: c.sizeIdx, size: c.size };
    pool = cands.filter(c => ok(c) && fillSize[c.fam] && c.sizeIdx === fillSize[c.fam].sizeIdx)
      .map(c => ({ ...c, largerUnchecked: cands.some(d => d.fam === c.fam && d.sizeIdx === c.sizeIdx + 1 && !d.ev) }));
  }
  const fs = Object.fromEntries(Object.entries(fillSize).map(([k, v]) => [k, v.size]));
  if (!pool.length) {
    const why = !cands.length ? 'no placement could be made' : !cands.some(c => c.ev) ? 'the canal\'s inner cortex was not traced along enough of the stem' : 'every size reaches more than 0.5 mm past the inner cortex';
    return { best: null, bestStd: null, bestHigh: null, none: true, why, excluded: [], possible: null, overLimit: false, overHand: false, fillSize: fs };
  }
  const better = (a, b) => {   // is a better than b
    if (abs(a) !== abs(b)) return abs(a) < abs(b);
    if ((a.fill ?? -1) !== (b.fill ?? -1)) return (a.fill ?? -1) > (b.fill ?? -1);
    const ca = a.comb === null ? Infinity : Math.abs(a.comb), cb = b.comb === null ? Infinity : Math.abs(b.comb);
    return ca < cb;
  };
  const pick = list => list.reduce((b, c) => (!b || better(c, b) ? c : b), null);
  const over = c => c.cls === 'high' && c.comb !== null && c.comb > limitMm;
  const hand = handSize || handFam;
  const excluded = hand ? [] : pool.filter(over), allowed = hand ? pool : pool.filter(c => !over(c));
  if (!allowed.length) {
    return { best: null, bestStd: null, bestHigh: pick(excluded), none: true, overLimit: true, overHand: false, excluded, possible: null, fillSize: fs,
      why: `every version that fits the canal is a high offset one that would restore combined offset more than ${limitMm} mm over the native hip` };
  }
  const best = pick(allowed);
  return { best, bestStd: pick(allowed.filter(c => c.cls !== 'high')), bestHigh: pick(allowed.filter(c => c.cls === 'high')), excluded, possible: Math.min(...allowed.map(abs)),
    none: false, overLimit: false, overHand: hand && over(best), fillSize: fs };
}

// The scale's own uncertainty, as a fraction, for numbers that depend on it (amendment 2: combined offset is reported
// with it): a ball, one pixel on its diameter plus 1 % for where it sat (its depth against the joint's); a known length,
// two pixels on it plus 1 %; the machine's estimate, 15 %. Null without a scale.
export function scaleUncertainty(cal) {
  if (!cal || !(cal.mmPerPx > 0)) return null;
  if (cal.method === 'ball' && cal.ball && cal.ball.r > 0) return 1 / (2 * cal.ball.r) + 0.01 + (cal.warnings && cal.warnings.length ? 0.01 : 0);
  if (cal.method === 'line' && cal.line && cal.line.length === 2) { const L = G.dist(cal.line[0], cal.line[1]); return L > 0 ? 2 / L + 0.01 : 0.15; }
  return 0.15;
}
