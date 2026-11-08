// Where to put the photos: a second gallery, of albums. Folders show as album tiles with a cover photo and a count.
// Opening an album shows the albums inside it and the photos already there, plus a big "Copy here" button.
import { el, icon, iconBtn, pushPage, popPage, backPage, pageBar, promptText, toast, fmtSize, dialog } from './ui.js';
import { locationList, locationHandle, addLocation, recents, ensurePermission, idbGet, idbPut } from './store.js';
import { listAlbum, resolvePath, createAlbum, pickFolder, cleanName } from './files.js';
import { thumbFor, requestThumb } from './media.js';

const refreshed = new Set();   // album covers re-checked this session
const memCovers = new Map();   // key -> {url, label}

export function invalidateCovers() { refreshed.clear(); }

const coverKey = (loc, names) => loc + ':' + names.join('/');
const albumItemKey = (loc, names, file) => 'album:' + loc + ':' + [...names, file].join('/');

/**
 * mode: 'copy' | 'move' | 'browse'. start (optional): {loc, path} to open straight into an album.
 */
export function openAlbumPicker(app, items, mode, start) {
  const browse = mode === 'browse';
  const bytes = items.reduce((a, i) => a + i.size, 0);
  const what = `${items.length} photo${items.length === 1 ? '' : 's'}`;
  const crumbs = el('div', { class: 'crumbs' });
  const body = el('div', { class: 'content' });
  const scroll = el('div', { class: 'scroll' }, body);
  const goBtn = el('button', { class: 'btn primary', text: `${mode === 'move' ? 'Move' : 'Copy'} ${what} here` });
  const footer = el('div', { class: 'footer' }, goBtn);
  const page = {
    el: el('div', null,
      pageBar(browse ? 'Albums' : mode === 'move' ? 'Move to album' : 'Copy to album', browse ? null : `${what} · ${fmtSize(bytes)}`),
      crumbs, scroll, footer),
  };
  // path: [] = start screen; else [{loc, names, dir, label}] from a location down to the open album
  let path = [];
  let token = 0;

  page.onBack = () => { if (!path.length) return false; path.pop(); render(); return true; };
  page.onRefresh = () => render();
  goBtn.onclick = async () => {
    const cur = path[path.length - 1];
    if (!cur) return;
    const ok = await app.confirmTransfer(items, mode === 'move', { dir: cur.dir, label: cur.label, loc: cur.loc, path: cur.names });
    if (ok) popPage();
  };

  const locLabel = (loc) => loc.name;
  const label = (loc, names) => [locLabel(loc), ...names].join(' / ');

  async function openLoc(loc, names) {
    const h = await locationHandle(loc.id);
    if (!h || !(await ensurePermission(h, true))) { toast('Offload needs permission to use ' + loc.name); return; }
    const out = [{ loc: loc.id, names: [], dir: h, label: locLabel(loc) }];
    try {
      let d = h;
      for (let k = 0; k < names.length; k++) {
        d = await d.getDirectoryHandle(names[k]);
        out.push({ loc: loc.id, names: names.slice(0, k + 1), dir: d, label: label(loc, names.slice(0, k + 1)) });
      }
    } catch (e) { toast('That album no longer exists'); }
    path = out;
    render();
  }

  function render() {
    token++;
    body.innerHTML = '';
    scroll.scrollTop = 0;
    renderCrumbs();
    const cur = path[path.length - 1];
    footer.style.display = cur && !browse ? '' : 'none';
    crumbs.style.display = cur ? '' : 'none';
    if (!cur) renderStart(); else renderAlbum(cur);
  }

  function renderCrumbs() {
    crumbs.innerHTML = '';
    crumbs.append(el('button', { text: 'Albums', onclick: () => { path = []; render(); } }));
    path.forEach((p, k) => {
      crumbs.append(el('span', { class: 'sep', text: '›' }));
      const name = k === 0 ? p.label : p.names[p.names.length - 1];
      crumbs.append(el('button', { class: k === path.length - 1 ? 'last' : '', text: name, onclick: () => { path = path.slice(0, k + 1); render(); } }));
    });
  }

  function albumTile(loc, names, dir, name, onclick) {
    const img = el('img', { alt: '' });
    img.style.display = 'none';
    const cover = el('div', { class: 'cover', html: icon.folder });
    cover.append(img);
    const count = el('div', { class: 'count', text: ' ' });
    const tile = el('button', { class: 'album', title: name, onclick }, cover, el('div', { class: 'name', text: name }), count);
    bindCover(loc, names, dir, img, count);
    return tile;
  }

  function newAlbumTile(parent) {
    return el('button', { class: 'album new', onclick: () => newAlbum(parent) },
      el('div', { class: 'cover', html: icon.plus }), el('div', { class: 'name', text: 'New album' }), el('div', { class: 'count' }));
  }

  async function newAlbum(parent) {
    const d = new Date();
    const def = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    const name = await promptText('New album in ' + (parent.names.length ? parent.names[parent.names.length - 1] : parent.label), def, 'Create');
    if (!name || !cleanName(name)) return;
    try {
      const dir = await createAlbum(parent.dir, name);
      const names = [...parent.names, cleanName(name)];
      const keep = path.findIndex(p => p.loc === parent.loc && p.names.join('/') === parent.names.join('/'));
      path = keep >= 0 ? path.slice(0, keep + 1) : [parent];
      path.push({ loc: parent.loc, names, dir, label: parent.label.split(' / ')[0] + ' / ' + names.join(' / ') });
      render();
    } catch (e) { dialog({ title: 'Couldn\'t create the album', body: e.message }); }
  }

  // ---- start screen: recent albums, then every location's albums
  async function renderStart() {
    const my = token;
    const locs = locationList();
    const rec = recents().filter(r => locs.some(l => l.id === r.loc));
    if (rec.length) {
      body.append(el('div', { class: 'loc-head' }, el('h3', { text: 'Recent' })));
      const grid = el('div', { class: 'albums' });
      body.append(grid);
      for (const r of rec) {
        const loc = locs.find(l => l.id === r.loc);
        const name = r.path.length ? r.path[r.path.length - 1] : loc.name;
        grid.append(albumTile(r.loc, r.path, null, name, () => openLoc(loc, r.path)));
      }
    }
    if (!locs.length) {
      body.append(el('div', { class: 'loc-head' }, el('h3', { text: 'Albums' })));
      body.append(el('p', { class: 'dim', text: 'Add a place on this computer to keep photos, such as your Pictures folder. The browser asks once; after that your albums show up here.' }));
      body.append(el('button', { class: 'btn primary', text: 'Add a photo location', onclick: addLoc }));
      return;
    }
    for (const loc of locs) {
      const h = await locationHandle(loc.id);
      if (my !== token) return;
      const head = el('div', { class: 'loc-head' }, el('h3', { text: loc.name }));
      body.append(head);
      const slot = el('div');
      body.append(slot);
      const ok = h && await ensurePermission(h, false);
      if (!ok) {
        slot.append(el('button', { class: 'btn secondary', text: 'Allow access to ' + loc.name, onclick: async () => { if (await ensurePermission(h, true)) render(); } }));
        continue;
      }
      head.append(el('button', { class: 'btn flat', text: 'Open ›', onclick: () => openLoc(loc, []) }));
      const root = { loc: loc.id, names: [], dir: h, label: loc.name };
      const grid = el('div', { class: 'albums' }, newAlbumTile(root));
      slot.append(grid);
      listAlbum(h, false).then(({ folders }) => {
        if (my !== token) return;
        for (const f of folders) grid.append(albumTile(loc.id, [f.name], f.handle, f.name, () => openLoc(loc, [f.name])));
      }).catch(e => slot.append(el('p', { class: 'dim', text: 'Couldn\'t open ' + loc.name + ': ' + e.message })));
    }
    body.append(el('div', { style: { marginTop: '24px' } }, el('button', { class: 'btn flat', text: '+  Add another photo location', onclick: addLoc })));
  }

  async function addLoc() {
    try {
      const h = await pickFolder('dest');
      await addLocation(h);
      app.log('Photo location added: ' + h.name);
      render();
    } catch (e) { if (e.name !== 'AbortError') toast('Could not add that folder: ' + e.message); }
  }

  // ---- inside an album
  async function renderAlbum(cur) {
    const my = token;
    const name = cur.names.length ? cur.names[cur.names.length - 1] : cur.label;
    body.append(el('div', { class: 'album-title', text: name }));
    const meta = el('div', { class: 'dim', text: 'Loading…' });
    body.append(meta);
    if (!browse && app.state.dateFolders) body.append(el('div', { class: 'dim small', text: 'Photos will go into date folders (YYYY-MM-DD) inside this album.' }));
    let res;
    try { res = await listAlbum(cur.dir, true); } catch (e) { meta.textContent = 'Couldn\'t open this album: ' + e.message; return; }
    if (my !== token) return;
    meta.textContent = describe(res);
    body.append(el('div', { class: 'loc-head' }, el('h3', { text: res.folders.length ? 'Albums inside' : 'Albums' })));
    const grid = el('div', { class: 'albums' }, newAlbumTile(cur));
    for (const f of res.folders) {
      const names = [...cur.names, f.name];
      grid.append(albumTile(cur.loc, names, f.handle, f.name, () => {
        path.push({ loc: cur.loc, names, dir: f.handle, label: cur.label + ' / ' + f.name });
        render();
      }));
    }
    body.append(grid);
    body.append(el('div', { class: 'loc-head' }, el('h3', { text: 'Photos in this album' })));
    if (!res.photos.length) { body.append(el('p', { class: 'dim', text: 'No photos here yet.' })); return; }
    const n = Math.min(browse ? 400 : 60, res.photos.length);
    const photos = el('div', { class: 'photos' });
    for (let k = 0; k < n; k++) {
      const p = res.photos[k];
      p.key = albumItemKey(cur.loc, cur.names, p.name);
      const img = el('img', { alt: '', title: p.name });
      photos.append(el('div', { class: 'ph' }, img));
      requestThumb(p, u => { if (u) img.src = u; }, false);
    }
    body.append(photos);
    if (res.photos.length > n) body.append(el('p', { class: 'dim small', text: `and ${res.photos.length - n} more` }));
  }

  pushPage(page);
  if (start && start.loc) {
    const loc = locationList().find(l => l.id === start.loc);
    if (loc) { openLoc(loc, start.path || []); return; }
  }
  render();
}

function describe(res) {
  const p = res.photos.length, f = res.folders.length;
  if (!p && !f) return 'Empty';
  return [p && `${p} photo${p === 1 ? '' : 's'}`, f && `${f} album${f === 1 ? '' : 's'}`].filter(Boolean).join(' · ');
}

/** Fills an album tile's cover and count: cached ones at once, then re-checked once per session. */
async function bindCover(loc, names, dir, img, countEl) {
  const key = coverKey(loc, names);
  const show = (url, label) => {
    if (url) { img.src = url; img.style.display = ''; }
    if (label) countEl.textContent = label;
  };
  const mem = memCovers.get(key);
  if (mem) show(mem.url, mem.label);
  else {
    const stored = await idbGet('covers', key);
    if (stored) {
      const url = stored.blob ? URL.createObjectURL(stored.blob) : null;
      memCovers.set(key, { url, label: stored.label });
      show(url, stored.label);
    }
  }
  if (refreshed.has(key)) return;
  refreshed.add(key);
  try {
    if (!dir) {
      const h = await locationHandle(loc);
      if (!h || !(await ensurePermission(h, false))) { refreshed.delete(key); return; }
      dir = await resolvePath(h, names);
    }
    const res = await listAlbum(dir, true);
    let cover = res.photos[0], coverNames = names;
    if (!cover && res.folders.length) {
      const sub = await listAlbum(res.folders[0].handle, true);
      cover = sub.photos[0];
      coverNames = [...names, res.folders[0].name];
    }
    const label = describe(res);
    let blob = null, url = null;
    if (cover) {
      cover.key = albumItemKey(loc, coverNames, cover.name);
      url = await thumbFor(cover);
      if (url) blob = await (await fetch(url)).blob();
    }
    await idbPut('covers', key, { blob, label });
    memCovers.set(key, { url, label });
    show(url, label);
    if (!url) img.style.display = 'none';
  } catch (e) { refreshed.delete(key); }
}
