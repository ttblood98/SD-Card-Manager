const { chromium } = require('playwright');
// End-to-end test of the Windows web app in headless Chromium, on a fake card built in the browser's private file system.
// Run from the repo root:  python3 -m http.server 8123 &   then   node test/e2e/flow.test.js
const path = require('path');
const D = process.env.OUT || require('os').tmpdir();
const FIXTURE = path.join(__dirname, 'fixture.js');
const ls = (p, path) => p.evaluate(async (path) => {
  let d = await navigator.storage.getDirectory();
  for (const n of path) d = await d.getDirectoryHandle(n);
  const out = [];
  for await (const [n, h] of d.entries()) out.push(h.kind === 'directory' ? n + '/' : n);
  return out.sort().join(', ');
}, path);
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 1280, height: 800 } });
  await ctx.addInitScript({ path: FIXTURE });
  const p = await ctx.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push('pageerror: ' + e.message));
  p.on('console', m => { if (m.type() === 'error') errs.push('console: ' + m.text()); });
  await p.goto('http://127.0.0.1:8123/index.html');
  await p.evaluate(() => { localStorage.clear(); return window.__buildFixture(); });
  await p.reload();
  await p.click('text=Choose card folder');
  await p.waitForSelector('.tile');
  await p.waitForTimeout(800);
  // drag-select the first 4 photos (start in the middle of a tile)
  const c = async i => { const r = await p.locator('.tile').nth(i).boundingBox(); return [r.x + r.width / 2, r.y + r.height / 2]; };
  const [x0, y0] = await c(0), [x3, y3] = await c(3);
  await p.mouse.move(x0, y0); await p.mouse.down(); await p.mouse.move(x3, y3, { steps: 8 }); await p.mouse.up();
  console.log('selected:', await p.textContent('#title'));
  // click the check of tile 5 to add it
  const r5 = await p.locator('.tile').nth(5).boundingBox();
  await p.mouse.click(r5.x + 18, r5.y + 18);
  console.log('after check click:', await p.textContent('#title'));
  await p.click('text=Copy to album');
  await p.waitForTimeout(400);
  await p.screenshot({ path: D + '/10-picker-empty.png' });
  await p.click('text=Add a photo location');
  await p.waitForTimeout(1200);
  await p.screenshot({ path: D + '/11-picker.png' });
  await p.click('.album.new');
  await p.waitForSelector('.text-input');
  await p.fill('.text-input', 'Shoot A');
  await p.click('.dialog .btn.primary');
  await p.waitForTimeout(800);
  await p.screenshot({ path: D + '/12-album.png' });
  await p.click('.footer .btn.primary');
  await p.waitForSelector('text=Done', { timeout: 15000 });
  await p.waitForTimeout(300);
  console.log('result:', (await p.textContent('.scrim .dialog')).replace(/\s+/g, ' '));
  await p.screenshot({ path: D + '/13-result.png' });
  console.log('Pictures/Shoot A:', await ls(p, ['Pictures', 'Shoot A']));
  await p.click('text=Show in album');
  await p.waitForTimeout(1200);
  await p.screenshot({ path: D + '/14-show-in-album.png' });
  for (let k = 0; k < 5 && await p.locator('#pages .page').count(); k++) { await p.keyboard.press('Escape'); await p.waitForTimeout(250); }
  // MOVE a NEF + JPG pair into Trip 2026
  const idx = await p.evaluate(() => [...document.querySelectorAll('.tile')].findIndex(t => t.title === 'DSC_4512.NEF'));
  const idj = await p.evaluate(() => [...document.querySelectorAll('.tile')].findIndex(t => t.title === 'DSC_4512.JPG'));
  await p.keyboard.down('Control');
  await p.locator('.tile').nth(idx).click(); await p.locator('.tile').nth(idj).click();
  await p.keyboard.up('Control');
  console.log('move selection:', await p.textContent('#title'));
  await p.click('text=Move to album');
  await p.waitForTimeout(800);
  await p.screenshot({ path: D + '/15-picker-recent.png' });
  await p.click('.album[title="Trip 2026"]');
  await p.waitForTimeout(800);
  await p.click('.footer .btn.primary');
  await p.waitForSelector('.dialog >> text=Move', { timeout: 5000 });
  await p.click('.dialog .btn.primary');
  await p.waitForSelector('text=Done', { timeout: 15000 });
  console.log('move result:', (await p.textContent('.scrim .dialog')).replace(/\s+/g, ' '));
  await p.click('.scrim .btn.primary');
  console.log('Trip 2026:', await ls(p, ['Pictures', 'Trip 2026']));
  console.log('card 100NIKON has 4512?', (await ls(p, ['card', 'DCIM', '100NIKON'])).includes('4512'));
  // viewer delete with partner checkbox
  const i10 = await p.evaluate(() => [...document.querySelectorAll('.tile')].findIndex(t => t.title === 'DSC_4510.NEF'));
  await p.locator('.tile').nth(i10).click();
  await p.waitForTimeout(600);
  await p.keyboard.press('Delete');
  await p.waitForSelector('.checkline');
  console.log('delete dialog:', (await p.textContent('.scrim .dialog')).replace(/\s+/g, ' '));
  await p.screenshot({ path: D + '/16-delete.png' });
  await p.click('.dialog .btn.danger');
  await p.waitForTimeout(800);
  console.log('viewer now on:', await p.textContent('#viewer .vtitle'));
  await p.keyboard.press('Escape');
  const card = await ls(p, ['card', 'DCIM', '100NIKON']);
  console.log('4510 still on card?', card.includes('4510'), '| files left:', card.split(', ').length);
  console.log('gallery:', await p.textContent('#subtitle'));
  console.log('ERRORS:', errs.length ? errs.join('\n') : 'none');
  await b.close();
})();
