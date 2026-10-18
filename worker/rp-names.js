// ════════════════════════════════════════════════════════════════════════════
// HISTORIQUE DES NOMS RP — alimenté par le mod Minecraft « Wathe RP »
// ════════════════════════════════════════════════════════════════════════════
// Le mod envoie chaque nom RP défini (et chaque pseudonyme présenté) ; le Worker les
// conserve dans D1. Auth : clé de service (X-Service-Key) avec le scope `rp.write`
// (envoi) et `rp.read` (consultation depuis le jeu : /r names ...).
//
//   POST /mc/rp/names     X-Service-Key  scope rp.write  OU mc.register          { entries: [ {...} ] }  (1 à 50)
//   GET  /mc/rp/names     X-Service-Key  scope rp.read   OU mc.permissions.read  ?uuid= | ?q= [&kind=name|alias] [&limit=] [&offset=]
//   (les scopes mc.* sont ceux de la clé « mc-<slug> » créée par l'appairage /relink connect : le mod n'a plus qu'une clé)
//   GET  /admin/rp-names  Bearer users.read | X-Admin-Secret   mêmes filtres + compte Discord lié
//
// Idempotent : `event_id` est UNIQUE, un lot renvoyé après une réponse perdue ne crée aucun doublon.

const RP_NAMES_MAX_BATCH = 50;
const RP_KINDS   = ['name', 'alias'];
const RP_SOURCES = ['player', 'kept', 'staff', 'alias'];
const RP_EVENT_ID_RE = /^[A-Za-z0-9-]{8,64}$/;

// Accepte la clé si elle porte AU MOINS UN des scopes (clé dédiée rp.* ou clé d'appairage mc.*).
async function checkAnyScope(request, env, scopes) {
  for (const scope of scopes) if (await checkServiceKey(request, env, scope)) return true;
  return false;
}

const rpSchemaByDb = new WeakMap();
function ensureRpSchema(env) {
  const db = env.relinkdb;
  let p = rpSchemaByDb.get(db);
  if (!p) {
    p = db.batch([
      db.prepare(`CREATE TABLE IF NOT EXISTS rp_name_history (
        id                INTEGER PRIMARY KEY AUTOINCREMENT,
        event_id          TEXT NOT NULL UNIQUE,
        mc_uuid           TEXT NOT NULL,           -- 32 hex, minuscules, sans tirets
        mc_username       TEXT,
        kind              TEXT NOT NULL DEFAULT 'name',   -- 'name' | 'alias'
        rp_name           TEXT NOT NULL,
        rp_name_fold      TEXT NOT NULL,           -- sans accents/casse, pour la recherche
        previous_rp_name  TEXT,
        source            TEXT NOT NULL,           -- 'player' | 'kept' | 'staff' | 'alias'
        set_by            TEXT,                    -- pseudo du staff si source = 'staff'
        shown_to_uuid     TEXT,                    -- alias : à qui on s'est présenté
        shown_to_username TEXT,
        created_at        TEXT NOT NULL,           -- heure côté serveur Minecraft
        received_at       TEXT NOT NULL)`),
      db.prepare('CREATE INDEX IF NOT EXISTS idx_rp_names_uuid    ON rp_name_history(mc_uuid, created_at)'),
      db.prepare('CREATE INDEX IF NOT EXISTS idx_rp_names_fold    ON rp_name_history(rp_name_fold)'),
      db.prepare('CREATE INDEX IF NOT EXISTS idx_rp_names_created ON rp_name_history(created_at)'),
    ]).catch((e) => { rpSchemaByDb.delete(db); throw e; });
    rpSchemaByDb.set(db, p);
  }
  return p;
}

// Même règle que le mod (RpGame.fold) : accents et casse ignorés, « _ » = espace.
function foldRpName(s) {
  return String(s || '').normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase()
    .replace(/_/g, ' ').trim().replace(/\s+/g, ' ');
}
function normalizeMcUuid(u) {
  const v = String(u || '').replace(/-/g, '').toLowerCase();
  return /^[0-9a-f]{32}$/.test(v) ? v : null;
}

function cleanRpEntry(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const eventId = typeof raw.event_id === 'string' ? raw.event_id.trim() : '';
  const uuid = normalizeMcUuid(raw.mc_uuid);
  const rpName = sanitizeText(raw.rp_name, 64);
  const kind = RP_KINDS.includes(raw.kind) ? raw.kind : 'name';
  const source = RP_SOURCES.includes(raw.source) ? raw.source : null;
  if (!RP_EVENT_ID_RE.test(eventId) || !uuid || !rpName || !source) return null;

  let shownTo = null;
  if (kind === 'alias') {
    shownTo = normalizeMcUuid(raw.shown_to_uuid);
    if (!shownTo) return null; // un pseudonyme n'a de sens que présenté à quelqu'un
  }
  const d = new Date(raw.created_at);
  return {
    eventId, uuid, rpName, kind, source, shownTo,
    fold: foldRpName(rpName),
    username: sanitizeText(raw.mc_username, 32) || null,
    previous: sanitizeText(raw.previous_rp_name, 64) || null,
    setBy: sanitizeText(raw.set_by, 32) || null,
    shownToName: sanitizeText(raw.shown_to_username, 32) || null,
    createdAt: isNaN(d.getTime()) ? new Date().toISOString() : d.toISOString(),
  };
}

// ── POST /mc/rp/names ───────────────────────────────────────────────────────
async function handleRpNamesPost(request, env, origin) {
  if (!(await checkAnyScope(request, env, ['rp.write', 'mc.register']))) return json({ error: 'Non autorisé' }, 401, origin);

  const body = await readJson(request);
  if (!body) return json({ error: 'Payload JSON invalide' }, 400, origin);
  const raw = Array.isArray(body.entries) ? body.entries : [body];
  if (raw.length === 0) return json({ error: 'entries vide' }, 400, origin);
  if (raw.length > RP_NAMES_MAX_BATCH) return json({ error: `Maximum ${RP_NAMES_MAX_BATCH} entrées par requête` }, 413, origin);

  const clean = raw.map(cleanRpEntry).filter(Boolean);
  if (clean.length === 0) return json({ error: 'Aucune entrée valide' }, 400, origin);

  try {
    await ensureRpSchema(env);
    const now = new Date().toISOString();
    const stmt = env.relinkdb.prepare(
      `INSERT OR IGNORE INTO rp_name_history
       (event_id, mc_uuid, mc_username, kind, rp_name, rp_name_fold, previous_rp_name, source, set_by,
        shown_to_uuid, shown_to_username, created_at, received_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
    await runInChunks(env, clean.map(e => stmt.bind(
      e.eventId, e.uuid, e.username, e.kind, e.rpName, e.fold, e.previous, e.source, e.setBy,
      e.shownTo, e.shownToName, e.createdAt, now)));
  } catch (e) {
    return internalError(e, origin, 'rp_names_post');
  }
  return json({ ok: true, received: clean.length, rejected: raw.length - clean.length }, 200, origin);
}

// ── Lecture (partagée service / admin) ──────────────────────────────────────
async function queryRpNames(env, params, { withDiscord }) {
  await ensureRpSchema(env);
  const where = [], binds = [];
  const uuidParam = params.get('uuid');
  if (uuidParam) {
    const u = normalizeMcUuid(uuidParam);
    if (!u) return { error: 'uuid invalide' };
    where.push('h.mc_uuid = ?'); binds.push(u);
  }
  const q = params.get('q');
  if (q) { where.push("h.rp_name_fold LIKE ? ESCAPE '\\'"); binds.push(`%${escapeLike(foldRpName(q))}%`); }
  const kind = params.get('kind');
  if (kind) {
    if (!RP_KINDS.includes(kind)) return { error: 'kind invalide (name|alias)' };
    where.push('h.kind = ?'); binds.push(kind);
  }
  const limit = clampInt(params.get('limit'), 20, 1, 100);
  const offset = clampInt(params.get('offset'), 0, 0, 1000000);
  const whereSql = where.length ? `WHERE ${where.join(' AND ')}` : '';

  const cols = `h.mc_uuid, h.mc_username, h.kind, h.rp_name, h.previous_rp_name, h.source, h.set_by,
                h.shown_to_uuid, h.shown_to_username, h.created_at`;
  const sql = withDiscord
    ? `SELECT ${cols}, u.discord_id, u.pseudo AS discord_pseudo
       FROM rp_name_history h
       LEFT JOIN users u ON LOWER(REPLACE(u.minecraft_uuid, '-', '')) = h.mc_uuid
       ${whereSql} ORDER BY h.created_at DESC, h.id DESC LIMIT ? OFFSET ?`
    : `SELECT ${cols} FROM rp_name_history h ${whereSql} ORDER BY h.created_at DESC, h.id DESC LIMIT ? OFFSET ?`;

  const rows = await env.relinkdb.prepare(sql).bind(...binds, limit, offset).all();
  const total = await env.relinkdb.prepare(`SELECT COUNT(*) AS n FROM rp_name_history h ${whereSql}`).bind(...binds).first();
  return { entries: rows.results || [], total: Number(total?.n || 0), limit, offset };
}

// ── GET /mc/rp/names ────────────────────────────────────────────────────────
async function handleRpNamesGet(request, env, origin) {
  if (!(await checkAnyScope(request, env, ['rp.read', 'mc.permissions.read']))) return json({ error: 'Non autorisé' }, 401, origin);
  const params = new URL(request.url).searchParams;
  if (!params.get('uuid') && !params.get('q') && params.get('limit') !== '1')
    return json({ error: 'uuid ou q requis' }, 400, origin); // pas de dump complet via la clé du serveur de jeu
  try {
    const r = await queryRpNames(env, params, { withDiscord: false });
    if (r.error) return json({ error: r.error }, 400, origin);
    return json(r, 200, origin);
  } catch (e) {
    return internalError(e, origin, 'rp_names_get');
  }
}

// ── GET /admin/rp-names ─────────────────────────────────────────────────────
async function handleAdminRpNames(request, env, origin) {
  if (!(await checkPermissionOrAdmin(request, env, 'users.read'))) return json({ error: 'Non autorisé' }, 401, origin);
  try {
    const r = await queryRpNames(env, new URL(request.url).searchParams, { withDiscord: true });
    if (r.error) return json({ error: r.error }, 400, origin);
    return json(r, 200, origin);
  } catch (e) {
    return internalError(e, origin, 'admin_rp_names');
  }
}
