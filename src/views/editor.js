/**
 * Éditeur de note : page A4 blanche, encre noire fluide, zoom/déplacement,
 * pages multiples, undo/redo, sauvegarde automatique page par page.
 */

import { InkEngine, PEN_SIZES, renderThumb } from '../ink/engine.js';
import { History } from '../ink/history.js';
import { getNote, loadPage, savePage, saveNoteMeta } from '../storage/notes.js';
import { settings } from '../settings.js';
import { debounce } from '../lib/util.js';
import { icon } from '../lib/icons.js';

const sizeDot = (v) => Math.max(4, Math.min(10, Math.round(v * 1.6)));

export function openEditor(root, noteId, { onClose }) {
  root.innerHTML = `
    <div class="editor">
      <header class="topbar">
        <button class="icon-btn" id="btnClose" title="Fermer la note">${icon('close')}</button>
        <input id="titleInput" class="title-input" type="text" maxlength="80" spellcheck="false" placeholder="Titre de la note" />
        <span class="save-state" id="saveState"></span>
        <span class="flex-spacer"></span>
        <button class="icon-btn" id="btnFit" title="Ajuster la page à l'écran (Ctrl+0)">${icon('fit')}</button>
        <span class="sep"></span>
        <button class="icon-btn" id="btnUndo" title="Annuler (Ctrl+Z)" disabled>${icon('undo')}</button>
        <button class="icon-btn" id="btnRedo" title="Rétablir (Ctrl+Maj+Z)" disabled>${icon('redo')}</button>
        <span class="sep"></span>
        <div class="size-group" id="sizeGroup">
          ${PEN_SIZES.map(
            (p, i) => `
            <button class="size-btn${i === 1 ? ' active' : ''}" data-size="${p.value}" title="Trait ${p.id}">
              <i class="dot" style="width:${sizeDot(p.value)}px;height:${sizeDot(p.value)}px"></i>
            </button>`
          ).join('')}
        </div>
        <button class="icon-btn" id="btnEraser" title="Gomme (E) — efface un trait entier">${icon('eraser')}</button>
        <span class="sep"></span>
        <button class="icon-btn" id="btnPrevPage" title="Page précédente">${icon('chevronLeft')}</button>
        <span class="page-label" id="pageLabel">– / –</span>
        <button class="icon-btn" id="btnNextPage" title="Page suivante">${icon('chevronRight')}</button>
        <button class="icon-btn" id="btnAddPage" title="Ajouter une page">${icon('pagePlus')}</button>
      </header>
      <div class="canvas-host" id="canvasHost">
        <canvas id="inkCanvas"></canvas>
        <button class="zoom-chip" id="zoomChip" title="Ajuster la page à l'écran">100 %</button>
      </div>
    </div>`;

  const $ = (sel) => root.querySelector(sel);
  const canvas = $('#inkCanvas');
  const titleInput = $('#titleInput');
  const saveStateEl = $('#saveState');
  const zoomChip = $('#zoomChip');
  const pageLabel = $('#pageLabel');
  const btnUndo = $('#btnUndo');
  const btnRedo = $('#btnRedo');

  let note = null;
  let pageIndex = 0;
  const pages = new Map(); // index -> strokes[]
  const histories = new Map(); // index -> History
  let closed = false;

  const strokes = () => pages.get(pageIndex) || [];
  const history = () => {
    if (!histories.has(pageIndex)) {
      const h = new History();
      h.onChange = updateHistoryButtons;
      histories.set(pageIndex, h);
    }
    return histories.get(pageIndex);
  };

  /* --------------------------- moteur ------------------------------ */

  const engine = new InkEngine(canvas, {
    getInputMode: () => settings.get().inputMode,
    onCommitStroke(stroke) {
      strokes().push(stroke);
      engine.appendStroke(stroke);
      history().push({ type: 'add', stroke });
      scheduleSave();
    },
    onErase(items) {
      history().push({ type: 'erase', items });
      engine.invalidateCache();
      scheduleSave();
    },
    onViewChange(s) {
      zoomChip.textContent = `${Math.round(s * 100)} %`;
    },
  });

  /* -------------------------- sauvegarde --------------------------- */

  function setSaveState(txt) {
    saveStateEl.innerHTML = txt;
    saveStateEl.classList.toggle('visible', !!txt);
  }

  async function flushSave() {
    if (!note || closed) return;
    setSaveState('Enregistrement…');
    await savePage(note.id, pageIndex, strokes());
    await saveNoteMeta(note);
    note.updatedAt = Date.now();
    setSaveState(`${icon('check', 13)} Enregistré`);
  }

  const scheduleSave = debounce(() => flushSave(), 350);
  const scheduleTitleSave = debounce(() => note && saveNoteMeta(note), 600);

  /* ------------------------- historique ---------------------------- */

  function updateHistoryButtons() {
    const h = histories.get(pageIndex);
    btnUndo.disabled = !h || !h.canUndo;
    btnRedo.disabled = !h || !h.canRedo;
  }

  function applyOp(op, redo) {
    const arr = strokes();
    if (op.type === 'add') {
      if (redo) {
        if (!arr.includes(op.stroke)) arr.push(op.stroke);
      } else {
        const i = arr.indexOf(op.stroke);
        if (i >= 0) arr.splice(i, 1);
      }
    } else {
      if (redo) {
        for (const it of op.items) {
          const i = arr.indexOf(it.stroke);
          if (i >= 0) arr.splice(i, 1);
        }
      } else {
        for (const it of [...op.items].sort((a, b) => a.index - b.index)) {
          arr.splice(Math.min(it.index, arr.length), 0, it.stroke);
        }
      }
    }
    engine.invalidateCache();
    scheduleSave();
  }

  const undo = () => {
    const op = history().undo();
    if (op) applyOp(op, false);
  };
  const redo = () => {
    const op = history().redo();
    if (op) applyOp(op, true);
  };

  /* ---------------------------- pages ------------------------------ */

  async function ensurePage(i) {
    if (!pages.has(i)) pages.set(i, await loadPage(note.id, i));
    return pages.get(i);
  }

  function updatePageLabel() {
    pageLabel.textContent = `${pageIndex + 1} / ${note.pageCount}`;
    $('#btnPrevPage').disabled = pageIndex <= 0;
    $('#btnNextPage').disabled = pageIndex >= note.pageCount - 1;
  }

  async function gotoPage(i) {
    if (!note || i < 0 || i >= note.pageCount || i === pageIndex) return;
    await flushSave();
    pageIndex = i;
    engine.setStrokes(await ensurePage(i));
    engine.fitPage();
    updatePageLabel();
    updateHistoryButtons();
  }

  async function addPage() {
    if (!note) return;
    await flushSave();
    note.pageCount += 1;
    pageIndex = note.pageCount - 1;
    const arr = [];
    pages.set(pageIndex, arr);
    engine.setStrokes(arr); // même tableau que le modèle
    engine.fitPage();
    updatePageLabel();
    updateHistoryButtons();
    await saveNoteMeta(note);
  }

  /* --------------------------- outils ------------------------------ */

  function setTool(tool) {
    engine.setTool(tool);
    $('#btnEraser').classList.toggle('active', tool === 'eraser');
    $('#sizeGroup').classList.toggle('disabled', tool === 'eraser');
  }

  $('#sizeGroup').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-size]');
    if (!btn) return;
    engine.setPenSize(parseFloat(btn.dataset.size));
    root.querySelectorAll('.size-btn').forEach((b) => b.classList.toggle('active', b === btn));
    if (engine.tool !== 'pen') setTool('pen');
  });

  $('#btnEraser').addEventListener('click', () => setTool(engine.tool === 'eraser' ? 'pen' : 'eraser'));
  $('#btnUndo').addEventListener('click', undo);
  $('#btnRedo').addEventListener('click', redo);
  $('#btnFit').addEventListener('click', () => engine.fitPage());
  zoomChip.addEventListener('click', () => engine.fitPage());
  $('#btnPrevPage').addEventListener('click', () => gotoPage(pageIndex - 1));
  $('#btnNextPage').addEventListener('click', () => gotoPage(pageIndex + 1));
  $('#btnAddPage').addEventListener('click', addPage);

  titleInput.addEventListener('input', () => {
    if (!note) return;
    note.title = titleInput.value;
    scheduleTitleSave();
  });

  /* --------------------------- clavier ----------------------------- */

  const onKeyDown = (e) => {
    if (e.target === titleInput) return;
    const mod = e.ctrlKey || e.metaKey;
    const k = e.key.toLowerCase();
    if (e.key === ' ') {
      e.preventDefault();
      engine.setSpaceDown(true);
    } else if (mod && k === 'z') {
      e.preventDefault();
      e.shiftKey ? redo() : undo();
    } else if (mod && k === 'y') {
      e.preventDefault();
      redo();
    } else if (mod && k === '0') {
      e.preventDefault();
      engine.fitPage();
    } else if (!mod && k === 'b') {
      setTool('pen');
    } else if (!mod && k === 'e') {
      setTool('eraser');
    } else if (e.key === 'ArrowLeft') {
      gotoPage(pageIndex - 1);
    } else if (e.key === 'ArrowRight') {
      gotoPage(pageIndex + 1);
    }
  };
  const onKeyUp = (e) => {
    if (e.key === ' ') engine.setSpaceDown(false);
  };
  document.addEventListener('keydown', onKeyDown);
  document.addEventListener('keyup', onKeyUp);

  // Empêche le zoom système à deux doigts (iOS Safari) sur l'éditeur
  const preventGesture = (e) => e.preventDefault();
  document.addEventListener('gesturestart', preventGesture);

  const onVisibility = () => {
    if (document.hidden) flushSave();
  };
  document.addEventListener('visibilitychange', onVisibility);

  /* ---------------------------- fermeture -------------------------- */

  async function close() {
    if (closed) return;
    closed = true;
    try {
      await flushSave();
      // vignette de couverture (page 1)
      if (pageIndex === 0) {
        note.thumb = renderThumb(strokes(), 220);
      } else if (!note.thumb) {
        note.thumb = renderThumb(pages.get(0) || (await loadPage(note.id, 0)), 220);
      }
      await saveNoteMeta(note);
    } catch (err) {
      console.error('Erreur à la fermeture', err);
    }
    destroy();
    onClose();
  }
  $('#btnClose').addEventListener('click', close);

  /* ----------------------------- chargement ------------------------ */

  (async () => {
    note = await getNote(noteId);
    if (!note) {
      onClose();
      return;
    }
    titleInput.value = note.title;
    engine.setStrokes(await ensurePage(0));
    engine.fitPage();
    updatePageLabel();
    updateHistoryButtons();
  })();

  function destroy() {
    engine.destroy();
    document.removeEventListener('keydown', onKeyDown);
    document.removeEventListener('keyup', onKeyUp);
    document.removeEventListener('gesturestart', preventGesture);
    document.removeEventListener('visibilitychange', onVisibility);
    scheduleSave.cancel();
    scheduleTitleSave.cancel();
  }

  return { destroy };
}
