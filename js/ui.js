// Small UI toolkit: element builder, dialogs, menus, toasts, full-screen pages, the date range calendar.

export function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === 'class') e.className = v;
      else if (k === 'text') e.textContent = v;
      else if (k === 'html') e.innerHTML = v;
      else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else if (k === 'style' && typeof v === 'object') Object.assign(e.style, v);
      else e.setAttribute(k, v === true ? '' : v);
    }
  }
  for (const k of kids.flat()) if (k != null && k !== false) e.append(k.nodeType ? k : document.createTextNode(k));
  return e;
}

export const icon = {
  back: '<svg viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z"/></svg>',
  close: '<svg viewBox="0 0 24 24"><path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>',
  gear: '<svg viewBox="0 0 24 24"><path d="M19.14 12.94a7.43 7.43 0 0 0 .06-.94 7.43 7.43 0 0 0-.06-.94l2.03-1.58a.5.5 0 0 0 .12-.64l-1.92-3.32a.5.5 0 0 0-.61-.22l-2.39.96a7.03 7.03 0 0 0-1.62-.94l-.36-2.54a.5.5 0 0 0-.5-.42h-3.84a.5.5 0 0 0-.5.42l-.36 2.54c-.59.24-1.13.56-1.62.94l-2.39-.96a.5.5 0 0 0-.61.22L2.71 8.84a.5.5 0 0 0 .12.64l2.03 1.58a7.43 7.43 0 0 0 0 1.88l-2.03 1.58a.5.5 0 0 0-.12.64l1.92 3.32c.13.22.39.3.61.22l2.39-.96c.49.38 1.03.7 1.62.94l.36 2.54c.05.24.26.42.5.42h3.84c.25 0 .45-.18.5-.42l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.48 0 .61-.22l1.92-3.32a.5.5 0 0 0-.12-.64zM12 15.5A3.5 3.5 0 1 1 12 8.5a3.5 3.5 0 0 1 0 7z"/></svg>',
  sort: '<svg viewBox="0 0 24 24"><path d="M16 17.01V10h-2v7.01h-3L15 21l4-3.99h-3zM9 3 5 6.99h3V14h2V6.99h3L9 3z"/></svg>',
  trash: '<svg viewBox="0 0 24 24"><path d="M16 9v10H8V9h8m-1.5-6h-5l-1 1H5v2h14V4h-3.5l-1-1zM18 7H6v12c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7z"/></svg>',
  left: '<svg viewBox="0 0 24 24"><path d="M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z"/></svg>',
  right: '<svg viewBox="0 0 24 24"><path d="M8.59 16.59 10 18l6-6-6-6-1.41 1.41L13.17 12z"/></svg>',
  up: '<svg viewBox="0 0 24 24"><path d="M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z"/></svg>',
  check: '<svg viewBox="0 0 24 24"><path d="M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>',
  cal: '<svg viewBox="0 0 24 24"><path d="M19 4h-1V2h-2v2H8V2H6v2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 16H5V10h14v10zm0-12H5V6h14v2z"/></svg>',
  down: '<svg viewBox="0 0 24 24"><path d="M7 10l5 5 5-5z"/></svg>',
  folder: '<svg viewBox="0 0 24 24"><path d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z"/></svg>',
  plus: '<svg viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>',
  minus: '<svg viewBox="0 0 24 24"><path d="M19 13H5v-2h14v2z"/></svg>',
};

export function iconBtn(svg, title, onclick, cls) {
  return el('button', { class: 'icon-btn ' + (cls || ''), title, 'aria-label': title, html: svg, onclick });
}

// ------------------------------------------------------------------ toast

let toastTimer;
export function toast(msg) {
  let t = document.getElementById('toast');
  if (!t) { t = el('div', { id: 'toast' }); document.body.append(t); }
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 2200);
}

// ------------------------------------------------------------------ modal dialogs

const dialogStack = [];
export function dialogOpen() { return dialogStack.length > 0; }

/** Shows a modal. buttons: [{text, kind: 'primary'|'danger'|'', value, close: true}]. Resolves with the value. */
export function dialog({ title, body, buttons, wide }) {
  return new Promise(resolve => {
    const scrim = el('div', { class: 'scrim' });
    const box = el('div', { class: 'dialog' + (wide ? ' wide' : ''), role: 'dialog', 'aria-modal': 'true' });
    if (title) box.append(el('h2', { text: title }));
    if (body) box.append(typeof body === 'string' ? el('p', { class: 'dim', text: body }) : body);
    const row = el('div', { class: 'dialog-buttons' });
    const close = (v) => {
      scrim.remove();
      dialogStack.splice(dialogStack.indexOf(entry), 1);
      resolve(v);
    };
    const entry = { close, cancel: () => close(undefined) };
    for (const b of buttons || [{ text: 'OK', kind: 'primary', value: true }]) {
      const btn = el('button', { class: 'btn ' + (b.kind || 'flat'), text: b.text });
      btn.addEventListener('click', () => {
        if (b.onclick && b.onclick() === false) return;
        close(b.value);
      });
      row.append(btn);
      if (b.ref) b.ref(btn);
    }
    box.append(row);
    scrim.append(box);
    scrim.addEventListener('mousedown', e => { if (e.target === scrim) entry.cancel(); });
    document.body.append(scrim);
    dialogStack.push(entry);
    const focusBtn = row.querySelector('.btn.primary, .btn.danger') || row.lastChild;
    (box.querySelector('input') || focusBtn)?.focus();
  });
}

export function closeTopDialog() {
  const d = dialogStack[dialogStack.length - 1];
  if (d) { d.cancel(); return true; }
  return false;
}

export async function promptText(title, value, okText) {
  const input = el('input', { type: 'text', value, class: 'text-input' });
  input.addEventListener('focus', () => input.select());
  let okBtn;
  input.addEventListener('keydown', e => { if (e.key === 'Enter') okBtn.click(); });
  const v = await dialog({
    title, body: input,
    buttons: [{ text: 'Cancel', value: null }, { text: okText || 'OK', kind: 'primary', value: 'ok', ref: b => { okBtn = b; } }],
  });
  return v === 'ok' ? input.value : null;
}

// ------------------------------------------------------------------ dropdown menus

let openMenu = null;
export function closeMenu() { if (openMenu) { openMenu.remove(); openMenu = null; return true; } return false; }

/** items: [{text, checked, onclick}] shown under the anchor element. */
export function menu(anchor, items) {
  closeMenu();
  const m = el('div', { class: 'menu', role: 'menu' });
  for (const it of items) {
    if (it === '-') { m.append(el('div', { class: 'menu-sep' })); continue; }
    m.append(el('button', {
      class: 'menu-item' + (it.checked ? ' checked' : ''), role: 'menuitem',
      onclick: () => { closeMenu(); it.onclick(); },
    }, el('span', { class: 'menu-check', html: it.checked ? icon.check : '' }), it.text));
  }
  document.body.append(m);
  const r = anchor.getBoundingClientRect();
  const left = Math.min(r.left, window.innerWidth - m.offsetWidth - 8);
  m.style.left = Math.max(8, left) + 'px';
  m.style.top = (r.bottom + 4) + 'px';
  openMenu = m;
  setTimeout(() => {
    const off = (e) => { if (!m.contains(e.target)) { closeMenu(); document.removeEventListener('mousedown', off, true); } };
    document.addEventListener('mousedown', off, true);
  });
}

// ------------------------------------------------------------------ pages (full-screen layers above the gallery)

const pages = [];
export function pageOpen() { return pages.length > 0; }
export function topPage() { return pages[pages.length - 1]; }

/** page: {el, onBack?: () => bool (true = handled), onRefresh?} */
export function pushPage(page) {
  page.el.classList.add('page');
  document.getElementById('pages').append(page.el);
  pages.push(page);
  requestAnimationFrame(() => page.el.classList.add('in'));
}

export function popPage() {
  const p = pages.pop();
  if (!p) return;
  if (p.onClose) p.onClose();
  p.el.classList.remove('in');
  setTimeout(() => p.el.remove(), 160);
}

export function backPage() {
  const p = topPage();
  if (!p) return false;
  if (p.onBack && p.onBack()) return true;
  popPage();
  return true;
}

export function refreshPages() { for (const p of pages) if (p.onRefresh) p.onRefresh(); }

export function pageBar(title, subtitle, ...actions) {
  return el('header', { class: 'bar' },
    iconBtn(icon.back, 'Back (Esc)', () => backPage()),
    el('div', { class: 'bar-title' }, el('div', { class: 'title', text: title }), subtitle ? el('div', { class: 'subtitle', text: subtitle }) : null),
    ...actions);
}

// ------------------------------------------------------------------ date range calendar

const ymd = d => d.getFullYear() * 10000 + (d.getMonth() + 1) * 100 + d.getDate();
const fromYmd = v => new Date(Math.floor(v / 10000), Math.floor(v / 100) % 100 - 1, v % 100);
export { ymd };

/** Click the first day, then the last. photoDays: Set of yyyymmdd with photos. Resolves {from, to, label} or null. */
export function dateRangeDialog(photoDays, newest) {
  const month = newest ? new Date(newest) : new Date();
  month.setDate(1);
  let start = 0, end = 0;
  const summary = el('div', { class: 'cal-summary' });
  const monthLabel = el('div', { class: 'cal-month' });
  const grid = el('div', { class: 'cal-grid' });
  const fmt = (v, year) => fromYmd(v).toLocaleDateString(undefined, year ? { month: 'short', day: 'numeric', year: 'numeric' } : { month: 'short', day: 'numeric' });
  const label = (s, e) => s === e ? fmt(s, true) : (fromYmd(s).getFullYear() === fromYmd(e).getFullYear() ? fmt(s) : fmt(s, true)) + ' – ' + fmt(e, true);
  let applyBtn;
  const render = () => {
    monthLabel.textContent = month.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
    summary.textContent = !start ? 'Click the first day' : !end ? label(start, start) + '  →  click the last day' : label(start, end);
    if (applyBtn) applyBtn.disabled = !start;
    grid.innerHTML = '';
    const first = new Date(month), lead = first.getDay();
    for (const n of ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa']) grid.append(el('div', { class: 'cal-dow', text: n }));
    for (let i = 0; i < lead; i++) grid.append(el('div'));
    const days = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
    const today = ymd(new Date());
    for (let d = 1; d <= days; d++) {
      const v = ymd(new Date(month.getFullYear(), month.getMonth(), d));
      const endpoint = v === start || (end && v === end);
      const inside = start && end && v > start && v < end;
      const cls = ['cal-day', endpoint && 'end', inside && 'inside', photoDays.has(v) && 'has', v === today && 'today'].filter(Boolean).join(' ');
      grid.append(el('button', {
        class: cls, onclick: () => {
          if (!start || end) { start = v; end = 0; } else if (v < start) start = v; else end = v;
          render();
        },
      }, el('span', { text: String(d) })));
    }
  };
  const nav = el('div', { class: 'cal-nav' },
    iconBtn(icon.left, 'Previous month', () => { month.setMonth(month.getMonth() - 1); render(); }),
    monthLabel,
    iconBtn(icon.right, 'Next month', () => { month.setMonth(month.getMonth() + 1); render(); }));
  const body = el('div', { class: 'cal' }, summary, el('div', { class: 'dim small', text: 'Dots mark days with photos on the card.' }), nav, grid);
  render();
  return dialog({
    title: null, body,
    buttons: [{ text: 'Cancel', value: null }, { text: 'Apply', kind: 'primary', value: 'apply', ref: b => { applyBtn = b; b.disabled = !start; } }],
  }).then(v => {
    if (v !== 'apply' || !start) return null;
    const e = end || start;
    const from = fromYmd(start).getTime();
    const toD = fromYmd(e); toD.setDate(toD.getDate() + 1);
    return { from, to: toD.getTime() - 1, label: start === e ? fmt(start) : fmt(start) + ' – ' + fmt(e) };
  });
}

export function fmtSize(n) {
  if (n >= 1073741824) return (n / 1073741824).toFixed(2) + ' GB';
  if (n >= 1048576) return (n / 1048576).toFixed(1) + ' MB';
  if (n >= 1024) return Math.round(n / 1024) + ' KB';
  return n + ' B';
}
