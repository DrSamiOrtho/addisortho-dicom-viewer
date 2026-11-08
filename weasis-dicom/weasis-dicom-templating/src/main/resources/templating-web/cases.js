// My cases and the patient on the result (r44g, Sami, 7 Nov 2026: "saving the case as data base locally in the surgeon's computer
// for his cases"; "populate the metadata of patient name age and mrn"). Both pages use this module.
//   The patient: the X-ray's name, age and record number, which the surgeon may change on the Result card, for the file name, the
//   picture's report and the metadata of the PNG, the PDF and the DICOM (the 11 Oct exception; a box leaves them all out).
//   My cases: a database in this browser on this computer (IndexedDB), one record per case: the patient, the plan and the X-ray
//   itself, so a case opens with one click. Nothing here is sent anywhere. Clearing the browser's site data removes it, so a
//   backup file can be written and read back.
//   The hand-over: the full page gives its film to Quick's PFNA through the same database (same origin; one short-lived record).
import { zipParts, unzip, concat, crc32 } from './zip.js';

// ---- the patient (pure) -----------------------------------------------------------------------------------------------------
const AGE_MAX = 130;
export const cleanText = (v, n = 120) => String(v ?? '').replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, n);
const bytesToText = raw => { try { return new TextDecoder('utf-8').decode(Uint8Array.from(raw, c => c.charCodeAt(0) & 0xFF)); } catch (e) { return raw; } };
// a DICOM PatientName as read in the clinic ("FAMILY^GIVEN^MIDDLE" becomes "GIVEN MIDDLE FAMILY"), as the full page reads it
export function dicomName(m) {
  const raw = String((m && m.PatientName) || ''); if (!raw.trim()) return '';
  const txt = /ISO_IR 192/.test(String((m && m.SpecificCharacterSet) || '')) ? bytesToText(raw) : raw;
  const [fam = '', giv = '', mid = ''] = txt.split('=')[0].split('^');
  return cleanText([giv, mid, fam].map(x => x.trim()).filter(Boolean).join(' '));
}
// age in whole years: the birth and study dates when both are there, else PatientAge with its unit (as projects.js ageOf)
export function dicomAge(m) {
  const b = String((m && m.PatientBirthDate) || ''), d = String((m && m.StudyDate) || '');
  if (/^\d{8}$/.test(b) && /^\d{8}$/.test(d)) { const n = +d.slice(0, 4) - +b.slice(0, 4) - (d.slice(4) < b.slice(4) ? 1 : 0); return n >= 0 && n <= AGE_MAX ? n : null; }
  const am = String((m && m.PatientAge) || '').trim().toUpperCase().match(/^(\d{1,3})([YMWD])$/); if (!am) return null;
  const y = Math.floor(parseInt(am[1], 10) / ({ Y: 1, M: 12, W: 52.1775, D: 365.25 })[am[2]] + 1e-9);
  return y <= AGE_MAX ? y : null;
}
export const patientFromMeta = m => ({ name: dicomName(m), age: dicomAge(m), mrn: cleanText(m && m.PatientID, 64) });
export const parseAge = v => { const t = String(v ?? '').trim(); if (!t) return null; const n = Number(t); return Number.isInteger(n) && n >= 0 && n <= AGE_MAX ? n : undefined; };   // undefined: not an age
export const patientLine = (p, sep = ' · ') => [p.name, Number.isInteger(p.age) ? `Age ${p.age}` : '', p.mrn ? `MRN ${p.mrn}` : ''].filter(Boolean).join(sep);
const toDicomText = s => (/^[\x20-\x7e]*$/.test(s) ? s : String.fromCharCode(...new TextEncoder().encode(s)));
// the X-ray's attributes with the patient on the card. What the film says stays byte for byte when unchanged. A different record
// number makes a new study with no link to the X-ray, so one study never holds two patients.
export function dicomIdentity(meta, p) {
  const m = { ...(meta || {}) }, film = patientFromMeta(meta || {}), name = cleanText(p.name, 64), mrn = cleanText(p.mrn, 64);
  let wide = false;
  if (name !== film.name) {
    const w = name.split(' '), pn = w.length > 1 ? `${w[w.length - 1]}^${w.slice(0, -1).join(' ')}` : name;
    m.PatientName = toDicomText(pn); wide = wide || !/^[\x20-\x7e]*$/.test(pn);
  }
  if (mrn !== film.mrn) {
    m.PatientID = toDicomText(mrn); wide = wide || !/^[\x20-\x7e]*$/.test(mrn);
    for (const k of ['StudyInstanceUID', 'SOPInstanceUID', 'SOPClassUID', 'IssuerOfPatientID', 'IssuerQualifiers', 'AccessionNumber', 'StudyID']) delete m[k];
  }
  if (wide) m.SpecificCharacterSet = 'ISO_IR 192';
  const age = Number.isInteger(p.age) ? p.age : null;
  if (age !== film.age) { delete m.PatientBirthDate; if (age === null) delete m.PatientAge; else m.PatientAge = `${String(age).padStart(3, '0')}Y`; }
  else if (age !== null && !m.PatientAge) m.PatientAge = `${String(age).padStart(3, '0')}Y`;
  return m;
}
// the file name of a result, as the full page's: name, record number, side and joint, date; with the details left out, "shared"
export function resultBase({ patient, leaveOut, sides, joint, when = new Date() }) {
  const d = `${when.getFullYear()}-${String(when.getMonth() + 1).padStart(2, '0')}-${String(when.getDate()).padStart(2, '0')}`;
  const safe = s => String(s || '').replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ').replace(/\s+/g, ' ').trim();
  const what = `${sides.map(s => s.toUpperCase()).join('-')}${sides.length ? '-' : ''}${joint}_template_${d}`;
  if (leaveOut) return `shared_${what}`;
  const nm = safe(patient.name).slice(0, 60), id = safe(patient.mrn).replace(/\s+/g, '-').slice(0, 40);
  return `${nm ? nm + '_' : ''}${id ? id + '_' : ''}${Number.isInteger(patient.age) ? patient.age + 'y_' : ''}${what}`;
}
// a PNG with text in its own metadata (iTXt chunks after IHDR): pairs of keyword and UTF-8 text
export function pngWithText(u8, pairs) {
  const chunks = pairs.filter(([, v]) => v).map(([k, v]) => {
    const enc = new TextEncoder(), key = enc.encode(k), val = enc.encode(v), type = enc.encode('iTXt');
    const body = concat([type, key, new Uint8Array(5), val]);   // keyword, NUL, not compressed, method, no language, no translated keyword
    const c = new Uint8Array(4 + body.length + 4), dv = new DataView(c.buffer);
    dv.setUint32(0, body.length - 4); c.set(body, 4); dv.setUint32(4 + body.length, crc32(body)); return c;
  });
  return chunks.length ? concat([u8.subarray(0, 33), ...chunks, u8.subarray(33)]) : u8;   // 33: the signature and IHDR
}

// ---- the database -----------------------------------------------------------------------------------------------------------
const DB_NAME = 'ao-templating-cases', CASES = 'cases', FILMS = 'films', HAND = 'handoff';
const ID_RE = /^[0-9a-f]{32}$/;
export const newCaseId = () => [...crypto.getRandomValues(new Uint8Array(16))].map(b => b.toString(16).padStart(2, '0')).join('');
export const casesOk = () => { try { return typeof indexedDB !== 'undefined' && !!indexedDB; } catch (e) { return false; } };
function db() {
  return new Promise((res, rej) => {
    const r = indexedDB.open(DB_NAME, 1);
    r.onupgradeneeded = () => { for (const s of [CASES, FILMS, HAND]) r.result.createObjectStore(s); };
    r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error || new Error('The cases on this computer cannot be opened.'));
    r.onblocked = () => rej(new Error('Close the other templating windows, then try again.'));
  });
}
async function tx(stores, mode, fn) {
  const d = await db();
  try {
    return await new Promise((res, rej) => {
      const t = d.transaction(stores, mode); let out;
      t.oncomplete = () => res(out); t.onerror = () => rej(t.error); t.onabort = () => rej(t.error || new Error('Not saved: this computer\'s browser storage refused it (it may be full).'));
      out = fn(t);
    });
  } finally { d.close(); }
}
const req = r => new Promise((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
const filmKey = (id, i) => `${id}/${i}`;
// rec: {id, page: 'quick' | 'full', savedAt, patient, joint, side, summary, plan, films: [{name, mime, ...}]}; bytes: one Uint8Array per film
export async function putCase(rec, bytes) {
  if (!ID_RE.test(rec.id)) throw new Error('A case without an identity.');
  try { if (navigator.storage && navigator.storage.persist) navigator.storage.persist(); } catch (e) { /* the browser decides */ }
  await tx([CASES, FILMS], 'readwrite', t => {
    t.objectStore(CASES).put(rec, rec.id);
    const fs = t.objectStore(FILMS);
    fs.delete(IDBKeyRange.bound(`${rec.id}/`, `${rec.id}/\uffff`));
    bytes.forEach((b, i) => fs.put(b, filmKey(rec.id, i)));
  });
  return rec.id;
}
export async function listCases() {
  const all = await tx([CASES], 'readonly', t => { const r = t.objectStore(CASES).getAll(); const o = { }; r.onsuccess = () => { o.v = r.result; }; return o; });
  return (all.v || []).sort((a, b) => String(b.savedAt).localeCompare(String(a.savedAt)));
}
export async function getCase(id) {
  if (!ID_RE.test(String(id))) return null;
  const d = await db();
  try {
    const t = d.transaction([CASES, FILMS]), rec = await req(t.objectStore(CASES).get(id)); if (!rec) return null;
    const bytes = await Promise.all((rec.films || []).map((_, i) => req(t.objectStore(FILMS).get(filmKey(id, i)))));
    return { rec, bytes };
  } finally { d.close(); }
}
export const deleteCase = id => tx([CASES, FILMS], 'readwrite', t => { t.objectStore(CASES).delete(id); t.objectStore(FILMS).delete(IDBKeyRange.bound(`${id}/`, `${id}/\uffff`)); });
// the hand-over to Quick's PFNA: one record, taken once; an old one is dropped
export const putHandoff = o => tx([HAND], 'readwrite', t => { t.objectStore(HAND).put({ ...o, at: Date.now() }, 'film'); });
export async function takeHandoff() {
  const d = await db();
  try {
    const t = d.transaction([HAND], 'readwrite'), s = t.objectStore(HAND), o = await req(s.get('film'));
    s.delete('film');
    return o && Date.now() - o.at < 600000 ? o : null;
  } finally { d.close(); }
}

// ---- backup: one ZIP of every case on this computer; it holds patients' details and X-rays ----------------------------------------
const BACKUP_FORMAT = 'addis-ortho-templating-cases', BACKUP_MAX = 1536 * 1048576;
export async function backupBlob() {
  const list = await listCases(), man = [], entries = [];
  for (const c of list) {
    const g = await getCase(c.id); if (!g) continue;
    man.push(g.rec);
    g.bytes.forEach((b, i) => { if (b) entries.push({ name: `film-${c.id}-${i}`, data: b }); });
  }
  entries.unshift({ name: 'cases.json', data: new TextEncoder().encode(JSON.stringify({ format: BACKUP_FORMAT, v: 1, cases: man })) });
  return { blob: new Blob(zipParts(entries), { type: 'application/zip' }), count: man.length };
}
// a backup is another computer's file: every field checked or clipped here, a plan checked again when it is opened; a case that
// is already here and newer stays
export async function restoreBackup(file) {
  if (file.size > BACKUP_MAX) throw new Error('This file is too large to be a backup of cases.');
  const zip = await unzip(new Uint8Array(await file.arrayBuffer()), { maxEntries: 5000, maxTotal: BACKUP_MAX });
  const get = n => (zip.get ? zip.get(n) : zip[n]);
  let man; try { man = JSON.parse(new TextDecoder().decode(get('cases.json'))); } catch (e) { throw new Error('This is not a backup of cases.'); }
  if (!man || man.format !== BACKUP_FORMAT || !Array.isArray(man.cases)) throw new Error('This is not a backup of cases.');
  const here = new Map((await listCases()).map(c => [c.id, c])); let added = 0, kept = 0, bad = 0;
  for (const c of man.cases) {
    if (!c || !ID_RE.test(String(c.id)) || !['quick', 'full'].includes(c.page) || !Array.isArray(c.films) || c.films.length > 8 || !c.films.length) { bad++; continue; }
    const bytes = c.films.map((_, i) => get(`film-${c.id}-${i}`));
    if (bytes.some(b => !(b instanceof Uint8Array))) { bad++; continue; }
    const h = here.get(c.id); if (h && String(h.savedAt) >= String(c.savedAt)) { kept++; continue; }
    const p = c.patient || {};
    await putCase({ ...c, savedAt: String(c.savedAt || '').slice(0, 30), joint: cleanText(c.joint, 20), side: cleanText(c.side, 12), summary: cleanText(c.summary, 300),
      patient: { name: cleanText(p.name), age: Number.isInteger(p.age) ? p.age : null, mrn: cleanText(p.mrn, 64) } }, bytes);
    added++;
  }
  return { added, kept, bad };
}

// ---- the My cases window (both pages); host: {onOpen(rec), onExport(rec)?, say(text)} -------------------------------------------
const whenText = iso => { const d = new Date(iso); if (isNaN(d)) return ''; const p = n => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`; };
const make = (tag, css, text) => { const e = document.createElement(tag); if (css) e.style.cssText = css; if (text !== undefined) e.textContent = text; return e; };
const BTN = 'font:inherit;font-size:14px;padding:7px 12px;border-radius:6px;border:1px solid #034537;background:#fff;color:#034537;cursor:pointer';
const BTN_GO = 'font:inherit;font-size:14px;padding:7px 12px;border-radius:6px;border:1px solid #034537;background:#034537;color:#fff;cursor:pointer';
export async function openCasesWindow(host) {
  if (document.getElementById('aoCases')) return;
  const wrap = make('div', 'position:fixed;inset:0;z-index:9000;background:rgba(0,0,0,.55);display:flex;align-items:center;justify-content:center;padding:12px');
  wrap.id = 'aoCases'; wrap.setAttribute('role', 'dialog'); wrap.setAttribute('aria-modal', 'true'); wrap.setAttribute('aria-label', 'My cases');
  const box = make('div', 'background:#fff;color:#000;border-radius:10px;width:min(720px,100%);max-height:92vh;display:flex;flex-direction:column;font:15px system-ui,sans-serif');
  const head = make('div', 'padding:14px 16px 8px;display:flex;gap:10px;align-items:center;flex-wrap:wrap');
  const close = () => { document.removeEventListener('keydown', onKey, true); wrap.remove(); };
  const onKey = e => { if (e.key === 'Escape') { e.stopPropagation(); close(); } };
  const closeB = make('button', BTN, 'Close'); closeB.onclick = close;
  const search = make('input', 'font:inherit;flex:1;min-width:150px;padding:7px 9px;border:1px solid #999;border-radius:6px'); search.type = 'search'; search.placeholder = 'Search name or record number'; search.setAttribute('aria-label', 'Search cases');
  head.append(make('h2', 'margin:0;font-size:19px;color:#034537', 'My cases'), search, closeB);
  const note = make('p', 'margin:0;padding:0 16px 8px;font-size:13px;color:#444', 'Kept only in this browser on this computer, with the X-ray, and never sent anywhere. Anyone using this browser profile can open them.');
  const rows = make('div', 'overflow:auto;flex:1;padding:0 16px;min-height:90px');
  const foot = make('div', 'padding:10px 16px 14px;display:flex;gap:8px;flex-wrap:wrap;align-items:center');
  const backB = make('button', BTN, 'Back up all cases'), restB = make('button', BTN, 'Restore a backup'), picker = make('input', 'display:none'); picker.type = 'file'; picker.accept = '.zip,application/zip';
  const said = make('span', 'font-size:13px;color:#034537;flex:1;min-width:140px');
  foot.append(backB, restB, picker, said);
  box.append(head, note, rows, foot); wrap.appendChild(box); document.body.appendChild(wrap);
  document.addEventListener('keydown', onKey, true);
  let all = [];
  const draw = () => {
    rows.textContent = '';
    const q = search.value.trim().toLowerCase();
    const list = all.filter(c => !q || `${(c.patient || {}).name || ''} ${(c.patient || {}).mrn || ''}`.toLowerCase().includes(q));
    if (!list.length) rows.appendChild(make('p', 'color:#444', all.length ? 'No case matches.' : 'No cases yet. Save keeps the case here.'));
    for (const c of list) {
      const p = c.patient || {}, r = make('div', 'display:flex;gap:10px;align-items:center;padding:9px 0;border-top:1px solid #ddd;flex-wrap:wrap');
      const t = make('div', 'flex:1;min-width:190px');
      t.append(make('div', 'font-weight:600', patientLine(p) || 'No patient details'), make('div', 'font-size:13px;color:#444', [c.joint, c.side, whenText(c.savedAt)].filter(Boolean).join(' · ')));
      if (c.summary) t.appendChild(make('div', 'font-size:13px;color:#444', c.summary));
      const open = make('button', BTN_GO, 'Open'); open.onclick = () => { close(); host.onOpen(c); };
      r.append(t, open);
      if (host.onExport && c.page === 'full') { const ex = make('button', BTN, 'Save as file'); ex.onclick = () => host.onExport(c); r.appendChild(ex); }
      const del = make('button', BTN, 'Delete');
      del.onclick = async () => {
        if (!confirm(`Delete this case from this computer? ${patientLine(p, ', ') || 'No patient details'}, ${c.joint || ''} ${whenText(c.savedAt)}. It cannot be undone unless you have a backup.`)) return;
        try { await deleteCase(c.id); all = all.filter(x => x.id !== c.id); draw(); } catch (e) { said.textContent = `Not deleted: ${(e && e.message) || e}`; }
      };
      r.appendChild(del); rows.appendChild(r);
    }
  };
  search.oninput = draw;
  backB.onclick = async () => {
    backB.disabled = true; said.textContent = 'Writing the backup…';
    try {
      const { blob, count } = await backupBlob(), a = make('a'), u = URL.createObjectURL(blob), d = new Date().toISOString().slice(0, 10);
      a.href = u; a.download = `AO-cases-backup-${d}.zip`; document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(u), 8000);
      said.textContent = `${count} case${count === 1 ? '' : 's'} written to your Downloads. The file holds patients' details and X-rays: keep it safe.`;
    } catch (e) { said.textContent = `No backup: ${(e && e.message) || e}`; } finally { backB.disabled = false; }
  };
  restB.onclick = () => picker.click();
  picker.onchange = async () => {
    const f = picker.files && picker.files[0]; picker.value = ''; if (!f) return;
    said.textContent = 'Reading the backup…';
    try { const r = await restoreBackup(f); all = await listCases(); draw(); said.textContent = `${r.added} added, ${r.kept} already here${r.bad ? `, ${r.bad} unreadable` : ''}.`; }
    catch (e) { said.textContent = `Not restored: ${(e && e.message) || e}`; }
  };
  try { all = await listCases(); draw(); } catch (e) { rows.textContent = ''; rows.appendChild(make('p', 'color:#a40000', `The cases on this computer cannot be read: ${(e && e.message) || e}`)); }
  search.focus();
}
