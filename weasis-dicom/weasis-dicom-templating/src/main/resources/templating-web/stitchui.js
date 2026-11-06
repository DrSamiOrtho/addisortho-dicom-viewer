// The "Join films" sheet (4 Nov 2026), one copy for the full planner and Quick: DESIGN-2026-11-04-stitching.md section 2.
// The page passes its own decoders and ball finder (host), and gets back the joined film as a derived DICOM file, which it opens
// through its own "open a file" path: the page then recognises the film from its header (stitchFromMeta), by whatever path it came.
// Nothing leaves the device. File names are never shown or used (they can carry a patient's name).
import { STITCH, stitchWarning, stitchScaleLine, stitchStatus } from './shared.js';
import * as ST from './stitch.js';

const CSS = `.aost{position:fixed;inset:0;z-index:9999;background:#fff;color:#000;overflow:auto;font:15px/1.4 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
.aost header{position:sticky;top:0;background:#034537;color:#fff;padding:10px 16px;display:flex;align-items:center;gap:12px;z-index:2}
.aost header h2{font-size:17px;margin:0;flex:1}.aost header button{background:transparent;color:#fff;border:1px solid #fff}
.aost main{max-width:920px;margin:0 auto;padding:12px 16px 80px}.aost p{margin:6px 0}
.aost button{font:inherit;border-radius:8px;border:1px solid #034537;background:#034537;color:#fff;padding:7px 12px;margin:3px 4px 3px 0;cursor:pointer}
.aost button.alt{background:#fff;color:#034537}.aost button:disabled{opacity:.45;cursor:default}
.aost .film{display:flex;gap:12px;align-items:flex-start;border-top:1px solid #d5e2de;padding:10px 0;flex-wrap:wrap}
.aost .film canvas{background:#000;border-radius:4px;max-width:120px}.aost .film .ctl{flex:1;min-width:220px}
.aost .film h3{margin:0 0 4px;font-size:15px;color:#034537}.aost label{display:block;margin:3px 0}
.aost .warn{background:#fff3d6;border-left:4px solid #b07800;padding:8px 10px;margin:8px 0}.aost .bad{background:#fde8e8;border-left:4px solid #9b1c1c;padding:8px 10px;margin:8px 0}
.aost .ok{background:#eef6f3;border-left:4px solid #034537;padding:8px 10px;margin:8px 0}
.aost .join{border:1px solid #d5e2de;border-radius:8px;padding:8px 10px;margin:8px 0}.aost .chip{display:inline-block;border-radius:10px;padding:1px 8px;font-size:13px;margin-left:6px}
.aost .chip.matched{background:#eef6f3;color:#034537}.aost .chip.weak,.aost .chip.hand{background:#fff3d6;color:#6b4a00}.aost .chip.failed,.aost .chip.flipped,.aost .chip.inverted{background:#fde8e8;color:#9b1c1c}
.aost .pv{width:100%;background:#000;border-radius:6px;touch-action:none}.aost .tap{position:fixed;inset:0;background:#000;z-index:3;display:flex;flex-direction:column}
.aost .tap p{color:#fff;padding:8px 16px;margin:0}.aost .tap canvas{flex:1;width:100%;min-height:0;touch-action:none}
.aost .nudge button{min-width:44px}.aost small{color:#3c4b47}.aost hr{border:0;border-top:2px solid #A8FF30;width:48px;margin:10px 0}`;

const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const sniff = u => (u.length > 132 && String.fromCharCode(u[128], u[129], u[130], u[131]) === 'DICM' ? 'dicom' : u[0] === 0x89 && u[1] === 0x50 ? 'png' : u[0] === 0xFF && u[1] === 0xD8 ? 'jpeg' : u[0] === 0x52 && u[8] === 0x57 ? 'webp' : null);
const EXT = { dicom: 'dcm', png: 'png', jpeg: 'jpg', webp: 'webp' };
// the size in pixels from the header, before anything is decoded (the memory budget is checked first: Fable F8, code review C3)
function dimsOf(u, kind, host) {
  if (kind === 'png' && u.length > 24) return { cols: ((u[16] << 24) | (u[17] << 16) | (u[18] << 8) | u[19]) >>> 0, rows: ((u[20] << 24) | (u[21] << 16) | (u[22] << 8) | u[23]) >>> 0 };
  if (kind === 'jpeg') { let i = 2; while (i + 9 < u.length) { if (u[i] !== 0xFF) break; const m = u[i + 1]; if (m === 0xFF) { i++; continue; } if (m === 0xD8 || m === 0x01 || (m >= 0xD0 && m <= 0xD7)) { i += 2; continue; } if (m >= 0xC0 && m <= 0xCF && m !== 0xC4 && m !== 0xC8 && m !== 0xCC) return { rows: (u[i + 5] << 8) | u[i + 6], cols: (u[i + 7] << 8) | u[i + 8] }; i += 2 + ((u[i + 2] << 8) | u[i + 3]); } }
  if (kind === 'dicom' && host.parseDicom) { try { const t = host.parseDicom(u.buffer.slice(u.byteOffset, u.byteOffset + u.byteLength)); const c = parseInt(t.Columns, 10), r = parseInt(t.Rows, 10); if (c > 0 && r > 0) return { cols: c, rows: r }; } catch (e) { /* decoded below says what is wrong */ } }
  return null;
}
const day = () => { const d = new Date(), p = n => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`; };
const coarse = () => (typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches) || (navigator.deviceMemory && navigator.deviceMemory <= 4);
function grey(c, g, w, h) {   // a kept copy or grid (0..1) into a canvas of w x h
  c.width = w; c.height = h; const x = c.getContext('2d'), im = x.createImageData(w, h);
  for (let y = 0; y < h; y++) for (let i = 0; i < w; i++) { const v = g.data[Math.floor(y * g.rows / h) * g.cols + Math.floor(i * g.cols / w)], q = Math.max(0, Math.min(255, v * 255)), o = 4 * (y * w + i); im.data[o] = im.data[o + 1] = im.data[o + 2] = q; im.data[o + 3] = 255; }
  x.putImageData(im, 0, 0); return x;
}
function u16Canvas(c, film, y0, y1, w) {   // a band of the joined film (rows y0..y1) into a canvas w wide
  const h = Math.max(1, Math.round((y1 - y0) * w / film.cols)); c.width = w; c.height = h;
  const x = c.getContext('2d'), im = x.createImageData(w, h), q = v => (v / 65535) * 1.5 - 0.25;
  for (let yy = 0; yy < h; yy++) for (let i = 0; i < w; i++) { const v = film.u16[Math.min(film.rows - 1, Math.floor(y0 + yy * (y1 - y0) / h)) * film.cols + Math.floor(i * film.cols / w)], g = Math.max(0, Math.min(255, q(v) * 255)), o = 4 * (yy * w + i); im.data[o] = im.data[o + 1] = im.data[o + 2] = g; im.data[o + 3] = 255; }
  x.putImageData(im, 0, 0); return x;
}
function download(bytes, name, type) { const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([bytes], { type })); a.download = name; document.body.appendChild(a); a.click(); setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000); }

// host: {decodeAny, decodePNG, decodeBitmap, findBalls, detectBall, onDone(file), ballMm}
export function openStitchSheet(host) {
  if (!document.getElementById('aost-css')) { const s = document.createElement('style'); s.id = 'aost-css'; s.textContent = CSS; document.head.appendChild(s); }
  const root = document.createElement('div'); root.className = 'aost'; root.setAttribute('role', 'dialog'); root.setAttribute('aria-label', 'Join films into one long leg');
  document.body.appendChild(root);
  const S = { films: [], half: false, J: null, sel: 0, tick: false, anchor: -1, busy: '', err: '', result: null, film: null };
  const outMm = () => STITCH.outMm * (S.half ? 2 : 1);
  const budget = () => (coarse() ? STITCH.budgetPhone : STITCH.budgetDesk);
  const close = () => { root.remove(); };
  const reset = () => { S.J = null; S.film = null; S.result = null; S.res = null; };
  const ownScale = f => (f.how === 'ball' && f.ball && f.ballOk) || (f.how === 'line' && f.line && f.len > 0);
  const segs = () => S.films.map(f => ({ keep: f.keep, how: ownScale(f) ? f.how : 'carried', ball: f.ball, d: f.d, line: f.line, len: f.len, turned: f.turned, sha: f.sha, ips: f.ips }));
  const checks = () => ST.checkSet(S.films.map(f => ({ meta: f.meta, sha: f.sha })));
  const toOrig = (f, p) => ({ x: p.x * f.keep.f + (f.keep.f - 1) / 2, y: p.y * f.keep.f + (f.keep.f - 1) / 2 });

  async function addBytes(u, preset = null) {
    if (S.films.length >= STITCH.maxSeg) throw new Error(`At most ${STITCH.maxSeg} films can be joined.`);
    if (u.length > 80 * 1024 * 1024) throw new Error('A film is at most 80 MB.');
    const kind = sniff(u); if (!kind) throw new Error('Not a DICOM, PNG, JPEG or WebP picture.');
    const sha = await ST.sha256hex(u);
    if (S.films.some(f => f.sha === sha)) throw new Error('This file is already in the set.');
    const pre = dimsOf(u, kind, host), keptBytes = S.films.reduce((a, f) => a + f.bytes.length, 0) + u.length;
    const check = d => {
      const dims = [...S.films.map(f => ({ cols: f.cols, rows: f.rows, kind: f.kind })), { ...d, kind }];
      const need = ST.budgetBytes(dims, dims.reduce((a, q) => a + q.cols * q.rows, 0) * Math.pow(0.15 / outMm(), 2), keptBytes);
      if (need > budget()) throw new Error(`Joining these films needs about ${Math.round(need / 1e6)} MB of memory; this device is given ${Math.round(budget() / 1e6)} MB. ${S.half ? 'Use fewer or smaller films.' : 'Tick "Join at half size" and add the film again.'}`);
    };
    if (pre) { if (pre.cols * pre.rows > 60e6) throw new Error('This film is larger than 60 megapixels.'); check(pre); }
    const buf = u.byteOffset === 0 && u.byteLength === u.buffer.byteLength ? u.buffer : u.slice().buffer;
    const bmp = async () => host.decodeBitmap(await createImageBitmap(new Blob([u], { type: kind === 'jpeg' ? 'image/jpeg' : 'image/' + kind })));
    let img = kind === 'dicom' ? await host.decodeAny(buf) : kind === 'png' ? (await host.decodePNG(buf)) || await bmp() : await bmp();
    if (img.cols * img.rows > 60e6) throw new Error('This film is larger than 60 megapixels.');
    if (!pre) { try { check({ cols: img.cols, rows: img.rows }); } catch (e) { img = null; throw e; } }   // no header size (WebP, an odd DICOM): checked after
    let cands = []; try { cands = host.findBalls(img, { ballMm: host.ballMm || 25 }) || []; } catch (e) { cands = []; }
    const meta = img.meta || {}, ips = meta.imagerPixelSpacing && meta.imagerPixelSpacing[0] > 0 ? meta.imagerPixelSpacing[0] : null;
    const keep = ST.keepCopy(img), cols = img.cols, rows = img.rows;
    const m = { ...meta }; delete m.PixelData;
    img = null;   // the full decode goes now: only the kept copy stays (Fable F8)
    const f = { bytes: u, kind, ext: EXT[kind], sha, meta: m, keep, cols, rows, ips, cands, ci: 0, d: host.ballMm || 25, turned: false, markerOk: false,
      how: cands.length ? 'ball' : 'carried', ball: cands.length ? { x: cands[0].x, y: cands[0].y, r: cands[0].r } : null, ballOk: false, line: null, len: 0 };
    if (preset) Object.assign(f, preset);
    S.films.push(f); reset(); S.tick = false;
  }
  async function addFiles(list) {
    for (const file of list) { S.busy = 'Reading a film…'; render(); try { await addBytes(new Uint8Array(await file.arrayBuffer())); S.err = ''; } catch (e) { S.err = e.message || String(e); break; } }
    S.busy = ''; render();
  }
  async function openSet(file) {
    S.busy = 'Opening the film set…'; render();
    try {
      const { recipe, files } = await ST.openFilmSet(new Uint8Array(await file.arrayBuffer()));
      S.films = []; reset(); S.half = recipe.outMm > STITCH.outMm * 1.5;
      for (const [i, x] of files.entries()) {
        const sg = recipe.segs[i];
        await addBytes(x.bytes, { how: sg.how, ball: sg.ball || null, ballOk: sg.how === 'ball', d: sg.d || 25, line: sg.line || null, len: sg.len || 0, turned: !!sg.turned, markerOk: false });
      }
      S.anchor = recipe.anchor;
      S.J = ST.applySaved(segs(), recipe); recompose();
      S.err = ''; S.note = 'The saved joins are applied as they were. Confirm the R/L markers and the series again before using the film.';
    } catch (e) { S.err = e.message || String(e); }
    S.busy = ''; render();
  }
  async function join() {
    S.busy = 'Joining…'; S.err = ''; render();
    try {
      const J = await ST.joinAll(segs(), { outMm: outMm(), onStep: t => { S.busy = 'Joining: ' + t + '…'; render(); } });
      if (J.errors && J.errors.length) { S.err = J.errors.join(' '); S.J = null; }
      else { S.J = J; if (!(S.anchor >= 0) || !ownScale(S.films[S.anchor])) S.anchor = ST.defaultAnchor(segs()); recompose(); }
    } catch (e) { S.err = e.message || String(e); }
    S.busy = ''; render();
  }
  function recompose() {
    S.film = null; S.result = null; S.res = null;
    if (!S.J || !S.J.joins.every(j => j.cA)) return;
    try { S.film = ST.compose(S.J.grids, S.J.joins, { outMm: outMm() }); } catch (e) { S.film = null; S.err = e.message; }
  }
  function byHand(i) {   // a failed join placed by hand starts from the best guess, or a 50 mm overlap down the middle
    const j = S.J.joins[i], A = S.J.grids[i], B = S.J.grids[i + 1];
    if (!j.cA) { const h = 25 / outMm(); j.cA = { x: A.cols / 2, y: A.rows - h }; j.cB = { x: B.cols / 2, y: h }; j.rot = 0; j.s = 1; }
    j.how = 'hand'; j.status = j.status === 'matched' ? 'matched' : j.status; recompose();
  }
  function nudge(i, dx, dy, dr) {
    const j = S.J.joins[i]; if (!j.cA) return;
    const px = 1 / outMm(), nc = { x: j.cA.x + dx * px, y: j.cA.y + dy * px }, ov = (A_(i).rows - (nc.y - j.cB.y * (j.s || 1))) * outMm();
    if (ov < STITCH.minOverlapMm) { S.err = `The films would overlap by ${Math.round(ov)} mm; a join needs at least ${STITCH.minOverlapMm} mm.`; render(); return; }
    S.err = '';
    j.cA = nc; j.rot = Math.max(-STITCH.handTurnDeg, Math.min(STITCH.handTurnDeg, (j.rot || 0) + dr)); j.how = 'hand';
    try { j.ncc = ST.scoreAt(A_(i), A_(i + 1), j.cA, j.cB, j.rot, j.s || 1, outMm()); } catch (e) { /* the score is for information */ }
    recompose(); render();
  }
  const A_ = i => S.J.grids[i];
  // the recipe and band for the film already composed (no second composition per click: code review C2); cached until something changes
  function finishNow() {
    const tickBox = S.tick && S.films.every(f => f.markerOk), key = `${tickBox}|${S.anchor}`;
    if (S.res && S.resKey === key) return S.res;
    S.res = ST.finish(segs(), S.J, { outMm: outMm(), anchor: S.anchor, tickBox, film: S.film }); S.resKey = key;
    return S.res;
  }
  async function useIt() {
    S.busy = 'Writing the joined film…'; render();
    try {
      const { film, recipe } = finishNow();
      const first = S.films.find(f => f.kind === 'dicom'), meta = first ? first.meta : {};
      const sources = S.films.filter(f => f.meta.SOPInstanceUID && f.meta.SOPClassUID).map(f => ({ cls: f.meta.SOPClassUID, uid: f.meta.SOPInstanceUID }));
      const bytes = await ST.stitchedDicom(film, recipe, meta, sources);
      const file = new File([bytes], 'stitched-long-leg.dcm', { type: 'application/dicom' });
      S.lastRecipe = recipe; close(); await host.onDone(file, { recipe });
    } catch (e) { S.err = e.message || String(e); S.busy = ''; render(); }
  }
  function saveSet() {
    try { const { recipe } = finishNow(); download(ST.filmSet(S.films.map(f => ({ bytes: f.bytes, ext: f.ext })), recipe), `AO-film-set_${day()}.aots`, 'application/zip'); }
    catch (e) { S.err = e.message || String(e); render(); }
  }
  function tapView(fi, mode) {   // tap the ball, or two ends of a known length, on a large view of one film
    const f = S.films[fi], v = document.createElement('div'); v.className = 'tap';
    v.innerHTML = `<p>${mode === 'ball' ? `Tap the middle of the ${esc(f.d)} mm ball on film ${fi + 1}.` : `Tap both ends of a known length on film ${fi + 1} (a ruler).`} <button class="alt" data-x>Cancel</button></p><canvas></canvas>`;
    root.appendChild(v);
    const c = v.querySelector('canvas'), r = c.getBoundingClientRect(), k = Math.min(r.width / f.keep.cols, r.height / f.keep.rows), w = Math.round(f.keep.cols * k), h = Math.round(f.keep.rows * k);
    grey(c, f.keep, w, h); c.style.width = w + 'px'; c.style.height = h + 'px'; c.style.flex = 'none'; c.style.margin = '0 auto';
    const pts = [];
    v.querySelector('[data-x]').onclick = () => v.remove();
    c.onpointerup = e => {
      const b = c.getBoundingClientRect(), p = { x: (e.clientX - b.left) / b.width * f.keep.cols, y: (e.clientY - b.top) / b.height * f.keep.rows };
      if (mode === 'ball') {
        const got = host.detectBall({ cols: f.keep.cols, rows: f.keep.rows, data: f.keep.data, invert: false }, p, Math.max(10, 0.06 * Math.max(f.keep.cols, f.keep.rows)));
        if (!got || got.rejected) { v.querySelector('p').firstChild.textContent = `${got && got.reasons ? 'Not taken as the ball: ' + got.reasons.join('; ') : 'No ball edge found there'}. Tap the middle of the ball again, or mark a known length. `; return; }
        const o = toOrig(f, got); f.ball = { x: o.x, y: o.y, r: got.r * f.keep.f }; f.how = 'ball'; f.ballOk = true; f.ballWarn = (got.warnings || []).join('; '); v.remove(); reset(); S.anchor = -1; render(); return;
      }
      pts.push(toOrig(f, p));
      if (pts.length === 2) {
        const mm = parseFloat(prompt('How long is it, in millimetres?', '100'));
        v.remove();
        if (mm > 5 && mm < 1500) { f.line = pts.slice(); f.len = mm; f.how = 'line'; reset(); }
        render();
      }
    };
  }

  function render() {
    const ck = S.films.length ? checks() : { refuse: [], warn: [], compared: [] };
    const sx = (f, x) => x;   // eslint calm
    void sx;
    const joined = !!S.J, J = S.J;
    let res = null, why = '';
    if (joined && S.film) { try { res = finishNow(); } catch (e) { why = e.message; S.res = null; } }
    const st = res ? stitchStatus(res.recipe) : null;
    const allUsable = joined && J.joins.every(ST.joinUsable);
    const markers = S.films.length && S.films.every(f => f.markerOk);
    const canUse = !!res && res.recipe.tick && !ck.refuse.length && allUsable && !S.busy;
    root.innerHTML = `<header><h2>Join 2 to 4 films into one long leg</h2><button data-a="close">Close</button></header><main>
      <p>Films of <b>one standing series</b>, from the pelvis down to the ankle. Each film is scaled by its own ball or a known length; a film with neither takes its scale through the join from its neighbour (flagged). Nothing leaves this device.</p><hr>
      <p><button data-a="add"${S.films.length >= STITCH.maxSeg ? ' disabled' : ''}>Add films</button><button class="alt" data-a="set">Open a film set (.aots)</button>
      <label style="display:inline"><input type="checkbox" data-a="half"${S.half ? ' checked' : ''}> Join at half size (${STITCH.outMm * 2} mm a pixel; for phones)</label></p>
      ${S.busy ? `<div class="ok">${esc(S.busy)}</div>` : ''}${S.err ? `<div class="bad">${esc(S.err)}</div>` : ''}${S.note ? `<div class="warn">${esc(S.note)}</div>` : ''}
      ${ck.refuse.length ? `<div class="bad"><b>These films cannot be joined:</b> ${esc(ck.refuse.join('; '))}.</div>` : ''}
      ${ck.warn.length ? `<div class="warn">${esc(ck.warn.join('; '))}.</div>` : ''}
      <div id="films"></div>
      ${S.films.length >= 2 ? `<p><label><input type="checkbox" data-a="tick"${S.tick ? ' checked' : ''}> These films are of <b>one patient, one standing series</b>, in order from the top.</label>
        <small>Checked from the headers: ${ck.compared.length ? esc(ck.compared.join(', ')) : 'nothing (no headers)'}.</small></p>
        <p><button data-a="join"${S.busy || ck.refuse.length ? ' disabled' : ''}>${joined ? 'Match again' : 'Join'}</button></p>` : '<p><small>Add at least two films.</small></p>'}
      <div id="joins"></div>
      ${joined && S.film ? `<div class="${st && st.level === 'red' ? 'bad' : 'warn'}"><b>${esc(res ? stitchWarning(res.recipe) : '')}</b><br>${esc(res ? stitchScaleLine(res.recipe) : '')}${st && st.reasons.length ? `<br>Check: ${esc(st.reasons.join('; '))}.` : ''}</div>
        <p><label>Scale shown for: <select data-a="anchor">${S.films.map((f, i) => (ownScale(f) ? `<option value="${i}"${i === S.anchor ? ' selected' : ''}>film ${i + 1}</option>` : '')).join('')}</select></label></p>
        <canvas class="pv" id="pvAll"></canvas>
        ${why ? `<div class="warn">${esc(why)}</div>` : ''}
        ${!markers ? '<div class="warn">Confirm the R/L marker on every film before using the joined film.</div>' : ''}
        ${!S.tick ? '<div class="warn">Tick "one patient, one standing series" before using the joined film.</div>' : ''}
        <p><button data-a="use"${canUse ? '' : ' disabled'}>Use this joined film</button><button class="alt" data-a="saveset"${res && res.recipe.tick ? '' : ' disabled'}>Save the film set (.aots)</button><button class="alt" data-a="savedcm"${canUse ? '' : ' disabled'}>Save the joined film (DICOM)</button></p>
        <p><small>The film set keeps the original files byte for byte with the joins; keep it with the patient's records. It is confidential like the films.</small></p>` : ''}
      </main>`;
    const fl = root.querySelector('#films');
    S.films.forEach((f, i) => {
      const d = document.createElement('div'); d.className = 'film';
      const cv = document.createElement('canvas'); const w = 110, h = Math.round(w * f.keep.rows / f.keep.cols); const x = grey(cv, f.keep, w, Math.min(h, 220));
      if (f.ball) { const k = w / f.keep.cols, kk = Math.min(h, 220) / f.keep.rows; x.strokeStyle = f.ballOk ? '#A8FF30' : '#ff4fa3'; x.lineWidth = 2; x.beginPath(); x.ellipse((f.ball.x / f.keep.f) * k, (f.ball.y / f.keep.f) * kk, Math.max(3, f.ball.r / f.keep.f * k), Math.max(3, f.ball.r / f.keep.f * kk), 0, 0, 7); x.stroke(); }
      const m = f.meta || {}, what = `${f.kind === 'dicom' ? 'DICOM' : f.kind.toUpperCase()}, ${f.cols} × ${f.rows}${m.StudyDate ? `, study ${m.StudyDate}` : ''}`;
      const sc = f.how === 'ball' && f.ball ? (f.ballOk ? `Ball ${f.d} mm, confirmed` : `Ball ${f.d} mm found: is the pink circle the ball?`) : f.how === 'line' && f.line ? `Known length ${f.len} mm` : 'No scale of its own: carried through the join (flagged)';
      // the film's size by this scale: a wrong ball (a blob taken for it) shows at once as a film of an impossible size
      const mmpp = f.how === 'ball' && f.ball && f.ball.r > 0 ? f.d / (2 * f.ball.r) : f.how === 'line' && f.line && f.len > 0 ? f.len / Math.hypot(f.line[1].x - f.line[0].x, f.line[1].y - f.line[0].y) : null;
      const wmm = mmpp ? f.cols * mmpp : 0, hmm = mmpp ? f.rows * mmpp : 0, odd = mmpp && (wmm < 150 || wmm > 500 || hmm < 150 || hmm > 520);
      const size = mmpp ? `<p class="${odd ? 'bad' : ''}">By this scale the film is ${Math.round(wmm)} × ${Math.round(hmm)} mm${odd ? ': not the size of an X-ray cassette. This is probably not the ball; tap the ball, or choose "Not the ball".' : '.'}</p>` : '';
      d.innerHTML = `<div class="ctl"><h3>Film ${i + 1} <small>${esc(what)}</small></h3><p>${esc(sc)}</p>${f.ballWarn ? `<p class="warn">Ball: ${esc(f.ballWarn)}</p>` : ''}${size}
        ${f.how === 'ball' && f.ball && !f.ballOk ? `<button data-f="${i}" data-a="ballyes">Yes, that is the ball</button>${f.cands.length > 1 ? `<button class="alt" data-f="${i}" data-a="ballnext">Not the ball</button>` : ''}` : ''}
        <button class="alt" data-f="${i}" data-a="tapball">Tap the ball</button><button class="alt" data-f="${i}" data-a="tapline">Known length</button><button class="alt" data-f="${i}" data-a="carry">No scale</button>
        <label><input type="checkbox" data-f="${i}" data-a="turn"${f.turned ? ' checked' : ''}> Turned 180° (exported upside down)</label>
        <label><input type="checkbox" data-f="${i}" data-a="marker"${f.markerOk ? ' checked' : ''}> Its R/L marker is on the same side as on the other films</label>
        <button class="alt" data-f="${i}" data-a="up"${i ? '' : ' disabled'}>↑</button><button class="alt" data-f="${i}" data-a="down"${i < S.films.length - 1 ? '' : ' disabled'}>↓</button><button class="alt" data-f="${i}" data-a="del">Remove</button></div>`;
      d.insertBefore(cv, d.firstChild); fl.appendChild(d);
    });
    const jl = root.querySelector('#joins');
    if (joined) J.joins.forEach((j, i) => {
      const stn = j.how === 'hand' ? 'hand' : j.status, d = document.createElement('div'); d.className = 'join';
      const usable = ST.joinUsable(j), fixable = j.status === 'failed' || j.status === 'weak' || j.how === 'hand';
      d.innerHTML = `<b>Join ${i + 1}</b> (films ${i + 1} and ${i + 2})<span class="chip ${stn}">${{ matched: 'matched', weak: 'weak', failed: 'not matched', flipped: 'refused: flipped?', inverted: 'refused: inverted grey?', hand: 'placed by hand' }[stn] || stn}</span>
        <p>${esc(j.why || (j.status === 'matched' ? 'Matched on the overlap.' : ''))}${Number.isFinite(j.ncc) ? ` <small>score ${j.ncc.toFixed(2)}${Number.isFinite(j.uyMm) ? `, could slide ${j.uyMm} mm along the bone, ${j.uxMm} mm sideways` : ''}${Number.isFinite(j.dSize) ? `, size disagreement ${(Math.abs(j.dSize) * 100).toFixed(1)} % (${(Math.abs(j.dSize) * STITCH.boneMm).toFixed(1)} mm over ${STITCH.boneMm} mm)` : ''}${j.rot ? `, turned ${j.rot.toFixed(2)}°` : ''}</small>` : ''}</p>
        ${j.status === 'weak' && j.how !== 'hand' ? `<label><input type="checkbox" data-j="${i}" data-a="accept"${j.accepted ? ' checked' : ''}> I have looked at this join below and it is right</label>` : ''}
        ${j.status === 'failed' && j.how !== 'hand' ? `<button data-j="${i}" data-a="hand">Place it by hand</button>` : ''}
        ${(j.status === 'matched' || j.status === 'weak') && j.how !== 'hand' ? `<button class="alt" data-j="${i}" data-a="hand">Not right: place it by hand</button>` : ''}
        ${(j.status === 'flipped' || j.status === 'inverted') ? '<p><small>Not joined: correct the film (turn it, remove a mirrored export, or use the original DICOM) and match again.</small></p>' : ''}
        ${fixable && j.cA ? `<p class="nudge">Move the lower film: <button class="alt" data-j="${i}" data-a="n" data-v="0,-1,0">↑ 1 mm</button><button class="alt" data-j="${i}" data-a="n" data-v="0,1,0">↓ 1 mm</button><button class="alt" data-j="${i}" data-a="n" data-v="-1,0,0">← 1 mm</button><button class="alt" data-j="${i}" data-a="n" data-v="1,0,0">→ 1 mm</button><button class="alt" data-j="${i}" data-a="n" data-v="0,-0.25,0">↑ ¼</button><button class="alt" data-j="${i}" data-a="n" data-v="0,0.25,0">↓ ¼</button><button class="alt" data-j="${i}" data-a="n" data-v="0,0,-0.1">⟲ 0.1°</button><button class="alt" data-j="${i}" data-a="n" data-v="0,0,0.1">⟳ 0.1°</button></p>` : ''}
        ${S.film && j.cA ? `<canvas class="pv" data-seam="${i}"></canvas>` : ''}${usable ? '' : '<p><small>This join is not usable yet.</small></p>'}`;
      jl.appendChild(d);
    });
    if (S.film) {
      const all = root.querySelector('#pvAll'); if (all) { const w = Math.min(420, all.clientWidth || 420); u16Canvas(all, S.film, 0, S.film.rows, w); all.style.maxWidth = w + 'px'; }
      root.querySelectorAll('canvas[data-seam]').forEach(c => { const i = +c.dataset.seam, y = S.film.seams[i], h = 40 / outMm(); u16Canvas(c, S.film, Math.max(0, y - h), Math.min(S.film.rows, y + h), Math.min(900, c.clientWidth || 600)); const g = c.getContext('2d'); g.strokeStyle = '#A8FF30'; g.beginPath(); g.moveTo(0, c.height / 2); g.lineTo(12, c.height / 2); g.moveTo(c.width - 12, c.height / 2); g.lineTo(c.width, c.height / 2); g.stroke(); });
    }
  }
  root.addEventListener('click', async e => {
    const b = e.target.closest('[data-a]'); if (!b || b.tagName === 'SELECT' || (b.tagName === 'INPUT' && b.type === 'checkbox')) return;
    const a = b.dataset.a, f = S.films[+b.dataset.f], ji = +b.dataset.j;
    if (a === 'close') return close();
    if (a === 'add' || a === 'set') { const inp = document.createElement('input'); inp.type = 'file'; inp.multiple = a === 'add'; inp.accept = a === 'add' ? '.dcm,.dicom,application/dicom,image/png,image/jpeg,image/webp' : '.aots,application/zip'; inp.onchange = () => (a === 'add' ? addFiles([...inp.files]) : inp.files[0] && openSet(inp.files[0])); inp.click(); return; }
    if (a === 'join') return join();
    if (a === 'use') return useIt();
    if (a === 'saveset') return saveSet();
    if (a === 'savedcm') { try { const { film, recipe } = finishNow(); const first = S.films.find(x => x.kind === 'dicom'); download(await ST.stitchedDicom(film, recipe, first ? first.meta : {}, S.films.filter(x => x.meta.SOPInstanceUID && x.meta.SOPClassUID).map(x => ({ cls: x.meta.SOPClassUID, uid: x.meta.SOPInstanceUID }))), `AO-stitched-long-leg_${day()}.dcm`, 'application/dicom'); } catch (er) { S.err = er.message; render(); } return; }
    if (a === 'ballyes') { f.ballOk = true; reset(); S.anchor = -1; }
    else if (a === 'ballnext') { f.ci = (f.ci + 1) % f.cands.length; const c = f.cands[f.ci]; f.ball = { x: c.x, y: c.y, r: c.r }; f.ballOk = false; reset(); S.anchor = -1; }
    else if (a === 'tapball') return tapView(+b.dataset.f, 'ball');
    else if (a === 'tapline') return tapView(+b.dataset.f, 'line');
    else if (a === 'carry') { f.how = 'carried'; f.ballOk = false; reset(); S.anchor = -1; }
    else if (a === 'up' || a === 'down') { const i = +b.dataset.f, k = a === 'up' ? i - 1 : i + 1; [S.films[i], S.films[k]] = [S.films[k], S.films[i]]; reset(); S.anchor = -1; S.tick = false; }
    else if (a === 'del') { S.films.splice(+b.dataset.f, 1); reset(); S.anchor = -1; S.tick = false; }
    else if (a === 'hand') { byHand(ji); }
    else if (a === 'n') { const [dx, dy, dr] = b.dataset.v.split(',').map(Number); return nudge(ji, dx, dy, dr); }
    render();
  });
  root.addEventListener('change', e => {
    const b = e.target, a = b.dataset.a, f = S.films[+b.dataset.f];
    if (a === 'half') { S.half = b.checked; reset(); }
    else if (a === 'tick') S.tick = b.checked;
    else if (a === 'turn') { f.turned = b.checked; reset(); S.tick = false; }
    else if (a === 'marker') f.markerOk = b.checked;
    else if (a === 'accept') { S.J.joins[+b.dataset.j].accepted = b.checked; S.res = null; }
    else if (a === 'anchor') S.anchor = +b.value;
    render();
  });
  render();
  return { root, state: S };
}
