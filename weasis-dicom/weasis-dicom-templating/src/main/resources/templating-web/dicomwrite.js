// The saved templating picture as a DICOM Secondary Capture object (11 Oct 2026), so it can join the patient's study
// in the archive (PACS) next to the X-ray it was planned on. Explicit VR little endian, RGB, 8 bits.
// The patient and study attributes are copied byte for byte from the X-ray (same character set), with a new series and
// instance; the source image is referenced. Pure function: test.mjs reads the result back with dicom.js.

const SC = '1.2.840.10008.5.1.4.1.1.7', EXPLICIT_LE = '1.2.840.10008.1.2.1', JPEG_BASELINE = '1.2.840.10008.1.2.4.50';
export const IMPLEMENTATION_UID = '2.25.307251432110847562391027463915201146871';   // this writer (random, fixed)

// A UID nobody else will make: 2.25 followed by a random 128-bit number (ISO/IEC 9834-8).
export function newUid(rand = n => crypto.getRandomValues(new Uint8Array(n))) {
  let v = 0n; for (const b of rand(16)) v = (v << 8n) | BigInt(b);
  return '2.25.' + v.toString();
}
const LONG = new Set(['OB', 'OW', 'SQ', 'UN', 'UT', 'UC', 'UR']);
const bytesOf = s => Uint8Array.from(String(s), c => c.charCodeAt(0) & 0xFF);   // the X-ray's own bytes back as they were
export function el(tag, vr, value) {   // value: string, number (US/UL), Uint8Array, or array of items (SQ)
  let body;
  if (vr === 'SQ') {
    const items = value.map(it => { const inner = concat(it); const h = new DataView(new ArrayBuffer(8)); h.setUint16(0, 0xFFFE, true); h.setUint16(2, 0xE000, true); h.setUint32(4, inner.length, true); return concat([new Uint8Array(h.buffer), inner]); });
    body = concat(items);
  } else if (vr === 'US') { body = new Uint8Array(2); new DataView(body.buffer).setUint16(0, value, true); }
  else if (vr === 'UL') { body = new Uint8Array(4); new DataView(body.buffer).setUint32(0, value, true); }
  else if (value instanceof Uint8Array) body = value;
  else body = bytesOf(value ?? '');
  if (body.length % 2) { const p = new Uint8Array(body.length + 1); p.set(body); p[body.length] = vr === 'UI' || vr === 'OB' ? 0 : 0x20; body = p; }
  const g = parseInt(tag.slice(0, 4), 16), e = parseInt(tag.slice(4), 16), long = LONG.has(vr);
  const h = new DataView(new ArrayBuffer(long ? 12 : 8));
  h.setUint16(0, g, true); h.setUint16(2, e, true); h.setUint8(4, vr.charCodeAt(0)); h.setUint8(5, vr.charCodeAt(1));
  if (long) h.setUint32(8, body.length, true); else h.setUint16(6, body.length, true);
  return concat([new Uint8Array(h.buffer), body]);
}
export function concat(parts) { const n = parts.reduce((a, p) => a + p.length, 0), o = new Uint8Array(n); let i = 0; for (const p of parts) { o.set(p, i); i += p.length; } return o; }
const da = d => `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
const tm = d => `${String(d.getHours()).padStart(2, '0')}${String(d.getMinutes()).padStart(2, '0')}${String(d.getSeconds()).padStart(2, '0')}`;

// Pixels: either jpeg (a baseline JPEG file's bytes, as a canvas makes them; stored encapsulated, about 20 times
// smaller) or rgba (Uint8ClampedArray from a canvas, width x height x 4; stored uncompressed). meta: the X-ray's
// attributes (dicom.js), may be empty for a picture opened from disk. Returns {bytes: a DICOM Part 10 file, uids}.
export function secondaryCapture({ rgba, jpeg, width, height, meta = {}, seriesDescription = 'Templating plan (draft)', comments = '', now = new Date(), uid = newUid }) {
  let pixels;
  if (jpeg) {   // encapsulated: empty offset table, one fragment, sequence delimiter (PS3.5 A.4)
    const item = (b) => { const h = new DataView(new ArrayBuffer(8)); h.setUint16(0, 0xFFFE, true); h.setUint16(2, 0xE000, true); h.setUint32(4, b.length, true); return concat([new Uint8Array(h.buffer), b]); };
    const frag = jpeg.length % 2 ? concat([jpeg, new Uint8Array(1)]) : jpeg;
    const head = new DataView(new ArrayBuffer(12)); head.setUint16(0, 0x7FE0, true); head.setUint16(2, 0x0010, true); head.setUint8(4, 79); head.setUint8(5, 66); head.setUint32(8, 0xFFFFFFFF, true);
    const end = new DataView(new ArrayBuffer(8)); end.setUint16(0, 0xFFFE, true); end.setUint16(2, 0xE0DD, true);
    pixels = concat([new Uint8Array(head.buffer), item(new Uint8Array(0)), item(frag), new Uint8Array(end.buffer)]);
  } else {
    const rgb = new Uint8Array(width * height * 3);
    for (let i = 0, j = 0; i < width * height; i++, j += 3) { rgb[j] = rgba[4 * i]; rgb[j + 1] = rgba[4 * i + 1]; rgb[j + 2] = rgba[4 * i + 2]; }
    pixels = el('7FE00010', 'OB', rgb);
  }
  const sop = uid(), series = uid(), study = meta.StudyInstanceUID || uid();
  const src = meta.SOPInstanceUID && meta.SOPClassUID ? [[el('00081150', 'UI', meta.SOPClassUID), el('00081155', 'UI', meta.SOPInstanceUID)]] : null;
  const ds = [
    meta.SpecificCharacterSet ? el('00080005', 'CS', meta.SpecificCharacterSet) : null,
    el('00080008', 'CS', 'DERIVED\\SECONDARY'), el('00080012', 'DA', da(now)), el('00080013', 'TM', tm(now)),
    el('00080016', 'UI', SC), el('00080018', 'UI', sop),
    el('00080020', 'DA', meta.StudyDate || ''), el('00080030', 'TM', meta.StudyTime || ''), el('00080050', 'SH', meta.AccessionNumber || ''),
    el('00080060', 'CS', 'OT'), el('00080064', 'CS', 'WSD'), el('00080070', 'LO', 'Addis Ortho'),
    el('00080090', 'PN', meta.ReferringPhysicianName || ''), el('00081030', 'LO', meta.StudyDescription || ''), el('0008103E', 'LO', seriesDescription),
    el('00082111', 'ST', 'X-ray templating plan drawn on the image, with its report. Not true size. Draft for the surgeon.'),
    src ? el('00082112', 'SQ', src) : null,
    el('00100010', 'PN', meta.PatientName || ''), el('00100020', 'LO', meta.PatientID || ''),
    meta.IssuerOfPatientID ? el('00100021', 'LO', meta.IssuerOfPatientID) : null,   // the record number's namespace (Astra, 11 Oct)
    Array.isArray(meta.IssuerQualifiers) && meta.IssuerQualifiers.length ? el('00100024', 'SQ', meta.IssuerQualifiers.map(q => [   // (0010,0024)
      q.ueid ? el('00400032', 'UT', q.ueid) : null, q.type ? el('00400033', 'CS', q.type) : null, q.idType ? el('00400035', 'CS', q.idType) : null].filter(Boolean))) : null,
    el('00100030', 'DA', meta.PatientBirthDate || ''), el('00100040', 'CS', meta.PatientSex || ''),
    el('0020000D', 'UI', study), el('0020000E', 'UI', series), el('00200010', 'SH', meta.StudyID || ''),
    el('00200011', 'IS', '990'), el('00200013', 'IS', '1'), el('00200020', 'CS', ''),
    comments ? el('00204000', 'LT', comments) : null,
    el('00280002', 'US', 3), el('00280004', 'CS', jpeg ? 'YBR_FULL_422' : 'RGB'), el('00280006', 'US', 0),
    el('00280010', 'US', height), el('00280011', 'US', width),
    el('00280100', 'US', 8), el('00280101', 'US', 8), el('00280102', 'US', 7), el('00280103', 'US', 0),
    el('00280301', 'CS', 'YES'),
    jpeg ? el('00282110', 'CS', '01') : null, jpeg ? el('00282114', 'CS', 'ISO_10918_1') : null,
    pixels,
  ].filter(Boolean);
  const metaEls = [el('00020001', 'OB', new Uint8Array([0, 1])), el('00020002', 'UI', SC), el('00020003', 'UI', sop),
    el('00020010', 'UI', jpeg ? JPEG_BASELINE : EXPLICIT_LE), el('00020012', 'UI', IMPLEMENTATION_UID), el('00020013', 'SH', 'AOC_TPL_1')];
  const group = concat(metaEls);
  return { bytes: concat([new Uint8Array(128), bytesOf('DICM'), el('00020000', 'UL', group.length), group, ...ds]), sop, series, study };
}
