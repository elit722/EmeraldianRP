package fr.watherp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Connexion du serveur au Worker ReLink (voir MC_SERVER_INTEGRATION.md) :
 *  1. appairage par code        POST /mc/servers/pair            (« /relink connect <code> »)
 *  2. déclaration + heartbeat   POST /mc/servers/register        (au démarrage puis toutes les 1 à 5 min)
 *  3. permissions d'un joueur   GET  /mc/servers/{slug}/permissions?uuid=…   (à la connexion, cache TTL)
 *
 * Règles respectées : tous les appels HTTP sont asynchrones (jamais sur le thread serveur), timeout 5 s, la clé n'est
 * jamais journalisée ni affichée, un 401 arrête les tentatives (pas de boucle), un échec réseau garde le dernier cache
 * et n'accorde jamais de permissions par défaut.
 */
public final class Relink {
    private Relink() {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern SLUG_OK = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,62}$");

    /** Permissions d'un joueur telles que renvoyées par le Worker. */
    public record PlayerPerms(Set<String> roles, Set<String> perms, boolean linked, String status, long fetchedMs) {}

    private static final Map<UUID, PlayerPerms> cache = new ConcurrentHashMap<>();
    private static final Set<UUID> fetching = ConcurrentHashMap.newKeySet();

    private static MinecraftServer server;
    /** Clé refusée (401) : on n'insiste pas tant que l'admin n'a pas refait « /relink connect ». */
    private static boolean authFailed = false;
    /** Les permissions ont déjà été acceptées par le Worker pendant cette session. */
    private static boolean permsDeclared = false;
    /** Le Worker a refusé la liste de permissions (400) : inutile de la renvoyer telle quelle. */
    private static boolean permsRejected = false;
    private static boolean inFlightRegister = false;
    private static long lastHeartbeatMs = 0;
    private static int tickCounter = 0;
    private static boolean lastRegisterFailed = false;

    public static volatile long lastOkMs = 0;
    public static volatile String lastError = "";

    public static void setServer(MinecraftServer s) {
        server = s;
    }

    // ---------------------------------------------------------------- état

    /** URL + clé présentes (suffit pour l'historique des noms RP). */
    public static boolean hasCredentials() {
        RpConfig c = RpConfig.INSTANCE;
        return c.workerUrl != null && c.workerUrl.startsWith("https://")
            && c.workerApiKey != null && !c.workerApiKey.isBlank();
    }

    /** URL + clé + slug : le serveur est relié au panel (permissions des joueurs, heartbeat). */
    public static boolean configured() {
        return hasCredentials() && SLUG_OK.matcher(RpConfig.INSTANCE.serverSlug == null ? "" : RpConfig.INSTANCE.serverSlug).matches();
    }

    public static boolean authFailed() {
        return authFailed;
    }

    public static String base() {
        String u = RpConfig.INSTANCE.workerUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-Service-Key", RpConfig.INSTANCE.workerApiKey.trim());
    }

    /** Exécute {@code r} sur le thread serveur (les callbacks HTTP arrivent sur un autre thread). */
    private static void onServer(Runnable r) {
        MinecraftServer s = server;
        if (s != null) s.execute(r);
        else r.run();
    }

    private static JsonObject parse(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String errorText(JsonObject o) {
        if (o == null || !o.has("error")) return "";
        JsonElement e = o.get("error");
        if (e.isJsonPrimitive()) return e.getAsString();
        if (e.isJsonObject()) {
            JsonObject eo = e.getAsJsonObject();
            String code = eo.has("code") && !eo.get("code").isJsonNull() ? eo.get("code").getAsString() : "";
            String msg = eo.has("message") && !eo.get("message").isJsonNull() ? eo.get("message").getAsString() : "";
            String details = eo.has("details") && !eo.get("details").isJsonNull() ? " " + eo.get("details") : "";
            return (code.isEmpty() ? "" : code + " : ") + msg + details;
        }
        return "";
    }

    // ---------------------------------------------------------------- appairage

    /**
     * « /relink connect <code> [worker_url] ». Seul appel sans X-Service-Key : le code tient lieu d'authentification.
     * {@code feedback} est appelé sur le thread serveur.
     */
    public static void pair(String code, String urlOpt, Consumer<String> feedback) {
        String url = urlOpt == null || urlOpt.isBlank() ? (RpConfig.INSTANCE.workerUrl.isBlank() ? RpConfig.DEFAULT_WORKER_URL : RpConfig.INSTANCE.workerUrl) : urlOpt.trim();
        if (!RpConfig.isHttpsUrl(url)) {
            feedback.accept("URL du Worker invalide : https://… requis.");
            return;
        }
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        String clean = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!clean.matches("[A-Z0-9-]{4,24}")) {
            feedback.accept("Code invalide : il ressemble à ABCD-EFGH (affiché dans le panel).");
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("code", clean);
        HttpRequest rq;
        try {
            rq = HttpRequest.newBuilder(URI.create(url + "/mc/servers/pair"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        } catch (Exception e) {
            feedback.accept("URL du Worker invalide.");
            return;
        }
        final String usedUrl = url;
        HTTP.sendAsync(rq, HttpResponse.BodyHandlers.ofString()).whenComplete((r, ex) -> onServer(() -> {
            if (ex != null) {
                feedback.accept("Worker injoignable (" + usedUrl + ").");
                return;
            }
            JsonObject o = parse(r.body());
            int sc = r.statusCode();
            if (sc / 100 != 2 || o == null || !o.has("api_key")) {
                String why = switch (sc) {
                    case 400 -> "Code invalide ou expiré : générez-en un nouveau dans le panel (ne pas réessayer en boucle).";
                    case 404 -> "Ce serveur a été supprimé du panel.";
                    case 429 -> "Trop de tentatives (10 par 10 min et par IP) : patientez.";
                    default -> "Le Worker a refusé l'appairage (HTTP " + sc + ").";
                };
                feedback.accept(why);
                return;
            }
            String slug = o.has("server") ? o.get("server").getAsString().trim().toLowerCase(Locale.ROOT) : "";
            String key = o.get("api_key").getAsString().trim();
            String wurl = o.has("worker_url") && !o.get("worker_url").isJsonNull() ? o.get("worker_url").getAsString().trim() : usedUrl;
            if (!RpConfig.isHttpsUrl(wurl)) wurl = usedUrl;
            if (!SLUG_OK.matcher(slug).matches() || key.isEmpty()) {
                feedback.accept("Réponse du Worker inattendue : appairage annulé.");
                return;
            }
            RpConfig.INSTANCE.workerUrl = wurl.replaceAll("/+$", "");
            RpConfig.INSTANCE.serverSlug = slug;
            RpConfig.INSTANCE.workerApiKey = key; // remplace l'ancienne clé (révoquée côté Worker)
            RpConfig.save();
            String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : slug;
            feedback.accept("✅ Connecté à ReLink en tant que " + name);
            authFailed = false;
            permsDeclared = false;
            permsRejected = false;
            lastRegisterFailed = false;
            cache.clear();
            Discord.log("Worker ReLink connecté", "Serveur appairé en tant que **" + name + "** (`" + slug + "`).", 0x2ECC71);
            // démarre tout de suite : déclaration des permissions + heartbeat + permissions des joueurs connectés
            register(true, true);
            RpHistory.flush();
            refreshAllOnline(true);
        }));
    }

    /** « /relink reload » : retente après un 401 ou un refus de permissions (une seule fois, sans boucle). */
    public static void retry() {
        authFailed = false;
        permsRejected = false;
        lastRegisterFailed = false;
        lastError = "";
        register(true, true);
    }

    /** Efface la connexion (clé, slug) : le serveur n'est plus relié. */
    public static void disconnect() {
        RpConfig.INSTANCE.workerApiKey = "";
        RpConfig.INSTANCE.serverSlug = "";
        RpConfig.save();
        cache.clear();
        authFailed = false;
        permsDeclared = false;
        MinecraftServer s = server;
        if (s != null) for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) onPermissionsChanged(p);
    }

    // ---------------------------------------------------------------- déclaration + heartbeat

    /** Corps de register : version + état, et la liste des permissions quand {@code withPermissions}. */
    private static String registerBody(boolean withPermissions, boolean online) {
        JsonObject o = new JsonObject();
        MinecraftServer s = server;
        if (s != null) {
            o.addProperty("version", s.getVersion());
            JsonObject st = new JsonObject();
            st.addProperty("online", online);
            st.addProperty("players_online", s.getCurrentPlayerCount());
            st.addProperty("players_max", s.getMaxPlayerCount());
            o.add("status", st);
        }
        if (withPermissions) {
            JsonArray arr = new JsonArray();
            for (String[] d : Perms.DECLARED) {
                JsonObject p = new JsonObject();
                p.addProperty("name", d[0]);
                p.addProperty("description", d[1]);
                arr.add(p);
            }
            o.add("permissions", arr);
        }
        return o.toString();
    }

    /** register : déclare les permissions (si demandé et pas déjà fait) et sert de heartbeat. */
    public static void register(boolean forcePermissions, boolean online) {
        if (!configured() || authFailed || inFlightRegister) return;
        boolean withPerms = (forcePermissions || !permsDeclared) && !permsRejected;
        HttpRequest rq;
        try {
            rq = req("/mc/servers/register").POST(HttpRequest.BodyPublishers.ofString(registerBody(withPerms, online))).build();
        } catch (Exception e) {
            logFailure("URL du Worker invalide.");
            return;
        }
        inFlightRegister = true;
        lastHeartbeatMs = System.currentTimeMillis();
        HTTP.sendAsync(rq, HttpResponse.BodyHandlers.ofString()).whenComplete((r, ex) -> onServer(() -> {
            inFlightRegister = false;
            if (ex != null) {
                logFailure("Worker injoignable (" + ex.getClass().getSimpleName() + "), nouvel essai au prochain heartbeat.");
                return;
            }
            int sc = r.statusCode();
            JsonObject o = parse(r.body());
            if (sc / 100 == 2) {
                lastRegisterFailed = false;
                lastError = "";
                lastOkMs = System.currentTimeMillis();
                if (withPerms) {
                    permsDeclared = true;
                    if (o != null && o.has("permissions_created") && o.get("permissions_created").isJsonArray()
                        && !o.getAsJsonArray("permissions_created").isEmpty()) {
                        WatheRP.LOGGER.info("ReLink : permissions créées dans le panel : {}", o.get("permissions_created"));
                    }
                }
            } else if (sc == 401) {
                authFailed = true;
                lastError = "clé refusée (401)";
                WatheRP.LOGGER.error("ReLink : serveur non connecté : demande un nouveau code dans le panel et tape /relink connect <code>");
            } else if (sc == 404) {
                lastError = "serveur non enregistré dans le panel (404)";
                logFailure("Serveur non enregistré dans le panel (SERVER_NOT_REGISTERED) : crée-le puis refais /relink connect <code>.");
            } else if (sc == 400) {
                if (withPerms) permsRejected = true; // on ne renvoie pas la même liste en boucle
                lastError = "permissions refusées (400)";
                WatheRP.LOGGER.error("ReLink : liste de permissions refusée : {}", errorText(o));
            } else {
                logFailure("Worker HTTP " + sc + ", nouvel essai au prochain heartbeat.");
            }
        }));
    }

    /** Un seul message d'erreur tant que ça ne change pas (pas de spam de logs). */
    private static void logFailure(String msg) {
        lastError = msg;
        if (!lastRegisterFailed) WatheRP.LOGGER.warn("ReLink : {}", msg);
        lastRegisterFailed = true;
    }

    /** À appeler à chaque tick serveur : heartbeat périodique + rafraîchissement des permissions expirées. */
    public static void tick() {
        if (++tickCounter < 100) return; // toutes les 5 s
        tickCounter = 0;
        if (!configured() || authFailed || server == null) return;
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatMs >= RpConfig.INSTANCE.heartbeatSeconds * 1000L) register(false, true);
        long ttl = RpConfig.INSTANCE.permissionTtlSeconds * 1000L;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PlayerPerms pp = cache.get(p.getUuid());
            if (pp == null || now - pp.fetchedMs() > ttl) refresh(p.getUuid(), false);
        }
    }

    /** Dernier message au Worker : online=false (au mieux, avec un timeout court : le serveur s'arrête). */
    public static void shutdown() {
        if (!configured() || authFailed) return;
        try {
            HttpRequest rq = req("/mc/servers/register")
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString(registerBody(false, false))).build();
            HTTP.send(rq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- permissions des joueurs

    public static void refreshAllOnline(boolean force) {
        MinecraftServer s = server;
        if (s == null) return;
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) refresh(p.getUuid(), force);
    }

    /** GET /mc/servers/{slug}/permissions?uuid=… — asynchrone ; garde le cache précédent en cas d'erreur. */
    public static void refresh(UUID id, boolean force) {
        if (!configured() || authFailed) return;
        if (!fetching.add(id)) return;
        HttpRequest rq;
        try {
            String slug = URLEncoder.encode(RpConfig.INSTANCE.serverSlug, StandardCharsets.UTF_8);
            rq = req("/mc/servers/" + slug + "/permissions?uuid=" + id).GET().build();
        } catch (Exception e) {
            fetching.remove(id);
            return;
        }
        HTTP.sendAsync(rq, HttpResponse.BodyHandlers.ofString()).whenComplete((r, ex) -> onServer(() -> {
            fetching.remove(id);
            if (ex != null) return; // injoignable : on garde le cache, on réessaiera
            int sc = r.statusCode();
            if (sc == 401) {
                authFailed = true;
                lastError = "clé refusée (401)";
                WatheRP.LOGGER.error("ReLink : serveur non connecté : demande un nouveau code dans le panel et tape /relink connect <code>");
                return;
            }
            if (sc / 100 != 2) return; // 404/5xx : cache conservé
            JsonObject o = parse(r.body());
            if (o == null) return;
            Set<String> roles = new HashSet<>();
            Set<String> perms = new HashSet<>();
            if (o.has("roles") && o.get("roles").isJsonArray()) for (JsonElement e : o.getAsJsonArray("roles")) roles.add(e.getAsString().toLowerCase(Locale.ROOT));
            if (o.has("permissions") && o.get("permissions").isJsonArray()) for (JsonElement e : o.getAsJsonArray("permissions")) perms.add(e.getAsString().toLowerCase(Locale.ROOT));
            boolean linked = o.has("linked") && !o.get("linked").isJsonNull() && o.get("linked").getAsBoolean();
            String status = o.has("status") && !o.get("status").isJsonNull() ? o.get("status").getAsString() : "";
            if (!status.isEmpty() && !status.equals("active")) { // suspendu / banni : aucune permission
                roles.clear();
                perms.clear();
            }
            PlayerPerms old = cache.get(id);
            PlayerPerms now = new PlayerPerms(roles, perms, linked, status, System.currentTimeMillis());
            cache.put(id, now);
            boolean changed = old == null || !old.roles().equals(roles) || !old.perms().equals(perms);
            lastOkMs = System.currentTimeMillis();
            if (changed && server != null) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p != null) onPermissionsChanged(p);
            }
        }));
    }

    /** Permissions modifiées : le client (touches, boutons) et l'arbre de commandes (/r) sont mis à jour. */
    public static void onPermissionsChanged(ServerPlayerEntity p) {
        Staff.syncCaps(p);
        Chats.syncTo(p); // le chat helper apparaît / disparaît selon les droits
        RpGame.syncKnowledge(p); // /r voirpseudo ne reste actif que pour qui en a encore le droit
        if (server != null) server.getPlayerManager().sendCommandTree(p);
    }

    public static void forget(UUID id) {
        cache.remove(id);
        fetching.remove(id);
    }

    public static PlayerPerms of(UUID id) {
        return cache.get(id);
    }

    public static boolean hasPermission(UUID id, String perm) {
        PlayerPerms p = cache.get(id);
        return p != null && p.perms().contains(perm.toLowerCase(Locale.ROOT));
    }

    public static boolean hasAdminRole(UUID id) {
        PlayerPerms p = cache.get(id);
        if (p == null) return false;
        for (String r : RpConfig.INSTANCE.adminRoles) if (p.roles().contains(r.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
}
