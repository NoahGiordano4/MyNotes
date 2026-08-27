/** Écran d'accueil : liste des notes, création, renommage, export, suppression. */

import { listNotes, deleteNote, saveNoteMeta, exportNote } from '../storage/notes.js';
import { formatDate, slugify, downloadJSON, toast } from '../lib/util.js';
import { icon } from '../lib/icons.js';
import { confirmModal, promptModal } from './modals.js';
import { openSettingsModal } from './settings-modal.js';

export function renderMenu(root, { onOpenNote, onNewNote }) {
  root.innerHTML = `
    <div class="menu">
      <header class="menu-header">
        <div class="brand">
          <img class="brand-logo" src="/icon.svg" alt="" />
          <h1>MyNotes</h1>
        </div>
        <div class="menu-actions">
          <button class="icon-btn" id="btnSettings" title="Paramètres">${icon('settings')}</button>
          <button class="btn-primary" id="btnNew" title="Créer une note">${icon('plus', 18)}<span class="btn-label">Nouvelle note</span></button>
        </div>
      </header>
      <main class="notes-wrap">
        <div class="notes-grid" id="notesGrid">
          <div class="loading">Chargement…</div>
        </div>
      </main>
    </div>`;

  const grid = root.querySelector('#notesGrid');

  async function refresh() {
    const notes = await listNotes();
    if (!notes.length) {
      grid.innerHTML = `
        <div class="empty-state">
          <div class="empty-art">${icon('pagePlus', 44)}</div>
          <h2>Aucune note</h2>
          <p>Créez votre première note et écrivez au stylet ou au doigt.</p>
          <button class="btn-primary" id="btnNewEmpty">${icon('plus', 18)} Nouvelle note</button>
        </div>`;
      grid.querySelector('#btnNewEmpty').addEventListener('click', onNewNote);
      return;
    }
    grid.innerHTML = notes.map(cardHTML).join('');
    grid.querySelectorAll('.note-card').forEach((card) => {
      const id = card.dataset.id;
      card.addEventListener('click', (e) => {
        if (e.target.closest('button')) return;
        onOpenNote(id);
      });
      card.querySelector('[data-act="rename"]')?.addEventListener('click', () => rename(notes.find((n) => n.id === id)));
      card.querySelector('[data-act="export"]')?.addEventListener('click', () => exportOne(id));
      card.querySelector('[data-act="delete"]')?.addEventListener('click', () => remove(notes.find((n) => n.id === id)));
    });
  }

  function cardHTML(note) {
    return `
      <article class="note-card" data-id="${note.id}" title="Ouvrir la note">
        <div class="card-cover">
          ${note.thumb ? `<img src="${note.thumb}" alt="" loading="lazy" />` : '<div class="cover-empty"></div>'}
          <div class="card-actions">
            <button data-act="rename" title="Renommer">${icon('pencil', 16)}</button>
            <button data-act="export" title="Exporter (.json)">${icon('download', 16)}</button>
            <button data-act="delete" title="Supprimer" class="danger">${icon('trash', 16)}</button>
          </div>
        </div>
        <div class="card-info">
          <h3 class="card-title">${escapeHTML(note.title)}</h3>
          <p class="card-meta">
            ${formatDate(note.updatedAt)}${note.pageCount > 1 ? ` · ${note.pageCount} pages` : ' · 1 page'}
          </p>
        </div>
      </article>`;
  }

  async function rename(note) {
    if (!note) return;
    const title = await promptModal({ title: 'Renommer la note', label: 'Titre', value: note.title });
    if (title && title !== note.title) {
      await saveNoteMeta({ ...note, title });
      toast('Note renommée');
      refresh();
    }
  }

  async function exportOne(id) {
    try {
      const note = await exportNote(id);
      downloadJSON(`${slugify(note.title)}.mynotes.json`, note);
      toast('Note exportée (.json)');
    } catch (err) {
      console.error(err);
      toast("Export impossible");
    }
  }

  async function remove(note) {
    if (!note) return;
    const ok = await confirmModal({
      title: 'Supprimer la note ?',
      message: `« ${escapeHTML(note.title)} » et ses ${note.pageCount} page(s) seront définitivement supprimées.`,
    });
    if (ok) {
      await deleteNote(note.id);
      toast('Note supprimée');
      refresh();
    }
  }

  root.querySelector('#btnNew').addEventListener('click', onNewNote);
  root.querySelector('#btnSettings').addEventListener('click', () => {
    openSettingsModal({ onNoteImported: refresh });
  });

  refresh();

  return { destroy() {} };
}

function escapeHTML(str) {
  return String(str ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
