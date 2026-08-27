/** MyNotes — point d'entrée : navigation menu ⇄ éditeur. */

import './styles.css';
import { renderMenu } from './views/menu.js';
import { openEditor } from './views/editor.js';
import { createNote } from './storage/notes.js';
import { toast } from './lib/util.js';

const app = document.getElementById('app');
let teardown = null;

function swap(view) {
  if (teardown) {
    teardown();
    teardown = null;
  }
  teardown = view.destroy.bind(view);
}

function showMenu() {
  const view = renderMenu(app, {
    onOpenNote: (id) => showEditor(id),
    onNewNote: async () => {
      try {
        const note = await createNote();
        showEditor(note.id);
      } catch (err) {
        console.error(err);
        toast("Impossible de créer la note");
      }
    },
  });
  swap(view);
}

function showEditor(noteId) {
  const view = openEditor(app, noteId, { onClose: showMenu });
  swap(view);
}

showMenu();
