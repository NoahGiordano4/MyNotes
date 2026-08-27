# MyNotes ✍️

Application de prise de notes manuscrites : page A4 blanche, encre noire
fluide, écriture au stylet ou au doigt, zoom/déplacement à deux doigts,
pages multiples par note, sauvegarde compatible cloud (Google Drive /
OneDrive).

**Deux applications, un même format de données** (`mynotes.doc` v1) :
une note exportée par l'une s'importe dans l'autre.

| Plateforme | Dossier | Stack |
|---|---|---|
| **Web** (tablette, iPad, PC) | `/` (racine) | Vite + JavaScript vanilla + perfect-freehand |
| **Android natif** (tablette + stylet, cible principale) | `/android-app` | Kotlin, Views + ViewBinding, Material 3 |

```
/                    application web (src/, public/, tests/, index.html…)
/android-app         application Android native (projet Gradle autonome, wrapper inclus)
/cahier-des-charges.json   référence unique du projet (exigences, format, CI)
```

---

## Web

Aucune dépendance lourde : **Vite + JavaScript vanilla + perfect-freehand**
(algorithme de tracé à pression). L'app fonctionne sur iPad / tablette
Android / Surface / ordinateur (souris + molette).

```bash
npm install
npm run dev      # développement (http://localhost:5173)
npm test         # tests (tests/logic.test.mjs)
npm run build    # build de production dans dist/
npm run preview  # sert le build
```

### Fonctionnalités de la base

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
  automatique, indicateur « Enregistré ».

### Stockage web

- **Local** : IndexedDB (base `mynotes`), deux stores :
  - `notes` : métadonnées (id, titre, dates, nombre de pages, vignette) ;
  - `pages` : contenu, clé `${noteId}:${index}` → **une écriture par page**,
    donc rapide même sur de longues notes.
- **Export / import** : fichier `.mynotes.json` (voir format ci-dessous).

---

## Android

Application native dans [`/android-app`](android-app/) — **Kotlin**,
Views + ViewBinding, Material 3, RecyclerView.

- **Ouvrir** : Android Studio → File → Open → dossier `android-app/`
- **SDK** : minSdk 24 (Android 7.0+), target/compileSdk 34
- **Outils** : Gradle 8.7 (wrapper inclus), AGP 8.5.2, Kotlin 1.9.24

```bash
cd android-app
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

> 💡 Pas besoin d'Android Studio pour installer : à chaque push sur
> `android-app/**`, la CI GitHub Actions construit l'APK — onglet
> **Actions** → dernier run → artefact `app-debug-apk` → l'ouvrir sur la
> tablette.

### Spécificités Android

- Stockage : fichiers privés `files/notes/<id>/meta.json` +
  `p<index>.json` (écriture atomique), vignettes PNG, réglages en
  SharedPreferences.
- Événements historiques (`getHistorical*`) : aucune interposition du
  stylet n'est perdue.
- Gomme physique du stylet reconnue (`TOOL_TYPE_ERASER`).
- Export / import via SAF (Storage Access Framework) : Google Drive est
  proposé directement par le sélecteur système.

---

## Format de données partagé : `mynotes.doc` v1

Une note = **un fichier JSON autonome et versionné**. Coordonnées
arrondies à 2 décimales pour des fichiers légers. C'est ce format qui est
synchronisé vers **Google Drive / OneDrive** (une note = un fichier).

```
{ "format": "mynotes.doc", "version": 1, "id": "…", "title": "…",
  "createdAt": 0, "updatedAt": 0,
  "pages": [ { "index": 0, "strokes": [ { "c": "#000000", "s": 3.4,
              "p": [x, y, pression, …] } ] } ] }
```

Règle : toute évolution du format reste **rétrocompatible** (nouveaux
champs optionnels uniquement, version incrémentée).

## Optimisations de rendu (communes aux deux plateformes)

- Traits terminés dessinés **une seule fois** dans un cache bitmap
  hors-écran ; pendant l'écriture on ne redessine que le trait en
  cours → fluide.
- Événements **coalescés / historiques** : toutes les interpositions du
  stylet sont utilisées, même entre deux frames.
- Sous-échantillonnage spatial (~1 px/dp) et rendu piloté par frame.
- Rejet de la paume : en mode stylet, les pointeurs tactiles ne tracent
  jamais ; pendant un tracé de stylet, ils sont ignorés.

## CI / CD (GitHub Actions)

- **`web`** (`.github/workflows/web.yml`) : à chaque push et PR vers
  main → `npm ci` + `npm test` + `npm run build`.
- **`android`** (`.github/workflows/android.yml`) : à chaque push sur
  `android-app/**` et PR vers main → `./gradlew assembleDebug` + APK
  publié en artefact.

## Feuille de route

1. **v1.1** — Google Drive (EF-20) puis OneDrive (EF-21) : OAuth,
   une note = un fichier `mynotes.doc`, conflits « dernier modifié
   gagne », synchronisation en arrière-plan (WorkManager sur Android) ;
2. **v1.2** — reconnaissance d'écriture manuscrite (ML Kit Digital Ink
   sur Android, champ `text` + timestamps optionnels en format v1.1) et
   recherche plein texte ; PWA hors ligne complète (service worker) ;
3. **v1.3** — couleurs d'encre et surligneur.

→ Référence complète : [`cahier-des-charges.json`](cahier-des-charges.json)
