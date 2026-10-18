package fr.watherp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Configuration serveur : config/watherp.json (créée au premier lancement). */
public final class RpConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static RpConfig INSTANCE = new RpConfig();

    /** URL du webhook Discord (Paramètres du salon > Intégrations > Webhooks). Vide = désactivé. */
    public String webhookUrl = "";
    /** ID d'un rôle Discord à mentionner à chaque ticket (optionnel). */
    public String discordPingRoleId = "";
    public String serverName = "Wathe RP";
    public List<String> helpTypes = new ArrayList<>(List.of("role", "bug", "joueur", "question", "urgence", "autre"));
    public int helpCooldownSeconds = 30;
    public int proposalSeconds = 60;
    /** Durée d'affichage de la popup de proposition (la proposition reste ensuite dans le centre des notifications jusqu'à expiration). */
    public int proposalPopupSeconds = 30;
    public int minNameLength = 2;
    /** 32 max. Les sélecteurs vanilla sont limités à 16 caractères : EntitySelectorReaderMixin relève cette limite
     *  (côté serveur ET client) pour que « /tp Nom_Roleplay_Long » fonctionne quand même. */
    public int maxNameLength = 32;
    /** Version du fichier de config (sert aux migrations). */
    public int configVersion = 5;
    public boolean helperCanTeleport = true;
    /** Délai (ticks) entre le début de la partie et l'ouverture du menu de nom RP. */
    public int promptDelayTicks = 60;
    /** URL de base du Worker ReLink (ex. https://relink.xxx.workers.dev). Vide = historique des noms RP désactivé. */
    public String workerUrl = "";
    /** Clé de service du Worker. Remplie par « /relink connect <code> » (appairage), ou à la main via /r worker key. */
    public String workerApiKey = "";
    /** Slug du serveur dans le panel ReLink (ex. « survie »). Rempli par l'appairage. */
    public String serverSlug = "";
    /** Rôles du panel ReLink qui ont TOUTES les permissions du mod automatiquement (comparaison sans casse). */
    public List<String> adminRoles = new ArrayList<>(List.of("admin"));
    /** Intervalle (secondes) du heartbeat vers le Worker (1 à 5 minutes conseillé). */
    public int heartbeatSeconds = 120;
    /** Durée de vie (secondes) des permissions d'un joueur en cache avant rafraîchissement. */
    public int permissionTtlSeconds = 180;
    /** Webhook Discord séparé pour le journal staff (désanonymisation, suppression, avertissements…). Vide = on utilise webhookUrl. */
    public String logWebhookUrl = "";
    /** Envoyer aussi les actions du staff (désanonymisation, suppression de ticket, avertissements, rangs…) sur Discord. */
    public boolean logStaffActions = true;
    /** Délai (ms) minimal entre deux messages de chat intégré d'un même joueur. */
    public int chatCooldownMs = 400;

    /** Plafond absolu : c'est la limite relevée par EntitySelectorReaderMixin. */
    public static final int MAX_NAME_HARD = 32;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("watherp.json");
    }

    public static void load() {
        Path p = path();
        try {
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    RpConfig c = GSON.fromJson(r, RpConfig.class);
                    if (c != null) INSTANCE = c;
                }
            }
            // migration : les anciennes configs avaient écrit « 16 » en dur
            if (INSTANCE.configVersion < 2) {
                if (INSTANCE.maxNameLength == 16) INSTANCE.maxNameLength = 32;
                INSTANCE.configVersion = 2;
            }
            if (INSTANCE.maxNameLength > MAX_NAME_HARD) INSTANCE.maxNameLength = MAX_NAME_HARD;
            if (INSTANCE.maxNameLength < INSTANCE.minNameLength) INSTANCE.maxNameLength = MAX_NAME_HARD;
            if (INSTANCE.helpTypes == null || INSTANCE.helpTypes.isEmpty()) INSTANCE.helpTypes = new ArrayList<>(List.of("autre"));
            if (INSTANCE.webhookUrl == null) INSTANCE.webhookUrl = "";
            if (INSTANCE.workerUrl == null) INSTANCE.workerUrl = "";
            if (INSTANCE.workerApiKey == null) INSTANCE.workerApiKey = "";
            if (INSTANCE.serverSlug == null) INSTANCE.serverSlug = "";
            if (INSTANCE.logWebhookUrl == null) INSTANCE.logWebhookUrl = "";
            if (INSTANCE.adminRoles == null || INSTANCE.adminRoles.isEmpty()) INSTANCE.adminRoles = new ArrayList<>(List.of("admin"));
            INSTANCE.heartbeatSeconds = Math.max(60, Math.min(300, INSTANCE.heartbeatSeconds));
            INSTANCE.permissionTtlSeconds = Math.max(60, Math.min(600, INSTANCE.permissionTtlSeconds));
            INSTANCE.chatCooldownMs = Math.max(100, INSTANCE.chatCooldownMs);
            // v3 : le type « role » (questions sur le rôle, ticket anonyme recommandé) est ajouté aux configs existantes
            if (INSTANCE.configVersion < 3) {
                if (!INSTANCE.helpTypes.contains("role")) INSTANCE.helpTypes.add(0, "role");
                INSTANCE.configVersion = 3;
            }
            // v5 : expiration 60 s, popup affichée 30 s (la v4 avait mis l'expiration à 30 s)
            if (INSTANCE.configVersion < 5) {
                if (INSTANCE.proposalSeconds == 30) INSTANCE.proposalSeconds = 60;
                INSTANCE.proposalPopupSeconds = 30;
                INSTANCE.configVersion = 5;
            }
            INSTANCE.proposalSeconds = Math.max(10, INSTANCE.proposalSeconds);
            INSTANCE.proposalPopupSeconds = Math.max(5, Math.min(INSTANCE.proposalPopupSeconds, INSTANCE.proposalSeconds));
        } catch (Exception e) {
            WatheRP.LOGGER.error("Impossible de lire {}", p, e);
        }
        save();
    }

    public static synchronized void save() {
        Path p = path();
        try (Writer w = Files.newBufferedWriter(p)) {
            GSON.toJson(INSTANCE, w);
        } catch (Exception e) {
            WatheRP.LOGGER.error("Impossible d'écrire {}", p, e);
        }
        // le fichier contient la clé du Worker et l'URL du webhook : droits restreints (best effort, ignoré sous Windows)
        try {
            Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
        }
    }

    /** URL du Worker utilisée par « /relink connect » quand aucune n'est précisée. */
    public static final String DEFAULT_WORKER_URL = "https://relink-auth.refugeemeraudien-direction.workers.dev";

    // ---------------------------------------------------------------- validation / affichage masqué

    private static final java.util.Set<String> DISCORD_HOSTS =
        java.util.Set.of("discord.com", "ptb.discord.com", "canary.discord.com", "discordapp.com");

    /** Accepte uniquement une vraie URL de webhook Discord (https, hôte Discord, /api/webhooks/...). */
    public static boolean isDiscordWebhook(String url) {
        try {
            java.net.URI u = java.net.URI.create(url.trim());
            return "https".equals(u.getScheme()) && u.getHost() != null
                && DISCORD_HOSTS.contains(u.getHost().toLowerCase(java.util.Locale.ROOT))
                && u.getPath() != null && u.getPath().startsWith("/api/webhooks/");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isHttpsUrl(String url) {
        try {
            java.net.URI u = java.net.URI.create(url.trim());
            return "https".equals(u.getScheme()) && u.getHost() != null && !u.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /** N'affiche jamais le token d'un webhook : seulement son id. */
    public static String maskWebhook(String url) {
        if (url == null || url.isBlank()) return "(non configuré)";
        try {
            String[] parts = java.net.URI.create(url.trim()).getPath().split("/");
            // /api/webhooks/{id}/{token}
            if (parts.length >= 4) return "…/webhooks/" + parts[3] + "/••••••••";
        } catch (Exception ignored) {
        }
        return "(configuré)";
    }

    public static String maskKey(String key) {
        if (key == null || key.isBlank()) return "(non configurée)";
        return key.length() <= 8 ? "••••" : key.substring(0, 3) + "••••••••" + key.substring(key.length() - 4);
    }
}
