// X-ray templating page (10-11 Oct 2026). Synthetic sandbox; not for clinical use until validated.
// Loads one X-ray (archive or local file), calibrates it with a marker of known size (found automatically, or by a
// click), reads the R/L marker, takes the surgeon's landmarks with a guide for each, lays true-size implant outlines
// (library.json, Addis Ortho range from the manufacturer's 1:1 sheets and models) on it, places them automatically by
// stated rules for the surgeon to fine-tune, and works out the numbers a templating sheet gives: hip (cup + stem on
// either or both sides: inclination, centre of rotation, leg length, offset, neck cut, canal fill; the operated side
// can mirror the other) and knee (TKA: alignment angles, cut angles, resections, sizes). Everything is computed in
// image pixels and converted with the calibration.
import { decodeImage, decodeAny, decodeBitmap, parseDicom } from './dicom.js';
import { decodePNG } from './imageio.js';
import * as G from './geom.js';
import { findBalls, findMarkers, traceCanal, canalFit, fitIsEvidence, stemRegion, findOpaque, exclusionRaster, rasterHas, contactInZone, existFits, isExclusion, isUnresolved } from './auto.js';
import { GUIDE } from './guide.js';
import { secondaryCapture } from './dicomwrite.js';
import { reduceFilm, REDUCE_OVER } from './reduce.js';
import { cleanCopy, addStroke } from './clean.js';
import { focusKind, shortcut, keepPanel, restorePanel, makeSheet } from './uikit.js';
import * as PJ from './projects.js';
import { teardropCentre, anatomicCentre, anatomicRoofFit, cupVsTeardrop, faceHalf, cupSizeFor, offsetClasses, chooseStem, scaleUncertainty, latDirOf, longLegAngles, turnToward, scaleFromAp, overhangEach, patellarTilt, skylineAngles, patellaSuggest, XN_KNEE, traysFor, kneeGrade, kneePairCheck, insertGroupOf, kneeStd, planVal, planLabel, isXnFemur } from './autoplan.js';

const $ = id => document.getElementById(id);
// Standalone build (build_standalone.sh, for the login-gated t.addisortho.com): local files only, no archive, no Send to
// PACS; names and record numbers as in the hospital unless "sharing outside the hospital" is ticked.
const STANDALONE = document.documentElement.dataset.mode === 'standalone';
// Viewer build (build_viewer.sh, 14 Oct 2026): the Addis Ortho DICOM Viewer serves this page on 127.0.0.1 and answers for
// the archive (dicomweb, wado, store) for the one X-ray it hands over: offline, Addis Ortho primary hip and knee ranges
// only. Only words differ from the hospital copy: the plan goes into the viewer, which can send it on to PACS.
const VIEWER = document.documentElement.dataset.mode === 'viewer';
const SEND = VIEWER ? 'Save in the viewer' : 'Send to PACS', DEST = VIEWER ? 'the viewer' : 'PACS', SRCN = VIEWER ? 'the viewer' : 'archive';
const RS = '../dicomweb', WADO = '../wado';            // relative to /openmrs/aoc/pacs/templating/ (pacs-web, read-only)
const EXPLICIT_LE = '1.2.840.10008.1.2.1';
const val = (o, tag) => ((o[tag] || {}).Value || [''])[0];
const SIDE = { R: 'right', L: 'left' }, other = s => (s === 'R' ? 'L' : 'R');
const cap = s => s.charAt(0).toUpperCase() + s.slice(1);

// ---------- workflows: landmarks and templates per kind of film ----------
// Hip landmarks are kept by anatomical side (tdR, tdL, ...), so changing the operated side changes only which side is
// reported as operated. Order: what the operated side needs, then the other side (optional, for comparison and mirroring).
// r32 (23 Oct 2026): the tip of the greater trochanter, only where a proximal femoral replacement is planned on that side
// (its tumour resection is measured from it); gtAll: every side (the list of all landmarks)
const gtLm = (sides, gtAll) => sides.filter(sd => gtAll || pfrIn(sd)).map(sd => ({ key: 'gt' + sd, g: 'gt', short: `GT ${sd}`, type: 'point', label: `Tip of the greater trochanter, ${SIDE[sd]} (tumour resection)`, hint: 'its highest point', optional: true }));
function hipLm(o, scope = (typeof S !== 'undefined' && S.scope) || 'both', ref = (typeof S !== 'undefined' && S.ref) || 'own', gtAll = false) {
  const n = other(o), O = SIDE[o], N = SIDE[n];
  // surgery-side hip too deformed to plan from (Sami, 11 Oct, case 2): the other hip's head and canal are the reference
  // and are needed; this hip's own head is optional and not used
  if (scope === 'both' && ref === 'other') return [
    { key: 'td' + o, g: 'td', short: `TD ${o}`, type: 'point', label: `Teardrop, ${O} (surgery side)`, hint: 'its lowest point' },
    { key: 'td' + n, g: 'td', short: `TD ${n}`, type: 'point', label: `Teardrop, ${N}`, hint: 'its lowest point' },
    { key: 'lt' + o, g: 'lt', short: `LT ${o}`, type: 'point', label: `Lesser trochanter, ${O} (surgery side)`, hint: 'its tip' },
    { key: 'lt' + n, g: 'lt', short: `LT ${n}`, type: 'point', label: `Lesser trochanter, ${N}`, hint: 'its tip' },
    { key: 'head' + n, g: 'head', short: `head ${n}`, type: 'circle', label: `Femoral head, ${N} (the reference hip)`, hint: '3 points spread round its rim' },
    { key: 'canal' + n, g: 'canal', short: `canal ${n}`, type: 'line', label: `Femoral canal, ${N} (the reference hip)`, hint: 'the middle of the canal just below the lesser trochanter, then 10-15 cm lower' },
    { key: 'head' + o, g: 'head', short: `head ${o}`, type: 'circle', label: `Femoral head, ${O} (deformed; not used)`, hint: '3 points spread round its rim', optional: true },
    { key: 'canal' + o, g: 'canal', short: `canal ${o}`, type: 'line', label: `Femoral canal, ${O} (not templated; optional)`, hint: 'the middle of the canal, high then low', optional: true },
    { key: 'ac' + n, g: 'ac', short: `roof ${n}`, type: 'point', label: `Acetabular roof edge, ${N}`, hint: 'the outer end of the roof', optional: true },
    ...gtLm([o, n], gtAll),
  ];
  const all = [
    { key: 'td' + o, g: 'td', short: `TD ${o}`, type: 'point', label: `Teardrop, ${O} (surgery side)`, hint: 'its lowest point' },
    { key: 'td' + n, g: 'td', short: `TD ${n}`, type: 'point', label: `Teardrop, ${N}`, hint: 'its lowest point' },
    { key: 'lt' + o, g: 'lt', short: `LT ${o}`, type: 'point', label: `Lesser trochanter, ${O} (surgery side)`, hint: 'its tip' },
    { key: 'lt' + n, g: 'lt', short: `LT ${n}`, type: 'point', label: `Lesser trochanter, ${N}`, hint: 'its tip' },
    { key: 'head' + o, g: 'head', short: `head ${o}`, type: 'circle', label: `Femoral head, ${O} (surgery side)`, hint: '3 points spread round its rim' },
    { key: 'canal' + o, g: 'canal', short: `canal ${o}`, type: 'line', label: `Femoral canal, ${O} (surgery side)`, hint: 'the middle of the canal just below the lesser trochanter, then 10-15 cm lower' },
    { key: 'ac' + o, g: 'ac', short: `roof ${o}`, type: 'point', label: `Acetabular roof edge, ${O} (surgery side)`, hint: 'the outer end of the roof', optional: true },
    { key: 'head' + n, g: 'head', short: `head ${n}`, type: 'circle', label: `Femoral head, ${N}`, hint: '3 points spread round its rim', optional: true },
    { key: 'canal' + n, g: 'canal', short: `canal ${n}`, type: 'line', label: `Femoral canal, ${N}`, hint: 'the middle of the canal, high then low', optional: true },
    { key: 'ac' + n, g: 'ac', short: `roof ${n}`, type: 'point', label: `Acetabular roof edge, ${N}`, hint: 'the outer end of the roof', optional: true },
    ...gtLm([o, n], gtAll),
  ];
  // a film of one hip: only what is on it (the other side's teardrop and trochanter are not there; Astra, 11 Oct)
  return scope === 'one' ? all.filter(d => d.key.endsWith(o)) : all;
}
// The knee case (release 1, 14 Oct 2026; DESIGN-2026-10-14-knee-case.md with its binding amendments, contract section 12):
// one patient's knee from up to four films. Its AP and lateral films use the knee AP and lateral plans with the case's own
// landmark lists (no hip or ankle on the knee AP: the long leg has them; the condylar and plateau widths for coverage and
// for scaling the long leg); the long leg and the skyline have plans of their own, without implants. Each landmark is
// defined exactly, the same definition on every film it is marked on (Astra, P1 2 and 3).
const KC_AP_LM = [
  { key: 'femShaft', short: 'femoral shaft', type: 'line', label: 'Femoral anatomic axis', hint: 'the middle of the femoral shaft about 5 cm and about 15 cm above the joint (the same shaft section as on the long leg)' },
  { key: 'knee', short: 'knee', type: 'point', label: 'Knee centre', hint: 'the midpoint between the tibial spines, at the top of the intercondylar notch' },
  { key: 'condMed', short: 'MC', type: 'point', label: 'Medial condyle, lowest point' },
  { key: 'condLat', short: 'LC', type: 'point', label: 'Lateral condyle, lowest point' },
  { key: 'platMed', short: 'MP', type: 'point', label: 'Medial tibial plateau', hint: 'joint surface (subchondral line), middle of the plateau' },
  { key: 'platLat', short: 'LP', type: 'point', label: 'Lateral tibial plateau', hint: 'joint surface (subchondral line), middle of the plateau' },
  { key: 'tibShaft', short: 'tibial shaft', type: 'line', label: 'Tibial anatomic axis', hint: 'the middle of the tibial shaft about 5 cm and about 15 cm below the joint' },
  { key: 'condW', short: 'condylar width', type: 'line', label: 'Femoral condylar width', hint: 'the most medial and the most lateral bony points of the distal femoral condyles at their widest, osteophytes excluded (the same two points on the long leg)' },
  { key: 'platW', short: 'plateau width', type: 'line', label: 'Tibial plateau width', hint: 'the medial and the lateral cortical edges of the tibial plateau, osteophytes excluded' },
];
const KNEE_LAT_LM = [
  { key: 'antCortex', short: 'ant. cortex', type: 'line', label: 'Anterior femoral cortex', hint: 'two points on the front of the distal shaft' },
  { key: 'postCond', short: 'post. condyles', type: 'point', label: 'Posterior condyles, back edge', hint: 'most posterior point' },
  { key: 'platAnt', short: 'plateau front', type: 'point', label: 'Tibial plateau, front edge' },
  { key: 'platPost', short: 'plateau back', type: 'point', label: 'Tibial plateau, back edge' },
  { key: 'tibAxis', short: 'tibial axis', type: 'line', label: 'Tibial shaft axis', hint: 'centre of the shaft, high then low' },
];
const WIDTH_LM = ['condW', 'platW'];
WIDTH_LM.push('diaCanN1', 'diaCanN2', 'diaCanN3', 'diaCanF1', 'diaCanF2', 'diaCanF3');   // r39: the diaphyseal canal widths too (the line above as test.mjs pins it)   // landmarks that are measurements: their length shown on the film
const KC_LAT_EXTRA = [{ key: 'patThick', short: 'patella', type: 'line', label: 'Patella thickness', hint: 'its anterior and posterior surfaces where it is thickest', optional: true }];
const KC_LONG_LM = [
  { key: 'hip', short: 'hip centre', type: 'circle', label: 'Femoral head centre', hint: '3 points spread round the rim of the head' },
  { key: 'knee', short: 'knee', type: 'point', label: 'Knee centre', hint: 'the midpoint between the tibial spines, at the top of the intercondylar notch (used for both mechanical axes)' },
  { key: 'condMed', short: 'MC', type: 'point', label: 'Medial condyle, lowest point' },
  { key: 'condLat', short: 'LC', type: 'point', label: 'Lateral condyle, lowest point' },
  { key: 'platMed', short: 'MP', type: 'point', label: 'Medial tibial plateau', hint: 'joint surface (subchondral line), middle of the plateau' },
  { key: 'platLat', short: 'LP', type: 'point', label: 'Lateral tibial plateau', hint: 'joint surface (subchondral line), middle of the plateau' },
  { key: 'ankle', short: 'ankle', type: 'point', label: 'Ankle centre', hint: 'the centre of the talus (midway between its medial and lateral borders, halfway down its body)' },
  { key: 'femShaft', short: 'femoral shaft', type: 'line', label: 'Femoral anatomic axis', hint: 'the middle of the femoral shaft about 5 cm and about 15 cm above the joint (as on the knee AP)' },
  { key: 'tibShaft', short: 'tibial shaft', type: 'line', label: 'Tibial anatomic axis', hint: 'the middle of the tibial shaft about 5 cm and about 15 cm below the joint (as on the knee AP)' },
  { key: 'condW', short: 'condylar width', type: 'line', label: 'Femoral condylar width (to scale from the AP)', hint: 'the same two points as on the AP: the most medial and most lateral bony points of the condyles at their widest, osteophytes excluded', optional: true },
];
// (15 Oct, Sami: "skyline to assess patellar tracking and consideration of femoral external rotation"): the trochlear groove
// line, its deepest point and the patella's apex for the sulcus and congruence angles, the posterior condylar line where it
// shows for the rotation (all optional: the skyline is a review film)
const KC_SKY_LM = [
  { key: 'patW', short: 'patella', type: 'line', label: 'Patella, medial and lateral edges', hint: 'the medial edge, then the lateral edge of the patella', optional: true },
  { key: 'trochAnt', short: 'trochlea', type: 'line', label: 'Trochlear groove line (anterior trochlear facets)', hint: 'the highest point of the medial facet, then of the lateral facet', optional: true },
  { key: 'sulcus', short: 'sulcus', type: 'point', label: 'Trochlear sulcus, deepest point', optional: true },
  { key: 'patApex', short: 'apex', type: 'point', label: 'Patella, apex of its articular ridge', optional: true },
  { key: 'postCondSky', short: 'post. condyles', type: 'line', label: 'Posterior condylar line (where visible)', hint: 'the back of the medial condyle, then of the lateral condyle', optional: true },
];
const WF = {
  hip: { label: 'Hip AP: total hip', short: 'Hip', view: 'AP', slots: ['cupR', 'stemR', 'cupL', 'stemL'], get lm() { return hipLm(S.side); } },
  kneeAP: {
    label: 'Knee AP / long leg: total knee', short: 'Knee AP', view: 'AP', slots: ['femur', 'tibia'],
    get lm() { return S.kc ? (S.kc.active === 'long' && S.ll ? llLm(KC_LONG_LM) : KC_AP_LM) : S.ll ? llLm(KNEE_AP_LM) : KNEE_AP_LM; },   // r44: the long-leg plan's knee zone
  },
  kneeLat: {
    label: 'Knee lateral: total knee', short: 'Knee lat', view: 'LAT', slots: ['femur', 'tibia'],
    get lm() { return S.kc ? [...KNEE_LAT_LM, ...KC_LAT_EXTRA] : KNEE_LAT_LM; },
  },
  // r33 (24 Oct 2026): the shoulder AP, for a proximal humeral replacement (tumour)
  shoulder: { label: 'Shoulder AP: proximal humeral replacement', short: 'Shoulder', view: 'AP', slots: ['humerus'], get lm() { return SHOULDER_LM; } },
  // the knee case's own films: no implants (so never offered in the Plan list), angles measured without a scale
  // r39 (29 Oct 2026): the Chunli diaphyseal (segmental) defect, measurement only: no implant placed (view: the film's, chosen on the card)
  diaph: { label: 'Diaphyseal (segmental) replacement: femur or tibia', short: 'Diaphysis', get view() { return diaU().view === 'LAT' ? 'LAT' : 'AP'; }, slots: [], get lm() { return DIA_LM; } },
  kneeLong: { label: 'Knee case: long leg', view: 'AP', slots: [], lm: KC_LONG_LM },
  kneeSky: { label: 'Knee case: skyline', view: 'SKY', slots: [], lm: KC_SKY_LM },
};
const KNEE_AP_LM = [   // the knee AP film of one image (also a long-leg film): as before the knee case
      { key: 'hip', short: 'hip centre', type: 'circle', label: 'Femoral head (long-leg film)', hint: 'click 3 points on the rim', optional: true },
      { key: 'femShaft', short: 'femoral shaft', type: 'line', label: 'Femoral shaft axis', hint: 'centre of the shaft, high then low' },
      { key: 'knee', short: 'knee', type: 'point', label: 'Knee centre', hint: 'top of the notch, between the tibial spines' },
      { key: 'condMed', short: 'MC', type: 'point', label: 'Medial condyle, lowest point' },
      { key: 'condLat', short: 'LC', type: 'point', label: 'Lateral condyle, lowest point' },
      { key: 'platMed', short: 'MP', type: 'point', label: 'Medial tibial plateau', hint: 'joint surface, middle of the plateau' },
      { key: 'platLat', short: 'LP', type: 'point', label: 'Lateral tibial plateau', hint: 'joint surface, middle of the plateau' },
      { key: 'ankle', short: 'ankle', type: 'point', label: 'Ankle centre (long-leg film)', hint: 'the centre of the talus (midway between its medial and lateral borders, halfway down its body)', optional: true },
      { key: 'tibShaft', short: 'tibial shaft', type: 'line', label: 'Tibial shaft axis (short film)', hint: 'centre of the shaft, high then low', optional: true },
];
// r33: the shoulder AP's landmarks: the native head (its centre is where the implant's head centre goes), the tip of the
// greater tuberosity (the resection is measured from it), the humeral shaft axis (the implant's axis)
const SHOULDER_LM = [
  { key: 'humHead', short: 'humeral head', type: 'circle', label: 'Humeral head', hint: '3 points spread round its rim' },
  { key: 'gtub', short: 'GT', type: 'point', label: 'Greater tuberosity, its highest point', hint: 'its tip: the tumour resection is measured from it' },
  { key: 'humShaft', short: 'humeral shaft', type: 'line', label: 'Humeral shaft axis', hint: 'the middle of the shaft below the tumour, high then low' },
];
const NPTS = { point: 1, line: 2, circle: 3 };
const SLOT = {
  cupR: { kind: 'cup', side: 'R', label: 'Cup' }, stemR: { kind: 'stem', side: 'R', label: 'Stem' },
  cupL: { kind: 'cup', side: 'L', label: 'Cup' }, stemL: { kind: 'stem', side: 'L', label: 'Stem' },
  femur: { kind: 'tka-femur', label: 'Femoral component' }, tibia: { kind: 'tka-tibia', label: 'Tibial tray' },
  humerus: { kind: 'humeral-body', label: 'Proximal humerus' },   // r33
};
const slotSide = slot => SLOT[slot].side || S.side;
const slotName = slot => SLOT[slot].side ? `${SLOT[slot].label}, ${SIDE[SLOT[slot].side]}${SLOT[slot].side === S.side ? ' (surgery side)' : ''}` : SLOT[slot].label;
// operated side: brand lime; the other side: sky blue, so the two plans are never confused
const COL = { tpl: '#A8FF30', tpl2: '#7fd4ff', fill: 'rgba(168,255,48,0.13)', fill2: 'rgba(127,212,255,0.10)', lm: '#ffd400', line: '#38d6ff', cut: '#ff8a1f', cal: '#ff4fd8', ruler: '#ffffff', ghost: '#ffffff', green: '#034537' };

// Surgeon's preferences, kept in this browser (not in the plan; the plan file records the values used).
const SET_KEY = 'aoc-tpl-settings';
// stemRule (13 Oct 2026, DESIGN-2026-10-13-autotemplate.md B): 'cup' puts the stem's head centre on the cup centre (the
// default); 'offset' is the older rule, the neck that restores the native femoral offset (offsetTarget)
const DEFAULT_SET = { halfSizes: false, cupRule: 'anatomic', cupIncl: 40, tdLatMm: 5, legTarget: 'equal', snapCanal: true, autoFind: true, autoTemplate: true, share: false, mirrorRef: 'native', offsetTarget: 'auto', stemRule: 'cup', picture: 'clean' };
// sv: settings version. Before version 5 the outside build forced "sharing" on and stored it, so a browser that used it
// kept leaving the patient's name out (Sami, 12 Oct): that stored value is dropped once.
// Version 6 (14 Oct 2026): the cup rule's default became "native centre height, medial edge at the teardrop" (anatomic);
// every browser stored the old default ("teardrop") with its other settings, so a stored "teardrop" from before is moved
// to the new default once (the old rule stays in Auto plan settings as "Lowest rim on the teardrop line").
function loadSet() {
  let st = {}; try { st = JSON.parse(localStorage.getItem(SET_KEY) || '{}') || {}; } catch (e) { st = {}; }
  if (!(st.sv >= 5)) delete st.share;
  if (!(st.sv >= 6) && st.cupRule === 'teardrop') delete st.cupRule;
  return { ...DEFAULT_SET, ...st, sv: 6 };
}
function saveSet() { try { localStorage.setItem(SET_KEY, JSON.stringify(S.set)); } catch (e) { /* private window */ } }

// ---------- state ----------
const S = {
  lib: null, fam: {}, img: null, off: null, src: {}, key: null,
  view: { k: 1, x: 0, y: 0 }, wl: { c: 0, w: 1 }, wlFile: null, wlPrev: null, wlAuto: false, invertView: false,
  wf: 'hip', wfs: Object.keys(WF), side: 'R', mirrored: false, cranial: 'up', orient: { from: 'assumed', note: '' },
  cal: null, ballD: 25.0, vca: 6.0, kres: null,   // kres: a single knee film's own plan values (null each: the assumed value, with its provenance; Sami, 15-16 Oct)
  lm: {}, tpl: {}, tplAll: {}, hidden: {}, hiddenGeo: {}, active: null, rulers: [], angles: [], brand: null, allSizes: null, restoreNote: '',
  arm: null, drag: null, labels: true, set: loadSet(),
  auto: { busy: false, balls: [], ballIdx: 0, markers: [] }, canal: {}, notes: {}, mirror: null,
  clean: { strokes: [], crop: null }, cleanData: null, showOriginal: false, brush: 8, lmSeq: [], ref: null, prep: { rot: 0 }, prepDone: false, loupe: null,
  segHand: {}, segStep: null, fitted: false,   // segments opened or closed by hand while this step lasts; the view is "fitted" until zoomed or moved
  // saved projects (round 6, 13 Oct 2026; projects.js): the film's original file as opened (bytes, mime, sha256, name),
  // the case on t.addisortho.com it belongs to ({id, rev, sha256} of the server's copy), and the name and record number
  // typed for a picture that carries none
  orig: null, caseRef: null, patientIn: null,
  // the implant choice (13 Oct 2026, design C): the cup and stem family picked on the choice card before Auto template
  // places them ({cup?, stem?} family ids; none: the rules choose). Once placed, a family picked by hand is the slot's
  // own (t.userFam), and Auto template keeps it, as it keeps a size set by hand (t.userSize; amendment 4).
  choice: {},
  // r38: the hips planned as a bipolar hemiarthroplasty ({R?: true, L?: true}): no cup, the Chunli shell on the stem's head
  hemi: {},
  preBip: {},   // r38: a head chosen by hand before Bipolar, per hip, put back when it is a total hip again (this session only; Fable P3-2)
};
const cv = $('cv'), ctx = cv.getContext('2d');
let dpr = 1, raf = 0, cvRect = { left: 0, top: 0 };   // the canvas's place on the page, read on resize and when a gesture starts (never while it moves)
let sheet = null, lastInput = 'pointer';   // the phone's panel (uikit.makeSheet); whether the last input was a key or a pointer
const PHONE = typeof matchMedia === 'function' ? matchMedia('(max-width: 700px), (pointer: coarse) and (orientation: portrait)') : { matches: false };

// The guided steps (round 6, 13 Oct 2026): one identifier shared by the step strip, the five segments of the panel and
// the step's own button, so they can never disagree (Astra found the strip a step off: implants were shown as "Save").
const STEPS = [
  { id: 'cal', strip: 'Calibrate', title: 'Calibrate' },
  { id: 'prep', strip: 'Prepare', title: 'Prepare (clean, crop, level)' },
  { id: 'lm', strip: 'Landmarks', title: 'Landmarks' },
  { id: 'tpl', strip: 'Implants', title: 'Implants' },
  { id: 'save', strip: 'Save', title: 'Measurements and save' },
];

// ---------- loading ----------
async function boot() {
  try {
    const r = await fetch('library.json', { credentials: 'same-origin' });
    if (r.status === 401 || r.status === 403) return STANDALONE ? openMsg('Please sign in again', 'Your templating session has ended. Reload the page to sign in through a.addisortho.com.') : openMsg('Please sign in to the EMR first', 'Your EMR session has ended. Sign in, then open the X-ray from the chart.');
    S.lib = await r.json(); S.lib.heads = S.lib.heads || {}; S.lib.families = Array.isArray(S.lib.families) ? S.lib.families : [];
    for (const f of S.lib.families) S.fam[f.id] = f;
    PENDING = pendingList(S.lib);
    // implant ranges (12 Oct 2026): t.addisortho.com sends only the ranges open to this user, so a workflow is offered
    // when at least one of its implant kinds has a family
    S.wfs = Object.keys(WF).filter(k => WF[k].slots.some(sl => hasKind(SLOT[sl].kind)) || (k === 'diaph' && diaRangeOpen()));   // r39: the diaphyseal plan with the Chunli tumour range
    // ranges the account holds whose template files are not on the server yet (13 Oct 2026, amendment 6): with nothing
    // else open the page explains it and stays usable (open, calibrate, landmarks, measure; no implants) instead of exiting
    if (!S.wfs.length && PENDING.length) {
      S.wfs = Object.keys(WF).filter(k => WF[k].slots.some(sl => PENDING.some(p => p.kinds.includes(SLOT[sl].kind))));
      if (!S.wfs.length) S.wfs = ['hip'];
      S.noRange = true;
    }
    if (!S.wfs.length) return openMsg('No implant range is open to your account', STANDALONE ? 'Ask the administrator to open one for you on a.addisortho.com, then sign out (t.addisortho.com/logout) and sign in again.' : 'The implant library has no implants: tell the administrator.');
    if (!S.wfs.includes(S.wf)) S.wf = S.wfs[0];
  } catch (e) { return openMsg('Could not load the implant library', String(e.message || e)); }
  // short names in the header (Sami, 15 Oct: "keep hip/knee/bipolar narrower"), the full name as each option's title; the bipolar
  // hemiarthroplasty is a choice per hip within the hip plan (r38: Total hip or Bipolar)
  const wfOpt = (text, k, full) => { const o = new Option(text, k); o.title = full; return o; };
  for (const k of S.wfs) $('wf').add(wfOpt(WF[k].short || WF[k].label, k, WF[k].label));
  if (S.wfs.includes('hip') || S.wfs.includes('kneeAP')) $('wf').add(wfOpt('Long leg', 'll', `${LL_LABEL} (a standing long leg or a CT scout)`));   // r44 (Sami: "ONE PLAN")
  // the knee case (contract section 12): one patient's knee from up to four films; chosen here for a knee film on screen
  if (S.wfs.includes('kneeAP') || S.wfs.includes('kneeLat')) $('wf').add(wfOpt('Knee case', 'kc', 'Knee case: AP · lateral · skyline · long leg'));
  $('wf').onchange = () => {
    if (S.kc) { $('wf').value = 'kc'; return prompt('This is a knee case: open another X-ray or case (Open) to plan something else.'); }
    if ($('wf').value === 'kc') { if (!S.img) { $('wf').value = S.wf; return; } if (S.wf === 'hip') switchWf(S.wfs.includes('kneeAP') ? 'kneeAP' : 'kneeLat'); return kcStart(); }
    if ($('wf').value === 'll') return llStart();   // r44
    S.ll = null; switchWf($('wf').value); S.arm = null; prompt(''); save(); panel(); draw();
  };
  $('side').onchange = () => {
    if (S.kc) return kcSetSide($('side').value);   // a knee case: every film's side at once, their side checks cleared (one Undo step)
    S.side = $('side').value; S.sideSet = true; S.arm = null; llSideMoved(); prompt(S.ll && S.ll.hs !== S.side && (S.lm.hip || S.lm['head' + S.ll.hs]) ? `The femoral head was marked for the ${SIDE[S.ll.hs]} leg: mark the ${SIDE[S.side]} hip's head (and its other landmarks) on its own.` : ''); refreshFlips();   // Fable r44 P2-1 save(); panel(); draw();
  };
  $('file').onchange = e => { if (e.target.files[0]) openFile(e.target.files[0]); e.target.value = ''; };
  // t.addisortho.com: Open shows the start card, with the file chooser and the templated-patients list (round 6); the
  // hospital copy has no list, so Open goes straight to the file chooser
  $('openBtn').onclick = () => (STANDALONE ? showOpenCard() : $('file2').click());
  if (STANDALONE) { $('openBtn').title = 'Open: an X-ray, a saved project (.aotp) or a templated patient from the list'; $('openBtn').setAttribute('aria-label', 'Open'); }
  addEventListener('keydown', e => { if (e.key === 'Escape' && S.img && document.body.classList.contains('choosing') && !document.querySelector('.pv')) hideOpen(); });
  $('panelBtn').onclick = () => { document.body.classList.toggle('nopanel'); resize(); };
  // Undo and Fit in the header, always in reach (Sami, 13 Oct); Fit needs no implant selected
  $('undoBtn').onclick = undo;
  $('redoBtn').onclick = redo;   // Sami, 15 Oct: "add redo button as we have the undo button"
  $('fitBtn').onclick = () => { if (S.img) { fit(); draw(); } };
  sheet = makeSheet({ col: $('pcol'), bar: $('sheetbar'), handle: $('sheetHandle') });
  // round 11 (Sami, 13 Oct: a phone's film cleared by default, more on demand): the prompt shrinks to a chip, the
  // reference lines have a toggle on the film, the results to check a badge in the panel's row
  $('prompt').addEventListener('click', () => { if (PHONE.matches) promptMode($('prompt').classList.contains('mini') ? 'full' : 'mini'); });
  $('prompt').addEventListener('keydown', e => { if (PHONE.matches && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); $('prompt').click(); } });
  $('linesBtn').onclick = () => { S.linesOn = !S.linesOn; panel(); draw(); };   // panel(): its Clean view button follows
  $('warnBadge').onclick = () => { if (!mm()) { if (sheet.get() === 'peek') sheet.set('half'); return; } showChecks(); };
  const phoneChanged = () => { syncPrompt(); placeAlerts(); if (S.img) { panel(); draw(); } };
  placeAlerts();
  if (PHONE.addEventListener) PHONE.addEventListener('change', phoneChanged); syncPrompt();
  // a button or segment title clicked or tapped does not keep the keyboard focus, so the arrow keys go on working on the
  // X-ray; one reached with the keyboard (or a screen reader: no pointer, detail 0) keeps it
  document.addEventListener('click', e => {
    const c = e.detail > 0 && e.target.closest && e.target.closest('button, summary, input[type=checkbox]');
    if (c && document.activeElement === c && !c.closest('.pv')) c.blur();
  });
  addEventListener('keydown', () => { lastInput = 'key'; }, true);
  addEventListener('pointerdown', () => { lastInput = 'pointer'; }, true);
  $('file2').onchange = e => { if (e.target.files[0]) openFile(e.target.files[0]); e.target.value = ''; };
  $('planFile').onchange = e => { if (e.target.files[0]) openPlanFile(e.target.files[0]); e.target.value = ''; };
  const st = $('stage');
  st.addEventListener('dragover', e => { e.preventDefault(); $('open').classList.add('drag'); });
  st.addEventListener('dragleave', () => $('open').classList.remove('drag'));
  st.addEventListener('drop', e => { e.preventDefault(); $('open').classList.remove('drag'); if (e.dataTransfer.files[0]) openFile(e.dataTransfer.files[0]); });
  new ResizeObserver(resize).observe(st);
  PJ.initProjects(PROJ);   // the header's Save project button; projects.js has the file format, the server and the list
  bindPointer(); bindKeys(); resize(); panel();
  const q = new URLSearchParams(location.search);
  // wording that stays true once projects are saved to the server (Astra, 13 Oct: "nothing is uploaded" would not be)
  if (STANDALONE) $('foot').textContent = 'Addis Ortho Trading PLC · X-ray templating for surgeons. A planning aid: the plan is a draft and the surgeon decides. The X-ray opens in this browser; a saved project is stored for your team.';
  if (VIEWER) $('foot').textContent = 'Addis Ortho DICOM Viewer · X-ray templating on this computer, offline (templating r44). Trial software for planning practice; not for clinical decisions until validated and approved. Implants: the Addis Ortho ranges (made by Chunli), outlines from the manufacturer\'s 1:1 template sheets and 3D models.';
  pendingNote();
  for (const [text, fn] of kcOpenItems()) { const li = document.createElement('li'), a = document.createElement('a'); a.href = '#'; a.textContent = text; a.onclick = e => { e.preventDefault(); fn(); }; li.appendChild(a); $('openList').appendChild(li); }
  // the short part on top and the rest under the templated-patients list, so a phone sees the list without scrolling far
  if (STANDALONE) {
    $('openMsg').textContent = 'Drop a DICOM, PNG or JPEG file or a saved project (.aotp) here, choose one below, or open a templated patient from the list. The X-ray opens in this browser. Saving a project stores it for your team.';
    $('openNote').textContent = 'Plans are not kept in this browser after the page closes unless you ask for it (Auto plan settings). Saved pictures and files carry the patient\'s name and record number from the X-ray, as in the hospital; tick "sharing outside the hospital" (Auto plan settings) to leave them out. Text burned into the X-ray itself is never removed automatically: cover it with "Black out" before sharing.';
    PJ.showList($('cases')); return;
  }
  if (q.get('study') || q.get('accession')) openFromArchive(q).catch(failed);
}

function openMsg(title, msg, items) {
  $('open').style.display = 'flex'; $('openTitle').textContent = title; $('openMsg').textContent = msg;
  $('openList').textContent = '';
  // over a film already open (Open on t.addisortho.com, or a file that failed): a way back to it, and the card has the
  // whole stage (the panel and the implant bar are of the film behind)
  document.body.classList.toggle('choosing', !!S.img);
  if (S.img) items = [...(items || []), ['Back to the X-ray', hideOpen]];
  for (const [text, fn] of items || []) {
    const li = document.createElement('li'), a = document.createElement('a');
    a.href = '#'; a.textContent = text; a.onclick = e => { e.preventDefault(); fn(); }; li.appendChild(a); $('openList').appendChild(li);
  }
}
function failed(e) {
  if (e && e.message === 'signin') return openMsg('Please sign in to the EMR first', 'Your EMR session has ended. Sign in, then open the X-ray from the chart.');
  // an X-ray that is stored in the patient list but cannot be read can be deleted for good (21 Oct 2026; contract section 14)
  openMsg(e instanceof PJ.ProjectError ? 'Could not open the project' : 'Could not open the X-ray', String((e && e.message) || e), e && e.deleteSubject ? PJ.deleteItems(e.deleteSubject) : undefined);
}
function hideOpen() { $('open').style.display = 'none'; document.body.classList.remove('choosing'); resize(); }
// t.addisortho.com's Open: the start card with the file chooser and the templated-patients list (projects.js)
function showOpenCard() {
  openMsg(S.img ? 'Open another X-ray or a templated patient' : 'Open an X-ray to template', S.img ? 'Drop a DICOM, PNG or JPEG file or a saved project (.aotp) here, choose one below, or open a templated patient from the list. The X-ray on screen stays until another one opens.' : $('openMsg').textContent, kcOpenItems());
  PJ.showList($('cases'));
}
// the start card's way into a knee case: its first film from a file
const kcOpenItems = () => (S.wfs.includes('kneeAP') || S.wfs.includes('kneeLat') ? [['Start a knee case (AP, lateral, skyline, long leg): choose its first film', () => kcPick(null, true)]] : []);
// a refusal from the archive in words; in the viewer a 404 means the hand-over has ended (the viewer was restarted)
const answered = st => (VIEWER && st === 404 ? 'this templating link has ended: press Template in the viewer again' : `${SRCN} answered HTTP ${st}`);
async function qido(path, params) {
  const r = await fetch(path + '?' + new URLSearchParams(params), { headers: { Accept: 'application/dicom+json' }, credentials: 'same-origin' });
  if (r.status === 401 || r.status === 403) throw new Error('signin');
  if (r.status === 204) return [];
  if (!r.ok) throw new Error(answered(r.status));
  return r.json();
}
async function openFromArchive(q) {
  openMsg('Opening the X-ray…', '');
  let study = q.get('study');
  if (!study) {
    const s = await qido(`${RS}/studies`, { AccessionNumber: q.get('accession'), includefield: '0020000D' });
    if (s.length !== 1) return openMsg(s.length ? 'More than one study for this order' : 'No images for this order yet', 'Open the X-ray from the imaging viewer instead.');
    study = val(s[0], '0020000D');
  }
  const inst = (await qido(`${RS}/studies/${encodeURIComponent(study)}/instances`, { includefield: '00080060,0008103E,0020000E,00080018,00200013,00080016' }))
    .filter(i => ['DX', 'CR', 'RG', 'DR'].includes(val(i, '00080060')));
  const want = q.get('instance');
  const pick = want ? inst.filter(i => val(i, '00080018') === want) : inst;
  if (!pick.length) return openMsg('No plain X-ray in this study', 'Templating needs a DX or CR image. CT and other images open in the imaging viewer.');
  const load = i => loadInstance(study, val(i, '0020000E'), val(i, '00080018')).catch(failed);
  if (pick.length === 1) return load(pick[0]);
  openMsg('Choose the X-ray to template', `${pick.length} images in this study:`,
    pick.map((i, n) => [`${val(i, '0008103E') || 'Image'} · ${val(i, '00080060')} · #${val(i, '00200013') || n + 1}`, () => load(i)]));
}
async function loadInstance(study, series, sop) {
  openMsg('Opening the X-ray…', `Reading the full-quality image from ${VIEWER ? 'the viewer' : 'the archive'}.`);
  const url = `${WADO}?` + new URLSearchParams({ requestType: 'WADO', studyUID: study, seriesUID: series, objectUID: sop, contentType: 'application/dicom', transferSyntax: EXPLICIT_LE });
  const r = await fetch(url, { credentials: 'same-origin' });
  if (r.status === 401 || r.status === 403) throw new Error('signin');
  if (!r.ok) throw new Error(answered(r.status));
  // the archive sends it uncompressed; the viewer hands over the file as it was stored (often JPEG 2000 or JPEG-LS), so
  // it is decoded as a file from disk is
  const buf = await r.arrayBuffer(), u = new Uint8Array(buf), img = VIEWER ? await decodeAny(buf) : decodeImage(buf);
  setImage(img, { study, series, sop }, await origOf(u, 'application/dicom', (sop || 'archive') + '.dcm'));
}
// the film's original bytes kept for Save project (round 6; Astra: openFile threw them away), with their sha256
async function origOf(u, mime, name, known = '') {
  if (u.length > PJ.IMAGE_MAX) return { origWhy: `This X-ray cannot be saved as a project: its file is over ${PJ.IMAGE_MAX / 1048576} MB.` };
  let sha = known; if (!sha) try { sha = await PJ.sha256hex(u); } catch (e) { sha = ''; }   // no SubtleCrypto: the page is not on https
  return sha ? { orig: { bytes: u, mime, sha256: sha, name }, hash: sha } : { origWhy: 'This X-ray cannot be saved as a project here: the page must be opened over https.' };
}
// A file from this computer: DICOM (any common compression), PNG (16-bit kept at full depth), JPEG or WebP, or a saved
// project (.aotp, projects.js). Images over 80 MB or 60 megapixels are refused before they are decoded (round 6: phones).
async function openFile(f) {
  let sha = '';
  try {
    const proj = /\.aotp$/i.test(f.name);
    const pick = takePick();   // a knee case's film chosen for a role (its tab's "+"), or a knee case started from the start card
    if (f.size > (proj ? PJ.CASE_FILE_MAX : PJ.IMAGE_MAX)) throw new Error(proj ? `This project file is ${(f.size / 1048576).toFixed(0)} MB; a project is at most ${Math.round(PJ.CASE_FILE_MAX / 1048576)} MB.` : `This file is ${(f.size / 1048576).toFixed(0)} MB; the page opens X-rays up to ${PJ.IMAGE_MAX / 1048576} MB. Save a smaller copy (a JPEG of the film, or the DICOM without extra frames) and open that.`);
    openMsg('Opening ' + f.name + '…', '');
    const buf = await f.arrayBuffer(), u = new Uint8Array(buf);
    if (proj || PJ.isZip(u)) return await PJ.openAotp(u, f.name);
    if (pick && (pick.startNew || S.kc)) return await kcAddFile(u, f.name, pick.role, { startNew: pick.startNew || !S.kc });
    // t.addisortho.com (round 13): a film whose case this tab (or this browser, when plans are kept here) already made is
    // reopened from the patient list instead of becoming a second case
    sha = '';
    if (STANDALONE && u.length <= PJ.IMAGE_MAX) {
      try { sha = await PJ.sha256hex(u); } catch (e) { sha = ''; }
      const known = sha && PJ.knownCase(sha);
      if (known && await PJ.openKnown(known, u, sha)) return;
      // the X-ray of a knee case whose changes were kept in this browser (never reached the patient list): offered back
      const kept = sha && PJ.keptCaseFor(sha);
      if (kept) {
        hideOpen();
        const yes = await PJ.choose(`This X-ray is a film of a knee case with changes kept in this browser at ${hhmmOf(kept.rec.at)} that may not have reached the patient list (${kept.rec.label}).`, [['Restore the knee case', 'yes', 'primary'], ['Open it on its own', 'no', '']],
          'Restore: the case comes back with this film; the others are named so you can open them again.');
        if (yes === 'yes') return await kcRestoreFrom(kept, u, f.name, sha);
      }
    }
    const { img, mime } = await decodeBytes(buf, f.name);
    const o = await origOf(u, mime, f.name, sha);
    setImage(img, { file: f.name, hash: o.hash || '' }, { ...o, how: 'file' });
  } catch (e) {
    // this file is the stored X-ray of a case in the list: it can be removed from there (the case, or its film of a knee case)
    const kn = sha && STANDALONE ? PJ.knownCase(sha) : null;
    if (e && typeof e === 'object' && kn) e.deleteSubject = { id: kn.id, role: null, caseOnly: !!kn.ck, what: 'the stored copy of this X-ray' };
    failed(e);
  }
}
// The X-ray from a file's bytes → {img, mime}. The size in pixels is read from the header first where that is cheap.
async function decodeBytes(buf, name = '', mime = '') {
  const u = new Uint8Array(buf), k = PJ.sniffImage(u, name, mime);
  if (!k.kind) throw new Error('Not a DICOM, PNG, JPEG or WebP file, nor a saved project (.aotp).');
  let w = k.width, h = k.height;
  if (k.kind === 'dicom') { try { const t = parseDicom(buf); w = parseInt(t.Columns, 10); h = parseInt(t.Rows, 10); } catch (e) { /* decodeAny says what is wrong */ } }
  if (w > 0 && h > 0 && w * h > PJ.PIXELS_MAX) throw new Error(PJ.tooManyPixels(w, h));
  const bitmap = async () => decodeBitmap(await createImageBitmap(new Blob([u], { type: k.mime })));
  const img = k.kind === 'dicom' ? await decodeAny(buf) : k.kind === 'png' ? (await decodePNG(buf)) || await bitmap() : await bitmap();
  if (img.cols * img.rows > PJ.PIXELS_MAX) throw new Error(PJ.tooManyPixels(img.cols, img.rows));
  return { img, mime: k.mime };
}
// A project (an .aotp, or a case from the patient list), already checked whole by projects.js, put on the page: the image
// decoded and its size checked against the project's before the film on screen is replaced; no automatic ball or marker
// search and no plan from this browser while it loads (contract 5); the plan put back exactly (applyState).
async function loadProject(project, bytes, { caseRef = null, how = 'file', role = null } = {}) {
  if (project.v === 2) return loadCase(project, bytes, { caseRef, how, role });   // a knee case (contract section 12)
  // its implants against the library this page has loaded (Astra's page re-review, finding 4): each in a place of its kind,
  // with a size, head and neck its family has; refused before anything on the page changes (the film open stays)
  const lp = PJ.libraryProblems(project.state, S.lib);
  if (lp.length) throw new PJ.ProjectError(`This project cannot be opened: ${lp.slice(0, 4).join('; ')}${lp.length > 4 ? '; …' : ''}.`);
  const buf = bytes.byteOffset === 0 && bytes.byteLength === bytes.buffer.byteLength ? bytes.buffer : bytes.slice().buffer;
  const { img, mime } = await decodeBytes(buf, project.image.fileName, project.image.mime);
  if (mime !== project.image.mime) throw new PJ.ProjectError('This project cannot be opened: its image is not the type the project says.');
  if (img.cols !== project.image.width || img.rows !== project.image.height) throw new PJ.ProjectError(`This project cannot be opened: its image is ${img.cols} × ${img.rows} pixels, the project says ${project.image.width} × ${project.image.height}.`);
  const ref = STANDALONE ? caseRef : null;   // the hospital copy has no patient list
  setImage(img, { file: project.image.fileName, hash: project.image.sha256 }, { orig: { bytes: new Uint8Array(buf), mime, sha256: project.image.sha256, name: project.image.fileName }, state: project.state, caseRef: ref, patient: project.patient,
    how: how === 'file' ? 'aotp' : how,
    note: how === 'known' ? `This X-ray is already in the templated-patients list: its saved plan is opened${ref ? ` (version ${ref.rev})` : ''}.` : `Project opened${how === 'list' ? ' from the templated-patients list' : ''}${ref ? ` (version ${ref.rev})` : ''}: as it was saved.` });
}

// opt (round 6, projects): orig / origWhy (the original file for Save project, or why there is none), and for a project
// state (put back instead of this browser's plan, with no automatic search), caseRef, patient and note
function setImage(img, src, opt = {}) {
  if (S.img) PJ.filmClosing();   // t.addisortho.com (round 13): the film open before is saved to the patient list in the background
  if (keepT) keepNow();   // a change to the film open before, still waiting to be kept in this browser, goes under that film's own key
  S.kc = opt.kc || null;   // a knee case (contract section 12), or none: an X-ray of its own
  S.orig = opt.orig || null; S.origWhy = opt.origWhy || ''; S.caseRef = opt.caseRef || null;
  S.patientIn = opt.patient ? { name: opt.patient.name || '', recordNo: opt.patient.recordNo || '', hospital: opt.patient.hospital || '', age: Number.isInteger(opt.patient.age) ? opt.patient.age : null, asked: true } : null;
  PJ.forget();
  loadFilm(img, src, opt, true);
}
// The film's picture and plan put on the page: a new X-ray or case (setImage, fresh), or another film of the knee case on
// screen (kcShowImg: the case, its patient list entry and its "unsaved" mark stay as they are)
function loadFilm(img, src, opt, fresh) {
  S.img = img; S.src = src; S.wl = { c: img.wc, w: img.ww }; S.wlFile = { ...S.wl }; S.wlAuto = false; S.wlPrev = null; S.invertView = false;
  if (img.ww >= 0.95 * (img.max - img.min)) { autoWindow(); S.wlAuto = true; }   // a window over the whole range shows a flat grey film
  const m = img.meta || {};
  // a plan belongs to one image: the archive's instance UID, or the file's own content (same name is not enough)
  S.key = 'aoc-tpl:' + (m.SOPInstanceUID || (src.hash ? 'sha256:' + src.hash : null) || ('file:' + (src.file || '') + ':' + img.cols + 'x' + img.rows));
  // orientation from Patient Orientation: [direction of +x (rows), direction of +y (columns)]; "L\F" is the standard
  // AP display (patient's left at image right, feet at the bottom). Both values are read: films can be rotated.
  const po = String(m.PatientOrientation || '').toUpperCase().split('\\').map(v => v.trim()[0] || '');
  const dirOfLetter = (ch, want) => ch === want ? 1 : ch === { H: 'F', F: 'H', L: 'R', R: 'L' }[want] ? -1 : 0;
  const cx = dirOfLetter(po[0], 'H'), cy = dirOfLetter(po[1], 'H'), lx = dirOfLetter(po[0], 'L'), ly = dirOfLetter(po[1], 'L');
  S.cranial = cy === -1 ? 'up' : cy === 1 ? 'down' : cx === 1 ? 'right' : cx === -1 ? 'left' : 'up';
  const known = (lx || ly) !== 0;
  S.mirrored = known && G.dot(G.P(lx, ly), rot90(CRAN[S.cranial])) < 0;   // patient's left not where a standard film has it
  S.orient = known ? { from: 'header', note: 'from the image header (Patient Orientation)' } : { from: 'assumed', note: '' };
  const bp = String(m.BodyPartExamined || m.SeriesDescription || m.StudyDescription || '').toUpperCase(), vp = String(m.ViewPosition || '').toUpperCase();
  S.wf = /KNEE/.test(bp) ? (/^(LL|RL|LAT|ML|LM|LATERAL)/.test(vp) ? 'kneeLat' : 'kneeAP') : /SHOULDER|HUMER/.test(bp) && S.wfs.includes('shoulder') ? 'shoulder' : /LEG|LOWER|EXTREM|FEMUR|TIBIA/.test(bp) ? 'kneeAP' : 'hip';   // r43 (DIAG #11): a shoulder film opens as Shoulder
  if (!S.wfs.includes(S.wf)) S.wf = S.wfs[0];
  S.wfAsk = !opt.state && !opt.kcRole && !bp.trim() && ['kneeAP', 'kneeLat', 'shoulder'].some(w => S.wfs.includes(w));   // r43: the header names no body part: asked on the panel, not guessed silently   // a film this account has no implants for: its first workflow
  const lat = String(m.ImageLaterality || m.Laterality || '').toUpperCase();
  const desc = [m.StudyDescription, m.SeriesDescription].filter(Boolean).join(' ').toUpperCase();
  const dl = /\b(LEFT|LT)\b/.test(desc), dr = /\b(RIGHT|RT)\b/.test(desc);
  S.headerSide = lat === 'R' || lat === 'L' ? lat : dl !== dr ? (dl ? 'L' : 'R') : null;
  if (S.headerSide) S.side = S.headerSide;
  S.sideSet = !!S.headerSide;
  // one joint or both only when the image says so (a bare picture says nothing; Astra, 11 Oct)
  const both = /PELVIS|BILAT|BOTH/.test(bp + ' ' + desc) || lat === 'B', oneJoint = !both && (lat === 'R' || lat === 'L' || /\bHIP\b|KNEE/.test(bp + ' ' + desc));
  S.singleJoint = oneJoint;
  S.scope = both ? 'both' : oneJoint && S.wf === 'hip' ? 'one' : null;
  S.headerView = /^(LL|RL|LAT|ML|LM|LATERAL|XTABLE)/.test(vp) ? 'lateral' : /^(AP|PA)/.test(vp) ? 'AP' : null;
  S.oldCupRule = false; S.cal = null; S.kres = null; S.lm = {}; S.tplAll = { hip: {}, kneeAP: {}, kneeLat: {} }; S.tpl = S.tplAll[S.wf] || (S.tplAll[S.wf] = {}); S.hidden = {}; S.hiddenGeo = {}; S.rulers = []; S.active = null; S.arm = null; S.restoreNote = '';
  S.auto = { busy: false, balls: [], ballIdx: 0, markers: [] }; S.canal = {}; S.notes = {}; S.mirror = null; S.sent = null; S.sendError = null; S.masks = [];
  S.caseType = 'primary'; S.exist = null; S.rv = {}; S.exRevSeen = 0;   // revision and conversion (contract section 13): the case type, the film's existing-implant regions, the revision record
  S.clean = { strokes: [], crop: null }; S.cleanData = null; S.showOriginal = false; S.lmSeq = []; S.ref = null; S.filling = false; S.prep = { rot: 0 }; S.prepDone = false;
  S.ll = null; S.segHand = {}; S.segStep = null; S.choice = {}; S.keptLibraryId = null; S.brand = null; S.angles = []; S.allSizes = null; S.hemi = {}; S.preBip = {}; S.dia = null;
  defaultCalibration();
  if (opt.state) applyState(opt.state); else if (!opt.kcRole) restore();   // a knee case's new film starts empty (its plan is the case's)
  if (S.kc && opt.kcRole) kcFilmSetup(opt.kcRole);
  $('wf').value = wfSel(); $('side').value = S.side;
  ensureSlots();
  $('open').style.display = 'none'; document.body.classList.add('has-img'); document.body.classList.remove('choosing');
  $('study').textContent = STANDALONE && S.set.share ? `${m.format || 'file'}, ${img.cols} × ${img.rows}` : [m.StudyDescription || m.SeriesDescription || src.file || 'X-ray', fmtDate(m.StudyDate),
    m.AccessionNumber ? 'order ' + m.AccessionNumber : '', m.PatientID ? 'MRN ' + m.PatientID : '',
    src.file ? `${m.format || 'file'}, ${img.cols} × ${img.rows}` : ''].filter(Boolean).join(' · ');
  HIST.length = 0; REDO.length = 0; save();
  if (fresh) { markSaved(); PJ.filmOpened(opt.how || (opt.state ? 'aotp' : 'file')); }   // t.addisortho.com: the image sent and the case made in the background (round 13)
  renderImage(); panel(); fit(); draw();   // the panel first: on a phone its height sets the film's
  if (opt.state) { prompt([opt.note, S.restoreNote].filter(Boolean).join(' ')); return; }   // a project: as saved, nothing looked for again
  if (S.set.autoFind) setTimeout(runAutoFind, 40);
  else if (S.cal && !calEst(S.cal)) nextStep();
}
const fmtDate = d => (d && /^\d{8}$/.test(d)) ? `${d.slice(6)}/${d.slice(4, 6)}/${d.slice(0, 4)}` : '';
const hhmmOf = t => { const d = new Date(t); return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`; };

// Detector spacing / magnification: a starting estimate only. It is not true size at the hip or knee.
// detector: true when the number is the detector's own pitch with no magnification taken off.
function dicomEstimate() {
  const m = S.img.meta || {}, ct = String(m.PixelSpacingCalibrationType || '').toUpperCase();
  if (ctFilmOf(m)) { const ps = m.pixelSpacing, sc = ctScoutOf(m); if (!(ps && ps[0] > 0.01 && ps[0] < 5)) return null; return { mmPerPx: ps[0], note: sc ? `CT scanner pixel spacing ${ps[0]} mm` : `CT pixel spacing ${ps[0]} mm (not marked as a scout)` }; }   // r43 (Fable P2-1): a CT's detector pitch (Imager Pixel Spacing) is never a scale, on any CT film
  // a Pixel Spacing calibrated by the machine (geometry or a fiducial) is nearer the patient than detector spacing
  if (m.pixelSpacing && m.pixelSpacing[0] > 0 && (ct === 'GEOMETRY' || ct === 'FIDUCIAL' ||
      (m.imagerPixelSpacing && Math.abs(m.pixelSpacing[0] / m.imagerPixelSpacing[0] - 1) > 0.002)))
    return { mmPerPx: m.pixelSpacing[0], note: `pixel spacing ${m.pixelSpacing[0]} mm calibrated by the machine (${ct || 'method not stated'})${m.PixelSpacingCalibrationDescription ? ': ' + m.PixelSpacingCalibrationDescription : ''}` };
  if (m.imagerPixelSpacing && m.imagerPixelSpacing[0] > 0) {
    const mag = m.magnification > 0.9 && m.magnification < 2 ? m.magnification : null;
    return { mmPerPx: m.imagerPixelSpacing[0] / (mag || 1.0), detector: !mag, note: mag ? `detector ${m.imagerPixelSpacing[0]} mm ÷ magnification ${mag} from the X-ray machine` : `detector ${m.imagerPixelSpacing[0]} mm, magnification unknown` };
  }
  if (m.pixelSpacing && m.pixelSpacing[0] > 0) return { mmPerPx: m.pixelSpacing[0], note: `pixel spacing ${m.pixelSpacing[0]} mm (${m.PixelSpacingCalibrationType || 'type not stated'})` };
  return null;
}
function defaultCalibration() {
  const c = ctScale(); if (c) { S.cal = c; return; }   // r43: a CT scout with Pixel Spacing: the scanner's scale, no ball
  const e = dicomEstimate();
  S.cal = e ? { method: 'dicom', mmPerPx: e.mmPerPx, note: e.note } : null;
}
// r43 (29 Oct 2026; Sami: "CT scout should not need calibration with sphere as it is already a CT scan"; DESIGN-2026-10-29-
// long-leg-ct-scout.md section 4): a CT localizer (scout, topogram, surview) is known by its header: Modality CT (or a CT image
// SOP class) and ImageType LOCALIZER, or TOPOGRAM / SCOUT / SURVIEW / SCANOGRAM / LOCALIZER in its image type or series description.
// Its Pixel Spacing is the scanner's scale at the patient: used at once as "Scale from the CT scanner", no ball and no
// magnification. Kept as method 'dicom' with ct 'scanner', so a plan saved here opens on an older page as a machine estimate
// (the safe side), and it counts only while the film on screen is that scout with that spacing (a plan cannot claim it). A
// scout with only Imager Pixel Spacing (the detector's pitch, far from the patient on a CT) is never used as a scale.
const CT_SOP = ['1.2.840.10008.5.1.4.1.1.2', '1.2.840.10008.5.1.4.1.1.2.1'];
const ctFilmOf = m => !!m && (String(m.Modality || '').toUpperCase().trim() === 'CT' || CT_SOP.includes(String(m.SOPClassUID || '').trim()));   // Fable P2-1: any CT image
function ctScoutOf(m) {
  m = m || {};
  if (String(m.Modality || '').toUpperCase().trim() !== 'CT' && !CT_SOP.includes(String(m.SOPClassUID || '').trim())) return '';
  const it = String(m.ImageType || '').toUpperCase(), d = String(m.SeriesDescription || '').toUpperCase();   // the series' own words (a study or protocol name covers the whole exam)
  if (/LOCALI[SZ]ER/.test(it)) return 'its image type, LOCALIZER';
  const w = (it + ' ' + d).match(/TOPOGRAM|SCOUT|SURVIEW|SCANOGRAM|LOCALI[SZ]ER/);
  return w ? `"${w[0]}" in its header` : '';
}
const ctPs = () => { const m = (S.img && S.img.meta) || {}; return ctScoutOf(m) && m.pixelSpacing && m.pixelSpacing[0] > 0.01 && m.pixelSpacing[0] < 5 ? m.pixelSpacing[0] : null; };
function ctScale() { const ps = ctPs(); return ps ? { method: 'dicom', ct: 'scanner', confirmed: true, mmPerPx: ps, note: `CT scanner pixel spacing ${ps} mm (recognised as a CT scout from ${ctScoutOf(S.img.meta)})` } : null; }
const isCtCal = c => !!c && c.method === 'dicom' && c.ct === 'scanner' && ctPs() !== null && Math.abs(c.mmPerPx - ctPs()) < 1e-9;
const calEst = c => !!c && c.method === 'dicom' && !isCtCal(c);   // a machine estimate (in a knee case: DICOM spacing only): not a calibration
// why a CT scout has no scale of its own ('' when it has one, or is not a scout)
function ctNoScaleWhy() {
  const m = (S.img && S.img.meta) || {}; if (!ctFilmOf(m) || ctPs()) return '';
  const ps = m.pixelSpacing; if (ps && ps[0] > 0) return ctScoutOf(m) ? `This CT scout's Pixel Spacing (${ps[0]} mm) is outside 0.01-5 mm, so it is not believed: mark a known length${S.kc ? ', or scale it from the AP' : ''}.` : ps[0] > 0.01 && ps[0] < 5 ? '' : `This CT image's Pixel Spacing (${ps[0]} mm) is outside 0.01-5 mm, so it is not believed: mark a known length${S.kc ? ', or scale it from the AP' : ''}.`;   // r44 (Fable r43 recheck P3): the same gate on a CT not marked as a scout; a CT not marked as a scout with Pixel Spacing: a machine estimate, as r40
  return m.imagerPixelSpacing && m.imagerPixelSpacing[0] > 0 ? `This CT ${ctScoutOf(m) ? 'scout' : 'image'} gives only the detector's pixel spacing (Imager Pixel Spacing ${m.imagerPixelSpacing[0]} mm), not the scanner's scale at the patient. On a CT the detector is far from the patient, so sizes would come out about 1.8 times too large: it is not used. Mark a known length (a ruler on the scout, or an implant of known size)${S.kc ? ', or scale it from the AP' : ''}.`
    : `This CT ${ctScoutOf(m) ? 'scout' : 'image'} carries no pixel spacing (an export or a screen picture): mark a known length${S.kc ? ', or scale it from the AP' : ''}.`;
}
// the CT scale checked against the anatomy, never blocking: a femoral head 38-60 mm; a condylar width 60-95 mm; in a knee case
// the condylar width within 5 % of the calibrated AP's. → [[name, text, cls]]
function ctChecks() {
  const k = mm(); if (!isCtCal(S.cal) || !k) return [];
  const out = [];
  for (const key of ['head' + S.side, 'head' + other(S.side), 'hip']) {
    const g = S.lm[key] && lmGeom(key); if (!g || !g.r) continue;
    const d = 2 * g.r * k, ok = d >= 38 && d <= 60;
    out.push(['CT scale check', `femoral head ${f1(d)} mm (expected 38-60 mm)${ok ? '' : ': check the CT scale (a ball or a known length replaces it)'}`, ok ? '' : 'warn']); break;
  }
  const w = S.lm.condW && lmGeom('condW');
  if (w) {
    const d = G.dist(w.a, w.b) * k, ok = d >= 60 && d <= 95;
    out.push(['CT scale check', `condylar width ${f1(d)} mm (expected 60-95 mm)${ok ? '' : ': check the CT scale'}`, ok ? '' : 'warn']);
    const st = S.kc ? kcFilmState('ap') : null, ac = st && st.cal, aw = st && st.lm ? lmGeomOf('condW', st.lm.condW) : null;
    if (aw && ac && (ac.method === 'ball' || ac.method === 'line') && ac.confirmed !== false) {
      const da = G.dist(aw.a, aw.b) * ac.mmPerPx, p = (d / da - 1) * 100;
      out.push(['CT scale check', `condylar width ${f1(d)} mm here, ${f1(da)} mm on the calibrated AP (${p >= 0 ? '+' : ''}${f1(p)} %)${Math.abs(p) > 5 ? ': more than 5 % apart, check the CT scale' : ''}`, Math.abs(p) > 5 ? 'warn' : '']);
    }
  }
  if (!out.length) out.push(['CT scale check', 'not yet: mark the femoral head (or the condylar width) to check the scanner\'s scale against the anatomy', '']);
  return out;
}
function ctCalBox() {
  const why = ctNoScaleWhy(), on = isCtCal(S.cal), box = el(`<div class="${why ? 'warnbox' : on ? 'okbox' : 'note'}" id="ctcal"></div>`);
  box.appendChild(el(ctScoutOf(S.img.meta) ? `<div><b>CT scout</b>, recognised from ${esc(ctScoutOf(S.img.meta))}.</div>` : '<div><b>CT image</b> (Modality CT), not marked as a scout.</div>'));
  if (why) box.appendChild(el(`<div>${esc(why)}</div>`));
  else if (on) {
    box.appendChild(el(`<div>Scale from the CT scanner: ${S.cal.mmPerPx} mm per pixel from the scout's header. No ball is needed and no magnification is applied.</div>`));
    for (const [, t, c] of ctChecks()) box.appendChild(el(`<div class="${c === 'warn' ? 'warn' : ''}">${esc(cap(t))}.</div>`));
    box.appendChild(el('<div class="note">If the scan\'s spacing looks wrong, use a ball or a known length below: that replaces the scanner\'s scale (said in the results).</div>'));
  } else box.appendChild(btn('Use the CT scanner scale', () => { S.cal = ctScale(); afterCal(); nextStep(); }, 'primary', false, 'Back to the scanner\'s own scale from the scout\'s Pixel Spacing'));
  return box;
}
const ctBoxOn = m => ctFilmOf(m) && (!!ctScoutOf(m) || !!ctNoScaleWhy());   // the CT card on Calibrate

// ---------- automatic calibration and R/L marker ----------
// On opening: the whole film is searched for the ball (same quality gate as a click) and for an R or L marker. Both
// are proposals: the scale is used but marked unconfirmed, and the guided steps stop until the surgeon confirms the
// pink circle is the ball (another round, dense object can pass every shape test; Astra, 11 Oct) and confirms which
// way the film faces.
function runAutoFind() {
  const img = S.img; if (!img) return;
  const est = dicomEstimate(), mmpp = est ? est.mmPerPx : null, needBall = !S.cal || calEst(S.cal);
  const unconfirmed = S.cal && S.cal.method === 'ball' && S.cal.confirmed === false;   // a restored proposal: find the others too
  S.auto = { busy: true, balls: [], ballIdx: 0, markers: [] }; panel();
  setTimeout(() => {
    if (S.img !== img) return;
    const un = exUnknown();   // none on a film just opened; a restored plan's confirmed implant is never taken for the ball or a letter
    if (exOpen().length) { S.auto.busy = false; panel(); return; }   // an unresolved proposal: no search until it is resolved (Astra, 16 Oct, P1 2)
    try { S.auto.markers = findMarkers(img, { mmPerPx: mmpp, unknown: un }); } catch (e) { S.auto.markers = []; }
    if (S.orient.from === 'assumed') applyMarker();
    if (needBall) {
      try { S.auto.balls = findBalls(img, { mmPerPx: mmpp, ballMm: S.ballD, unknown: un }); } catch (e) { S.auto.balls = []; }
      if (S.auto.balls.length) setBall(S.auto.balls[0], 'found automatically', true);
    } else if (unconfirmed) { try { S.auto.balls = findBalls(img, { mmPerPx: mmpp, ballMm: S.ballD, unknown: un }); } catch (e) { S.auto.balls = []; } }
    S.auto.busy = false; save();
    HIST.splice(0, HIST.length - 1); REDO.length = 0; markSaved(); syncHeader();   // the history starts here: Undo never takes back what the page found by itself
    panel(); draw();
    nextStep();
  }, 30);
}
// Marker position against the film's middle: a standard film has the patient's left on the image right.
function applyMarker() {
  const ms = S.auto.markers.slice(0, 2); if (!ms.length) return;
  const lr = rot90(cranVec()), c = G.P((S.img.cols - 1) / 2, (S.img.rows - 1) / 2), L = Math.max(S.img.cols, S.img.rows);
  let vote = 0; const said = [];
  for (const mk of ms) {
    const pos = G.dot(G.sub(G.P(mk.x, mk.y), c), lr) / L;
    if (Math.abs(pos) < 0.08) continue;   // too near the middle to say which side it marks
    vote += (mk.letter === 'L' ? 1 : -1) * Math.sign(pos);
    said.push(`"${mk.letter}" at the image ${pos > 0 ? 'right' : 'left'}${mk.mirrored ? ' (the letter reads backwards)' : ''}`);
  }
  if (!vote) { if (said.length) S.orient = { from: 'assumed', note: `markers disagree (${said.join(', ')}): set the orientation below` }; return; }
  // the image's middle is the pelvic midline only on a film of both hips; on a one-joint film the marker can sit on
  // either side of the joint, so the letter is only reported there, and nothing is decided until confirmed
  if (S.scope !== 'both') { S.orient = { from: 'assumed', note: `marker ${said.join(' and ')} read; on a film of one joint its place does not show which way the film faces`, marker: ms[0] }; return; }
  S.mirrored = vote < 0;
  S.orient = { from: 'marker', note: `marker ${said.join(' and ')}: the patient's right is on the image ${S.mirrored ? 'right (mirrored film)' : 'left (standard)'}`, marker: ms[0], markerMirrored: S.mirrored, markerSaid: said.join(' and ') };
  refreshFlips();
}

// ---------- persistence (this browser only, geometry only) ----------
// Undo: every saved change is a snapshot (the last 60); Ctrl/Cmd+Z or the Undo button in the header steps back. The
// eraser's strokes, the one large part of a plan, are never copied into a snapshot: a stroke list is never changed in
// place (a change makes a new list of the same stroke objects), so the snapshot keeps the list itself (round 6: phones).
// Redo (Sami, 15 Oct: "add redo button as we have the undo button"): what Undo took back, newest last; any new change
// empties it. A knee case's shared plan (its values, sizes, assessment, the films' checks) is in each snapshot too.
// Redo is also emptied by a change Undo does not record (the window, display choices, notes, the plan's rules, the patient's
// details: everything the saved state holds; Astra, 15 Oct, P2 7): the saved-state fingerprint taken when Undo or Redo put
// the page back (redoBase) must still be the page's when Redo is pressed, else what Redo holds belongs to another branch.
const HIST = [], HIST_MAX = 60, REDO = []; let histLock = false, redoBase = null, snapping = false;
const redoStale = () => REDO.length > 0 && !snapping && (redoBase === null || stateKey() !== redoBase);
function snapshot() {
  const K = S.kc;
  return { j: JSON.stringify({ wf: S.wf, ll: S.ll || null, side: S.side, mirrored: S.mirrored, cranial: S.cranial, orient: S.orient, cal: S.cal, ballD: S.ballD, vca: S.vca, kres: S.kres,
    lm: S.lm, dia: S.dia || null, tplAll: { ...S.tplAll, [S.wf]: S.tpl }, rulers: S.rulers, angles: S.angles, brand: S.brand, hemi: S.hemi, clean: { ...S.clean, strokes: undefined }, masks: S.masks, caseType: S.caseType, exist: S.exist, rv: S.rv, lmSeq: S.lmSeq, ref: S.ref, sideSet: !!S.sideSet, scope: S.scope, prep: S.prep, prepDone: S.prepDone,
    kc: K ? { side: K.side, shared: K.shared, notices: K.notices, qc: Object.fromEntries(Object.entries(K.films).map(([r, f]) => [r, f.qc])) } : null }),
    strokes: S.clean.strokes };
}
const sameStrokes = (a, b) => a === b || (!!a && !!b && a.length === b.length && a.every((s, i) => s === b[i]));
const sameSnap = (a, b) => !!a && !!b && a.j === b.j && sameStrokes(a.strokes, b.strokes);
function undo() {
  if (HIST.length < 2) return;
  REDO.push(HIST.pop()); toSnap(HIST[HIST.length - 1]);
}
function redo() {
  if (!REDO.length || !S.img) return;
  if (redoStale()) { REDO.length = 0; syncHeader(); return prompt('Nothing to redo: the plan was changed after Undo.'); }
  const top = REDO.pop(); HIST.push(top); if (HIST.length > HIST_MAX) HIST.shift(); toSnap(top);
}
// the page put back to a snapshot (Undo, Redo); saved as a change (the plan in this browser, the patient list), never
// recorded as a new step
function toSnap(top) {
  const p = JSON.parse(top.j);
  const strokes0 = S.clean.strokes, view0 = JSON.stringify([S.clean.crop, S.prep]);
  Object.assign(S, { wf: p.wf, ll: p.ll || null, side: p.side, mirrored: p.mirrored, cranial: p.cranial, orient: p.orient, cal: p.cal, ballD: p.ballD, vca: p.vca, kres: p.kres || null, dia: diaIn(p.dia), lm: p.lm, rulers: p.rulers, angles: cleanAngles(p.angles), brand: brandIn(p), hemi: hemiIn(p.hemi), tplAll: p.tplAll,
    clean: { crop: null, ...p.clean, strokes: top.strokes }, masks: p.masks, caseType: p.caseType || 'primary', exist: p.exist || null, rv: p.rv || {}, lmSeq: p.lmSeq, ref: p.ref, sideSet: p.sideSet, scope: p.scope, prep: p.prep || { rot: 0 }, prepDone: !!p.prepDone });
  if (!sameStrokes(S.clean.strokes, strokes0)) rebuildClean();
  if (JSON.stringify([S.clean.crop, S.prep]) !== view0) fit();   // a crop or turn undone: show the film as it now is
  if (S.kc && p.kc) { const K = S.kc; K.side = p.kc.side; K.shared = p.kc.shared; K.notices = p.kc.notices; for (const [r, q] of Object.entries(p.kc.qc || {})) if (K.films[r]) K.films[r].qc = q; kcSideToFilms(); }
  S.tpl = S.tplAll[S.wf] || (S.tplAll[S.wf] = {}); if (S.active && !S.tpl[S.active]) S.active = null; S.arm = null; prompt('');
  $('wf').value = wfSel(); $('side').value = S.side; snapping = true; histLock = true; save(); histLock = false; panel(); snapping = false; redoBase = stateKey(); syncHeader(); draw();
}
// now: write the plan to this browser at once (a send to PACS must survive a reload straight after it)
function save(now = false) {
  if (!S.img) return;
  if (!histLock) { const sn = snapshot(); if (!sameSnap(HIST[HIST.length - 1], sn)) { HIST.push(sn); if (HIST.length > HIST_MAX) HIST.shift(); REDO.length = 0; } }   // a new change: nothing left to redo
  syncHeader();
  if (STANDALONE && !S.set.keepLocal) return;   // outside the hospital a plan is kept in the browser only when asked for
  keepSoon(now);
}
// The plan kept in this browser: written at once after a quiet spell, and at most every quarter second while changes come
// quickly (a held arrow key), then once more after the last one; also when the page is hidden or closed.
let keepT = 0, keptAt = 0;
function keepSoon(now) {
  if (now || (!keepT && performance.now() - keptAt > 250)) return keepNow();
  if (!keepT) keepT = setTimeout(keepNow, 250);
}
function keepNow() {
  if (keepT) { clearTimeout(keepT); keepT = 0; }
  if (!S.img || (STANDALONE && !S.set.keepLocal)) return;
  keptAt = performance.now();
  try { localStorage.setItem(S.key, JSON.stringify(plan())); } catch (e) { /* private window: plan just is not kept */ }
}
addEventListener('pagehide', () => { if (keepT) keepNow(); });
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden' && keepT) keepNow(); });
function syncHeader() { if (redoStale()) REDO.length = 0; $('undoBtn').disabled = !S.img || HIST.length < 2; $('redoBtn').disabled = !S.img || !REDO.length; $('fitBtn').disabled = !S.img; PJ.sync(); }
// changes not yet in a saved project: the history has moved on from where the film opened or was last saved
let histBase = null;
// rev: the history entry a save was made of (projects.js takes it when the save starts; Astra's page re-review, finding 5):
// changes made while the save ran stay unsaved
// What is "saved" is the whole project state (Astra's third page review, 13 Oct: the history snapshot leaves out the plan's
// rules, the window (contrast), display choices, notes and markers): a fingerprint of fullState() with the patient details
// typed, without the save time. A change to any of them, made at any time (also while a save runs), counts as unsaved.
// (round 2, Astra P1 3: sharing mode is in it too: turning it on changes what the patient list may hold, so it is saved)
function stateKey() { if (!S.img) return null; if (S.kc) return JSON.stringify([kcState(), S.patientIn || null, !!S.set.share]); const st = fullState(); delete st.saved; return JSON.stringify([st, S.patientIn || null, !!S.set.share]); }
function markSaved(rev) { histBase = rev === undefined ? stateKey() : rev; }
const revision = () => stateKey();
const isDirty = () => !!S.img && stateKey() !== histBase;
function restore() {
  if (STANDALONE && !S.set.keepLocal) return;
  let p = null; try { p = JSON.parse(localStorage.getItem(S.key) || 'null'); } catch (e) { p = null; }
  if (p && p.v === 1) applyPlan(p);
}
// Plans saved before 11 Oct 2026 named hip landmarks and implants by operated/other side: rename them by anatomy.
function migrate(p) {
  const o = p.side || 'R', n = other(o), map = { tdOp: 'td' + o, tdCo: 'td' + n, ltOp: 'lt' + o, ltCo: 'lt' + n, headOp: 'head' + o, headCo: 'head' + n, canal: 'canal' + o };
  if (p.lm) for (const [a, b] of Object.entries(map)) if (p.lm[a]) { p.lm[b] = p.lm[a]; delete p.lm[a]; }
  const hip = p.tplAll && p.tplAll.hip;
  if (hip) for (const k of ['cup', 'stem']) if (hip[k]) { hip[k + o] = hip[k]; delete hip[k]; }
  return p;
}
// a single knee film's own plan values as kept in a plan (anything else: none, the assumed values); the provenance of each
// (contract section 12c) and the films the plan-values question was answered for are kept with them
function kresOf(k) {
  if (!k || typeof k !== 'object') return null;
  const n = (v, lo, hi) => (Number.isFinite(v) && v >= lo && v <= hi ? v : null);
  const o = { femDistal: n(k.femDistal, 0, 25), tibial: n(k.tibial, 0, 25), slope: n(k.slope, -5, 15), tibRef: ['less', 'medial', 'worn'].includes(k.tibRef) ? k.tibRef : 'less', vcaSet: k.vcaSet === true, rot: n(k.rot, 0, 10),
    prov: PJ.normShared({ res: { prov: k.prov } }).res.prov, asked: typeof k.asked === 'string' && k.asked.length <= 80 ? k.asked : null };
  return o;
}
function applyPlan(p) {
  p = migrate(p);
  const wfOk = !p.wf || S.wfs.includes(p.wf) || (!!S.kc && !!WF[p.wf] && !WF[p.wf].slots.length);   // a knee case's long leg and skyline have no implants   // a plan for a workflow this account has no implants for: landmarks only
  if (!wfOk) S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `This plan is for ${WF[p.wf] ? WF[p.wf].label : p.wf}, which needs an implant range not open to your account.`;
  Object.assign(S, { wf: (wfOk && p.wf) || S.wf, side: p.side || S.side, cal: p.cal || S.cal, ballD: p.ballD || 25, lm: p.lm || {}, rulers: p.rulers || [], angles: cleanAngles(p.angles), vca: p.vca ?? 6, kres: kresOf(p.kres) });
  S.ll = llIn(p.ll, S.wf); if (S.ll) S.wf = llWf(S.ll.zone);   // r44: a long-leg plan opens on its zone (an r43 plan has none)
  S.hemi = S.kc && !S.ll ? {} : hemiIn(p.hemi); S.preBip = {};
  S.dia = diaIn(p.dia);   // r39: the diaphyseal plan's typed values (none in a plan from before)   // r38: the hips planned as a bipolar (none in a plan from before)
  if (!S.kc) S.brand = brandIn(p);   // r36: the case's implant maker (a knee case keeps it in its shared values)
  if (p.side && (p.sideSet || Object.keys(S.lm).length)) S.sideSet = true;
  // a ball found automatically is confirmed only if the plan says so (plans saved before 11 Oct did not; Astra, 11 Oct)
  if (S.cal && S.cal.method === 'ball' && S.cal.how === 'found automatically' && S.cal.confirmed !== true) S.cal = { ...S.cal, confirmed: false };
  S.tplAll = p.tplAll || { hip: {}, kneeAP: {}, kneeLat: {}, [S.wf]: p.tpl || {} };
  if (typeof p.mirrored === 'boolean') { S.mirrored = p.mirrored; S.orient = p.orient || { from: 'user', note: '' }; }
  if (p.cranial) S.cranial = p.cranial;
  // a pending-only account has no family at all (contract 9): every implant of the plan is kept aside below, as it is,
  // and the plan's own library id is written back with it (libIdNow), so the plan stays whole for whoever has the range
  S.keptLibraryId = typeof p.libraryId === 'string' && p.libraryId ? p.libraryId : null;
  // r40 (29 Oct 2026): a plan of another library id (r38b: or an id this library only adds sizes to is the same, PJ.LIB_SAME)
  // keeps each implant whose family outline (a vendor geomId, an Addis Ortho famGeo) is the one the plan was made with: its
  // geo, for a plan from before famGeo PJ.LIB_GEO of its library; only the changed families are placed again (before r40 every
  // implant was: Kevin's DWG changed the 130 long, 160-200 and 160-250 stems and the bipolar, nothing else)
  const perFam = !!S.lib.families.length && !PJ.libSame(p.libraryId, S.lib.libraryId), pg = perFam ? PJ.planGeo(p.libraryId, p.geo) : (p.geo || {});
  // implants of a range this account does not have are not shown, and a vendor implant whose outline changed since
  // (its geomId; the libraryId above covers the Addis Ortho range) is dropped like any changed implant. The ones not
  // shown are kept aside as they were (S.hidden, per workflow, with their geomId): not drawn, measured or recommended,
  // and plan() writes them back, so opening a film never saves a shorter plan (Fable, 12 Oct 2026)
  // an implant that does not fit the library it claims (Astra's page re-review, 13 Oct, finding 4): a family of another
  // kind than its place (a stem in a cup's place), a size, head or neck the family does not have. A project is refused
  // before it is opened (projects.js libraryProblems); a plan kept in this browser or opened as plan data leaves such an
  // implant out, with a note, instead of drawing it wrong (an unknown neck would otherwise count as no neck length)
  let gone = 0, changed = 0, wrong = 0, bipGone = 0, linerBad = 0, libChanged = 0; S.hidden = {}; S.hiddenGeo = {};
  const libLost = new Set(), bipNew = new Set();   // r40: the changed families taken off; the hips whose bipolar shell (and stem) were
  for (const [wf, o] of Object.entries(S.tplAll)) for (const [k, t] of Object.entries(o || {})) {
    const f = t && S.fam[t.fam];
    if (SLOT[k] && t && t.fam && !f) {
      gone++; (S.hidden[wf] || (S.hidden[wf] = {}))[k] = t;
      if (pg[t.fam]) S.hiddenGeo[t.fam] = pg[t.fam];   // r40: the outline it was made with (an Addis Ortho one from LIB_GEO too)
      delete o[k]; continue;
    }
    if (f && geoOf(f) && pg[t.fam] && pg[t.fam] !== geoOf(f)) { if (perFam) { libChanged++; libLost.add(f.label || f.id); } else changed++; delete o[k]; continue; }
    if (f && perFam && pg[t.fam] !== geoOf(f)) { libChanged++; libLost.add(f.label || f.id); delete o[k]; continue; }   // r40: an outline not known for the plan's library: placed again, as before r40
    if (!SLOT[k] || !f) { delete o[k]; continue; }
    { const sf = perFam && f.stems && t.stem && S.fam[f.stems]; if (sf && pg[sf.id] !== geoOf(sf)) { libChanged++; libLost.add(sf.label || sf.id); delete o[k]; continue; } }   // r40: its stem extension's outline too
    if (f.kind !== SLOT[k].kind || !(WF[wf] && WF[wf].slots.includes(k)) || !sizeOf(t)) { wrong++; delete o[k]; continue; }
    if (f.kind === 'stem' && !headKindsOf(f).includes(t.headKind)) setHead(t, f.sheetHead.kind, f.sheetHead.diameter);
    if (f.kind === 'stem' && !Object.prototype.hasOwnProperty.call(headSet(t), t.neck)) { wrong++; delete o[k]; continue; }
    if (f.kind === 'cup' && (t.liner !== undefined || t.userLiner !== undefined || linerOpts(t).length)) {   // r38b: the default liner instead of one the size does not offer (said), not a dropped cup
      const ok = linerTypesFor(t, null);
      if (!ok.length) { delete t.liner; delete t.userLiner; }
      else if (!(typeof t.liner === 'string' && ok.includes(t.liner))) { if (t.liner !== undefined) linerBad++; t.liner = ok.includes('neutral') ? 'neutral' : ok.includes('plus4') ? 'plus4' : ok[0]; delete t.userLiner; }
      else if (t.userLiner !== undefined && t.userLiner !== true) delete t.userLiner;
    }
    if (f.kind === 'stem' && t.bip) {   // r38: the bipolar shell on this stem: a range not open here keeps it as it is (not drawn)
      // r40: a new outline (an r38b plan: Kevin's DWG moved the head and the centre of rotation), or a size or head it does not
      // have, takes the shell AND its stem off: the stem was placed for that shell's centre of rotation, on its head (said)
      const bf = S.fam[t.bip.fam], bz = bf && bf.kind === 'bipolar' ? bf.sizes.find(z => z.size === t.bip.size) : null, ih = bz && bipHead(bf, bz);
      if (!bf) bipGone++;
      else if ((geoOf(bf) && pg[bf.id] && pg[bf.id] !== geoOf(bf)) || (perFam && pg[bf.id] !== geoOf(bf)) || !bz || t.headKind !== ih.kind || t.head !== String(ih.diameter)) { bipNew.add(SIDE[slotSide(k)] || k); delete o[k]; continue; }
    }
  }
  for (const [k, v] of Object.entries(p.geo || {})) if (!S.fam[k]) S.hiddenGeo[k] = v;   // a family not open here (a hidden component's stems too) keeps its version (Fable r20 P3)
  if (linerBad) S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `${linerBad} cup liner${linerBad > 1 ? 's are' : ' is'} not offered for that cup size: set to the neutral liner (or +4 neutral where there is none); check it.`;   // r38b
  if (libChanged) S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `The implant library has changed since this plan was saved: ${libChanged} implant${libChanged > 1 ? 's' : ''} of a changed family (${[...libLost].join('; ')}) ${libChanged > 1 ? 'were' : 'was'} taken off: place ${libChanged > 1 ? 'them' : 'it'} again. Calibration, landmarks and the other implants are kept.`;   // r40
  if (bipNew.size) { const bf = bipFams()[0]; S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `The bipolar shell on the ${[...bipNew].join(' and ')} hip is not the one this plan was made with${bf ? ` (${bipCredit(bf)}: ${bipHeadText(bf)}; the centre of rotation moved)` : ''}: the shell and its stem were taken off, so no old head, leg length or offset is shown. The hip is still a bipolar: press Auto template to place the stem and the shell again.`; }   // r40
  if (bipGone) S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + 'This plan has a bipolar shell from a range not open to your account: not shown, and its leg length and offset are measured at the head centre here; it is kept in the plan.';
  if (gone) S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `This plan also had ${gone} implant${gone > 1 ? 's' : ''} from a range not open to your account: not shown.`;
  if (changed) S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `${changed} implant${changed > 1 ? 's have' : ' has'} a new outline since this plan was saved: place ${changed > 1 ? 'them' : 'it'} again.`;
  if (wrong) S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `${wrong} implant${wrong > 1 ? 's do' : ' does'} not match the implant library (its place, size, head or neck) and ${wrong > 1 ? 'were' : 'was'} left out: place ${wrong > 1 ? 'them' : 'it'} again.`;
  S.tpl = S.tplAll[S.wf] || (S.tplAll[S.wf] = {});
  if (p.wl) S.wl = p.wl;
  if (p.scope) S.scope = p.scope;
  S.masks = p.masks || [];
  // revision and conversion (contract section 13): the case type, the film's existing-implant regions (only on the image they
  // were made on: a record of another image or size is dropped, and with it the canal records made against it) and the
  // revision record
  S.caseType = CASE_T.includes(p.caseType) ? p.caseType : 'primary';
  S.exist = p.exist && typeof p.exist === 'object' && Array.isArray(p.exist.regions) ? p.exist : null;
  S.rv = p.rv && typeof p.rv === 'object' && !Array.isArray(p.rv) ? p.rv : {};
  { const why = S.exist ? existFits(S.exist, imgSha(), S.img.cols, S.img.rows) : '';
    if (why) { S.exist = null; S.rv = { ...S.rv, canal: {} }; S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `This plan's existing implant marks were made on ${why}: they were removed, with the canal lines checked against them. Mark the existing implant again.`; } }
  S.exRevSeen = Math.max(S.exRevSeen || 0, exRev());
  { const b = brandSet(); if (b && b !== 'mixed' && S.tpl) { S.tpl = S.tplAll[S.wf] || (S.tplAll[S.wf] = {}); const gone = foreignOf(b).filter(x => x.wf !== 'kc'); if (gone.length) { dropForeign(b); S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `Implants of another maker than this case's (${b}) were removed: ${[...new Set(gone.map(x => x.label))].join('; ')}.`; } } }   // r36: a knee case's other film after a maker change
  S.clean = { strokes: [], crop: null, ...(p.clean || {}) }; S.lmSeq = p.lmSeq || []; S.ref = p.ref || null; rebuildClean();
  S.prep = p.prep || { rot: 0 }; S.prepDone = p.prepDone ?? Object.keys(S.lm).length > 0;   // plans from before this step have it done
  S.sent = p.sent ? { ...p.sent, at: new Date(p.sent.at) } : null;
  S.pending = p.pendingSend || null;   // an earlier send that got no answer (without its picture: it is looked up first)
  // the plan's own automatic rules come back with it (they are not this browser's preferences; Astra, 11 Oct)
  const ps = { ...(p.settings || {}) };
  // a plan saved before 14 Oct 2026 with the then default cup rule (teardrop) is re-planned by the new default; said
  // (Astra's review, finding 2: the plan's rule is set to the new one explicitly, whatever this browser's setting is)
  S.oldCupRule = false;
  if (ps.cupRule === 'teardrop' && !(String(p.saved || '') >= '2026-10-14')) { delete ps.cupRule; S.set.cupRule = 'anatomic'; S.oldCupRule = true; }
  if (S.oldCupRule) S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + OLD_CUP_NOTE;
  const diff = PLAN_KEYS.filter(k => ps[k] !== undefined && ps[k] !== S.set[k]);
  if (diff.length) { for (const k of diff) S.set[k] = ps[k]; S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `This plan's own rules are used: ${diff.map(k => `${k} ${ps[k]}`).join(', ')}.`; }
}
// The implant library a plan or project is made with (contract 9; Astra's page re-review, finding 7): this library's id;
// for a pending-only account (no family: t.addisortho.com's library.json then says "no-templates", an older one "") the
// id of the plan it opened, whose implants it keeps aside, else "no-templates", a valid id that names no template file
const libIdNow = () => (S.lib.families.length && S.lib.libraryId ? S.lib.libraryId : S.keptLibraryId || S.lib.libraryId || PJ.NO_LIBRARY);
const PLAN_KEYS = ['cupRule', 'cupIncl', 'tdLatMm', 'legTarget', 'mirrorRef', 'offsetTarget', 'stemRule'];
function plan() {
  S.tplAll[S.wf] = S.tpl;
  return { v: 1, saved: new Date().toISOString(), wf: wfBase(), ...(S.ll && !(S.kc && S.ll.zone === 'align' && S.ll.hs === S.side) ? { ll: { zone: S.ll.zone, hs: S.ll.hs } } : {}), side: S.side, sideSet: !!S.sideSet, scope: S.scope, mirrored: S.mirrored, orient: S.orient, cranial: S.cranial, cal: S.cal, ballD: S.ballD, vca: S.vca, ...(S.kres ? { kres: S.kres } : {}),
    lm: S.lm, lmSeq: S.lmSeq, ref: S.ref, prep: S.prep, prepDone: S.prepDone, tplAll: withHidden(S.tplAll), rulers: S.rulers, angles: S.angles, ...(S.kc ? {} : { brand: S.brand || null }), ...((S.kc && !S.ll) || !hemiOut() ? {} : { hemi: hemiOut() }), ...(S.dia ? { dia: S.dia } : {}), masks: S.masks, ...(S.kc ? {} : { caseType: S.caseType || 'primary' }), exist: S.exist || null, rv: S.rv || {}, clean: S.clean, sent: S.sent, pendingSend: S.pending ? { key: S.pending.key, ident: S.pending.ident, sop: S.pending.sop, series: S.pending.series } : null, wl: S.wl, library: S.lib && S.lib.version, libraryId: libIdNow(), geo: { ...S.hiddenGeo, ...planGeo() }, settings: Object.fromEntries(PLAN_KEYS.map(k => [k, S.set[k]])) };
}
// the implants on screen with the ones kept aside (applyPlan: a range not open to this account) put back unchanged; an
// implant placed in the same slot since takes its place
function withHidden(all) {
  const out = { ...all };
  for (const [wf, o] of Object.entries(S.hidden)) out[wf] = { ...o, ...(all[wf] || {}) };
  return out;
}
const hiddenHere = slot => !!(S.hidden[S.wf] && (slot ? S.hidden[S.wf][slot] : Object.keys(S.hidden[S.wf]).length));
// the outline version (geomId) of each vendor implant in the plan: applyPlan drops one whose outline has changed since
// r40: and an Addis Ortho family's famGeo (build_ranges.py), so a later library id keeps the families that did not change
const geoOf = f => PJ.famGeoOf(f);
function planGeo() {
  const g = {};
  for (const o of Object.values(S.tplAll)) for (const t of Object.values(o || {})) {
    const f = S.fam[t.fam]; if (geoOf(f)) g[t.fam] = geoOf(f);
    const sf = f && f.stems && t.stem && S.fam[f.stems]; if (geoOf(sf)) g[sf.id] = geoOf(sf);   // its stem extension's (r20)
    const bf = t.bip && S.fam[t.bip.fam]; if (geoOf(bf)) g[bf.id] = geoOf(bf);   // its bipolar shell's (r38)
  }
  return g;
}

// ---------- image ----------
function autoWindow() {   // 1st to 99th percentile of the pixel values
  const d = S.img.data, step = Math.max(1, Math.floor(d.length / 200000)), smp = [];
  for (let i = 0; i < d.length; i += step) smp.push(d[i]);
  smp.sort((a, b) => a - b); const lo = smp[Math.floor(smp.length * 0.01)], hi = smp[Math.floor(smp.length * 0.99)];
  S.wl = { c: (lo + hi) / 2, w: Math.max(1, hi - lo) };
}
// The cleaned copy of the film (brush strokes filled from the surroundings): for the display and the saved picture only.
function rebuildClean() {
  if (!S.img) return;
  S.cleanData = S.img.data ? cleanCopy(S.img.data, S.img.cols, S.img.rows, S.clean.strokes) : null;
  if (S.off) renderImage();
}
function renderImage() {
  const { cols, rows } = S.img, data = (!S.showOriginal && S.cleanData) || S.img.data, invert = S.img.invert !== S.invertView, lo = S.wl.c - S.wl.w / 2, k = 255 / S.wl.w;
  if (!S.off) S.off = document.createElement('canvas');
  S.off.width = cols; S.off.height = rows;
  const g = S.off.getContext('2d'), id = g.createImageData(cols, rows), px = id.data;
  for (let i = 0, n = cols * rows; i < n; i++) {
    let v = (data[i] - lo) * k; v = v < 0 ? 0 : v > 255 ? 255 : v; if (invert) v = 255 - v;
    px[4 * i] = px[4 * i + 1] = px[4 * i + 2] = v; px[4 * i + 3] = 255;
  }
  g.putImageData(id, 0, 0);
}
let wlRaf = 0;
function setWL(c, w) { S.wl = { c, w: Math.max(1, w) }; S.wlAuto = false; if (!wlRaf) wlRaf = requestAnimationFrame(() => { wlRaf = 0; renderImage(); draw(); }); }
// The film's area changed (window, panel, the phone's panel height): the canvas follows. A film still shown whole (not
// zoomed or moved since) is fitted again, so on a phone it always uses all the room the panel leaves.
function resize() {
  dpr = window.devicePixelRatio || 1;
  const r = $('stage').getBoundingClientRect(), w = Math.round(r.width * dpr), h = Math.round(r.height * dpr);
  cvRect = cv.getBoundingClientRect();
  if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; if (S.img && S.fitted) fit(); }
  draw();
}
// The film can be turned on screen to level the pelvis (Sami, 11 Oct: symmetry when the pelvis is tilted) and cropped
// to the area that matters. Both are display only: landmarks, implants and every measurement stay in the film's own
// pixels. rot: radians, about the film's centre.
const ROT = () => (S.prep && S.prep.rot) || 0;
const imgC = () => G.P(S.img.cols / 2, S.img.rows / 2);
const rotP = (p, a, c) => { const sn = Math.sin(a), co = Math.cos(a), dx = p.x - c.x, dy = p.y - c.y; return G.P(c.x + co * dx - sn * dy, c.y + sn * dx + co * dy); };
const area = () => S.clean.crop || { x0: 0, y0: 0, x1: S.img.cols, y1: S.img.rows };
const corners = a => [G.P(a.x0, a.y0), G.P(a.x1, a.y0), G.P(a.x1, a.y1), G.P(a.x0, a.y1)];
function fit() {   // the working area (crop, turned) fills the view
  const r = $('stage').getBoundingClientRect(), q = corners(area()).map(p => rotP(p, ROT(), imgC()));
  const x0 = Math.min(...q.map(p => p.x)), x1 = Math.max(...q.map(p => p.x)), y0 = Math.min(...q.map(p => p.y)), y1 = Math.max(...q.map(p => p.y));
  const k = Math.min(r.width / (x1 - x0), r.height / (y1 - y0)) * 0.96;
  if (!(k > 0) || !Number.isFinite(k)) return;   // no room yet (a hidden or zero-height film area)
  S.view = { k, x: (r.width - (x1 - x0) * k) / 2 - x0 * k, y: (r.height - (y1 - y0) * k) / 2 - y0 * k }; S.fitted = true;
}
const toS = p => { const q = ROT() ? rotP(p, ROT(), imgC()) : p; return G.P(q.x * S.view.k + S.view.x, q.y * S.view.k + S.view.y); };
const toI = p => { const q = G.P((p.x - S.view.x) / S.view.k, (p.y - S.view.y) / S.view.k); return ROT() ? rotP(q, -ROT(), imgC()) : q; };
// keep image point ip under screen point sp after a zoom
function anchor(ip, sp) { const s2 = toS(ip); S.view.x += sp.x - s2.x; S.view.y += sp.y - s2.y; }
// mm per pixel, or nothing: a long leg scaled from an AP that has changed since gives no lengths until scaled again
const mm = () => S.cal && !(S.cal.method === 'xfer' && kcXferStale()) ? S.cal.mmPerPx : null;
const pxOf = vmm => vmm / mm();

// ---------- landmarks ----------
const lmDefs = () => WF[S.wf].lm;
const ALL_LM = () => [...DIA_LM, ...hipLm('R', 'both', 'own', true), ...KNEE_AP_LM, ...KNEE_LAT_LM, ...KC_AP_LM, ...KC_LAT_EXTRA, ...KC_LONG_LM, ...KC_SKY_LM, ...SHOULDER_LM, LL_OK];
const lmDef = key => ALL_LM().find(d => d.key === key);
// what the surgery side is planned from: its own anatomy, or the other hip mirrored (both hips on the film only)
const refMode = () => (S.wf === 'hip' && S.scope === 'both' ? S.ref : 'own');
const refOther = () => refMode() === 'other';
// a landmark of the film on screen -> {p} | {a, b} | {c, r} or null
// r44: on a long-leg plan the femoral head marked in one zone serves the other (the same circle: the knee's hip centre, the hip's head)
// Fable r44 P2-1: only for the side it was marked on (S.ll.hs): after a side change the other hip's head is its own landmark
const lmPts = key => S.lm[key] || (S.ll && S.ll.hs === S.side ? S.lm[key === 'hip' ? 'head' + S.side : key === 'head' + S.side ? 'hip' : ''] : undefined);
function lmGeom(key) { return lmGeomOf(key, lmPts(key)); }
// a landmark from the given points (another film's plan): absent points are absent, never the film on screen's (Astra,
// 15 Oct, P1 1: a missing landmark of another film was silently read from the film on screen)
function lmGeomOf(key, L) {
  if (!Array.isArray(L)) return null;
  const def = lmDef(key);
  if (!def || L.length < NPTS[def.type]) return null;
  if (def.type === 'point') return { p: L[0] };
  if (def.type === 'line') return G.dist(L[0], L[1]) >= 5 ? { a: L[0], b: L[1] } : null;   // two clicks on one spot: no line
  const c = G.circle3(L[0], L[1], L[2]); return c ? { c: G.P(c.x, c.y), r: c.r } : null;
}
// a landmark that was clicked but gives no usable geometry (points on one spot, rim points in a line)
function lmBroken(key) {
  const L = S.lm[key], def = lmDef(key);
  return !!(L && def && L.length >= NPTS[def.type] && !lmGeom(key));
}
// a landmark that gives geometry, but not the geometry it should (a canal line across the femur, a head of 10 mm)
function lmProblem(key) {
  const g = lmGeom(key); if (!g) return '';
  if (/^td[RL]$/.test(key)) { const pr = tdProblem(); if (pr) return pr; }
  // knee landmarks the wrong way round (Fable, 11 Oct), checked with or without a scale (the knee case measures angles
  // without one): plateau front behind its back edge; femoral head below the knee or ankle above it
  if (key === 'platAnt' && S.wf === 'kneeLat') { const L = latKnee(false), ant = antDir(L), pp = lmGeom('platPost'); if (ant && pp && L.ac && L.pc && G.dot(G.sub(g.p, pp.p), ant) < 0) return 'is behind the back edge (the front of the knee is towards the anterior cortex): swap the two plateau edges'; }
  if ((key === 'hip' || key === 'ankle') && (S.wf === 'kneeAP' || S.wf === 'kneeLong')) { const kn = lmGeom('knee'); if (kn) { const d = G.dot(G.sub(key === 'hip' ? g.c : g.p, kn.p), cranVec()); if (key === 'hip' ? d <= 0 : d >= 0) return key === 'hip' ? 'is below the knee: is it the femoral head?' : 'is above the knee: is it the ankle?'; } }
  if (!mm()) return '';
  if (S.wf === 'diaph' && /^dia/.test(key)) { const pr = diaLmProblem(key, g); if (pr) return pr; }   // r39
  if (/^canal[RL]$/.test(key)) {
    const H = hipFrame(), v = H ? H.v : cranVec(), a = G.angleDeg(G.sub(g.b, g.a), v), off = Math.min(a, 180 - a);
    if (off > 30) return `runs ${Math.round(off)}° across the femur instead of down it: click the middle of the canal just below the lesser trochanter, then 10-15 cm lower`;
    if (G.dist(g.a, g.b) * mm() < 30) return 'too short: mark at least 3 cm down the canal';
  }
  if (/^head[RL]$/.test(key) || key === 'hip') { const d = 2 * g.r * mm(); if (d < 30 || d > 75) return `a head of ${f1(d)} mm is not plausible: click on the rim of the head`; }
  // hip landmarks on the wrong side of the pelvis, or the canal on the other femur (Fable, 11 Oct)
  const m_ = key.match(/^(lt|head|canal)([RL])$/);
  if (m_ && S.wf === 'hip' && S.scope !== 'one') {
    const sd = m_[2], H = hipFrame(sd);
    if (H && !H.single) {
      const M = G.mid(H.T, H.Tc), p = m_[1] === 'lt' ? g.p : m_[1] === 'head' ? g.c : G.mid(g.a, g.b);
      if (G.dot(G.sub(p, M), H.u) < 0) return `is on the ${SIDE[other(sd)]} side of the pelvis: click it on the ${SIDE[sd]} ${m_[1] === 'canal' ? 'femur' : 'side'}`;
      if (m_[1] === 'lt') { const o2 = lmGeom('lt' + other(sd)); if (o2 && G.dist(g.p, o2.p) * mm() < 50) return `the two lesser trochanters are only ${f1(G.dist(g.p, o2.p) * mm())} mm apart: mark each side's own`; }
    }
    if (m_[1] === 'canal' && !(refOther() && sd === S.side)) { const hd = lmGeom('head' + sd); if (hd && G.lineDist(hd.c, g.a, g.b) * mm() > 80) return `is ${f1(G.lineDist(hd.c, g.a, g.b) * mm())} mm from the ${SIDE[sd]} femoral head: is it on this femur?`; }
  }
  return '';
}

// ---------- templates ----------
// r36 (28 Oct 2026; Sami: "enable option of selecting brands ... do not blindly give recommendations"): the implant maker
// of the case, chosen by the surgeon (Addis Ortho, J&J, Zimmer Biomet, ...; or mixed, each part chosen by hand). Every
// family list and every automatic choice keeps to it; when more than one maker is open for a part and none is chosen,
// Auto template waits and asks: the page never starts from the first range in the library (that put an Addis Ortho knee on
// a Zimmer Biomet case). A plan saved before r36 takes the maker of the implants it holds.
// r36: the angle at a[1] between the arms to a[0] and a[2], degrees (0-180)
const cleanAngles = a => (Array.isArray(a) ? a.filter(x => Array.isArray(x) && x.length === 3 && x.every(p => p && Number.isFinite(p.x) && Number.isFinite(p.y))).slice(0, 50) : []);
const angleAt = a => { const u = G.sub(a[0], a[1]), v = G.sub(a[2], a[1]); return Math.acos(Math.max(-1, Math.min(1, G.dot(u, v) / ((Math.hypot(u.x, u.y) * Math.hypot(v.x, v.y)) || 1)))) * 180 / Math.PI; };
function allFams(kind) { return S.lib.families.filter(f => f.kind === kind); }
const brandOf = f => (f && (f.brand || makerOf(f))) || '';
const brandsFor = kind => [...new Set(allFams(kind).map(brandOf).filter(Boolean))];
const brandSet = () => ((S.kc ? S.kc.shared.brand : S.brand) || null);
const brandIn = o => (o && typeof o.brand === 'string' && o.brand.length <= 60 ? o.brand : null);   // a saved maker, checked
// Addis Ortho's primary families, where the page started before r36 (cementless cup, BE2 stems, XN knee): with Addis Ortho as
// the maker they are still the start, said as a default; another maker with more than one range of a part has no default
// (Fable, r36 P1 2: within a maker the page must not take the first range in the library either)
const MAKER_DEFAULT = { cup: 'chunli-cup', stem: 'chunli-be2-133s', 'tka-femur': 'chunli-tka-femur', 'tka-tibia': 'chunli-tka-tibia' };
// the ranges of a part open within the case's maker, one per group (a stem's offset versions are one group); tumour bodies
// and the PFR are chosen on their own and do not count
function famGroups(kind) {
  const out = new Map();
  for (const f of famsFor(kind)) { if (isPfr(f) || isTumourBody(f)) continue; const key = f.kind === 'stem' ? (stemVersions(f.id)[0] || f.id) : f.id; if (!out.has(key)) out.set(key, f); }
  return [...out.values()];
}
function brandInferred() {
  const seen = new Set(), add = id => { const f = id && S.fam[id]; if (f) seen.add(brandOf(f)); };
  for (const o of Object.values({ ...S.tplAll, [S.wf]: S.tpl })) for (const t of Object.values(o || {})) add(t && t.fam);
  if (S.kc) for (const z of Object.values(S.kc.shared.size || {})) add(z && z.fam);
  return seen.size === 1 ? [...seen][0] : seen.size > 1 ? 'mixed' : null;
}
const brandNow = () => brandSet() || brandInferred();
function brandFor(kind) { const b = brandNow(); if (b) return b; const bs = brandsFor(kind); return bs.length === 1 ? bs[0] : null; }
function famsFor(kind) { const all = allFams(kind), b = brandFor(kind); return !b || b === 'mixed' ? all : all.filter(f => brandOf(f) === b); }
// the family a part starts from when nothing was picked: only within a chosen (or the only) maker, else none
function defaultFam(kind) {
  const b = brandFor(kind); if (!b || b === 'mixed') return null;
  const gs = famGroups(kind); if (gs.length === 1) return gs[0]; if (!gs.length) return famsFor(kind)[0] || null;
  const d = S.fam[MAKER_DEFAULT[kind]]; return d && brandOf(d) === b && famsFor(kind).includes(d) ? d : null;
}
const isMakerDefault = f => !!f && famGroups(f.kind).length > 1 && !!MAKER_DEFAULT[f.kind] && (f.kind === 'stem' ? stemVersions(MAKER_DEFAULT.stem).includes(f.id) : MAKER_DEFAULT[f.kind] === f.id);   // a stem: any of the default's offset versions
const KIND_WORD = { cup: 'cup', stem: 'stem', 'tka-femur': 'femoral component', 'tka-tibia': 'tibial tray', 'humeral-body': 'proximal humerus' };
const wfKinds = () => [...new Set(((WF[S.wf] || {}).slots || []).map(sl => SLOT[sl].kind))].filter(kd => allFams(kd).length);
const brandsHere = () => [...new Set(wfKinds().flatMap(brandsFor))];
// what keeps Auto template from choosing implants: '' or what to do
function brandGate() {
  for (const kd of wfKinds()) {
    if (kd === 'cup' && S.wf === 'hip' && hemiOn(S.side)) continue;   // r38: a bipolar hip needs no cup
    const b = brandFor(kd), placed = Object.keys(S.tpl).some(sl => SLOT[sl] && SLOT[sl].kind === kd) || (!!S.kc && Object.values(S.kc.shared.size || {}).some(z => z && S.fam[z.fam] && S.fam[z.fam].kind === kd));
    if (!b) return `Choose the implant maker for this case first (${brandsHere().join(', ')}${S.wf === 'hip' ? ', or mixed' : ''}): the page does not pick one for you.`;
    if (b === 'mixed' && !chosenFam(kd) && !placed) return `Mixed makers: choose the ${KIND_WORD[kd] || kd} family first (tap it below, or add it by hand).`;
    if (b !== 'mixed' && !famsFor(kd).length) return `${b} has no ${KIND_WORD[kd] || kd} range open to your account: choose another maker${S.wf === 'hip' ? ' or mixed' : ''}.`;
    if (b !== 'mixed' && !placed && !chosenFam(kd) && !defaultFam(kd)) return `${b} has more than one ${KIND_WORD[kd] || kd} range open (${famGroups(kd).map(f => f.label).join('; ')}): choose the ${KIND_WORD[kd] || kd} family first${S.wf === 'hip' ? ' (tap it on the card below)' : ' (under the maker)'}.`;
  }
  return '';
}
// implants of another maker than b in this plan (and a knee case's shared sizes): [{where, slot, label}]
function foreignOf(b) {
  const out = [];
  for (const [wf, o] of Object.entries({ ...S.tplAll, [S.wf]: S.tpl })) for (const [sl, t] of Object.entries(o || {})) { const f = t && S.fam[t.fam]; if (f && brandOf(f) !== b) out.push({ wf, sl, label: `${f.label} ${t.size}` }); }
  if (S.kc) for (const [sl, z] of Object.entries(S.kc.shared.size || {})) { const f = z && S.fam[z.fam]; if (f && brandOf(f) !== b && !out.some(x => x.sl === sl)) out.push({ wf: 'kc', sl, label: `${f.label} ${z.size}` }); }
  return out;
}
function dropForeign(b) {
  for (const x of foreignOf(b)) {
    if (x.wf === 'kc') S.kc.shared.size[x.sl] = null;
    else { const o = x.wf === S.wf ? S.tpl : S.tplAll[x.wf]; if (o) delete o[x.sl]; if (S.kc && S.kc.shared.size[x.sl] && brandOf(S.fam[S.kc.shared.size[x.sl].fam]) !== b) S.kc.shared.size[x.sl] = null; }
  }
  if (S.active && !S.tpl[S.active]) S.active = null;
}
function setBrand(b) {
  const gone = b === 'mixed' ? [] : foreignOf(b);
  if (gone.length && !confirm(`Change the implant maker to ${b}? These implants of another maker are removed from the plan: ${[...new Set(gone.map(x => x.label))].join('; ')}.`)) { panel(); return; }
  if (b !== 'mixed') dropForeign(b);
  if (S.kc) S.kc.shared = { ...S.kc.shared, brand: b }; else S.brand = b;
  S.choice = b === 'mixed' ? {} : Object.fromEntries(Object.entries(S.choice).filter(([, id]) => S.fam[id] && brandOf(S.fam[id]) === b)); S.mirror = gone.length ? null : S.mirror;   // r43: a family of this maker picked first (a PFR, a knee type) is kept
  if (b !== 'mixed' && (S.wf === 'hip' || S.wf === 'shoulder') && !Object.keys(S.tpl).length && mm() && stepNow().id === 'tpl') { save(); return autoTemplate(); }   // chosen at the hip's implant step: the plan goes on (a knee waits for its button: 23 Oct rule; Fable r36 P2 3)
  save(); panel(); draw(); prompt(b === 'mixed' ? 'Mixed makers: choose each part\'s family, then Auto template.' : `Implant maker: ${b}. Press Auto template.`);
}
function brandRow() {
  const bs = brandsHere(); if (bs.length < 2 && !(S.wf === 'hip' && brandSet() === 'mixed')) return null;   // one maker open: nothing to choose
  const set = brandSet(), now = brandNow(), box = el('<div class="choice brand"></div>');
  box.appendChild(el(`<div class="ch-h">Implant maker for this case${set ? '' : now ? ' (from the implants in this plan)' : ''}</div>`));
  const g = el('<div class="ch-g" role="radiogroup" aria-label="Implant maker"></div>');
  for (const b of [...bs, ...(S.wf === 'hip' ? ['mixed'] : [])]) {   // mixed: hip only (a knee's femur and tray are one maker's)
    const lacks = b === 'mixed' ? [] : wfKinds().filter(kd => !allFams(kd).some(f => brandOf(f) === b)).map(kd => KIND_WORD[kd] || kd);
    const o = el(`<button type="button" class="ch-o" role="radio" aria-checked="${now === b}"><span class="ch-l">${b === 'mixed' ? 'Mixed (choose each part)' : esc(b)}</span>${lacks.length ? `<span class="note"> no ${esc(lacks.join(', '))} open</span>` : ''}</button>`);
    o.onclick = () => { if (now !== b || !set) setBrand(b); };
    g.appendChild(o);
  }
  box.appendChild(g);
  if (!now) box.appendChild(el('<p class="warnbox">Not chosen: Auto template waits for it. The page does not choose a maker for you.</p>'));
  if (now && now !== 'mixed' && S.wf !== 'hip') for (const kd of wfKinds()) {   // the knee: a maker with more than one range of a part (the hip has its card)
    const gs = famGroups(kd), placedF = Object.entries(S.tpl).filter(([sl]) => SLOT[sl] && SLOT[sl].kind === kd).map(([, t]) => t.fam);
    if (gs.length < 2) continue;
    const cur = (chosenFam(kd) || {}).id || placedF[0] || (defaultFam(kd) || {}).id, fg = el(`<div class="ch-g" role="radiogroup" aria-label="${esc(KIND_WORD[kd] || kd)} family"><div class="ch-t">${esc(cap(KIND_WORD[kd] || kd))}</div></div>`);
    for (const f of gs) {
      const o = el(`<button type="button" class="ch-o" role="radio" aria-checked="${cur === f.id}"><span class="ch-l">${esc(f.label)}</span>${isMakerDefault(f) ? '<span class="note"> default</span>' : ''}</button>`);
      o.onclick = () => {
        S.choice = { ...S.choice, [kd]: f.id };
        const sl = Object.keys(S.tpl).find(x => SLOT[x] && SLOT[x].kind === kd);   // placed already (Fable 17): changed as its own family menu does
        const sel = sl && document.querySelector(`.tpl[data-slot="${sl}"] .row select`);
        if (sel && S.tpl[sl].fam !== f.id && [...sel.options].some(op => op.value === f.id)) { sel.value = f.id; sel.dispatchEvent(new Event('change')); prompt(`${f.label} chosen: placed.`); return; }
        save(); panel(); prompt(sl ? `${f.label} chosen.` : `${f.label} chosen: press Auto template.`);
      };
      fg.appendChild(o);
    }
    box.appendChild(fg);
  }
  return box;
}
const hasKind = kind => allFams(kind).length > 0;   // is a range of this kind open to this account
// Ranges this account holds whose template file is not on the server, or failed the server's check (13 Oct 2026, design
// D with amendment 6): t.addisortho.com's library.json lists them as "pending" from its trusted range catalogue, filtered
// by the signed session's ranges. Shown greyed in the family lists and on the choice card; never selectable.
let PENDING = [];
function pendingList(lib) {
  const s = (v, n) => String(v || '').replace(/[\u0000-\u001f\u007f]+/g, ' ').trim().slice(0, n);
  return (Array.isArray(lib.pending) ? lib.pending : []).filter(p => p && typeof p.key === 'string' && typeof p.label === 'string' && !(lib.ranges || {})[p.key] && !(lib.families || []).some(f => f && f.rangeOwn === p.key))   // r43 (DIAG #5): ao-tumour's parts are served in ao-knee
    .slice(0, 24).map(p => ({ key: s(p.key, 30), label: s(p.label, 80), maker: s(p.maker, 80), kinds: Array.isArray(p.kinds) ? p.kinds.filter(k => typeof k === 'string') : [], why: p.why === 'failed' ? 'failed' : 'nofile' }));
}
const pendingOf = kind => PENDING.filter(p => p.kinds.includes(kind));
// the two wordings (Astra: a file that failed its check is not "awaiting the maker's files")
const pendingText = p => `${p.label}: ${p.why === 'failed' ? 'its template file failed its check (tell the administrator)' : 'no template file yet'}`;
// The opening screen lists them only when nothing at all can be placed (Sami, 17 Oct 2026: "on the opening first there is
// no point in adding these"); otherwise they stay greyed in the implant lists only.
function pendingNote() {
  const box = $('pendNote'); if (!box) return;
  box.textContent = ''; box.className = '';
  if (!PENDING.length || !S.noRange) return;
  box.className = 'warnbox';
  box.appendChild(el('<div><b>No implant range with template files is open to your account yet.</b> Your ranges:</div>'));
  const ul = document.createElement('ul'); for (const p of PENDING) { const li = document.createElement('li'); li.textContent = pendingText(p); ul.appendChild(li); }
  box.appendChild(ul);
  if (S.noRange) box.appendChild(el('<div>You can still open an X-ray or a saved project, calibrate it, mark the landmarks and measure. Implants can be placed once their template files are on the server.</div>'));
}
// the head systems a stem takes that this library has: a vendor stem lists its own (headKinds); an Addis Ortho stem
// takes the ceramic and CoCrMo heads
const headKindsOf = f => (f.headKinds || ['ceramic', 'cocr']).filter(k => (S.lib.heads[k] || {}).sizes);
function sizeOf(t) { const f = S.fam[t.fam]; return f && f.sizes.find(s => s.size === t.size); }
const isHalf = z => /\.5$/.test(String(z.size));
// sizes offered: half sizes only when the surgeon asks for them (not stocked); a plan's own size is always kept
function sizesShown(f, t) { return f.sizes.filter(z => S.set.halfSizes || !isHalf(z) || (t && t.fam === f.id && z.size === t.size)); }
function viewOf() { return WF[S.wf].view; }
function geomOf(t) {   // local outline + landmarks for this template in the current film view
  const f = S.fam[t.fam], s = sizeOf(t); if (!f || !s) return null;
  if (f.kind === 'tka-femur') return s.sides[S.side].views[viewOf()];
  if (f.kind === 'tka-tibia') return s.views[viewOf()];
  return s.views.AP;
}
// stem extension of a knee component (r20, SCHEMA-2026-10-17-r20.md): a child of its component (t.stem = {size}), never
// placed on its own; drawn at the component's stemPort in this view. Only the sizes the maker's sheet draws for that
// component size (stemSizes) are offered; anything else is not drawn and extWhy() says why (Astra, 17 Oct, P1).
function stemOf(t) {
  const f = t && S.fam[t.fam], z = t && sizeOf(t); if (!f || !z || !t.stem || extWhy(t)) return null;
  const sf = S.fam[f.stems]; return { f: sf, s: sf.sizes.find(q => q.size === t.stem.size) };
}
// ---------- tumour reconstruction (r22, 17 Oct 2026; Sami: proximal tibial case, "MRI/CT required") ----------
// A tumour body is a tibial family whose sizes carry reconstructionMm (joint line reference to the extension's distal end).
// The resection is planned only from the surgeon's MRI or CT extent and margin (never from the plain film); the page then
// lists every body + extension within 5 mm of it, none chosen for the surgeon. All lengths are projected on this film.
const isTumourBody = f => !!(f && (f.kind === 'tka-tibia' || f.kind === 'tka-femur') && f.sizes && f.sizes[0] && Number.isFinite(f.sizes[0].reconstructionMm));
// r27 (21 Oct 2026): the distal femoral replacement is a femoral tumour body: its joint line (y = 0) is the distal condyle
// surface (no insert on the femur, Sami), the resection runs proximally along the body's taper axis (stemPort.dir)
const isTumFemur = f => isTumourBody(f) && f.kind === 'tka-femur';
const tumSide = f => (isTumFemur(f) ? 'above' : 'below');
// r32 (23 Oct 2026): the proximal femoral replacement (Chunli PFR, vendor-families/ao-pfr.json) is a hip stem with tumour
// 'pfr'. It is placed, never sized: its head centre on the cup centre or the native head, its axis along the canal; no stem
// rule offers it. Its resection runs from the tip of the greater trochanter (MRI/CT extent + margin); its stack (body + gap +
// extension + gap + stem collar) from the 0 head centre's level on the body axis (tumOrigin). Both are measured along the
// body axis on this film, so the GT tip's height against that level is part of the plan (Fable's design read, 23 Oct, 1).
function isPfr(f) { return !!(f && f.kind === 'stem' && f.tumour === 'pfr'); }
function pfrIn(sd) { return typeof S !== 'undefined' && S.wf === 'hip' && !!S.tpl && !!S.tpl['stem' + sd] && !!S.fam && isPfr(S.fam[S.tpl['stem' + sd].fam]); }
function pfrFrame(t) {   // {o, d} in the image: the 0 head centre's level on the body axis, and the axis distally
  const g = geomOf(t); if (!g || !g.tumOrigin || !g.stemPort) return null;
  return { o: xf(t)(g.tumOrigin), d: dirOf(t, g.stemPort.dir) };
}
// the plan on this film, mm along the body axis from tumOrigin (+ distal): sGT the GT tip, sPlan the planned osteotomy
function pfrPlan(t, side, gtKey = 'gt' + side) {   // r33: the shoulder's tuberosity (gtub) too
  const tp = tumPlan(t), F = pfrFrame(t), gt = lmGeom(gtKey), k = mm(), out = { tp, gt: !!gt };
  if (!F || !k) return out;
  if (gt) out.sGT = G.dot(G.sub(gt.p, F.o), F.d) * k;
  if (tp.ok && gt) out.sPlan = out.sGT + tp.resection;
  return out;
}
function pfrOptions(f, sPlan) {   // every length and stem collar within 5 mm of the planned osteotomy, closest first
  const out = [];
  for (const z of f.sizes) for (const c of collarsOf(f)) { const tot = z.reconstructionMm + c; if (Math.abs(tot - sPlan) <= 5) out.push({ z, c, tot }); }
  return out.sort((a, b) => Math.abs(a.tot - sPlan) - Math.abs(b.tot - sPlan) || a.z.extensionMm - b.z.extensionMm);
}
function pfrPlace(slot) {   // placed, not sized: the axis along the canal (when marked), the head centre on the cup centre or the native head
  const t = S.tpl[slot], side = slotSide(slot);
  if (!alignStem(t, side)) t.angle = filmTurn();
  const lp = levelPlan(side), target = lp ? lp.target : stemStart(side);
  if (target) { const H = corAt(t); t.x += target.x - H.x; t.y += target.y - H.y; }   // r38: with a bipolar shell its outer centre goes there
  S.notes['fit' + side] = null; delete S.notes['stemPlan' + side]; S.notes['offset' + side] = '';
  return null;
}
// how far the chosen neck puts the head centre above the neck-0 level the stack is measured from (mm, along the body axis)
function pfrNeckRise(t) { const g = geomOf(t); return g ? -(headSet(t)[t.neck] ?? 0) * g.neckDir[1] : 0; }
const pfrLevelTxt = t => { const r = pfrNeckRise(t); return Math.abs(r) < 0.05 ? 'its head centre level (neck 0)' : `its neck-0 head centre level (with neck ${neckLabel(t, t.neck)} the head centre sits ${f1(Math.abs(r))} mm ${r > 0 ? 'higher' : 'lower'})`; };
// r33 (24 Oct 2026): the proximal humeral replacement (Chunli, vendor-families/ao-phr.json): kind humeral-body, tumour 'phr'.
// Placed with its head centre (the middle of its head face: hemispherical heads) on the native head and its axis along the
// humeral shaft; the resection from the tip of the greater tuberosity; the stack as the PFR's (extension 35, stem collar L1).
function isPhr(f) { return !!(f && f.kind === 'humeral-body' && f.tumour === 'phr'); }
function phrPlace(slot) {
  const t = S.tpl[slot], f = S.fam[t.fam], g = geomOf(t), sh = lmGeom('humShaft'), hd = lmGeom('humHead');
  if (sh && g && g.axis) { let up = G.unit(G.sub(sh.b, sh.a)); if (G.dot(up, cranVec()) < 0) up = G.mul(up, -1); const ax = dirOf(t, [g.axis[1][0] - g.axis[0][0], g.axis[1][1] - g.axis[0][1]]); t.angle = normA(t.angle + G.turn(ax, up)); }
  else t.angle = filmTurn();
  if (hd) { const H = xf(t)([0, 0]); t.x += hd.c.x - H.x; t.y += hd.c.y - H.y;
    if (!t.userHead && f.humHeads && mm()) { const dn = 2 * hd.r * mm(); t.head = String(f.humHeads.reduce((b, q) => (Math.abs(q.d - dn) < Math.abs(b.d - dn) ? q : b)).d); } }
  if (!t.head && f.humHeads) t.head = String(f.humHeads[Math.floor(f.humHeads.length / 2)].d);
  return null;
}
function shoulderResults(R, k) {
  const hd = lmGeom('humHead'); R.push([`Native shoulder (${SIDE[S.side]})`]);
  if (hd && k) R.push(['Humeral head diameter', `${f1(2 * hd.r * k)} mm`]);
  if (!lmGeom('humShaft')) R.push(['Humeral shaft axis', 'not marked: the implant is turned to the film, not to the shaft', 'warn']);
  const t = S.tpl.humerus, f = t && t.on !== false && S.fam[t.fam]; if (!isPhr(f) || !k) return;
  const z = sizeOf(t), g = geomOf(t);
  R.push(['Proximal humerus']); R.push([f.label, z.label || z.size]);
  if (g.headSource) R.push(['Head centre from', g.headSource, 'warn']);
  R.push(['Head', t.head ? `${t.head} mm humeral head (Chunli's standard heads, hemispherical; which fit the tumour upper end is not confirmed)` : 'not chosen', 'warn']);
  if (hd) { const H = xf(t)([0, 0]), d = G.dist(H, hd.c) * k; R.push(['Head centre vs native', `${f1(d)} mm apart (as placed)`, d > 3 ? 'warn' : '']);
    if (t.head) { const dd = +t.head - 2 * hd.r * k; R.push(['Head size vs native', `${dd >= 0 ? '+' : ''}${f1(dd)} mm`]); } }
  phrRows(R, t);
}
// the resection rows of a PHR (as pfrRows, from the tip of the greater tuberosity)
function phrRows(R, t) {
  const f = S.fam[t.fam], z = sizeOf(t), P = pfrPlan(t, S.side, 'gtub'), tp = P.tp, c = tumCollar(t), st = stemOf(t);
  R.push(['Tumour resection (proximal humerus)']);
  if (!tp.ok) R.push(['Resection', `not planned: ${tp.why}`, 'warn']);
  else {
    R.push(['Tumour extent', `${f1(tp.extent)} mm below the tip of the greater tuberosity, from the ${tp.src} (entered by the surgeon)`]);
    R.push(['Margin', `${f1(tp.margin)} mm (your choice)`]);
    R.push(['Resection length', `${f1(tp.resection)} mm below the tip of the greater tuberosity`]);
  }
  if (!P.gt) R.push(['Greater tuberosity', 'not marked: mark its tip; the resection is measured from it', 'warn']);
  else if (Number.isFinite(P.sGT)) R.push(['GT tip vs head centre', `the tuberosity tip is ${f1(Math.abs(P.sGT))} mm ${P.sGT >= 0 ? 'below' : 'above'} this implant's head centre level, along its axis (this film, as placed)`]);
  if (c === null) R.push(['Reconstruction', `${z.label || z.size}: ${fmm(z.reconstructionMm)} mm below its head centre level without the stem collar; choose an option or a stem (${tumParts(z, null)})`, 'warn']);
  else {
    const tot = z.reconstructionMm + c, d = Number.isFinite(P.sPlan) ? tot - P.sPlan : null;
    R.push(['Reconstruction', `${z.label || z.size} + stem collar ${c}: ${tumParts(z, c)} = ${fmm(tot)} mm below its head centre level`]);
    if (Number.isFinite(P.sGT)) R.push(['Osteotomy of this stack', `${fmm(tot - P.sGT)} mm below the tuberosity tip${d !== null ? ` (${d >= 0 ? '+' : ''}${fmm(d)} mm against the planned resection)` : ''}`, d !== null && Math.abs(d) > 5 ? 'warn' : '']);
  }
  R.push(['Stem', st ? `${st.s.label || st.s.size}: ${st.s.diameterMm} mm below its collar, ${f1(st.s.drawnLengthMm - (c || 0))} mm beyond its collar as drawn; check the canal width with the ruler` : 'not chosen yet: choose the universal stem; check its diameter against the canal with the ruler', st ? '' : 'warn']);
  R.push(['Taper junctions', pfrGapNote(z, true), 'warn']);
  for (const a of f.assumptions || []) R.push(['Assumed (not confirmed by Chunli)', a, 'warn']);
  R.push(['Placement', 'placed, not sized: the head centre on the native head centre, the axis along the humeral shaft; drag and turn to fine-tune', 'warn']);
  R.push(['Scale', 'lengths use this film\'s ball; the resection level lies further from it, where magnification may differ (not validated)', 'warn']);
}
// r37: Chunli confirmed (Kevin, 27 Oct 2026) that the PFR's Ø24 tapers and their 2.25 mm gap are the knee's; the shoulder's Ø22
// system has no such answer, so its row keeps the gap as taken from the Ø24 figure
const pfrGapNote = (z, phr) => `Each taper junction is taken as ${z.junctionGapMm} mm open after impaction (${phr ? "Chunli's figure for its Ø24 tapers, confirmed for the knee and the femoral replacement; taken for this Ø22 system, not confirmed" : "Chunli: the same Ø24 taper and gap as the knee, confirmed 27 Oct 2026"}): ${z.junctions} junction${z.junctions > 1 ? 's' : ''}, so the stack can sit up to about ${f1(z.junctions * 0.25)} mm longer or shorter: check the length with the trial.`;
function tumAxis(t) {   // {p0, dir} in the template's frame: where the body's axis crosses the joint line, and up/down it into the bone
  const g = geomOf(t), z = sizeOf(t);
  if (!g || !g.stemPort || !z || !Number.isFinite(z.reconstructionMm)) return { p0: [0, 0], dir: [0, 1] };
  const d = g.stemPort.dir, p = g.stemPort.p; return { p0: [p[0] - d[0] * z.reconstructionMm, p[1] - d[1] * z.reconstructionMm], dir: d };
}
const tumAt = (t, mm) => { const a = tumAxis(t); return [a.p0[0] + a.dir[0] * mm, a.p0[1] + a.dir[1] * mm]; };
const TUM_SRC = { MRI: 'MRI', CT: 'CT' };
// ---------- r39 (29 Oct 2026): the Chunli diaphyseal (segmental bone defect) plan, the measurement half ----------
// DESIGN-2026-10-29-diaphyseal.md; governing spec: REVIEW-FABLE-2026-10-29-diaphyseal-design.md, "Build now". No implant is
// placed and nothing is autoplanned. The surgeon types the tumour's near and far extents from the MRI or CT (from a reference
// level he marks on the film), and his margin; the page draws the two osteotomy levels along the bone axis of the calibrated
// film and reports the defect, the bone left each side, the canal widths marked at stations, the tips of the stems he typed
// against the reference level, the far joint and a physis, and which of Chunli's two defect ranges the defect falls in. It
// never chooses the margin, a stem diameter, a cement mantle or a rounding direction: every value is shown with Chunli's
// neighbouring steps and their consequence, never clamped. A schematic is drawn only from sizes the surgeon typed. Chunli's
// tables: DIA_TYPES (slides 18-19); which length is L1/L2/L3, how the parts add up to the defect, the mantle and press-fit,
// the minimum bone each side and whether a stem may cross a physis are not stated by Chunli (asked: KEVIN-REQUEST). No
// library family: the libraryId and every geomId are unchanged; the plan is offered with the Chunli tumour range (ao-tumour).
const DIA_SRC = 'Chunli, Segmental Bone Defect Prosthesis, range tables (slides 18-19), received 29 Oct 2026';
const DIA_TYPES = {
  III: { defect: [40, 165], fix: 'split body joined by screws, no cross-lock', A: { model: 'SZ-A(III)', L1: [20, 200], phi: [9, 40] }, B: { model: 'SZ-B(I)', L1: [10, 130], phi: [9, 40] } },
  VI: { defect: [50, 310], fix: 'tapered press-fit intercalary segments (JGD(I)), stem B cross-locked (LD(I) screw)', A: { model: 'SZ-A(IV)', L1: [20, 200], phi: [9, 39] }, B: { model: 'SZ-B(VI)', L1: [10, 130], phi: null } },
};
const DIA_TIP_MM = 30;   // the page's stem-tip warning (as the tumour plans', tumRowsOf), not a Chunli minimum
const DIA_SAFETY = 'Measurements for a Chunli order request, not a plan of parts. The page does not choose the margin, the stem diameter, the cement mantle or the rounding; it reports what you measured and typed, projected on this film and scaled by its ball, which sits at the joint while the lesion is mid-shaft (magnification there not validated). Canal widths on a plain film are a check, not a measurement: take them from the CT. Chunli confirms the configuration; nothing here is a part number.';
const DIA_NOT_STATED = ['which length is L1, L2 and L3 on each part, and what the second "L2" (10-36, ±0.45) of SZ-A(III) is', 'how the parts add up to the defect (Type III: defect = L3 is our reading of the table; Type VI: stem A L2, segments, stem B L2 and shims)', 'the cement mantle of stem A and the press-fit of the porous stem', 'the minimum bone and stem length each side, and whether a stem may cross an open physis', 'a stem diameter for Type VI stem B SZ-B(VI) (its Φ1 3-7 mm is probably the locking-screw hole)', 'whether any part is made per patient from the CT'];
const DIA_CAN = ['diaCanN1', 'diaCanN2', 'diaCanN3', 'diaCanF1', 'diaCanF2', 'diaCanF3'];
const DIA_LM = [
  { key: 'diaAxis', short: 'bone axis', type: 'line', label: 'Bone axis (femur or tibia)', hint: 'the middle of the shaft: one point towards the reference end, one towards the far end (the levels are measured along it)' },
  { key: 'diaRef', short: 'near joint line', type: 'point', label: 'Near joint line (the reference level of the MRI/CT extents)', hint: 'the middle of the joint line at the reference end: measure the tumour extents on the MRI or CT from this same joint line (re-measure them if you used another point, a trochanter or malleolus tip); the levels, the bone left and the stem tip on this side are counted from it' },
  { key: 'diaFar', short: 'far joint', type: 'point', label: 'Far joint line (the other end of the bone)', hint: 'the middle of the joint line at the other end: the bone left beyond the far osteotomy and the far stem tip are measured to it' },
  { key: 'diaPhysN', short: 'physis, ref. side', type: 'point', label: 'Open physis, reference side', hint: 'only for an open physis: its middle', optional: true },
  { key: 'diaPhysF', short: 'physis, far side', type: 'point', label: 'Open physis, far side', hint: 'only for an open physis: its middle', optional: true },
  ...[1, 2, 3].map(i => ({ key: 'diaCanN' + i, short: `canal ref ${i}`, type: 'line', label: `Canal width, reference-side fragment, station ${i}`, hint: 'inner cortex to inner cortex, straight across the canal, where the stem will sit', optional: true })),
  ...[1, 2, 3].map(i => ({ key: 'diaCanF' + i, short: `canal far ${i}`, type: 'line', label: `Canal width, far fragment, station ${i}`, hint: 'inner cortex to inner cortex, straight across the canal, where the stem will sit', optional: true })),
];
const diaRangeOpen = () => !!(S.lib && S.lib.families.some(f => f.rangeOwn === 'ao-tumour' || f.range === 'ao-tumour'));
const DIA_EMPTY = Object.freeze({ bone: '', view: '', src: '', refEnd: '', ...Object.fromEntries(Object.keys(PJ.DIA_NUM).map(k => [k, null])) });
const diaU = () => S.dia || DIA_EMPTY;
// a plan's diaphyseal values, field by field (a value out of range is dropped, not the plan); null when there are none
function diaIn(v) {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return null;
  const o = { ...DIA_EMPTY };
  for (const [k, l] of Object.entries(PJ.DIA_ENUM)) if (l.includes(v[k])) o[k] = v[k];
  for (const [k, [lo, hi]] of Object.entries(PJ.DIA_NUM)) if (Number.isFinite(v[k]) && v[k] >= lo && v[k] <= hi) o[k] = v[k];
  return o;
}
const latFilm = () => S.wf === 'kneeLat' || (S.wf === 'diaph' && diaU().view === 'LAT');
const DIA_VIEW = { AP: 'AP', LAT: 'lateral' };
const diaViewTxt = () => DIA_VIEW[diaU().view] || 'view not chosen';
const diaRefTxt = () => `(the near joint line, ${({ prox: 'at the proximal end', dist: 'at the distal end' }[diaU().refEnd] || 'end not said')})`;
// the plan from the typed values alone: {ok, why, src, ext1, ext2, margin, cutN, cutF, defect} (mm from the reference level)
function diaPlan() {
  const u = diaU(), src = TUM_SRC[u.src] ? u.src : '';
  if (!src) return { ok: false, why: 'enter where the tumour extents come from (MRI or CT): the plain film does not show them' };
  if (!Number.isFinite(u.ext1) || !Number.isFinite(u.ext2)) return { ok: false, src, why: `enter the tumour's near and far ends, in mm from the reference level, measured on the ${src}` };
  if (!(u.ext2 > u.ext1)) return { ok: false, src, why: 'the far end of the tumour must be further from the reference level than its near end' };
  if (!Number.isFinite(u.margin)) return { ok: false, src, why: 'enter your margin (mm)' };
  const cutN = u.ext1 - u.margin, cutF = u.ext2 + u.margin;
  return { ok: true, src, ext1: u.ext1, ext2: u.ext2, margin: u.margin, cutN, cutF, defect: cutF - cutN };
}
// the bone axis on this film: o, the reference level projected on the axis; u, the unit along it towards the far joint; n,
// across it; farPx, the far joint's distance along it (px) or null
function diaFrame() {
  const ax = lmGeom('diaAxis'), r = lmGeom('diaRef'); if (!ax || !r) return null;
  let u = G.unit(G.sub(ax.b, ax.a)); const o = G.project(r.p, ax.a, u), fr = lmGeom('diaFar');
  const toward = fr ? fr.p : G.dist(ax.a, o) > G.dist(ax.b, o) ? ax.a : ax.b;
  if (G.dot(G.sub(toward, o), u) < 0) u = G.mul(u, -1);
  return { o, u, n: G.P(-u.y, u.x), farPx: fr ? G.dot(G.sub(fr.p, o), u) : null };
}
const diaAt = (F, s, k) => G.add(F.o, G.mul(F.u, s / k));   // the point s mm from the reference level along the axis
const diaS = (F, p, k) => G.dot(G.sub(p, F.o), F.u) * k;     // a point's level, mm from the reference level along the axis
function diaLmProblem(key, g) {
  const k = mm(), F = diaFrame();
  if (key === 'diaAxis' && G.dist(g.a, g.b) * k < 50) return 'too short: mark the middle of the shaft at two points at least 5 cm apart';
  if (key === 'diaFar' && F && Math.abs(F.farPx) * k < 100) return `is only ${f1(Math.abs(F.farPx) * k)} mm from the reference level along the axis: is it the other end of the bone?`;
  if (DIA_CAN.includes(key)) {
    const w = G.dist(g.a, g.b) * k;
    if (w < 3 || w > 45) return `a canal ${f1(w)} mm wide is not plausible: click the inner cortex on each side`;
    if (F) { const a = G.angleDeg(G.sub(g.b, g.a), F.u); if (Math.min(a, 180 - a) < 65) return 'runs along the bone instead of across it: click the inner cortex on each side, straight across the canal'; }
  }
  return '';
}
const diaStep = (x, st) => [Math.floor(x / st + 1e-9) * st, Math.ceil(x / st - 1e-9) * st];
const sgnMm = d => `${d >= 0 ? '+' : ''}${fmm(Math.round(d * 100) / 100)}`;
// the Chunli types whose stated defect range holds x
const diaTypesAt = x => Object.keys(DIA_TYPES).filter(t => x >= DIA_TYPES[t].defect[0] && x <= DIA_TYPES[t].defect[1]);
// which stem diameter (Φ1) ranges of Chunli's tables hold a width w: {inn, out} as "III stem A SZ-A(III) 9-40"
function diaPhi(w) {
  const inn = [], out = [];
  for (const [t, T] of Object.entries(DIA_TYPES)) for (const s of ['A', 'B']) { const r = T[s].phi; if (!r) continue; (w >= r[0] && w <= r[1] ? inn : out).push(`${t} stem ${s} ${T[s].model} ${r[0]}-${r[1]}`); }
  return { inn, out };
}
const diaPhiTxt = w => { const { inn, out } = diaPhi(w), [lo, hi] = diaStep(w, 1); return `Chunli 1 mm steps ${lo === hi ? lo : `${lo} / ${hi}`}; ${inn.length ? `inside Φ1 of ${inn.join(', ')}` : 'outside every stem Φ1 in Chunli\'s tables'}${inn.length && out.length ? `; outside ${out.join(', ')}` : ''}`; };
const diaL1Txt = l => { const [lo, hi] = diaStep(l, 5), inn = [], out = []; for (const [t, T] of Object.entries(DIA_TYPES)) for (const s of ['A', 'B']) { const r = T[s].L1; (l >= r[0] && l <= r[1] ? inn : out).push(`${t} stem ${s} ${r[0]}-${r[1]}`); }
  return `Chunli 5 mm steps ${lo === hi ? lo : `${lo} (${sgnMm(lo - l)} mm: the tip that much nearer the osteotomy) / ${hi} (${sgnMm(hi - l)} mm: that much nearer the joint)`}; ${inn.length ? `inside L1 of ${[...new Set(inn)].join(', ')}` : 'outside every stem L1 in Chunli\'s tables'}${inn.length && out.length ? `; outside ${[...new Set(out)].join(', ')}` : ''} (which length L1 is, and which stem sits in which fragment: not stated by Chunli)`; };
// everything the rows, the card, the drawing and the order sheet use, from one calculation
function diaCalc() {
  const u = diaU(), k = mm(), P = diaPlan(), F = k ? diaFrame() : null, C = { u, k, P, F, far: null, stations: [], stems: [], phys: [] };
  if (!F) return C;
  C.far = F.farPx !== null ? F.farPx * k : null;
  for (const [key, side] of [['diaPhysN', 'N'], ['diaPhysF', 'F']]) { const g = lmGeom(key); if (g) C.phys.push({ key, side, s: diaS(F, g.p, k) }); }
  for (const key of DIA_CAN) {
    const g = lmGeom(key); if (!g || lmProblem(key)) continue;
    const s = diaS(F, G.mid(g.a, g.b), k), side = key[6], at = !P.ok ? side : s < P.cutN ? 'N' : s > P.cutF ? 'F' : 'D';
    C.stations.push({ key, side, i: +key[7], w: G.dist(g.a, g.b) * k, s, at });
  }
  if (P.ok) for (const side of ['N', 'F']) {
    const d = u['d' + side], l = u['l' + side]; if (!Number.isFinite(d) && !Number.isFinite(l)) continue;
    const tip = Number.isFinite(l) ? (side === 'N' ? P.cutN - l : P.cutF + l) : null;
    const toJoint = tip === null ? null : side === 'N' ? tip : C.far === null ? null : C.far - tip;
    const ph = C.phys.find(q => q.side === side), toPhys = tip === null || !ph ? null : side === 'N' ? tip - ph.s : ph.s - tip;
    const own = C.stations.filter(q => q.at === side), narrow = own.length ? Math.min(...own.map(q => q.w)) : null;
    C.stems.push({ side, d, l, tip, toJoint, toPhys, narrow });
  }
  return C;
}
const DIA_FRAG = { N: 'reference-side fragment', F: 'far fragment', D: 'resected segment' };
const DIA_JOINT = { N: 'the near joint line (your reference level)', F: 'the far joint line' };   // Fable r39 P2-1: the reference level is the near joint line
// the results rows (r39), in the tumour rows' style (tumRowsOf): the plan, the bone left, the canal, the stems, every assumption
function diaRows(R, k) {
  const C = diaCalc(), u = C.u, P = C.P;
  R.push(['Diaphyseal defect (Chunli segmental prosthesis): measurements for an order request']);
  R.push(['Not a plan of parts', 'the page does not choose the margin, the stem diameter, the cement mantle or the rounding; Chunli confirms the configuration and sizes before manufacture; nothing here is a part number', 'warn']);
  R.push(['Bone and film', `${u.bone || 'bone not chosen (femur or tibia)'}, ${diaViewTxt()} film`, u.bone && u.view ? '' : 'warn']);
  if (!P.ok) R.push(['Resection', `not planned: ${P.why}`, 'warn']);
  else {
    R.push(['Tumour extent', `${f1(P.ext1)} to ${f1(P.ext2)} mm from the reference level ${diaRefTxt()}, from the ${P.src} (entered by the surgeon)`]);
    R.push(['Margin', `${f1(P.margin)} mm at each end (your choice)`]);
    R.push(['Osteotomy levels', `${f1(P.cutN)} and ${f1(P.cutF)} mm from the reference level, along the bone axis`, P.cutN <= 0 ? 'bad' : '']);
    if (P.cutN <= 0) R.push(['Reference side', 'the near osteotomy is at or beyond the reference level: not a diaphyseal (intercalary) resection on these values', 'bad']);
    R.push(['Defect length', `${fmm(P.defect)} mm (far minus near osteotomy level, from your typed values; the same on every film)`]);
    const ty = diaTypesAt(P.defect), [lo, hi] = diaStep(P.defect, 5), tw = x => { const t = diaTypesAt(x); return t.length ? `Type ${t.join(' and ')}` : 'outside both ranges'; };
    if (!ty.length) R.push(['Chunli range', `${fmm(P.defect)} mm is outside Chunli's stated range (Type III 40-165 mm, Type VI 50-310 mm): not orderable from these tables; ask Chunli`, 'bad']);
    else {
      for (const t of ty) R.push([`Type ${t} (defect ${DIA_TYPES[t].defect[0]}-${DIA_TYPES[t].defect[1]} mm)`, `qualifies by its stated range: ${DIA_TYPES[t].fix}`]);
      if (ty.length > 1) R.push(['Type III or VI', 'both qualify for a defect of 50-165 mm: the choice is yours with Chunli; the page does not choose']);
      R.push(['Chunli 5 mm steps', lo === hi ? `${lo} mm is a step (${tw(lo)})` : `${lo} mm (${sgnMm(lo - P.defect)} mm against the defect: the gap shorter, the limb about that much shorter unless the bone is cut longer; ${tw(lo)}) or ${hi} mm (${sgnMm(hi - P.defect)} mm: the limb about that much longer, or resect that much more; ${tw(hi)}); defect = Type III L3 is our reading of the table, and how Type VI's parts add up to the defect is not stated by Chunli`, 'warn']);
    }
  }
  if (!k) { R.push(['Film', 'calibrate first: the levels are drawn and the bone measured on the calibrated film', 'warn']); return; }
  const F = C.F;
  if (!F) { R.push(['Film', 'mark the bone axis and the reference level: the levels are drawn and the bone left measured along the axis', 'warn']); return; }
  if (C.far !== null) R.push(['Reference level to far joint', `${f1(C.far)} mm along the axis (projected, ${diaViewTxt()} film)`]);
  if (Number.isFinite(u.other) && C.far !== null) { const d = C.far - u.other; R.push(['AP vs lateral', `the other view gives ${f1(u.other)} mm (you typed it), this film ${f1(C.far)} mm: ${f1(Math.abs(d))} mm ${Math.abs(d) > 3 ? 'apart, over 3 mm: check the landmarks on both films' : 'apart'}`, Math.abs(d) > 3 ? 'warn' : '']); }
  else R.push(['AP vs lateral', 'this plan measures one film: plan the other view on its own film and type its reference-to-far-joint length here to compare (a difference over 3 mm is flagged)', 'warn']);
  if (P.ok) {
    R.push(['Bone left, reference side', `${f1(P.cutN)} mm from the near joint line (your reference level) to the near osteotomy (your typed values)`, P.cutN <= 0 ? 'bad' : '']);
    if (C.far !== null) { const fr = C.far - P.cutF; R.push(['Bone left, far side', `${f1(fr)} mm from the far osteotomy to the far joint line (projected)`, fr <= 0 ? 'bad' : '']); }
    else R.push(['Bone left, far side', 'not measured: mark the far joint line', 'warn']);
    if (C.far !== null && P.cutF >= C.far) R.push(['Far side', 'the far osteotomy is at or beyond the far joint line: not a diaphyseal (intercalary) resection on this film', 'bad']);
  }
  for (const q of C.phys) {
    const inDef = P.ok && q.s >= P.cutN && q.s <= P.cutF;
    R.push([`Open physis, ${q.side === 'N' ? 'reference side' : 'far side'}`, `at ${f1(q.s)} mm from the reference level${inDef ? ': inside the resected segment' : ''}; whether a stem may cross or come near an open physis is not stated by Chunli: outside what the page can plan until Chunli answers`, 'warn']);
  }
  if (C.stations.length) {
    R.push([`Canal widths (${diaViewTxt()} film)`]);
    for (const q of C.stations) {
      const wrong = q.at !== q.side, ph = diaPhi(q.w);
      R.push([`Canal, ${DIA_FRAG[q.side]} ${q.i}`, `${f1(q.w)} mm at ${f1(q.s)} mm from the reference level${wrong ? ` (this station lies in the ${DIA_FRAG[q.at]})` : ''}; ${diaPhiTxt(q.w)}`, wrong || !ph.inn.length ? 'warn' : '']);
    }
    for (const side of ['N', 'F']) { const own = C.stations.filter(q => q.at === side); if (own.length) R.push([`Narrowest canal, ${DIA_FRAG[side]}`, `${f1(Math.min(...own.map(q => q.w)))} mm on this ${diaViewTxt()} film; the smaller of AP and lateral governs a round stem: compare with the other view's plan, and take the widths from the CT`]); }
  } else R.push(['Canal widths', 'none marked: mark canal widths at stations in each fragment (optional landmarks), or take them from the CT', 'warn']);
  for (const s of C.stems) {
    const nm = `Stem, ${DIA_FRAG[s.side]} (as you typed)`, parts = [];
    if (Number.isFinite(s.d)) parts.push(`Ø ${f1(s.d)} mm: ${diaPhiTxt(s.d)}${s.narrow !== null ? `; ${sgnMm(s.d - s.narrow)} mm against the narrowest canal marked in this fragment on this film (${f1(s.narrow)} mm)` : ''}; cement mantle or press-fit: not stated by Chunli`);
    if (Number.isFinite(s.l)) parts.push(`${f1(s.l)} mm beyond the osteotomy: ${diaL1Txt(s.l)}`);
    R.push([nm, parts.join('. '), Number.isFinite(s.d) && s.narrow !== null && s.d - s.narrow > 0 ? 'warn' : '']);   // Fable r39 P3-1: wider than a marked canal
    if (s.tip !== null) {
      if (s.toJoint !== null) R.push([`Stem tip, ${DIA_FRAG[s.side]}`, `${f1(s.toJoint)} mm from ${DIA_JOINT[s.side]}${s.toJoint <= 0 ? ': reaches past it' : s.toJoint < DIA_TIP_MM ? `: under ${DIA_TIP_MM} mm (the page's warning, not a Chunli minimum)` : ''} (projected)`, s.toJoint <= 0 ? 'bad' : s.toJoint < DIA_TIP_MM ? 'warn' : '']);
      else R.push([`Stem tip, ${DIA_FRAG[s.side]}`, 'not measured: mark the far joint line', 'warn']);
      if (s.toPhys !== null) R.push([`Stem tip vs physis, ${DIA_FRAG[s.side]}`, s.toPhys <= 0 ? `crosses the physis mark by ${f1(-s.toPhys)} mm: outside what the page can plan until Chunli says whether a stem may cross it` : `${f1(s.toPhys)} mm short of the physis mark${s.toPhys < DIA_TIP_MM ? ` (under ${DIA_TIP_MM} mm)` : ''}`, s.toPhys <= 0 ? 'bad' : s.toPhys < DIA_TIP_MM ? 'warn' : '']);
    }
  }
  if (Number.isFinite(u.dBody)) { const [lo, hi] = diaStep(u.dBody, 1); R.push(['Body (as you typed)', `Ø ${f1(u.dBody)} mm: Chunli 1 mm steps ${lo === hi ? lo : `${lo} / ${hi}`}; which Φ is the body is not stated by Chunli (Type III stem A Φ2 16-50, Type VI segment JGD(I) Φ 16-50)`]); }
  if (C.stems.some(s => Number.isFinite(s.d) && Number.isFinite(s.l)) || Number.isFinite(u.dBody)) R.push(['Schematic', 'drawn dashed only from the sizes you typed: schematic, not a Chunli drawing', 'warn']);
  for (const a of DIA_NOT_STATED) R.push(['Not stated by Chunli', a, 'warn']);
  R.push(['Scale', 'lengths use this film\'s ball, which sits at the joint; the lesion is mid-shaft, where magnification may differ (not validated)', 'warn']);
  R.push(['Source', DIA_SRC]);
}
// the order request (r39, Fable's "Build now"): what the surgeon measured and typed, and what he requests; plain lines for the
// card, the saved picture and the list. Chunli to confirm sizes before manufacture.
function diaOrderLines(C = diaCalc()) {
  const u = C.u, P = C.P, out = [], frag = { N: 'reference side', F: 'far side' };
  out.push(`Chunli segmental (diaphyseal) prosthesis, order request: ${SIDE[S.side]} ${u.bone || '(bone not chosen)'}. Chunli to confirm sizes before manufacture.`);
  if (!P.ok) { out.push(`Not ready: ${P.why}.`); return out; }
  out.push(`Tumour extent ${f1(P.ext1)} to ${f1(P.ext2)} mm from the reference level ${diaRefTxt()}, from the ${P.src}; margin ${f1(P.margin)} mm each end (the surgeon's).`);
  out.push(`Osteotomy levels ${f1(P.cutN)} and ${f1(P.cutF)} mm from the reference level: defect ${fmm(P.defect)} mm.`);
  const ty = diaTypesAt(P.defect);
  out.push(ty.length ? `Defect range: Type ${ty.join(' or ')} by Chunli's stated ranges (III 40-165, VI 50-310 mm); type to be agreed with Chunli.` : `Defect outside Chunli's stated ranges (III 40-165, VI 50-310 mm): ask Chunli.`);
  if (C.F) {
    out.push(`Bone left: reference side ${f1(P.cutN)} mm; far side ${C.far !== null ? `${f1(C.far - P.cutF)} mm (projected, ${diaViewTxt()} film)` : 'not measured'}.`);
    for (const side of ['N', 'F']) { const q = C.stations.filter(x => x.at === side); if (q.length) out.push(`Canal widths, ${frag[side]} (${diaViewTxt()} film, a check: take them from the CT): ${q.map(x => `${f1(x.w)} mm at ${f1(x.s)}`).join(', ')}.`); }
    if (C.phys.length) out.push('Open physis marked: whether a stem may cross it to be answered by Chunli.');
  } else out.push('Bone left and canal widths: not measured on this film (mark the bone axis and the reference level).');
  const req = C.stems.map(s => `${frag[s.side]} stem${Number.isFinite(s.d) ? ` Ø ${f1(s.d)}` : ''}${Number.isFinite(s.l) ? ` × ${f1(s.l)} mm beyond the osteotomy` : ''}${s.toJoint !== null ? ` (tip ${f1(s.toJoint)} mm from ${DIA_JOINT[s.side]})` : ''}`);
  if (Number.isFinite(u.dBody)) req.push(`body Ø ${f1(u.dBody)} mm`);
  out.push(req.length ? `Requested by the surgeon: ${req.join('; ')}.` : 'No stem or body size requested: Chunli to propose the configuration.');
  out.push('Not stated by Chunli yet: which length is L1/L2/L3, how the parts make up the defect, the cement mantle or press-fit, the minimum bone each side, a physis.');
  out.push('Scale: this film\'s ball, at the joint; the lesion is mid-shaft (magnification there not validated). Measurements for a Chunli order request, not a plan of parts; nothing here is a part number.');
  return out;
}
// the patient list's lines for this plan (no implant placed): the defect and what the surgeon requests
function diaImplants() {
  const C = diaCalc(), P = C.P, u = C.u, out = [];
  if (P.ok) { const ty = diaTypesAt(P.defect); out.push({ slot: 'diaDefect', label: clip(`Chunli segmental, ${u.bone || 'bone not chosen'}, ${ty.length ? 'Type ' + ty.join(' or ') : 'outside the stated range'} (order request)`, 120), size: clip(`${fmm(P.defect)} mm`, 32) }); }
  for (const s of C.stems) if (Number.isFinite(s.d) || Number.isFinite(s.l)) out.push({ slot: s.side === 'N' ? 'diaStemN' : 'diaStemF', label: 'requested by the surgeon', size: clip(`${Number.isFinite(s.d) ? 'D ' + f1(s.d) : ''}${Number.isFinite(s.l) ? ' L ' + f1(s.l) : ''} mm`.trim(), 32) });
  if (Number.isFinite(u.dBody)) out.push({ slot: 'diaBody', label: 'requested by the surgeon', size: clip(`D ${f1(u.dBody)} mm`, 32) });
  return out;
}
// a typed value: a choice, or a number in its range (else said, and the field put back); one Undo step each
function diaInput(i) {
  const key = i.dataset.dia, u = diaU();
  if (PJ.DIA_ENUM[key]) { const v = PJ.DIA_ENUM[key].includes(i.value) ? i.value : ''; S.dia = { ...u, [key]: v }; }
  else if (PJ.DIA_NUM[key]) {
    const t = i.value.trim(), n = parseFloat(t), [lo, hi] = PJ.DIA_NUM[key];
    if (t === '') S.dia = { ...u, [key]: null };
    else if (!(n >= lo && n <= hi)) { prompt(`${i.title || key}: enter ${lo} to ${hi} mm, or empty it.`); i.value = Number.isFinite(u[key]) ? u[key] : ''; return; }
    else S.dia = { ...u, [key]: Math.round(n * 10) / 10 };
  } else return;
  save(); panel(); draw();
}
// the plan card (r39): Fable's safety wording, the values typed, and the order request sheet
function diaCard(R, cur) {
  const u = diaU(), C = diaCalc(), P = C.P;
  const d = el('<details class="sec kplan" id="sec-dplan"><summary><span class="segn">✚</span><span class="segt">Diaphyseal plan</span><span class="segr"></span></summary><div id="dplanb"></div></details>');
  d.querySelector('.segr').textContent = `${cap(SIDE[S.side])} ${u.bone || 'bone not chosen'} · ${P.ok ? `defect ${fmm(P.defect)} mm` : 'extents not entered'}`;
  d.open = S.segHand.dplan ?? true;
  d.querySelector('summary').addEventListener('click', () => { S.segHand.dplan = !d.open; });
  const b = d.querySelector('#dplanb');
  const sel = (key, list, what) => `<label class="note">${esc(what)} <select data-dia="${key}" title="${esc(what)}"><option value="">choose</option>${list.map(([v, t]) => `<option value="${v}"${u[key] === v ? ' selected' : ''}>${esc(t)}</option>`).join('')}</select></label>`;
  const num = (key, what, w = '5.5em') => `<label class="note">${esc(what)} <input type="number" step="0.5" min="${PJ.DIA_NUM[key][0]}" max="${PJ.DIA_NUM[key][1]}" data-dia="${key}" title="${esc(what)}" style="width:${w}" value="${Number.isFinite(u[key]) ? u[key] : ''}"></label>`;
  b.appendChild(el(`<div class="warnbox">${esc(DIA_SAFETY)}</div>`));
  b.appendChild(el(`<div class="row">${sel('bone', [['femur', 'Femur'], ['tibia', 'Tibia']], 'Bone')}${sel('view', [['AP', 'AP film'], ['LAT', 'Lateral film']], 'This film')}</div>`));
  b.appendChild(el(`<div class="row">${sel('src', [['MRI', 'MRI'], ['CT', 'CT']], 'Extents from')}${sel('refEnd', [['prox', 'proximal end'], ['dist', 'distal end']], 'Near joint line (reference) at the')}</div>`));
  b.appendChild(el('<p class="note">The reference level is the near joint line: measure the tumour ends on the MRI or CT from that joint line (re-measure if you used a trochanter or malleolus tip). The bone left and the stem tip on that side are measured to it.</p>'));
  b.appendChild(el(`<div class="row">${num('ext1', 'Tumour from')}${num('ext2', 'to')}<span class="note">mm from the near joint line</span></div>`));
  b.appendChild(el(`<div class="row">${num('margin', 'Margin each end, mm')}</div>`));
  if (!P.ok) b.appendChild(el(`<p class="note warn">Resection not planned: ${esc(P.why)}.</p>`));
  else b.appendChild(el(`<p class="note">Osteotomies ${f1(P.cutN)} and ${f1(P.cutF)} mm from the reference level (${esc(P.src)} extents ${f1(P.ext1)} - margin and ${f1(P.ext2)} + margin ${f1(P.margin)}): defect ${fmm(P.defect)} mm. Drawn across the bone axis on this film.</p>`));
  b.appendChild(el('<p class="note">Sizes you want to request (optional; drawn as a dashed schematic only from what you type, never computed):</p>'));
  b.appendChild(el(`<div class="row">${num('dN', 'Reference-side stem Ø')}${num('lN', 'length beyond the cut, mm')}</div>`));
  b.appendChild(el(`<div class="row">${num('dF', 'Far stem Ø')}${num('lF', 'length beyond the cut, mm')}</div>`));
  b.appendChild(el(`<div class="row">${num('dBody', 'Body Ø, mm (schematic)')}</div>`));
  b.appendChild(el(`<div class="row">${num('other', 'Other view: reference level to far joint, mm')}</div>`));
  const ord = el('<div class="rec" id="dorder"><div class="rec-h">Order request to Chunli (draft)</div><ul></ul></div>');
  for (const l of diaOrderLines(C)) { const li = document.createElement('li'); li.textContent = l; ord.querySelector('ul').appendChild(li); }
  b.appendChild(ord);
  const ns = el(`<details class="note warn"><summary>Not stated by Chunli (${DIA_NOT_STATED.length})</summary></details>`), ul = document.createElement('ul');
  for (const a of DIA_NOT_STATED) { const li = document.createElement('li'); li.textContent = a; ul.appendChild(li); }
  ns.appendChild(ul); b.appendChild(ns);
  b.appendChild(el(`<p class="note">Ranges: ${esc(DIA_SRC)}. Type III defect 40-165 mm, Type VI 50-310 mm (both for 50-165); stems: L1 in 5 mm steps (A 20-200, B 10-130), Φ1 in 1 mm steps (III A and B 9-40, VI A 9-39, VI B none stated).</p>`));
  b.addEventListener('change', e => { const i = e.target.closest('[data-dia]'); if (i) diaInput(i); });
  return d;
}
function tumPlan(t) {   // {ok, why, src, extent, margin, resection} from the values typed on this body
  const u = (t && t.tum) || {}, src = TUM_SRC[u.src] ? u.src : '', ex = Number(u.extent), mg = Number(u.margin);
  if (!src) return { ok: false, why: 'enter where the tumour extent comes from (MRI or CT): the plain film does not show it' };
  const fem = isTumFemur(S.fam[t.fam]);
  if (!(Number.isFinite(ex) && ex > 0 && ex <= 400)) return { ok: false, src, why: isPfr(S.fam[t.fam]) ? `enter the tumour's distal extent below the tip of the greater trochanter, measured on the ${src}` : isPhr(S.fam[t.fam]) ? `enter the tumour's distal extent below the tip of the greater tuberosity, measured on the ${src}` : fem ? `enter the tumour's proximal extent above the joint line, measured on the ${src}` : `enter the tumour's distal extent below the joint line, measured on the ${src}` };
  if (!(Number.isFinite(mg) && mg >= 0 && mg <= 100)) return { ok: false, src, why: 'enter your margin (mm)' };
  return { ok: true, src, extent: ex, margin: mg, resection: ex + mg };
}
// Osteotomy below the joint line = reconstructionMm (the body, the gap at each taper junction and the extension) + the stem's own
// collar (30/40/50/60). r24 (21 Oct 2026; Kevin, Chunli, and Sami's rule): body 59.5 = tray 55 + (insert 8.5 - 4), each
// junction about 2-2.5 mm open after impaction, taken as junctionGapMm 2.25; one junction without an extension, two with one.
// tumLenWarn stays for a family that still carries techniqueBodyMm (r23 data). The options keep the body size chosen (its width)
// and list extension + collar within 5 mm.
const fmm = x => (Number.isFinite(x) ? String(Math.round(x * 100) / 100) : '–');   // r26 (Fable r24 P3 3): tumour lengths to 0.01 mm, no trailing zeros (91.75, 134)
const tumBodyLen = z => Number.isFinite(z.bodyLengthMm) ? z.bodyLengthMm : z.reconstructionMm - (z.extensionMm || 0);
const tumParts = (z, c) => { const g = Number.isFinite(z.junctionGapMm) ? ` + gap ${z.junctionGapMm}` : '';
  return `${fmm(tumBodyLen(z))}${g}${z.extensionMm ? ` + extension ${z.extensionMm}${g}` : ''} + collar ${c === null || c === undefined ? 'c' : c}`; };
const tumGapNote = z => Number.isFinite(z.junctionGapMm)
  ? `${z.insertMm ? `Joint line = the surface of the ${z.insertMm} mm insert.` : 'Joint line = the distal condyle surface (no insert on the femur).'} Each taper junction is taken as ${z.junctionGapMm} mm open after impaction (Chunli: about 2 to 2.5 mm), so the parts can sit up to about ${z.extensionMm ? 0.5 : 0.25} mm longer or shorter: check the length with the trial.` : '';
const tumLenWarn = (f, z) => Number.isFinite(f.techniqueBodyMm) && Math.abs(f.techniqueBodyMm - tumBodyLen(z)) > 1
  ? `Chunli's 1:1 sheet gives this body ${f1(tumBodyLen(z))} mm from the joint line to the stem junction; its technique (${f.techniqueRef || 'p. 11'}) says ${f1(f.techniqueBodyMm)}. If ${f1(f.techniqueBodyMm)} is right, these parts reach ${f1(f.techniqueBodyMm - tumBodyLen(z))} mm further below the joint line than planned here: not confirmed by Chunli, check the length with the trial before cutting.` : '';
const collarsOf = f => { const sf = f && S.fam[f.stems]; return sf ? [...new Set(sf.sizes.map(q => q.collarMm).filter(Number.isFinite))].sort((a, b) => a - b) : []; };
const tumBodyNo = size => String(size).split('-')[0];
function tumOptions(f, t, R) {
  const out = [];
  for (const z of f.sizes.filter(q => tumBodyNo(q.size) === tumBodyNo(t.size))) for (const c of collarsOf(f)) {
    const tot = z.reconstructionMm + c; if (Math.abs(tot - R) <= 5) out.push({ z, c, tot });
  }
  return out.sort((a, b) => Math.abs(a.tot - R) - Math.abs(b.tot - R) || a.z.extensionMm - b.z.extensionMm);
}
// the collar of the stem on this body: the chosen stem's, else the one picked with an option, else null
const tumCollar = t => { const st = stemOf(t); return st && Number.isFinite(st.s.collarMm) ? st.s.collarMm : (t.tum && Number.isFinite(t.tum.c) ? t.tum.c : null); };
// a stem chosen now: its size and its family's outline version (geomId), so a later change of the outline is never drawn
// silently on a reopened plan (Astra r20 P1)
function stemPick(f, size) { const sf = S.fam[f.stems]; return { size, ...(sf && sf.geomId ? { geo: sf.geomId } : {}) }; }
function extWhy(t) {
  const f = S.fam[t.fam], z = sizeOf(t); if (!t.stem || !f || !z) return '';
  if (!f.stems) return 'this component takes no stem extension';
  const sf = S.fam[f.stems]; if (!sf) return 'the stem extensions of this range are not on this server';
  if (!(z.stemSizes || []).includes(t.stem.size) || !sf.sizes.some(q => q.size === t.stem.size)) return `stem ${String(t.stem.size).slice(0, 12)} is not drawn for size ${z.size} on the maker's sheet`;
  if (t.stem.geo && sf.geomId && t.stem.geo !== sf.geomId) return 'its outline has changed since this plan was saved: check it and choose it again';   // (Astra r20 P1)
  const gm = geomOf(t); if (!gm || !gm.stemPort) return 'no stem junction in this view';
  return '';
}
// stem outline in the component's own frame: stem x across, y along its axis from the junction
function stemRings(t, gm) {
  const st = stemOf(t); if (!st || !gm || !gm.stemPort) return null;
  const v = st.s.views[viewOf()] || st.s.views.AP, p = gm.stemPort.p, d = gm.stemPort.dir, n = [-d[1], d[0]];
  return v.rings.map(r => r.map(([x, y]) => [p[0] + x * n[0] + y * d[0], p[1] + x * n[1] + y * d[1]]));
}
// tibial cut to the joint line for the thickness chosen: the tray's own seat added when the sheet's numbers are the
// insert's own thickness above it (LDK XR: seatAboveCutMm 3.03; r20), else the number itself (tray + insert, XN, CCK)
const thickTxt = t => { const f = S.fam[t.fam]; return f && f.thicknessDrawnMm ? `insert number ${t.thick} (the sheet draws it ${f1(tibJl(t))} mm above the tibial cut; which is the composite is not confirmed)` : f && f.seatAboveCutMm ? `${t.thick} mm insert on a ${f1(f.seatAboveCutMm)} mm seat (${f1(tibJl(t))} mm cut to joint line)` : `total thickness ${t.thick} mm (tray + insert)`; };
// (Chunli XN-CCK: the sheet draws each line 1.0 mm above its printed number: thicknessDrawnMm, the line as drawn, is used)
function tibJl(t) {
  const f = S.fam[t.fam], th = t.thick || 10, i = f && Array.isArray(f.thicknessDrawnMm) ? f.thicknesses.indexOf(th) : -1;
  return i >= 0 ? f.thicknessDrawnMm[i] : th + ((f && f.seatAboveCutMm) || 0);
}
function pivotOf(t, g) {
  const f = S.fam[t.fam];
  if (f.kind === 'cup') return [0, 0];
  if (f.kind === 'stem') return g.headM;   // the head centre: sizing up or changing family keeps the head where it was
  if (f.kind === 'humeral-body') return [0, 0];   // r33: its head centre
  if (f.kind === 'tka-femur' && viewOf() === 'AP') return [0, g.jointLineY];
  if (f.kind === 'tka-tibia' && viewOf() === 'AP') return [0, g.cutY];
  if (f.kind === 'tka-tibia') return [(g.cutLine[0][0] + g.cutLine[1][0]) / 2, (g.cutLine[0][1] + g.cutLine[1][1]) / 2];
  const r = g.rings[0]; let x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
  for (const [x, y] of r) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
  return [(x0 + x1) / 2, (y0 + y1) / 2];
}
// local template mm -> image px
function xf(t) {
  const g = geomOf(t), pv = pivotOf(t, g), s = 1 / mm(), c = Math.cos(t.angle), sn = Math.sin(t.angle), fx = t.flip ? -1 : 1;
  return l => { const lx = fx * (l[0] - pv[0]) * s, ly = (l[1] - pv[1]) * s; return G.P(t.x + c * lx - sn * ly, t.y + sn * lx + c * ly); };
}
const dirOf = (t, l) => G.unit(G.rot(G.P((t.flip ? -1 : 1) * l[0], l[1]), t.angle));   // local direction -> image unit vector
// Orientation of the film (nothing may assume the head is at the top of the image).
//   cranVec: image direction towards the patient's head; leftVec: towards the patient's left; latVec(side): towards the
//   lateral side of that limb. Standard AP: head up, patient's left at image right.
const CRAN = { up: G.P(0, -1), down: G.P(0, 1), left: G.P(-1, 0), right: G.P(1, 0) };
function rot90(v) { return G.P(-v.y, v.x); }                      // +90 deg on screen (y down): up -> right
const cranVec = () => CRAN[S.cranial] || CRAN.up;
const leftVec = () => G.mul(rot90(cranVec()), S.mirrored ? -1 : 1);
const latVec = (side = S.side) => G.mul(leftVec(), side === 'R' ? -1 : 1);
const filmTurn = () => G.turn(G.P(0, -1), cranVec());              // rotation of this film against a standard one
const lateralSign = (side = S.side) => Math.sign(G.dot(latVec(side), G.rot(G.P(1, 0), filmTurn()))) || 1;   // in the film's own frame
function defaultFlip(kind, side = S.side) {
  if (kind === 'cup' || kind === 'stem' || kind === 'humeral-body') return lateralSign(side) === 1;   // r33: the humerus as the femur
  return viewOf() === 'AP' ? S.mirrored : false;
}
function refreshFlips() {   // a mirrored template also mirrors its angle; a stem that was on the canal is put back on it
  const hadStem = S.wf === 'hip' && !!S.tpl['stem' + S.side]; ensureSlots();   // Fable, 11 Oct: a side change in "template the other side" mode
  if (hadStem && !S.tpl['stem' + S.side]) prompt(`The ${SIDE[S.side]} stem was removed: the ${SIDE[S.side]} femur is not templated in this mode (the stem is the ${SIDE[other(S.side)]} one). Press Auto template to plan the ${SIDE[other(S.side)]} side.`);
  for (const [slot, t] of Object.entries(S.tpl)) if (!t.userFlip) {
    const side = slotSide(slot), kind = S.fam[t.fam].kind, f = defaultFlip(kind, side); if (f === t.flip) continue;
    const isStem = kind === 'stem', was = isStem && stemAligned(t, side), H0 = isStem ? corAt(t) : null;
    const Hc = kind === 'cup' ? hipFrame(side) : null, inc0 = Hc ? (() => { const a = G.angleDeg(dirOf(t, [1, 0]), Hc.u); return a > 90 ? 180 - a : a; })() : null;
    t.flip = f; t.angle = normA(-t.angle + 2 * filmTurn());
    if (Hc) { const lat0 = dirOf({ ...t, angle: 0 }, [-1, 0]); t.angle = normA((t.flip ? -1 : 1) * G.rad(inc0) + G.turn(lat0, Hc.u)); }   // same inclination to the teardrop line
    if (was && (!guardedStem() || canalReviewed(side))) { alignStem(t, side); setHeadLevel(t, side, G.dot(H0, vertical())); }   // (round 3: only onto a reviewed canal line)
  }
}
// each workflow keeps its own implants: AP and lateral outlines have different shapes and pivots
function switchWf(wf) {
  S.tplAll[S.wf] = S.tpl; S.wf = wf; S.tpl = S.tplAll[wf] || (S.tplAll[wf] = {}); S.active = null; ensureSlots();
}
function ensureSlots() {
  for (const k of Object.keys(S.tpl)) if (!WF[S.wf].slots.includes(k)) delete S.tpl[k];
  // a fractured femur carries no stem, also in older plans, unless the surgeon added one there by hand (Sami, 14 Oct)
  if (S.wf === 'hip' && refOther() && S.tpl['stem' + S.side] && !S.tpl['stem' + S.side].byHand) delete S.tpl['stem' + S.side];
  if (S.wf === 'hip') for (const sd of ['R', 'L']) { const t = S.tpl['stem' + sd]; if (t && t.bip && !hemiOn(sd)) delete t.bip; }   // r38: a shell only while that hip is planned as a bipolar
  if (S.wf === 'hip') for (const sd of ['R', 'L']) if (hemiOn(sd)) {   // r38 (Fable P2-1): and no cup on a bipolar hip, also one placed while it was planned from the other hip
    if (S.tpl['cup' + sd]) { delete S.tpl['cup' + sd]; if (S.active === 'cup' + sd) S.active = null; }
    if (S.hidden && S.hidden.hip && S.hidden.hip['cup' + sd]) delete S.hidden.hip['cup' + sd];
  }
  if (S.active && !S.tpl[S.active]) S.active = null;
}
// a new implant for this slot, or null when no range of its kind is open to this account
function newTemplate(slot) {
  if (llKcLong() && S.kc.shared.size[slot] === null) return null;   // r44: the knee case's long film draws the case's sizes only
  const kind = SLOT[slot].kind, side = slotSide(slot);
  const twin0 = SLOT[slot].side ? S.tpl[SLOT[slot].kind + other(side)] : null;   // the other side's choice is the start
  const picked = chosenFam(kind);   // the family picked on the choice card comes first (design C)
  const twinF = twin0 && !isPfr(S.fam[twin0.fam]) ? S.fam[twin0.fam] : null;   // r32 (Fable P2 5): a PFR is never copied to the other hip
  const fam = picked || twinF || defaultFam(kind); if (!fam) return null;   // r36: never another maker's first family
  const twin = twin0 && twin0.fam === fam.id ? twin0 : null, shown = sizesShown(fam);
  const t = { fam: fam.id, size: twin ? twin.size : shown[Math.floor(shown.length / 2)].size, thick: 10, x: 0, y: 0, angle: 0, flip: defaultFlip(kind, side), on: true };
  if (!twin && (kind === 'tka-femur' || kind === 'tka-tibia')) t.size = kneeStartSize(fam, null);   // r43 (DIAG #2): a tumour body without an extension, never the middle length
  else if (!twin && (isPfr(fam) || isPhr(fam))) { const z0 = fam.sizes.find(z => z.extensionMm === 0); if (z0) t.size = z0.size; }   // r43 (DIAG #11): no extension until the resection is planned
  if (picked) t.userFam = true;
  if (kind === 'stem') { if (twin) Object.assign(t, { headKind: twin.headKind, head: twin.head, neck: twin.neck }); else setHead(t, fam.sheetHead.kind, fam.sheetHead.diameter); }
  const vc = toI(G.P(cv.width / dpr / 2, cv.height / dpr / 2)); t.x = vc.x; t.y = vc.y;
  if (S.kc && (slot === 'femur' || slot === 'tibia')) {   // a knee case: the size the other film has (one size for both)
    const z = S.kc.shared.size[slot], f = z && S.fam[z.fam];
    if (f && f.kind === kind && f.sizes.some(q => q.size === z.size)) { t.fam = z.fam; t.size = z.size; if (z.byHand) t.userSize = true; if (z.stem) t.stem = { ...z.stem }; if (isTumourBody(f)) { const g = kcTumFrom(slot, z.fam); if (g) t.tum = g.tum; } }
  }
  kneeFamFix(t);
  return t;
}
function addTemplate(slot) {
  const nr = notReady(); if (nr) return prompt(nr);
  { const w = llImplWhy(); if (w) return prompt(w); }   // r44
  if (!mm()) return prompt('Calibrate first: implants are drawn true size.');
  if (refOther() && slot === 'stem' + S.side) return addStemByHand(slot);
  if (llKcLong() && S.kc.shared.size[slot] === null) return prompt(`Knee case: the ${slot === 'tibia' ? 'tray' : 'femoral component'} has no size in the case yet: it is sized on the AP or the lateral, then drawn here.`);   // r44
  const t = newTemplate(slot); if (!t) { const kd = SLOT[slot].kind, b = brandFor(kd); return prompt(!b && hasKind(kd) ? `Choose the implant maker for this case first (${brandsHere().join(', ')}${S.wf === 'hip' ? ', or mixed' : ''}).` : b === 'mixed' && hasKind(kd) ? `Mixed makers: choose the ${KIND_WORD[kd] || kd} family first.` : `No ${SLOT[slot].label.toLowerCase()} range is open to your account.`); }   // r36
  S.tpl[slot] = t; S.active = slot;
  if (SLOT[slot].kind === 'stem') bipEnsure(slotSide(slot), { resize: true });   // r38: a bipolar hip's stem takes the shell at once
  autoPlace(slot);
  const side = slotSide(slot);
  if (SLOT[slot].side) { matchHeadToCup(side); if (SLOT[slot].kind === 'stem') autoPlace(slot); }
  save(); panel(); draw();
}
// A stem on the deformed surgery side when the plan is made on the other hip (Sami, 14 Oct 2026: "add option of adding
// stem on contralateral side as needed"). It starts as the other side's stem mirrored about the pelvic midline (same
// family, size, head and neck; its head on the mirrored head centre), or in the middle of the screen when the other side
// has no stem yet. No rule moves it afterwards: the surgeon places it on the deformed femur by hand. The plan stays the
// other side's; the results say so.
function addStemByHand(slot) {
  const sd = slotSide(slot), sN = S.tpl['stem' + other(sd)], H = hipFrame(sd);
  const t = newTemplate(slot); if (!t) return prompt(`No stem range is open to your account.`);
  t.byHand = true;
  if (sN && sN.on && guardedStem()) { Object.assign(t, { fam: sN.fam, size: sN.size, headKind: sN.headKind, head: sN.head, neck: sN.neck }); S.tpl[slot] = t; S.active = slot; save(); panel(); draw(); return prompt(`${SIDE[sd]} stem added by hand in the middle of the screen (a conversion or revision: no position is worked out for it): drag and turn it onto the ${SIDE[sd]} femur.`); }   // (round 2, P1 2)
  if (sN && sN.on) {
    Object.assign(t, { fam: sN.fam, size: sN.size, headKind: sN.headKind, head: sN.head, neck: sN.neck });
    if (H && !H.single) {   // the head centre reflected, the axis turned the other way about the pelvic vertical
      const hN = headAt(sN), at = reflect(hN, H), tilt = sN.angle - filmTurn();
      t.angle = filmTurn() - tilt; t.x = at.x; t.y = at.y;
      const d = G.sub(at, headAt(t)); t.x += d.x; t.y += d.y;
    }
  }
  S.tpl[slot] = t; S.active = slot;
  save(); panel(); draw();
  prompt(`${SIDE[sd]} stem added by hand${sN && sN.on ? `, mirrored from the ${SIDE[other(sd)]} stem` : ''}: drag and turn it onto the ${SIDE[sd]} femur. The plan is still the ${SIDE[other(sd)]} one; no rule moves this stem.`);
}
// the family picked on the choice card for this kind, while it is open to this account; else null
function chosenFam(kind) { const f = S.choice[kind] && S.fam[S.choice[kind]]; return f && f.kind === kind ? f : null; }
// the implant in this slot, made if there is none; null when no range of its kind is open to this account
function tplFor(slot) {
  if (!S.tpl[slot]) { const t = newTemplate(slot); if (t) S.tpl[slot] = t; }
  return S.tpl[slot] || null;
}

// ---------- automatic placement (rules shown in the panel; every result can be moved, turned and resized) ----------
// the slots a plan needs, of the kinds open to this account (a hip plan with stems only has no cup to place)
const opSlots = () => (S.wf === 'hip' ? (refOther() ? ['cup' + S.side, 'cup' + other(S.side), 'stem' + other(S.side)] : ['cup' + S.side, 'stem' + S.side]) : WF[S.wf].slots).filter(sl => hasKind(SLOT[sl].kind) && !(SLOT[sl].kind === 'cup' && hemiOn(slotSide(sl))) && !(llKcLong() && S.kc.shared.size[sl] === null));   // r38: a bipolar hip has no cup; r44: the knee case's long film, only the parts the case has sized
function autoPlace(slot, opt = {}) {
  const t = S.tpl[slot], f = S.fam[t.fam];
  if (f.kind === 'cup') return autoCup(slot, opt);
  if (f.kind === 'stem') return autoStem(slot, opt);
  if (f.kind === 'humeral-body') return phrPlace(slot);   // r33
  autoKnee(slot);
}
// Cup. Size: from the acetabular roof edge when marked (the cup's outer rim reaches it at the planned angle), else the
// head's diameter + 4 mm (outside). Centre, by the chosen rule:
//   anatomic (the default since 14 Oct 2026, "native centre height, medial edge at the teardrop"): at the native head
//     centre's height (pelvic vertical), moved medially until the shell's most medial point is tdLatMm lateral of the
//     teardrop, at most 10 mm medial of the native centre and never lateral of it (autoplan.js anatomicCentre); the native
//     centre is this hip's head, for a deformed side the reference hip's head mirrored; with no head marked, the
//     teardrop technique below, said;
//   teardrop technique ("lowest rim on the teardrop line"): inferomedial edge level with the teardrop line, tdLatMm lateral to the teardrop's lowest
//     point, at the planned inclination (the usual medialised position);
//   native head centre: on the head circle's centre;
//   mirror: on the other hip's centre reflected about the pelvic midline.
function autoCup(slot, { keepSize = true, rule: ruleWanted = null } = {}) {
  const t = S.tpl[slot], side = slotSide(slot), f = S.fam[t.fam], H = hipFrame(side), h = nativeHead(side), ac = lmGeom('ac' + side), k = mm();
  const normal = side !== S.side, fromOther = refOther() && !normal, hn = nativeHead(other(side)), twin = fromOther ? otherPlanCup(side) : null;
  // a revision cup, or a cup over an old prosthetic head (amendment 4): the old cup or head centre is an observation that may
  // have migrated, never a target; the cup goes only to the target the surgeon set, sized from the roof edge (native bone)
  // when it is marked, else left at the size it has
  if (exOpen().length) { S.notes['cupRule' + side] = ''; return; }   // an unresolved proposal: nothing placed (Astra, 16 Oct, P1 5)
  if (cupNeedsTarget(side)) {
    const T = rvOf().cupT;
    delete S.notes['cupRoof' + side]; delete S.notes['cupAnat' + side]; delete S.notes['mirrorMissing' + side];
    if (!T) { S.notes['cupRule' + side] = ''; S.notes['cupSize' + side] = ''; return; }
    const inc = G.rad(S.set.cupIncl);
    if (!(keepSize && t.userSize) && H && ac) {
      const reach = G.dot(G.sub(ac.p, H.T), H.u) * k - S.set.tdLatMm;
      if (reach > 20) { const wantD = faceToOuter(f, reach / Math.cos(inc)), c = cupSizeFor(f.sizes, wantD); t.size = c.size; t.userSize = false; S.notes['cupSize' + side] = 'cover to the acetabular roof edge (native bone)'; if (c.fail) S.notes['cupFail' + side] = { dir: c.fail, want: Math.round(wantD * 10) / 10, limit: c.limit, size: c.size, fam: f.id }; else delete S.notes['cupFail' + side]; }
    } else if (!(keepSize && t.userSize)) S.notes['cupSize' + side] = 'as it was (the old head is not a size reference): set the size yourself';
    const lat0 = dirOf({ ...t, angle: 0 }, [-1, 0]);
    t.angle = normA((t.flip ? -1 : 1) * inc + G.turn(lat0, H ? H.u : latVec(side)));
    t.x = T.p.x; t.y = T.p.y; linerFix(side); linerShift(t, linerOff(t)); S.notes['cupRule' + side] = 'target';   // r38b: the head centre on the target
    return;
  }
  const incl = G.rad(twin ? twin.incl : S.set.cupIncl);
  let roofSized = false;   // the size came from the roof edge: solved with the centre below (anatomic rule)
  delete S.notes['cupRoof' + side];
  if (!(keepSize && t.userSize)) {
    let want = null, how = '', twinFail = null;
    if (twin && t.userFam && t.fam !== twin.t.fam) {   // a family chosen by hand for this cup is kept (amendment 4): the size nearest the other plan's outside
      // beyond that family's range it is said, as for any cup (amendment 1; Astra's third page review): never quietly the largest
      const tz = sizeOf(twin.t), tw = tz.catalogueOuter || tz.outerDiameter, c = cupSizeFor(f.sizes, tw);
      t.size = c.size; t.userSize = false; how = `the ${SIDE[other(side)]} plan's outside diameter (cup ${twin.t.size}, ${f1(twin.incl)}°) in the ${f.label} you chose`;
      if (c.fail) twinFail = { dir: c.fail, want: Math.round(tw * 10) / 10, limit: c.limit, size: c.size, fam: f.id };
    } else if (twin) { t.fam = twin.t.fam; t.size = twin.t.size; t.userSize = false; how = `the ${SIDE[other(side)]} plan (cup ${twin.t.size}, ${f1(twin.incl)}°)`; }
    else if (fromOther && hn) { want = 2 * hn.r * k + 4; how = `the ${SIDE[other(side)]} head diameter + 4 mm (this hip is deformed)`; }
    else if (H && ac) { const reach = G.dot(G.sub(ac.p, H.T), H.u) * k - S.set.tdLatMm; if (reach > 20) { want = faceToOuter(f, reach / Math.cos(incl)); how = 'cover to the acetabular roof edge'; roofSized = true; } }
    if (!want && h && !twin) { want = 2 * h.r * k + 4; how = 'head diameter + 4 mm'; }
    delete S.notes['cupFail' + side];
    if (twinFail) S.notes['cupFail' + side] = twinFail;
    // a wanted size beyond the range is said, never quietly replaced by the largest (amendment 1)
    if (want && !twin) { const c = cupSizeFor(f.sizes, want); t.size = c.size; t.userSize = false; if (c.fail) S.notes['cupFail' + side] = { dir: c.fail, want: Math.round(want * 10) / 10, limit: c.limit, size: c.size, fam: f.id }; }
    S.notes['cupSize' + side] = how;
  } else delete S.notes['cupFail' + side];
  // r38b: a mirrored copy takes the other plan's liner (a choice by hand stays one); then the liner for the head
  if (twin && twin.t.fam === t.fam && twin.t.size === t.size && linerOf(twin.t)) { t.liner = twin.t.liner; if (twin.t.userLiner) t.userLiner = true; else delete t.userLiner; }
  linerFix(side);
  const lat0 = dirOf({ ...t, angle: 0 }, [-1, 0]);                                    // the template's lateral at 0 deg
  const tilt = G.turn(lat0, H ? H.u : latVec(side));                                  // pelvis tilt and film rotation
  t.angle = normA((t.flip ? -1 : 1) * incl + tilt);
  const sz = sizeOf(t), R = (sz.catalogueOuter || sz.outerDiameter) / 2 / k, off = cupOff(sz) / k, mirrorC = mirrorTarget(side);
  let rule = ruleWanted || cupRuleFor(side), C = null;
  if (rule === 'mirror' && !mirrorC && !normal) S.notes['mirrorMissing' + side] = true;
  else delete S.notes['mirrorMissing' + side];
  delete S.notes['cupAnat' + side];
  if (rule === 'mirror' && mirrorC) C = mirrorC;
  else if (rule === 'anatomic' && H && roofSized && ac && h && !fromOther) {
    // the roof-edge size solved with the centre (Astra, finding 3): stepped down until the lateral rim stays within the roof edge
    const head = false, N = h.c, start = f.sizes.findIndex(z => z.size === t.size);
    const fit = anatomicRoofFit(f.sizes, start, z => (z.catalogueOuter || z.outerDiameter) / 2 / k, N, H.T, H.u, pxOf(S.set.tdLatMm), head ? 0 : pxOf(ANAT_MAX_MED), G.deg(incl), G.dot(G.sub(ac.p, H.T), H.u), pxOf(0.5), z => cupOff(z) / k);
    if (fit.stepped) { t.size = f.sizes[fit.idx].size; S.notes['cupSize' + side] = `${S.notes['cupSize' + side]}, with the cup ${head ? 'on the native head centre' : 'at the native centre height'} (${fit.stepped} size${fit.stepped > 1 ? 's' : ''} down from the teardrop-placement size, so the rim stays within the roof edge)`; }
    if (fit.overPx > 0) S.notes['cupRoof' + side] = { over: Math.round(fit.overPx * k * 10) / 10, size: t.size };
    C = fit.C; if (!head) S.notes['cupAnat' + side] = { med: Math.round(fit.a.medPx * k * 10) / 10, capped: fit.a.capped, kept: fit.a.kept, mirrored: false };
  }
  else if (rule === 'anatomic' && H) {
    const N = fromOther ? (hn && !H.single ? reflect(hn.c, H) : null) : h ? h.c : null;
    if (N) { const a = anatomicCentre(N, H.T, H.u, pxOf(S.set.tdLatMm), R, pxOf(ANAT_MAX_MED), off, G.deg(incl)); C = a.C; S.notes['cupAnat' + side] = { med: Math.round(a.medPx * k * 10) / 10, capped: a.capped, kept: a.kept, mirrored: fromOther }; }
    else { C = teardropCentre(H.T, H.u, H.v, pxOf(S.set.tdLatMm), G.deg(incl), R, off); rule = 'teardrop'; S.notes['cupAnat' + side] = { noHead: true }; }
  }
  else if (rule === 'teardrop' && H) C = teardropCentre(H.T, H.u, H.v, pxOf(S.set.tdLatMm), G.deg(incl), R, off);
  else if (h && !fromOther) { C = h.c; rule = 'head'; }
  else if (H) { C = teardropCentre(H.T, H.u, H.v, pxOf(S.set.tdLatMm), G.deg(incl), R, off); rule = 'teardrop'; }
  if (C) { t.x = C.x; t.y = C.y; if (COR_RULES.includes(rule)) linerShift(t, linerOff(t)); }   // r38b: head and mirror aim the head centre; anatomic and teardrop place the shell
  S.notes['cupRule' + side] = C ? rule : '';
}
// Which rule places a side's cup (13 Oct 2026, design A with amendment 1): every surgical plan by the surgical rule in
// Auto plan settings (since 14 Oct 2026 native centre height with the medial edge at the teardrop, unless the surgeon picks another): the surgery side in own-hip mode, and the
// normal side when it is the reference for a deformed surgery side (its plan is mirrored across, so it is the surgical
// plan; it has no normal other hip to be mirrored from). The deformed surgery side copies the reference, mirrored. Only a
// comparison-only other side (own-hip mode) keeps its native head centre.
function cupRuleFor(side) {
  if (side === S.side) return refOther() ? 'mirror' : S.set.cupRule;
  if (refOther()) return S.set.cupRule === 'mirror' ? 'anatomic' : S.set.cupRule;
  return 'head';
}
const ANAT_MAX_MED = 10;   // mm: the anatomic rule never medialises the cup centre more than this from the native centre
// what the anatomic rule did, for the cup box and the results
const anatText = (a, sd) => !a ? '' : a.noHead ? 'no native head centre is marked, so the cup went by the teardrop technique (lowest rim on the teardrop line)'
  : a.kept ? `the native centre${a.mirrored ? ' (mirrored)' : ''} is already medial of the teardrop position, so the cup stays on it`
  : `${f1(a.med)} mm medial of the native centre${a.mirrored ? ' (mirrored)' : ''}${a.capped ? `, held at the ${ANAT_MAX_MED} mm limit (the medial edge stays short of the teardrop position)` : ''}`;
const cupFailText = (cf, f) => (cf.dir === 'above'
  ? `the wanted cup (${f1(cf.want)} mm outside) is larger than the largest ${f.label} (${cf.limit} mm): ${cf.size} is shown only as the nearest. Choose the size yourself or another range, or check the landmarks`
  : `the wanted cup (${f1(cf.want)} mm outside) is smaller than the smallest ${f.label} (${cf.limit} mm): ${cf.size} is shown`);
// The other hip's centre reflected about the pelvic midline: its planned cup if there is one, else its native head.
// The same reference for "Mirror", the dashed target and the comparison (Astra, 11 Oct).
// What the operated side is mirrored to: the other hip as it is (native head centre and femoral offset), unless the
// surgeon chooses "the other side's plan" in Auto plan settings (its cup centre and its stem's offset). Fable, 11 Oct:
// matching an automatic plan of the other side made the answer depend on which button was pressed first.
// the other side's planned cup and its inclination to the teardrop line, or null
function otherPlanCup(side) {
  const n = other(side), t = S.tpl['cup' + n], H = hipFrame(n); if (!t || !t.on || !H || H.single) return null;
  let inc = G.angleDeg(dirOf(t, [1, 0]), H.u); if (inc > 90) inc = 180 - inc;
  return { t, incl: inc };
}
function mirrorRef(side) {
  const n = other(side), hn = nativeHead(n), cn = S.tpl['cup' + n], cnl = canalOf(n), sn = S.tpl['stem' + n];
  const planCup = cn && cn.on && mm() ? (() => { const s = sizeOf(cn); return { p: cupCentre(cn), r: pxOf((s.catalogueOuter || s.outerDiameter) / 2) }; })() : null;   // r38b: its head centre
  const planBip = !planCup && sn && sn.on && bipOf(sn) && mm() ? { p: corAt(sn), r: pxOf(bipOf(sn).z.outerDiameter / 2) } : null;   // r38: the other hip planned as a bipolar: its shell
  if ((S.set.mirrorRef === 'plan' || !hn || (refOther() && side === S.side)) && (planCup || planBip)) return { ...(planCup || planBip), kind: 'plan', what: `${SIDE[n]} plan`, offset: sn && sn.on && cnl ? G.lineDist(corAt(sn), cnl.a, cnl.b) * mm() : null };
  return hn ? { p: hn.c, r: hn.r, kind: 'native', what: `${SIDE[n]} native hip`, offset: cnl && mm() ? G.lineDist(hn.c, cnl.a, cnl.b) * mm() : null } : null;
}
function mirrorTarget(side) {
  const H = hipFrame(side), ref = mirrorRef(side);
  return H && !H.single && ref ? reflect(ref.p, H) : null;
}
function reflect(p, H) { const M = G.mid(H.T, H.Tc); return G.sub(p, G.mul(H.u, 2 * G.dot(G.sub(p, M), H.u))); }
// Stem by the older rule (restore the native femoral offset), or with no cup to aim at: along the canal (centred between
// the inner cortical edges when they were found), sized by canal fill (the largest stem whose outline stays inside the
// inner cortex, 0.5 mm allowed, wherever the edges were found), head set so leg length after reduction meets the target
// (equal to the other side, or unchanged).
// target: femoral offset (mm) to restore, or null. With a target each size is placed with the neck that comes nearest
// to it before its canal fit is judged, so a longer neck cannot push a chosen stem into the cortex afterwards.
// The same fit rejection and manual choices as the head-on-cup rule (Astra's page re-review, 13 Oct, finding 2): only
// sizes with canal evidence inside the cortex count, and when none does no size is chosen ("no suitable match", never
// an oversized fallback); a size set by hand (keepSize and t.userSize) or a family chosen by hand (keepFam or t.userFam)
// is never replaced by the standard/high offset twin; the twin is sized by the same fit rule and is not taken when it is
// a high offset version that would restore combined offset more than 5 mm over the native hip.
function autoStem(slot, { keepSize = true, keepFam = false, target = null } = {}) {
  const t = S.tpl[slot], side = slotSide(slot);
  if (isPfr(S.fam[t.fam])) return pfrPlace(slot);   // r32: placed, never sized by canal fill
  const place = tt => (target ? bestNeck(tt, side, target) : placeStem(tt, side));
  const start = stemStart(side);
  if (!start && refOther() && side === S.side) { S.notes['fit' + side] = { none: true, why: `no reference: plan the ${SIDE[other(side)]} side (or mark its head) first` }; return null; }   // Astra, 11 Oct
  const gate = stemGate(side); if (gate) { S.notes['fit' + side] = { none: true, why: gate }; return null; }   // revision and conversion: nothing in doubt is read
  const kept = guardedStem() ? stemKeep(t) : null;
  if (start && !stemAligned(t, side)) { t.x = start.x; t.y = start.y; t.angle = filmTurn(); }
  const tr = canalTraceFor(side), handSize = !!(keepSize && t.userSize), handFam = !!(keepFam || t.userFam);
  S.notes['fit' + side] = null;
  // sized only where the canal's inner cortex was traced along most of the stem: a short or broken trace leaves the
  // size to the surgeon instead of choosing one (Astra, 11 Oct); the largest size inside the cortex, or none
  const sizeBy = tt => {
    const shown = sizesShown(S.fam[tt.fam], tt.fam === t.fam ? t : null), fits = [];
    if (tr) for (const [i, z] of shown.entries()) { const t2 = { ...tt, size: z.size }; place(t2); const fit = stemFit(t2, tr); if (fitIsEvidence(fit, tr, stemLen(t2, tr))) fits.push({ size: z.size, fit, i }); }
    const ok = fits.filter(o => o.fit.maxIn <= 0.5), best = ok.length ? ok[ok.length - 1] : null;
    if (!best) return { none: true, why: !tr ? 'no canal line' : !tr.axis ? `the canal's inner cortex was traced over only ${Math.round(tr.spanMm || 0)} mm` : !fits.length ? 'the traced canal does not cover enough of the stem' : 'every size reaches more than 0.5 mm past the inner cortex' };
    return { size: best.size, note: { ...best.fit, size: best.size, tooBig: false, largerUnchecked: best.i < shown.length - 1 && !fits.some(o => o.i > best.i) } };
  };
  let main = null;
  if (!handSize) {
    main = sizeBy({ ...t });
    if (main.none) S.notes['fit' + side] = { none: true, why: main.why };   // the size is left as it is, for the surgeon
    else { t.size = main.size; t.userSize = false; S.notes['fit' + side] = main.note; }
  }
  if (kept) {   // guarded: no evidence for the size (or for the size set by hand), no move
    let why = main && main.none ? main.why : '';
    if (handSize) { const t2 = { ...t }; place(t2); const f2 = tr ? stemFit(t2, tr) : null; if (!fitIsEvidence(f2, tr, tr ? stemLen(t2, tr) : 0)) why = 'the canal fill of your size is not evidence on the traced canal'; }
    if (why) { Object.assign(t, kept); S.notes['fit' + side] = { none: true, why: `${why}: the stem is not moved; place it yourself` }; return null; }
  }
  const nb = place(t);
  if (target && nb && !handSize && !handFam) {   // the standard or high offset version of the same stem, if it restores the offset clearly better and fits
    const pf = pairOf(t.fam);
    if (pf && S.fam[pf]) {   // only when that version's range is open to this account
      const tt = { ...t, fam: pf }; setHead(tt, tt.headKind, tt.head);
      const r2 = sizeBy(tt);
      if (!r2.none) {
        tt.size = r2.size;
        const nb2 = bestNeck(tt, side, target), ch = combinedChange(tt, side);
        const high = offsetClasses([S.fam[t.fam], S.fam[pf]])[pf] === 'high', over = high && ch !== null && ch > 5;
        if (nb2 && !over && ((main && main.none) || nb2.err < nb.err - 2)) {
          Object.assign(t, { fam: pf, size: tt.size, neck: tt.neck, headKind: tt.headKind, head: tt.head }); placeStem(t, side); S.notes['fit' + side] = r2.note; return nb2;
        }
      }
    }
  }
  return nb;
}
// a stem placement's combined offset against the native hip (mm; the cup centre's acetabular offset plus its head's
// femoral offset, minus the native's), or null when it is not measured
function combinedChange(tt, sd) {
  const cup = S.tpl['cup' + sd], c = canalOf(sd), H = hipFrame(sd), nat = nativeCombined(sd), k = mm();
  if (!cup || !cup.on || !c || !H || !nat || !k) return null;
  return G.dot(G.sub(cupCentre(cup), H.T), H.u) * k + G.lineDist(headAt(tt), c.a, c.b) * k - nat.mm;   // r38b: at the liner's head centre
}
// the neck (same stem) whose femoral offset comes nearest `target`; places the stem with it
// where a stem starts before it is turned onto the canal: the native head, or (a deformed hip) the planned cup centre or
// the other hip mirrored
function stemStart(side) {
  const h = lmGeom('head' + side), cup = S.tpl['cup' + side];
  if (h && !(refOther() && side === S.side)) return h.c;
  if (cup && cup.on && mm()) return cupCentre(cup);   // r38b
  return mirrorTarget(side) || (h && !(refOther() && side === S.side) ? h.c : null);
}
function bestNeck(t, side, target) {
  const c = canalOf(side); if (!c || !(target > 0)) { placeStem(t, side); return null; }
  let best = null;
  for (const [neck] of neckOpts(t)) {
    const tt = { ...t, neck }; placeStem(tt, side);
    const off = G.lineDist(corAt(tt), c.a, c.b) * mm(), err = Math.abs(off - target);
    if (!best || err < best.err) best = { err, neck, off };
  }
  t.neck = best.neck; placeStem(t, side);
  return best;
}
// the offset the operated stem aims for (Auto plan settings): the other hip's native offset when its head and canal are
// marked, else this hip's own; or none (neck 0)
function offsetTarget(side) {
  const rule = S.set.offsetTarget || 'auto', nat = sd => { const h = nativeHead(sd), c = canalOf(sd); return h && c && mm() ? G.lineDist(h.c, c.a, c.b) * mm() : null; };
  if (rule === 'none') return null;
  const o2 = S.scope !== 'one' ? nat(other(side)) : null, own = nat(side);
  if (rule === 'auto' && o2) return { mm: o2, what: `the ${SIDE[other(side)]} hip's native femoral offset` };
  return own ? { mm: own, what: `this hip's own native femoral offset` } : null;
}
function placeStem(t, side) {
  alignStem(t, side);
  const lvl = targetLevel(t, side); if (lvl !== null) setHeadLevel(t, side, lvl);
}
// head height wanted on the pelvic vertical: level with the cup centre (or native head), plus the leg length to gain
function targetLevel(t, side) { const L = levelPlan(side); return L ? L.level : null; }
// The planned height and the intended leg-length change (amendment 2: kept apart from any residual miss). gain (mm, +
// longer): the surgery side's difference to the other leg when legs are to be equal; none for the other side (a
// reference hip keeps its own length) or when the leg is kept as it is. missing: it should be gained but cannot be
// measured (a lesser trochanter or the teardrop line missing): shown as missing, never as no change, and the head is
// set level with the cup.
function levelPlan(side) {
  const cup = S.tpl['cup' + side], h = refOther() && side === S.side ? null : nativeHead(side), target = cup && cup.on ? cupCentre(cup) : h ? h.c : hemiOn(side) ? mirrorTarget(side) : null;   // r38: a bipolar hip: its native head centre (or the other hip mirrored)
  if (!target) return null;
  let gain = 0, missing = false, why = side !== S.side ? 'other' : S.set.legTarget === 'equal' ? 'equal' : 'keep';
  if (why === 'equal') { const lld = legLengthNow(); if (lld !== null) gain = -lld; else missing = true; }
  return { target, gain, missing, why, level: G.dot(target, vertical()) + pxOf(gain) };
}
const vertical = () => { const H = hipFrame(); return H ? H.v : cranVec(); };
// slide along the canal (or the stem's own axis) until the head centre sits at `level` on the pelvic vertical
function setHeadLevel(t, side, level) {
  const c = canalOf(side), g = geomOf(t), v = vertical();
  const up = c ? canalUp(c) : dirOf(t, [g.axis[1][0] - g.axis[0][0], g.axis[1][1] - g.axis[0][1]]);
  const cur = G.dot(corAt(t), v), den = G.dot(up, v), d = Math.abs(den) > 0.3 ? (level - cur) / den : level - cur;
  t.x += up.x * d; t.y += up.y * d;
}
function canalOf(side) { const c = lmGeom('canal' + side); return c && !lmProblem('canal' + side) ? c : null; }
function canalUp(c) { let up = G.unit(G.sub(c.b, c.a)); if (G.dot(up, vertical()) < 0) up = G.mul(up, -1); return up; }
// Turn the stem so its axis lies along the canal line and slide it sideways onto the line (the head height stays).
function alignStem(t, side) {
  const c = canalOf(side); if (!t || !c) return false;
  const g = geomOf(t), up = canalUp(c);
  const ax = dirOf(t, [g.axis[1][0] - g.axis[0][0], g.axis[1][1] - g.axis[0][1]]);
  t.angle = normA(t.angle + G.turn(ax, up));
  const X = xf(t), m = X([(g.axis[0][0] + g.axis[1][0]) / 2, (g.axis[0][1] + g.axis[1][1]) / 2]), q = G.project(m, c.a, up);
  t.x += q.x - m.x; t.y += q.y - m.y;
  return true;
}
// Is the stem lying along the canal (within half a degree and half a millimetre)?
function stemAligned(t, side) {
  const c = canalOf(side); if (!t || !c || !mm()) return false;
  const g = geomOf(t), X = xf(t), a = X(g.axis[0]), b = X(g.axis[1]);
  const ang = G.angleDeg(G.sub(b, a), G.sub(c.b, c.a));
  return Math.min(ang, 180 - ang) < 0.5 && G.lineDist(G.mid(a, b), c.a, c.b) * mm() < 0.5;
}
// The canal's inner cortical edges along the canal landmark (auto.js), kept until the landmark or scale changes.
// the canal's two clicks, higher first, so tracing always runs down the shaft and on past the lower click
function canalHL(c) { return G.dot(G.sub(c.b, c.a), vertical()) > 0 ? { a: c.b, b: c.a } : c; }
// (14 Oct 2026, revision and conversion: amendment 2) keyed by the image and the existing-implant regions' revision too: the
// pixels under a confirmed region are unknown to the trace; while a proposal is unresolved nothing is traced (amendment 1)
const canalKey = c => JSON.stringify([c.a, c.b, mm(), S.key, exRev(), exActive().length > 0]);
function canalTraceFor(side) {
  const c0 = lmGeom('canal' + side); if (!c0 || !mm() || lmProblem('canal' + side)) return null;
  if (exOpen().length) return null;
  const c = canalHL(c0), key = canalKey(c);
  if (!S.canal[side] || S.canal[side].key !== key) { let tr = null; try { tr = traceCanal(S.img, c.a, c.b, mm(), { unknown: exUnknown() }); } catch (e) { tr = null; } S.canal[side] = { key, tr }; }
  return S.canal[side].tr;
}
// After the canal's second click: centre the line between the inner cortical edges, if they were found clearly.
// In a conversion or revision, or with any existing implant marked (amendment 2), the line is never moved by itself: the trace
// on the bone around the confirmed implant is offered (its cortical pairs) for the surgeon to use, or the line is kept as
// clicked. Either way the canal record says how the line was made and against which regions (canalReviewed).
function snapCanal(side) {
  const c0 = lmGeom('canal' + side); S.notes['canal' + side] = '';
  if (!c0 || lmProblem('canal' + side)) return;
  if (revCase() || exRegions().some(r => isExclusion(r) || isUnresolved(r))) {
    rvSetCanal(side, { how: 'hand', mrev: exRev() });
    const tr = canalTraceFor(side);
    S.notes['canal' + side] = exOpen().length ? 'Kept as clicked. Confirm or dismiss the possible hardware first: the canal is then traced on the bone around it.'
      : tr && tr.axis ? `Kept as clicked. Cortical pairs found at ${tr.n} levels over ${Math.round(tr.spanMm)} mm of bone outside the marked implant: "Use the cortical pairs" centres the line on them.`
      : 'Kept as clicked: the inner cortex was not found clearly on the bone outside the marked implant.';
    return;
  }
  if (!S.set.snapCanal) { rvSetCanal(side, { how: 'hand', mrev: exRev() }); return; }
  const c = canalHL(c0);
  let tr = null; try { tr = traceCanal(S.img, c.a, c.b, mm(), { unknown: exUnknown() }); } catch (e) { tr = null; }   // (reached only with no region marked; the mask passed all the same)
  if (tr && tr.axis) {
    const moved = Math.max(G.dist(tr.axis.a, c.a), G.dist(tr.axis.b, c.b)) * mm();
    S.lm['canal' + side] = [tr.axis.a, tr.axis.b];
    rvSetCanal(side, { how: 'snap', mrev: exRev() });   // centred automatically: flagged for review if an existing implant is marked later
    S.notes['canal' + side] = `Centred on the film: inner cortical edges found at ${tr.n} levels over ${Math.round(tr.spanMm)} mm (line fit ${f1(tr.rmsMm)} mm; moved ${f1(moved)} mm). Drag the points to override.`;
  } else { rvSetCanal(side, { how: 'hand', mrev: exRev() }); S.notes['canal' + side] = 'The inner edges of the cortex were not clear here: the line is kept as clicked.'; }
}
// How the stem sits in the canal: at every level where the canal's edges were found and the stem crosses, how far the
// stem's outline reaches past the inner cortex (positive) on either side, and the canal fill.
// the neck cut's medial end above the lesser trochanter's tip, along the canal (mm), or null
function neckCutAboveLT(t, side) {
  const g = geomOf(t), lt = lmGeom('lt' + side); if (!g || !g.neckCut || !lt || !mm() || lmProblem('lt' + side)) return null;
  const c = canalOf(side), up = c ? canalUp(c) : vertical();
  return G.dot(G.sub(xf(t)(g.neckCut.medial), lt.p), up) * mm();
}
function stemFit(t, tr) { const g = geomOf(t), X = xf(t); return canalFit(g.rings[0].map(X), tr, mm(), stemRegion(X(g.axis[0]), X(g.neckCut ? g.neckCut.medial : g.axis[1]), tr, mm())); }
// the stem's length along the traced canal, from where tracing starts (or its neck cut, if lower) to its tip (mm):
// what the trace has to cover before the fit counts
function stemLen(t, tr) { const g = geomOf(t), X = xf(t); return stemRegion(X(g.axis[0]), X(g.neckCut ? g.neckCut.medial : g.axis[1]), tr, mm()).lenMm; }

// ======================= existing implants: revision and conversion (14-15 Oct 2026) =======================
// Sami, 14 Oct: existing implants at the surgical site must not interfere with templating for hip or knee conversion and
// revision; the page identifies them and allows accurate templating; in a revision or conversion the automatic stem stays
// aligned in the bone canal, not on the old implant. DESIGN-2026-10-14-revision-conversion.md with its binding amendments
// after Astra (REVIEW-ASTRA-2026-10-14-revision-design.md); contract section 13.
//  - The case type (Primary / Conversion / Revision) belongs to the operative plan: S.caseType, or a knee case's shared plan.
//  - Each film's existing-implant regions (S.exist; auto.js): proposed by "Find possible hardware" as "possible hardware /
//    opaque region", or drawn by the surgeon (brush or outline) and corrected with "Not an implant". Only regions the surgeon
//    confirmed as hardware or cement exclude pixels; the ball and the side marker are classes of their own. While a proposal
//    is unresolved nothing that reads the film around it runs (canal tracing, stem sizing). The regions are never given to
//    the film cleaner (clean.js), and the cleaner's filled pixels are never read (the trace reads the original film).
//  - The revision record (S.rv): how each canal line was made and against which mask revision, the old implant's tip, holes
//    or defects, the osteotomy, the fixation zone, the revision cup's target, each reference's label (native bone, existing
//    implant, obscured; targets are the surgeon's own), the knee's joint-line target and its evidence.
const CASE_T = PJ.CASE_TYPES;
const CASE_WORD = { primary: 'Primary', conversion: 'Conversion', revision: 'Revision' };
const CASE_LONG = { primary: 'primary', conversion: 'conversion (existing hardware: a DHS, nail, plate, hemiarthroplasty or UKA)', revision: 'revision (failed arthroplasty)' };
const ctype = () => (S.kc ? (S.kc.shared && S.kc.shared.caseType) || 'primary' : S.caseType || 'primary');
const revCase = () => ctype() !== 'primary';
function setCaseType(v) {
  if (!CASE_T.includes(v) || v === ctype() || !S.img) return;
  if (S.kc) S.kc.shared = { ...S.kc.shared, caseType: v }; else S.caseType = v;
  S.canal = {}; REC = { key: null, val: null };
  prompt(v === 'primary' ? 'Primary case.' : `${CASE_WORD[v]}: mark the existing implant (2 · Prepare, Existing implants), confirm it before the canal is traced, and say for each reference whether it is native bone or the existing implant${v === 'revision' ? '; the cup target is yours to set' : ''}.`);
  save(); panel(); draw();
}
const imgSha = () => (S.orig && S.orig.sha256) || (S.src && S.src.hash) || null;
const exRegions = () => (S.exist && Array.isArray(S.exist.regions) ? S.exist.regions : []);
const exActive = () => exRegions().filter(isExclusion);
const exOpen = () => exRegions().filter(isUnresolved);
const exRev = () => (S.exist && Number.isInteger(S.exist.rev) ? S.exist.rev : 0);
const EX_WORD = { hardware: 'Existing implant', cement: 'Cement', unknown: 'Possible hardware / opaque region', ball: 'Calibration ball (not an implant)', marker: 'Side marker (not an implant)', clear: 'Not an implant' };
// the confirmed exclusions as a raster (auto.js exclusionRaster, grown by 1 mm): cached per image and mask revision
let EXR = { key: null, R: null, layer: null };
function exRaster() {
  if (!S.img || !exActive().length) return null;
  const marg = mm() ? 1 / mm() : 2, key = JSON.stringify([S.key, exRev(), Math.round(marg * 100)]);
  if (EXR.key !== key) { let R = null; try { R = exclusionRaster(S.exist, S.img.cols, S.img.rows, { marginPx: marg }); } catch (e) { R = null; } EXR = { key, R, layer: null }; }
  return EXR.R;
}
function exUnknown() { const R = exRaster(); return R ? (x, y) => rasterHas(R, x, y) : null; }
const exNextId = e => e.regions.reduce((m, r) => Math.max(m, r.id || 0), 0) + 1;
const exPts = e => e.regions.reduce((n, r) => n + (Array.isArray(r.pts) ? r.pts.length : 0), 0);
const rp = p => ({ x: Math.round(p.x * 10) / 10, y: Math.round(p.y * 10) / 10 });
// Every change of a film's regions: a new revision (it only grows in this page, also after Undo, so a revision names one set
// of regions), the image it belongs to and its decoded size; the canal traces and the stem's best match made before are
// dropped, and a canal line made before is flagged for review (canalReviewed). fn changes a copy; false: nothing changes.
function exChange(fn, msg = '') {
  if (!S.img) return false;
  const e = S.exist ? { ...S.exist, regions: S.exist.regions.map(r => ({ ...r })) } : { v: 1, sha: null, w: 0, h: 0, coord: 'image-px', rev: 0, none: false, regions: [] };
  if (fn(e) === false) return false;
  if (e.regions.length > PJ.EXIST_MAX.regions || exPts(e) > PJ.EXIST_MAX.total) { prompt(`Not added: a film holds at most ${PJ.EXIST_MAX.regions} existing implant regions and ${PJ.EXIST_MAX.total} outline points. Remove one first.`); return false; }
  S.exRevSeen = Math.max(S.exRevSeen || 0, exRev()) + 1;
  Object.assign(e, { v: 1, sha: imgSha(), w: S.img.cols, h: S.img.rows, coord: 'image-px', rev: S.exRevSeen });
  if (e.regions.some(r => r.cls !== 'clear')) e.none = false;
  S.exist = e; S.canal = {}; REC = { key: null, val: null };
  if (msg) prompt(msg);
  save(); panel(); draw();
  return true;
}
const polyHas = (pts, p) => { let c = false; for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) if ((pts[i].y > p.y) !== (pts[j].y > p.y) && p.x < (pts[j].x - pts[i].x) * (p.y - pts[i].y) / (pts[j].y - pts[i].y) + pts[i].x) c = !c; return c; };
const segDist = (p, a, b) => { const d = G.sub(b, a), L2 = G.dot(d, d), t = L2 > 0 ? Math.max(0, Math.min(1, G.dot(G.sub(p, a), d) / L2)) : 0; return G.dist(p, G.add(a, G.mul(d, t))); };
const regionHas = (r, p) => (r.kind === 'poly' ? polyHas(r.pts, p) : r.pts.some((q, i) => segDist(p, i ? r.pts[i - 1] : q, q) <= r.r));
// "Find possible hardware": proposals only (amendment 1); a region already in the list (any class) is not proposed again
function exDetect() {
  if (!S.img || !S.img.data) return prompt('This picture cannot be searched: mark the existing implant yourself (brush or outline).');
  const est = dicomEstimate(), k = mm() || (est ? est.mmPerPx : null);
  const balls = [...(S.cal && S.cal.ball ? [S.cal.ball] : []), ...(S.auto.balls || []).slice(0, 3)];   // the calibration ball and the round objects found on opening
  let got = []; try { got = findOpaque(S.img, { mmPerPx: k, balls, markers: (S.auto.markers || []).slice(0, 2) }); } catch (e) { got = []; }
  const fresh = got.filter(g => { const c = G.P(g.pts.reduce((a, p) => a + p.x, 0) / g.pts.length, g.pts.reduce((a, p) => a + p.y, 0) / g.pts.length); return !exRegions().some(r => regionHas(r, c)); });
  if (!fresh.length) { prompt(got.length ? 'Nothing new: every region found is already in the list.' : 'Nothing clearly brighter than the bone was found. Mark the existing implant yourself (brush or outline), or tick that this film shows none.'); panel(); return; }
  const nU = fresh.filter(g => g.cls === 'unknown').length;
  exChange(e => { for (const g of fresh) { if (e.regions.length >= PJ.EXIST_MAX.regions) break; e.regions.push({ id: exNextId(e), cls: g.cls, kind: 'poly', pts: g.pts, src: 'detected', conf: false }); } },
    `${nU} possible hardware region${nU === 1 ? '' : 's'} found${fresh.length > nU ? ` (and the ${fresh.filter(g => g.cls !== 'unknown').map(g => g.cls === 'ball' ? 'calibration ball' : 'side marker').join(' and ')}, never excluded)` : ''}: say what each one is (2 · Prepare, Existing implants). Nothing uses them until you confirm.`);
}
function exSet(id, cls) {
  exChange(e => { const r = e.regions.find(x => x.id === id); if (!r) return false; r.cls = cls; r.conf = true; },
    cls === 'clear' ? 'Marked: not an implant.' : `Confirmed as ${cls === 'cement' ? 'cement' : 'existing implant'}: excluded from the automatic measurements.`);
}
function exRemove(id) { exChange(e => { const n = e.regions.length; e.regions = e.regions.filter(r => r.id !== id); return e.regions.length < n; }, 'Region removed.'); }
function exConfirmAll() { exChange(e => { let n = 0; for (const r of e.regions) if (isUnresolved(r)) { r.cls = 'hardware'; r.conf = true; n++; } return n > 0; }, 'Every possible hardware region confirmed as existing implant.'); }
function exAddStroke(st, cls) {
  let pts = st.pts.map(rp);
  if (pts.length > PJ.EXIST_MAX.pts) { const last = pts[pts.length - 1], k = (pts.length - 1) / (PJ.EXIST_MAX.pts - 1); pts = Array.from({ length: PJ.EXIST_MAX.pts }, (_, i) => pts[Math.min(pts.length - 1, Math.round(i * k))]); pts[pts.length - 1] = last; }
  exChange(e => { e.regions.push({ id: exNextId(e), cls, kind: 'stroke', pts, r: Math.max(0.5, Math.round(st.r * 10) / 10), src: 'user', conf: true }); },
    cls === 'clear' ? 'Taken out of the existing implant. Drag over the next part, or tap the button again to stop.' : `Marked as ${cls === 'cement' ? 'cement' : 'existing implant'}. Drag over the next part, or tap the button again to stop.`);
}
function exClosePoly() {
  const a = S.arm; if (!a || a.kind !== 'expoly') return;
  if (a.pts.length < 3) return prompt('An outline needs at least 3 points.');
  const pts = a.pts.slice(0, PJ.EXIST_MAX.pts).map(rp), cls = a.cls || 'hardware'; S.arm = null;
  exChange(e => { e.regions.push({ id: exNextId(e), cls, kind: 'poly', pts, src: 'user', conf: true }); }, `Outline marked as ${cls === 'cement' ? 'cement' : 'existing implant'}.`);
}
const exArmed = () => !!S.arm && ['exbrush', 'exclear', 'expoly'].includes(S.arm.kind);
const brushArm = () => !!S.arm && ['brush', 'exbrush', 'exclear'].includes(S.arm.kind);

// ---------- the revision record ----------
const rvOf = () => (S.rv && typeof S.rv === 'object' ? S.rv : (S.rv = {}));
function rvSet(fn) { const v = clone(rvOf()); fn(v); S.rv = v; }   // a new object every change (the history keeps the old one)
function rvSetCanal(sd, rec) { rvSet(v => { v.canal = { ...(v.canal || {}), [sd]: rec }; }); }
const canalRec = sd => (rvOf().canal || {})[sd] || null;
// Is a side's canal line one the surgeon has seen against the regions as they are now (amendment 2)? Always, in a primary case
// with nothing marked; else only a line centred on the confirmed cortical pairs or kept by hand under this mask revision. A
// line centred automatically, or made before the regions last changed, is flagged for review and nothing is sized on it.
function canalReviewed(sd) {
  if (!exActive().length && !revCase()) return true;
  const r = canalRec(sd);
  return !!r && (r.how === 'pairs' || r.how === 'hand') && r.mrev === exRev();
}
function canalFlag(sd) {
  if (!lmGeom('canal' + sd) || canalReviewed(sd)) return '';
  const r = canalRec(sd);
  return r && r.how === 'snap' ? 'centred on the film automatically before the existing implant was marked: review it (use the cortical pairs, or keep your line)' : r ? 'made before the existing implant regions last changed: review it (use the cortical pairs, or keep your line)' : 'not yet checked against the marked implant: use the cortical pairs, or keep your line';
}
// "Use the cortical pairs": the line through the pairs the trace found on bone (pixels under the confirmed implant are unknown)
function canalUsePairs(sd) {
  const tr = canalTraceFor(sd);
  if (!tr || !tr.axis) return prompt(exOpen().length ? 'Confirm or dismiss the possible hardware first: the canal is then traced on the bone around it.' : 'No usable cortical pairs on the bone outside the marked implant: keep your line, or mark it again over more of the shaft.');
  const moved = Math.max(G.dist(tr.axis.a, canalHL(lmGeom('canal' + sd)).a), G.dist(tr.axis.b, canalHL(lmGeom('canal' + sd)).b)) * mm();
  S.lm['canal' + sd] = [tr.axis.a, tr.axis.b];
  rvSetCanal(sd, { how: 'pairs', mrev: exRev(), n: tr.n, span: Math.round(tr.spanMm) });
  S.notes['canal' + sd] = `Centred on ${tr.n} cortical pairs you confirmed, seen over ${Math.round(tr.spanMm)} mm of bone outside the marked implant (line fit ${f1(tr.rmsMm)} mm; moved ${f1(moved)} mm). Solid where the cortex was seen, dashed where the line is extrapolated.`;
  save(); panel(); draw();
}
function canalKeep(sd) { rvSetCanal(sd, { how: 'hand', mrev: exRev() }); S.notes['canal' + sd] = 'Kept as you placed it (checked against the marked implant).'; save(); panel(); draw(); }
// the stem is sized and placed automatically only when nothing it reads is in doubt (amendments 1 and 2): '' or why not
function stemGate(sd) {
  const nU = exOpen().length;
  if (nU) return `${nU} possible hardware region${nU > 1 ? 's are' : ' is'} not yet confirmed or dismissed (2 · Prepare, Existing implants)`;
  if (revCase() && !exActive().length && !(S.exist && S.exist.none)) return 'no existing implant is marked on this film: mark it (2 · Prepare, Existing implants), or tick that this film shows none';
  if (lmGeom('canal' + sd) && !canalReviewed(sd)) return `the ${SIDE[sd]} canal line is ${canalFlag(sd)}`;
  // (Astra, 16 Oct 2026, P1 4) a conversion or revision, or any implant marked: placed only for a fixation zone the surgeon
  // chose and on a canal traced on bone; and only when the fit is evidence (guardedStem below)
  if (revCase() || exActive().length) {
    if (sd === S.side && !refOther() && !rvOf().fix) return 'no fixation zone is chosen (4 · Implants, Revision): the stem is placed only for a zone you choose';
    const tr = lmGeom('canal' + sd) ? canalTraceFor(sd) : null;
    if (!tr || !tr.axis) return `the ${SIDE[sd]} canal was not traced on enough bone outside the marked implant: place the stem yourself`;
  }
  return '';
}
// a conversion or revision (or any implant marked): the stem is moved only when it can be sized on evidence; otherwise it is
// put back exactly where it was, with the reason (Astra, 16 Oct 2026, P1 4: abstaining on the size moved it all the same)
const guardedStem = () => revCase() || exActive().length > 0;
const STEM_KEYS = ['x', 'y', 'angle', 'fam', 'size', 'neck', 'headKind', 'head', 'userSize'];
const stemKeep = t => Object.fromEntries(STEM_KEYS.map(k => [k, t[k]]));
// Astra, 16 Oct 2026, round 2, P1 2: every path that runs the stem rule after changing the stem (a neck reset, the head
// matched to the cup, a family or size from Mirror) runs inside this: when guarded and the rule refuses, the stem is put back
// exactly as it was before the path began, so nothing is changed ahead of the gate
// (Astra, 16 Oct 2026, round 3) it ends with the same full check as placement (stemEvidenceWhy): the gate, a canal traced on
// bone, and the stem as it now sits being evidence; anything less puts the stem back whole
let IN_GUARD = false;
function stemEvidenceWhy(sd) {
  const g = stemGate(sd); if (g) return g;
  const t = S.tpl['stem' + sd], tr = t ? canalTraceFor(sd) : null;
  if (!t || !tr) return 'the canal is not traced here';
  return fitIsEvidence(stemFit(t, tr), tr, stemLen(t, tr)) ? '' : 'the canal fill is not evidence on the traced canal';
}
function guardStemOp(sd, fn) {
  const t = S.tpl['stem' + sd], k0 = guardedStem() && t ? { ...stemKeep(t), userHead: t.userHead } : null;
  if (k0) delete S.notes['fit' + sd];
  const was = IN_GUARD; IN_GUARD = true;
  let r; try { r = fn(); } finally { IN_GUARD = was; }
  if (k0 && S.tpl['stem' + sd] === t) {
    const refused = S.notes['fit' + sd] && S.notes['fit' + sd].none, why = refused ? '' : stemEvidenceWhy(sd);
    if (refused || why) { Object.assign(t, k0); if (!refused) S.notes['fit' + sd] = { none: true, why: `${why}: the stem is not changed` }; }
  }
  return r;
}
// references (amendment 4): each labelled native bone, existing implant (an observation, never an automatic target) or
// obscured; a reconstruction target is the surgeon's own (the revision cup's, the knee's joint line)
const KNEE_REF = ['condMed', 'condLat', 'platMed', 'platLat', 'postCond', 'platAnt', 'platPost'];
function refChoices(key) {
  if (S.wf === 'hip' && /^head[RL]$/.test(key)) return ['native', 'implant'];
  if (S.wf === 'hip' && /^lt[RL]$/.test(key)) return ['native', 'obscured'];
  if ((S.wf === 'kneeAP' || S.wf === 'kneeLat') && KNEE_REF.includes(key)) return ['native', 'implant'];
  return null;
}
const REF_WORD = { native: 'native bone', implant: 'existing implant', obscured: 'obscured' };
function refLabel(key) {
  if (!revCase()) return 'native';
  const ch = refChoices(key); if (!ch) return 'native';
  const v = (rvOf().refs || {})[key]; if (ch.includes(v)) return v;
  return ctype() === 'revision' && ch.includes('implant') && (key === 'head' + S.side || KNEE_REF.includes(key)) ? 'implant' : 'native';
}
function setRef(key, val) { rvSet(v => { v.refs = { ...(v.refs || {}), [key]: val }; }); REC = { key: null, val: null }; save(); panel(); draw(); }
// the femoral head of a side as a reconstruction reference: only native bone (an old prosthetic head is an observation)
function nativeHead(sd) { const h = lmGeom('head' + sd); return h && refLabel('head' + sd) !== 'implant' ? h : null; }
const ltObscured = () => refLabel('lt' + S.side) === 'obscured' || refLabel('lt' + other(S.side)) === 'obscured';
// a revision cup (or one over an old head) is placed only on a target the surgeon set (amendment 4)
const cupNeedsTarget = sd => S.wf === 'hip' && sd === S.side && !refOther() && (ctype() === 'revision' || refLabel('head' + sd) === 'implant');
function setCupTargetMirror() {
  const H = hipFrame(S.side), hn = nativeHead(other(S.side));
  if (!H || H.single || !hn) return prompt(`Mark both teardrops and the ${SIDE[other(S.side)]} femoral head (native bone) first: the target is that head's centre mirrored about the pelvic midline.`);
  rvSet(v => { v.cupT = { how: 'mirror', p: rp(reflect(hn.c, H)) }; }); save(); panel(); draw();
  prompt(`Cup target set: the ${SIDE[other(S.side)]} native head centre, mirrored. Press Auto template (or Auto place on the cup).`);
}
// the knee's joint-line target (amendment 4): from the contralateral knee (its suitability confirmed by the surgeon) or from
// the epicondylar line and the fibular head, both marked and confirmed to agree; otherwise no automatic placement
function jlState() {
  const j = rvOf().jl || {};
  if (!(Array.isArray(j.line) && j.line.length === 2)) return { ok: false, why: 'no joint-line target marked' };
  if (j.src === 'contra') return j.suit ? { ok: true } : { ok: false, why: 'the contralateral knee is not confirmed as suitable' };
  if (j.src === 'refs') return j.epi && j.fib && j.agree ? { ok: true } : { ok: false, why: 'the epicondylar line and the fibular head must both be marked and confirmed to agree with the target' };
  return { ok: false, why: 'its source is not said (the contralateral knee, or the epicondylar and fibular references)' };
}
function kneeRevWhy() {
  const nU = exOpen().length;   // (Astra, 16 Oct 2026, P1 5) before anything is created, sized or placed, in any case type
  if (nU) return `${nU} possible hardware region${nU > 1 ? 's are' : ' is'} not yet confirmed or dismissed (2 · Prepare, Existing implants): nothing is placed automatically until ${nU > 1 ? 'they are' : 'it is'}.`;
  if (!revCase()) return '';
  if (S.wf === 'kneeAP' && ['condMed', 'condLat', 'platMed', 'platLat'].some(k => refLabel(k) === 'implant')) {
    const j = jlState(); if (!j.ok) return `Revision joint line: insufficient evidence (${j.why}). With the condyles or plateaus on the existing implant the page places nothing automatically: set the joint-line target (4 · Implants, Revision joint line), or place the components by hand.`;
  }
  if (S.wf === 'kneeLat' && ['postCond', 'platAnt', 'platPost'].some(k => refLabel(k) === 'implant')) return 'The posterior condyles or the plateau on this lateral are marked as the existing implant: the page does not place the components on it automatically; place them by hand.';
  return '';
}
const jlTarget = () => { const j = rvOf().jl; return revCase() && S.wf === 'kneeAP' && jlState().ok && ['condMed', 'condLat', 'platMed', 'platLat'].some(k => refLabel(k) === 'implant') ? { a: j.line[0], b: j.line[1] } : null; };
// taps that record the revision (the arms of armedClick): how many points each takes
const RV_ARMS = { rvtip: 1, rvhole: 1, rvosteo: 2, rvfix: 2, cupT: 1, jlline: 2, jlepi: 2, jlfib: 1 };
const RV_NAME = { rvtip: 'Old implant tip', rvhole: 'Hole or defect', rvosteo: 'Osteotomy (its two ends)', rvfix: 'Fixation zone (its two ends, along the canal)', cupT: 'Cup target', jlline: 'Joint-line target (two points)', jlepi: 'Epicondylar line (medial, then lateral epicondyle)', jlfib: 'Fibular head tip' };
function rvArm(kind) { S.arm = S.arm && S.arm.kind === kind ? null : { kind, pts: [] }; prompt(S.arm ? `${RV_NAME[kind]}: tap it on the X-ray. Esc to stop.` : ''); panel(); draw(); }
function rvArmed(a, ip) {
  a.pts.push(ip);
  if (a.pts.length < RV_ARMS[a.kind]) return prompt(`${RV_NAME[a.kind]}: the second point.`);
  const P = a.pts.map(rp);
  rvSet(v => {
    if (a.kind === 'rvtip') v.tip = P[0];
    else if (a.kind === 'rvhole') v.holes = [...(v.holes || []), P[0]].slice(-20);
    else if (a.kind === 'rvosteo') v.osteo = P;
    else if (a.kind === 'rvfix') v.fix = P;
    else if (a.kind === 'cupT') v.cupT = { how: 'point', p: P[0] };
    else {
      const j = { src: null, line: null, suit: false, epi: null, fib: null, agree: false, ...(v.jl || {}) };
      if (a.kind === 'jlline') j.line = P; else if (a.kind === 'jlepi') j.epi = P; else j.fib = P[0];
      j.agree = false;   // a reference or the target moved: their agreement is confirmed again
      v.jl = j;
    }
  });
  if (a.kind === 'rvhole' && (rvOf().holes || []).length < 20) { a.pts = []; prompt('Hole or defect marked: tap the next one, or Esc (or the button again) to stop.'); }
  else { S.arm = null; prompt(`${RV_NAME[a.kind].replace(/ \(.*$/, '')} marked.`); }
  save();
}
function setJl(o) { rvSet(v => { v.jl = { src: null, line: null, suit: false, epi: null, fib: null, agree: false, ...(v.jl || {}), ...o }; }); save(); panel(); draw(); }
// a point's place along the traced canal (trace units: px from where tracing starts)
const alongTr = (p, tr) => G.dot(G.sub(p, tr.origin), tr.d);

// ---------- results ----------
function caseRows(R) {
  R.push([`Case: ${CASE_WORD[ctype()].toLowerCase()}`]);
  if (revCase()) R.push(['Case type', CASE_LONG[ctype()]]);
  const nA = exActive().length, nU = exOpen().length, cl = { hardware: 0, cement: 0 }; for (const r of exActive()) cl[r.cls]++;
  R.push(['Existing implants', nA ? `${nA} region${nA > 1 ? 's' : ''} confirmed (${[cl.hardware && `${cl.hardware} implant`, cl.cement && `${cl.cement} cement`].filter(Boolean).join(', ')}), excluded from the automatic measurements` : S.exist && S.exist.none ? 'none on this film (confirmed)' : 'none marked on this film', nA || (S.exist && S.exist.none) || !revCase() ? '' : 'warn']);
  if (nU) R.push(['Possible hardware not confirmed', `${nU} region${nU > 1 ? 's' : ''}: confirm or dismiss ${nU > 1 ? 'them' : 'it'} (2 · Prepare, Existing implants); the canal is not traced and no stem is sized meanwhile`, 'bad']);
  if (S.cal && S.cal.ball && rasterHas(exRaster(), S.cal.ball.x, S.cal.ball.y)) R.push(['Calibration ball', 'lies on a region marked as existing implant: check that the pink circle is the ball, not an implant head', 'bad']);
  if (!revCase()) return;
  const keys = lmDefs().map(d => d.key).filter(k => refChoices(k) && lmGeom(k));
  if (keys.length || (S.wf === 'hip' && S.tpl['cup' + S.side])) R.push(['References (native bone, existing implant, target)']);
  for (const k of keys) { const l = refLabel(k); R.push([lmDef(k).label, l === 'implant' ? 'existing implant: an observation, never an automatic target' : l === 'obscured' ? 'obscured: what depends on it is not measured' : 'native bone']); }
  if (S.wf === 'hip') {
    const T = rvOf().cupT, need = cupNeedsTarget(S.side);
    R.push(['Cup target', T ? `target set by you (${T.how === 'mirror' ? `the ${SIDE[other(S.side)]} native head centre, mirrored` : 'a point you chose'})` : need ? 'not set: the cup is not placed automatically until you set it (4 · Implants, Revision)' : 'the cup rule on native bone (the head is marked native)', T || !need ? '' : 'warn']);
    if (ctype() === 'revision') R.push(['Acetabular bone defect', 'defect not assessed by the page: judge the defect, coverage and fixation yourself', 'warn']);
  }
}
// The stem in a conversion or revision (amendment 3): the old implant's tip, holes or defects, the osteotomy and the fixation
// zone recorded separately; the projected potential contact length in the chosen zone (AP film only), never "engagement"; no
// length rule and no family suggestion.
function revHipRows(R, sd, stem, k) {
  const v = rvOf(), c = canalOf(sd), tr = c ? canalTraceFor(sd) : null;
  R.push([`${CASE_WORD[ctype()]}: the canal and fixation (${SIDE[sd]}, this AP film)`]);
  const fl = canalFlag(sd), rec = canalRec(sd);
  R.push(['Canal axis', !c ? 'not marked' : fl ? fl : rec && rec.how === 'pairs' ? `from ${rec.n} cortical pairs you confirmed, seen over ${rec.span} mm of bone (solid on the film; dashed where extrapolated)` : 'your line, checked against the marked implant', fl ? 'bad' : '']);
  if (tr && tr.observed) { const seen = tr.observed.reduce((a, r) => a + (r.to - r.from) * k, 0); R.push(['Cortex seen along the canal', `${Math.round(seen)} mm in ${tr.observed.length} stretch${tr.observed.length > 1 ? 'es' : ''}${tr.masked ? `; ${tr.masked} level${tr.masked > 1 ? 's' : ''} met the marked implant (unknown, not read)` : ''}`]); }
  const along = p => (tr ? alongTr(p, tr) * k : null);
  R.push(['Old implant tip', v.tip ? (tr ? `marked, ${f1(along(v.tip))} mm down the canal` : 'marked') : 'not marked', v.tip ? '' : 'warn']);
  const holes = v.holes || [];
  R.push(['Distal holes or defects', holes.length ? `${holes.length} marked${tr ? ` (${holes.map(p => `${f1(along(p))} mm`).join(', ')} down the canal)` : ''}` : 'none marked']);
  if (v.osteo) R.push(['Osteotomy', `${f1(G.dist(v.osteo[0], v.osteo[1]) * k)} mm long${tr ? ` (${f1(Math.min(along(v.osteo[0]), along(v.osteo[1])))} to ${f1(Math.max(along(v.osteo[0]), along(v.osteo[1])))} mm down the canal)` : ''}`]);
  else R.push(['Osteotomy', 'none marked']);
  if (!v.fix) { R.push(['Fixation zone', 'not chosen: mark it (4 · Implants, Revision) to see the projected contact', 'warn']); return; }
  const z = tr ? { from: alongTr(v.fix[0], tr), to: alongTr(v.fix[1], tr) } : null;
  R.push(['Fixation zone (your choice)', `${f1(G.dist(v.fix[0], v.fix[1]) * k)} mm${z ? `, ${f1(Math.min(z.from, z.to) * k)} to ${f1(Math.max(z.from, z.to) * k)} mm down the canal` : ''}`]);
  if (z && holes.length) { const inZ = holes.filter(p => { const s = alongTr(p, tr); return s >= Math.min(z.from, z.to) && s <= Math.max(z.from, z.to); }).length; if (inZ) R.push(['Holes or defects in the zone', `${inZ}: bypass and fixation are your judgement`, 'warn']); }
  if (z && v.tip) { const s = alongTr(v.tip, tr); R.push(['Zone against the old tip', `the zone starts ${f1(Math.abs(Math.min(z.from, z.to) - s) * k)} mm ${Math.min(z.from, z.to) >= s ? 'below' : 'above'} the old implant tip`]); }
  if (!stem || !stem.on) return;
  if (!tr || !z) { R.push(['Projected potential contact length in the chosen zone (AP film only)', 'not measured: the canal is not traced here', 'warn']); return; }
  const g = geomOf(stem), X = xf(stem), cz = contactInZone(g.rings[0].map(X), tr, k, z, 1);
  R.push(['Projected potential contact length in the chosen zone (AP film only)', `${Math.round(cz.contactMm)} mm of the ${Math.round(cz.zoneMm)} mm zone (an outline edge within 1 mm of the inner cortex, not past it); ${Math.round(cz.observedMm)} mm of the zone observed, the rest hidden by the marked implant or not traced`, cz.observedMm < 0.5 * cz.zoneMm ? 'warn' : '']);
  if (cz.penetrationLevels) R.push(['Cortical penetration in the zone (AP film only)', `${Math.round(cz.penetrationMm)} mm where the outline reaches more than 1 mm past the inner cortex: not contact`, 'warn']);
  if (cz.outsideLevels) R.push(['Outline outside the canal in the zone', `${Math.round(cz.outsideMm)} mm where the stem outline lies beside the canal, not in it`, 'bad']);
  if (v.tip) { const tipS = alongTr(X(g.axis[0]), tr), s = alongTr(v.tip, tr); R.push(['New stem tip against the old implant tip', `${f1(Math.abs(tipS - s) * k)} mm ${tipS >= s ? 'beyond' : 'short of'} it (a distance, not a rule)`]); }
  R.push(['Not decided by the page', 'a projection on one film, not engagement: the lateral film, bone quality, removal, the fixation and stability at surgery decide; no stem family or length is suggested (long stems are listed as available sizes only)']);
}
function jlRows(R, k) {
  const j = rvOf().jl || {}, st = jlState(), need = ['condMed', 'condLat', 'platMed', 'platLat'].some(x => refLabel(x) === 'implant');
  R.push(['Revision joint line (target)']);
  R.push(['Source', j.src === 'contra' ? `the contralateral knee${j.suit ? ' (confirmed suitable)' : ' (suitability not confirmed)'}` : j.src === 'refs' ? `the epicondylar line and the fibular head${j.agree ? ' (confirmed to agree)' : ' (not confirmed to agree)'}` : 'not said', st.ok || !need ? '' : 'warn']);
  if (j.line && j.epi) R.push(['Target below the epicondylar line', k ? `${f1(G.lineDist(G.mid(j.line[0], j.line[1]), j.epi[0], j.epi[1]) * k)} mm` : 'needs a scale']);
  if (j.line && j.fib) R.push(['Target above the fibular head tip', k ? `${f1(G.lineDist(j.fib, j.line[0], j.line[1]) * k)} mm` : 'needs a scale']);
  R.push(['Automatic placement', !need ? 'the condyles and plateaus are marked native bone: placed on them as usual' : st.ok ? 'the components\' joint line on the target (the resection against the old component is not measured)' : `insufficient evidence (${st.why}): no automatic placement`, !need || st.ok ? '' : 'bad']);
}

// ---------- drawing ----------
// The confirmed exclusions dimmed, with a dashed outline round them (cells of the raster; one picture for the screen and the
// saved picture), the unresolved proposals outlined in amber, the revision record's marks. T: image px -> canvas units.
function exLayer() {
  const R = exRaster(); if (!R) return null;
  if (EXR.layer) return EXR.layer;
  const c = document.createElement('canvas'); c.width = R.W; c.height = R.H;
  const g = c.getContext('2d'), id = g.createImageData(R.W, R.H), px = id.data, m = R.m, W = R.W, H = R.H;
  for (let j = 0; j < H; j++) for (let i = 0; i < W; i++) {
    const q = j * W + i; if (!m[q]) continue;
    const edge = i === 0 || j === 0 || i === W - 1 || j === H - 1 || !m[q - 1] || !m[q + 1] || !m[q - W] || !m[q + W];
    if (edge && ((i + j) >> 2) % 2 === 0) { px[4 * q] = 255; px[4 * q + 1] = 176; px[4 * q + 2] = 64; px[4 * q + 3] = 240; }   // the dashed outline
    else if (!edge) { px[4 * q + 3] = 110; }   // dimmed
  }
  g.putImageData(id, 0, 0); EXR.layer = c; return c;
}
function exDraw(g, T, lw, ui, line, label, showLabels) {
  const L = exLayer(), R = EXR.R;
  if (L && R) {   // the raster as an image under the same mapping as the film
    const o = T(G.P(0, 0)), ex = G.sub(T(G.P(1, 0)), o), ey = G.sub(T(G.P(0, 1)), o);
    g.save(); g.transform(ex.x, ex.y, ey.x, ey.y, o.x, o.y); g.imageSmoothingEnabled = false;
    g.drawImage(L, -0.5, -0.5, R.W * R.f, R.H * R.f); g.restore();
  }
  for (const r of exRegions()) {
    if (!(isExclusion(r) || isUnresolved(r)) || !r.pts.length) continue;
    const un = isUnresolved(r);
    if (un) for (let i = 0; i < r.pts.length; i++) line(r.pts[i], r.pts[(i + 1) % r.pts.length], '#ffb000', 1.4, [5, 4]);
    if (showLabels) label(r.pts[0], un ? 'possible hardware: confirm' : r.cls === 'cement' ? 'cement (excluded)' : 'existing implant (excluded)', un ? '#ffb000' : '#ffb040');
  }
}
function rvDraw(g, T, k, line, dot, cross, ring, label, showLabels) {
  if (!revCase()) return;
  const v = rvOf(), C = '#ff8a1f';
  if (S.wf === 'hip') {
    if (v.tip) { cross(v.tip, C, 7); if (showLabels) label(v.tip, 'old implant tip', C); }
    for (const p of v.holes || []) ring(p, k ? pxOf(2.5) : 6, C, 1.3);
    if ((v.holes || []).length && showLabels) label(v.holes[0], `hole or defect${v.holes.length > 1 ? 's' : ''}`, C);
    if (v.osteo) { line(v.osteo[0], v.osteo[1], C, 1.6, [3, 3]); if (showLabels) label(v.osteo[1], 'osteotomy', C); }
    if (v.fix) { line(v.fix[0], v.fix[1], '#A8FF30', 3.2); if (showLabels) label(v.fix[0], 'fixation zone (your choice)', '#A8FF30'); }
    if (v.cupT) { cross(v.cupT.p, '#ffffff', 9); ring(v.cupT.p, k ? pxOf(4) : 10, '#ffffff', 1.2, [3, 3]); if (showLabels) label(v.cupT.p, 'cup target (yours)', '#ffffff'); }
  } else if (S.wf === 'kneeAP' && v.jl) {
    const j = v.jl;
    if (j.line) { line(j.line[0], j.line[1], '#ffffff', 1.6, [8, 5]); if (showLabels) label(j.line[1], 'joint-line target', '#ffffff'); }
    if (j.epi) { line(j.epi[0], j.epi[1], C, 1.2, [3, 3]); if (showLabels) label(j.epi[1], 'epicondylar line', C); }
    if (j.fib) { cross(j.fib, C, 6); if (showLabels) label(j.fib, 'fibular head tip', C); }
  }
}

// ---------- the panel ----------
function caseTypeSel(short = false) {
  const l = el(`<label class="note casetype">${short ? 'Case' : 'Case type'} <select aria-label="Case type"><option value="primary">Primary</option><option value="conversion">Conversion (existing hardware)</option><option value="revision">Revision (failed arthroplasty)</option></select></label>`);
  const s = l.querySelector('select'); s.value = ctype(); s.onchange = () => setCaseType(s.value);
  return l;
}
// 2 · Prepare: the existing implants (phone-friendly: large buttons, a brush, an outline, an eraser)
function existCard() {
  const d = el('<div class="exist" id="existCard"><div class="kch">Existing implants (conversion, revision)</div></div>');
  d.appendChild(caseTypeSel());
  const on = k => S.arm && S.arm.kind === k, cls = (S.arm && S.arm.cls) || S.exCls || 'hardware';
  const r1 = el('<div class="row"></div>');
  r1.append(btn('Find possible hardware', exDetect, '', !S.img.data, 'Propose regions brighter than the bone (metal, cement, contrast): you say what each one is'),
    btn(on('exbrush') ? 'Marking (tap to stop)' : 'Mark existing implant', () => { S.arm = on('exbrush') ? null : { kind: 'exbrush', cls }; prompt(S.arm ? 'Drag over the existing implant (brush). Each stroke is confirmed as you draw it.' : ''); panel(); draw(); }, on('exbrush') ? 'on' : '', false, 'Paint over the old implant, screws, wires or cement'),
    btn(on('expoly') ? 'Outlining…' : 'Outline', () => { S.arm = on('expoly') ? null : { kind: 'expoly', pts: [], cls }; prompt(S.arm ? 'Outline the existing implant: tap round it, then tap the first point again (or "Close the outline").' : ''); panel(); draw(); }, on('expoly') ? 'on' : '', false, 'Tap round the implant'),
    btn(on('exclear') ? 'Erasing (tap to stop)' : 'Not an implant', () => { S.arm = on('exclear') ? null : { kind: 'exclear' }; prompt(S.arm ? 'Drag over what is not an implant (bone, contrast) to take it out of the marked regions.' : ''); panel(); draw(); }, on('exclear') ? 'on' : '', false, 'Take an area out of the marked implant'));
  const cs = el('<label class="note">as <select aria-label="Mark as"><option value="hardware">implant (metal)</option><option value="cement">cement</option></select></label>'), csS = cs.querySelector('select');
  csS.value = cls; csS.onchange = () => { S.exCls = csS.value; if (S.arm && (S.arm.kind === 'exbrush' || S.arm.kind === 'expoly')) S.arm.cls = csS.value; };
  r1.appendChild(cs); d.appendChild(r1);
  if (on('expoly')) { const rp_ = el('<div class="row"></div>'); rp_.append(btn('Close the outline', exClosePoly, 'primary', S.arm.pts.length < 3), btn('Cancel', () => { S.arm = null; prompt(''); panel(); draw(); })); d.appendChild(rp_); }
  const regs = exRegions().filter(r => r.cls !== 'clear' || r.src === 'detected');
  if (regs.length) {
    const ul = el('<ul class="exlist"></ul>');
    regs.forEach(r => {
      const li = el(`<li><span>${esc(EX_WORD[r.cls])} ${r.id}${r.src === 'detected' ? ' (found)' : ' (marked by you)'}${isUnresolved(r) ? ': <b>what is it?</b>' : ''}</span></li>`), row = el('<span class="row"></span>');
      if (r.cls === 'unknown' || r.cls === 'ball' || r.cls === 'marker') row.append(btn('Existing implant', () => exSet(r.id, 'hardware'), isUnresolved(r) ? 'primary' : ''), btn('Cement', () => exSet(r.id, 'cement')));
      if (r.cls === 'unknown') row.appendChild(btn('Not an implant', () => exSet(r.id, 'clear')));
      if (r.src === 'user' || r.cls === 'hardware' || r.cls === 'cement' || r.cls === 'clear') row.appendChild(btn('Remove', () => exRemove(r.id), 'x'));
      li.appendChild(row); ul.appendChild(li);
    });
    d.appendChild(ul);
    if (exOpen().length > 1) d.appendChild(btn(`Confirm all ${exOpen().length} as existing implant`, exConfirmAll));
  }
  if (!exActive().length && !exOpen().length) {
    const nl = el('<label class="note"><input type="checkbox"> This film shows no existing implant</label>'), cb = nl.querySelector('input');
    cb.checked = !!(S.exist && S.exist.none); cb.onchange = () => exChange(e => { e.none = cb.checked; });
    d.appendChild(nl);
  }
  d.appendChild(help('<p>"Find possible hardware" proposes regions brighter than the bone; brightness cannot tell metal from cement, contrast or dense cortex, so each one waits for you: an existing implant, cement, or not an implant. The calibration ball and the side marker are never excluded. A confirmed region is dimmed with a dashed outline; its pixels are unknown to the page: the canal is traced only on the bone around it, never on its edges, and nothing is filled in across it. While a proposal waits, the canal is not traced and no stem is sized. Erasing (Prepare, Erase) never uses these regions, and what it fills is never measured.</p>', 'How the existing implant is used'));
  return d;
}
// 4 · Implants: the hip revision record (fixation zone, old tip, holes, osteotomy) and the cup target
function revCard() {
  const d = el(`<div class="rev" id="revCard"><div class="kch">${esc(CASE_WORD[ctype()])}: fixation and targets</div></div>`), v = rvOf(), on = k => S.arm && S.arm.kind === k;
  const b = (k, text, title, has) => btn(on(k) ? `${text}: tap it…` : has ? `${text} ✓` : text, () => rvArm(k), on(k) ? 'on' : '', !S.img, title);
  const r1 = el('<div class="row"></div>');
  r1.append(b('rvfix', 'Fixation zone', 'Two taps along the canal: where you plan the new stem to fix', !!v.fix), b('rvtip', 'Old implant tip', 'The tip of the old stem or nail', !!v.tip), b('rvhole', 'Hole or defect', 'A distal screw hole, a cortical window or defect (tap each; up to 20)', !!(v.holes || []).length), b('rvosteo', 'Osteotomy', 'Its two ends (an extended trochanteric osteotomy, a window)', !!v.osteo));
  d.appendChild(r1);
  if ((v.holes || []).length || v.tip || v.osteo || v.fix) d.appendChild(btn('Clear these marks', () => { rvSet(x => { delete x.tip; delete x.holes; delete x.osteo; delete x.fix; }); save(); panel(); draw(); }));
  const r2 = el('<div class="row"></div>');
  r2.append(btn(`Cup target: ${SIDE[other(S.side)]} hip mirrored`, setCupTargetMirror, v.cupT && v.cupT.how === 'mirror' ? 'on' : '', !mm(), `The ${SIDE[other(S.side)]} native head centre, mirrored about the pelvic midline`),
    btn(on('cupT') ? 'Cup target: tap it…' : 'Cup target: a point', () => rvArm('cupT'), on('cupT') || (v.cupT && v.cupT.how === 'point') ? 'on' : '', !mm(), 'Tap the centre you want for the new cup'));
  if (v.cupT) r2.appendChild(btn('Clear the target', () => { rvSet(x => { delete x.cupT; }); save(); panel(); draw(); }));
  d.appendChild(r2);
  d.appendChild(el(`<p class="note">${cupNeedsTarget(S.side) ? '<b>The cup is placed only on a target you set</b> (the old cup or head centre is an observation: it may have migrated). ' : ''}${ctype() === 'revision' ? 'Defect not assessed by the page. ' : ''}The stem lies along the bone canal (the cortical pairs you confirmed), never along the old implant; it is sized only when the canal fill is evidence. The page suggests no stem family and no length: long stems are listed as available sizes only. It reports the projected potential contact length in the zone you chose, on this AP film only.</p>`));
  return d;
}
// 4 · Implants: the knee AP's joint-line target and its evidence
function jlCard() {
  const d = el('<div class="rev" id="jlCard"><div class="kch">Revision joint line</div></div>'), j = rvOf().jl || {}, on = k => S.arm && S.arm.kind === k;
  const src = el('<label class="note">Target from <select aria-label="Joint-line target from"><option value="">(say where from)</option><option value="contra">the contralateral knee</option><option value="refs">the epicondyles and the fibular head</option></select></label>'), ss = src.querySelector('select');
  ss.value = j.src || ''; ss.onchange = () => setJl({ src: ss.value || null }); d.appendChild(src);
  const r1 = el('<div class="row"></div>'), b = (k, text, has) => btn(on(k) ? `${text}: tap it…` : has ? `${text} ✓` : text, () => rvArm(k), on(k) ? 'on' : '');
  r1.appendChild(b('jlline', 'Mark the target joint line', !!j.line));
  if (j.src === 'refs') r1.append(b('jlepi', 'Epicondylar line', !!j.epi), b('jlfib', 'Fibular head tip', !!j.fib));
  d.appendChild(r1);
  const cb = (text, key, dis) => { const l = el(`<label class="note"><input type="checkbox"${dis ? ' disabled' : ''}> ${esc(text)}</label>`), i = l.querySelector('input'); i.checked = !!j[key]; i.onchange = () => setJl({ [key]: i.checked }); return l; };
  if (j.src === 'contra') d.appendChild(cb('The contralateral knee is suitable (native, not deformed): I have checked it', 'suit'));
  if (j.src === 'refs') d.appendChild(cb('The epicondylar line and the fibular head agree with this target (checked by me)', 'agree', !(j.epi && j.fib && j.line)));
  const st = jlState();
  d.appendChild(el(`<p class="${st.ok ? 'note' : 'warnbox'}">${st.ok ? 'Joint-line target set: Auto template puts the components\' joint line on it.' : `No automatic placement while the condyles or plateaus are on the existing implant: ${esc(st.why)}.`} Resections against the old component, ligament balance and constraint are not decided from the films.</p>`));
  return d;
}

// ---------- head centre on the cup centre (13 Oct 2026; DESIGN-2026-10-13-autotemplate.md B, Astra's amendments 2-4) ----------
// The versions of one stem the search may choose among, open to this account: the Addis Ortho BE2 133S/133H/127S/127H,
// and for any other maker the family and the families it names as its offset twins (offsetPair), both ways. Each version
// is placed with its own outline, neck-shaft angle and offset (the library's sizes), never by a formula.
function stemVersions(fid) {
  const f = S.fam[fid]; if (!f || f.kind !== 'stem') return [];
  if (isPfr(f)) return [fid];   // r32
  const stems = famsFor('stem').filter(g => !isPfr(g)), out = new Set([fid]), be2 = /^chunli-be2-/;
  if (be2.test(fid)) for (const g of stems) if (be2.test(g.id)) out.add(g.id);
  const links = g => [].concat(Array.isArray(g.offsetPair) ? g.offsetPair : typeof g.offsetPair === 'string' ? [g.offsetPair] : [], OFFSET_PAIR[g.id] || []);
  for (let grew = true, n = 0; grew && n < 20; n++) {
    grew = false;
    for (const g of stems) {
      const ls = links(g).filter(id => S.fam[id] && S.fam[id].kind === 'stem');
      if (out.has(g.id)) { for (const id of ls) if (!out.has(id)) { out.add(id); grew = true; } }
      else if (ls.some(id => out.has(id))) { out.add(g.id); grew = true; }
    }
  }
  return stems.map(g => g.id).filter(id => out.has(id));   // in library order
}
// Combined offset (amendment 2): acetabular (teardrop to the hip centre, along the teardrop line) plus femoral (hip
// centre to the canal axis), mm. Of a native hip (its marked head), or null when not measured.
function combinedOf(sd) {
  const h = nativeHead(sd), c = canalOf(sd), H = hipFrame(sd), k = mm();
  if (!h || !c || !H || !k || lmProblem('head' + sd)) return null;
  const acc = G.dot(G.sub(h.c, H.T), H.u) * k, fem = G.lineDist(h.c, c.a, c.b) * k;
  return { acc, fem, mm: acc + fem };
}
// what a side's plan is compared with: the other hip as it is when the surgery side is planned on its own anatomy and
// that hip is marked (as the offset rule does), else this hip as it is; the deformed hip of a mirrored plan is not used
function nativeCombined(sd) {
  if (refOther() && sd === S.side) return null;
  const oth = sd === S.side && S.scope !== 'one' ? combinedOf(other(sd)) : null, own = combinedOf(sd), use = oth || own;
  return use ? { ...use, what: oth ? `the ${SIDE[other(sd)]} hip as it is` : `this hip as it is` } : null;
}
// The plan's combined offset now: the cup centre's acetabular offset plus the stem head's femoral offset (mm)
function combinedNow(sd) {
  const cup = S.tpl['cup' + sd], stem = S.tpl['stem' + sd], c = canalOf(sd), H = hipFrame(sd), k = mm();
  if (!cup || !cup.on || !stem || !stem.on || !c || !H || !k) return null;
  const C = cupCentre(cup);   // r38b: the head centre (a +4 liner's is 4 mm out of the shell)
  return G.dot(G.sub(C, H.T), H.u) * k + G.lineDist(headAt(stem), c.a, c.b) * k;
}
// Every version, size and neck of the stem in this slot placed on the canal at the planned height, with its canal fit
// and how far its head centre lies from the cup centre (pelvic axes). keepFam: only the slot's family (chosen by hand);
// keepSize: only its size too (set by hand). The slot itself is not changed.
function stemCandidates(sd, { keepFam = false, keepSize = false } = {}) {
  const t = S.tpl['stem' + sd], cup = S.tpl['cup' + sd], c = canalOf(sd), k = mm();
  if (!t || !cup || !cup.on || !c || !k || isPfr(S.fam[t.fam])) return null;   // r32: a PFR is no candidate
  const L = levelPlan(sd); if (!L) return null;
  const H = hipFrame(sd), u = H ? H.u : latVec(sd), v = vertical(), C = cupCentre(cup), tr = canalTraceFor(sd), nat = nativeCombined(sd);
  const accNew = H ? G.dot(G.sub(C, H.T), H.u) * k : null;
  const fams = keepFam || keepSize ? [t.fam] : stemVersions(t.fam), cls = offsetClasses(fams.map(id => S.fam[id]));
  const out = [];
  for (const fid of fams) {
    const f = S.fam[fid], shown = sizesShown(f, fid === t.fam ? t : null), list = keepSize ? shown.filter(z => z.size === t.size) : shown;
    for (const z of list) {
      const tt = { ...t, fam: fid, size: z.size };
      if (!headKindsOf(f).includes(tt.headKind) || !((S.lib.heads[tt.headKind] || {}).sizes || {})[tt.head]) setHead(tt, tt.headKind, tt.head);
      for (const [neck] of neckOpts(tt)) {
        const t2 = { ...tt, neck }; placeStem(t2, sd);
        const hp = headAt(t2), d = G.sub(hp, C), fit = tr ? stemFit(t2, tr) : null, ev = !!fit && fitIsEvidence(fit, tr, stemLen(t2, tr));
        const off = G.lineDist(hp, c.a, c.b) * k;
        out.push({ fam: fid, size: z.size, sizeIdx: shown.indexOf(z), neck, headKind: t2.headKind, head: t2.head, missH: G.dot(d, u) * k, missV: G.dot(d, v) * k - L.gain,
          ev, maxIn: fit ? fit.maxIn : null, fill: fit ? fit.fillDistal : null, off, comb: nat && accNew !== null ? accNew + off - nat.mm : null, cls: cls[fid] });
      }
    }
  }
  return { cands: out, L, nat, tr, C };
}
// Put the stem's head centre on the cup centre (the default stem rule): the stem along the canal, its head at the cup
// centre's height plus the planned lengthening; each version sized by canal fill (its largest size inside the traced inner
// cortex), then of those versions and their necks the one whose head centre comes nearest the cup centre horizontally
// (autoplan.chooseStem; revised amendment 3, 13 Oct). A size or family set by hand is
// kept (only the neck is chosen then). No suitable match: the stem is left as it is, with the reason, never oversized.
// Returns the note it leaves (S.notes['stemPlan' + sd]), or null when it cannot run (no cup, no canal, no scale).
function stemToCup(sd, { keepFam = false, keepSize = false } = {}) {
  const t = S.tpl['stem' + sd]; if (!t) return null;
  const kept = guardedStem() ? stemKeep(t) : null;
  const gate = stemGate(sd);   // revision and conversion (amendments 1 and 2): the stem waits; said, never placed on the old implant
  if (gate) { S.notes['fit' + sd] = { none: true, why: gate }; delete S.notes['stemPlan' + sd]; S.notes['offset' + sd] = `not placed automatically: ${gate}`; return { none: true, why: gate }; }
  const st = stemStart(sd); if (st && !stemAligned(t, sd)) { t.x = st.x; t.y = st.y; t.angle = filmTurn(); }
  const P = stemCandidates(sd, { keepFam, keepSize }); if (!P) return null;
  let pick = chooseStem(P.cands, { handSize: keepSize, handFam: keepFam });
  let b = pick.best;
  if (kept && (pick.none || !b || !b.ev)) {   // guarded (Astra, 16 Oct, P1 4): nothing placed without evidence
    Object.assign(t, kept); const why = pick.none ? pick.why : 'the canal fill is not evidence on the traced canal';
    S.notes['fit' + sd] = { none: true, why: `${why}: the stem is not moved; place it yourself` }; delete S.notes['stemPlan' + sd]; S.notes['offset' + sd] = `not placed automatically: ${why}`;
    return { none: true, why };
  }
  if (pick.none) {   // nothing qualifies: this family and size as they are, with the neck that comes nearest
    const same = P.cands.filter(c => c.fam === t.fam && c.size === t.size);
    b = same.reduce((x, c) => (!x || Math.abs(c.missH) < Math.abs(x.missH) ? c : x), null);
  }
  if (b) { Object.assign(t, { fam: b.fam, size: b.size, neck: b.neck, headKind: b.headKind, head: b.head }); }
  placeStem(t, sd);
  if (!keepSize && !pick.none) t.userSize = false;
  const tr = P.tr, fit = tr ? stemFit(t, tr) : null;
  S.notes['fit' + sd] = pick.none ? { none: true, why: `no suitable match (${pick.why}): choose the stem yourself` }
    : keepSize ? null : { ...(fit || {}), size: t.size, tooBig: false, largerUnchecked: !!b.largerUnchecked, by: 'cup' };
  const why = stemWhy(sd, pick, P, { keepFam, keepSize });
  const note = { rule: 'cup', fam: t.fam, size: t.size, neck: t.neck, missH: b ? r1(b.missH) : null, missV: b ? r1(b.missV) : null, gain: r1(P.L.gain), gainMissing: P.L.missing, gainWhy: P.L.why,
    comb: b && b.comb !== null ? r1(b.comb) : null, nat: P.nat ? r1(P.nat.mm) : null, natWhat: P.nat ? P.nat.what : '', cls: b ? b.cls : null, possible: Number.isFinite(pick.possible) ? r1(pick.possible) : null,
    excludedHigh: pick.excluded.length ? r1(Math.min(...pick.excluded.map(c => c.comb))) : null, none: !!pick.none, overLimit: !!pick.overLimit, overHand: !!pick.overHand, hand: keepSize ? 'size' : keepFam ? 'family' : '', why };
  S.notes['stemPlan' + sd] = note; S.notes['offset' + sd] = why;
  return note;
}
const r1 = x => (Number.isFinite(x) ? Math.round(x * 10) / 10 : null);
// Why this stem (shown with the plan and in the recommendation): the head's distance from the cup centre, and why
// standard or high offset (the design's wording: "high offset: the cup sits 6.1 mm medial to the native head centre;
// standard offset would leave the head 5.4 mm lateral to the cup centre").
function stemWhy(sd, pick, P, { keepFam, keepSize }) {
  if (pick.none) return `no suitable match on the traced canal (${pick.why}): choose the stem yourself; the head is set at the cup centre's height${pick.overLimit ? '; high offset is not chosen just to reach the cup' : ''}`;
  const b = pick.best, f = S.fam[b.fam], side = d => (d >= 0 ? 'lateral' : 'medial');
  const h = refOther() && sd === S.side ? null : lmGeom('head' + sd), H = hipFrame(sd), k = mm();
  const med = h && H ? G.dot(G.sub(h.c, P.C), H.u) * k : null;   // + the cup medial to the native head centre
  const pct = x => (Number.isFinite(x) ? `${Math.round(x * 100)} %` : 'not measured');
  const parts = [`head centre on the cup centre: ${f.label} ${b.size}, neck ${neckLabel(b, b.neck)}, head ${f1(Math.abs(b.missH))} mm ${side(b.missH)} of the cup centre, canal fill ${pct(b.fill)}`];
  // the size is set by canal fill, each version's own (revised amendment 3, 13 Oct): reported per version
  const fsz = Object.entries(pick.fillSize || {}).map(([fid, z]) => { const c = P.cands.find(x => x.fam === fid && x.size === z && x.fill !== null); return `${S.fam[fid].label} ${z} (fill ${pct(c && c.fill)})`; });
  if (!keepSize && fsz.length) parts.push(`size by canal fill (the largest inside the inner cortex): ${fsz.join(', ')}`);
  if (keepSize) parts.push('size set by hand, kept (only the neck was chosen)');
  else if (keepFam) parts.push(`${f.label} chosen by hand, kept`);
  const std = pick.bestStd, hi = pick.bestHigh, ex = pick.excluded.length ? pick.excluded.reduce((a, c) => (Math.abs(c.missH) < Math.abs(a.missH) ? c : a)) : null;
  if (b.cls === 'high' && std) parts.push(`high offset: ${med !== null && med > 0.5 ? `the cup sits ${f1(med)} mm medial to the native head centre; ` : ''}standard offset would leave the head ${f1(Math.abs(std.missH))} mm ${side(std.missH)} of the cup centre`);
  else if (b.cls !== 'high' && ex && Math.abs(ex.missH) < Math.abs(b.missH)) parts.push(`standard offset: high offset would reach the cup better (${f1(Math.abs(ex.missH))} mm) but restore ${f1(ex.comb)} mm more combined offset than ${P.nat ? P.nat.what : 'the native hip'} (limit 5 mm)`);
  else if (b.cls !== 'high' && hi) parts.push(`standard offset: high offset would leave the head ${f1(Math.abs(hi.missH))} mm ${side(hi.missH)} of the cup centre${med !== null && Math.abs(med) > 0.5 ? `; the cup sits ${f1(Math.abs(med))} mm ${med > 0 ? 'medial' : 'lateral'} to the native head centre` : ''}`);
  else if (b.cls !== 'high') parts.push(P.cands.some(c => c.cls === 'high') ? 'standard offset: no high offset version of this stem fits inside the traced inner cortex here' : keepFam || keepSize ? 'standard offset (the version chosen by hand)' : 'standard offset: no high offset version of this stem is open to you');
  else if (!std) parts.push(P.cands.some(c => c.cls !== 'high') ? 'high offset: no standard offset version of this stem fits inside the traced inner cortex here' : keepFam || keepSize ? 'high offset (the version chosen by hand)' : 'high offset: no standard offset version of this stem is open to you');
  if (pick.overHand) parts.push(`the high offset version you chose restores combined offset ${f1(b.comb)} mm more than ${P.nat ? P.nat.what : 'the native hip'} (limit 5 mm for the rules): check it`);
  if (b.comb !== null && b.cls !== 'high' && b.comb > 5) parts.push(`combined offset ${f1(b.comb)} mm more than ${P.nat.what}`);
  return parts.join('; ');
}
// The stem rule for a side (Auto template, Mirror, a family picked on the choice card, "Auto size and place": one rule
// set, amendment 4): head centre on the cup centre, or the older native-offset rule when chosen in Auto plan settings or
// when there is no cup to aim at. keep: what the surgeon set by hand ({keepFam, keepSize}). target: the older rule's
// femoral offset ({mm, what}).
function planStem(sd, keep = {}, target = null) {
  const t = S.tpl['stem' + sd]; if (!t) return null;
  if (isPfr(S.fam[t.fam])) { pfrPlace('stem' + sd); return null; }   // r32
  const cup = S.tpl['cup' + sd];
  if (S.set.stemRule !== 'offset' && cup && cup.on && canalOf(sd)) { const n = stemToCup(sd, keep); if (n) return n; }
  delete S.notes['stemPlan' + sd];
  const nb = autoStem('stem' + sd, { keepSize: !!keep.keepSize, keepFam: !!keep.keepFam, target: target && target.mm });
  S.notes['offset' + sd] = target && nb ? `neck chosen to restore ${target.what} (${f1(nb.off)} against ${f1(target.mm)} mm)` : '';
  return null;
}
// the older rule's femoral offset target for a side's stem: the surgery side's from Auto plan settings, the other side's
// own native one
function stemTarget(sd) {
  if (sd === S.side) return offsetTarget(sd);
  const hn = nativeHead(sd), cn = canalOf(sd);
  return hn && cn && mm() ? { mm: G.lineDist(hn.c, cn.a, cn.b) * mm(), what: 'its own native femoral offset' } : null;
}
// One dependency-aware operation for every change by hand that other parts of the plan depend on (amendment 4; Astra's
// page re-review, 13 Oct, finding 3; third review: a stem's family from its list too): a cup's size or family (panel
// lists, "+" and "-", the implant bar, the keys), a stem's family, and Mirror itself. A stem family change runs the stem
// rule for that slot within the new family. A cup change re-plans the stem of its side by the stem rule; then the mirrored surgery side follows the
// reference side (template-the-other-side mode, or a Mirror made to the other side's plan). A family picked on the choice
// card places the whole plan again by the same rules (pickFamily). What the surgeon set by hand (a size, a family, a head)
// is kept every time: planStem and Mirror are given those choices, never told to let them go ("Use recommended" does
// that). A stem's own size changed from its box keeps its head where it was (changeStem; "Auto size and place" runs the
// rule for it), and dragging or turning an implant is fine-tuning: neither re-plans anything.
function replanAfter(slot) {
  const kind = SLOT[slot] && SLOT[slot].kind, sd = slotSide(slot), o = S.side;
  if ((kind === 'tka-femur' || kind === 'tka-tibia') && (S.wf === 'kneeAP' || S.wf === 'kneeLat')) { if (S.tpl[slot] && mm() && !kneeLmGate() && !kneeRevWhy()) autoPlace(slot); return; }   // r43 (DIAG #2): on the joint line again
  if (S.wf !== 'hip' || (kind !== 'cup' && kind !== 'stem')) return;
  if (kind === 'stem') bipEnsure(sd);   // r38: a new stem family keeps the shell only when it takes the shell's head
  const cupR = S.tpl['cup' + sd], corRule = !!cupR && COR_RULES.includes(S.notes['cupRule' + sd]);
  const rawOff = c => { const v = c && typeof c.liner === 'string' && (linerTypesOf(c)[c.liner] || {}).headCentreMm; return Number.isFinite(v) ? v : 0; };
  const off0 = cupR ? rawOff(cupR) : 0;
  linerFix(sd);   // r38b: a new cup size or family: its liner for the head
  const stem = S.tpl['stem' + sd], plan = stem && !(refOther() && sd === o) && mm() && canalOf(sd);
  guardStemOp(sd, () => {   // (round 2, P1 2: the head matched to the cup only when the stem may be planned)
    matchHeadToCup(sd);
    if (corRule && S.tpl['cup' + sd] === cupR) linerShift(cupR, linerOff(cupR) - off0);   // r38b (Fable P3-2): the head centre stays on the rule's target when the liner flips
    if (plan) planStem(sd, { keepSize: !!stem.userSize, keepFam: !!stem.userFam }, stemTarget(sd));
  });
  { const d1 = cupR && S.tpl['cup' + sd] === cupR ? linerFix(sd) : 0; if (d1 && corRule) linerShift(cupR, d1); }   // a stem put back by the guard: its head's liner (as planHip)
  if (!plan && kind === 'stem') { delete S.notes['stemPlan' + sd]; S.notes['offset' + sd] = ''; S.notes['fit' + sd] = null; }   // no canal to plan on: no explanation of an earlier plan left behind
  if (sd !== o && S.tpl['cup' + o] && (refOther() || (S.mirror && (mirrorRef(o) || {}).kind === 'plan'))) mirrorOther(true);
}
// Mirror the other (normal) side onto the operated side: the cup on the other hip's centre reflected about the pelvic
// midline (perpendicular bisector of the teardrops) at the planned angle, the same stem family, size, head and neck on
// the operated canal, then the neck (and standard/high offset version) that brings the femoral offset nearest the
// other side's, with leg length set to the target. A starting plan: the surgeon adjusts.
const OFFSET_PAIR = { 'chunli-be2-133s': 'chunli-be2-133h', 'chunli-be2-133h': 'chunli-be2-133s', 'chunli-be2-127s': 'chunli-be2-127h', 'chunli-be2-127h': 'chunli-be2-127s' };
// a family's standard/high offset twin: a vendor family names it (offsetPair: an id, or a list whose first is the twin);
// the Addis Ortho BE2 pairs are known here
const pairOf = id => { const f = S.fam[id], p = f && f.offsetPair; return (Array.isArray(p) ? p[0] : typeof p === 'string' ? p : null) || OFFSET_PAIR[id] || null; };
function mirrorOther(quiet = false) {
  const nr = notReady(); if (nr) return prompt(nr);
  const keep = JSON.stringify([S.tpl, S.notes, S.mirror]);
  try { return mirrorOtherNow(quiet); } catch (e) {   // Astra, 11 Oct: a failed mirror must not leave a broken implant
    if (!e.plain) console.error(e); [S.tpl, S.notes, S.mirror] = JSON.parse(keep); S.tplAll[S.wf] = S.tpl;
    prompt(e.plain ? `Not copied: ${e.message}. The plan is as it was.` : `Mirroring failed (${e.message || e}); the plan is as it was.`); panel(); draw(); return false;
  }
}
function mirrorOtherNow(quiet) {
  const o = S.side, n = other(o), H = hipFrame(o);
  if (!H || H.single) return prompt(S.scope === 'one' ? 'Mirroring needs both hips on the film.' : 'Mark both teardrops first: the mirror line runs midway between them.');
  const ref = mirrorRef(o);
  if (!ref) return prompt(`Mark the ${SIDE[n]} femoral head (or place a ${SIDE[n]} cup) first.`);
  const info = [], cupN = S.tpl['cup' + n], stemN = S.tpl['stem' + n];
  // cup: on the mirrored centre at the planned angle, sized from the operated hip itself (Fable, 11 Oct)
  const cup = hemiOn(o) ? null : tplFor('cup' + o);   // none when no cup range is open to this account; r38: none on a bipolar hip
  if (hemiOn(o)) info.push(`bipolar on the ${SIDE[o]}: no cup; the shell's outer centre goes on the ${nativeHead(o) ? 'native head centre' : `${ref.what} centre, mirrored`}`);
  if (cup) {   // a size or family set by hand is kept (amendment 4; Astra's page re-review, finding 3)
    autoCup('cup' + o, { keepSize: true, rule: 'mirror' });
    info.push(`cup ${cup.size}${cup.userSize ? ' (your size, kept)' : cup.userFam ? ` (${S.fam[cup.fam].label}, your choice, kept)` : ''} on the ${ref.what} centre, mirrored`);
    if (cupN && cupN.on) { const f = S.fam[cup.fam], i0 = f.sizes.findIndex(z => z.size === cup.size), i1 = f.sizes.findIndex(z => z.size === cupN.size); if (Math.abs(i0 - i1) > 1) info.push(`the ${SIDE[n]} cup is ${cupN.size}: more than one size different`); }
  }
  if (refOther()) {   // Sami, 11 Oct: a fractured, shortened or rotated femur is not templated; the stem is the normal side's
    if (S.tpl['stem' + o] && !S.tpl['stem' + o].byHand) delete S.tpl['stem' + o];   // one added by hand stays (14 Oct)
    const cutN = stemN && stemN.on ? neckCutAboveLT(stemN, n) : null;
    S.notes['fit' + o] = { copied: true, cut: cutN }; S.notes['offset' + o] = '';
    info.push(stemN && stemN.on ? `stem as on the ${SIDE[n]}: ${S.fam[stemN.fam].label} ${stemN.size}, ${stemN.head} mm head, neck ${neckLabel(stemN, stemN.neck)}; ${isPfr(S.fam[stemN.fam]) ? 'a proximal femoral replacement: its resection and stack are in the results' : cutN !== null ? `neck cut ${f1(cutN)} mm above the lesser trochanter` : `mark the ${SIDE[n]} lesser trochanter to measure the neck cut`}` : `plan the ${SIDE[n]} stem: it is the stem for the ${SIDE[o]}`);
    S.mirror = { note: info.join('; '), at: new Date().toISOString() };
    S.active = 'cup' + o; if (quiet) return true; save(); panel(); draw(); return true;
  }
  // stem: the other side's family if it has a plan, sized by fill on the operated canal, then the neck for the offset
  // (Astra, 16 Oct 2026, round 2, P1 2) guarded: the stem's gate before any change to it; nothing of it is touched when it
  // refuses, and a refusal of the rule afterwards puts it back exactly as it was
  { const g = guardedStem() ? stemGate(o) : ''; if (g) { S.notes['fit' + o] = { none: true, why: g }; info.push(`stem not changed: ${g}`); S.mirror = { note: info.join('; '), at: new Date().toISOString() }; S.active = cup ? 'cup' + o : null; if (quiet) return true; save(); panel(); draw(); prompt(`Mirrored the cup only. The stem is not changed: ${g}.`); return true; } }
  const had0 = !!S.tpl['stem' + o], t = tplFor('stem' + o);
  if (!t) { S.mirror = { note: info.join('; '), at: new Date().toISOString() }; S.active = cup ? 'cup' + o : null; if (quiet) return true; save(); panel(); draw(); return true; }
  const t0 = guardedStem() ? { ...stemKeep(t), userHead: t.userHead } : null;
  if (stemN && stemN.fam !== t.fam && !t.userFam && !t.userSize && !isPfr(S.fam[stemN.fam])) {   // r32: never a PFR by Mirror   // the other side's family (unless this stem's family or size was set by hand), at the size nearest in canal fill
    const w = widthAt(sizeOf(t), 60), shown = sizesShown(S.fam[stemN.fam]);
    const pick = Number.isFinite(w) ? shown.reduce((b, z) => Math.abs(widthAt(z, 60) - w) < Math.abs(widthAt(b, 60) - w) ? z : b) : shown[Math.floor(shown.length / 2)];
    t.fam = stemN.fam; t.size = pick.size; setHead(t, t.headKind, t.head);
  }
  if (!t.userSize) t.neck = zeroNeck(t);
  bipEnsure(o, { resize: true });   // r38: a bipolar hip's stem: the shell on its head, sized from the native head unless set by hand
  matchHeadToCup(o);   // unless the head was chosen by hand
  if (t.userSize || t.userFam) info.push(`kept as you set them: ${[t.userFam ? S.fam[t.fam].label : '', t.userSize ? `stem size ${t.size}` : ''].filter(Boolean).join(', ')} ("Use recommended" lets the rules choose)`);
  const st0 = stemStart(o); if (st0) { t.x = st0.x; t.y = st0.y; t.angle = filmTurn(); }
  // the same stem rule as Auto template (amendment 4): the head centre on the mirrored cup centre, or the older rule (the
  // other side's femoral offset) when chosen or when there is no cup
  const keepO = { keepFam: !!t.userFam, keepSize: !!t.userSize };
  const nt = S.set.stemRule !== 'offset' && cup && cup.on ? stemToCup(o, keepO) : null, c_ = canalOf(o);
  if (nt && !nt.none) info.push(`${S.fam[t.fam].label} ${t.size}, neck ${neckLabel(t, t.neck)}: head centre ${f1(Math.abs(nt.missH))} mm ${nt.missH >= 0 ? 'lateral' : 'medial'} of the mirrored cup centre${ref.offset && c_ ? `; femoral offset ${f1(G.lineDist(corAt(t), c_.a, c_.b) * mm())} mm against ${f1(ref.offset)} mm (${ref.what})` : ''}`);
  else if (nt) info.push(`stem: ${nt.why}`);
  else {
    delete S.notes['stemPlan' + o];
    const m_ = autoStem('stem' + o, { keepSize: true, keepFam: keepO.keepFam, target: ref.offset });
    if (m_) info.push(`${S.fam[t.fam].label} ${t.size}, neck ${neckLabel(t, t.neck)}: femoral offset ${f1(m_.off)} mm against ${f1(ref.offset)} mm (${ref.what})`);
    else info.push(`femoral offset not matched: ${ref.kind === 'native' ? `mark the ${SIDE[n]} femoral canal` : `place a ${SIDE[n]} stem`} first`);
  }
  if (t0 && S.notes['fit' + o] && S.notes['fit' + o].none) {   // refused on the evidence: the stem exactly as before (or none, if Mirror made it)
    if (had0) Object.assign(t, t0); else delete S.tpl['stem' + o];
    info.push('stem not changed (no evidence for it on the traced canal)');
  }
  S.mirror = { note: info.join('; '), at: new Date().toISOString() };
  S.active = S.tpl['stem' + o] ? 'stem' + o : cup ? 'cup' + o : null; if (quiet) return true; save(); panel(); draw(); return true;
}
const zeroNeck = t => (neckOpts(t).find(([, o]) => o === 0) || neckOpts(t)[0])[0];
// Auto template. opt.replan: the reference plan of a deformed surgery side is placed again too (otherwise a reference
// plan the surgeon made is copied as it is); opt.fresh: sizes and families set by hand are let go first ("Use
// recommended"); without it a size or family set by hand is kept (amendment 4: never overridden by Auto).
function autoTemplate(opt = {}) {
  const nr = notReady(); if (nr) return prompt(nr);
  { const w = llImplWhy(); if (w) { panel(); draw(); return prompt(w); } }   // r44: no implant on a long film without a real scale
  if (!mm()) return prompt('Calibrate first: implants are drawn true size.');
  if (S.noRange) return prompt(`No implant range with template files is open to your account yet (${PENDING.map(pendingText).join('; ')}).`);
  { const bq = brandGate(); if (bq) { S.segHand.tpl = true; panel(); draw(); return prompt(bq); } }   // r36
  if (S.wf === 'shoulder') {   // r33: the proximal humerus placed on the head and the shaft (no rules beyond that)
    const t = tplFor('humerus'); if (t) { S.active = 'humerus'; phrPlace('humerus'); } save(); panel(); draw();
    return prompt(t ? 'Placed on the humeral head and the shaft axis: enter the resection, then choose the length and the stem from the options.' : 'No proximal humeral replacement is open to your account.');
  }
  if (S.wf !== 'hip') {
    { const w = kneeRevWhy(); if (w) { S.segHand.tpl = true; panel(); draw(); return prompt(w); } }   // revision: insufficient evidence, nothing placed
    // placement only on valid landmarks (Astra, 16 Oct, P1 3): one the placement reads that is marked red stops it
    { const lm = kneeLmGate(); if (lm) { S.segHand.lm = true; panel(); draw(); return prompt(lm); } }
    // no question (Sami, 23 Oct, replacing his 16 Oct (2) question): the values on the knee plan card are used as shown, an
    // unchecked default stays "default"; pressed for these films, Auto template may run by itself again (nextStep)
    if (pvNeedsAsk()) pvSetAsked(pvAskKey());
    return kneePlace();
  }
  { const nU = exOpen().length; if (nU) { S.segHand.prep = true; panel(); draw(); return prompt(`${nU} possible hardware region${nU > 1 ? 's are' : ' is'} not yet confirmed or dismissed (2 · Prepare, Existing implants): nothing is placed automatically until then.`); } }   // (Astra, 16 Oct, P1 5)
  const r = planHip(opt);
  if ('stop' in r) { if (r.stop) prompt(r.stop); return; }
  save(); panel(); draw(); prompt(r.say);
}
// the landmarks a knee placement reads (a red one stops Auto template)
const KNEE_PLACE_LM = ['hip', 'knee', 'ankle', 'femShaft', 'tibShaft', 'condMed', 'condLat', 'platMed', 'platLat', 'antCortex', 'postCond', 'platAnt', 'platPost', 'tibAxis'];
// what keeps a knee placement from running (Astra, r17 P1 1): every landmark the placement needs on this film present and
// valid (the AP: both condyles, both plateaus, the femoral shaft axis or the hip and knee centres, the tibial shaft axis or
// the knee and ankle centres; the lateral: anterior cortex, posterior condyles, both plateau edges, the tibial axis), and
// none it reads marked red. → '' or what to do (nothing is placed)
function kneeLmGate() {
  if (S.wf !== 'kneeAP' && S.wf !== 'kneeLat') return '';
  const has = k => !!S.lm[k] && !!lmGeom(k) && !lmProblem(k) && !lmBroken(k), name = k => (lmDefs().find(d => d.key === k) || { label: k }).label;
  const bad = lmDefs().filter(d => KNEE_PLACE_LM.includes(d.key) && S.lm[d.key] && (lmProblem(d.key) || lmBroken(d.key))).map(d => d.label);
  if (bad.length) return `Check these landmarks first (marked red): ${bad.join('; ')}. Nothing placed.`;
  const miss = [];
  if (S.wf === 'kneeAP') {
    for (const k of ['condMed', 'condLat', 'platMed', 'platLat']) if (!has(k)) miss.push(name(k));
    if (!has('femShaft') && !(has('hip') && has('knee'))) miss.push(name('femShaft'));
    if (!has('tibShaft') && !(has('ankle') && has('knee'))) miss.push(name('tibShaft'));
  } else for (const k of ['antCortex', 'postCond', 'platAnt', 'platPost', 'tibAxis']) if (!has(k)) miss.push(name(k));
  return miss.length ? `Mark these landmarks first: ${miss.join('; ')}. Nothing placed.` : '';
}
// the knee's components placed by the rules with the plan values (each said with its origin)
function kneePlace() {
  for (const slot of opSlots()) { const t = tplFor(slot); if (!t) continue; if (!(S.kc && t.userSize)) t.userSize = false; autoPlace(slot); }   // a knee case keeps a size set by hand (on either film)
  S.arm = null; save(); panel(); draw();
  { const tb = opSlots().map(sl => S.tpl[sl]).find(t => t && isTumourBody(S.fam[t.fam])); if (tb) return prompt(`Components placed: ${S.fam[tb.fam].label} on the native joint line${tumPlan(tb).ok && tb.userSize ? '' : ', without an extension'}. Enter the MRI/CT extent and your margin in its box, then pick one of the options (none is chosen for you).`); }   // r43 (DIAG #9)
  const v = kneeVals(), ap = S.wf === 'kneeAP', how = k => { const pv = v.pv[k]; return pv.o === 'surgeon' ? 'your target' : pv.o === 'unknown' ? 'entered before 16 Oct' : pv.rv ? 'assumed, reviewed' : 'assumed, not surgeon-confirmed'; };
  return prompt(`Components placed (cuts square to the ${ap ? 'mechanical axes' : 'tibial axis'}; ${ap ? `distal femoral resection ${f1(v.femDistal)} mm (${how('femDistal')}), ` : ''}tibial ${f1(v.tibial)} mm below the ${TIB_SHORT[v.tibRef]} (${how('tibial')})${ap ? '' : `, posterior slope ${f1(v.slope)}°${pvXn() ? ' on the extramedullary guide' : ''} (${how('slope')})`}${S.kc ? '; one femoral and one tibial size for both films' : '; sized from the lateral film'}). Check and fine-tune.`);
}
// The hip plan by the rules, without touching the page (recommendStem runs it on a copy, opt.dry: the stem's side only):
// {stop} when it cannot run ('' when it has said why itself), else {say}. One rule set for both sides, Mirror and the
// choice card (amendment 4).
function planHip(opt = {}) {
  const o = S.side, n = other(o), fresh = !!opt.fresh, kept = [], stemsBefore = { R: !!S.tpl.stemR, L: !!S.tpl.stemL };
  const side = (sd, keep = false) => {   // cup, then stem; the head follows the cup's liner; the stem is sized and levelled with that head
    const had = { cup: keep && !!S.tpl['cup' + sd], stem: keep && !!S.tpl['stem' + sd] };   // kept as the surgeon left them
    for (const k of ['cup', 'stem']) {
      if (had[k]) continue;
      if (k === 'cup' && hemiOn(sd)) continue;   // r38: a bipolar hip has no cup
      if (!S.tpl[k + sd] && (k === 'cup' ? cupNeedsTarget(sd) && !rvOf().cupT : stemGate(sd))) continue;   // revision: not made where it cannot be placed by the rules
      const t = tplFor(k + sd); if (!t) continue;   // none of a kind not open to this account
      if (fresh) { t.userSize = false; t.userFam = false; t.userHead = false; if (t.bip) t.bip = { ...t.bip, userSize: false }; }   // a head chosen by hand is kept too, unless "Use recommended"
      const pf = chosenFam(k);   // the family picked on the choice card, unless this slot's own was chosen by hand
      if (pf && !t.userFam && t.fam !== pf.id) setFam(t, pf); if (pf && t.fam === pf.id) t.userFam = true;
      if (t.userSize) kept.push(`${k} ${SIDE[sd]} size ${t.size}`); else if (t.userFam && k === 'stem') kept.push(`${S.fam[t.fam].label} on the ${SIDE[sd]}`);
    }
    const stem = S.tpl['stem' + sd], made = !had.stem && !!stem && !stemsBefore[sd];
    if (!had.cup && S.tpl['cup' + sd]) autoPlace('cup' + sd, { keepSize: true });   // a size set by hand is kept
    if (had.stem || !stem) return;
    guardStemOp(sd, () => {   // the neck reset and the head matched only when the stem may be placed (round 2, P1 2)
      bipEnsure(sd, { resize: true });   // r38: a bipolar hip: the shell on the head, sized from the native head unless set by hand
      if (!stem.userSize) stem.neck = zeroNeck(stem);
      const c0 = S.tpl['cup' + sd], o0 = c0 ? linerOff(c0) : 0;
      matchHeadToCup(sd);
      // r38b: the head chosen for the cup may need the +4 liner; a cup placed by a rule that aims the centre keeps its head centre there
      if (c0 && !had.cup && COR_RULES.includes(S.notes['cupRule' + sd])) linerShift(c0, linerOff(c0) - o0);
      planStem(sd, { keepSize: !!stem.userSize, keepFam: !!stem.userFam }, stemTarget(sd));
    });
    { const c1 = S.tpl['cup' + sd], d1 = c1 ? linerFix(sd) : 0; if (d1 && !had.cup && COR_RULES.includes(S.notes['cupRule' + sd])) linerShift(c1, d1); }   // a stem put back by the guard: its head's liner
    // guarded (Astra, 16 Oct, P1 4): a stem made here that could not be placed on evidence is taken away again, not left
    // wherever it was made
    if (made && guardedStem() && S.notes['fit' + sd] && S.notes['fit' + sd].none) { delete S.tpl['stem' + sd]; if (S.active === 'stem' + sd) S.active = null; }
  };
  const vis = k => S.tpl[k + n] && S.tpl[k + n].on;
  const keepN = refOther() && !opt.replan && (S.tpl['cup' + n] || S.tpl['stem' + n]);   // the reference plan the surgeon made is copied, not redone
  if (keepN) for (const k of ['cup', 'stem']) if (S.tpl[k + n] && !S.tpl[k + n].on) return { stop: `The ${SIDE[n]} ${k} is hidden: show it (or remove it) first, so it is clear what is copied.` };
  if (lmGeom('head' + n) && canalOf(n) && S.scope !== 'one' && !(opt.dry && !refOther())) side(n, !!keepN);   // the other side first: the reference plan, or a comparison of its own anatomy
  const keptSay = () => (kept.length ? ` Kept as you set them: ${[...new Set(kept)].join(', ')} ("Use recommended" lets the rules choose them too).` : '');   // read when said: side(o) adds to it
  if (opt.dry && refOther()) return { say: '' };
  if (refOther() && vis('stem') && vis('cup')) {   // deformed surgery side: the other hip's plan copied across, mirrored
    const cO = S.tpl['cup' + o]; if (fresh && cO) { cO.userSize = false; cO.userFam = false; }
    if (cO && (cO.userSize || cO.userFam)) kept.push(`cup ${SIDE[o]} ${cO.userSize ? `size ${cO.size}` : S.fam[cO.fam].label}`);
    if (!mirrorOther(true)) return { stop: '' };   // it has said why, and put the plan back (Astra, 11 Oct)
    S.active = 'stem' + n;
    return { say: `Templated on the ${SIDE[n]} side: the ${SIDE[n]} cup by the ${cupRuleName(S.notes['cupRule' + n])}, the ${SIDE[o]} cup the same size and angle on the mirrored centre; the stem, head, neck and neck cut are the ${SIDE[n]} ones. Check and fine-tune the ${SIDE[n]} plan.${keptSay()}` };
  }
  if (refOther()) return { stop: `The ${SIDE[o]} hip is planned from the ${SIDE[n]} one: mark the ${SIDE[n]} femoral head and canal first (or place a ${SIDE[n]} cup and stem).` };   // never from the deformed hip
  side(o);
  S.active = S.tpl['stem' + o] ? 'stem' + o : S.tpl['cup' + o] ? 'cup' + o : null;
  const wait = [];   // revision and conversion: what waits for the surgeon, said
  if (cupNeedsTarget(o) && !rvOf().cupT) wait.push('the cup waits for the target you set (4 · Implants, Revision)');
  { const g = stemGate(o), fn = S.notes['fit' + o]; if (g) wait.push(`the stem is not placed or sized automatically: ${g}`); else if (guardedStem() && fn && fn.none) wait.push(`the stem is not placed: ${fn.why}`); }
  if (wait.length) return { say: `${CASE_WORD[ctype()]}: ${wait.join('; ')}.${S.tpl['cup' + o] || S.tpl['stem' + o] ? ' What could be placed was placed by the rules on native bone.' : ''}${keptSay()}` };
  return { say: `Implants placed by the rules in "Auto plan settings". Check and fine-tune: drag, turn, change size, neck or head.${keptSay()}` };
}
const cupRuleName = r => ({ anatomic: 'native centre height, medial edge at the teardrop', teardrop: 'teardrop technique (lowest rim on the teardrop line)', head: 'native head centre', mirror: 'other hip, mirrored', target: 'target you set' }[r] || 'rules');
// a family put on a slot by a rule or the choice card: a valid size of it, and for a stem a head system it takes
function setFam(t, f) {
  if (f.kind === 'stem') {
    const w = sizeOf(t) ? widthAt(sizeOf(t), 60) : NaN, shown = sizesShown(f);
    const pick = Number.isFinite(w) ? shown.reduce((b, z) => (Math.abs(widthAt(z, 60) - w) < Math.abs(widthAt(b, 60) - w) ? z : b)) : shown[Math.floor(shown.length / 2)];
    t.fam = f.id; t.size = pick.size; if (!headKindsOf(f).includes(t.headKind)) setHead(t, f.sheetHead.kind, t.head); else setHead(t, t.headKind, t.head);
    if (isPfr(f)) { const z0 = f.sizes.find(z => z.extensionMm === 0); if (z0 && !(t.tum && Number.isFinite(t.tum.c))) t.size = z0.size; }   // r43: a PFR starts without an extension
  } else { const o = t.fam; t.fam = f.id; if (!f.sizes.find(z => z.size === t.size)) t.size = f.sizes[Math.floor(f.sizes.length / 2)].size; kneeFamFix(t, o); }
}
// the femoral family of the knee plan on screen: placed, else the case's shared one, else the first of its kind open here
const kneeFemFam = () => (S.tpl && S.tpl.femur && S.tpl.femur.fam) || (S.kc && S.kc.shared.size.femur && S.kc.shared.size.femur.fam) || ((defaultFam('tka-femur') || {}).id) || null;
const kneeTibFam = () => (S.tpl && S.tpl.tibia && S.tpl.tibia.fam) || (S.kc && S.kc.shared.size.tibia && S.kc.shared.size.tibia.fam) || ((defaultFam('tka-tibia') || {}).id) || null;
// ---------- the knee's plan values (Sami, 16 Oct 2026; DESIGN-2026-10-16-knee-defaults.md, its amendments binding) ----------
// No resection questions that block: the values are filled from the XN manual and Addis Ortho's planning assumptions
// (autoplan.js kneeStd), each with its own source, and the surgeon may change any of them. Origin ('assumed' | 'surgeon' |
// 'unknown': a value stored before 16 Oct) and review are kept apart (contract section 12c). Where they live: a knee case's
// shared plan (K.shared.res; the rotation K.shared.rot), else the knee film's own (S.kres; the valgus S.vca with vcaSet).
// Sami 23 Oct (replacing his 16 Oct (2) question): no question; the values are edited and checked on the knee plan card.
// "asked" now records that Auto template was pressed for these films: until then (and again after a value is reset or a
// film replaced, or for a plan from before 16 Oct) Auto template does not run by itself after the last landmark.
const PV_KEYS = ['femDistal', 'tibial', 'slope', 'vca', 'rot'];
const PV_NAME = { femDistal: 'Distal femoral resection', tibial: 'Tibial resection', slope: 'Planned posterior slope (extramedullary guide)', vca: 'Femoral valgus cut angle', rot: 'Planned femoral component external rotation' };
// the XN knee's own words (the manual, the extramedullary guide, the rotation guide) only for the XN femur (Astra, r17 P2 3)
const pvXn = () => { const f = kneeFemFam(); return !!f && isXnFemur(f); };   // r36 (Fable P2 6): no femur yet (no maker chosen) is not the XN
const pvName = key => (key === 'slope' && !pvXn() ? 'Planned posterior slope' : PV_NAME[key]);
// a value's label; a value kept from before 16 Oct outside today's entry range says so (Astra, r17 P2 4: kept as it is)
const pvOut = pv => { const [lo, hi] = PV_BOUND[pv.key]; return pv.o === 'unknown' && (pv.v < lo || pv.v > hi); };
const pvLab = pv => planLabel(pv, pv.src) + (pvOut(pv) ? ' · outside the usual entry range (entered before 16 Oct)' : '');
const PV_BOUND = { femDistal: [0, 25, 0.5], tibial: [0, 25, 0.5], slope: [XN_KNEE.slopeMin, XN_KNEE.slopeMax, 0.5], vca: [0, 15, 0.5], rot: [0, 10, 1] };
const pvUnit = key => (key === 'femDistal' || key === 'tibial' ? ' mm' : '°');
const finOrNull = v => (Number.isFinite(v) ? v : null);
const TIB_SHORT = { less: 'reference plateau (the higher one, chosen by the page)', medial: 'lowest point of the medial plateau', worn: 'lower plateau (saved before 16 Oct)' };
function pvTibRef() { const b = S.kc ? S.kc.shared.res : S.kres; return b && ['less', 'medial', 'worn'].includes(b.tibRef) ? b.tibRef : 'less'; }
// a knee case saved before 16 Oct kept the valgus on its AP film (state vca with kres.vcaSet): read as a value of unknown origin
function kcLegacyVca() {
  const K = S.kc; if (!K || !K.films.ap) return null;
  if (K.active === 'ap' && S.img && !K.films.ap.state) return S.kres && S.kres.vcaSet && Number.isFinite(S.vca) ? S.vca : null;
  const st = K.films.ap.state; return st && st.kres && st.kres.vcaSet && Number.isFinite(st.vca) ? st.vca : null;
}
function kcClearLegacyVca() {
  const K = S.kc; if (!K || !K.films.ap) return;
  if (K.active === 'ap' && S.img && !K.films.ap.state) { if (S.kres && S.kres.vcaSet) S.kres = { ...S.kres, vcaSet: false }; return; }
  const f = K.films.ap; if (f.state && f.state.kres && f.state.kres.vcaSet) { f.state = { ...f.state, kres: { ...f.state.kres, vcaSet: false } }; f.stateJ = null; }
}
// the stored value (null: the assumed value is used)
function pvRaw(key) {
  if (S.kc) {
    const r = S.kc.shared.res;
    if (key === 'rot') return finOrNull(S.kc.shared.rot);
    if (key === 'vca') return Number.isFinite(r.vca) ? r.vca : kcLegacyVca();
    return finOrNull(r[key]);
  }
  const k = S.kres || {};
  if (key === 'vca') return k.vcaSet || (Number.isFinite(S.vca) && S.vca !== 6 && S.vca !== XN_KNEE.vcaStd) ? S.vca : null;   // (a plan from before 15 Oct may hold another valgus without vcaSet; 6° was the default then, kept in every plan)
  return finOrNull(k[key]);
}
const kresNow = () => kresOf(S.kres) || kresOf({});
function pvSetRaw(key, val) {
  if (S.kc) {
    if (key === 'rot') S.kc.shared.rot = val; else S.kc.shared.res[key] = val;
    if (key === 'vca' && val === null) kcClearLegacyVca();
    return;
  }
  if (key === 'vca') { S.vca = val === null ? XN_KNEE.vcaStd : val; S.kres = { ...kresNow(), vcaSet: val !== null }; return; }
  S.kres = { ...kresNow(), [key]: val };
}
function pvProv() { const b = S.kc ? S.kc.shared.res : S.kres; return (b && b.prov && typeof b.prov === 'object') ? b.prov : {}; }
function pvSetProv(key, p) {
  const next = { ...pvProv() }; if (p) next[key] = p; else delete next[key];
  if (S.kc) S.kc.shared.res.prov = next; else S.kres = { ...kresNow(), prov: next };
}
const pvAsked = () => { const b = S.kc ? S.kc.shared.res : S.kres; return b && typeof b.asked === 'string' ? b.asked : null; };
function pvSetAsked(v) { if (S.kc) S.kc.shared.res.asked = v; else S.kres = { ...kresNow(), asked: v }; }
// the films the answer was given for: the single film's image, or each film of the case by role (a film added later is not
// a change; a film replaced is)
function pvAskKey() {
  if (S.kc) return 'k:' + ['ap', 'lat', 'long'].filter(r => S.kc.films[r]).map(r => `${r}-${S.kc.films[r].image.sha256.slice(0, 12)}`).join(',');
  return 's:' + String(imgSha() || 'none').slice(0, 16);
}
function pvNeedsAsk() {
  const a = pvAsked(); if (!a) return true;
  if (!S.kc) return a !== pvAskKey();
  if (!a.startsWith('k:')) return true;
  const now = Object.fromEntries(pvAskKey().slice(2).split(',').filter(Boolean).map(x => x.split('-')));
  return a.slice(2).split(',').filter(Boolean).some(x => { const [r, h] = x.split('-'); return now[r] !== h; });
}
const pvStd = () => kneeStd(kneeFemFam() || 'none', pvTibRef());   // r36 (Fable 16): no femur yet is not the XN
function pvGet(key) { const std = pvStd(), raw = pvRaw(key); return { key, ...planVal(raw, pvProv()[key], std[key]), raw, src: std[key + 'From'], def: std[key] }; }
// a change by the surgeon: a number is the surgeon's target; null resets the value to the assumed one (and the question is
// asked again at the next Auto template)
function pvSet(key, val) {
  pvSetRaw(key, val);
  if (val === null) { pvSetProv(key, null); pvSetAsked(null); } else pvSetProv(key, { o: 'surgeon', rv: true });
}
// an unchanged value marked reviewed (its origin stays as it was)
function pvReview(key) { const pv = pvGet(key); if (pv.o !== 'surgeon') pvSetProv(key, { o: pv.o, rv: true, v: pv.v }); }
// the tibial reference switched: the number follows the new reference (the manual's for it), said, never kept silently
function pvSetTibRef(ref) {
  if (S.kc) S.kc.shared.res.tibRef = ref; else S.kres = { ...kresNow(), tibRef: ref };
  pvSet('tibial', null);
}
function pvResetAll() {
  if (S.kc) { const r = S.kc.shared.res; Object.assign(r, { femDistal: null, tibial: null, slope: null, tibRef: 'less', vca: null, prov: {}, asked: null }); S.kc.shared.rot = null; kcClearLegacyVca(); return; }
  S.vca = XN_KNEE.vcaStd; S.kres = null;
}
function pvText(key, more = '') { const pv = pvGet(key); return `${f1(pv.v)}${pvUnit(key)}${more} · ${pvLab(pv)}`; }
const pvCls = key => { const pv = pvGet(key); return pv.o === 'surgeon' || pv.rv ? '' : 'warn'; };
// The values a knee placement uses → { femDistal, tibial, tibRef, slope, vca, rot, pv: {key: pvGet(key)} }
function kneeVals() {
  const pv = Object.fromEntries(PV_KEYS.map(k => [k, pvGet(k)]));
  return { ...Object.fromEntries(PV_KEYS.map(k => [k, pv[k].v])), tibRef: pvTibRef(), pv };
}
const vcaNow = () => pvGet('vca').v;
// which plateau the tibial resection is measured from on this AP (Astra, 16 Oct, P1 2): 'less' the higher one (the page's
// choice), 'medial' the medial, 'worn' (before 16 Oct) the lower; → {plat: 'medial' | 'lateral', p} or null
function tibRefPlat(up) {
  const A = kneeAxes(); if (!A.pm || !A.pl) return null;
  const u = up || A.tma || cranVec(), m = G.mid(A.pm.p, A.pl.p), medHi = G.dot(G.sub(A.pm.p, m), u) > G.dot(G.sub(A.pl.p, m), u), ref = pvTibRef();
  const med = ref === 'medial' ? true : ref === 'less' ? medHi : !medHi;
  return { plat: med ? 'medial' : 'lateral', p: med ? A.pm.p : A.pl.p };
}
function tibRefText() {
  const ref = pvTibRef(), STY = 'radiographic landmarks do not reproduce the stylus on cartilage';
  if (S.wf === 'kneeLat') return `the plateau line's midpoint (front and back edges) on the lateral: medial and lateral plateaus are not told apart on a lateral; ${STY}`;
  const t = S.tpl && S.tpl.tibia, tr = t && t.tref, pl = S.wf === 'kneeAP' ? (tr && tr.plat && tr.mode === ref ? tr : tibRefPlat()) : null;   // a tray placed for another reference: the plateau now chosen
  const where = pl ? `${pl.plat} plateau` : S.wf === 'kneeAP' ? 'plateau not marked yet' : 'the plateau chosen on the AP film';
  const how = { less: 'its highest point (the higher plateau, chosen by the page: lateral if the medial is lower); wear severity not assessed', medial: `the lowest point of the medial plateau${pvXn() ? ' (XN manual p. 12: non-severe medial chondrosis)' : ''}`, worn: 'the lower plateau (reference saved before 16 Oct 2026)' }[ref];
  return `${where}: mm below ${how}, square to the tibial axis on the film; ${STY}`;
}
// the knee's plan values for the results (and so the saved picture) with each one's origin and review
function kneeValueRows(R, A, native = null) {
  const v = kneeVals(), ap = S.wf === 'kneeAP', lat = S.wf === 'kneeLat';
  if (!ap && !lat) return;
  R.push(['Plan values (each with its origin; change or review them under Implants, Plan values)']);
  if (ap && !isTumourBody(S.tpl.femur && S.fam[S.tpl.femur.fam])) R.push([PV_NAME.femDistal, pvText('femDistal', ' from the more distal condyle'), pvCls('femDistal')]);   // r27 (Fable P3): not for a distal femoral replacement
  R.push([PV_NAME.tibial, pvText('tibial', ` below the ${TIB_SHORT[v.tibRef]}`), pvCls('tibial')]);
  R.push(['Tibial resection reference', tibRefText()]);
  if (lat) {
    R.push([pvName('slope'), pvText('slope'), pvCls('slope')]);
    if (native !== null) R.push(['Native vs planned slope', `native ${f1(native)}° (measured on this lateral), planned cut ${f1(v.slope)}°: ${Math.abs(native - v.slope) < 0.05 ? 'the same' : `${f1(Math.abs(native - v.slope))}° ${v.slope < native ? 'less' : 'more'} slope than native`}`]);
  }
  if (ap && A && (A.fmaHow === 'assumed' || A.fmaHow === 'entered')) R.push([PV_NAME.vca + (A.fmaHow === 'assumed' ? ' (no hip or long leg)' : ''), pvText('vca', ' to the femoral shaft axis'), pvCls('vca')]);
  if (!S.kc) R.push([PV_NAME.rot, pvText('rot', ' to the posterior condylar axis'), pvCls('rot')]);
  kneePairRow(R);
}
// the trays Chunli's chart grades for the femur on this plan: {femSize, green, yellow, gray, sizes: green} or null (no femur,
// or not the XN knee)
function kneePermitted() {
  const sz = S.kc ? S.kc.shared.size : null, fe = (S.tpl.femur && { fam: S.tpl.femur.fam, size: S.tpl.femur.size }) || (sz && sz.femur);
  const ti = kneeTibFam(); if (!fe || ti !== XN_KNEE.tibia) return null;
  const t = traysFor(fe.fam, fe.size); return t ? { femSize: String(fe.size), ...t, sizes: t.green } : null;
}
// the femur and tibia of the plan on screen against Chunli's compatibility chart (Sami, 15 Oct; autoplan.js XN_KNEE): null
// when there is nothing to check (one of them missing, or another maker's), else {ok, grade, femSize, tibSize, green,
// yellow, missing: closest-match trays with no template in this library}
function kneePair() {
  const sz = S.kc ? S.kc.shared.size : null;
  const fe = (S.tpl.femur && S.tpl.femur.on !== false && { fam: S.tpl.femur.fam, size: S.tpl.femur.size }) || (sz && sz.femur), ti = (S.tpl.tibia && S.tpl.tibia.on !== false && { fam: S.tpl.tibia.fam, size: S.tpl.tibia.size }) || (sz && sz.tibia);
  if (!fe || !ti) return null;
  const c = kneePairCheck(fe.fam, fe.size, ti.fam, ti.size); if (!c) return null;
  const tf = S.fam[ti.fam], missing = c.green.filter(z => !(tf && tf.sizes.some(q => q.size === z)));
  return { ...c, femSize: String(fe.size), tibSize: String(ti.size), missing };
}
const CHART = 'Chunli compatibility chart';
// the femur and tray of a revision or hinged knee (r20): one system (a hinge femur only on its own hinge tray), and a
// pairing the maker has not charted said so; the XN primary keeps Chunli's chart (kneePair)
function kneeSystemRow(R) {
  const sz = S.kc ? S.kc.shared.size : null, on = sl => (S.tpl[sl] && S.tpl[sl].on !== false && S.tpl[sl]) || (sz && sz[sl]);
  const fe = on('femur'), ti = on('tibia'), ff = fe && S.fam[fe.fam], tf = ti && S.fam[ti.fam]; if (!ff || !tf) return;
  const sys = f => f.rangeOwn || f.range || f.id;   // a family hosted in another range (build_ranges HOST_RANGE) keeps its own
  const OWN = ['ao-cck', 'ldk-xr', 'ao-tumour'];
  if (sys(ff) !== sys(tf) && (OWN.includes(sys(ff)) || OWN.includes(sys(tf)))) R.push(['Femur and tibia system', `${ff.label} with ${tf.label}: not one system; the page cannot check that they fit together`, 'warn']);
  else if (ff.range === 'ldk-xr') {   // LDK's matching tables: any hinge femur 1-3 with any tray 1-5; the axle and rotating component follow the sizes
    const zf = ff.sizes.find(q => q.size === String(fe.size)), zt = tf.sizes.find(q => q.size === String(ti.size));
    R.push(['Femur and tibia pairing', `femur ${fe.size} with tray ${ti.size}: allowed (LDK matching table: any femur with any tray)${zf && zf.axleMm ? `; axle ${zf.axleMm} mm` : ''}${zt && zt.rotatingComponentMm ? `; rotating component ${zt.rotatingComponentMm} mm` : ''}`]);
  } else if (ff.range === 'ao-cck') R.push(['Femur and tibia pairing', `femur ${fe.size} with tray ${ti.size}: pairing not confirmed (no maker's chart for the CCK in this library)`, 'warn']);
  else if (sys(ff) === 'ao-tumour') {   // Chunli's technique lists the femoral and tibial tumour components by the same size numbers
    const fn = parseInt(fe.size, 10), tn = parseInt(String(ti.size), 10);
    R.push(['Femur and tibia pairing', fn === tn ? `femur ${fn}# with tibial body ${tn}#: the same size number (Chunli's technique lists them in pairs; not confirmed as a rule)` : `femur ${fn}# with tibial body ${tn}#: different size numbers; Chunli's technique lists them in pairs (2#, 4#, 6#, 8#, 10#): check with Chunli`, fn === tn ? '' : 'warn']);
  }
}
function kneePairRow(R) {
  kneeSystemRow(R);
  const P_ = kneePair(); if (!P_) return;
  const grp = insertGroupOf(P_.tibSize), ins = grp ? `; PS insert group ${grp.join('/')}` : '';
  if (P_.grade === 'green') R.push(['Femur and tibia match', `femur ${P_.femSize} with tray ${P_.tibSize}: the closest match (${CHART})${ins}`]);
  else if (P_.grade === 'yellow') R.push(['Femur and tibia match', `femur ${P_.femSize} with tray ${P_.tibSize}: OK (not the closest match; closest: tray ${P_.green.join('/')}, ${CHART})${ins}`]);
  else R.push(['Femur and tibia match', `femur ${P_.femSize} with tray ${P_.tibSize}: not compatible per the ${CHART} (compatible: tray ${[...P_.green, ...P_.yellow].join(', ')}); your override, check it`, 'warn']);
}
// the tray's size by the rules (a size set by hand is kept): a closest-match tray of Chunli's chart for the femur that this
// library has (the femur's own size first, as the manual's p. 20 trial; else the nearest the plateau depth apMm on a lateral);
// without the XN femur, on a lateral, the tray nearest the plateau depth. Coverage against the bone is shown with the results.
function kneeTibSize(t, f, apMm) {
  if (t.userSize || isTumourBody(f) || llKcLong()) return;   // r44: the knee case's long film keeps the case's size; a tumour body: the surgeon picks it from the options (none chosen for them)
  const perm = kneePermitted(), have = z => f.sizes.find(q => q.size === z);
  const green = perm ? perm.green.filter(have).map(have) : [];
  const nearest = (list) => list.reduce((b, z) => Math.abs(z.apDepth - apMm) < Math.abs(b.apDepth - apMm) ? z : b);
  if (green.length) t.size = (green.find(z => z.size === perm.femSize) || (apMm !== null ? nearest(green) : green[0])).size;
  else if (apMm !== null) t.size = nearest(f.sizes).size;
  else return;
  if (S.kc) kcSizeSet('tibia', t, false);
}
function autoKnee(slot) {
  const t = S.tpl[slot], f = S.fam[t.fam];
  // revision or conversion (amendment 4): with the joint surfaces on the existing implant the components go only on a
  // joint-line target with enough evidence; else nothing is placed automatically (said)
  { const w = kneeRevWhy(); if (w) { S.notes.kneeRev = w; return; } delete S.notes.kneeRev; }
  const J = jlTarget();
  // the plan values (each with its origin: kneeVals), one femoral and one tibial size for both films of a knee case, and a
  // note of what the placement used, so a later change of the long leg, the scale or these values flags the component (kcDepKey)
  const V = kneeVals();
  if (S.kc) t.dep = kcDepKey();
  if (f.kind === 'tka-femur' && viewOf() === 'AP') {
    const A = kneeAxes(), cm = A.cm, cl = A.cl;
    if (A && A.fma) t.angle = G.turn(G.P(0, -1), A.fma);
    if (cm && cl) {   // joint line through the more distal condyle: measured resection = implant thickness there
      const down = dirOf(t, [0, 1]), m = G.mid(cm.p, cl.p), deeper = G.dot(G.sub(cm.p, m), down) > G.dot(G.sub(cl.p, m), down) ? cm.p : cl.p;
      let p = G.add(m, G.mul(down, G.dot(G.sub(deeper, m), down)));
      // the knee case: the distal cut the surgeon's resection depth above the more distal condyle (the joint line then
      // sits the implant's distal thickness below the cut)
      // (every knee plan since 16 Oct: the surgeon's value or the assumed one, said with its origin)
      if (Number.isFinite(V.femDistal) && sizeOf(t) && !isTumourBody(f)) p = G.add(p, G.mul(down, pxOf(sizeOf(t).distalThickness - V.femDistal)));   // a tumour body: its joint line on the native one (r27)
      t.x = p.x; t.y = p.y;
    }
    if (J) { const kn = lmGeom('knee'), q = kn ? kn.p : G.mid(J.a, J.b), p = G.project(q, J.a, G.sub(J.b, J.a)); t.x = p.x; t.y = p.y; }   // the joint line on the revision target
  } else if (f.kind === 'tka-tibia' && viewOf() === 'AP') {
    kneeTibSize(t, f, null);
    const A = kneeAxes(), pm = A.pm, pl = A.pl;
    if (A && A.tma) t.angle = G.turn(G.P(0, -1), A.tma);
    if (pm && pl && Number.isFinite(V.tibial)) {   // the value below the reference plateau (Astra, 16 Oct, P1 2): the higher one (the page's choice), or the medial (the manual's 2 mm), or the lower (before 16 Oct); stored with the tray and drawn
      const up = dirOf(t, [0, -1]), rp = tibRefPlat(up), m = G.mid(pm.p, pl.p), hi = rp ? rp.p : pm.p;
      const off = isTumourBody(f) ? 0 : V.tibial;   // a tumour body's joint line on the native joint line (the reference plateau)
      const p = G.sub(G.add(m, G.mul(up, G.dot(G.sub(hi, m), up))), G.mul(up, pxOf(off))); t.x = p.x; t.y = p.y;
      t.tref = { plat: rp ? rp.plat : null, mode: V.tibRef, mm: off, p: { x: hi.x, y: hi.y } };
    } else delete t.tref;
    if (J) { const kn = lmGeom('knee'), q = kn ? kn.p : G.mid(J.a, J.b), j0 = G.project(q, J.a, G.sub(J.b, J.a)), p = G.sub(j0, G.mul(dirOf(t, [0, -1]), pxOf(tibJl(t)))); t.x = p.x; t.y = p.y; }   // the tray's joint line on the revision target
  } else if (f.kind === 'tka-tibia') {   // lateral: along the tibial axis, at the planned slope set on the extramedullary guide (Sami, 16 Oct: no angled blocks), the tibial value under the plateau
    const L = latKnee(), ant = antDir(L);
    kneeTibSize(t, f, L.pa && L.pp ? G.dist(L.pa.p, L.pp.p) * mm() : null);
    if (ant && !t.userFlip) t.flip = ant.x < 0;   // outlines face anterior to the right unless flipped
    delete t.tref;   // a lateral does not tell the medial and lateral plateaus apart (the reference row says so)
    if (L.up && Number.isFinite(V.slope)) {   // turn by the slope one way; if that tilts the wrong way, the other (the planned slope set on the extramedullary guide; nothing added for the tray)
      const a0 = G.turn(G.P(0, -1), L.up), want = isTumourBody(f) ? 0 : V.slope;   // a tumour body along the tibial axis
      t.angle = a0 + G.rad(want);
      if (L.post && Math.abs(slopeOf(t, L) - want) > 0.05) t.angle = a0 - G.rad(want);
    }
    if (L.pa && L.pp && L.up && Number.isFinite(V.tibial)) {
      const m = G.mid(L.pa.p, L.pp.p), up = dirOf(t, [0, -1]), p = G.sub(m, G.mul(up, pxOf(isTumourBody(f) ? 0 : V.tibial))); t.x = p.x; t.y = p.y;
    }
  } else if (f.kind === 'tka-femur' && isTumourBody(f)) {   // r27: a distal femoral replacement has no bone cuts to place it by: facing anterior only, placed by hand
    const ant0 = antDir(latKnee()); if (ant0 && !t.userFlip) t.flip = ant0.x < 0;
  } else if (f.kind === 'tka-femur') {     // lateral: anterior cut on the anterior cortex, back level with the condyles
    const L = latKnee(), ant0 = antDir(L);
    if (L.ac && L.pc && !t.userSize) { const d = G.lineDist(L.pc.p, L.ac.a, L.ac.b) * mm(); t.size = femBySizing(f, d).size; if (S.kc) kcSizeSet('femur', t, false); }
    const g = geomOf(t);
    if (ant0 && !t.userFlip) t.flip = ant0.x < 0;
    if (L.ac) {
      let up = G.unit(G.sub(L.ac.a, L.ac.b)); if (G.dot(up, cranVec()) < 0) up = G.mul(up, -1);
      const cutDir = dirOf(t, [g.cutProfile[0][0] - g.cutProfile[1][0], g.cutProfile[0][1] - g.cutProfile[1][1]]);   // anterior cut, upwards
      t.angle = normA(t.angle + G.turn(cutDir, up));
      const X = xf(t), a0 = X(g.cutProfile[0]), foot = G.project(a0, L.ac.a, up); t.x += foot.x - a0.x; t.y += foot.y - a0.y;
      if (L.pc && ant0) {   // slide along the cortex so the back of the component is level with the back of the condyles
        const X2 = xf(t), back = g.rings[0].map(X2).reduce((b, q) => (G.dot(q, ant0) < G.dot(b, ant0) ? q : b));
        const d = G.dot(G.sub(L.pc.p, back), up); t.x += up.x * d; t.y += up.y * d;
      }
    }
  }
}
// lateral knee landmarks in one place: tibial axis (proximal), plateau, native slope, anterior cortex, back of condyles
function latKnee(checked = true) {
  const ac = lmGeom('antCortex'), pc = lmGeom('postCond'), pp = lmGeom('platPost'), ta = lmGeom('tibAxis');
  const pa = checked && lmProblem('platAnt') ? null : lmGeom('platAnt');
  let up = null;
  if (ta) { up = G.unit(G.sub(ta.a, ta.b)); const pm = pa && pp ? G.mid(pa.p, pp.p) : null; if (pm ? G.dot(G.sub(pm, G.mid(ta.a, ta.b)), up) < 0 : G.dot(up, cranVec()) < 0) up = G.mul(up, -1); }
  const post = pa && pp ? G.unit(G.sub(pp.p, pa.p)) : null;
  const slope = up && post ? G.angleDeg(up, post) - 90 : null;
  return { ac, pc, pa, pp, up, post, slope };
}
// Anterior on this lateral film: from the back of the condyles towards the anterior cortex line (or plateau back to front).
function antDir(L) {
  if (L.ac && L.pc) { const d = G.sub(L.ac.b, L.ac.a); return G.unit(G.sub(G.project(L.pc.p, L.ac.a, d), L.pc.p)); }
  if (L.post) return G.mul(L.post, -1);
  return null;
}
// slope of the tray's cut line as placed, relative to the tibial axis (posterior down = positive)
function slopeOf(t, L) {
  const g = geomOf(t), X = xf(t), a = X(g.cutLine[0]), b = X(g.cutLine[1]);
  const post = L.post && G.dot(G.sub(b, a), L.post) < 0 ? G.sub(a, b) : G.sub(b, a);
  return G.angleDeg(L.up, post) - 90;
}
const normA = a => Math.atan2(Math.sin(a), Math.cos(a));
// Head centre (template mm) for a stem: the sheet's 0 head centre moved along the neck axis by the catalogue neck length
// of the chosen head (ceramic S/M/L/XL or CoCrMo -6..+9; all 12/14 taper).
const headSet = t => ((S.lib.heads[t.headKind] || {}).sizes || {})[t.head] || {};
// neck lengths shortest first (an object's integer-like key "0" would otherwise come first)
const neckOpts = t => Object.entries(headSet(t)).sort((a, b) => a[1] - b[1]);
function headPoint(t, neck) {
  const g = geomOf(t), off = headSet(t)[neck] ?? 0;
  return [g.headM[0] + off * g.neckDir[0], g.headM[1] + off * g.neckDir[1]];
}
const headAt = t => xf(t)(headPoint(t, t.neck));
// ---------- r38 (29 Oct 2026): the bipolar hemiarthroplasty ----------
// Sami, 29 Oct 2026: "build bipolar shells ... (PFR uses the same bipolar shell) build the bipolar shell with the corresponding
// femoral head from this template". Chunli's bipolar shells (vendor-families/ao-bipolar.json, extract_chunli_bipolar.py): r40,
// Kevin's DWG (29 Oct 2026): sizes 38-54 by 2 (outer diameter), each on ITS OWN inner head (size.innerHead: 22 mm on 38 and 40,
// 28 mm on 42-54; the family's innerHead gives the head system and the default; bipHead); frame: origin the head centre, +y along
// the shell axis to the dome, the outer sphere's centre at (0, -headOffset), headOffset per size (3.6 / 2.1 / 1.1 mm).
// A hip planned as a bipolar (S.hemi[side]) has no cup; its stem carries t.bip = {fam, size, userSize?}. The shell is drawn on
// the head centre with its axis along the stem's neck axis (it turns on the head in the joint: not modelled). Its outer centre
// is the hip's centre of rotation (corAt): placed on the native head centre, and leg length and offset are measured at it.
const hemiOn = sd => S.wf === 'hip' && !!(S.hemi && S.hemi[sd]) && !refOther();   // planned on the hip's own anatomy only
const hemiIn = v => (v && typeof v === 'object' && !Array.isArray(v) ? Object.fromEntries(['R', 'L'].filter(k => v[k] === true).map(k => [k, true])) : {});
const hemiOut = () => { const o = hemiIn(S.hemi); return Object.keys(o).length ? o : null; };
const bipFams = () => allFams('bipolar');
// r40: the head inside a shell size: the size's own diameter (Chunli: 22 mm on 38 and 40, 28 mm on 42-54), else the family's
const bipHead = (f, z) => ({ kind: f.innerHead.kind, diameter: z && Number.isFinite(z.innerHead) ? z.innerHead : f.innerHead.diameter });
const bipHeadOk = (f, z) => { const ih = bipHead(f, z); return !!((S.lib.heads[ih.kind] || {}).sizes || {})[String(ih.diameter)]; };
// "38-40 mm on the 22 mm head, 42-54 mm on the 28 mm head", from the data; and "CoCrMo (T head), 22 or 28 mm by shell size"
function bipHeadText(f) {
  const g = [];
  for (const z of f.sizes) { const d = bipHead(f, z).diameter, l = g[g.length - 1]; if (l && l.d === d) l.b = z.size; else g.push({ a: z.size, b: z.size, d }); }
  return g.map(x => `${x.a === x.b ? x.a : `${x.a}-${x.b}`} mm on the ${x.d} mm head`).join(', ');
}
// Fable r40 P3-1: said when a shell step switches the head
const bipSwitchMsg = (t, size, was, ih) => `Shell ${size} mm takes the ${ih.diameter} mm head (was ${was} mm), neck ${neckLabel(t, t.neck)}: the centre of rotation moved with it; check the offset and leg length rows.`;
const bipHeadsShort = f => `${(S.lib.heads[f.innerHead.kind] || {}).label || f.innerHead.kind}, ${[...new Set(f.sizes.map(z => bipHead(f, z).diameter))].join(' or ')} mm by shell size`;
// the shell family a stem can carry: its inner head is a head system the stem takes, with a size's head in it (Chunli's own stems)
function bipFamFor(sf) {
  if (!sf || sf.kind !== 'stem') return null;
  const ks = headKindsOf(sf);
  return bipFams().find(b => b.innerHead && ks.includes(b.innerHead.kind) && b.sizes.some(z => bipHeadOk(b, z))) || null;
}
function bipOf(t) { const f = t && t.bip && S.fam[t.bip.fam]; if (!f || f.kind !== 'bipolar') return null; const z = f.sizes.find(q => q.size === t.bip.size); return z ? { f, z } : null; }
const bipAxis = t => dirOf(t, geomOf(t).neckDir);   // image direction of the shell axis, toward the dome
// the hip's centre of rotation for a stem: the bipolar shell's outer centre, else the head centre
function corAt(t) { const b = bipOf(t), H = headAt(t); return b ? G.sub(H, G.mul(bipAxis(t), pxOf(b.z.headOffset))) : H; }
function bipRings(t) {   // the shell's outline in the image
  const b = bipOf(t); if (!b) return [];
  const H = headAt(t), d = bipAxis(t), n = G.P(-d.y, d.x), s = 1 / mm();
  return b.z.views.AP.rings.map(r => r.map(([x, y]) => G.P(H.x + (x * n.x + y * d.x) * s, H.y + (x * n.y + y * d.y) * s)));
}
// the native head the shell is sized from: this hip's own femoral head landmark (not on a hip planned from the other one)
function bipNative(sd) {
  if (refOther() && sd === S.side) return null;
  const hd = nativeHead(sd); if (!hd || !mm() || lmProblem('head' + sd)) return null;
  return { d: 2 * hd.r * mm(), what: `the ${SIDE[sd]} femoral head as marked` };
}
// the shell whose outer diameter is nearest d (mm); midway between two sizes goes to the smaller
const bipPick = (f, d) => f.sizes.filter(z => bipHeadOk(f, z)).reduce((b, z) => (Math.abs(z.outerDiameter - d) < Math.abs(b.outerDiameter - d) - 1e-9 ? z : b));   // r40: a size whose head is in the library
const bipRange = f => `${f.sizes[0].size}-${f.sizes[f.sizes.length - 1].size} mm`;
// r43 (Fable r42 P2): a size whose inner part Chunli has not drawn (r42: 40, 41, 43-55 odd), and the steps from the data
const bipAssumed = z => !!z && (!!(z.sheet && z.sheet.assumed) || /inner part assumed/.test(z.label || ''));
function bipSteps(f) {
  const d = f.sizes.slice(1).map((z, i) => z.outerDiameter - f.sizes[i].outerDiameter).filter(Number.isFinite); if (!d.length) return 'one size';
  const lo = Math.min(...d), mmS = v => String(+v.toFixed(1)); if (d.every(x => Math.abs(x - lo) < 0.01)) return `${mmS(lo)} mm steps`;
  return `${mmS(lo)} mm steps from ${f.sizes[d.findIndex(x => Math.abs(x - lo) < 0.01)].size} mm`;
}
function bipCredit(f) {   // the 22/28 mm split: Chunli's drawing, and Sami's decision (29 Oct 2026) for the sizes not drawn
  const a = f.sizes.filter(bipAssumed).map(z => z.size);
  return a.length ? `Chunli's drawing of 29 Oct 2026 for the drawn sizes; ${a.join(', ')} mm on the ${bipHead(f, f.sizes.find(bipAssumed)).diameter} mm head by Sami's decision of 29 Oct 2026, their inner part assumed until Chunli confirms` : 'Chunli\'s drawing of 29 Oct 2026';
}
function bipMissing(sd, t) {
  const bf = bipFams()[0];
  if (!bf) return 'no bipolar shell range is open to your account';
  if (!t) return 'place the stem first: the shell goes on its head';
  if (bipFamFor(S.fam[t.fam])) return 'the shell is not on the stem: press "Put the shell on the head" in the Bipolar head box, or Auto template';   // r40
  return `the ${bf.label} takes Chunli's ${bipHeadsShort(bf)} (${bipHeadText(bf)}): ${S.fam[t.fam] ? S.fam[t.fam].label : 'this stem'} does not take it; choose an Addis Ortho (Chunli) stem`;
}
// the shell on a bipolar hip's stem (and none on a total hip's): its head becomes the shell's inner head (the neck nearest the
// one it had), the centre of rotation kept where it was; resize: sized again from the native head unless set by hand
function bipEnsure(sd, { resize = false } = {}) {
  const t = S.tpl['stem' + sd]; if (!t || !S.fam[t.fam]) return;
  if (!hemiOn(sd)) { if (t.bip) delete t.bip; return; }
  if (t.bip && !S.fam[t.bip.fam]) return;   // a shell of a range not open here: kept as it is
  const bf = bipFamFor(S.fam[t.fam]); if (!bf) { delete t.bip; return; }
  // r40: the size first (a kept size set by hand, else from the native head), then the head THAT size takes (22 or 28 mm)
  const ok = !!(t.bip && t.bip.fam === bf.id && bipOf(t) && bipHeadOk(bf, bipOf(t).z)), keep = ok && (t.bip.userSize || !resize);
  const nd = keep ? null : bipNative(sd), z = keep ? bipOf(t).z : nd ? bipPick(bf, nd.d) : (ok ? bipOf(t).z : (L => L[Math.floor((L.length - 1) / 2)])(bf.sizes.filter(q => bipHeadOk(bf, q))));
  const ih = bipHead(bf, z), d = String(ih.diameter), off = t.headKind !== ih.kind || t.head !== d, nb = keep ? t.bip : { fam: bf.id, size: z.size };
  if (off && t.userHead && !(S.preBip || (S.preBip = {}))[sd]) S.preBip[sd] = { kind: t.headKind, head: t.head };   // Fable P3-2: kept, put back by setHemi
  if (ok && (off || nb.size !== t.bip.size)) changeStem(t, () => { t.bip = nb; if (off) setHead(t, ih.kind, d); });   // a shell already on: its centre of rotation kept
  else { if (off) changeStem(t, () => setHead(t, ih.kind, d)); t.bip = nb; }
  if (off) t.userHead = false;
}
// Total hip or Bipolar for a hip: a bipolar removes that hip's cup (asked first) and puts the shell on its stem, which is placed
// again by the stem rule (its outer centre on the native head centre); back to a total hip the shell goes, the cup waits for Auto template
function setHemi(sd, on) {
  if (S.wf !== 'hip' || !!hemiOn(sd) === on) return;
  if (on && refOther()) return prompt('Bipolar is planned on the hip\'s own anatomy: choose "plan on its own anatomy" first.');
  if (on && !bipFams().length) return prompt('No bipolar shell range is open to your account.');
  const cup = S.tpl['cup' + sd];
  if (on && cup && !confirm(`Bipolar on the ${SIDE[sd]}: the ${SIDE[sd]} cup (${S.fam[cup.fam] ? S.fam[cup.fam].label : cup.fam} ${cup.size}) is removed from the plan (Undo puts it back).`)) { panel(); return; }
  S.hemi = { ...hemiIn(S.hemi) }; if (on) S.hemi[sd] = true; else delete S.hemi[sd];
  if (on && cup) { delete S.tpl['cup' + sd]; if (S.active === 'cup' + sd) S.active = null; }
  const t = S.tpl['stem' + sd];
  if (t && on) {
    bipEnsure(sd, { resize: true });
    if (bipOf(t) && mm() && canalOf(sd)) guardStemOp(sd, () => planStem(sd, { keepSize: !!t.userSize, keepFam: !!t.userFam }, stemTarget(sd)));
  } else if (t) {   // the head chosen by hand before Bipolar again (Fable P3-2), else the stem's usual head until a cup's liner sets it
    delete t.bip; const f = S.fam[t.fam], pb = (S.preBip || {})[sd];
    if (f && pb && headKindsOf(f).includes(pb.kind) && ((S.lib.heads[pb.kind] || {}).sizes || {})[pb.head]) { changeStem(t, () => setHead(t, pb.kind, pb.head)); t.userHead = true; }
    else if (f && f.sheetHead && !t.userHead) changeStem(t, () => setHead(t, f.sheetHead.kind, f.sheetHead.diameter));
  }
  if (!on && S.preBip) delete S.preBip[sd];
  save(); panel(); draw();
  const b = t && bipOf(t);
  prompt(on ? (b ? `Bipolar on the ${SIDE[sd]}: shell ${b.z.size} mm on the ${bipHead(b.f, b.z).diameter} mm head; the stem placed again so the shell's outer centre sits on the native head centre. Check and fine-tune.` : t ? `Bipolar on the ${SIDE[sd]}: ${bipMissing(sd, t)}.` : `Bipolar on the ${SIDE[sd]}: press Auto template to place the stem and the shell.`)
    : `Total hip on the ${SIDE[sd]}: press Auto template to place the cup (the head follows the cup's liner).`);
}
// the choice in the panel and on the choice card: two radio buttons for a hip
function hemiRow(sd, inCard = false) {
  if (S.wf !== 'hip' || !hasKind('bipolar')) return null;
  if (refOther()) return inCard ? null : el('<p class="note">Bipolar (hemiarthroplasty) is planned on the hip\'s own anatomy: not offered while the surgery side is planned from the other hip.</p>');
  const on = hemiOn(sd), box = el('<div class="choice hemi"></div>');
  const g = el(`<div class="ch-g" role="radiogroup" aria-label="Procedure, ${SIDE[sd]} hip"><div class="ch-t">${cap(SIDE[sd])} hip${sd === S.side ? ' (surgery side)' : ''}</div></div>`);
  for (const [v, text] of [[false, 'Total hip (cup)'], [true, 'Bipolar (hemi)']]) {
    const b = el(`<button type="button" class="ch-o" role="radio" aria-checked="${on === v}" data-hemi="${v ? 'bipolar' : 'total'}" data-side="${sd}"><span class="ch-l">${text}</span></button>`);
    b.onclick = ev => { ev.stopPropagation(); if (on !== v) setHemi(sd, v); };
    g.appendChild(b);
  }
  box.appendChild(g);
  return box;
}
// the shell's own box, under the stem of a bipolar hip
function bipBox(sd) {
  const t = S.tpl['stem' + sd], box = el(`<div class="tpl ${sd === S.side ? '' : 'other'}" data-slot="bip${sd}"></div>`);
  box.appendChild(el(`<div class="row"><span class="name">Bipolar head, ${SIDE[sd]}${sd === S.side ? ' (surgery side)' : ''}</span></div>`));
  if (!t) { box.appendChild(el('<p class="note">No cup on a bipolar hip. Place the stem: the shell goes on its head (Auto template places both).</p>')); return box; }
  const bf = bipFamFor(S.fam[t.fam]), bp = bipOf(t);
  if (t.bip && !S.fam[t.bip.fam]) { box.appendChild(el('<p class="note">This plan\'s shell is from a range not open to your account: not shown, kept in the plan.</p>')); return box; }
  if (!bf) { box.appendChild(el(`<div class="warnbox">${esc(cap(bipMissing(sd, t)))}.</div>`)); return box; }
  if (!bp) { box.appendChild(btn('Put the shell on the head', ev => { ev.stopPropagation(); bipEnsure(sd, { resize: true }); save(); panel(); draw(); }, 'primary', !mm())); return box; }
  const sizes = bp.f.sizes, i = sizes.indexOf(bp.z), nd = bipNative(sd), rs = el('<div class="row"></div>'), ss = el('<select title="Bipolar shell, outside diameter"></select>');
  // r40: a new size takes ITS head (40 -> 42: 22 -> 28 mm), the centre of rotation kept where it was
  const setB = (size, hand = true) => { const z = sizes.find(q => q.size === size); if (!z || !bipHeadOk(bp.f, z)) return; const ih = bipHead(bp.f, z), off = t.headKind !== ih.kind || t.head !== String(ih.diameter);
    const was = t.head; changeStem(t, () => { t.bip = { fam: bp.f.id, size, ...(hand ? { userSize: true } : {}) }; if (off) setHead(t, ih.kind, String(ih.diameter)); }); if (off) t.userHead = false; save(); panel(); draw();
    if (off) prompt(bipSwitchMsg(t, size, was, ih)); };   // Fable r40 P3-1
  rs.appendChild(btn('−', ev => { ev.stopPropagation(); if (i > 0) setB(sizes[i - 1].size); }, '', i <= 0));
  for (const z of sizes) ss.add(new Option(z.label || `${z.size} mm`, z.size));
  ss.value = bp.z.size; ss.onchange = e => setB(e.target.value); rs.appendChild(ss);
  rs.appendChild(btn('+', ev => { ev.stopPropagation(); if (i < sizes.length - 1) setB(sizes[i + 1].size); }, '', i >= sizes.length - 1));
  rs.appendChild(btn('Size from the native head', ev => { ev.stopPropagation(); setB(bipPick(bp.f, nd.d).size, false); }, '', !nd, nd ? `The shell nearest the ${f1(nd.d)} mm native head (midway goes to the smaller)` : `Mark the ${SIDE[sd]} femoral head first`));
  box.appendChild(rs);
  box.appendChild(el(`<p class="note">${nd ? `Native head ${f1(nd.d)} mm (${esc(nd.what)}): nearest shell ${esc(bipPick(bp.f, nd.d).size)} mm (sizes ${bipRange(bp.f)}, ${bipSteps(bp.f)}; midway goes to the smaller)${t.bip.userSize ? `; ${esc(bp.z.size)} mm set by hand` : ''}.` : `Native head not marked: mark the ${SIDE[sd]} femoral head to size the shell from it.`} The head inside is the ${bipHead(bp.f, bp.z).diameter} mm ${esc((S.lib.heads[bp.f.innerHead.kind] || {}).label || '')} head (its neck on the stem above; the head goes with the shell size: ${esc(bipHeadText(bp.f))}). The shell's outer centre, ${f1(bp.z.headOffset)} mm from the head centre toward the neck, is the hip's centre of rotation: it goes on the native head centre, and leg length and offset are measured at it. Drawn with its axis along the neck axis.</p>`));
  if (isPfr(S.fam[t.fam])) box.appendChild(el('<p class="note warn">On the proximal femoral replacement: Sami (29 Oct 2026) says it takes the same Chunli bipolar shell; not confirmed by Chunli.</p>'));
  box.appendChild(el(`<p class="note">${esc(bp.f.note || '')}${bp.f.verified ? '' : ' <b>Not yet confirmed by the manufacturer.</b>'}</p>`));
  return box;
}
// the results rows of a stem's shell
function bipRows(R, sd, stem, bp, pre = '') {
  const { f, z } = bp, nd = bipNative(sd), hand = !!(stem.bip && stem.bip.userSize);
  R.push([lab(pre, 'Bipolar head'), `${f.label} ${z.size} mm outside${bipAssumed(z) ? ' (inner part assumed)' : ''}, on the ${bipHead(f, z).diameter} mm ${(S.lib.heads[f.innerHead.kind] || {}).label || ''} head, neck ${neckLabel(stem, stem.neck)}`]);
  if (String(bipHead(f, z).diameter) !== String(stem.head)) R.push([lab(pre, 'Bipolar head'), `the stem's head is ${stem.head} mm: this shell takes the ${bipHead(f, z).diameter} mm head`, 'bad']);   // r40: never shown silently
  if (nd) {
    const pick = bipPick(f, nd.d), dd = z.outerDiameter - nd.d, lo = f.sizes[0].outerDiameter, hi = f.sizes[f.sizes.length - 1].outerDiameter;
    R.push([lab(pre, 'Shell size from'), `the native head, ${f1(nd.d)} mm measured: nearest shell ${pick.size} mm (sizes ${bipRange(f)}, ${bipSteps(f)}; midway goes to the smaller)${hand ? `; ${z.size} mm set by hand` : ''}`, nd.d < lo - 1 || nd.d > hi + 1 ? 'warn' : '']);
    R.push([lab(pre, 'Shell vs native head'), Math.abs(dd) < 0.05 ? 'the same' : sgn(dd, 'larger', 'smaller'), Math.abs(dd) > 2 ? 'warn' : '']);
  } else R.push([lab(pre, 'Shell size from'), `not measured: mark the ${SIDE[sd]} femoral head; ${z.size} mm ${hand ? 'set by hand' : 'is a start, not a size'}`, 'warn']);
  R.push([lab(pre, 'Centre of rotation'), `the shell's outer centre, ${f1(z.headOffset)} mm from the ${bipHead(f, z).diameter} mm head centre toward the neck, along the neck axis (${bipAssumed(z) ? 'inner part assumed: not drawn by Chunli for this size, asked of Chunli' : 'Chunli\'s sheet'}): leg length and offset are measured at it, against the native head centre`, bipAssumed(z) ? 'warn' : '']);
  R.push([lab(pre, 'Shell axis'), 'drawn along the stem\'s neck axis; how the shell turns on the head in the joint is not modelled', 'warn']);
  if (isPfr(S.fam[stem.fam])) R.push([lab(pre, 'Bipolar on the PFR'), 'Sami (29 Oct 2026): the proximal femoral replacement takes the same Chunli bipolar shell; not confirmed by Chunli', 'warn']);
}
// the families drawn, with each stem's shell (for "not yet confirmed by the manufacturer")
const withBip = ts => ts.flatMap(t => (t && bipOf(t) ? [t, { fam: t.bip.fam, on: t.on }] : [t]));
function setHead(t, kind, size) {   // keep the neck length nearest to the current one; only a head system this stem takes
  const f = S.fam[t.fam], ok = headKindsOf(f), sh = f.sheetHead || {};
  if (!ok.includes(kind)) kind = ok.includes(sh.kind) ? sh.kind : ok[0];
  if (!kind) return;
  const was = t.headKind && t.head ? headSet(t)[t.neck] ?? 0 : 0;
  t.headKind = kind; const sizes = S.lib.heads[kind].sizes; t.head = sizes[size] ? size : Object.keys(sizes).includes('32') ? '32' : Object.keys(sizes)[0];
  const opts = Object.entries(sizes[t.head]); t.neck = opts.reduce((b, o) => Math.abs(o[1] - was) < Math.abs(b[1] - was) ? o : b)[0];
}
// femoral size whose anterior-cut-to-posterior-condyle dimension is nearest the native depth (anterior referencing)
function femBySizing(f, d) { return f.sizes.reduce((b, z) => Math.abs(z.sides[S.side].apSizing - d) < Math.abs(b.sides[S.side].apSizing - d) ? z : b); }
const neckLabel = (t, n) => { const o = headSet(t)[n]; return t.headKind === 'ceramic' ? `${n} (${o > 0 ? '+' : ''}${o})` : `${n} mm`; };
// Change stem size or family the way a surgeon expects: the head stays level (leg length unchanged); if the stem was
// along the canal it stays along the canal, so only a real offset difference shows.
function changeStem(t, apply) {
  const side = Object.keys(S.tpl).find(k => S.tpl[k] === t), sd = side ? slotSide(side) : S.side;
  const was = stemAligned(t, sd), H0 = corAt(t);
  apply();
  const H1 = corAt(t); t.x += H0.x - H1.x; t.y += H0.y - H1.y;   // same head centre (r38: centre of rotation: a bipolar shell's outer centre)
  if (was && (!guardedStem() || canalReviewed(sd))) { alignStem(t, sd); setHeadLevel(t, sd, G.dot(H0, vertical())); }   // (round 3: only onto a reviewed canal line)
}
// Size in a new family nearest in canal fill: stem width 60 mm below the head centre (template mm)
function widthAt(z, y) {
  const r = z.views.AP.rings[0], xs = [];
  for (let i = 0; i < r.length; i++) { const a = r[i], b = r[(i + 1) % r.length]; if ((a[1] - y) * (b[1] - y) <= 0 && a[1] !== b[1]) xs.push(a[0] + (y - a[1]) * (b[0] - a[0]) / (b[1] - a[1])); }
  return xs.length >= 2 ? Math.max(...xs) - Math.min(...xs) : NaN;
}

// ---------- calculations ----------
// u: along the teardrop line towards `side` (its lateral), v: cranial. Two teardrops too close together or a teardrop
// line far off the film's left-right give no frame at all, so nothing is measured from them (Astra, 11 Oct). On a film
// of one hip the film's own axes stand in (single: true): no leg length, no mirror.
function hipFrame(side = S.side) {
  const a = lmGeom('td' + side);
  if (S.scope === 'one') return a ? { u: latVec(side), v: cranVec(), T: a.p, Tc: null, single: true } : null;
  const b = lmGeom('td' + other(side)); if (!a || !b || tdProblem()) return null;
  const u = G.unit(G.sub(a.p, b.p)); let v = G.perp(u); if (G.dot(v, cranVec()) < 0) v = G.mul(v, -1);
  return { u, v, T: a.p, Tc: b.p };
}
function tdProblem() {
  const a = lmGeom('tdR'), b = lmGeom('tdL'); if (!a || !b || S.scope === 'one') return '';
  const d = G.dist(a.p, b.p), dm = mm() ? d * mm() : null;
  if (dm !== null ? dm < 50 : d < 40) return `the two teardrops are only ${dm !== null ? f1(dm) + ' mm' : Math.round(d) + ' px'} apart: mark each side's teardrop`;
  const tilt = G.angleDeg(G.sub(a.p, b.p), rot90(cranVec())), off = Math.min(tilt, 180 - tilt);
  if (off > 25) return `the teardrop line is ${Math.round(off)}° off the film's left-right: check both teardrops (or the orientation)`;
  return '';
}
// leg length now, operated against the other side, from the lesser trochanters below the teardrop line (mm, + longer)
function legLengthNow() {
  const H = hipFrame(), lo = lmGeom('lt' + S.side), lc = lmGeom('lt' + other(S.side)); if (!H || H.single || !lo || !lc) return null;
  if (lmProblem('lt' + S.side) || lmProblem('lt' + other(S.side))) return null;   // a trochanter on the wrong side measures nothing
  if (ltObscured()) return null;   // revision and conversion (amendment 4): an obscured lesser trochanter leaves leg length not measured
  return (G.dot(G.sub(lo.p, H.Tc), G.mul(H.v, -1)) - G.dot(G.sub(lc.p, H.Tc), G.mul(H.v, -1))) * mm();
}
// The femoral mechanical axis decision of a knee AP, one rule for the placement (kneeAxes) and the knee case's summary
// (Astra, r17 round 2): the hip and knee centres on the film ('measured'); else the long leg's anatomic-mechanical angle
// put onto the shaft axis ('transferred'), only for an assumed valgus and a valid measurement on the femoral shaft axis
// marked on both films (0-15°); else the valgus: the surgeon's or one entered before 16 Oct ('entered'), or the assumed
// one ('assumed'). → {how: 'measured' | 'transferred' | 'entered' | 'assumed' | null, deg, src, longUsed}
function femAxisRule({ hipKnee, anat, amaF, supine, vc }) {
  const xfOk = !!(anat && amaF !== null && amaF !== undefined && Math.abs(amaF) <= 15);
  if (hipKnee) return { how: 'measured', deg: null, src: 'hip centre to knee centre', longUsed: false };
  if (xfOk && vc.o === 'assumed') return { how: 'transferred', deg: Math.abs(amaF), src: `shaft axis turned ${f1(Math.abs(amaF))}° ${amaF >= 0 ? 'medially' : 'laterally'} (measured on the long leg${supine ? ', a supine CT scout' : ''}, on the same shaft section)`, longUsed: true };
  if (!anat) return { how: null, deg: null, src: '', longUsed: false };
  const lg = amaF !== null && amaF !== undefined ? (xfOk ? `; the long leg's ${f1(Math.abs(amaF))}° not used over it` : `; the long leg's ${f1(Math.abs(amaF))}° is outside 0-15°: not used`) : '';
  if (vc.o === 'surgeon') return { how: 'entered', deg: vc.v, src: `shaft axis turned ${f1(vc.v)}° (your valgus cut angle${lg})`, longUsed: false };
  if (vc.o === 'unknown') return { how: 'entered', deg: vc.v, src: `shaft axis turned ${f1(vc.v)}° (the valgus entered before 16 Oct, origin not recorded, kept${lg})`, longUsed: false };   // (Astra, r17 P1 2: never replaced by the long leg)
  return { how: 'assumed', deg: vc.v, src: `shaft axis turned ${f1(vc.v)}° (assumed, no hip on film${S.kc ? ' and no long film measurement' : ''}${lg})`, longUsed: false };
}
// the knee case's AP femoral axis as the placement decides it, whichever film is on screen
function kcApFemAxis() {
  const K = S.kc; if (!K || !K.films.ap) return null;
  if (K.active === 'ap' && S.wf === 'kneeAP') { const A = kneeAxes(); return { how: A.fmaHow || null, src: A.fmaSrc, longUsed: A.fmaHow === 'transferred' }; }
  const g = kcGeo('ap'), A2 = K.films.long ? kcLongAngles() : null, fs = g && g.g('femShaft');
  return femAxisRule({ hipKnee: !!(g && g.g('hip') && g.g('knee')), anat: !!fs, amaF: A2 && fs ? A2.amaF : null, supine: K.shared.longSrc === 'ctscout', vc: pvGet('vca') });
}
function kneeAxes() {
  const knee = lmGeom('knee'), hip = lmProblem('hip') ? null : lmGeom('hip'), ankle = lmProblem('ankle') ? null : lmGeom('ankle'), fs = lmGeom('femShaft'), ts = lmGeom('tibShaft');
  let cm = lmGeom('condMed'), cl = lmGeom('condLat'), pm = lmGeom('platMed'), pl = lmGeom('platLat'), swapCond = false, swapPlat = false;
  // which point is medial is decided by the film's sides, not by which button was clicked; a disagreement is reported
  if (cm && cl && G.dot(G.sub(cl.p, cm.p), latVec()) < 0) { [cm, cl] = [cl, cm]; swapCond = true; }
  if (pm && pl && G.dot(G.sub(pl.p, pm.p), latVec()) < 0) { [pm, pl] = [pl, pm]; swapPlat = true; }
  const latDir = cm && cl ? G.unit(G.sub(cl.p, cm.p)) : latVec();
  let anat = null;
  if (fs) { anat = G.unit(G.sub(fs.a, fs.b)); if (knee ? G.dot(G.sub(G.mid(fs.a, fs.b), knee.p), anat) < 0 : G.dot(anat, cranVec()) < 0) anat = G.mul(anat, -1); }
  let fma = null, fmaSrc = '', fmaHow = '';
  // a knee case's AP: the long leg's anatomic-to-mechanical angles put onto this film's own anatomic axes (the same shaft
  // section marked on both films), never a screen angle copied between films (Astra, P1 3); without a long leg, assumed
  const xf_ = S.kc && S.wf === 'kneeAP' && S.kc.active !== 'long' ? kcTransfer() : null;   // r44: the long film's own knee zone measures its axes
  // the long leg replaces the assumed valgus only with a valid measurement on a comparable shaft reference (the femoral shaft
  // axis marked on both films, the angle within 0-15°), and never over the surgeon's own valgus (Astra, 16 Oct, P1 3)
  const vc = S.wf === 'kneeAP' || S.wf === 'kneeLong' ? pvGet('vca') : { v: XN_KNEE.vcaStd, o: 'assumed' };
  const D = femAxisRule({ hipKnee: !!(hip && knee), anat: !!anat, amaF: xf_ ? xf_.amaF : null, supine: !!(xf_ && xf_.supine), vc });
  if (D.how === 'measured') fma = G.unit(G.sub(hip.c, knee.p));
  else if (D.how === 'transferred') fma = turnToward(anat, xf_.amaF, latVec());
  else if (D.how) { const a1 = G.rot(anat, G.rad(vc.v)), a2 = G.rot(anat, -G.rad(vc.v)); fma = G.dot(a1, latDir) < G.dot(a2, latDir) ? a1 : a2; }   // no hip on the film: the shaft axis turned medially by the valgus
  fmaSrc = D.src; fmaHow = D.how || '';
  let tma = null, tmaSrc = '', tmaHow = '';
  if (ankle && knee) { tma = G.unit(G.sub(knee.p, ankle.p)); tmaSrc = 'ankle centre to knee centre'; tmaHow = 'measured'; }
  else if (ts) {
    tma = G.unit(G.sub(ts.a, ts.b)); if (knee ? G.dot(G.sub(knee.p, G.mid(ts.a, ts.b)), tma) < 0 : G.dot(tma, cranVec()) < 0) tma = G.mul(tma, -1); tmaSrc = 'tibial shaft axis (no ankle on film)'; tmaHow = 'assumed';
    if (xf_ && xf_.amaT !== null) { tma = turnToward(tma, xf_.amaT, latVec()); tmaSrc = `tibial shaft axis turned ${f1(Math.abs(xf_.amaT))}° ${xf_.amaT >= 0 ? 'medially' : 'laterally'} (measured on the long leg, on the same shaft section)`; tmaHow = 'transferred'; }
    else if (S.kc) tmaSrc = K_NOLONG() ? 'the AP\'s tibial shaft axis (no long film): the tibial cut square to it' : 'tibial shaft axis (assumed: no long film with ankle and tibial axis)';
  }
  return { knee, hip, ankle, anat, fma, fmaSrc, fmaHow, tma, tmaSrc, tmaHow, latDir, cm, cl, pm, pl, swapCond, swapPlat };
}
const f1 = x => (Number.isFinite(x) ? (Math.round(x * 10) / 10).toFixed(1) : '–');
const sgn = (x, pos, neg) => `${f1(Math.abs(x))} mm ${x >= 0 ? pos : neg}`;

function results() { return llBare(results0); }   // Fable r44 P2-2: a long leg's implants not on a usable scale are not measured
function results0() {   // [[label, value, cls]] with group headers as [label]
  const R = [], k = mm();
  if (!S.img) return R;
  if (S.headerSide && S.headerSide !== S.side && S.wf !== 'hip') R.push(['Check side', `the image says ${SIDE[S.headerSide]}, the plan is for the ${SIDE[S.side]}`, 'bad']);
  if (S.headerSide && S.headerSide !== S.side && S.wf === 'hip' && S.singleJoint) R.push(['Check side', `the image says ${SIDE[S.headerSide]}, the surgery side is set to ${SIDE[S.side]}`, 'bad']);
  if (S.wf === 'diaph' && !diaU().view) { if (S.headerView === 'lateral' || S.headerView === 'AP') R.push(['Check view', `the image says ${S.headerView}: choose the film view on the Diaphyseal plan card`, 'warn']); }
  else if (S.headerView === 'lateral' && WF[S.wf].view === 'AP') R.push(['Check view', 'the image says lateral, the plan is for an AP film', 'bad']);
  if (S.headerView === 'AP' && WF[S.wf].view === 'LAT') R.push(['Check view', 'the image says AP, the plan is for a lateral film', 'bad']);
  for (const d of lmDefs()) { const pr = lmProblem(d.key); if (pr) R.push(['Check landmarks', `${d.label}: ${pr}`, 'bad']); }
  if ((S.orient.from === 'marker' || S.orient.from === 'assumed') && !latFilm()) R.push(['Orientation', 'not yet confirmed: which way the film faces', 'warn']);
  // set by hand against a marker that was read: stays in the results, on the film and in the saved report (Fable, 11 Oct)
  if (S.orient.from === 'user' && typeof S.orient.markerMirrored === 'boolean' && S.mirrored !== S.orient.markerMirrored)
    R.push(['Check orientation', `set to the patient's right on the image ${S.mirrored ? 'right' : 'left'}, against the R/L marker read on the film (${S.orient.markerSaid || 'marker'}), which puts it on the image ${S.orient.markerMirrored ? 'right' : 'left'}`, 'bad']);
  if (S.kc) kcFilmRows(R);   // this film of the knee case: its role, side and quality checks
  { const placed = Object.values(S.tpl).filter(t => t && S.fam[t.fam]), b = brandNow();   // r36 (Fable 9): the maker and any default start, also on the saved picture
    if (placed.length) {
      const dflt = [...new Set(placed.filter(t => !t.userFam && !chosenFam(S.fam[t.fam].kind) && isMakerDefault(S.fam[t.fam])).map(t => S.fam[t.fam].label))];
      R.push(['Implant maker', `${b === 'mixed' ? 'mixed (each part chosen)' : b || 'not chosen'}${brandSet() ? ', chosen for this case' : b ? ', from the implants placed' : ''}${dflt.length ? `; ${dflt.join(', ')}: Addis Ortho's usual primary range (the default start), not ranked against the maker's other ranges` : ''}`, dflt.length ? 'warn' : '']);
    }
  }
  if (revCase() || exRegions().length) caseRows(R);   // revision and conversion: the case type, the existing implants, the references
  R.push(['Calibration']);
  if (!S.cal) R.push(['Scale', angleOnly() ? 'none: the angles need no scale; lengths are not measured' : S.kc ? 'none: angles are measured; sizes and lengths need a calibration' : 'none: calibrate first', angleOnly() ? '' : 'bad']);
  else if (S.cal.method === 'xfer') {   // the long leg scaled from the AP (contract section 12): approximate, said so
    const st = kcXferStale();
    R.push(['Method', `scaled from the AP (the condylar width marked on both films): approximate, uncertainty not validated${st ? `; out of date (${st}): scale again` : ''}`, st ? 'bad' : 'warn']);
    R.push(['mm per pixel', S.cal.mmPerPx.toFixed(4)]);
  } else {
    R.push(['Method', S.cal.method === 'ball' ? `${S.ballD} mm ball${S.cal.how === 'found automatically' ? ' (found automatically)' : ''}` : S.cal.method === 'line' ? `line of ${S.cal.lengthMm} mm` : isCtCal(S.cal) ? `scale from the CT scanner: pixel spacing ${S.cal.mmPerPx} mm from the scout's header; no ball and no magnification (lengths along the limb are true; widths are true at the scanner's centre line and a few per cent off for a limb lying well above or below it)` : S.kc ? `DICOM pixel spacing: ${S.cal.note || 'from the image header'}${kcCtScout() ? '; a CT scout\'s spacing is the scanner\'s, not checked against a marker (magnification differs with depth)' : '; not checked against a marker'}` : 'machine estimate', calEst(S.cal) ? 'warn' : '']);
    R.push(['mm per pixel', S.cal.mmPerPx.toFixed(4)]);
    if (isCtCal(S.cal)) R.push(...ctChecks());   // r43
    if (ctPs() && (S.cal.method === 'ball' || S.cal.method === 'line')) R.push(['CT scanner scale', `${ctPs()} mm per pixel, replaced by your ${S.cal.method === 'ball' ? 'ball' : 'known length'} (${f1((S.cal.mmPerPx / ctPs() - 1) * 100)} % from it)`, Math.abs(S.cal.mmPerPx / ctPs() - 1) > 0.05 ? 'warn' : '']);   // r43: the override, said
    const e = dicomEstimate();
    if (e && S.cal.method !== 'dicom' && !ctPs()) {
      if (e.detector) {   // detector pitch only: the ball tells the magnification at the joint, usually 1.05-1.35
        const mag = e.mmPerPx / S.cal.mmPerPx;
        R.push(['Magnification at the marker', `${mag.toFixed(3)}`, mag < 1.0 || mag > 1.45 ? 'bad' : mag < 1.03 || mag > 1.35 ? 'warn' : '']);
      } else { const dd = (S.cal.mmPerPx / e.mmPerPx - 1) * 100; R.push(['vs machine estimate', `${f1(dd)} %`, Math.abs(dd) > 25 ? 'bad' : Math.abs(dd) > 10 ? 'warn' : '']); }
    }
    if (S.cal.method === 'ball' && S.cal.warnings && S.cal.warnings.length) R.push(['Calibration', 'uncertain: ' + S.cal.warnings[0].split(':')[0], 'warn']);
    if (S.cal.method === 'ball' && S.cal.confirmed === false) R.push(['Ball', 'found automatically, not yet confirmed', 'warn']);
  }
  // angles need no scale (Astra, P1 2): the knee's are measured with or without one; lengths and implants only with it
  if (!k && S.wf === 'hip') { if (S.ll) llRows(R, k); return R; }
  if (S.wf === 'hip') hipResults(R, k);
  else if (S.wf === 'kneeAP') kneeAPResults(R, k);
  else if (S.wf === 'kneeLat') kneeLatResults(R, k);
  else if (S.wf === 'shoulder') shoulderResults(R, k);   // r33
  else if (S.wf === 'diaph') diaRows(R, k);   // r39
  if ((S.wf === 'kneeAP' || S.wf === 'kneeLat') && k) tumRows(R, k);   // tumour resection (r22)
  else if (S.wf === 'kneeLong') kneeLongResults(R, k);
  else if (S.wf === 'kneeSky') kneeSkyResults(R);   // r33 (Fable P2 4): the skyline's rows only on the skyline film
  if (S.ll) llRows(R, k);   // r44: the long leg's own rows, in every zone
  if (k) for (const [i, r] of S.rulers.entries()) if (r.length === 2) { if (i === 0) R.push(['Rulers']); R.push([`Ruler ${i + 1}`, `${f1(G.dist(r[0], r[1]) * k)} mm`]); }
  for (const [i, a] of (S.angles || []).entries()) if (a.length === 3) { if (i === 0) R.push(['Angles']); R.push([`Angle ${i + 1}`, `${f1(angleAt(a))}°`]); }   // r36
  if (S.kc) kcSummaryRows(R);   // the whole knee case, every value with its film and its source
  return R;
}
function hipResults(R, k) {
  const o = S.side, n = other(o), H = hipFrame(o), h = lmGeom('head' + o), hc = lmGeom('head' + n), canal = canalOf(o), canalN = canalOf(n);
  const lo = lmGeom('lt' + o), lc = lmGeom('lt' + n), Hn = hipFrame(n);
  R.push([`Native hip (surgery side: ${SIDE[o]})`]);
  if (refOther()) R.push(['Planned from', `the ${SIDE[n]} hip, mirrored: the ${SIDE[o]} hip is deformed, its own head is not used`]);
  if (S.scope === 'one') R.push(['Reference', 'one hip on the film: the film\'s own axes stand in for the teardrop line; no leg length, no mirror', 'warn']);
  if (H && !H.single && G.dot(G.sub(H.T, H.Tc), latVec(o)) < 0) R.push(['Check side', `the ${SIDE[o]} teardrop is on the ${SIDE[n]} half of the film: check the surgery side and the orientation settings`, 'bad']);
  if (h) { const sp = G.arcSpan(lmPts('head' + o), h.c); if (sp < 90) R.push(['Head rim points', `cover only ${Math.round(sp)}° of the rim: spread them round the head`, 'warn']); }
  if (h) R.push([refOther() ? 'Head diameter (deformed)' : 'Head diameter', `${f1(2 * h.r * k)} mm`]);
  if (hc) R.push(['Other head diameter', `${f1(2 * hc.r * k)} mm`]);
  const lld = legLengthNow();
  if (lld !== null) R.push([refOther() ? 'Leg length now (fractured side)' : 'Leg length now', Math.abs(lld) < 0.05 ? 'equal' : `surgery side ${f1(Math.abs(lld))} mm ${lld > 0 ? 'longer' : 'shorter'}`]);
  else if (ltObscured()) R.push(['Leg length', 'not measured: a lesser trochanter is marked obscured', 'warn']);
  if (refOther()) R.push(['Leg length after', `restored by matching the ${SIDE[n]} side: same stem, head and neck, neck cut at the same height above the lesser trochanter (not measured on the fractured femur)`]);
  const nativeOff = h && canal ? G.lineDist(h.c, canal.a, canal.b) * k : null, otherOff = hc && canalN ? G.lineDist(hc.c, canalN.a, canalN.b) * k : null;
  if (nativeOff !== null) R.push([refOther() ? 'Femoral offset (deformed)' : 'Femoral offset', `${f1(nativeOff)} mm`]);
  if (otherOff !== null) R.push(['Other side femoral offset', `${f1(otherOff)} mm`]);
  if (h && H && !refOther()) R.push(['Acetabular offset (teardrop to head centre)', `${f1(G.dot(G.sub(h.c, H.T), H.u) * k)} mm`]);
  if (hc && Hn && !Hn.single) R.push(['Other side acetabular offset', `${f1(G.dot(G.sub(hc.c, Hn.T), Hn.u) * k)} mm`]);
  const cup = S.tpl['cup' + o], stem = S.tpl['stem' + o];
  let cor = null;
  if (hemiOn(o)) R.push(['Procedure', `bipolar hemiarthroplasty on the ${SIDE[o]}: no cup`]);   // r38
  if (cup && cup.on) {
    const f = S.fam[cup.fam], s = sizeOf(cup), X = xf(cup); cor = cupCentre(cup);   // r38b: the hip centre (the liner's head centre); X([0, 0]) is the shell's
    if (S.notes['mirrorMissing' + o] && !mirrorTarget(o)) R.push(['Cup centre', `not mirrored: the ${SIDE[n]} hip is not marked, so the cup went to the native head centre. Mark the ${SIDE[n]} femoral head (or place a ${SIDE[n]} cup), then press Mirror`, 'bad']);
    R.push([`Cup (${SIDE[o]})`]); R.push([f.label, `${s.size} (outside ${s.catalogueOuter || s.outerDiameter} mm)`]);
    if (s.catalogueOuter && Math.abs(s.outerDiameter - s.catalogueOuter) > 0.5) R.push(['Cup size check', `the ${drawnFrom(f)} is ${s.outerDiameter} mm outside, the catalogue ${s.catalogueOuter} mm`, 'warn']);
    if (headsAcrossLiners(cup)) R.push([...HEADS_ACROSS, 'warn']);
    if (H) {
      const face = dirOf(cup, [1, 0]); let inc = G.angleDeg(face, H.u); if (inc > 90) inc = 180 - inc;
      R.push(['Inclination', `${f1(inc)}°`, inc < 30 || inc > 50 ? 'warn' : '']);
      const d = G.sub(cor, H.T); R.push(['Centre from teardrop', `${f1(G.dot(d, H.u) * k)} lateral, ${f1(G.dot(d, H.v) * k)} up`]);
      if (linerOff(cup)) { const ds = G.sub(X([0, 0]), H.T); R.push(['Shell centre from teardrop', `${f1(G.dot(ds, H.u) * k)} lateral, ${f1(G.dot(ds, H.v) * k)} up`]); }   // r38b
      const out = rimVsRoof(cup, o, H, k);
      if (out !== null) R.push(['Cup rim vs roof edge', roofTxt(out), out > 8 ? 'warn' : '']);
    }
    cupRows(R, o, cup, H, k, refOther() ? (hc ? 2 * hc.r * k : null) : h ? 2 * h.r * k : null, refOther() ? `, ${SIDE[n]}` : '');
    if (h && H && !refOther()) { const d = G.sub(cor, h.c), up = G.dot(d, H.v) * k, upS = G.dot(G.sub(X([0, 0]), h.c), H.v) * k; R.push(['Centre vs native head', `${sgn(G.dot(d, H.u) * k, 'lateral', 'medial')}, ${sgn(up, 'up', 'down')}`, up > 3 ? 'warn' : '']); if (upS > 3) R.push(['Cup height', `the ${linerOff(cup) ? 'shell' : 'cup'} centre is ${f1(upS)} mm above the native head centre: check that the dome is not in the ilium above the acetabulum`, 'warn']); }
  }
  if (stem && stem.on) {
    const f = S.fam[stem.fam], s = sizeOf(stem), g = geomOf(stem), X = xf(stem), Hs = corAt(stem), bp = bipOf(stem);   // r38: measured at the centre of rotation
    R.push([`Stem (${SIDE[o]})`]); R.push([f.label, `size ${s.size}`]);
    R.push(['Head', `${stem.head} mm ${S.lib.heads[stem.headKind].label}, neck ${neckLabel(stem, stem.neck)}${bp ? ', inside the bipolar shell' : ''}`]);
    if (bp) bipRows(R, o, stem, bp); else if (hemiOn(o)) R.push(['Bipolar head', `none on the stem: ${bipMissing(o, stem)}`, 'warn']);   // r38
    if (cup && cup.on) {
      const cs = sizeOf(cup), ch = cupHeads(cup);
      if (ch && !ch.includes(String(stem.head))) R.push(['Head and cup', `cup ${cs.size} takes ${headsMm(ch)} heads`, 'warn']);
      else if (!ch && cs.maxHead && +stem.head !== cs.maxHead) R.push(['Head and cup', `cup ${cs.size} takes a ${linerTxt(S.fam[cup.fam], cs.liner)} liner for a ${headMm(cs.maxHead)} head`, 'warn']);
      headCaution(R, cup, stem);
    }
    if (g.headSource) R.push(['Head centre from', g.headSource.replace(/^Chunli sheet/, 'manufacturer\'s sheet'), /sheet/.test(g.headSource) && !/^assumed/.test(g.headSource) ? '' : 'warn']);   // r32: an assumed one flagged
    const tip = X(g.axis[0]), top = X(g.axis[1]);
    if (canal) {
      R.push(['New femoral offset', `${f1(G.lineDist(Hs, canal.a, canal.b) * k)} mm`]);
      // signed angle between the stem axis and the canal (both pointing to the hip): varus when the stem's top leans
      // medially, whatever its position
      let cUp = G.unit(G.sub(canal.b, canal.a)); if (G.dot(cUp, G.sub(Hs, tip)) < 0) cUp = G.mul(cUp, -1);
      const sUp = G.unit(G.sub(top, tip)), a2 = G.angleDeg(sUp, cUp), medial = G.mul(H ? H.u : latVec(o), -1);
      const varus = G.dot(G.sub(sUp, cUp), medial) > 0;
      const offLine = G.lineDist(G.mid(tip, top), canal.a, canal.b) * k;
      R.push(['Stem vs canal axis', !(a2 >= 0.25) ? (offLine > 1 ? `parallel, ${f1(offLine)} mm off the canal line` : 'aligned') : `${f1(a2)}° ${varus ? 'varus' : 'valgus'}`, a2 > 3 || offLine > 2 ? 'warn' : '']);
      const tr = !isPfr(f) && canalTraceFor(o), fit = tr ? stemFit(stem, tr) : null, Ls = tr ? stemLen(stem, tr) : 0, ev = fitIsEvidence(fit, tr, Ls);   // r32: a PFR is not sized by canal fill
      if (isPfr(f)) R.push(['Canal fill', 'not used: a tumour body is not sized by canal fill; check the stem against the canal with the ruler']);
      else if (fit) R.push(['Canal fill (distal third of the traced canal)', `${Math.round(fit.fillDistal * 100)} %; ${fit.maxIn > 0.5 ? `${f1(fit.maxIn)} mm past the inner cortex at the tightest level` : fit.maxIn >= -0.5 ? 'touching the inner cortex at the tightest level' : `${f1(-fit.maxIn)} mm clear of the inner cortex at the tightest level`}${ev ? '' : `; checked over only ${Math.round(fit.coveredMm)} of ${Math.round(Ls)} mm of stem (${Math.round(fit.maxGapMm)} mm unchecked in one stretch)`}`, fit.maxIn > 1 || !ev ? 'warn' : '']);
      else R.push(['Canal fill', 'not checked: the canal\'s inner cortex was not traced along this stem', 'warn']);
    }
    if (isPfr(f)) pfrRows(R, stem, o);   // r32
    else if (g.neckCut && lo) {
      const up = canal ? canalUp(canal) : vertical(), med = X(g.neckCut.medial), d = G.dot(G.sub(med, lo.p), up) * k;
      R.push(['Neck cut above lesser trochanter', `${f1(d)} mm (medial end of the cut)`]);
    } else if (!g.neckCut) R.push(['Neck cut', 'not printed on the sheet for this size', 'warn']);
    const target = cor || (!refOther() && h && h.c);
    if (target && H) {
      const d = G.sub(target, Hs), dL = -G.dot(d, H.v) * k, dO = G.dot(d, H.u) * k;
      R.push([cor ? 'After reduction to the cup' : bp ? 'After reduction: the bipolar outer centre against the native head centre' : 'After reduction to the native head centre']);
      R.push(['Leg length change', sgn(dL, 'longer', 'shorter')]);
      R.push(['Offset change', sgn(dO, 'more', 'less')]);
      if (lld !== null) { const p = lld + dL; R.push(['Leg length after', Math.abs(p) < 0.05 ? 'equal' : `surgery side ${f1(Math.abs(p))} mm ${p > 0 ? 'longer' : 'shorter'}`, Math.abs(p) > 5 ? 'warn' : '']); }
    }
    if (cor && H) stemCupRows(R, o, stem, cup, H, k);
  }
  if (revCase() && !refOther()) revHipRows(R, o, stem, k);   // the canal, the old implant and the fixation zone (amendment 3)
  // the other side's plan, and the operated side against it (mirrored about the pelvic midline)
  const cupN = S.tpl['cup' + n], stemN = S.tpl['stem' + n];
  if (refOther() && stemN && stemN.on) {   // the stem for the fractured side is templated on the normal one (Sami, 11 Oct)
    const gN = geomOf(stemN), Hs = headAt(stemN), cutN = neckCutAboveLT(stemN, n), hnat = lmGeom('head' + n), cN = cupN && cupN.on ? cupCentre(cupN) : null;   // r38b
    R.push([`Stem for the ${SIDE[o]} (templated on the ${SIDE[n]})`]);
    const sO = S.tpl['stem' + o];
    if (sO && sO.on && sO.byHand) R.push([`${SIDE[o]} stem on the film`, `${S.fam[sO.fam].label} ${sizeOf(sO).size}, placed by hand on the ${SIDE[o]} femur (not templated there)`]);
    R.push([S.fam[stemN.fam].label, `size ${sizeOf(stemN).size}`]);
    R.push(['Head', `${stemN.head} mm ${S.lib.heads[stemN.headKind].label}, neck ${neckLabel(stemN, stemN.neck)}`]);
    if (isPfr(S.fam[stemN.fam])) { R.push(['Tumour resection', `planned on the ${SIDE[n]} side, mirrored: measure it from the ${SIDE[o]} greater trochanter tip at surgery`, 'warn']); pfrRows(R, stemN, n); }   // r32 (Fable P2 4)
    else {
    R.push(['Neck cut above lesser trochanter', cutN !== null ? `${f1(cutN)} mm (medial end of the cut; measure from the ${SIDE[o]} lesser trochanter at surgery)` : `mark the ${SIDE[n]} lesser trochanter to measure it`, cutN !== null ? '' : 'warn']);
    if (!gN.neckCut) R.push(['Neck cut', 'not printed on the sheet for this size', 'warn']);
    }
    if (canalN && hnat) R.push(['Femoral offset', `${f1(G.lineDist(Hs, canalN.a, canalN.b) * k)} mm planned, ${f1(G.lineDist(hnat.c, canalN.a, canalN.b) * k)} mm native (${SIDE[n]})`]);
    if (cN && Hn && !Hn.single) { const d = G.sub(cN, Hs); R.push([`Leg length change on the ${SIDE[n]} plan`, sgn(-G.dot(d, Hn.v) * k, 'longer', 'shorter'), Math.abs(G.dot(d, Hn.v) * k) > 5 ? 'warn' : '']); }
    const cupP = cupN && cupN.on ? cupN : cup && cup.on ? cup : null; if (cupP) headCaution(R, cupP, stemN);
    if (cN && Hn && !Hn.single) stemCupRows(R, n, stemN, cupN, Hn, k);
    const trN = !isPfr(S.fam[stemN.fam]) && canalTraceFor(n), fitN = trN ? stemFit(stemN, trN) : null;   // r32: a PFR is not sized by canal fill
    if (fitN) R.push([`Canal fill, ${SIDE[n]} (distal third)`, `${Math.round(fitN.fillDistal * 100)} %; ${fitN.maxIn > 0.5 ? `${f1(fitN.maxIn)} mm past the inner cortex` : fitN.maxIn >= -0.5 ? 'touching the inner cortex' : `${f1(-fitN.maxIn)} mm clear of the inner cortex`} at the tightest level`, fitN.maxIn > 1 ? 'warn' : '']);
    if (cupN && cupN.on) {
      const s2 = sizeOf(cupN); R.push([`${SIDE[n]} cup (reference)`, `${s2.size} (outside ${s2.catalogueOuter || s2.outerDiameter} mm), placed by the ${cupRuleName(S.notes['cupRule' + n])}`]);
      if (Hn) {
        const d = G.sub(cN, Hn.T); let inc = G.angleDeg(dirOf(cupN, [1, 0]), Hn.u); if (inc > 90) inc = 180 - inc;
        R.push([`${SIDE[n]} cup inclination`, `${f1(inc)}°`, inc < 30 || inc > 50 ? 'warn' : '']);
        R.push([`${SIDE[n]} cup centre from teardrop`, `${f1(G.dot(d, Hn.u) * k)} lateral, ${f1(G.dot(d, Hn.v) * k)} up`]);
        const outN = rimVsRoof(cupN, n, Hn, k);   // the reference cup's roof coverage (Astra, finding 3)
        if (outN !== null) R.push([`${SIDE[n]} cup rim vs roof edge`, roofTxt(outN), outN > 8 ? 'warn' : '']);
      }
      cupRows(R, n, cupN, Hn, k, hnat ? 2 * hnat.r * k : null, '', `${SIDE[n]} `);
      if (hnat && Hn) { const d = G.sub(cN, hnat.c), up = G.dot(d, Hn.v) * k; R.push([`${SIDE[n]} cup centre vs native head`, `${sgn(G.dot(d, Hn.u) * k, 'lateral', 'medial')}, ${sgn(up, 'up', 'down')}`, up > 3 ? 'warn' : '']); if (up > 3) R.push([`${SIDE[n]} cup height`, `the cup centre is ${f1(up)} mm above the native head centre: check that the dome is not in the ilium above the acetabulum`, 'warn']); }
    }
  } else if ((cupN && cupN.on) || (stemN && stemN.on)) {
    R.push([`Other side plan (${SIDE[n]})`]);
    if (cupN && cupN.on) { const s = sizeOf(cupN); R.push(['Other side cup', `${s.size} (outside ${s.catalogueOuter || s.outerDiameter} mm)`]); }
    if (stemN && stemN.on) {
      R.push(['Other side stem', `${S.fam[stemN.fam].label} ${sizeOf(stemN).size}, ${stemN.head} mm head, neck ${neckLabel(stemN, stemN.neck)}${bipOf(stemN) ? `, bipolar ${bipOf(stemN).z.size} mm` : ''}`]);
      if (canalN) R.push(['Other side new femoral offset', `${f1(G.lineDist(corAt(stemN), canalN.a, canalN.b) * k)} mm${bipOf(stemN) ? ' (at the bipolar outer centre)' : ''}`]);
    }
  }
  const mt = mirrorTarget(o);
  if (H && !H.single && (cor || (stem && stem.on)) && mirrorRef(o)) {
    R.push([`Surgery side against the ${SIDE[n]} side`]);
    const ref = mirrorRef(o);
    if (ref) R.push(['Mirror reference', `${ref.what}${ref.kind === 'plan' && !hc ? ` (the ${SIDE[n]} head is not marked)` : ''}${ref.offset ? ` (femoral offset ${f1(ref.offset)} mm)` : ''}`]);
    if (cor && mt) {
      const d = G.sub(cor, mt), off = G.dist(cor, mt) * k, meant = S.set.cupRule === 'mirror' || S.mirror;
      R.push(['Hip centre vs mirrored other side', `${sgn(G.dot(d, H.u) * k, 'lateral', 'medial')}, ${sgn(G.dot(d, H.v) * k, 'up', 'down')}`, meant && off > 2 ? 'warn' : '']);
      if (meant && off > 2) R.push(['Mirror', `the cup is ${f1(off)} mm from the mirrored ${SIDE[n]} hip centre: press Mirror to put it there`, 'warn']);
    }
    if (stem && stem.on && canal && ref && ref.offset) {
      const dOff = G.lineDist(corAt(stem), canal.a, canal.b) * k - ref.offset;
      R.push(['Femoral offset vs other side', sgn(dOff, 'more', 'less'), Math.abs(dOff) > 5 ? 'warn' : '']);
    }
  }
}
// the cup's superolateral rim against the roof edge of its side, across the pelvis (mm, + lateral of the edge: uncovered),
// or null when the roof edge is not marked
// a cup size's head centre beyond its sphere centre (mm): 0 for a hemisphere, 1.5 for the Zimmer Biomet G7 shell (r21)
const cupOff = z => (z && Number.isFinite(z.headBeyondCentre) ? z.headBeyondCentre : 0);
// ---------- r38b (29 Oct 2026): the Pinnacle +4 liner (DESIGN-2026-10-29-pinnacle-plus4.md) ----------
// A cup size that lists its liners (J&J Pinnacle 38-76: {code, material, head, type, headCentreMm}) has one liner type per cup,
// cup.liner, a key of the family's linerTypes; cup.userLiner when the surgeon chose it. The outline's origin is the shell's
// sphere centre, which is the neutral liner's head centre; a +4 liner puts the head centre 4 mm further out along the shell
// axis (+y, the family's headCentreAxis). cupCentre is the hip's centre of rotation (offset, leg length, stem levelling,
// mirror, the drawn head ring); the shell centre, xf(cup)([0, 0]), stays for the bone (the roof, the teardrop, the dome).
// A cup without per-size liners (Chunli, Trilogy, G7, Pinnacle 78/80) has cupCentre = xf(cup)([0, 0]): nothing else moves.
const COR_RULES = ['head', 'mirror', 'target'];   // the cup rules that aim the centre of rotation (the others place the shell on bone)
const linerTypesOf = cup => { const f = cup && S.fam[cup.fam]; return f && f.linerTypes && typeof f.linerTypes === 'object' ? f.linerTypes : {}; };
const linerOpts = cup => { const z = cup && S.fam[cup.fam] && sizeOf(cup); return z && Array.isArray(z.liners) ? z.liners : []; };
// the liner types the size offers for a head (every type it has when head is null), in linerTypes order
function linerTypesFor(cup, head) {
  const have = new Set(linerOpts(cup).filter(l => head === null || head === undefined || String(l.head) === String(head)).map(l => l.type));
  return Object.keys(linerTypesOf(cup)).filter(k => have.has(k));
}
function linerOf(cup) { return cup && typeof cup.liner === 'string' && linerTypesOf(cup)[cup.liner] && linerOpts(cup).some(l => l.type === cup.liner) ? cup.liner : null; }
function linerOff(cup) { const l = linerOf(cup), v = l ? linerTypesOf(cup)[l].headCentreMm : 0; return Number.isFinite(v) ? v : 0; }
const cupCentre = cup => xf(cup)([0, linerOff(cup)]);
const linerName = (cup, ty) => ((linerTypesOf(cup)[ty] || {}).label || String(ty));
// the liners of the chosen type for a head, e.g. "+4 neutral, MARATHON or ALTRX (1219-32-448, 1221-32-448)", or null
function linerLine(cup, head) {
  const ty = linerOf(cup); if (!ty) return null;
  const L = linerOpts(cup).filter(l => l.type === ty && String(l.head) === String(head)); if (!L.length) return null;
  return `${linerName(cup, ty)}, ${[...new Set(L.map(l => l.material))].join(' or ')} (${[...new Set(L.map(l => l.code))].join(', ')})`;   // one code printed for two materials (ALTRX +4 32 on 50-54) shown once
}
// the shell moved so that its head centre stays put when the liner's head centre moves d mm out of the shell
function linerShift(cup, d) { const k = mm(); if (!d || !k) return; const v = dirOf(cup, [0, 1]); cup.x -= v.x * d / k; cup.y -= v.y * d / k; }
// the head the cup's liner must take: its own stem's, or (a hip planned from the other one) the other side's stem's
function linerHead(sd) {
  const st = S.tpl['stem' + sd] || (refOther() && sd === S.side ? S.tpl['stem' + other(sd)] : null);
  return st && S.fam[st.fam] && S.fam[st.fam].kind === 'stem' && !bipOf(st) ? st.head : null;
}
// The liner of a side's cup: the surgeon's type while the size offers it for the head; otherwise (and always when not chosen
// by hand) neutral if offered, else +4 neutral, else the first type offered; said in S.notes.liner<side> when it is not
// neutral or a choice made by hand had to go. Returns how far the head centre moved (mm, out of the shell).
function linerFix(sd) {
  const cup = S.tpl['cup' + sd]; if (!cup || !S.fam[cup.fam]) return 0;
  const before = linerOff(cup);
  if (!linerOpts(cup).length) { delete cup.liner; delete cup.userLiner; delete S.notes['liner' + sd]; return linerOff(cup) - before; }
  const head = linerHead(sd), ok = linerTypesFor(cup, head), all = linerTypesFor(cup, null), had = linerOf(cup);
  if (cup.userLiner && had && ok.includes(had)) return 0;   // the surgeon's choice, offered for this head
  const lost = cup.userLiner && had ? had : null;
  const from = ok.length ? ok : all;   // no liner of any type for this head on this size: "Head and cup" says so; the type stays a default
  const pick = from.includes('neutral') ? 'neutral' : from.includes('plus4') ? 'plus4' : from[0] || null;
  if (!pick) { delete cup.liner; delete cup.userLiner; return linerOff(cup) - before; }
  cup.liner = pick; delete cup.userLiner;
  if (lost) S.notes['liner' + sd] = `Liner set to ${linerName(cup, pick)}: no ${linerName(cup, lost)} liner for a ${headMm(head)} head on cup ${cup.size}`;
  else if (pick !== 'neutral' && ok.length && head !== null) S.notes['liner' + sd] = `Liner ${linerName(cup, pick)}: no neutral liner for a ${headMm(head)} head on cup ${cup.size}`;
  else delete S.notes['liner' + sd];
  return linerOff(cup) - before;
}
// the outside diameter whose face spans faceD (mm): the face is a chord 2 cupOff before the sphere's equator (Astra r21b P3)
function faceToOuter(f, faceD) { const o = cupOff(f && f.sizes && f.sizes[0]); return Math.sqrt(faceD * faceD + 4 * o * o); }
function rimVsRoof(cup, sd, H, k) {
  const ac = lmGeom('ac' + sd); if (!ac || !H) return null;
  const s = sizeOf(cup), cor = xf(cup)([0, 0]); let inc = G.angleDeg(dirOf(cup, [1, 0]), H.u); if (inc > 90) inc = 180 - inc;
  const r = faceHalf((s.catalogueOuter || s.outerDiameter) / 2 / k, cupOff(s) / k), rim = G.add(cor, G.mul(G.add(G.mul(H.u, Math.cos(G.rad(inc))), G.mul(H.v, Math.sin(G.rad(inc)))), r));
  return G.dot(G.sub(rim, ac.p), H.u) * k;
}
const roofTxt = out => (Math.abs(out) < 0.5 ? 'level' : out > 0 ? `${f1(out)} mm uncovered` : `${f1(-out)} mm inside the roof`);
// A cup's size failure and its position for the surgeon to judge (13 Oct 2026, amendment 1): the teardrop technique is
// a starting position, so the inferomedial edge and the shell's most medial point are given against the teardrop, with
// the roof coverage when the roof edge is marked; Köhler's line is not a landmark here, so it is named as unchecked.
// nativeD: the native head diameter the cup is compared with (mm) or null.
const lab = (pre, t) => (pre ? pre + t.charAt(0).toLowerCase() + t.slice(1) : t);   // "left cup size" for the other side's rows
function cupRows(R, sd, cup, H, k, nativeD, whatHead = '', pre = '') {
  const f = S.fam[cup.fam], s = sizeOf(cup), outer = s.catalogueOuter || s.outerDiameter, cf = S.notes['cupFail' + sd];
  if (cf && cf.size === cup.size && cf.fam === cup.fam) R.push([lab(pre, 'Cup size'), cupFailText(cf, f), cf.dir === 'above' ? 'bad' : 'warn']);
  const cr = S.notes['cupRoof' + sd];
  if (cr && cr.size === cup.size) R.push([lab(pre, 'Cup vs roof edge'), `even the smallest ${f.label} at the native centre height reaches ${f1(cr.over)} mm past the marked roof edge: check the roof mark and the size`, 'warn']);
  if (nativeD && outer < nativeD + 2) R.push([lab(pre, 'Cup vs native head'), `${outer} mm outside is smaller than the native head (${f1(nativeD)} mm${whatHead}) + 2 mm: check the size`, 'warn']);
  if (!H) return;
  let inc = G.angleDeg(dirOf(cup, [1, 0]), H.u); if (inc > 90) inc = 180 - inc;
  const p = cupVsTeardrop(xf(cup)([0, 0]), outer / 2 / k, inc, H.T, H.u, H.v, k, cupOff(s) / k);
  R.push([lab(pre, 'Inferomedial edge vs teardrop'), `${sgn(p.inferomedial.lat, 'lateral', 'medial')}, ${sgn(p.inferomedial.up, 'up', 'down')}`]);
  R.push([lab(pre, 'Most medial point of the cup'), `${sgn(p.medial.lat, 'lateral', 'medial')} of the teardrop`, p.medial.lat < -3 ? 'warn' : '']);
  const copy = refOther() && sd === S.side;   // a mirrored copy: judged on the reference side
  // the lowest rim (the face's inferomedial end) against the teardrop line (14 Oct 2026): over 5 mm off it, check the cup's height
  R.push([lab(pre, 'Lowest rim vs teardrop line'), Math.abs(p.lowest.up) < 0.05 ? 'on the line' : `${sgn(p.lowest.up, 'above', 'below')} the line`, !copy && Math.abs(p.lowest.up) > 5 ? 'warn' : '']);
  if (!copy && S.notes['cupAnat' + sd] && S.notes['cupRule' + sd] === 'anatomic') R.push([lab(pre, 'Cup medialisation'), anatText(S.notes['cupAnat' + sd], sd), S.notes['cupAnat' + sd].capped ? 'warn' : '']);
  if (copy) return;
  if (!lmGeom('ac' + sd)) R.push([lab(pre, 'Roof coverage'), 'not measured: mark the acetabular roof edge to see the uncovered rim']);
  R.push([lab(pre, 'Medial wall'), 'Köhler\'s line is not marked here: judge the medial wall and the bone left on the film']);
}
// The stem against its cup (13 Oct 2026, amendment 2): the planned lengthening kept apart from the residual miss of the
// head centre (pelvic axes, signed), the femoral offset against the native hip, the combined offset against the native
// or other side with the scale's uncertainty, and why this stem was chosen.
function stemCupRows(R, sd, stem, cup, H, k) {
  const C = cupCentre(cup), hp = headAt(stem), d = G.sub(hp, C), L = levelPlan(sd), gain = L ? L.gain : 0;
  const pre = sd === S.side ? '' : `${SIDE[sd]} `;
  R.push([lab(pre, 'Planned lengthening'), !L ? '–' : L.missing ? 'not known: mark both lesser trochanters (the head is set level with the cup)' : L.why === 'other' ? 'none: this hip keeps its own length' : L.why === 'keep' ? 'none (leg length kept as it is)' : Math.abs(gain) < 0.05 ? 'none (legs already equal)' : gain > 0 ? `${f1(gain)} mm longer` : `${f1(-gain)} mm shorter`, L && L.missing ? 'warn' : '']);
  const mh = G.dot(d, H.u) * k, mv = G.dot(d, H.v) * k - gain;
  R.push([lab(pre, 'Head centre vs cup centre'), `${sgn(mh, 'lateral', 'medial')}, ${sgn(mv, 'up', 'down')}${Math.abs(gain) >= 0.05 ? ' (planned lengthening taken off)' : ''}`, Math.abs(mh) > 3 || Math.abs(mv) > 3 ? 'warn' : '']);
  const h = refOther() && sd === S.side ? null : lmGeom('head' + sd), c = canalOf(sd);
  if (h && c) R.push([lab(pre, 'Femoral offset vs native'), sgn((G.lineDist(hp, c.a, c.b) - G.lineDist(h.c, c.a, c.b)) * k, 'gained', 'lost')]);
  const nat = nativeCombined(sd), now = combinedNow(sd), un = scaleUncertainty(S.cal);
  if (nat && now !== null) { const ch = now - nat.mm; R.push([lab(pre, 'Combined offset'), `${f1(now)} mm: ${sgn(ch, 'more', 'less')} than ${nat.what} (${f1(nat.mm)} mm), ±${f1(un * nat.mm)} mm for the scale (±${f1(un * 100)} %)`, Math.abs(ch) > 5 ? 'warn' : '']); }
  else if (now !== null) R.push([lab(pre, 'Combined offset'), `${f1(now)} mm; not compared: the native hip is not measured (mark its head and canal)`]);
  const sp = S.notes['stemPlan' + sd];
  if (sp && sp.why && sp.fam === stem.fam && sp.size === stem.size && sp.neck === stem.neck) R.push([lab(pre, 'Stem choice'), sp.why, sp.none || sp.overHand ? 'warn' : '']);
}
// r32: a proximal femoral replacement's rows: the resection from the GT tip, that tip against the implant's head centre level,
// the stack and its osteotomy, the stem, and every assumption it rests on
function pfrRows(R, t, side) {
  const f = S.fam[t.fam], z = sizeOf(t), P = pfrPlan(t, side), tp = P.tp, c = tumCollar(t), st = stemOf(t);
  R.push(['Tumour resection (proximal femur)']);
  if (!tp.ok) R.push(['Resection', `not planned: ${tp.why}`, 'warn']);
  else {
    R.push(['Tumour extent', `${f1(tp.extent)} mm below the tip of the greater trochanter, from the ${tp.src} (entered by the surgeon)`]);
    R.push(['Margin', `${f1(tp.margin)} mm (your choice)`]);
    R.push(['Resection length', `${f1(tp.resection)} mm below the tip of the greater trochanter`]);
  }
  if (!P.gt) R.push(['Greater trochanter', `not marked: mark the tip of the ${SIDE[side]} greater trochanter; the resection is measured from it`, 'warn']);
  else if (Number.isFinite(P.sGT)) R.push(['GT tip vs head centre', `the GT tip is ${f1(Math.abs(P.sGT))} mm ${P.sGT >= 0 ? 'below' : 'above'} this implant's ${pfrLevelTxt(t)}, along its axis (this film, as placed)`]);
  if (c === null) R.push(['Reconstruction', `${z.label || z.size}: ${fmm(z.reconstructionMm)} mm below its neck-0 head centre level without the stem collar; choose an option or a stem (${tumParts(z, null)})`, 'warn']);
  else {
    const tot = z.reconstructionMm + c, d = Number.isFinite(P.sPlan) ? tot - P.sPlan : null;
    R.push(['Reconstruction', `${z.label || z.size} + stem collar ${c}: ${tumParts(z, c)} = ${fmm(tot)} mm below its neck-0 head centre level`]);
    if (Number.isFinite(P.sGT)) R.push(['Osteotomy of this stack', `${fmm(tot - P.sGT)} mm below the GT tip${d !== null ? ` (${d >= 0 ? '+' : ''}${fmm(d)} mm against the planned resection)` : ''}`, d !== null && Math.abs(d) > 5 ? 'warn' : '']);
  }
  R.push(['Stem', st ? `${st.s.label || st.s.size}: ${st.s.diameterMm} mm, ${f1(st.s.drawnLengthMm - (c || 0))} mm beyond its collar as drawn; check the canal width at the tip and mid-stem with the ruler` : 'not chosen yet: choose the universal stem; check its diameter against the canal with the ruler', st ? '' : 'warn']);
  R.push(['Taper junctions', pfrGapNote(z), 'warn']);
  for (const a of f.assumptions || []) R.push(['Assumed (not confirmed by Chunli)', a, 'warn']);
  R.push(['Placement', bipOf(t) ? 'placed, not sized: the bipolar shell\'s outer centre on the native head centre, the axis along the canal; drag and turn to fine-tune. The bipolar shell on this body: Sami (29 Oct 2026) says the PFR takes the same Chunli shell; not confirmed by Chunli'
    : `placed, not sized: the head centre on the planned centre (the cup, or the native head), the axis along the canal; drag and turn to fine-tune. ${hasKind('bipolar') ? 'For a hemiarthroplasty choose Bipolar for this hip: the PFR takes the same Chunli bipolar shell (Sami, 29 Oct 2026; not confirmed by Chunli)' : 'A bipolar shell on this body (Sami, 29 Oct 2026: the same Chunli shell) needs the Addis Ortho bipolar head range, which is not open to your account'}`, 'warn']);   // Fable P3-1
  R.push(['Scale', 'lengths use this film\'s ball; the resection level lies further from it, where magnification may differ (not validated)', 'warn']);
}
// the tumour resection rows (r22): what the plan is built on (MRI/CT extent and margin), the body chosen against it, the
// remaining bone and the stem; every length projected on this film and scaled by its ball (not at the resection level)
function tumRows(R, k) { for (const slot of ['femur', 'tibia']) tumRowsOf(R, k, slot); }
function tumRowsOf(R, k, slot) {
  const t = S.tpl[slot], f = t && t.on !== false && S.fam[t.fam]; if (!isTumourBody(f)) return;
  const fem = isTumFemur(f), side = tumSide(f), z = sizeOf(t), tp = tumPlan(t); R.push([fem ? 'Tumour resection (distal femur)' : 'Tumour resection (proximal tibia)']);
  if (!tp.ok) { R.push(['Resection', `not planned: ${tp.why}`, 'warn']); return; }
  R.push(['Tumour extent', `${f1(tp.extent)} mm ${side} the joint line, from the ${tp.src} (entered by the surgeon)`]);
  R.push(['Margin', `${f1(tp.margin)} mm (your choice)`]);
  R.push(['Resection length', `${f1(tp.resection)} mm ${side} the joint line${fem ? ', along the femoral body\'s axis' : ''}`]);
  const st = stemOf(t), c = tumCollar(t), tot = z.reconstructionMm + (c || 0), d = tot - tp.resection;
  const lw = tumLenWarn(f, z);
  if (c === null) R.push(['Reconstruction', `${z.label || z.size}: ${f1(z.reconstructionMm)} mm without the stem collar; choose an option or a stem (${tumParts(z, null)})`, 'warn']);
  else R.push(['Reconstruction', `${z.label || z.size} + stem collar ${c}: ${tumParts(z, c)} = ${fmm(tot)} mm (${d >= 0 ? '+' : ''}${fmm(d)} mm against the resection; not a leg-length measurement)`, Math.abs(d) > 5 ? 'warn' : '']);
  if (tumGapNote(z)) R.push(['Taper junctions', tumGapNote(z), 'warn']);
  if (lw) R.push(['Body length', lw, 'warn']);
  const A = S.wf === 'kneeAP' && fem ? kneeAxes() : null;
  const far = S.wf !== 'kneeAP' ? null : fem ? (A && A.hip ? A.hip.c : null) : (lmGeom('ankle') || {}).p || null;   // the hip centre (femur) or the ankle centre (tibia)
  const bone = fem ? 'Femur above the osteotomy' : 'Tibia below the osteotomy', to = fem ? 'the hip centre' : 'the ankle centre';
  if (far) {   // remaining bone: osteotomy to the hip or ankle centre along the body's axis
    const X = xf(t), o = X(tumAt(t, tp.resection)), rem = G.dot(G.sub(far, o), dirOf(t, tumAxis(t).dir)) * k;
    R.push([bone, `${f1(rem)} mm to ${to} (projected)`]);
    if (st && c !== null) { const tip = rem - d - (st.s.drawnLengthMm - c); R.push([fem ? 'Stem in the remaining femur' : 'Stem in the remaining tibia', `${st.s.label || st.s.size}: ${f1(st.s.drawnLengthMm - c)} mm ${fem ? 'above' : 'below'} its collar; tip ${f1(tip)} mm ${fem ? 'below' : 'above'} ${to}`, tip < DIA_TIP_MM ? 'warn' : '']); }
  } else R.push([bone, S.wf === 'kneeAP' ? (fem ? 'not measured: mark the hip centre (a long-leg film, or the knee case\'s long film)' : 'not measured: mark the ankle centre (open a film that shows the ankle as the knee AP)') : 'measured on the knee AP', 'warn']);
  if (!st) R.push(['Stem', 'not chosen yet: choose the universal stem; check its diameter against the canal with the ruler', 'warn']);
  R.push(['Scale', 'lengths use this film\'s ball, placed at the knee; the resection level lies further from it, where magnification may differ (not validated)', 'warn']);
}
// The placed components' cuts on this knee AP (one calculation for the results and the knee plan card): {fe, ti}, each null
// when that component is not placed or is a tumour body; fe {s, mech (the distal cut to the femoral mechanical axis, lateral
// angle), valgus (the cut to the shaft axis: what is set on the guide), res {m, l} mm}; ti {s, mech (medial angle), res}
function kneeApCuts(A, k) {
  const fe = S.tpl.femur, ti = S.tpl.tibia, out = { fe: null, ti: null };
  if (fe && fe.on && !isTumourBody(S.fam[fe.fam])) {
    const s = sizeOf(fe), g = geomOf(fe), cut = xf(fe)([0, g.distalCutY]), down = dirOf(fe, [0, 1]);
    let lat = dirOf(fe, [1, 0]); if (G.dot(lat, A.latDir) < 0) lat = G.mul(lat, -1);
    out.fe = { s, mech: A.fma ? G.angleDeg(A.fma, lat) : null, valgus: A.anat ? Math.abs(90 - G.angleDeg(A.anat, lat)) : null,
      res: A.cm && A.cl && k ? { m: G.dot(G.sub(A.cm.p, cut), down) * k, l: G.dot(G.sub(A.cl.p, cut), down) * k } : null };
  }
  if (ti && ti.on && !isTumourBody(S.fam[ti.fam])) {
    const s = sizeOf(ti), g = geomOf(ti), cut = xf(ti)([0, g.cutY]), up = dirOf(ti, [0, -1]);
    let med = dirOf(ti, [1, 0]); if (G.dot(med, A.latDir) > 0) med = G.mul(med, -1);
    out.ti = { s, mech: A.tma ? G.angleDeg(G.mul(A.tma, -1), med) : null,
      res: A.pm && A.pl && k ? { m: G.dot(G.sub(A.pm.p, cut), up) * k, l: G.dot(G.sub(A.pl.p, cut), up) * k } : null };
  }
  return out;
}
function kneeAPResults(R, k) {
  if (revCase()) jlRows(R, k);   // the revision joint line's target and its evidence (amendment 4)
  const A = kneeAxes(); R.push(['Alignment']);
  if (A.swapCond || A.swapPlat) R.push(['Check landmarks', `${[A.swapCond && 'condyles', A.swapPlat && 'plateaus'].filter(Boolean).join(' and ')} clicked medial for lateral: the page used the film's sides; check Side if that is wrong`, 'bad']);
  if (A.hip && A.knee && A.ankle) {
    const hka = G.angleDeg(G.sub(A.hip.c, A.knee.p), G.sub(A.ankle.p, A.knee.p));
    const foot = G.project(A.knee.p, A.hip.c, G.sub(A.ankle.p, A.hip.c)), s = G.dot(G.sub(A.knee.p, foot), A.latDir);
    R.push(['Hip-knee-ankle angle', `${f1(hka)}° (${f1(180 - hka)}° ${s > 0 ? 'varus' : 'valgus'})`]);
    if (k) R.push(['Mechanical axis passes', `${f1(G.dist(A.knee.p, foot) * k)} mm ${s > 0 ? 'medial' : 'lateral'} to the knee centre`]);
  } else R.push(['Hip-knee-ankle angle', S.kc ? (K_NOLONG() ? 'not measured (no long film)' : 'measured on the long film (see Knee case below)') : 'needs hip, knee and ankle centres (long-leg film)', S.kc ? '' : 'warn']);
  const noLong = K_NOLONG();   // a knee case without its optional long film: the long-leg angles are not measured, never zero (Sami, 15 Oct)
  if (noLong) for (const n of ['mLDFA', 'MPTA', 'Joint line convergence']) R.push([n, 'not measured (no long film)']);
  if (!noLong && A.fma && A.cm && A.cl) R.push(['mLDFA', `${f1(G.angleDeg(A.fma, G.sub(A.cl.p, A.cm.p)))}°`]);
  if (A.anat && A.cm && A.cl) R.push(['aLDFA', `${f1(G.angleDeg(A.anat, G.sub(A.cl.p, A.cm.p)))}°`]);
  // MPTA: medial angle inside the tibia, between the plateau line and the axis running down to the ankle
  if (!noLong && A.tma && A.pm && A.pl) R.push(['MPTA', `${f1(G.angleDeg(G.mul(A.tma, -1), G.sub(A.pm.p, A.pl.p)))}°`]);
  if (!noLong && A.cm && A.cl && A.pm && A.pl) { const j = G.angleDeg(G.sub(A.cl.p, A.cm.p), G.sub(A.pl.p, A.pm.p)); R.push(['Joint line convergence', `${f1(Math.min(j, 180 - j))}°`]); }
  if (A.anat && A.hip && A.knee) R.push(['Femoral valgus angle (shaft to mechanical)', `${f1(G.angleDeg(A.anat, A.fma))}°`]);
  if (A.fmaSrc) R.push([S.kc ? `Femoral mechanical axis (${A.fmaHow})` : 'Femoral mechanical axis', A.fmaSrc, A.hip || A.fmaHow === 'transferred' || A.fmaHow === 'entered' ? '' : 'warn']);
  if (A.tmaSrc) R.push([S.kc ? `Tibial mechanical axis (${A.tmaHow})` : 'Tibial mechanical axis', A.tmaSrc, A.ankle || A.tmaHow === 'transferred' ? '' : 'warn']);
  kneeValueRows(R, A);
  if (!k) return;   // components and lengths need the scale
  if (S.kc) {   // coverage (contract section 12): the widths marked on this AP against the components'
    const cw = lmGeom('condW'), pw = lmGeom('platW');
    if (cw) R.push(['Femoral condylar width (measured)', `${f1(G.dist(cw.a, cw.b) * k)} mm`]);
    if (pw) R.push(['Tibial plateau width (measured)', `${f1(G.dist(pw.a, pw.b) * k)} mm`]);
  }
  const fe = S.tpl.femur, ti = S.tpl.tibia, C = kneeApCuts(A, k); let femThick = null, femRes = null;
  if (C.fe) {   // r27: a femoral tumour body has its own rows (tumRows), no distal cut
    const s = C.fe.s;
    R.push(['Femoral component']); R.push([S.fam[fe.fam].label, `size ${s.size}, ${s.sides[S.side].mlWidth} mm wide`]);
    if (C.fe.mech !== null) { const a = C.fe.mech; R.push(['Distal cut to mechanical axis (lateral angle)', `${f1(a)}°`, Math.abs(a - 90) > 2 ? 'warn' : '']); }
    if (S.kc) { const cw = lmGeom('condW'); if (cw) { const o = overhangEach(s.sides[S.side].mlWidth, G.dist(cw.a, cw.b) * k); R.push(['Femoral M/L coverage (centred)', Math.abs(o) < 0.5 ? 'matches the condyles' : o > 0 ? `${f1(o)} mm overhang each side` : `${f1(-o)} mm short of the edge each side`, o > 2 ? 'warn' : '']); } else R.push(['Femoral M/L coverage', 'not checked: mark the femoral condylar width', 'warn']); kcDepRow(R, fe, 'femoral component'); }
    if (C.fe.valgus !== null) R.push(['Distal cut to shaft axis (valgus for the guide)', `${f1(C.fe.valgus)}°`]);
    femThick = s.distalThickness;
    if (C.fe.res) {
      femRes = C.fe.res;
      R.push(['Distal resection medial / lateral', `${f1(femRes.m)} / ${f1(femRes.l)} mm`]);
      R.push(['Implant distal thickness', `${f1(femThick)} mm`]);
    }
  }
  if (C.ti) {   // r23 (Fable P2 1): a tumour body has its own rows (tumRows), no tray thickness or resection
    const s = C.ti.s;
    R.push(['Tibial tray']); R.push([S.fam[ti.fam].label, S.kc ? `size ${s.size}, ${s.mlWidth} mm wide; drawn at ${thickTxt(ti)} for the picture only (the insert is chosen at surgery)` : `size ${s.size}, ${s.mlWidth} mm wide, ${thickTxt(ti)}`]);
    if (S.kc) { const pw = lmGeom('platW'); if (pw) { const o = overhangEach(s.mlWidth, G.dist(pw.a, pw.b) * k); R.push(['Tibial M/L coverage (centred)', Math.abs(o) < 0.5 ? 'matches the plateau' : o > 0 ? `${f1(o)} mm overhang each side` : `${f1(-o)} mm short of the cortex each side`, o > 1 ? 'warn' : '']); } else R.push(['Tibial M/L coverage', 'not checked: mark the tibial plateau width', 'warn']); kcDepRow(R, ti, 'tibial tray'); }
    if (C.ti.mech !== null) { const a = C.ti.mech; R.push(['Tibial cut to mechanical axis (medial angle)', `${f1(a)}°`, Math.abs(a - 90) > 2 ? 'warn' : '']); }
    if (C.ti.res) {
      const tm = C.ti.res.m, tl = C.ti.res.l;
      R.push(['Tibial resection medial / lateral', `${f1(tm)} / ${f1(tl)} mm`]);
      if (femRes) {
        R.push(['Extension gap: bone off vs implant']);
        if (S.kc) {   // no automatic insert (Astra, P1 3): the bone resection only
          R.push(['Medial bone resection (femur + tibia)', `${f1(femRes.m + tm)} mm (radiographic; not a balanced gap)`]);
          R.push(['Lateral bone resection (femur + tibia)', `${f1(femRes.l + tl)} mm (radiographic; not a balanced gap)`]);
          R.push(['Insert', 'chosen at surgery from the trials: not selected from the film']);
        } else {
          R.push(['Medial', `${f1(femRes.m + tm)} vs ${f1(femThick + tibJl(ti))} mm (not a balanced gap: no soft tissue on a film)`]);
          R.push(['Lateral', `${f1(femRes.l + tl)} vs ${f1(femThick + tibJl(ti))} mm`]);
        }
      }
    }
  }
}
function kneeLatResults(R, k) {
  { const w = kneeRevWhy(); if (w) R.push(['Revision', w, 'warn']); }
  const ac = lmGeom('antCortex'), pc = lmGeom('postCond'), pa = lmProblem('platAnt') ? null : lmGeom('platAnt'), pp = lmGeom('platPost'), ta = lmGeom('tibAxis');
  R.push(['Native knee']);
  // sizes from the placed component's family, else the first of that kind open to this account
  const famOf = (slot, kind) => (S.tpl[slot] && S.fam[S.tpl[slot].fam]) || defaultFam(kind);   // r36: the case's maker only
  if (ac && pc && k) {
    const depth = G.lineDist(pc.p, ac.a, ac.b) * k, ff = famOf('femur', 'tka-femur'); R.push(['Femur: anterior cortex to back of condyles', `${f1(depth)} mm`]);
    if (ff && !isTumourBody(ff)) { const best = femBySizing(ff, depth); R.push(['Nearest femoral size', `${best.size} (${best.sides[S.side].apSizing} mm from anterior cut to back of condyles)`]); }
  }
  let up = null;
  if (ta) { up = G.unit(G.sub(ta.a, ta.b)); const pmid = pa && pp ? G.mid(pa.p, pp.p) : null; if (pmid ? G.dot(G.sub(pmid, G.mid(ta.a, ta.b)), up) < 0 : G.dot(up, cranVec()) < 0) up = G.mul(up, -1); }
  if (up && pa && pp) R.push([S.kc ? 'Native posterior tibial slope (measured)' : 'Posterior tibial slope', `${f1(latKnee().slope)}°`]);
  kneeValueRows(R, null, up && pa && pp ? latKnee().slope : null);
  if (S.kc) { const pt = lmGeom('patThick'); if (pt && k) R.push(['Patella thickness (measured)', `${f1(G.dist(pt.a, pt.b) * k)} mm`]); }
  if (!k) return;   // sizes and components need the scale
  if (pa && pp) {
    const L = G.dist(pa.p, pp.p) * k, tib = famOf('tibia', 'tka-tibia');
    const best = tib && tib.sizes.reduce((b, s) => Math.abs(s.apDepth - L) < Math.abs(b.apDepth - L) ? s : b);
    R.push(['Plateau depth', best ? `${f1(L)} mm, nearest tray ${best.size} (${best.apDepth} mm)` : `${f1(L)} mm`]);
  }
  const fe = S.tpl.femur, ti = S.tpl.tibia;
  if (fe && fe.on && isTumourBody(S.fam[fe.fam])) { R.push(['Femoral component']); R.push(['Size', `${sizeOf(fe).label || sizeOf(fe).size}: distal femoral replacement, placed by hand on the lateral (no bone cuts to place it by)`, 'warn']); }
  else if (fe && fe.on) {
    const s = sizeOf(fe); R.push(['Femoral component']); R.push(['Size', `${s.size}: ${s.sides[S.side].apSizing} mm anterior cut to back of condyles`]);
    const L = latKnee(), toAnt = antDir(L);
    if (ac && toAnt) {   // where the anterior cut meets the top of the flange, relative to the anterior cortex line
      const g = geomOf(fe), a0 = xf(fe)(g.cutProfile[0]), foot = G.project(a0, ac.a, G.sub(ac.b, ac.a));
      const d = G.dot(G.sub(a0, foot), toAnt) * k;   // + : cut in front of the cortex (overhang), - : behind it (notch)
      R.push(['Anterior cut vs anterior cortex', Math.abs(d) < 0.05 ? 'flush' : d < 0 ? `${f1(-d)} mm behind: notching` : `${f1(d)} mm proud`, d < -0.5 ? 'bad' : d > 2 ? 'warn' : '']);
      if (pc) {   // the back of the implant's condyles against the back of the native condyles (posterior offset)
        const back = g.rings[0].map(xf(fe)).reduce((b, q) => (G.dot(q, toAnt) < G.dot(b, toAnt) ? q : b));
        const e = G.dot(G.sub(pc.p, back), toAnt) * k;   // + : implant stops short of the native back (less posterior condyle)
        R.push(['Back of implant vs back of condyles', Math.abs(e) < 0.5 ? 'matched' : e > 0 ? `${f1(e)} mm short (posterior offset less)` : `${f1(-e)} mm beyond (posterior offset more)`, Math.abs(e) > 3 ? 'warn' : '']);
      }
    }
  }
  if (ti && ti.on && up) {
    R.push(['Tibial tray']); R.push([S.kc ? 'Cut slope as placed (simulation)' : 'Cut slope as placed', `${f1(slopeOf(ti, latKnee()))}°`]);
    if (S.kc && pa && pp) { const z = sizeOf(ti), o = z.apDepth - G.dist(pa.p, pp.p) * k; R.push(['Tray A/P coverage', Math.abs(o) < 0.5 ? 'matches the plateau depth' : o > 0 ? `${f1(o)} mm longer than the plateau (overhang)` : `${f1(-o)} mm shorter than the plateau`, o > 2 ? 'warn' : '']); }
    if (S.kc || kneeTibFam() === XN_KNEE.tibia) R.push(['Built-in slope', `not stated in the XN manual for the tray or the insert: the tray is drawn level on the cut (its "5° of caster angle", p. 4, is not a surface slope: nothing added to or taken from the cut); the cut slope is set on the extramedullary guide (p. 10-11)`, 'warn']);
    if (S.kc) kcDepRow(R, ti, 'tibial tray');
  }
  if (S.kc && fe && fe.on) kcDepRow(R, fe, 'femoral component');
}

// ---------- the knee plan card (Sami, 23 Oct 2026; DESIGN-2026-10-23-knee-plan-card.md with Astra's and Fable's amendments) ----------
// Sami: "at the end we want to know valgus cut angle, femoral external rotation angle, tibial slope angle, measured resections
// from each condyle on femur and tibial plateau medial and lateral side, femoral and tibial size and maybe recommendation on
// whether to resurface patella or patelloplasty". One card with those lines on every knee film, each with its origin tag, its
// film and that film's calibration, and what is stale or missing on the line itself; the full results stay under Details.
// The posterior condylar resection is not planned (Sami, 23 Oct: "leave it out for now"). The card's numbers come from the
// same calculations as the results (kneeApCuts, latKnee, slopeOf, kcGeo); another film's placed-component values are those
// worked out when it was last on screen in this page (KCARD), said so, and out of date when the plan values changed since.
const KCARD = {};   // role → {sha, vals, data, nW, nB}
const kneeCardOn = () => !!S.img && (S.kc ? true : S.wf === 'kneeAP' || S.wf === 'kneeLat');
// what another film's remembered numbers depend on beyond its own plan (Fable, r29 code review P2): the plan values, the
// case's one size per component (a size set on one film moves the other's component when that film is next opened), and the
// long leg (its image and the transfer angles the AP's axes take)
const cardValsKey = () => {
  const K = S.kc, A = K && K.films.long && !K.films.long.missing ? kcLongAngles() : null, r = x => (Number.isFinite(x) ? Math.round(x * 10) : null);
  return JSON.stringify([PV_KEYS.map(k => pvGet(k).v), pvTibRef(), K ? ['femur', 'tibia'].map(sl => { const z = K.shared.size[sl]; return z ? `${z.fam}:${z.size}` : null; }) : null,
    K && K.films.long ? K.films.long.image.sha256.slice(0, 16) : null, A ? [r(A.amaF), r(A.amaT)] : null]);
};
function cardApNow() {
  const A = kneeAxes(), k = mm(), C = kneeApCuts(A, k), fe = S.tpl.femur, ti = S.tpl.tibia, cw = lmGeom('condW'), pw = lmGeom('platW');
  const dep = t => !!(S.kc && t && t.on && t.dep && t.dep !== kcDepKey());
  const tp = C.ti && C.ti.res ? ((ti.tref && ti.tref.plat && ti.tref.mode === pvTibRef() ? ti.tref : tibRefPlat()) || {}).plat || null : null;
  return { vgPlaced: C.fe ? C.fe.valgus : null, vgAxis: A.anat && A.fma ? G.angleDeg(A.anat, A.fma) : null, vgHow: A.fmaHow || null,
    femRes: C.fe ? C.fe.res : null, tibRes: C.ti ? C.ti.res : null, tibPlat: tp, femSize: C.fe ? C.fe.s.label || C.fe.s.size : null, tibSize: C.ti ? C.ti.s.label || C.ti.s.size : null,
    femCov: C.fe && cw && k ? overhangEach(C.fe.s.sides[S.side].mlWidth, G.dist(cw.a, cw.b) * k) : null, tibCov: C.ti && pw && k ? overhangEach(C.ti.s.mlWidth, G.dist(pw.a, pw.b) * k) : null,
    femStale: dep(fe), tibStale: dep(ti), tumour: [fe, ti].some(t => t && t.on && isTumourBody(S.fam[t.fam])) };
}
function cardLatNow() {
  const L = latKnee(), k = mm(), fe = S.tpl.femur, ti = S.tpl.tibia, dep = t => !!(S.kc && t && t.on && t.dep && t.dep !== kcDepKey());
  const pd = L.pa && L.pp && k ? G.dist(L.pa.p, L.pp.p) * k : null, tz = ti && ti.on ? sizeOf(ti) : null, fz = fe && fe.on ? sizeOf(fe) : null;
  return { slopePlaced: ti && ti.on && L.up ? slopeOf(ti, L) : null, femSize: fz ? fz.label || fz.size : null, tibSize: tz ? tz.label || tz.size : null,
    tibCovAP: tz && pd !== null && Number.isFinite(tz.apDepth) ? tz.apDepth - pd : null, femStale: dep(fe), tibStale: dep(ti) };
}
// the lateral's landmark measurements (no component needed): the film on screen, or a knee case's lateral by its own scale
function cardLatGeo() {
  if (!S.kc) {
    if (S.wf !== 'kneeLat') return null;
    const L = latKnee(), k = mm(), pt = null;
    return { slope: L.slope, femDepth: L.ac && L.pc && k ? G.lineDist(L.pc.p, L.ac.a, L.ac.b) * k : null, platDepth: L.pa && L.pp && k ? G.dist(L.pa.p, L.pp.p) * k : null, pat: pt };
  }
  const f = S.kc.films.lat; if (!f || f.missing) return null;
  const L = kcGeo('lat'); if (!L) return null;
  const pa = L.g('platAnt'), pp = L.g('platPost'), ta = L.g('tibAxis'), ac = L.g('antCortex'), pc = L.g('postCond'), pt = L.g('patThick');
  let slope = null;
  if (pa && pp && ta) { let up = G.unit(G.sub(ta.a, ta.b)); if (G.dot(G.sub(G.mid(pa.p, pp.p), G.mid(ta.a, ta.b)), up) < 0) up = G.mul(up, -1); slope = G.angleDeg(up, G.unit(G.sub(pp.p, pa.p))) - 90; }
  return { slope, femDepth: ac && pc && L.k ? G.lineDist(pc.p, ac.a, ac.b) * L.k : null, platDepth: pa && pp && L.k ? G.dist(pa.p, pp.p) * L.k : null, pat: pt && L.k ? G.dist(pt.a, pt.b) * L.k : null };
}
// this film's card values remembered (a knee case), and another film's as last worked out: {data, old, from} or null
function cardFilm(role, R) {
  const K = S.kc, wf = role === 'ap' ? 'kneeAP' : 'kneeLat';
  if (!K) return S.wf === wf ? { data: role === 'ap' ? cardApNow() : cardLatNow(), old: '', from: '' } : null;
  const f = K.films[role]; if (!f) return null;
  if (f.missing) return { data: null, old: '', from: `the ${KC_NAME[role]} film's X-ray is not on this page: open it again` };
  if (K.active === role && S.wf === wf) {
    const data = role === 'ap' ? cardApNow() : cardLatNow();
    KCARD[role] = { sha: f.image.sha256, vals: cardValsKey(), data, nW: R.filter(r => r.length > 1 && r[2] === 'warn').length, nB: R.filter(r => r.length > 1 && r[2] === 'bad').length };
    return { data, old: '', from: '' };
  }
  const c = KCARD[role];
  if (!c || c.sha !== f.image.sha256) return { data: null, old: '', from: `open the ${KC_NAME[role]} film to work it out` };
  return { data: c.data, old: c.vals !== cardValsKey() ? `your plan values, the case's sizes or the long leg changed since the ${KC_NAME[role]} was last open: open it and press Auto template again` : '', from: ` (as last worked out on the ${KC_NAME[role]})` };
}
const cardCal = role => (S.kc ? kcCalText(role) : calStatus()[0]);
const covText = (o, lim, edge) => (o === null ? '' : Math.abs(o) < 0.5 ? 'M/L matches' : o > 0 ? `${f1(o)} mm overhang each side` : `${f1(-o)} mm short of the ${edge} each side`);
// a plan value's tag: [words, class] (amber: an assumed value nobody has checked)
function pvTag(pv) {
  if (pv.o === 'surgeon') return ['your target', 't'];
  if (pv.o === 'unknown') return [`entered before 16 Oct${pvOut(pv) ? ', outside the usual entry range' : ''}${pv.rv ? ', checked' : ''}`, pv.rv ? 'd' : 'w'];
  return [pv.rv ? 'default, checked' : 'default', pv.rv ? 'd' : 'w'];
}
const pvUsed = key => (S.kc ? true : key === 'femDistal' || key === 'vca' ? S.wf === 'kneeAP' : key === 'slope' ? S.wf === 'kneeLat' : true);
// → {rows: [{id, label, val, tag: [words, class], note, cls, pv}], checks: [[film, n, nBad, role]], head}
function kneeCardRows(R) {
  const K = S.kc, AP = cardFilm('ap', R), LT = cardFilm('lat', R), D = AP && AP.data, DL = LT && LT.data, LG = cardLatGeo(), rows = [];
  const miss = (F, role, what) => (F && F.from && !F.data ? F.from : K ? `needs the ${KC_NAME[role]} film${K.films[role] ? '' : ' (add it to the case)'}` : `needs a knee ${role === 'ap' ? 'AP' : 'lateral'} film (start a knee case to add it)`) + (what ? `: ${what}` : '');
  const old = F => (F && F.old ? F.old : '');
  const push = (id, label, val, tag, note, cls = '', pv = null) => rows.push({ id, label, val, tag, note, cls, pv });
  const xn = pvXn();
  // 1. the valgus cut angle: the placed femur's cut to the shaft (what is set on the guide), else the axis the placement will use
  { const pv = pvGet('vca'), how = D && D.vgHow, ax = { measured: 'the femoral mechanical axis from the hip centre on the AP', transferred: 'the femoral mechanical axis taken from the long leg', entered: 'the shaft axis turned by your valgus value', assumed: 'the shaft axis turned by the default valgus' }[how] || '';
    if (D && D.vgPlaced !== null) push('vca', 'Valgus cut angle', `${f1(D.vgPlaced)}° to the femoral shaft`, ['placed', 'm'], `the placed femur's distal cut on the AP${AP.from} (${cardCal('ap')}); square to ${ax}${D.femStale ? '; placed before a change: press Auto template again' : ''}${old(AP) ? '; ' + old(AP) : ''}`, D.femStale || old(AP) ? 'warn' : '', 'vca');
    else if (D && D.vgAxis !== null && (how === 'measured' || how === 'transferred')) push('vca', 'Valgus cut angle', `${f1(D.vgAxis)}° to the femoral shaft`, [how === 'measured' ? 'measured' : 'long leg', 'm'], `${ax} (not placed yet)${AP.from}`, '', 'vca');
    else { const [t, c] = pvTag(pv); push('vca', 'Valgus cut angle', `${f1(pv.v)}° to the femoral shaft`, [t, c], `the plan value, used on the AP when no hip or long leg sets the axis${D ? '' : ` (${miss(AP, 'ap', '')})`}`, c === 'w' ? 'warn' : '', 'vca'); } }
  // 2. the femoral external rotation: a surgical target (not shown on these films)
  { const pv = pvGet('rot'), [t, c] = pvTag(pv); push('rot', 'Femoral external rotation', `${f1(pv.v)}° to the posterior condyles`, [t, c], `a target, set at surgery${xn ? ` on the rotation guide (XN guide 0°, 3° or 5°, manual p. ${XN_KNEE.rotPage})` : ''}; not shown on these films`, c === 'w' ? 'warn' : '', 'rot'); }
  // 3. the tibial slope: the placed tray's cut on the lateral, else the plan value; the native slope beside it
  { const pv = pvGet('slope'), [t, c] = pvTag(pv), nat = LG && LG.slope !== null ? `native ${f1(LG.slope)}° on the lateral` : 'native slope not measured';
    const guide = xn ? ' on the extramedullary guide' : '';
    if (DL && DL.slopePlaced !== null) { const off = Math.abs(DL.slopePlaced - pv.v) >= 0.5; push('slope', 'Tibial slope', `${f1(DL.slopePlaced)}° posterior`, ['placed', 'm'], `the placed tray's cut on the lateral${LT.from} (${cardCal('lat')}); ${nat}; your plan value ${f1(pv.v)}°${guide} (${t})${off ? ': the placed cut differs, press Auto template again or check it' : ''}${DL.tibStale ? '; placed before a change' : ''}${old(LT) ? '; ' + old(LT) : ''}`, off || DL.tibStale || old(LT) ? 'warn' : '', 'slope'); }
    else push('slope', 'Tibial slope', `${f1(pv.v)}° posterior`, [t, c], `${guide ? guide.trim() + '; ' : ''}${nat}${DL ? '' : `; the tray on the lateral: ${miss(LT, 'lat', '')}`}`, c === 'w' ? 'warn' : '', 'slope'); }
  // 4-6. the resections: from the landmarks to the placed components' cuts (radiographic, not a balanced gap)
  { const pv = pvGet('femDistal'), plan = `plan ${f1(pv.v)} mm from the more distal condyle (${pvTag(pv)[0]})`;
    if (D && D.femRes) push('femDistal', 'Distal femur resection, medial / lateral', `${f1(D.femRes.m)} / ${f1(D.femRes.l)} mm`, ['radiographic', 'm'], `from the condyles to the placed femur's cut on the AP${AP.from} (${cardCal('ap')}); ${plan}${D.femStale ? '; placed before a change: press Auto template again' : ''}${old(AP) ? '; ' + old(AP) : ''}`, D.femStale || old(AP) ? 'warn' : '', 'femDistal');
    else push('femDistal', 'Distal femur resection, medial / lateral', 'not worked out', ['missing', 'x'], `${D ? (D.tumour ? 'a distal femoral replacement: see Details' : 'place the femur on the calibrated AP') : miss(AP, 'ap', 'place the femur on it')}; ${plan}`, '', 'femDistal'); }
  push('post', 'Posterior femur resection, medial / lateral', 'determine intraoperatively', ['not planned', 'x'], `not worked out from the films: ${xn ? 'the XN rotation guide\'s pivot' : 'the sizing and rotation instruments\' geometry'} and the component outlines are not confirmed (Sami, 23 Oct)`);
  { const pv = pvGet('tibial'), ref = pvTibRef(), plan = `plan ${f1(pv.v)} mm below the ${TIB_SHORT[ref]} (${pvTag(pv)[0]})`;
    if (D && D.tibRes) push('tibial', 'Tibial resection, medial / lateral', `${f1(D.tibRes.m)} / ${f1(D.tibRes.l)} mm`, ['radiographic', 'm'], `from the plateaus to the placed tray's cut on the AP${AP.from} (${cardCal('ap')})${D.tibPlat ? `, referenced on the ${D.tibPlat} plateau` : ''}; ${plan}${D.tibStale ? '; placed before a change: press Auto template again' : ''}${old(AP) ? '; ' + old(AP) : ''}`, D.tibStale || old(AP) ? 'warn' : '', 'tibial');
    else push('tibial', 'Tibial resection, medial / lateral', 'not worked out', ['missing', 'x'], `${D ? (D.tumour ? 'a tumour component: see Details' : 'place the tray on the calibrated AP') : miss(AP, 'ap', 'place the tray on it')}; ${plan}`, '', 'tibial'); }
  // 7-8. the sizes: the case's one size per component (else this film's), with each film's coverage
  for (const slot of ['femur', 'tibia']) {
    const fem = slot === 'femur', z = K ? K.shared.size[slot] : null, here = S.tpl[slot] && S.tpl[slot].on ? sizeOf(S.tpl[slot]) : null;
    const size = z ? (S.fam[z.fam] && (S.fam[z.fam].sizes.find(s => s.size === z.size) || {}).label) || z.size : here ? here.label || here.size : null;
    const bits = [], warn = [];
    if (LG && (fem ? LG.femDepth : LG.platDepth) !== null) bits.push(`${fem ? 'femoral A/P' : 'plateau depth'} ${f1(fem ? LG.femDepth : LG.platDepth)} mm on the lateral`);
    const cov = D ? (fem ? D.femCov : D.tibCov) : null;
    if (cov !== null) { bits.push(`AP: ${covText(cov, 0, fem ? 'edge' : 'cortex')}`); if (cov > (fem ? 2 : 1)) warn.push(1); }
    if (!fem && DL && DL.tibCovAP !== null) { const o = DL.tibCovAP; bits.push(`lateral: ${Math.abs(o) < 0.5 ? 'A/P matches' : o > 0 ? `${f1(o)} mm longer than the plateau` : `${f1(-o)} mm shorter than the plateau`}`); if (o > 2) warn.push(1); }
    if (z && z.byHand) bits.push('size set by hand');
    if ((D && (fem ? D.femStale : D.tibStale)) || (DL && (fem ? DL.femStale : DL.tibStale))) { bits.push('placed before a change: press Auto template again'); warn.push(1); }
    push(slot, fem ? 'Femoral size' : 'Tibial size', size || 'not placed', size ? ['placed', 'm'] : ['missing', 'x'], bits.join('; ') || (fem ? 'sized from the lateral A/P, M/L checked on the AP' : 'sized from the AP M/L and the lateral A/P'), warn.length ? 'warn' : '');
  }
  // 9. the patella: a suggestion from the surgeon's own skyline assessment (never written into the decision)
  if (K) {
    const sg = patellaSuggest(K.shared.pf, LG ? LG.pat : null, xn), dec = { undecided: 'not decided', resurface: 'resurface', retain: 'do not resurface' }[K.shared.patella] || 'not decided';
    push('patella', 'Patella', sg.s === 'resurface' ? 'Resurface' : sg.s === 'patelloplasty' ? 'Patelloplasty' : 'No suggestion', ['suggestion, your call', 's'], `${cap(sg.why.join('; '))}. From your skyline assessment; bone thickness on the X-ray (the caliper at surgery reads 2-4 mm more); native thickness alone does not decide it. Your decision: ${dec} (Knee case card)`, sg.s ? '' : 'warn');
  } else push('patella', 'Patella', 'No suggestion', ['missing', 'x'], 'needs a knee case: the skyline assessment and the patella thickness on the lateral');
  const checks = [[S.kc ? KC_NAME[S.kc.active] : 'this film', R.filter(r => r.length > 1 && r[2] === 'warn').length + R.filter(r => r.length > 1 && r[2] === 'bad').length, R.filter(r => r.length > 1 && r[2] === 'bad').length, null]];
  if (K) for (const r of ['ap', 'lat']) { const c = KCARD[r], f = K.films[r]; if (r !== K.active && c && f && c.sha === f.image.sha256 && c.nW + c.nB) checks.push([KC_NAME[r], c.nW + c.nB, c.nB, r]); }
  const head = `Knee plan · ${cap(SIDE[S.kc ? S.kc.side : S.side])} knee${revCase() ? ' · revision: the lines of a primary knee, check the joint line in Details' : ''}${D && D.tumour ? ' · tumour component: its rows are in Details' : ''}`;
  return { rows, checks, head };
}
// the card's body (written by drawNow when it changes): the lines, each plan value editable in place with a Checked tap
function kneeCardHTML(R) {
  const C = kneeCardRows(R), tagH = t => `<span class="kptag ${t[1]}">${esc(t[0])}</span>`;
  let h = C.head.includes(' · ', C.head.indexOf('knee')) ? `<div class="kphead">${esc(C.head.slice(C.head.indexOf(' · ', C.head.indexOf('knee')) + 3))}</div>` : '';
  for (const r of C.rows) {
    h += `<div class="kprow${r.cls ? ' ' + r.cls : ''}" data-row="${r.id}"><span class="kpl">${esc(r.label)}</span>${tagH(r.tag)}<span class="kpv">${esc(r.val)}</span><span class="kpn">${esc(r.note)}</span>`;
    if (r.pv && pvUsed(r.pv)) {
      const pv = pvGet(r.pv), [lo, hi, step] = PV_BOUND[r.pv], [t, c] = pvTag(pv);
      h += `<span class="kped"><label>${r.pv === 'vca' || r.pv === 'rot' || r.pv === 'slope' ? 'Target' : 'Plan'} <input type="number" data-pv="${r.pv}" min="${lo}" max="${hi}" step="${step}" value="${pv.v}" aria-label="${esc(pvName(r.pv))}">${esc(pvUnit(r.pv).trim())}</label>`;
      if (r.pv === 'tibial') h += `<select data-tref aria-label="Tibial resection reference (card)"><option value="less"${pvTibRef() === 'less' ? ' selected' : ''}>below the higher plateau</option><option value="medial"${pvTibRef() === 'medial' ? ' selected' : ''}>below the lowest medial point</option>${pvTibRef() === 'worn' ? '<option value="worn" selected>below the lower plateau (before 16 Oct)</option>' : ''}</select>`;
      h += pv.o !== 'surgeon' && !pv.rv ? `<button type="button" data-ck="${r.pv}" title="You have looked at ${esc(pvName(r.pv).toLowerCase())} ${f1(pv.v)}${esc(pvUnit(r.pv))} and keep it (its origin stays: ${esc(t)})">Checked</button>` : `<span class="kpok">${c === 't' ? '' : '✓ checked'}</span>`;
      h += '</span>';
    }
    h += '</div>';
  }
  const n = C.checks.reduce((a, c) => a + c[1], 0);
  h += `<div class="kpchk${C.checks.some(c => c[2]) ? ' bad' : n ? ' warn' : ''}">${n ? `⚠ ${C.checks.filter(c => c[1]).map(c => `${esc(c[0])}: ${c[1]} to check${c[3] ? ' (when last open)' : ''}`).join(' · ')}` : 'Nothing to check on this film'}`;
  if (C.checks[0][1]) h += ' <button type="button" data-show>Show</button>';
  for (const c of C.checks.slice(1)) h += ` <button type="button" data-film="${c[3]}">Open ${esc(c[0])}</button>`;
  h += ' · the full results: Details under Measurements</div>';
  return h;
}
// the card (built with the panel; its body kept current by drawNow): the place button while components are missing
function kneeCard(R, cur) {
  const d = el('<details class="sec kplan" id="sec-kplan"><summary><span class="segn">✚</span><span class="segt">Knee plan</span><span class="segr"></span></summary><div id="kplanb"></div></details>');
  const C = kneeCardRows(R), val = id => { const r = C.rows.find(x => x.id === id); return r && r.tag[1] !== 'x' ? r.val : '–'; };
  d.querySelector('.segr').textContent = `${cap(SIDE[S.kc ? S.kc.side : S.side])} knee · femur ${val('femur')}, tibia ${val('tibia')}`;
  d.open = S.segHand.kplan ?? (cur === 'tpl' || cur === 'save');
  d.querySelector('summary').addEventListener('click', () => { S.segHand.kplan = !d.open; });
  const b = d.querySelector('#kplanb');
  b.addEventListener('change', e => {
    const i = e.target.closest('input[data-pv]'), s = e.target.closest('select[data-tref]');
    if (s) { pvSetTibRef(s.value); save(); panel(); draw(); return prompt(`Tibial reference changed: the tibial resection is now the assumed ${f1(pvGet('tibial').v)} mm for it (enter your own if different); press Auto template to place again.`); }
    if (!i) return;
    const key = i.dataset.pv, [lo, hi] = PV_BOUND[key], t = i.value.trim(), n = parseFloat(t);
    if (t === '') { pvSet(key, null); save(); panel(); draw(); return; }
    if (!(n >= lo && n <= hi)) { prompt(`${pvName(key)}: enter a value from ${lo} to ${hi}${pvUnit(key)}, or empty it for the assumed value.`); i.value = String(pvGet(key).v); return; }
    if (Math.abs(n - pvGet(key).v) >= 0.005) pvSet(key, Math.round(n * 100) / 100); else pvReview(key);   // the same number typed again: a review, the origin kept (Fable, r29 P3)
    save(); panel(); draw();
    if (opSlots().some(sl => S.tpl[sl])) prompt(`${pvName(key)}: your target ${f1(pvGet(key).v)}${pvUnit(key)}. Press Auto template to place the components with it.`);
  });
  b.addEventListener('click', e => {
    const ck = e.target.closest('button[data-ck]'), fl = e.target.closest('button[data-film]');
    if (ck) { pvReview(ck.dataset.ck); save(); panel(); draw(); return; }
    if (fl) return kcShow(fl.dataset.film);
    if (e.target.closest('button[data-show]')) showChecks();
  });
  if ((S.wf === 'kneeAP' || S.wf === 'kneeLat') && opSlots().length && !opSlots().every(sl => S.tpl[sl])) {
    const r = el('<div class="row"></div>'), why = !mm() ? 'Calibrate first' : '';
    r.appendChild(btn('Place with these values', autoTemplate, 'primary', !!why, why || 'Place the components by the rules with the values on this card (an unchecked default stays marked "default")'));
    d.appendChild(r);
  }
  { const h = kneeCardHTML(R); b.innerHTML = h; b._html = h; }   // the string drawNow compares with (Fable, r29 P3)
  return d;
}

// ---------- drawing ----------
function draw() { if (!raf) raf = requestAnimationFrame(() => { raf = 0; drawNow(); }); }
function drawNow() {
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  const W = cv.width / dpr, H = cv.height / dpr;
  ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);
  if (!S.img) return;
  ctx.imageSmoothingEnabled = S.view.k < 2; ctx.imageSmoothingQuality = 'high';
  const ar = area(), poly = corners(ar).map(toS);
  ctx.save(); ctx.translate(S.view.x, S.view.y); ctx.scale(S.view.k, S.view.k);
  if (ROT()) { const c = imgC(); ctx.translate(c.x, c.y); ctx.rotate(ROT()); ctx.translate(-c.x, -c.y); }
  ctx.beginPath(); ctx.rect(ar.x0, ar.y0, ar.x1 - ar.x0, ar.y1 - ar.y0); ctx.clip();   // a crop shows only the cropped film
  ctx.drawImage(S.off, 0, 0); ctx.restore();
  ctx.save(); ctx.beginPath(); poly.forEach((q, i) => (i ? ctx.lineTo(q.x, q.y) : ctx.moveTo(q.x, q.y))); ctx.closePath(); ctx.clip();
  const cl = filmMode(); S.cleanNow = cl;
  try { overlay(ctx, toS, 1, true, cl); } catch (e) { ctx.restore(); console.error(e); $('alerts').innerHTML = `<div>The overlay could not be drawn (${esc(e.message || e)}). Undo the last change or reload.</div>`; return; }
  ctx.restore();
  const bd = S.drag && S.drag.kind === 'brush' ? S.drag.stroke : null;
  if (bd) {   // the stroke being drawn (the eraser pink; an existing implant orange; "Not an implant" green)
    ctx.save(); ctx.strokeStyle = !S.drag.ex ? 'rgba(255,79,216,0.45)' : S.drag.ex === 'exclear' ? 'rgba(80,220,120,0.45)' : 'rgba(255,160,40,0.45)'; ctx.lineCap = ctx.lineJoin = 'round'; ctx.lineWidth = 2 * bd.r * S.view.k;
    ctx.beginPath(); bd.pts.forEach((p, i) => { const q = toS(p); i ? ctx.lineTo(q.x, q.y) : ctx.moveTo(q.x, q.y); }); if (bd.pts.length === 1) { const q = toS(bd.pts[0]); ctx.lineTo(q.x + 0.1, q.y); } ctx.stroke(); ctx.restore();
  }
  if (brushArm() && S.hover) { const q = toS(S.hover); ctx.save(); ctx.strokeStyle = '#ff4fd8'; ctx.lineWidth = 1.5; ctx.beginPath(); ctx.arc(q.x, q.y, S.brush, 0, 7); ctx.stroke(); ctx.restore(); }
  if (S.loupe) drawLoupe(W, H);
  const k = mm();
  setText('hud', `W ${Math.round(S.wl.w)} L ${Math.round(S.wl.c)} · zoom ${(S.view.k * 100).toFixed(0)} %` + (k ? ` · 10 mm = ${(10 / k * S.view.k).toFixed(0)} screen px` : ''));
  let R = []; try { R = results(); } catch (e) { console.error(e); R = [['Results', `could not be worked out (${e.message || e})`, 'bad']]; }
  setHTML('res', resTable(R));
  if ($('kplanb')) { let h = ''; try { h = kneeCardHTML(R); } catch (e) { console.error(e); h = `<div class="kpchk bad">The knee plan card could not be worked out (${esc(e.message || e)}): see Details.</div>`; } setHTML('kplanb', h); }
  const al = R.filter(r => r.length > 1 && r[2] === 'bad');   // the serious ones also on the film, where the eyes are, and at the top of the panel
  setHTML('alerts', al.map(r => `<div>${esc(r[0])}: ${esc(r[1])}</div>`).join(''));
  setHTML('warns', al.map(r => `<div>${esc(r[0])}: ${esc(r[1])}</div>`).join(''));
  setHTML('warnsum', warnSummary(R));
  warnBadge(R);
}
// On a phone, once the landmarks are done, the film shows the plan only: the implants, the neck cut and the lines that
// belong to them. The reference lines (teardrop line, midline, canal lines, landmark points, the mirrored target, the
// ball) come back while a landmark is being marked, or with the "Lines" toggle on the film (round 11, 13 Oct 2026).
// The saved picture is not affected (its own setting, "picture").
const linesAuto = () => !!(PHONE.matches && S.img && S.lib && ['tpl', 'save'].includes(S.segStep));
// What the film shows (Astra's review of round 11, finding 1: one rule for the drawing and for what a tap can pick up).
// 'phone': the plan only; true: "Clean view" (implants, neck cut, teardrop line, landmark points); false: everything.
// On a phone, once the landmarks are done, the Lines toggle decides (the panel's Clean view button is the same switch);
// a landmark being marked always shows them.
function filmMode() {
  if (S.arm && S.arm.kind === 'lm') return false;
  if (linesAuto()) return S.linesOn ? false : 'phone';
  return S.cleanView ? true : false;
}
// is this landmark's handle drawn (and so draggable) in this mode? A landmark to place again (red) always is. The width lines'
// two ends show as the point landmarks do (Sami, 23 Oct: "show the width line points in the image like the others")
const lmShown = (d, bad, mode) => bad || !mode || (mode === true && (d.type === 'point' || WIDTH_LM.includes(d.key)));
// On a phone the serious alerts sit in the panel column, above its row (Astra's review of round 11, finding 3): the
// layout gives them their own room, so a tall panel, the implant bar or the prompt never covers them. On a desktop they
// stay on the film, top right.
function placeAlerts() {
  const a = $('alerts'), home = PHONE.matches ? $('pcol') : $('stage');
  if (a.parentElement !== home) { if (PHONE.matches) home.insertBefore(a, $('sheetbar')); else home.insertBefore(a, $('hud').nextSibling); }
}
function syncLines() {
  const b = $('linesBtn'), on = linesAuto(); b.hidden = !on;
  b.setAttribute('aria-pressed', String(!!S.linesOn));
  b.title = S.linesOn ? 'Hide the reference lines (the plan only)' : 'Show the reference lines (teardrop line, midline, canal lines, landmarks, mirrored target)';
}
// the panel row's badge: how many results to check (amber; red when one is serious, or with no scale); a tap opens them
function warnBadge(R) {
  const b = $('warnBadge'); if (!b) return;
  const nW = R.filter(r => r.length > 1 && r[2] === 'warn').length, nB = R.filter(r => r.length > 1 && r[2] === 'bad').length, n = nW + nB;   // results() has the missing scale (once)
  const key = `${nW}|${nB}`; if (b._key === key) return; b._key = key;
  b.hidden = !n; b.textContent = `⚠ ${n}`; b.className = nB ? 'bad' : '';
  b.setAttribute('aria-label', `Show the ${n} result${n > 1 ? 's' : ''} to check${nB ? `, ${nB} serious` : ''}`);
  b.title = b.getAttribute('aria-label');
  // (2) one warning status for screen readers, outside the panel (never hidden with it): said when what is to be
  // checked changes, not on every frame of a drag
  const bads = [...new Set(R.filter(r => r.length > 1 && r[2] === 'bad').map(r => r[0]))], st = $('srStatus');
  if (st) st.textContent = !n ? '' : `${nB ? `${nB} serious: ${bads.join(', ')}. ` : ''}${nW ? `${nW} to check. ` : ''}Open them with the warnings button.`;
}
// The amber results in a few words, with a way to all of them (Astra's page re-review, 13 Oct, finding 8): kept at the top
// of the panel, so a phone's panel at its smallest still says there is something to check (a lengthening not known, a
// combined offset over 5 mm, a head off the cup centre) and opens it in one tap; the red ones are in #warns above it.
function warnSummary(R) {
  const w = R.filter(r => r.length > 1 && r[2] === 'warn'); if (!w.length) return '';
  const short = t => { const x = String(t); return x.length > 70 ? x.slice(0, 68).replace(/\s+\S*$/, '') + '…' : x; };
  return `<span class="wsn"><b>${w.length} to check:</b> ${w.slice(0, 2).map(r => `${esc(r[0])}: ${esc(short(r[1]))}`).join('; ')}${w.length > 2 ? `; and ${w.length - 2} more` : ''}</span><button type="button" class="wsbtn" aria-label="Show all ${w.length} results to check">Show all</button>`;
}
// "Show all": the Measurements and save segment opened (with every result), the phone's panel made taller, and it in view
function showChecks() {
  S.segHand.save = true; S.segHand.resd = true;
  if (sheet && PHONE.matches && sheet.get() === 'peek') sheet.set('half');
  panel();
  const d = $('sec-save'); if (d) { d.open = true; const t = d.querySelector('table.res .warn, table.res tr'); (t || d).scrollIntoView({ block: 'nearest' }); }
}
// Text that is drawn every frame is written to the page only when it changes (no layout work while dragging).
function setHTML(id, html) { const e = $(id); if (e && e._html !== html) { e.innerHTML = html; e._html = html; } }
function setText(id, t) { const e = $(id); if (e && e._text !== t) { e.textContent = t; e._text = t; } }
// A magnifier while a finger places or drags a point (the finger hides the spot it is on): 3x the film around the point,
// in the corner away from the finger, with a cross where the point will go.
function drawLoupe(W, H) {
  const L = S.loupe, sp = toS(L), R0 = Math.min(80, W / 4), cx = sp.x < W / 2 ? W - R0 - 10 : R0 + 10, cy = R0 + 10, z = Math.max(S.view.k * 3, 0.6);
  const q = ROT() ? rotP(L, ROT(), imgC()) : L;
  ctx.save(); ctx.beginPath(); ctx.arc(cx, cy, R0, 0, 7); ctx.fillStyle = '#000'; ctx.fill(); ctx.clip();
  ctx.translate(cx, cy); ctx.scale(z, z); ctx.translate(-q.x, -q.y);
  if (ROT()) { const c = imgC(); ctx.translate(c.x, c.y); ctx.rotate(ROT()); ctx.translate(-c.x, -c.y); }
  ctx.imageSmoothingEnabled = z < 2; ctx.drawImage(S.off, 0, 0); ctx.restore();
  ctx.save(); ctx.strokeStyle = '#A8FF30'; ctx.lineWidth = 1.5; ctx.beginPath(); ctx.arc(cx, cy, R0, 0, 7); ctx.stroke();
  ctx.beginPath(); ctx.moveTo(cx - 12, cy); ctx.lineTo(cx - 3, cy); ctx.moveTo(cx + 3, cy); ctx.lineTo(cx + 12, cy); ctx.moveTo(cx, cy - 12); ctx.lineTo(cx, cy - 3); ctx.moveTo(cx, cy + 3); ctx.lineTo(cx, cy + 12); ctx.stroke(); ctx.restore();
}
// Draws all overlays with the mapping T (image px -> canvas units); lw: line width scale; ui: handles and prompts.
// Every line has a dark edge under it and every label a solid box, so both show on white bone and on black.
// r44: a long-leg plan draws its other zone first (its implants, landmarks and lines; not the scale, masks, rulers or angles), then
// the zone on screen; drawnSlots (a test hook) lists every implant drawn
let drawnSlots = null;
function overlay(g, T, lw, ui, clean = false) { return llBare(() => overlay0(g, T, lw, ui, clean)); }   // Fable r44 P2-2
function overlay0(g, T, lw, ui, clean = false) {
  if (S.ll) { const skip = new Set(lmDefs().map(d => d.key)), cur = S.ll.zone; for (const z of llZones()) if (z !== cur && z !== 'align') inZone(z, () => overlay1(g, T, lw, ui, clean, { skip, noAxes: cur === 'align' && z === 'knee' })); }
  overlay1(g, T, lw, ui, clean);
}
function overlay1(g, T, lw, ui, clean = false, zo = null) {   // clean: no construction lines (saved picture, or "Clean view"); zo: another zone of a long-leg plan
  const k = mm(), HALO = 'rgba(0,0,0,0.78)';
  g.lineJoin = 'round'; g.lineCap = 'round';
  const stroke = (build, col, w = 1.5, dash) => {
    g.setLineDash(dash ? dash.map(d => d * lw) : []);
    g.beginPath(); build(); g.strokeStyle = HALO; g.lineWidth = (w + 2.2) * lw; g.stroke();
    g.beginPath(); build(); g.strokeStyle = col; g.lineWidth = w * lw; g.stroke(); g.setLineDash([]);
  };
  const line = (a, b, col, w = 1.5, dash) => stroke(() => { const A = T(a), B = T(b); g.moveTo(A.x, A.y); g.lineTo(B.x, B.y); }, col, w, dash);
  const longLine = (a, dir, col, w, dash, L = 4000) => line(G.sub(a, G.mul(dir, L)), G.add(a, G.mul(dir, L)), col, w, dash);
  const dot = (p, col, r = 4) => { const s = T(p); g.fillStyle = col; g.strokeStyle = '#000'; g.lineWidth = 1.2 * lw; g.beginPath(); g.arc(s.x, s.y, r * lw, 0, 7); g.fill(); g.stroke(); };
  const ring = (c, r, col, w = 1.5, dash) => stroke(() => { const s = T(c), e = T(G.add(c, G.P(r, 0))); g.arc(s.x, s.y, Math.hypot(e.x - s.x, e.y - s.y), 0, 7); }, col, w, dash);
  const cross = (p, col, r = 6) => stroke(() => { const s = T(p); g.moveTo(s.x - r * lw, s.y); g.lineTo(s.x + r * lw, s.y); g.moveTo(s.x, s.y - r * lw); g.lineTo(s.x, s.y + r * lw); }, col, 1.5);
  const placed = [];   // label boxes already drawn: a new one steps down until it overlaps none
  const later = [], label = (p, text, col) => later.push([p, text, col]);   // drawn last, over every outline
  const drawLabel = (p, text, col) => {   // solid dark-green box, white text, a coloured bar saying what it belongs to
    const s = T(p), fs = 12.5 * lw; g.font = `600 ${fs}px system-ui, sans-serif`;
    const w = g.measureText(text).width, x = s.x + 8 * lw, pad = 3 * lw, bw = w + 2 * pad + 3 * lw, bh = fs + 2 * pad;
    let y = s.y - 8 * lw - fs;
    for (let i = 0; i < 8 && placed.some(r => x < r.x + r.w && r.x < x + bw && y < r.y + r.h && r.y < y + bh); i++) y += bh + 2 * lw;
    placed.push({ x, y, w: bw, h: bh });
    g.fillStyle = 'rgba(3,69,55,0.92)'; g.fillRect(x, y, w + 2 * pad + 3 * lw, fs + 2 * pad);
    g.fillStyle = col; g.fillRect(x, y, 3 * lw, fs + 2 * pad);
    g.fillStyle = '#fff'; g.textBaseline = 'top'; g.fillText(text, x + 3 * lw + pad, y + pad); g.textBaseline = 'alphabetic';
  };
  const showLabels = (S.labels && smallOk()) || !ui;
  if (!zo) for (const mk of S.masks || []) {   // black-outs: solid on the saved picture, shaded with an outline on screen
    const A = T(mk.a), B = T(mk.b), x = Math.min(A.x, B.x), y = Math.min(A.y, B.y), w = Math.abs(A.x - B.x), h = Math.abs(A.y - B.y);
    g.fillStyle = ui ? 'rgba(0,0,0,0.75)' : '#000'; g.fillRect(x, y, w, h);
    if (ui) { g.strokeStyle = '#fff'; g.lineWidth = 1; g.setLineDash([4, 3]); g.strokeRect(x, y, w, h); g.setLineDash([]); }
  }
  if (!zo) exDraw(g, T, lw, ui, line, label, showLabels);   // existing implants: confirmed ones dimmed with a dashed outline, proposals in amber

  // calibration
  const ph = clean === 'phone';   // a phone's clean film: no ball, no teardrop line, no landmark points either
  if (!zo && S.cal && S.cal.ball && !ph) { ring(S.cal.ball, S.cal.ball.r, COL.cal, 1.5); cross(S.cal.ball, COL.cal); if (showLabels) label(G.add(S.cal.ball, G.P(S.cal.ball.r * 0.7, -S.cal.ball.r * 0.7)), `${S.ballD} mm ball`, COL.cal); }
  if (!zo && S.cal && S.cal.line) { line(S.cal.line[0], S.cal.line[1], COL.cal, 1.5); label(S.cal.line[1], `${S.cal.lengthMm} mm`, COL.cal); }
  if (!zo && S.orient.marker && !ph && (ui || (S.orient.from === 'user' && typeof S.orient.markerMirrored === 'boolean' && S.mirrored !== S.orient.markerMirrored))) {   // the marker read on the film
    const m = S.orient.marker, a = G.P(m.x - m.w / 2 - 6, m.y - m.h / 2 - 6), b = G.P(m.x + m.w / 2 + 6, m.y + m.h / 2 + 6);
    stroke(() => { const A = T(a), B = T(b); g.rect(A.x, A.y, B.x - A.x, B.y - A.y); }, COL.cal, 1.2, [4, 3]);
  }

  // derived axes
  if (zo && zo.noAxes) { /* r44: drawn by the alignment on screen */ }
  else if (S.wf === 'hip') {
    const Hf = hipFrame();
    if (ph) { /* the plan only */ }
    else if (Hf && clean && !Hf.single) { const ext = G.mul(Hf.u, pxOf(25)); line(G.add(Hf.T, ext), G.sub(Hf.Tc, ext), COL.line, 1.2, [6, 5]); }   // the teardrop line, between the teardrops
    else if (Hf) longLine(Hf.T, Hf.u, COL.line, 1, [6, 5]);
    if (!clean) for (const sd of ['R', 'L']) {
      const c = lmGeom('canal' + sd);
      if (c) { const u = G.unit(G.sub(c.b, c.a)); line(G.sub(c.a, G.mul(u, 50 / (k || 0.2))), G.add(c.b, G.mul(u, 80 / (k || 0.2))), COL.line, 1, [6, 5]); }
      const tr = S.canal[sd] && S.canal[sd].tr;   // the inner cortical edges found on the film
      if (tr && c && S.canal[sd].key === canalKey(canalHL(c))) {
        for (const lv of tr.levels) { dot(lv.l, COL.line, 1.6); dot(lv.r, COL.line, 1.6); }
        // revision and conversion (amendment 2): the canal line solid where the cortex was seen, dashed where it is extrapolated
        if ((revCase() || exActive().length) && tr.observed) { const u = G.unit(G.sub(c.b, c.a)); for (const r of tr.observed) { const pa = G.project(G.add(tr.origin, G.mul(tr.d, r.from)), c.a, u), pb = G.project(G.add(tr.origin, G.mul(tr.d, r.to)), c.a, u); line(pa, pb, COL.line, 2.6); } }
      }
    }
    const mt = mirrorTarget(S.side), ref = mirrorRef(S.side);
    if (mt && Hf && ref && !clean) {   // the other hip mirrored onto the surgery side: the reconstruction target
      ring(mt, ref.r, COL.ghost, 1.2, [5, 4]); cross(mt, COL.ghost, 5);
      const M = G.mid(Hf.T, Hf.Tc); line(G.sub(M, G.mul(Hf.v, pxOf(120))), G.add(M, G.mul(Hf.v, pxOf(160))), COL.ghost, 0.8, [2, 6]);
      if (showLabels) label(G.add(mt, G.mul(G.add(G.mul(Hf.u, -1), Hf.v), ref.r * 0.95)), `${ref.what}, mirrored`, COL.ghost);
    }
  } else if (S.wf === 'kneeAP' || S.wf === 'kneeLong') {
    const A = kneeAxes();
    if (A.hip && A.ankle) line(A.hip.c, A.ankle.p, COL.line, 1, [8, 6]);
    if (A.hip && A.knee) line(A.hip.c, A.knee.p, COL.line, 1.2);
    if (A.knee && A.ankle) line(A.knee.p, A.ankle.p, COL.line, 1.2);
    if (A.knee && A.fma && !A.hip && !clean) longLine(A.knee.p, A.fma, COL.line, 1, [3, 4], 400 / (k || 0.2));
    if (A.knee && A.tma && !A.ankle && !clean) longLine(A.knee.p, A.tma, COL.line, 1, [3, 4], 400 / (k || 0.2));
    if (A.cm && A.cl) line(A.cm.p, A.cl.p, COL.line, 1);
    if (A.pm && A.pl) line(A.pm.p, A.pl.p, COL.line, 1);
  } else if (S.wf === 'kneeLat') {
    const ac = lmGeom('antCortex'); if (ac && !clean) longLine(ac.a, G.unit(G.sub(ac.b, ac.a)), COL.line, 1, [6, 5], 300 / (k || 0.2));
    const ta = lmGeom('tibAxis'); if (ta && !clean) longLine(ta.a, G.unit(G.sub(ta.b, ta.a)), COL.line, 1, [6, 5], 300 / (k || 0.2));
    const pa = lmGeom('platAnt'), pp = lmGeom('platPost'); if (pa && pp) line(pa.p, pp.p, COL.line, 1);
  } else if (S.wf === 'diaph' && k) {   // r39: the axis, the osteotomy levels from the typed values, the tumour, and a schematic only of typed sizes
    const C = diaCalc(), F = C.F, P = C.P;
    if (F) {
      const at = s => diaAt(F, s, k), across = (s, half) => [G.sub(at(s), G.mul(F.n, half / k)), G.add(at(s), G.mul(F.n, half / k))];
      if (!clean) line(F.o, C.far !== null ? at(C.far) : diaAt(F, 100, k), COL.line, 1, [6, 5]);
      { const [a, b] = across(0, 18); line(a, b, COL.line, 1.2); if (showLabels) label(b, `reference level ${diaRefTxt()}`, COL.line); }
      if (P.ok) {
        for (const [s, ex, w] of [[P.ext1, 'near', 0], [P.ext2, 'far', 1]]) { const [a, b] = across(s, 14); line(a, b, COL.ghost, 1.2, [3, 3]); if (showLabels && w) label(b, `tumour ${f1(P.ext1)}-${f1(P.ext2)} mm (${P.src}, as typed)`, COL.ghost); }
        for (const [s, sg] of [[P.cutN, '-'], [P.cutF, '+']]) { const [a, b] = across(s, 30); line(a, b, COL.cut, 2); if (showLabels) label(b, `osteotomy ${f1(s)} mm (${P.src} extent ${sg} margin ${f1(P.margin)}, projected)`, COL.cut); }
        const rect = (s0, s1, dmm) => stroke(() => { const q = [...across(s0, dmm / 2), ...across(s1, dmm / 2).reverse()].map(T); q.forEach((p, i) => (i ? g.lineTo(p.x, p.y) : g.moveTo(p.x, p.y))); g.closePath(); }, COL.tpl2, 1.6, [7, 4]);
        let drawn = null;
        for (const s of C.stems) if (Number.isFinite(s.d) && Number.isFinite(s.l)) { rect(s.side === 'N' ? P.cutN - s.l : P.cutF, s.side === 'N' ? P.cutN : P.cutF + s.l, s.d); drawn = drawn || at(s.side === 'N' ? P.cutN - s.l / 2 : P.cutF + s.l / 2); }
        if (Number.isFinite(C.u.dBody)) { rect(P.cutN, P.cutF, C.u.dBody); drawn = at((P.cutN + P.cutF) / 2); }
        if (drawn && showLabels) label(drawn, 'schematic, not a Chunli drawing', COL.tpl2);
      }
    }
  }

  // templates
  if (k) for (const [slot, t] of Object.entries(S.tpl)) {
    if (!t.on) continue;
    const gm = geomOf(t); if (!gm) continue;
    if (drawnSlots) drawnSlots.push(slot);
    const X = xf(t), f = S.fam[t.fam], act = ui && S.active === slot, op = !SLOT[slot].side || SLOT[slot].side === S.side;
    const col = op ? COL.tpl : COL.tpl2, fill = op ? COL.fill : COL.fill2;
    const outline = () => { for (const r of gm.rings) { r.forEach((p, i) => { const s = T(X(p)); i ? g.lineTo(s.x, s.y) : g.moveTo(s.x, s.y); }); g.closePath(); } };
    g.beginPath(); outline(); g.fillStyle = fill; g.fill('evenodd');
    stroke(outline, col, act ? 2.2 : 1.7, f.schematic ? [7, 4] : undefined);   // a schematic body (no maker's sheet) dashed
    if (isTumourBody(f)) {   // the osteotomy at the surgeon's resection (MRI/CT extent + margin), along this body's axis
      const tp = tumPlan(t);
      if (tp.ok) { const o = tumAt(t, tp.resection), dv = tumAxis(t).dir, nv = [-dv[1], dv[0]], a = X([o[0] - 45 * nv[0], o[1] - 45 * nv[1]]), b = X([o[0] + 45 * nv[0], o[1] + 45 * nv[1]]); line(a, b, COL.cut, 2); if (showLabels) label(b, `osteotomy ${f1(tp.resection)} mm (${tp.src} extent ${f1(tp.extent)} + margin ${f1(tp.margin)}, projected)`, COL.cut); }
      if (f.schematic && showLabels) label(X([0, -6]), 'schematic body (no Chunli sheet)', col);
    }
    if ((isPfr(f) || isPhr(f)) && gm.tumOrigin) {   // r32/r33: the planned osteotomy (MRI/CT extent + margin below the GT tip), across this body's axis
      const ph = isPhr(f), P = ph ? pfrPlan(t, S.side, 'gtub') : pfrPlan(t, slotSide(slot)), g0 = gm.tumOrigin;
      if (Number.isFinite(P.sPlan)) { const a = X([g0[0] - 30, g0[1] + P.sPlan]), b = X([g0[0] + 30, g0[1] + P.sPlan]); line(a, b, COL.cut, 2); if (showLabels) label(b, `osteotomy ${f1(P.tp.resection)} mm below the ${ph ? 'tuberosity' : 'GT'} tip (${P.tp.src} extent ${f1(P.tp.extent)} + margin ${f1(P.tp.margin)}, projected)`, COL.cut); }
    }
    if (f.kind === 'humeral-body' && gm.headFace && t.head) {   // r33: the head, a hemisphere seated on its face
      const hf = gm.headFace, r = +t.head / 2, n = hf.n, u = [-n[1], n[0]], hp = [];
      for (let i = 0; i <= 24; i++) { const a = Math.PI * i / 24; hp.push(X([hf.c[0] + r * (Math.cos(a) * u[0] + Math.sin(a) * n[0]), hf.c[1] + r * (Math.cos(a) * u[1] + Math.sin(a) * n[1])])); }
      const ho = () => { hp.forEach((p, i) => { const s = T(p); i ? g.lineTo(s.x, s.y) : g.moveTo(s.x, s.y); }); g.closePath(); };
      g.beginPath(); ho(); g.fillStyle = fill; g.fill(); stroke(ho, col, act ? 2 : 1.5);
    }
    if (ui && S.allSizes === slot) for (const z of sizesShown(f, t)) {   // r36: every size faintly, from the same point, its size at its far end
      if (z.size === t.size) continue;
      const tz = { ...t, size: z.size }, gz = geomOf(tz); if (!gz || !gz.rings || !gz.rings[0]) continue;
      const Xz = xf(tz), oz = () => { for (const r of gz.rings) { r.forEach((p, i) => { const q = T(Xz(p)); i ? g.lineTo(q.x, q.y) : g.moveTo(q.x, q.y); }); g.closePath(); } };
      stroke(oz, COL.ghost, 0.8, [3, 3]);
      const pv = f.kind === 'cup' ? null : pivotOf(tz, gz), far = gz.rings[0].reduce((a, p) => (pv ? Math.hypot(p[0] - pv[0], p[1] - pv[1]) > Math.hypot(a[0] - pv[0], a[1] - pv[1]) : p[1] < a[1]) ? p : a);   // farthest from the pivot (cup: the dome)
      if (showLabels) label(Xz(far), String(z.size), COL.ghost);
    }
    const sr = f.stems ? stemRings(t, gm) : null;   // the stem extension at the component's junction (r20)
    if (sr) {
      const so = () => { for (const r of sr) { r.forEach((p, i) => { const s = T(X(p)); i ? g.lineTo(s.x, s.y) : g.moveTo(s.x, s.y); }); g.closePath(); } };
      g.beginPath(); so(); g.fillStyle = fill; g.fill('evenodd'); stroke(so, col, act ? 2 : 1.5);
    }
    if (gm.hingeAxis && !clean) ring(X(gm.hingeAxis.c), pxOf(gm.hingeAxis.r), col, 1, [3, 3]);   // the hinge axle (LDK)
    if (f.kind === 'cup') {
      const c = X([0, 0]); cross(c, col, clean ? 6 : 8);
      const stem = S.tpl['stem' + slotSide(slot)], hc = cupCentre(t);   // r38b: a +4 liner's head centre, its own small cross
      if (linerOff(t)) cross(hc, col, clean ? 4 : 5);
      if (!clean) { ring(hc, pxOf(+(stem ? stem.head : 32) / 2), col, 1, [4, 4]); line(X([-40, 0]), X([40, 0]), col, 1, [2, 4]); }
    } else if (f.kind === 'stem') {
      if (!clean) line(X(gm.axis[0]), X([gm.axis[1][0] + 2 * (gm.axis[1][0] - gm.axis[0][0]) / 3, gm.axis[1][1] + 2 * (gm.axis[1][1] - gm.axis[0][1]) / 3]), col, 1, [6, 4]);
      if (gm.neck && gm.neck.end) {   // the neck from the drawn stub to the chosen head (the sheet's heads cover it)
        const hp = headPoint(t, t.neck), nd = gm.neckDir, nn = [-nd[1], nd[0]], w0 = gm.neck.width / 2, w1 = 6.5;   // 12/14 taper
        const e = gm.neck.end, q = [[e[0] + nn[0] * w0, e[1] + nn[1] * w0], [hp[0] + nn[0] * w1, hp[1] + nn[1] * w1], [hp[0] - nn[0] * w1, hp[1] - nn[1] * w1], [e[0] - nn[0] * w0, e[1] - nn[1] * w0]];
        if (G.dot(G.P(hp[0] - e[0], hp[1] - e[1]), G.P(nd[0], nd[1])) > 0) {
          const nk = () => { q.forEach((p, i) => { const s2 = T(X(p)); i ? g.lineTo(s2.x, s2.y) : g.moveTo(s2.x, s2.y); }); g.closePath(); };
          g.beginPath(); nk(); g.fillStyle = fill; g.fill(); stroke(nk, col, 1, [3, 3]);
        }
      }
      for (const [nkey] of neckOpts(t)) {
        const p = X(headPoint(t, nkey));
        if (nkey === t.neck) { ring(p, pxOf(+t.head / 2), col, 1.7); cross(p, col, 7); }
        else if (!clean) dot(p, op ? 'rgba(168,255,48,.75)' : 'rgba(127,212,255,.75)', 2.2);
      }
      const bpd = bipOf(t);
      if (bpd) {   // r38: the bipolar shell on the head, its axis along the neck axis; its outer centre (the centre of rotation) crossed
        const rr = bipRings(t), so = () => { for (const r of rr) { r.forEach((p, i) => { const s2 = T(p); i ? g.lineTo(s2.x, s2.y) : g.moveTo(s2.x, s2.y); }); g.closePath(); } };
        g.beginPath(); so(); g.fillStyle = fill; g.fill('evenodd'); stroke(so, col, act ? 2.2 : 1.7);
        const oc = corAt(t); cross(oc, col, clean ? 6 : 8);
        if (showLabels) label(G.add(oc, G.mul(bipAxis(t), pxOf(bpd.z.outerDiameter / 2 + 3))), `bipolar ${bpd.z.size}`, col);
      }
      if (gm.neckCut) {   // the neck resection line printed on the sheet, run on a little past the calcar
        const a = X(gm.neckCut.lateral), b = X(gm.neckCut.medial), d = G.unit(G.sub(b, a));
        line(a, b, COL.cut, 2.2); line(b, G.add(b, G.mul(d, pxOf(8))), COL.cut, 1.2, [3, 3]);
        const nc = neckCutAboveLT(t, slotSide(slot));
        if (showLabels) label(G.add(b, G.mul(d, pxOf(8))), nc === null ? 'neck cut' : `neck cut ${f1(nc)} mm above LT`, COL.cut);
      }
    } else if (f.kind === 'tka-femur' && isTumourBody(f)) {   // r27: a distal femoral replacement's y = 0 is the joint line (distal condyles), not a cut
      line(X([-50, 0]), X([50, 0]), col, 1, [2, 4]); if (showLabels) label(X([40, 0]), 'joint line', col);
    } else if (f.kind === 'tka-femur' && gm.distalCutY !== undefined) {
      line(X([-50, gm.distalCutY]), X([50, gm.distalCutY]), COL.cut, 1.5, [8, 5]);
      line(X([-50, gm.jointLineY]), X([50, gm.jointLineY]), col, 1, [2, 4]);
    } else if (f.kind === 'tka-femur' && gm.cutProfile) {
      stroke(() => gm.cutProfile.forEach((p, i) => { const s = T(X(p)); i ? g.lineTo(s.x, s.y) : g.moveTo(s.x, s.y); }), COL.cut, 1.5, [8, 5]);
    } else if (f.kind === 'tka-tibia' && isTumourBody(f)) {   // r23 (Fable P2 1): a tumour body's y = 0 is the joint line, not a cut
      line(X([-55, 0]), X([55, 0]), col, 1, [2, 4]); if (showLabels) label(X([45, 0]), 'joint line', col);
    } else if (f.kind === 'tka-tibia' && gm.cutY !== undefined) {
      line(X([-55, gm.cutY]), X([55, gm.cutY]), COL.cut, 1.5, [8, 5]);
      const jl = gm.cutY - tibJl(t); line(X([-55, jl]), X([55, jl]), col, 1, [2, 4]); if (showLabels) label(X([45, jl]), f.seatAboveCutMm ? `${t.thick} mm insert` : `${t.thick} mm`, col);
      if (t.tref && t.tref.p && S.wf === 'kneeAP') { cross(t.tref.p, COL.cut, 7); label(t.tref.p, `ref: ${t.tref.plat || ''} plateau, ${f1(t.tref.mm)} mm`, COL.cut); }   // the tibial resection's reference drawn (Astra, 16 Oct, P1 2)
    } else if (f.kind === 'tka-tibia' && gm.cutLine) {
      line(X(gm.cutLine[0]), X(gm.cutLine[1]), COL.cut, 1.5, [8, 5]);
    }
    if (act) {   // move and turn handles
      const pv = G.P(t.x, t.y), hnd = rotHandle(t);
      dot(pv, col, 4); line(pv, hnd, col, 1, [3, 3]); dot(hnd, '#fff', 6);
    }
    if (showLabels) label(X(f.kind === 'cup' ? [0, -22] : gm.rings[0][0]), `${SLOT[slot].label}${SLOT[slot].side ? ' ' + SLOT[slot].side : ''} ${sizeOf(t).size}`, col);
  }

  if (!zo) rvDraw(g, T, k, line, dot, cross, ring, label, showLabels);   // the revision record: old tip, holes, osteotomy, fixation zone, targets
  if (!zo && ui && S.arm && S.arm.kind === 'expoly' && S.arm.pts.length > 1) for (let i = 1; i < S.arm.pts.length; i++) line(S.arm.pts[i - 1], S.arm.pts[i], '#ffb040', 1.4, [4, 3]);
  // landmarks
  for (const d of lmDefs()) {
    const L = S.lm[d.key]; if (!L || (zo && zo.skip.has(d.key))) continue;
    const bad = !!lmProblem(d.key), gm = lmGeom(d.key);
    // the femoral condylar and tibial plateau widths are measurements (Sami, 23 Oct): drawn with their length in mm at the middle,
    // on the screen (a phone with Lines off too) and in the saved picture, as the rulers are
    const wid = WIDTH_LM.includes(d.key) && d.type === 'line' && gm && k;
    if (ph && !bad && !wid) continue;   // (lmShown: a hidden landmark is neither drawn nor picked up)
    if (lmShown(d, bad, clean === 'phone' ? 'phone' : !!clean)) for (const p of L) dot(p, bad ? '#ff4040' : COL.lm, clean ? 3 : 3.8);
    if (d.type === 'circle' && gm) { ring(gm.c, gm.r, bad ? '#ff4040' : COL.lm, clean ? 1 : 1.3, clean ? [4, 4] : undefined); if (!clean) cross(gm.c, COL.lm, 5); }
    if (d.type === 'line' && gm && (!clean || bad || wid)) line(gm.a, gm.b, bad ? '#ff4040' : COL.lm, 1.4);
    if (wid) label(G.mid(gm.a, gm.b), `${d.short} ${f1(G.dist(gm.a, gm.b) * k)} mm`, bad ? '#ff4040' : COL.lm);
    else if (ui && showLabels && (d.type === 'point' || gm)) label(d.type === 'circle' && gm ? gm.c : L[0], d.short || d.label, bad ? '#ff4040' : COL.lm);
  }
  if (!zo) for (const a of S.angles || []) if (a.length === 3) {   // r36: angle, three points (arm end, vertex, arm end), as Medstrat's angle tool
    line(a[1], a[0], COL.ruler, 1.2); line(a[1], a[2], COL.ruler, 1.2); for (const p of a) dot(p, COL.ruler, 3);
    label(a[1], `${f1(angleAt(a))}°`, COL.ruler);
  }
  if (!zo) for (const r of S.rulers) { for (const p of r) dot(p, COL.ruler, 3); if (r.length === 2) { line(r[0], r[1], COL.ruler, 1.2); if (k) label(G.mid(r[0], r[1]), `${f1(G.dist(r[0], r[1]) * k)} mm`, COL.ruler); } }
  if (!zo && ui && S.arm && S.arm.pts) for (const p of S.arm.pts) dot(p, '#fff', 3.5);
  for (const l of later) drawLabel(...l);
}
// labels only when 10 mm is at least 15 screen pixels: at fit zoom on a long-leg film they pile up at the knee
const smallOk = () => !mm() || 10 / mm() * S.view.k >= 15;
function rotHandle(t) { const up = G.rot(G.P(0, -1), t.angle), L = 70 / S.view.k; return G.add(G.P(t.x, t.y), G.mul(up, L)); }

// ---------- pointer ----------
function hit(sp) { return llBare(() => hit0(sp)); }   // Fable r44 P2-2: a part not drawn is not picked
function hit0(sp) {
  const near = (p, r = 9) => G.dist(toS(p), sp) <= r * (COARSE ? 2 : 1);
  if (S.active && S.tpl[S.active] && S.tpl[S.active].on && mm()) {
    const t = S.tpl[S.active];
    if (near(rotHandle(t), 10)) return { kind: 'rotate', slot: S.active };
  }
  if (mm()) for (const [slot, t] of Object.entries(S.tpl))   // a cup's centre cross is its handle, even under a stem's head
    if (t.on && S.fam[t.fam].kind === 'cup' && near(G.P(t.x, t.y), 10)) return { kind: 'tpl', slot };
  const mode = filmMode();
  for (const d of lmDefs()) if (lmShown(d, !!lmProblem(d.key), mode)) for (const [i, p] of (S.lm[d.key] || []).entries()) if (near(p)) return { kind: 'lm', key: d.key, i };
  for (const [ri, r] of S.rulers.entries()) for (const [i, p] of r.entries()) if (near(p)) return { kind: 'ruler', ri, i };
  for (const [ai, a] of (S.angles || []).entries()) for (const [i, p] of a.entries()) if (near(p)) return { kind: 'angle', ai, i };   // r36
  if (S.cal && S.cal.line) for (const [i, p] of S.cal.line.entries()) if (near(p)) return { kind: 'calline', i };
  if (mm()) {
    const ip = toI(sp), order = Object.keys(S.tpl).sort((a, b) => (b === S.active) - (a === S.active));
    for (const slot of order) {
      const t = S.tpl[slot]; if (!t.on) continue;
      if (inside(t, ip)) return { kind: 'tpl', slot };
      const f = S.fam[t.fam];   // the cup's whole disc (its centre cross is the natural handle) and the stem's head
      if (f.kind === 'cup' && G.dist(ip, G.P(t.x, t.y)) * mm() <= sizeOf(t).outerDiameter / 2) return { kind: 'tpl', slot };
      if (f.kind === 'stem' && G.dist(ip, bipOf(t) ? corAt(t) : headAt(t)) * mm() <= (bipOf(t) ? bipOf(t).z.outerDiameter : +t.head) / 2) return { kind: 'tpl', slot };   // r38: a bipolar shell's disc too
    }
  }
  return null;
}
function inside(t, p) {
  const gm = geomOf(t), X = xf(t); let c = false;
  for (const r of gm.rings) {
    const q = r.map(X);
    for (let i = 0, j = q.length - 1; i < q.length; j = i++) if ((q[i].y > p.y) !== (q[j].y > p.y) && p.x < (q[j].x - q[i].x) * (p.y - q[i].y) / (q[j].y - q[i].y) + q[i].x) c = !c;
  }
  return c;
}
const LOGO = new Image(); LOGO.src = 'brand/logo-green.png';   // Addis Ortho logo on the saved picture's report column
const COARSE = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches;
const PTRS = new Map();   // fingers on the film, for pinch zoom
// The pointer's place on the canvas from the place read when the gesture started (offsetX would make the browser lay
// the page out again on every move; round 6: mid-range phones).
const spOf = e => G.P(e.clientX - cvRect.left, e.clientY - cvRect.top);
const setCursor = c => { if (cv.style.cursor !== c) cv.style.cursor = c; };
function bindPointer() {
  cv.addEventListener('contextmenu', e => e.preventDefault());
  cv.addEventListener('pointerdown', e => {
    if (!S.img) return;
    if (PHONE.matches) { promptQuiet = true; promptMode('mini'); }
    cv.setPointerCapture(e.pointerId);
    if (PTRS.size === 0) cvRect = cv.getBoundingClientRect();   // once per gesture
    const sp = spOf(e), ip = toI(sp);
    PTRS.set(e.pointerId, sp);
    if (PTRS.size === 2) {   // a second finger: pinch to zoom and move, whatever the first one was doing
      const [a, b] = [...PTRS.values()], mid = G.mid(a, b);
      S.drag = { kind: 'pinch', d0: Math.max(10, G.dist(a, b)), k0: S.view.k, ip: toI(mid) }; S.loupe = null; S.fitted = false; draw(); return;
    }
    if (PTRS.size > 2) return;
    if (e.button === 2 || e.shiftKey) { S.drag = { kind: 'wl', sp, wl: { ...S.wl } }; return; }
    if (brushArm()) { S.drag = { kind: 'brush', ex: S.arm.kind === 'brush' ? null : S.arm.kind, cls: S.arm.cls || 'hardware', stroke: { pts: [ip], r: S.brush / S.view.k } }; draw(); return; }   // the eraser, or an existing implant's brush
    if (S.arm && e.pointerType === 'touch') { S.drag = { kind: 'aim', ip }; S.loupe = ip; draw(); return; }   // placed when the finger lifts
    if (S.arm) { armedClick(ip); return; }
    const h = hit(sp);
    if (h && h.kind === 'tpl') { S.active = h.slot; const t = S.tpl[h.slot]; S.drag = { kind: 'tpl', slot: h.slot, off: G.sub(G.P(t.x, t.y), ip) }; panel(); }
    else if (h) S.drag = h;
    else S.drag = { kind: 'pan', sp, v: { ...S.view } };
    draw();
  });
  cv.addEventListener('pointermove', e => {
    if (!S.img) return;
    const sp = spOf(e), ip = toI(sp), d = S.drag;
    if (PTRS.has(e.pointerId)) PTRS.set(e.pointerId, sp);
    if (d && d.kind === 'pinch') {
      if (PTRS.size < 2) return;
      const [a, b] = [...PTRS.values()];
      S.view.k = Math.min(20, Math.max(0.02, d.k0 * G.dist(a, b) / d.d0)); anchor(d.ip, G.mid(a, b)); draw(); return;
    }
    if (d && d.kind === 'aim') { d.ip = ip; S.loupe = ip; draw(); return; }
    if (!d) { S.hover = ip; if (brushArm()) { setCursor('none'); draw(); return; } if (e.pointerType === 'touch') return; const h = hit(sp); setCursor(S.arm ? 'crosshair' : h ? (h.kind === 'rotate' ? 'alias' : 'move') : 'grab'); return; }
    if (d.kind === 'brush') { S.hover = ip; const q = d.stroke.pts[d.stroke.pts.length - 1]; if (G.dist(q, ip) >= d.stroke.r / 3) d.stroke.pts.push(ip); draw(); return; }
    if (d.kind === 'pan') { S.view.x = d.v.x + sp.x - d.sp.x; S.view.y = d.v.y + sp.y - d.sp.y; if (Math.abs(sp.x - d.sp.x) + Math.abs(sp.y - d.sp.y) > 2) S.fitted = false; }
    else if (d.kind === 'wl') { setWL(d.wl.c - (sp.y - d.sp.y) * d.wl.w / 400, d.wl.w * Math.exp((sp.x - d.sp.x) / 200)); syncWL(); return; }
    else if (d.kind === 'lm') { S.lm[d.key][d.i] = ip; if (e.pointerType === 'touch') S.loupe = ip; }
    else if (d.kind === 'ruler') { S.rulers[d.ri][d.i] = ip; if (e.pointerType === 'touch') S.loupe = ip; }
    else if (d.kind === 'angle') { S.angles[d.ai][d.i] = ip; if (e.pointerType === 'touch') S.loupe = ip; }   // r36
    else if (d.kind === 'calline') { S.cal.line[d.i] = ip; const L2 = G.dist(S.cal.line[0], S.cal.line[1]); if (L2 >= 20) S.cal.mmPerPx = S.cal.lengthMm / L2; calBadge(); }
    else if (d.kind === 'tpl') { const t = S.tpl[d.slot], p = G.add(ip, d.off); t.x = p.x; t.y = p.y; }
    else if (d.kind === 'rotate') { const t = S.tpl[d.slot], v = G.sub(ip, G.P(t.x, t.y)); t.angle = Math.atan2(v.x, -v.y); }
    draw();
  });
  const up = e => {
    PTRS.delete(e.pointerId);
    if (S.drag && S.drag.kind === 'pinch') {   // lift every finger to finish; with two left, pinch on from where it is
      if (PTRS.size === 0) S.drag = null;
      else if (PTRS.size >= 2) { const [a, b] = [...PTRS.values()]; S.drag = { kind: 'pinch', d0: Math.max(10, G.dist(a, b)), k0: S.view.k, ip: toI(G.mid(a, b)) }; }
      return;
    }
    S.loupe = null;
    if (S.drag && S.drag.kind === 'aim') { const ip = S.drag.ip; S.drag = null; if (e.type === 'pointerup' && S.arm) armedClick(ip); draw(); return; }
    if (S.drag && S.drag.kind === 'brush') { const dd = S.drag; S.drag = null; if (dd.ex) exAddStroke(dd.stroke, dd.ex === 'exclear' ? 'clear' : dd.cls); else addCleanStroke(dd.stroke); return; }
    if (S.drag && S.drag.kind === 'lm') { const key = S.drag.key; if (/^canal[RL]$/.test(key)) { S.notes[key] = 'Moved by hand.'; rvSetCanal(key.slice(-1), { how: 'hand', mrev: exRev() }); } panel(); }
    if (S.drag && S.drag.kind !== 'pan') save();
    if (S.drag && S.drag.kind === 'wl') panel();
    S.drag = null;
  };
  cv.addEventListener('pointerup', up); cv.addEventListener('pointercancel', up);
  cv.addEventListener('wheel', e => {
    if (!S.img) return; e.preventDefault();
    const sp = spOf(e), ip = toI(sp), f = Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0015));
    S.view.k = Math.min(20, Math.max(0.02, S.view.k * f)); anchor(ip, sp); S.fitted = false; draw();
  }, { passive: false });
}
function armedClick(ip) {
  const a = S.arm;
  if (a.kind === 'lm') {
    a.pts.push(ip);
    const def = lmDefs().find(d => d.key === a.key);
    if (a.pts.length >= NPTS[def.type]) {
      if (S.ll && (a.key === 'hip' || a.key === 'head' + S.side)) S.ll = { ...S.ll, hs: S.side };   // Fable r44 recheck P3
      S.lm[a.key] = a.pts; S.arm = null; S.lmSeq = [...(S.lmSeq || []).filter(k => k !== a.key), a.key];
      if (/^canal[RL]$/.test(a.key)) snapCanal(a.key.slice(-1));
      save();
      const pr = lmProblem(a.key);
      if (pr) { prompt(`${def.label}: ${pr}. Click it in the list to place it again.`); panel(); draw(); return; }
      if (Object.keys(S.tpl).length && !lmDefs().some(d => (!d.optional || neededHere(d)) && !lmGeom(d.key))) { prompt(`${def.label} marked again. The implants are as they were: press Auto template to place them for the new landmarks.`); panel(); draw(); return; }
      nextStep();
      return;
    }
    prompt(`${def.label}: point ${a.pts.length + 1} of ${NPTS[def.type]}${def.hint ? ' (' + def.hint + ')' : ''}`);
  } else if (a.kind === 'expoly') {   // the outline of an existing implant: closed on its first point, or with "Close the outline"
    if (a.pts.length >= 3 && G.dist(toS(ip), toS(a.pts[0])) < 14 * (COARSE ? 2 : 1)) return exClosePoly();
    if (a.pts.length >= PJ.EXIST_MAX.pts) { prompt(`An outline has at most ${PJ.EXIST_MAX.pts} points: close it.`); return; }
    a.pts.push(ip); prompt(`Outline: ${a.pts.length} point${a.pts.length > 1 ? 's' : ''}. Tap the first point again (or "Close the outline") to finish.`);
  } else if (RV_ARMS[a.kind]) { rvArmed(a, ip);   // the revision record and the targets
  } else if (a.kind === 'ball') {
    if (rasterHas(exRaster(), ip.x, ip.y)) { prompt('That point is on a region marked as the existing implant: click the calibration ball.'); return; }
    if (exOpen().length) { S.arm = null; prompt('Confirm or dismiss the possible hardware first (2 · Prepare, Existing implants): the ball is then found on the film around it.'); panel(); return; }   // (Astra, 16 Oct, P1 2)
    S.arm = null; prompt('');
    const e = dicomEstimate(), rMax = Math.min(600, Math.max(30, e ? 1.8 * (S.ballD / 2) / e.mmPerPx : 200)), un = exUnknown();
    const b = G.detectBall(un ? { ...S.img, unknown: un } : S.img, ip, rMax);   // the rim found on film only, never on a confirmed implant
    if (!b || b.rejected) {   // no ball, or something that is not a clean ball: say why, then let the surgeon mark it
      prompt(`${b ? 'That does not look like the ball: ' + b.reasons.join('; ') + '.' : 'No ball edge found there.'} Click 3 points on the rim of the ball instead.`);
      S.arm = { kind: 'ball3', pts: [] }; draw(); return;
    }
    setBall(b, 'auto');
  } else if (a.kind === 'ball3') {
    a.pts.push(ip);
    if (a.pts.length === 3) { const c = G.circle3(...a.pts); S.arm = null; prompt(''); if (c) setBall({ x: c.x, y: c.y, r: c.r }, '3 points'); }
    else prompt(`Ball rim: point ${a.pts.length + 1} of 3`);
  } else if (a.kind === 'calline') {
    a.pts.push(ip);
    if (a.pts.length === 2) {
      S.arm = null; prompt('');
      const raw = window.prompt('True length of this line, in mm (e.g. a ruler on the film):', '100'); const L = parseFloat(raw);
      if (raw !== null && !(L > 0)) prompt(`"${raw}" is not a length in mm: calibration not changed.`);
      if (G.dist(a.pts[0], a.pts[1]) < 20) prompt('The two ends are too close together: mark a longer known length.');
      else if (L > 0) { S.cal = { method: 'line', line: a.pts, lengthMm: L, mmPerPx: L / G.dist(a.pts[0], a.pts[1]) }; afterCal(); nextStep(); }
    } else prompt('Known length: second end');
  } else if (a.kind === 'mask') {
    a.pts.push(ip); if (a.pts.length === 2) { (S.masks = S.masks || []).push({ a: a.pts[0], b: a.pts[1] }); S.arm = null; prompt('Area blacked out on the saved picture.'); save(); } else prompt('Black out: the opposite corner');
  } else if (a.kind === 'crop') {
    a.pts.push(ip);
    if (a.pts.length === 2) {
      const [ps, qs] = a.pts.map(toS), box = [G.P(ps.x, ps.y), G.P(qs.x, ps.y), G.P(qs.x, qs.y), G.P(ps.x, qs.y)].map(toI);   // the box as tapped on screen (Fable, 11 Oct)
      const x0 = Math.max(0, Math.round(Math.min(...box.map(b => b.x)))), y0 = Math.max(0, Math.round(Math.min(...box.map(b => b.y)))), x1 = Math.min(S.img.cols, Math.round(Math.max(...box.map(b => b.x)))), y1 = Math.min(S.img.rows, Math.round(Math.max(...box.map(b => b.y))));
      S.arm = null;
      if (x1 - x0 < 100 || y1 - y0 < 100) prompt('That area is too small to keep: crop not changed.');
      else { S.clean.crop = { x0, y0, x1, y1 }; fit(); prompt('Cropped: only this part of the film is shown and saved. "Remove crop" brings the whole film back.'); save(); }
    } else prompt('Crop: click the opposite corner of the area to keep.');
  } else if (a.kind === 'level') {   // two points that should be level (e.g. the teardrops or the ischial tuberosities)
    a.pts.push(ip);
    if (a.pts.length === 2) {
      S.arm = null; const v = G.sub(a.pts[1], a.pts[0]);
      if (G.dist(a.pts[0], a.pts[1]) < 30) prompt('The two points are too close together: pick two points far apart.');
      else { let ang = -Math.atan2(v.y, v.x); if (ang > Math.PI / 2) ang -= Math.PI; if (ang < -Math.PI / 2) ang += Math.PI; const clipped = setRot(ang); prompt(clipped ? `Those points are ${f1(Math.abs(ang) * 180 / Math.PI)}° off level; turned the most allowed (${ROT_MAX}°). Check the points: a pelvis is rarely tilted that much.` : `Turned ${f1(Math.abs(ROT()) * 180 / Math.PI)}° so the two points are level (on screen and on the saved picture; measurements are not changed).`); }
    } else prompt('Level: click the second point that should be at the same height.');
  } else if (a.kind === 'ruler') {
    a.pts.push(ip); if (a.pts.length === 2) { S.rulers.push(a.pts); S.arm = null; prompt(''); save(); } else prompt('Ruler: second end');
  } else if (a.kind === 'angle') {   // r36
    a.pts.push(ip); if (a.pts.length === 3) { (S.angles || (S.angles = [])).push(a.pts); S.arm = null; prompt(''); save(); } else prompt(a.pts.length === 1 ? 'Angle: click the vertex (the corner).' : 'Angle: click the end of the second arm.');
  }
  draw(); panel();
}
// Turn the film on screen (display only), within 30 degrees either way.
const ROT_MAX = 20;   // degrees either way: the slider's range and the limit for levelling
function setRot(a) { const lim = ROT_MAX * Math.PI / 180; S.prep = { ...(S.prep || {}), rot: Math.max(-lim, Math.min(lim, a)) }; fit(); save(); panel(); draw(); return Math.abs(a) > lim + 1e-9; }
function levelOnTeardrops() { const a = lmGeom('tdR'), b = lmGeom('tdL'); if (!a || !b) return prompt('Mark both teardrops first.'); const v = G.sub(b.p, a.p); let ang = -Math.atan2(v.y, v.x); if (ang > Math.PI / 2) ang -= Math.PI; if (ang < -Math.PI / 2) ang += Math.PI; const clipped = setRot(ang); prompt(clipped ? `The teardrop line is ${f1(Math.abs(ang) * 180 / Math.PI)}° off; turned the most allowed (${ROT_MAX}°). Check the teardrops.` : `Turned ${f1(Math.abs(ROT()) * 180 / Math.PI)}° so the teardrops are level.`); }
// A brush stroke: filled from the film around it, on the display and the saved picture (measurements keep the original).
function addCleanStroke(st) {
  if (!S.img || !S.img.data || !st.pts.length || S.filling) return;
  // the cleaner never paints over a marked existing implant unless the surgeon says so (it is not a label)
  { const R = exRaster(); if (R && st.pts.some(p => rasterHas(R, p.x, p.y)) && !confirm('This stroke crosses a marked existing implant. Fill over it anyway? (display only: the marks and the measurements keep the original film)')) return; }
  const i = S.clean.strokes.length, img = S.img;
  S.clean = { ...S.clean, strokes: [...S.clean.strokes, { pts: st.pts.map(p => ({ x: Math.round(p.x * 10) / 10, y: Math.round(p.y * 10) / 10 })), r: Math.round(st.r * 10) / 10 }] };
  prompt('Filling the stroke from the film around it…');
  const stroke = S.clean.strokes[i]; S.filling = true;
  setTimeout(() => {   // after the prompt is on screen; only if nothing changed meanwhile (Astra, 11 Oct)
    S.filling = false;
    if (S.img !== img || S.clean.strokes[i] !== stroke) { rebuildClean(); panel(); draw(); return; }
    if (!S.cleanData) S.cleanData = S.img.data.slice();
    addStroke(S.cleanData, S.img.cols, S.img.rows, stroke, i);
    S.showOriginal = false; renderImage(); save(); panel(); draw();
    prompt(S.arm && S.arm.kind === 'brush' ? 'Filled. Drag over the next one, or Esc to stop.' : '');
  }, 20);
}
function setBall(b, how, quiet) {
  S.cal = { method: 'ball', ball: { x: b.x, y: b.y, r: b.r }, how, confirmed: how !== 'found automatically', mmPerPx: S.ballD / (2 * b.r), edgeR: b.edgeR, rMajor: b.rMajor, blur: b.blur, model: b.model, quality: b.quality, warnings: b.warnings || [] };
  S.canal = {};
  afterCal();
  if (!quiet) nextStep();
}
function afterCal() {
  // templates keep their position and angle; their size on screen follows the new scale. Sizes chosen at the old scale
  // are not called "chosen by canal fill" any more, and the surgeon is told (Fable, 11 Oct).
  scaleChanged();
  save(); calBadge(); panel(); draw();
}
function scaleChanged() {
  if (!Object.keys(S.tpl).length) return;
  for (const k of Object.keys(S.notes)) if (/^(fit|cupSize|cupFail|offset|stemPlan)[RL]$/.test(k)) delete S.notes[k];
  prompt('The scale has changed: sizes chosen before may no longer fit. Press "Auto template" to choose them again, or check each one.');
}
function calBadge() { const b = $('calBadge'), [t, c] = calStatus(); b.textContent = t; b.className = 'badge' + (c ? ' ' + c : ''); }
function prompt(text) {
  const p = $('prompt'), was = p.textContent; p.textContent = text; cv.classList.toggle('armed', !!S.arm);
  if (text && (text !== was || p.classList.contains('mini'))) promptMode(promptQuiet ? 'mini' : 'line');
  // something to tap on the film: show the film (Astra, 13 Oct); the eraser keeps its stop button in view
  if (S.arm && sheet && sheet.get() === 'full' && PHONE.matches) sheet.set(brushArm() || exArmed() ? 'half' : 'peek');
}
// The prompt on a phone (round 11): one line when it changes, a small "i" chip after 4 s or the first tap on the film (it
// never sits over the hip for long); a tap on the chip shows the whole text again (for 8 s). Once the film has been
// tapped, new prompts of the same step stay a chip (the panel's row names the landmark to tap); a new step shows its
// prompt again. A desktop keeps it as it was.
let promptT = 0, promptQuiet = false;
function promptMode(m) {
  const p = $('prompt'); clearTimeout(promptT); promptT = 0;
  if (!PHONE.matches) { p.classList.remove('mini', 'full'); return; }
  if (!p.textContent) m = 'line';
  p.classList.toggle('mini', m === 'mini'); p.classList.toggle('full', m === 'full');
  p.setAttribute('aria-label', m === 'mini' ? 'Show the instruction: ' + p.textContent : p.textContent);
  if (m !== 'mini' && p.textContent) promptT = setTimeout(() => promptMode('mini'), m === 'full' ? 8000 : 4000);
}
function syncPrompt() {
  const p = $('prompt');
  if (PHONE.matches) { p.tabIndex = 0; p.setAttribute('role', 'button'); } else { p.removeAttribute('tabindex'); p.removeAttribute('role'); p.removeAttribute('aria-label'); }
  promptMode('line');
}
function armLm(key) {
  const nr = notReady(); if (nr) { S.arm = null; prompt(nr); panel(); draw(); return; }   // measured landmarks need a confirmed scale and film (Fable, 11 Oct)
  const d = lmDefs().find(x => x.key === key);
  S.arm = { kind: 'lm', key, pts: [] };
  if (S.segStep !== 'lm') S.segHand.lm = true;   // marked again later (◀ Back, the list): its segment, with the guide, opens
  prompt(`${d.label}: ${d.hint || 'click it'}${NPTS[d.type] > 1 ? ` (point 1 of ${NPTS[d.type]})` : ''}. Esc to stop.`);
  panel(); draw();
}
// On a long-leg film (much taller than wide) the hip and ankle centres are part of the plan, not optional.
const neededHere = d => (S.wf === 'kneeAP' && (d.key === 'hip' || d.key === 'ankle') && S.img && (!!S.ll || S.img.rows > 2 * S.img.cols))   // r44: always on a long-leg plan
  // a cup to be mirrored needs the other hip's head (Sami, 11 Oct: it was optional, and the cup went to the native head)
  || (S.wf === 'hip' && S.scope !== 'one' && S.set.cupRule === 'mirror' && d.key === 'head' + other(S.side) && !S.tpl['cup' + other(S.side)]);
// Planning and saving wait for what the surgeon has to confirm: the ball found automatically, and which way the film
// faces when it was read from a marker or assumed (Astra, 11 Oct: the lower buttons skipped the guided steps).
// angleOnly: the knee case's long leg and skyline films measure angles and need no scale (Astra, P1 2); a knee case's AP
// and lateral films may be marked without one too (their angles), but implants need it (addTemplate, autoTemplate)
const angleOnly = () => S.wf === 'kneeLong' || S.wf === 'kneeSky';
function notReady() {
  if (!mm() && !angleOnly() && !S.kc) return 'Calibrate first.';
  if (S.cal && S.cal.confirmed === false) return `Confirm the calibration first: is the pink circle the ${S.ballD} mm ball? (answer at the top of the panel)`;
  if ((S.orient.from === 'marker' || S.orient.from === 'assumed') && !latFilm()) return 'Confirm which way the film faces first (answer at the top of the panel).';
  if (S.wf === 'hip' && (!S.sideSet || !S.scope)) return 'Choose the surgery side (and whether the film shows both hips or one) first (at the top of the panel).';   // Sol, 11 Oct
  if (S.wf === 'hip' && S.scope === 'both' && !S.ref) return `Say first whether the ${SIDE[S.side]} hip can be planned from its own shape (at the top of the panel).`;
  return '';
}
// The guided order: calibrate -> prepare (surgery side, reference hip, clean the film) -> landmarks (each armed in turn) ->
// implants (placed automatically when the landmarks are done, if the surgeon wants that) -> measurements and save. id:
// the step (STEPS), shared by the step strip, the panel's segments and the step's button.
function stepNow() {
  if ((!S.cal || calEst(S.cal)) && !angleOnly()) return { id: 'cal', text: S.img && ctNoScaleWhy() ? 'Calibrate: this CT film has no scanner scale the page can use: mark a known length.' : 'Calibrate: click "Find ball" and click the ball, or mark a known length.' };
  if (S.cal && S.cal.confirmed === false) return { id: 'cal', confirmBall: true, text: `Is the pink circle the ${S.ballD} mm calibration ball?${S.auto.balls.length > 1 ? ` (${S.auto.balls.length} round objects were found; this is the most ball-like.)` : ''}` };
  if ((S.orient.from === 'marker' || S.orient.from === 'assumed') && !latFilm()) return { id: 'cal', confirmOrient: true, text: S.orient.from === 'marker' ? `${cap(S.orient.note)}. Is that right?` : `${S.orient.note ? cap(S.orient.note) + '. ' : 'The image does not say which way it faces. '}Is the patient's right on the image left (a standard film)?` };
  if (S.wf === 'hip' && (!S.sideSet || !S.scope)) return { id: 'prep', side: true, text: S.scope ? 'Which hip is the surgery side?' : 'Which hip is the surgery side, and does the film show both hips or one?' };
  if (S.wf === 'hip' && S.scope === 'both' && !S.ref) return { id: 'prep', ref: true, text: `Is the ${SIDE[S.side]} hip fit to template on? Not if it is fractured, shortened, externally rotated or destroyed: then template the normal ${SIDE[other(S.side)]} side and copy it across (sizes, neck cut, offset).` };
  if (!S.prepDone) return { id: 'prep', prep: true, text: 'Prepare the film (optional): erase leads, clips and labels; crop to the part you need; level the pelvis if it is tilted. Then continue to the landmarks.' };
  const lm = lmDefs().find(d => (!d.optional || neededHere(d)) && (!lmGeom(d.key) || lmProblem(d.key)));
  if (lm) return { id: 'lm', lm, text: `Mark: ${lm.label}` };
  if (S.wf === 'kneeAP' && !kneeAxes().tma) { const t = WF.kneeAP.lm.find(d => d.key === 'tibShaft'); return { id: 'lm', lm: t, text: `Mark: ${t.label}` }; }
  if (angleOnly()) return { id: 'save', text: S.wf === 'kneeLong' ? 'Long leg marked: check the alignment in Measurements (angles need no scale).' : 'Skyline: record your patellofemoral assessment in Measurements (the tilt lines are optional).' };
  if (S.wf === 'diaph') return { id: 'save', text: diaPlan().ok ? 'Landmarks done: check the Diaphyseal plan card and its order request, then save the picture or DICOM.' : 'Landmarks done: enter the MRI/CT extents and your margin on the Diaphyseal plan card.' };   // r39: no implant step
  if (S.ll) { const w = llImplWhy(); if (w) return { id: 'save', text: w }; }   // r44: the long film's angles and rows; no implant without a real scale
  if (!opSlots().every(sl => S.tpl[sl])) return { id: 'tpl', text: S.wf === 'hip' ? 'Landmarks done: place the implants (Auto template), then fine-tune.' : 'Landmarks done: place the components (Auto template), then fine-tune.' };
  { const w = tumourOpen(); if (w) return { id: 'tpl', text: w }; }   // r43 (DIAG #8)
  return { id: 'save', text: 'Check the plan, adjust the implants if needed, then save the picture or DICOM.' };
}
// r43 (DIAG #8): what a placed tumour part still needs before the plan is done ('' when nothing): the MRI/CT extent and the
// margin, the tip of the greater trochanter or tuberosity for a PFR or PHR, and one of the options (length and stem collar)
function tumourOpen() {
  for (const sl of opSlots()) {
    const t = S.tpl[sl], f = t && t.on !== false && S.fam[t.fam]; if (!f || !(isTumourBody(f) || isPfr(f) || isPhr(f))) continue;
    let tp = tumPlan(t), u = t; const nm = f.label.replace(/^Addis Ortho\s+/, '');
    if (!tp.ok && S.kc) { const g = kcTumFrom(sl, t.fam); if (g) { u = { ...t, tum: g.tum, userSize: g.userSize && g.size === t.size }; tp = tumPlan(u); } }   // Fable r43 P2-2
    if (!tp.ok) return `Tumour resection (${nm}): ${tp.why}; then pick one of the options in its box.`;
    if ((isPfr(f) || isPhr(f)) && !lmGeom(isPhr(f) ? 'gtub' : 'gt' + slotSide(sl))) return `Tumour resection (${nm}): mark the tip of the greater ${isPhr(f) ? 'tuberosity' : 'trochanter'} (2 · Landmarks); the resection is measured from it.`;
    if (!u.userSize || !(u.tum && Number.isFinite(u.tum.c))) return `Tumour resection (${nm}): pick one of the options (length and stem collar) in its box; none is chosen for you.`;
  }
  return '';
}
function nextStep() {
  const st = stepNow();
  if (st.confirmBall || st.confirmOrient) { S.arm = null; prompt(st.text + ' Answer in the panel.'); panel(); draw(); return; }
  if (st.side) { prompt('Which hip is the surgery side? Choose in the panel.'); panel(); draw(); return; }
  if (st.ref) { S.arm = null; prompt(`Is the ${SIDE[S.side]} hip fit to plan from? Answer in the panel.`); panel(); draw(); return; }
  if (st.prep) { S.arm = null; prompt('Prepare the film if needed (erase, crop, level), then press "Continue to landmarks".'); panel(); draw(); return; }
  if (st.id === 'lm') {
    const was = S.cal && !calEst(S.cal) && !S.lm[st.lm.key] && Object.keys(S.lm).length === 0;
    armLm(st.lm.key);
    if (was) prompt(`Calibrated (${S.cal.method === 'ball' ? S.ballD + ' mm ball' : S.cal.method === 'line' ? S.cal.lengthMm + ' mm line' : 'scale from the CT scanner'}, ${S.cal.mmPerPx.toFixed(4)} mm per pixel). Next: ${st.lm.label}: ${st.lm.hint || 'click it'}. Esc to stop.`);
  } else if (st.id === 'tpl' && S.set.autoTemplate && Object.keys(S.tpl).length === 0 && !hiddenHere() && !(S.wf !== 'hip' && S.wf !== 'shoulder' && pvNeedsAsk())) autoTemplate();   // never over implants kept aside
  else if (st.id === 'tpl' && S.wf !== 'hip' && S.wf !== 'shoulder' && Object.keys(S.tpl).length === 0) { prompt('Landmarks done: check the values on the Knee plan card, then press Auto template (or "Place with these values").'); panel(); draw(); }   // (Astra and Fable, 23 Oct: a deliberate press places a knee)
  else { prompt(st.id === 'tpl' ? st.text : ''); panel(); draw(); }
}

// Back (Sami, 11 Oct: "go back to a previous stage without starting over"): one step of the guided order back. It never
// deletes anything: from the plan, the last landmark is armed to be marked again (implants stay; Auto template places
// them again when wanted); from a landmark, the one marked before it (its points stay until new ones are clicked); before
// the landmarks, the reference, the surgery side, the orientation and the ball are asked again, in that order. Undo still
// steps back single changes.
function backTarget() {
  if (!S.img) return null;
  // the questions before the landmarks, when one of them is open, go back in their own order (Astra, 11 Oct: a loop)
  const ballOpen = S.cal && S.cal.confirmed === false, oriOpen = (S.orient.from === 'marker' || S.orient.from === 'assumed') && !latFilm();
  const sideOpen = S.wf === 'hip' && (!S.sideSet || !S.scope), refOpen = S.wf === 'hip' && S.scope === 'both' && !S.ref;
  const ballBack = () => (S.cal && S.cal.method === 'ball' && S.cal.confirmed ? { what: 'check the calibration ball again', go: recalibrate } : null);
  const oriBack = () => (S.orient.from === 'user' && S.orient.prevFrom ? { what: 'confirm which way the film faces again', go: () => { S.orient = { ...S.orient, from: S.orient.prevFrom }; save(); nextStep(); } } : ballBack());
  if (ballOpen) return null;
  if (oriOpen) return ballBack();
  if (sideOpen) return oriBack();
  if (refOpen) return { what: 'choose the surgery side again', go: () => { S.sideSet = false; save(); nextStep(); } };
  if (!S.prepDone) return S.wf === 'hip' && S.scope === 'both' && S.ref ? { what: 'answer again whether the surgery-side hip is fit to template on', go: () => { S.ref = null; save(); nextStep(); } }
    : S.wf === 'hip' && S.sideSet ? { what: 'choose the surgery side again', go: () => { S.sideSet = false; save(); nextStep(); } } : oriBack();
  const seq = (S.lmSeq || []).filter(k => S.lm[k] && lmDefs().some(d => d.key === k));
  const extra = lmDefs().map(d => d.key).filter(k => S.lm[k] && !seq.includes(k));   // plans saved before Back existed
  const order = [...extra, ...seq], cur = S.arm && S.arm.kind === 'lm' ? S.arm.key : null, i = cur ? order.indexOf(cur) : -1;
  const prev = i > 0 ? order[i - 1] : i === -1 && order.length ? order[order.length - 1] : null;
  if (!prev && S.prepDone) return { what: 'prepare the film again (erase, crop, level)', go: () => { S.prepDone = false; save(); nextStep(); } };
  if (prev) return { what: `mark again: ${lmDefs().find(d => d.key === prev).label}${Object.keys(S.tpl).length ? ' (implants are kept)' : ''}`, go: () => armLm(prev) };
  if (S.wf === 'hip' && S.scope === 'both' && S.ref) return { what: 'choose again how the surgery side is planned', go: () => { S.ref = null; save(); nextStep(); } };
  if (S.wf === 'hip' && S.sideSet) return { what: 'choose the surgery side again', go: () => { S.sideSet = false; save(); nextStep(); } };
  if (S.orient.from === 'user' && S.orient.prevFrom) return { what: 'confirm which way the film faces again', go: () => { S.orient = { ...S.orient, from: S.orient.prevFrom }; save(); nextStep(); } };
  if (S.cal && S.cal.method === 'ball' && S.cal.confirmed) return { what: 'check the calibration ball again', go: recalibrate };
  return null;
}
function goBack() { const b = backTarget(); if (b) { S.arm = null; b.go(); panel(); draw(); } }
// Calibrate again, keeping the landmarks (they are in image pixels) and the implants (their sizes are checked again).
function recalibrate() {
  S.arm = null;
  if (S.cal && S.cal.method === 'ball') { S.cal = { ...S.cal, confirmed: false }; save(); nextStep(); prompt(`Is the pink circle the ${S.ballD} mm ball? Answer in the panel, or click "Not the ball". Landmarks and implants are kept.`); }
  else { S.arm = { kind: 'ball' }; prompt('Click in the middle of the calibration ball (landmarks and implants are kept).'); }
  panel(); draw();
}

// ---------- keys ----------
// Shortcuts (Sami, 13 Oct: "up and down arrows" change the selected implant's size), only where they cannot take a key
// from something else (uikit.shortcut and focusKind: never while typing in a field; arrows and letters only with the
// X-ray or nothing in particular focused; Tab never). The page's default action is stopped only for a shortcut it ran.
function bindKeys() {
  window.addEventListener('keydown', e => {
    if (!S.img || document.querySelector('.pv') || document.body.classList.contains('choosing')) return;   // the sharing preview and the dialogs have their own buttons; the start card is over the film
    const sc = shortcut(e, focusKind(document.activeElement, cv)); if (!sc) return;
    if (sc.act === 'escape') { S.arm = null; prompt(''); panel(); draw(); return; }
    if (sc.act === 'undo') { e.preventDefault(); undo(); return; }
    if (sc.act === 'redo') { e.preventDefault(); redo(); return; }
    if (sc.act === 'fit') { e.preventDefault(); fit(); draw(); return; }   // before the selected-implant check: Fit needs none (Astra, 13 Oct)
    const t = S.active && S.tpl[S.active];
    if (!t || !mm()) { if (!S.arm && Object.keys(S.tpl).length) prompt('Select an implant first: tap it on the X-ray, or press "Next implant".'); return; }
    e.preventDefault();
    if (sc.act === 'size') { stepSize(S.active, sc.d); save(); panel(); draw(); return; }
    if (sc.act === 'move') { const v = G.rot(G.P(sc.dx * pxOf(sc.mm), sc.dy * pxOf(sc.mm)), -ROT()); t.x += v.x; t.y += v.y; }   // along the screen, also on a turned film
    else if (sc.act === 'turn') t.angle += G.rad(sc.deg);
    save(); panelSoon(); draw();   // a held key moves smoothly; the panel's notes follow when it stops
  });
}
// the panel rebuilt once changes stop coming (a held key), not on every one
let panelT = 0;
function panelSoon() { clearTimeout(panelT); panelT = setTimeout(() => { panelT = 0; panel(); }, 160); }
function stepSize(slot, d) {
  const t = S.tpl[slot], f = S.fam[t.fam], list = sizesShown(f, t), i = list.findIndex(s => s.size === t.size), j = Math.max(0, Math.min(list.length - 1, i + d));
  setSize(slot, list[j].size);
}
// a size chosen for the implant in this slot: a stem keeps its head where it was; a cup's head follows its liner
function setSize(slot, size) {
  if (llKcLong() && (slot === 'femur' || slot === 'tibia')) return prompt(LL_KC_SIZE);   // r44
  const t = S.tpl[slot], f = S.fam[t.fam];
  // a tray Chunli's chart grades not compatible with the femur: only on the surgeon's explicit override (Sami, 15 Oct)
  if (slot === 'tibia' || slot === 'femur') {
    const fe = slot === 'femur' ? { fam: t.fam, size } : S.tpl.femur, ti = slot === 'tibia' ? { fam: t.fam, size } : S.tpl.tibia;
    if (fe && ti && kneeGrade(fe.fam, fe.size, ti.fam, ti.size) === 'gray' && kneeGrade(fe.fam, slot === 'femur' ? fe.size : S.tpl.femur.size, ti.fam, slot === 'tibia' ? t.size : ti.size) !== 'gray'
      && !confirm(`Femur ${fe.size} with tray ${ti.size} is not compatible per the ${CHART}. Use it anyway (your override)?`)) return;
  }
  if (f.kind === 'stem') changeStem(t, () => { t.size = size; }); else t.size = size;
  t.userSize = true;
  if (S.kc && (slot === 'femur' || slot === 'tibia')) kcSizeSet(slot, t, true);   // one size for both films
  if (f.kind === 'cup') replanAfter(slot);   // the stem and the mirrored plan follow the cup (finding 3)
}
// "Next implant" (in place of Tab, which stays for moving the focus; Astra, 13 Oct): the next implant shown on the film
function nextImplant() {
  const on = WF[S.wf].slots.filter(sl => S.tpl[sl] && S.tpl[sl].on);
  if (!on.length) return prompt('No implant on the film yet.');
  S.active = on[(on.indexOf(S.active) + 1) % on.length];
  prompt(`Selected: ${slotName(S.active)}.`); panel(); draw();
}
// the stem's head follows the cup's liner (one liner, and so one head size, per cup) unless chosen by hand; a vendor cup
// that lists its heads gets the largest of them the stem's head systems offer. Never a head system the stem does not take.
// Never changed for a cup and stem of different makers, nor for a cup that lists neither (headCaution says so instead).
function matchHeadToCup(side) {
  // guarded (Astra, round 3): every automatic head match runs inside guardStemOp, so the stem is changed only when it then sits
  // on evidence, and is put back whole otherwise (placing a cup re-posed an unevidenced stem through its head)
  if (guardedStem() && !IN_GUARD && S.tpl['stem' + side]) { const r = guardStemOp(side, () => matchHeadNow(side)); linerFix(side); return r; }
  const r = matchHeadNow(side); linerFix(side); return r;   // r38b: the liner follows the head (neutral, else +4 neutral)
}
function matchHeadNow(side) {
  const cup = S.tpl['cup' + side], stem = S.tpl['stem' + side]; if (!cup || !stem || stem.userHead) return;
  if (guardedStem() && stemGate(side)) return;   // the stem waits: its head is not changed either (Astra, round 2, P1 2)
  if (makerOf(S.fam[cup.fam]) !== makerOf(S.fam[stem.fam])) return;
  const cs = sizeOf(cup), ch0 = cupHeads(cup); if (!cs) return;
  // r38b: with a liner type chosen by hand, the largest head that type is offered for (else every head, as before)
  const lty = cup.userLiner && linerOf(cup), chT = ch0 && lty ? ch0.filter(d => linerTypesFor(cup, d).includes(lty)) : null, ch = chT && chT.length ? chT : ch0;
  const kinds = headKindsOf(S.fam[stem.fam]), kindFor = d => (kinds.includes(stem.headKind) && S.lib.heads[stem.headKind].sizes[d] ? stem.headKind : kinds.find(k => S.lib.heads[k].sizes[d]));
  const want = ch ? [...ch].sort((a, b) => b - a).find(d => kindFor(d)) : cs.maxHead ? String(cs.maxHead) : null;
  const kind = want && kindFor(want);
  if (!kind || (stem.head === want && stem.headKind === kind)) return;
  changeStem(stem, () => setHead(stem, kind, want));
}
// the head diameters a cup takes when it lists them ("heads" on the size or the family), as text; else null
const cupHeads = cup => { const z = sizeOf(cup), h = (z && z.heads) || S.fam[cup.fam].heads; return Array.isArray(h) && h.length ? h.map(String) : null; };
const linerTxt = (f, l) => (f.maker === 'Chunli' ? `${l}#` : String(l));   // Chunli numbers its liners: 39#, 44#
// a head diameter as it is ordered: J&J's 22.225 mm head is the "22 mm" head (Fable, 13 Oct 2026)
const headMm = d => (Math.abs(+d - 22.225) < 1e-6 ? '22 mm (22.225)' : `${d} mm`);
const headsMm = hs => hs.map(headMm).join(', ');
const linerSub = (cup, stem) => { const LL = cup && stem && linerLine(cup, stem.head); if (LL) return `liner ${LL}; `; const L = cup && stem && linersFor(cup, stem.head); return L ? `liner for this head: ${L.join(', ')}; ` : ''; };   // r38b: the type and codes
// the liner materials (or types) a vendor cup size lists for a head ("headLiners" {head: [materials]}), else null
const linersFor = (cup, head) => { const z = sizeOf(cup), L = z && z.headLiners && z.headLiners[String(head)]; return Array.isArray(L) && L.length ? L : null; };
// a vendor cup whose heads list is a union across liner materials (more than one material listed for the size, or no
// per-head list at all): a standing caution in the results, the recommendation and the saved picture (Fable, 13 Oct)
const HEADS_ACROSS = ['Head sizes listed are across all liner materials', 'check the liner type for the chosen head'];
const headsAcrossLiners = cup => {
  const z = sizeOf(cup), f = S.fam[cup.fam];
  if (!z || !f || f.kind !== 'cup' || makerOf(f) === 'Chunli' || !Array.isArray(z.heads) || !z.heads.length) return false;
  return !z.headLiners || new Set(Object.values(z.headLiners).flat()).size > 1;
};
const drawnFrom = f => (f.maker === 'Chunli' ? '3D model' : 'fitted template');   // Chunli cups: its meshes; others: fitted to their sheets
const makerOf = f => (f && (f.maker || ((S.lib.ranges || {})[f.range] || {}).maker)) || '';
// What the page cannot check about the head (Fable, 12 Oct 2026): a cup size that lists neither its heads nor its
// liner's largest head (J&J Pinnacle today), and a cup and stem of different makers. A warning row each, in the results,
// the recommendation and the saved picture; the head is not changed (matchHeadToCup).
function headCaution(R, cup, stem) {
  const cs = sizeOf(cup);
  if (cs && !cupHeads(cup) && !cs.maxHead) R.push(['Head and liner not checked for this cup', 'use the maker\'s own liner and head chart', 'warn']);
  const mats = linersFor(cup, stem.head), ll = linerLine(cup, stem.head), lt = linerOf(cup);
  if (ll) R.push(['Liner', `${ll} (cup ${cs.size}, ${headMm(stem.head)} head)`]);   // r38b: the type, materials and codes
  else if (lt && linerTypesFor(cup, stem.head).length) R.push(['Liner', `no ${linerName(cup, lt)} liner for a ${headMm(stem.head)} head on cup ${cs.size}: choose the liner again`, 'warn']);
  else if (mats) R.push(['Liner for this head', `${mats.join(', ')} (cup ${cs.size}, ${headMm(stem.head)} head)`]);
  if (lt && linerOff(cup) && ll) {   // where the +4 head centre is on this film (already inside the lengthening and offset rows)
    const Hf = hipFrame(slotSide(Object.keys(S.tpl).find(k => S.tpl[k] === cup) || 'cup' + S.side)); let inc = Hf ? G.angleDeg(dirOf(cup, [1, 0]), Hf.u) : null; if (inc !== null && inc > 90) inc = 180 - inc;
    R.push([`${linerName(cup, lt)} liner`, inc === null ? `head centre ${f1(linerOff(cup))} mm out of the shell along its axis` : `head centre ${f1(linerOff(cup) * Math.sin(G.rad(inc)))} mm lateral, ${f1(linerOff(cup) * Math.cos(G.rad(inc)))} mm down from the shell centre (at ${f1(inc)}°); measured in the leg length and offset rows`]);
  }
  if (lt === 'plus4-10' || (linerTypesOf(cup)[lt] || {}).faceDeg) R.push(['Liner face', `${linerName(cup, lt)}: a face-changing liner; the face change is not drawn`, 'warn']);
  if (lt === 'lipped') R.push(['Liner lip', 'lipped liner: the lip is not drawn', 'warn']);
  if (makerOf(S.fam[cup.fam]) !== makerOf(S.fam[stem.fam])) R.push(['Cup and stem from different makers', 'confirm the head, taper and liner are compatible', 'warn']);
}
// a family its maker has not yet confirmed (verified: false): said in the recommendation and on the saved picture, for
// cups, stems and knee parts alike. A family Addis Ortho has checked against the maker's catalogue (catalogueChecked: a
// few printed dimensions, not the outline; Fable, 13 Oct 2026) says that too, and still that the outline is unconfirmed.
const CAT_CHECKED = 'Dimensions checked against the maker\'s catalogue by Addis Ortho; outline not yet confirmed by the manufacturer';
const unconfirmed = ts => [...new Set(ts.filter(t => t && S.fam[t.fam] && !S.fam[t.fam].verified).map(t => S.fam[t.fam].catalogueChecked
  ? `${S.fam[t.fam].label}: ${CAT_CHECKED.charAt(0).toLowerCase()}${CAT_CHECKED.slice(1)}` : `${S.fam[t.fam].label} outline not yet confirmed by the manufacturer`))];

// ---------- side panel ----------
function el(html) { const d = document.createElement('div'); d.innerHTML = html.trim(); return d.firstChild; }
function btn(text, fn, cls = '', dis = false, title = '') { const b = document.createElement('button'); b.textContent = text; b.onclick = fn; if (cls) b.className = cls; b.disabled = dis; if (title) b.title = title; return b; }
function resTable(R) {
  if (!R.length) return '';
  // a long value (a warning sentence) gets its own full-width row, so the number column never runs off the panel
  return '<table class="res">' + R.map(r => r.length === 1 ? `<tr class="h"><td colspan="2">${esc(r[0])}</td></tr>`
    : String(r[1]).length > 30 ? `<tr class="note"><td colspan="2" class="${r[2] || ''}"><span>${esc(r[0])}:</span> ${esc(r[1])}</td></tr>`
    : `<tr><td>${esc(r[0])}</td><td class="${r[2] || ''}">${esc(r[1])}</td></tr>`).join('') + '</table>';
}
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
// Slider scales: brightness across the film's 1st-99th percentile grey range (with half a range either side), contrast
// from 4 times that range (flat) to a tenth of it (hard), the auto window in the middle (Fable, 11 Oct).
function wlRef() {
  if (!S.wlRefs || S.wlRefs.img !== S.img) { const keep = S.wl; autoWindow(); S.wlRefs = { img: S.img, c: S.wl.c, w: S.wl.w }; S.wl = keep; }
  return S.wlRefs;
}
function wlFromSliders(b, c) {
  const r = wlRef(), w = c <= 50 ? r.w * Math.pow(4, (50 - c) / 50) : r.w * Math.pow(10, -(c - 50) / 50);
  return { c: r.c + r.w * (0.5 - b / 100) * 2, w };
}
function syncWL() {   // brightness and contrast sliders follow the window
  const b = $('wlB'), c = $('wlC'); if (!b || !c || !S.img) return;
  const r = wlRef(), lr = Math.log10(S.wl.w / r.w);
  b.value = Math.round(Math.max(0, Math.min(100, 100 * (0.5 - (S.wl.c - r.c) / (2 * r.w)))));
  c.value = Math.round(Math.max(0, Math.min(100, lr >= 0 ? 50 - 50 * lr / Math.log10(4) : 50 - 50 * lr)));
}

// Erase, crop and level: in the guided "Prepare" step and in the Prepare segment.
function prepTools(guided = false) {
  const d = el('<div class="prep"></div>'), brushOn = S.arm && S.arm.kind === 'brush';
  const r1 = el('<div class="row"></div>');
  r1.append(btn(brushOn ? 'Erasing (tap again to stop)' : 'Erase', () => { S.arm = brushOn ? null : { kind: 'brush' }; prompt(S.arm ? 'Drag over leads, clips, wires or stray labels. Each stroke is filled from the film around it.' : ''); panel(); draw(); }, brushOn ? 'on' : '', !S.img.data, 'Remove leads, clips, wires and stray labels from the picture'),
    btn(S.clean.crop ? 'Crop again' : 'Crop', () => { S.arm = { kind: 'crop', pts: [] }; prompt('Crop: tap one corner of the part to keep, then the opposite corner.'); panel(); }, S.arm && S.arm.kind === 'crop' ? 'on' : ''),
    btn('Level', () => { S.arm = { kind: 'level', pts: [] }; prompt('Level: tap two points that should be at the same height (both teardrops, or both ischial tuberosities).'); panel(); }, S.arm && S.arm.kind === 'level' ? 'on' : '', false, 'Turn the film so two points are level'));
  const bs = el(`<label class="note">brush <select><option value="4">fine</option><option value="8">medium</option><option value="16">wide</option><option value="30">very wide</option></select></label>`);
  bs.querySelector('select').value = String(S.brush); bs.querySelector('select').onchange = e => { S.brush = +e.target.value; draw(); };
  r1.appendChild(bs);
  d.appendChild(r1);
  const deg = ROT() * 180 / Math.PI;
  const rr = el(`<label class="note rot">Turn <input type="range" min="${-ROT_MAX}" max="${ROT_MAX}" step="0.5" value="${f1(deg)}"> <span>${f1(deg)}°</span></label>`);
  rr.querySelector('input').oninput = e => { rr.querySelector('span').textContent = `${(+e.target.value).toFixed(1)}°`; S.prep = { ...(S.prep || {}), rot: +e.target.value * Math.PI / 180 }; draw(); };
  rr.querySelector('input').onchange = () => { fit(); save(); panel(); draw(); };
  d.appendChild(rr);
  const r2 = el('<div class="row"></div>');
  if (S.clean.strokes.length) r2.appendChild(btn('Undo last stroke', () => { S.clean = { ...S.clean, strokes: S.clean.strokes.slice(0, -1) }; rebuildClean(); save(); panel(); draw(); }));
  if (S.clean.crop) r2.appendChild(btn('Remove crop', () => { S.clean = { ...S.clean, crop: null }; fit(); save(); panel(); draw(); }));
  if (ROT()) r2.appendChild(btn('Straighten (0°)', () => setRot(0)));
  if (r2.childNodes.length) d.appendChild(r2);
  if (guided) d.appendChild(btn('Continue to landmarks', () => { S.prepDone = true; S.arm = null; save(); nextStep(); }, 'primary wide'));
  return d;
}
// One segment of the side panel (round 6, 13 Oct 2026; Sami: "segments up to 5 by default collapsed and when one segment
// is finished and when we transition to the other one collapse the other one and open the one in use"). Only the
// current step's segment is open; one the surgeon opens or closes by hand stays so until the step changes. Closed, its
// title line says what it holds (the scale, landmarks 6/6, the implants and their sizes).
function seg(id, cur, summary, cls = '') {
  const i = STEPS.findIndex(s => s.id === id);
  const d = el(`<details class="sec" id="sec-${id}"><summary><span class="segn">${i + 1}</span><span class="segt">${esc(STEPS[i].title)}</span><span class="segr ${cls}">${esc(summary)}</span></summary></details>`);
  d.open = S.segHand[id] ?? id === cur;
  d.querySelector('summary').addEventListener('click', () => { S.segHand[id] = !d.open; });   // before it opens or closes
  return d;
}
// Explanations fold away under a small "How this works" line (Sami, 11 Oct: "make the description expandable").
function help(html, title = 'How this works') { return el(`<details class="help"><summary>${esc(title)}</summary><div>${html}</div></details>`); }

// The scale, as the header badge and the panel's top line say it: [text, '' | 'warn' | 'bad']
function calStatus() {
  if (!S.cal) return ['Not calibrated', 'bad'];
  if (isCtCal(S.cal)) return ['Scale from the CT scanner', ''];   // r43
  if (S.cal.method === 'dicom') return [S.kc ? 'DICOM spacing only' : 'Machine estimate only', 'warn'];
  if (S.cal.method === 'xfer') return kcXferStale() ? ['Scale from the AP out of date', 'bad'] : ['Scaled from the AP (approximate)', 'warn'];
  if (S.cal.method === 'ball' && S.cal.confirmed === false) return ['Ball found: confirm it', 'warn'];
  const what = S.cal.method === 'ball' ? `${S.ballD} mm ball` : `${S.cal.lengthMm} mm line`;
  if (S.cal.method === 'ball' && S.cal.warnings && S.cal.warnings.length) return [`Calibrated: ${what}, check it`, 'warn'];
  return [`Calibrated: ${what}`, ''];
}
// Who, which side and the scale: always at the top of the panel, also with every segment closed and on a phone's
// smallest panel (Astra, 13 Oct). The patient's name and record number as in the hospital; none in sharing mode.
function ctxBox() {
  const m = S.img.meta || {}, [ct, cc] = calStatus();
  const id = identNow(), who = S.set.share ? `Sharing mode: no patient details${id.hospital ? ` · ${id.hospital}` : ''}` : [id.name, id.hospital, Number.isInteger(id.age) ? `${id.age} y` : '', recordNo() ? 'MRN ' + recordNo() : ''].filter(Boolean).join(' · ') || 'No patient details in this image';
  const side = (S.kc ? `${cap(SIDE[S.kc.side])} knee case · ${KC_NAME[S.kc.active]} film` : S.wf === 'hip' ? (S.sideSet ? `${cap(SIDE[S.side])} hip${refOther() ? `, planned from the ${SIDE[other(S.side)]}` : ''}` : 'Surgery side not chosen') : S.wf === 'shoulder' ? `${cap(SIDE[S.side])} shoulder` : S.wf === 'diaph' ? `${cap(SIDE[S.side])} ${diaU().bone || 'femur or tibia'}, diaphysis (${diaViewTxt()})` : `${cap(SIDE[S.side])} knee`) + (revCase() ? ` · ${CASE_WORD[ctype()].toLowerCase()}` : '');
  return el(`<div class="ctx" id="ctx"><span class="who">${esc(who)}</span><span class="side">${esc(side)}</span><span class="cal ${cc}">${esc(ct)}${mm() && !calEst(S.cal) ? `, ${S.cal.mmPerPx.toFixed(4)} mm/px` : ''}</span></div>`);
}
// what each closed segment says
const shortSlot = sl => (SLOT[sl].side ? `${SLOT[sl].label} ${SLOT[sl].side}` : { femur: 'Femur', tibia: 'Tibia' }[sl] || SLOT[sl].label);
const shortFam = f => f.label.replace(/^Addis Ortho\s+/, '').replace(/^(cementless cup|total knee .*)$/, '');
function segSummaries(R) {
  const out = {};
  { const [t, c] = calStatus(); out.cal = [mm() && !calEst(S.cal) && S.cal.method !== 'xfer' && S.cal.confirmed !== false ? `${S.cal.method === 'ball' ? S.ballD + ' mm ball' : S.cal.method === 'line' ? S.cal.lengthMm + ' mm line' : 'CT scanner'}, ${S.cal.mmPerPx.toFixed(4)} mm/px` : !S.cal && angleOnly() ? 'no scale: angles only' : t, !S.cal && angleOnly() ? '' : c];
    if (!c && (S.orient.from === 'marker' || S.orient.from === 'assumed') && !latFilm()) out.cal = [out.cal[0] + '; confirm which way the film faces', 'warn']; }
  const p = [], nS = S.clean.strokes.length;
  if (S.wf === 'hip' && refOther()) p.push(`planned from the ${SIDE[other(S.side)]} hip`);
  if (nS) p.push(`${nS} area${nS > 1 ? 's' : ''} erased`);
  if (S.clean.crop) p.push('cropped');
  if (Math.abs(ROT()) > 0.0005) p.push(`turned ${f1(ROT() * 180 / Math.PI)}°`);
  if (S.masks && S.masks.length) p.push(`${S.masks.length} blacked out`);
  if (revCase()) p.unshift(CASE_WORD[ctype()].toLowerCase());
  if (exActive().length) p.push(`${exActive().length} existing implant region${exActive().length > 1 ? 's' : ''}`);
  if (exOpen().length) p.push(`${exOpen().length} to confirm`);
  out.prep = [p.join(', ') || (S.prepDone ? 'nothing changed' : 'optional'), exOpen().length ? 'warn' : ''];
  const need = lmDefs().filter(d => !d.optional || neededHere(d)), ok = need.filter(d => lmGeom(d.key) && !lmProblem(d.key)).length, bad = lmDefs().filter(d => lmProblem(d.key) || lmBroken(d.key)).length;
  out.lm = [`${ok}/${need.length} marked${bad ? `, ${bad} to place again` : ''}`, bad ? 'bad' : ''];
  const imp = WF[S.wf].slots.filter(sl => S.tpl && S.tpl[sl]).map(sl => { const t = S.tpl[sl], f = S.fam[t.fam], fn = shortFam(f); return `${shortSlot(sl)} ${fn ? fn + ' ' : ''}${t.size}${f.kind === 'stem' ? `, head ${t.head}${bipOf(t) ? `, bipolar ${bipOf(t).z.size}${bipAssumed(bipOf(t).z) ? ' (inner part assumed)' : ''}` : ''}` : ''}${t.on ? '' : ' (hidden)'}`; });
  const kept = Object.keys((S.hidden || {})[S.wf] || {}).length;
  out.tpl = [imp.join(' · ') + (kept ? `${imp.length ? ' · ' : ''}${kept} kept aside` : '') || (mm() ? 'none placed' : 'calibrate first'), ''];
  const done = opSlots().length > 0 && opSlots().every(sl => S.tpl[sl] && S.tpl[sl].on), g = l => (R.find(r => r[0] === l) || [])[1];
  const nWarn = R.filter(r => r.length > 1 && r[2] === 'warn').length, nBad = R.filter(r => r.length > 1 && r[2] === 'bad').length;
  const sv = done ? ['recommendation ready', g('Leg length after') ? `leg length after ${g('Leg length after')}` : ''] : ['recommendation when the implants are placed'];
  if (nBad + nWarn) sv.push(`${nBad + nWarn} to check`);
  if (S.sent) sv.push(VIEWER ? 'saved in the viewer' : 'sent to PACS');
  out.save = [sv.filter(Boolean).join(', '), nBad ? 'bad' : nWarn ? 'warn' : ''];
  if (S.wf === 'diaph') { const P = diaPlan(); out.tpl = ['none: measurements only', '']; out.save = [[P.ok ? `defect ${fmm(P.defect)} mm, order request ready` : 'order request when the extents are entered', nBad + nWarn ? `${nBad + nWarn} to check` : ''].filter(Boolean).join(', '), nBad ? 'bad' : nWarn ? 'warn' : '']; }   // r39
  return out;
}

function panel() {
  calBadge(); syncHeader();
  if (panelT) { clearTimeout(panelT); panelT = 0; }
  const P_ = $('panel'), fe = document.activeElement, field = !!fe && P_.contains(fe) && /^(INPUT|SELECT|TEXTAREA)$/.test(fe.tagName);
  const kept = keepPanel(P_), step0 = S.segStep;
  P_.textContent = '';
  if (!S.lib) return;
  if (!S.img) { P_.appendChild(el('<p class="note">No X-ray open.</p>')); dock(); return; }
  if (STANDALONE) P_.appendChild(PJ.awayEl());   // earlier X-rays still waiting for the patient list (round 13b): on top, never folded away
  try { panelBody(P_); } catch (e) {   // a fault in one part of the panel must not leave the surgeon with nothing
    console.error(e);
    P_.appendChild(el(`<div class="warnbox">Part of the panel could not be shown (${esc(e.message || e)}). The plan is kept: press Undo, or reload the page.</div>`));
    P_.appendChild(btn('Undo', undo));
  }
  // the same scroll position and open boxes after the rebuild (a new step starts at the top, where its button is); the
  // focus goes back for the keyboard and for a field or list being changed, not after a click or tap (then the arrow
  // keys go on working on the X-ray)
  restorePanel(P_, kept, { focus: lastInput === 'key' || field, top: S.segStep === step0 });
  dock(); syncLines();
}
// The phone's panel row (round 11): the step by name, its one button (the same action as the button in the panel), the
// badge and the handle. A question step (no one button: the ball, the side, the reference, preparing) also shows its
// answers at the smallest height ('ask').
function sheetRow(st, ci, nb, R) {
  const s = $('sbStep'), a = $('sbAct'); if (!s || !a) return;
  const lmName = st.id === 'lm' && st.lm ? ': ' + st.lm.label : '';   // the landmark to tap, in full (the prompt may be a chip)
  s.textContent = `${S.kc ? KC_NAME[S.kc.active] + ' · ' : ''}${ci + 1}/${STEPS.length} ${STEPS[ci].strip}${lmName}`; s.title = st.text || '';
  a.textContent = '';
  // a greyed button (a landmark armed: "Click it on the X-ray") is left out: the row names the landmark in full instead
  if (nb && !nb.disabled) { const b = btn(nb.textContent, () => nb.click(), 'primary', false, nb.title); b.id = 'sbBtn'; a.appendChild(b); }
  $('pcol').classList.toggle('ask', !nb || !!S.restoreNote);
  warnBadge(R);
}
function panelBody(P_) {
  const add = n => P_.appendChild(n);
  // where we are, the next thing to do, and a way back
  const st = stepNow(), cur = st.id, ci = STEPS.findIndex(s => s.id === cur);
  const recNow = S.wf === 'hip' && (cur === 'tpl' || cur === 'save') ? recommendStem() : null;   // first: it runs the rules on a copy of the plan
  if (S.segStep !== cur) { S.segStep = cur; S.segHand = {}; if (promptQuiet) { promptQuiet = false; if ($('prompt').textContent) promptMode('line'); } PJ.stepChanged(); }   // a new step: its segment opens, the others close (and, on t.addisortho.com, the plan is saved)
  let R = []; try { R = results(); } catch (e) { R = [['Results', `could not be worked out (${e.message || e})`, 'bad']]; }
  const steps = el('<div class="steps"></div>');
  STEPS.forEach((s, i) => {
    // the current step by name; the others by a tick or their number (and their name where there is room: a phone)
    const b = el(`<button class="${i < ci ? 'done' : i === ci ? 'cur' : ''}" aria-label="Step ${i + 1}, ${s.strip}${i < ci ? ', done' : ''}" title="${s.title}">${i < ci ? '<span class="mk">✓</span>' : i > ci ? `<span class="mk">${i + 1}</span>` : ''}<span class="nm">${s.strip}</span></button>`);
    if (i === ci) b.setAttribute('aria-current', 'step');
    if (i < ci && s.id === 'cal') { b.title = 'Check the calibration again; landmarks and implants are kept'; b.onclick = recalibrate; }
    else if (i < ci && s.id === 'prep') { b.title = 'Erase, crop or level the film again; nothing is removed'; b.onclick = () => { S.prepDone = false; save(); nextStep(); }; }
    else if (i < ci && s.id === 'lm') { b.title = 'Go back to the landmarks; nothing is removed'; b.onclick = () => { S.segHand.lm = true; const k = (S.lmSeq || []).filter(x => S.lm[x]).pop() || lmDefs()[0].key; armLm(k); }; }
    else if (i < ci && s.id === 'tpl') { b.title = 'Show the implants'; b.onclick = () => { S.segHand.tpl = true; panel(); }; }
    else b.disabled = i > ci;
    steps.appendChild(b);
  });
  add(steps);
  add(ctxBox());
  if (S.wfAsk && !S.kc && S.wf === 'hip' && !Object.keys(S.lm).length) add(wfAskRow());   // r43 (DIAG #11)
  if (S.ll) add(llCard());   // r44
  if (S.kc) add(kcTabs());   // the knee case's films: AP · Lateral · Skyline · Long leg, each done, in progress or missing
  else if (!S.ll && (S.wf === 'kneeAP' || S.wf === 'kneeLat') && (S.wfs.includes('kneeAP') || S.wfs.includes('kneeLat'))) { const o = el('<div class="row kcoffer"></div>'); o.appendChild(btn('Knee case: add lateral, skyline, long leg', kcStart, '', false, 'Make this X-ray the first film of a knee case and add the patient\'s other films (one patient, one knee)')); add(o); }
  const nx = el(`<div class="next"><span>${esc(S.auto.busy ? 'Looking for the calibration ball and the R/L marker…' : st.text)}</span></div>`);
  if (st.side) {
    nx.appendChild(caseTypeSel(true));   // asked with the side (revision and conversion, amendment 1 of the design): Primary unless changed
    const pick = (sd, sc) => () => { S.side = sd; S.sideSet = true; llSideMoved(); if (sc) S.scope = sc; $('side').value = sd; refreshFlips(); save(); nextStep(); };
    if (S.scope) nx.append(btn('Right', pick('R'), 'primary'), btn('Left', pick('L'), 'primary'));
    else { const g = el('<div class="choices"></div>'); g.append(btn('Right, both hips on the film', pick('R', 'both'), 'primary'), btn('Left, both hips on the film', pick('L', 'both'), 'primary'), btn('Right hip only on the film', pick('R', 'one')), btn('Left hip only on the film', pick('L', 'one'))); nx.appendChild(g); }
  }
  if (st.ref) {
    const g = el('<div class="choices"></div>'), set = r => () => { S.ref = r; S.arm = null; ensureSlots(); refreshFlips(); save(); nextStep(); };
    g.append(btn(`Yes: template the ${SIDE[S.side]} hip`, set('own'), 'primary', false, 'A good film of this hip: cup and stem from its own head, socket and canal'),
      btn(`No: template the ${SIDE[other(S.side)]} side`, set('other'), 'primary', false, `This hip is fractured, shortened, rotated or destroyed: template the normal ${SIDE[other(S.side)]} side and copy its plan across`));
    nx.appendChild(g);
  }
  if (st.prep) nx.appendChild(prepTools(true));
  if (st.confirmBall) {
    const yes = () => { S.cal.confirmed = true; save(); nextStep(); }, n = S.auto.balls.length;
    nx.append(btn(`Yes, the ${S.ballD} mm ball`, yes, 'primary'));
    if (n > 1) nx.append(btn('No: next one', () => { S.auto.ballIdx = (S.auto.ballIdx + 1) % n; setBall(S.auto.balls[S.auto.ballIdx], 'found automatically', true); nextStep(); }));
    nx.append(btn('No: I will click it', () => { S.arm = { kind: 'ball' }; prompt('Click in the middle of the calibration ball.'); panel(); }));
  }
  if (st.confirmOrient) {
    const set = mir => () => { S.mirrored = mir; S.orient = { ...S.orient, prevFrom: S.orient.from, from: 'user', note: `confirmed: patient's right on the image ${mir ? 'right (mirrored)' : 'left (standard)'}` }; refreshFlips(); save(); nextStep(); };
    if (S.orient.from === 'marker') nx.append(btn('Yes', set(S.mirrored), 'primary'), btn('No, the other way', set(!S.mirrored)));
    else nx.append(btn('Yes, standard', set(false), 'primary'), btn('No, mirrored', set(true)));
  }
  // the step's one button, always in the same place (Undo and Fit are in the header: Sami, 13 Oct)
  const nb = st.side || st.ref || st.prep || st.confirmBall || st.confirmOrient ? null : cur === 'cal' ? btn('Find ball', () => { S.arm = { kind: 'ball' }; prompt('Click in the middle of the calibration ball.'); panel(); }, 'primary')
    : cur === 'lm' ? btn(S.arm && S.arm.kind === 'lm' && S.arm.key === st.lm.key ? 'Click it on the X-ray' : 'Mark it', () => armLm(st.lm.key), 'primary', S.arm && S.arm.kind === 'lm' && S.arm.key === st.lm.key)
    : cur === 'tpl' ? btn('Auto template', autoTemplate, 'primary') : btn('Save picture', exportPNG, 'primary');
  const bk = backTarget(), row = el(`<div class="nav${S.auto.busy || !nb ? ' solo' : ''}"></div>`);   // solo: Back beside the answers
  if (!S.auto.busy && nb) row.appendChild(nb);
  sheetRow(st, ci, S.auto.busy ? null : nb, R);
  row.appendChild(btn('◀ Back', goBack, 'back', !bk, bk ? `Back one step: ${bk.what}` : 'Nothing to go back to'));
  nx.appendChild(row);
  if (cur === 'tpl' && !S.auto.busy) { const br = brandRow(); if (br) nx.appendChild(br); const cc = choiceCard('next', recNow); if (cc) nx.appendChild(cc); }   // (r38: Total hip or Bipolar is on the choice card)   // under the step's button, so it stays in view
  add(nx);
  // warnings that must not hide in a closed segment: the serious results (kept up to date while dragging, by drawNow);
  // screen readers hear them from #srStatus, the one live status outside the panel (round 11 review)
  const wb = el('<div class="badbox" id="warns"></div>'); add(wb);
  setHTML('warns', R.filter(r => r.length > 1 && r[2] === 'bad').map(r => `<div>${esc(r[0])}: ${esc(r[1])}</div>`).join(''));
  const ws = el('<div class="warnsum" id="warnsum"></div>'); add(ws);   // the amber ones, at every height of the phone's panel (finding 8)
  ws.addEventListener('click', e => { if (e.target.closest('.wsbtn')) showChecks(); });
  setHTML('warnsum', warnSummary(R));
  if (S.restoreNote) { const wbx = el(`<div class="warnbox keep">${esc(S.restoreNote)}</div>`); if (S.oldCupRule && S.wf === 'hip' && Object.keys(S.tpl).length) { const r_ = el('<div class="row"></div>'); r_.appendChild(btn(REPLAN_CUP, replanCupRule, 'primary', false, REPLAN_TIP)); wbx.appendChild(r_); } add(wbx); }   // keep: shown at the phone panel's smallest too
  if (kneeCardOn()) add(kneeCard(R, cur));
  if (S.wf === 'diaph' && !S.kc) add(diaCard(R, cur));   // r39: the diaphyseal plan card, with the order request   // the knee plan card (Sami, 23 Oct): the nine lines, above the steps' segments
  const sum = segSummaries(R);

  // 1. calibration and orientation
  const s1 = seg('cal', cur, ...sum.cal);
  const a1 = n => s1.appendChild(n);
  if (S.kc) a1(kcFilmCard());   // this film's role, its side and quality checks, where its scale comes from
  if (S.cal && S.cal.method === 'ball' && S.cal.how === 'found automatically') {
    const box = el(`<div class="${S.cal.confirmed ? 'okbox' : 'warnbox'}">Ball found automatically${S.cal.confirmed ? ' and confirmed' : ': not yet confirmed (answer at the top)'}. The pink circle should sit on its rim. </div>`);
    const nAlt = S.auto.balls.length;
    if (nAlt > 1) box.appendChild(btn('Not the ball: next one', () => { S.auto.ballIdx = (S.auto.ballIdx + 1) % nAlt; setBall(S.auto.balls[S.auto.ballIdx], 'found automatically', true); nextStep(); }));
    box.appendChild(btn('Not the ball: click it', () => { S.arm = { kind: 'ball' }; prompt('Click in the middle of the calibration ball.'); panel(); }));
    a1(box);
  } else if (!S.auto.busy && S.set.autoFind && (!S.cal || calEst(S.cal)) && !ctFilmOf(S.img.meta) && S.auto.balls.length === 0 && S.img.data) a1(el('<p class="note">No ball was found automatically on this film: click "Find ball" and click it, or mark its rim by hand.</p>'));
  if (ctBoxOn(S.img.meta)) a1(ctCalBox());   // r43: the CT scout's own scale, or why it has none
  const r1 = el('<div class="row"></div>');
  r1.appendChild(btn('Find ball', () => { S.arm = { kind: 'ball' }; prompt('Click in the middle of the calibration ball.'); panel(); }, S.arm && S.arm.kind.startsWith('ball') ? 'on' : ''));
  const bd = el(`<label>diameter <input type="number" step="0.1" min="5" max="100" value="${S.ballD}"> mm</label>`);
  bd.querySelector('input').onchange = e => { const v = parseFloat(e.target.value); if (!(v >= 10 && v <= 60)) { prompt(`A calibration ball of ${e.target.value} mm is not plausible: enter its diameter in mm (usually 25).`); e.target.value = S.ballD; return; } S.ballD = v; if (S.cal && S.cal.method === 'ball') S.cal.mmPerPx = v / (2 * S.cal.ball.r); S.canal = {}; prompt(''); scaleChanged(); save(); panel(); draw(); };
  r1.appendChild(bd);
  r1.appendChild(btn('Mark rim by hand', () => { S.arm = { kind: 'ball3', pts: [] }; prompt('Ball rim: click 3 points spread round the edge of the ball.'); panel(); }, S.arm && S.arm.kind === 'ball3' ? 'on' : ''));
  a1(r1);
  const r2 = el('<div class="row"></div>');
  r2.appendChild(btn('Known length', () => { S.arm = { kind: 'calline', pts: [] }; prompt('Known length: click the first end.'); panel(); }, S.arm && S.arm.kind === 'calline' ? 'on' : ''));
  const est = dicomEstimate();
  r2.appendChild(btn(S.kc ? 'Use DICOM pixel spacing' : 'Use machine estimate', () => { S.cal = { method: 'dicom', mmPerPx: est.mmPerPx, note: est.note }; afterCal(); }, '', !est || !!ctScoutOf(S.img.meta) || !!ctNoScaleWhy()));
  a1(r2);
  if (calEst(S.cal)) a1(el(`<div class="warnbox">Not calibrated to the joint: ${esc(S.cal.note)}. Sizes may be wrong by 10-20 %. Find the ball before planning.</div>`));
  if (!S.cal && !ctBoxOn(S.img.meta)) a1(el('<div class="warnbox">No scale. Find the ball or mark a known length.</div>'));
  if (S.cal && S.cal.method === 'ball' && S.cal.warnings && S.cal.warnings.length) a1(el(`<div class="warnbox">Ball: ${esc(S.cal.warnings.join('; '))}.</div>`));
  const ro = el('<div class="row"></div>');
  const cr = el(`<label class="note">Patient's head is towards the <select><option value="up">top (standard)</option><option value="down">bottom</option><option value="left">left</option><option value="right">right</option></select></label>`);
  cr.querySelector('select').value = S.cranial;
  cr.querySelector('select').onchange = e => { S.cranial = e.target.value; S.orient = { ...S.orient, from: 'user', note: 'set by hand' }; refreshFlips(); save(); panel(); draw(); };
  const sel = el(`<label class="note">and the patient's right is <select><option value="0">on the image left (standard)</option><option value="1">on the image right (mirrored)</option></select></label>`);
  sel.querySelector('select').value = S.mirrored ? '1' : '0';
  sel.querySelector('select').onchange = e => { S.mirrored = e.target.value === '1'; S.orient = { ...S.orient, from: 'user', note: 'set by hand' }; refreshFlips(); save(); panel(); draw(); };
  ro.append(cr, sel); a1(ro);
  if (S.wf === 'hip') {
    const sc = el(`<label class="note">The film shows <select><option value="">(not set)</option><option value="both">both hips</option><option value="one">one hip</option></select></label>`);
    sc.querySelector('select').value = S.scope || ''; sc.querySelector('select').onchange = e => { S.scope = e.target.value || null; S.arm = null; save(); panel(); draw(); };
    a1(sc);
  }
  if (S.orient.from === 'marker') a1(el(`<div class="warnbox">Read from the film: ${esc(S.orient.note)}. Not yet confirmed (answer at the top).</div>`));
  else if (S.orient.from === 'assumed') a1(el(`<div class="warnbox">${S.orient.note ? esc(cap(S.orient.note)) + '.' : 'The image does not say which way it faces and no R/L marker was read.'} A standard film is assumed until you confirm (answer at the top).</div>`));
  const calHow = [`The ${S.ballD} mm ball taped at the level of the joint sets the scale. It is looked for automatically when the X-ray opens; the machine's own scale is only an estimate (bones look 10-20 % bigger on the detector).`];
  if (S.cal && S.cal.method === 'ball') calHow.push(`Ball ${S.cal.how === 'found automatically' ? 'found automatically' : S.cal.how === 'auto' ? 'found from your click' : 'from 3 rim points'}: ${f1(2 * S.cal.ball.r)} px across${S.cal.rMajor && S.cal.rMajor - S.cal.ball.r > 0.05 ? ` (and ${f1(2 * S.cal.rMajor)} px the long way: a ball off the beam centre casts an oval; the short width is used)` : ''}${S.cal.model && S.cal.model !== 'edge' ? ` (sized from its shading as a ${S.cal.model})` : ''}.`);
  if ((S.img.meta || {}).format === '8-bit picture') calHow.push('This is an 8-bit picture (JPEG or PNG): fine for templating, but a DICOM or 16-bit PNG keeps the full grey range for bone detail. Pictures carry no scale: calibrate on the ball.');
  if (S.img.resampleNote) calHow.push(`This image's ${S.img.resampleNote} so lengths and angles are true.`);
  if (S.orient.from === 'header' || S.orient.from === 'user') calHow.push(`Orientation ${S.orient.note || 'set by hand'}.`);
  s1.appendChild(help(calHow.map(t => `<p>${esc(t)}</p>`).join('')));
  add(s1);

  // 2. prepare: clean, crop and level the film
  const s4 = seg('prep', cur, ...sum.prep);
  const nS = S.clean.strokes.length;
  s4.appendChild(prepTools(false));
  const rc2 = el('<div class="row"></div>');
  rc2.appendChild(btn(S.showOriginal ? 'Showing original ✓' : 'Show original', () => { S.showOriginal = !S.showOriginal; renderImage(); panel(); draw(); }, S.showOriginal ? 'on' : '', !nS, 'Compare with the film as it came'));
  rc2.appendChild(btn('Clear all strokes', () => { S.clean = { ...S.clean, strokes: [] }; rebuildClean(); save(); panel(); draw(); }, '', !nS));
  if (lmGeom('tdR') && lmGeom('tdL')) rc2.appendChild(btn('Level on the teardrops', levelOnTeardrops, '', false, 'Turn the film so the teardrop line is level'));
  s4.appendChild(rc2);
  const rc3 = el('<div class="row"></div>');
  rc3.appendChild(btn('Black out an area', () => { S.arm = { kind: 'mask', pts: [] }; prompt('Black out: click one corner of the area to cover (a name or number burned into the film), then the opposite corner.'); }, S.arm && S.arm.kind === 'mask' ? 'on' : '', false, 'Cover text burned into the film on the saved picture'));
  rc3.appendChild(btn('Clear black-outs', () => { S.masks = []; save(); draw(); panel(); }, '', !(S.masks && S.masks.length)));
  s4.appendChild(rc3);
  s4.appendChild(existCard());   // existing implants: the case type, proposals to confirm, the brush, the outline, "Not an implant"
  if (nS || S.clean.crop) s4.appendChild(el(`<p class="note">${nS ? `${nS} area${nS > 1 ? 's' : ''} filled from the film around ${nS > 1 ? 'them' : 'it'}. ` : ''}${S.clean.crop ? 'The saved picture is cropped to the bright area. ' : ''}Measurements always use the original film; the saved report says what was cleaned.</p>`));
  s4.appendChild(help('<p>Erase removes things that are not anatomy (ECG leads and wires, clips, positioning aids, stray labels) by filling each brush stroke from the film around it. Use it only away from the bone you are planning on: a filled area is a guess, not anatomy. The ball and the R/L marker are found on the original film, so erasing them changes nothing measured.</p><p>Crop keeps only the area you choose on the saved picture. Black out covers a name or number burned into the film with solid black (for sharing).</p>'));
  add(s4);

  // 3. landmarks
  const s2 = seg('lm', cur, ...sum.lm);
  if (S.wf === 'hip' && S.scope === 'both' && S.ref) {
    const rr = el(`<label class="note">Template on <select><option value="own">the ${SIDE[S.side]} hip itself (a good film of it)</option><option value="other">the normal ${SIDE[other(S.side)]} side, copied across (${SIDE[S.side]} fractured, rotated or deformed)</option></select></label>`);
    rr.querySelector('select').value = S.ref; rr.querySelector('select').onchange = e => { S.ref = e.target.value; S.arm = null; ensureSlots(); save(); panel(); draw(); prompt(S.ref === 'other' ? `The ${SIDE[other(S.side)]} head and canal are now needed. Press Auto template when they are marked.` : ''); };
    s2.appendChild(rr);
  }
  const ul = el('<ul class="lm"></ul>');
  for (const d of lmDefs()) {
    const done = !!lmGeom(d.key), armed = S.arm && S.arm.kind === 'lm' && S.arm.key === d.key;
    const broken = lmBroken(d.key), prob = lmProblem(d.key);
    const li = el(`<li class="${armed ? 'armed' : ''}"><span class="tick">${prob || broken ? '!' : done ? '✓' : armed ? '›' : '·'}</span><span>${esc(d.label)}${d.optional && !neededHere(d) ? ' <span class="opt">optional</span>' : ''}${broken ? ' <span class="opt bad">points too close or in a line: place again</span>' : ''}${prob ? ` <span class="opt bad">${esc(prob)}</span>` : ''}</span></li>`);
    li.onclick = () => armLm(d.key);
    if (S.lm[d.key] && revCase() && refChoices(d.key)) {   // revision and conversion: what this reference is (amendment 4)
      const rs = el(`<select class="refsel" aria-label="${esc(d.label)}: what this reference is">${refChoices(d.key).map(v => `<option value="${v}">${REF_WORD[v]}</option>`).join('')}</select>`);
      rs.value = refLabel(d.key); rs.onclick = ev => ev.stopPropagation(); rs.onchange = () => setRef(d.key, rs.value); li.appendChild(rs);
    }
    if (S.lm[d.key]) { const x = btn('✕', ev => { ev.stopPropagation(); delete S.lm[d.key]; S.lmSeq = (S.lmSeq || []).filter(k => k !== d.key); save(); panel(); draw(); }, 'x', false, 'Remove this landmark'); x.setAttribute('aria-label', `Remove ${d.label}`); li.appendChild(x); }
    ul.appendChild(li);
    const gd = GUIDE[d.g || d.key];
    if (armed && gd) ul.appendChild(el(`<li class="guide"><div>${gd.svg(d.key.slice(-1) === 'L' && S.wf === 'hip' ? 'L' : 'R', S.mirrored)}</div><p>${esc(gd.text)}</p></li>`));
    const cn = S.notes[d.key]; if (cn && /^canal[RL]$/.test(d.key) && done) ul.appendChild(el(`<li class="sub">${esc(cn)}</li>`));
    if (/^canal[RL]$/.test(d.key) && done && (revCase() || exActive().length)) {   // the canal checked against the marked implant (amendment 2)
      const sd = d.key.slice(-1), fl = canalFlag(sd), tr = canalTraceFor(sd), li2 = el(`<li class="sub">${fl ? `<span class="opt bad">${esc(cap(fl))}</span>` : ''}</li>`);
      li2.append(btn('Use the cortical pairs', () => canalUsePairs(sd), 'primary', !(tr && tr.axis), tr && tr.axis ? `Centre the line on the ${tr.n} cortical pairs found on the bone outside the marked implant` : 'No usable cortical pairs outside the marked implant'), btn('Keep my line', () => canalKeep(sd), '', false, 'Keep the line as you placed it'));
      ul.appendChild(li2);
    }
  }
  s2.appendChild(ul);
  if (S.wf === 'kneeAP' && !lmGeom('hip')) {
    const pvv = pvGet('vca'), rv = el(`<div class="row note">No hip on the film: femoral valgus cut angle <input type="number" step="0.5" min="0" max="15" aria-label="Femoral valgus (no hip on the film)" value="${pvv.v}">°</div>`);
    rv.querySelector('input').onchange = e => { const t = e.target.value.trim(), v = parseFloat(t); if (t === '') pvSet('vca', null); else if (Number.isFinite(v) && v >= 0 && v <= 15) { if (Math.abs(v - pvGet('vca').v) >= 0.005) pvSet('vca', v); } else e.target.value = pvGet('vca').v; save(); panel(); draw(); }; s2.appendChild(rv);
    rv.appendChild(el(`<span class="opt">${esc(pvLab(pvv))}</span>`));
  }
  s2.appendChild(help(`<p>${esc(WF[S.wf].label)}. The next landmark is asked for in turn; click any line to mark it again, or press ◀ Back to go to the one before. Drag any yellow point to correct it.</p>`
    + (S.wf === 'hip' && refOther() ? `<p>The ${SIDE[S.side]} hip is planned from the ${SIDE[other(S.side)]} one: its head and canal are the reference, and its plan is mirrored about the pelvic midline (halfway between the teardrops). The ${SIDE[S.side]} canal is still needed to size the stem, and both lesser trochanters for leg length.</p>` : '')));
  add(s2);

  // 4. implants
  const s3 = seg('tpl', cur, ...sum.tpl);
  if (S.wf === 'diaph') { s3.appendChild(el('<p class="note">No implant is placed on a diaphyseal plan: it measures the film for a Chunli order request (the Diaphyseal plan card above). Chunli confirms the configuration.</p>')); add(s3); } else {   // r39
  if (!mm()) s3.appendChild(el('<p class="note">Calibrate first: implants are drawn true size.</p>'));
  if (cur !== 'tpl') { const br = brandRow(); if (br) s3.appendChild(br); }   // r36: the maker, where the step's own card is not
  const ra0 = el('<div class="row"></div>');
  ra0.appendChild(btn('Auto template', autoTemplate, 'primary', !mm(), 'Place and size the implants by the rules; then fine-tune by hand'));
  if (S.wf === 'hip') { const H_ = hipFrame(), why = !mm() ? 'Calibrate first' : S.scope === 'one' ? 'Needs both hips on the film' : !H_ ? 'Mark both teardrops first' : ''; ra0.appendChild(btn(`Mirror ${SIDE[other(S.side)]} side onto ${SIDE[S.side]}`, () => mirrorOther(), '', !!why, why || `Plan the surgery side to match the ${SIDE[other(S.side)]} hip, reflected about the pelvic midline`)); }
  if (Object.keys(S.tpl).length) ra0.appendChild(btn('Remove implants', () => { S.tplAll[S.wf] = S.tpl = {}; S.active = null; S.mirror = null; save(); panel(); draw(); prompt('Implants removed; landmarks kept. Press Auto template to place them again.'); }, '', false, 'Keep the landmarks, start the implants again'));
  s3.appendChild(ra0);
  if (S.mirror && S.wf === 'hip') s3.appendChild(el(`<p class="note">Mirrored: ${esc(S.mirror.note)}.</p>`));
  if (cur === 'save') { const cc = choiceCard('seg', recNow); if (cc) s3.appendChild(cc); }
  if (S.wf === 'kneeAP' || S.wf === 'kneeLat') {   // the knee's plan values with their origins, reset and reference (also on the knee plan card): folded
    const pe = el('<details class="pvd"><summary>Plan values: origins, reset, tibial reference</summary></details>'); pe.open = !!S.segHand.pvd;
    pe.querySelector('summary').addEventListener('click', () => { S.segHand.pvd = !pe.open; }); pe.appendChild(kneeEntries()); s3.insertBefore(pe, s3.firstChild);
  }
  if (revCase() && S.wf === 'hip') s3.insertBefore(revCard(), s3.firstChild);   // revision and conversion: fixation zone, old tip, holes, osteotomy, cup target
  if (revCase() && S.wf === 'kneeAP') s3.insertBefore(jlCard(), s3.firstChild);   // the revision joint line's target and its evidence
  s3.appendChild(settingsBox());
  const groups = S.wf === 'hip' ? [[`Surgery side: ${SIDE[S.side]}`, ['cup' + S.side, 'stem' + S.side]], [`Other side: ${SIDE[other(S.side)]} (${refOther() ? 'the reference plan' : 'for comparison and mirroring'})`, ['cup' + other(S.side), 'stem' + other(S.side)]]] : [['', WF[S.wf].slots]];
  for (const [title, slots] of groups) {
    if (title) s3.appendChild(el(`<h3>${esc(title)}</h3>`));
    const hsd = S.wf === 'hip' ? slotSide(slots[0]) : null;   // r38: Total hip or Bipolar for this hip; a bipolar's shell under its stem, no cup
    if (hsd) { const hr = hemiRow(hsd); if (hr) s3.appendChild(hr); }
    for (const slot of slots) {
      if (hsd && hemiOn(hsd) && SLOT[slot].kind === 'cup') continue;
      s3.appendChild(templateBox(slot));
      if (hsd && hemiOn(hsd) && SLOT[slot].kind === 'stem') s3.appendChild(bipBox(hsd));
    }
  }
  s3.appendChild(help('<p>Drag an implant to move it, the white handle to turn it; the bar above the panel works on the selected one. Keys, with the X-ray selected: <kbd>↑</kbd>/<kbd>↓</kbd> size, <kbd>Shift</kbd>+arrows move 0.5 mm (<kbd>Shift</kbd>+<kbd>Alt</kbd> 2 mm), <kbd>,</kbd> <kbd>.</kbd> turn 0.5°, <kbd>F</kbd> fit, <kbd>Ctrl</kbd>/<kbd>⌘</kbd>+<kbd>Z</kbd> undo; right-drag: brightness and contrast.</p>', 'Moving implants by hand'));
  add(s3); }   // r39: the implant segment of every other plan

  // 5. measurements and save (the recommendation at its top)
  const s5 = seg('save', cur, ...sum.save);
  const rc = recommendation(st); if (rc) s5.appendChild(rc);
  if (STANDALONE) s5.appendChild(identCard());   // the patient: hospital, full name, age, record number (Sami, 15 Oct)
  s5.appendChild(el('<h3>Measurements</h3>'));
  if (kneeCardOn()) {   // a knee film: the full results under Details (the knee plan card above has the plan's lines)
    const dd = el('<details class="resd" id="resd"><summary>Details: alignment, calibration, coverage, sources</summary><div id="res"></div></details>');
    dd.open = !!S.segHand.resd; dd.querySelector('summary').addEventListener('click', () => { S.segHand.resd = !dd.open; }); s5.appendChild(dd);
  } else s5.appendChild(el('<div id="res"></div>'));
  const wr = el(`<div class="wl"><label>Brightness <input id="wlB" type="range" min="0" max="100" step="1"></label><label>Contrast <input id="wlC" type="range" min="0" max="100" step="1"></label></div>`);
  s5.appendChild(wr);
  const fromSl = () => { const w = wlFromSliders(+wr.querySelector('#wlB').value, +wr.querySelector('#wlC').value); setWL(w.c, w.w); };
  wr.querySelector('#wlB').oninput = fromSl; wr.querySelector('#wlC').oninput = fromSl;
  wr.querySelector('#wlB').onchange = wr.querySelector('#wlC').onchange = () => { save(); panel(); };
  const rw = el('<div class="row"></div>');
  rw.appendChild(btn(S.wlAuto ? 'Auto contrast ✓ (undo)' : 'Auto contrast', () => {
    if (S.wlAuto) { S.wl = { ...(S.wlPrev || S.wlFile) }; S.wlAuto = false; }
    else { S.wlPrev = { ...S.wl }; autoWindow(); S.wlAuto = true; }
    renderImage(); save(); panel(); draw();
  }, S.wlAuto ? 'on' : '', false, 'Stretch the grey range from the 1st to the 99th percentile; click again to go back'));
  rw.appendChild(btn('As in the file', () => { S.wl = { ...S.wlFile }; S.wlAuto = false; renderImage(); save(); panel(); draw(); }, '', false, 'The window stored in the image'));
  rw.appendChild(btn(S.invertView ? 'Invert ✓' : 'Invert', () => { S.invertView = !S.invertView; renderImage(); panel(); draw(); }, S.invertView ? 'on' : ''));
  s5.appendChild(rw);
  const rr = el('<div class="row"></div>');
  rr.appendChild(btn(S.labels ? 'Hide labels' : 'Show labels', () => { S.labels = !S.labels; panel(); draw(); }));
  { const ph = linesAuto(), on = ph ? !S.linesOn : S.cleanView;   // a phone after the landmarks: the same switch as Lines on the film
    rr.appendChild(btn(on ? 'Clean view ✓' : 'Clean view', () => { if (ph) { S.linesOn = !S.linesOn; syncLines(); } else S.cleanView = !S.cleanView; panel(); draw(); }, on ? 'on' : '', false, ph ? 'Only the plan on the film (the Lines button on the film is the same switch)' : 'Only the implants, the neck cut and the teardrop line, as on the saved picture')); }
  rr.appendChild(btn('Ruler', () => { S.arm = { kind: 'ruler', pts: [] }; prompt('Ruler: click the first end.'); }));
  rr.appendChild(btn('Clear rulers', () => { S.rulers = []; save(); draw(); }, '', !S.rulers.length));
  rr.appendChild(btn('Angle', () => { S.arm = { kind: 'angle', pts: [] }; prompt('Angle: click the end of the first arm.'); }, '', false, 'Three clicks: an arm end, the vertex, the other arm end (r36)'));
  rr.appendChild(btn('Clear angles', () => { S.angles = []; save(); draw(); panel(); }, '', !(S.angles || []).length));
  s5.appendChild(rr);
  const rp = el(`<div class="row note">Saved picture <select><option value="clean">clean: implants, neck cut, teardrop line</option><option value="full">with every reference line</option></select></div>`);
  rp.querySelector('select').value = S.set.picture || 'clean'; rp.querySelector('select').onchange = e => { S.set.picture = e.target.value; saveSet(); };
  s5.appendChild(rp);
  // the project (round 6, 13 Oct 2026; Sami: "save the project and upload project for easy adjustment"): here as well as in
  // the header, so a phone reaches it from this segment too; its two outcomes (this device, the patient list) under it
  const rj = el('<div class="row"></div>');
  rj.appendChild(btn('Save project', () => PJ.saveProject(), 'primary projsave', false, STANDALONE ? 'The X-ray and the whole plan, to this device and to the templated-patients list, to reopen and adjust later' : 'The X-ray and the whole plan in one file (.aotp), to reopen and adjust later'));
  s5.appendChild(rj);
  s5.appendChild(PJ.outcomeEl());
  s5.appendChild(el(`<p class="note"><b>Project</b> (.aotp${STANDALONE ? ', and the patient list' : ''}): the original X-ray and the whole plan, to reopen and adjust. Confidential: it keeps the patient's details from the X-ray, also in sharing mode, so it stays inside the team. <b>Picture</b> (PNG): the flattened copy for sharing.</p>`));
  const rx = el('<div class="row"></div>');
  rx.appendChild(btn('Save picture (PNG)', exportPNG, 'primary', false, 'A flattened picture of the plan, for sharing (in sharing mode without the name, record number and dates)'));
  rx.appendChild(btn('Save DICOM', exportDICOM, '', !!S.set.share, S.set.share ? 'Not in sharing mode: a DICOM carries the patient\'s details' : 'The same picture as a DICOM file, to keep or import elsewhere'));
  const fromArchive = !!((S.img.meta || {}).StudyInstanceUID && (S.img.meta || {}).SOPInstanceUID);
  if (!STANDALONE) rx.appendChild(btn(SEND, sendToPACS, 'primary', !fromArchive || !!S.set.share, S.set.share ? 'Not in sharing mode' : !fromArchive ? `Only for an X-ray from ${VIEWER ? 'the viewer' : 'the archive'} or a DICOM file` : VIEWER ? 'Put the plan picture into the viewer, in this X-ray\'s study (you confirm the patient first); the viewer can then send it to PACS' : 'Store the plan picture in this X-ray\'s study in the archive (you confirm the patient first)'));
  s5.appendChild(rx);
  if (S.pending) s5.appendChild(el(`<div class="warnbox">The last "${SEND}" got no answer: the plan may or may not be in ${DEST}. ${VIEWER ? 'Saving' : 'Sending'} again asks ${VIEWER ? 'the viewer' : 'the archive'} first.</div>`));
  if (S.sent) s5.appendChild(el(`<div class="okbox">${VIEWER ? 'Saved in the viewer' : 'Sent to PACS'} on ${S.sent.at.toLocaleDateString()} at ${S.sent.at.toTimeString().slice(0, 5)}: in this X-ray's study, next to the film. ${VIEWER ? 'Saving' : 'Sending'} again adds another copy.</div>`));
  const ry = el('<div class="row"></div>');
  ry.appendChild(btn('Save plan data', exportJSON, '', !!S.set.share, S.set.share ? 'Not in sharing mode: plan data carries the record and order numbers' : 'Landmarks, implants and results as a data file, to reopen this plan later on the same X-ray'));
  ry.appendChild(btn('Open plan data…', () => $('planFile').click()));
  if (STANDALONE) ry.appendChild(btn('Forget plans on this computer', () => { if (!confirm('Remove every templating plan kept in this browser?')) return; if (keepT) { clearTimeout(keepT); keepT = 0; } try { for (const k of Object.keys(localStorage)) if (k.startsWith('aoc-tpl:')) localStorage.removeItem(k); } catch (e) { /* nothing kept */ } HIST.length = 0; REDO.length = 0; syncHeader(); prompt('Plans kept in this browser removed (the one on screen stays until you close the page).'); }, '', false, 'Plans are kept in this browser so a film can be reopened; this removes them'));
  ry.appendChild(btn('Start again', () => { if (confirm('Remove all landmarks, implants and the calibration on this X-ray? (◀ Back and Undo go back one step without this.)')) { S.lm = {}; S.lmSeq = []; S.ref = null; S.tplAll = { hip: {}, kneeAP: {}, kneeLat: {} }; S.tpl = S.tplAll[S.wf] || (S.tplAll[S.wf] = {}); S.hidden = {}; S.hiddenGeo = {}; S.restoreNote = S.restoreNote.replace(/ ?This plan also had [^:]*: not shown\./, '').trim(); S.rulers = []; S.angles = []; S.active = null; S.canal = {}; S.notes = {}; S.mirror = null; S.rv = {}; defaultCalibration(); save(); panel(); draw(); } }));
  s5.appendChild(ry);
  if (S.set.share) s5.appendChild(el(`<div class="warnbox">Sharing mode: the name, record number and dates are left out of the file names, the picture and the patient list, and DICOM, plan data and the project file are not saved to this device (a project file would hold the original X-ray, whose DICOM header can name the patient); the patient list keeps the case for the team without the name, age or record number. Text burned into the film itself (a name, a number, a date) is not removed: cover it with "Black out an area" (2 · Prepare) and look at the picture before sending it.</div>`));
  s5.appendChild(help(`<p>Files are named ${esc(baseName())}${S.set.share ? '' : ' (tick "sharing outside the hospital" in Auto plan settings before sending a plan outside)'}. The project file (.aotp) holds the original X-ray and the whole plan: open it with Open to go on where you left off${STANDALONE ? '; it is also kept in the templated-patients list (Open), for you and the colleagues you share it with' : ''}. It is confidential, like the X-ray itself. The picture (PNG) is the copy for sharing. The DICOM copy carries the patient's details from the X-ray, for the archive. Plan data is for reopening a plan here; it is not a document to read. A plan is a draft for the surgeon.</p>`, 'About the files'));
  add(s5);
  if (S.kc) add(kcCaseCard());   // the case's shared plan, the surgeon's values and the patellofemoral assessment
  PJ.sync();
  setHTML('res', resTable(R));
  syncWL();
}

// The patient's details for the templated-patients list and the file names (Sami, 15 Oct 2026: "use their full name along
// with hospital name's initial and age, MRN and name for record, use the hospital initial e.g SSC-MRN for each hospital"):
// the hospital picked from projects.js HOSPITALS (filled from the X-ray's InstitutionName when it names one on the list) or
// typed as its initials, the full name, the age (from the X-ray, editable) and the record number, shown with the hospital's
// code in front. Sharing mode keeps the name, age and record number out of the list and the files: only the hospital stays.
function identCard() {
  const id = identNow(), share = !!S.set.share, d = el('<div class="ident" id="identCard"><div class="kch">Patient</div></div>');
  const hl = el('<label class="note">Hospital <select aria-label="Hospital"><option value="">(not set)</option></select></label>'), hs = hl.querySelector('select');
  for (const h of PJ.HOSPITALS) hs.add(new Option(`${h.code} · ${h.name}`, h.code));
  if (id.hospital && !PJ.HOSPITALS.some(h => h.code === id.hospital)) hs.add(new Option(id.hospital, id.hospital));
  hs.add(new Option('Another hospital: type its initials', '*'));
  hs.value = id.hospital || '';
  const other = el('<label class="note" hidden>Initials <input type="text" autocomplete="off" aria-label="Hospital initials" placeholder="e.g. SSC"></label>'), oi = other.querySelector('input');   // (no maxlength on identity: never cut, checked whole; Astra, round 4)
  const changed = () => { save(); panel(); draw(); };
  hs.onchange = () => { if (hs.value === '*') { other.hidden = false; oi.focus(); return; } setIdent({ hospital: hs.value }); if (S.kc) S.kc.pt.asked = true; changed(); };
  oi.onchange = () => { const v = oi.value.trim().toUpperCase(); if (!PJ.HOSP_RE.test(v)) { prompt('Hospital initials: 2 to 6 capital letters (for example SSC).'); return; } setIdent({ hospital: v }); changed(); };
  d.append(hl, other);
  const txt = (label, key, max, val, more = '') => { const l = el(`<label class="note">${esc(label)} <input type="text" autocomplete="off" aria-label="${esc(label)}"${share ? ' disabled' : ''}>${more}</label>`), i = l.querySelector('input'); i.value = share ? '' : val || ''; i.onchange = () => { setIdent({ [key]: i.value }); changed(); }; return l; };
  d.appendChild(txt('Full name', 'name', 120, id.name));
  const al = el(`<label class="note">Age <input type="number" min="0" max="${PJ.AGE_MAX}" step="1" aria-label="Age in years"${share ? ' disabled' : ''}> years</label>`), ai = al.querySelector('input');
  ai.value = share || !Number.isInteger(id.age) ? '' : String(id.age);
  ai.onchange = () => { const t = ai.value.trim(), n = t === '' ? null : Number(t); if (n !== null && !(Number.isInteger(n) && n >= 0 && n <= PJ.AGE_MAX)) { prompt(`Age: a whole number of years from 0 to ${PJ.AGE_MAX}, or leave it empty.`); ai.value = Number.isInteger(id.age) ? String(id.age) : ''; return; } setIdent({ age: n }); changed(); };
  d.appendChild(al);
  const core = PJ.mrnCore(id.hospital, id.recordNo);
  d.appendChild(txt('Record number', 'recordNo', 40, core, id.hospital && !share ? ` <span class="opt">stored as ${esc(PJ.normMrn(id.hospital, core) || `${id.hospital}-…`)}</span>` : ''));
  if (share) d.appendChild(el('<p class="note">Sharing mode: the name, age and record number are left out of the patient list, the file names and the pictures; only the hospital\'s initials stay.</p>'));
  else if (!id.hospital) d.appendChild(el('<div class="warnbox">Hospital not set: the case is listed under "Hospital not set" and not grouped with the patient\'s other cases until you pick the hospital (the patient is the hospital and the record number together).</div>'));
  else if (!core) d.appendChild(el('<p class="note">No record number: this case is listed on its own (cases are put together under one patient by hospital and record number only).</p>'));
  if (!share && id.name && id.name.length > PJ.NAME_MAX) d.appendChild(el(`<div class="badbox">Name too long: over ${PJ.NAME_MAX} characters, so the case cannot be saved to the patient list. Shorten it here (it is never cut automatically).</div>`));
  if (!share && core && !PJ.mrnOk(id.hospital, core)) d.appendChild(el(`<div class="badbox">Record number too long: with the hospital's code it is over ${PJ.MRN_MAX} characters, so the case cannot be saved to the patient list. Shorten it here (it is never cut automatically).</div>`));
  return d;
}
// The implant bar (round 6, 13 Oct 2026): above the panel, never over the film. − size +, turn and "Next implant" for the
// selected implant, so a phone needs no panel to size it (Sami: mobile templating as friendly as possible). Shown once
// implants are on the film; updated in place, so its buttons keep the keyboard focus.
function dock() {
  const d = $('dock'), ok = S.img && mm() && WF[S.wf].slots.some(sl => S.tpl[sl]);
  d.hidden = !ok || (PHONE.matches && !(S.active && S.tpl[S.active]));   // a phone: only with an implant selected (over the film)
  if (d.hidden) { const m = d.querySelector('.dmenu'); if (m) m.hidden = true; }
  if (!ok) return;
  if (!d.firstChild) {
    d.append(el('<span class="dname"></span>'),
      btn('−', () => dockDo(t => stepSize(S.active, -1)), '', false, 'Smaller size'), el('<select aria-label="Size"></select>'), btn('+', () => dockDo(t => stepSize(S.active, 1)), '', false, 'Larger size'),
      btn('⟲', () => dockDo(t => { t.angle -= G.rad(0.5); }, true), 'rot', false, 'Turn 0.5° anticlockwise'), btn('⟳', () => dockDo(t => { t.angle += G.rad(0.5); }, true), 'rot', false, 'Turn 0.5° clockwise'),
      el('<button class="next-imp" aria-label="Next implant" title="Select the next implant on the film">Next<span class="lg"> implant</span></button>'),
      el('<button class="dmore" aria-label="More for this implant" aria-haspopup="true" aria-expanded="false" title="Next implant, or hide this bar">⋯</button>'),
      el('<div class="dmenu" hidden></div>'));
    const [, mi, sz, pl, tl, tr] = d.children;
    mi.setAttribute('aria-label', 'Smaller size'); pl.setAttribute('aria-label', 'Larger size'); tl.setAttribute('aria-label', 'Turn anticlockwise'); tr.setAttribute('aria-label', 'Turn clockwise');
    sz.onchange = e => dockDo(() => setSize(S.active, e.target.value));
    d.querySelector('.next-imp').onclick = nextImplant;
    // a phone's ⋯ menu (round 11): Next implant, and Done (nothing selected: the bar goes, the film is all there)
    const more = d.querySelector('.dmore'), menu = d.querySelector('.dmenu');
    const close = () => { menu.hidden = true; more.setAttribute('aria-expanded', 'false'); };
    menu.append(btn('Next implant', () => { close(); nextImplant(); }, '', false, 'Select the next implant on the film'),
      btn('Done: hide this bar', () => { close(); S.active = null; panel(); draw(); }, '', false, 'Nothing selected; tap an implant to bring the bar back'));
    more.onclick = () => { menu.hidden = !menu.hidden; more.setAttribute('aria-expanded', String(!menu.hidden)); };
    addEventListener('pointerdown', e => { if (!menu.hidden && !e.target.closest('.dmenu, .dmore')) close(); }, true);
  }
  const [nm, mi, sz, pl, tl, tr] = d.children, t = S.active && S.tpl[S.active];
  nm.innerHTML = t ? `<b>${esc(shortSlot(S.active))}</b>${shortFam(S.fam[t.fam]) ? ` <span class="fam">${esc(shortFam(S.fam[t.fam]))}</span>` : ''}` : 'Tap an implant';
  for (const b of [mi, sz, pl, tl, tr]) b.disabled = !t;
  const opts = t ? sizesShown(S.fam[t.fam], t) : [], perm = t && S.active === 'tibia' ? kneePermitted() : null;   // Chunli's chart: an OK or a not compatible tray is said
  const key = t ? S.active + '|' + t.fam + '|' + opts.map(z => z.size).join(',') + '|' + (perm ? perm.femSize : '') : '';
  if (sz._key !== key) { sz.textContent = ''; for (const z of opts) sz.add(new Option(`${z.label || z.size}${perm && perm.yellow.includes(z.size) ? ' (OK)' : perm && perm.gray.includes(z.size) ? ' (not compatible)' : ''}`, z.size)); sz._key = key; }
  if (t) sz.value = t.size;
  sz.setAttribute('aria-label', t ? `Size of the ${slotName(S.active).toLowerCase()}` : 'Size'); sz.title = t ? slotName(S.active) : '';   // the bar's name is hidden on a short screen
}
// a change from the implant bar; turn: only the angle changed (the panel follows when the taps stop)
function dockDo(fn, turn = false) {
  const t = S.active && S.tpl[S.active]; if (!t || !mm()) return;
  fn(t); save(); if (turn) { panelSoon(); dock(); } else panel(); draw();
}

// The whole restorable state of the page (round 6 hook for the project file and the server copy; contract 13 Oct, §5):
// plan(), which already has the calibration, landmarks, crop, turn, eraser strokes, black-outs, rulers, window and the
// implants (with those kept aside), plus what it leaves out: inversion, labels, original or cleaned film, clean view, the
// saved picture's style and the notes; and (projects, 13 Oct) the note of a mirrored plan (a result reads it), the R/L
// markers read on the film (the saved picture's scale bar keeps clear of them) and whether the window is the automatic
// one. A copy: changing it changes nothing on the page. It does not validate anything.
export function fullState() {
  return JSON.parse(JSON.stringify({ ...plan(), display: { invert: !!S.invertView, labels: S.labels !== false, showOriginal: !!S.showOriginal, cleanView: !!S.cleanView, picture: S.set.picture || 'clean' }, notes: S.notes || {},
    mirror: S.mirror || null, markers: (S.auto.markers || []).slice(0, 4), wlAuto: !!S.wlAuto }));
}
// The same state put back on the film that is open, exactly: what applyPlan leaves to the film's header (the side chosen,
// one hip or both) is set from the state too. The caller checks it first (contract §5: format, version, image, counts,
// coordinates, scale; projects.js); this only applies it. The saved picture's style is used, not kept as this browser's.
function applyState(st) {
  applyPlan(JSON.parse(JSON.stringify(st)));
  const dsp = st.display || {};
  S.invertView = !!dsp.invert; S.labels = dsp.labels !== false; S.showOriginal = !!dsp.showOriginal; S.cleanView = !!dsp.cleanView;
  if (dsp.picture === 'clean' || dsp.picture === 'full') S.set.picture = dsp.picture;
  S.notes = st.notes && typeof st.notes === 'object' ? JSON.parse(JSON.stringify(st.notes)) : {};
  S.sideSet = !!st.sideSet; S.scope = st.scope || null; S.mirror = st.mirror ? { ...st.mirror } : null; S.wlAuto = !!st.wlAuto;
  S.auto = { busy: false, balls: [], ballIdx: 0, markers: Array.isArray(st.markers) ? JSON.parse(JSON.stringify(st.markers)) : [] };
  S.canal = {}; S.arm = null; S.active = null; S.segHand = {}; S.segStep = null;
}
export function applyFullState(st) {
  applyState(st);
  $('wf').value = wfSel(); $('side').value = S.side; ensureSlots(); renderImage(); fit();
  HIST.length = 0; REDO.length = 0; save(); markSaved(); panel(); draw();
}

// What to use, once the page has what it needs (Sami, 11 Oct: "after calibration, recommend the implant to be used
// after having all the information you need"). Before that: what is still missing. The sizes are the plan as it stands
// (placed by the rules, then perhaps changed by hand), with the next size either side to have ready, and the reasons.
function recommendation(st) {
  if (st.id === 'cal' || S.auto.busy || angleOnly() || S.wf === 'diaph') return null;   // r39: the diaphyseal plan's order request is on its card   // the knee case's long leg and skyline have no implants to recommend
  const box = el('<div class="rec"></div>');
  if (S.noRange) {   // a pending-only account (amendment 6): what it holds and why nothing can be placed
    box.appendChild(el('<div class="rec-h">No implants to place yet</div>'));
    box.appendChild(el(`<ul>${PENDING.map(p => `<li>${esc(pendingText(p))}</li>`).join('')}</ul>`));
    box.appendChild(el('<p class="note">Calibration, landmarks and measurements work as usual; implants can be placed once their template files are on the server.</p>'));
    return box;
  }
  if (st.id === 'prep' || st.id === 'lm' || !opSlots().every(sl => S.tpl[sl] && S.tpl[sl].on)) {
    const miss = [];
    const nr = notReady(); if (nr) miss.push(nr.replace(/ first.*$/, '').replace(/\.$/, ''));
    for (const d of lmDefs()) if ((!d.optional || neededHere(d)) && (!lmGeom(d.key) || lmProblem(d.key))) miss.push(d.label + (lmProblem(d.key) ? ' (marked red: place it again)' : ''));
    if (S.wf === 'kneeAP' && !kneeAxes().tma && !miss.some(m => /ankle|Tibial shaft/i.test(m))) miss.push('Tibial shaft axis (or the ankle centre)');
    if (!miss.length && st.id === 'tpl') miss.push('press Auto template');
    box.appendChild(el(`<div class="rec-h">Recommendation: still needed</div>`));
    box.appendChild(el(`<ul>${miss.slice(0, 8).map(m => `<li>${esc(m)}</li>`).join('')}${miss.length > 8 ? `<li>and ${miss.length - 8} more</li>` : ''}</ul>`));
    return box;
  }
  const k = mm(), rows = [], why = [], caution = [];
  const near = (t, d) => { const f = S.fam[t.fam], L = sizesShown(f, t), i = L.findIndex(z => z.size === t.size); return L.slice(Math.max(0, i - d), i + d + 1).map(z => z.size); };
  const byHand = t => t.userSize ? ' (size changed by hand)' : '';
  if (S.wf === 'hip') {
    // a plan has only the kinds open to this account (opSlots): no cup or no stem rows when that range is not open
    const o = S.side, cup = S.tpl['cup' + o], cs = cup && sizeOf(cup);
    const cupRow = byH => ['Cup', `${S.fam[cup.fam].label} <b>${cs.size}</b> (outside ${cs.catalogueOuter || cs.outerDiameter} mm${cs.liner ? `, liner ${linerTxt(S.fam[cup.fam], cs.liner)}${linerOf(cup) ? ` ${linerName(cup, linerOf(cup))}` : ''}` : ''})${byH}`, `have ready ${near(cup, 1).join(' · ')}${linerOff(cup) ? `; head centre ${f1(linerOff(cup))} mm out of the shell` : ''}`];   // r38b: liner 54 +4 neutral
    if (refOther()) {   // stem, head, neck and neck cut from the normal side (Sami, 11 Oct)
      const n = other(o), sN = S.tpl['stem' + n], zN = sN && sizeOf(sN), cutN = sN ? neckCutAboveLT(sN, n) : null, R = results();
      if (cup) rows.push(cupRow(''));
      if (sN) {
        rows.push(['Stem', `${S.fam[sN.fam].label} <b>${zN.size}</b>`, `templated on the ${SIDE[n]}; have ready ${near(sN, 1).join(' · ')}`]);
        rows.push(['Head', `<b>${sN.head} mm</b> ${S.lib.heads[sN.headKind].label}, neck <b>${neckLabel(sN, sN.neck)}</b>`, `${linerSub(cup, sN)}trial the necks either side`]);
        rows.push(['Neck cut', cutN !== null ? `<b>${f1(cutN)} mm</b> above the lesser trochanter` : `mark the ${SIDE[n]} lesser trochanter`, `medial end of the cut, as measured on the ${SIDE[n]}`]);
      }
      rows.push(['Leg length', `match the ${SIDE[n]} side`, `the ${SIDE[o]} femur is fractured or rotated: not measured on it`]);
      why.push(`the ${SIDE[o]} hip is not fit to template on: the ${SIDE[n]} side is templated and copied (cup mirrored at the same size and angle; same stem, head, neck and neck cut)`);
      if (S.notes['cupSize' + o]) why.push(`cup size from ${S.notes['cupSize' + o]}`);
      if (S.notes['offset' + n]) why.push(`${SIDE[n]} stem: ${S.notes['offset' + n]}`);
      if (S.notes['cupRule' + n]) why.push(`${SIDE[n]} cup by the ${cupRuleName(S.notes['cupRule' + n])}`);
      { const g2 = l => (R.find(r => r[0] === lab(`${SIDE[n]} `, l)) || [])[1], hvc = g2('Head centre vs cup centre'), cmb = g2('Combined offset');
        if (hvc) rows.push([`${cap(SIDE[n])} head vs cup`, esc(hvc), 'templated on the normal side']); if (cmb) rows.push([`${cap(SIDE[n])} combined offset`, esc(cmb), '']); }
      if (sN && cutN === null) caution.push(`neck cut not measured: mark the ${SIDE[n]} lesser trochanter`);
      caution.push(...unconfirmed([cup, sN]));
      if (cs && cs.catalogueOuter && Math.abs(cs.outerDiameter - cs.catalogueOuter) > 0.5) caution.push(`cup ${cs.size}: catalogue and ${drawnFrom(S.fam[cup.fam])} differ (${cs.catalogueOuter} vs ${cs.outerDiameter} mm outside)`);
      for (const r of R) if (r.length > 1 && (r[2] === 'bad' || r[2] === 'warn') && !/^Cup size check$/.test(r[0])) caution.push(`${r[0]}: ${r[1]}`);
    } else {
    const stem = S.tpl['stem' + o], ss = stem && sizeOf(stem);
    if (cup) rows.push(cupRow(byHand(cup)));
    if (S.notes['cupSize' + o]) why.push(`cup size from ${S.notes['cupSize' + o]}`);
    if (stem) {
    rows.push(['Stem', `${S.fam[stem.fam].label} <b>${ss.size}</b>${byHand(stem)}`, `have ready ${near(stem, 1).join(' · ')}`]);
    const bpr = bipOf(stem);
    rows.push(['Head', `<b>${stem.head} mm</b> ${S.lib.heads[stem.headKind].label}, neck <b>${neckLabel(stem, stem.neck)}</b>`, bpr ? 'the head inside the bipolar shell; trial the necks either side' : `${linerSub(cup, stem)}trial the necks either side`]);
    if (bpr) {   // r38: the shell, and the next size either side to have ready
      const L = bpr.f.sizes, i = L.indexOf(bpr.z), nd = bipNative(o);
      const rd = L.slice(Math.max(0, i - 1), i + 2), rh = [...new Set(rd.map(z => bipHead(bpr.f, z).diameter))];   // r40: a neighbour on another head is said
      rows.push(['Bipolar', `${esc(bpr.f.label)} <b>${esc(bpr.z.size)} mm</b> outside, on the <b>${esc(bipHead(bpr.f, bpr.z).diameter)} mm</b> head${stem.bip.userSize ? ' (size set by hand)' : ''}`, `have ready ${rd.map(z => z.size).join(' · ')}${rh.length > 1 ? `; heads: ${rd.map(z => `${z.size} on ${bipHead(bpr.f, z).diameter}`).join(', ')} mm: have both heads ready` : ''}`]);
      why.push(nd ? `bipolar shell from the native head: ${f1(nd.d)} mm measured, nearest shell ${bipPick(bpr.f, nd.d).size} mm (midway goes to the smaller)` : 'bipolar shell not sized from the native head (not marked)');
      why.push(`leg length and offset measured at the shell's outer centre, ${f1(bpr.z.headOffset)} mm from the head centre toward the neck, against the native head centre`);
    } else if (hemiOn(o)) caution.push(`bipolar chosen but no shell on the stem: ${bipMissing(o, stem)}`);
    { const cut = neckCutAboveLT(stem, o); rows.push(['Neck cut', cut !== null ? `<b>${f1(cut)} mm</b> above the lesser trochanter` : `mark the ${SIDE[o]} lesser trochanter`, 'medial end of the cut']); }
    const fn = S.notes['fit' + o], tr = canalTraceFor(o), cur = tr ? stemFit(stem, tr) : null, ev = cur && fitIsEvidence(cur, tr, stemLen(stem, tr));
    const sN = S.tpl['stem' + other(o)], cutO = neckCutAboveLT(stem, o), cutN = sN ? neckCutAboveLT(sN, other(o)) : null;
    const same = sN && sN.on && ['fam', 'size', 'headKind', 'head', 'neck'].every(k => sN[k] === stem[k]), cutSame = cutO !== null && cutN !== null && Math.abs(cutO - cutN) <= 0.5;
    if (fn && fn.copied && same && cutSame) why.push(`stem, head and neck copied from the ${SIDE[other(o)]} plan; neck cut ${f1(cutO)} mm above the lesser trochanter, as on the ${SIDE[other(o)]} (the ${SIDE[o]} film is not used for sizing)`);
    else if (fn && fn.copied) caution.push(`no longer the same as the ${SIDE[other(o)]} plan: ${same ? '' : 'stem, head or neck differ; '}${cutSame ? '' : `neck cut ${f1(cutO)} mm above the lesser trochanter against ${f1(cutN)} mm on the ${SIDE[other(o)]}`}. Press "Copy the ${SIDE[other(o)]} stem" to match again`);
    if (fn && fn.copied) { /* said above */ }
    else if (fn && !fn.none && !fn.copied && fn.size === stem.size && !fn.tooBig && !fn.largerUnchecked && ev && cur.maxIn <= 0.5) why.push(fn.by === 'cup' ? `stem size by canal fill: the largest of this version that stays inside the inner cortex; the version and neck bring the head nearest the cup centre (` : `stem size by canal fill: the largest that stays inside the inner cortex (${cur.maxIn >= -0.5 ? 'touching it' : f1(-cur.maxIn) + ' mm clear'} at the tightest level, as placed now)`);
    else if (fn && fn.none) caution.push(`stem size not chosen by the rules: ${fn.why}`);
    else why.push('stem size as set now: see the canal fill below and in Measurements');
    if (cur && !(fn && fn.copied)) { if (cur.maxIn > 0.5) caution.push(`stem ${stem.size} as placed reaches ${f1(cur.maxIn)} mm past the inner cortex`); else if (!ev) caution.push('canal fill checked over only part of the stem'); }
    if (fn && !fn.none && !fn.copied && fn.size === stem.size && fn.largerUnchecked) caution.push('larger stems could not be checked: the canal was not traced far enough down');
    if (fn && fn.copied && cur && cur.maxIn > 0.5) caution.push(`on this (rotated) film the copied stem reaches ${f1(cur.maxIn)} mm past the inner cortex: expected when the femur is rotated; judge the size on the ${SIDE[other(o)]} side`);
    if (S.notes['offset' + o]) why.push(S.notes['offset' + o]);
    }
    const R = results(), g = l => (R.find(r => r[0] === l) || [])[1];
    const lla = g('Leg length after'), oc = g('Femoral offset vs other side') || g('Offset change');
    if (lla) rows.push(['Leg length after', esc(lla), '']);
    if (oc) rows.push([g('Femoral offset vs other side') ? 'Offset vs other side' : 'Offset change', esc(oc), '']);
    const hvc = g('Head centre vs cup centre'), cmb = g('Combined offset');   // the stem against its cup (amendment 2)
    if (hvc) rows.push(['Head vs cup centre', esc(hvc), g('Planned lengthening') ? `planned lengthening: ${g('Planned lengthening')}` : '']);
    if (cmb) rows.push(['Combined offset', esc(cmb), '']);
    if (cs && cs.catalogueOuter && Math.abs(cs.outerDiameter - cs.catalogueOuter) > 0.5) caution.push(`cup ${cs.size}: catalogue and ${drawnFrom(S.fam[cup.fam])} differ (${cs.catalogueOuter} vs ${cs.outerDiameter} mm outside)`);
    caution.push(...unconfirmed(withBip([cup, stem])));
    for (const r of R) if (r.length > 1 && (r[2] === 'bad' || r[2] === 'warn') && !/^Cup size check$/.test(r[0])) caution.push(`${r[0]}: ${r[1]}`);   // every warning in the results (Astra, 11 Oct)
    }
  } else {
    for (const sl of opSlots()) { const t = S.tpl[sl], z = sizeOf(t); rows.push([SLOT[sl].label, `${S.fam[t.fam].label} <b>${z.size}</b>${sl === 'tibia' ? `, ${thickTxt(t)}` : ''}${t.stem && !extWhy(t) ? `, stem ${t.stem.size}` : ''}${byHand(t)}`, `have ready ${near(t, 1).join(' · ')}`]); }
    why.push(S.wf === 'kneeLat' ? 'femur sized from the anterior cortex to the back of the condyles, tray from the plateau depth' : 'cuts square to the mechanical axes; check the sizes on the lateral film');
    caution.push(...unconfirmed(opSlots().map(sl => S.tpl[sl])));
  }
  if (!S.cal || (S.cal.method !== 'ball' && S.cal.method !== 'line' && !isCtCal(S.cal))) caution.push('not calibrated on the ball: sizes may be 10-20 % off');
  box.appendChild(el(`<div class="rec-h">Recommended${S.wf === 'hip' ? ` for the ${SIDE[S.side]} hip${hemiOn(S.side) ? ' (bipolar hemiarthroplasty)' : ''}` : ''}${revCase() ? ` (${CASE_WORD[ctype()].toLowerCase()}: your decisions, see the cautions)` : ''}</div>`));
  box.appendChild(el(`<table>${rows.map(r => `<tr><td>${esc(r[0])}</td><td>${r[1]}${r[2] ? `<div class="sub">${esc(r[2])}</div>` : ''}</td></tr>`).join('')}</table>`));
  const det = help(`${why.length ? `<p><b>Why:</b> ${why.map(esc).join('; ')}.</p>` : ''}<p>These are the implants as planned on the film now, by the rules in Auto plan settings and any changes you made. A draft for the surgeon: confirm on the film and in theatre.</p>`, 'Why these');
  box.appendChild(det);
  if (caution.length) box.appendChild(el(`<div class="warnbox">${[...new Set(caution)].map(esc).join('<br>')}</div>`));
  return box;
}
// ---------- the implant choice card (13 Oct 2026, design C with amendments 4 and 5) ----------
// Sami, 13 Oct: Auto template "doesn't ask or give the option of which implant to use". Once the landmarks are done: the
// cup and stem families open to this account as rows to tap (a phone's 44 px targets), the ranges without template files
// greyed (design D), and on the stem the best geometric match: the version whose head centre comes nearest the cup centre
// on this film with the canal fill checked (the rule Auto template uses). Cups are listed without ranking: canal matching
// cannot rank cups or judge clinical suitability (Astra). Before placing: pick, then Auto template (or Use recommended).
// After: a family picked here is the slot's own, and the plan is placed again around it (the stem and the mirrored copy
// follow a cup change); "Re-run with my choice" does that again, "Use recommended" lets the rules choose everything.
let REC = { key: null, val: null };
// the best geometric match for the stem on the plan's side, or null; the rules run on a copy of the plan when nothing is
// placed yet (the page's plan is put back exactly as it was). Kept until the landmarks, scale, rules or cup change.
function recommendStem() {
  if (S.wf !== 'hip' || !mm() || !hasKind('stem') || revCase()) return null;   // a conversion or revision: no family suggestion (amendment 3)
  const sd = refOther() ? other(S.side) : S.side, cup = S.tpl['cup' + sd], stem = S.tpl['stem' + sd];
  if (stem && isPfr(S.fam[stem.fam])) return null;   // r32: no family suggestion over a tumour body
  const key = JSON.stringify([S.lm, mm(), S.side, S.scope, S.ref, PLAN_KEYS.map(k => S.set[k]), S.set.halfSizes, S.choice, cup ? [cup.fam, cup.size, cup.x, cup.y, cup.angle, cup.on, cup.liner] : null, stem ? [stem.fam, stem.headKind, stem.head] : null]);
  if (REC.key === key) return REC.val;
  let val = null;
  const keep = JSON.stringify({ tplAll: { ...S.tplAll, [S.wf]: S.tpl }, notes: S.notes, mirror: S.mirror, active: S.active, choice: S.choice });
  try {
    if (!(cup && cup.on && stem)) planHip({ fresh: true, dry: true });
    const s2 = S.tpl['stem' + sd], c2 = S.tpl['cup' + sd];
    if (s2 && c2 && c2.on && canalOf(sd)) {
      const P = stemCandidates(sd, {}), pick = P && chooseStem(P.cands, {});
      if (pick && !pick.none) { const b = pick.best; val = { fam: b.fam, size: b.size, missH: r1(b.missH), why: `${S.fam[b.fam].label} ${b.size} brings the head centre within ${f1(Math.abs(b.missH))} mm of the cup centre.` }; }
    }
  } catch (e) { console.error(e); val = null; }
  finally {
    const k0 = JSON.parse(keep);
    S.tplAll = k0.tplAll; S.tpl = S.tplAll[S.wf] || (S.tplAll[S.wf] = {}); S.notes = k0.notes; S.mirror = k0.mirror; S.active = k0.active; S.choice = k0.choice;
  }
  REC = { key, val }; return val;
}
// what the surgeon set by hand in the plan (kept by Auto template), as words
function handChoices() {
  const out = [];
  for (const sl of WF.hip.slots) {
    const t = S.tpl[sl]; if (!t || !S.fam[t.fam]) continue;   // a mirrored cup set by hand is kept by Mirror too (finding 3)
    const nm = `${SLOT[sl].label.toLowerCase()} ${SIDE[SLOT[sl].side]}`;
    if (t.userFam) out.push(`${nm}: ${S.fam[t.fam].label}`);
    if (t.userSize) out.push(`${nm}: size ${t.size}`);
  }
  return out;
}
function pickFamily(kind, id) {
  const f = S.fam[id]; if (!f || f.kind !== kind) return;
  const setBy = !brandSet(); if (setBy) { if (S.kc) S.kc.shared = { ...S.kc.shared, brand: brandOf(f) }; else S.brand = brandOf(f); }   // r36: a family chosen by hand sets the case's maker (said: Fable P2 7)
  const said = () => { if (setBy) { const pe = document.getElementById('prompt'); prompt(`Implant maker for this case set to ${brandOf(f)} by your choice of ${f.label}. ${pe ? pe.innerText : ''}`.trim()); } };
  S.choice = { ...S.choice, [kind]: id };
  const slots = WF.hip.slots.filter(sl => SLOT[sl].kind === kind && S.tpl[sl]);
  if (!slots.length) { prompt(`${f.label} chosen: press Auto template to place it.`); said(); panel(); return; }   // nothing placed yet
  for (const sl of slots) { const t = S.tpl[sl]; if (t.fam !== id) setFam(t, f); t.userFam = true; t.userSize = false; }
  autoTemplate({ replan: true });   // the plan placed again around it: a cup change re-runs the stem and the mirrored copy
  said();
}
// a plan saved before 14 Oct 2026 (Astra's review of the cup rule, finding 1): Auto template keeps a reference plan the
// surgeon made, so the new cup rule is applied by this action: the cup placed again by it, the stem and a mirrored cup
// after it; sizes and families set by hand are kept (as "Re-run with my choice")
const OLD_CUP_NOTE = 'This plan was saved before 14 Oct 2026 with the cup rule of that time (lowest rim on the teardrop line). Its rule is now native centre height, medial edge at the teardrop. The implants stay as saved until you press "Re-plan with the new cup rule": that places the cup again by the new rule and the stem (and a mirrored cup) after it, keeping sizes and families you set by hand. To keep the older rule, choose "Lowest rim on the teardrop line" in Auto plan settings.';
const REPLAN_CUP = 'Re-plan with the new cup rule', REPLAN_TIP = 'Place the cup again by native centre height, medial edge at the teardrop, and the stem (and a mirrored cup) after it; sizes and families you set by hand are kept';
function replanCupRule() { S.oldCupRule = false; S.restoreNote = S.restoreNote.replace(OLD_CUP_NOTE, '').trim(); autoTemplate({ replan: true }); }
function useRecommended() { S.choice = {}; autoTemplate({ fresh: true, replan: true }); }
// r43 (29 Oct 2026; Sami: "major issue with adding implants for tumors and CCK as well as hinged knee"; DIAG-2026-10-29-add-
// implants.md #1, #6, #10): the knee type, on a card at the implant step (as the hip's): each type sets the femur and the tray
// (or tumour body) as one pair, placed again by the rules when the parts are on the film. Only the types whose two families
// are open to this account are offered (the others named, "ask the administrator"). No type chosen: a primary knee, as before;
// the page never starts from a tumour, CCK or hinge knee. The hinge (LDK XR) is offered under any maker: choosing it makes LDK
// the case's maker and puts the hinge on the placed parts, nothing removed (DIAG #6).
const PART_GROUP = { 'ao-tumour': 'Addis Ortho tumour knee (PTR, DFR)', 'ao-pfr': 'Addis Ortho proximal femur (PFR, tumour)', 'ao-phr': 'Addis Ortho shoulder (PHR, tumour)' };   // DIAG #5
const KNEE_TYPES = [
  { id: 'primary', label: 'Primary' },
  { id: 'cck', label: 'CCK (constrained condylar)', fem: 'chunli-cck-femur', tib: 'chunli-cck-tibia' },
  { id: 'ldk', label: 'Hinge (LDK XR)', fem: 'ldk-xr-femur', tib: 'ldk-xr-tibia' },
  { id: 'ptr', label: 'Proximal tibial replacement (tumour)', fem: 'chunli-ptr-femur', tib: 'chunli-ptr-tibia' },
  { id: 'dfr', label: 'Distal femoral replacement (tumour)', fem: 'chunli-dfr-femur', tib: 'chunli-dfr-tibia' }];
const KT_PAIR = new Set(KNEE_TYPES.flatMap(k => (k.fem ? [k.fem, k.tib] : [])));
const ktPrimary = kind => allFams(kind).filter(f => !KT_PAIR.has(f.id));
const ktHeld = k => (k.fem ? !!(S.fam[k.fem] && S.fam[k.tib]) : ktPrimary('tka-femur').length > 0 && ktPrimary('tka-tibia').length > 0);
const ktOfFam = id => (id && S.fam[id] ? (KNEE_TYPES.find(k => k.fem === id || k.tib === id) || KNEE_TYPES[0]).id : null);
function kneeTypeNow() {
  const sz = (S.kc && S.kc.shared.size) || {}, on = sl => (S.tpl[sl] && S.tpl[sl].fam) || (sz[sl] && sz[sl].fam) || null;
  return ktOfFam(on('femur') || S.choice['tka-femur']) || ktOfFam(on('tibia') || S.choice['tka-tibia']);
}
// the size a knee part starts at on a family (DIAG #2): a tumour body without an extension (its length comes from the MRI/CT
// options), the body number of its tumour partner when that is placed; else the size it had when the new family has it, else
// the middle one
function kneeStartSize(nf, t) {
  const has = s => nf.sizes.some(z => z.size === s), pslot = nf.kind === 'tka-femur' ? 'tibia' : 'femur';
  const pt = (S.tpl && S.tpl[pslot]) || (S.kc && S.kc.shared.size[pslot]), pf = pt && S.fam[pt.fam];
  const pn = pf && pf.id !== nf.id && (pf.rangeOwn || pf.range) === (nf.rangeOwn || nf.range) && KT_PAIR.has(pf.id) ? parseInt(pt.size, 10) : NaN;
  if (isTumourBody(nf)) {
    const l0 = nf.sizes.filter(z => z.extensionMm === 0);
    if (l0.length) return (l0.find(z => parseInt(z.size, 10) === pn) || l0[Math.floor((l0.length - 1) / 2)]).size;
  }
  if (Number.isFinite(pn) && has(String(pn))) return String(pn);
  if (t && has(t.size)) return t.size;
  return nf.sizes[Math.floor(nf.sizes.length / 2)].size;
}
// a knee part put on another family: its start size, no size by hand, no tumour values of the old family (DIAG #13)
function kneeFamStart(t, nf) { const o = t.fam; t.size = kneeStartSize(nf, t); t.fam = nf.id; t.userSize = false; if (o !== nf.id) delete t.tum; kneeFamFix(t, o); }
// a knee case's shared family and start size: never "by hand" (DIAG #3: the lateral took the start size as the surgeon's)
function kcFamSet(slot, t) { if (!S.kc || !t) return; S.kc.shared.size[slot] = { fam: t.fam, size: t.size, byHand: false, ...(t.stem ? { stem: { ...t.stem } } : {}) }; }
function pickKneeType(id) {
  const k = KNEE_TYPES.find(q => q.id === id); if (!k || !ktHeld(k)) return;
  const setMaker = b => { if (S.kc) S.kc.shared = { ...S.kc.shared, brand: b }; else S.brand = b; };
  let fe = null, ti = null, said = '', pm = null;
  if (k.fem) {
    fe = S.fam[k.fem]; ti = S.fam[k.tib];
    const b = brandOf(fe); if (brandSet() !== b) { setMaker(b); said = ` Implant maker for this case: ${b}.`; }
  } else {
    let b = brandSet() || brandInferred(); const ms = [...new Set(ktPrimary('tka-femur').map(brandOf))];
    if (!b || b === 'mixed' || !ms.includes(b)) { b = ms.length === 1 ? ms[0] : null; setMaker(b); if (b) said = ` Implant maker for this case: ${b}.`; }
    if (b && brandSet() !== b) setMaker(b);
    const d = kind => { const f = b ? defaultFam(kind) : null; return f && !KT_PAIR.has(f.id) ? f : null; };
    fe = d('tka-femur'); ti = d('tka-tibia'); pm = b;
  }
  S.choice = { ...S.choice }; for (const [kind, f] of [['tka-femur', fe], ['tka-tibia', ti]]) { if (f) S.choice[kind] = f.id; else delete S.choice[kind]; }
  const moved = [];
  for (const [slot, f] of [['femur', fe], ['tibia', ti]]) {
    const t = S.tpl[slot];
    if (t && f) { if (t.fam !== f.id) kneeFamStart(t, f); t.userFam = !!k.fem; moved.push(slot); }
    else if (t && !f && pm && KT_PAIR.has(t.fam)) { delete S.tpl[slot]; if (S.active === slot) S.active = null; }   // the maker has more than one primary range: chosen under the maker (a part of another type is not kept as "primary")
    if (S.kc) { if (S.tpl[slot]) kcFamSet(slot, S.tpl[slot]); else if (f) S.kc.shared.size[slot] = { fam: f.id, size: kneeStartSize(f, null), byHand: false }; else if (S.kc.shared.size[slot] && KT_PAIR.has(S.kc.shared.size[slot].fam)) S.kc.shared.size[slot] = null; }
  }
  if (moved.length && mm() && !kneeLmGate() && !kneeRevWhy()) for (const sl of moved) autoPlace(sl);
  save(); panel(); draw();
  const tum = k.id === 'ptr' || k.id === 'dfr', two = k.id === 'ptr' ? 'the femoral part and the tibial body' : k.id === 'dfr' ? 'the femoral body and the tibial part' : 'the femur and the tray';
  if (!fe && !k.fem) return prompt(`Primary knee: ${pm ? `${pm} has more than one primary range: choose the femoral and tray family under the maker` : 'choose the implant maker above (the placed parts stay until you do)'}, then Auto template.${said}`);
  prompt(`Knee type: ${k.label}. ${moved.length ? `${cap(two)} put on it and placed again on this film` : `Press Auto template to place ${two}`}.${said}${tum ? ' The body starts without an extension: enter the MRI/CT extent and your margin in its box, then pick one of the options (none is chosen for you).' : ''}`);
}
function kneeTypeCard(where) {
  if (!mm() || (S.wf !== 'kneeAP' && S.wf !== 'kneeLat') || llKcLong()) return null;   // r44: the long film draws the case's type
  const held = KNEE_TYPES.filter(ktHeld); if (held.length < 2) return null;
  const now = kneeTypeNow(), placed = !!(S.tpl.femur || S.tpl.tibia);
  const box = el(`<div class="choice ktype" id="choice-${where}"></div>`);
  box.appendChild(el(`<div class="ch-h">Knee type${placed ? ': tap another to change both parts' : ': choose it, then Auto template'}</div>`));
  const g = el('<div class="ch-g" role="radiogroup" aria-label="Knee type"></div>');
  for (const k of held) {
    const b = el(`<button type="button" class="ch-o" role="radio" aria-checked="${now === k.id}" data-ktype="${k.id}"><span class="ch-l">${esc(k.label)}</span></button>`);
    if (k.fem) b.title = `${S.fam[k.fem].label} with ${S.fam[k.tib].label}`;
    b.onclick = ev => { ev.stopPropagation(); pickKneeType(k.id); };
    g.appendChild(b);
  }
  box.appendChild(g);
  const miss = KNEE_TYPES.filter(k => !ktHeld(k)).map(k => k.label);
  if (miss.length) box.appendChild(el(`<p class="note">Not open to your account: ${esc(miss.join('; '))} (ask the administrator).</p>`));
  if (!now) box.appendChild(el('<p class="note">No knee type chosen: Auto template places a primary knee. Choose CCK, the hinge or a tumour replacement here first; the page never picks one for you.</p>'));
  else if (now === 'ptr' || now === 'dfr') box.appendChild(el('<p class="note">Tumour replacement: the body starts without an extension. Enter the MRI/CT extent and your margin in its box, then pick one of the options.</p>'));
  return box;
}
// r43 (DIAG #11): a film whose header names no body part opens as Hip; the surgeon is asked (not blocking)
function wfAskRow() {
  const box = el('<div class="choice wfask" id="wfask"><div class="ch-h">The film does not say which joint it shows: it opened as a hip. Which is it?</div></div>'), g = el('<div class="ch-g" role="radiogroup" aria-label="Which joint"></div>');
  for (const [w, txt] of [['hip', 'Hip'], ['kneeAP', 'Knee AP'], ['kneeLat', 'Knee lateral'], ['shoulder', 'Shoulder']]) {
    if (!S.wfs.includes(w)) continue;
    const b = el(`<button type="button" class="ch-o" role="radio" aria-checked="false" data-wf="${w}"><span class="ch-l">${txt}</span></button>`);
    b.onclick = ev => { ev.stopPropagation(); S.wfAsk = false; if (w !== S.wf) { $('wf').value = w; $('wf').onchange(); } else panel(); };
    g.appendChild(b);
  }
  box.appendChild(g); return box;
}
function choiceCard(where, rec) {
  if (S.wf === 'kneeAP' || S.wf === 'kneeLat') return kneeTypeCard(where);   // r43
  if (S.wf !== 'hip' || !mm()) return null;
  const sd = refOther() ? other(S.side) : S.side, cups = famsFor('cup'), stems = famsFor('stem').filter(f => !isPfr(f));   // r32: a PFR is chosen on the stem itself, not here
  if (!cups.length && !stems.length && !PENDING.length) return null;
  const cupT = S.tpl['cup' + sd], stemT = S.tpl['stem' + sd], placed = !!(cupT || stemT);
  const box = el(`<div class="choice" id="choice-${where}"></div>`);
  box.appendChild(el(`<div class="ch-h">${placed ? 'Implants in this plan: tap another to change it' : 'Choose the implants, then Auto template'}${refOther() ? ` (planned on the ${SIDE[sd]})` : ''}</div>`));
  { const hr = hemiRow(sd, true); if (hr) box.appendChild(hr); }   // r38: Total hip or Bipolar, before Auto template
  const group = (kind, title, fams, cur, best) => {
    const g = el(`<div class="ch-g" role="radiogroup" aria-label="${title} family"><div class="ch-t">${title}</div></div>`);
    for (const f of fams) {
      const b = el(`<button type="button" class="ch-o" role="radio" aria-checked="${f.id === cur}" data-fam="${esc(f.id)}"><span class="ch-l">${esc(f.label)}</span>${best === f.id ? '<span class="best">Best geometric match (check it)</span>' : ''}</button>`);
      if (kind === 'stem' && hemiOn(sd) && !bipFamFor(f)) { b.disabled = true; b.setAttribute('aria-disabled', 'true'); b.title = `The Addis Ortho bipolar shell goes on Chunli's ${bipFams()[0] ? bipHeadsShort(bipFams()[0]) : 'head'}: this stem does not take it`; b.querySelector('.ch-l').textContent += ' (takes no bipolar shell)'; }   // r38 (Fable P3-4)
      else b.onclick = () => pickFamily(kind, f.id);
      g.appendChild(b);
    }
    for (const p of pendingOf(kind)) g.appendChild(el(`<div class="ch-o pend" aria-disabled="true" data-range="${esc(p.key)}">${esc(pendingText(p))}</div>`));
    if (!fams.length && !pendingOf(kind).length) g.appendChild(el(`<p class="note">No ${title.toLowerCase()} range is open to your account.</p>`));
    return g;
  };
  if (hemiOn(sd)) box.appendChild(el('<p class="note">Bipolar on this hip: no cup. The shell is sized from the native head and goes on the stem\'s head.</p>'));   // r38
  else box.appendChild(group('cup', 'Cup', cups, (cupT && cupT.fam) || ((chosenFam('cup') || defaultFam('cup') || {}).id), null));
  box.appendChild(group('stem', 'Stem', stems, (stemT && stemT.fam) || ((chosenFam('stem') || defaultFam('stem') || {}).id), rec && rec.fam));
  { const pfs = famsFor('stem').filter(isPfr), cur = (stemT && stemT.fam) || ((chosenFam('stem') || {}).id); if (pfs.length) {   // r43 (DIAG #7): the PFR is a plan of its own, chosen here
    const g = el('<div class="ch-g" role="radiogroup" aria-label="Proximal femoral replacement"><div class="ch-t">Tumour: proximal femoral replacement</div></div>');
    for (const f of pfs) { const b = el(`<button type="button" class="ch-o" role="radio" aria-checked="${f.id === cur}" data-fam="${esc(f.id)}"><span class="ch-l">${esc(f.label)}</span></button>`); b.onclick = () => pickFamily('stem', f.id); g.appendChild(b); }
    g.appendChild(el('<p class="note">Placed on the head and the canal, never sized for you: its length and stem collar come from the MRI/CT extent and your margin (in its box).</p>'));
    box.appendChild(g); } }
  const hand = placed ? handChoices() : [], row = el('<div class="row"></div>');
  if (cups.length || stems.length) row.appendChild(btn('Use recommended', useRecommended, 'primary', false, 'The rules choose the families and sizes: the stem version whose head centre comes nearest the cup centre, with the canal fill checked'));
  if (S.oldCupRule && placed) row.appendChild(btn(REPLAN_CUP, replanCupRule, '', false, REPLAN_TIP));
  if (hand.length) row.appendChild(btn('Re-run with my choice', () => autoTemplate({ replan: true }), '', false, `Place the plan again by the rules, keeping what you set by hand: ${hand.join('; ')}`));
  box.appendChild(row);
  if (hand.length) box.appendChild(el(`<p class="note">Kept by Auto template, as you set them: ${esc(hand.join('; '))}.</p>`));
  { const dc = [cupT, stemT].map(t => t && !t.userFam && S.fam[t.fam]).filter(isMakerDefault); if (dc.length) box.appendChild(el(`<p class="note">${esc(dc.map(f => f.label).join(' and '))}: Addis Ortho's usual primary range, where the page starts when you have not chosen one (as before r36); it is not ranked against the maker's other ranges. Tap another to change it.</p>`)); }   // r36
  box.appendChild(el(`<p class="note">${rec ? `${esc(rec.why)} ` : ''}The best geometric match is worked out on this film from the canal and the cup position; it does not rank the cups or judge clinical suitability. Check it.</p>`));
  if (revCase()) box.appendChild(el(`<p class="warnbox">${esc(CASE_WORD[ctype()])}: no family is suggested; long stems are listed as available sizes only. Fixation, length and family are your decisions.</p>`));
  return box;
}

function settingsBox() {
  const s = S.set, d = el('<details class="settings"><summary>Auto plan settings</summary></details>');
  if (S.settingsOpen) d.open = true;
  d.ontoggle = () => { S.settingsOpen = d.open; };
  const row = html => { const r = el(`<div class="row note">${html}</div>`); d.appendChild(r); return r; };
  if (S.wf === 'hip') {
    if (refOther()) { const n_ = SIDE[other(S.side)], o_ = SIDE[S.side]; d.appendChild(el(`<p class="note"><b>The ${o_} hip is planned from the ${n_} hip</b>: the ${n_} plan is the surgical plan, so its cup is placed by the cup rule below (native centre height with the medial edge at the teardrop unless you choose another; "other hip, mirrored" means that rule there) and its stem by the stem rule below (head centre on the cup centre, or the older native offset rule). The ${o_} cup is the ${n_} cup copied across: the same size and angle on the mirrored centre. The ${o_} femur carries no stem: the ${n_} stem, head, neck and neck cut are used. A size or family you set by hand is kept.</p>`)); }
    const r1 = row(`Cup centre <select><option value="anatomic">Native centre height, medial edge at the teardrop</option><option value="teardrop">Lowest rim on the teardrop line</option><option value="head">native head centre</option><option value="mirror">other hip, mirrored</option></select>`);
    r1.querySelector('select').value = s.cupRule; r1.querySelector('select').onchange = e => { s.cupRule = e.target.value; saveSet(); panel(); };
    const r2 = row(`inclination <input type="number" min="30" max="55" step="1" value="${s.cupIncl}">°; ${s.cupRule === 'teardrop' ? 'lowest rim (inferomedial edge)' : 'cup\'s most medial point'} <input type="number" min="0" max="15" step="0.5" value="${s.tdLatMm}"> mm lateral to the teardrop`);
    const [ia, ib] = r2.querySelectorAll('input');
    ia.onchange = e => { const v = parseFloat(e.target.value); if (v >= 30 && v <= 55) s.cupIncl = v; saveSet(); panel(); };
    ib.onchange = e => { const v = parseFloat(e.target.value); if (v >= 0 && v <= 15) s.tdLatMm = v; saveSet(); panel(); };
    const rsr = row(`Stem: <select><option value="cup">head centre on the cup centre</option><option value="offset">restore the native femoral offset (older rule)</option></select>`);
    rsr.querySelector('select').value = s.stemRule || 'cup'; rsr.querySelector('select').onchange = e => { s.stemRule = e.target.value; saveSet(); panel(); };
    const r3 = row(`Stem height: <select><option value="equal">make leg lengths equal</option><option value="keep">keep leg length as it is</option></select>`);
    r3.querySelector('select').value = s.legTarget; r3.querySelector('select').onchange = e => { s.legTarget = e.target.value; saveSet(); panel(); };
    const r4 = row(`<label><input type="checkbox"> centre the canal line on the film's cortex</label>`);
    r4.querySelector('input').checked = s.snapCanal; r4.querySelector('input').onchange = e => { s.snapCanal = e.target.checked; saveSet(); };
  }
  const r5 = row(`<label><input type="checkbox"> show half sizes (BE2 133S 6.5-17.5; not stocked)</label>`);
  r5.querySelector('input').checked = s.halfSizes; r5.querySelector('input').onchange = e => { s.halfSizes = e.target.checked; saveSet(); panel(); };
  if (S.wf === 'hip') {
    const ro2 = row(`Surgery-side stem's femoral offset: <select><option value="auto">the other hip's native one when marked, else this hip's</option><option value="own">this hip's own native one</option><option value="none">not matched (neck 0)</option></select>`);
    ro2.querySelector('select').value = s.offsetTarget || 'auto'; ro2.querySelector('select').onchange = e => { s.offsetTarget = e.target.value; saveSet(); panel(); };
    const rm = row(`Mirror the surgery side to <select><option value="native">the other hip as it is (native)</option><option value="plan">the other side's plan</option></select>`);
    rm.querySelector('select').value = s.mirrorRef; rm.querySelector('select').onchange = e => { s.mirrorRef = e.target.value; saveSet(); panel(); draw(); };
  }
  const r6 = row(`<label><input type="checkbox"> look for the ball and the R/L marker when an X-ray opens</label>`);
  r6.querySelector('input').checked = s.autoFind; r6.querySelector('input').onchange = e => { s.autoFind = e.target.checked; saveSet(); };
  if (STANDALONE) {
    const rk = row(`<label><input type="checkbox"> keep my plans in this browser, to reopen a film later (off: nothing is kept after the page closes)</label>`);
    rk.querySelector('input').checked = !!s.keepLocal; rk.querySelector('input').onchange = e => { s.keepLocal = e.target.checked; saveSet(); if (s.keepLocal) save(); panel(); };
  }
  const r8 = row(`<label><input type="checkbox"> sharing outside the hospital: leave out the patient's name, age, record number and dates</label>`);
  r8.querySelector('input').checked = s.share; r8.querySelector('input').onchange = e => { s.share = e.target.checked; saveSet(); panel(); };
  const r7 = row(`<label><input type="checkbox"> place the implants when the landmarks are done</label>`);
  r7.querySelector('input').checked = s.autoTemplate; r7.querySelector('input').onchange = e => { s.autoTemplate = e.target.checked; saveSet(); };
  d.appendChild(el('<p class="note">Rules: the cup is sized from the acetabular roof edge when marked (its rim reaches the edge at the planned angle), else head diameter + 4 mm outside; a size beyond the range is said, not replaced. Cup centre (every surgical plan, also the normal side when it is the reference), by default native centre height, medial edge at the teardrop: the cup centre at the height of the native head centre (for a deformed side the other hip\'s, mirrored), moved medially until the cup\'s most medial point is the set distance lateral of the teardrop, never more than 10 mm medial of the native centre and never lateral of it; the older rule (lowest rim on the teardrop line, just lateral to the teardrop) can be chosen. Either is a starting position to judge against the medial wall and the roof; a cup centre over 3 mm above the native centre or a lowest rim over 5 mm off the teardrop line is flagged. The stem lies along the canal with its head at the cup centre\'s height plus the leg length to gain; canal fill sets the size: each version of the stem open to you (BE2 133S, 133H, 127S, 127H, or a maker\'s standard/high pair) gets its largest size that stays inside the inner cortex (0.5 mm allowed) wherever the cortex edges were found; then of those versions and their necks the one whose head centre comes nearest the cup centre horizontally is chosen, and high offset not when it would restore the combined offset more than 5 mm over the native hip; when nothing qualifies the page says so and leaves the stem to you. The older rule (restore the native femoral offset) takes the largest size inside the inner cortex and the neck nearest the offset. A size or family you set by hand is kept, also by Mirror and when the cup changes (the stem and the mirrored plan follow a cup change). Every result can be changed by hand.</p>'));
  return d;
}
function templateBox(slot) {
  const t = S.tpl[slot], side = slotSide(slot), op = !SLOT[slot].side || side === S.side;
  if (refOther() && slot === 'stem' + S.side && !(t && t.byHand)) {   // the fractured femur: no outline, the normal side's stem instead
    const sN = S.tpl['stem' + other(side)], cut = sN && sN.on ? neckCutAboveLT(sN, other(side)) : null;
    const nb = el(`<div class="tpl" data-slot="${slot}"><div class="name">${esc(slotName(slot))}</div><p class="note">Not templated on this femur (fractured, shortened or rotated). Use the ${SIDE[other(side)]} stem${sN ? `: <b>${esc(S.fam[sN.fam].label)} ${esc(sN.size)}</b>, ${esc(sN.head)} mm head, neck ${esc(neckLabel(sN, sN.neck))}` : ' (plan it on that side)'}${cut !== null ? `; neck cut <b>${f1(cut)} mm above the lesser trochanter</b>` : ''}.</p></div>`);
    nb.appendChild(btn('Add a stem here anyway', () => addTemplate(slot), '', !mm(), `A ${SIDE[side]} stem to place by hand on this femur, starting as the ${SIDE[other(side)]} stem mirrored; the plan stays the ${SIDE[other(side)]} one`));
    return nb;
  }
  const box = el(`<div class="tpl ${S.active === slot ? 'active' : ''} ${op ? '' : 'other'}" data-slot="${slot}"></div>`);
  if (!t) {
    const none = !hasKind(SLOT[slot].kind);   // no range of this kind open to this account
    box.appendChild(el(`<div class="row"><span class="name">${esc(slotName(slot))}</span></div>`));
    box.firstChild.appendChild(btn('Place', () => addTemplate(slot), op ? 'primary' : '', !mm() || none));
    if (none) box.appendChild(el(`<p class="note">No ${esc(SLOT[slot].label.toLowerCase())} range is open to your account${pendingOf(SLOT[slot].kind).length ? ` with its template files on the server: ${esc(pendingOf(SLOT[slot].kind).map(pendingText).join('; '))}` : ''}.</p>`));
    if (hiddenHere(slot)) box.appendChild(el(`<p class="note">This plan has a ${esc(SLOT[slot].label.toLowerCase())} here from a range not open to your account: not shown, and kept in the plan unless you place one here.</p>`));
    return box;
  }
  const f = S.fam[t.fam];
  box.onclick = () => { if (S.active !== slot) { S.active = slot; panel(); draw(); } };
  if (t.byHand && refOther()) box.appendChild(el(`<p class="note">Added by hand on the ${SIDE[side]} femur, which is not templated: no rule moves it. The plan is the ${SIDE[other(side)]} one.</p>`));
  const head = el(`<div class="row"><span class="name">${esc(slotName(slot))}</span></div>`);
  const fs = el('<select></select>'), grp = {};   // grouped by implant range (library "ranges")
  const fl0 = [...famsFor(SLOT[slot].kind), ...(famsFor(SLOT[slot].kind).some(g => g.id === t.fam) || !S.fam[t.fam] ? [] : [S.fam[t.fam]])];   // r36: the case's maker, and this implant's own family
  for (const ff of isPfr(S.fam[t.fam]) ? [...fl0.filter(isPfr), ...fl0.filter(x => !isPfr(x))] : fl0) {   // r43 (DIAG #7): a PFR plan lists the PFR first
    const gk = PART_GROUP[ff.rangeOwn] ? ff.rangeOwn : ff.range, rg = ff.range && S.lib.ranges && S.lib.ranges[ff.range];   // r43 (DIAG #5): a hosted tumour family under what it is
    if (rg && !grp[gk]) { grp[gk] = document.createElement('optgroup'); grp[gk].label = PART_GROUP[gk] || rg.label; fs.appendChild(grp[gk]); }
    const nob = ff.kind === 'stem' && S.wf === 'hip' && hemiOn(side) && ff.id !== t.fam && !bipFamFor(ff);   // r38 (Fable P3-4): a stem that cannot take the shell, greyed on a bipolar hip
    const fo = new Option(nob ? `${ff.label} (takes no bipolar shell)` : ff.label, ff.id); if (nob) { fo.disabled = true; fo.title = `The Addis Ortho bipolar shell goes on Chunli's ${bipFams()[0] ? bipHeadsShort(bipFams()[0]) : 'head'}: this stem does not take it`; }
    (rg ? grp[gk] : fs).appendChild(fo);
  }
  const pk = pendingOf(SLOT[slot].kind);   // ranges without template files: greyed, never selectable (design D)
  if (pk.length) { const og = document.createElement('optgroup'); og.label = 'Not yet available'; for (const p of pk) { const op = new Option(pendingText(p), ''); op.disabled = true; og.appendChild(op); } fs.appendChild(og); }
  fs.value = t.fam;
  fs.onchange = e => {
    const nf = S.fam[e.target.value]; if (!nf) { fs.value = t.fam; return; }
    if (llKcLong() && (slot === 'femur' || slot === 'tibia')) { fs.value = t.fam; return prompt(LL_KC_SIZE); }   // r44
    t.userFam = true;   // chosen by hand: Auto template keeps it (amendment 4)
    if (nf.kind === 'stem') {   // nearest canal fill in the new family, head kept where it was (in a head system it takes)
      const w = widthAt(sizeOf(t), 60), shown = sizesShown(nf), pick = shown.reduce((b, z) => Math.abs(widthAt(z, 60) - w) < Math.abs(widthAt(b, 60) - w) ? z : b);
      changeStem(t, () => { t.fam = nf.id; t.size = Number.isFinite(w) ? pick.size : shown[Math.floor(shown.length / 2)].size; if (!headKindsOf(nf).includes(t.headKind)) setHead(t, nf.sheetHead.kind, t.head); });
      // then the planning rule for this stem within the family chosen (Astra's third page review): sized by canal fill,
      // neck and head height by the stem rule, its explanation written afresh; the nearest width above is only the start
      t.userSize = false;
    } else if (nf.kind === 'tka-femur' || nf.kind === 'tka-tibia') {   // r43 (DIAG #2, #3, #13): its start size (a tumour body without an extension), placed again by the rules (replanAfter); the case's size is not "by hand"
      if (nf.id !== t.fam) kneeFamStart(t, nf);
      if (S.kc && (slot === 'femur' || slot === 'tibia')) kcFamSet(slot, t);
    } else {   // a cup: the stem of its side and the mirrored plan follow it (finding 3)
      const o = t.fam; t.fam = nf.id; if (!nf.sizes.find(z => z.size === t.size)) t.size = nf.sizes[Math.floor(nf.sizes.length / 2)].size; kneeFamFix(t, o);
      if (S.kc && (slot === 'femur' || slot === 'tibia')) kcSizeSet(slot, t, true);   // a knee case: one family and size for both films
    }
    replanAfter(slot);
    save(); panel(); draw();
  };
  head.appendChild(fs);
  if (sizesShown(f).length > 1) head.appendChild(btn(S.allSizes === slot ? 'All sizes ✓' : 'All sizes', () => { S.allSizes = S.allSizes === slot ? null : slot; panel(); draw(); }, S.allSizes === slot ? 'on' : '', false, 'Every size of this family drawn faintly over the film, from the same point (r36, as Medstrat\'s "show all sizes"); not in the saved picture'));   // r36
  box.appendChild(head);
  if (S.wf !== 'hip' && !t.userFam && !chosenFam(f.kind) && isMakerDefault(f)) box.appendChild(el(`<p class="note">${esc(f.label)}: Addis Ortho's usual primary range, where the page starts when you have not chosen one (as before r36); not ranked against the maker's other ranges. Change it above if this case needs another.</p>`));   // r36
  const rs = el('<div class="row"></div>'); let headRow = null;
  rs.appendChild(btn('−', ev => { ev.stopPropagation(); stepSize(slot, -1); save(); panel(); draw(); }));
  const ss = el('<select></select>');
  const perm = f.kind === 'tka-tibia' ? kneePermitted() : null;   // Chunli's chart: the closest matches first, then OK, then not compatible (an override)
  if (perm) {
    const mk = label => { const g = document.createElement('optgroup'); g.label = label; return g; };
    const gG = mk(`Closest match for femur ${perm.femSize} (${CHART})`), gY = mk('OK (not the closest match)'), gX = mk(`Not compatible per the ${CHART}: override only`);
    for (const z of sizesShown(f, t)) (perm.green.includes(z.size) ? gG : perm.yellow.includes(z.size) ? gY : gX).appendChild(new Option(`${z.label || z.size}${perm.yellow.includes(z.size) ? ' (OK)' : perm.gray.includes(z.size) ? ' (not compatible)' : ''}`, z.size));
    for (const z of perm.green.filter(z => !f.sizes.some(q => q.size === z))) { const o = new Option(`${z}# (no template in this library)`, ''); o.disabled = true; gG.appendChild(o); }
    for (const g of [gG, gY, gX]) if (g.children.length) ss.appendChild(g);
  } else for (const z of sizesShown(f, t)) ss.add(new Option(z.label || z.size, z.size));
  ss.value = t.size;
  ss.onchange = e => { setSize(slot, e.target.value); save(); panel(); draw(); }; rs.appendChild(ss);
  rs.appendChild(btn('+', ev => { ev.stopPropagation(); stepSize(slot, 1); save(); panel(); draw(); }));
  if (f.kind === 'stem') {
    if (!headKindsOf(f).includes(t.headKind) || headSet(t)[t.neck] === undefined) setHead(t, t.headKind || f.sheetHead.kind, t.head || f.sheetHead.diameter);
    const bph = bipOf(t);   // r38: on a bipolar shell only the shell's own head is offered
    const hk = el('<select title="Head material"></select>'); for (const k of bph ? [bipHead(bph.f, bph.z).kind] : headKindsOf(f)) hk.add(new Option(S.lib.heads[k].label, k)); hk.value = t.headKind;
    hk.onchange = e => { changeStem(t, () => setHead(t, e.target.value, t.head)); t.userHead = true; if (linerFix(side)) replanAfter(slot); save(); panel(); draw(); };   // r38b: the cup's liner for the head; a liner flip re-levels the stem (Fable P3-1)
    const hs = el('<select title="Head diameter"></select>'); for (const h of bph ? [String(bipHead(bph.f, bph.z).diameter)] : Object.keys(S.lib.heads[t.headKind].sizes)) hs.add(new Option(`${h} mm head`, h)); hs.value = t.head;
    hs.onchange = e => { setHead(t, t.headKind, e.target.value); t.userHead = true; if (linerFix(side)) replanAfter(slot); save(); panel(); draw(); };   // r38b (Fable P3-1)
    const ns = el('<select title="Neck length"></select>'); for (const [n] of neckOpts(t)) ns.add(new Option(`neck ${neckLabel(t, n)}`, n)); ns.value = t.neck;
    ns.onchange = e => { t.neck = e.target.value; save(); panel(); draw(); };
    headRow = el('<div class="row"></div>'); headRow.append(hk, hs, ns);
  }
  if (f.kind === 'tka-tibia') { const th = el('<select></select>'); if (f.thicknessMeaning) th.title = f.thicknessMeaning; for (const v of f.thicknesses) th.add(new Option(f.seatAboveCutMm ? `${v} mm insert` : f.thicknessDrawnMm ? `${v} (sheet number)` : `${v} mm total (tray + insert)`, v)); th.value = t.thick; th.onchange = e => { t.thick = +e.target.value; save(); panel(); draw(); }; rs.appendChild(th); }
  box.appendChild(rs); if (headRow) box.appendChild(headRow);
  if (f.kind === 'cup' && linerOpts(t).length) {   // r38b: the liner type, for the stem's head on this side (every type the size has when there is no stem)
    const hd = linerHead(side), forHead = linerTypesFor(t, hd), types = forHead.length ? forHead : linerTypesFor(t, null);
    const lr = el('<div class="row"></div>'), ls = el('<select title="Liner"></select>');
    for (const ty of types) ls.add(new Option(`Liner: ${linerName(t, ty)} (${[...new Set(linerOpts(t).filter(l => l.type === ty && (!forHead.length || String(l.head) === String(hd))).map(l => l.material))].join(', ')})`, ty));
    ls.value = linerOf(t) || types[0];
    ls.onchange = e => { e.stopPropagation(); t.liner = e.target.value; t.userLiner = true; delete S.notes['liner' + side]; replanAfter(slot); save(); panel(); draw(); };
    lr.appendChild(ls); box.appendChild(lr);
    if (S.notes['liner' + side]) box.appendChild(el(`<p class="note warn">${esc(S.notes['liner' + side])}.</p>`));
    if (linerOff(t)) box.appendChild(el(`<p class="note">Head centre ${f1(linerOff(t))} mm out of the shell (+4 liner): the hip's centre of rotation (small cross); the large cross is the shell centre, where the roof and teardrop are measured.</p>`));
  }
  if (f.kind === 'humeral-body' && f.humHeads) {   // r33: the head size (Chunli's standard heads; hemispherical)
    const hr = el('<div class="row"></div>'), hs = el('<select title="Humeral head"></select>');
    for (const q of f.humHeads) hs.add(new Option(`${q.d} mm head (height ${q.h})`, String(q.d)));
    hs.value = t.head || ''; hs.onchange = e => { t.head = e.target.value; t.userHead = true; save(); panel(); draw(); };
    hr.appendChild(hs); box.appendChild(hr);
  }
  if (isPhr(f)) {   // r33: the proximal humeral replacement's resection from the tuberosity tip; options within 5 mm, none chosen
    const u = t.tum || (t.tum = { src: '', extent: null, margin: null }), tr = el('<div class="row"></div>');
    const sx = el('<select title="Where the tumour extent comes from"><option value="">Extent from: choose</option><option value="MRI">MRI</option><option value="CT">CT</option></select>');
    sx.value = TUM_SRC[u.src] ? u.src : '';
    const num = (key, ph) => { const i = el(`<input type="number" step="1" min="0" placeholder="${ph}" title="${ph}" aria-label="${ph}" style="width:13em">`); if (Number.isFinite(u[key])) i.value = u[key]; i.onchange = e => { const v = e.target.value === '' ? null : +e.target.value; u[key] = Number.isFinite(v) ? v : null; save(); panel(); draw(); }; return i; };
    sx.onchange = e => { u.src = e.target.value; save(); panel(); draw(); };
    tr.append(sx, num('extent', 'extent below the tuberosity tip, mm'), num('margin', 'margin, mm')); box.appendChild(tr);
    const P = pfrPlan(t, S.side, 'gtub');
    if (!P.tp.ok) box.appendChild(el(`<p class="note warn">Resection not planned: ${esc(P.tp.why)}.</p>`));
    else if (!P.gt) box.appendChild(el('<p class="note warn">Mark the tip of the greater tuberosity (landmark list, 2 · Landmarks): the resection is measured from it.</p>'));
    else if (Number.isFinite(P.sPlan)) {
      const opts = pfrOptions(f, P.sPlan);
      box.appendChild(el(`<p class="note">Resection ${f1(P.tp.resection)} mm below the tip of the greater tuberosity (${esc(P.tp.src)} extent ${f1(P.tp.extent)} + your margin ${f1(P.tp.margin)}). On this film the tip lies ${f1(Math.abs(P.sGT))} mm ${P.sGT >= 0 ? 'below' : 'above'} this implant's head centre level, so the osteotomy is ${fmm(P.sPlan)} mm below that level. Stack = upper end + gap + extension + gap + stem collar. Within 5 mm${opts.length ? ', closest first; none is chosen for you' : ': none'}:</p>`));
      const ob = el('<div class="row" style="flex-wrap:wrap"></div>');
      for (const o of opts) { const d = o.tot - P.sPlan, on = o.z.size === t.size && tumCollar(t) === o.c; ob.appendChild(btn(`${tumParts(o.z, o.c)} = ${fmm(o.tot)} mm (${d >= 0 ? '+' : ''}${fmm(d)})`, () => {
        if (!kcShareOk(slot, o.z.size)) return; setSize(slot, o.z.size); t.userSize = true; t.tum.c = o.c; const st = stemOf(t); if (t.stem && (!st || st.s.collarMm !== o.c)) delete t.stem; save(); panel(); draw(); }, on ? 'primary' : '')); }
      box.appendChild(ob);
    }
    const z0 = sizeOf(t); if (z0) box.appendChild(el(`<p class="note">${esc(pfrGapNote(z0, true))}</p>`));
    const as = el(`<details class="note warn"><summary>Assumed until Chunli confirms (${(f.assumptions || []).length})</summary></details>`), ul = document.createElement('ul');
    for (const a of f.assumptions || []) { const li = document.createElement('li'); li.textContent = a; ul.appendChild(li); }
    as.appendChild(ul); box.appendChild(as);
  }
  if (isPfr(f)) {   // r32: the proximal femoral replacement's resection from the GT tip; options within 5 mm, none chosen
    const u = t.tum || (t.tum = { src: '', extent: null, margin: null }), tr = el('<div class="row"></div>');
    const sx = el('<select title="Where the tumour extent comes from"><option value="">Extent from: choose</option><option value="MRI">MRI</option><option value="CT">CT</option></select>');
    sx.value = TUM_SRC[u.src] ? u.src : '';
    const num = (key, ph) => { const i = el(`<input type="number" step="1" min="0" placeholder="${ph}" title="${ph}" aria-label="${ph}" style="width:11em">`); if (Number.isFinite(u[key])) i.value = u[key]; i.onchange = e => { const v = e.target.value === '' ? null : +e.target.value; u[key] = Number.isFinite(v) ? v : null; save(); panel(); draw(); }; return i; };
    sx.onchange = e => { u.src = e.target.value; save(); panel(); draw(); };
    tr.append(sx, num('extent', 'extent below the GT tip, mm'), num('margin', 'margin, mm')); box.appendChild(tr);
    const P = pfrPlan(t, side);
    if (!P.tp.ok) box.appendChild(el(`<p class="note warn">Resection not planned: ${esc(P.tp.why)}.</p>`));
    else if (!P.gt) box.appendChild(el(`<p class="note warn">Mark the tip of the ${SIDE[side]} greater trochanter (landmark list, 2 · Landmarks): the resection is measured from it.</p>`));
    else if (Number.isFinite(P.sPlan)) {
      const opts = pfrOptions(f, P.sPlan);
      box.appendChild(el(`<p class="note">Resection ${f1(P.tp.resection)} mm below the tip of the greater trochanter (${esc(P.tp.src)} extent ${f1(P.tp.extent)} + your margin ${f1(P.tp.margin)}). On this film the tip lies ${f1(Math.abs(P.sGT))} mm ${P.sGT >= 0 ? 'below' : 'above'} this implant's ${esc(pfrLevelTxt(t))}, so the osteotomy is ${fmm(P.sPlan)} mm below that neck-0 level. Stack = body + gap + extension + gap + stem collar. Within 5 mm${opts.length ? ', closest first; none is chosen for you' : ': none'}:</p>`));
      const ob = el('<div class="row" style="flex-wrap:wrap"></div>');
      for (const o of opts) { const d = o.tot - P.sPlan, on = o.z.size === t.size && tumCollar(t) === o.c; ob.appendChild(btn(`${tumParts(o.z, o.c)} = ${fmm(o.tot)} mm (${d >= 0 ? '+' : ''}${fmm(d)})${o.z.trialSet === false ? ' *' : ''}`, () => {
        if (!kcShareOk(slot, o.z.size)) return; setSize(slot, o.z.size); t.userSize = true; t.tum.c = o.c; const st = stemOf(t); if (t.stem && (!st || st.s.collarMm !== o.c)) delete t.stem; save(); panel(); draw(); }, on ? 'primary' : '')); }
      box.appendChild(ob);
      if (opts.some(o => o.z.trialSet === false)) box.appendChild(el('<p class="note">* extension length in Chunli\'s code list but not in the trial set: check it is available.</p>'));
    }
    const z0 = sizeOf(t); if (z0) box.appendChild(el(`<p class="note">${esc(pfrGapNote(z0))}</p>`));
    const as = el(`<details class="note warn"><summary>Assumed until Chunli confirms (${(f.assumptions || []).length})</summary></details>`), ul = document.createElement('ul');
    for (const a of f.assumptions || []) { const li = document.createElement('li'); li.textContent = a; ul.appendChild(li); }
    as.appendChild(ul); box.appendChild(as);
    box.appendChild(el(`<p class="note">Heads: CoCrMo or ceramic, as on Chunli's other stems (same trunnion). ${hasKind('bipolar') ? `For a hemiarthroplasty choose Bipolar for this hip: the same Chunli bipolar shell, its ${esc((S.lib.heads[bipFams()[0].innerHead.kind] || {}).label || '')} head by shell size (${esc(bipHeadText(bipFams()[0]))}; Sami, 29 Oct 2026: the PFR uses the same bipolar shell; not confirmed by Chunli).` : 'A bipolar shell (Sami, 29 Oct 2026: the same Chunli shell) needs the Addis Ortho bipolar head range, which is not open to your account.'}</p>`));   // Fable P3-1
  }
  if (isTumourBody(f)) {   // tumour resection (r22): MRI/CT extent and the surgeon's margin; options within 5 mm, none chosen
    const u = t.tum || (t.tum = { src: '', extent: null, margin: null }), tr = el('<div class="row"></div>');
    const sx = el('<select title="Where the tumour extent comes from"><option value="">Extent from: choose</option><option value="MRI">MRI</option><option value="CT">CT</option></select>');
    sx.value = TUM_SRC[u.src] ? u.src : '';
    const num = (key, ph) => { const i = el(`<input type="number" step="1" min="0" placeholder="${ph}" title="${ph}" aria-label="${ph}" style="width:9em">`); if (Number.isFinite(u[key])) i.value = u[key]; i.onchange = e => { const v = e.target.value === '' ? null : +e.target.value; u[key] = Number.isFinite(v) ? v : null; save(); panel(); draw(); }; return i; };
    sx.onchange = e => { u.src = e.target.value; save(); panel(); draw(); };
    tr.append(sx, num('extent', `extent ${tumSide(f)} joint line, mm`), num('margin', 'margin, mm')); box.appendChild(tr);
    if (S.kc && (slot === 'femur' || slot === 'tibia') && S.kc.shared.size[slot]) { const z = S.kc.shared.size[slot]; box.appendChild(el(`<p class="note">Knee case: one size for all its films (${esc(z.size)}${z.byHand ? ', chosen by hand' : ''}); an option picked here changes it on every film (asked first when chosen by hand).</p>`)); }   // Fable r43 P2-2
    const tp = tumPlan(t);
    if (!tp.ok) box.appendChild(el(`<p class="note warn">Resection not planned: ${esc(tp.why)}.</p>`));
    else {
      const opts = tumOptions(f, t, tp.resection);
      const z0 = sizeOf(t), lw = z0 ? tumLenWarn(f, z0) : '';
      box.appendChild(el(`<p class="note">Resection ${f1(tp.resection)} mm ${tumSide(f)} the joint line (${esc(tp.src)} extent ${f1(tp.extent)} + your margin ${f1(tp.margin)}). Osteotomy = body (joint line to the stem junction) + the gap at each taper junction + extension + stem collar. For body ${esc(tumBodyNo(t.size))}# (its width; change it with the size list) within 5 mm${opts.length ? ', closest first; none is chosen for you' : ': none'}:</p>`));
      if (lw) box.appendChild(el(`<p class="note warn">${esc(lw)}</p>`));
      if (z0 && tumGapNote(z0)) box.appendChild(el(`<p class="note">${esc(tumGapNote(z0))}</p>`));
      const ob = el('<div class="row" style="flex-wrap:wrap"></div>');
      for (const o of opts) { const d = o.tot - tp.resection, on = o.z.size === t.size && tumCollar(t) === o.c; ob.appendChild(btn(`${tumParts(o.z, o.c)} = ${fmm(o.tot)} mm (${d >= 0 ? '+' : ''}${fmm(d)})${o.z.trialSet === false ? ' *' : ''}`, () => {
        if (!kcShareOk(slot, o.z.size)) return; setSize(slot, o.z.size); t.userSize = true; t.tum.c = o.c; const st = stemOf(t); if (t.stem && (!st || st.s.collarMm !== o.c)) delete t.stem; save(); panel(); draw(); }, on ? 'primary' : '')); }
      box.appendChild(ob);
      if (opts.some(o => o.z.trialSet === false)) box.appendChild(el('<p class="note">* extension length in Chunli\'s code list but not in the trial set: check it is available.</p>'));
    }
    if (f.schematic) box.appendChild(el('<p class="note warn">Schematic body from the technique\'s dimension table: Chunli has no template sheet for it. Lengths are projected on this film, scaled by the knee ball.</p>'));
  }
  if (f.stems) {   // stem extension (r20): only the sizes the maker's sheet draws for this component size
    const z = sizeOf(t), sf = S.fam[f.stems], sr = el('<div class="row"></div>'), sx = el('<select title="Stem extension"></select>');
    sx.add(new Option('No stem extension', ''));
    const cWant = (isTumourBody(f) || isPfr(f) || isPhr(f)) && t.tum && Number.isFinite(t.tum.c) ? t.tum.c : null;   // a tumour body: the stems with the collar picked
    for (const q of sf ? sf.sizes.filter(q => (z.stemSizes || []).includes(q.size) && (cWant === null || q.collarMm === cWant)) : []) sx.add(new Option(`Stem ${q.label || q.size}`, q.size));
    if (t.stem && ![...sx.options].some(o => o.value === t.stem.size)) { const o = new Option(`Stem ${t.stem.size} (not available)`, t.stem.size); o.disabled = true; sx.add(o); }
    sx.value = t.stem ? t.stem.size : '';
    sx.onchange = e => {
      if (e.target.value) t.stem = stemPick(f, e.target.value); else delete t.stem;
      const sh = S.kc && S.kc.shared.size[slot]; if (sh && sh.fam === t.fam) { if (t.stem) sh.stem = { ...t.stem }; else delete sh.stem; }   // one stem for both films
      save(); panel(); draw();
    };
    sr.appendChild(sx); box.appendChild(sr);
    const why = extWhy(t); if (why) box.appendChild(el(`<p class="note warn">Stem not drawn: ${esc(why)}.</p>`));
    if (why && /outline has changed/.test(why)) sr.appendChild(btn('Use the new outline', () => { t.stem = stemPick(f, t.stem.size); const sh = S.kc && S.kc.shared.size[slot]; if (sh && sh.fam === t.fam) sh.stem = { ...t.stem }; save(); panel(); draw(); }));
    else if (t.stem) { const st = stemOf(t); box.appendChild(el(`<p class="note">Stem ${esc(st.s.label || st.s.size)}: ${st.s.diameterMm} mm, ${st.s.drawnLengthMm} mm from the junction to the tip as drawn (catalogue name ${st.s.nominalLengthMm} mm). Check the canal width at the tip and mid-stem with the ruler.</p>`)); }
  }
  if (COARSE && S.active === slot && mm()) {   // on a phone: nudge and turn by buttons (no arrow keys)
    const nudge = (dx, dy, da) => ev => { ev.stopPropagation(); const v = G.rot(G.P(dx * pxOf(0.5), dy * pxOf(0.5)), -ROT()); t.x += v.x; t.y += v.y; t.angle += G.rad(da * 0.5); save(); draw(); };
    const np = el('<div class="row nudge"></div>');
    np.append(btn('◀', nudge(-1, 0, 0)), btn('▲', nudge(0, -1, 0)), btn('▼', nudge(0, 1, 0)), btn('▶', nudge(1, 0, 0)), btn('⟲', nudge(0, 0, -1)), btn('⟳', nudge(0, 0, 1)));
    box.appendChild(np);
  }
  const ra = el('<div class="row"></div>');
  if (f.kind === 'stem') {
    const c = lmGeom('canal' + side), why = !c ? `mark the ${SIDE[side]} femoral canal first` : lmProblem('canal' + side) ? 'the canal line runs across the femur: place it again' : '';
    ra.appendChild(btn('Align to canal', () => { if (guardedStem() && (exOpen().length || !canalReviewed(side))) return prompt(`Not aligned: ${exOpen().length ? 'confirm or dismiss the possible hardware first' : `the ${SIDE[side]} canal line is ${canalFlag(side)}`}.`); const H0 = corAt(t); if (alignStem(t, side)) setHeadLevel(t, side, G.dot(H0, vertical())); save(); panel(); draw(); }, '', !!why, why || 'Turn the stem onto the canal line and centre it, head height kept'));
    if (refOther() && side === S.side) ra.appendChild(btn(`Copy the ${SIDE[other(side)]} stem`, () => mirrorOther(), '', !S.tpl['stem' + other(side)], `Same stem, head and neck as the ${SIDE[other(side)]} plan, neck cut at the same height above the lesser trochanter (this film is not used for sizing)`));
    else if (isPfr(f)) ra.appendChild(btn('Place on the head centre', () => { pfrPlace(slot); save(); panel(); draw(); }, '', false, 'The head centre on the cup centre (or the native head), the axis along the canal when it is marked; the length is chosen from the resection options'));   // r32
    else ra.appendChild(btn('Auto size and place', () => { guardStemOp(side, () => { t.userSize = false; matchHeadToCup(side); planStem(side, { keepFam: !!t.userFam }, stemTarget(side)); }); save(); panel(); draw(); if (guardedStem() && S.notes['fit' + side] && S.notes['fit' + side].none) prompt(`The stem is not changed: ${S.notes['fit' + side].why}.`); }, '', !c || !!why, S.set.stemRule === 'offset' ? 'Size by canal fill and restore the femoral offset with the neck' : 'Size by canal fill, the head centre on the cup centre'));
  } else ra.appendChild(btn('Auto place', () => { autoPlace(slot); save(); panel(); draw(); }));
  if (!(viewOf() === 'LAT' && f.kind.startsWith('tka'))) ra.appendChild(btn('Mirror outline', () => { t.flip = !t.flip; t.userFlip = true; save(); draw(); }, '', false, 'Only for an unusual film: the outline normally faces the right way by itself'));
  ra.appendChild(btn(t.on ? 'Hide' : 'Show', () => { t.on = !t.on; save(); panel(); draw(); }));
  ra.appendChild(btn('Remove', () => { delete S.tpl[slot]; if (S.active === slot) S.active = null; save(); panel(); draw(); }));
  box.appendChild(ra);
  const sz = sizeOf(t);
  if (isPfr(f)) box.appendChild(el('<p class="note">Not sized by canal fill: a tumour body. Check the stem against the canal with the ruler.</p>'));   // r32
  else if (f.kind === 'stem' && S.notes['fit' + side] && S.notes['fit' + side].none) box.appendChild(el(`<div class="warnbox">Size not chosen automatically: ${esc(S.notes['fit' + side].why)}. Choose it by eye against the canal.</div>`));
  else if (f.kind === 'stem' && S.notes['fit' + side] && S.notes['fit' + side].copied) box.appendChild(el(`<p class="note">Copied from the ${SIDE[other(side)]} plan; this film is not used for sizing.</p>`));
  else if (f.kind === 'stem' && canalOf(side)) {   // the fit as the stem sits now, never the numbers from when it was sized (Astra, 11 Oct)
    const fn = S.notes['fit' + side], tr = canalTraceFor(side), cf = tr ? stemFit(t, tr) : null, ev = cf && fitIsEvidence(cf, tr, stemLen(t, tr));
    const tight = cf ? (cf.maxIn > 0.5 ? `${f1(cf.maxIn)} mm past the inner cortex` : cf.maxIn >= -0.5 ? 'touching the inner cortex' : `${f1(-cf.maxIn)} mm clear of the inner cortex`) : '';
    if (!cf) box.appendChild(el('<p class="note">Canal fill not checked: the inner cortex was not traced along this stem.</p>'));
    else if (cf.maxIn > 0.5) box.appendChild(el(`<div class="warnbox">As placed now: ${tight} at the tightest level.</div>`));
    else if (fn && fn.size === t.size && !fn.tooBig && !fn.largerUnchecked && ev) box.appendChild(el(`<p class="note">Size ${esc(t.size)} chosen by canal fill${fn.by === 'cup' ? ' (the version and neck by the head-on-cup rule)' : ''}; as placed now ${tight} at the tightest of ${cf.n} levels over ${Math.round(cf.coveredMm)} mm.</p>`));
    else box.appendChild(el(`<p class="note">As placed now: ${tight} at the tightest level${ev ? '' : ' (checked over part of the stem only)'}${fn && fn.largerUnchecked && fn.size === t.size ? '; larger sizes could not be checked (the canal was not traced far enough down)' : ''}.</p>`));
  }
  if (f.kind === 'stem' && S.notes['offset' + side]) box.appendChild(el(`<p class="note">${esc(cap(S.notes['offset' + side]))}.</p>`));
  if (f.kind === 'cup' && S.notes['mirrorMissing' + side] && !mirrorTarget(side)) box.appendChild(el(`<p class="warnbox">Not mirrored: the ${SIDE[other(side)]} hip is not marked, so this cup went to the native head centre. Mark the ${SIDE[other(side)]} femoral head, then press Mirror.</p>`));
  const cf = S.notes['cupFail' + side];
  if (f.kind === 'cup' && cf && cf.size === t.size && cf.fam === t.fam) box.appendChild(el(`<div class="${cf.dir === 'above' ? 'badbox' : 'warnbox'}">${esc(cupFailText(cf, f))}</div>`));
  if (f.kind === 'cup' && S.notes['cupRule' + side]) box.appendChild(el(`<p class="note">Placed by ${{ anatomic: 'native centre height, medial edge at the teardrop', teardrop: 'the teardrop technique (lowest rim on the teardrop line)', head: 'the native head centre', mirror: 'the other hip, mirrored', target: 'the target you set (revision)' }[S.notes['cupRule' + side]]}${S.notes['cupAnat' + side] ? ` (${esc(anatText(S.notes['cupAnat' + side], side))})` : ''}${S.notes['cupSize' + side] ? `; size from ${esc(S.notes['cupSize' + side])}` : ''}.</p>`));
  box.appendChild(el(`<p class="note">${esc(f.note || '')}${f.verified ? '' : f.catalogueChecked ? ` <b>${esc(CAT_CHECKED)}.</b>` : ' <b>Not yet confirmed by the manufacturer.</b>'}</p>`));
  if (f.kind === 'cup') box.appendChild(el(`<p class="note">Size ${sz.size}: outside ${sz.catalogueOuter || '?'} mm in the catalogue, ${sz.outerDiameter} mm on the ${drawnFrom(f)}${sz.catalogueOuter && Math.abs(sz.outerDiameter - sz.catalogueOuter) > 0.5 ? ' (<b>the two differ: to be confirmed</b>)' : ''}.${sz.liner ? ` Liner ${linerTxt(f, sz.liner)}${sz.maxHead ? `, head up to ${headMm(sz.maxHead)}` : ''}.` : ''}${cupHeads(t) ? ` Heads ${headsMm(cupHeads(t))}.` : ''}${headsAcrossLiners(t) ? ` ${HEADS_ACROSS[0]}: ${HEADS_ACROSS[1]}.` : ''}</p>`));
  if (f.heldBack && f.heldBack.length) box.appendChild(el(`<p class="note">Not offered: ${f.heldBack.map(h => `${h.size} (${esc(h.why)})`).join('; ')}.</p>`));
  return box;
}

// ======================= the knee case (release 1, 14 Oct 2026) =======================
// Sami, 14 Oct: "for a single knee we would like to plan the templating for one patient from ap with sphere on medial
// femoral condyle, true lateral with sphere anterior to center of patella, skyline xray and then full leg pelvis to ankle
// standing xray or supine CT scout". DESIGN-2026-10-14-knee-case.md with its binding amendments (Astra and Sami, 14 Oct),
// contract section 12. One patient, one knee, up to four films (AP, Lateral, Skyline, Long leg), each its own image,
// calibration (with where it came from), landmarks and placements; one shared plan: one femoral and one tibial size for
// both the AP and the lateral, the surgeon's own resection depths, slope and target, the patella decision, the recorded
// patellofemoral assessment. One film is on screen at a time (the others keep their plan as data and their original bytes,
// decoded again when shown); the case is one unit for saving (projects.js), under its own draft id, never an image hash.
// Measured anatomy, axes taken from another film or assumed, the surgeon's targets and the geometric simulation of the
// placed components are kept apart in the summary; nothing chooses an insert or the patella for the surgeon.
const KC_ROLES = PJ.ROLES, KC_NAME = PJ.ROLE_NAME, KC_WF = PJ.ROLE_WF;
// the film buttons (Sami, 15 Oct: the usual set is a standing AP, a lateral and a skyline; the long film is optional, a standing
// long leg or, when there is none, a CT scout)
const KC_TAB = { ap: 'Standing AP', lat: 'Lateral', sky: 'Skyline', long: 'Long leg (standing) or CT scout' };
const hexId = n => { const a = new Uint8Array(n); crypto.getRandomValues(a); return [...a].map(b => b.toString(16).padStart(2, '0')).join(''); };
const KC_SHARED = () => ({ size: { femur: null, tibia: null }, res: { femDistal: null, tibial: null, slope: null, tibRef: 'less', vca: null, prov: {}, asked: null }, rot: null, hkaTarget: null, patella: 'undecided', pf: { grade: '', facet: '', dysplasia: '', tracking: '', note: '' }, longSrc: null, caseType: 'primary' });
const KC_CAL_HOW = { ap: 'the 25 mm ball on the medial femoral condyle of the standing AP (at joint level)', lat: 'the 25 mm ball anterior to the centre of the patella', sky: 'no scale needed: the skyline is reviewed and its angles measured without one', long: 'optional. A standing long leg: its ball if there is one, else scaled from the AP by the condylar width marked on both films (approximate). A CT scout: its DICOM pixel spacing (shown with where it comes from); a supine CT scout\'s alignment is not weight-bearing' };
const META_KEEP = ['PatientName', 'PatientID', 'InstitutionName', 'PatientAge', 'PatientBirthDate', 'StudyDate', 'SpecificCharacterSet', 'Modality', 'ImageLaterality', 'Laterality', 'ViewPosition', 'BodyPartExamined', 'SeriesDescription', 'StudyDescription'];
const metaSub = m => { const o = {}; for (const k of META_KEEP) if (m && m[k] !== undefined && m[k] !== '') o[k] = m[k]; return o; };
const clone = v => JSON.parse(JSON.stringify(v));
const bufOf = u8 => (u8.byteOffset === 0 && u8.byteLength === u8.buffer.byteLength ? u8.buffer : u8.slice().buffer);
function kcNew(side, cid = hexId(8)) { return { cid, side: side === 'L' ? 'L' : 'R', films: {}, active: null, shared: KC_SHARED(), notices: [], pt: { name: '', recordNo: '', hospital: '', age: null } }; }
const kcNotice = t => { const K = S.kc; if (!K) return; K.notices = [...K.notices, t].slice(-20); };
// a film's plan as data: the one on screen as it is now, the others as kept (without the save time)
function kcFilmState(role) {
  const K = S.kc, f = K && K.films[role]; if (!f) return null;
  if (role === K.active && S.img && !f.state) { const st = fullState(); delete st.saved; st.pendingSend = null; return st; }
  return f.state;
}
function kcStash() {
  const K = S.kc, f = K && K.films[K.active];
  if (!f || !S.img || f.state) return;
  const st = fullState(); delete st.saved; st.pendingSend = null; f.state = st; f.stateJ = JSON.stringify(st);
}
// the case's revision (stateKey): every film's image and plan, the shared plan; not which film is shown. Every film's plan
// goes in as the same JSON text, the one on screen as well as the others (Astra, 15 Oct, P2 6: the film on screen went in
// as an object, so merely switching films changed the fingerprint)
function kcState() {
  const K = S.kc, out = [K.side, K.shared, K.notices, K.pt];   // (K.pt: the patient's details, saved with the case: a change of them is a change)
  for (const r of KC_ROLES) { const f = K.films[r]; if (!f) continue; out.push([r, f.image.sha256, !!f.missing, f.qc, r === K.active && S.img && !f.state ? JSON.stringify(kcFilmState(r)) : (f.stateJ || (f.stateJ = JSON.stringify(f.state)))]); }
  return out;
}
function kcFilmSetup(role) {
  const K = S.kc; S.wf = KC_WF[role]; S.side = K.side; S.sideSet = true;
  S.ll = role === 'long' ? llIn(S.ll || { zone: 'align' }, 'kneeLong') : null; if (S.ll) S.wf = llWf(S.ll.zone);   // r44: the long film's plan is the long-leg plan (its alignment first, as before)
  S.tpl = S.tplAll[S.wf] || (S.tplAll[S.wf] = {});
  if (role === 'long' && !K.shared.longSrc && String((S.img.meta || {}).Modality || '').toUpperCase() === 'CT') K.shared.longSrc = 'ctscout';
  kcApplySizes();
}
// The operated side of a knee case changed (the header's Side): one change for the whole case (Astra, 15 Oct, P2 4): the
// case's side, the film on screen and every other film's kept plan take it at once, and every film's "this is the right/left
// knee" check is cleared (it was ticked for the other side); one Undo step puts all of it back (toSnap: kcSideToFilms)
function kcSetSide(sd) {
  const K = S.kc; if (!K || (sd !== 'R' && sd !== 'L')) { $('side').value = S.side; return; }
  if (sd === K.side) return;
  K.side = sd; S.side = sd; S.sideSet = true; S.arm = null; llSideMoved();
  for (const f of Object.values(K.films)) if (f) f.qc = { ...f.qc, side: false };
  kcSideToFilms();
  kcNotice(`Operated side changed to ${SIDE[sd]} at ${hhmmNow()}: confirm each film's side again`);
  prompt(`This knee case is now the ${SIDE[sd]} knee: every film's side check is cleared; confirm each film again (Calibrate).`);
  refreshFlips(); save(); panel(); draw(); PJ.caseChanged();
}
// every film of the case carries the case's side (the film on screen, the kept plans of the others, a missing film's)
function kcSideToFilms() {
  const K = S.kc; if (!K) return;
  S.side = K.side; $('side').value = K.side;
  for (const f of Object.values(K.films)) if (f && f.state && f.state.side !== K.side) { f.state = { ...f.state, side: K.side, ...(f.role === 'long' ? { ll: { zone: 'align', ...(f.state.ll || {}), hs: (f.state.ll && f.state.ll.hs) || f.state.side } } : {}) }; f.stateJ = null; }   // Fable r44 P2-1: the long film's head stays the side it was marked for
}
// one femoral and one tibial size for the AP and the lateral (Astra, case model): the film shown takes the shared ones
function kcSizeSet(slot, t, byHand) { if (!S.kc || !t || S.kc.active === 'long') return;   // r44: the long film draws the case's size, never sets it
  const cur = S.kc.shared.size[slot]; if (!byHand && cur && cur.byHand) return; S.kc.shared.size[slot] = { fam: t.fam, size: t.size, byHand: !!byHand || !!(S.kc.shared.size[slot] && S.kc.shared.size[slot].byHand), ...(t.stem ? { stem: { ...t.stem } } : {}) }; }
// Fable r43 P2-2: the tumour resection of a knee case's part, as entered on another film of the case (the shared size carries
// family and size only: projects.js keeps no other field there). → {tum, role, size, userSize} or null
function kcTumFrom(slot, fam) {
  const K = S.kc; if (!K) return null;
  for (const r of ['ap', 'lat']) {
    if (r === K.active) continue;
    const st = kcFilmState(r), o = st && st.tplAll && st.tplAll[KC_WF[r]], u = o && o[slot];
    if (u && u.fam === fam && u.tum && tumPlan(u).ok) return { tum: clone(u.tum), role: r, size: u.size, userSize: !!u.userSize, stem: u.stem ? u.stem.size : '' };
  }
  return null;
}
// a tumour option picked on one film of a knee case changes the size of every film: asked when the shared size was chosen by hand
function kcShareOk(slot, size) {
  if (llKcLong() && (slot === 'femur' || slot === 'tibia')) { prompt(LL_KC_SIZE); return false; }   // r44
  const z = S.kc && (slot === 'femur' || slot === 'tibia') ? S.kc.shared.size[slot] : null;
  if (!z || !z.byHand || String(z.size) === String(size)) return true;
  return confirm(`One ${slot === 'tibia' ? 'tibial' : 'femoral'} size is shared by every film of this knee case: ${z.size}, chosen by hand. Change it to ${size} for every film?`);
}
function kcApplySizes() {
  const K = S.kc; if (!K) return;
  for (const slot of ['femur', 'tibia']) {
    const t = S.tpl[slot], z = K.shared.size[slot]; if (!t || !z) continue;
    const f = S.fam[z.fam]; if (!f || !f.sizes.some(q => q.size === z.size)) continue;
    const was = `${t.fam}|${t.size}|${t.stem ? t.stem.size : ''}`, now = `${z.fam}|${z.size}|${z.stem ? z.stem.size : ''}`;
    t.fam = z.fam; t.size = z.size; if (z.byHand) t.userSize = true;
    if (z.stem) t.stem = { ...z.stem }; else delete t.stem;   // one stem extension for both films (r20)
    kneeFamFix(t);
    // Fable r43 P2-2: the resection entered on the other film; r44 (Fable r43 recheck P3): and taken again when the other film's
    // later pick made the shared size since this film was shown (its own copy was stale; a pick of the same size on both is kept)
    if (isTumourBody(f)) { const g = kcTumFrom(slot, t.fam); if (g && (!tumPlan(t).ok || (z.byHand && was !== now && `${z.fam}|${g.size}|${g.stem}` === now && JSON.stringify(g.tum) !== JSON.stringify(t.tum)))) t.tum = g.tum; }
  }
}
// a knee component put on another family: its stem extension only if the new family takes the same stems, its tray
// thickness one the new tray lists (r20: CCK 9-19, LDK 11-20, XN 10-18)
function kneeFamFix(t, oldFam) {
  const f = S.fam[t.fam]; if (!f) return;
  if (t.stem && (!f.stems || (oldFam && S.fam[oldFam] && S.fam[oldFam].stems !== f.stems))) delete t.stem;
  if (f.kind === 'tka-tibia' && Array.isArray(f.thicknesses) && !f.thicknesses.includes(t.thick)) t.thick = f.thicknesses.reduce((b, v) => (Math.abs(v - (t.thick || 10)) < Math.abs(b - (t.thick || 10)) ? v : b));
}
const K_NOLONG = () => !!S.kc && !(S.kc.films.long && !S.kc.films.long.missing);   // the usual set: standing AP, lateral, skyline (Sami, 15 Oct)
const kcCtScout = () => !!(S.kc && (S.wf === 'kneeLong' || llKcLong()) && S.kc.shared.longSrc === 'ctscout');   // r44: in every zone of the long film
// what a component placed by the rules depended on: the axes taken from the long leg, the scale, the surgeon's values
function kcDepKey() {
  const t = S.wf === 'kneeAP' ? kcTransfer() : null, r = kneeVals();
  const lf = S.wf === 'kneeAP' && S.kc.films.long ? S.kc.films.long.image.sha256.slice(0, 16) : null;   // a replaced long leg flags too
  return JSON.stringify([lf, t && t.amaF !== null ? Math.round(t.amaF * 10) : null, t && t.amaT !== null ? Math.round(t.amaT * 10) : null, mm() ? +mm().toFixed(5) : null, r.femDistal, r.tibial, r.slope, r.tibRef, r.vca]);
}
function kcDepRow(R, t, what) { if (t && t.on && t.dep && t.dep !== kcDepKey()) R.push(['Placed before a change', `the ${what} was placed before the long leg, the scale or your values changed: press Auto template again, or check it`, 'warn']); }

// ---------- a film's landmarks read from its plan (the film on screen or another) ----------
function kcGeo(role) {
  const st = kcFilmState(role); if (!st) return null;
  const g = k => lmGeomOf(k, st.lm ? st.lm[k] || (role === 'long' && k === 'hip' ? st.lm['head' + ((st.ll && st.ll.hs) || st.side)] : undefined) : undefined);   // Fable r44 P3-5: a head marked in the long film's hip zone
  return { st, g, lat: latDirOf(st.cranial, st.mirrored, S.kc.side), up: ({ up: G.P(0, -1), down: G.P(0, 1), left: G.P(-1, 0), right: G.P(1, 0) })[st.cranial] || G.P(0, -1), k: st.cal && st.cal.mmPerPx && (st.cal.method !== 'dicom' || st.cal.ct === 'scanner') && st.cal.confirmed !== false ? st.cal.mmPerPx : null };
}
function kcLongAngles() {
  const L = kcGeo('long'); if (!L) return null;
  const g = L.g;
  return { ...longLegAngles({ hip: g('hip'), knee: g('knee'), ankle: g('ankle'), cm: g('condMed'), cl: g('condLat'), pm: g('platMed'), pl: g('platLat'), fs: g('femShaft'), ts: g('tibShaft') }, L.lat, L.up), L };
}
// the long leg's anatomic-to-mechanical angles, for the AP's axes, when both films have the same shaft section marked
function kcTransfer() {
  const K = S.kc; if (!K || !K.films.long) return null;
  const A = kcLongAngles(); if (!A) return null;
  return { amaF: lmGeom('femShaft') ? A.amaF : null, amaT: lmGeom('tibShaft') ? A.amaT : null, supine: K.shared.longSrc === 'ctscout' };
}
// the long leg's scale from the AP is out of date when the AP film, its calibration or either condylar width changed
function kcXferStale() {
  const K = S.kc, c = S.cal; if (!K || !c || c.method !== 'xfer') return '';
  const ap = K.films.ap; if (!ap || ap.image.sha256 !== c.apSha) return 'the AP film was replaced or taken out';
  const st = kcFilmState('ap'), ac = st && st.cal;
  if (!ac || ac.method === 'dicom' || ac.method === 'xfer' || ac.confirmed === false || Math.abs(ac.mmPerPx - c.apMmPerPx) > 1e-9) return 'the AP\'s calibration changed';
  const aw = lmGeomOf('condW', st && st.lm ? st.lm.condW : undefined), lw = lmGeom('condW');
  if (!aw || Math.abs(G.dist(aw.a, aw.b) - c.apWidthPx) > 0.01) return 'the condylar width on the AP changed';
  if (!lw || Math.abs(G.dist(lw.a, lw.b) - c.widthPx) > 0.01) return 'the condylar width on the long leg changed';
  return '';
}
function kcXfer() {
  const K = S.kc, st = kcFilmState('ap'), ac = st && st.cal, ap = K.films.ap;
  if (!ap || ap.missing) return prompt('Add the AP film first: the long leg is scaled from its calibration.');
  if (!ac || ac.method === 'dicom' || ac.method === 'xfer' || ac.confirmed === false) return prompt('Calibrate the AP film on its ball first (and confirm it).');
  const aw = lmGeomOf('condW', st && st.lm ? st.lm.condW : undefined), lw = lmGeom('condW');
  if (!aw) return prompt('Mark the femoral condylar width on the AP film first.');
  if (!lw) { armLm('condW'); return prompt('Mark the femoral condylar width on this long leg (the same two points as on the AP), then press "Scale from the AP" again.'); }
  const k = scaleFromAp(G.dist(aw.a, aw.b), ac.mmPerPx, G.dist(lw.a, lw.b));
  if (!k) return prompt('The two widths do not give a plausible scale: check the condylar width on both films.');
  S.cal = { method: 'xfer', from: 'ap', apSha: ap.image.sha256, apMmPerPx: ac.mmPerPx, apWidthPx: G.dist(aw.a, aw.b), widthPx: G.dist(lw.a, lw.b), mmPerPx: k, note: 'scaled from the AP: approximate, uncertainty not validated' };
  afterCal(); prompt('Scaled from the AP by the condylar width: approximate (rotation, flexion and projection differ between the films; the uncertainty is not validated). Angles do not depend on it.');
}

// ---------- results: this film's place in the case, the long leg, the skyline, the case summary ----------
function kcFilmRows(R) {
  const K = S.kc, f = K.films[K.active]; if (!f) return;
  R.push([`Knee case: ${KC_NAME[K.active]} film`]);
  if (!f.qc.side) R.push(['Side', `not yet confirmed that this is the ${SIDE[K.side]} knee (marker checked): tick it under Calibrate`, 'warn']);
  if (!f.qc.quality) R.push(['Image quality', 'not yet confirmed (rotation, flexion, the whole region on the film): tick it under Calibrate', 'warn']);
  if (f.qc.ptSame) R.push(['Patient', 'this X-ray names another patient (taken elsewhere); added as this case\'s patient by you', 'warn']);
  if (K.active === 'long' && !K.shared.longSrc) R.push(['Long leg', 'say whether it is a standing film or a supine CT scout (under Calibrate)', 'warn']);
  if (kcCtScout()) R.push(['Long film', 'supine CT scout: alignment not weight-bearing (not a standing film)', 'warn']);
}
const fDeg = x => (x === null || x === undefined ? 'not measured' : `${f1(x)}°`);
function longRows(R, A, k, pre = '') {
  const sup = S.kc && S.kc.shared.longSrc === 'ctscout' ? ' (supine CT scout: not weight-bearing)' : '';
  R.push([`${pre}HKA${sup}`, A.hka === null ? 'not measured: hip, knee and ankle centres' : `${f1(A.hka)}° (${Math.abs(A.dev) < 0.05 ? 'straight' : `${f1(Math.abs(A.dev))}° ${A.varus ? 'varus' : 'valgus'}`}; 180° = straight)`]);
  R.push([`${pre}mLDFA`, fDeg(A.mldfa)]);
  R.push([`${pre}MPTA`, fDeg(A.mpta)]);
  R.push([`${pre}JLCA`, A.jlca === null ? 'not measured' : `${f1(A.jlca)}°${A.converge ? ` (the joint lines converge ${A.converge}ly)` : ' (parallel)'}`]);
  R.push([`${pre}Femoral anatomic-mechanical angle`, A.amaF === null ? 'not measured: hip, knee and femoral axis' : `${f1(Math.abs(A.amaF))}° (mechanical axis ${A.amaF >= 0 ? 'medial' : 'lateral'} of the shaft at the hip)`]);
  R.push([`${pre}Tibial anatomic-mechanical angle`, A.amaT === null ? 'not measured: knee, ankle and tibial axis' : `${f1(Math.abs(A.amaT))}°`]);
  if (k && A.madPx !== null) R.push([`${pre}Mechanical axis deviation`, `${f1(A.madPx * k)} mm ${A.varus ? 'medial' : 'lateral'} of the knee centre${S.cal && S.cal.method === 'xfer' ? ' (scale from the AP: approximate)' : ''}`]);
}
function kneeLongResults(R, k) {
  R.push([`Long film: measured alignment (angles need no scale)${kcCtScout() ? ', supine CT scout: alignment not weight-bearing (not standing)' : ''}`]);
  const g = key => (lmProblem(key) ? null : lmGeom(key));
  const A = longLegAngles({ hip: g('hip'), knee: lmGeom('knee'), ankle: g('ankle'), cm: lmGeom('condMed'), cl: lmGeom('condLat'), pm: lmGeom('platMed'), pl: lmGeom('platLat'), fs: lmGeom('femShaft'), ts: lmGeom('tibShaft') }, latVec(), cranVec());
  if (A.swapCond || A.swapPlat) R.push(['Check landmarks', `${[A.swapCond && 'condyles', A.swapPlat && 'plateaus'].filter(Boolean).join(' and ')} clicked medial for lateral: the page used the film's sides; check the side if that is wrong`, 'bad']);
  longRows(R, A, k);
}
// ---------- r44 (30 Oct 2026): the long leg, hip and knee as one plan (DESIGN-2026-10-29-long-leg-ct-scout.md sections 5 and 7) ----------
// Sami, 30 Oct 2026: "1. ONE PLAN 2. THE SAME 3. CT SCOUT IS ALREADY CALIBRATED AND THE LONG LEG STANDIGN WITH SPHERE CAN BE
// CALIBERATED AS WELL. YES ALLOW IMPLANT 4. YES INCLUDE". "Long leg: hip and knee", for a standing long leg or a CT scout (and the
// plan of a knee case's long film): a hip zone (the hip plan: cup, stem, bipolar, PFR, with its landmarks) and a knee zone (the knee
// AP plan: femur and tray, CCK, LDK hinge, PTR, DFR with stems; the knee landmarks with the hip and ankle centres). Each zone is the
// existing plan while it is on screen (S.wf), so no rule is forked; the other zone is drawn with it (overlay), in the saved picture
// too. S.ll = {zone, base}: base is the plan type stored (the plan the film was in when this one was chosen; a knee case's long
// film: kneeLong), so a project's procedure never changes and an older page opens the base plan. A knee case's long film keeps its
// alignment view (zone 'align', the long film as before r44) and adds the hip and knee zones.
const LL_LABEL = 'Long leg: hip and knee';
const LL_ZONE = { align: 'Alignment', hip: 'Hip zone', knee: 'Knee zone' };
const LL_OK = { key: 'otherKnee', short: 'other knee', type: 'point', label: 'Other femur: lowest point of its distal condyles (total femur length)', hint: 'the more distal of its condyles, on the other leg', optional: true };
const LL_KC_SIZE = 'Knee case: the long film draws the case\'s one femoral and tibial size; change the size or the family on the AP or the lateral film (here you move and turn it).';
const llWf = z => (z === 'hip' ? 'hip' : z === 'knee' ? 'kneeAP' : 'kneeLong');
const llKcLong = () => !!(S.ll && S.kc && S.kc.active === 'long');
const wfBase = () => (S.ll ? S.ll.base : S.wf);
const wfLabel = () => (S.ll ? LL_LABEL : WF[S.wf].label);
const wfSel = () => (S.kc ? 'kc' : S.ll ? 'll' : S.wf);
function llZones(base = S.ll && S.ll.base) { return (base === 'kneeLong' ? ['align', 'hip', 'knee'] : ['hip', 'knee']).filter(z => z === 'align' || S.wfs.includes(llWf(z))); }
// a stored {zone} → {zone, base} (null: not a long-leg plan; a zone this account has no implants for: the first it has)
function llIn(v, base) {
  if (!v || typeof v !== 'object' || !['hip', 'kneeAP', 'kneeLong'].includes(base) || (base === 'kneeLong' && !S.kc)) return null;
  const zs = llZones(base); if (!zs.length) return null;
  return { zone: zs.includes(v.zone) ? v.zone : zs[0], base, hs: v.hs === 'R' || v.hs === 'L' ? v.hs : S.side };   // hs: the side the shared head circle is for (Fable r44 P2-1)
}
// fn run with another zone's plan on screen (read only: drawing, rows); the page's own state is put back
function inZone(z, fn) {
  const w = llWf(z); if (w === S.wf) return fn();
  const k0 = [S.wf, S.tpl, S.active]; S.tplAll[S.wf] = S.tpl; S.wf = w; S.tpl = S.tplAll[w] || {}; S.active = null;
  try { return fn(); } finally { [S.wf, S.tpl, S.active] = k0; }
}
const llTpl = z => (llWf(z) === S.wf ? S.tpl : S.tplAll[llWf(z)] || {});
const llTplAll = () => Object.assign({}, ...llZones().filter(z => z !== 'align').map(llTpl));
const llOn = t => (t && t.on !== false && S.fam[t.fam] ? S.fam[t.fam] : null);
// a total femur: the PFR in the hip zone with the DFR in the knee zone (placed, or chosen for them)
function llTF() {
  if (!S.ll || !S.choice) return false;
  const st = llTpl('hip')['stem' + S.side], fe = llTpl('knee').femur;
  return isPfr(llOn(st) || S.fam[S.choice.stem]) && isTumFemur(llOn(fe) || S.fam[S.choice['tka-femur']]);
}
const llLm = list => (llTF() ? [...list, LL_OK] : list);
// chosen in the Plan list: the zone of the plan the film is in (the hip, else the knee)
function llStart() {
  if (!S.img) { $('wf').value = wfSel(); return; }
  const z = S.wf === 'hip' || !S.wfs.includes('kneeAP') ? 'hip' : 'knee';
  if (!S.wfs.includes(llWf(z))) { $('wf').value = wfSel(); return prompt('No hip or knee implant range is open to your account.'); }
  S.ll = { zone: z, base: llWf(z), hs: S.side }; switchWf(llWf(z)); S.arm = null; S.allSizes = null;
  const lg = S.img.rows > 1.8 * S.img.cols || !!ctScoutOf(S.img.meta);
  $('wf').value = 'll'; save(); panel(); draw();
  prompt(`${LL_LABEL}: one plan, the ${llZones().map(q => LL_ZONE[q].toLowerCase()).join(' and the ')} drawn together; the zone is switched at the top of the panel.${lg ? '' : ' This film does not look like a long leg or a CT scout: check it is the one you meant.'} ${llImplWhy()}`.trim());
}
function llZone(z) {
  if (!S.ll || !llZones().includes(z) || S.ll.zone === z) return;
  S.ll = { ...S.ll, zone: z }; switchWf(llWf(z)); if (S.kc) kcApplySizes(); S.arm = null; S.allSizes = null;
  save(); panel(); draw();
  prompt(`${LL_ZONE[z]}${z === 'align' ? ': the long film\'s alignment (angles need no scale).' : `: ${z === 'hip' ? 'the hip plan (cup, stem, bipolar or PFR)' : 'the knee plan (femur and tray, CCK, hinge, PTR or DFR)'}; Auto template places this zone by its own rules.`} ${llImplWhy()}`.trim());
}
// why no implant is placed on this long film ('' when it may be): a ball, a known length or the CT scanner's scale is needed
// (Sami: "CT SCOUT IS ALREADY CALIBRATED AND THE LONG LEG STANDIGN WITH SPHERE CAN BE CALIBERATED AS WELL. YES ALLOW IMPLANT"); a
// machine estimate or the knee case's AP transfer (approximate) never carries an implant
function llImplWhy() { return !S.ll || S.ll.zone === 'align' ? '' : llScaleWhy() || llKcWhy(); }
// Fable r44 recheck P3: a side change with no head marked for the old side moves the shared head's side with it
const llSideMoved = () => { if (S.ll && !S.lm.hip && !S.lm['head' + S.ll.hs]) S.ll = { ...S.ll, hs: S.side }; };
const llKcWhy = () => (llKcLong() && S.ll.zone === 'knee' && !(S.kc.shared.size.femur || S.kc.shared.size.tibia) ? 'Knee case: choose the femur and the tray on the AP (or the lateral) first: the long film draws the case\'s one size.' : '');
// Fable r44 P2-2: while the film's scale is not one implants may use, the zones' implants placed earlier (on a machine estimate
// before "Long leg" was chosen, or before the scale changed) are kept in the plan but not drawn, listed, measured or picked
const llHide = () => !!(S.ll && llScaleWhy());
let llHidN = 0;
function llBare(fn) {
  if (!llHide()) return fn();
  const k0 = [S.tpl, S.tplAll], n = Object.keys({ ...(S.tplAll.hip || {}), ...(S.tplAll.kneeAP || {}), ...(S.wf === 'hip' || S.wf === 'kneeAP' ? S.tpl : {}) }).length;
  if (n) llHidN = n;
  S.tplAll = { ...S.tplAll, hip: {}, kneeAP: {} }; S.tpl = S.tplAll[S.wf] || {};
  try { return fn(); } finally { [S.tpl, S.tplAll] = k0; }
}
function llScaleWhy() {
  if (!S.ll) return '';
  const c = S.cal;
  if (!c) return 'No scale on this long film: no implant is placed (none can be drawn true size). Calibrate it on its ball or on a known length (a ruler on the film); a CT scout with Pixel Spacing takes the scanner\'s scale.';
  if (c.method === 'xfer') return 'This long film is scaled from the AP by the condylar width (approximate): no implant is placed on it. Calibrate it on its ball or a known length to place implants; the angles are measured as they are.';
  if (calEst(c)) return `This long film has a machine estimate only (${c.note || 'DICOM spacing'}): no implant is placed on it. Calibrate it on its ball or a known length.`;
  if (c.confirmed === false) return 'Confirm the ball first (at the top of the panel): implants are placed on a confirmed scale only.';
  return '';
}
function llCard() {
  const box = el(`<div class="choice llzone" id="llcard"><div class="ch-h">${esc(LL_LABEL)}: one plan, both zones drawn together</div></div>`);
  const g = el('<div class="ch-g" role="radiogroup" aria-label="Long leg zone"></div>');
  for (const z of llZones()) { const b = el(`<button type="button" class="ch-o" role="radio" aria-checked="${S.ll.zone === z}" data-zone="${z}"><span class="ch-l">${LL_ZONE[z]}</span></button>`); b.onclick = ev => { ev.stopPropagation(); llZone(z); }; g.appendChild(b); }
  box.appendChild(g);
  const n = t => box.appendChild(el(`<p class="note">${esc(t)}</p>`));
  n(S.ll.zone === 'align' ? 'Alignment: the long film\'s angles, as before; the hip and knee zones place implants on this film when it is scaled.' : 'Auto template places the zone on screen by its own rules (the hip\'s, or the knee AP\'s); one zone may be planned alone. The other zone is drawn with it: switch zone to move its implants.');
  const w = llImplWhy(); if (w) box.appendChild(el(`<div class="warnbox">${esc(w)}</div>`));
  if (llKcLong() && S.ll.zone === 'knee') n(LL_KC_SIZE);
  if (S.ll.zone !== 'align') n('Scale: a standing long leg by its ball or by a known length (a radiopaque ruler on a stitched film), equal choices; a CT scout by the scanner (supine: alignment not weight-bearing).');
  const pf = allFams('stem').find(isPfr), dk = KNEE_TYPES.find(k => k.id === 'dfr');
  if (pf && ktHeld(dk) && llZones().includes('hip') && llZones().includes('knee')) {
    const r = el('<div class="row"></div>'); r.appendChild(btn('Total femur (PFR + DFR)', llTotalFemur, llTF() ? 'on' : '', false, 'The proximal femoral replacement in the hip zone and the distal femoral replacement in the knee zone, on one femur'));
    box.appendChild(r);
    if (llTF()) n('Total femur: the connection between the PFR and the DFR is shown as a measured length only: connection part not confirmed by Chunli (ask for the total femur sheet).');
  }
  return box;
}
// Sami, 30 Oct 2026 ("4. YES INCLUDE"): the PFR in the hip zone and the DFR in the knee zone on one femur; no connector is drawn
function llTotalFemur() {
  const pf = allFams('stem').find(isPfr), dk = KNEE_TYPES.find(k => k.id === 'dfr');
  if (!S.ll || !pf || !ktHeld(dk)) return prompt('Total femur: the PFR and the DFR are not both open to your account.');
  const z0 = S.ll.zone;
  llZone('hip'); pickFamily('stem', pf.id);
  if (S.kc) { llZone(z0); return prompt('Total femur: the PFR chosen for the hip zone. In a knee case the DFR is chosen as the knee type on the AP film (the long film draws the case\'s parts). The connection is shown as a measured length only: connection part not confirmed by Chunli.'); }
  llZone('knee'); pickKneeType('dfr');
  save(); panel(); draw();
  prompt('Total femur (PFR + DFR): the PFR for the hip zone and the DFR for the knee zone, each placed by its own rules (Auto template in each zone); enter each tumour resection in its box. The length between them is measured only: connection part not confirmed by Chunli; ask for the total femur sheet.');
}
// a placed part's outline in image px (with its stem extension unless body only); the farthest point along up (hi) or down
function llPts(t, z, body) {
  return inZone(z, () => { const gm = geomOf(t); if (!gm) return []; const X = xf(t), rs = [...gm.rings]; if (!body && S.fam[t.fam].stems) { const sr = stemRings(t, gm); if (sr) rs.push(...sr); } return rs.flat().map(X); });
}
const llExt = (P, up, hi) => P.reduce((b, p) => (b === null || (hi ? G.dot(p, up) > G.dot(b, up) : G.dot(p, up) < G.dot(b, up)) ? p : b), null);
// the rows the long leg adds (every zone): HKA now and after the plan, leg length from the hip plan, the bone between the implants
function llRows(R, k) {
  R.push([`${LL_LABEL} (one plan)`]);
  const why = llImplWhy() || (llHide() ? llScaleWhy() : ''); if (why) R.push(['Implants', `${why}${llHide() && llHidN ? ` Implants placed earlier on this scale (${llHidN}) are kept in the plan but not drawn, listed or measured until the film is scaled.` : ''}`, 'warn']);
  if (S.ll.hs !== S.side && (S.lm.hip || S.lm['head' + S.ll.hs])) R.push(['Femoral head', `the femoral head circle was marked for the ${SIDE[S.ll.hs]} leg: the ${SIDE[S.side]} hip's head and the knee zone's hip centre are now marked each on its own`, 'warn']);   // Fable r44 P2-1
  if (!S.kc && ctScoutOf((S.img && S.img.meta) || {})) R.push(['Long film', 'supine CT scout: alignment not weight-bearing (not a standing film)', 'warn']);
  const H_ = llTpl('hip'), N_ = llTpl('knee'), st = H_['stem' + S.side], cup = H_['cup' + S.side], fe = N_.femur, ti = N_.tibia;
  const nm = t => { const f = llOn(t); return f ? `${f.label.replace(/^Addis Ortho\s+/, '')} ${t.size}` : null; };
  if (llKcLong()) for (const sl of ['femur', 'tibia']) if (S.kc.shared.size[sl] === null) R.push(['Knee case', `the ${sl === 'tibia' ? 'tray' : 'femoral component'} has no size in the case yet: it is sized on the AP or the lateral, then drawn on this film`, 'warn']);
  R.push(['Planned', `hip zone: ${[nm(cup), nm(st)].filter(Boolean).join(', ') || 'nothing placed'}; knee zone: ${[nm(fe), nm(ti)].filter(Boolean).join(', ') || 'nothing placed'}`]);
  const A = inZone('knee', () => kneeAxes()), Hc = llOn(st) && k ? inZone('hip', () => corAt(st)) : null;
  if (A.hip && A.knee && A.ankle) {
    const hka = G.angleDeg(G.sub(A.hip.c, A.knee.p), G.sub(A.ankle.p, A.knee.p)), foot = G.project(A.knee.p, A.hip.c, G.sub(A.ankle.p, A.hip.c)), s = G.dot(G.sub(A.knee.p, foot), A.latDir);
    R.push(['HKA now', `${f1(hka)}° (${f1(180 - hka)}° ${s > 0 ? 'varus' : 'valgus'}; 180° = straight)`]);
    const fma = G.unit(G.sub(Hc || A.hip.c, A.knee.p)), tma = G.unit(G.sub(A.knee.p, A.ankle.p));
    if (llOn(fe) && llOn(ti) && k) {   // the components' joint lines against the mechanical axes after the plan (the femoral one from the hip plan's centre)
      let lat = dirOf(fe, [1, 0]); if (G.dot(lat, A.latDir) < 0) lat = G.mul(lat, -1);
      let med = dirOf(ti, [1, 0]); if (G.dot(med, A.latDir) > 0) med = G.mul(med, -1);
      const ldfa = G.angleDeg(fma, lat), mpta = G.angleDeg(G.mul(tma, -1), med), v = ldfa - mpta;
      R.push(['HKA after the plan', `${f1(180 - Math.abs(v))}° (${Math.abs(v) < 0.05 ? 'straight' : `${f1(Math.abs(v))}° ${v > 0 ? 'varus' : 'valgus'}`}): femoral component ${f1(ldfa)}° and tray ${f1(mpta)}° to the mechanical axes${Hc ? ', the femoral axis from the hip plan\'s centre of rotation' : ''}; joint lines taken parallel, soft tissue not counted`, Math.abs(v) > 3 ? 'warn' : '']);
    } else if (Hc) R.push(['HKA after the plan', `${f1(G.angleDeg(G.sub(Hc, A.knee.p), G.sub(A.ankle.p, A.knee.p)))}° with the hip plan only (its centre of rotation; the knee as it is)`]);
    else R.push(['HKA after the plan', 'nothing planned yet: the knee zone\'s femur and tray (or the hip zone\'s stem)']);
    if (k) R.push(['Limb length now', `${f1(G.dist(A.hip.c, A.ankle.p) * k)} mm, femoral head centre to ankle centre (this film)`]);
  } else R.push(['HKA', 'needs the femoral head, knee and ankle centres (knee zone landmarks)', 'warn']);
  if (!k) return;
  if ((llOn(st) || llOn(cup)) && S.cal && (S.cal.method === 'ball' || S.cal.method === 'line')) R.push(['Hip zone scale', `the film's one scale (${S.cal.method === 'ball' ? `the ${S.ballD} mm ball` : 'the known length'}) is used at the hip too; no separate hip-zone scale; not validated at the hip, which may lie at another depth from the detector`, 'warn']);   // Fable r44 P3-2 (design section 4: not built)
  if (llOn(st)) {
    let hr = []; try { hr = inZone('hip', () => { const r = []; hipResults(r, k); return r; }); } catch (e) { hr = []; }
    const lc = hr.find(r => r[0] === 'Leg length change'), la = hr.find(r => r[0] === 'Leg length after');
    R.push(['Leg length change from the hip plan', lc ? `${lc[1]} at the hip (the knee and the ankle move with the femur)` : 'not measured: the hip zone needs its teardrop and its reduction target (cup or native head)', lc ? '' : 'warn']);
    if (la) R.push(['Leg length after (hip plan)', la[1], la[2] || '']);
  }
  const up = A.hip && A.knee ? G.unit(G.sub(A.hip.c, A.knee.p)) : cranVec();
  if (!(llOn(st) && llOn(fe))) return R.push(['Interprosthetic bone', 'needs a stem in the hip zone and a femoral component in the knee zone']);
  if (isPfr(llOn(st)) && isTumFemur(llOn(fe))) return llTfRows(R, k, st, fe, up);
  const hp = llExt(llPts(st, 'hip'), up, false), kp = llExt(llPts(fe, 'knee'), up, true); if (!hp || !kp) return;
  // Sami, 30 Oct 2026 ("2. THE SAME"): the page's stem-tip warning, DIA_TIP_MM, one constant
  const gap = G.dot(G.sub(hp, kp), up) * k, ke = fe.stem && stemOf(fe) ? 'stem' : isTumFemur(llOn(fe)) ? 'body' : 'component', he = isPfr(llOn(st)) ? 'PFR' : 'stem';
  R.push(['Interprosthetic bone', gap <= 0 ? `none: the hip ${he} and the knee's femoral ${ke} overlap by ${f1(-gap)} mm along the femur (as drawn)` : `${f1(gap)} mm from the ${he === 'PFR' ? 'PFR\'s lowest point' : 'hip stem\'s tip'} to the top of the knee's femoral ${ke}, along the femur (this film)${gap < DIA_TIP_MM ? `: under ${DIA_TIP_MM} mm (the page's warning, not a Chunli or published minimum)` : ''}`, gap <= 0 ? 'bad' : gap < DIA_TIP_MM ? 'warn' : '']);
}
// the total femur's rows: the reconstruction against the other femur, and the length left for a connection nobody has drawn yet
function llTfRows(R, k, st, fe, up) {
  R.push(['Total femur (PFR + DFR)']);
  const hc = inZone('hip', () => headAt(st)), jl = inZone('knee', () => xf(fe)(tumAxis(fe).p0)), L = G.dist(hc, jl) * k;
  R.push(['Reconstructed femoral length', `${f1(L)} mm, the PFR's head centre to the DFR's joint line (this film, as placed)`]);
  const hn = lmGeom('head' + other(S.side)), kn = lmGeom(LL_OK.key);
  if (hn && kn) { const Lo = G.dist(hn.c, kn.p) * k, d = L - Lo; R.push(['Against the other femur', `${f1(Lo)} mm (its head centre to its condyles): the reconstruction ${Math.abs(d) < 0.05 ? 'equal' : sgn(d, 'longer', 'shorter')}`, Math.abs(d) > 10 ? 'warn' : '']); }
  else R.push(['Against the other femur', 'not marked: mark the other femoral head (hip zone) and the lowest point of its condyles (knee zone, "Other femur")']);
  const pb = llExt(llPts(st, 'hip', true), up, false), db = llExt(llPts(fe, 'knee', true), up, true);
  if (pb && db) { const c = G.dot(G.sub(pb, db), up) * k; R.push(['Length the connection must fill', c <= 0 ? `none: the PFR body and the DFR body overlap by ${f1(-c)} mm (as drawn): shorter parts are needed` : `${f1(c)} mm between the PFR body's end and the DFR body's top (measured on this film)`, c <= 0 ? 'bad' : 'warn']); }
  R.push(['Connection', 'connection part not confirmed by Chunli: ask for the total femur sheet (no part is drawn or sized for it)', 'warn']);
}
function kneeSkyResults(R) {
  const K = S.kc; R.push(['Skyline: patellar tracking']);
  const t = patellarTilt(lmGeom('patW'), lmGeom('trochAnt'), latVec(), null);
  R.push(['Patellar tilt (optional)', t ? `${f1(t.deg)}° between the patella and the trochlear groove line` : 'not measured (optional: mark both lines)']);
  skyRows(R, skylineAngles({ troch: lmGeom('trochAnt'), sulcus: lmGeom('sulcus'), apex: lmGeom('patApex'), post: lmGeom('postCondSky') }, latVec()));
  if (K) { const p = K.shared.pf; R.push(['Your assessment', p.grade || p.facet || p.dysplasia || p.tracking ? pfText(p) : 'not recorded yet (Measurements and save: Knee case)', p.grade ? '' : 'warn']); }
}
// the skyline's angles and the femoral rotation it supports (Sami, 15 Oct: "skyline to assess patellar tracking and
// consideration of femoral external rotation"); the planned external rotation: the surgeon's, else the assumed 5° to the
// posterior condylar axis (Addis Ortho standard, Sami 16 Oct; the manual's usual 3°, p. 17), said with its origin
function skyRows(R, a) {
  R.push(['Sulcus angle (optional)', a.sulcusAngle === null ? 'not measured (mark the trochlear groove line and the sulcus)' : `${f1(a.sulcusAngle)}°`]);
  R.push(['Congruence angle (optional)', a.congruence === null ? 'not measured (also mark the patella\'s apex)' : `${f1(Math.abs(a.congruence))}° ${a.congruence >= 0 ? 'lateral' : 'medial'}`]);
  R.push(['Femoral rotation on the skyline']);
  R.push(['Trochlear groove line vs posterior condylar line', a.rotation === null ? 'not measured (mark both lines; the posterior condyles only where they show)' : `${f1(a.rotation)}°`]);
  if (S.kc) R.push([PV_NAME.rot, pvText('rot', ' to the posterior condylar axis'), pvCls('rot')]);   // (XN manual p. 17: the guide 0°, 3° or 5°)
  R.push(['Rotation on a skyline', 'an approximation only: the transepicondylar axis is not seen on a skyline; rotation is set at surgery', 'warn']);
}
const PF_WORDS = { grade: { none: 'no patellofemoral arthritis', mild: 'mild patellofemoral arthritis', moderate: 'moderate patellofemoral arthritis', severe: 'severe patellofemoral arthritis' }, facet: { no: 'no lateral facet wear', yes: 'lateral facet wear' }, dysplasia: { none: 'no trochlear dysplasia', suspected: 'trochlear dysplasia suspected', present: 'trochlear dysplasia' }, tracking: { central: 'patella central', tilted: 'patella tilted', subluxed: 'patella subluxed laterally' } };
const pfText = p => [...['grade', 'facet', 'dysplasia', 'tracking'].map(k => PF_WORDS[k][p[k]]).filter(Boolean), p.note ? `note: ${p.note}` : ''].filter(Boolean).join('; ');
function kcCalText(role) {
  const st = kcFilmState(role), c = st && st.cal;
  if (!c) return role === 'sky' || role === 'long' ? 'no scale (angles only)' : 'not calibrated';
  if (c.method === 'ball') return `${st.ballD || 25} mm ball${c.confirmed === false ? ' (not yet confirmed)' : ''}`;
  if (c.method === 'line') return `known length ${c.lengthMm} mm`;
  if (c.method === 'xfer') return 'scaled from the AP (approximate, uncertainty not validated)';
  if (c.ct === 'scanner') return `scale from the CT scanner (pixel spacing ${c.mmPerPx} mm; no ball, no magnification)`;   // r43
  return `DICOM pixel spacing (${c.note || 'from the header'}; not checked against a marker)`;
}
function kcFilmDone(role) {
  const K = S.kc, f = K.films[role]; if (!f || f.missing) return false;
  const st = kcFilmState(role); if (!st) return false;
  const defs = { ap: KC_AP_LM, lat: KNEE_LAT_LM, sky: KC_SKY_LM, long: KC_LONG_LM }[role];
  const marked = defs.filter(d => !d.optional).every(d => lmGeomOf(d.key, st.lm ? st.lm[d.key] : undefined));
  const cal = role === 'sky' || role === 'long' || (st.cal && st.cal.method !== 'dicom' && st.cal.confirmed !== false);
  return marked && cal;
}
function kcSummaryRows(R) {
  const K = S.kc;
  R.push(['Knee case: films (each with its own calibration)']);
  for (const r of KC_ROLES) {
    const f = K.films[r];
    if (!f) { R.push([KC_NAME[r], r === 'long' ? 'optional, not added: the alignment angles are not measured (planning uses the standing AP, lateral and skyline)' : 'not added: its values are not measured']); continue; }
    if (f.missing) { R.push([KC_NAME[r], `its X-ray must be opened again (image ${f.image.sha256.slice(0, 6)}): use + ${KC_NAME[r]}`, 'warn']); continue; }
    const chk = [f.qc.side ? 'side confirmed' : 'side not confirmed', f.qc.quality ? 'quality confirmed' : 'quality not confirmed', ...(f.qc.ptSame ? ['X-ray from elsewhere, added as this patient by you'] : [])].join(', ');
    R.push([KC_NAME[r], `${kcFilmDone(r) ? 'marked' : 'in progress'}; ${kcCalText(r)}; ${chk}${r === 'long' ? `; ${K.shared.longSrc === 'ctscout' ? 'supine CT scout: alignment not weight-bearing (not standing)' : K.shared.longSrc === 'standing' ? 'standing long leg' : 'standing or CT scout not said'}` : ''}`]);
  }
  for (const n of K.notices.slice(-3)) R.push(['Changed', n, 'warn']);
  const A = K.films.long && !K.films.long.missing ? kcLongAngles() : null;
  R.push([`Measured on the long film${K.shared.longSrc === 'ctscout' ? ' (supine CT scout: alignment not weight-bearing, not standing)' : ''}: angles, no scale needed`]);
  if (A) longRows(R, A, A.L.k && !(A.L.st.cal && A.L.st.cal.method === 'xfer') ? A.L.k : null, '');
  else for (const n of ['HKA', 'mLDFA', 'MPTA', 'JLCA', 'Femoral anatomic-mechanical angle']) R.push([n, 'not measured (no long film)']);   // never zero (Sami, 15 Oct: the long film is optional)
  R.push(['Measured on the lateral']);
  const L = K.films.lat && !K.films.lat.missing ? kcGeo('lat') : null;
  let slope = null;
  if (L) {
    const pa = L.g('platAnt'), pp = L.g('platPost'), ta = L.g('tibAxis'), ac = L.g('antCortex'), pc = L.g('postCond'), pt = L.g('patThick');
    if (pa && pp && ta) { let up = G.unit(G.sub(ta.a, ta.b)); const pm = G.mid(pa.p, pp.p); if (G.dot(G.sub(pm, G.mid(ta.a, ta.b)), up) < 0) up = G.mul(up, -1); slope = G.angleDeg(up, G.unit(G.sub(pp.p, pa.p))) - 90; }
    R.push(['Native posterior slope', slope === null ? 'not measured' : `${f1(slope)}°`]);
    R.push(['Femoral depth (anterior cortex to back of condyles)', ac && pc && L.k ? `${f1(G.lineDist(pc.p, ac.a, ac.b) * L.k)} mm` : 'not measured (needs the lateral calibrated)']);
    R.push(['Plateau depth', pa && pp && L.k ? `${f1(G.dist(pa.p, pp.p) * L.k)} mm` : 'not measured']);
    R.push(['Patella thickness', pt && L.k ? `${f1(G.dist(pt.a, pt.b) * L.k)} mm (from the lateral; not used to scale the skyline)` : 'not measured']);
  } else R.push(['Lateral', 'not measured: no lateral film']);
  R.push(['Skyline: recorded assessment']);
  const sky = K.films.sky && !K.films.sky.missing ? kcGeo('sky') : null;
  if (sky) { const t = patellarTilt(sky.g('patW'), sky.g('trochAnt'), sky.lat, null); R.push(['Patellar tilt (optional)', t ? `${f1(t.deg)}°` : 'not measured']); if (S.wf !== 'kneeSky') skyRows(R, skylineAngles({ troch: sky.g('trochAnt'), sulcus: sky.g('sulcus'), apex: sky.g('patApex'), post: sky.g('postCondSky') }, sky.lat)); }
  else if (S.wf !== 'kneeSky') R.push([PV_NAME.rot, pvText('rot', ' to the posterior condylar axis'), pvCls('rot')]);
  R.push(['Patellofemoral assessment', pfText(K.shared.pf) || 'not recorded', K.films.sky && !K.shared.pf.grade ? 'warn' : '']);
  R.push(['Axes on the AP (taken from the long leg, or assumed)']);
  const T = K.films.ap && K.films.long ? (K.active === 'ap' ? kcTransfer() : null) : null;
  if (!K.films.ap) R.push(['AP', 'no AP film']);
  else if (!K.films.long) R.push(['Femoral and tibial cuts (no long film)', `femoral cut at the valgus angle to the AP's femoral shaft axis (${pvText('vca')}); tibial cut square to the AP's tibial shaft axis`, pvCls('vca')]);
  else {
    const A2 = kcLongAngles(), apG = kcGeo('ap'), fsAp = apG && apG.g('femShaft'), tsAp = apG && apG.g('tibShaft');
    const FA = kcApFemAxis();   // the axis the placement uses (Astra, r17 round 2: never a long-leg angle it did not use)
    R.push(['Femoral mechanical axis', !FA || !FA.how ? 'not set: mark the femoral axis on the AP (and the hip, knee and femoral axis on the long leg)' : `the AP's ${FA.how === 'measured' ? 'hip centre to knee centre' : FA.src.replace(/^shaft axis/, 'femoral shaft axis')} (${FA.how})`, FA && (FA.how === 'measured' || FA.how === 'transferred' || FA.how === 'entered') ? '' : 'warn']);
    R.push(['Tibial mechanical axis', A2 && A2.amaT !== null && tsAp ? `the AP's tibial shaft axis turned ${f1(Math.abs(A2.amaT))}° (measured on the long leg)` : 'the AP\'s tibial shaft axis (assumed)', A2 && A2.amaT !== null && tsAp ? '' : 'warn']);
    void T;
  }
  R.push(['Plan values (surgical targets; each with its origin and review)']);
  const rs = kneeVals(), FA3 = kcApFemAxis(), vcUsed = !(FA3 && (FA3.longUsed || FA3.how === 'measured'));
  R.push(['Target HKA', K.shared.hkaTarget === null ? 'not set' : `${f1(K.shared.hkaTarget)}° (a target, not a prediction)`]);
  R.push([PV_NAME.femDistal, pvText('femDistal', ' from the more distal condyle'), pvCls('femDistal')]);
  R.push([PV_NAME.tibial, pvText('tibial', ` below the ${TIB_SHORT[rs.tibRef]}`), pvCls('tibial')]);
  R.push([pvName('slope'), pvText('slope'), pvCls('slope')]);
  R.push([PV_NAME.vca, pvText('vca', ' to the femoral shaft axis') + (vcUsed ? '' : FA3.how === 'measured' ? ' (not used: the hip on the AP gives the femoral mechanical axis)' : ' (not used: the long leg gives the femoral mechanical axis)'), vcUsed ? pvCls('vca') : '']);
  if (slope !== null) R.push(['Native vs planned slope', `native ${f1(slope)}° (lateral), planned cut ${f1(rs.slope)}°${pvXn() ? ' (extramedullary guide)' : ''}`]);
  const pt2 = L && L.g('patThick') && L.k ? ` (patella ${f1(G.dist(L.g('patThick').a, L.g('patThick').b) * L.k)} mm thick)` : '';
  R.push(['Patella', { undecided: 'not decided yet: your decision', resurface: 'resurface (your decision)', retain: 'not resurfaced (your decision)' }[K.shared.patella] + pt2]);
  R.push(['Plan on the films (geometric simulation, not a prediction)']);
  for (const slot of ['femur', 'tibia']) {
    const z = K.shared.size[slot], f = z && S.fam[z.fam];
    R.push([slot === 'femur' ? 'Femoral component (AP and lateral)' : 'Tibial tray (AP and lateral)', z && f ? `${f.label} ${z.size}${z.byHand ? ' (set by hand)' : ''}` : 'not placed']);
  }
  if (S.wf !== 'kneeAP' && S.wf !== 'kneeLat') kneePairRow(R);   // (on the AP and the lateral it is with that film's values)
  R.push(['Insert', 'chosen at surgery from the trials: not selected from the films']);
  const tib = S.fam[XN_KNEE.tibia];
  if (tib) R.push(['Addis Ortho knee data', `templates of trays ${tib.sizes.map(z => z.label || z.size).join(', ')} (the manual also lists tray 3#, p. 24: no template yet), PS inserts ${XN_KNEE.insertHeights.join(', ')} mm (p. ${XN_KNEE.insertPage}); outlines not yet confirmed by the manufacturer; the slope is set on the extramedullary guide (p. 10-11): the manual states no built-in tray or insert slope, so the trays are drawn level`, 'warn']);
}

// ---------- the case's films on the page ----------
// the films' original bytes, by sha256 (projects.js sends and files them); the single X-ray's own when there is no case
function kcImages() {
  const out = {};
  if (!S.kc) { if (S.orig && S.orig.bytes) out[S.orig.sha256] = { bytes: S.orig.bytes, mime: S.orig.mime, holder: S.orig }; return out; }
  for (const f of Object.values(S.kc.films)) if (f && f.orig && f.orig.bytes) out[f.image.sha256] = { bytes: f.orig.bytes, mime: f.image.mime, role: f.role, holder: f.orig };
  return out;
}
// why the case cannot be saved as a file now ('' when it can)
function kcWhy() {
  const K = S.kc; if (!K) return '';
  const miss = KC_ROLES.filter(r => K.films[r] && K.films[r].missing);
  return miss.length ? `The ${miss.map(r => KC_NAME[r]).join(' and ')} film${miss.length > 1 ? "s'" : "'s"} X-ray is not on this page: open it again (+ ${KC_NAME[miss[0]]}) before saving the project file.` : '';
}
function kcImageDesc(f) {
  const share = !!S.set.share, ext = PJ.MIME_EXT[f.image.mime];
  return { fileName: share ? `image-${f.role}.${ext}` : clip(String(f.image.fileName || '').split(/[\\/]/).pop(), 120) || `image-${f.role}.${ext}`, mime: f.image.mime, sha256: f.image.sha256, size: f.image.size, width: f.image.width, height: f.image.height };
}
// the films a save of the case leaves out (kcProjectNow): restored from kept changes, their X-ray not opened again, their
// image not in the patient list's revision
function kcUnsentRoles() {
  const K = S.kc; if (!K) return [];
  const held = new Set(Object.values((S.caseRef && S.caseRef.shas) || {}));
  return KC_ROLES.filter(r => K.films[r] && K.films[r].missing && !held.has(K.films[r].image.sha256));
}
// project.json version 2 (contract section 12): a film whose X-ray is not on this page is left out, unless the patient
// list holds its image (then it stays in the case's revision as it was)
function kcProjectNow() {
  const K = S.kc, films = {}, held = new Set(Object.values((S.caseRef && S.caseRef.shas) || {}));
  for (const r of KC_ROLES) {
    const f = K.films[r]; if (!f) continue;
    if (f.missing && !held.has(f.image.sha256)) continue;
    const st = clone(kcFilmState(r)); st.pendingSend = null;
    films[r] = { image: kcImageDesc(f), state: st, qc: { side: !!f.qc.side, quality: !!f.qc.quality, ...(f.qc.ptSame ? { ptSame: true } : {}) } };
  }
  const fa = K.films[K.active], m = (fa && fa.qc.ptSame ? null : S.img.meta) || {}, study = String(m.StudyInstanceUID || '');   // another hospital's film: not this patient's study
  return { format: PJ.FORMAT, v: 2, savedAt: new Date().toISOString(), savedBy: null, app: 'Addis Ortho X-ray templating, knee case release 1 (14 Oct 2026)' + (STANDALONE ? ', t.addisortho.com' : ''),
    libraryId: libIdNow(), patient: { ...patientNow(), side: K.side, procedure: 'knee', ...(!S.set.share && /^[0-9.]{1,64}$/.test(study) ? { studyUID: study } : {}) },
    case: { cid: K.cid, kind: 'knee' }, films, active: films[K.active] ? K.active : Object.keys(films)[0], shared: clone(K.shared), notices: K.notices.slice(-20) };
}
// what the kept-changes store keeps of the case: its project's, and the films whose X-ray must be opened again
function kcKept() {
  const K = S.kc, st = PJ.keptStateOf(kcProjectNow());
  for (const r of KC_ROLES) { const f = K.films[r]; if (f && f.missing && !st.films[r]) st.films[r] = { image: { mime: f.image.mime, sha256: f.image.sha256, size: f.image.size, width: f.image.width, height: f.image.height }, state: f.state, qc: f.qc }; }
  return st;
}
// kept changes put back (projects.js offerUnsent): each film's plan where this page has its X-ray, the others marked to
// be opened again; → the films to open again, in words (role and the start of the image's hash; never a name)
function kcApplyKept(st) {
  const K = S.kc, missing = [];
  kcStash();
  K.shared = { ...PJ.normShared(st.shared), brand: brandIn(st.shared) };   // every part present, every value a number or null (Astra, 15 Oct, P2 5)   // r36 (Fable P1 1): the case's maker kept
  K.notices = Array.isArray(st.notices) ? [...st.notices] : [];
  if (st.side === 'R' || st.side === 'L') K.side = st.side;
  for (const [r, f] of Object.entries(st.films)) {
    const have = Object.values(K.films).find(x => x && !x.missing && x.image.sha256 === f.image.sha256);
    if (have) { if (have.role !== r) { delete K.films[have.role]; have.role = r; K.films[r] = have; if (K.active === have.role) K.active = r; } have.state = clone(f.state); have.stateJ = null; have.qc = { ...f.qc }; continue; }
    K.films[r] = { role: r, orig: null, image: { ...f.image, fileName: '' }, meta: null, state: clone(f.state), stateJ: null, qc: { ...(f.qc || {}) }, missing: true };
    missing.push(`${KC_NAME[r]} (image ${f.image.sha256.slice(0, 6)})`);
  }
  const a = K.films[K.active]; if (a && a.state && !a.missing) { const s0 = a.state; a.state = null; applyFullState(s0); kcFilmSetup(K.active); panel(); draw(); }
  return missing;
}
function kcGuessRole(img) {
  const m = img.meta || {}, vp = String(m.ViewPosition || '').toUpperCase(), d = [m.SeriesDescription, m.StudyDescription, m.BodyPartExamined].filter(Boolean).join(' ').toUpperCase();
  if (/SKYLINE|SUNRISE|MERCHANT|AXIAL|PATELLA/.test(d)) return 'sky';
  if (String(m.Modality || '').toUpperCase() === 'CT' || /LONG|LOWER LIMB|LEG LENGTH|SCANOGRAM|STANDING/.test(d) || img.rows > 2 * img.cols) return 'long';
  if (/^(LL|RL|LAT|ML|LM|LATERAL)/.test(vp) || /LAT/.test(d)) return 'lat';
  return 'ap';
}
// the patient of a new film against the case's (Sami, 14 Oct: every film of a case is that patient's): '' when they agree
// or one of them carries no details; else why, never adding the film (a new case only on the surgeon's explicit choice).
// Compared with the case's established patient and with every film's own (Astra, 15 Oct, P1 3), by hospital, record
// number and name (projects.js identClash).
// A film the surgeon declared this patient's though its X-ray names another (qc.ptSame: a long leg, CT or MRI done at
// another hospital under its own record number; Sami, 21 Oct) binds nothing: neither the case's patient nor the next film.
function kcClash(meta) {
  const K = S.kc, known = [K.pt, ...Object.values(K.films).map(f => (f && f.meta && !(f.qc && f.qc.ptSame) ? identFromMeta(f.meta) : null))];
  return PJ.identClash(known, identFromMeta(meta));
}
// the patient an X-ray names (DICOM): name, record number, hospital (its InstitutionName, mapped by projects.js HOSPITALS)
// and age (PatientAge, or the birth and study dates)
const identFromMeta = m => ({ name: PJ.cleanId(nameOf(m || {})), recordNo: PJ.cleanId((m || {}).PatientID),   // never cut to fit: refused downstream if too long (Astra, round 3)
  hospital: PJ.hospitalFromDicom((m || {}).InstitutionName), age: PJ.ageOf(m || {}) });
// a film chosen for a role (the tabs' "+", the start card's "Start a knee case"): what the next file chooser opens for
let KC_PICK = null;
function kcPick(role, startNew = false) { KC_PICK = { role, startNew, at: Date.now() }; $('file2').click(); }
const takePick = () => { const p = KC_PICK; KC_PICK = null; return p && Date.now() - p.at < 600000 ? p : null; };
// A film added to the case (or a new case started with it). Its patient is checked against the case's first; a film
// whose image the case has as another role, or whose X-ray is too large for the case, is refused; one that replaces
// a film clears that film's plan and flags what used it (Astra, P1 2).
async function kcAddFile(u8, name, role, { startNew = false } = {}) {
  if (u8.length > PJ.IMAGE_MAX) throw new Error(`This file is ${(u8.length / 1048576).toFixed(0)} MB; a film is at most ${PJ.IMAGE_MAX / 1048576} MB.`);
  let sha = ''; try { sha = await PJ.sha256hex(u8); } catch (e) { sha = ''; }
  if (!sha) throw new Error('A knee case needs the page opened over https (its films are identified by their content).');
  const K0 = startNew ? null : S.kc;
  if (K0) {
    const dup = Object.values(K0.films).find(f => f && !f.missing && f.image.sha256 === sha);
    if (dup) { hideOpen(); if (role && dup.role !== role) return prompt(`This X-ray is already the ${KC_NAME[dup.role]} film of this case.`); return kcShow(dup.role); }
  }
  let { img, mime } = await decodeBytes(bufOf(u8), name);
  role = role || (K0 ? KC_ROLES.find(r => !K0.films[r] && r === kcGuessRole(img)) || KC_ROLES.find(r => !K0.films[r]) || kcGuessRole(img) : kcGuessRole(img));
  // a long-leg DICOM over 20 MB is kept reduced (reduce.js; Sami, 21 Oct: the case reopened slowly): the case holds,
  // saves and reopens the smaller copy, and from here on the film is the copy (its bytes, sha256 and pixels)
  // (Fable P2: a browser that cannot make the copy, or a phone short of memory, keeps the film whole, as before, and says so)
  let reduced = null, keptWhole = '';
  if (role === 'long' && mime === 'application/dicom' && u8.length > REDUCE_OVER) {
    openMsg('Reducing the long-leg film…', 'Kept at a coarser pixel so the case saves and reopens quickly; the angles are unaffected. The full film stays in PACS.');
    await new Promise(res => setTimeout(res, 30));   // the message is painted before the work starts
    let r = null;
    try { r = typeof CompressionStream === 'undefined' ? null : await reduceFilm(img, sha); } catch (e) { r = null; keptWhole = (e && e.message) || 'error'; }
    if (!r) keptWhole = keptWhole || 'this browser cannot make the copy';
    if (r) {
      reduced = r; u8 = r.bytes; name = String(name || 'long-leg').replace(/(\.dcm|\.dicom)?$/i, '-reduced.dcm');
      sha = await PJ.sha256hex(u8); img = await decodeAny(bufOf(u8));
      const dup = K0 && Object.values(K0.films).find(f => f && !f.missing && f.image.sha256 === sha);
      if (dup) { hideOpen(); if (dup.role !== role) return prompt(`This X-ray is already the ${KC_NAME[dup.role]} film of this case.`); return kcShow(dup.role); }
    }
  }
  const desc = { fileName: name, mime, sha256: sha, size: u8.length, width: img.cols, height: img.rows };
  // a film added earlier as this patient's (qc.ptSame) whose X-ray is opened again is not asked about again
  let ptSame = !!(K0 && Object.values(K0.films).some(f => f && f.missing && f.image.sha256 === sha && f.qc && f.qc.ptSame));
  if (K0 && !ptSame) {
    const clash = kcClash(img.meta);
    if (clash) {
      hideOpen();
      // permanent deletes (21 Oct 2026): the X-ray, when it is already stored in a case of the list (it is not stored by this refusal); the knee case on screen
      const knX = sha && PJ.knownCase(sha), delOpts = [];
      if (knX && !(S.caseRef && knX.id === S.caseRef.id)) delOpts.push(['Delete this X-ray (permanent)', 'delx', 'cdel']);
      if (S.caseRef) delOpts.push(['Delete this patient case (permanent)', 'delc', 'cdel']);
      const pick = await PJ.choose(`This X-ray names another patient: ${clash}. It has not been added to this knee case.`, [['Cancel', null, 'primary'], ['Same patient: add it', 'same', ''], ['Start a new case with it', 'new', ''], ...delOpts],
        'A film taken at another hospital (a long leg, CT or MRI) carries that hospital\'s record number. If you have checked it is this patient, add it: the case keeps its own patient details. Otherwise start a new case only if you mean to template this other patient.');
      if (pick === 'delx') { await PJ.runDelete('xray', { id: knX.id, role: null, caseOnly: !!knX.ck, what: 'the stored copy of this X-ray', report: t => prompt(t) }); return; }
      if (pick === 'delc') { await PJ.runDelete('case', PJ.openSubject({ what: '' })); return; }
      if (pick === 'new') { kcStartWith(img, u8, desc, role); if (reduced) kcNotice(kcReducedNote(reduced)); if (keptWhole) kcNotice(`Long leg film kept at full size at ${hhmmNow()} (could not be reduced: ${keptWhole}); the case saves and reopens more slowly`); return; }
      if (pick !== 'same' || !confirm(`Add this X-ray to the ${SIDE[K0.side]} knee case as the same patient? Its own name and record number are not used for the case, and the film is marked as added by you.`)) return prompt('Not added: the X-ray names another patient than this knee case.');
      if (S.kc !== K0) return prompt('Not added: the knee case on screen changed meanwhile.');
      ptSame = true;
    }
  }
  if (K0) {
    const old = K0.films[role], replacing = old && !(old.missing && old.image.sha256 === sha);
    if (replacing && !confirm(`Replace the ${KC_NAME[role]} film? Its landmarks, calibration and components are cleared; values that used it are flagged.`)) { hideOpen(); return; }
    const total = Object.values(K0.films).filter(f => f && f.role !== role).reduce((n, f) => n + f.image.size, 0) + u8.length;
    if (total > PJ.CASE_IMAGES_MAX) { hideOpen(); return prompt(`Not added: the case's films would be over ${PJ.CASE_IMAGES_MAX / 1048576} MB together. Use smaller copies of the films.`); }
    const restore = old && old.missing && old.image.sha256 === sha ? old : null;
    kcStash();
    const f = { role, orig: { bytes: u8, mime, sha256: sha, name }, image: desc, meta: metaSub(img.meta), state: null, stateJ: null, qc: restore ? { ...restore.qc } : { side: false, quality: false } };
    if (ptSame) f.qc.ptSame = true; else delete f.qc.ptSame;
    K0.films[role] = f; K0.active = role;
    if (reduced) kcNotice(kcReducedNote(reduced));
    if (keptWhole) kcNotice(`Long leg film kept at full size at ${hhmmNow()} (could not be reduced: ${keptWhole}); the case saves and reopens more slowly`);
    if (!ptSame) K0.pt = PJ.identFill(K0.pt, identFromMeta(img.meta));   // what the case's patient lacks, from this film (Astra, 15 Oct, P1 3)
    if (ptSame && !restore) kcNotice(`${KC_NAME[role]} film added at ${hhmmNow()} as this patient's by the surgeon, though its X-ray names another patient (taken elsewhere)`);
    if (replacing && role === 'long') K0.shared.longSrc = null;   // a new long leg: standing or CT scout said again (from its header, or by the surgeon)
    const hadMarks = !!(replacing && old && old.state && old.state.exist && (old.state.exist.regions || []).length);   // its existing implant marks go with it (contract section 13)
    if (replacing && old) kcNotice(`${KC_NAME[role]} film replaced at ${hhmmNow()}: its landmarks, calibration${hadMarks ? ', existing implant marks' : ''} and components were cleared; values that used it are flagged`);
    kcShowImg(img, f, restore && restore.state ? { state: restore.state, note: `The ${KC_NAME[role]} film is back with its plan.` } : {});
    PJ.caseChanged();
    return;
  }
  { kcStartWith(img, u8, desc, role); if (reduced) kcNotice(kcReducedNote(reduced)); if (keptWhole) kcNotice(`Long leg film kept at full size at ${hhmmNow()} (could not be reduced: ${keptWhole}); the case saves and reopens more slowly`); return; }
}
const hhmmNow = () => { const d = new Date(); return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`; };
const kcReducedNote = r => `Long leg film reduced at ${hhmmNow()}: ${r.fromCols} × ${r.fromRows} to ${r.cols} × ${r.rows} pixels${r.mmFrom ? `, ${+r.mmFrom.toFixed(3)} to ${+r.mmTo.toFixed(3)} mm a pixel` : ''} (box average); its angles are unaffected, the full film stays in PACS`;
// a new knee case with this film
function kcStartWith(img, u8, desc, role, cid) {
  const lat = String((img.meta || {}).ImageLaterality || (img.meta || {}).Laterality || '').toUpperCase();
  const K = kcNew(lat === 'R' || lat === 'L' ? lat : S.side, cid);
  K.films[role] = { role, orig: { bytes: u8, mime: desc.mime, sha256: desc.sha256, name: desc.fileName }, image: desc, meta: metaSub(img.meta), state: null, stateJ: null, qc: { side: false, quality: false } };
  K.active = role; K.pt = PJ.identFill(null, identFromMeta(img.meta));
  setImage(img, { file: desc.fileName, hash: desc.sha256 }, { orig: K.films[role].orig, how: 'case', kc: K, kcRole: role });
  prompt(`Knee case started with this X-ray as its ${KC_NAME[role]} film (change it under Calibrate if that is wrong). Add the others with the film buttons at the top of the panel.`);
}
// the knee film on screen made into a knee case (its plan, its patient list case and its image kept)
function kcStart() {
  if (!S.img || S.kc || S.wf === 'hip') return;
  if (!S.orig || !S.orig.sha256) return prompt(S.origWhy || 'This X-ray cannot start a knee case here: its original file is not available. Open the file again.');
  const role = S.wf === 'kneeLat' ? 'lat' : S.img.rows > 2 * S.img.cols ? 'long' : 'ap';
  const K = kcNew(S.side);
  K.films[role] = { role, orig: S.orig, image: { fileName: S.orig.name || '', mime: S.orig.mime, sha256: S.orig.sha256, size: S.orig.bytes.length, width: S.img.cols, height: S.img.rows }, meta: metaSub(S.img.meta), state: null, stateJ: null, qc: { side: false, quality: false } };
  K.active = role; { const id = identNow(); K.pt = { name: PJ.cleanId(id.name), recordNo: PJ.cleanId(id.recordNo), hospital: id.hospital || '', age: Number.isInteger(id.age) ? id.age : null }; }
  K.shared.caseType = S.caseType || 'primary';   // the film's case type becomes the case's (contract section 13)
  S.kc = K;
  if (S.caseRef) S.caseRef = { id: S.caseRef.id, rev: S.caseRef.rev, shas: S.caseRef.sha256 === S.orig.sha256 ? { [role]: S.orig.sha256 } : {} };
  if (role === 'long') { S.tplAll[S.wf] = S.tpl; S.wf = 'kneeLong'; S.tpl = S.tplAll.kneeLong || (S.tplAll.kneeLong = {}); }
  kcFilmSetup(role);
  for (const sl of ['femur', 'tibia']) if (S.tpl[sl]) kcSizeSet(sl, S.tpl[sl], !!S.tpl[sl].userSize);
  const llNo = role === 'long' && S.ll && llHide() && !!((S.tplAll.kneeAP || {}).femur || (S.tplAll.kneeAP || {}).tibia);   // Fable r44 recheck P2-3: parts on an unusable scale are never the case's size
  if (role === 'long' && S.ll && !llHide()) { const kt = S.tplAll.kneeAP || {}; for (const sl of ['femur', 'tibia']) if (kt[sl]) K.shared.size[sl] = { fam: kt[sl].fam, size: kt[sl].size, byHand: !!kt[sl].userSize, ...(kt[sl].stem ? { stem: { ...kt[sl].stem } } : {}) }; }   // Fable r44 P3-1: a long-leg plan's knee parts become the case's one size
  PJ.toCase(PJ.caseKey(K.cid), role);
  $('wf').value = 'kc'; HIST.length = 0; REDO.length = 0; save(); panel(); draw();
  prompt(`This X-ray is now the ${KC_NAME[role]} film of a ${SIDE[K.side]} knee case. Add the others (AP, lateral, skyline, long leg) with the film buttons at the top of the panel.${llNo ? ' The knee parts placed on this film on an unusable scale were not taken as the case size: size them on the AP or the lateral.' : ''}`);
}
// another film of the case on screen
async function kcShow(role) {
  const K = S.kc, f = K && K.films[role];
  if (!f) return;
  if (f.missing || !f.orig) return prompt(`The ${KC_NAME[role]} film's X-ray is not on this page (image ${f.image.sha256.slice(0, 6)}): open it again with "+ ${KC_NAME[role]}".`);
  if (K.active === role && S.img && !f.state) return;
  if (S.filling || S.drag) return prompt('Wait a moment: a stroke is still being filled.');
  kcStash();
  prompt(`Opening the ${KC_NAME[role]} film…`);
  let img;
  try { ({ img } = await decodeBytes(bufOf(f.orig.bytes), f.image.fileName, f.image.mime)); }
  catch (e) { return prompt(`The ${KC_NAME[role]} film could not be shown: ${e.message || e}`); }
  if (S.kc !== K) return;
  if (img.cols !== f.image.width || img.rows !== f.image.height) return prompt(`The ${KC_NAME[role]} film's image is ${img.cols} × ${img.rows} pixels, its plan says ${f.image.width} × ${f.image.height}: not shown.`);
  K.active = role;
  kcShowImg(img, f, f.state ? { state: f.state, note: `${KC_NAME[role]} film.` } : {});
}
function kcShowImg(img, f, { state = null, note = '' } = {}) {
  if (keepT) keepNow();
  S.orig = f.orig; S.origWhy = '';
  loadFilm(img, { file: f.image.fileName, hash: f.image.sha256 }, { state, kcRole: f.role, note }, false);
  f.state = null; f.stateJ = null;
  if (!f.meta || !Object.keys(f.meta).length) f.meta = metaSub(img.meta);
  if (!f.qc.ptSame) S.kc.pt = PJ.identFill(S.kc.pt, identFromMeta(img.meta));   // the film shown may carry what the case's patient lacks (it agreed when it was added)
  PJ.sync(); panel();
}
// a film moved to another role (the guess was wrong): its plan goes with it to that role's plan type
function kcMoveRole(to) {
  const K = S.kc, from = K.active, f = K.films[from];
  if (!f || to === from || K.films[to]) return;
  const st = fullState(); delete st.saved; st.pendingSend = null;
  delete K.films[from]; f.role = to; K.films[to] = f; K.active = to;
  // landmarks of the other plan type are kept only where the two types share them; implants stay with the old type
  st.wf = KC_WF[to];
  applyFullState(st); kcFilmSetup(to); ensureSlots();
  kcNotice(`${KC_NAME[from]} film moved to ${KC_NAME[to]} at ${hhmmNow()}`);
  save(); panel(); draw(); PJ.caseChanged();
  prompt(`This film is now the ${KC_NAME[to]} film. Its landmarks that the ${KC_NAME[to]} plan uses are kept; check them.`);
}
// a knee case from a project (an .aotp or the patient list): every film's bytes checked by projects.js; the film shown
// decoded (the others when they are shown)
async function loadCase(project, imgs, { caseRef = null, how = 'file', role = null } = {}) {
  for (const f of Object.values(project.films)) { const lp = PJ.libraryProblems(f.state, S.lib); if (lp.length) throw new PJ.ProjectError(`This project cannot be opened: ${lp.slice(0, 4).join('; ')}.`); }
  const active = role && project.films[role] ? role : project.active && project.films[project.active] ? project.active : Object.keys(project.films)[0];
  const fa = project.films[active];
  const { img, mime } = await decodeBytes(bufOf(imgs[active]), fa.image.fileName, fa.image.mime);
  if (mime !== fa.image.mime) throw new PJ.ProjectError('This project cannot be opened: an image is not the type the project says.');
  if (img.cols !== fa.image.width || img.rows !== fa.image.height) throw new PJ.ProjectError(`This project cannot be opened: the ${KC_NAME[active]} film's image is ${img.cols} × ${img.rows} pixels, the project says ${fa.image.width} × ${fa.image.height}.`);
  const K = kcNew(project.patient.side, project.case.cid);
  K.shared = { ...PJ.normShared(project.shared), brand: brandIn(project.shared) };   // every part present, every value a number or null (Astra, 15 Oct, P2 5)   // r36 (Fable P1 1): the case's maker kept
  K.notices = Array.isArray(project.notices) ? [...project.notices] : [];
  K.pt = { name: project.patient.name || '', recordNo: project.patient.recordNo || '', hospital: project.patient.hospital || '', age: Number.isInteger(project.patient.age) ? project.patient.age : null };
  for (const [r, f] of Object.entries(project.films)) K.films[r] = { role: r, orig: { bytes: imgs[r], mime: f.image.mime, sha256: f.image.sha256, name: f.image.fileName }, image: { ...f.image }, meta: r === active ? metaSub(img.meta) : null, state: r === active ? null : clone(f.state), stateJ: null, qc: { side: !!(f.qc && f.qc.side), quality: !!(f.qc && f.qc.quality), ...(f.qc && f.qc.ptSame === true ? { ptSame: true } : {}) } };
  K.active = active;
  const ref = STANDALONE ? caseRef : null, n = Object.keys(project.films).length;
  setImage(img, { file: fa.image.fileName, hash: fa.image.sha256 }, { orig: K.films[active].orig, state: clone(fa.state), caseRef: ref, patient: project.patient, kc: K, kcRole: active, how: how === 'file' ? 'aotp' : how,
    note: `Knee case opened${how === 'list' ? ' from the templated-patients list' : how === 'known' ? ' (this X-ray is one of its films)' : ''}${ref ? ` (version ${ref.rev})` : ''}: ${n} film${n > 1 ? 's' : ''}, the ${KC_NAME[active]} on screen.` });
}
// a knee case kept in this browser (its changes never reached the patient list) restored from one of its X-rays opened again
async function kcRestoreFrom(kept, u8, name, sha) {
  const st = kept.rec.state, role = Object.keys(st.films).find(r => st.films[r].image.sha256 === sha);
  const { img, mime } = await decodeBytes(bufOf(u8), name);
  const K = kcNew(st.side, st.cid);
  K.shared = { ...PJ.normShared(st.shared), brand: brandIn(st.shared) };   // every part present, every value a number or null (Astra, 15 Oct, P2 5)   // r36 (Fable P1 1): the case's maker kept
  K.notices = [...(st.notices || [])];
  const same = !!(st.films[role].qc && st.films[role].qc.ptSame), own = same ? null : identFromMeta(img.meta);   // another hospital's film names no one for the case
  K.pt = PJ.identFill(null, own);
  const missing = [];
  for (const [r, f] of Object.entries(st.films)) {
    if (r === role) K.films[r] = { role: r, orig: { bytes: u8, mime, sha256: sha, name }, image: { ...f.image, fileName: name }, meta: metaSub(img.meta), state: null, stateJ: null, qc: { ...(f.qc || {}) } };
    else { K.films[r] = { role: r, orig: null, image: { ...f.image, fileName: '' }, meta: null, state: clone(f.state), stateJ: null, qc: { ...(f.qc || {}) }, missing: true }; missing.push(`${KC_NAME[r]} (image ${f.image.sha256.slice(0, 6)})`); }
  }
  K.active = role;
  // the case may already be in the patient list (an earlier opening of this tab saved part of it): go on with that case, its
  // films there stay in its revision, rather than making another or replacing the restored plan with the list's
  // (Fable, round 7) its patient is the case's as the list holds it (the hospital, age and name typed there), filled from this
  // film only where it lacks something; when the list cannot be read the case is not attached (never saved over with a
  // poorer patient), and the usual new-case path finds it later
  const known = STANDALONE ? PJ.knownCase(PJ.caseKey(st.cid)) : null;
  // (Fable, round 8) the known case is attached in any case; when its patient cannot be read now, it is read before the
  // first save as a step of that save (retried like an offline save), never a second case, never the list's plan instead
  let caseRef = null;
  if (known) {
    caseRef = { id: known.id, rev: known.rev, shas: { ...(known.shas || {}) } };
    try { const pt = await PJ.casePatient(known.id); K.pt = PJ.identFill(PJ.identFill(null, pt), own); if (pt.hospital) K.pt.asked = true; }
    catch (e) { K.ptFrom = known.id; }
  }
  setImage(img, { file: name, hash: sha }, { orig: K.films[role].orig, state: clone(st.films[role].state), how: 'case', kc: K, kcRole: role, caseRef,
    note: `The knee case is back from this browser's kept changes, with this X-ray as its ${KC_NAME[role]} film.${missing.length ? ` Open these X-rays again to restore their films: ${missing.join('; ')}.` : ''}` });
  PJ.adoptKept(kept);
}

// ---------- the panel: film tabs, this film's card, the case card ----------
function kcTabs() {
  const K = S.kc, d = el('<div class="kctabs keep" role="group" aria-label="Films of this knee case"></div>');
  for (const r of KC_ROLES) {
    const f = K.films[r], on = K.active === r, st = !f ? 'add' : f.missing ? 'reopen' : kcFilmDone(r) ? 'done' : 'open';
    const mark = { add: '+', reopen: '⟲', done: '✓', open: '•' }[st];
    const b = btn(`${mark} ${KC_TAB[r]}`, () => (st === 'add' || st === 'reopen' ? kcPick(r) : kcShow(r)), `kctab st-${st}${on ? ' on' : ''}`, false,
      { add: `Add the ${KC_NAME[r].toLowerCase()} film`, reopen: `Open the ${KC_NAME[r].toLowerCase()} film's X-ray again to restore it`, done: `${KC_NAME[r]} film: marked`, open: `${KC_NAME[r]} film: in progress` }[st]);
    b.setAttribute('aria-pressed', String(on));
    b.setAttribute('aria-label', `${KC_NAME[r]} film: ${{ add: 'missing, add it', reopen: 'its X-ray must be opened again', done: 'done', open: 'in progress' }[st]}${on ? ', on screen' : ''}`);
    d.appendChild(b);
  }
  return d;
}
function kcFilmCard() {
  const K = S.kc, f = K.films[K.active], d = el(`<div class="kcfilm"><div class="kch">${esc(KC_NAME[K.active])} film of the ${esc(SIDE[K.side])} knee case</div></div>`);
  const rs = el('<label class="note">This X-ray is the <select aria-label="Which film this is"></select> film</label>'), sel = rs.querySelector('select');
  for (const r of KC_ROLES) { const o = new Option(KC_NAME[r], r); o.disabled = r !== K.active && !!K.films[r]; sel.add(o); }
  sel.value = K.active; sel.onchange = e => kcMoveRole(e.target.value);
  d.appendChild(rs);
  { const rr = el('<div class="row"></div>'); rr.appendChild(btn('Replace this film…', () => kcPick(K.active), '', false, `Open another X-ray as the ${KC_NAME[K.active].toLowerCase()} film: this film's landmarks, calibration and components are cleared and what used them is flagged`)); d.appendChild(rr); }
  const chk = (key, text) => { const l = el(`<label class="note"><input type="checkbox"> ${esc(text)}</label>`), i = l.querySelector('input'); i.checked = !!f.qc[key]; i.onchange = () => { f.qc[key] = i.checked; save(); panel(); draw(); }; return l; };
  d.append(chk('side', `This is the ${SIDE[K.side]} knee (the R/L marker checked)`), chk('quality', 'Image quality adequate (rotation, flexion, the whole region on the film)'));
  d.appendChild(el(`<p class="note">Calibration for this film: ${esc(KC_CAL_HOW[K.active])}. Now: ${esc(kcCalText(K.active))}.</p>`));
  if (K.active === 'long') {
    const ls = el('<label class="note">This long-leg film is <select aria-label="Standing or CT scout"><option value="">(not said)</option><option value="standing">a standing long leg</option><option value="ctscout">a supine CT scout (alignment not weight-bearing)</option></select></label>');
    ls.querySelector('select').value = K.shared.longSrc || ''; ls.querySelector('select').onchange = e => { K.shared.longSrc = e.target.value || null; save(); panel(); draw(); };
    d.appendChild(ls);
    const r = el('<div class="row"></div>');
    r.appendChild(btn('Scale from the AP', kcXfer, '', false, 'Mark the femoral condylar width on this film and on the calibrated AP: the AP\'s scale is carried over (approximate)'));
    d.appendChild(r);
    if (S.cal && S.cal.method === 'xfer') { const st = kcXferStale(); d.appendChild(el(`<div class="${st ? 'badbox' : 'warnbox'}">Scaled from the AP: approximate, uncertainty not validated${st ? `. Out of date: ${esc(st)}. Lengths are not shown until it is scaled again.` : '. Angles do not use it.'}</div>`)); }
  }
  return d;
}
function kcNum(label, get, set, lo, hi, step, unit, title = '') {
  const l = el(`<label class="note kcnum">${esc(label)} <input type="number" min="${lo}" max="${hi}" step="${step}" placeholder="not set"> ${esc(unit)}</label>`), i = l.querySelector('input');
  const v = get(); i.value = v === null ? '' : String(v); if (title) l.title = title;
  i.onchange = () => { const t = i.value.trim(); if (t === '') { set(null); } else { const n = parseFloat(t); if (!(n >= lo && n <= hi)) { prompt(`${label}: enter a value from ${lo} to ${hi} ${unit}, or leave it empty.`); i.value = get() === null ? '' : String(get()); return; } set(n); } save(); panel(); draw(); };
  return l;
}
// The plan values (Implants; Sami, 16 Oct 2026; DESIGN-2026-10-16-knee-defaults.md): every value shown with the number the
// placement uses and its own origin label; typing a number makes it the surgeon's target, emptying it (or Reset) goes back
// to the assumed value; an unchanged value can be marked reviewed. The same card for a knee case (shared by its films) and
// a single knee film. No cutting-block choice: the slope is set by tilting the extramedullary guide (Sami, 16 Oct).
function kneeEntries() {
  const kc = !!S.kc, v = kneeVals(), xn = kneeTibFam() === XN_KNEE.tibia, ap = S.wf === 'kneeAP', lat = S.wf === 'kneeLat';
  const d = el(`<div class="kcvals"><div class="kch">Plan values${kc ? ' (the knee case\'s)' : ''}: each with its origin</div></div>`);
  const row = (key, more) => {
    const pv = v.pv[key], [lo, hi, step] = PV_BOUND[key];
    const l = el(`<label class="note kcnum">${esc(pvName(key))} <input type="number" min="${lo}" max="${hi}" step="${step}" aria-label="${esc(pvName(key))}"> ${pvUnit(key).trim()}${more ? ` ${esc(more)}` : ''}</label>`), i = l.querySelector('input');
    i.value = String(pv.v); i.placeholder = `assumed ${f1(pv.def)}`;
    i.onchange = () => {
      const t = i.value.trim(); if (t === '') { pvSet(key, null); save(); panel(); draw(); return; }
      const n = parseFloat(t); if (!(n >= lo && n <= hi)) { prompt(`${pvName(key)}: enter a value from ${lo} to ${hi}${pvUnit(key)}, or empty it for the assumed value.`); i.value = String(pvGet(key).v); return; }
      if (Math.abs(n - pvGet(key).v) >= 0.005 || pvGet(key).o !== 'surgeon') pvSet(key, Math.round(n * 100) / 100);
      save(); panel(); draw();
    };
    d.appendChild(l);
    d.appendChild(el(`<p class="note stdv${pv.o === 'surgeon' || pv.rv ? '' : ' warn'}" data-pv="${key}">${esc(pvLab(pv))}</p>`));
    const r = el('<div class="row"></div>');
    if (pv.o !== 'surgeon' && !pv.rv) r.appendChild(btn('Mark reviewed', () => { pvReview(key); save(); panel(); draw(); }, '', false, `You have looked at ${pvName(key).toLowerCase()} ${f1(pv.v)}${pvUnit(key)} and keep it unchanged (its origin stays as it is)`));
    if (pv.raw !== null) r.appendChild(btn('Reset', () => { pvSet(key, null); save(); panel(); draw(); prompt(`${pvName(key)}: back to the assumed ${f1(pvGet(key).v)}${pvUnit(key)}; press Auto template to place again.`); }, '', false, 'Back to the assumed value (said as such)'));
    if (r.children.length) d.appendChild(r);
  };
  if (kc || ap) row('femDistal', 'from the more distal condyle');
  { const tr = el(`<label class="note">Tibial resection measured from <select aria-label="Tibial resection reference"><option value="less">the reference plateau (the higher one, chosen by the page): ${XN_KNEE.tibialStd.less} mm</option><option value="medial">the lowest point of the medial plateau${pvXn() ? ' (XN manual, non-severe medial chondrosis)' : ''}: ${XN_KNEE.tibialStd.medial} mm</option></select></label>`), sel = tr.querySelector('select');
    if (v.tibRef === 'worn') sel.add(new Option('the lower plateau (saved before 16 Oct)', 'worn'));
    sel.value = v.tibRef; sel.onchange = () => { pvSetTibRef(sel.value); save(); panel(); draw(); prompt(`Tibial reference changed: the tibial resection is now the assumed ${f1(pvGet('tibial').v)} mm for it (enter your own if different); press Auto template to place again.`); }; d.appendChild(tr); }
  row('tibial', `below the ${TIB_SHORT[v.tibRef]}`);
  d.appendChild(el(`<p class="note">Tibial reference: ${esc(tibRefText())}.</p>`));
  if (kc || lat) { row('slope', pvXn() ? 'on the extramedullary guide' : ''); d.appendChild(el(`<p class="note">${pvXn() ? 'The slope is set by tilting the extramedullary guide (XN manual p. 10-11; no angled blocks). ' : ''}${XN_KNEE.slopeMin}–${XN_KNEE.slopeMax}° is an entry bound, not a manufacturer-approved or safe range.</p>`)); }
  if (kc || ap) row('vca', 'to the femoral shaft axis');
  row('rot', 'to the posterior condylar axis');
  if (PV_KEYS.some(k => v.pv[k].raw !== null) || pvAsked()) { const r = el('<div class="row"></div>'); r.appendChild(btn('Back to the assumed values', () => { pvResetAll(); save(); panel(); draw(); prompt('Every plan value is back to its assumed value (said as such); press Auto template to place again.'); }, '', false, 'Forget the values entered here and their reviews; the assumed values are used (and said) again')); d.appendChild(r); }
  d.appendChild(el(`<p class="note">The radiographic resection is shown, not a balanced gap; the insert is chosen at surgery${xn ? ` (PS inserts ${XN_KNEE.insertHeights.join(', ')} mm, manual p. ${XN_KNEE.insertPage})` : ''}.${xn ? ' The manual states no built-in slope of the tray or the insert, so the tray is drawn level on the cut.' : ''}</p>`));
  return d;
}
function kcCaseCard() {
  const K = S.kc, d = el('<details class="sec kccase" id="sec-kc"><summary><span class="segn">K</span><span class="segt">Knee case</span><span class="segr"></span></summary></details>');
  const nF = KC_ROLES.filter(r => K.films[r] && !K.films[r].missing).length;
  d.querySelector('.segr').textContent = `${nF} of 4 films; ${K.shared.size.femur ? `femur ${K.shared.size.femur.size}` : 'no femur'}, ${K.shared.size.tibia ? `tibia ${K.shared.size.tibia.size}` : 'no tibia'}; patella ${K.shared.patella}`;
  d.open = S.segHand.kc ?? S.segStep === 'save';
  d.querySelector('summary').addEventListener('click', () => { S.segHand.kc = !d.open; });
  d.appendChild(kcNum('Target HKA', () => K.shared.hkaTarget, v => { K.shared.hkaTarget = v; }, 165, 195, 0.5, '° (180 = straight; a target, not a prediction)'));
  if (K.shared.hkaTarget === null) d.appendChild(btn('Target: straight (180°)', () => { K.shared.hkaTarget = 180; save(); panel(); }, '', false, 'Your choice: a neutral mechanical axis as the target'));
  d.appendChild(kneeEntries());
  const pt = el('<label class="note">Patella: <select aria-label="Patella decision"><option value="undecided">not decided</option><option value="resurface">resurface</option><option value="retain">do not resurface</option></select></label>');
  pt.querySelector('select').value = K.shared.patella; pt.querySelector('select').onchange = e => { K.shared.patella = e.target.value; save(); panel(); draw(); };
  d.append(pt, el('<p class="note">Your decision: native thickness alone does not decide it (the bone left after the cut and the component\'s thickness matter).</p>'));
  d.appendChild(kcPfForm());
  return d;
}
function kcPfForm() {
  const K = S.kc, p = K.shared.pf, d = el('<div class="kcpf"><div class="kch">Patellofemoral assessment (from the skyline)</div></div>');
  const sel = (key, label, opts) => { const l = el(`<label class="note">${esc(label)} <select aria-label="${esc(label)}"><option value="">(not recorded)</option>${opts.map(([v, t]) => `<option value="${v}">${esc(t)}</option>`).join('')}</select></label>`), s = l.querySelector('select'); s.value = p[key] || ''; s.onchange = () => { p[key] = s.value; save(); panel(); draw(); }; return l; };
  d.append(sel('grade', 'Arthritis', [['none', 'none'], ['mild', 'mild'], ['moderate', 'moderate'], ['severe', 'severe']]), sel('facet', 'Lateral facet wear', [['no', 'no'], ['yes', 'yes']]),
    sel('dysplasia', 'Trochlear dysplasia', [['none', 'none'], ['suspected', 'suspected'], ['present', 'present']]), sel('tracking', 'Patella', [['central', 'central'], ['tilted', 'tilted'], ['subluxed', 'subluxed laterally']]));
  const nt = el('<label class="note">Note <input type="text" maxlength="300" aria-label="Patellofemoral note"></label>'), ni = nt.querySelector('input'); ni.value = p.note || ''; ni.onchange = () => { p.note = clip(ni.value, 300); save(); panel(); };
  d.appendChild(nt);
  return d;
}

// ---------- export ----------
// The patient's name as read in the clinic: DICOM "FAMILY^GIVEN^MIDDLE" becomes "GIVEN MIDDLE FAMILY", in the X-ray's
// own character set (UTF-8 when it says ISO_IR 192). A picture from disk has none: the name typed for it when its project
// was saved, if any (round 6).
function patientName() { return identNow().name; }
// The patient's identity now (Sami, 15 Oct): a knee case's own (filled from its films, or as saved or typed); a single X-ray's
// details typed or saved with its project, else its DICOM's. Raw: the record number as typed or read (recordNo() puts the
// hospital's code in front).
function identNow() {
  if (S.kc) { const p = S.kc.pt; return { name: p.name || '', recordNo: p.recordNo || '', hospital: p.hospital || '', age: Number.isInteger(p.age) ? p.age : null }; }
  const d = identFromMeta(S.img && S.img.meta), t = S.patientIn || {};
  return { name: t.name || d.name, recordNo: t.recordNo || d.recordNo, hospital: t.hospital || d.hospital, age: Number.isInteger(t.age) ? t.age : d.age };
}
// details typed for the patient (the panel's Patient card, Save project's question): a knee case's patient, else the X-ray's
function setIdent(o) {
  const c = {};
  if (o.name !== undefined) c.name = PJ.cleanId(o.name);   // never shortened: too long is refused when saved
  if (o.recordNo !== undefined) c.recordNo = PJ.cleanId(o.recordNo);
  if (o.hospital !== undefined) c.hospital = PJ.HOSP_RE.test(o.hospital) ? o.hospital : '';
  if (o.age !== undefined) c.age = Number.isInteger(o.age) && o.age >= 0 && o.age <= PJ.AGE_MAX ? o.age : null;
  if (S.kc) S.kc.pt = { ...S.kc.pt, ...c };
  else S.patientIn = { name: '', recordNo: '', hospital: '', age: null, ...(S.patientIn || {}), ...c, asked: true };
}
// a DICOM PatientName as read in the clinic ("FAMILY^GIVEN^MIDDLE" → "GIVEN MIDDLE FAMILY"), in its character set
function nameOf(m) {
  const raw = String(m.PatientName || ''); if (!raw.trim()) return '';
  let txt = raw;
  if (/ISO_IR 192/.test(String(m.SpecificCharacterSet || ''))) { try { txt = new TextDecoder('utf-8').decode(Uint8Array.from(raw, c => c.charCodeAt(0) & 0xFF)); } catch (e) { txt = raw; } }
  const [fam = '', giv = '', mid = ''] = txt.split('=')[0].split('^');
  return [giv, mid, fam].map(x => x.trim()).filter(Boolean).join(' ');
}
// The record number (MRN) as stored and shown: the hospital's code in front once ("SSC-12345"; Sami, 15 Oct), else as read
function recordNo() { const id = identNow(); return PJ.normMrn(id.hospital, id.recordNo); }
// File names: patient's name, record number (MRN), operated side and joint, date (Sami, 11 Oct 2026); in sharing mode
// none of the patient's details, only "shared", side, joint and date. A picture from disk without a record number uses the
// start of its content hash.
function baseName() {
  const m = (S.img && S.img.meta) || {}, d = new Date().toISOString().slice(0, 10);
  const id = String(recordNo() || m.AccessionNumber || ('local-' + ((S.src && S.src.hash) || '').slice(0, 8))).replace(/[^\w.-]+/g, '-');
  const joint = S.kc && kcProjFile ? 'knee-case' : S.ll ? (S.kc ? 'knee-long-leg' : 'long-leg') : { hip: 'hip', kneeAP: 'knee-AP', kneeLat: 'knee-lateral', kneeLong: 'knee-long-leg', kneeSky: 'knee-skyline' }[S.wf];
  if (S.set.share) return `shared_${SIDE[S.side].toUpperCase()}-${joint}_template_${d}`;
  const nm = patientName().replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 60);
  return `${nm ? nm + '_' : ''}${id}_${SIDE[S.side].toUpperCase()}-${joint}_template_${d}`;
}

// ---------- saved projects (round 6, 13 Oct 2026; projects.js has the file, the server and the list) ----------
// The project file is named like the pictures (name, record number, side and joint, date; sharing mode: none of the
// patient's details), as name_RECORD_RIGHT-hip_project_2026-10-13.aotp.
let kcProjFile = false;   // a knee case's project file is named for the case, not the film on screen
const projectFileName = () => { kcProjFile = true; try { return baseName().replace(/_template_/, '_project_') + '.aotp'; } finally { kcProjFile = false; } };
const clip = (v, n) => String(v || '').replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, n);
// the patient's details for the project and the list: none in sharing mode (the file itself still carries the X-ray)
// (15 Oct: with the hospital's code and the age; sharing mode keeps only the hospital's code, which names no patient)
function patientNow() {
  const id = identNow(), h = PJ.HOSP_RE.test(id.hospital) ? id.hospital : '';
  if (S.set.share) return h ? { hospital: h } : {};
  const o = {}, nm = PJ.cleanId(id.name), rn = PJ.cleanId(PJ.normMrn(h, id.recordNo));   // never cut short: a record number too long is refused (projects.js identProblem), never merged
  if (nm) o.name = nm; if (rn) o.recordNo = rn; if (h) o.hospital = h; if (Number.isInteger(id.age)) o.age = id.age;
  return o;
}
const patientKnown = () => !!(patientName() || recordNo() || (S.patientIn && S.patientIn.asked) || (S.kc && S.kc.pt.asked));
const hospitalKnown = () => !!identNow().hospital || !!(S.kc ? S.kc.pt.asked : S.patientIn && S.patientIn.asked);
// the implants planned, for the list (family and size per place; the stem's head and the knee's insert as their own
// lines, for the planned-versus-used comparison later). Those kept aside (a range not open here) are listed too.
function implantsNow() { return llHide() ? [] : S.ll ? llZones().filter(z => z !== 'align').flatMap(z => inZone(z, implantsOne)).slice(0, 24) : implantsOne(); }   // r44: both zones
function implantsOne() {
  const out = [], all = { ...((S.hidden || {})[S.wf] || {}), ...S.tpl };
  for (const sl of WF[S.wf].slots) {
    const t = all[sl]; if (!t || t.on === false) continue;
    const f = S.fam[t.fam];
    out.push({ slot: sl, label: clip(f ? f.label : t.fam, 120), size: clip(t.size, 32) });
    if (f && f.kind === 'cup' && linerOf(t)) out.push({ slot: 'liner' + sl.slice(-1), label: clip(`${linerName(t, linerOf(t))} liner`, 120), size: clip(t.size, 32) });   // r38b
    if (f && f.kind === 'stem' && t.head) { let nk = ''; try { nk = neckLabel(t, t.neck); } catch (e) { nk = String(t.neck || ''); } out.push({ slot: 'head' + sl.slice(-1), label: clip(`${(S.lib.heads[t.headKind] || {}).label || 'Femoral'} head, neck ${nk}${bipOf(t) ? ', inside the bipolar shell' : ''}`, 120), size: clip(t.head, 32) }); }
    { const bp = f && f.kind === 'stem' ? bipOf(t) : null; if (bp) out.push({ slot: 'bip' + sl.slice(-1), label: clip(`${bp.f.label}, outside diameter (mm)${bipAssumed(bp.z) ? ' (inner part assumed)' : ''}`, 120), size: clip(bp.z.size, 32) }); }   // r38
    if (f && f.kind === 'humeral-body' && t.head) out.push({ slot: 'humHead', label: 'Humeral head (Chunli standard, hemispherical; fit not confirmed)', size: clip(`${t.head} mm`, 32) });   // r33
    if (f && f.kind === 'tka-tibia' && t.thick) out.push({ slot: 'insert', label: clip(f.seatAboveCutMm ? 'Insert thickness' : f.thicknessDrawnMm ? `Insert number (sheet); drawn ${f1(tibJl(t))} mm tibial cut to joint line, composite not confirmed` : 'Tray and insert, total thickness', 120), size: f.thicknessDrawnMm ? String(t.thick) : `${t.thick} mm` });
    if (f && f.stems && t.stem && !extWhy(t)) out.push({ slot: sl + 'Stem', label: clip(`${(S.fam[f.stems] || {}).label || 'Stem extension'}`, 120), size: clip(t.stem.size, 32) });
  }
  if (S.wf === 'diaph') out.push(...diaImplants());   // r39: no implant placed; the defect and the sizes requested
  return out.slice(0, 24);
}
const metaNow = () => ({ patient: patientNow(), side: S.kc ? S.kc.side : S.side, procedure: S.kc ? 'knee' : PJ.procOf(wfBase()), implants: S.kc ? kcImplants() : implantsNow() });
// a knee case's implants for the list: its one femoral and one tibial component (no insert: chosen at surgery)
function kcImplants() {
  const out = [];
  for (const sl of ['femur', 'tibia']) {
    const z = S.kc.shared.size[sl], f = z && S.fam[z.fam]; if (!z) continue;
    out.push({ slot: sl, label: clip(f ? f.label : z.fam, 120), size: clip(z.size, 32) });
    const zs = f && f.sizes.find(q => q.size === z.size);   // its stem extension, only one the sheet draws for that size (r20)
    if (z.stem && f && f.stems && zs && (zs.stemSizes || []).includes(z.stem.size) && !(z.stem.geo && S.fam[f.stems] && S.fam[f.stems].geomId && z.stem.geo !== S.fam[f.stems].geomId)) out.push({ slot: sl + 'Stem', label: clip((S.fam[f.stems] || {}).label || 'Stem extension', 120), size: clip(z.stem.size, 32) });
  }
  return out;
}
// project.json (contract 5): the one authoritative state (fullState) with what the file and the list need around it.
// savedBy stays empty: the page does not know who is signed in (the patient list records it from the sign-in).
function projectNow() {
  const p = projectNow0();
  return S.set.share ? PJ.scrubForSharing(p) : p;   // sharing mode: one scrub for every project this page sends or writes (Fable, round 7)
}
function projectNow0() {
  if (S.kc) return kcProjectNow();   // a knee case: project version 2 (contract section 12)
  const m = S.img.meta || {}, o = S.orig, share = !!S.set.share, study = String(m.StudyInstanceUID || '');
  const state = fullState(); state.pendingSend = null;   // a send to PACS that got no answer belongs to this browser
  return { format: PJ.FORMAT, v: 1, savedAt: new Date().toISOString(), savedBy: null, app: 'Addis Ortho X-ray templating, round 6 (13 Oct 2026)' + (STANDALONE ? ', t.addisortho.com' : ''),
    libraryId: libIdNow(), patient: { ...patientNow(), side: S.side, procedure: PJ.procOf(wfBase()), ...(!share && /^[0-9.]{1,64}$/.test(study) ? { studyUID: study } : {}) },
    image: { fileName: share ? 'image.' + PJ.MIME_EXT[o.mime] : clip(String(o.name || '').split(/[\\/]/).pop(), 120) || 'image.' + PJ.MIME_EXT[o.mime], mime: o.mime, sha256: o.sha256, size: o.bytes.length, width: S.img.cols, height: S.img.rows },
    state };
}
// what projects.js uses of the page
const PROJ = { S, STANDALONE, prompt: t => prompt(t), panel: () => panel(), projectNow, metaNow, fileName: projectFileName, loadProject, patientKnown, markSaved, isDirty, revision, base: () => histBase, stepNow: () => stepNow().id, applyState: st => applyFullState(st),
  // the knee case (contract section 12): what is open (the case, else the X-ray), its case key, its films' bytes, what the
  // kept-changes store keeps of it, kept changes put back, and why its file cannot be written now
  unit: () => S.kc || S.img, isCase: () => !!S.kc, caseKeyNow: () => (S.kc ? PJ.caseKey(S.kc.cid) : (S.orig && S.orig.sha256) || null), images: () => kcImages(),
  keptNow: () => { if (S.kc) return kcKept(); const st = fullState(); st.pendingSend = null; return st; }, applyCaseState: st => kcApplyKept(st), caseWhy: () => kcWhy(),
  // the films of the knee case whose plan a save leaves out (their X-ray is not on this page and the patient list does not
  // hold their image): their kept changes must outlive every save until they reach the server (Astra, 15 Oct, P1 2)
  unsentRoles: () => kcUnsentRoles(),
  // the patient's identity (Sami, 15 Oct): as it is now, typed details put in, whether the hospital is known or was asked
  identNow: () => identNow(), setIdent: o => { setIdent(o); if (S.kc) S.kc.pt.asked = true; }, hospitalKnown: () => hospitalKnown(),
  // a restored knee case whose patient is still to be read from the list (Fable, round 8): read before a save is built;
  // throws like a failed request (the save is then tried again later)
  patientPending: () => !!(S.kc && S.kc.ptFrom),
  preparePatient: async () => {
    const K = S.kc; if (!K || !K.ptFrom) return;
    const id = K.ptFrom;
    let pt;
    try { pt = await PJ.casePatient(id); }
    catch (e) { if (e instanceof PJ.ApiError && e.status === 404) { if (S.kc === K) delete K.ptFrom; return; } throw e instanceof PJ.ApiError ? e : new PJ.ApiError(0, 'network'); }
    if (S.kc !== K || K.ptFrom !== id) return;
    K.pt = PJ.identFill(PJ.identFill(null, pt), K.pt); if (pt.hospital) K.pt.asked = true; delete K.ptFrom;
  },
  privacyKey: () => privacyKey() };   // (Astra, round 4: Save project's file checks it just before it is written)
function download(blob, name) { const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 5000); }
function exportJSON() {
  if (S.set.share) return;
  const m = S.img.meta || {};
  const doc = { what: 'Addis Ortho X-ray templating plan data (trial, draft for the surgeon). Reopen with "Open plan data" on the same X-ray.', accession: m.AccessionNumber || null, patientId: m.PatientID || null, studyDate: m.StudyDate || null,
    sopInstanceUID: m.SOPInstanceUID || null, fileSha256: (S.src && S.src.hash) || null, workflow: wfLabel(), side: S.side,
    implantSource: implantSource(), results: results(), plan: plan() };
  download(new Blob([JSON.stringify(doc, null, 1)], { type: 'application/json' }), baseName() + '.json');
}
// what the plan's outlines come from: its implant ranges, their makers and how each was made. Addis Ortho: Chunli's own
// 1:1 sheets and 3D models; another maker's: fitted to that maker's printed template and scaled to true size, as its
// source says (J&J Pinnacle: a 120 % sheet scaled by its ruler), never called a 3D model
function implantSource() {
  const used = [...new Set(Object.values(S.tplAll).flatMap(o => Object.values(o || {})).map(t => (S.fam[t.fam] || {}).range).filter(k => k && S.lib.ranges && S.lib.ranges[k]))];
  // the base Addis Ortho ranges are the families of library.json (no geomId); a vendor file's (ao-cck too: Chunli's CCK
  // sheets, r20) says how its outlines were made
  const R = S.lib.ranges || {}, base = k => S.lib.families.some(f => f.range === k && !f.geomId), ao = used.filter(base), parts = [];
  if (ao.length) parts.push(`${ao.map(k => R[k].label).join(', ')} (made by ${R[ao[0]].maker}): outlines from the manufacturer's 1:1 sheets and 3D models`);
  for (const k of used.filter(k => !base(k))) parts.push(`${R[k].label} (made by ${R[k].maker}): outlines fitted to the maker's printed template and scaled to true size (${[...new Set(S.lib.families.filter(f => f.range === k).map(f => f.source).filter(Boolean))].join('; ')})`);
  return parts.length ? parts.join('; ') : 'Addis Ortho range; outlines from the manufacturer (Chunli) 1:1 sheets and 3D models';
}
async function openPlanFile(file) {
  try {
    const doc = JSON.parse(await file.text()), p = doc.plan || doc, m = S.img.meta || {};
    if (!p || p.v !== 1) throw new Error('not a templating plan data file');
    const same = (doc.sopInstanceUID && doc.sopInstanceUID === m.SOPInstanceUID) || (doc.fileSha256 && doc.fileSha256 === (S.src && S.src.hash));
    if (!same && !confirm('This plan data was saved on a different X-ray (or the X-ray cannot be matched). Landmarks would not fit this film. Open it anyway?')) return;
    applyPlan(JSON.parse(JSON.stringify(p))); S.canal = {};
    $('wf').value = wfSel(); $('side').value = S.side; ensureSlots(); renderImage(); save(); panel(); draw();
    prompt('Plan data opened.');
  } catch (e) { prompt('Could not open that file: ' + (e.message || e)); }
}
// the saved picture's report, line by line ([kind, text, cls]): in sharing mode without the patient's name, age, record number
// or dates (Sami, 15 Oct: the option to leave the name out must leave it out of exports too; 28p reads these lines)
function exportItems() { return llBare(exportItems0); }   // Fable r44 P2-2
function exportItems0() {
  const m = S.img.meta || {}, R = results(), rot = ROT();
  const items = [['title', 'X-ray templating (trial)'], ['sub', 'Draft plan for the surgeon. Not for clinical decisions until validated.'],
    ['sub', S.set.share ? 'Shared copy: the patient\'s name, age, record number and dates are left out.' : (() => { const id = identNow(); return `${id.name ? id.name + ' · ' : ''}${id.hospital ? id.hospital + ' · ' : ''}${Number.isInteger(id.age) ? id.age + ' y · ' : ''}MRN ${recordNo() || '-'} · order ${m.AccessionNumber || '-'} · ${fmtDate(m.StudyDate) || ''}`; })()],
    ['sub', `${wfLabel()}${S.ll ? ` (${LL_ZONE[S.ll.zone].toLowerCase()} on screen)` : ''} · surgery side ${SIDE[S.side]} · ${CASE_WORD[ctype()]} case`]];
  // existing implants (amendment 6): the confirmed exclusions and the unresolved regions on lines of their own
  const nA = exActive().length, nU = exOpen().length;
  if (nA) items.push(['row', `Confirmed exclusions: ${nA} existing implant region${nA > 1 ? 's' : ''} (${['hardware', 'cement'].map(c => [c, exActive().filter(r => r.cls === c).length]).filter(x => x[1]).map(([c, n]) => `${n} ${c === 'cement' ? 'cement' : 'implant'}`).join(', ')}), dimmed with a dashed outline: excluded from the automatic measurements.`]);
  if (nU) items.push(['row', `Unresolved: ${nU} possible hardware region${nU > 1 ? 's' : ''} not confirmed (outlined in amber): not excluded, and the automatic steps that read the film around ${nU > 1 ? 'them' : 'it'} did not run.`, 'warn']);
  if (S.wf === 'diaph' && !S.kc) {   // r39: the order request first (the plan card's), then the full results
    items.push(['head', 'Order request to Chunli (draft): Chunli to confirm sizes before manufacture']);
    for (const l of diaOrderLines()) items.push(['row', l]);
    items.push(['head', 'Details']);
  }
  if (kneeCardOn()) {   // the knee plan card first, then the full results from the same calculation (Astra and Fable, 23 Oct)
    const C = kneeCardRows(R); items.push(['head', C.head]);
    for (const r of C.rows) items.push(['row', `${r.label}: ${r.val} [${r.tag[0]}]; ${r.note}`, r.cls]);
    items.push(['head', 'Details: alignment, calibration, coverage, sources']);
  }
  for (const r of R) if (!(r.length > 1 && (r[0] === 'Existing implants' || r[0] === 'Possible hardware not confirmed'))) items.push(r.length === 1 ? ['head', r[0]] : ['row', `${r[0]}: ${r[1]}`, r[2]]);
  for (const u of unconfirmed(S.ll ? llZones().filter(z => z !== 'align').flatMap(z => inZone(z, () => withBip(Object.values(S.tpl).filter(t => t.on)))) : withBip(Object.values(S.tpl).filter(t => t.on)))) items.push(['row', `${u}.`, 'warn']);   // every outline drawn on it
  const nS = S.showOriginal ? 0 : S.clean.strokes.length;
  if (nS) items.push(['row', `Film cleaned for display: ${nS} area${nS > 1 ? 's' : ''} (leads, clips, labels) filled from the film around ${nS > 1 ? 'them' : 'it'}, outlined with thin dashes; not anatomy. Measurements use the original film.`, 'warn']);
  if (S.clean.crop) items.push(['sub', 'Picture cropped to the area of interest.']);
  if (Math.abs(rot) > 0.001) items.push(['sub', `Picture turned ${f1(rot * 180 / Math.PI)}° to level the pelvis (display only; measurements are on the film as taken).`]);
  items.push(['sub', 'A printout is not true size; measure on screen or use the scale bar.']);
  return items;
}
// The saved picture: the film with the plan on the left, the report in its own column on the right (never over the
// anatomy), warnings in colour, a scale bar at the joint plane, and a note that a printout is not true size.
function exportCanvas(maxSide = 3600) {
  const cp = area(), rot = ROT(), c0 = imgC(), qs = corners(cp).map(p => rotP(p, rot, c0));
  const bx0 = Math.min(...qs.map(p => p.x)), by0 = Math.min(...qs.map(p => p.y)), cols = Math.max(...qs.map(p => p.x)) - bx0, rows = Math.max(...qs.map(p => p.y)) - by0;
  const scale = Math.min(1, maxSide / Math.max(cols, rows)), T = p => { const q = rotP(p, rot, c0); return G.P((q.x - bx0) * scale, (q.y - by0) * scale); };
  const W0 = Math.round(cols * scale), H0 = Math.round(rows * scale), lw = Math.max(1.5, Math.max(W0, H0) / 1100);
  const repW = Math.round(Math.max(420 * lw, W0 * 0.42)), pad = 14 * lw, fs = 12 * lw, lh = fs * 1.42;
  const meas = document.createElement('canvas').getContext('2d'); meas.font = `${fs}px system-ui, sans-serif`;
  const wrap = (text, width) => { const out = []; let line = ''; for (const w of String(text).split(' ')) { const t = line ? line + ' ' + w : w; if (meas.measureText(t).width > width && line) { out.push(line); line = w; } else line = t; } if (line) out.push(line); return out; };
  const items = exportItems(), nS = S.showOriginal ? 0 : S.clean.strokes.length;
  const lines = []; for (const [kind, text, cls] of items) for (const l of wrap(text, repW - 2 * pad)) lines.push([kind, l, cls]);
  const logoH = LOGO.complete && LOGO.naturalWidth ? Math.round(56 * lw) : 0;
  const H = Math.max(H0, Math.round(pad * 2 + logoH + lines.length * lh + 10 * lw));
  const c = document.createElement('canvas'); c.width = W0 + repW; c.height = H;
  const g = c.getContext('2d'); g.fillStyle = '#000'; g.fillRect(0, 0, W0, H);
  g.save(); g.setTransform(scale, 0, 0, scale, -bx0 * scale, -by0 * scale);
  if (rot) { g.translate(c0.x, c0.y); g.rotate(rot); g.translate(-c0.x, -c0.y); }
  g.beginPath(); g.rect(cp.x0, cp.y0, cp.x1 - cp.x0, cp.y1 - cp.y0); g.clip(); g.drawImage(S.off, 0, 0); g.restore();
  const poly = corners(cp).map(T);
  g.save(); g.beginPath(); poly.forEach((q, i) => (i ? g.lineTo(q.x, q.y) : g.moveTo(q.x, q.y))); g.closePath(); g.clip();
  overlay(g, T, lw, false, S.set.picture !== 'full');
  if (nS) {   // where the film was filled: a thin dashed outline round each stroke (Astra, 11 Oct: a count alone is not enough)
    g.save(); g.strokeStyle = 'rgba(255,255,255,0.55)'; g.lineWidth = Math.max(1, lw * 0.7); g.setLineDash([4 * lw, 4 * lw]);
    for (const st of S.clean.strokes) {
      let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
      for (const q of st.pts) { x0 = Math.min(x0, q.x - st.r); y0 = Math.min(y0, q.y - st.r); x1 = Math.max(x1, q.x + st.r); y1 = Math.max(y1, q.y + st.r); }
      x0 = Math.max(x0, cp.x0); y0 = Math.max(y0, cp.y0); x1 = Math.min(x1, cp.x1); y1 = Math.min(y1, cp.y1);   // the part inside the crop
      if (x1 > x0 && y1 > y0) { const q = corners({ x0, y0, x1, y1 }).map(T); g.beginPath(); q.forEach((p, i) => (i ? g.lineTo(p.x, p.y) : g.moveTo(p.x, p.y))); g.closePath(); g.stroke(); }
    }
    g.restore();
  }
  g.restore();
  if (mm()) {   // scale bar: 50 mm at the plane of the calibration marker
    const mk = S.auto.markers[0], mq = mk ? T(G.P(mk.x, mk.y)) : null, right = !(mq && mq.x > W0 / 2 && mq.y > H0 / 2);   // bottom right, unless the marker is there
    const L = 50 / mm() * scale, x0 = right ? W0 - L - 28 * lw : 20 * lw, y0 = H0 - 24 * lw;
    g.fillStyle = 'rgba(0,0,0,.7)'; g.fillRect(x0 - 8 * lw, y0 - 26 * lw, L + 16 * lw, 40 * lw);
    g.strokeStyle = '#fff'; g.lineWidth = 3 * lw; g.beginPath(); g.moveTo(x0, y0); g.lineTo(x0 + L, y0); g.moveTo(x0, y0 - 6 * lw); g.lineTo(x0, y0 + 6 * lw); g.moveTo(x0 + L, y0 - 6 * lw); g.lineTo(x0 + L, y0 + 6 * lw); g.stroke();
    g.font = `600 ${12 * lw}px system-ui, sans-serif`; const lab = `50 mm at the joint (${calEst(S.cal) ? 'machine estimate' : isCtCal(S.cal) ? 'CT scanner scale' : 'calibrated'})`, tw = g.measureText(lab).width;
    const lx = Math.max(4 * lw, Math.min(x0, W0 - tw - 8 * lw));   // the label stays on the film, clear of the report
    g.fillStyle = 'rgba(0,0,0,.7)'; g.fillRect(lx - 4 * lw, y0 - 24 * lw, tw + 8 * lw, 18 * lw); g.fillStyle = '#fff'; g.fillText(lab, lx, y0 - 10 * lw);
  }
  g.fillStyle = '#fff'; g.fillRect(W0, 0, repW, H); g.fillStyle = COL.green; g.fillRect(W0, 0, 6 * lw, H);
  let y = pad;
  if (logoH) { const lwid = logoH * LOGO.naturalWidth / LOGO.naturalHeight; g.drawImage(LOGO, W0 + pad, y, lwid, logoH); y += logoH; }
  for (const [kind, text, cls] of lines) {
    y += lh;
    g.font = `${kind === 'title' ? 'bold ' : kind === 'head' ? '600 ' : ''}${kind === 'title' ? fs * 1.15 : fs}px system-ui, sans-serif`;
    g.fillStyle = kind === 'title' || kind === 'head' ? COL.green : kind === 'sub' ? '#444' : cls === 'bad' ? '#a40000' : cls === 'warn' ? '#8a5a00' : '#000';
    g.fillText(text, W0 + pad, y);
  }
  return c;
}
// landmarks marked red (refused): saving or sending a plan built on them needs them fixed first (Fable, 11 Oct)
function refused() { const one = () => lmDefs().filter(d => lmProblem(d.key) || lmBroken(d.key)).map(d => d.label); return S.ll ? [...new Set(llZones().flatMap(z => inZone(z, one)))] : one(); }   // Fable r44 P3-4: every zone's red landmarks
// Outputs and privacy (Astra, round 3, P2): an export taken outside sharing mode carries the patient's details; if sharing
// mode is turned on (or another X-ray opened) while it is being made, it is not downloaded or sent. privacyKey() is taken
// when the export starts and checked again just before the file or the send leaves the page.
const privacyKey = () => JSON.stringify([!!S.set.share, S.key, S.kc ? S.kc.cid : null]);
const privacyChanged = (k0, what) => { if (k0 === privacyKey()) return false; prompt(`${what} not saved: sharing mode or the X-ray changed while it was being made. Make it again.`); return true; };
function exportPNG() {
  if (S.filling) return prompt('Wait a moment: a stroke is still being filled.');
  const nr = notReady(); if (nr) return prompt(nr); const rf = refused(); if (rf.length && !confirm(`These landmarks are marked red: ${rf.join('; ')}. Save the picture anyway (the red marks are on it)?`)) return;
  const c = exportCanvas(), k0 = privacyKey();
  if (S.set.share) return preview(c, () => c.toBlob(bl => { if (!privacyChanged(k0, 'Picture')) download(bl, baseName() + '.png'); }, 'image/png'));   // Astra, 11 Oct: look before sharing
  c.toBlob(bl => { if (!privacyChanged(k0, 'Picture')) download(bl, baseName() + '.png'); }, 'image/png');
}
// The picture as it will be saved, to check for a name or number burned into the X-ray before it leaves the hospital.
function preview(c, ok) {
  const d = el(`<div class="pv"><div class="pvbox"><p><b>Look at the whole picture before saving it for sharing.</b> Names, record numbers or dates burned into the X-ray itself are not removed automatically: if you see any, cancel and cover them with "Black out" (2 · Prepare).</p><div class="pvimg"></div><div class="row"></div></div></div>`);
  const im = new Image(); im.src = c.toDataURL('image/jpeg', 0.85); d.querySelector('.pvimg').appendChild(im);
  const close = () => d.remove();
  d.querySelector('.row').append(btn('No name or number on it: save', () => { close(); ok(); }, 'primary'), btn('Cancel', close));
  document.body.appendChild(d);
}
// The same picture as a DICOM Secondary Capture in the X-ray's own study (JPEG inside, so a few MB), to send to the
// archive with the hospital's usual tools. The page itself cannot write to the archive (read-only on purpose).
async function planDicom() {
  const c = exportCanvas(2400), m = S.img.meta || {};
  const bl = await new Promise(res => c.toBlob(res, 'image/jpeg', 0.92)), jpeg = new Uint8Array(await bl.arrayBuffer());
  return secondaryCapture({ jpeg, width: c.width, height: c.height, meta: m, seriesDescription: `Templating plan ${SIDE[S.side]} ${PJ.procOf(wfBase())} (draft)`,
    comments: `Addis Ortho X-ray templating (trial). ${wfLabel()}, surgery side ${SIDE[S.side]}. Draft for the surgeon; not true size.${S.clean.strokes.length && !S.showOriginal ? ` ${S.clean.strokes.length} area(s) of the film filled for display (not anatomy), outlined on the picture.` : ''}` });
}
async function exportDICOM() { if (S.set.share) return; if (S.filling) return prompt('Wait a moment: a stroke is still being filled.'); const nr = notReady(); if (nr) return prompt(nr); const rf = refused(); if (rf.length) return prompt(`Fix the landmarks marked red first: ${rf.join('; ')}.`); const k0 = privacyKey(), { bytes } = await planDicom(); if (S.set.share || privacyChanged(k0, 'DICOM')) { if (S.set.share) prompt('DICOM not saved: sharing mode is on (a DICOM carries the patient\'s details).'); return; } download(new Blob([bytes], { type: 'application/dicom' }), baseName() + '.dcm'); }
// "Send to PACS": the same DICOM picture into the X-ray's own study, through the one checked way in
// (templating/store/store.py checks the user's capability, the picture, and that the study and patient match). The
// surgeon confirms the patient first.
// Is this instance in the study? true, false, or null when the archive cannot be asked (read-only query through pacs-web).
async function inArchive(study, sop) {
  try {
    const r = await fetch(`${RS}/studies/${encodeURIComponent(study)}/instances?` + new URLSearchParams({ SOPInstanceUID: sop }), { headers: { Accept: 'application/dicom+json' }, credentials: 'same-origin' });
    if (r.status === 204) return false;
    if (!r.ok) return null;
    return ((await r.json()) || []).length > 0;
  } catch (e) { return null; }
}
async function sendToPACS() {
  if (STANDALONE) return;
  const V = VIEWER, NOT = V ? 'Not saved in the viewer' : 'Not sent to PACS';   // the viewer build: the same send, other words
  if (S.filling) return prompt('Wait a moment: a stroke is still being filled.');
  const m = S.img.meta || {}, nr = notReady(); if (nr) return prompt(nr);
  const rf = refused(); if (rf.length) return prompt(`Fix the landmarks marked red before ${V ? 'saving in the viewer' : 'sending to PACS'}: ${rf.join('; ')}.`);
  if (S.set.share) return prompt(`${NOT}: sharing mode is on.`);
  // taken before anything is awaited: another X-ray, knee film or sharing mode afterwards aborts the send before the
  // confirmation, before the picture is made and before it is sent (Astra, round 4, P1)
  const k0 = privacyKey(), gone = () => { if (privacyKey() === k0) return false; S.pending = null; prompt(`${NOT}: the X-ray on screen or sharing mode changed meanwhile. ${V ? 'Save' : 'Send'} again.`); panel(); return true; };
  if (!m.StudyInstanceUID || !m.SOPInstanceUID) return prompt(`Only an X-ray from ${V ? 'the viewer' : 'the archive'} (or a DICOM file of one) can be ${V ? 'saved in the viewer' : 'sent to PACS'}.`);
  const who = [patientName() || '(no name in the image)', m.PatientID ? 'MRN ' + m.PatientID : '', m.AccessionNumber ? 'order ' + m.AccessionNumber : '', fmtDate(m.StudyDate)].filter(Boolean).join(', ');
  // the picture's identity: everything that changes what is drawn (plan, results, black-outs, window, inversion, labels,
  // sharing); a retry after an unclear answer resends the same picture only if none of it changed (Sol, 11 Oct)
  const ident = JSON.stringify([snapshot().j, S.masks, S.clean, S.showOriginal, S.set.picture, S.wl, S.invertView, S.labels, S.set.share, results()]);
  if (S.pending && S.pending.key === S.key && (S.pending.ident !== ident || !S.pending.bytes)) {   // an unclear send whose picture changed, or from before a reload: is it there?
    const there = await inArchive(m.StudyInstanceUID, S.pending.sop);
    if (gone()) return;
    if (there === true) { S.sent = { sop: S.pending.sop, series: S.pending.series, at: new Date(), earlier: S.pending.ident !== ident, late: true }; S.pending = null; save(true); }
    else if (there === null && !confirm(`An earlier version of this plan may or may not have reached ${DEST} (no answer at the time, and ${V ? 'the viewer' : 'the archive'} cannot be asked now). ${V ? 'Save' : 'Send'} this version anyway?`)) return;
    S.pending = null; save(true);
  }
  if (S.sent && !confirm(`${S.sent.earlier ? 'An earlier version of this plan is' : S.sent.late ? `The earlier send did reach ${DEST}: this plan is` : 'This plan was'} already in ${DEST}. ${V ? 'Save another copy (the viewer will hold both)' : 'Send another copy (the archive will hold both)'}?`)) return;
  if (gone()) return;
  if (!confirm(`${V ? 'Save this plan in the viewer' : 'Send this plan to PACS'}, into the X-ray's own study?\n\nPatient: ${who}\nPlan: ${wfLabel()}, surgery side ${SIDE[S.side]}\n\nCheck it is the right patient.`)) return;
  if (gone()) return;
  prompt(V ? 'Saving the plan in the viewer…' : 'Sending the plan to PACS…');
  try {
    // after an unclear answer the same picture (same instance) is sent again, so a retry cannot make a second copy
    const pend = S.pending && S.pending.key === S.key && S.pending.ident === ident ? S.pending : (S.pending = { key: S.key, ident, ...(await planDicom()) });
    if (gone()) return;   // checked again just before it leaves (Astra, rounds 3 and 4)
    let r, js = {};
    try { r = await fetch('store', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/dicom', 'X-AOC-Templating': '1' }, body: pend.bytes }); js = await r.json().catch(() => ({})); }
    catch (e) { S.sendError = V ? 'no answer from the viewer (is it still open?); "Save in the viewer" again checks the viewer first, so it will not make a second copy' : 'no answer from the server; "Send to PACS" again checks the archive first, so it will not make a second copy'; save(true); prompt(`Not sure it was ${V ? 'saved' : 'sent'}: ` + S.sendError + '.'); panel(); return; }
    if ((r.ok && js.ok) || (r.status === 409 && js.sop === pend.sop)) {
      S.sent = { sop: pend.sop, series: js.series || pend.series, at: new Date(), warning: js.result && /warning/.test(js.result) }; S.pending = null; S.sendError = null;
      save(true);   // kept with the plan at once: a reload still knows it was sent (Sol, 11 Oct)
      prompt(`${V ? 'Saved in the viewer' : 'Sent to PACS'}${S.sent.warning ? ` (${V ? 'the viewer' : 'the archive'} stored it with a warning)` : ''}: the plan is in this X-ray's study as "Templating plan ${SIDE[S.side]} ${PJ.procOf(wfBase())} (draft)".${V ? ' From the viewer it can be sent to PACS.' : ''}`);
    } else {
      S.sendError = js.error || (V ? answered(r.status) : `HTTP ${r.status}`); if (r.status < 500) S.pending = null; save(true);   // refused for a reason: a new picture next time
      prompt(`${NOT}: ${S.sendError}.`);
    }
  } catch (e) { S.sendError = e.message || String(e); prompt(`${NOT}: ` + S.sendError); }
  panel();
}

window.addEventListener('error', e => { const a = $('alerts'); if (a) a.innerHTML = `<div>Something went wrong (${esc(e.message || 'error')}). The plan is kept in this browser: press Undo or reload.</div>`; });
window.addEventListener('unhandledrejection', e => { const a = $('alerts'); if (a) a.innerHTML = `<div>Something went wrong (${esc((e.reason && e.reason.message) || e.reason || 'error')}). The plan is kept: press Undo or reload.</div>`; });
// for the automated UI tests (workflow/28f-28l); state / applyState: the round 6 hook for saved projects (fullState)
window.aocTemplating = { S, toS, toI, results, plan, lmGeom, goBack, backTarget, recalibrate, headAt: slot => headAt(S.tpl[slot]), corAt: slot => corAt(S.tpl[slot]), cupCentre: slot => cupCentre(S.tpl[slot]), shellCentre: slot => xf(S.tpl[slot])([0, 0]), linerOff: slot => linerOff(S.tpl[slot]), linerTypesFor: (slot, head) => linerTypesFor(S.tpl[slot], head), linerFix: sd => linerFix(sd), replanAfter: slot => replanAfter(slot), dirOf: (slot, l) => dirOf(S.tpl[slot], l), /* r38b */ setHemi: (sd, on) => setHemi(sd, on), hemiOn: sd => hemiOn(sd), bipNative: sd => bipNative(sd), bipOf: slot => { const b = bipOf(S.tpl[slot]); return b ? { fam: b.f.id, size: b.z.size, outer: b.z.outerDiameter, headOffset: b.z.headOffset } : null; }, /* r38 */ stemFit: slot => { const t = S.tpl[slot], tr = canalTraceFor(slotSide(slot)); return tr ? stemFit(t, tr) : null; },
  // the knee case (28p): its state, a film shown, a film added from bytes, the knee film on screen made a case
  kc: () => (S.kc ? { cid: S.kc.cid, side: S.kc.side, active: S.kc.active, shared: clone(S.kc.shared), notices: [...S.kc.notices], pt: { ...S.kc.pt }, films: Object.fromEntries(Object.entries(S.kc.films).map(([r, f]) => [r, { sha256: f.image.sha256, missing: !!f.missing, bytes: !!(f.orig && f.orig.bytes), qc: { ...f.qc }, done: kcFilmDone(r) }])) } : null),
  kcShow: role => kcShow(role), kcStart: () => kcStart(), kcAdd: (role, bytes, name, startNew = false) => kcAddFile(bytes, name, role, { startNew }), kcXfer: () => kcXfer(), kcStale: () => kcXferStale(),
  kcProject: () => (S.kc ? projectNow() : null), kcKept: () => (S.kc ? kcKept() : null),
  // 15 Oct (28p): another film's landmark as the case reads it, the saved-state fingerprint, the saved picture's report lines,
  // the patient's identity now
  kcLm: (role, key) => { const g = S.kc ? kcGeo(role) : null; return g ? g.g(key) : null; }, rev: () => revision(), pictureLines: () => exportItems().map(i => i[1]),
  // 16 Oct (28p): the plan values with their origin and review, whether the question would be asked, the question open
  planValues: () => ({ ...Object.fromEntries(PV_KEYS.map(k => { const p = pvGet(k); return [k, { v: p.v, o: p.o, rv: p.rv, raw: p.raw, label: pvLab(p) }]; })), tibRef: pvTibRef(), asked: pvAsked(), needsAsk: pvNeedsAsk() }), card: () => (kneeCardOn() ? kneeCardRows(results()) : null), ident: () => ({ ...identNow(), patient: patientNow() }), kneeVals: () => kneeVals(),
  // r20 (28r): a knee component's stem extension as drawn (image px), the port in image px, and why it is not drawn
  kneeStem: slot => { const t = S.tpl[slot]; if (!t) return null; const gm = geomOf(t), r = stemRings(t, gm), X = xf(t); return { why: extWhy(t), px: r ? r.map(q => q.map(X)) : null, port: gm && gm.stemPort ? X(gm.stemPort.p) : null, dir: gm && gm.stemPort ? G.sub(X([gm.stemPort.p[0] + gm.stemPort.dir[0], gm.stemPort.p[1] + gm.stemPort.dir[1]]), X(gm.stemPort.p)) : null, jl: t.thick !== undefined ? tibJl(t) : null }; },
  touch: () => { save(); panel(); draw(); },
  bipHeadOf: (sd = S.side) => { const t = S.tpl['stem' + sd], b = bipOf(t); return b ? { size: b.z.size, inner: bipHead(b.f, b.z), head: t.head, headKind: t.headKind, neck: t.neck, necks: neckOpts(t).map(n => n[0]), cor: corAt(t), headAt: headAt(t), off: b.z.headOffset } : null; },
  bipSet: (size, sd = S.side) => { const t = S.tpl['stem' + sd], b = bipOf(t); if (!b) return false; const z = b.f.sizes.find(q => q.size === size); if (!z) return false; const ih = bipHead(b.f, z), off = t.headKind !== ih.kind || t.head !== String(ih.diameter), was = t.head; changeStem(t, () => { t.bip = { fam: b.f.id, size, userSize: true }; if (off) setHead(t, ih.kind, String(ih.diameter)); }); if (off) t.userHead = false; save(); panel(); draw(); if (off) prompt(bipSwitchMsg(t, size, was, ih)); return true; },
  dia: () => { const C = diaCalc(); return { u: { ...C.u }, P: C.P, far: C.far, stations: C.stations, stems: C.stems, phys: C.phys, frame: C.F, order: diaOrderLines(C), implants: diaImplants(), latFilm: latFilm(), wfs: [...S.wfs] }; },
  diaSet: o => { S.dia = diaIn({ ...diaU(), ...o }); save(); panel(); draw(); }, redo: () => redo(), undo: () => undo(), notReady: () => notReady(),
  state: fullState, applyState: applyFullState, stepNow: () => stepNow().id, sheet: () => sheet && sheet.get(),
  project: () => projectNow(), caseRef: () => S.caseRef && { ...S.caseRef }, dirty: () => isDirty(), autosave: PJ.autoTest,
  // the automatic plan (13 Oct 2026, 28m): the stem note of a side, the recommendation, and every placement the search tried
  stemPlan: sd => S.notes['stemPlan' + sd] || null, recommend: () => recommendStem(), pending: () => PENDING.map(p => ({ ...p })),
  stemCands: sd => { const P = stemCandidates(sd, {}); return P ? P.cands.map(c => ({ fam: c.fam, size: c.size, neck: c.neck, missH: c.missH, missV: c.missV, ev: c.ev, maxIn: c.maxIn, cls: c.cls, comb: c.comb, sizeIdx: c.sizeIdx, fill: c.fill })) : null; },
  // revision and conversion (28q): the case type, the regions, the revision record, the canal trace, the gates
  caseType: () => ctype(), setCaseType: v => setCaseType(v), exist: () => (S.exist ? clone(S.exist) : null), rv: () => clone(rvOf()), exDetect: () => exDetect(), exSet: (id, cls) => exSet(id, cls),
  exAdd: (r) => exChange(e => { e.regions.push({ id: exNextId(e), src: 'user', conf: true, ...clone(r) }); }), exNone: on => exChange(e => { e.none = !!on; }),
  canalTrace: sd => { const tr = canalTraceFor(sd); return tr ? { n: tr.n, axis: tr.axis, masked: tr.masked, observed: tr.observed, spanMm: tr.spanMm, levels: tr.levels.map(l => ({ mid: l.mid, w: l.w })) } : null; },
  canalUsePairs: sd => canalUsePairs(sd), canalKeep: sd => canalKeep(sd), canalReviewed: sd => canalReviewed(sd), stemGate: sd => stemGate(sd), kneeRevWhy: () => kneeRevWhy(), setRef: (k, v) => setRef(k, v), refLabel: k => refLabel(k),
  rvSet: o => { rvSet(v => Object.assign(v, clone(o))); save(); panel(); draw(); }, setCupTargetMirror: () => setCupTargetMirror(),
  // (Astra, 16 Oct: the reviewer's cases) an implant put at a point, a slot placed by its rule, where it is
  mirror: () => mirrorOther(), setSize: (slot, size) => { setSize(slot, size); save(); panel(); draw(); }, stemEvidence: sd => stemEvidenceWhy(sd), removeCup: sd => { delete S.tpl['cup' + sd]; save(); panel(); draw(); }, placeSlot: slot => { addTemplate(slot); },
  stemOf: sd => { const t = S.tpl['stem' + sd]; return t ? { ...stemKeep(t), userHead: !!t.userHead } : null; },
  autoSizePlace: sd => { const b = [...document.querySelectorAll(`.tpl[data-slot="stem${sd}"] button`)].find(x => x.textContent === 'Auto size and place'); if (b) b.click(); return !!b; },
  putAt: (slot, x, y) => { const t = S.tpl[slot] || newTemplate(slot); if (!t) return null; t.x = x; t.y = y; S.tpl[slot] = t; save(); return { x: t.x, y: t.y }; },
  autoPlace: slot => { if (S.tpl[slot]) autoPlace(slot, { keepSize: true }); const t = S.tpl[slot]; return t ? { x: t.x, y: t.y, why: (S.notes['fit' + (SLOT[slot].side || '')] || {}).why || S.notes.kneeRev || '' } : null; } };
// r44 (28ad): the long-leg plan's zone, its rows, the implants the saved picture draws
Object.assign(window.aocTemplating, { ll: () => (S.ll ? { ...S.ll } : null), llStart: () => llStart(), llZone: z => llZone(z), llWhy: () => llImplWhy(), llTotalFemur: () => llTotalFemur(), llTF: () => llTF(),
  pictureSlots: () => { drawnSlots = []; try { exportCanvas(1200); return [...drawnSlots]; } finally { drawnSlots = null; } }, implants: () => implantsNow() });
boot();
