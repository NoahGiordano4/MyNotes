# MyNotes ✍️

Application web de prise de notes manuscrites : page A4 blanche, encre noire
fluide, écriture au stylet ou au doigt, zoom/deplacement à deux doigts.

Aucune dépendance lourde : **Vite + JavaScript vanilla + perfect-freehand**
(algorithme de tracé à pression). L'app fonctionne sur iPad / tablette
Android / Surface / ordinateur (souris + molette) et s'installe comme app
(PWA de base).

## Lancer le projet

```bash
npm install
npm run dev      # développement (http://localhost:5173)
npm run build    # build de production dans dist/
npm run preview  # sert le build
```

## Fonctionnalités de la base

- **Menu** : liste des notes (vignette, date, nombre de pages), bouton
  « + Nouvelle note », renommage, suppression, export.
- **Éditeur** : page A4 (210 × 297 mm), fond blanc, encre noire fluide avec
  pression réelle du stylet (pression simulée par la vitesse pour le
  doigt / la souris).
- **Outils** : 3 épaisseurs de trait, gomme (efface un trait entier),
  annuler / rétablir (Ctrl+Z / Ctrl+Maj+Z), raccourcis B / E.
- **Pages** : navigation ‹ › , ajout de pages, une note = plusieurs pages A4.
- **Navigation dans la page** :
  - 2 doigts = pincement pour zoomer + se déplacer ;
  - 1 doigt = se déplacer quand l'écriture au doigt est désactivée ;
  - souris : molette = déplacement, Ctrl+molette = zoom, Espace = déplacement,
    Ctrl+0 = ajuster la page à l'écran.
- **Paramètres** : mode de saisie « Stylet uniquement » (rejet de la paume,
  le doigt déplace la page) ou « Stylet + doigt ». Persistants
  (localStorage), appliqués immédiatement.
- Les notes se **ferment et se rouvrent** telles quelles : sauvegarde
  automatique (voir ci-dessous), indicateur « Enregistré ».

## Stockage : pourquoi ce choix

- **Local** : IndexedDB (base `mynotes`), deux stores :
  - `notes` : métadonnées (id, titre, dates, nombre de pages, vignette) ;
  - `pages` : contenu, clé `${noteId}:${index}` → **une écriture par page**,
    donc rapide même sur de longues notes.
- **Export / cloud** : une note = **un fichier JSON autonome et versionné**
  (`format: "mynotes.doc", version: 1`). Coordonnées arrondies à 2 décimales
  pour des fichiers légers. C'est ce format qui sera synchronisé vers
  **Google Drive / OneDrive** (upload du fichier, ouverture d'appDataFolder /
  Files Picker API plus tard). L'import d'un fichier `.mynotes.json` est déjà
  possible (Paramètres → Sauvegarde).

```
{ "format": "mynotes.doc", "version": 1, "id": "…", "title": "…",
  "createdAt": 0, "updatedAt": 0,
  "pages": [ { "index": 0, "strokes": [ { "c": "#000000", "s": 3.4,
              "p": [x, y, pression, …] } ] } ] }
```

## Optimisations de rendu

- Traits terminés dessinés **une seule fois** sur un canvas hors-écran
  (cache bitmap, résolution adaptée au zoom) ; pendant l'écriture on ne
  redessine que le trait en cours → fluide.
- Événements **coalescés** (`getCoalescedEvents`) : toutes les interpositions
  du stylet sont utilisées, même entre deux frames.
- Sous-échantillonnage spatial (~1 px) et rendu piloté par
  `requestAnimationFrame`.
- Rejet de la paume natif : en mode stylet, les pointeurs tactiles ne
  tracent jamais ; pendant un tracé de stylet, ils sont ignorés.

## Structure du code

```
src/
  main.js               navigation menu ⇄ éditeur
  settings.js           réglages (localStorage) + abonnements
  storage/
    notes.js            API notes/pages + export/import JSON versionné
  ink/
    engine.js           moteur canvas : rendu, stylet, gestes, gomme, cache
    history.js          undo/redo
  views/
    menu.js             écran d'accueil (liste, création, suppression…)
    editor.js           éditeur (outils, pages, sauvegarde auto, vignette)
    settings-modal.js   fenêtre Paramètres
    modals.js           modales génériques
  lib/                  idb.js (IndexedDB), util.js, icons.js
```

## Feuille de route suggérée

1. **Google Drive / OneDrive** : OAuth + upload des fichiers `.mynotes.json`
   (le format est déjà prêt) ;
2. synchronisation multi-appareils (fuseau `updatedAt`, résolution simple du
   dernier-écrit-gagne) ;
3. outils supplémentaires : couleurs, surligneur, formes, sélection ;
4. découpage du cache bitmap en tuiles pour les pages très denses ;
5. PWA complète (service worker) pour une utilisation hors ligne installée.
