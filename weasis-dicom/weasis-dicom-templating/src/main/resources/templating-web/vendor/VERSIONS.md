# Vendored decoders (10 Oct 2026)

Loaded by `../imageio.js` only when a compressed DICOM file is opened from disk (images from the archive arrive
uncompressed). Pinned, copied unchanged from the npm tarballs (`npm pack`), sha256 of each tarball:

| File | Package | Licence | Tarball sha256 |
| --- | --- | --- | --- |
| `charlswasm_decode.js`, `.wasm` | @cornerstonejs/codec-charls 1.2.7 (JPEG-LS) | MIT (`LICENSE-charls.txt`) | 0383755533c104b171b3c0e2bcc45c3c5e9c317e7e6f35ffef38a74371bfa93f |
| `openjpegwasm_decode.js`, `.wasm` | @cornerstonejs/codec-openjpeg 1.3.6 (JPEG 2000) | MIT (package.json; no licence file shipped) | e81b0597fbd58d4b3b55b2f06dbca58b83d82b644ac799871324de7da46ce04b |
| `jpeg-lossless.js` | jpeg-lossless-decoder-js 2.1.2 (JPEG lossless, process 14) | MIT (`LICENSE-jpeg-lossless.txt`) | 5ef03afb9d0b6fce21c534a345024a7285cc4661a84d2ac6a72d0ebb89a36303 |

Only the source-map comment was removed from `jpeg-lossless.js` (the map is not shipped).
