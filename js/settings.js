// Settings (one-time setup lives here) and the activity log.
import { el, icon, pushPage, pageBar, dialog, toast, menu } from './ui.js';
import { setPref, locationList, removeLocation, recents, setPref as save, idbClear } from './store.js';
import { pickFolder } from './files.js';
import { addLocation, diskPath, setDiskPath } from './store.js';
import { thumbStats } from './media.js';

function row(ico, title, sub, trailing, onclick) {
  const tag = onclick ? 'button' : 'div';
  return el(tag, { class: 'row', onclick },
    el('span', { class: 'ri', html: ico || '' }),
    el('div', { class: 'rt' }, el('div', { class: 't', text: title }), sub ? el('div', { class: 's', text: sub }) : null),
    trailing || null);
}

export function openSettings(app) {
  const body = el('div', { class: 'content' });
  const page = { el: el('div', null, pageBar('Settings'), el('div', { class: 'scroll' }, body)) };
  const render = () => {
    const s = app.state;
    body.innerHTML = '';

    body.append(el('div', { class: 'section-head', text: 'Camera card' }));
    body.append(el('div', { class: 'card' },
      row(icon.folder, 'Card folder', s.card ? s.card.name : 'Not set – click to choose', null, () => app.pickCard()),
      row(null, 'Rescan card', s.scanning ? 'Reading…' : `${s.items.length} files found`, null, () => app.rescan())));

    body.append(el('div', { class: 'section-head', text: 'Photo locations' }));
    body.append(el('p', { class: 'dim small', style: { margin: '0 4px 8px' }, text: 'Add a top folder once (for example Pictures). When you copy, you pick or create albums inside it in Offload\'s own album browser.' }));
    const locCard = el('div', { class: 'card' });
    for (const l of locationList()) {
      locCard.append(row(icon.folder, l.name, null, el('button', {
        class: 'btn flat', text: 'Remove', onclick: async (e) => {
          e.stopPropagation();
          const ok = await dialog({ title: `Remove ${l.name}?`, body: 'Offload will no longer save into this folder. Files already there are not touched.', buttons: [{ text: 'Cancel', value: false }, { text: 'Remove', kind: 'primary', value: true }] });
          if (ok) { await removeLocation(l.id); render(); }
        },
      }), null));
    }
    for (const l of locationList()) {
      locCard.append(row(null, `Where is ${l.name} on disk?`, diskPath(l.id) || 'For "Show in File Explorer" \u2013 browsers can\'t tell us. Optional.', null, async () => {
        const input = el('input', { type: 'text', class: 'text-input', value: diskPath(l.id), placeholder: 'C:\\Users\\you\\Pictures' });
        const v = await dialog({ title: `Folder path of ${l.name}`, body: input, buttons: [{ text: 'Cancel', value: null }, { text: 'Save', kind: 'primary', value: 'ok' }] });
        if (v === 'ok') { setDiskPath(l.id, input.value.trim().replace(/^"|"$/g, '')); render(); }
      }));
    }
    locCard.append(row(icon.plus, 'Add photo location', 'The browser asks once; it is remembered', null, async () => {
      try { const h = await pickFolder('dest'); await addLocation(h); app.log('Photo location added: ' + h.name); render(); }
      catch (e) { if (e.name !== 'AbortError') toast(e.message); }
    }));
    if (recents().length) locCard.append(row(null, 'Clear recent albums', null, null, () => { save('recents', []); render(); }));
    body.append(locCard);

    body.append(el('div', { class: 'section-head', text: 'Copying' }));
    const sw = el('input', { type: 'checkbox', class: 'switch' });
    sw.checked = s.dateFolders;
    sw.addEventListener('change', () => { s.dateFolders = sw.checked; setPref('dateFolders', sw.checked); });
    sw.addEventListener('click', e => e.stopPropagation());
    body.append(el('div', { class: 'card' },
      row(icon.cal, 'Sort into date folders', 'Creates YYYY-MM-DD folders from the card\'s file dates', sw, () => { sw.checked = !sw.checked; sw.dispatchEvent(new Event('change')); }),
      row(icon.check, 'Every copy is verified', 'Each copy is read back and checked. Files already in the album are skipped, never overwritten. Move deletes an original only after its copy checks out.', null, null)));

    body.append(el('div', { class: 'section-head', text: 'Gallery' }));
    body.append(el('div', { class: 'card' },
      row(null, 'Thumbnail size', `${s.cols} per row – or hold Ctrl and use the scroll wheel in the gallery`, null, (e) => {
        menu(e.currentTarget, [2, 3, 4, 5, 6, 7, 8, 10, 12].map(n => ({ text: `${n} per row`, checked: s.cols === n, onclick: () => { app.setColumns(n); render(); } })));
      }),
      row(null, 'Keyboard and mouse', 'Click opens a photo · drag across photos to select · Shift/Ctrl+click · Ctrl+A · Del · Esc · in the viewer: wheel zooms, drag or ← → to move', null, null)));

    body.append(el('div', { class: 'section-head', text: 'Diagnostics' }));
    const avg = thumbStats.ok ? Math.round(thumbStats.ms / thumbStats.ok) : 0;
    body.append(el('div', { class: 'card' },
      row(null, 'Activity log', 'Scans and transfers, with a copyable report', null, () => openLog(app)),
      row(null, 'Thumbnails', `${thumbStats.ok} made${avg ? ` (avg ${avg} ms)` : ''}, ${thumbStats.cached} from cache, ${thumbStats.failed} failed`, null, null),
      row(null, 'Clear thumbnail cache', 'Frees space; thumbnails are made again when needed', null, async () => { await idbClear('thumbs'); await idbClear('covers'); toast('Cache cleared'); })));

    body.append(el('p', { class: 'dim small', style: { textAlign: 'center', margin: '28px 0' }, text: `Offload ${app.version} for Windows` }));
  };
  page.onRefresh = render;
  render();
  pushPage(page);
}

export function openLog(app) {
  const pre = el('div', { class: 'log', text: app.logText() });
  const copy = el('button', {
    class: 'btn flat', text: 'Copy', onclick: async () => {
      try { await navigator.clipboard.writeText(app.logText()); toast('Report copied'); } catch (e) { toast('Could not copy'); }
    },
  });
  const scroll = el('div', { class: 'scroll' }, pre);
  pushPage({ el: el('div', null, pageBar('Activity log', null, copy), scroll), onRefresh: () => { pre.textContent = app.logText(); } });
  requestAnimationFrame(() => { scroll.scrollTop = scroll.scrollHeight; });
}
