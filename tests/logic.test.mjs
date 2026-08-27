/**
 * Tests fonctionnels MyNotes (sans navigateur) :
 *  - moteur d'encrage : machine à états des pointeurs (souris, stylet, doigt,
 *    pincement, rejet de paume), gomme, pression ;
 *  - stockage : cycle complet IndexedDB + format d'export/import.
 *
 * Exécution : npm test   (jsdom + fake-indexeddb)
 */
import { JSDOM } from 'jsdom';
import 'fake-indexeddb/auto';

const dom = new JSDOM('<!doctype html><html><body><div id="host"><canvas id="c"></canvas></div></body></html>', {
  pretendToBeVisual: true,
  url: 'http://localhost/',
});
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.localStorage = dom.window.localStorage;
globalThis.requestAnimationFrame = (cb) => dom.window.requestAnimationFrame(cb);
globalThis.cancelAnimationFrame = (id) => dom.window.cancelAnimationFrame(id);
globalThis.Path2D = class {
  constructor() { this.ops = []; }
  moveTo(...a) { this.ops.push(['M', a]); }
  lineTo(...a) { this.ops.push(['L', a]); }
  quadraticCurveTo(...a) { this.ops.push(['Q', a]); }
  closePath() { this.ops.push(['Z']); }
};

const sleep = (ms = 30) => new Promise((r) => setTimeout(r, ms));

let passed = 0;
let failed = 0;
function check(name, cond, extra = '') {
  if (cond) { passed++; console.log(`  ✔ ${name}`); }
  else { failed++; console.log(`  ✘ ${name} ${extra}`); }
}

/* ------------------------------------------------------------------ */
/* Modules réels de l'application                                      */
/* ------------------------------------------------------------------ */
const { InkEngine } = await import('../src/ink/engine.js');
const notes = await import('../src/storage/notes.js');
const { History } = await import('../src/ink/history.js');

/* ------------------------------------------------------------------ */
/* Simulateur de canvas (jsdom n'a pas de rendu 2D)                    */
/* ------------------------------------------------------------------ */
function mockCtx() {
  const noop = () => {};
  return new Proxy({}, {
    get: (t, p) => (p in t ? t[p] : noop),
    set: (t, p, v) => ((t[p] = v), true),
  });
}
const origCreate = document.createElement.bind(document);
document.createElement = (tag) => {
  const el = origCreate(tag);
  if (tag.toLowerCase() === 'canvas') {
    el.getContext = () => mockCtx();
    el.toDataURL = () => 'data:image/jpeg;base64,x';
  }
  return el;
};

const canvas = document.getElementById('c');
canvas.getContext = () => mockCtx();
canvas.getBoundingClientRect = () => ({ left: 0, top: 0, width: 900, height: 650, right: 900, bottom: 650 });
Object.defineProperty(canvas.parentElement, 'clientWidth', { value: 900 });
Object.defineProperty(canvas.parentElement, 'clientHeight', { value: 650 });
canvas.setPointerCapture = () => {};
canvas.releasePointerCapture = () => {};

let uid = 0;
function ptr(etype, type, x, y, extra = {}) {
  const ev = new dom.window.MouseEvent(etype, { clientX: x, clientY: y, bubbles: true, cancelable: true });
  Object.defineProperties(ev, {
    pointerId: { value: extra.id ?? 1 },
    pointerType: { value: type },
    pressure: { value: extra.pressure ?? 0.5 },
    button: { value: extra.button ?? 0 },
  });
  ev.getCoalescedEvents = () => [ev];
  return ev;
}
const down = (t, x, y, e = {}) => canvas.dispatchEvent(ptr('pointerdown', t, x, y, e));
const move = (t, x, y, e = {}) => canvas.dispatchEvent(ptr('pointermove', t, x, y, e));
const up = (t, x, y, e = {}) => canvas.dispatchEvent(ptr('pointerup', t, x, y, e));

function makeEngine(cb = {}) {
  const eng = new InkEngine(canvas, {
    getInputMode: () => cb.inputMode ?? 'stylus',
    onCommitStroke: (s) => {
      eng.strokes.push(s);
      eng.appendStroke(s);
      cb.onCommitStroke?.(s);
    },
    onErase: (items) => cb.onErase?.(items),
    onViewChange: () => {},
  });
  eng.setStrokes([]);
  eng.fitPage();
  return eng;
}

/* ================================================================== */
console.log('\n— Moteur : écriture souris');
{
  const committed = [];
  const eng = makeEngine({ onCommitStroke: (s) => committed.push(s) });
  const s0 = { ...eng.view };
  down('mouse', 200, 200);
  for (let i = 1; i <= 20; i++) move('mouse', 200 + i * 6, 200 + i * 2);
  up('mouse', 320, 240);
  await sleep(60);
  check('un trait est commis', committed.length === 1);
  check('points multiples (échantillonnage)', committed[0]?.points.length >= 30, `(got ${(committed[0]?.points.length ?? 0) / 3})`);
  check('encre noire', committed[0]?.color === '#000000');
  check("la vue n'a pas bougé en dessinant", eng.view.x === s0.x && eng.view.y === s0.y);
  eng.destroy();
}

console.log('\n— Moteur : mode stylet uniquement (le doigt ne trace pas)');
{
  const committed = [];
  const eng = makeEngine({ inputMode: 'stylus', onCommitStroke: (s) => committed.push(s) });
  down('touch', 300, 300);
  for (let i = 1; i <= 10; i++) move('touch', 300 + i * 4, 300);
  up('touch', 340, 300);
  await sleep(40);
  check("rien n'a été tracé au doigt", committed.length === 0);
  // quand la page entière tient à l'écran, le pan est neutralisé (recentrage) ;
  // on zoome d'abord pour rendre le déplacement possible.
  eng.view.scale = 2;
  eng.view.x = -300;
  eng.view.y = -300;
  down('touch', 300, 300);
  for (let i = 1; i <= 10; i++) move('touch', 300 + i * 4, 300);
  up('touch', 340, 300);
  await sleep(40);
  check('1 doigt = déplacement (pan) une fois zoomé', eng.view.x === -260, `(dx=${eng.view.x + 300})`);
  eng.destroy();
}

console.log('\n— Moteur : 2 doigts = pincement zoom + déplacement');
{
  const eng = makeEngine({ inputMode: 'stylus' });
  const s0 = { ...eng.view };
  down('touch', 300, 300, { id: 11 });
  down('touch', 500, 300, { id: 12 });
  await sleep(20);
  for (let i = 1; i <= 10; i++) {
    move('touch', 300 - i * 5, 300 - i * 2, { id: 11 });
    move('touch', 500 + i * 5, 300 + i * 2, { id: 12 });
  }
  await sleep(20);
  up('touch', 250, 280, { id: 11 });
  up('touch', 550, 320, { id: 12 });
  await sleep(40);
  check('le zoom a augmenté', eng.view.scale > s0.scale, `(${s0.scale.toFixed(2)} → ${eng.view.scale.toFixed(2)})`);
  eng.destroy();
}

console.log('\n— Moteur : mode stylet+doigt, 2e doigt annule le tracé en cours');
{
  const committed = [];
  const eng = makeEngine({ inputMode: 'finger', onCommitStroke: (s) => committed.push(s) });
  down('touch', 300, 300, { id: 21 });
  for (let i = 1; i <= 8; i++) move('touch', 300 + i * 5, 310, { id: 21 });
  await sleep(20);
  check('tracé doigt en cours', eng.live !== null);
  down('touch', 320, 380, { id: 22 });
  check('le tracé est annulé', eng.live === null);
  check('mode gestuel', eng.mode === 'pinch');
  up('touch', 300, 390, { id: 21 });
  up('touch', 320, 380, { id: 22 });
  await sleep(40);
  check('aucun trait commis', committed.length === 0);
  eng.destroy();
}

console.log('\n— Moteur : le stylet a priorité (rejet de paume)');
{
  const committed = [];
  const eng = makeEngine({ inputMode: 'stylus', onCommitStroke: (s) => committed.push(s) });
  down('pen', 300, 300, { id: 31, pressure: 0.6 });
  move('pen', 310, 300, { id: 31, pressure: 0.7 });
  down('touch', 250, 450, { id: 32 }); // la paume se pose
  move('touch', 260, 460, { id: 32 }); // … et bouge
  move('pen', 330, 305, { id: 31, pressure: 0.8 });
  up('touch', 250, 450, { id: 32 });
  up('pen', 340, 305, { id: 31, pressure: 0.4 });
  await sleep(40);
  check('un seul trait (le stylet)', committed.length === 1);
  // le stylet interrompt une gestuelle tactile en cours
  down('touch', 200, 200, { id: 41 });
  down('touch', 400, 200, { id: 42 });
  check('geste pincement actif', eng.mode === 'pinch');
  down('pen', 300, 300, { id: 43, pressure: 0.5 });
  check('le stylet prend la main', eng.mode === 'draw');
  up('pen', 320, 310, { id: 43 });
  up('touch', 200, 200, { id: 41 });
  up('touch', 400, 200, { id: 42 });
  await sleep(40);
  eng.destroy();
}

console.log('\n— Moteur : pression du stylet enregistrée');
{
  const committed = [];
  const eng = makeEngine({ inputMode: 'stylus', onCommitStroke: (s) => committed.push(s) });
  down('pen', 300, 300, { id: 51, pressure: 0.9 });
  move('pen', 310, 300, { id: 51, pressure: 0.9 });
  move('pen', 340, 302, { id: 51, pressure: 0.2 });
  up('pen', 360, 305, { id: 51, pressure: 0.2 });
  await sleep(40);
  const pts = committed[0]?.points;
  const pressures = pts ? [pts[2], pts[5], pts[8]] : [];
  check('pressions variables conservées', pressures.some((p) => p > 0.6) && pressures.some((p) => p < 0.5), `(${pressures.map((p) => p?.toFixed(2))})`);
  eng.destroy();
}

console.log('\n— Moteur : gomme par trait entier');
{
  const committed = [];
  let erased = null;
  const eng = makeEngine({ onCommitStroke: (s) => committed.push(s), onErase: (items) => (erased = items) });
  down('mouse', 300, 300);
  for (let i = 1; i <= 20; i++) move('mouse', 300 + i * 4, 300);
  up('mouse', 380, 300);
  await sleep(40);
  check('trait présent', eng.strokes.length === 1);
  eng.setTool('eraser');
  down('mouse', 310, 302);
  move('mouse', 340, 300);
  up('mouse', 360, 300);
  await sleep(40);
  check('trait retiré du modèle', eng.strokes.length === 0);
  check('événement onErase avec index', Array.isArray(erased) && erased.length === 1 && erased[0].index === 0);
  eng.destroy();
}

console.log('\n— Historique : sémantique de pile undo/redo');
{
  const h = new History();
  const stroke = { id: 'a' };
  check('vide au départ', !h.canUndo && !h.canRedo);
  h.push({ type: 'add', stroke });
  check('canUndo après push', h.canUndo && !h.canRedo);
  const op = h.undo();
  check('undo renvoie l’opération', op?.type === 'add' && op.stroke === stroke && h.canRedo);
  const op2 = h.redo();
  check('redo renvoie l’opération', op2?.type === 'add' && h.canUndo && !h.canRedo);
  h.push({ type: 'erase', items: [{ stroke, index: 0 }] });
  check('push vide la pile redo', !h.canRedo);
  h.clear();
  check('clear remet à zéro', !h.canUndo && !h.canRedo);
}

/* ================================================================== */
console.log('\n— Stockage : cycle complet (IndexedDB)');
{
  const note = await notes.createNote('Mes cours');
  check('note créée', !!note.id && note.pageCount === 1);
  const strokes = [{ id: 's1', color: '#000000', size: 3.4, points: Float32Array.from([10, 20, 0.5, 30, 40, 0.6]) }];
  await notes.savePage(note.id, 0, strokes);
  const loaded = await notes.loadPage(note.id, 0);
  check('page relue identiquement', loaded.length === 1 && loaded[0].size === 3.4 && Math.abs(loaded[0].points[3] - 30) < 1e-6);
  const list = await notes.listNotes();
  check('note listée', list.some((n) => n.id === note.id));

  const doc = await notes.exportNote(note.id);
  check('export au format mynotes.doc v1', doc.format === 'mynotes.doc' && doc.version === 1 && doc.pages.length === 1);
  check('points exportés en tableau arrondi', Array.isArray(doc.pages[0].strokes[0].p));

  const imported = await notes.importNote(JSON.parse(JSON.stringify(doc)));
  const reloaded = await notes.loadPage(imported.id, 0);
  check('import puis relecture OK', reloaded.length === 1 && reloaded[0].points.length === 6);

  await notes.deleteNote(note.id);
  await notes.deleteNote(imported.id);
  const after = await notes.listNotes();
  check('suppression effective', !after.some((n) => n.id === note.id || n.id === imported.id));
}

console.log(`\nRésultat : ${passed} OK, ${failed} échec(s)\n`);
process.exit(failed ? 1 : 0);
