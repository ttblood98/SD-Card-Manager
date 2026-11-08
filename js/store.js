// Persistent storage: IndexedDB for folder handles, thumbnails and album covers; localStorage for settings.

const DB_NAME = 'offload', DB_VERSION = 1;
let dbp = null;

function db() {
  if (!dbp) {
    dbp = new Promise((resolve, reject) => {
      const r = indexedDB.open(DB_NAME, DB_VERSION);
      r.onupgradeneeded = () => {
        const d = r.result;
        for (const s of ['handles', 'thumbs', 'covers', 'kv']) if (!d.objectStoreNames.contains(s)) d.createObjectStore(s);
      };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
  }
  return dbp;
}

async function tx(store, mode, fn) {
  const d = await db();
  return new Promise((resolve, reject) => {
    const t = d.transaction(store, mode);
    const s = t.objectStore(store);
    let out;
    Promise.resolve(fn(s)).then(v => { out = v; });
    t.oncomplete = () => resolve(out);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  });
}

function req(r) {
  return new Promise((resolve, reject) => { r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
}

// Tests (headless Chromium) can't read folder handles back from IndexedDB, so they keep them in memory instead.
const testHandles = () => (store) => store === 'handles' && window.__testHandles;

export async function idbGet(store, key) {
  if (testHandles()(store)) return window.__testHandles.get(key);
  try { return await tx(store, 'readonly', s => req(s.get(key))); } catch (e) { return undefined; }
}

export async function idbPut(store, key, value) {
  if (testHandles()(store)) { window.__testHandles.set(key, value); return; }
  try { await tx(store, 'readwrite', s => { s.put(value, key); }); } catch (e) { /* storage full or blocked */ }
}

export async function idbDel(store, key) {
  if (testHandles()(store)) { window.__testHandles.delete(key); return; }
  try { await tx(store, 'readwrite', s => { s.delete(key); }); } catch (e) { }
}

export async function idbClear(store) {
  try { await tx(store, 'readwrite', s => { s.clear(); }); } catch (e) { }
}

// ---- settings (localStorage, never throws)

const PREFIX = 'offload.';

export function getPref(name, def) {
  try {
    const v = localStorage.getItem(PREFIX + name);
    return v == null ? def : JSON.parse(v);
  } catch (e) { return def; }
}

export function setPref(name, value) {
  try { localStorage.setItem(PREFIX + name, JSON.stringify(value)); } catch (e) { }
}

// ---- folder handles

/** Asks for (or checks) read/write permission on a stored handle. Needs a click to prompt. */
export async function ensurePermission(handle, prompt) {
  if (!handle) return false;
  const opts = { mode: 'readwrite' };
  try {
    if ((await handle.queryPermission(opts)) === 'granted') return true;
    if (prompt && (await handle.requestPermission(opts)) === 'granted') return true;
  } catch (e) { }
  return false;
}

// Destination locations: [{id, name}] in prefs, handles in IndexedDB under "loc:<id>".
export function locationList() { return getPref('locations', []); }

export async function addLocation(handle) {
  const list = locationList();
  for (const l of list) {
    const h = await idbGet('handles', 'loc:' + l.id);
    if (h && (await h.isSameEntry(handle).catch(() => false))) return l;
  }
  const loc = { id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6), name: handle.name };
  await idbPut('handles', 'loc:' + loc.id, handle);
  list.push(loc);
  setPref('locations', list);
  return loc;
}

export async function removeLocation(id) {
  setPref('locations', locationList().filter(l => l.id !== id));
  setPref('recents', recents().filter(r => r.loc !== id));
  await idbDel('handles', 'loc:' + id);
}

export function locationHandle(id) { return idbGet('handles', 'loc:' + id); }

// Where a photo location lives on disk (browsers never reveal this, so the user tells us once): {locId: 'C:\\Users\\me\\Pictures'}
export function diskPath(id) { return (getPref('diskpaths', {})[id]) || ''; }

export function setDiskPath(id, p) {
  const all = getPref('diskpaths', {});
  const v = (p || '').trim().replace(/[\\/]+$/, '');
  if (v) all[id] = v; else delete all[id];
  setPref('diskpaths', all);
}

// Recent destinations: [{loc, path: [names], label}]
export function recents() { return getPref('recents', []); }

export function pushRecent(dest) {
  const r = recents().filter(x => !(x.loc === dest.loc && x.path.join('/') === dest.path.join('/')));
  r.unshift({ loc: dest.loc, path: dest.path, label: dest.label });
  setPref('recents', r.slice(0, 6));
}
