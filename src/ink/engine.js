/**
 * Moteur d'encrage MyNotes.
 *
 * - Rendu Canvas 2D + perfect-freehand (trait fluide, sensible à la pression).
 * - Cache hors-écran : les traits terminés sont dessinés une seule fois sur un
 *   canvas caché ; pendant l'écriture on ne redessine que le trait en cours
 *   => fluide même sur de longues pages.
 * - Événements Pointer (stylet / doigt / souris) avec événements coalescés
 *   (haute fréquence d'échantillonnage) et pression réelle du stylet.
 *   Pression simulée à partir de la vitesse pour le doigt / la souris.
 * - Gestes : pincement à 2 doigts = zoom + déplacement ; 1 doigt = déplacement
 *   quand l'écriture au doigt est désactivée ; molette + Ctrl/molette (pavé
 *   tactile) sur ordinateur.
 * - Rejet de la paume : pendant une écriture au stylet, les pointeurs tactiles
 *   sont ignorés ; en mode « stylet uniquement », le doigt ne trace jamais.
 *
 * Coordonnées « monde » : la page A4 fait 794 × 1123 unités (210 × 297 mm @96dpi).
 * écran = monde × échelle + offset.
 */

import { getStroke } from 'perfect-freehand';
import { uuid } from '../lib/util.js';

export const PAGE_W = 794;
export const PAGE_H = 1123;

export const INK_COLOR = '#000000';
export const PEN_SIZES = [
  { id: 'fine', value: 2.2 },
  { id: 'moyenne', value: 3.4 },
  { id: 'epaisse', value: 5.4 },
];

const BG_COLOR = '#e8ecf1';
const PAGE_SHADOW = 'rgba(15, 23, 42, 0.16)';
const MIN_SCALE = 0.2;
const MAX_SCALE = 8;
const PAN_MARGIN = 72; // px d'écran : la page ne peut pas sortir complètement
const ERASE_RADIUS_PX = 20; // rayon visuel de la gomme (px écran)
const DECIMATE_PX = 1.15; // distance mini (px écran) entre 2 points échantillonnés

const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const dist = (a, b) => Math.hypot(b.x - a.x, b.y - a.y);

/* ------------------------------------------------------------------ */
/* Aides de dessin                                                     */
/* ------------------------------------------------------------------ */

function strokeOutline(points, size, last) {
  return getStroke(points, {
    size,
    thinning: 0.58,
    smoothing: 0.5,
    streamline: 0.36,
    simulatePressure: false, // la pression (réelle ou simulée) est fournie
    last: !!last,
    cap: true,
  });
}

function outlineToPath(outline) {
  if (!outline || outline.length < 3) return null;
  const path = new Path2D();
  path.moveTo(outline[0][0], outline[0][1]);
  for (let i = 1; i < outline.length - 1; i++) {
    const mx = (outline[i][0] + outline[i + 1][0]) / 2;
    const my = (outline[i][1] + outline[i + 1][1]) / 2;
    path.quadraticCurveTo(outline[i][0], outline[i][1], mx, my);
  }
  const lastPt = outline[outline.length - 1];
  path.lineTo(lastPt[0], lastPt[1]);
  path.closePath();
  return path;
}

/** Dessine un trait (points {x,y,p}) sur un contexte en unités monde. */
function paintPoints(ctx, points, size, color, last = false) {
  const path = outlineToPath(strokeOutline(points, size, last));
  if (path) {
    ctx.fillStyle = color;
    ctx.fill(path, 'nonzero');
    return;
  }
  // Point isolé (un simple toucher) : petit disque.
  if (points.length) {
    ctx.beginPath();
    ctx.arc(points[0].x, points[0].y, Math.max(0.4, size * 0.45), 0, Math.PI * 2);
    ctx.fillStyle = color;
    ctx.fill();
  }
}

/** Dessine un trait stocké (Float32Array [x,y,p, ...]) sur un contexte monde. */
export function paintStroke(ctx, stroke) {
  const pts = stroke.points;
  const n = pts.length / 3;
  const arr = new Array(n);
  for (let i = 0; i < n; i++) arr[i] = { x: pts[i * 3], y: pts[i * 3 + 1], p: pts[i * 3 + 2] };
  paintPoints(ctx, arr, stroke.size, stroke.color, true);
}

/** Vignette rapide d'une page (traits simplifiés en polylignes). */
export function renderThumb(strokes, width = 220) {
  const height = Math.round((width * PAGE_H) / PAGE_W);
  const c = document.createElement('canvas');
  c.width = width;
  c.height = height;
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, width, height);
  const k = width / PAGE_W;
  ctx.strokeStyle = '#111827';
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  for (const s of strokes) {
    const pts = s.points;
    if (!pts || pts.length < 3) continue;
    ctx.lineWidth = Math.max(0.7, s.size * k);
    ctx.beginPath();
    ctx.moveTo(pts[0] * k, pts[1] * k);
    if (pts.length === 3) ctx.lineTo(pts[0] * k + 0.01, pts[1] * k);
    for (let i = 3; i < pts.length; i += 3) ctx.lineTo(pts[i] * k, pts[i + 1] * k);
    ctx.stroke();
  }
  return c.toDataURL('image/jpeg', 0.72);
}

/* ------------------------------------------------------------------ */
/* Moteur                                                             */
/* ------------------------------------------------------------------ */

export class InkEngine {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {object} cb
   *   cb.getInputMode()  -> 'stylus' | 'finger'
   *   cb.onCommitStroke(stroke)  — trait validé (pointerup)
   *   cb.onErase(items)          — items : [{stroke, index}] traits effacés
   *   cb.onViewChange(scale)     — échelle changée (chip de zoom)
   */
  constructor(canvas, cb) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d', { alpha: false });
    this.cb = cb || {};

    this.dpr = Math.max(1, Math.min(window.devicePixelRatio || 1, 3));
    this.view = { x: 0, y: 0, scale: 1 };
    this.strokes = [];
    this.ready = false; // devient vrai dès qu'une page est chargée
    this.tool = 'pen'; // 'pen' | 'eraser'
    this.penSize = 3.4;

    // état des pointeurs / gestes
    this.pointers = new Map(); // id -> {x, y, type}
    this.mode = null; // 'draw' | 'erase' | 'pan' | 'pinch'
    this.live = null; // trait en cours
    this.gesture = null;
    this.eraseHits = null;
    this.erasePointerId = null;
    this.erasePos = null; // px écran
    this.spaceDown = false;

    // cache hors-écran des traits terminés
    this.cache = document.createElement('canvas');
    this.cacheCtx = this.cache.getContext('2d');
    this.cacheScale = 0;
    this._cacheDirty = true;

    this._bbox = new WeakMap();
    this._rect = { left: 0, top: 0, width: 0, height: 0 };
    this._raf = 0;
    this._settleTimer = 0;
    this.cssW = 0;
    this.cssH = 0;

    this._bind();
    this._resize();
    if (typeof ResizeObserver !== 'undefined') {
      this.ro = new ResizeObserver(() => this._resize());
      this.ro.observe(canvas.parentElement || canvas);
    }
  }

  destroy() {
    this.ro?.disconnect();
    this._unbind();
    cancelAnimationFrame(this._raf);
    clearTimeout(this._settleTimer);
  }

  /* ---------------------------- modèle ---------------------------- */

  setStrokes(strokes) {
    this.strokes = strokes || [];
    this._bbox = new WeakMap();
    this.ready = true;
    this._invalidateCache();
    this.requestRender();
  }

  setTool(tool) {
    this.tool = tool;
    this._updateCursor();
    this.requestRender();
  }

  setPenSize(size) {
    this.penSize = size;
  }

  /** Redessine tout le cache (après undo/redo/gomme/changement de page). */
  invalidateCache() {
    this._invalidateCache();
    this.requestRender();
  }

  /** Ajout incrémental d'un trait au cache (aucun recalcul global). */
  appendStroke(stroke) {
    if (!this._cacheDirty) {
      const c = this.cacheCtx;
      c.setTransform(this.cacheScale, 0, 0, this.cacheScale, 0, 0);
      paintStroke(c, stroke);
    }
    this.requestRender();
  }

  /* ------------------------------ vue ----------------------------- */

  toWorld(sx, sy) {
    return { x: (sx - this.view.x) / this.view.scale, y: (sy - this.view.y) / this.view.scale };
  }

  fitPage() {
    const vw = this.cssW || window.innerWidth;
    const vh = this.cssH || window.innerHeight;
    const s = clamp(Math.min((vw - 28) / PAGE_W, (vh - 28) / PAGE_H), MIN_SCALE, MAX_SCALE);
    this.view.scale = s;
    this.view.x = (vw - PAGE_W * s) / 2;
    this.view.y = (vh - PAGE_H * s) / 2;
    this._afterZoom();
  }

  zoomAt(sx, sy, factor) {
    const s = clamp(this.view.scale * factor, MIN_SCALE, MAX_SCALE);
    const k = s / this.view.scale;
    this.view.x = sx - (sx - this.view.x) * k;
    this.view.y = sy - (sy - this.view.y) * k;
    this.view.scale = s;
    this._clampPan();
    this._afterZoom();
  }

  _clampPan() {
    const { view } = this;
    const w = PAGE_W * view.scale;
    const h = PAGE_H * view.scale;
    const vw = this.cssW || window.innerWidth;
    const vh = this.cssH || window.innerHeight;
    if (w <= vw) view.x = (vw - w) / 2;
    else view.x = clamp(view.x, PAN_MARGIN - w, vw - PAN_MARGIN);
    if (h <= vh) view.y = (vh - h) / 2;
    else view.y = clamp(view.y, PAN_MARGIN - h, vh - PAN_MARGIN);
  }

  _afterZoom() {
    this.requestRender();
    this.cb.onViewChange?.(this.view.scale);
    clearTimeout(this._settleTimer);
    this._settleTimer = setTimeout(() => {
      const target = this._targetCacheScale();
      if (Math.abs(target - this.cacheScale) / (this.cacheScale || 1) > 0.3) {
        this._invalidateCache();
        this.requestRender();
      }
    }, 350);
  }

  /* ---------------------------- rendu ------------------------------ */

  _targetCacheScale() {
    return clamp(this.view.scale * this.dpr, 1, 3);
  }

  _invalidateCache() {
    this._cacheDirty = true;
  }

  _ensureCache() {
    if (!this._cacheDirty) return;
    const res = this._targetCacheScale();
    const w = Math.max(1, Math.round(PAGE_W * res));
    const h = Math.max(1, Math.round(PAGE_H * res));
    if (this.cache.width !== w || this.cache.height !== h) {
      this.cache.width = w;
      this.cache.height = h;
    } else {
      this.cacheCtx.setTransform(1, 0, 0, 1, 0, 0);
      this.cacheCtx.clearRect(0, 0, w, h);
    }
    const c = this.cacheCtx;
    c.setTransform(res, 0, 0, res, 0, 0);
    c.lineJoin = 'round';
    for (const s of this.strokes) paintStroke(c, s);
    this.cacheScale = res;
    this._cacheDirty = false;
  }

  requestRender() {
    if (!this._raf) {
      this._raf = requestAnimationFrame(() => {
        this._raf = 0;
        this.render();
      });
    }
  }

  render() {
    this._ensureCache();
    const { ctx, view, dpr } = this;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = BG_COLOR;
    ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);

    // page A4 blanche + ombre (en pixels écran)
    const x = view.x * dpr;
    const y = view.y * dpr;
    const w = PAGE_W * view.scale * dpr;
    const h = PAGE_H * view.scale * dpr;
    ctx.save();
    ctx.shadowColor = PAGE_SHADOW;
    ctx.shadowBlur = 16 * dpr;
    ctx.shadowOffsetY = 3 * dpr;
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(x, y, w, h);
    ctx.restore();

    // encre (unités monde)
    ctx.setTransform(view.scale * dpr, 0, 0, view.scale * dpr, x, y);
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(this.cache, 0, 0, PAGE_W, PAGE_H);

    if (this.live && this.live.pts.length) {
      const path = outlineToPath(strokeOutline(this.live.pts, this.live.size));
      if (path) {
        ctx.fillStyle = this.live.color;
        ctx.fill(path, 'nonzero');
      } else {
        const p0 = this.live.pts[0];
        ctx.beginPath();
        ctx.arc(p0.x, p0.y, Math.max(0.4, this.live.size * 0.45), 0, Math.PI * 2);
        ctx.fillStyle = this.live.color;
        ctx.fill();
      }
    }

    // curseur de la gomme (souris / stylet)
    if (this.tool === 'eraser' && this.erasePos && !this._eraseWithTouch()) {
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.beginPath();
      ctx.arc(this.erasePos.x * dpr, this.erasePos.y * dpr, ERASE_RADIUS_PX * dpr, 0, Math.PI * 2);
      ctx.strokeStyle = 'rgba(15,23,42,0.55)';
      ctx.lineWidth = 1.5 * dpr;
      ctx.stroke();
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0);
  }

  /* --------------------------- pointeurs --------------------------- */

  _bind() {
    const c = this.canvas;
    this._onDown = (e) => this._pointerDown(e);
    this._onMove = (e) => this._pointerMove(e);
    this._onUp = (e) => this._pointerUp(e);
    this._onWheel = (e) => this._wheel(e);
    this._onMenu = (e) => e.preventDefault();
    c.addEventListener('pointerdown', this._onDown);
    c.addEventListener('pointermove', this._onMove);
    c.addEventListener('pointerup', this._onUp);
    c.addEventListener('pointercancel', this._onUp);
    c.addEventListener('pointerleave', (e) => {
      if (e.pointerType === 'mouse' && !this.pointers.has(e.pointerId)) this.erasePos = null;
    });
    c.addEventListener('wheel', this._onWheel, { passive: false });
    c.addEventListener('contextmenu', this._onMenu);
  }

  _unbind() {
    const c = this.canvas;
    c.removeEventListener('pointerdown', this._onDown);
    c.removeEventListener('pointermove', this._onMove);
    c.removeEventListener('pointerup', this._onUp);
    c.removeEventListener('pointercancel', this._onUp);
    c.removeEventListener('wheel', this._onWheel);
    c.removeEventListener('contextmenu', this._onMenu);
  }

  _updateRect() {
    this._rect = this.canvas.getBoundingClientRect();
  }

  _pos(e) {
    return { x: e.clientX - this._rect.left, y: e.clientY - this._rect.top };
  }

  _resize() {
    const host = this.canvas.parentElement;
    const w = Math.max(1, Math.round(host?.clientWidth || window.innerWidth));
    const h = Math.max(1, Math.round(host?.clientHeight || window.innerHeight));
    if (w === this.cssW && h === this.cssH) return;
    // conserve le point au centre de l'écran
    const cx = (this.cssW || w) / 2;
    const cy = (this.cssH || h) / 2;
    const center = this.toWorld(cx, cy);
    this.cssW = w;
    this.cssH = h;
    this.dpr = Math.max(1, Math.min(window.devicePixelRatio || 1, 3));
    this.canvas.width = Math.round(w * this.dpr);
    this.canvas.height = Math.round(h * this.dpr);
    if (this.view.scale) {
      this.view.x = w / 2 - center.x * this.view.scale;
      this.view.y = h / 2 - center.y * this.view.scale;
    }
    this._updateRect();
    this._invalidateCache();
    this.requestRender();
  }

  _touchPointers() {
    return [...this.pointers.entries()].filter(([, p]) => p.type === 'touch');
  }

  _pointerDown(e) {
    if (!this.ready) return;
    if (e.pointerType === 'mouse' && e.button !== 0 && e.button !== 1) return;
    e.preventDefault();
    this.canvas.setPointerCapture(e.pointerId);
    this._updateRect();
    const p = this._pos(e);
    this.pointers.set(e.pointerId, { x: p.x, y: p.y, type: e.pointerType });

    if (e.pointerType === 'pen') {
      // Le stylet a priorité absolue : il interrompt la gestuelle tactile.
      this._endGesture();
      if (this.mode === 'erase' && this.erasePointerId !== e.pointerId) this._finishErase();
      if (this.mode === 'draw' && this.live && this.live.pointerId !== 'pen') this._discardLive();
      if (this.tool === 'eraser') this._startErase(e, p, 'pen');
      else this._startDraw(e, p, 'pen');
      return;
    }

    if (e.pointerType === 'touch') {
      // Rejet de la paume : pendant une écriture au stylet, on ignore le tactile.
      if (this.mode === 'draw' && this.live && this.live.pointerType === 'pen') return;
      if (this.mode === 'erase' && this.erasePointerType === 'pen') return;

      const touches = this._touchPointers();
      const stylusOnly = this.cb.getInputMode() === 'stylus';
      if (stylusOnly || touches.length >= 2) {
        // 2 doigts (ou doigt en mode stylet) => navigation
        if (this.mode === 'draw') this._discardLive();
        if (this.mode === 'erase') this._finishErase();
        this._startGesture();
      } else {
        // un seul doigt et l'écriture au doigt est activée
        if (this.tool === 'eraser') this._startErase(e, p, 'touch');
        else this._startDraw(e, p, 'touch');
      }
      return;
    }

    // souris : bouton du milieu (ou barre espace) = déplacement
    if (e.button === 1 || this.spaceDown) {
      this._startGesture();
    } else if (this.tool === 'eraser') {
      this._startErase(e, p, 'mouse');
    } else {
      this._startDraw(e, p, 'mouse');
    }
  }

  _pointerMove(e) {
    if (e.pointerType === 'mouse') this._updateRect();
    if (!this.pointers.has(e.pointerId)) return;
    const p = this._pos(e);
    this.pointers.set(e.pointerId, { x: p.x, y: p.y, type: e.pointerType });

    if (this.mode === 'draw' && this.live && e.pointerId === this.live.pointerId) {
      const evs = e.getCoalescedEvents ? e.getCoalescedEvents() : null;
      if (evs && evs.length) for (const ev of evs) this._addLivePoint(ev);
      else this._addLivePoint(e);
      this.requestRender();
    } else if (this.mode === 'erase' && e.pointerId === this.erasePointerId) {
      const evs = e.getCoalescedEvents ? e.getCoalescedEvents() : null;
      if (evs && evs.length) for (const ev of evs) this._eraseAt(this._pos(ev));
      else this._eraseAt(p);
      this.requestRender();
    } else if (this.mode === 'pan' || this.mode === 'pinch') {
      this._applyGesture();
    }
  }

  _pointerUp(e) {
    const wasGesture = this.mode === 'pan' || this.mode === 'pinch';
    this.pointers.delete(e.pointerId);

    if (this.mode === 'draw' && this.live && e.pointerId === this.live.pointerId) {
      this._finishLive();
    } else if (this.mode === 'erase' && e.pointerId === this.erasePointerId) {
      this._finishErase();
    } else if (wasGesture) {
      const pts = this._gesturePointers();
      if (!pts.length) this._endGesture();
      else {
        // un doigt reste après le pincement => il poursuit le déplacement
        this.gesture.ids = pts.map(([id]) => id);
        this._initGestureFrame();
        this.mode = this.gesture.type;
      }
    }
    this._updateCursor();
  }

  _wheel(e) {
    e.preventDefault();
    this._updateRect();
    const p = this._pos(e);
    if (e.ctrlKey || e.metaKey) {
      // pinch du trackpad ou Ctrl+molette
      const factor = clamp(Math.exp(-e.deltaY * 0.0022), 0.75, 1.35);
      this.zoomAt(p.x, p.y, factor);
    } else {
      this.view.x -= e.deltaX;
      this.view.y -= e.deltaY;
      this._clampPan();
      this.requestRender();
    }
  }

  /* ----------------------------- traits ---------------------------- */

  _startDraw(e, p, pointerType) {
    this.mode = 'draw';
    const w = this.toWorld(p.x, p.y);
    this.live = {
      pointerId: e.pointerId,
      pointerType,
      color: INK_COLOR,
      size: this.penSize,
      pts: [{ x: w.x, y: w.y, p: 0.5 }],
      lastRaw: p,
      lastT: performance.now(),
      simP: 0.5,
    };
    this._updateCursor();
    this.requestRender();
  }

  _addLivePoint(e) {
    const live = this.live;
    if (!live) return;
    const p = this._pos(e);
    const d = dist(p, live.lastRaw);
    if (d < DECIMATE_PX && live.pts.length > 1) return; // sous-échantillonnage

    const now = performance.now();
    const dt = Math.max(1, now - live.lastT);
    let pressure;
    if (live.pointerType === 'pen' && e.pressure > 0) {
      pressure = live.simP + (e.pressure - live.simP) * 0.45;
    } else {
      // pression simulée par la vitesse (doigt / souris)
      const speed = d / dt;
      const target = clamp(0.72 - speed * 0.18, 0.28, 0.72);
      pressure = live.simP + (target - live.simP) * 0.18;
    }
    live.simP = pressure;
    live.lastRaw = p;
    live.lastT = now;

    const w = this.toWorld(p.x, p.y);
    live.pts.push({ x: w.x, y: w.y, p: pressure });
  }

  _finishLive() {
    const live = this.live;
    this.live = null;
    this.mode = null;
    if (!live || !live.pts.length) return;
    if (live.pts.length === 1) live.pts.push({ ...live.pts[0] }); // point isolé
    const flat = new Float32Array(live.pts.length * 3);
    live.pts.forEach((pt, i) => {
      flat[i * 3] = pt.x;
      flat[i * 3 + 1] = pt.y;
      flat[i * 3 + 2] = pt.p;
    });
    const stroke = { id: uuid(), color: live.color, size: live.size, points: flat };
    this.cb.onCommitStroke?.(stroke);
    this._updateCursor();
  }

  _discardLive() {
    this.live = null;
    if (this.mode === 'draw') this.mode = null;
    this.requestRender();
  }

  /* ----------------------------- gomme ----------------------------- */

  _eraseWithTouch() {
    return this.erasePointerType === 'touch';
  }

  _startErase(e, p, pointerType) {
    this.mode = 'erase';
    this.erasePointerId = e.pointerId;
    this.erasePointerType = pointerType;
    this.eraseHits = [];
    this.erasePos = p;
    this._eraseAt(p);
    this._updateCursor();
  }

  _eraseAt(p) {
    this.erasePos = p;
    const w = this.toWorld(p.x, p.y);
    const r = ERASE_RADIUS_PX / this.view.scale + 2;
    let removed = false;
    for (let i = this.strokes.length - 1; i >= 0; i--) {
      const s = this.strokes[i];
      if (this._strokeHit(s, w.x, w.y, r)) {
        this.strokes.splice(i, 1);
        this.eraseHits.push({ stroke: s, index: i });
        removed = true;
      }
    }
    if (removed) this._invalidateCache();
  }

  _finishErase() {
    const hits = this.eraseHits || [];
    this.eraseHits = null;
    this.erasePointerId = null;
    this.erasePointerType = null;
    this.erasePos = null;
    this.mode = null;
    if (hits.length) this.cb.onErase?.(hits);
  }

  _strokeHit(s, x, y, r) {
    const box = this._bboxOf(s);
    if (x + r < box[0] || x - r > box[2] || y + r < box[1] || y - r > box[3]) return false;
    const pts = s.points;
    let px = pts[0];
    let py = pts[1];
    const rr = (r + s.size / 2) * (r + s.size / 2);
    if (pts.length === 3) {
      return (x - px) * (x - px) + (y - py) * (y - py) <= rr;
    }
    for (let i = 3; i < pts.length; i += 3) {
      const qx = pts[i];
      const qy = pts[i + 1];
      // distance point-segment
      const dx = qx - px;
      const dy = qy - py;
      const len2 = dx * dx + dy * dy || 1e-6;
      let t = ((x - px) * dx + (y - py) * dy) / len2;
      t = t < 0 ? 0 : t > 1 ? 1 : t;
      const ex = px + t * dx - x;
      const ey = py + t * dy - y;
      if (ex * ex + ey * ey <= rr) return true;
      px = qx;
      py = qy;
    }
    return false;
  }

  _bboxOf(s) {
    let box = this._bbox.get(s);
    if (!box) {
      const pts = s.points;
      let minX = Infinity;
      let minY = Infinity;
      let maxX = -Infinity;
      let maxY = -Infinity;
      for (let i = 0; i < pts.length; i += 3) {
        if (pts[i] < minX) minX = pts[i];
        if (pts[i + 1] < minY) minY = pts[i + 1];
        if (pts[i] > maxX) maxX = pts[i];
        if (pts[i + 1] > maxY) maxY = pts[i + 1];
      }
      const m = s.size;
      box = [minX - m, minY - m, maxX + m, maxY + m];
      this._bbox.set(s, box);
    }
    return box;
  }

  /* ---------------------------- gestuelle -------------------------- */

  _gesturePointers() {
    const touches = this._touchPointers();
    if (touches.length) return touches.slice(0, 2);
    const mouse = [...this.pointers.entries()].filter(([, p]) => p.type === 'mouse');
    return mouse.length ? [mouse[0]] : [];
  }

  _startGesture() {
    const pts = this._gesturePointers();
    if (!pts.length) {
      this.mode = null;
      return;
    }
    this.gesture = { ids: pts.map(([id]) => id) };
    this._initGestureFrame();
    this.mode = this.gesture.type;
    this._updateCursor();
  }

  _initGestureFrame() {
    const g = this.gesture;
    const pts = g.ids.map((id) => this.pointers.get(id)).filter(Boolean);
    const v0 = { ...this.view };
    if (pts.length >= 2) {
      g.type = 'pinch';
      g.start = { d: Math.max(1, dist(pts[0], pts[1])), mx: (pts[0].x + pts[1].x) / 2, my: (pts[0].y + pts[1].y) / 2, view: v0 };
    } else {
      g.type = 'pan';
      g.start = { x: pts[0].x, y: pts[0].y, view: v0 };
    }
  }

  _applyGesture() {
    const g = this.gesture;
    if (!g) return;
    const pts = g.ids.map((id) => this.pointers.get(id)).filter(Boolean);
    if (!pts.length) return;
    const v0 = g.start.view;
    if (g.type === 'pinch' && pts.length >= 2) {
      const d = Math.max(1, dist(pts[0], pts[1]));
      const mx = (pts[0].x + pts[1].x) / 2;
      const my = (pts[0].y + pts[1].y) / 2;
      const s = clamp(v0.scale * (d / g.start.d), MIN_SCALE, MAX_SCALE);
      const k = s / v0.scale;
      this.view.scale = s;
      this.view.x = mx - (g.start.mx - v0.x) * k;
      this.view.y = my - (g.start.my - v0.y) * k;
    } else {
      const p = pts[0];
      this.view.x = v0.x + (p.x - g.start.x);
      this.view.y = v0.y + (p.y - g.start.y);
    }
    this._clampPan();
    this._afterZoom();
  }

  _endGesture() {
    this.gesture = null;
    if (this.mode === 'pan' || this.mode === 'pinch') this.mode = null;
    this._updateCursor();
  }

  /* ---------------------------- curseur ---------------------------- */

  _updateCursor() {
    let cur = 'crosshair';
    if (this.tool === 'eraser') cur = 'none';
    if (this.mode === 'pan' || this.mode === 'pinch') cur = 'grabbing';
    else if (this.spaceDown) cur = 'grab';
    this.canvas.style.cursor = cur;
  }

  setSpaceDown(v) {
    this.spaceDown = v;
    this._updateCursor();
  }
}
