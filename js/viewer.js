// Full-screen photo viewer for mouse and keyboard.
// Scroll wheel zooms (around the pointer), drag pans when zoomed, drag left/right (or arrows / ← →) changes photo.
import { el, icon, iconBtn, fmtSize } from './ui.js';
import { largeImage, details, cachedThumb, requestThumb, TYPE_LABEL } from './media.js';

let V = null;
const detailCache = new Map();
const GAP = 24;

export function viewerOpen() { return !!V; }

export function openViewer(app, index) {
  const root = document.getElementById('viewer');
  root.innerHTML = '';
  const track = el('div', { class: 'track' });
  const slots = [0, 1, 2].map(() => {
    const s = el('div', { class: 'slide' });
    const img = el('img', { alt: '', draggable: 'false' });
    s.append(img);
    track.append(s);
    return { el: s, img, key: null, large: false, url: null, w: 0, h: 0, s: 1, fit: 1, tx: 0, ty: 0 };
  });
  const title = el('div', { class: 't' }), counter = el('div', { class: 's' });
  const selBtn = el('button', { class: 'selbtn', onclick: () => toggleSelect() });
  const bar = el('div', { class: 'vbar' },
    iconBtn(icon.back, 'Back to gallery (Esc)', () => closeViewer()),
    el('div', { class: 'vtitle' }, title, counter),
    iconBtn(icon.trash, 'Delete (Del)', () => del()),
    selBtn);
  const d1 = el('div', { class: 'd1' }), d2 = el('div', { class: 'd2' });
  const info = el('div', { class: 'vinfo' }, d1, d2);
  const prev = el('button', { class: 'nav prev', title: 'Previous (←)', html: icon.left, onclick: () => go(-1) });
  const next = el('button', { class: 'nav next', title: 'Next (→)', html: icon.right, onclick: () => go(1) });
  const zoomLabel = el('div', { class: 'zoomlabel' });
  root.append(track, bar, info, prev, next, zoomLabel);
  root.classList.add('open');
  V = { app, root, track, slots, index, title, counter, selBtn, d1, d2, prev, next, zoomLabel, offset: 0, anim: null };
  layout();
  loadAll();
  root.addEventListener('wheel', onWheel, { passive: false });
  root.addEventListener('pointerdown', onPointerDown);
  root.addEventListener('dblclick', onDbl);
  root.addEventListener('mousemove', wake);
  window.addEventListener('resize', onResize);
  document.addEventListener('keydown', onKey);
  wake();
}

export function closeViewer() {
  if (!V) return;
  const { root, app, index } = V;
  for (const s of V.slots) if (s.url) URL.revokeObjectURL(s.url);
  root.removeEventListener('wheel', onWheel);
  root.removeEventListener('pointerdown', onPointerDown);
  root.removeEventListener('dblclick', onDbl);
  root.removeEventListener('mousemove', wake);
  window.removeEventListener('resize', onResize);
  document.removeEventListener('keydown', onKey);
  clearTimeout(V.idle);
  root.classList.remove('open');
  root.innerHTML = '';
  V = null;
  const t = document.querySelector(`.tile[data-i="${index}"]`);
  if (t) t.scrollIntoView({ block: 'nearest' });
  app.onSelectionChanged();
}

/** After a delete or rescan: keep showing a valid photo. */
export function refreshViewer() {
  if (!V) return;
  const shown = V.app.state.shown;
  if (!shown.length) { closeViewer(); return; }
  V.index = Math.min(V.index, shown.length - 1);
  loadAll();
}

// ---------------------------------------------------------------- layout & loading

function W() { return V.root.clientWidth; }
function H() { return V.root.clientHeight; }

function layout() {
  const w = W() + GAP;
  V.slots.forEach((s, k) => { s.el.style.left = ((k - 1) * w) + 'px'; });
  V.track.style.transform = `translateX(${V.offset}px)`;
}

function onResize() { layout(); for (const s of V.slots) fit(s); }

function loadAll() {
  loadSlot(1, V.index);
  loadSlot(2, V.index + 1);
  loadSlot(0, V.index - 1);
  onCurrent();
}

function loadSlot(k, idx) {
  const s = V.slots[k];
  const shown = V.app.state.shown;
  if (idx < 0 || idx >= shown.length) {
    s.key = null; s.large = false; s.img.removeAttribute('src'); s.w = s.h = 0;
    if (s.url) { URL.revokeObjectURL(s.url); s.url = null; }
    return;
  }
  const item = shown[idx];
  if (s.key === item.key && s.large) return;
  if (s.key !== item.key) {
    if (s.url) { URL.revokeObjectURL(s.url); s.url = null; }
    s.key = item.key;
    s.large = false;
    s.s = 0;
    const t = cachedThumb(item);
    if (t) setSrc(s, t, false);
    else { s.img.removeAttribute('src'); requestThumb(item, u => { if (u && s.key === item.key && !s.large) setSrc(s, u, false); }, true); }
  }
  const key = item.key;
  largeImage(item).then(r => {
    if (!r) return;
    if (!V || s.key !== key) { URL.revokeObjectURL(r.url); return; }
    if (s.url) URL.revokeObjectURL(s.url);
    s.url = r.url;
    s.large = true;
    setSrc(s, r.url, true);
  }).catch(e => V && V.app.log('Preview failed for ' + item.name + ': ' + e.message));
}

function setSrc(s, url, keepZoom) {
  const probe = new Image();
  probe.onload = () => {
    if (!V) return;
    const ow = s.w, zoomed = s.s > s.fit * 1.01 && ow;
    s.img.src = url;
    s.w = probe.naturalWidth;
    s.h = probe.naturalHeight;
    s.img.style.width = s.w + 'px';
    s.img.style.height = s.h + 'px';
    if (keepZoom && zoomed) {  // same photo, sharper: keep the same view
      s.s *= ow / s.w;
      s.fit = Math.min(W() / s.w, H() / s.h);
      apply(s);
    } else fit(s);
  };
  probe.src = url;
}

function fit(s) {
  if (!s.w) return;
  s.fit = Math.min(W() / s.w, H() / s.h);
  s.s = s.fit;
  s.tx = (W() - s.w * s.s) / 2;
  s.ty = (H() - s.h * s.s) / 2;
  apply(s);
}

function clamp(s) {
  const w = s.w * s.s, h = s.h * s.s;
  s.tx = w <= W() ? (W() - w) / 2 : Math.min(0, Math.max(W() - w, s.tx));
  s.ty = h <= H() ? (H() - h) / 2 : Math.min(0, Math.max(H() - h, s.ty));
}

function apply(s) { s.img.style.transform = `translate(${s.tx}px, ${s.ty}px) scale(${s.s})`; }

function zoomed() { const s = V.slots[1]; return s.w && s.s > s.fit * 1.01; }

// ---------------------------------------------------------------- current photo & details

function current() {
  if (V.anim) finishAnim();
  const item = V.app.state.shown[V.index];
  return item && item.key === V.slots[1].key ? item : null;
}

const DOT = '  ·  ';

function onCurrent() {
  const shown = V.app.state.shown, item = shown[V.index];
  if (!item) return;
  V.title.textContent = item.name;
  V.counter.textContent = `${V.index + 1} of ${shown.length}`;
  const d = new Date(item.mtime);
  V.d1.textContent = d.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' })
    + DOT + d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit', hour12: true });
  V.d2.textContent = compose(item, detailCache.get(item.key));
  V.prev.disabled = V.index === 0;
  V.next.disabled = V.index === shown.length - 1;
  updateSel();
  fetchDetails(V.index, true);
  fetchDetails(V.index + 1);
  fetchDetails(V.index - 1);
}

function compose(item, det) {
  let line = TYPE_LABEL[item.type] + DOT + fmtSize(item.size);
  if (det && det.res) line += DOT + det.res;
  const lines = [line];
  if (det && det.cam) lines.push(det.cam);
  if (det && det.exp) lines.push(det.exp);
  return lines.join('\n');
}

function fetchDetails(idx) {
  const item = V.app.state.shown[idx];
  if (!item || item.type === 'other' || detailCache.has(item.key)) return;
  detailCache.set(item.key, null);
  details(item).then(d => {
    detailCache.set(item.key, d);
    if (V && V.app.state.shown[V.index] === item) V.d2.textContent = compose(item, d);
  }).catch(() => detailCache.delete(item.key));
}

function updateSel() {
  const item = V.app.state.shown[V.index];
  const on = item && V.app.state.selected.has(item.key);
  V.selBtn.textContent = on ? '✓ Selected' : 'Select';
  V.selBtn.classList.toggle('on', !!on);
}

function toggleSelect() {
  const item = current();
  if (!item) return;
  const sel = V.app.state.selected;
  if (sel.has(item.key)) sel.delete(item.key); else sel.add(item.key);
  updateSel();
  V.app.onSelectionChanged();
}

function del() {
  const item = current();
  if (item) V.app.confirmDeleteOne(item);
}

// ---------------------------------------------------------------- changing photo (animated slide)

function go(dir) {
  if (V.anim) finishAnim();
  const n = V.app.state.shown.length;
  if ((dir > 0 && V.index >= n - 1) || (dir < 0 && V.index <= 0)) { settle(0); return; }
  settle(dir);
}

/** Slides the track to the next/previous photo (dir ±1) or back to centre (0). */
function settle(dir) {
  const target = dir === 1 ? -(W() + GAP) : dir === -1 ? W() + GAP : 0;
  const from = V.offset;
  const dist = Math.abs(target - from) / (W() + GAP);
  const dur = 120 + 200 * Math.min(1, dist);
  const t0 = performance.now();
  const anim = { dir, done: false };
  V.anim = anim;
  const step = (now) => {
    if (!V || V.anim !== anim) return;
    const t = Math.min(1, (now - t0) / dur), e = 1 - Math.pow(1 - t, 3);
    V.offset = from + (target - from) * e;
    V.track.style.transform = `translateX(${V.offset}px)`;
    if (t < 1) requestAnimationFrame(step); else finishAnim();
  };
  requestAnimationFrame(step);
}

function finishAnim() {
  const a = V.anim;
  if (!a || a.done) return;
  a.done = true;
  V.anim = null;
  V.offset = 0;
  if (a.dir) rotate(a.dir);
  layout();
}

function rotate(dir) {
  const sl = V.slots;
  if (dir === 1) {
    const t = sl.shift(); sl.push(t);
    V.index++;
    fit(sl[0]);
    loadSlot(2, V.index + 1);
  } else {
    const t = sl.pop(); sl.unshift(t);
    V.index--;
    fit(sl[2]);
    loadSlot(0, V.index - 1);
  }
  onCurrent();
}

// ---------------------------------------------------------------- mouse & keyboard

function onWheel(e) {
  e.preventDefault();
  if (e.target.closest('.vbar, .vinfo, .nav')) return;
  if (V.anim) finishAnim();
  const s = V.slots[1];
  if (!s.w) return;
  const factor = Math.exp(-e.deltaY * (e.deltaMode === 1 ? 0.05 : 0.0018));
  zoomAt(s, s.s * factor, e.clientX, e.clientY);
}

function zoomAt(s, ns, cx, cy) {
  ns = Math.max(s.fit, Math.min(s.fit * 10, ns));
  const px = (cx - s.tx) / s.s, py = (cy - s.ty) / s.s;
  s.s = ns;
  s.tx = cx - px * ns;
  s.ty = cy - py * ns;
  clamp(s);
  apply(s);
  V.zoomLabel.textContent = Math.round(ns / s.fit * 100) + '%';
  V.zoomLabel.classList.add('show');
  clearTimeout(V.zoomT);
  V.zoomT = setTimeout(() => V && V.zoomLabel.classList.remove('show'), 700);
}

function onDbl(e) {
  if (e.target.closest('.vbar, .vinfo, .nav')) return;
  const s = V.slots[1];
  if (!s.w) return;
  const target = zoomed() ? s.fit : s.fit * 2.5;
  const from = s.s, t0 = performance.now(), cx = e.clientX, cy = e.clientY;
  const step = (now) => {
    if (!V) return;
    const t = Math.min(1, (now - t0) / 200), k = 1 - Math.pow(1 - t, 3);
    zoomAt(s, from + (target - from) * k, cx, cy);
    if (t < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

function onPointerDown(e) {
  if (e.button !== 0 || e.target.closest('.vbar, .vinfo, .nav')) return;
  if (V.anim) finishAnim();
  e.preventDefault();
  const s = V.slots[1];
  const startX = e.clientX, startY = e.clientY, tx0 = s.tx, ty0 = s.ty;
  const panning = zoomed();
  let lastX = startX, lastT = performance.now(), vx = 0;
  V.root.style.cursor = 'grabbing';
  const move = (ev) => {
    const dx = ev.clientX - startX, dy = ev.clientY - startY;
    const now = performance.now();
    vx = (ev.clientX - lastX) / Math.max(1, now - lastT) * 1000;
    lastX = ev.clientX; lastT = now;
    if (panning) { s.tx = tx0 + dx; s.ty = ty0 + dy; clamp(s); apply(s); return; }
    const n = V.app.state.shown.length;
    const edge = (dx > 0 && V.index === 0) || (dx < 0 && V.index === n - 1);
    V.offset = edge ? dx * 0.3 : dx;
    V.track.style.transform = `translateX(${V.offset}px)`;
  };
  const up = () => {
    document.removeEventListener('pointermove', move);
    document.removeEventListener('pointerup', up);
    if (!V) return;
    V.root.style.cursor = '';
    if (panning) return;
    const n = V.app.state.shown.length, w = W() + GAP;
    let dir = 0;
    if ((V.offset < -w * 0.18 || vx < -600) && V.offset < 0 && V.index < n - 1) dir = 1;
    else if ((V.offset > w * 0.18 || vx > 600) && V.offset > 0 && V.index > 0) dir = -1;
    settle(dir);
  };
  document.addEventListener('pointermove', move);
  document.addEventListener('pointerup', up);
}

function onKey(e) {
  if (document.querySelector('.scrim')) return;  // a dialog is open
  if (e.key === 'ArrowRight') { e.preventDefault(); go(1); }
  else if (e.key === 'ArrowLeft') { e.preventDefault(); go(-1); }
  else if (e.key === 'Delete') { e.preventDefault(); del(); }
  else if (e.key === ' ') { e.preventDefault(); toggleSelect(); }
  else if (e.key === '+' || e.key === '=') { const s = V.slots[1]; zoomAt(s, s.s * 1.25, W() / 2, H() / 2); }
  else if (e.key === '-') { const s = V.slots[1]; zoomAt(s, s.s / 1.25, W() / 2, H() / 2); }
  else if (e.key === '0') fit(V.slots[1]);
}

function wake() {
  if (!V) return;
  V.root.classList.remove('chrome-off');
  V.root.style.cursor = '';
  clearTimeout(V.idle);
  V.idle = setTimeout(() => { if (V) { V.root.classList.add('chrome-off'); } }, 3000);
}
