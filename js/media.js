// Photo files: types, EXIF/TIFF parsing, the JPEG preview inside RAW files, thumbnails (cached in IndexedDB).
import { idbGet, idbPut } from './store.js';

const RAW_EXT = new Set(['nef', 'nrw', 'dng', 'cr2', 'cr3', 'crw', 'arw', 'raf', 'orf', 'rw2', 'pef', 'srw', 'x3f', 'raw']);

export function extOf(name) {
  const i = name.lastIndexOf('.');
  return i > 0 ? name.slice(i + 1).toLowerCase() : '';
}

export function typeOf(name) {
  const x = extOf(name);
  if (RAW_EXT.has(x)) return 'raw';
  if (x === 'jpg' || x === 'jpeg') return 'jpg';
  return 'other';
}

export const TYPE_LABEL = { raw: 'RAW', jpg: 'JPEG', other: 'Other' };

async function slice(file, off, len) {
  if (off < 0 || off >= file.size) return new Uint8Array(0);
  return new Uint8Array(await file.slice(off, Math.min(file.size, off + len)).arrayBuffer());
}

// ------------------------------------------------------------------ TIFF / EXIF

const TYPE_SIZE = { 1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8 };

/** Reads one IFD. Returns {entries: Map(tag -> entry), next}, or null. Offsets are relative to the TIFF header at base. */
async function readIfd(file, base, off, le) {
  if (!off || base + off + 2 > file.size) return null;
  const cb = await slice(file, base + off, 2);
  if (cb.length < 2) return null;
  const count = new DataView(cb.buffer).getUint16(0, le);
  if (!count || count > 600) return null;
  const b = await slice(file, base + off + 2, count * 12 + 4);
  if (b.length < count * 12 + 4) return null;
  const v = new DataView(b.buffer);
  const entries = new Map();
  for (let i = 0; i < count; i++) {
    const o = i * 12;
    const tag = v.getUint16(o, le), type = v.getUint16(o + 2, le), cnt = v.getUint32(o + 4, le);
    const size = (TYPE_SIZE[type] || 1) * cnt;
    entries.set(tag, { type, cnt, size, inline: b.slice(o + 8, o + 12), ptr: v.getUint32(o + 8, le) });
  }
  return { entries, next: v.getUint32(count * 12, le) };
}

async function rawValue(file, base, e) {
  if (!e) return null;
  if (e.size <= 4) return e.inline;
  if (e.size > 65536) return null;
  return slice(file, base + e.ptr, e.size);
}

async function num(file, base, e, le) {
  if (!e) return null;
  const b = await rawValue(file, base, e);
  if (!b || !b.length) return null;
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  switch (e.type) {
    case 1: case 7: return v.getUint8(0);
    case 3: return v.getUint16(0, le);
    case 4: return v.getUint32(0, le);
    case 9: return v.getInt32(0, le);
    case 5: { const d = v.getUint32(4, le); return d ? v.getUint32(0, le) / d : null; }
    case 10: { const d = v.getInt32(4, le); return d ? v.getInt32(0, le) / d : null; }
    default: return null;
  }
}

async function str(file, base, e) {
  if (!e || e.type !== 2) return null;
  const b = await rawValue(file, base, e);
  if (!b) return null;
  let s = '';
  for (const c of b) { if (!c) break; s += String.fromCharCode(c); }
  return s.trim() || null;
}

/** Finds the TIFF header: at 0 for TIFF-based RAW files, inside the APP1 "Exif" segment for JPEG. */
async function tiffBase(file) {
  const head = await slice(file, 0, 4);
  if (head.length < 4) return null;
  if ((head[0] === 0x49 && head[1] === 0x49) || (head[0] === 0x4d && head[1] === 0x4d)) return 0;
  if (head[0] !== 0xff || head[1] !== 0xd8) return null;
  let p = 2;
  for (let n = 0; n < 40 && p < file.size; n++) {
    const m = await slice(file, p, 10);
    if (m.length < 4 || m[0] !== 0xff) return null;
    const marker = m[1], len = (m[2] << 8) | m[3];
    if (marker === 0xe1 && m[4] === 0x45 && m[5] === 0x78 && m[6] === 0x69 && m[7] === 0x66) return p + 10;
    if (marker === 0xda || marker === 0xd9) return null;
    p += 2 + len;
  }
  return null;
}

/** Camera, lens, exposure, orientation and (for RAW) the embedded JPEG previews. */
export async function readMeta(file) {
  const out = { orient: 1, previews: [] };
  try {
    const base = await tiffBase(file);
    if (base == null) return out;
    const h = await slice(file, base, 8);
    const le = h[0] === 0x49;
    const dv = new DataView(h.buffer);
    if (dv.getUint16(2, le) !== 42) return out;
    const seen = new Set();
    const walk = async (off, depth, isIfd0) => {
      if (!off || depth > 3 || seen.has(off)) return;
      seen.add(off);
      const ifd = await readIfd(file, base, off, le);
      if (!ifd) return;
      const E = ifd.entries;
      if (isIfd0) {
        out.make = await str(file, base, E.get(0x010f));
        out.model = await str(file, base, E.get(0x0110));
        const o = await num(file, base, E.get(0x0112), le);
        if (o) out.orient = o;
        const exifPtr = await num(file, base, E.get(0x8769), le);
        if (exifPtr) {
          const ex = await readIfd(file, base, exifPtr, le);
          if (ex) {
            const X = ex.entries;
            out.exposure = await num(file, base, X.get(0x829a), le);
            out.fnumber = await num(file, base, X.get(0x829d), le);
            out.iso = await num(file, base, X.get(0x8827), le);
            out.focal = await num(file, base, X.get(0x920a), le);
            out.lens = await str(file, base, X.get(0xa434));
            out.taken = await str(file, base, X.get(0x9003));
            out.pxW = await num(file, base, X.get(0xa002), le);
            out.pxH = await num(file, base, X.get(0xa003), le);
          }
        }
      }
      // embedded JPEG previews (RAW files)
      if (base === 0) {
        const jo = await num(file, base, E.get(0x0201), le), jl = await num(file, base, E.get(0x0202), le);
        if (jo && jl) out.previews.push({ off: jo, len: jl });
        const comp = await num(file, base, E.get(0x0103), le);
        const so = E.get(0x0111), sl = E.get(0x0117);
        if ((comp === 6 || comp === 7) && so && sl && so.cnt === 1 && sl.cnt === 1) {
          out.previews.push({ off: await num(file, base, so, le), len: await num(file, base, sl, le) });
        }
        const sub = E.get(0x014a);
        if (sub) {
          if (sub.cnt === 1) await walk(await num(file, base, sub, le), depth + 1, false);
          else if (sub.cnt <= 8) {
            const b = await slice(file, base + sub.ptr, sub.cnt * 4);
            const v = new DataView(b.buffer);
            for (let i = 0; i + 4 <= b.length; i += 4) await walk(v.getUint32(i, le), depth + 1, false);
          }
        }
        if (isIfd0 && ifd.next) await walk(ifd.next, 0, false);
      }
    };
    await walk(dv.getUint32(4, le), 0, true);
  } catch (e) { /* unreadable metadata: show what we have */ }
  return out;
}

/** Width/height of a JPEG from its SOF marker (reads only the header). */
export async function jpegSize(blob) {
  try {
    const b = await slice(blob, 0, 512 * 1024);
    if (b[0] !== 0xff || b[1] !== 0xd8) return null;
    let p = 2;
    while (p + 9 < b.length) {
      if (b[p] !== 0xff) { p++; continue; }
      const m = b[p + 1];
      if (m === 0xd8 || m === 0x01 || (m >= 0xd0 && m <= 0xd7)) { p += 2; continue; }
      const len = (b[p + 2] << 8) | b[p + 3];
      if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) {
        return { h: (b[p + 5] << 8) | b[p + 6], w: (b[p + 7] << 8) | b[p + 8] };
      }
      if (m === 0xda) return null;
      p += 2 + len;
    }
  } catch (e) { }
  return null;
}

/** The embedded preview of a RAW file as a JPEG Blob. largest=false picks the smallest one that is at least 400px. */
export async function rawPreview(file, largest, meta) {
  const m = meta || await readMeta(file);
  const cands = m.previews.filter(c => c.len > 500 && c.off + c.len <= file.size).sort((a, b) => a.len - b.len);
  if (largest) cands.reverse();
  let fallback = null;
  for (const c of cands) {
    const head = await slice(file, c.off, 2);
    if (head[0] !== 0xff || head[1] !== 0xd8) continue;
    const blob = file.slice(c.off, c.off + c.len, 'image/jpeg');
    if (largest) return { blob, orient: m.orient };
    const sz = await jpegSize(blob);
    if (sz && Math.max(sz.w, sz.h) >= 400) return { blob, orient: m.orient };
    if (!fallback) fallback = blob;
  }
  if (fallback) return { blob: fallback, orient: m.orient };
  throw new Error('no JPEG preview found inside the RAW file');
}

/** Draws a bitmap into a canvas, rotated for EXIF orientation 3/6/8, scaled so the longest side is at most max. */
function drawOriented(bmp, orient, max) {
  const rot = orient === 6 || orient === 8;
  const sw = bmp.width, sh = bmp.height;
  const k = Math.min(1, max / Math.max(sw, sh));
  const w = Math.round(sw * k), h = Math.round(sh * k);
  const cw = rot ? h : w, ch = rot ? w : h;
  const c = new OffscreenCanvas(cw, ch);
  const g = c.getContext('2d');
  g.imageSmoothingQuality = 'high';
  g.translate(cw / 2, ch / 2);
  if (orient === 3) g.rotate(Math.PI);
  else if (orient === 6) g.rotate(Math.PI / 2);
  else if (orient === 8) g.rotate(-Math.PI / 2);
  g.drawImage(bmp, -w / 2, -h / 2, w, h);
  return c;
}

/** A large, correctly rotated image for the viewer: an object URL plus pixel size. */
export async function largeImage(item) {
  const file = await item.handle.getFile();
  if (item.type === 'jpg') {
    return { url: URL.createObjectURL(file), revoke: true };
  }
  if (item.type === 'raw') {
    const meta = await readMeta(file);
    const { blob, orient } = await rawPreview(file, true, meta);
    if (orient === 3 || orient === 6 || orient === 8) {
      const bmp = await createImageBitmap(blob);
      const c = drawOriented(bmp, orient, 8000);
      bmp.close();
      const out = await c.convertToBlob({ type: 'image/jpeg', quality: 0.92 });
      return { url: URL.createObjectURL(out), revoke: true };
    }
    return { url: URL.createObjectURL(blob), revoke: true };
  }
  return null;
}

/** Resolution + camera/lens + exposure lines for the viewer. */
export async function details(item) {
  const file = await item.handle.getFile();
  const m = await readMeta(file);
  let w = 0, h = 0;
  if (item.type === 'jpg') {
    const s = await jpegSize(file);
    if (s) { w = s.w; h = s.h; }
  } else if (item.type === 'raw') {
    for (const c of m.previews) {
      if (c.off + c.len > file.size || c.len < 500) continue;
      const s = await jpegSize(file.slice(c.off, c.off + c.len));
      if (s && s.w * s.h > w * h) { w = s.w; h = s.h; }
    }
  }
  if (m.orient === 6 || m.orient === 8) [w, h] = [h, w];
  const DOT = '  ·  ';
  const res = w && h ? `${w} × ${h}${DOT}${(w * h / 1e6).toFixed(1)} MP` : '';
  const cam = [];
  if (m.model) {
    let model = m.model;
    const brand = (m.make || '').split(' ')[0];
    if (brand && !model.toLowerCase().startsWith(brand.toLowerCase())) model = brand + ' ' + model;
    cam.push(model);
  }
  if (m.lens) cam.push(m.lens);
  const exp = [];
  if (m.focal) exp.push(Math.round(m.focal) + ' mm');
  if (m.fnumber) exp.push('f/' + m.fnumber.toFixed(1));
  if (m.exposure) exp.push(m.exposure >= 1 ? m.exposure.toFixed(1) + ' s' : '1/' + Math.round(1 / m.exposure) + ' s');
  if (m.iso) exp.push('ISO ' + m.iso);
  return { res, cam: cam.join(DOT), exp: exp.join(DOT) };
}

// ------------------------------------------------------------------ thumbnails

const THUMB_PX = 360;
const urls = new Map();       // key -> object URL (this session)
const failed = new Set();
const waiting = new Map();    // key -> [callbacks]
const queue = [];             // {item, key}
let running = 0;
const MAX_RUNNING = 4;
export const thumbStats = { ok: 0, failed: 0, ms: 0, cached: 0 };

export function thumbKey(item) { return `${item.key}|${item.size}|${item.mtime}`; }

export function cachedThumb(item) { return urls.get(thumbKey(item)) || null; }

/** Calls back with an object URL for the item's thumbnail (or null). Visible items should call this first. */
export function requestThumb(item, cb, priority) {
  const key = thumbKey(item);
  const have = urls.get(key);
  if (have) { cb(have); return () => { }; }
  if (item.type === 'other' || failed.has(key)) { cb(null); return () => { }; }
  let list = waiting.get(key);
  if (!list) {
    list = [];
    waiting.set(key, list);
    const job = { item, key };
    if (priority) queue.unshift(job); else queue.push(job);
    pump();
  }
  list.push(cb);
  return () => {
    const l = waiting.get(key);
    if (!l) return;
    const i = l.indexOf(cb);
    if (i >= 0) l.splice(i, 1);
    if (!l.length) {  // nobody needs it any more: drop it from the queue if it has not started
      const qi = queue.findIndex(j => j.key === key);
      if (qi >= 0) { queue.splice(qi, 1); waiting.delete(key); }
    }
  };
}

/** Loads a thumbnail and waits for it (used for album covers). */
export function thumbFor(item) {
  return new Promise(resolve => requestThumb(item, resolve, false));
}

function pump() {
  while (running < MAX_RUNNING && queue.length) {
    const job = queue.shift();
    running++;
    makeThumb(job.item, job.key).then(url => {
      running--;
      const l = waiting.get(job.key) || [];
      waiting.delete(job.key);
      for (const cb of l) cb(url);
      pump();
    });
  }
}

async function makeThumb(item, key) {
  const t0 = performance.now();
  try {
    const stored = await idbGet('thumbs', key);
    if (stored instanceof Blob) {
      const u = URL.createObjectURL(stored);
      urls.set(key, u);
      thumbStats.cached++;
      return u;
    }
    const file = await item.handle.getFile();
    let blob = file, orient = 1;
    if (item.type === 'raw') ({ blob, orient } = await rawPreview(file, false));
    // JPEG orientation is applied by the decoder; RAW previews need the orientation from the RAW file
    const bmp = await createImageBitmap(blob, { imageOrientation: 'from-image', resizeWidth: 520, resizeQuality: 'medium' });
    const c = drawOriented(bmp, item.type === 'raw' ? orient : 1, THUMB_PX);
    bmp.close();
    const out = await c.convertToBlob({ type: 'image/jpeg', quality: 0.82 });
    idbPut('thumbs', key, out);
    const u = URL.createObjectURL(out);
    urls.set(key, u);
    thumbStats.ok++;
    thumbStats.ms += performance.now() - t0;
    return u;
  } catch (e) {
    failed.add(key);
    thumbStats.failed++;
    if (thumbStats.failed === 1 && media.onError) media.onError(item, e);
    return null;
  }
}

export const media = { onError: null };
