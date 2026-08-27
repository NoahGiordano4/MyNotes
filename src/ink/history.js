/**
 * Historique undo/redo (par page, opérations simples).
 * Opérations :
 *   { type: 'add',   stroke }
 *   { type: 'erase', items: [{ stroke, index }] }
 */

export class History {
  constructor(limit = 120) {
    this.undoStack = [];
    this.redoStack = [];
    this.limit = limit;
    this.onChange = null;
  }

  push(op) {
    this.undoStack.push(op);
    if (this.undoStack.length > this.limit) this.undoStack.shift();
    this.redoStack.length = 0;
    this._changed();
  }

  undo() {
    const op = this.undoStack.pop();
    if (op) {
      this.redoStack.push(op);
      this._changed();
    }
    return op || null;
  }

  redo() {
    const op = this.redoStack.pop();
    if (op) {
      this.undoStack.push(op);
      this._changed();
    }
    return op || null;
  }

  clear() {
    this.undoStack.length = 0;
    this.redoStack.length = 0;
    this._changed();
  }

  get canUndo() {
    return this.undoStack.length > 0;
  }

  get canRedo() {
    return this.redoStack.length > 0;
  }

  _changed() {
    this.onChange?.(this);
  }
}
