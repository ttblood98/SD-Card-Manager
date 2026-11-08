// Offload for Windows: a gallery of the camera card, with verified copy/move into photo albums.
import { el, icon, iconBtn, toast, dialog, menu, closeMenu, closeTopDialog, dialogOpen, pushPage, backPage, pageOpen,
  refreshPages, dateRangeDialog, ymd, fmtSize } from './ui.js';
import { idbGet, idbPut, getPref, setPref, ensurePermission, pushRecent } from './store.js';
import { scan, pickFolder, supported, copyVerified, deleteItem, Cancelled } from './files.js';
import { requestThumb, cachedThumb, TYPE_LABEL, extOf, media } from './media.js';
import { openViewer, viewerOpen, closeViewer, refreshViewer } from './viewer.js';
import { openAlbumPicker, invalidateCovers } from './albums.js';
import { openSettings, openLog } from './settings.js';

export const VERSION = '1.0.0';
const SORTS = { new: 'Newest first', old: 'Oldest first', name: 'Name A–Z', size: 'Largest first' };
const TYPES = ['raw', 'jpg', 'other'];

export const state = {
  card: null, items: [], shown: [], selected: new Set(),
  types: getPref('types', { raw: true, jpg: true, other: false }),
  sort: getPref('sort', 'new'), cols: getPref('cols', 8), dateFolders: getPref('dateFolders', false),
  dateFrom: 0, dateTo: Infinity, dateLabel: 'All dates',
  scanning: false, scanError: null, needsReconnect: false, scannedOnce: false, lastScan: 0, busy: false,
  removed: new Set(),
};
const logLines = [];
export function log(msg) {
  const t = new Date().toLocaleTimeString([], { hour12: false });
  logLines.push(t + '  ' + msg);
  if (logLines.length > 4000) logLines.splice(0, 1000);
  console.log('[offload]', msg);
}
export function logText() { return logLines.join('\n'); }
media.onError = (item, e) => log('Thumbnail failed for ' + item.name + ': ' + (e && e.message || e));

const $ = id => document.getElementById(id);
let tiles = [];          // index in state.shown -> tile element
let lastClicked = -1;

// ================================================================== startup

async function start() {
  buildChrome();
  log(`Offload ${VERSION} on ${navigator.userAgent}`);
  if (!supported()) { renderEmpty(); return; }
  const card = await idbGet('handles', 'card');
  if (card) {
    state.card = card;
    if (await ensurePermission(card, false)) await rescan();
    else { state.needsReconnect = true; render(); }
  } else render();
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && state.card && !state.busy && !state.needsReconnect
      && Date.now() - state.lastScan > 5000) rescan();
  });
  if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => { });
}

export async function pickCard() {
  try {
    const h = await pickFolder('card');
    await idbPut('handles', 'card', h);
    state.card = h;
    state.needsReconnect = false;
    state.items = [];
    state.selected.clear();
    log('Card folder: ' + h.name);
    await rescan();
    refreshPages();
  } catch (e) { if (e.name !== 'AbortError') toast('Could not open that folder: ' + e.message); }
}

async function reconnect() {
  if (await ensurePermission(state.card, true)) { state.needsReconnect = false; await rescan(); }
  else toast('Permission was not granted');
}

export async function rescan() {
  if (!state.card || state.scanning || state.busy) return;
  state.scanning = true;
  state.scanError = null;
  state.removed.clear();
  render();
  const t0 = performance.now();
  try {
    const found = await scan(state.card);
    const sel = state.selected;
    state.items = found.filter(i => !state.removed.has(i.key));
    state.selected = new Set(state.items.filter(i => sel.has(i.key)).map(i => i.key));
    const bytes = state.items.reduce((a, i) => a + i.size, 0);
    log(`Scanned ${state.items.length} files (${fmtSize(bytes)}) in ${Math.round(performance.now() - t0)} ms`);
  } catch (e) {
    state.scanError = e.message || String(e);
    state.items = [];
    log('Could not read the card: ' + state.scanError);
  }
  state.scanning = false;
  state.scannedOnce = true;
  state.lastScan = Date.now();
  applyFilter();
  refreshPages();
  refreshViewer();
}

// ================================================================== filtering, sorting, grouping

export function applyFilter() {
  const s = state;
  s.shown = s.items.filter(i => s.types[i.type] && i.mtime >= s.dateFrom && i.mtime <= s.dateTo);
  const keep = new Set(s.shown.map(i => i.key));
  for (const k of [...s.selected]) if (!keep.has(k)) s.selected.delete(k);
  const by = {
    new: (a, b) => b.mtime - a.mtime || b.name.localeCompare(a.name),
    old: (a, b) => a.mtime - b.mtime || a.name.localeCompare(b.name),
    name: (a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }),
    size: (a, b) => b.size - a.size,
  };
  s.shown.sort(by[s.sort] || by.new);
  render();
}

function dayLabel(ms) {
  const d = new Date(ms), now = new Date();
  const y = new Date(now); y.setDate(now.getDate() - 1);
  if (d.toDateString() === now.toDateString()) return 'Today';
  if (d.toDateString() === y.toDateString()) return 'Yesterday';
  return d.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' });
}

// ================================================================== chrome (top bar, filters, selection bar)

function buildChrome() {
  const bar = $('topbar');
  bar.append(
    iconBtn(icon.close, 'Clear selection (Esc)', () => clearSelection(), 'sel-only'),
    el('div', { class: 'bar-title' }, el('div', { class: 'title', id: 'title' }), el('div', { class: 'subtitle', id: 'subtitle' })),
    el('button', { class: 'btn sel-only', id: 'selAll', onclick: () => selectAllToggle() }),
    iconBtn(icon.sort, 'Sort', e => sortMenu(e.currentTarget), 'nosel-only'),
    iconBtn(icon.gear, 'Settings', () => openSettings(app), 'nosel-only'),
  );
  const f = $('filters');
  for (const t of TYPES) {
    f.append(el('button', { class: 'chip', id: 'chip-' + t, text: TYPE_LABEL[t], onclick: () => { state.types[t] = !state.types[t]; setPref('types', state.types); applyFilter(); } }));
  }
  f.append(el('button', { class: 'dropdown', id: 'dateBtn', onclick: e => dateMenu(e.currentTarget) }));
  f.append(el('div', { class: 'spacer' }));
  f.append(el('div', { class: 'zoom', title: 'Thumbnail size (Ctrl + scroll wheel)' },
    iconBtn(icon.minus, 'Smaller thumbnails (Ctrl + scroll down)', () => setColumns(state.cols + 1)),
    iconBtn(icon.plus, 'Bigger thumbnails (Ctrl + scroll up)', () => setColumns(state.cols - 1))));

  const sb = $('selbar');
  sb.append(
    el('div', { class: 'info', id: 'selInfo' }),
    el('button', { class: 'btn danger', onclick: () => confirmDelete(selectedItems()) }, el('span', { class: 'ico', html: icon.trash }), 'Delete'),
    el('button', { class: 'btn secondary', text: 'Copy to album…', onclick: () => startTransfer(false) }),
    el('button', { class: 'btn primary', text: 'Move to album…', onclick: () => startTransfer(true) }),
  );

  const g = $('gallery');
  g.addEventListener('scroll', () => $('toTop').classList.toggle('show', g.scrollTop > 400), { passive: true });
  $('toTop').innerHTML = icon.up;
  $('toTop').addEventListener('click', () => g.scrollTo({ top: 0, behavior: 'smooth' }));
  g.addEventListener('wheel', onWheel, { passive: false });
  g.addEventListener('mousedown', onDown);
  document.addEventListener('keydown', onKey);
  window.addEventListener('resize', () => closeMenu());
}

function render() {
  renderChrome();
  renderGallery();
  renderEmpty();
}

function renderChrome() {
  const s = state;
  const n = s.selected.size;
  document.body.classList.toggle('selecting', n > 0);
  const selBytes = n ? s.shown.reduce((a, i) => a + (s.selected.has(i.key) ? i.size : 0), 0) : 0;
  const shownBytes = s.shown.reduce((a, i) => a + i.size, 0);
  $('title').textContent = n ? `${n} selected` : 'Offload';
  $('subtitle').textContent = n ? fmtSize(selBytes)
    : s.scanning && !s.items.length ? 'Reading card…'
      : !s.card ? 'No card folder yet'
        : s.shown.length === s.items.length ? `${s.items.length} files · ${fmtSize(shownBytes)}`
          : `${s.shown.length} of ${s.items.length} files · ${fmtSize(shownBytes)}`;
  $('selAll').textContent = n && n === s.shown.length ? 'Select none' : 'Select all';
  $('selInfo').textContent = n ? `${n} file${n === 1 ? '' : 's'} · ${fmtSize(selBytes)}` : '';
  for (const el2 of document.querySelectorAll('.sel-only')) el2.style.display = n ? '' : 'none';
  for (const el2 of document.querySelectorAll('.nosel-only')) el2.style.display = n ? 'none' : '';
  for (const t of TYPES) $('chip-' + t).classList.toggle('on', !!s.types[t]);
  const dated = s.dateFrom !== 0 || s.dateTo !== Infinity;
  const db = $('dateBtn');
  db.classList.toggle('on', dated);
  db.innerHTML = `${icon.cal}<span>${s.dateLabel}</span>${icon.down}`;
  document.documentElement.style.setProperty('--cols', s.cols);
}

function renderEmpty() {
  const box = $('empty');
  box.innerHTML = '';
  const s = state;
  let head = null, body = null;
  const btns = [];
  if (!supported()) {
    head = 'Please open this in Chrome or Edge';
    body = 'Offload needs the folder access that Chrome and Edge provide on Windows.';
  } else if (!s.card) {
    head = 'Welcome to Offload';
    body = 'Choose the DCIM folder on your camera\'s SD card. It is remembered, so you only do this once.';
    btns.push(el('button', { class: 'btn primary', text: 'Choose card folder', onclick: () => pickCard() }));
  } else if (s.needsReconnect) {
    head = 'Reconnect to the card';
    body = `The browser needs your OK to open "${s.card.name}" again.`;
    btns.push(el('button', { class: 'btn primary', text: 'Reconnect', onclick: () => reconnect() }));
    btns.push(el('button', { class: 'btn secondary', text: 'Choose a different folder', onclick: () => pickCard() }));
  } else if (s.scanError) {
    head = 'Can\'t read the card';
    body = `Is the SD card plugged in? (${s.scanError})`;
    btns.push(el('button', { class: 'btn primary', text: 'Try again', onclick: () => rescan() }));
    btns.push(el('button', { class: 'btn secondary', text: 'Choose a different folder', onclick: () => pickCard() }));
  } else if (s.scanning && !s.items.length) {
    head = 'Reading card…';
  } else if (!s.items.length && s.scannedOnce) {
    head = 'No files here';
    body = 'The card folder is empty. Did you pick the right folder?';
    btns.push(el('button', { class: 'btn secondary', text: 'Choose a different folder', onclick: () => pickCard() }));
  } else if (!s.shown.length && s.items.length) {
    head = 'Nothing matches these filters';
    btns.push(el('button', { class: 'btn secondary', text: 'Reset filters', onclick: resetFilters }));
  }
  if (!head) return;
  box.append(el('div', { class: 'box' }, el('h2', { text: head }), body ? el('p', { class: 'dim', text: body }) : null, el('div', null, btns)));
}

function resetFilters() {
  state.types = { raw: true, jpg: true, other: false };
  setPref('types', state.types);
  state.dateFrom = 0; state.dateTo = Infinity; state.dateLabel = 'All dates';
  applyFilter();
}

function sortMenu(anchor) {
  menu(anchor, Object.entries(SORTS).map(([k, v]) => ({
    text: v, checked: state.sort === k, onclick: () => { state.sort = k; setPref('sort', k); applyFilter(); },
  })));
}

function dateMenu(anchor) {
  const day = 86400000;
  const midnight = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return d.getTime(); };
  const set = (from, to, label) => { state.dateFrom = from; state.dateTo = to; state.dateLabel = label; applyFilter(); };
  const opts = [
    ['All dates', () => set(0, Infinity, 'All dates')],
    ['Today', () => set(midnight(), Infinity, 'Today')],
    ['Yesterday', () => set(midnight() - day, midnight() - 1, 'Yesterday')],
    ['Last 3 days', () => set(midnight() - 2 * day, Infinity, 'Last 3 days')],
    ['Last 7 days', () => set(midnight() - 6 * day, Infinity, 'Last 7 days')],
    ['Last 30 days', () => set(midnight() - 29 * day, Infinity, 'Last 30 days')],
  ];
  menu(anchor, [
    ...opts.map(([t, f]) => ({ text: t, checked: state.dateLabel === t, onclick: f })),
    '-',
    { text: 'Pick dates…', onclick: pickDates },
  ]);
}

async function pickDates() {
  const days = new Set();
  let newest = 0;
  for (const i of state.items) {
    if (!state.types[i.type]) continue;
    days.add(ymd(new Date(i.mtime)));
    newest = Math.max(newest, i.mtime);
  }
  const r = await dateRangeDialog(days, newest);
  if (r) { state.dateFrom = r.from; state.dateTo = r.to; state.dateLabel = r.label; applyFilter(); }
}

// ================================================================== gallery

let observer = null;
const cancels = new WeakMap();

function renderGallery() {
  const g = $('gallery');
  if (observer) observer.disconnect();
  observer = new IntersectionObserver(onIntersect, { root: g, rootMargin: '800px 0px' });
  g.innerHTML = '';
  tiles = new Array(state.shown.length);
  const group = state.sort === 'new' || state.sort === 'old';
  let i = 0;
  const frag = document.createDocumentFragment();
  while (i < state.shown.length) {
    let end = state.shown.length;
    const section = el('section', { class: 'day' });
    if (group) {
      const key = new Date(state.shown[i].mtime).toDateString();
      end = i;
      while (end < state.shown.length && new Date(state.shown[end].mtime).toDateString() === key) end++;
      const s0 = i, e0 = end;
      section.append(el('div', { class: 'day-head' },
        el('span', { class: 'label', text: dayLabel(state.shown[i].mtime) }),
        el('span', { class: 'count', text: String(end - i) }),
        el('button', { class: 'day-sel', 'data-s': s0, 'data-e': e0, onclick: () => toggleRange(s0, e0) })));
      section.dataset.s = s0;
      section.dataset.e = e0;
    }
    const grid = el('div', { class: 'grid' });
    for (let k = i; k < end; k++) grid.append(tiles[k] = makeTile(state.shown[k], k));
    section.append(grid);
    frag.append(section);
    i = end;
  }
  g.append(frag);
  for (const t of tiles) observer.observe(t);
  updateSelectionMarks();
}

function makeTile(item, idx) {
  const t = el('div', { class: 'tile', 'data-i': idx, title: item.name });
  const img = el('img', { alt: '', draggable: 'false' });
  const have = cachedThumb(item);
  if (have) img.src = have;
  t.append(img);
  if (item.type === 'other') t.append(el('div', { class: 'ext', text: extOf(item.name).toUpperCase() || 'FILE' }));
  const badge = item.type === 'raw' ? (extOf(item.name).toUpperCase() || 'RAW') : item.type === 'jpg' ? 'JPG' : '';
  if (badge) t.append(el('span', { class: 'badge', text: badge }));
  t.append(el('span', { class: 'check', html: icon.check }));
  return t;
}

function onIntersect(entries) {
  for (const en of entries) {
    const t = en.target;
    const item = state.shown[+t.dataset.i];
    if (!item) continue;
    const img = t.firstChild;
    if (en.isIntersecting) {
      if (img.src) continue;
      const c = requestThumb(item, url => { if (url) img.src = url; cancels.delete(t); }, true);
      cancels.set(t, c);
    } else {
      const c = cancels.get(t);
      if (c) { c(); cancels.delete(t); }
    }
  }
}

// ================================================================== selection

export function selectedItems() { return state.shown.filter(i => state.selected.has(i.key)); }

export function onSelectionChanged() {
  updateSelectionMarks();
  renderChrome();
}

function updateSelectionMarks() {
  for (let k = 0; k < tiles.length; k++) {
    const t = tiles[k];
    if (t) t.classList.toggle('sel', state.selected.has(state.shown[k].key));
  }
  for (const b of document.querySelectorAll('.day-sel')) {
    const s = +b.dataset.s, e = +b.dataset.e;
    let all = true;
    for (let k = s; k < e; k++) if (!state.selected.has(state.shown[k].key)) { all = false; break; }
    b.textContent = all ? 'Deselect' : 'Select';
  }
}

export function clearSelection() { state.selected.clear(); onSelectionChanged(); }

function selectAllToggle() {
  if (state.selected.size === state.shown.length) state.selected.clear();
  else for (const i of state.shown) state.selected.add(i.key);
  onSelectionChanged();
}

function toggleRange(s, e) {
  let all = true;
  for (let k = s; k < e; k++) if (!state.selected.has(state.shown[k].key)) { all = false; break; }
  for (let k = s; k < e; k++) { const key = state.shown[k].key; if (all) state.selected.delete(key); else state.selected.add(key); }
  onSelectionChanged();
}

function toggle(idx) {
  const key = state.shown[idx].key;
  if (state.selected.has(key)) state.selected.delete(key); else state.selected.add(key);
}

// mouse: click opens (or toggles while selecting); click-and-drag across photos selects a range; Shift/Ctrl work too
let down = null, drag = null;

function tileIndexAt(x, y) {
  const t = document.elementFromPoint(x, y)?.closest?.('.tile');
  return t ? +t.dataset.i : -1;
}

function onDown(e) {
  if (e.button !== 0) return;
  const tile = e.target.closest('.tile');
  if (!tile) return;
  e.preventDefault();
  const idx = +tile.dataset.i;
  // a press on the round check behaves like Ctrl: a click toggles that photo; dragging still selects a range
  down = { x: e.clientX, y: e.clientY, idx, shift: e.shiftKey, ctrl: e.ctrlKey || e.metaKey || !!e.target.closest('.check') };
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp, { once: true });
}

function onMove(e) {
  if (!down) return;
  if (!drag) {
    if (Math.hypot(e.clientX - down.x, e.clientY - down.y) < 6) return;
    drag = { anchor: down.idx, target: !state.selected.has(state.shown[down.idx].key), snap: new Set(state.selected), last: -1, x: 0, y: 0 };
    autoScroll();
  }
  drag.x = e.clientX;
  drag.y = e.clientY;
  dragTo(tileIndexAt(e.clientX, e.clientY));
}

function dragTo(idx) {
  if (idx < 0 || idx === drag.last) return;
  drag.last = idx;
  const lo = Math.min(drag.anchor, idx), hi = Math.max(drag.anchor, idx);
  state.selected = new Set(drag.snap);
  for (let k = lo; k <= hi; k++) {
    const key = state.shown[k].key;
    if (drag.target) state.selected.add(key); else state.selected.delete(key);
  }
  onSelectionChanged();
}

function autoScroll() {
  if (!drag) return;
  const g = $('gallery'), r = g.getBoundingClientRect(), edge = 70;
  let dy = 0;
  if (drag.y && drag.y < r.top + edge) dy = -Math.max(3, (r.top + edge - drag.y) / edge * 28);
  else if (drag.y > r.bottom - edge) dy = Math.max(3, (drag.y - (r.bottom - edge)) / edge * 28);
  if (dy) { g.scrollTop += dy; dragTo(tileIndexAt(drag.x, drag.y)); }
  requestAnimationFrame(autoScroll);
}

function onUp() {
  document.removeEventListener('mousemove', onMove);
  const d = down, wasDrag = !!drag;
  down = null;
  drag = null;
  if (!d || wasDrag) { if (wasDrag) lastClicked = d.idx; return; }
  const idx = d.idx;
  if (d.shift && lastClicked >= 0) {
    const lo = Math.min(lastClicked, idx), hi = Math.max(lastClicked, idx);
    for (let k = lo; k <= hi; k++) state.selected.add(state.shown[k].key);
    onSelectionChanged();
  } else if (d.ctrl || state.selected.size) {
    toggle(idx);
    onSelectionChanged();
  } else {
    openViewer(app, idx);
  }
  lastClicked = idx;
}

// ================================================================== thumbnail size: Ctrl + wheel (or +/-), animated

let wheelAcc = 0, wheelLock = 0;

function onWheel(e) {
  if (!e.ctrlKey) return;
  e.preventDefault();
  wheelAcc += e.deltaY;
  const now = performance.now();
  if (now < wheelLock) return;
  if (Math.abs(wheelAcc) >= 40) {
    setColumns(state.cols + (wheelAcc > 0 ? 1 : -1), e.clientX, e.clientY);
    wheelAcc = 0;
    wheelLock = now + 160;
  }
}

/** Changes the column count; every visible photo glides to its new place, and the one under the mouse stays put. */
export function setColumns(n, ax, ay) {
  n = Math.max(2, Math.min(12, n));
  if (n === state.cols) return;
  const g = $('gallery'), gr = g.getBoundingClientRect();
  if (ax == null) { ax = gr.left + gr.width / 2; ay = gr.top + gr.height / 2; }
  const visible = el2 => { const r = el2.getBoundingClientRect(); return r.bottom > gr.top - 50 && r.top < gr.bottom + 50 ? r : null; };
  const before = new Map();
  let anchor = null, best = Infinity;
  for (const t of tiles) {
    if (!t) continue;
    const r = visible(t);
    if (!r) continue;
    before.set(t, r);
    const d = Math.hypot(r.left + r.width / 2 - ax, r.top + r.height / 2 - ay);
    if (d < best) { best = d; anchor = t; }
  }
  const heads = new Map();
  for (const h of g.querySelectorAll('.day-head')) { const r = visible(h); if (r) heads.set(h, r); }

  state.cols = n;
  setPref('cols', n);
  document.documentElement.style.setProperty('--cols', n);

  if (anchor) {  // zoom about the mouse: keep the photo under it in place
    const a0 = before.get(anchor), a1 = anchor.getBoundingClientRect();
    const want = ay - (ay - a0.top) * (a1.width / a0.width);
    g.scrollTop += a1.top - want;
  }
  const ease = 'cubic-bezier(.2,.7,.2,1)', dur = 260;
  for (const t of tiles) {
    if (!t) continue;
    const r1 = visible(t);
    if (!r1) continue;
    const r0 = before.get(t);
    if (r0 && Math.abs(r0.left - r1.left) < gr.width / 2) {
      const s = r0.width / r1.width;
      t.animate([{ transform: `translate(${r0.left - r1.left}px, ${r0.top - r1.top}px) scale(${s})`, transformOrigin: '0 0' },
        { transform: 'none', transformOrigin: '0 0' }], { duration: dur, easing: ease });
    } else {  // newly visible, or wrapped to another row: fade in where it lands
      t.animate([{ opacity: 0, transform: 'scale(.92)' }, { opacity: 1, transform: 'none' }], { duration: dur, easing: ease });
    }
  }
  for (const [h, r0] of heads) {
    const r1 = h.getBoundingClientRect();
    h.animate([{ transform: `translateY(${r0.top - r1.top}px)` }, { transform: 'none' }], { duration: dur, easing: ease });
  }
}

// ================================================================== keyboard

function onKey(e) {
  if (e.target.matches?.('input, textarea')) return;
  if (e.key === 'Escape') {
    if (closeMenu() || closeTopDialog()) return;
    if (viewerOpen()) { closeViewer(); return; }
    if (pageOpen()) { backPage(); return; }
    if (state.selected.size) clearSelection();
    return;
  }
  if (dialogOpen() || viewerOpen() || pageOpen()) return;
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') { e.preventDefault(); for (const i of state.shown) state.selected.add(i.key); onSelectionChanged(); }
  else if (e.key === 'Delete' && state.selected.size) confirmDelete(selectedItems());
  else if ((e.key === '+' || e.key === '=') && !e.ctrlKey) setColumns(state.cols - 1);
  else if (e.key === '-' && !e.ctrlKey) setColumns(state.cols + 1);
}

// ================================================================== copy / move

function startTransfer(move) {
  const items = selectedItems();
  if (!items.length || state.busy) return;
  openAlbumPicker(app, items, move ? 'move' : 'copy');
}

/** Called by the album picker. dest: {dir, label, loc, path}. */
export async function confirmTransfer(items, move, dest) {
  if (move) {
    const bytes = items.reduce((a, i) => a + i.size, 0);
    const ok = await dialog({
      title: `Move ${items.length} file${items.length === 1 ? '' : 's'}?`,
      body: `${fmtSize(bytes)} will go to ${dest.label}.\n\nEach original is deleted from the card only after its copy has been read back and checked. If anything fails, that original stays on the card.`,
      buttons: [{ text: 'Cancel', value: false }, { text: 'Move', kind: 'primary', value: true }],
    });
    if (!ok) return false;
  }
  runTransfer(items, move, dest);
  return true;
}

function progressOverlay(title) {
  const bar = el('div');
  const line1 = el('div', { class: 'ellipsis', text: 'Starting…' });
  const line2 = el('div', { class: 'dim small' });
  const cancelBtn = el('button', { class: 'btn secondary', text: 'Cancel' });
  const box = el('div', { class: 'dialog' }, el('h2', { text: title }), el('div', { class: 'progress' }, bar), line1, line2,
    el('div', { class: 'dialog-buttons' }, cancelBtn));
  const scrim = el('div', { class: 'scrim' }, box);
  document.body.append(scrim);
  return { scrim, box, bar, line1, line2, cancelBtn };
}

async function runTransfer(items, move, dest) {
  state.busy = true;
  pushRecent({ loc: dest.loc, path: dest.path, label: dest.label });
  const total = items.reduce((a, i) => a + i.size, 0);
  const ov = progressOverlay(move ? 'Moving photos' : 'Copying photos');
  let cancelled = false;
  ov.cancelBtn.onclick = () => { cancelled = true; ov.cancelBtn.textContent = 'Cancelling…'; ov.cancelBtn.disabled = true; };
  let done = 0, cur = 0, curName = '';
  const t0 = performance.now();
  const tick = setInterval(() => {
    ov.bar.style.width = (total ? done / total * 100 : 0) + '%';
    ov.line1.textContent = `${Math.min(cur, items.length)} of ${items.length} · ${curName}`;
    const secs = (performance.now() - t0) / 1000, speed = secs > 0.3 ? done / 1048576 / secs : 0;
    const left = speed > 0.1 ? ` · about ${fmtDur((total - done) / 1048576 / speed)} left` : '';
    ov.line2.textContent = `${fmtSize(done)} of ${fmtSize(total)}${speed ? ` · ${speed.toFixed(1)} MB/s` : ''}${left}`;
  }, 200);
  const r = { copied: 0, skipped: 0, failed: [], deleted: 0, deleteFailed: 0, bytes: 0, moved: [] };
  log(`${move ? 'MOVE' : 'COPY'} ${items.length} files to ${dest.label}${state.dateFolders ? ' (date folders)' : ''}`);
  const dirs = new Map();
  for (const it of items) {
    if (cancelled) { r.cancelled = true; break; }
    cur++;
    curName = it.name;
    const base = done;
    try {
      let dir = dest.dir;
      if (state.dateFolders) {
        const d = new Date(it.mtime);
        const name = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        if (!dirs.has(name)) dirs.set(name, await dest.dir.getDirectoryHandle(name, { create: true }));
        dir = dirs.get(name);
      }
      const t1 = performance.now();
      const res = await copyVerified(it, dir, n => { done += n; }, () => cancelled);
      if (res === 'skipped') { r.skipped++; log('  skipped (already there): ' + it.name); }
      else {
        r.copied++;
        r.bytes += it.size;
        let line = `  ok ${it.name} (${fmtSize(it.size)}, ${(it.size / 1048576 / Math.max(0.001, (performance.now() - t1) / 1000)).toFixed(1)} MB/s incl. verify)`;
        if (move) {
          try { await deleteItem(it); r.deleted++; r.moved.push(it); line += ' -> original deleted'; }
          catch (e) { r.deleteFailed++; line += ' -> could NOT delete original (copy is safe): ' + e.message; }
        }
        log(line);
      }
    } catch (e) {
      if (e instanceof Cancelled || cancelled) { r.cancelled = true; log('Cancelled during ' + it.name + ' (partial copy removed)'); break; }
      r.failed.push(it.name);
      log(`  FAILED ${it.name}: ${e.message}${move ? ' (original kept)' : ''}`);
    }
    done = base + it.size;
  }
  clearInterval(tick);
  const ms = performance.now() - t0;
  log(`DONE: ${r.copied} copied, ${r.skipped} skipped, ${r.failed.length} failed${move ? `, ${r.deleted} originals deleted` : ''}. ${fmtSize(r.bytes)} in ${(ms / 1000).toFixed(1)} s`);
  state.busy = false;
  invalidateCovers();
  if (r.moved.length) {
    const gone = new Set(r.moved.map(i => i.key));
    state.items = state.items.filter(i => !gone.has(i.key));
    for (const k of gone) state.removed.add(k);
  }
  for (const i of items) state.selected.delete(i.key);
  applyFilter();

  // result card
  const problems = r.failed.length || r.deleteFailed;
  ov.box.innerHTML = '';
  ov.box.append(el('h2', { text: r.cancelled ? 'Stopped' : problems ? 'Finished with problems' : 'Done', style: problems ? { color: 'var(--danger)' } : null }));
  const lines = [`${r.copied} file${r.copied === 1 ? '' : 's'} ${move ? 'moved' : 'copied'} to ${dest.label}.`];
  if (r.copied) lines.push(`${fmtSize(r.bytes)} in ${(ms / 1000).toFixed(1)} s`);
  if (r.skipped) lines.push(`${r.skipped} skipped: a file with the same name was already there.`);
  if (r.failed.length) lines.push(`${r.failed.length} failed${move ? ' (originals kept on the card)' : ''}: ${r.failed.slice(0, 4).join(', ')}${r.failed.length > 4 ? ` and ${r.failed.length - 4} more` : ''}.`);
  if (r.deleteFailed) lines.push(`${r.deleteFailed} copied but the original could not be deleted.`);
  if (r.cancelled) lines.push('You cancelled before the rest were done.');
  ov.box.append(el('p', { class: 'dim', text: lines.join('\n') }));
  const close = () => ov.scrim.remove();
  ov.box.append(el('div', { class: 'dialog-buttons' },
    problems ? el('button', { class: 'btn flat', text: 'Show log', onclick: () => { close(); openLog(app); } }) : null,
    (r.copied || r.skipped) ? el('button', { class: 'btn flat', text: 'Show in album', onclick: () => { close(); openAlbumPicker(app, [], 'browse', dest); } }) : null,
    el('button', { class: 'btn primary', text: 'Done', onclick: close })));
}

function fmtDur(s) { return s < 60 ? Math.max(1, Math.round(s)) + ' s' : Math.round(s / 60) + ' min'; }

// ================================================================== delete

function dirPath(item) { const i = item.path.lastIndexOf('/'); return i >= 0 ? item.path.slice(0, i) : ''; }
function baseName(n) { const i = n.lastIndexOf('.'); return (i > 0 ? n.slice(0, i) : n).toLowerCase(); }

/** The other half of a RAW+JPEG pair: same name, same folder, the other type. */
export function partnerOf(item) {
  if (item.type === 'other') return null;
  const b = baseName(item.name), d = dirPath(item);
  return state.items.find(o => o !== item && o.type !== 'other' && o.type !== item.type && baseName(o.name) === b && dirPath(o) === d) || null;
}

export async function confirmDelete(items) {
  if (!items.length || state.busy) return;
  if (items.length === 1) return confirmDeleteOne(items[0]);
  const bytes = items.reduce((a, i) => a + i.size, 0);
  const raw = items.filter(i => i.type === 'raw').length, jpg = items.filter(i => i.type === 'jpg').length;
  const other = items.length - raw - jpg;
  const what = [raw && `${raw} RAW`, jpg && `${jpg} JPEG`, other && `${other} other`].filter(Boolean).join(', ');
  const ok = await dialog({
    title: `Delete ${items.length} files?`,
    body: `${what} · ${fmtSize(bytes)}\n\nThis permanently deletes them from the SD card. It can't be undone.`,
    buttons: [{ text: 'Cancel', value: false }, { text: 'Delete', kind: 'danger solid', value: true }],
  });
  if (ok) runDelete(items);
}

/** One photo (the viewer, or a single selection): offers to delete its RAW/JPEG partner too, ticked by default. */
export async function confirmDeleteOne(item) {
  if (state.busy) return;
  const partner = partnerOf(item);
  const box = el('div', null, el('p', { class: 'dim', text: `${TYPE_LABEL[item.type]} · ${fmtSize(item.size)}\n\nThis permanently deletes it from the SD card. It can't be undone.` }));
  let cb = null;
  if (partner) {
    cb = el('input', { type: 'checkbox', checked: true });
    box.append(el('label', { class: 'checkline' }, cb,
      el('span', { text: `Also delete the matching ${partner.type === 'raw' ? 'RAW' : 'JPEG'}: ${partner.name} (${fmtSize(partner.size)})` })));
  }
  const ok = await dialog({
    title: `Delete ${item.name}?`, body: box,
    buttons: [{ text: 'Cancel', value: false }, { text: 'Delete', kind: 'danger solid', value: true }],
  });
  if (!ok) return;
  const todo = [item];
  if (partner && cb.checked) todo.push(partner);
  runDelete(todo);
}

async function runDelete(items) {
  state.busy = true;
  const big = items.length > 3;
  const ov = big ? progressOverlay('Deleting from the card') : null;
  let cancelled = false;
  if (ov) ov.cancelBtn.onclick = () => { cancelled = true; ov.cancelBtn.disabled = true; };
  const gone = [], failed = [];
  log(`DELETE ${items.length} files from the card`);
  for (let k = 0; k < items.length; k++) {
    if (cancelled) break;
    const it = items[k];
    if (ov) { ov.line1.textContent = `${k + 1} of ${items.length} · ${it.name}`; ov.bar.style.width = (k / items.length * 100) + '%'; }
    try { await deleteItem(it); gone.push(it); log('  deleted ' + it.name); }
    catch (e) { failed.push(it.name); log('  could NOT delete ' + it.name + ': ' + e.message); }
  }
  state.busy = false;
  const g = new Set(gone.map(i => i.key));
  state.items = state.items.filter(i => !g.has(i.key));
  for (const k of g) { state.removed.add(k); state.selected.delete(k); }
  applyFilter();
  refreshViewer();
  if (ov) ov.scrim.remove();
  if (failed.length) {
    dialog({ title: 'Finished with problems', body: `${gone.length} deleted. ${failed.length} could not be deleted: ${failed.slice(0, 4).join(', ')}${failed.length > 4 ? '…' : ''}` });
  } else toast(gone.length === 1 ? 'Deleted' : `Deleted ${gone.length} files${cancelled ? ' (stopped)' : ''}`);
}

// ================================================================== the API other modules use

export const app = {
  state, log, logText, rescan, pickCard, applyFilter, onSelectionChanged, confirmTransfer, confirmDeleteOne, partnerOf,
  setColumns, get version() { return VERSION; },
};

start();
