/** Fenêtre « Paramètres » : mode de saisie (stylet / doigt) + sauvegarde. */

import { settings } from '../settings.js';
import { customModal } from './modals.js';
import { icon } from '../lib/icons.js';
import { importNote } from '../storage/notes.js';
import { toast } from '../lib/util.js';

export function openSettingsModal({ onNoteImported } = {}) {
  const s = settings.get();
  const { el, close } = customModal(`
    <div class="modal-head">
      <h3>Paramètres</h3>
      <button class="icon-btn" data-close title="Fermer">${icon('close')}</button>
    </div>

    <section class="settings-section">
      <h4>Saisie</h4>
      <div class="choice-group" id="inputModeGroup">
        <label class="choice">
          <input type="radio" name="inputMode" value="stylus" ${s.inputMode === 'stylus' ? 'checked' : ''} />
          <span class="choice-body">
            <span class="choice-title">${icon('pen')} Stylet uniquement</span>
            <span class="choice-desc">Écriture au stylet. Le doigt déplace la page (1 ou 2 doigts), la paume est ignorée.</span>
          </span>
        </label>
        <label class="choice">
          <input type="radio" name="inputMode" value="finger" ${s.inputMode === 'finger' ? 'checked' : ''} />
          <span class="choice-body">
            <span class="choice-title">${icon('pencil')} Stylet + doigt</span>
            <span class="choice-desc">Écrivez avec le stylet ou un doigt. Deux doigts = zoom et déplacement (le trait en cours est annulé).</span>
          </span>
        </label>
      </div>
    </section>

    <section class="settings-section">
      <h4>Sauvegarde</h4>
      <p class="settings-note">
        Les notes sont enregistrées automatiquement sur cet appareil, page par page,
        dans un format JSON conçu pour le cloud (une note = un fichier autonome).
        Exportez vos notes depuis le menu pour les conserver ailleurs.
      </p>
      <div class="backup-row disabled">
        <span class="backup-name">Google Drive</span>
        <span class="backup-status">Bientôt disponible</span>
      </div>
      <div class="backup-row disabled">
        <span class="backup-name">OneDrive</span>
        <span class="backup-status">Bientôt disponible</span>
      </div>
      <div class="backup-row">
        <span class="backup-name">Importer un fichier MyNotes (.json)</span>
        <button class="btn-ghost" id="btnImport">${icon('upload', 16)} Importer</button>
        <input type="file" id="importFile" accept=".json,application/json" hidden />
      </div>
    </section>

    <div class="modal-actions">
      <button class="btn-primary" data-close>Terminé</button>
    </div>
  `);

  el.querySelector('#inputModeGroup').addEventListener('change', (e) => {
    settings.set({ inputMode: e.target.value });
    toast(
      e.target.value === 'stylus'
        ? 'Stylet uniquement : le doigt déplace la page'
        : 'Stylet + doigt activé'
    );
  });

  const fileInput = el.querySelector('#importFile');
  el.querySelector('#btnImport').addEventListener('click', () => fileInput.click());
  fileInput.addEventListener('change', async () => {
    const file = fileInput.files?.[0];
    if (!file) return;
    try {
      const doc = JSON.parse(await file.text());
      const note = await importNote(doc);
      toast(`« ${note.title} » importée`);
      close();
      onNoteImported?.();
    } catch (err) {
      toast('Import impossible : fichier invalide');
      console.error(err);
    }
  });
}
