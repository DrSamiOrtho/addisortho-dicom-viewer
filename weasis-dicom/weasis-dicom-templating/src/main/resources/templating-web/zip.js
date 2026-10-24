// A small ZIP writer and reader for the templating project file (.aotp; round 6, 13 Oct 2026, contract
// CONTRACT-2026-10-13-projects.md section 5, after Astra's review: one portable file, the original image kept as its own
// binary entry rather than base64 in the JSON, entry count, sizes and names bounded). No other ZIP is read or written.
// Writer: entries stored (no compression: the image is already compressed or is raw DICOM). Reader: stored and deflate
// entries (DecompressionStream "deflate-raw"), every name a plain file name (no "/", "\", ".." or control characters),
// at most maxEntries entries, the expanded total at most maxTotal bytes (checked against the directory before anything
// is expanded, and again while expanding, so a forged size cannot make it expand further), each entry's CRC-32 checked.
// No ZIP64, split or encrypted archives. Plain functions, no page or DOM: test.mjs runs them in Node.

export class ZipError extends Error {}

const CRC_T = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; }
  return t;
})();
// CRC-32 (the ZIP one) of a Uint8Array
export function crc32(u8) {
  let c = 0xFFFFFFFF;
  for (let i = 0, n = u8.length; i < n; i++) c = CRC_T[(c ^ u8[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

// a name the reader accepts and the writer writes: one plain file name, nothing that could be a path
const NAME_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;
export function okName(name) { return typeof name === 'string' && NAME_RE.test(name) && !name.includes('..'); }

function dosTime(d) {
  const y = Math.max(1980, d.getFullYear());
  return { time: (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1), date: ((y - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate() };
}

// The archive as a list of parts (headers and the entries' own bytes, never copied), for new Blob(parts) or zipBytes().
// entries: [{name, data: Uint8Array, crc?}] (crc: when already known, the image's is kept so it is worked out once)
export function zipParts(entries, when = new Date()) {
  const enc = new TextEncoder(), parts = [], central = [], { time, date } = dosTime(when);
  let off = 0;
  for (const e of entries) {
    if (!okName(e.name)) throw new ZipError(`not a plain file name: ${e.name}`);
    const name = enc.encode(e.name), n = e.data.length, crc = e.crc ?? crc32(e.data);
    if (n > 0xFFFFFFF0 || off > 0xFFFFFFF0) throw new ZipError('too large for this file format');
    const lh = new Uint8Array(30 + name.length), v = new DataView(lh.buffer);
    v.setUint32(0, 0x04034b50, true); v.setUint16(4, 20, true); v.setUint16(6, 0x0800, true); v.setUint16(8, 0, true);
    v.setUint16(10, time, true); v.setUint16(12, date, true); v.setUint32(14, crc, true); v.setUint32(18, n, true); v.setUint32(22, n, true);
    v.setUint16(26, name.length, true); v.setUint16(28, 0, true); lh.set(name, 30);
    const ch = new Uint8Array(46 + name.length), w = new DataView(ch.buffer);
    w.setUint32(0, 0x02014b50, true); w.setUint16(4, 20, true); w.setUint16(6, 20, true); w.setUint16(8, 0x0800, true); w.setUint16(10, 0, true);
    w.setUint16(12, time, true); w.setUint16(14, date, true); w.setUint32(16, crc, true); w.setUint32(20, n, true); w.setUint32(24, n, true);
    w.setUint16(28, name.length, true); w.setUint32(42, off, true); ch.set(name, 46);
    parts.push(lh, e.data); central.push(ch); off += lh.length + n;
  }
  const cd = central.reduce((s, c) => s + c.length, 0), end = new Uint8Array(22), x = new DataView(end.buffer);
  x.setUint32(0, 0x06054b50, true); x.setUint16(8, entries.length, true); x.setUint16(10, entries.length, true); x.setUint32(12, cd, true); x.setUint32(16, off, true);
  return [...parts, ...central, end];
}
export function concat(parts) {
  const out = new Uint8Array(parts.reduce((s, p) => s + p.length, 0)); let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}
export const zipBytes = (entries, when) => concat(zipParts(entries, when));

// deflate-raw data expanded, refused as soon as it passes limit bytes
async function inflateRaw(bytes, limit) {
  if (typeof DecompressionStream === 'undefined') throw new ZipError('this browser cannot read a compressed project file');
  const rd = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw')).getReader(), chunks = [];
  let n = 0;
  try {
    for (;;) {
      const { done, value } = await rd.read(); if (done) break;
      n += value.length;
      if (n > limit) { try { await rd.cancel(); } catch (e) { /* closed */ } throw new ZipError('an entry expands beyond the size it states'); }
      chunks.push(value);
    }
  } catch (e) { if (e instanceof ZipError) throw e; throw new ZipError('an entry is damaged (it does not expand)'); }
  return concat(chunks);
}

// The entries of an archive: Map name -> Uint8Array (a stored entry is a view into u8, not a copy).
export async function unzip(u8, { maxEntries = 4, maxTotal = 85 * 1024 * 1024 } = {}) {
  if (!(u8 instanceof Uint8Array) || u8.length < 22) throw new ZipError('not a project file (too short)');
  const v = new DataView(u8.buffer, u8.byteOffset, u8.byteLength), dec = new TextDecoder('utf-8', { fatal: true });
  let e = -1;
  for (let i = u8.length - 22, stop = Math.max(0, u8.length - 22 - 65535); i >= stop; i--) if (v.getUint32(i, true) === 0x06054b50) { e = i; break; }
  if (e < 0) throw new ZipError('not a project file (no ZIP directory)');
  const n = v.getUint16(e + 10, true), cdSize = v.getUint32(e + 12, true), cdOff = v.getUint32(e + 16, true);
  if (v.getUint16(e + 4, true) || v.getUint16(e + 6, true) || v.getUint16(e + 8, true) !== n) throw new ZipError('a split archive is not a project file');
  if (n === 0xFFFF || cdOff === 0xFFFFFFFF || cdSize === 0xFFFFFFFF) throw new ZipError('ZIP64 archives are not project files');
  if (n === 0) throw new ZipError('the project file is empty');
  if (n > maxEntries) throw new ZipError(`the project file has ${n} entries (at most ${maxEntries})`);
  if (cdOff + cdSize > e) throw new ZipError('the project file is damaged (directory)');
  const items = []; let p = cdOff, total = 0;
  for (let k = 0; k < n; k++) {
    if (p + 46 > cdOff + cdSize || v.getUint32(p, true) !== 0x02014b50) throw new ZipError('the project file is damaged (directory entry)');
    const flags = v.getUint16(p + 8, true), method = v.getUint16(p + 10, true), crc = v.getUint32(p + 16, true), csize = v.getUint32(p + 20, true), usize = v.getUint32(p + 24, true);
    const nl = v.getUint16(p + 28, true), xl = v.getUint16(p + 30, true), cl = v.getUint16(p + 32, true), lho = v.getUint32(p + 42, true);
    if (p + 46 + nl > cdOff + cdSize) throw new ZipError('the project file is damaged (directory entry)');
    let name; try { name = dec.decode(u8.subarray(p + 46, p + 46 + nl)); } catch (er) { throw new ZipError('an entry name is not text'); }
    p += 46 + nl + xl + cl;
    if (!okName(name)) throw new ZipError(`unexpected entry "${name.slice(0, 40)}" (folders and ".." are not allowed)`);
    if (items.some(it => it.name === name)) throw new ZipError(`entry "${name}" is there twice`);
    if (flags & 1) throw new ZipError('encrypted entries are not allowed');
    if (method !== 0 && method !== 8) throw new ZipError(`entry "${name}" uses a compression this page does not read`);
    if (method === 0 && csize !== usize) throw new ZipError(`entry "${name}" is damaged (sizes)`);
    total += usize;
    if (total > maxTotal) throw new ZipError(`the project file expands to more than ${Math.round(maxTotal / 1048576)} MB`);
    items.push({ name, method, crc, csize, usize, lho });
  }
  const out = new Map();
  for (const it of items) {
    const q = it.lho;
    if (q + 30 > cdOff || v.getUint32(q, true) !== 0x04034b50) throw new ZipError(`entry "${it.name}" is damaged (header)`);
    const nl = v.getUint16(q + 26, true), xl = v.getUint16(q + 28, true), start = q + 30 + nl + xl, end = start + it.csize;
    let lname = ''; try { lname = dec.decode(u8.subarray(q + 30, q + 30 + nl)); } catch (er) { lname = ''; }
    if (lname !== it.name) throw new ZipError(`entry "${it.name}" is damaged (its two names differ)`);
    if (end > cdOff) throw new ZipError(`entry "${it.name}" runs past the end of the data`);
    const raw = u8.subarray(start, end), data = it.method === 0 ? raw : await inflateRaw(raw, it.usize);
    if (data.length !== it.usize) throw new ZipError(`entry "${it.name}" is damaged (size)`);
    if (crc32(data) !== it.crc) throw new ZipError(`entry "${it.name}" is damaged (checksum)`);
    out.set(it.name, data);
  }
  return out;
}
