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
import { findBalls, findMarkers, traceCanal, canalFit, fitIsEvidence, stemRegion } from './auto.js';
import { GUIDE } from './guide.js';
import { secondaryCapture } from './dicomwrite.js';
import { cleanCopy, addStroke } from './clean.js';
import { focusKind, shortcut, keepPanel, restorePanel, makeSheet } from './uikit.js';
import * as PJ from './projects.js';
import { teardropCentre, anatomicCentre, anatomicRoofFit, cupVsTeardrop, cupSizeFor, offsetClasses, chooseStem, scaleUncertainty } from './autoplan.js';

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
function hipLm(o, scope = (typeof S !== 'undefined' && S.scope) || 'both', ref = (typeof S !== 'undefined' && S.ref) || 'own') {
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
  ];
  // a film of one hip: only what is on it (the other side's teardrop and trochanter are not there; Astra, 11 Oct)
  return scope === 'one' ? all.filter(d => d.key.endsWith(o)) : all;
}
const WF = {
  hip: { label: 'Hip AP: total hip', view: 'AP', slots: ['cupR', 'stemR', 'cupL', 'stemL'], get lm() { return hipLm(S.side); } },
  kneeAP: {
    label: 'Knee AP / long leg: total knee', view: 'AP', slots: ['femur', 'tibia'],
    lm: [
      { key: 'hip', short: 'hip centre', type: 'circle', label: 'Femoral head (long-leg film)', hint: 'click 3 points on the rim', optional: true },
      { key: 'femShaft', short: 'femoral shaft', type: 'line', label: 'Femoral shaft axis', hint: 'centre of the shaft, high then low' },
      { key: 'knee', short: 'knee', type: 'point', label: 'Knee centre', hint: 'top of the notch, between the tibial spines' },
      { key: 'condMed', short: 'MC', type: 'point', label: 'Medial condyle, lowest point' },
      { key: 'condLat', short: 'LC', type: 'point', label: 'Lateral condyle, lowest point' },
      { key: 'platMed', short: 'MP', type: 'point', label: 'Medial tibial plateau', hint: 'joint surface, middle of the plateau' },
      { key: 'platLat', short: 'LP', type: 'point', label: 'Lateral tibial plateau', hint: 'joint surface, middle of the plateau' },
      { key: 'ankle', short: 'ankle', type: 'point', label: 'Ankle centre (long-leg film)', hint: 'centre of the talus', optional: true },
      { key: 'tibShaft', short: 'tibial shaft', type: 'line', label: 'Tibial shaft axis (short film)', hint: 'centre of the shaft, high then low', optional: true },
    ],
  },
  kneeLat: {
    label: 'Knee lateral: total knee', view: 'LAT', slots: ['femur', 'tibia'],
    lm: [
      { key: 'antCortex', short: 'ant. cortex', type: 'line', label: 'Anterior femoral cortex', hint: 'two points on the front of the distal shaft' },
      { key: 'postCond', short: 'post. condyles', type: 'point', label: 'Posterior condyles, back edge', hint: 'most posterior point' },
      { key: 'platAnt', short: 'plateau front', type: 'point', label: 'Tibial plateau, front edge' },
      { key: 'platPost', short: 'plateau back', type: 'point', label: 'Tibial plateau, back edge' },
      { key: 'tibAxis', short: 'tibial axis', type: 'line', label: 'Tibial shaft axis', hint: 'centre of the shaft, high then low' },
    ],
  },
};
const NPTS = { point: 1, line: 2, circle: 3 };
const SLOT = {
  cupR: { kind: 'cup', side: 'R', label: 'Cup' }, stemR: { kind: 'stem', side: 'R', label: 'Stem' },
  cupL: { kind: 'cup', side: 'L', label: 'Cup' }, stemL: { kind: 'stem', side: 'L', label: 'Stem' },
  femur: { kind: 'tka-femur', label: 'Femoral component' }, tibia: { kind: 'tka-tibia', label: 'Tibial tray' },
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
  cal: null, ballD: 25.0, vca: 6.0,
  lm: {}, tpl: {}, tplAll: {}, hidden: {}, hiddenGeo: {}, active: null, rulers: [], restoreNote: '',
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
    S.wfs = Object.keys(WF).filter(k => WF[k].slots.some(sl => hasKind(SLOT[sl].kind)));
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
  for (const k of S.wfs) $('wf').add(new Option(WF[k].label, k));
  $('wf').onchange = () => { switchWf($('wf').value); S.arm = null; prompt(''); save(); panel(); draw(); };
  $('side').onchange = () => { S.side = $('side').value; S.sideSet = true; S.arm = null; prompt(''); refreshFlips(); save(); panel(); draw(); };
  $('file').onchange = e => { if (e.target.files[0]) openFile(e.target.files[0]); e.target.value = ''; };
  // t.addisortho.com: Open shows the start card, with the file chooser and the templated-patients list (round 6); the
  // hospital copy has no list, so Open goes straight to the file chooser
  $('openBtn').onclick = () => (STANDALONE ? showOpenCard() : $('file2').click());
  if (STANDALONE) { $('openBtn').title = 'Open an X-ray or a saved project (.aotp), or a templated patient from the list'; $('openBtn').setAttribute('aria-label', 'Open: an X-ray, a project or a templated patient'); }
  addEventListener('keydown', e => { if (e.key === 'Escape' && S.img && document.body.classList.contains('choosing') && !document.querySelector('.pv')) hideOpen(); });
  $('panelBtn').onclick = () => { document.body.classList.toggle('nopanel'); resize(); };
  // Undo and Fit in the header, always in reach (Sami, 13 Oct); Fit needs no implant selected
  $('undoBtn').onclick = undo;
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
  if (VIEWER) $('foot').textContent = 'Addis Ortho DICOM Viewer · X-ray templating on this computer, offline. Trial software for planning practice; not for clinical decisions until validated and approved. Implants: Addis Ortho primary hip and knee range, outlines from the manufacturer\'s 1:1 template sheets and 3D models.';
  pendingNote();
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
  openMsg(e instanceof PJ.ProjectError ? 'Could not open the project' : 'Could not open the X-ray', String((e && e.message) || e));
}
function hideOpen() { $('open').style.display = 'none'; document.body.classList.remove('choosing'); resize(); }
// t.addisortho.com's Open: the start card with the file chooser and the templated-patients list (projects.js)
function showOpenCard() {
  openMsg(S.img ? 'Open another X-ray or a templated patient' : 'Open an X-ray to template', S.img ? 'Drop a DICOM, PNG or JPEG file or a saved project (.aotp) here, choose one below, or open a templated patient from the list. The X-ray on screen stays until another one opens.' : $('openMsg').textContent);
  PJ.showList($('cases'));
}
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
// project (.aotp, projects.js). Images over 40 MB or 60 megapixels are refused before they are decoded (round 6: phones).
async function openFile(f) {
  try {
    const proj = /\.aotp$/i.test(f.name);
    if (f.size > (proj ? PJ.FILE_MAX : PJ.IMAGE_MAX)) throw new Error(proj ? `This project file is ${(f.size / 1048576).toFixed(0)} MB; a project is at most ${PJ.FILE_MAX / 1048576} MB.` : `This file is ${(f.size / 1048576).toFixed(0)} MB; the page opens X-rays up to ${PJ.IMAGE_MAX / 1048576} MB. Save a smaller copy (a JPEG of the film, or the DICOM without extra frames) and open that.`);
    openMsg('Opening ' + f.name + '…', '');
    const buf = await f.arrayBuffer(), u = new Uint8Array(buf);
    if (proj || PJ.isZip(u)) return await PJ.openAotp(u, f.name);
    // t.addisortho.com (round 13): a film whose case this tab (or this browser, when plans are kept here) already made is
    // reopened from the patient list instead of becoming a second case
    let sha = '';
    if (STANDALONE && u.length <= PJ.IMAGE_MAX) {
      try { sha = await PJ.sha256hex(u); } catch (e) { sha = ''; }
      const known = sha && PJ.knownCase(sha);
      if (known && await PJ.openKnown(known, u, sha)) return;
    }
    const { img, mime } = await decodeBytes(buf, f.name);
    const o = await origOf(u, mime, f.name, sha);
    setImage(img, { file: f.name, hash: o.hash || '' }, { ...o, how: 'file' });
  } catch (e) { failed(e); }
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
async function loadProject(project, bytes, { caseRef = null, how = 'file' } = {}) {
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
  S.orig = opt.orig || null; S.origWhy = opt.origWhy || ''; S.caseRef = opt.caseRef || null;
  S.patientIn = opt.patient ? { name: opt.patient.name || '', recordNo: opt.patient.recordNo || '', asked: true } : null;
  PJ.forget();
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
  S.wf = /KNEE/.test(bp) ? (/^(LL|RL|LAT|ML|LM|LATERAL)/.test(vp) ? 'kneeLat' : 'kneeAP') : /LEG|LOWER|EXTREM|FEMUR|TIBIA/.test(bp) ? 'kneeAP' : 'hip';
  if (!S.wfs.includes(S.wf)) S.wf = S.wfs[0];   // a film this account has no implants for: its first workflow
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
  S.oldCupRule = false; S.cal = null; S.lm = {}; S.tplAll = { hip: {}, kneeAP: {}, kneeLat: {} }; S.tpl = S.tplAll[S.wf]; S.hidden = {}; S.hiddenGeo = {}; S.rulers = []; S.active = null; S.arm = null; S.restoreNote = '';
  S.auto = { busy: false, balls: [], ballIdx: 0, markers: [] }; S.canal = {}; S.notes = {}; S.mirror = null; S.sent = null; S.sendError = null; S.masks = [];
  S.clean = { strokes: [], crop: null }; S.cleanData = null; S.showOriginal = false; S.lmSeq = []; S.ref = null; S.filling = false; S.prep = { rot: 0 }; S.prepDone = false;
  S.segHand = {}; S.segStep = null; S.choice = {}; S.keptLibraryId = null;
  defaultCalibration();
  if (opt.state) applyState(opt.state); else restore();
  $('wf').value = S.wf; $('side').value = S.side;
  ensureSlots();
  $('open').style.display = 'none'; document.body.classList.add('has-img'); document.body.classList.remove('choosing');
  $('study').textContent = STANDALONE && S.set.share ? `${m.format || 'file'}, ${img.cols} × ${img.rows}` : [m.StudyDescription || m.SeriesDescription || src.file || 'X-ray', fmtDate(m.StudyDate),
    m.AccessionNumber ? 'order ' + m.AccessionNumber : '', m.PatientID ? 'MRN ' + m.PatientID : '',
    src.file ? `${m.format || 'file'}, ${img.cols} × ${img.rows}` : ''].filter(Boolean).join(' · ');
  HIST.length = 0; save(); markSaved();
  PJ.filmOpened(opt.how || (opt.state ? 'aotp' : 'file'));   // t.addisortho.com: the image sent and the case made in the background (round 13)
  renderImage(); panel(); fit(); draw();   // the panel first: on a phone its height sets the film's
  if (opt.state) { prompt([opt.note, S.restoreNote].filter(Boolean).join(' ')); return; }   // a project: as saved, nothing looked for again
  if (S.set.autoFind) setTimeout(runAutoFind, 40);
  else if (S.cal && S.cal.method !== 'dicom') nextStep();
}
const fmtDate = d => (d && /^\d{8}$/.test(d)) ? `${d.slice(6)}/${d.slice(4, 6)}/${d.slice(0, 4)}` : '';

// Detector spacing / magnification: a starting estimate only. It is not true size at the hip or knee.
// detector: true when the number is the detector's own pitch with no magnification taken off.
function dicomEstimate() {
  const m = S.img.meta || {}, ct = String(m.PixelSpacingCalibrationType || '').toUpperCase();
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
  const e = dicomEstimate();
  S.cal = e ? { method: 'dicom', mmPerPx: e.mmPerPx, note: e.note } : null;
}

// ---------- automatic calibration and R/L marker ----------
// On opening: the whole film is searched for the ball (same quality gate as a click) and for an R or L marker. Both
// are proposals: the scale is used but marked unconfirmed, and the guided steps stop until the surgeon confirms the
// pink circle is the ball (another round, dense object can pass every shape test; Astra, 11 Oct) and confirms which
// way the film faces.
function runAutoFind() {
  const img = S.img; if (!img) return;
  const est = dicomEstimate(), mmpp = est ? est.mmPerPx : null, needBall = !S.cal || S.cal.method === 'dicom';
  const unconfirmed = S.cal && S.cal.method === 'ball' && S.cal.confirmed === false;   // a restored proposal: find the others too
  S.auto = { busy: true, balls: [], ballIdx: 0, markers: [] }; panel();
  setTimeout(() => {
    if (S.img !== img) return;
    try { S.auto.markers = findMarkers(img, { mmPerPx: mmpp }); } catch (e) { S.auto.markers = []; }
    if (S.orient.from === 'assumed') applyMarker();
    if (needBall) {
      try { S.auto.balls = findBalls(img, { mmPerPx: mmpp, ballMm: S.ballD }); } catch (e) { S.auto.balls = []; }
      if (S.auto.balls.length) setBall(S.auto.balls[0], 'found automatically', true);
    } else if (unconfirmed) { try { S.auto.balls = findBalls(img, { mmPerPx: mmpp, ballMm: S.ballD }); } catch (e) { S.auto.balls = []; } }
    S.auto.busy = false; save();
    HIST.splice(0, HIST.length - 1); markSaved(); syncHeader();   // the history starts here: Undo never takes back what the page found by itself
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
const HIST = [], HIST_MAX = 60; let histLock = false;
function snapshot() {
  return { j: JSON.stringify({ wf: S.wf, side: S.side, mirrored: S.mirrored, cranial: S.cranial, orient: S.orient, cal: S.cal, ballD: S.ballD, vca: S.vca,
    lm: S.lm, tplAll: { ...S.tplAll, [S.wf]: S.tpl }, rulers: S.rulers, clean: { ...S.clean, strokes: undefined }, masks: S.masks, lmSeq: S.lmSeq, ref: S.ref, sideSet: !!S.sideSet, scope: S.scope, prep: S.prep, prepDone: S.prepDone }),
    strokes: S.clean.strokes };
}
const sameStrokes = (a, b) => a === b || (!!a && !!b && a.length === b.length && a.every((s, i) => s === b[i]));
const sameSnap = (a, b) => !!a && !!b && a.j === b.j && sameStrokes(a.strokes, b.strokes);
function undo() {
  if (HIST.length < 2) return;
  HIST.pop(); const top = HIST[HIST.length - 1], p = JSON.parse(top.j);
  const strokes0 = S.clean.strokes, view0 = JSON.stringify([S.clean.crop, S.prep]);
  Object.assign(S, { wf: p.wf, side: p.side, mirrored: p.mirrored, cranial: p.cranial, orient: p.orient, cal: p.cal, ballD: p.ballD, vca: p.vca, lm: p.lm, rulers: p.rulers, tplAll: p.tplAll,
    clean: { crop: null, ...p.clean, strokes: top.strokes }, masks: p.masks, lmSeq: p.lmSeq, ref: p.ref, sideSet: p.sideSet, scope: p.scope, prep: p.prep || { rot: 0 }, prepDone: !!p.prepDone });
  if (!sameStrokes(S.clean.strokes, strokes0)) rebuildClean();
  if (JSON.stringify([S.clean.crop, S.prep]) !== view0) fit();   // a crop or turn undone: show the film as it now is
  S.tpl = S.tplAll[S.wf] || (S.tplAll[S.wf] = {}); if (S.active && !S.tpl[S.active]) S.active = null; S.arm = null; prompt('');
  $('wf').value = S.wf; $('side').value = S.side; histLock = true; save(); histLock = false; panel(); draw();
}
// now: write the plan to this browser at once (a send to PACS must survive a reload straight after it)
function save(now = false) {
  if (!S.img) return;
  if (!histLock) { const sn = snapshot(); if (!sameSnap(HIST[HIST.length - 1], sn)) { HIST.push(sn); if (HIST.length > HIST_MAX) HIST.shift(); } }
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
function syncHeader() { $('undoBtn').disabled = !S.img || HIST.length < 2; $('fitBtn').disabled = !S.img; PJ.sync(); }
// changes not yet in a saved project: the history has moved on from where the film opened or was last saved
let histBase = null;
// rev: the history entry a save was made of (projects.js takes it when the save starts; Astra's page re-review, finding 5):
// changes made while the save ran stay unsaved
// What is "saved" is the whole project state (Astra's third page review, 13 Oct: the history snapshot leaves out the plan's
// rules, the window (contrast), display choices, notes and markers): a fingerprint of fullState() with the patient details
// typed, without the save time. A change to any of them, made at any time (also while a save runs), counts as unsaved.
function stateKey() { if (!S.img) return null; const st = fullState(); delete st.saved; return JSON.stringify([st, S.patientIn || null]); }
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
function applyPlan(p) {
  p = migrate(p);
  const wfOk = !p.wf || S.wfs.includes(p.wf);   // a plan for a workflow this account has no implants for: landmarks only
  if (!wfOk) S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `This plan is for ${WF[p.wf] ? WF[p.wf].label : p.wf}, which needs an implant range not open to your account.`;
  Object.assign(S, { wf: (wfOk && p.wf) || S.wf, side: p.side || S.side, cal: p.cal || S.cal, ballD: p.ballD || 25, lm: p.lm || {}, rulers: p.rulers || [], vca: p.vca ?? 6 });
  if (p.side && (p.sideSet || Object.keys(S.lm).length)) S.sideSet = true;
  // a ball found automatically is confirmed only if the plan says so (plans saved before 11 Oct did not; Astra, 11 Oct)
  if (S.cal && S.cal.method === 'ball' && S.cal.how === 'found automatically' && S.cal.confirmed !== true) S.cal = { ...S.cal, confirmed: false };
  S.tplAll = p.tplAll || { hip: {}, kneeAP: {}, kneeLat: {}, [S.wf]: p.tpl || {} };
  if (typeof p.mirrored === 'boolean') { S.mirrored = p.mirrored; S.orient = p.orient || { from: 'user', note: '' }; }
  if (p.cranial) S.cranial = p.cranial;
  // a pending-only account has no family at all (contract 9): every implant of the plan is kept aside below, as it is,
  // and the plan's own library id is written back with it (libIdNow), so the plan stays whole for whoever has the range
  S.keptLibraryId = typeof p.libraryId === 'string' && p.libraryId ? p.libraryId : null;
  if (S.lib.families.length && p.libraryId !== S.lib.libraryId) {   // outlines changed since the plan was made: keep calibration and landmarks only
    const had = Object.values(S.tplAll).some(o => Object.keys(o || {}).length);
    S.tplAll = { hip: {}, kneeAP: {}, kneeLat: {} };
    if (had) S.restoreNote = 'The implant library has changed since this plan was saved: calibration and landmarks are kept, implants need placing again.';
  }
  // implants of a range this account does not have are not shown, and a vendor implant whose outline changed since
  // (its geomId; the libraryId above covers the Addis Ortho range) is dropped like any changed implant. The ones not
  // shown are kept aside as they were (S.hidden, per workflow, with their geomId): not drawn, measured or recommended,
  // and plan() writes them back, so opening a film never saves a shorter plan (Fable, 12 Oct 2026)
  // an implant that does not fit the library it claims (Astra's page re-review, 13 Oct, finding 4): a family of another
  // kind than its place (a stem in a cup's place), a size, head or neck the family does not have. A project is refused
  // before it is opened (projects.js libraryProblems); a plan kept in this browser or opened as plan data leaves such an
  // implant out, with a note, instead of drawing it wrong (an unknown neck would otherwise count as no neck length)
  let gone = 0, changed = 0, wrong = 0; S.hidden = {}; S.hiddenGeo = {};
  for (const [wf, o] of Object.entries(S.tplAll)) for (const [k, t] of Object.entries(o || {})) {
    const f = t && S.fam[t.fam];
    if (SLOT[k] && t && t.fam && !f) {
      gone++; (S.hidden[wf] || (S.hidden[wf] = {}))[k] = t;
      if ((p.geo || {})[t.fam]) S.hiddenGeo[t.fam] = p.geo[t.fam];
      delete o[k]; continue;
    }
    if (f && f.geomId && (p.geo || {})[t.fam] && p.geo[t.fam] !== f.geomId) { changed++; delete o[k]; continue; }
    if (!SLOT[k] || !f) { delete o[k]; continue; }
    if (f.kind !== SLOT[k].kind || !(WF[wf] && WF[wf].slots.includes(k)) || !sizeOf(t)) { wrong++; delete o[k]; continue; }
    if (f.kind === 'stem' && !headKindsOf(f).includes(t.headKind)) setHead(t, f.sheetHead.kind, f.sheetHead.diameter);
    if (f.kind === 'stem' && !Object.prototype.hasOwnProperty.call(headSet(t), t.neck)) { wrong++; delete o[k]; continue; }
  }
  if (gone) S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `This plan also had ${gone} implant${gone > 1 ? 's' : ''} from a range not open to your account: not shown.`;
  if (changed) S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `${changed} implant${changed > 1 ? 's have' : ' has'} a new outline since this plan was saved: place ${changed > 1 ? 'them' : 'it'} again.`;
  if (wrong) S.restoreNote = (S.restoreNote ? S.restoreNote + ' ' : '') + `${wrong} implant${wrong > 1 ? 's do' : ' does'} not match the implant library (its place, size, head or neck) and ${wrong > 1 ? 'were' : 'was'} left out: place ${wrong > 1 ? 'them' : 'it'} again.`;
  S.tpl = S.tplAll[S.wf] || (S.tplAll[S.wf] = {});
  if (p.wl) S.wl = p.wl;
  if (p.scope) S.scope = p.scope;
  S.masks = p.masks || [];
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
  return { v: 1, saved: new Date().toISOString(), wf: S.wf, side: S.side, sideSet: !!S.sideSet, scope: S.scope, mirrored: S.mirrored, orient: S.orient, cranial: S.cranial, cal: S.cal, ballD: S.ballD, vca: S.vca,
    lm: S.lm, lmSeq: S.lmSeq, ref: S.ref, prep: S.prep, prepDone: S.prepDone, tplAll: withHidden(S.tplAll), rulers: S.rulers, masks: S.masks, clean: S.clean, sent: S.sent, pendingSend: S.pending ? { key: S.pending.key, ident: S.pending.ident, sop: S.pending.sop, series: S.pending.series } : null, wl: S.wl, library: S.lib && S.lib.version, libraryId: libIdNow(), geo: { ...S.hiddenGeo, ...planGeo() }, settings: Object.fromEntries(PLAN_KEYS.map(k => [k, S.set[k]])) };
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
function planGeo() {
  const g = {};
  for (const o of Object.values(S.tplAll)) for (const t of Object.values(o || {})) { const f = S.fam[t.fam]; if (f && f.geomId) g[t.fam] = f.geomId; }
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
const mm = () => S.cal && S.cal.mmPerPx;
const pxOf = vmm => vmm / mm();

// ---------- landmarks ----------
const lmDefs = () => WF[S.wf].lm;
const lmDef = key => [...hipLm('R', 'both', 'own'), ...WF.kneeAP.lm, ...WF.kneeLat.lm].find(d => d.key === key);
// what the surgery side is planned from: its own anatomy, or the other hip mirrored (both hips on the film only)
const refMode = () => (S.wf === 'hip' && S.scope === 'both' ? S.ref : 'own');
const refOther = () => refMode() === 'other';
function lmGeom(key) {   // -> {p} | {a, b} | {c, r} or null
  const L = S.lm[key]; if (!L) return null;
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
  if (!mm()) return '';
  if (/^canal[RL]$/.test(key)) {
    const H = hipFrame(), v = H ? H.v : cranVec(), a = G.angleDeg(G.sub(g.b, g.a), v), off = Math.min(a, 180 - a);
    if (off > 30) return `runs ${Math.round(off)}° across the femur instead of down it: click the middle of the canal just below the lesser trochanter, then 10-15 cm lower`;
    if (G.dist(g.a, g.b) * mm() < 30) return 'too short: mark at least 3 cm down the canal';
  }
  if (/^head[RL]$/.test(key) || key === 'hip') { const d = 2 * g.r * mm(); if (d < 30 || d > 75) return `a head of ${f1(d)} mm is not plausible: click on the rim of the head`; }
  // knee landmarks the wrong way round (Fable, 11 Oct): plateau front behind its back edge; femoral head below the knee
  // or ankle above it
  if (key === 'platAnt' && S.wf === 'kneeLat') { const L = latKnee(false), ant = antDir(L), pp = lmGeom('platPost'); if (ant && pp && L.ac && L.pc && G.dot(G.sub(g.p, pp.p), ant) < 0) return 'is behind the back edge (the front of the knee is towards the anterior cortex): swap the two plateau edges'; }
  if ((key === 'hip' || key === 'ankle') && S.wf === 'kneeAP') { const kn = lmGeom('knee'); if (kn) { const d = G.dot(G.sub(key === 'hip' ? g.c : g.p, kn.p), cranVec()); if (key === 'hip' ? d <= 0 : d >= 0) return key === 'hip' ? 'is below the knee: is it the femoral head?' : 'is above the knee: is it the ankle?'; } }
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
function famsFor(kind) { return S.lib.families.filter(f => f.kind === kind); }
const hasKind = kind => famsFor(kind).length > 0;   // is a range of this kind open to this account
// Ranges this account holds whose template file is not on the server, or failed the server's check (13 Oct 2026, design
// D with amendment 6): t.addisortho.com's library.json lists them as "pending" from its trusted range catalogue, filtered
// by the signed session's ranges. Shown greyed in the family lists and on the choice card; never selectable.
let PENDING = [];
function pendingList(lib) {
  const s = (v, n) => String(v || '').replace(/[\u0000-\u001f\u007f]+/g, ' ').trim().slice(0, n);
  return (Array.isArray(lib.pending) ? lib.pending : []).filter(p => p && typeof p.key === 'string' && typeof p.label === 'string' && !(lib.ranges || {})[p.key])
    .slice(0, 24).map(p => ({ key: s(p.key, 30), label: s(p.label, 80), maker: s(p.maker, 80), kinds: Array.isArray(p.kinds) ? p.kinds.filter(k => typeof k === 'string') : [], why: p.why === 'failed' ? 'failed' : 'nofile' }));
}
const pendingOf = kind => PENDING.filter(p => p.kinds.includes(kind));
// the two wordings (Astra: a file that failed its check is not "awaiting the maker's files")
const pendingText = p => `${p.label}: ${p.why === 'failed' ? 'its template file failed its check (tell the administrator)' : 'no template file yet'}`;
function pendingNote() {
  const box = $('pendNote'); if (!box) return;
  box.textContent = ''; box.className = '';
  if (!PENDING.length) return;
  box.className = S.noRange ? 'warnbox' : 'note';
  box.appendChild(el(`<div>${S.noRange ? '<b>No implant range with template files is open to your account yet.</b> ' : ''}${S.noRange ? 'Your ranges:' : 'Not yet available to you:'}</div>`));
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
function pivotOf(t, g) {
  const f = S.fam[t.fam];
  if (f.kind === 'cup') return [0, 0];
  if (f.kind === 'stem') return g.headM;   // the head centre: sizing up or changing family keeps the head where it was
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
  if (kind === 'cup' || kind === 'stem') return lateralSign(side) === 1;
  return viewOf() === 'AP' ? S.mirrored : false;
}
function refreshFlips() {   // a mirrored template also mirrors its angle; a stem that was on the canal is put back on it
  const hadStem = S.wf === 'hip' && !!S.tpl['stem' + S.side]; ensureSlots();   // Fable, 11 Oct: a side change in "template the other side" mode
  if (hadStem && !S.tpl['stem' + S.side]) prompt(`The ${SIDE[S.side]} stem was removed: the ${SIDE[S.side]} femur is not templated in this mode (the stem is the ${SIDE[other(S.side)]} one). Press Auto template to plan the ${SIDE[other(S.side)]} side.`);
  for (const [slot, t] of Object.entries(S.tpl)) if (!t.userFlip) {
    const side = slotSide(slot), kind = S.fam[t.fam].kind, f = defaultFlip(kind, side); if (f === t.flip) continue;
    const isStem = kind === 'stem', was = isStem && stemAligned(t, side), H0 = isStem ? headAt(t) : null;
    const Hc = kind === 'cup' ? hipFrame(side) : null, inc0 = Hc ? (() => { const a = G.angleDeg(dirOf(t, [1, 0]), Hc.u); return a > 90 ? 180 - a : a; })() : null;
    t.flip = f; t.angle = normA(-t.angle + 2 * filmTurn());
    if (Hc) { const lat0 = dirOf({ ...t, angle: 0 }, [-1, 0]); t.angle = normA((t.flip ? -1 : 1) * G.rad(inc0) + G.turn(lat0, Hc.u)); }   // same inclination to the teardrop line
    if (was) { alignStem(t, side); setHeadLevel(t, side, G.dot(H0, vertical())); }
  }
}
// each workflow keeps its own implants: AP and lateral outlines have different shapes and pivots
function switchWf(wf) {
  S.tplAll[S.wf] = S.tpl; S.wf = wf; S.tpl = S.tplAll[wf] || (S.tplAll[wf] = {}); S.active = null; ensureSlots();
}
function ensureSlots() {
  for (const k of Object.keys(S.tpl)) if (!WF[S.wf].slots.includes(k)) delete S.tpl[k];
  if (S.wf === 'hip' && refOther()) delete S.tpl['stem' + S.side];   // a fractured femur carries no stem, also in older plans
  if (S.active && !S.tpl[S.active]) S.active = null;
}
// a new implant for this slot, or null when no range of its kind is open to this account
function newTemplate(slot) {
  const kind = SLOT[slot].kind, side = slotSide(slot);
  const twin0 = SLOT[slot].side ? S.tpl[SLOT[slot].kind + other(side)] : null;   // the other side's choice is the start
  const picked = chosenFam(kind);   // the family picked on the choice card comes first (design C)
  const fam = picked || (twin0 ? S.fam[twin0.fam] : famsFor(kind)[0]); if (!fam) return null;
  const twin = twin0 && twin0.fam === fam.id ? twin0 : null, shown = sizesShown(fam);
  const t = { fam: fam.id, size: twin ? twin.size : shown[Math.floor(shown.length / 2)].size, thick: 10, x: 0, y: 0, angle: 0, flip: defaultFlip(kind, side), on: true };
  if (picked) t.userFam = true;
  if (kind === 'stem') { if (twin) Object.assign(t, { headKind: twin.headKind, head: twin.head, neck: twin.neck }); else setHead(t, fam.sheetHead.kind, fam.sheetHead.diameter); }
  const vc = toI(G.P(cv.width / dpr / 2, cv.height / dpr / 2)); t.x = vc.x; t.y = vc.y;
  return t;
}
function addTemplate(slot) {
  const nr = notReady(); if (nr) return prompt(nr);
  if (refOther() && slot === 'stem' + S.side) return prompt(`The ${SIDE[S.side]} femur is not templated: plan the stem on the ${SIDE[other(S.side)]} side.`);
  const t = newTemplate(slot); if (!t) return prompt(`No ${SLOT[slot].label.toLowerCase()} range is open to your account.`);
  S.tpl[slot] = t; S.active = slot;
  autoPlace(slot);
  const side = slotSide(slot);
  if (SLOT[slot].side) { matchHeadToCup(side); if (SLOT[slot].kind === 'stem') autoPlace(slot); }
  save(); panel(); draw();
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
const opSlots = () => (S.wf === 'hip' ? (refOther() ? ['cup' + S.side, 'cup' + other(S.side), 'stem' + other(S.side)] : ['cup' + S.side, 'stem' + S.side]) : WF[S.wf].slots).filter(sl => hasKind(SLOT[sl].kind));
function autoPlace(slot, opt = {}) {
  const t = S.tpl[slot], f = S.fam[t.fam];
  if (f.kind === 'cup') return autoCup(slot, opt);
  if (f.kind === 'stem') return autoStem(slot, opt);
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
  const t = S.tpl[slot], side = slotSide(slot), f = S.fam[t.fam], H = hipFrame(side), h = lmGeom('head' + side), ac = lmGeom('ac' + side), k = mm();
  const normal = side !== S.side, fromOther = refOther() && !normal, hn = lmGeom('head' + other(side)), twin = fromOther ? otherPlanCup(side) : null;
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
    else if (H && ac) { const reach = G.dot(G.sub(ac.p, H.T), H.u) * k - S.set.tdLatMm; if (reach > 20) { want = reach / Math.cos(incl); how = 'cover to the acetabular roof edge'; roofSized = true; } }
    if (!want && h && !twin) { want = 2 * h.r * k + 4; how = 'head diameter + 4 mm'; }
    delete S.notes['cupFail' + side];
    if (twinFail) S.notes['cupFail' + side] = twinFail;
    // a wanted size beyond the range is said, never quietly replaced by the largest (amendment 1)
    if (want && !twin) { const c = cupSizeFor(f.sizes, want); t.size = c.size; t.userSize = false; if (c.fail) S.notes['cupFail' + side] = { dir: c.fail, want: Math.round(want * 10) / 10, limit: c.limit, size: c.size, fam: f.id }; }
    S.notes['cupSize' + side] = how;
  } else delete S.notes['cupFail' + side];
  const lat0 = dirOf({ ...t, angle: 0 }, [-1, 0]);                                    // the template's lateral at 0 deg
  const tilt = G.turn(lat0, H ? H.u : latVec(side));                                  // pelvis tilt and film rotation
  t.angle = normA((t.flip ? -1 : 1) * incl + tilt);
  const sz = sizeOf(t), R = (sz.catalogueOuter || sz.outerDiameter) / 2 / k, mirrorC = mirrorTarget(side);
  let rule = ruleWanted || cupRuleFor(side), C = null;
  if (rule === 'mirror' && !mirrorC && !normal) S.notes['mirrorMissing' + side] = true;
  else delete S.notes['mirrorMissing' + side];
  delete S.notes['cupAnat' + side];
  if (rule === 'mirror' && mirrorC) C = mirrorC;
  else if (rule === 'anatomic' && H && roofSized && ac && h && !fromOther) {
    // the roof-edge size solved with the centre (Astra, finding 3): stepped down until the lateral rim stays within the roof edge
    const head = false, N = h.c, start = f.sizes.findIndex(z => z.size === t.size);
    const fit = anatomicRoofFit(f.sizes, start, z => (z.catalogueOuter || z.outerDiameter) / 2 / k, N, H.T, H.u, pxOf(S.set.tdLatMm), head ? 0 : pxOf(ANAT_MAX_MED), G.deg(incl), G.dot(G.sub(ac.p, H.T), H.u), pxOf(0.5));
    if (fit.stepped) { t.size = f.sizes[fit.idx].size; S.notes['cupSize' + side] = `${S.notes['cupSize' + side]}, with the cup ${head ? 'on the native head centre' : 'at the native centre height'} (${fit.stepped} size${fit.stepped > 1 ? 's' : ''} down from the teardrop-placement size, so the rim stays within the roof edge)`; }
    if (fit.overPx > 0) S.notes['cupRoof' + side] = { over: Math.round(fit.overPx * k * 10) / 10, size: t.size };
    C = fit.C; if (!head) S.notes['cupAnat' + side] = { med: Math.round(fit.a.medPx * k * 10) / 10, capped: fit.a.capped, kept: fit.a.kept, mirrored: false };
  }
  else if (rule === 'anatomic' && H) {
    const N = fromOther ? (hn && !H.single ? reflect(hn.c, H) : null) : h ? h.c : null;
    if (N) { const a = anatomicCentre(N, H.T, H.u, pxOf(S.set.tdLatMm), R, pxOf(ANAT_MAX_MED)); C = a.C; S.notes['cupAnat' + side] = { med: Math.round(a.medPx * k * 10) / 10, capped: a.capped, kept: a.kept, mirrored: fromOther }; }
    else { C = teardropCentre(H.T, H.u, H.v, pxOf(S.set.tdLatMm), G.deg(incl), R); rule = 'teardrop'; S.notes['cupAnat' + side] = { noHead: true }; }
  }
  else if (rule === 'teardrop' && H) C = teardropCentre(H.T, H.u, H.v, pxOf(S.set.tdLatMm), G.deg(incl), R);
  else if (h && !fromOther) { C = h.c; rule = 'head'; }
  else if (H) { C = teardropCentre(H.T, H.u, H.v, pxOf(S.set.tdLatMm), G.deg(incl), R); rule = 'teardrop'; }
  if (C) { t.x = C.x; t.y = C.y; }
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
  const n = other(side), hn = lmGeom('head' + n), cn = S.tpl['cup' + n], cnl = canalOf(n), sn = S.tpl['stem' + n];
  const planCup = cn && cn.on && mm() ? (() => { const s = sizeOf(cn); return { p: xf(cn)([0, 0]), r: pxOf((s.catalogueOuter || s.outerDiameter) / 2) }; })() : null;
  if ((S.set.mirrorRef === 'plan' || !hn || (refOther() && side === S.side)) && planCup) return { ...planCup, kind: 'plan', what: `${SIDE[n]} plan`, offset: sn && sn.on && cnl ? G.lineDist(headAt(sn), cnl.a, cnl.b) * mm() : null };
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
  const place = tt => (target ? bestNeck(tt, side, target) : placeStem(tt, side));
  const start = stemStart(side);
  if (!start && refOther() && side === S.side) { S.notes['fit' + side] = { none: true, why: `no reference: plan the ${SIDE[other(side)]} side (or mark its head) first` }; return null; }   // Astra, 11 Oct
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
  return G.dot(G.sub(xf(cup)([0, 0]), H.T), H.u) * k + G.lineDist(headAt(tt), c.a, c.b) * k - nat.mm;
}
// the neck (same stem) whose femoral offset comes nearest `target`; places the stem with it
// where a stem starts before it is turned onto the canal: the native head, or (a deformed hip) the planned cup centre or
// the other hip mirrored
function stemStart(side) {
  const h = lmGeom('head' + side), cup = S.tpl['cup' + side];
  if (h && !(refOther() && side === S.side)) return h.c;
  if (cup && cup.on && mm()) return xf(cup)([0, 0]);
  return mirrorTarget(side) || (h && !(refOther() && side === S.side) ? h.c : null);
}
function bestNeck(t, side, target) {
  const c = canalOf(side); if (!c || !(target > 0)) { placeStem(t, side); return null; }
  let best = null;
  for (const [neck] of neckOpts(t)) {
    const tt = { ...t, neck }; placeStem(tt, side);
    const off = G.lineDist(headAt(tt), c.a, c.b) * mm(), err = Math.abs(off - target);
    if (!best || err < best.err) best = { err, neck, off };
  }
  t.neck = best.neck; placeStem(t, side);
  return best;
}
// the offset the operated stem aims for (Auto plan settings): the other hip's native offset when its head and canal are
// marked, else this hip's own; or none (neck 0)
function offsetTarget(side) {
  const rule = S.set.offsetTarget || 'auto', nat = sd => { const h = lmGeom('head' + sd), c = canalOf(sd); return h && c && mm() ? G.lineDist(h.c, c.a, c.b) * mm() : null; };
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
  const cup = S.tpl['cup' + side], h = refOther() && side === S.side ? null : lmGeom('head' + side), target = cup && cup.on ? xf(cup)([0, 0]) : h ? h.c : null;
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
  const cur = G.dot(headAt(t), v), den = G.dot(up, v), d = Math.abs(den) > 0.3 ? (level - cur) / den : level - cur;
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
function canalTraceFor(side) {
  const c0 = lmGeom('canal' + side); if (!c0 || !mm() || lmProblem('canal' + side)) return null;
  const c = canalHL(c0);
  const key = JSON.stringify([c.a, c.b, mm()]);
  if (!S.canal[side] || S.canal[side].key !== key) { let tr = null; try { tr = traceCanal(S.img, c.a, c.b, mm()); } catch (e) { tr = null; } S.canal[side] = { key, tr }; }
  return S.canal[side].tr;
}
// After the canal's second click: centre the line between the inner cortical edges, if they were found clearly.
function snapCanal(side) {
  const c0 = lmGeom('canal' + side); S.notes['canal' + side] = '';
  if (!c0 || !S.set.snapCanal || lmProblem('canal' + side)) return;
  const c = canalHL(c0);
  let tr = null; try { tr = traceCanal(S.img, c.a, c.b, mm()); } catch (e) { tr = null; }
  if (tr && tr.axis) {
    const moved = Math.max(G.dist(tr.axis.a, c.a), G.dist(tr.axis.b, c.b)) * mm();
    S.lm['canal' + side] = [tr.axis.a, tr.axis.b];
    S.notes['canal' + side] = `Centred on the film: inner cortical edges found at ${tr.n} levels over ${Math.round(tr.spanMm)} mm (line fit ${f1(tr.rmsMm)} mm; moved ${f1(moved)} mm). Drag the points to override.`;
  } else S.notes['canal' + side] = 'The inner edges of the cortex were not clear here: the line is kept as clicked.';
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

// ---------- head centre on the cup centre (13 Oct 2026; DESIGN-2026-10-13-autotemplate.md B, Astra's amendments 2-4) ----------
// The versions of one stem the search may choose among, open to this account: the Addis Ortho BE2 133S/133H/127S/127H,
// and for any other maker the family and the families it names as its offset twins (offsetPair), both ways. Each version
// is placed with its own outline, neck-shaft angle and offset (the library's sizes), never by a formula.
function stemVersions(fid) {
  const f = S.fam[fid]; if (!f || f.kind !== 'stem') return [];
  const stems = famsFor('stem'), out = new Set([fid]), be2 = /^chunli-be2-/;
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
  const h = lmGeom('head' + sd), c = canalOf(sd), H = hipFrame(sd), k = mm();
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
  const C = xf(cup)([0, 0]);
  return G.dot(G.sub(C, H.T), H.u) * k + G.lineDist(headAt(stem), c.a, c.b) * k;
}
// Every version, size and neck of the stem in this slot placed on the canal at the planned height, with its canal fit
// and how far its head centre lies from the cup centre (pelvic axes). keepFam: only the slot's family (chosen by hand);
// keepSize: only its size too (set by hand). The slot itself is not changed.
function stemCandidates(sd, { keepFam = false, keepSize = false } = {}) {
  const t = S.tpl['stem' + sd], cup = S.tpl['cup' + sd], c = canalOf(sd), k = mm();
  if (!t || !cup || !cup.on || !c || !k) return null;
  const L = levelPlan(sd); if (!L) return null;
  const H = hipFrame(sd), u = H ? H.u : latVec(sd), v = vertical(), C = xf(cup)([0, 0]), tr = canalTraceFor(sd), nat = nativeCombined(sd);
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
  const st = stemStart(sd); if (st && !stemAligned(t, sd)) { t.x = st.x; t.y = st.y; t.angle = filmTurn(); }
  const P = stemCandidates(sd, { keepFam, keepSize }); if (!P) return null;
  let pick = chooseStem(P.cands, { handSize: keepSize, handFam: keepFam });
  let b = pick.best;
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
  const hn = lmGeom('head' + sd), cn = canalOf(sd);
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
  if (S.wf !== 'hip' || (kind !== 'cup' && kind !== 'stem')) return;
  matchHeadToCup(sd);
  const stem = S.tpl['stem' + sd];
  if (stem && !(refOther() && sd === o) && mm() && canalOf(sd)) planStem(sd, { keepSize: !!stem.userSize, keepFam: !!stem.userFam }, stemTarget(sd));
  else if (kind === 'stem') { delete S.notes['stemPlan' + sd]; S.notes['offset' + sd] = ''; S.notes['fit' + sd] = null; }   // no canal to plan on: no explanation of an earlier plan left behind
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
  const cup = tplFor('cup' + o);   // none when no cup range is open to this account
  if (cup) {   // a size or family set by hand is kept (amendment 4; Astra's page re-review, finding 3)
    autoCup('cup' + o, { keepSize: true, rule: 'mirror' });
    info.push(`cup ${cup.size}${cup.userSize ? ' (your size, kept)' : cup.userFam ? ` (${S.fam[cup.fam].label}, your choice, kept)` : ''} on the ${ref.what} centre, mirrored`);
    if (cupN && cupN.on) { const f = S.fam[cup.fam], i0 = f.sizes.findIndex(z => z.size === cup.size), i1 = f.sizes.findIndex(z => z.size === cupN.size); if (Math.abs(i0 - i1) > 1) info.push(`the ${SIDE[n]} cup is ${cupN.size}: more than one size different`); }
  }
  if (refOther()) {   // Sami, 11 Oct: a fractured, shortened or rotated femur is not templated; the stem is the normal side's
    delete S.tpl['stem' + o];
    const cutN = stemN && stemN.on ? neckCutAboveLT(stemN, n) : null;
    S.notes['fit' + o] = { copied: true, cut: cutN }; S.notes['offset' + o] = '';
    info.push(stemN && stemN.on ? `stem as on the ${SIDE[n]}: ${S.fam[stemN.fam].label} ${stemN.size}, ${stemN.head} mm head, neck ${neckLabel(stemN, stemN.neck)}; ${cutN !== null ? `neck cut ${f1(cutN)} mm above the lesser trochanter` : `mark the ${SIDE[n]} lesser trochanter to measure the neck cut`}` : `plan the ${SIDE[n]} stem: it is the stem for the ${SIDE[o]}`);
    S.mirror = { note: info.join('; '), at: new Date().toISOString() };
    S.active = 'cup' + o; if (quiet) return true; save(); panel(); draw(); return true;
  }
  // stem: the other side's family if it has a plan, sized by fill on the operated canal, then the neck for the offset
  const t = tplFor('stem' + o);
  if (!t) { S.mirror = { note: info.join('; '), at: new Date().toISOString() }; S.active = cup ? 'cup' + o : null; if (quiet) return true; save(); panel(); draw(); return true; }
  if (stemN && stemN.fam !== t.fam && !t.userFam && !t.userSize) {   // the other side's family (unless this stem's family or size was set by hand), at the size nearest in canal fill
    const w = widthAt(sizeOf(t), 60), shown = sizesShown(S.fam[stemN.fam]);
    const pick = Number.isFinite(w) ? shown.reduce((b, z) => Math.abs(widthAt(z, 60) - w) < Math.abs(widthAt(b, 60) - w) ? z : b) : shown[Math.floor(shown.length / 2)];
    t.fam = stemN.fam; t.size = pick.size; setHead(t, t.headKind, t.head);
  }
  if (!t.userSize) t.neck = zeroNeck(t);
  matchHeadToCup(o);   // unless the head was chosen by hand
  if (t.userSize || t.userFam) info.push(`kept as you set them: ${[t.userFam ? S.fam[t.fam].label : '', t.userSize ? `stem size ${t.size}` : ''].filter(Boolean).join(', ')} ("Use recommended" lets the rules choose)`);
  const st0 = stemStart(o); if (st0) { t.x = st0.x; t.y = st0.y; t.angle = filmTurn(); }
  // the same stem rule as Auto template (amendment 4): the head centre on the mirrored cup centre, or the older rule (the
  // other side's femoral offset) when chosen or when there is no cup
  const keepO = { keepFam: !!t.userFam, keepSize: !!t.userSize };
  const nt = S.set.stemRule !== 'offset' && cup && cup.on ? stemToCup(o, keepO) : null, c_ = canalOf(o);
  if (nt && !nt.none) info.push(`${S.fam[t.fam].label} ${t.size}, neck ${neckLabel(t, t.neck)}: head centre ${f1(Math.abs(nt.missH))} mm ${nt.missH >= 0 ? 'lateral' : 'medial'} of the mirrored cup centre${ref.offset && c_ ? `; femoral offset ${f1(G.lineDist(headAt(t), c_.a, c_.b) * mm())} mm against ${f1(ref.offset)} mm (${ref.what})` : ''}`);
  else if (nt) info.push(`stem: ${nt.why}`);
  else {
    delete S.notes['stemPlan' + o];
    const m_ = autoStem('stem' + o, { keepSize: true, keepFam: keepO.keepFam, target: ref.offset });
    if (m_) info.push(`${S.fam[t.fam].label} ${t.size}, neck ${neckLabel(t, t.neck)}: femoral offset ${f1(m_.off)} mm against ${f1(ref.offset)} mm (${ref.what})`);
    else info.push(`femoral offset not matched: ${ref.kind === 'native' ? `mark the ${SIDE[n]} femoral canal` : `place a ${SIDE[n]} stem`} first`);
  }
  S.mirror = { note: info.join('; '), at: new Date().toISOString() };
  S.active = 'stem' + o; if (quiet) return true; save(); panel(); draw(); return true;
}
const zeroNeck = t => (neckOpts(t).find(([, o]) => o === 0) || neckOpts(t)[0])[0];
// Auto template. opt.replan: the reference plan of a deformed surgery side is placed again too (otherwise a reference
// plan the surgeon made is copied as it is); opt.fresh: sizes and families set by hand are let go first ("Use
// recommended"); without it a size or family set by hand is kept (amendment 4: never overridden by Auto).
function autoTemplate(opt = {}) {
  const nr = notReady(); if (nr) return prompt(nr);
  if (S.noRange) return prompt(`No implant range with template files is open to your account yet (${PENDING.map(pendingText).join('; ')}).`);
  if (S.wf !== 'hip') {
    for (const slot of opSlots()) { const t = tplFor(slot); if (!t) continue; t.userSize = false; autoPlace(slot); }
    S.arm = null; save(); panel(); draw();
    return prompt('Components placed by the rules (cuts square to the axes, 9 mm under the higher plateau, sized from the lateral film). Check and fine-tune.');
  }
  const r = planHip(opt);
  if ('stop' in r) { if (r.stop) prompt(r.stop); return; }
  save(); panel(); draw(); prompt(r.say);
}
// The hip plan by the rules, without touching the page (recommendStem runs it on a copy, opt.dry: the stem's side only):
// {stop} when it cannot run ('' when it has said why itself), else {say}. One rule set for both sides, Mirror and the
// choice card (amendment 4).
function planHip(opt = {}) {
  const o = S.side, n = other(o), fresh = !!opt.fresh, kept = [];
  const side = (sd, keep = false) => {   // cup, then stem; the head follows the cup's liner; the stem is sized and levelled with that head
    const had = { cup: keep && !!S.tpl['cup' + sd], stem: keep && !!S.tpl['stem' + sd] };   // kept as the surgeon left them
    for (const k of ['cup', 'stem']) {
      if (had[k]) continue; const t = tplFor(k + sd); if (!t) continue;   // none of a kind not open to this account
      if (fresh) { t.userSize = false; t.userFam = false; t.userHead = false; }   // a head chosen by hand is kept too, unless "Use recommended"
      const pf = chosenFam(k);   // the family picked on the choice card, unless this slot's own was chosen by hand
      if (pf && !t.userFam && t.fam !== pf.id) setFam(t, pf); if (pf && t.fam === pf.id) t.userFam = true;
      if (t.userSize) kept.push(`${k} ${SIDE[sd]} size ${t.size}`); else if (t.userFam && k === 'stem') kept.push(`${S.fam[t.fam].label} on the ${SIDE[sd]}`);
    }
    const stem = S.tpl['stem' + sd];
    if (!had.stem && stem && !stem.userSize) stem.neck = zeroNeck(stem);
    if (!had.cup && S.tpl['cup' + sd]) autoPlace('cup' + sd, { keepSize: true });   // a size set by hand is kept
    if (had.stem || !stem) return;
    matchHeadToCup(sd);
    planStem(sd, { keepSize: !!stem.userSize, keepFam: !!stem.userFam }, stemTarget(sd));
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
  return { say: `Implants placed by the rules in "Auto plan settings". Check and fine-tune: drag, turn, change size, neck or head.${keptSay()}` };
}
const cupRuleName = r => ({ anatomic: 'native centre height, medial edge at the teardrop', teardrop: 'teardrop technique (lowest rim on the teardrop line)', head: 'native head centre', mirror: 'other hip, mirrored' }[r] || 'rules');
// a family put on a slot by a rule or the choice card: a valid size of it, and for a stem a head system it takes
function setFam(t, f) {
  if (f.kind === 'stem') {
    const w = sizeOf(t) ? widthAt(sizeOf(t), 60) : NaN, shown = sizesShown(f);
    const pick = Number.isFinite(w) ? shown.reduce((b, z) => (Math.abs(widthAt(z, 60) - w) < Math.abs(widthAt(b, 60) - w) ? z : b)) : shown[Math.floor(shown.length / 2)];
    t.fam = f.id; t.size = pick.size; if (!headKindsOf(f).includes(t.headKind)) setHead(t, f.sheetHead.kind, t.head); else setHead(t, t.headKind, t.head);
  } else { t.fam = f.id; if (!f.sizes.find(z => z.size === t.size)) t.size = f.sizes[Math.floor(f.sizes.length / 2)].size; }
}
function autoKnee(slot) {
  const t = S.tpl[slot], f = S.fam[t.fam];
  if (f.kind === 'tka-femur' && viewOf() === 'AP') {
    const A = kneeAxes(), cm = A.cm, cl = A.cl;
    if (A && A.fma) t.angle = G.turn(G.P(0, -1), A.fma);
    if (cm && cl) {   // joint line through the more distal condyle: measured resection = implant thickness there
      const down = dirOf(t, [0, 1]), m = G.mid(cm.p, cl.p), deeper = G.dot(G.sub(cm.p, m), down) > G.dot(G.sub(cl.p, m), down) ? cm.p : cl.p;
      const p = G.add(m, G.mul(down, G.dot(G.sub(deeper, m), down))); t.x = p.x; t.y = p.y;
    }
  } else if (f.kind === 'tka-tibia' && viewOf() === 'AP') {
    const A = kneeAxes(), pm = A.pm, pl = A.pl;
    if (A && A.tma) t.angle = G.turn(G.P(0, -1), A.tma);
    if (pm && pl) {   // cut 9 mm below the higher (less worn) plateau
      const up = dirOf(t, [0, -1]), m = G.mid(pm.p, pl.p), hi = G.dot(G.sub(pm.p, m), up) > G.dot(G.sub(pl.p, m), up) ? pm.p : pl.p;
      const p = G.sub(G.add(m, G.mul(up, G.dot(G.sub(hi, m), up))), G.mul(up, pxOf(9))); t.x = p.x; t.y = p.y;
    }
  } else if (f.kind === 'tka-tibia') {   // lateral: along the tibial axis, at the native slope, 9 mm under the plateau
    const L = latKnee(), ant = antDir(L);
    if (L.pa && L.pp && !t.userSize) { const d = G.dist(L.pa.p, L.pp.p) * mm(); t.size = f.sizes.reduce((b, z) => Math.abs(z.apDepth - d) < Math.abs(b.apDepth - d) ? z : b).size; }
    if (ant && !t.userFlip) t.flip = ant.x < 0;   // outlines face anterior to the right unless flipped
    if (L.up) {   // turn by the slope one way; if that tilts the wrong way, the other
      const a0 = G.turn(G.P(0, -1), L.up), want = Math.max(0, L.slope ?? 0);
      t.angle = a0 + G.rad(want);
      if (L.post && Math.abs(slopeOf(t, L) - want) > 0.05) t.angle = a0 - G.rad(want);
    }
    if (L.pa && L.pp && L.up) {
      const m = G.mid(L.pa.p, L.pp.p), up = dirOf(t, [0, -1]), p = G.sub(m, G.mul(up, pxOf(9))); t.x = p.x; t.y = p.y;
    }
  } else if (f.kind === 'tka-femur') {     // lateral: anterior cut on the anterior cortex, back level with the condyles
    const L = latKnee(), ant0 = antDir(L);
    if (L.ac && L.pc && !t.userSize) { const d = G.lineDist(L.pc.p, L.ac.a, L.ac.b) * mm(); t.size = femBySizing(f, d).size; }
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
  const was = stemAligned(t, sd), H0 = headAt(t);
  apply();
  const H1 = headAt(t); t.x += H0.x - H1.x; t.y += H0.y - H1.y;   // same head centre
  if (was) { alignStem(t, sd); setHeadLevel(t, sd, G.dot(H0, vertical())); }
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
  return (G.dot(G.sub(lo.p, H.Tc), G.mul(H.v, -1)) - G.dot(G.sub(lc.p, H.Tc), G.mul(H.v, -1))) * mm();
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
  let fma = null, fmaSrc = '';
  if (hip && knee) { fma = G.unit(G.sub(hip.c, knee.p)); fmaSrc = 'hip centre to knee centre'; }
  else if (anat) {   // no hip on the film: anatomical axis turned medially by the assumed valgus angle
    const a1 = G.rot(anat, G.rad(S.vca)), a2 = G.rot(anat, -G.rad(S.vca));
    fma = G.dot(a1, latDir) < G.dot(a2, latDir) ? a1 : a2; fmaSrc = `shaft axis turned ${S.vca}° (assumed, no hip on film)`;
  }
  let tma = null, tmaSrc = '';
  if (ankle && knee) { tma = G.unit(G.sub(knee.p, ankle.p)); tmaSrc = 'ankle centre to knee centre'; }
  else if (ts) { tma = G.unit(G.sub(ts.a, ts.b)); if (knee ? G.dot(G.sub(knee.p, G.mid(ts.a, ts.b)), tma) < 0 : G.dot(tma, cranVec()) < 0) tma = G.mul(tma, -1); tmaSrc = 'tibial shaft axis (no ankle on film)'; }
  return { knee, hip, ankle, anat, fma, fmaSrc, tma, tmaSrc, latDir, cm, cl, pm, pl, swapCond, swapPlat };
}
const f1 = x => (Number.isFinite(x) ? (Math.round(x * 10) / 10).toFixed(1) : '–');
const sgn = (x, pos, neg) => `${f1(Math.abs(x))} mm ${x >= 0 ? pos : neg}`;

function results() {   // [[label, value, cls]] with group headers as [label]
  const R = [], k = mm();
  if (!S.img) return R;
  if (S.headerSide && S.headerSide !== S.side && S.wf !== 'hip') R.push(['Check side', `the image says ${SIDE[S.headerSide]}, the plan is for the ${SIDE[S.side]}`, 'bad']);
  if (S.headerSide && S.headerSide !== S.side && S.wf === 'hip' && S.singleJoint) R.push(['Check side', `the image says ${SIDE[S.headerSide]}, the surgery side is set to ${SIDE[S.side]}`, 'bad']);
  if (S.headerView === 'lateral' && WF[S.wf].view === 'AP') R.push(['Check view', 'the image says lateral, the plan is for an AP film', 'bad']);
  if (S.headerView === 'AP' && WF[S.wf].view === 'LAT') R.push(['Check view', 'the image says AP, the plan is for a lateral film', 'bad']);
  for (const d of lmDefs()) { const pr = lmProblem(d.key); if (pr) R.push(['Check landmarks', `${d.label}: ${pr}`, 'bad']); }
  if ((S.orient.from === 'marker' || S.orient.from === 'assumed') && S.wf !== 'kneeLat') R.push(['Orientation', 'not yet confirmed: which way the film faces', 'warn']);
  // set by hand against a marker that was read: stays in the results, on the film and in the saved report (Fable, 11 Oct)
  if (S.orient.from === 'user' && typeof S.orient.markerMirrored === 'boolean' && S.mirrored !== S.orient.markerMirrored)
    R.push(['Check orientation', `set to the patient's right on the image ${S.mirrored ? 'right' : 'left'}, against the R/L marker read on the film (${S.orient.markerSaid || 'marker'}), which puts it on the image ${S.orient.markerMirrored ? 'right' : 'left'}`, 'bad']);
  R.push(['Calibration']);
  if (!S.cal) R.push(['Scale', 'none: calibrate first', 'bad']);
  else {
    R.push(['Method', S.cal.method === 'ball' ? `${S.ballD} mm ball${S.cal.how === 'found automatically' ? ' (found automatically)' : ''}` : S.cal.method === 'line' ? `line of ${S.cal.lengthMm} mm` : 'machine estimate', S.cal.method === 'dicom' ? 'warn' : '']);
    R.push(['mm per pixel', S.cal.mmPerPx.toFixed(4)]);
    const e = dicomEstimate();
    if (e && S.cal.method !== 'dicom') {
      if (e.detector) {   // detector pitch only: the ball tells the magnification at the joint, usually 1.05-1.35
        const mag = e.mmPerPx / S.cal.mmPerPx;
        R.push(['Magnification at the marker', `${mag.toFixed(3)}`, mag < 1.0 || mag > 1.45 ? 'bad' : mag < 1.03 || mag > 1.35 ? 'warn' : '']);
      } else { const dd = (S.cal.mmPerPx / e.mmPerPx - 1) * 100; R.push(['vs machine estimate', `${f1(dd)} %`, Math.abs(dd) > 25 ? 'bad' : Math.abs(dd) > 10 ? 'warn' : '']); }
    }
    if (S.cal.method === 'ball' && S.cal.warnings && S.cal.warnings.length) R.push(['Calibration', 'uncertain: ' + S.cal.warnings[0].split(':')[0], 'warn']);
    if (S.cal.method === 'ball' && S.cal.confirmed === false) R.push(['Ball', 'found automatically, not yet confirmed', 'warn']);
  }
  if (!k) return R;
  if (S.wf === 'hip') hipResults(R, k);
  else if (S.wf === 'kneeAP') kneeAPResults(R, k);
  else kneeLatResults(R, k);
  for (const [i, r] of S.rulers.entries()) if (r.length === 2) { if (i === 0) R.push(['Rulers']); R.push([`Ruler ${i + 1}`, `${f1(G.dist(r[0], r[1]) * k)} mm`]); }
  return R;
}
function hipResults(R, k) {
  const o = S.side, n = other(o), H = hipFrame(o), h = lmGeom('head' + o), hc = lmGeom('head' + n), canal = canalOf(o), canalN = canalOf(n);
  const lo = lmGeom('lt' + o), lc = lmGeom('lt' + n), Hn = hipFrame(n);
  R.push([`Native hip (surgery side: ${SIDE[o]})`]);
  if (refOther()) R.push(['Planned from', `the ${SIDE[n]} hip, mirrored: the ${SIDE[o]} hip is deformed, its own head is not used`]);
  if (S.scope === 'one') R.push(['Reference', 'one hip on the film: the film\'s own axes stand in for the teardrop line; no leg length, no mirror', 'warn']);
  if (H && !H.single && G.dot(G.sub(H.T, H.Tc), latVec(o)) < 0) R.push(['Check side', `the ${SIDE[o]} teardrop is on the ${SIDE[n]} half of the film: check the surgery side and the orientation settings`, 'bad']);
  if (h) { const sp = G.arcSpan(S.lm['head' + o], h.c); if (sp < 90) R.push(['Head rim points', `cover only ${Math.round(sp)}° of the rim: spread them round the head`, 'warn']); }
  if (h) R.push([refOther() ? 'Head diameter (deformed)' : 'Head diameter', `${f1(2 * h.r * k)} mm`]);
  if (hc) R.push(['Other head diameter', `${f1(2 * hc.r * k)} mm`]);
  const lld = legLengthNow();
  if (lld !== null) R.push([refOther() ? 'Leg length now (fractured side)' : 'Leg length now', Math.abs(lld) < 0.05 ? 'equal' : `surgery side ${f1(Math.abs(lld))} mm ${lld > 0 ? 'longer' : 'shorter'}`]);
  if (refOther()) R.push(['Leg length after', `restored by matching the ${SIDE[n]} side: same stem, head and neck, neck cut at the same height above the lesser trochanter (not measured on the fractured femur)`]);
  const nativeOff = h && canal ? G.lineDist(h.c, canal.a, canal.b) * k : null, otherOff = hc && canalN ? G.lineDist(hc.c, canalN.a, canalN.b) * k : null;
  if (nativeOff !== null) R.push([refOther() ? 'Femoral offset (deformed)' : 'Femoral offset', `${f1(nativeOff)} mm`]);
  if (otherOff !== null) R.push(['Other side femoral offset', `${f1(otherOff)} mm`]);
  if (h && H && !refOther()) R.push(['Acetabular offset (teardrop to head centre)', `${f1(G.dot(G.sub(h.c, H.T), H.u) * k)} mm`]);
  if (hc && Hn && !Hn.single) R.push(['Other side acetabular offset', `${f1(G.dot(G.sub(hc.c, Hn.T), Hn.u) * k)} mm`]);
  const cup = S.tpl['cup' + o], stem = S.tpl['stem' + o];
  let cor = null;
  if (cup && cup.on) {
    const f = S.fam[cup.fam], s = sizeOf(cup), X = xf(cup); cor = X([0, 0]);
    if (S.notes['mirrorMissing' + o] && !mirrorTarget(o)) R.push(['Cup centre', `not mirrored: the ${SIDE[n]} hip is not marked, so the cup went to the native head centre. Mark the ${SIDE[n]} femoral head (or place a ${SIDE[n]} cup), then press Mirror`, 'bad']);
    R.push([`Cup (${SIDE[o]})`]); R.push([f.label, `${s.size} (outside ${s.catalogueOuter || s.outerDiameter} mm)`]);
    if (s.catalogueOuter && Math.abs(s.outerDiameter - s.catalogueOuter) > 0.5) R.push(['Cup size check', `the ${drawnFrom(f)} is ${s.outerDiameter} mm outside, the catalogue ${s.catalogueOuter} mm`, 'warn']);
    if (headsAcrossLiners(cup)) R.push([...HEADS_ACROSS, 'warn']);
    if (H) {
      const face = dirOf(cup, [1, 0]); let inc = G.angleDeg(face, H.u); if (inc > 90) inc = 180 - inc;
      R.push(['Inclination', `${f1(inc)}°`, inc < 30 || inc > 50 ? 'warn' : '']);
      const d = G.sub(cor, H.T); R.push(['Centre from teardrop', `${f1(G.dot(d, H.u) * k)} lateral, ${f1(G.dot(d, H.v) * k)} up`]);
      const out = rimVsRoof(cup, o, H, k);
      if (out !== null) R.push(['Cup rim vs roof edge', roofTxt(out), out > 8 ? 'warn' : '']);
    }
    cupRows(R, o, cup, H, k, refOther() ? (hc ? 2 * hc.r * k : null) : h ? 2 * h.r * k : null, refOther() ? `, ${SIDE[n]}` : '');
    if (h && H && !refOther()) { const d = G.sub(cor, h.c), up = G.dot(d, H.v) * k; R.push(['Centre vs native head', `${sgn(G.dot(d, H.u) * k, 'lateral', 'medial')}, ${sgn(up, 'up', 'down')}`, up > 3 ? 'warn' : '']); if (up > 3) R.push(['Cup height', `the cup centre is ${f1(up)} mm above the native head centre: check that the dome is not in the ilium above the acetabulum`, 'warn']); }
  }
  if (stem && stem.on) {
    const f = S.fam[stem.fam], s = sizeOf(stem), g = geomOf(stem), X = xf(stem), Hs = headAt(stem);
    R.push([`Stem (${SIDE[o]})`]); R.push([f.label, `size ${s.size}`]);
    R.push(['Head', `${stem.head} mm ${S.lib.heads[stem.headKind].label}, neck ${neckLabel(stem, stem.neck)}`]);
    if (cup && cup.on) {
      const cs = sizeOf(cup), ch = cupHeads(cup);
      if (ch && !ch.includes(String(stem.head))) R.push(['Head and cup', `cup ${cs.size} takes ${headsMm(ch)} heads`, 'warn']);
      else if (!ch && cs.maxHead && +stem.head !== cs.maxHead) R.push(['Head and cup', `cup ${cs.size} takes a ${linerTxt(S.fam[cup.fam], cs.liner)} liner for a ${headMm(cs.maxHead)} head`, 'warn']);
      headCaution(R, cup, stem);
    }
    if (g.headSource) R.push(['Head centre from', g.headSource.replace(/^Chunli sheet/, 'manufacturer\'s sheet'), /sheet/.test(g.headSource) ? '' : 'warn']);
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
      const tr = canalTraceFor(o), fit = tr ? stemFit(stem, tr) : null, Ls = tr ? stemLen(stem, tr) : 0, ev = fitIsEvidence(fit, tr, Ls);
      if (fit) R.push(['Canal fill (distal third of the traced canal)', `${Math.round(fit.fillDistal * 100)} %; ${fit.maxIn > 0.5 ? `${f1(fit.maxIn)} mm past the inner cortex at the tightest level` : fit.maxIn >= -0.5 ? 'touching the inner cortex at the tightest level' : `${f1(-fit.maxIn)} mm clear of the inner cortex at the tightest level`}${ev ? '' : `; checked over only ${Math.round(fit.coveredMm)} of ${Math.round(Ls)} mm of stem (${Math.round(fit.maxGapMm)} mm unchecked in one stretch)`}`, fit.maxIn > 1 || !ev ? 'warn' : '']);
      else R.push(['Canal fill', 'not checked: the canal\'s inner cortex was not traced along this stem', 'warn']);
    }
    if (g.neckCut && lo) {
      const up = canal ? canalUp(canal) : vertical(), med = X(g.neckCut.medial), d = G.dot(G.sub(med, lo.p), up) * k;
      R.push(['Neck cut above lesser trochanter', `${f1(d)} mm (medial end of the cut)`]);
    } else if (!g.neckCut) R.push(['Neck cut', 'not printed on the sheet for this size', 'warn']);
    const target = cor || (!refOther() && h && h.c);
    if (target && H) {
      const d = G.sub(target, Hs), dL = -G.dot(d, H.v) * k, dO = G.dot(d, H.u) * k;
      R.push([cor ? 'After reduction to the cup' : 'After reduction to the native head centre']);
      R.push(['Leg length change', sgn(dL, 'longer', 'shorter')]);
      R.push(['Offset change', sgn(dO, 'more', 'less')]);
      if (lld !== null) { const p = lld + dL; R.push(['Leg length after', Math.abs(p) < 0.05 ? 'equal' : `surgery side ${f1(Math.abs(p))} mm ${p > 0 ? 'longer' : 'shorter'}`, Math.abs(p) > 5 ? 'warn' : '']); }
    }
    if (cor && H) stemCupRows(R, o, stem, cup, H, k);
  }
  // the other side's plan, and the operated side against it (mirrored about the pelvic midline)
  const cupN = S.tpl['cup' + n], stemN = S.tpl['stem' + n];
  if (refOther() && stemN && stemN.on) {   // the stem for the fractured side is templated on the normal one (Sami, 11 Oct)
    const gN = geomOf(stemN), Hs = headAt(stemN), cutN = neckCutAboveLT(stemN, n), hnat = lmGeom('head' + n), cN = cupN && cupN.on ? xf(cupN)([0, 0]) : null;
    R.push([`Stem for the ${SIDE[o]} (templated on the ${SIDE[n]})`]);
    R.push([S.fam[stemN.fam].label, `size ${sizeOf(stemN).size}`]);
    R.push(['Head', `${stemN.head} mm ${S.lib.heads[stemN.headKind].label}, neck ${neckLabel(stemN, stemN.neck)}`]);
    R.push(['Neck cut above lesser trochanter', cutN !== null ? `${f1(cutN)} mm (medial end of the cut; measure from the ${SIDE[o]} lesser trochanter at surgery)` : `mark the ${SIDE[n]} lesser trochanter to measure it`, cutN !== null ? '' : 'warn']);
    if (!gN.neckCut) R.push(['Neck cut', 'not printed on the sheet for this size', 'warn']);
    if (canalN && hnat) R.push(['Femoral offset', `${f1(G.lineDist(Hs, canalN.a, canalN.b) * k)} mm planned, ${f1(G.lineDist(hnat.c, canalN.a, canalN.b) * k)} mm native (${SIDE[n]})`]);
    if (cN && Hn && !Hn.single) { const d = G.sub(cN, Hs); R.push([`Leg length change on the ${SIDE[n]} plan`, sgn(-G.dot(d, Hn.v) * k, 'longer', 'shorter'), Math.abs(G.dot(d, Hn.v) * k) > 5 ? 'warn' : '']); }
    const cupP = cupN && cupN.on ? cupN : cup && cup.on ? cup : null; if (cupP) headCaution(R, cupP, stemN);
    if (cN && Hn && !Hn.single) stemCupRows(R, n, stemN, cupN, Hn, k);
    const trN = canalTraceFor(n), fitN = trN ? stemFit(stemN, trN) : null;
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
      R.push(['Other side stem', `${S.fam[stemN.fam].label} ${sizeOf(stemN).size}, ${stemN.head} mm head, neck ${neckLabel(stemN, stemN.neck)}`]);
      if (canalN) R.push(['Other side new femoral offset', `${f1(G.lineDist(headAt(stemN), canalN.a, canalN.b) * k)} mm`]);
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
      const dOff = G.lineDist(headAt(stem), canal.a, canal.b) * k - ref.offset;
      R.push(['Femoral offset vs other side', sgn(dOff, 'more', 'less'), Math.abs(dOff) > 5 ? 'warn' : '']);
    }
  }
}
// the cup's superolateral rim against the roof edge of its side, across the pelvis (mm, + lateral of the edge: uncovered),
// or null when the roof edge is not marked
function rimVsRoof(cup, sd, H, k) {
  const ac = lmGeom('ac' + sd); if (!ac || !H) return null;
  const s = sizeOf(cup), cor = xf(cup)([0, 0]); let inc = G.angleDeg(dirOf(cup, [1, 0]), H.u); if (inc > 90) inc = 180 - inc;
  const r = (s.catalogueOuter || s.outerDiameter) / 2 / k, rim = G.add(cor, G.mul(G.add(G.mul(H.u, Math.cos(G.rad(inc))), G.mul(H.v, Math.sin(G.rad(inc)))), r));
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
  const p = cupVsTeardrop(xf(cup)([0, 0]), outer / 2 / k, inc, H.T, H.u, H.v, k);
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
  const C = xf(cup)([0, 0]), hp = headAt(stem), d = G.sub(hp, C), L = levelPlan(sd), gain = L ? L.gain : 0;
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
function kneeAPResults(R, k) {
  const A = kneeAxes(); R.push(['Alignment']);
  if (A.swapCond || A.swapPlat) R.push(['Check landmarks', `${[A.swapCond && 'condyles', A.swapPlat && 'plateaus'].filter(Boolean).join(' and ')} clicked medial for lateral: the page used the film's sides; check Side if that is wrong`, 'bad']);
  if (A.hip && A.knee && A.ankle) {
    const hka = G.angleDeg(G.sub(A.hip.c, A.knee.p), G.sub(A.ankle.p, A.knee.p));
    const foot = G.project(A.knee.p, A.hip.c, G.sub(A.ankle.p, A.hip.c)), s = G.dot(G.sub(A.knee.p, foot), A.latDir);
    R.push(['Hip-knee-ankle angle', `${f1(hka)}° (${f1(180 - hka)}° ${s > 0 ? 'varus' : 'valgus'})`]);
    R.push(['Mechanical axis passes', `${f1(G.dist(A.knee.p, foot) * k)} mm ${s > 0 ? 'medial' : 'lateral'} to the knee centre`]);
  } else R.push(['Hip-knee-ankle angle', 'needs hip, knee and ankle centres (long-leg film)', 'warn']);
  if (A.fma && A.cm && A.cl) R.push(['mLDFA', `${f1(G.angleDeg(A.fma, G.sub(A.cl.p, A.cm.p)))}°`]);
  if (A.anat && A.cm && A.cl) R.push(['aLDFA', `${f1(G.angleDeg(A.anat, G.sub(A.cl.p, A.cm.p)))}°`]);
  // MPTA: medial angle inside the tibia, between the plateau line and the axis running down to the ankle
  if (A.tma && A.pm && A.pl) R.push(['MPTA', `${f1(G.angleDeg(G.mul(A.tma, -1), G.sub(A.pm.p, A.pl.p)))}°`]);
  if (A.cm && A.cl && A.pm && A.pl) { const j = G.angleDeg(G.sub(A.cl.p, A.cm.p), G.sub(A.pl.p, A.pm.p)); R.push(['Joint line convergence', `${f1(Math.min(j, 180 - j))}°`]); }
  if (A.anat && A.hip && A.knee) R.push(['Femoral valgus angle (shaft to mechanical)', `${f1(G.angleDeg(A.anat, A.fma))}°`]);
  if (A.fmaSrc) R.push(['Femoral mechanical axis', A.fmaSrc, A.hip ? '' : 'warn']);
  if (A.tmaSrc) R.push(['Tibial mechanical axis', A.tmaSrc, A.ankle ? '' : 'warn']);
  const fe = S.tpl.femur, ti = S.tpl.tibia; let femThick = null, femRes = null;
  if (fe && fe.on) {
    const s = sizeOf(fe), g = geomOf(fe), X = xf(fe), cut = X([0, g.distalCutY]), down = dirOf(fe, [0, 1]);
    let lat = dirOf(fe, [1, 0]); if (G.dot(lat, A.latDir) < 0) lat = G.mul(lat, -1);
    R.push(['Femoral component']); R.push([S.fam[fe.fam].label, `size ${s.size}, ${s.sides[S.side].mlWidth} mm wide`]);
    if (A.fma) { const a = G.angleDeg(A.fma, lat); R.push(['Distal cut to mechanical axis (lateral angle)', `${f1(a)}°`, Math.abs(a - 90) > 2 ? 'warn' : '']); }
    if (A.anat) R.push(['Distal cut to shaft axis (valgus for the guide)', `${f1(Math.abs(90 - G.angleDeg(A.anat, lat)))}°`]);
    femThick = s.distalThickness;
    if (A.cm && A.cl) {
      femRes = { m: G.dot(G.sub(A.cm.p, cut), down) * k, l: G.dot(G.sub(A.cl.p, cut), down) * k };
      R.push(['Distal resection medial / lateral', `${f1(femRes.m)} / ${f1(femRes.l)} mm`]);
      R.push(['Implant distal thickness', `${f1(femThick)} mm`]);
    }
  }
  if (ti && ti.on) {
    const s = sizeOf(ti), g = geomOf(ti), X = xf(ti), cut = X([0, g.cutY]), up = dirOf(ti, [0, -1]);
    let med = dirOf(ti, [1, 0]); if (G.dot(med, A.latDir) > 0) med = G.mul(med, -1);
    R.push(['Tibial tray']); R.push([S.fam[ti.fam].label, `size ${s.size}, ${s.mlWidth} mm wide, total thickness ${ti.thick} mm (tray + insert)`]);
    if (A.tma) { const a = G.angleDeg(G.mul(A.tma, -1), med); R.push(['Tibial cut to mechanical axis (medial angle)', `${f1(a)}°`, Math.abs(a - 90) > 2 ? 'warn' : '']); }
    if (A.pm && A.pl) {
      const tm = G.dot(G.sub(A.pm.p, cut), up) * k, tl = G.dot(G.sub(A.pl.p, cut), up) * k;
      R.push(['Tibial resection medial / lateral', `${f1(tm)} / ${f1(tl)} mm`]);
      if (femRes) {
        R.push(['Extension gap: bone off vs implant']);
        R.push(['Medial', `${f1(femRes.m + tm)} vs ${f1(femThick + ti.thick)} mm (not a balanced gap: no soft tissue on a film)`]);
        R.push(['Lateral', `${f1(femRes.l + tl)} vs ${f1(femThick + ti.thick)} mm`]);
      }
    }
  }
}
function kneeLatResults(R, k) {
  const ac = lmGeom('antCortex'), pc = lmGeom('postCond'), pa = lmProblem('platAnt') ? null : lmGeom('platAnt'), pp = lmGeom('platPost'), ta = lmGeom('tibAxis');
  R.push(['Native knee']);
  // sizes from the placed component's family, else the first of that kind open to this account
  const famOf = (slot, kind) => (S.tpl[slot] && S.fam[S.tpl[slot].fam]) || famsFor(kind)[0];
  if (ac && pc) {
    const depth = G.lineDist(pc.p, ac.a, ac.b) * k, ff = famOf('femur', 'tka-femur'); R.push(['Femur: anterior cortex to back of condyles', `${f1(depth)} mm`]);
    if (ff) { const best = femBySizing(ff, depth); R.push(['Nearest femoral size', `${best.size} (${best.sides[S.side].apSizing} mm from anterior cut to back of condyles)`]); }
  }
  let up = null;
  if (ta) { up = G.unit(G.sub(ta.a, ta.b)); const pmid = pa && pp ? G.mid(pa.p, pp.p) : null; if (pmid ? G.dot(G.sub(pmid, G.mid(ta.a, ta.b)), up) < 0 : G.dot(up, cranVec()) < 0) up = G.mul(up, -1); }
  if (up && pa && pp) R.push(['Posterior tibial slope', `${f1(latKnee().slope)}°`]);
  if (pa && pp) {
    const L = G.dist(pa.p, pp.p) * k, tib = famOf('tibia', 'tka-tibia');
    const best = tib && tib.sizes.reduce((b, s) => Math.abs(s.apDepth - L) < Math.abs(b.apDepth - L) ? s : b);
    R.push(['Plateau depth', best ? `${f1(L)} mm, nearest tray ${best.size} (${best.apDepth} mm)` : `${f1(L)} mm`]);
  }
  const fe = S.tpl.femur, ti = S.tpl.tibia;
  if (fe && fe.on) {
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
  if (ti && ti.on && up) { R.push(['Tibial tray']); R.push(['Cut slope as placed', `${f1(slopeOf(ti, latKnee()))}°`]); }
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
  if (bd) {   // the stroke being drawn
    ctx.save(); ctx.strokeStyle = 'rgba(255,79,216,0.45)'; ctx.lineCap = ctx.lineJoin = 'round'; ctx.lineWidth = 2 * bd.r * S.view.k;
    ctx.beginPath(); bd.pts.forEach((p, i) => { const q = toS(p); i ? ctx.lineTo(q.x, q.y) : ctx.moveTo(q.x, q.y); }); if (bd.pts.length === 1) { const q = toS(bd.pts[0]); ctx.lineTo(q.x + 0.1, q.y); } ctx.stroke(); ctx.restore();
  }
  if (S.arm && S.arm.kind === 'brush' && S.hover) { const q = toS(S.hover); ctx.save(); ctx.strokeStyle = '#ff4fd8'; ctx.lineWidth = 1.5; ctx.beginPath(); ctx.arc(q.x, q.y, S.brush, 0, 7); ctx.stroke(); ctx.restore(); }
  if (S.loupe) drawLoupe(W, H);
  const k = mm();
  setText('hud', `W ${Math.round(S.wl.w)} L ${Math.round(S.wl.c)} · zoom ${(S.view.k * 100).toFixed(0)} %` + (k ? ` · 10 mm = ${(10 / k * S.view.k).toFixed(0)} screen px` : ''));
  let R = []; try { R = results(); } catch (e) { console.error(e); R = [['Results', `could not be worked out (${e.message || e})`, 'bad']]; }
  setHTML('res', resTable(R));
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
// is this landmark's handle drawn (and so draggable) in this mode? A landmark to place again (red) always is.
const lmShown = (d, bad, mode) => bad || !mode || (mode === true && d.type === 'point');
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
  S.segHand.save = true;
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
function overlay(g, T, lw, ui, clean = false) {   // clean: no construction lines (saved picture, or "Clean view")
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
  for (const mk of S.masks || []) {   // black-outs: solid on the saved picture, shaded with an outline on screen
    const A = T(mk.a), B = T(mk.b), x = Math.min(A.x, B.x), y = Math.min(A.y, B.y), w = Math.abs(A.x - B.x), h = Math.abs(A.y - B.y);
    g.fillStyle = ui ? 'rgba(0,0,0,0.75)' : '#000'; g.fillRect(x, y, w, h);
    if (ui) { g.strokeStyle = '#fff'; g.lineWidth = 1; g.setLineDash([4, 3]); g.strokeRect(x, y, w, h); g.setLineDash([]); }
  }

  // calibration
  const ph = clean === 'phone';   // a phone's clean film: no ball, no teardrop line, no landmark points either
  if (S.cal && S.cal.ball && !ph) { ring(S.cal.ball, S.cal.ball.r, COL.cal, 1.5); cross(S.cal.ball, COL.cal); if (showLabels) label(G.add(S.cal.ball, G.P(S.cal.ball.r * 0.7, -S.cal.ball.r * 0.7)), `${S.ballD} mm ball`, COL.cal); }
  if (S.cal && S.cal.line) { line(S.cal.line[0], S.cal.line[1], COL.cal, 1.5); label(S.cal.line[1], `${S.cal.lengthMm} mm`, COL.cal); }
  if (S.orient.marker && !ph && (ui || (S.orient.from === 'user' && typeof S.orient.markerMirrored === 'boolean' && S.mirrored !== S.orient.markerMirrored))) {   // the marker read on the film
    const m = S.orient.marker, a = G.P(m.x - m.w / 2 - 6, m.y - m.h / 2 - 6), b = G.P(m.x + m.w / 2 + 6, m.y + m.h / 2 + 6);
    stroke(() => { const A = T(a), B = T(b); g.rect(A.x, A.y, B.x - A.x, B.y - A.y); }, COL.cal, 1.2, [4, 3]);
  }

  // derived axes
  if (S.wf === 'hip') {
    const Hf = hipFrame();
    if (ph) { /* the plan only */ }
    else if (Hf && clean && !Hf.single) { const ext = G.mul(Hf.u, pxOf(25)); line(G.add(Hf.T, ext), G.sub(Hf.Tc, ext), COL.line, 1.2, [6, 5]); }   // the teardrop line, between the teardrops
    else if (Hf) longLine(Hf.T, Hf.u, COL.line, 1, [6, 5]);
    if (!clean) for (const sd of ['R', 'L']) {
      const c = lmGeom('canal' + sd);
      if (c) { const u = G.unit(G.sub(c.b, c.a)); line(G.sub(c.a, G.mul(u, 50 / (k || 0.2))), G.add(c.b, G.mul(u, 80 / (k || 0.2))), COL.line, 1, [6, 5]); }
      const tr = S.canal[sd] && S.canal[sd].tr;   // the inner cortical edges found on the film
      if (tr && c && S.canal[sd].key === JSON.stringify([c.a, c.b, k])) for (const lv of tr.levels) { dot(lv.l, COL.line, 1.6); dot(lv.r, COL.line, 1.6); }
    }
    const mt = mirrorTarget(S.side), ref = mirrorRef(S.side);
    if (mt && Hf && ref && !clean) {   // the other hip mirrored onto the surgery side: the reconstruction target
      ring(mt, ref.r, COL.ghost, 1.2, [5, 4]); cross(mt, COL.ghost, 5);
      const M = G.mid(Hf.T, Hf.Tc); line(G.sub(M, G.mul(Hf.v, pxOf(120))), G.add(M, G.mul(Hf.v, pxOf(160))), COL.ghost, 0.8, [2, 6]);
      if (showLabels) label(G.add(mt, G.mul(G.add(G.mul(Hf.u, -1), Hf.v), ref.r * 0.95)), `${ref.what}, mirrored`, COL.ghost);
    }
  } else if (S.wf === 'kneeAP') {
    const A = kneeAxes();
    if (A.hip && A.ankle) line(A.hip.c, A.ankle.p, COL.line, 1, [8, 6]);
    if (A.hip && A.knee) line(A.hip.c, A.knee.p, COL.line, 1.2);
    if (A.knee && A.ankle) line(A.knee.p, A.ankle.p, COL.line, 1.2);
    if (A.knee && A.fma && !A.hip && !clean) longLine(A.knee.p, A.fma, COL.line, 1, [3, 4], 400 / (k || 0.2));
    if (A.knee && A.tma && !A.ankle && !clean) longLine(A.knee.p, A.tma, COL.line, 1, [3, 4], 400 / (k || 0.2));
    if (A.cm && A.cl) line(A.cm.p, A.cl.p, COL.line, 1);
    if (A.pm && A.pl) line(A.pm.p, A.pl.p, COL.line, 1);
  } else {
    const ac = lmGeom('antCortex'); if (ac && !clean) longLine(ac.a, G.unit(G.sub(ac.b, ac.a)), COL.line, 1, [6, 5], 300 / (k || 0.2));
    const ta = lmGeom('tibAxis'); if (ta && !clean) longLine(ta.a, G.unit(G.sub(ta.b, ta.a)), COL.line, 1, [6, 5], 300 / (k || 0.2));
    const pa = lmGeom('platAnt'), pp = lmGeom('platPost'); if (pa && pp) line(pa.p, pp.p, COL.line, 1);
  }

  // templates
  if (k) for (const [slot, t] of Object.entries(S.tpl)) {
    if (!t.on) continue;
    const gm = geomOf(t); if (!gm) continue;
    const X = xf(t), f = S.fam[t.fam], act = ui && S.active === slot, op = !SLOT[slot].side || SLOT[slot].side === S.side;
    const col = op ? COL.tpl : COL.tpl2, fill = op ? COL.fill : COL.fill2;
    const outline = () => { for (const r of gm.rings) { r.forEach((p, i) => { const s = T(X(p)); i ? g.lineTo(s.x, s.y) : g.moveTo(s.x, s.y); }); g.closePath(); } };
    g.beginPath(); outline(); g.fillStyle = fill; g.fill('evenodd');
    stroke(outline, col, act ? 2.2 : 1.7);
    if (f.kind === 'cup') {
      const c = X([0, 0]); cross(c, col, clean ? 6 : 8);
      const stem = S.tpl['stem' + slotSide(slot)];
      if (!clean) { ring(c, pxOf(+(stem ? stem.head : 32) / 2), col, 1, [4, 4]); line(X([-40, 0]), X([40, 0]), col, 1, [2, 4]); }
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
      if (gm.neckCut) {   // the neck resection line printed on the sheet, run on a little past the calcar
        const a = X(gm.neckCut.lateral), b = X(gm.neckCut.medial), d = G.unit(G.sub(b, a));
        line(a, b, COL.cut, 2.2); line(b, G.add(b, G.mul(d, pxOf(8))), COL.cut, 1.2, [3, 3]);
        const nc = neckCutAboveLT(t, slotSide(slot));
        if (showLabels) label(G.add(b, G.mul(d, pxOf(8))), nc === null ? 'neck cut' : `neck cut ${f1(nc)} mm above LT`, COL.cut);
      }
    } else if (f.kind === 'tka-femur' && gm.distalCutY !== undefined) {
      line(X([-50, gm.distalCutY]), X([50, gm.distalCutY]), COL.cut, 1.5, [8, 5]);
      line(X([-50, gm.jointLineY]), X([50, gm.jointLineY]), col, 1, [2, 4]);
    } else if (f.kind === 'tka-femur' && gm.cutProfile) {
      stroke(() => gm.cutProfile.forEach((p, i) => { const s = T(X(p)); i ? g.lineTo(s.x, s.y) : g.moveTo(s.x, s.y); }), COL.cut, 1.5, [8, 5]);
    } else if (f.kind === 'tka-tibia' && gm.cutY !== undefined) {
      line(X([-55, gm.cutY]), X([55, gm.cutY]), COL.cut, 1.5, [8, 5]);
      const jl = gm.cutY - t.thick; line(X([-55, jl]), X([55, jl]), col, 1, [2, 4]); if (showLabels) label(X([45, jl]), `${t.thick} mm`, col);
    } else if (f.kind === 'tka-tibia' && gm.cutLine) {
      line(X(gm.cutLine[0]), X(gm.cutLine[1]), COL.cut, 1.5, [8, 5]);
    }
    if (act) {   // move and turn handles
      const pv = G.P(t.x, t.y), hnd = rotHandle(t);
      dot(pv, col, 4); line(pv, hnd, col, 1, [3, 3]); dot(hnd, '#fff', 6);
    }
    if (showLabels) label(X(f.kind === 'cup' ? [0, -22] : gm.rings[0][0]), `${SLOT[slot].label}${SLOT[slot].side ? ' ' + SLOT[slot].side : ''} ${sizeOf(t).size}`, col);
  }

  // landmarks
  for (const d of lmDefs()) {
    const L = S.lm[d.key]; if (!L) continue;
    const bad = !!lmProblem(d.key);
    if (ph && !bad) continue;   // (lmShown: a hidden landmark is neither drawn nor picked up)
    if (lmShown(d, bad, clean === 'phone' ? 'phone' : !!clean)) for (const p of L) dot(p, bad ? '#ff4040' : COL.lm, clean ? 3 : 3.8);
    const gm = lmGeom(d.key);
    if (d.type === 'circle' && gm) { ring(gm.c, gm.r, bad ? '#ff4040' : COL.lm, clean ? 1 : 1.3, clean ? [4, 4] : undefined); if (!clean) cross(gm.c, COL.lm, 5); }
    if (d.type === 'line' && gm && (!clean || bad)) line(gm.a, gm.b, bad ? '#ff4040' : COL.lm, 1.4);
    if (ui && showLabels && (d.type === 'point' || gm)) label(d.type === 'circle' && gm ? gm.c : L[0], d.short || d.label, bad ? '#ff4040' : COL.lm);
  }
  for (const r of S.rulers) { for (const p of r) dot(p, COL.ruler, 3); if (r.length === 2) { line(r[0], r[1], COL.ruler, 1.2); if (k) label(G.mid(r[0], r[1]), `${f1(G.dist(r[0], r[1]) * k)} mm`, COL.ruler); } }
  if (ui && S.arm && S.arm.pts) for (const p of S.arm.pts) dot(p, '#fff', 3.5);
  for (const l of later) drawLabel(...l);
}
// labels only when 10 mm is at least 15 screen pixels: at fit zoom on a long-leg film they pile up at the knee
const smallOk = () => !mm() || 10 / mm() * S.view.k >= 15;
function rotHandle(t) { const up = G.rot(G.P(0, -1), t.angle), L = 70 / S.view.k; return G.add(G.P(t.x, t.y), G.mul(up, L)); }

// ---------- pointer ----------
function hit(sp) {
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
  if (S.cal && S.cal.line) for (const [i, p] of S.cal.line.entries()) if (near(p)) return { kind: 'calline', i };
  if (mm()) {
    const ip = toI(sp), order = Object.keys(S.tpl).sort((a, b) => (b === S.active) - (a === S.active));
    for (const slot of order) {
      const t = S.tpl[slot]; if (!t.on) continue;
      if (inside(t, ip)) return { kind: 'tpl', slot };
      const f = S.fam[t.fam];   // the cup's whole disc (its centre cross is the natural handle) and the stem's head
      if (f.kind === 'cup' && G.dist(ip, G.P(t.x, t.y)) * mm() <= sizeOf(t).outerDiameter / 2) return { kind: 'tpl', slot };
      if (f.kind === 'stem' && G.dist(ip, headAt(t)) * mm() <= +t.head / 2) return { kind: 'tpl', slot };
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
    if (S.arm && S.arm.kind === 'brush') { S.drag = { kind: 'brush', stroke: { pts: [ip], r: S.brush / S.view.k } }; draw(); return; }
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
    if (!d) { S.hover = ip; if (S.arm && S.arm.kind === 'brush') { setCursor('none'); draw(); return; } if (e.pointerType === 'touch') return; const h = hit(sp); setCursor(S.arm ? 'crosshair' : h ? (h.kind === 'rotate' ? 'alias' : 'move') : 'grab'); return; }
    if (d.kind === 'brush') { S.hover = ip; const q = d.stroke.pts[d.stroke.pts.length - 1]; if (G.dist(q, ip) >= d.stroke.r / 3) d.stroke.pts.push(ip); draw(); return; }
    if (d.kind === 'pan') { S.view.x = d.v.x + sp.x - d.sp.x; S.view.y = d.v.y + sp.y - d.sp.y; if (Math.abs(sp.x - d.sp.x) + Math.abs(sp.y - d.sp.y) > 2) S.fitted = false; }
    else if (d.kind === 'wl') { setWL(d.wl.c - (sp.y - d.sp.y) * d.wl.w / 400, d.wl.w * Math.exp((sp.x - d.sp.x) / 200)); syncWL(); return; }
    else if (d.kind === 'lm') { S.lm[d.key][d.i] = ip; if (e.pointerType === 'touch') S.loupe = ip; }
    else if (d.kind === 'ruler') { S.rulers[d.ri][d.i] = ip; if (e.pointerType === 'touch') S.loupe = ip; }
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
    if (S.drag && S.drag.kind === 'brush') { addCleanStroke(S.drag.stroke); S.drag = null; return; }
    if (S.drag && S.drag.kind === 'lm') { const key = S.drag.key; if (/^canal[RL]$/.test(key)) S.notes[key] = 'Moved by hand.'; panel(); }
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
  } else if (a.kind === 'ball') {
    S.arm = null; prompt('');
    const e = dicomEstimate(), rMax = Math.min(600, Math.max(30, e ? 1.8 * (S.ballD / 2) / e.mmPerPx : 200));
    const b = G.detectBall(S.img, ip, rMax);
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
  if (S.arm && sheet && sheet.get() === 'full' && PHONE.matches) sheet.set(S.arm.kind === 'brush' ? 'half' : 'peek');
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
const neededHere = d => (S.wf === 'kneeAP' && (d.key === 'hip' || d.key === 'ankle') && S.img && S.img.rows > 2 * S.img.cols)
  // a cup to be mirrored needs the other hip's head (Sami, 11 Oct: it was optional, and the cup went to the native head)
  || (S.wf === 'hip' && S.scope !== 'one' && S.set.cupRule === 'mirror' && d.key === 'head' + other(S.side) && !S.tpl['cup' + other(S.side)]);
// Planning and saving wait for what the surgeon has to confirm: the ball found automatically, and which way the film
// faces when it was read from a marker or assumed (Astra, 11 Oct: the lower buttons skipped the guided steps).
function notReady() {
  if (!mm()) return 'Calibrate first.';
  if (S.cal.confirmed === false) return `Confirm the calibration first: is the pink circle the ${S.ballD} mm ball? (answer at the top of the panel)`;
  if ((S.orient.from === 'marker' || S.orient.from === 'assumed') && S.wf !== 'kneeLat') return 'Confirm which way the film faces first (answer at the top of the panel).';
  if (S.wf === 'hip' && (!S.sideSet || !S.scope)) return 'Choose the surgery side (and whether the film shows both hips or one) first (at the top of the panel).';   // Sol, 11 Oct
  if (S.wf === 'hip' && S.scope === 'both' && !S.ref) return `Say first whether the ${SIDE[S.side]} hip can be planned from its own shape (at the top of the panel).`;
  return '';
}
// The guided order: calibrate -> prepare (surgery side, reference hip, clean the film) -> landmarks (each armed in turn) ->
// implants (placed automatically when the landmarks are done, if the surgeon wants that) -> measurements and save. id:
// the step (STEPS), shared by the step strip, the panel's segments and the step's button.
function stepNow() {
  if (!S.cal || S.cal.method === 'dicom') return { id: 'cal', text: 'Calibrate: click "Find ball" and click the ball, or mark a known length.' };
  if (S.cal.confirmed === false) return { id: 'cal', confirmBall: true, text: `Is the pink circle the ${S.ballD} mm calibration ball?${S.auto.balls.length > 1 ? ` (${S.auto.balls.length} round objects were found; this is the most ball-like.)` : ''}` };
  if ((S.orient.from === 'marker' || S.orient.from === 'assumed') && S.wf !== 'kneeLat') return { id: 'cal', confirmOrient: true, text: S.orient.from === 'marker' ? `${cap(S.orient.note)}. Is that right?` : `${S.orient.note ? cap(S.orient.note) + '. ' : 'The image does not say which way it faces. '}Is the patient's right on the image left (a standard film)?` };
  if (S.wf === 'hip' && (!S.sideSet || !S.scope)) return { id: 'prep', side: true, text: S.scope ? 'Which hip is the surgery side?' : 'Which hip is the surgery side, and does the film show both hips or one?' };
  if (S.wf === 'hip' && S.scope === 'both' && !S.ref) return { id: 'prep', ref: true, text: `Is the ${SIDE[S.side]} hip fit to template on? Not if it is fractured, shortened, externally rotated or destroyed: then template the normal ${SIDE[other(S.side)]} side and copy it across (sizes, neck cut, offset).` };
  if (!S.prepDone) return { id: 'prep', prep: true, text: 'Prepare the film (optional): erase leads, clips and labels; crop to the part you need; level the pelvis if it is tilted. Then continue to the landmarks.' };
  const lm = lmDefs().find(d => (!d.optional || neededHere(d)) && (!lmGeom(d.key) || lmProblem(d.key)));
  if (lm) return { id: 'lm', lm, text: `Mark: ${lm.label}` };
  if (S.wf === 'kneeAP' && !kneeAxes().tma) { const t = WF.kneeAP.lm.find(d => d.key === 'tibShaft'); return { id: 'lm', lm: t, text: `Mark: ${t.label}` }; }
  if (!opSlots().every(sl => S.tpl[sl])) return { id: 'tpl', text: S.wf === 'hip' ? 'Landmarks done: place the implants (Auto template), then fine-tune.' : 'Landmarks done: place the components (Auto template), then fine-tune.' };
  return { id: 'save', text: 'Check the plan, adjust the implants if needed, then save the picture or DICOM.' };
}
function nextStep() {
  const st = stepNow();
  if (st.confirmBall || st.confirmOrient) { S.arm = null; prompt(st.text + ' Answer in the panel.'); panel(); draw(); return; }
  if (st.side) { prompt('Which hip is the surgery side? Choose in the panel.'); panel(); draw(); return; }
  if (st.ref) { S.arm = null; prompt(`Is the ${SIDE[S.side]} hip fit to plan from? Answer in the panel.`); panel(); draw(); return; }
  if (st.prep) { S.arm = null; prompt('Prepare the film if needed (erase, crop, level), then press "Continue to landmarks".'); panel(); draw(); return; }
  if (st.id === 'lm') {
    const was = S.cal && S.cal.method !== 'dicom' && !S.lm[st.lm.key] && Object.keys(S.lm).length === 0;
    armLm(st.lm.key);
    if (was) prompt(`Calibrated (${S.cal.method === 'ball' ? S.ballD + ' mm ball' : S.cal.lengthMm + ' mm line'}, ${S.cal.mmPerPx.toFixed(4)} mm per pixel). Next: ${st.lm.label}: ${st.lm.hint || 'click it'}. Esc to stop.`);
  } else if (st.id === 'tpl' && S.set.autoTemplate && Object.keys(S.tpl).length === 0 && !hiddenHere()) autoTemplate();   // never over implants kept aside
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
  const ballOpen = S.cal && S.cal.confirmed === false, oriOpen = (S.orient.from === 'marker' || S.orient.from === 'assumed') && S.wf !== 'kneeLat';
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
  const t = S.tpl[slot], f = S.fam[t.fam];
  if (f.kind === 'stem') changeStem(t, () => { t.size = size; }); else t.size = size;
  t.userSize = true;
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
  const cup = S.tpl['cup' + side], stem = S.tpl['stem' + side]; if (!cup || !stem || stem.userHead) return;
  if (makerOf(S.fam[cup.fam]) !== makerOf(S.fam[stem.fam])) return;
  const cs = sizeOf(cup), ch = cupHeads(cup); if (!cs) return;
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
const linerSub = (cup, stem) => { const L = cup && stem && linersFor(cup, stem.head); return L ? `liner for this head: ${L.join(', ')}; ` : ''; };
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
  const mats = linersFor(cup, stem.head);
  if (mats) R.push(['Liner for this head', `${mats.join(', ')} (cup ${cs.size}, ${headMm(stem.head)} head)`]);
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
  if (S.cal.method === 'dicom') return ['Machine estimate only', 'warn'];
  if (S.cal.method === 'ball' && S.cal.confirmed === false) return ['Ball found: confirm it', 'warn'];
  const what = S.cal.method === 'ball' ? `${S.ballD} mm ball` : `${S.cal.lengthMm} mm line`;
  if (S.cal.method === 'ball' && S.cal.warnings && S.cal.warnings.length) return [`Calibrated: ${what}, check it`, 'warn'];
  return [`Calibrated: ${what}`, ''];
}
// Who, which side and the scale: always at the top of the panel, also with every segment closed and on a phone's
// smallest panel (Astra, 13 Oct). The patient's name and record number as in the hospital; none in sharing mode.
function ctxBox() {
  const m = S.img.meta || {}, [ct, cc] = calStatus();
  const who = S.set.share ? 'Sharing mode: no patient details' : [patientName(), recordNo() ? 'MRN ' + recordNo() : ''].filter(Boolean).join(' · ') || 'No patient details in this image';
  const side = S.wf === 'hip' ? (S.sideSet ? `${cap(SIDE[S.side])} hip${refOther() ? `, planned from the ${SIDE[other(S.side)]}` : ''}` : 'Surgery side not chosen') : `${cap(SIDE[S.side])} knee`;
  return el(`<div class="ctx" id="ctx"><span class="who">${esc(who)}</span><span class="side">${esc(side)}</span><span class="cal ${cc}">${esc(ct)}${mm() && S.cal.method !== 'dicom' ? `, ${S.cal.mmPerPx.toFixed(4)} mm/px` : ''}</span></div>`);
}
// what each closed segment says
const shortSlot = sl => (SLOT[sl].side ? `${SLOT[sl].label} ${SLOT[sl].side}` : { femur: 'Femur', tibia: 'Tibia' }[sl] || SLOT[sl].label);
const shortFam = f => f.label.replace(/^Addis Ortho\s+/, '').replace(/^(cementless cup|total knee .*)$/, '');
function segSummaries(R) {
  const out = {};
  { const [t, c] = calStatus(); out.cal = [mm() && S.cal.method !== 'dicom' && S.cal.confirmed !== false ? `${S.cal.method === 'ball' ? S.ballD + ' mm ball' : S.cal.lengthMm + ' mm line'}, ${S.cal.mmPerPx.toFixed(4)} mm/px` : t, c];
    if (!c && (S.orient.from === 'marker' || S.orient.from === 'assumed') && S.wf !== 'kneeLat') out.cal = [out.cal[0] + '; confirm which way the film faces', 'warn']; }
  const p = [], nS = S.clean.strokes.length;
  if (S.wf === 'hip' && refOther()) p.push(`planned from the ${SIDE[other(S.side)]} hip`);
  if (nS) p.push(`${nS} area${nS > 1 ? 's' : ''} erased`);
  if (S.clean.crop) p.push('cropped');
  if (Math.abs(ROT()) > 0.0005) p.push(`turned ${f1(ROT() * 180 / Math.PI)}°`);
  if (S.masks && S.masks.length) p.push(`${S.masks.length} blacked out`);
  out.prep = [p.join(', ') || (S.prepDone ? 'nothing changed' : 'optional'), ''];
  const need = lmDefs().filter(d => !d.optional || neededHere(d)), ok = need.filter(d => lmGeom(d.key) && !lmProblem(d.key)).length, bad = lmDefs().filter(d => lmProblem(d.key) || lmBroken(d.key)).length;
  out.lm = [`${ok}/${need.length} marked${bad ? `, ${bad} to place again` : ''}`, bad ? 'bad' : ''];
  const imp = WF[S.wf].slots.filter(sl => S.tpl[sl]).map(sl => { const t = S.tpl[sl], f = S.fam[t.fam], fn = shortFam(f); return `${shortSlot(sl)} ${fn ? fn + ' ' : ''}${t.size}${f.kind === 'stem' ? `, head ${t.head}` : ''}${t.on ? '' : ' (hidden)'}`; });
  const kept = Object.keys((S.hidden || {})[S.wf] || {}).length;
  out.tpl = [imp.join(' · ') + (kept ? `${imp.length ? ' · ' : ''}${kept} kept aside` : '') || (mm() ? 'none placed' : 'calibrate first'), ''];
  const done = opSlots().length > 0 && opSlots().every(sl => S.tpl[sl] && S.tpl[sl].on), g = l => (R.find(r => r[0] === l) || [])[1];
  const nWarn = R.filter(r => r.length > 1 && r[2] === 'warn').length, nBad = R.filter(r => r.length > 1 && r[2] === 'bad').length;
  const sv = done ? ['recommendation ready', g('Leg length after') ? `leg length after ${g('Leg length after')}` : ''] : ['recommendation when the implants are placed'];
  if (nBad + nWarn) sv.push(`${nBad + nWarn} to check`);
  if (S.sent) sv.push(VIEWER ? 'saved in the viewer' : 'sent to PACS');
  out.save = [sv.filter(Boolean).join(', '), nBad ? 'bad' : nWarn ? 'warn' : ''];
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
  s.textContent = `${ci + 1}/${STEPS.length} ${STEPS[ci].strip}${lmName}`; s.title = st.text || '';
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
  const nx = el(`<div class="next"><span>${esc(S.auto.busy ? 'Looking for the calibration ball and the R/L marker…' : st.text)}</span></div>`);
  if (st.side) {
    const pick = (sd, sc) => () => { S.side = sd; S.sideSet = true; if (sc) S.scope = sc; $('side').value = sd; refreshFlips(); save(); nextStep(); };
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
  if (cur === 'tpl' && !S.auto.busy) { const cc = choiceCard('next', recNow); if (cc) nx.appendChild(cc); }   // under the step's button, so it stays in view
  add(nx);
  // warnings that must not hide in a closed segment: the serious results (kept up to date while dragging, by drawNow);
  // screen readers hear them from #srStatus, the one live status outside the panel (round 11 review)
  const wb = el('<div class="badbox" id="warns"></div>'); add(wb);
  setHTML('warns', R.filter(r => r.length > 1 && r[2] === 'bad').map(r => `<div>${esc(r[0])}: ${esc(r[1])}</div>`).join(''));
  const ws = el('<div class="warnsum" id="warnsum"></div>'); add(ws);   // the amber ones, at every height of the phone's panel (finding 8)
  ws.addEventListener('click', e => { if (e.target.closest('.wsbtn')) showChecks(); });
  setHTML('warnsum', warnSummary(R));
  if (S.restoreNote) { const wbx = el(`<div class="warnbox keep">${esc(S.restoreNote)}</div>`); if (S.oldCupRule && S.wf === 'hip' && Object.keys(S.tpl).length) { const r_ = el('<div class="row"></div>'); r_.appendChild(btn(REPLAN_CUP, replanCupRule, 'primary', false, REPLAN_TIP)); wbx.appendChild(r_); } add(wbx); }   // keep: shown at the phone panel's smallest too
  const sum = segSummaries(R);

  // 1. calibration and orientation
  const s1 = seg('cal', cur, ...sum.cal);
  const a1 = n => s1.appendChild(n);
  if (S.cal && S.cal.method === 'ball' && S.cal.how === 'found automatically') {
    const box = el(`<div class="${S.cal.confirmed ? 'okbox' : 'warnbox'}">Ball found automatically${S.cal.confirmed ? ' and confirmed' : ': not yet confirmed (answer at the top)'}. The pink circle should sit on its rim. </div>`);
    const nAlt = S.auto.balls.length;
    if (nAlt > 1) box.appendChild(btn('Not the ball: next one', () => { S.auto.ballIdx = (S.auto.ballIdx + 1) % nAlt; setBall(S.auto.balls[S.auto.ballIdx], 'found automatically', true); nextStep(); }));
    box.appendChild(btn('Not the ball: click it', () => { S.arm = { kind: 'ball' }; prompt('Click in the middle of the calibration ball.'); panel(); }));
    a1(box);
  } else if (!S.auto.busy && S.set.autoFind && (!S.cal || S.cal.method === 'dicom') && S.auto.balls.length === 0 && S.img.data) a1(el('<p class="note">No ball was found automatically on this film: click "Find ball" and click it, or mark its rim by hand.</p>'));
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
  r2.appendChild(btn('Use machine estimate', () => { S.cal = { method: 'dicom', mmPerPx: est.mmPerPx, note: est.note }; afterCal(); }, '', !est));
  a1(r2);
  if (S.cal && S.cal.method === 'dicom') a1(el(`<div class="warnbox">Not calibrated to the joint: ${esc(S.cal.note)}. Sizes may be wrong by 10-20 %. Find the ball before planning.</div>`));
  if (!S.cal) a1(el('<div class="warnbox">No scale. Find the ball or mark a known length.</div>'));
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
    if (S.lm[d.key]) { const x = btn('✕', ev => { ev.stopPropagation(); delete S.lm[d.key]; S.lmSeq = (S.lmSeq || []).filter(k => k !== d.key); save(); panel(); draw(); }, 'x', false, 'Remove this landmark'); x.setAttribute('aria-label', `Remove ${d.label}`); li.appendChild(x); }
    ul.appendChild(li);
    const gd = GUIDE[d.g || d.key];
    if (armed && gd) ul.appendChild(el(`<li class="guide"><div>${gd.svg(d.key.slice(-1) === 'L' && S.wf === 'hip' ? 'L' : 'R', S.mirrored)}</div><p>${esc(gd.text)}</p></li>`));
    const cn = S.notes[d.key]; if (cn && /^canal[RL]$/.test(d.key) && done) ul.appendChild(el(`<li class="sub">${esc(cn)}</li>`));
  }
  s2.appendChild(ul);
  if (S.wf === 'kneeAP' && !lmGeom('hip')) {
    const rv = el(`<div class="row note">No hip on the film: assume femoral valgus <input type="number" step="0.5" min="0" max="12" value="${S.vca}">°</div>`);
    rv.querySelector('input').onchange = e => { const v = parseFloat(e.target.value); if (Number.isFinite(v) && v >= 0 && v <= 15) S.vca = v; else e.target.value = S.vca; save(); panel(); draw(); }; s2.appendChild(rv);
  }
  s2.appendChild(help(`<p>${esc(WF[S.wf].label)}. The next landmark is asked for in turn; click any line to mark it again, or press ◀ Back to go to the one before. Drag any yellow point to correct it.</p>`
    + (S.wf === 'hip' && refOther() ? `<p>The ${SIDE[S.side]} hip is planned from the ${SIDE[other(S.side)]} one: its head and canal are the reference, and its plan is mirrored about the pelvic midline (halfway between the teardrops). The ${SIDE[S.side]} canal is still needed to size the stem, and both lesser trochanters for leg length.</p>` : '')));
  add(s2);

  // 4. implants
  const s3 = seg('tpl', cur, ...sum.tpl);
  if (!mm()) s3.appendChild(el('<p class="note">Calibrate first: implants are drawn true size.</p>'));
  const ra0 = el('<div class="row"></div>');
  ra0.appendChild(btn('Auto template', autoTemplate, 'primary', !mm(), 'Place and size the implants by the rules; then fine-tune by hand'));
  if (S.wf === 'hip') { const H_ = hipFrame(), why = !mm() ? 'Calibrate first' : S.scope === 'one' ? 'Needs both hips on the film' : !H_ ? 'Mark both teardrops first' : ''; ra0.appendChild(btn(`Mirror ${SIDE[other(S.side)]} side onto ${SIDE[S.side]}`, () => mirrorOther(), '', !!why, why || `Plan the surgery side to match the ${SIDE[other(S.side)]} hip, reflected about the pelvic midline`)); }
  if (Object.keys(S.tpl).length) ra0.appendChild(btn('Remove implants', () => { S.tplAll[S.wf] = S.tpl = {}; S.active = null; S.mirror = null; save(); panel(); draw(); prompt('Implants removed; landmarks kept. Press Auto template to place them again.'); }, '', false, 'Keep the landmarks, start the implants again'));
  s3.appendChild(ra0);
  if (S.mirror && S.wf === 'hip') s3.appendChild(el(`<p class="note">Mirrored: ${esc(S.mirror.note)}.</p>`));
  if (cur === 'save') { const cc = choiceCard('seg', recNow); if (cc) s3.appendChild(cc); }
  s3.appendChild(settingsBox());
  const groups = S.wf === 'hip' ? [[`Surgery side: ${SIDE[S.side]}`, ['cup' + S.side, 'stem' + S.side]], [`Other side: ${SIDE[other(S.side)]} (${refOther() ? 'the reference plan' : 'for comparison and mirroring'})`, ['cup' + other(S.side), 'stem' + other(S.side)]]] : [['', WF[S.wf].slots]];
  for (const [title, slots] of groups) {
    if (title) s3.appendChild(el(`<h3>${esc(title)}</h3>`));
    for (const slot of slots) s3.appendChild(templateBox(slot));
  }
  s3.appendChild(help('<p>Drag an implant to move it, the white handle to turn it; the bar above the panel works on the selected one. Keys, with the X-ray selected: <kbd>↑</kbd>/<kbd>↓</kbd> size, <kbd>Shift</kbd>+arrows move 0.5 mm (<kbd>Shift</kbd>+<kbd>Alt</kbd> 2 mm), <kbd>,</kbd> <kbd>.</kbd> turn 0.5°, <kbd>F</kbd> fit, <kbd>Ctrl</kbd>/<kbd>⌘</kbd>+<kbd>Z</kbd> undo; right-drag: brightness and contrast.</p>', 'Moving implants by hand'));
  add(s3);

  // 5. measurements and save (the recommendation at its top)
  const s5 = seg('save', cur, ...sum.save);
  const rc = recommendation(st); if (rc) s5.appendChild(rc);
  s5.appendChild(el('<h3>Measurements</h3>')); s5.appendChild(el('<div id="res"></div>'));
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
  if (STANDALONE) ry.appendChild(btn('Forget plans on this computer', () => { if (!confirm('Remove every templating plan kept in this browser?')) return; if (keepT) { clearTimeout(keepT); keepT = 0; } try { for (const k of Object.keys(localStorage)) if (k.startsWith('aoc-tpl:')) localStorage.removeItem(k); } catch (e) { /* nothing kept */ } HIST.length = 0; syncHeader(); prompt('Plans kept in this browser removed (the one on screen stays until you close the page).'); }, '', false, 'Plans are kept in this browser so a film can be reopened; this removes them'));
  ry.appendChild(btn('Start again', () => { if (confirm('Remove all landmarks, implants and the calibration on this X-ray? (◀ Back and Undo go back one step without this.)')) { S.lm = {}; S.lmSeq = []; S.ref = null; S.tplAll = { hip: {}, kneeAP: {}, kneeLat: {} }; S.tpl = S.tplAll[S.wf]; S.hidden = {}; S.hiddenGeo = {}; S.restoreNote = S.restoreNote.replace(/ ?This plan also had [^:]*: not shown\./, '').trim(); S.rulers = []; S.active = null; S.canal = {}; S.notes = {}; S.mirror = null; defaultCalibration(); save(); panel(); draw(); } }));
  s5.appendChild(ry);
  if (S.set.share) s5.appendChild(el(`<div class="warnbox">Sharing mode: the name, record number and dates are left out of the file names, the picture and the patient list, and DICOM and plan data are not saved. A project file still holds the original X-ray and its details: keep it inside the team. Text burned into the film itself (a name, a number, a date) is not removed: cover it with "Black out an area" (2 · Prepare) and look at the picture before sending it.</div>`));
  s5.appendChild(help(`<p>Files are named ${esc(baseName())}${S.set.share ? '' : ' (tick "sharing outside the hospital" in Auto plan settings before sending a plan outside)'}. The project file (.aotp) holds the original X-ray and the whole plan: open it with Open to go on where you left off${STANDALONE ? '; it is also kept in the templated-patients list (Open), for you and the colleagues you share it with' : ''}. It is confidential, like the X-ray itself. The picture (PNG) is the copy for sharing. The DICOM copy carries the patient's details from the X-ray, for the archive. Plan data is for reopening a plan here; it is not a document to read. A plan is a draft for the surgeon.</p>`, 'About the files'));
  add(s5);
  PJ.sync();
  setHTML('res', resTable(R));
  syncWL();
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
  const opts = t ? sizesShown(S.fam[t.fam], t) : [], key = t ? S.active + '|' + t.fam + '|' + opts.map(z => z.size).join(',') : '';
  if (sz._key !== key) { sz.textContent = ''; for (const z of opts) sz.add(new Option(z.label || z.size, z.size)); sz._key = key; }
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
  $('wf').value = S.wf; $('side').value = S.side; ensureSlots(); renderImage(); fit();
  HIST.length = 0; save(); markSaved(); panel(); draw();
}

// What to use, once the page has what it needs (Sami, 11 Oct: "after calibration, recommend the implant to be used
// after having all the information you need"). Before that: what is still missing. The sizes are the plan as it stands
// (placed by the rules, then perhaps changed by hand), with the next size either side to have ready, and the reasons.
function recommendation(st) {
  if (st.id === 'cal' || S.auto.busy) return null;
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
    const cupRow = byH => ['Cup', `${S.fam[cup.fam].label} <b>${cs.size}</b> (outside ${cs.catalogueOuter || cs.outerDiameter} mm${cs.liner ? `, liner ${linerTxt(S.fam[cup.fam], cs.liner)}` : ''})${byH}`, `have ready ${near(cup, 1).join(' · ')}`];
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
    rows.push(['Head', `<b>${stem.head} mm</b> ${S.lib.heads[stem.headKind].label}, neck <b>${neckLabel(stem, stem.neck)}</b>`, `${linerSub(cup, stem)}trial the necks either side`]);
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
    caution.push(...unconfirmed([cup, stem]));
    for (const r of R) if (r.length > 1 && (r[2] === 'bad' || r[2] === 'warn') && !/^Cup size check$/.test(r[0])) caution.push(`${r[0]}: ${r[1]}`);   // every warning in the results (Astra, 11 Oct)
    }
  } else {
    for (const sl of opSlots()) { const t = S.tpl[sl], z = sizeOf(t); rows.push([SLOT[sl].label, `${S.fam[t.fam].label} <b>${z.size}</b>${sl === 'tibia' ? `, ${t.thick} mm total` : ''}${byHand(t)}`, `have ready ${near(t, 1).join(' · ')}`]); }
    why.push(S.wf === 'kneeLat' ? 'femur sized from the anterior cortex to the back of the condyles, tray from the plateau depth' : 'cuts square to the mechanical axes; check the sizes on the lateral film');
    caution.push(...unconfirmed(opSlots().map(sl => S.tpl[sl])));
  }
  if (S.cal.method !== 'ball' && S.cal.method !== 'line') caution.push('not calibrated on the ball: sizes may be 10-20 % off');
  box.appendChild(el(`<div class="rec-h">Recommended${S.wf === 'hip' ? ` for the ${SIDE[S.side]} hip` : ''}</div>`));
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
  if (S.wf !== 'hip' || !mm() || !hasKind('stem')) return null;
  const sd = refOther() ? other(S.side) : S.side, cup = S.tpl['cup' + sd], stem = S.tpl['stem' + sd];
  const key = JSON.stringify([S.lm, mm(), S.side, S.scope, S.ref, PLAN_KEYS.map(k => S.set[k]), S.set.halfSizes, S.choice, cup ? [cup.fam, cup.size, cup.x, cup.y, cup.angle, cup.on] : null, stem ? [stem.fam, stem.headKind, stem.head] : null]);
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
  S.choice = { ...S.choice, [kind]: id };
  const slots = WF.hip.slots.filter(sl => SLOT[sl].kind === kind && S.tpl[sl]);
  if (!slots.length) { prompt(`${f.label} chosen: press Auto template to place it.`); panel(); return; }   // nothing placed yet
  for (const sl of slots) { const t = S.tpl[sl]; if (t.fam !== id) setFam(t, f); t.userFam = true; t.userSize = false; }
  autoTemplate({ replan: true });   // the plan placed again around it: a cup change re-runs the stem and the mirrored copy
}
// a plan saved before 14 Oct 2026 (Astra's review of the cup rule, finding 1): Auto template keeps a reference plan the
// surgeon made, so the new cup rule is applied by this action: the cup placed again by it, the stem and a mirrored cup
// after it; sizes and families set by hand are kept (as "Re-run with my choice")
const OLD_CUP_NOTE = 'This plan was saved before 14 Oct 2026 with the cup rule of that time (lowest rim on the teardrop line). Its rule is now native centre height, medial edge at the teardrop. The implants stay as saved until you press "Re-plan with the new cup rule": that places the cup again by the new rule and the stem (and a mirrored cup) after it, keeping sizes and families you set by hand. To keep the older rule, choose "Lowest rim on the teardrop line" in Auto plan settings.';
const REPLAN_CUP = 'Re-plan with the new cup rule', REPLAN_TIP = 'Place the cup again by native centre height, medial edge at the teardrop, and the stem (and a mirrored cup) after it; sizes and families you set by hand are kept';
function replanCupRule() { S.oldCupRule = false; S.restoreNote = S.restoreNote.replace(OLD_CUP_NOTE, '').trim(); autoTemplate({ replan: true }); }
function useRecommended() { S.choice = {}; autoTemplate({ fresh: true, replan: true }); }
function choiceCard(where, rec) {
  if (S.wf !== 'hip' || !mm()) return null;
  const sd = refOther() ? other(S.side) : S.side, cups = famsFor('cup'), stems = famsFor('stem');
  if (!cups.length && !stems.length && !PENDING.length) return null;
  const cupT = S.tpl['cup' + sd], stemT = S.tpl['stem' + sd], placed = !!(cupT || stemT);
  const box = el(`<div class="choice" id="choice-${where}"></div>`);
  box.appendChild(el(`<div class="ch-h">${placed ? 'Implants in this plan: tap another to change it' : 'Choose the implants, then Auto template'}${refOther() ? ` (planned on the ${SIDE[sd]})` : ''}</div>`));
  const group = (kind, title, fams, cur, best) => {
    const g = el(`<div class="ch-g" role="radiogroup" aria-label="${title} family"><div class="ch-t">${title}</div></div>`);
    for (const f of fams) {
      const b = el(`<button type="button" class="ch-o" role="radio" aria-checked="${f.id === cur}" data-fam="${esc(f.id)}"><span class="ch-l">${esc(f.label)}</span>${best === f.id ? '<span class="best">Best geometric match (check it)</span>' : ''}</button>`);
      b.onclick = () => pickFamily(kind, f.id);
      g.appendChild(b);
    }
    for (const p of pendingOf(kind)) g.appendChild(el(`<div class="ch-o pend" aria-disabled="true" data-range="${esc(p.key)}">${esc(pendingText(p))}</div>`));
    if (!fams.length && !pendingOf(kind).length) g.appendChild(el(`<p class="note">No ${title.toLowerCase()} range is open to your account.</p>`));
    return g;
  };
  box.appendChild(group('cup', 'Cup', cups, (cupT && cupT.fam) || ((chosenFam('cup') || cups[0] || {}).id), null));
  box.appendChild(group('stem', 'Stem', stems, (stemT && stemT.fam) || ((chosenFam('stem') || stems[0] || {}).id), rec && rec.fam));
  const hand = placed ? handChoices() : [], row = el('<div class="row"></div>');
  if (cups.length || stems.length) row.appendChild(btn('Use recommended', useRecommended, 'primary', false, 'The rules choose the families and sizes: the stem version whose head centre comes nearest the cup centre, with the canal fill checked'));
  if (S.oldCupRule && placed) row.appendChild(btn(REPLAN_CUP, replanCupRule, '', false, REPLAN_TIP));
  if (hand.length) row.appendChild(btn('Re-run with my choice', () => autoTemplate({ replan: true }), '', false, `Place the plan again by the rules, keeping what you set by hand: ${hand.join('; ')}`));
  box.appendChild(row);
  if (hand.length) box.appendChild(el(`<p class="note">Kept by Auto template, as you set them: ${esc(hand.join('; '))}.</p>`));
  box.appendChild(el(`<p class="note">${rec ? `${esc(rec.why)} ` : ''}The best geometric match is worked out on this film from the canal and the cup position; it does not rank the cups or judge clinical suitability. Check it.</p>`));
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
  const r8 = row(`<label><input type="checkbox"> sharing outside the hospital: leave out the patient's name, record number and dates</label>`);
  r8.querySelector('input').checked = s.share; r8.querySelector('input').onchange = e => { s.share = e.target.checked; saveSet(); panel(); };
  const r7 = row(`<label><input type="checkbox"> place the implants when the landmarks are done</label>`);
  r7.querySelector('input').checked = s.autoTemplate; r7.querySelector('input').onchange = e => { s.autoTemplate = e.target.checked; saveSet(); };
  d.appendChild(el('<p class="note">Rules: the cup is sized from the acetabular roof edge when marked (its rim reaches the edge at the planned angle), else head diameter + 4 mm outside; a size beyond the range is said, not replaced. Cup centre (every surgical plan, also the normal side when it is the reference), by default native centre height, medial edge at the teardrop: the cup centre at the height of the native head centre (for a deformed side the other hip\'s, mirrored), moved medially until the cup\'s most medial point is the set distance lateral of the teardrop, never more than 10 mm medial of the native centre and never lateral of it; the older rule (lowest rim on the teardrop line, just lateral to the teardrop) can be chosen. Either is a starting position to judge against the medial wall and the roof; a cup centre over 3 mm above the native centre or a lowest rim over 5 mm off the teardrop line is flagged. The stem lies along the canal with its head at the cup centre\'s height plus the leg length to gain; canal fill sets the size: each version of the stem open to you (BE2 133S, 133H, 127S, 127H, or a maker\'s standard/high pair) gets its largest size that stays inside the inner cortex (0.5 mm allowed) wherever the cortex edges were found; then of those versions and their necks the one whose head centre comes nearest the cup centre horizontally is chosen, and high offset not when it would restore the combined offset more than 5 mm over the native hip; when nothing qualifies the page says so and leaves the stem to you. The older rule (restore the native femoral offset) takes the largest size inside the inner cortex and the neck nearest the offset. A size or family you set by hand is kept, also by Mirror and when the cup changes (the stem and the mirrored plan follow a cup change). Every result can be changed by hand.</p>'));
  return d;
}
function templateBox(slot) {
  const t = S.tpl[slot], side = slotSide(slot), op = !SLOT[slot].side || side === S.side;
  if (refOther() && slot === 'stem' + S.side) {   // the fractured femur: no outline, the normal side's stem instead
    const sN = S.tpl['stem' + other(side)], cut = sN && sN.on ? neckCutAboveLT(sN, other(side)) : null;
    return el(`<div class="tpl" data-slot="${slot}"><div class="name">${esc(slotName(slot))}</div><p class="note">Not templated on this femur (fractured, shortened or rotated). Use the ${SIDE[other(side)]} stem${sN ? `: <b>${esc(S.fam[sN.fam].label)} ${esc(sN.size)}</b>, ${esc(sN.head)} mm head, neck ${esc(neckLabel(sN, sN.neck))}` : ' (plan it on that side)'}${cut !== null ? `; neck cut <b>${f1(cut)} mm above the lesser trochanter</b>` : ''}.</p></div>`);
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
  const head = el(`<div class="row"><span class="name">${esc(slotName(slot))}</span></div>`);
  const fs = el('<select></select>'), grp = {};   // grouped by implant range (library "ranges")
  for (const ff of famsFor(SLOT[slot].kind)) {
    const rg = ff.range && S.lib.ranges && S.lib.ranges[ff.range];
    if (rg && !grp[ff.range]) { grp[ff.range] = document.createElement('optgroup'); grp[ff.range].label = rg.label; fs.appendChild(grp[ff.range]); }
    (rg ? grp[ff.range] : fs).appendChild(new Option(ff.label, ff.id));
  }
  const pk = pendingOf(SLOT[slot].kind);   // ranges without template files: greyed, never selectable (design D)
  if (pk.length) { const og = document.createElement('optgroup'); og.label = 'Not yet available'; for (const p of pk) { const op = new Option(pendingText(p), ''); op.disabled = true; og.appendChild(op); } fs.appendChild(og); }
  fs.value = t.fam;
  fs.onchange = e => {
    const nf = S.fam[e.target.value]; if (!nf) { fs.value = t.fam; return; }
    t.userFam = true;   // chosen by hand: Auto template keeps it (amendment 4)
    if (nf.kind === 'stem') {   // nearest canal fill in the new family, head kept where it was (in a head system it takes)
      const w = widthAt(sizeOf(t), 60), shown = sizesShown(nf), pick = shown.reduce((b, z) => Math.abs(widthAt(z, 60) - w) < Math.abs(widthAt(b, 60) - w) ? z : b);
      changeStem(t, () => { t.fam = nf.id; t.size = Number.isFinite(w) ? pick.size : shown[Math.floor(shown.length / 2)].size; if (!headKindsOf(nf).includes(t.headKind)) setHead(t, nf.sheetHead.kind, t.head); });
      // then the planning rule for this stem within the family chosen (Astra's third page review): sized by canal fill,
      // neck and head height by the stem rule, its explanation written afresh; the nearest width above is only the start
      t.userSize = false;
    } else {   // a cup: the stem of its side and the mirrored plan follow it (finding 3)
      t.fam = nf.id; if (!nf.sizes.find(z => z.size === t.size)) t.size = nf.sizes[Math.floor(nf.sizes.length / 2)].size;
    }
    replanAfter(slot);
    save(); panel(); draw();
  };
  head.appendChild(fs); box.appendChild(head);
  const rs = el('<div class="row"></div>'); let headRow = null;
  rs.appendChild(btn('−', ev => { ev.stopPropagation(); stepSize(slot, -1); save(); panel(); draw(); }));
  const ss = el('<select></select>'); for (const s of sizesShown(f, t)) ss.add(new Option(s.label || s.size, s.size)); ss.value = t.size;
  ss.onchange = e => { setSize(slot, e.target.value); save(); panel(); draw(); }; rs.appendChild(ss);
  rs.appendChild(btn('+', ev => { ev.stopPropagation(); stepSize(slot, 1); save(); panel(); draw(); }));
  if (f.kind === 'stem') {
    if (!headKindsOf(f).includes(t.headKind) || headSet(t)[t.neck] === undefined) setHead(t, t.headKind || f.sheetHead.kind, t.head || f.sheetHead.diameter);
    const hk = el('<select title="Head material"></select>'); for (const k of headKindsOf(f)) hk.add(new Option(S.lib.heads[k].label, k)); hk.value = t.headKind;
    hk.onchange = e => { changeStem(t, () => setHead(t, e.target.value, t.head)); t.userHead = true; save(); panel(); draw(); };
    const hs = el('<select title="Head diameter"></select>'); for (const h of Object.keys(S.lib.heads[t.headKind].sizes)) hs.add(new Option(`${h} mm head`, h)); hs.value = t.head;
    hs.onchange = e => { setHead(t, t.headKind, e.target.value); t.userHead = true; save(); panel(); draw(); };
    const ns = el('<select title="Neck length"></select>'); for (const [n] of neckOpts(t)) ns.add(new Option(`neck ${neckLabel(t, n)}`, n)); ns.value = t.neck;
    ns.onchange = e => { t.neck = e.target.value; save(); panel(); draw(); };
    headRow = el('<div class="row"></div>'); headRow.append(hk, hs, ns);
  }
  if (f.kind === 'tka-tibia') { const th = el('<select></select>'); for (const v of f.thicknesses) th.add(new Option(`${v} mm total (tray + insert)`, v)); th.value = t.thick; th.onchange = e => { t.thick = +e.target.value; save(); panel(); draw(); }; rs.appendChild(th); }
  box.appendChild(rs); if (headRow) box.appendChild(headRow);
  if (COARSE && S.active === slot && mm()) {   // on a phone: nudge and turn by buttons (no arrow keys)
    const nudge = (dx, dy, da) => ev => { ev.stopPropagation(); const v = G.rot(G.P(dx * pxOf(0.5), dy * pxOf(0.5)), -ROT()); t.x += v.x; t.y += v.y; t.angle += G.rad(da * 0.5); save(); draw(); };
    const np = el('<div class="row nudge"></div>');
    np.append(btn('◀', nudge(-1, 0, 0)), btn('▲', nudge(0, -1, 0)), btn('▼', nudge(0, 1, 0)), btn('▶', nudge(1, 0, 0)), btn('⟲', nudge(0, 0, -1)), btn('⟳', nudge(0, 0, 1)));
    box.appendChild(np);
  }
  const ra = el('<div class="row"></div>');
  if (f.kind === 'stem') {
    const c = lmGeom('canal' + side), why = !c ? `mark the ${SIDE[side]} femoral canal first` : lmProblem('canal' + side) ? 'the canal line runs across the femur: place it again' : '';
    ra.appendChild(btn('Align to canal', () => { const H0 = headAt(t); if (alignStem(t, side)) setHeadLevel(t, side, G.dot(H0, vertical())); save(); panel(); draw(); }, '', !!why, why || 'Turn the stem onto the canal line and centre it, head height kept'));
    if (refOther() && side === S.side) ra.appendChild(btn(`Copy the ${SIDE[other(side)]} stem`, () => mirrorOther(), '', !S.tpl['stem' + other(side)], `Same stem, head and neck as the ${SIDE[other(side)]} plan, neck cut at the same height above the lesser trochanter (this film is not used for sizing)`));
    else ra.appendChild(btn('Auto size and place', () => { t.userSize = false; matchHeadToCup(side); planStem(side, { keepFam: !!t.userFam }, stemTarget(side)); save(); panel(); draw(); }, '', !c || !!why, S.set.stemRule === 'offset' ? 'Size by canal fill and restore the femoral offset with the neck' : 'Size by canal fill, the head centre on the cup centre'));
  } else ra.appendChild(btn('Auto place', () => { autoPlace(slot); save(); panel(); draw(); }));
  if (!(viewOf() === 'LAT' && f.kind.startsWith('tka'))) ra.appendChild(btn('Mirror outline', () => { t.flip = !t.flip; t.userFlip = true; save(); draw(); }, '', false, 'Only for an unusual film: the outline normally faces the right way by itself'));
  ra.appendChild(btn(t.on ? 'Hide' : 'Show', () => { t.on = !t.on; save(); panel(); draw(); }));
  ra.appendChild(btn('Remove', () => { delete S.tpl[slot]; if (S.active === slot) S.active = null; save(); panel(); draw(); }));
  box.appendChild(ra);
  const sz = sizeOf(t);
  if (f.kind === 'stem' && S.notes['fit' + side] && S.notes['fit' + side].none) box.appendChild(el(`<div class="warnbox">Size not chosen automatically: ${esc(S.notes['fit' + side].why)}. Choose it by eye against the canal.</div>`));
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
  if (f.kind === 'cup' && S.notes['cupRule' + side]) box.appendChild(el(`<p class="note">Placed by ${{ anatomic: 'native centre height, medial edge at the teardrop', teardrop: 'the teardrop technique (lowest rim on the teardrop line)', head: 'the native head centre', mirror: 'the other hip, mirrored' }[S.notes['cupRule' + side]]}${S.notes['cupAnat' + side] ? ` (${esc(anatText(S.notes['cupAnat' + side], side))})` : ''}${S.notes['cupSize' + side] ? `; size from ${esc(S.notes['cupSize' + side])}` : ''}.</p>`));
  box.appendChild(el(`<p class="note">${esc(f.note || '')}${f.verified ? '' : f.catalogueChecked ? ` <b>${esc(CAT_CHECKED)}.</b>` : ' <b>Not yet confirmed by the manufacturer.</b>'}</p>`));
  if (f.kind === 'cup') box.appendChild(el(`<p class="note">Size ${sz.size}: outside ${sz.catalogueOuter || '?'} mm in the catalogue, ${sz.outerDiameter} mm on the ${drawnFrom(f)}${sz.catalogueOuter && Math.abs(sz.outerDiameter - sz.catalogueOuter) > 0.5 ? ' (<b>the two differ: to be confirmed</b>)' : ''}.${sz.liner ? ` Liner ${linerTxt(f, sz.liner)}${sz.maxHead ? `, head up to ${headMm(sz.maxHead)}` : ''}.` : ''}${cupHeads(t) ? ` Heads ${headsMm(cupHeads(t))}.` : ''}${headsAcrossLiners(t) ? ` ${HEADS_ACROSS[0]}: ${HEADS_ACROSS[1]}.` : ''}</p>`));
  if (f.heldBack && f.heldBack.length) box.appendChild(el(`<p class="note">Not offered: ${f.heldBack.map(h => `${h.size} (${esc(h.why)})`).join('; ')}.</p>`));
  return box;
}

// ---------- export ----------
// The patient's name as read in the clinic: DICOM "FAMILY^GIVEN^MIDDLE" becomes "GIVEN MIDDLE FAMILY", in the X-ray's
// own character set (UTF-8 when it says ISO_IR 192). A picture from disk has none: the name typed for it when its project
// was saved, if any (round 6).
function patientName() {
  const m = (S.img && S.img.meta) || {}, raw = String(m.PatientName || ''); if (!raw.trim()) return (S.patientIn && S.patientIn.name) || '';
  let txt = raw;
  if (/ISO_IR 192/.test(String(m.SpecificCharacterSet || ''))) { try { txt = new TextDecoder('utf-8').decode(Uint8Array.from(raw, c => c.charCodeAt(0) & 0xFF)); } catch (e) { txt = raw; } }
  const [fam = '', giv = '', mid = ''] = txt.split('=')[0].split('^');
  return [giv, mid, fam].map(x => x.trim()).filter(Boolean).join(' ');
}
// The record number (MRN): the X-ray's Patient ID, or the one typed for a picture when its project was saved
function recordNo() { const m = (S.img && S.img.meta) || {}; return String(m.PatientID || '') || (S.patientIn && S.patientIn.recordNo) || ''; }
// File names: patient's name, record number (MRN), operated side and joint, date (Sami, 11 Oct 2026); in sharing mode
// none of the patient's details, only "shared", side, joint and date. A picture from disk without a record number uses the
// start of its content hash.
function baseName() {
  const m = (S.img && S.img.meta) || {}, d = new Date().toISOString().slice(0, 10);
  const id = String(recordNo() || m.AccessionNumber || ('local-' + ((S.src && S.src.hash) || '').slice(0, 8))).replace(/[^\w.-]+/g, '-');
  const joint = { hip: 'hip', kneeAP: 'knee-AP', kneeLat: 'knee-lateral' }[S.wf];
  if (S.set.share) return `shared_${SIDE[S.side].toUpperCase()}-${joint}_template_${d}`;
  const nm = patientName().replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 60);
  return `${nm ? nm + '_' : ''}${id}_${SIDE[S.side].toUpperCase()}-${joint}_template_${d}`;
}

// ---------- saved projects (round 6, 13 Oct 2026; projects.js has the file, the server and the list) ----------
// The project file is named like the pictures (name, record number, side and joint, date; sharing mode: none of the
// patient's details), as name_RECORD_RIGHT-hip_project_2026-10-13.aotp.
const projectFileName = () => baseName().replace(/_template_/, '_project_') + '.aotp';
const clip = (v, n) => String(v || '').replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, n);
// the patient's details for the project and the list: none in sharing mode (the file itself still carries the X-ray)
function patientNow() {
  if (S.set.share) return {};
  const o = {}, nm = clip(patientName(), 120), rn = clip(recordNo(), 40);
  if (nm) o.name = nm; if (rn) o.recordNo = rn;
  return o;
}
const patientKnown = () => !!(patientName() || recordNo() || (S.patientIn && S.patientIn.asked));
// the implants planned, for the list (family and size per place; the stem's head and the knee's insert as their own
// lines, for the planned-versus-used comparison later). Those kept aside (a range not open here) are listed too.
function implantsNow() {
  const out = [], all = { ...((S.hidden || {})[S.wf] || {}), ...S.tpl };
  for (const sl of WF[S.wf].slots) {
    const t = all[sl]; if (!t || t.on === false) continue;
    const f = S.fam[t.fam];
    out.push({ slot: sl, label: clip(f ? f.label : t.fam, 120), size: clip(t.size, 32) });
    if (f && f.kind === 'stem' && t.head) { let nk = ''; try { nk = neckLabel(t, t.neck); } catch (e) { nk = String(t.neck || ''); } out.push({ slot: 'head' + sl.slice(-1), label: clip(`${(S.lib.heads[t.headKind] || {}).label || 'Femoral'} head, neck ${nk}`, 120), size: clip(t.head, 32) }); }
    if (f && f.kind === 'tka-tibia' && t.thick) out.push({ slot: 'insert', label: 'Tray and insert, total thickness', size: `${t.thick} mm` });
  }
  return out.slice(0, 24);
}
const metaNow = () => ({ patient: patientNow(), side: S.side, procedure: S.wf === 'hip' ? 'hip' : 'knee', implants: implantsNow() });
// project.json (contract 5): the one authoritative state (fullState) with what the file and the list need around it.
// savedBy stays empty: the page does not know who is signed in (the patient list records it from the sign-in).
function projectNow() {
  const m = S.img.meta || {}, o = S.orig, share = !!S.set.share, study = String(m.StudyInstanceUID || '');
  const state = fullState(); state.pendingSend = null;   // a send to PACS that got no answer belongs to this browser
  return { format: PJ.FORMAT, v: 1, savedAt: new Date().toISOString(), savedBy: null, app: 'Addis Ortho X-ray templating, round 6 (13 Oct 2026)' + (STANDALONE ? ', t.addisortho.com' : ''),
    libraryId: libIdNow(), patient: { ...patientNow(), side: S.side, procedure: S.wf === 'hip' ? 'hip' : 'knee', ...(!share && /^[0-9.]{1,64}$/.test(study) ? { studyUID: study } : {}) },
    image: { fileName: share ? 'image.' + PJ.MIME_EXT[o.mime] : clip(String(o.name || '').split(/[\\/]/).pop(), 120) || 'image.' + PJ.MIME_EXT[o.mime], mime: o.mime, sha256: o.sha256, size: o.bytes.length, width: S.img.cols, height: S.img.rows },
    state };
}
// what projects.js uses of the page
const PROJ = { S, STANDALONE, prompt: t => prompt(t), panel: () => panel(), projectNow, metaNow, fileName: projectFileName, loadProject, patientKnown, markSaved, isDirty, revision, base: () => histBase, stepNow: () => stepNow().id, applyState: st => applyFullState(st) };
function download(blob, name) { const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 5000); }
function exportJSON() {
  if (S.set.share) return;
  const m = S.img.meta || {};
  const doc = { what: 'Addis Ortho X-ray templating plan data (trial, draft for the surgeon). Reopen with "Open plan data" on the same X-ray.', accession: m.AccessionNumber || null, patientId: m.PatientID || null, studyDate: m.StudyDate || null,
    sopInstanceUID: m.SOPInstanceUID || null, fileSha256: (S.src && S.src.hash) || null, workflow: WF[S.wf].label, side: S.side,
    implantSource: implantSource(), results: results(), plan: plan() };
  download(new Blob([JSON.stringify(doc, null, 1)], { type: 'application/json' }), baseName() + '.json');
}
// what the plan's outlines come from: its implant ranges, their makers and how each was made. Addis Ortho: Chunli's own
// 1:1 sheets and 3D models; another maker's: fitted to that maker's printed template and scaled to true size, as its
// source says (J&J Pinnacle: a 120 % sheet scaled by its ruler), never called a 3D model
function implantSource() {
  const used = [...new Set(Object.values(S.tplAll).flatMap(o => Object.values(o || {})).map(t => (S.fam[t.fam] || {}).range).filter(k => k && S.lib.ranges && S.lib.ranges[k]))];
  const R = S.lib.ranges || {}, ao = used.filter(k => k.startsWith('ao-')), parts = [];
  if (ao.length) parts.push(`${ao.map(k => R[k].label).join(', ')} (made by ${R[ao[0]].maker}): outlines from the manufacturer's 1:1 sheets and 3D models`);
  for (const k of used.filter(k => !k.startsWith('ao-'))) parts.push(`${R[k].label} (made by ${R[k].maker}): outlines fitted to the maker's printed template and scaled to true size (${[...new Set(S.lib.families.filter(f => f.range === k).map(f => f.source).filter(Boolean))].join('; ')})`);
  return parts.length ? parts.join('; ') : 'Addis Ortho range; outlines from the manufacturer (Chunli) 1:1 sheets and 3D models';
}
async function openPlanFile(file) {
  try {
    const doc = JSON.parse(await file.text()), p = doc.plan || doc, m = S.img.meta || {};
    if (!p || p.v !== 1) throw new Error('not a templating plan data file');
    const same = (doc.sopInstanceUID && doc.sopInstanceUID === m.SOPInstanceUID) || (doc.fileSha256 && doc.fileSha256 === (S.src && S.src.hash));
    if (!same && !confirm('This plan data was saved on a different X-ray (or the X-ray cannot be matched). Landmarks would not fit this film. Open it anyway?')) return;
    applyPlan(JSON.parse(JSON.stringify(p))); S.canal = {};
    $('wf').value = S.wf; $('side').value = S.side; ensureSlots(); renderImage(); save(); panel(); draw();
    prompt('Plan data opened.');
  } catch (e) { prompt('Could not open that file: ' + (e.message || e)); }
}
// The saved picture: the film with the plan on the left, the report in its own column on the right (never over the
// anatomy), warnings in colour, a scale bar at the joint plane, and a note that a printout is not true size.
function exportCanvas(maxSide = 3600) {
  const cp = area(), rot = ROT(), c0 = imgC(), qs = corners(cp).map(p => rotP(p, rot, c0));
  const bx0 = Math.min(...qs.map(p => p.x)), by0 = Math.min(...qs.map(p => p.y)), cols = Math.max(...qs.map(p => p.x)) - bx0, rows = Math.max(...qs.map(p => p.y)) - by0;
  const scale = Math.min(1, maxSide / Math.max(cols, rows)), T = p => { const q = rotP(p, rot, c0); return G.P((q.x - bx0) * scale, (q.y - by0) * scale); };
  const W0 = Math.round(cols * scale), H0 = Math.round(rows * scale), lw = Math.max(1.5, Math.max(W0, H0) / 1100);
  const repW = Math.round(Math.max(420 * lw, W0 * 0.42)), pad = 14 * lw, fs = 12 * lw, lh = fs * 1.42;
  const m = S.img.meta || {}, R = results();
  const meas = document.createElement('canvas').getContext('2d'); meas.font = `${fs}px system-ui, sans-serif`;
  const wrap = (text, width) => { const out = []; let line = ''; for (const w of String(text).split(' ')) { const t = line ? line + ' ' + w : w; if (meas.measureText(t).width > width && line) { out.push(line); line = w; } else line = t; } if (line) out.push(line); return out; };
  const items = [['title', 'X-ray templating (trial)'], ['sub', 'Draft plan for the surgeon. Not for clinical decisions until validated.'],
    ['sub', S.set.share ? 'Shared copy: the patient\'s name, record number and dates are left out.' : `${patientName() ? patientName() + ' · ' : ''}MRN ${recordNo() || '-'} · order ${m.AccessionNumber || '-'} · ${fmtDate(m.StudyDate) || ''}`],
    ['sub', `${WF[S.wf].label} · surgery side ${SIDE[S.side]}`]];
  for (const r of R) items.push(r.length === 1 ? ['head', r[0]] : ['row', `${r[0]}: ${r[1]}`, r[2]]);
  for (const u of unconfirmed(Object.values(S.tpl).filter(t => t.on))) items.push(['row', `${u}.`, 'warn']);   // every outline drawn on it
  const nS = S.showOriginal ? 0 : S.clean.strokes.length;
  if (nS) items.push(['row', `Film cleaned for display: ${nS} area${nS > 1 ? 's' : ''} (leads, clips, labels) filled from the film around ${nS > 1 ? 'them' : 'it'}, outlined with thin dashes; not anatomy. Measurements use the original film.`, 'warn']);
  if (S.clean.crop) items.push(['sub', 'Picture cropped to the area of interest.']);
  if (Math.abs(rot) > 0.001) items.push(['sub', `Picture turned ${f1(rot * 180 / Math.PI)}° to level the pelvis (display only; measurements are on the film as taken).`]);
  items.push(['sub', 'A printout is not true size; measure on screen or use the scale bar.']);
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
    g.font = `600 ${12 * lw}px system-ui, sans-serif`; const lab = `50 mm at the joint (${S.cal.method === 'dicom' ? 'machine estimate' : 'calibrated'})`, tw = g.measureText(lab).width;
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
function refused() { return lmDefs().filter(d => lmProblem(d.key) || lmBroken(d.key)).map(d => d.label); }
function exportPNG() {
  if (S.filling) return prompt('Wait a moment: a stroke is still being filled.');
  const nr = notReady(); if (nr) return prompt(nr); const rf = refused(); if (rf.length && !confirm(`These landmarks are marked red: ${rf.join('; ')}. Save the picture anyway (the red marks are on it)?`)) return;
  const c = exportCanvas();
  if (S.set.share) return preview(c, () => c.toBlob(bl => download(bl, baseName() + '.png'), 'image/png'));   // Astra, 11 Oct: look before sharing
  c.toBlob(bl => download(bl, baseName() + '.png'), 'image/png');
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
  return secondaryCapture({ jpeg, width: c.width, height: c.height, meta: m, seriesDescription: `Templating plan ${SIDE[S.side]} ${S.wf === 'hip' ? 'hip' : 'knee'} (draft)`,
    comments: `Addis Ortho X-ray templating (trial). ${WF[S.wf].label}, surgery side ${SIDE[S.side]}. Draft for the surgeon; not true size.${S.clean.strokes.length && !S.showOriginal ? ` ${S.clean.strokes.length} area(s) of the film filled for display (not anatomy), outlined on the picture.` : ''}` });
}
async function exportDICOM() { if (S.set.share) return; if (S.filling) return prompt('Wait a moment: a stroke is still being filled.'); const nr = notReady(); if (nr) return prompt(nr); const rf = refused(); if (rf.length) return prompt(`Fix the landmarks marked red first: ${rf.join('; ')}.`); const { bytes } = await planDicom(); download(new Blob([bytes], { type: 'application/dicom' }), baseName() + '.dcm'); }
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
  if (!m.StudyInstanceUID || !m.SOPInstanceUID) return prompt(`Only an X-ray from ${V ? 'the viewer' : 'the archive'} (or a DICOM file of one) can be ${V ? 'saved in the viewer' : 'sent to PACS'}.`);
  const who = [patientName() || '(no name in the image)', m.PatientID ? 'MRN ' + m.PatientID : '', m.AccessionNumber ? 'order ' + m.AccessionNumber : '', fmtDate(m.StudyDate)].filter(Boolean).join(', ');
  // the picture's identity: everything that changes what is drawn (plan, results, black-outs, window, inversion, labels,
  // sharing); a retry after an unclear answer resends the same picture only if none of it changed (Sol, 11 Oct)
  const ident = JSON.stringify([snapshot().j, S.masks, S.clean, S.showOriginal, S.set.picture, S.wl, S.invertView, S.labels, S.set.share, results()]);
  if (S.pending && S.pending.key === S.key && (S.pending.ident !== ident || !S.pending.bytes)) {   // an unclear send whose picture changed, or from before a reload: is it there?
    const there = await inArchive(m.StudyInstanceUID, S.pending.sop);
    if (there === true) { S.sent = { sop: S.pending.sop, series: S.pending.series, at: new Date(), earlier: S.pending.ident !== ident, late: true }; S.pending = null; save(true); }
    else if (there === null && !confirm(`An earlier version of this plan may or may not have reached ${DEST} (no answer at the time, and ${V ? 'the viewer' : 'the archive'} cannot be asked now). ${V ? 'Save' : 'Send'} this version anyway?`)) return;
    S.pending = null; save(true);
  }
  if (S.sent && !confirm(`${S.sent.earlier ? 'An earlier version of this plan is' : S.sent.late ? `The earlier send did reach ${DEST}: this plan is` : 'This plan was'} already in ${DEST}. ${V ? 'Save another copy (the viewer will hold both)' : 'Send another copy (the archive will hold both)'}?`)) return;
  if (!confirm(`${V ? 'Save this plan in the viewer' : 'Send this plan to PACS'}, into the X-ray's own study?\n\nPatient: ${who}\nPlan: ${WF[S.wf].label}, surgery side ${SIDE[S.side]}\n\nCheck it is the right patient.`)) return;
  prompt(V ? 'Saving the plan in the viewer…' : 'Sending the plan to PACS…');
  try {
    // after an unclear answer the same picture (same instance) is sent again, so a retry cannot make a second copy
    const pend = S.pending && S.pending.key === S.key && S.pending.ident === ident ? S.pending : (S.pending = { key: S.key, ident, ...(await planDicom()) });
    let r, js = {};
    try { r = await fetch('store', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/dicom', 'X-AOC-Templating': '1' }, body: pend.bytes }); js = await r.json().catch(() => ({})); }
    catch (e) { S.sendError = V ? 'no answer from the viewer (is it still open?); "Save in the viewer" again checks the viewer first, so it will not make a second copy' : 'no answer from the server; "Send to PACS" again checks the archive first, so it will not make a second copy'; save(true); prompt(`Not sure it was ${V ? 'saved' : 'sent'}: ` + S.sendError + '.'); panel(); return; }
    if ((r.ok && js.ok) || (r.status === 409 && js.sop === pend.sop)) {
      S.sent = { sop: pend.sop, series: js.series || pend.series, at: new Date(), warning: js.result && /warning/.test(js.result) }; S.pending = null; S.sendError = null;
      save(true);   // kept with the plan at once: a reload still knows it was sent (Sol, 11 Oct)
      prompt(`${V ? 'Saved in the viewer' : 'Sent to PACS'}${S.sent.warning ? ` (${V ? 'the viewer' : 'the archive'} stored it with a warning)` : ''}: the plan is in this X-ray's study as "Templating plan ${SIDE[S.side]} ${S.wf === 'hip' ? 'hip' : 'knee'} (draft)".${V ? ' From the viewer it can be sent to PACS.' : ''}`);
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
window.aocTemplating = { S, toS, toI, results, plan, lmGeom, goBack, backTarget, recalibrate, headAt: slot => headAt(S.tpl[slot]), stemFit: slot => { const t = S.tpl[slot], tr = canalTraceFor(slotSide(slot)); return tr ? stemFit(t, tr) : null; },
  state: fullState, applyState: applyFullState, stepNow: () => stepNow().id, sheet: () => sheet && sheet.get(),
  project: () => projectNow(), caseRef: () => S.caseRef && { ...S.caseRef }, dirty: () => isDirty(), autosave: PJ.autoTest,
  // the automatic plan (13 Oct 2026, 28m): the stem note of a side, the recommendation, and every placement the search tried
  stemPlan: sd => S.notes['stemPlan' + sd] || null, recommend: () => recommendStem(), pending: () => PENDING.map(p => ({ ...p })),
  stemCands: sd => { const P = stemCandidates(sd, {}); return P ? P.cands.map(c => ({ fam: c.fam, size: c.size, neck: c.neck, missH: c.missH, missV: c.missV, ev: c.ev, maxIn: c.maxIn, cls: c.cls, comb: c.comb, sizeIdx: c.sizeIdx, fill: c.fill })) : null; } };
boot();
