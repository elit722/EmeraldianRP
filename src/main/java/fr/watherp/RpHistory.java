package fr.watherp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Historique des noms RP, stocké dans la base D1 du Worker ReLink.
 *
 * - Écriture : file d'attente persistée (watherp-data.json). Chaque événement est renvoyé tant que le Worker
 *   n'a pas répondu 2xx ; l'event_id permet au Worker d'ignorer un doublon si une réponse s'est perdue.
 *   Le Worker n'est donc jamais sur le chemin critique du jeu : s'il est en panne, rien n'est perdu.
 * - Lecture : /r names ... (asynchrone, résultat renvoyé sur le thread serveur).
 *
 * Toute mutation de RpData se fait sur le thread serveur (les retours HTTP repassent par server.execute).
 */
public final class RpHistory {
    private RpHistory() {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final int BATCH = 50;
    private static final int TICK_INTERVAL = 30 * 20;

    private static MinecraftServer server;
    private static boolean inFlight = false;
    private static int tickCounter = 0;

    public static void setServer(MinecraftServer s) {
        server = s;
    }

    /** Actif dès que le serveur a une URL + une clé (appairage « /relink connect » ou saisie manuelle). */
    public static boolean enabled() {
        return Relink.hasCredentials();
    }

    /** Les routes du Worker sont à la racine : {WORKER_URL}/mc/rp/names (même base que l'appairage et les permissions). */
    private static String base() {
        return Relink.base();
    }

    public static int pending() {
        return RpData.INSTANCE.pendingHistory.size();
    }

    // ---------------------------------------------------------------- écriture

    private static String usernameOf(MinecraftServer s, UUID id) {
        if (s != null) {
            ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
            if (p != null) return p.getGameProfile().getName();
        }
        return RpData.INSTANCE.usernames.getOrDefault(id.toString(), "");
    }

    private static HistoryEntry base(MinecraftServer s, UUID id, String kind, String rpName, String previous, String source) {
        HistoryEntry e = new HistoryEntry();
        e.eventId = UUID.randomUUID().toString();
        e.mcUuid = id.toString();
        e.mcUsername = usernameOf(s, id);
        e.kind = kind;
        e.rpName = rpName;
        e.previousRpName = previous == null ? "" : previous;
        e.source = source;
        e.createdAt = Instant.now().toString();
        return e;
    }

    /** Nom RP défini pour une partie. source : player | kept | staff. */
    public static void recordName(MinecraftServer s, UUID id, String rpName, String previous, String source, String setBy) {
        HistoryEntry e = base(s, id, "name", rpName, previous, source);
        e.setBy = setBy == null ? "" : setBy;
        enqueue(e);
    }

    /** Pseudonyme sous lequel {@code speaker} s'est présenté à {@code shownTo}. */
    public static void recordAlias(MinecraftServer s, UUID speaker, String alias, UUID shownTo) {
        HistoryEntry e = base(s, speaker, "alias", alias, "", "alias");
        e.shownToUuid = shownTo.toString();
        e.shownToUsername = usernameOf(s, shownTo);
        enqueue(e);
    }

    private static void enqueue(HistoryEntry e) {
        if (!enabled()) return; // pas de Worker configuré : on ne fait pas grossir la file pour rien
        RpData.INSTANCE.pendingHistory.add(e);
        RpData.save();
        flush();
    }

    public static void tick() {
        if (++tickCounter < TICK_INTERVAL) return;
        tickCounter = 0;
        flush();
    }

    private static void put(JsonObject o, String key, String value) {
        if (value != null && !value.isEmpty()) o.addProperty(key, value);
    }

    public static void flush() {
        if (inFlight || !enabled() || server == null) return;
        List<HistoryEntry> pending = RpData.INSTANCE.pendingHistory;
        if (pending.isEmpty()) return;

        List<HistoryEntry> batch = new ArrayList<>(pending.subList(0, Math.min(BATCH, pending.size())));
        JsonArray arr = new JsonArray();
        for (HistoryEntry e : batch) {
            JsonObject o = new JsonObject();
            o.addProperty("event_id", e.eventId);
            o.addProperty("mc_uuid", e.mcUuid);
            put(o, "mc_username", e.mcUsername);
            o.addProperty("kind", e.kind);
            o.addProperty("rp_name", e.rpName);
            put(o, "previous_rp_name", e.previousRpName);
            o.addProperty("source", e.source);
            put(o, "set_by", e.setBy);
            put(o, "shown_to_uuid", e.shownToUuid);
            put(o, "shown_to_username", e.shownToUsername);
            o.addProperty("created_at", e.createdAt);
            arr.add(o);
        }
        JsonObject body = new JsonObject();
        body.add("entries", arr);

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(base() + "/mc/rp/names"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Service-Key", RpConfig.INSTANCE.workerApiKey.trim())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        } catch (Exception ex) {
            WatheRP.LOGGER.warn("Historique RP : URL du Worker invalide ({})", ex.getMessage());
            return;
        }

        inFlight = true;
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((r, ex) -> server.execute(() -> {
            inFlight = false;
            if (ex != null) {
                WatheRP.LOGGER.warn("Historique RP : Worker injoignable ({}), nouvel essai plus tard.", ex.toString());
                return;
            }
            int sc = r.statusCode();
            boolean accepted = sc / 100 == 2;
            // 400/413/422 : le Worker refuse définitivement ce lot, le renvoyer ne servirait à rien.
            boolean rejectedForGood = sc == 400 || sc == 413 || sc == 422;
            if (accepted || rejectedForGood) {
                RpData.INSTANCE.pendingHistory.removeAll(batch);
                RpData.save();
                if (rejectedForGood) WatheRP.LOGGER.warn("Historique RP : lot refusé par le Worker (HTTP {}) : {}", sc, r.body());
                if (!RpData.INSTANCE.pendingHistory.isEmpty()) flush();
            } else {
                // 401/403 (clé/scope), 429, 5xx : on garde la file et on réessaiera.
                WatheRP.LOGGER.warn("Historique RP : Worker HTTP {} — {} événement(s) en attente.", sc, RpData.INSTANCE.pendingHistory.size());
            }
        }));
    }

    // ---------------------------------------------------------------- lecture

    public record Result(int status, JsonObject json, String error) {
        public boolean ok() {
            return status / 100 == 2 && json != null;
        }
    }

    /** GET /mc/rp/names?{query} — le callback s'exécute sur le thread serveur. */
    public static void fetch(String query, Consumer<Result> callback) {
        if (!enabled() || server == null) {
            callback.accept(new Result(0, null, "Worker non configuré (/r worker url + /r worker key)."));
            return;
        }
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(base() + "/mc/rp/names?" + query))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("X-Service-Key", RpConfig.INSTANCE.workerApiKey.trim())
                .GET().build();
        } catch (Exception ex) {
            callback.accept(new Result(0, null, "URL du Worker invalide."));
            return;
        }
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((r, ex) -> server.execute(() -> {
            if (ex != null) {
                callback.accept(new Result(0, null, "Worker injoignable."));
                return;
            }
            JsonObject json = null;
            try {
                json = JsonParser.parseString(r.body()).getAsJsonObject();
            } catch (Exception ignored) {
            }
            callback.accept(new Result(r.statusCode(), json, r.statusCode() / 100 == 2 ? null : "HTTP " + r.statusCode()));
        }));
    }
}
