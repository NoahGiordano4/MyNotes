/**
 * Réglages de l'application (persistés dans localStorage).
 * inputMode :
 *  - 'stylus' : écriture au stylet uniquement. Le doigt sert à se déplacer,
 *               la paume est ignorée (rejet naturel de la paume).
 *  - 'finger' : écriture au stylet ET au doigt (1 doigt = écrire,
 *               2 doigts = zoom/déplacement, le trait en cours est annulé).
 */

const KEY = 'mynotes:settings';

const DEFAULTS = {
  inputMode: 'stylus',
};

let state = load();
const listeners = new Set();

function load() {
  try {
    return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY) || '{}') };
  } catch {
    return { ...DEFAULTS };
  }
}

function persist() {
  try {
    localStorage.setItem(KEY, JSON.stringify(state));
  } catch {
    /* stockage plein ou indisponible : on ignore */
  }
}

export const settings = {
  get() {
    return state;
  },
  set(patch) {
    state = { ...state, ...patch };
    persist();
    listeners.forEach((fn) => fn(state));
  },
  onChange(fn) {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
};
