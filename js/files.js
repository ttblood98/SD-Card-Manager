// Everything that touches files: picking folders, scanning the card, album folders, verified copy, delete.
import { typeOf } from './media.js';

/** Opens the system folder picker (read/write). Tests can replace it with window.__testPick. */
export async function pickFolder(id) {
  if (window.__testPick) return window.__testPick(id);
  return window.showDirectoryPicker({ id, mode: 'readwrite' });
}

export const supported = () => 'showDirectoryPicker' in window || !!window.__testPick;

/** All files under the card folder (recursively), with size and date. */
export async function scan(root, onProgress) {
  const found = [];
  const files = [];
  async function walk(dir, path, depth) {
    for await (const h of dir.values()) {
      if (h.name.startsWith('.')) continue;
      if (h.kind === 'directory') { if (depth < 6) await walk(h, path + h.name + '/', depth + 1); }
      else files.push({ h, dir, path: path + h.name });
    }
  }
  await walk(root, '', 0);
  let i = 0, done = 0;
  async function worker() {
    while (i < files.length) {
      const f = files[i++];
      try {
        const file = await f.h.getFile();
        found.push({
          key: f.path, name: f.h.name, path: f.path, handle: f.h, dir: f.dir,
          size: file.size, mtime: file.lastModified, type: typeOf(f.h.name),
        });
      } catch (e) { /* vanished while scanning */ }
      if (++done % 100 === 0 && onProgress) onProgress(done, files.length);
    }
  }
  await Promise.all(Array.from({ length: 16 }, worker));
  return found;
}

// ------------------------------------------------------------------ album folders

/** Sub-folders ([{name, handle}], by name) and photos ([items], newest first) directly inside a folder. */
export async function listAlbum(dir, withDates = true) {
  const folders = [], photos = [];
  for await (const h of dir.values()) {
    if (h.name.startsWith('.')) continue;
    if (h.kind === 'directory') folders.push({ name: h.name, handle: h });
    else if (typeOf(h.name) !== 'other') photos.push({ key: null, name: h.name, handle: h, dir, type: typeOf(h.name), size: 0, mtime: 0 });
  }
  folders.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }));
  if (withDates) {
    let i = 0;
    const worker = async () => {
      while (i < photos.length) {
        const p = photos[i++];
        try { const f = await p.handle.getFile(); p.size = f.size; p.mtime = f.lastModified; } catch (e) { }
      }
    };
    await Promise.all(Array.from({ length: 12 }, worker));
    photos.sort((a, b) => b.mtime - a.mtime);
  }
  return { folders, photos };
}

export async function resolvePath(root, names) {
  let d = root;
  for (const n of names) d = await d.getDirectoryHandle(n);
  return d;
}

export function cleanName(s) { return s.trim().replace(/[\\/:*?"<>|]/g, '_'); }

export async function createAlbum(dir, name) { return dir.getDirectoryHandle(cleanName(name), { create: true }); }

async function exists(dir, name) {
  try { await dir.getFileHandle(name); return true; } catch (e) { return false; }
}

// ------------------------------------------------------------------ verified copy

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf, crc) {
  let c = ~crc >>> 0;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return ~c >>> 0;
}

async function crcOf(file) {
  const r = file.stream().getReader();
  let crc = 0, n = 0;
  for (;;) {
    const { done, value } = await r.read();
    if (done) break;
    crc = crc32(value, crc);
    n += value.length;
  }
  return { crc, n };
}

export class Cancelled extends Error { constructor() { super('cancelled'); } }

/**
 * Copies one file into dir, reads the copy back and compares size + CRC32. On any problem the partial copy is
 * removed and this throws. Returns 'copied' or 'skipped' (a file with that name is already there).
 */
export async function copyVerified(item, dir, onBytes, isCancelled) {
  if (await exists(dir, item.name)) return 'skipped';
  const src = await item.handle.getFile();
  let created = false;
  try {
    const out = await dir.getFileHandle(item.name, { create: true });
    created = true;
    const w = await out.createWritable();
    let crc = 0, n = 0;
    try {
      const r = src.stream().getReader();
      for (;;) {
        const { done, value } = await r.read();
        if (done) break;
        crc = crc32(value, crc);
        n += value.length;
        await w.write(value);
        onBytes(value.length);
        if (isCancelled()) throw new Cancelled();
      }
      await w.close();
    } catch (e) {
      try { await w.abort(); } catch (e2) { }
      throw e;
    }
    if (n !== src.size) throw new Error(`read ${n} bytes but the card lists ${src.size}`);
    const back = await crcOf(await out.getFile());
    if (back.n !== n) throw new Error(`copy is ${back.n} bytes, expected ${n}`);
    if (back.crc !== crc) throw new Error('verification failed: the copy differs from the original');
    return 'copied';
  } catch (e) {
    if (created) { try { await dir.removeEntry(item.name); } catch (e2) { } }
    throw e;
  }
}

export async function deleteItem(item) {
  await item.dir.removeEntry(item.name);
}
