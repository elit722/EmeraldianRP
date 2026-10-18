# Intégration au Worker ReLink

Le Worker n'est pas dans cette archive : `rp-names.js` est un bloc à coller dans ton `worker.js`.

## 1. Coller le code
Colle **tout le contenu de `rp-names.js`** juste avant la ligne `// ── Router ───…` (il réutilise tes helpers existants : `json`, `readJson`, `checkServiceKey`, `checkPermissionOrAdmin`, `sanitizeText`, `clampInt`, `escapeLike`, `runInChunks`, `internalError`).

## 2. Ajouter 3 routes dans `ROUTES`
```js
  // ── Historique des noms RP (mod Wathe RP) ───────────────────────────────
  ['POST',   '/mc/rp/names',             handleRpNamesPost],
  ['GET',    '/mc/rp/names',             handleRpNamesGet],
  ['GET',    '/admin/rp-names',          handleAdminRpNames],
```

## 3. Table D1
Rien à faire : `ensureRpSchema()` crée `rp_name_history` au premier appel (comme `ensureSiteSchema`).
(Tu peux aussi copier le `CREATE TABLE` de `rp-names.js` dans la console D1 si tu préfères.)

## 4. Clé du mod : appairage (recommandé)
Plus rien à copier : dans le panel, crée le serveur → code `ABCD-EFGH` → en jeu (console ou OP) `/relink connect ABCD-EFGH`.
Le mod récupère tout seul l'URL, le slug et la clé `mc-<slug>` (scopes `mc.register` + `mc.permissions.read`), qu'il utilise aussi pour l'historique des noms RP :
`rp-names.js` accepte donc `rp.write` **ou** `mc.register` (écriture) et `rp.read` **ou** `mc.permissions.read` (lecture).
**Redéploie ton Worker avec ce `rp-names.js` à jour**, sinon `/r names` répondra « clé refusée » et l'envoi de l'historique restera en file d'attente.

Alternative manuelle (clé dédiée) : `POST /admin/services/<id>/keys { "scopes": ["rp.write","rp.read"] }` puis `/r worker url`, `/r worker key`, `/r worker slug`.

## 5. Consulter l'historique
- En jeu : `/r names player <joueur>` · `/r names rp <nom>`
- Admin Panel : `GET /admin/rp-names?uuid=…|q=…&kind=name|alias&limit=&offset=` (permission `users.read`), avec le compte Discord lié.

## Tests
`node --no-warnings test-rp-names.mjs` (Node 22+) : 23 vérifications du module contre une vraie base SQLite (auth, validation, idempotence, recherche sans accents, jointure Discord).
