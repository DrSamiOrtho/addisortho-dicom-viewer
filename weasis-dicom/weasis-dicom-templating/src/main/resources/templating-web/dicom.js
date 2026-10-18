// Minimal DICOM Part 10 reader for the templating page (10 Oct 2026).
// Reads little-endian files (explicit or implicit VR). The archive sends uncompressed images (WADO-URI with transfer
// syntax 1.2.840.10008.1.2.1, decodeImage). Files opened from disk may be deflated or compressed (JPEG, JPEG lossless,
// JPEG-LS, JPEG 2000, RLE): decodeAny handles those with imageio.js.
// Returns the few attributes templating needs and the first frame as a Float32Array of modality values.
import { compressionOf, decodeFrame, inflate } from './imageio.js';

const EXPLICIT_LONG = new Set(['OB', 'OD', 'OF', 'OL', 'OV', 'OW', 'SQ', 'SV', 'UC', 'UN', 'UR', 'UT', 'UV']);
const TS_IMPLICIT = '1.2.840.10008.1.2';
const TS_EXPLICIT = '1.2.840.10008.1.2.1';
const TS_DEFLATED = '1.2.840.10008.1.2.1.99';

const WANT = {          // tag -> name; everything else is skipped
  '00020010': 'TransferSyntaxUID', '00080005': 'SpecificCharacterSet', '00080016': 'SOPClassUID', '00080018': 'SOPInstanceUID', '00080020': 'StudyDate',
  '00080030': 'StudyTime', '00080090': 'ReferringPhysicianName', '00100030': 'PatientBirthDate', '00100040': 'PatientSex', '00200010': 'StudyID',
  '00100021': 'IssuerOfPatientID',
  '00080050': 'AccessionNumber', '00080060': 'Modality', '00081030': 'StudyDescription', '0008103E': 'SeriesDescription',
  '00100010': 'PatientName', '00100020': 'PatientID', '00180015': 'BodyPartExamined', '00185101': 'ViewPosition',
  '00181110': 'DistanceSourceToDetector', '00181111': 'DistanceSourceToPatient', '00181164': 'ImagerPixelSpacing',
  '00181114': 'EstimatedRadiographicMagnificationFactor', '00280A02': 'PixelSpacingCalibrationType',
  '00280A04': 'PixelSpacingCalibrationDescription', '0020000D': 'StudyInstanceUID', '0020000E': 'SeriesInstanceUID',
  '00200020': 'PatientOrientation', '00200060': 'Laterality', '00200062': 'ImageLaterality',
  '00280002': 'SamplesPerPixel', '00280004': 'PhotometricInterpretation', '00280006': 'PlanarConfiguration',
  '00280010': 'Rows', '00280011': 'Columns', '00280030': 'PixelSpacing', '00280100': 'BitsAllocated',
  '00280101': 'BitsStored', '00280102': 'HighBit', '00280103': 'PixelRepresentation', '00281050': 'WindowCenter', '00281051': 'WindowWidth',
  '00281052': 'RescaleIntercept', '00281053': 'RescaleSlope', '00204000': 'ImageComments', '7FE00010': 'PixelData',
};
const NUMERIC = { US: [2, 'getUint16'], SS: [2, 'getInt16'], UL: [4, 'getUint32'], SL: [4, 'getInt32'], FL: [4, 'getFloat32'], FD: [8, 'getFloat64'] };

export function parseDicom(buffer) {
  const dv = new DataView(buffer), u8 = new Uint8Array(buffer), out = {};
  const ascii = (o, n) => { let s = ''; for (let i = 0; i < n; i++) s += String.fromCharCode(u8[o + i]); return s; };
  let pos = 0, explicit = true;
  if (buffer.byteLength > 132 && ascii(128, 4) === 'DICM') pos = 132;
  else explicit = false;   // no preamble: assume a bare implicit-VR dataset

  function value(vr, o, len) {
    if (NUMERIC[vr]) {
      const [size, fn] = NUMERIC[vr], vals = [];
      for (let i = 0; i + size <= len; i += size) vals.push(dv[fn](o + i, true));
      return vals.length === 1 ? vals[0] : vals;
    }
    const s = ascii(o, len).replace(/[\0 ]+$/, '').replace(/^ +/, '');
    return s;
  }
  function implicitVR(tag) {   // the few numeric tags we read from implicit-VR files
    return ['00280002', '00280006', '00280010', '00280011', '00280100', '00280101', '00280102', '00280103'].includes(tag) ? 'US' : 'XX';
  }
  // (0010,0024) Issuer of Patient ID Qualifiers: the identity parts of each item (universal entity ID and its type,
  // identifier type code), read the same way from explicit and implicit VR files (Sol, 11 Oct: implicit lost them).
  // Nested code sequences inside an item are skipped.
  function qualifiers(o, len, explicitVR) {
    const end = len === 0xFFFFFFFF ? buffer.byteLength : o + len, items = [];
    while (o + 8 <= end) {
      const g = dv.getUint16(o, true), e = dv.getUint16(o + 2, true), il = dv.getUint32(o + 4, true); o += 8;
      if (g === 0xFFFE && e === 0xE0DD) break;
      if (g !== 0xFFFE || e !== 0xE000) break;
      const iend = il === 0xFFFFFFFF ? end : o + il, it = {};
      while (o + 8 <= iend) {
        const g2 = dv.getUint16(o, true), e2 = dv.getUint16(o + 2, true);
        if (g2 === 0xFFFE && e2 === 0xE00D) { o += 8; break; }
        const t2 = ((g2 << 16) | e2) >>> 0; let ln;
        if (explicitVR) { const vr = ascii(o + 4, 2); if (EXPLICIT_LONG.has(vr)) { ln = dv.getUint32(o + 8, true); o += 12; } else { ln = dv.getUint16(o + 6, true); o += 8; } }
        else { ln = dv.getUint32(o + 4, true); o += 8; }
        if (ln === 0xFFFFFFFF) { o = walk(o, buffer.byteLength, 2, explicitVR); continue; }
        if (t2 === 0x00400032) it.ueid = value('XX', o, ln); else if (t2 === 0x00400033) it.type = value('XX', o, ln); else if (t2 === 0x00400035) it.idType = value('XX', o, ln);
        o += ln;
      }
      if (il !== 0xFFFFFFFF) o = iend;
      items.push(it);
    }
    return { items, next: len === 0xFFFFFFFF ? o : end };
  }
  // Walk elements from `o` to `end`. Sequences and items are walked, never stored (depth > 0 is skipped).
  function walk(o, end, depth, isExplicit) {
    while (o + 8 <= end) {
      const g = dv.getUint16(o, true), e = dv.getUint16(o + 2, true);
      const tag = (g.toString(16).padStart(4, '0') + e.toString(16).padStart(4, '0')).toUpperCase();
      if (g === 0xFFFE) {                                  // item, item delimiter, sequence delimiter
        const len = dv.getUint32(o + 4, true); o += 8;
        if (e === 0xE0DD || e === 0xE00D) return o;          // end of an undefined-length sequence or item
        if (len === 0xFFFFFFFF) o = walk(o, end, depth + 1, isExplicit);
        else o += len;
        continue;
      }
      const exp = g === 0x0002 ? true : isExplicit;
      let vr, len;
      if (exp) {
        vr = ascii(o + 4, 2);
        if (EXPLICIT_LONG.has(vr)) { len = dv.getUint32(o + 8, true); o += 12; }
        else { len = dv.getUint16(o + 6, true); o += 8; }
      } else { vr = implicitVR(tag); len = dv.getUint32(o + 4, true); o += 8; }
      if (g !== 0x0002 && !exp && tag === '7FE00010') vr = 'OW';
      if (depth === 0 && tag === '00100024') { const q = qualifiers(o, len, exp); out.IssuerQualifiers = q.items; o = q.next; continue; }
      if (len === 0xFFFFFFFF) {
        if (tag === '7FE00010' && depth === 0) {             // encapsulated (compressed) pixel data: items = fragments
          const frags = [];
          while (o + 8 <= end) {
            const ig = dv.getUint16(o, true), ie = dv.getUint16(o + 2, true), il = dv.getUint32(o + 4, true); o += 8;
            if (ig !== 0xFFFE || ie === 0xE0DD) break;
            frags.push({ offset: o, length: il }); o += il;
          }
          out.PixelData = { fragments: frags.slice(1) };     // the first item is the offset table
          continue;
        }
        o = walk(o, end, depth + 1, isExplicit);            // undefined-length sequence
        continue;
      }
      if (depth === 0 && WANT[tag]) {
        out[WANT[tag]] = tag === '7FE00010' ? { offset: o, length: len } : vr === 'SQ' ? null : value(vr, o, len);
      }
      o += len;
    }
    return o;
  }
  // file meta (group 0002) first, then the dataset in the transfer syntax it names
  let o = pos;
  if (pos === 132) {
    while (o + 8 <= buffer.byteLength && dv.getUint16(o, true) === 0x0002) {
      const vr = ascii(o + 4, 2); let len, start;
      if (EXPLICIT_LONG.has(vr)) { len = dv.getUint32(o + 8, true); start = o + 12; } else { len = dv.getUint16(o + 6, true); start = o + 8; }
      const tag = ('0002' + dv.getUint16(o + 2, true).toString(16).padStart(4, '0')).toUpperCase();
      if (WANT[tag]) out[WANT[tag]] = value(vr, start, len);
      o = start + len;
    }
    const ts = out.TransferSyntaxUID || TS_EXPLICIT;
    if (ts === TS_IMPLICIT) explicit = false;
    else if (ts === TS_EXPLICIT || compressionOf(ts)) explicit = true;
    else if (ts === TS_DEFLATED) { out.deflatedAt = o; return out; }
    else throw new Error(`Transfer syntax ${ts} is not supported (big-endian or unknown); export the image as uncompressed DICOM, PNG or JPEG.`);
  }
  walk(o, buffer.byteLength, 0, explicit);
  return out;
}

const num = (v, i = 0) => {
  if (v === undefined || v === null || v === '') return undefined;
  if (typeof v === 'number') return v;
  if (Array.isArray(v)) return v[i];
  const parts = String(v).split('\\'); const x = parseFloat(parts[Math.min(i, parts.length - 1)]);
  return Number.isFinite(x) ? x : undefined;
};

// Decode the first frame to modality values (rescale applied). Returns {cols, rows, data, min, max, wc, ww, invert, meta}.
// Uncompressed only (what the archive sends); decodeAny below also takes compressed and deflated files.
export function decodeImage(buffer) {
  const t = parseDicom(buffer);
  if (t.deflatedAt !== undefined || (t.PixelData && t.PixelData.fragments)) throw new Error('This image is compressed; ask for it uncompressed.');
  return toImage(t, rawSamples(buffer, t));
}

export async function decodeAny(buffer) {
  let t = parseDicom(buffer);
  if (t.deflatedAt !== undefined) {   // deflated dataset: inflate it behind the file meta, then read it as explicit VR
    const body = await inflate(new Uint8Array(buffer, t.deflatedAt), true), head = new Uint8Array(buffer, 0, t.deflatedAt);
    const all = new Uint8Array(head.length + body.length); all.set(head); all.set(body, head.length);
    patchTS(all, t.deflatedAt); buffer = all.buffer; t = parseDicom(buffer);
  }
  if (!t.PixelData) throw new Error('No image in this DICOM object.');
  if (!t.PixelData.fragments) return toImage(t, rawSamples(buffer, t));
  const fr = t.PixelData.fragments, total = fr.reduce((n, f) => n + f.length, 0), bytes = new Uint8Array(total);
  let o = 0; for (const f of fr) { bytes.set(new Uint8Array(buffer, f.offset, f.length), o); o += f.length; }   // one frame
  const info = { rows: num(t.Rows), cols: num(t.Columns), bitsAllocated: num(t.BitsAllocated), bitsStored: num(t.BitsStored),
    samples: num(t.SamplesPerPixel) || 1, signed: num(t.PixelRepresentation) === 1 };
  const { data } = await decodeFrame(t.TransferSyntaxUID, bytes, info);
  return toImage({ ...t, PlanarConfiguration: 0 }, data, `compressed (${compressionOf(t.TransferSyntaxUID)})`);
}

// file meta says deflated; after inflating, say explicit VR little endian so parseDicom reads the dataset
function patchTS(u8, end) {
  const want = '1.2.840.10008.1.2.1.99';
  for (let i = 132; i < end - want.length; i++) {
    let hit = true; for (let k = 0; k < want.length; k++) if (u8[i + k] !== want.charCodeAt(k)) { hit = false; break; }
    if (hit) { u8[i + 19] = 0; u8[i + 20] = 0; u8[i + 21] = 0; return; }   // "...1.2.1" then NUL padding
  }
}

function rawSamples(buffer, t) {   // the stored words as they are; toImage applies Bits Stored, High Bit and sign
  const rows = num(t.Rows), cols = num(t.Columns), bits = num(t.BitsAllocated), spp = num(t.SamplesPerPixel) || 1;
  const n = rows * cols;
  const src = buffer.slice(t.PixelData.offset, t.PixelData.offset + Math.min(t.PixelData.length, n * spp * bits / 8));
  if (bits === 16) return new Uint16Array(src);
  if (bits === 8) return new Uint8Array(src);
  throw new Error(`${bits}-bit pixels are not supported.`);
}

// One stored word to its value: drop the unused high bits (DICOM forbids assuming they are zero), then take the sign
// from the highest stored bit when Pixel Representation is 1 (PS3.5 chapter 8).
function sampleDecoder(t, wordBits) {
  const bs = num(t.BitsStored) || wordBits, hb = num(t.HighBit) ?? bs - 1, shift = Math.max(0, hb - bs + 1);
  const mask = bs >= 32 ? 0xFFFFFFFF : (2 ** bs) - 1, signed = num(t.PixelRepresentation) === 1, half = 2 ** (bs - 1), full = 2 ** bs;
  const wmask = wordBits === 8 ? 0xFF : 0xFFFF;
  return v => { let x = ((v & wmask) >>> shift) & mask; if (signed && x >= half) x -= full; return x; };
}

function toImage(t, a, how) {
  const rows = num(t.Rows), cols = num(t.Columns), spp = num(t.SamplesPerPixel) || 1, n = rows * cols;
  const slope = num(t.RescaleSlope) ?? 1, icpt = num(t.RescaleIntercept) ?? 0, data = new Float32Array(n);
  const word = a.BYTES_PER_ELEMENT === 1 ? 8 : 16, dec = spp === 1 ? sampleDecoder(t, word) : null;
  if (spp === 3) {
    const planar = num(t.PlanarConfiguration) === 1;
    for (let i = 0; i < n; i++) {
      const r = planar ? a[i] : a[3 * i], g = planar ? a[n + i] : a[3 * i + 1], b = planar ? a[2 * n + i] : a[3 * i + 2];
      data[i] = 0.299 * r + 0.587 * g + 0.114 * b;
    }
  } else for (let i = 0; i < n; i++) data[i] = dec(a[i]) * slope + icpt;
  let min = Infinity, max = -Infinity;
  for (let i = 0; i < n; i++) { const v = data[i]; if (v < min) min = v; if (v > max) max = v; }
  let wc = num(t.WindowCenter), ww = num(t.WindowWidth);
  if (!(ww > 1)) {   // no window in the file: 1st to 99th percentile of the pixel values, so the film is not washed out
    const step = Math.max(1, Math.floor(n / 200000)), smp = [];
    for (let i = 0; i < n; i += step) smp.push(data[i]);
    smp.sort((x, y) => x - y);
    const lo = smp[Math.floor(smp.length * 0.01)], hi = smp[Math.floor(smp.length * 0.99)];
    wc = (lo + hi) / 2; ww = Math.max(1, hi - lo);
  }
  let ips = t.ImagerPixelSpacing ? [num(t.ImagerPixelSpacing, 0), num(t.ImagerPixelSpacing, 1)] : null;
  let ps = t.PixelSpacing ? [num(t.PixelSpacing, 0), num(t.PixelSpacing, 1)] : null;
  // Spacing is [between rows, between columns]. Pixels that are not square would bend every length, angle and implant
  // outline: resample the columns so a pixel is the same size both ways (the row spacing), and say so.
  const sp = ps || ips; let note = '', out = { cols, data };
  if (sp && sp[0] > 0 && sp[1] > 0 && Math.abs(sp[1] / sp[0] - 1) > 0.005) {
    out = squarePixels(data, cols, rows, sp[1] / sp[0]);
    note = `pixels ${sp[0]} x ${sp[1]} mm resampled to square`;
    if (ps) ps = [ps[0], ps[0]]; if (ips) ips = [ips[0], ips[0]];
  }
  return {
    cols: out.cols, rows, data: out.data, min, max, wc, ww, invert: t.PhotometricInterpretation === 'MONOCHROME1', resampleNote: note,
    meta: {
      ...Object.fromEntries(Object.entries(t).filter(([k]) => k !== 'PixelData' && k !== 'deflatedAt')),
      imagerPixelSpacing: ips, pixelSpacing: ps, format: how || 'DICOM',
      magnification: num(t.EstimatedRadiographicMagnificationFactor),
      sid: num(t.DistanceSourceToDetector), sod: num(t.DistanceSourceToPatient),
    },
  };
}

// Column resampling by the factor col spacing / row spacing (linear), so pixels become square.
export function squarePixels(data, cols, rows, factor) {
  const nc = Math.max(1, Math.round(cols * factor)), out = new Float32Array(nc * rows);
  for (let y = 0; y < rows; y++) for (let x = 0; x < nc; x++) {
    const sx = Math.min(cols - 1, Math.max(0, (x + 0.5) / factor - 0.5)), x0 = Math.floor(sx), x1 = Math.min(cols - 1, x0 + 1), f = sx - x0;
    out[y * nc + x] = data[y * cols + x0] * (1 - f) + data[y * cols + x1] * f;
  }
  return { cols: nc, data: out };
}

// PNG/JPEG dropped on the page (no DICOM data): grey values from the picture.
export function decodeBitmap(bitmap) {
  const c = document.createElement('canvas'); c.width = bitmap.width; c.height = bitmap.height;
  const g = c.getContext('2d'); g.drawImage(bitmap, 0, 0);
  const px = g.getImageData(0, 0, c.width, c.height).data, n = c.width * c.height, data = new Float32Array(n);
  for (let i = 0; i < n; i++) data[i] = 0.299 * px[4 * i] + 0.587 * px[4 * i + 1] + 0.114 * px[4 * i + 2];
  return { cols: c.width, rows: c.height, data, min: 0, max: 255, wc: 128, ww: 256, invert: false, meta: { Modality: 'picture', format: '8-bit picture' } };
}
