// Test fixture, injected before the app loads: builds a fake SD card (JPEGs, synthetic Nikon NEFs with an embedded
// preview and EXIF, a stray .DAT) and a "Pictures" location in OPFS, and points the folder picker at them.
// Runs inside the page before the app: builds a fake card + Pictures folder in OPFS and wires the folder picker to them.
window.__testPick = async (id) => {
  const root = await navigator.storage.getDirectory();
  if (id === 'card') return (await (await root.getDirectoryHandle('card')).getDirectoryHandle('DCIM'));
  return root.getDirectoryHandle('Pictures');
};
window.__buildFixture = async () => {
  const root = await navigator.storage.getDirectory();
  for await (const [name] of root.entries()) await root.removeEntry(name, { recursive: true });
  const card = await (await root.getDirectoryHandle('card', { create: true })).getDirectoryHandle('DCIM', { create: true });
  const f1 = await card.getDirectoryHandle('100NIKON', { create: true });
  const f2 = await card.getDirectoryHandle('101NIKON', { create: true });
  const pics = await root.getDirectoryHandle('Pictures', { create: true });
  const trip = await pics.getDirectoryHandle('Trip 2026', { create: true });
  const jpeg = async (i, w, h) => {
    const c = new OffscreenCanvas(w, h), g = c.getContext('2d');
    const grd = g.createLinearGradient(0, 0, w, h);
    grd.addColorStop(0, `hsl(${(i * 47) % 360},70%,55%)`); grd.addColorStop(1, `hsl(${(i * 47 + 120) % 360},70%,35%)`);
    g.fillStyle = grd; g.fillRect(0, 0, w, h);
    g.fillStyle = 'rgba(255,255,255,.85)'; g.font = `bold ${Math.round(h / 4)}px sans-serif`; g.fillText(String(i), w * 0.08, h * 0.6);
    return new Uint8Array(await (await c.convertToBlob({ type: 'image/jpeg', quality: 0.9 })).arrayBuffer());
  };
  const write = async (dir, name, bytes) => { const h = await dir.getFileHandle(name, { create: true }); const w = await h.createWritable(); await w.write(bytes); await w.close(); };
  // synthetic NEF: TIFF with Make/Model/Exif and a SubIFD holding a JPEG preview strip
  const nef = (jpg) => {
    const ascii = s => { const a = new TextEncoder().encode(s + '\0'); return a; };
    const make = ascii('NIKON CORPORATION'), model = ascii('NIKON Z 5'), lens = ascii('NIKKOR Z 24-70mm f/4 S');
    const buf = new ArrayBuffer(1024 + jpg.length), v = new DataView(buf), u = new Uint8Array(buf);
    v.setUint8(0, 0x49); v.setUint8(1, 0x49); v.setUint16(2, 42, true); v.setUint32(4, 8, true);
    let o = 8;
    const ent = (p, tag, type, cnt, val) => { v.setUint16(p, tag, true); v.setUint16(p + 2, type, true); v.setUint32(p + 4, cnt, true); if (type === 3 && cnt === 1) v.setUint16(p + 8, val, true); else v.setUint32(p + 8, val, true); };
    // data area
    const MAKE = 400, MODEL = 440, LENS = 480, EXIF = 200, SUB = 120, RATS = 560, JPG = 1024;
    u.set(make, MAKE); u.set(model, MODEL); u.set(lens, LENS); u.set(jpg, JPG);
    v.setUint32(RATS, 1, true); v.setUint32(RATS + 4, 250, true);      // 1/250
    v.setUint32(RATS + 8, 40, true); v.setUint32(RATS + 12, 10, true);  // f/4.0
    v.setUint32(RATS + 16, 500, true); v.setUint32(RATS + 20, 10, true); // 50mm
    // IFD0: Make, Model, Orientation, SubIFDs, ExifIFD
    v.setUint16(o, 5, true);
    ent(o + 2, 0x010f, 2, make.length, MAKE); ent(o + 14, 0x0110, 2, model.length, MODEL); ent(o + 26, 0x0112, 3, 1, 1);
    ent(o + 38, 0x014a, 4, 1, SUB); ent(o + 50, 0x8769, 4, 1, EXIF); v.setUint32(o + 62, 0, true);
    // SubIFD: JPEG strip
    v.setUint16(SUB, 3, true); ent(SUB + 2, 0x0103, 3, 1, 7); ent(SUB + 14, 0x0111, 4, 1, JPG); ent(SUB + 26, 0x0117, 4, 1, jpg.length); v.setUint32(SUB + 38, 0, true);
    // Exif IFD
    v.setUint16(EXIF, 5, true); ent(EXIF + 2, 0x829a, 5, 1, RATS); ent(EXIF + 14, 0x829d, 5, 1, RATS + 8); ent(EXIF + 26, 0x8827, 3, 1, 400);
    ent(EXIF + 38, 0x920a, 5, 1, RATS + 16); ent(EXIF + 50, 0xa434, 2, lens.length, LENS); v.setUint32(EXIF + 62, 0, true);
    return u;
  };
  let n = 0;
  for (let i = 1; i <= 14; i++) {
    const big = await jpeg(i, 1800, 1200);
    await write(f1, `DSC_${String(4500 + i).padStart(4, '0')}.JPG`, big);
    if (i % 2 === 0) await write(f1, `DSC_${String(4500 + i).padStart(4, '0')}.NEF`, nef(await jpeg(i + 100, 1800, 1200)));
    n++;
  }
  for (let i = 1; i <= 6; i++) await write(f2, `DSC_${String(4600 + i)}.JPG`, await jpeg(i + 30, 1200, 1800));
  await write(f1, 'NC_FLLST.DAT', new Uint8Array(2000));
  await write(trip, 'OLD_0001.JPG', await jpeg(77, 1200, 800));
  await write(trip, 'OLD_0002.JPG', await jpeg(78, 1200, 800));
  return 'ok';
};
// Headless Chromium crashes on queryPermission for OPFS handles restored from IndexedDB; real folders on Windows don't.
FileSystemHandle.prototype.queryPermission = async () => 'granted';
FileSystemHandle.prototype.requestPermission = async () => 'granted';
window.__testHandles = new Map();
