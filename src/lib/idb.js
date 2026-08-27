/**
 * Mini-wrapper IndexedDB basé sur des promesses.
 * Base locale « mynotes » :
 *  - store « notes » : métadonnées des notes (id, titre, dates, vignette…)
 *  - store « pages » : contenu des pages, clé `${noteId}:${index}`
 *    (sauvegarde page par page => écritures rapides même sur de grosses notes)
 */

const DB_NAME = 'mynotes';
const DB_VERSION = 1;

let dbPromise = null;

export function openDB() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains('notes')) {
        db.createObjectStore('notes', { keyPath: 'id' });
      }
      if (!db.objectStoreNames.contains('pages')) {
        db.createObjectStore('pages', { keyPath: 'key' });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

function wrap(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

/** Exécute `fn(store)` dans une transaction et attend la complétion. */
export async function withStore(name, mode, fn) {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(name, mode);
    const store = tx.objectStore(name);
    let result;
    try {
      result = fn(store);
    } catch (err) {
      reject(err);
      return;
    }
    tx.oncomplete = () => resolve(result instanceof IDBRequest ? result.result : result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

export const idbGet = (store, key) => withStore(store, 'readonly', (s) => s.get(key)).then((r) => (r instanceof IDBRequest ? r.result : r));
export const idbGetAll = (store) => withStore(store, 'readonly', (s) => s.getAll()).then((r) => (r instanceof IDBRequest ? r.result : r));
export const idbPut = (store, value) => withStore(store, 'readwrite', (s) => s.put(value));
export const idbDelete = (store, key) => withStore(store, 'readwrite', (s) => s.delete(key));
