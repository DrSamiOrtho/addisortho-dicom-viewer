// Opening X-rays from disk at the best quality the file holds (10 Oct 2026).
//   DICOM: uncompressed, deflated, RLE, JPEG baseline (8-bit), JPEG lossless, JPEG-LS and JPEG 2000. The decoders for
//          the last three load only when such a file is opened (vendor/, pinned versions in vendor/VERSIONS.md).
//   PNG:   16-bit greyscale or colour PNGs are read at full depth here (the browser would cut them to 8 bits);
//          8-bit PNG and JPEG go through the browser's own decoder.
// Everything stays in this browser tab; nothing is uploaded.

const TS = {
  '1.2.840.10008.1.2.4.50': 'jpeg', '1.2.840.10008.1.2.4.51': 'jpeg',
  '1.2.840.10008.1.2.4.57': 'lossless', '1.2.840.10008.1.2.4.70': 'lossless',
  '1.2.840.10008.1.2.4.80': 'jls', '1.2.840.10008.1.2.4.81': 'jls',
  '1.2.840.10008.1.2.4.90': 'j2k', '1.2.840.10008.1.2.4.91': 'j2k',
  '1.2.840.10008.1.2.5': 'rle',
};
export const compressionOf = ts => TS[ts];

// Decode one compressed frame to raw samples. Returns {data: typed array, bits} (samples interleaved if colour).
export async function decodeFrame(ts, bytes, info) {
  const kind = TS[ts];
  if (kind === 'jpeg') {
    if ((info.bitsStored || 8) > 8) throw new Error('12-bit JPEG (extended) cannot be decoded in the browser; export the image uncompressed or open it from the archive.');
    const bmp = await createImageBitmap(new Blob([bytes], { type: 'image/jpeg' }));
    const c = document.createElement('canvas'); c.width = bmp.width; c.height = bmp.height;
    const g = c.getContext('2d'); g.drawImage(bmp, 0, 0); const px = g.getImageData(0, 0, c.width, c.height).data;
    const spp = info.samples || 1, out = new Uint8Array(c.width * c.height * spp);
    for (let i = 0, n = c.width * c.height; i < n; i++) for (let s = 0; s < spp; s++) out[i * spp + s] = px[4 * i + s];
    return { data: out, bits: 8 };
  }
  if (kind === 'lossless') {
    const { Decoder } = await import('./vendor/jpeg-lossless.js');
    const ab = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
    const nb = (info.bitsAllocated || 16) > 8 ? 2 : 1, out = new Decoder().decode(ab, 0, ab.byteLength, nb);
    const raw = out.buffer ? out.buffer : out;
    return { data: nb === 2 ? (info.signed ? new Int16Array(raw) : new Uint16Array(raw)) : new Uint8Array(raw), bits: nb * 8 };
  }
  if (kind === 'jls' || kind === 'j2k') {
    const mod = await codec(kind);
    const dec = new (kind === 'jls' ? mod.JpegLSDecoder : mod.J2KDecoder)();
    try {
      dec.getEncodedBuffer(bytes.length).set(bytes);
      dec.decode();
      const fi = dec.getFrameInfo(), d = dec.getDecodedBuffer();
      const copy = new Uint8Array(d.length); copy.set(d);                 // out of the decoder's memory before delete()
      const bits = fi.bitsPerSample > 8 ? 16 : 8, signed = info.signed || fi.isSigned;
      return { data: bits === 16 ? (signed ? new Int16Array(copy.buffer) : new Uint16Array(copy.buffer)) : new Uint8Array(copy.buffer), bits };
    } finally { dec.delete(); }
  }
  if (kind === 'rle') return { data: rle(bytes, info), bits: info.bitsAllocated };
  throw new Error(`Compression ${ts} is not supported here; export the image uncompressed, or open it from the archive.`);
}

const loaded = {};
function codec(kind) {   // classic scripts that define a global factory; load once
  if (loaded[kind]) return loaded[kind];
  const name = kind === 'jls' ? 'charlswasm_decode' : 'openjpegwasm_decode', global = kind === 'jls' ? 'CharLSWASM' : 'OpenJPEGWASM';
  loaded[kind] = new Promise((ok, fail) => {
    const s = document.createElement('script'); s.src = `vendor/${name}.js`;
    s.onload = () => window[global]({ locateFile: f => `vendor/${f}` }).then(ok, fail);
    s.onerror = () => fail(new Error(`could not load the ${kind === 'jls' ? 'JPEG-LS' : 'JPEG 2000'} decoder`));
    document.head.appendChild(s);
  });
  return loaded[kind];
}

// DICOM RLE (PS3.5 annex G): a 64-byte header of segment offsets, each segment PackBits; one segment per byte plane.
function rle(bytes, info) {
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength), nseg = dv.getUint32(0, true);
  const offs = []; for (let i = 0; i < nseg; i++) offs.push(dv.getUint32(4 + 4 * i, true));
  const n = info.rows * info.cols, bpp = info.bitsAllocated / 8, spp = info.samples || 1;
  const out = new Uint8Array(n * bpp * spp);
  for (let s = 0; s < nseg; s++) {
    const sample = Math.floor(s / bpp), byte = bpp - 1 - (s % bpp);           // most significant byte plane first
    let p = offs[s]; const end = s + 1 < nseg ? offs[s + 1] : bytes.length; let o = 0;
    const put = v => { if (o < n) out[(o * spp + sample) * bpp + byte] = v; o++; };
    while (p < end && o < n) {
      const c = (bytes[p++] << 24) >> 24;
      if (c >= 0) for (let k = 0; k <= c; k++) put(bytes[p++]);
      else if (c !== -128) { const v = bytes[p++]; for (let k = 0; k < 1 - c; k++) put(v); }
    }
  }
  return bpp === 2 ? (info.signed ? new Int16Array(out.buffer) : new Uint16Array(out.buffer)) : out;
}

export async function inflate(bytes, raw = false) {
  const s = new Blob([bytes]).stream().pipeThrough(new DecompressionStream(raw ? 'deflate-raw' : 'deflate'));
  return new Uint8Array(await new Response(s).arrayBuffer());
}

// PNG at full depth: 16-bit greyscale (the usual export of a radiograph) and 16-bit colour. Returns null when the
// browser's own decoder is as good (8-bit, palette) or the file is interlaced.
export async function decodePNG(buffer) {
  const u = new Uint8Array(buffer), dv = new DataView(buffer);
  if (u[0] !== 0x89 || u[1] !== 0x50) return null;
  let p = 8, W = 0, H = 0, depth = 0, ctype = 0, inter = 0; const idat = [];
  while (p + 8 <= u.length) {
    const len = dv.getUint32(p), type = String.fromCharCode(u[p + 4], u[p + 5], u[p + 6], u[p + 7]);
    if (type === 'IHDR') { W = dv.getUint32(p + 8); H = dv.getUint32(p + 12); depth = u[p + 16]; ctype = u[p + 17]; inter = u[p + 20]; }
    else if (type === 'IDAT') idat.push(u.subarray(p + 8, p + 8 + len));
    else if (type === 'IEND') break;
    p += 12 + len;
  }
  if (depth !== 16 || inter || ![0, 2, 4, 6].includes(ctype)) return null;
  const ch = { 0: 1, 2: 3, 4: 2, 6: 4 }[ctype], bpp = 2 * ch, stride = W * bpp;
  const total = idat.reduce((n, a) => n + a.length, 0), z = new Uint8Array(total); let o = 0;
  for (const a of idat) { z.set(a, o); o += a.length; }
  const raw = await inflate(z), img = new Uint8Array(H * stride);
  for (let y = 0; y < H; y++) {   // undo the per-row filters
    const f = raw[y * (stride + 1)], src = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)), row = img.subarray(y * stride, (y + 1) * stride);
    const up = y ? img.subarray((y - 1) * stride, y * stride) : new Uint8Array(stride);
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? row[i - bpp] : 0, b = up[i], c = i >= bpp ? up[i - bpp] : 0;
      let v = src[i];
      if (f === 1) v += a; else if (f === 2) v += b; else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) { const pp = a + b - c, pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c); v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      row[i] = v & 255;
    }
  }
  const n = W * H, data = new Float32Array(n), d2 = new DataView(img.buffer);
  for (let i = 0; i < n; i++) {
    const at = k => d2.getUint16(i * bpp + 2 * k);                       // PNG is big-endian
    data[i] = ch >= 3 ? 0.299 * at(0) + 0.587 * at(1) + 0.114 * at(2) : at(0);
  }
  let min = Infinity, max = -Infinity; for (let i = 0; i < n; i++) { if (data[i] < min) min = data[i]; if (data[i] > max) max = data[i]; }
  return { cols: W, rows: H, data, min, max, wc: (min + max) / 2, ww: Math.max(1, max - min), invert: false, meta: { Modality: 'picture', format: '16-bit PNG' } };
}
