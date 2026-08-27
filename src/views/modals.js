/** Petites fenêtres modales (confirmation, saisie) sans dépendance. */

import { icon } from '../lib/icons.js';

function overlay() {
  const el = document.createElement('div');
  el.className = 'modal-overlay';
  return el;
}

/** Booléen : true = confirmé. */
export function confirmModal({ title, message, confirmText = 'Supprimer', danger = true }) {
  return new Promise((resolve) => {
    const ov = overlay();
    ov.innerHTML = `
      <div class="modal" role="alertdialog" aria-modal="true">
        <h3>${title}</h3>
        ${message ? `<p>${message}</p>` : ''}
        <div class="modal-actions">
          <button class="btn-ghost" data-act="cancel">Annuler</button>
          <button class="btn-${danger ? 'danger' : 'primary'}" data-act="ok">${confirmText}</button>
        </div>
      </div>`;
    const close = (val) => {
      ov.remove();
      resolve(val);
    };
    ov.addEventListener('click', (e) => {
      if (e.target === ov) close(false);
      const act = e.target.closest('[data-act]')?.dataset.act;
      if (act === 'ok') close(true);
      if (act === 'cancel') close(false);
    });
    document.body.appendChild(ov);
    ov.querySelector('[data-act="ok"]').focus();
  });
}

/** Chaîne saisie, ou null si annulé. */
export function promptModal({ title, label, value = '', confirmText = 'Enregistrer' }) {
  return new Promise((resolve) => {
    const ov = overlay();
    ov.innerHTML = `
      <div class="modal" role="dialog" aria-modal="true">
        <h3>${title}</h3>
        <label class="field-label">${label}</label>
        <input class="field-input" type="text" maxlength="80" value="${value.replace(/"/g, '&quot;')}" />
        <div class="modal-actions">
          <button class="btn-ghost" data-act="cancel">Annuler</button>
          <button class="btn-primary" data-act="ok">${confirmText}</button>
        </div>
      </div>`;
    const input = ov.querySelector('input');
    const close = (val) => {
      ov.remove();
      resolve(val);
    };
    const ok = () => close(input.value.trim() || null);
    ov.addEventListener('click', (e) => {
      if (e.target === ov) close(null);
      const act = e.target.closest('[data-act]')?.dataset.act;
      if (act === 'ok') ok();
      if (act === 'cancel') close(null);
    });
    ov.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') ok();
      if (e.key === 'Escape') close(null);
    });
    document.body.appendChild(ov);
    input.focus();
    input.select();
  });
}

/** Ouvre une fenêtre modale générique (contenu HTML fourni). */
export function customModal(html) {
  const ov = overlay();
  ov.innerHTML = `<div class="modal modal-lg">${html}</div>`;
  document.body.appendChild(ov);
  const close = () => ov.remove();
  ov.addEventListener('click', (e) => {
    if (e.target === ov) close();
    if (e.target.closest('[data-close]')) close();
  });
  return { el: ov, close };
}

export const modalIcon = (name) => `<span class="modal-ic">${icon(name)}</span>`;
