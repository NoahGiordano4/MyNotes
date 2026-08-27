/**
 * Couche de stockage des notes.
 *
 * Local : IndexedDB, une écriture par page (rapide même sur de longues notes).
 * Export : un document JSON versionné par note (format « mynotes.doc » v1).
 *          C'est ce fichier qui sera synchronisé vers Google Drive / OneDrive :
 *          une note = un fichier autonome, facile à sauvegarder/restaurer.
 *
 * Format de fichier :
 * {
 *   "format": "mynotes.doc",
 *   "version": 1,
 *   "id": "uuid",
 *   "title": "…",
 *   "createdAt": 1724…,
 *   "updatedAt": 1724…,
 *   "pages": [
 *     { "index": 0, "strokes": [ { "c": "#000000", "s": 3.4, "p": [x, y, pression, …] } ] }
 *   ]
 * }
 */

import { idbGet, idbGetAll, idbPut, idbDelete, withStore } from '../lib/idb.js';
import { uuid } from '../lib/util.js';

const NOTES = 'notes';
const PAGES = 'pages';

const pageKey = (noteId, index) => `${noteId}:${index}`;

/* ------------------------------------------------------------------ */
/* Métadonnées des notes                                              */
/* ------------------------------------------------------------------ */

export async function listNotes() {
  const notes = await idbGetAll(NOTES);
  return notes.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}

export const getNote = (id) => idbGet(NOTES, id);

export async function createNote(title = 'Note sans titre') {
  const now = Date.now();
  const note = {
    id: uuid(),
    title,
    createdAt: now,
    updatedAt: now,
    pageCount: 1,
    thumb: null, // vignette (dataURL) de la 1re page
  };
  await idbPut(NOTES, note);
  await idbPut(PAGES, { key: pageKey(note.id, 0), noteId: note.id, index: 0, strokes: [] });
  return note;
}

export function saveNoteMeta(note) {
  return idbPut(NOTES, { ...note, updatedAt: Date.now() });
}

export async function deleteNote(id) {
  // Supprime la métadonnée + toutes les pages de la note (plage de clés).
  await withStore(PAGES, 'readwrite', (s) => {
    const range = IDBKeyRange.bound(`${id}:`, `${id}:\uffff`);
    s.delete(range);
  });
  await idbDelete(NOTES, id);
}

/* ------------------------------------------------------------------ */
/* Pages                                                              */
/* ------------------------------------------------------------------ */

export async function loadPage(noteId, index) {
  const rec = await idbGet(PAGES, pageKey(noteId, index));
  return rec ? rec.strokes : [];
}

export function savePage(noteId, index, strokes) {
  return idbPut(PAGES, { key: pageKey(noteId, index), noteId, index, strokes });
}

/* ------------------------------------------------------------------ */
/* Export / Import (prêt pour Google Drive / OneDrive)                 */
/* ------------------------------------------------------------------ */

export async function exportNote(id) {
  const note = await getNote(id);
  if (!note) throw new Error('Note introuvable');
  const pages = [];
  for (let i = 0; i < note.pageCount; i++) {
    const strokes = await loadPage(id, i);
    pages.push({ index: i, strokes: strokes.map(serializeStroke) });
  }
  return {
    format: 'mynotes.doc',
    version: 1,
    app: 'MyNotes',
    id: note.id,
    title: note.title,
    createdAt: note.createdAt,
    updatedAt: Date.now(),
    pages,
  };
}

function serializeStroke(s) {
  // Points arrondis à 2 décimales : fichiers légers, format stable.
  const p = [];
  for (let i = 0; i < s.points.length; i++) p.push(Math.round(s.points[i] * 100) / 100);
  return { c: s.color, s: s.size, p };
}

export async function importNote(doc) {
  if (!doc || doc.format !== 'mynotes.doc' || !Array.isArray(doc.pages)) {
    throw new Error('Fichier MyNotes invalide');
  }
  const now = Date.now();
  const note = {
    id: uuid(),
    title: doc.title || 'Note importée',
    createdAt: doc.createdAt || now,
    updatedAt: now,
    pageCount: doc.pages.length,
    thumb: null,
  };
  await idbPut(NOTES, note);
  for (const page of doc.pages) {
    const strokes = (page.strokes || []).map((s) => ({
      id: uuid(),
      color: s.c || '#000000',
      size: s.s || 3,
      points: Float32Array.from(s.p || []),
    }));
    await idbPut(PAGES, { key: pageKey(note.id, page.index), noteId: note.id, index: page.index, strokes });
  }
  return note;
}
