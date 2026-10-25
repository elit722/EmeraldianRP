package fr.watherp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/** Envoi / mise à jour des tickets via un webhook Discord (asynchrone, sans bloquer le serveur). */
public final class Discord {
    private Discord() {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public static boolean enabled() {
        String u = RpConfig.INSTANCE.webhookUrl;
        return u != null && u.startsWith("https://");
    }

    private static int color(Ticket t) {
        return switch (t.status) {
            case Ticket.CLAIMED -> 0xF1C40F;
            case Ticket.CLOSED -> 0x2ECC71;
            default -> 0xE74C3C;
        };
    }

    private static String cut(String s, int max) {
        if (s == null || s.isEmpty()) return "—";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static void field(JsonArray a, String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", cut(value, 1000));
        f.addProperty("inline", inline);
        a.add(f);
    }

    private static JsonObject embed(Ticket t, int staffOnline, int playersOnline, int maxPlayers) {
        String statusLabel = switch (t.status) {
            case Ticket.CLAIMED -> "🟡 Pris en charge par " + t.claimedBy;
            case Ticket.CLOSED -> "🟢 Clos" + (t.claimedBy.isEmpty() ? "" : " (" + t.claimedBy + ")");
            default -> "🔴 Ouvert";
        };
        JsonObject e = new JsonObject();
        e.addProperty("title", "🎫 Ticket #" + t.id + " — " + t.type.toUpperCase() + (t.anonymous ? " · 🕵️ ANONYME" : ""));
        e.addProperty("description", cut(t.message.isEmpty() ? "*(pas de message)*" : t.message, 1800));
        e.addProperty("color", color(t));
        e.addProperty("timestamp", Instant.ofEpochMilli(t.createdMs).toString());
        JsonArray f = new JsonArray();
        field(f, "Statut", statusLabel, false);
        if (t.anonymous) {
            // anonymisé : on n'envoie RIEN qui permette d'identifier le joueur (ni pseudo, ni nom RP, ni UUID, ni position)
            field(f, "Joueur", "🕵️ Anonyme — identité masquée (à lever depuis le menu helper si nécessaire)", false);
        } else {
            field(f, "Joueur (Minecraft)", t.playerName, true);
            field(f, "Nom RP", t.rpName.isEmpty() ? "non défini" : t.rpName, true);
            field(f, "UUID", "`" + t.playerUuid + "`", false);
            field(f, "Monde", t.world, true);
            field(f, "Position", String.format("%.0f %.0f %.0f", t.x, t.y, t.z) + "\n`/tp @s " + (int) t.x + " " + (int) t.y + " " + (int) t.z + "`", true);
            if (!t.deanonBy.isEmpty()) field(f, "Anonymat", "Levé par " + t.deanonBy, false);
        }
        if (t.wantChat) field(f, "Chat privé", "💬 Le joueur demande un chat privé" + (t.chatId > 0 ? " (ouvert)" : ""), false);
        field(f, "Partie Wathe", t.inGame ? "En cours" : "Pas de partie en cours", false);
        field(f, "Serveur", playersOnline + "/" + maxPlayers + " joueurs · " + staffOnline + " staff en ligne", false);
        e.add("fields", f);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", RpConfig.INSTANCE.serverName);
        e.add("footer", footer);
        return e;
    }

    private static String body(Ticket t, boolean ping, int staffOnline, int playersOnline, int maxPlayers) {
        JsonObject root = new JsonObject();
        root.addProperty("username", RpConfig.INSTANCE.serverName + " · Assistance");
        String role = RpConfig.INSTANCE.discordPingRoleId;
        if (ping && role != null && !role.isBlank()) {
            root.addProperty("content", "<@&" + role.trim() + "> nouvelle demande d'aide !");
            JsonObject allowed = new JsonObject();
            JsonArray parse = new JsonArray();
            JsonArray roles = new JsonArray();
            roles.add(role.trim());
            allowed.add("parse", parse);
            allowed.add("roles", roles);
            root.add("allowed_mentions", allowed);
        }
        JsonArray embeds = new JsonArray();
        embeds.add(embed(t, staffOnline, playersOnline, maxPlayers));
        root.add("embeds", embeds);
        return root.toString();
    }

    /** Envoie un nouveau ticket ; mémorise l'id du message pour les mises à jour. */
    public static void send(Ticket t, int staffOnline, int playersOnline, int maxPlayers) {
        if (!enabled()) return;
        String url = RpConfig.INSTANCE.webhookUrl + (RpConfig.INSTANCE.webhookUrl.contains("?") ? "&" : "?") + "wait=true";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body(t, true, staffOnline, playersOnline, maxPlayers)))
            .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(r -> {
            if (r.statusCode() / 100 == 2) {
                try {
                    t.discordMessageId = JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
                    RpData.save();
                } catch (Exception ignored) {
                }
            } else {
                WatheRP.LOGGER.warn("Webhook Discord : HTTP {} {}", r.statusCode(), r.body());
            }
        }).exceptionally(ex -> {
            WatheRP.LOGGER.warn("Webhook Discord injoignable : {}", ex.toString());
            return null;
        });
    }

    /** Met à jour l'embed existant (statut / couleur). */
    public static void update(Ticket t, int staffOnline, int playersOnline, int maxPlayers) {
        if (!enabled() || t.discordMessageId == null || t.discordMessageId.isEmpty()) return;
        String base = RpConfig.INSTANCE.webhookUrl;
        String url;
        int q = base.indexOf('?');
        url = (q < 0 ? base : base.substring(0, q)) + "/messages/" + t.discordMessageId;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body(t, false, staffOnline, playersOnline, maxPlayers)))
            .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).exceptionally(ex -> {
            WatheRP.LOGGER.warn("Webhook Discord (mise à jour) : {}", ex.toString());
            return null;
        });
    }

    /** Supprime le message Discord d'un ticket supprimé dans le jeu. */
    public static void delete(Ticket t) {
        if (!enabled() || t.discordMessageId == null || t.discordMessageId.isEmpty()) return;
        String base = RpConfig.INSTANCE.webhookUrl;
        int q = base.indexOf('?');
        String url = (q < 0 ? base : base.substring(0, q)) + "/messages/" + t.discordMessageId;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .DELETE()
            .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).exceptionally(ex -> {
            WatheRP.LOGGER.warn("Webhook Discord (suppression) : {}", ex.toString());
            return null;
        });
    }

    /**
     * Journal des actions du staff (désanonymisation, suppression de ticket, avertissements, rangs, Worker…).
     * Va sur logWebhookUrl s'il est défini, sinon sur le webhook des tickets. Ne contient jamais l'identité d'un joueur
     * qui a demandé l'anonymat. Désactivable avec logStaffActions=false.
     */
    public static void log(String title, String description, int color) {
        RpConfig c = RpConfig.INSTANCE;
        if (!c.logStaffActions) return;
        String url = journalUrl();
        if (url == null) return;
        JsonObject root = new JsonObject();
        root.addProperty("username", c.serverName + " · Journal staff");
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray()); // jamais de mention
        root.add("allowed_mentions", allowed);
        JsonObject e = new JsonObject();
        e.addProperty("title", cut(title, 200));
        e.addProperty("description", cut(description, 1800));
        e.addProperty("color", color);
        e.addProperty("timestamp", Instant.now().toString());
        JsonArray embeds = new JsonArray();
        embeds.add(e);
        root.add("embeds", embeds);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).exceptionally(ex -> {
                WatheRP.LOGGER.warn("Webhook Discord (journal) : {}", ex.toString());
                return null;
            });
        } catch (Exception ex) {
            WatheRP.LOGGER.warn("Webhook Discord (journal) : URL invalide");
        }
    }

    // ---------------------------------------------------------------- journal des présentations / débrief

    /** Webhook du journal staff : logWebhookUrl s'il est valide, sinon le webhook des tickets ; null si rien n'est configuré. */
    public static String journalUrl() {
        RpConfig c = RpConfig.INSTANCE;
        if (c.logWebhookUrl != null && RpConfig.isDiscordWebhook(c.logWebhookUrl)) return c.logWebhookUrl.trim();
        if (c.webhookUrl != null && c.webhookUrl.startsWith("https://")) return c.webhookUrl.trim();
        return null;
    }

    /** Webhook du débrief : debriefWebhookUrl s'il est valide, sinon celui du journal staff. */
    public static String debriefUrl() {
        RpConfig c = RpConfig.INSTANCE;
        if (c.debriefWebhookUrl != null && RpConfig.isDiscordWebhook(c.debriefWebhookUrl)) return c.debriefWebhookUrl.trim();
        return journalUrl();
    }

    /** Échappe le Markdown de Discord et neutralise les mentions (@everyone, @here, <@id>) dans un texte saisi par un joueur. */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\', '*', '_', '~', '`', '|', '>', '#', '[', ']', '(', ')' -> b.append('\\').append(ch);
                case '@' -> b.append("@\u200b");
                case '<' -> b.append("<\u200b");
                case '\n', '\r' -> b.append(' ');
                default -> b.append(ch);
            }
        }
        return b.toString();
    }

    private static final int EMBED_CHARS = 3800;

    /**
     * Envoie une liste de lignes en embeds (un message par tranche d'environ 3800 caractères, au plus {@code maxMessages}
     * messages, espacés de 700 ms pour rester sous la limite d'envoi des webhooks). Aucune mention n'est possible.
     */
    public static void postLines(String url, String username, String title, List<String> lines, int color, int maxMessages) {
        if (url == null || lines == null || lines.isEmpty()) return;
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int omitted = 0;
        for (String raw : lines) {
            String line = raw.length() > 900 ? raw.substring(0, 899) + "…" : raw;
            if (cur.length() + line.length() + 1 > EMBED_CHARS && cur.length() > 0) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            if (chunks.size() >= Math.max(1, maxMessages)) {
                omitted++;
                continue;
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(line);
        }
        if (cur.length() > 0 && chunks.size() < Math.max(1, maxMessages)) chunks.add(cur.toString());
        else if (cur.length() > 0) omitted += cur.toString().split("\n").length;
        if (omitted > 0 && !chunks.isEmpty()) {
            int last = chunks.size() - 1;
            chunks.set(last, chunks.get(last) + "\n*… " + omitted + " ligne(s) non affichée(s)*");
        }
        for (int i = 0; i < chunks.size(); i++) {
            final int idx = i;
            String t = idx == 0 ? title : title + " (suite " + (idx + 1) + ")";
            if (idx == 0) postEmbed(url, username, t, chunks.get(idx), color);
            else java.util.concurrent.CompletableFuture.delayedExecutor(700L * idx, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> postEmbed(url, username, t, chunks.get(idx), color));
        }
    }

    private static void postEmbed(String url, String username, String title, String description, int color) {
        JsonObject root = new JsonObject();
        root.addProperty("username", username);
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray()); // jamais de mention
        root.add("allowed_mentions", allowed);
        JsonObject e = new JsonObject();
        e.addProperty("title", cut(title, 250));
        e.addProperty("description", cut(description, 4000));
        e.addProperty("color", color);
        e.addProperty("timestamp", Instant.now().toString());
        JsonArray embeds = new JsonArray();
        embeds.add(e);
        root.add("embeds", embeds);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(r -> {
                if (r.statusCode() / 100 != 2) WatheRP.LOGGER.warn("Webhook Discord (journal) : HTTP {} {}", r.statusCode(), r.body());
            }).exceptionally(ex -> {
                WatheRP.LOGGER.warn("Webhook Discord (journal) : {}", ex.toString());
                return null;
            });
        } catch (Exception ex) {
            WatheRP.LOGGER.warn("Webhook Discord (journal) : URL invalide");
        }
    }

    /** Envoie un message de test sur le webhook ; {@code done} reçoit le résultat (sur le thread serveur). */
    public static void test(MinecraftServer server, Consumer<String> done) {
        if (!enabled()) {
            done.accept("Aucun webhook configuré (/r webhook set <url>).");
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("username", RpConfig.INSTANCE.serverName + " · Assistance");
        root.addProperty("content", "✅ Test du webhook : la configuration fonctionne.");
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        root.add("allowed_mentions", allowed);
        HttpRequest req = HttpRequest.newBuilder(URI.create(RpConfig.INSTANCE.webhookUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
            .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((r, ex) -> server.execute(() -> {
            if (ex != null) done.accept("Discord injoignable.");
            else if (r.statusCode() / 100 == 2) done.accept("OK : message de test envoyé.");
            else done.accept("Discord a refusé le webhook (HTTP " + r.statusCode() + ").");
        }));
    }
}
