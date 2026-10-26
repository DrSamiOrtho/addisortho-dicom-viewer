// The hip's automatic plan rules (13 Oct 2026; DESIGN-2026-10-13-autotemplate.md with Astra's binding amendments): the
// parts that are plain arithmetic, kept out of app.js so test.mjs can check them without a page. app.js lays the
// outlines on the film and measures; these decide. Every result is a starting position for the surgeon, not a decision:
// passing tests does not make the page ready for clinical use (it keeps its trial wording).
import * as G from './geom.js';

// Teardrop technique (amendment 1: a starting position the surgeon judges): the inferomedial end of the cup's face on
// the teardrop line, latPx lateral of the teardrop's lowest point, the face at inclDeg to the teardrop line.
// T: the teardrop; u: unit vector along the teardrop line towards this hip's lateral side; v: unit vector, cranial;
// R: the cup's outer radius; all in image pixels. Returns the cup centre.
// The cup centre is the head centre (the template's origin). Most cups are hemispheres with the face through it; a shell
// whose head centre sits off its sphere centre (Zimmer Biomet G7: the face and head centre 1.5 mm beyond the sphere centre,
// r21, 17 Oct 2026) passes off, that distance in px: its sphere centre is off up the dome from the head centre and its face
// ends are faceHalf(R, off) either side of it (Astra, r21 finding P2). off 0 gives the hemisphere, as before.
export const faceHalf = (R, off = 0) => Math.sqrt(Math.max(R * R - off * off, 0));
// the unit vector from the head centre into the dome (towards the sphere centre), for a face at inclDeg to u (v cranial)
export const domeDir = (u, v, inclDeg) => { const i = G.rad(inclDeg); return G.add(G.mul(u, -Math.sin(i)), G.mul(v, Math.cos(i))); };
export function teardropCentre(T, u, v, latPx, inclDeg, R, off = 0) {
  const i = G.rad(inclDeg), fd = G.add(G.mul(u, Math.cos(i)), G.mul(v, Math.sin(i)));
  return G.add(G.add(T, G.mul(u, latPx)), G.mul(fd, faceHalf(R, off)));
}

// Native centre height, medial edge at the teardrop (14 Oct 2026, the default cup rule; Sami on case 2: the teardrop rule
// put the cup centre 8.8 mm medial and 7.6 mm above the native head centre, the dome up in the ilium; he wants the cup in
// the native acetabulum, the centre of rotation restored, slightly medialised). N: the native head centre of this hip (for
// a deformed side the reference hip's, mirrored). The cup keeps N's height along the pelvic vertical v and moves medially
// along u from N until its most medial outer point (C - u R) is latPx lateral of the teardrop, but never more than
// maxMedPx medial of N and never lateral of N (a native centre already medial of that position is kept: protrusio-like).
// T, u, v, R as teardropCentre; all in image pixels. Returns {C, medPx (how far medial of N), capped, kept}.
// off, inclDeg: a shell whose sphere centre is off up the dome from its head centre (teardropCentre): its most medial point
// is the sphere's, off sin(i) medial of the head centre's line (the dome leans medially).
export function anatomicCentre(N, T, u, latPx, R, maxMedPx, off = 0, inclDeg = 0) {
  const want = G.dot(G.sub(N, T), u) - off * Math.sin(G.rad(inclDeg)) - (latPx + R);   // how far N is lateral of the position that puts the medial edge latPx from T
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
// offPx(z): a size's head-centre offset from its sphere centre in px (teardropCentre; 0 for a hemisphere).
export function anatomicRoofFit(sizes, startIdx, outerPx, N, T, u, latPx, maxMedPx, inclDeg, roofLatPx, tolPx = 0, offPx = () => 0) {
  const ci = Math.cos(G.rad(inclDeg));
  let out = null;
  for (let i = Math.min(startIdx, sizes.length - 1); i >= 0; i--) {
    const R = outerPx(sizes[i]), off = offPx(sizes[i]), a = anatomicCentre(N, T, u, latPx, R, maxMedPx, off, inclDeg), rim = G.dot(G.sub(a.C, T), u) + faceHalf(R, off) * ci;
    out = { idx: i, C: a.C, a, overPx: rim - roofLatPx, stepped: Math.min(startIdx, sizes.length - 1) - i };
    if (rim <= roofLatPx + tolPx) return { ...out, overPx: 0 };
  }
  return out;
}

// Where a cup sits against the teardrop (mm; lat + lateral, up + cranial), for the surgeon to judge medialisation and
// coverage (Astra: the rim's end is not the shell's most medial point): the inferomedial end of the face, the shell's
// most medial point (it bulges past the face's end by R(1 - cos i)) and its lowest point (the face's inferomedial end for
// any inclination from 0 to 90 degrees). C: cup centre, R: outer radius (px), k: mm per pixel.
// off: the head centre's offset from the sphere centre (px; teardropCentre): the face ends faceHalf(R, off) from C, the
// sphere's own medial and lowest points from its centre (the lowest is the sphere's only when it lies on the dome side).
export function cupVsTeardrop(C, R, inclDeg, T, u, v, k, off = 0) {
  const i = G.rad(inclDeg), fd = G.add(G.mul(u, Math.cos(i)), G.mul(v, Math.sin(i))), Sc = G.add(C, G.mul(domeDir(u, v, inclDeg), off));
  const im = G.sub(C, G.mul(fd, faceHalf(R, off))), med = G.sub(Sc, G.mul(u, R)), at = p => ({ lat: G.dot(G.sub(p, T), u) * k, up: G.dot(G.sub(p, T), v) * k });
  const low = off - R * Math.cos(i) >= 0 ? G.sub(Sc, G.mul(v, R)) : im;
  return { inferomedial: at(im), medial: at(med), lowest: at(low) };
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

// ======================= the knee case (release 1, 14 Oct 2026; DESIGN-2026-10-14-knee-case.md, contract section 12) =======================
// Plain geometry on image points, so test.mjs checks it without a page and app.js measures a film whether it is on screen or
// not. Angles need no scale (square pixels: the page makes DICOM pixels square when it decodes); lengths are in pixels
// and turned into mm only with a calibration the caller trusts. Sign words, not signs, go to the surgeon.

// The patient's lateral side of a limb on a film: cranial 'up' | 'down' | 'left' | 'right' (towards the head), mirrored
// (the patient's right on the image right), side 'R' | 'L'. As app.js latVec().
const CRAN = { up: G.P(0, -1), down: G.P(0, 1), left: G.P(-1, 0), right: G.P(1, 0) };
export function latDirOf(cranial, mirrored, side) {
  const c = CRAN[cranial] || CRAN.up, left = G.mul(G.P(-c.y, c.x), mirrored ? -1 : 1);
  return G.mul(left, side === 'R' ? -1 : 1);
}
// The angle from an anatomic axis to a mechanical axis (both pointing proximally), in degrees, positive when the mechanical
// axis lies medial of the anatomic one at its proximal end (the femur's usual case: the hip centre is medial of the shaft
// line). lat: the limb's lateral direction on the film.
export function signedAxisAngle(anatUp, mechUp, lat) {
  const a = G.angleDeg(anatUp, mechUp);
  if (!Number.isFinite(a)) return null;
  return G.dot(G.sub(G.unit(mechUp), G.unit(anatUp)), lat) > 0 ? -a : a;
}
// u turned by |deg| towards medial (deg > 0) or lateral (deg < 0): how an angle measured on the long leg is put onto the
// same anatomic axis on another film (registration to local anatomy, never a screen angle copied: Astra, P1 3)
export function turnToward(u, deg, lat) {
  const r = G.rad(Math.abs(deg)), a = G.rot(u, r), b = G.rot(u, -r);
  const medA = -G.dot(a, lat), medB = -G.dot(b, lat);
  return deg >= 0 ? (medA >= medB ? a : b) : (medA >= medB ? b : a);
}
// a line landmark's direction pointing towards `towards` (or, without it, along `fallback`)
const along = (ln, towards, fallback) => { let u = G.unit(G.sub(ln.a, ln.b)); const ref = towards ? G.sub(towards, G.mid(ln.a, ln.b)) : fallback; if (ref && G.dot(u, ref) < 0) u = G.mul(u, -1); return u; };
// Long-leg alignment from its landmarks (each optional): hip {c} (femoral head centre), knee {p} (the knee centre: the
// midpoint between the tibial spines at the top of the intercondylar notch, used for both mechanical axes), ankle {p}
// (the centre of the talus; Sami, 23 Oct), cm/cl (lowest points of the medial and lateral femoral condyles: the distal femoral
// joint line), pm/pl (the medial and lateral tibial plateaus: the proximal tibial joint line), fs/ts (the femoral and
// tibial anatomic axes, two mid-shaft points each); lat: the limb's lateral direction on the film; up: towards the head.
// Each result is null when its landmarks are missing:
//   hka: the hip-knee-ankle angle (180 = straight) and dev (180 - hka) with varus true when the knee lies lateral of the
//     hip-ankle line; madPx: that distance in pixels;
//   mldfa: the lateral angle between the femoral mechanical axis and the distal femoral joint line;
//   mpta: the medial angle between the tibial mechanical axis and the proximal tibial joint line;
//   jlca: the angle between the two joint lines and where they converge ('medial' | 'lateral' | null when parallel);
//   amaF / amaT: the femoral / tibial anatomic-to-mechanical angle (signedAxisAngle: + mechanical medial at the top);
//   swapCond / swapPlat: medial and lateral points clicked the other way round (the film's sides were used).
export function longLegAngles(g, lat, up = G.P(0, -1)) {
  const out = { hka: null, dev: null, varus: null, madPx: null, mldfa: null, mpta: null, jlca: null, converge: null, amaF: null, amaT: null, swapCond: false, swapPlat: false };
  let { cm, cl, pm, pl } = g;
  if (cm && cl && G.dot(G.sub(cl.p, cm.p), lat) < 0) { [cm, cl] = [cl, cm]; out.swapCond = true; }
  if (pm && pl && G.dot(G.sub(pl.p, pm.p), lat) < 0) { [pm, pl] = [pl, pm]; out.swapPlat = true; }
  const { hip, knee, ankle, fs, ts } = g;
  if (hip && knee && ankle) {
    out.hka = G.angleDeg(G.sub(hip.c, knee.p), G.sub(ankle.p, knee.p));
    const foot = G.project(knee.p, hip.c, G.sub(ankle.p, hip.c)), s = G.dot(G.sub(knee.p, foot), lat);
    out.varus = s > 0; out.dev = 180 - out.hka; out.madPx = G.dist(knee.p, foot);
  }
  if (hip && knee && cm && cl) out.mldfa = G.angleDeg(G.sub(hip.c, knee.p), G.sub(cl.p, cm.p));
  if (ankle && knee && pm && pl) out.mpta = G.angleDeg(G.sub(ankle.p, knee.p), G.sub(pm.p, pl.p));
  if (cm && cl && pm && pl) {
    const j = G.angleDeg(G.sub(cl.p, cm.p), G.sub(pl.p, pm.p)); out.jlca = Math.min(j, 180 - j);
    if (out.jlca >= 0.05) { const gm = G.lineDist(cm.p, pm.p, pl.p), gl = G.lineDist(cl.p, pm.p, pl.p); out.converge = gm < gl ? 'medial' : 'lateral'; }
  }
  if (hip && knee && fs) out.amaF = signedAxisAngle(along(fs, hip.c), G.sub(hip.c, knee.p), lat);
  if (ankle && knee && ts) out.amaT = signedAxisAngle(along(ts, knee.p, up), G.sub(knee.p, ankle.p), lat);
  out.cm = cm; out.cl = cl; out.pm = pm; out.pl = pl;
  return out;
}
// The long leg scaled from the AP (Sami, 14 Oct; Astra, P1 2): the same defined bony width (the femoral condyles at their
// widest, osteophytes excluded) marked on both films; the AP's own calibration turned into the long leg's. Approximate:
// rotation, flexion and projection differ between the films and its uncertainty is not validated, so the page says so.
// → mm per pixel on the long leg, or null when an input is missing or the result is not a plausible scale.
export function scaleFromAp(apWidthPx, apMmPerPx, longWidthPx) {
  if (!(apWidthPx > 5) || !(apMmPerPx > 0) || !(longWidthPx > 5)) return null;
  const k = apWidthPx * apMmPerPx / longWidthPx;
  return k >= 0.005 && k <= 5 ? k : null;
}
// A component's width against the bone's (mm), centred: + overhang on each side, - short of the edge on each side
export const overhangEach = (componentMm, boneMm) => (componentMm - boneMm) / 2;
// The skyline's patellar tilt (optional): the angle between the line across the patella (its medial and lateral edges)
// and the line across the front of the femoral condyles; and which way the patella opens ('lateral' when its lateral
// edge is nearer the condyles, i.e. tilted laterally). lat: lateral direction on the skyline film; ant: towards the front.
export function patellarTilt(pat, troch, lat, ant) {
  if (!pat || !troch) return null;
  let a = G.sub(pat.b, pat.a); if (G.dot(a, lat) < 0) a = G.mul(a, -1);
  let b = G.sub(troch.b, troch.a); if (G.dot(b, lat) < 0) b = G.mul(b, -1);
  const deg = G.angleDeg(a, b);
  if (!Number.isFinite(deg)) return null;
  const tiltLat = ant ? G.dot(G.sub(G.unit(a), G.unit(b)), ant) < 0 : null;   // the patella's lateral end lower (towards the condyles)
  return { deg, toward: deg < 0.05 ? null : tiltLat === null ? null : tiltLat ? 'lateral' : 'medial' };
}

// ---------- the Addis Ortho XN knee (PS): Chunli's compatibility chart and the technique manual (Sami, 15 Oct 2026) ----------
// Sami: "use the technique manual of addis ortho knee to see the matching table for femur and tibia. we do the tibia cut in
// slope; the tibia cutting block is what we use." The femur-tibia matching is Chunli's "Femur&Tibia components correlation
// chart" (a picture Sami sent on 15 Oct 2026; authoritative over the manual): rows the tibial tray groups 1, 2/3/4, 5/6,
// 7/8, columns the femur sizes 1-8; green the closest match, yellow OK, gray not compatible. The manual's crosswalk (p. 26)
// pairs femur N with tray N only (every one of those is green on the chart); KNEE-XN-MANUAL-NOTES.md has both and the
// differences. Other values from the manual (Addis Ortho_XN_Knee_System_PS_Updated_2026-10-15.pdf); what it does not state
// is null and the page says "not stated in the manual":
//  - insertGroups: the PS insert fits tray groups 1, 2/3/4, 5/6, 7/8 (p. 25, p. 27); heights 10, 12, 14, 16, 18 mm.
//  - the posterior slope (Sami, 16 Oct 2026: "we actually do not have those blocks for xn, we do the cut by adjusting the
//    extramedullary tibial cutting block"): set by tilting the extramedullary guide (p. 10-11: "loosen the knob nut ... Pull
//    the rod by back and forth, can adjust posterior angle"). The manual describes 3° and 5° blocks Addis Ortho does not
//    have, and states no target slope. The page's 5° is Addis Ortho's standard (Sami, 16 Oct 2026: "slope should be 5
//    valgus 5 rotation 5"); 0-10° is an input bound, not a manufacturer-approved or safe range. The tray's "5° of caster
//    angle" (p. 4; the text extract's "2.5°" is item 2) is not a surface slope: nothing is added to or taken from the cut.
//  - distal femoral resection at the guide's "0" position: 8 mm (p. 15; confirmed by Sami, 16 Oct 2026); tibial resection
//    gauge 10 mm on the less damaged plateau when one plateau has severe cartilage loss, or 2 mm below the lowest point of
//    the medial plateau when the chondrosis is not severe (p. 12); femoral valgus usually 3-8°, generally 5-7° (p. 8, p. 14);
//    femoral rotation 0°/3°/5°, usually 3° (p. 17).
const G_ = 'green', Y_ = 'yellow', X_ = 'gray';
export const XN_KNEE = {
  femur: 'chunli-tka-femur', tibia: 'chunli-tka-tibia', range: 'ao-knee',
  source: 'Chunli Femur&Tibia components correlation chart (Sami, 15 Oct 2026); Addis Ortho XN Knee System PS technique manual (updated 15 Oct 2026)',
  // chart[tibia group][femur size 1..8]
  chart: { '1': [G_, Y_, X_, X_, X_, X_, X_, X_], '2/3/4': [Y_, G_, G_, G_, Y_, Y_, X_, X_], '5/6': [X_, Y_, Y_, Y_, G_, G_, Y_, Y_], '7/8': [X_, X_, X_, X_, Y_, Y_, G_, G_] },
  manualPairs: { 1: ['1'], 2: ['2'], 3: ['3'], 4: ['4'], 5: ['5'], 6: ['6'], 7: ['7'], 8: ['8'] }, pairsPage: 26,
  insertGroups: [['1'], ['2', '3', '4'], ['5', '6'], ['7', '8']], insertHeights: [10, 12, 14, 16, 18], insertPage: 25,
  slopeStd: 5, slopeMin: 0, slopeMax: 10, slopePage: 11,   // no blocks: the extramedullary guide tilted; 5° Addis Ortho's standard (Sami, 16 Oct)
  femDistalStd: 8, femDistalPage: 15, tibialStd: { less: 10, medial: 2, worn: 2 }, tibialPage: 12, vca: [5, 7], vcaStd: 5, vcaPage: 8,   // valgus 5°: Addis Ortho's standard (Sami, 16 Oct); rotation below (rotStd)
};
const TRAYS = ['1', '2', '3', '4', '5', '6', '7', '8'];
const trayGroup = t => ({ 1: '1', 2: '2/3/4', 3: '2/3/4', 4: '2/3/4', 5: '5/6', 6: '5/6', 7: '7/8', 8: '7/8' })[String(t)] || null;
// the chart's grade of a femur and tray of the XN knee: 'green' | 'yellow' | 'gray', or null (another maker's, or a size
// the chart does not have)
export function kneeGrade(femFam, femSize, tibFam, tibSize) {
  if (femFam !== XN_KNEE.femur || tibFam !== XN_KNEE.tibia) return null;
  const g = trayGroup(tibSize), i = parseInt(femSize, 10) - 1;
  return g && i >= 0 && i < 8 ? XN_KNEE.chart[g][i] : null;
}
// the trays of each grade for a femur: {green, yellow, gray} (tray sizes 1-8; whether the library has a template is the
// page's question), or null for another maker's femur
export function traysFor(femFam, femSize) {
  if (femFam !== XN_KNEE.femur) return null;
  const out = { green: [], yellow: [], gray: [] };
  for (const t of TRAYS) { const g = kneeGrade(femFam, femSize, XN_KNEE.tibia, t); if (g) out[g].push(t); }
  return out;
}
// the closest-match (green) trays for a femur, or null (another maker's femur)
export function tibiaFor(femFam, femSize) { const t = traysFor(femFam, femSize); return t ? t.green : null; }
// a femur and tray against the chart → { ok, grade, green, yellow } (ok: not gray) | null (nothing to check)
export function kneePairCheck(femFam, femSize, tibFam, tibSize) {
  const grade = kneeGrade(femFam, femSize, tibFam, tibSize); if (!grade) return null;
  const t = traysFor(femFam, femSize);
  return { ok: grade !== X_, grade, green: t.green, yellow: t.yellow, permitted: t.green, manual: XN_KNEE.manualPairs[String(femSize)] || [] };
}
// the PS insert group a tray size takes (p. 25): its tray sizes, or null
export const insertGroupOf = tibSize => XN_KNEE.insertGroups.find(g => g.includes(String(tibSize))) || null;
// The assumed plan values of a knee plan (Sami, 16 Oct 2026: "built it as default from the template and the details on
// technique manual"; DESIGN-2026-10-16-knee-defaults.md with Astra's amendments): each with its own source, never one
// combined label. tibRef: 'less' (10 mm below the reference plateau the page chooses: the higher one), 'medial' (the
// manual's 2 mm below the lowest point of the medial plateau), 'worn' (read only: plans saved before 16 Oct, 2 mm below the
// lower plateau). Another maker's femur: 9 mm distal (the usual standard, not the XN manual).
export const TIB_REF_TEXT = {
  less: 'reference plateau chosen by the page (lateral if the medial is lower, i.e. the higher plateau); wear severity not assessed',
  medial: `XN manual p. 12: 2 mm below the lowest point of the medial plateau (non-severe medial chondrosis)`,
  worn: 'below the lower plateau (reference saved before 16 Oct 2026)',
};
// Another maker's knee (Astra, r17 review P2 3): the same numbers, never the XN manual's or the XN instruments' words.
export const OTHER_MAKER = "Addis Ortho standard (Sami); not from this maker's technique; check against its manual";
export const isXnFemur = femFam => !femFam || femFam === XN_KNEE.femur;
export function kneeStd(femFam, tibRef = 'less') {
  const xn = isXnFemur(femFam), ref = TIB_REF_TEXT[tibRef] ? tibRef : 'less';
  if (!xn) return {
    femDistal: 9, femDistalFrom: 'the usual standard for another maker\'s femur; not from this maker\'s technique; check against its manual',
    tibial: XN_KNEE.tibialStd[ref], tibialFrom: ref === 'less' ? `reference plateau chosen by the page (lateral if the medial is lower, i.e. the higher plateau); wear severity not assessed; ${OTHER_MAKER}`
      : ref === 'medial' ? `2 mm below the lowest point of the medial plateau; ${OTHER_MAKER}` : TIB_REF_TEXT.worn,
    slope: XN_KNEE.slopeStd, slopeFrom: OTHER_MAKER, vca: XN_KNEE.vcaStd, vcaFrom: OTHER_MAKER, rot: XN_KNEE.rotStd, rotFrom: OTHER_MAKER,
  };
  return {
    femDistal: XN_KNEE.femDistalStd, femDistalFrom: `XN manual (guide '0', p. ${XN_KNEE.femDistalPage}), confirmed by Sami`,
    tibial: XN_KNEE.tibialStd[ref], tibialFrom: ref === 'less' ? `${TIB_REF_TEXT.less} (XN manual p. ${XN_KNEE.tibialPage}: the 10 mm stylus)` : TIB_REF_TEXT[ref],
    slope: XN_KNEE.slopeStd, slopeFrom: 'Addis Ortho standard (Sami); the manual states no target',
    vca: XN_KNEE.vcaStd, vcaFrom: `Addis Ortho standard (Sami), within the manual's ${XN_KNEE.vca.join('–')}°`,
    rot: XN_KNEE.rotStd, rotFrom: 'Addis Ortho standard (Sami); the manual says usually 3°, and 5° when the patella tracks laterally (p. 8, 17)',
  };
}
// One plan value with its origin and review status, kept apart (Astra, 16 Oct, P1 1; contract section 12c): raw the value
// stored (null: the assumed value def is used), p the stored provenance {o: 'assumed' | 'surgeon' | 'unknown', rv, v}.
// A stored value without provenance is a legacy value of unknown origin (never replaced); a review of an assumed or legacy
// value counts only for the number it was given for (p.v). → {v, o, rv}
export function planVal(raw, p, def) {
  const ok = x => typeof x === 'number' && Number.isFinite(x);
  if (!ok(raw)) return { v: def, o: 'assumed', rv: !!(p && p.o === 'assumed' && p.rv === true && p.v === def) };
  if (p && p.o === 'surgeon') return { v: raw, o: 'surgeon', rv: true };
  return { v: raw, o: 'unknown', rv: !!(p && p.rv === true && p.v === raw) };
}
// the words for a plan value's origin and review (the panel, the summary, the saved picture and a reopened case say the same)
export function planLabel(pv, src) {
  if (pv.o === 'surgeon') return 'Surgeon-entered target';
  if (pv.o === 'unknown') return `Entered before 16 Oct 2026 · origin not recorded · ${pv.rv ? 'reviewed by the surgeon' : 'not reviewed'}`;
  return `Assumed plan value · not patient-measured · ${pv.rv ? 'reviewed by the surgeon (unchanged), not surgeon-entered' : 'not surgeon-confirmed'} · ${src}`;
}

// ---------- the skyline: patellar tracking and femoral rotation (Sami, 15 Oct 2026) ----------
// Sami: "skyline to assess patellar tracking and consideration of femoral external rotation". troch: the trochlear groove
// line (the highest points of the medial then the lateral anterior trochlear facets); sulcus: the deepest point of the
// trochlear groove; apex: the patella's articular ridge; post: the posterior condylar line where it shows. Angles in degrees;
// null when their landmarks are missing.
//  - sulcusAngle: the angle at the sulcus between the two facet highs (Merchant; larger = shallower trochlea).
//  - congruence: the angle between the sulcus angle's bisector and the line from the sulcus to the patellar apex, + when the
//    apex lies lateral of the bisector (Merchant).
//  - rotation: the angle between the trochlear groove line and the posterior condylar line (an approximation of the
//    femur's rotation: a skyline does not show the transepicondylar axis).
export function skylineAngles(g, lat) {
  const out = { sulcusAngle: null, congruence: null, rotation: null };
  const t = g.troch, s = g.sulcus, ap = g.apex, pc = g.post;
  if (t && s) {
    const u = G.sub(t.a, s.p), v = G.sub(t.b, s.p);
    const a = G.angleDeg(u, v); if (Number.isFinite(a)) out.sulcusAngle = a;
    if (ap && Number.isFinite(a)) {
      const bis = G.add(G.unit(u), G.unit(v)), w = G.sub(ap.p, s.p), c = G.angleDeg(bis, w);
      if (Number.isFinite(c)) out.congruence = G.dot(G.sub(G.unit(w), G.mul(G.unit(bis), G.dot(G.unit(w), G.unit(bis)))), lat) > 0 ? c : -c;
    }
  }
  if (t && pc) { const r = G.angleDeg(G.sub(t.b, t.a), G.sub(pc.b, pc.a)); if (Number.isFinite(r)) out.rotation = Math.min(r, 180 - r); }
  return out;
}
// the XN manual on femoral rotation (p. 17): the external rotation guide 0°, 3° or 5° (L/R), "usually select 3°", referenced
// with the sizing device's foot on the posterior condyles; p. 8: "consider 5 degree femoral external rotation if patella is
// tracking laterally, 3 degree if tracking well"
XN_KNEE.rotOptions = [0, 3, 5]; XN_KNEE.rotStd = 5;   // the manual's usual 3°; Addis Ortho's standard 5° (Sami, 16 Oct)
XN_KNEE.rotManual = 3; XN_KNEE.rotPage = 17; XN_KNEE.rotTrackPage = 8;

// ---------- the patella: a suggestion for the knee plan card (Sami, 23 Oct 2026; DESIGN-2026-10-23-knee-plan-card.md) ----------
// Fable's tightened rule, chosen by Sami: resurfacing is suggested for severe patellofemoral arthritis, or moderate with
// lateral facet wear or a tilted or subluxed patella, when the patella's bone is at least 20 mm thick on the lateral (12 mm
// left after an 8 mm button); patelloplasty otherwise. pf: the surgeon's own skyline assessment {grade, facet, tracking};
// boneMm: the patella's thickness on the calibrated lateral (bone only), or null; xn: the XN knee (its manual cited). → {s: 'resurface' | 'patelloplasty' |
// null, why: [reasons]}. A suggestion only: it never decides, and it is never written into the surgeon's decision.
export const PATELLA_MIN_BONE = 20;
export function patellaSuggest(pf, boneMm, xn = true) {
  const p = pf || {}, why = [];
  if (!p.grade) return { s: null, why: ['the patellofemoral arthritis grade is not recorded (skyline assessment)'] };
  const mal = p.tracking === 'tilted' || p.tracking === 'subluxed';
  why.push(`${p.grade === 'none' ? 'no' : p.grade} patellofemoral arthritis`);
  if (p.facet === 'yes') why.push('lateral facet wear');
  if (mal) why.push(p.tracking === 'tilted' ? 'patella tilted' : 'patella subluxed laterally');
  const findings = p.grade === 'severe' || (p.grade === 'moderate' && (p.facet === 'yes' || mal));
  const bone = Number.isFinite(boneMm) && boneMm > 0 ? boneMm : null;
  if (!findings) {
    if (mal) why.push(`check the tracking and the femoral rotation${xn ? ` (XN manual p. ${XN_KNEE.rotTrackPage}: 5° when the patella tracks laterally)` : ''}`);
    return { s: 'patelloplasty', why };
  }
  if (bone === null) return { s: null, why: [...why, 'the findings favour resurfacing, but the patella\'s bone thickness is not measured (mark it on the calibrated lateral)'] };
  if (bone < PATELLA_MIN_BONE) return { s: 'patelloplasty', why: [...why, `bone ${bone.toFixed(1)} mm thick, under ${PATELLA_MIN_BONE} mm: resurfacing would leave little bone`] };
  return { s: 'resurface', why: [...why, `bone ${bone.toFixed(1)} mm thick (at least ${PATELLA_MIN_BONE} mm)`] };
}
