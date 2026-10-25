package fr.watherp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.watherp.net.Net;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Chat intégré : conversations privées entre un helper et un joueur, groupes, et chat des helpers.
 *
 * - Le serveur fait foi : l'appartenance, la fermeture et l'anonymat sont vérifiés ici, jamais côté client.
 * - Un joueur voit ses chats tant qu'ils ne sont pas fermés par le staff, et peut les rouvrir quand il veut (touche Messagerie).
 * - Anonymat : pour un chat lié à un ticket anonyme, l'auteur apparaît comme « Joueur anonyme » aux autres membres
 *   jusqu'à la désanonymisation du ticket (le libellé est calculé à l'envoi, l'historique se « dévoile » donc aussi).
 * - Persistance : config/watherp-chats.json (écrit au plus toutes les 10 s, et à l'arrêt).
 */
public final class Chats {
    private Chats() {}

    public static final String DIRECT = "direct", GROUP = "group", HELPER = "helper";
    /** Le chat des helpers est unique, d'identifiant fixe. */
    public static final int HELPER_ID = 1;
    public static final String ANON_LABEL = "Joueur anonyme";
    public static final int MAX_LINES = 300, HISTORY_SENT = 100, TEXT_MAX = 256, TITLE_MAX = 32;

    public static class Line {
        public long ms;
        public String uuid = "";
        public String name = "";
        public String text = "";
        public boolean system;
        public boolean staff;
    }

    public static class Chat {
        public int id;
        public String kind = DIRECT;
        public String title = "";
        public String createdBy = "";
        public long createdMs;
        public boolean closed;
        public String closedBy = "";
        public int ticketId;
        public Set<String> members = new LinkedHashSet<>();
        /** Membres dont l'identité est masquée aux autres. */
        public Set<String> anon = new LinkedHashSet<>();
        /** Membres hors ligne à prévenir (popup) à leur prochaine connexion. */
        public Set<String> pendingInvite = new LinkedHashSet<>();
        /** Membres qui ont ARCHIVÉ ce chat : consultation seule pour eux, sans popup (les autres membres ne sont pas touchés). */
        public Set<String> archived = new LinkedHashSet<>();
        public List<Line> lines = new ArrayList<>();
        public long lastMs;
    }

    private static final class Store {
        int nextId = HELPER_ID + 1;
        List<Chat> chats = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Store store = new Store();
    private static boolean dirty = false;
    private static int saveTimer = 0;
    private static final Map<UUID, Long> lastSend = new HashMap<>();

    // ---------------------------------------------------------------- persistance

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("watherp-chats.json");
    }

    public static void load() {
        Path p = path();
        try {
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    Store s = GSON.fromJson(r, Store.class);
                    if (s != null) store = s;
                }
            }
        } catch (Exception e) {
            WatheRP.LOGGER.error("Impossible de lire {}", p, e);
        }
        if (store.chats == null) store.chats = new ArrayList<>();
        for (Chat c : store.chats) {
            if (c.members == null) c.members = new LinkedHashSet<>();
            if (c.anon == null) c.anon = new LinkedHashSet<>();
            if (c.pendingInvite == null) c.pendingInvite = new LinkedHashSet<>();
            if (c.archived == null) c.archived = new LinkedHashSet<>();
            if (c.lines == null) c.lines = new ArrayList<>();
            if (c.kind == null) c.kind = DIRECT;
        }
        if (store.nextId <= HELPER_ID) store.nextId = HELPER_ID + 1;
        if (get(HELPER_ID) == null) {
            Chat h = new Chat();
            h.id = HELPER_ID;
            h.kind = HELPER;
            h.title = "Chat helper";
            h.createdMs = System.currentTimeMillis();
            store.chats.add(h);
            dirty = true;
        }
    }

    public static void tick() {
        if (!dirty || ++saveTimer < 200) return;
        saveTimer = 0;
        saveNow();
    }

    public static synchronized void saveNow() {
        if (!dirty) return;
        dirty = false;
        Path p = path();
        try {
            // on garde les 200 derniers chats fermés au plus
            int closed = 0;
            for (int i = store.chats.size() - 1; i >= 0; i--) {
                Chat c = store.chats.get(i);
                if (c.closed && ++closed > 200) store.chats.remove(i);
            }
            try (Writer w = Files.newBufferedWriter(p)) {
                GSON.toJson(store, w);
            }
        } catch (Exception e) {
            WatheRP.LOGGER.error("Impossible d'écrire {}", p, e);
        }
    }

    // ---------------------------------------------------------------- accès

    public static Chat get(int id) {
        for (Chat c : store.chats) if (c.id == id) return c;
        return null;
    }

    public static boolean isMember(Chat c, ServerPlayerEntity p) {
        if (HELPER.equals(c.kind)) return Staff.isStaff(p);
        return c.members.contains(p.getUuidAsString());
    }

    /** Un joueur ne voit pas un chat fermé ; un staff membre le garde en lecture seule. */
    public static boolean canSee(Chat c, ServerPlayerEntity p) {
        if (!isMember(c, p)) return false;
        return !c.closed || Staff.isStaff(p);
    }

    private static String usernameOf(String uuid) {
        return RpData.INSTANCE.usernames.getOrDefault(uuid, "?");
    }

    /** Libellé d'un membre tel que les AUTRES le voient. */
    public static String label(Chat c, String uuid) {
        if (c.anon.contains(uuid)) return ANON_LABEL;
        return usernameOf(uuid);
    }

    private static String titleFor(Chat c, ServerPlayerEntity viewer) {
        if (HELPER.equals(c.kind)) return "Chat helper";
        if (GROUP.equals(c.kind)) return c.title.isEmpty() ? "Groupe #" + c.id : c.title;
        StringBuilder sb = new StringBuilder();
        for (String m : c.members) {
            if (m.equals(viewer.getUuidAsString())) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(label(c, m));
        }
        String t = sb.length() == 0 ? "Chat privé" : sb.toString();
        if (c.ticketId > 0) t += " (#" + c.ticketId + ")";
        return t.length() > 90 ? t.substring(0, 89) + "…" : t;
    }

    private static Net.ChatSummary summary(Chat c, ServerPlayerEntity viewer) {
        List<String> members = new ArrayList<>();
        if (!HELPER.equals(c.kind)) {
            for (String m : c.members) {
                // un membre masqué reste « Joueur anonyme » ; soi-même on se voit toujours
                members.add(m.equals(viewer.getUuidAsString()) ? usernameOf(m) + " (vous)" : label(c, m));
            }
        }
        return new Net.ChatSummary(c.id, c.kind, titleFor(c, viewer), c.closed, c.ticketId, members, c.lines.size(), c.archived.contains(viewer.getUuidAsString()));
    }

    private static Net.ChatLineData lineData(Chat c, Line l, ServerPlayerEntity viewer) {
        if (l.system) return new Net.ChatLineData(l.ms, "", l.text, true, false, false);
        boolean mine = l.uuid.equals(viewer.getUuidAsString());
        String lab = label(c, l.uuid);
        if (lab.equals("?") && !l.name.isEmpty()) lab = l.name;
        return new Net.ChatLineData(l.ms, mine ? "Moi" : lab, l.text, false, l.staff, mine);
    }

    // ---------------------------------------------------------------- synchronisation

    public static void syncTo(ServerPlayerEntity p) {
        boolean staff = Staff.isStaff(p);
        List<Chat> visible = new ArrayList<>();
        int closedStaff = 0;
        for (Chat c : store.chats) {
            if (!canSee(c, p)) continue;
            if (c.closed && staff && ++closedStaff > 15) continue; // limite de l'archive en lecture seule
            visible.add(c);
        }
        String me = p.getUuidAsString();
        visible.sort((a, b) -> {
            if (a.id == HELPER_ID) return -1;
            if (b.id == HELPER_ID) return 1;
            boolean ia = a.closed || a.archived.contains(me), ib = b.closed || b.archived.contains(me);
            if (ia != ib) return ia ? 1 : -1;
            return Long.compare(Math.max(b.lastMs, b.createdMs), Math.max(a.lastMs, a.createdMs));
        });
        List<Net.ChatSummary> out = new ArrayList<>();
        for (Chat c : visible) out.add(summary(c, p));
        ServerPlayNetworking.send(p, new Net.ChatSync(out));
    }

    /** Renvoie la liste à tous les membres en ligne de ce chat. */
    private static void syncMembers(MinecraftServer server, Chat c) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (isMember(c, p)) syncTo(p);
        }
    }

    public static void sendHistory(ServerPlayerEntity p, int id) {
        Chat c = get(id);
        if (c == null || !canSee(c, p)) return;
        List<Net.ChatLineData> out = new ArrayList<>();
        int from = Math.max(0, c.lines.size() - HISTORY_SENT);
        for (int i = from; i < c.lines.size(); i++) out.add(lineData(c, c.lines.get(i), p));
        ServerPlayNetworking.send(p, new Net.ChatHistory(id, out));
    }

    /** À la connexion : liste des chats + popup pour ceux ouverts pendant l'absence. */
    public static void onJoin(ServerPlayerEntity p) {
        syncTo(p);
        String me = p.getUuidAsString();
        boolean changed = false;
        for (Chat c : store.chats) {
            if (c.closed || !c.pendingInvite.remove(me)) continue;
            changed = true;
            ServerPlayNetworking.send(p, new Net.ChatInvite(c.id, titleFor(c, p), c.createdBy, "Un chat vous attend"));
        }
        if (changed) dirty = true;
    }

    public static void onDisconnect(ServerPlayerEntity p) {
        lastSend.remove(p.getUuid());
    }

    // ---------------------------------------------------------------- lignes

    private static String clean(String raw, int max) {
        if (raw == null) return "";
        String t = raw.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").replace('\n', ' ').trim().replaceAll("\\s+", " ");
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static Line add(Chat c, ServerPlayerEntity sender, String text, boolean system) {
        Line l = new Line();
        l.ms = System.currentTimeMillis();
        l.text = text;
        l.system = system;
        if (!system && sender != null) {
            l.uuid = sender.getUuidAsString();
            l.name = sender.getGameProfile().getName();
            l.staff = Staff.isStaff(sender);
        }
        c.lines.add(l);
        while (c.lines.size() > MAX_LINES) c.lines.remove(0);
        c.lastMs = l.ms;
        dirty = true;
        return l;
    }

    private static void push(MinecraftServer server, Chat c, Line l) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!canSee(c, p)) continue;
            ServerPlayNetworking.send(p, new Net.ChatPush(c.id, lineData(c, l, p)));
        }
    }

    private static void systemLine(MinecraftServer server, Chat c, String text) {
        push(server, c, add(c, null, text, true));
    }

    /** @return message d'erreur ou null */
    public static String send(ServerPlayerEntity p, int id, String raw) {
        MinecraftServer server = p.getServer();
        if (server == null) return "Serveur indisponible.";
        Chat c = get(id);
        if (c == null || !canSee(c, p)) return "Ce chat n'existe plus.";
        if (c.closed) return "Ce chat a été fermé par le staff.";
        if (c.archived.contains(p.getUuidAsString())) return "Chat archivé : désarchivez-le pour y écrire.";
        String text = clean(raw, TEXT_MAX);
        if (text.isEmpty()) return null;
        long now = System.currentTimeMillis();
        Long last = lastSend.get(p.getUuid());
        if (last != null && now - last < RpConfig.INSTANCE.chatCooldownMs) return "Doucement : attendez un instant entre deux messages.";
        lastSend.put(p.getUuid(), now);
        push(server, c, add(c, p, text, false));
        return null;
    }

    // ---------------------------------------------------------------- création / gestion

    public record Result(String error, Chat chat) {
        static Result err(String m) {
            return new Result(m, null);
        }
    }

    private static boolean isKnown(MinecraftServer server, UUID id) {
        return server.getPlayerManager().getPlayer(id) != null || RpData.INSTANCE.usernames.containsKey(id.toString());
    }

    private static void invite(MinecraftServer server, Chat c, UUID target, String by, String reason) {
        if (c.archived.remove(target.toString())) dirty = true; // réinvité : le chat sort des archives
        ServerPlayerEntity t = server.getPlayerManager().getPlayer(target);
        if (t != null) {
            ServerPlayNetworking.send(t, new Net.ChatInvite(c.id, titleFor(c, t), by, reason));
            syncTo(t);
        } else {
            c.pendingInvite.add(target.toString());
            dirty = true;
        }
    }

    /**
     * Helper : ouvre (ou rouvre) un chat direct avec un joueur, en ligne ou non (il sera prévenu à sa connexion).
     * @param ticketId  ticket à l'origine du chat (0 si aucun)
     * @param anonymous le joueur reste « Joueur anonyme » pour les helpers (ticket anonyme)
     */
    public static Result openDirect(ServerPlayerEntity staff, UUID target, int ticketId, boolean anonymous) {
        MinecraftServer server = staff.getServer();
        if (server == null) return Result.err("Serveur indisponible.");
        if (!Staff.isStaff(staff)) return Result.err("Réservé au staff.");
        if (target.equals(staff.getUuid())) return Result.err("Vous ne pouvez pas ouvrir un chat avec vous-même.");
        if (!isKnown(server, target)) return Result.err("Joueur inconnu.");
        String me = staff.getUuidAsString(), him = target.toString();

        Chat found = null;
        for (Chat c : store.chats) {
            if (!DIRECT.equals(c.kind) || c.closed || c.ticketId != ticketId) continue;
            if (c.members.size() == 2 && c.members.contains(me) && c.members.contains(him)) {
                found = c;
                break;
            }
        }
        boolean created = found == null;
        Chat c = found;
        if (found != null && found.archived.remove(me)) dirty = true;
        if (created) {
            c = new Chat();
            c.id = store.nextId++;
            c.kind = DIRECT;
            c.createdBy = staff.getGameProfile().getName();
            c.createdMs = System.currentTimeMillis();
            c.ticketId = ticketId;
            c.members.add(me);
            c.members.add(him);
            if (anonymous) c.anon.add(him);
            store.chats.add(c);
            dirty = true;
            systemLine(server, c, "Chat ouvert par " + c.createdBy + (anonymous ? " (le joueur reste anonyme)." : "."));
        }
        invite(server, c, target, staff.getGameProfile().getName(), created ? "Un helper vous ouvre un chat privé" : "Chat privé rouvert");
        syncMembers(server, c);
        ServerPlayNetworking.send(staff, new Net.ChatOpen(c.id));
        sendHistory(staff, c.id);
        if (created) {
            Discord.log("Chat privé ouvert", staff.getGameProfile().getName() + " ouvre un chat privé"
                + (ticketId > 0 ? " (ticket #" + ticketId + ")" : "")
                + (anonymous ? " avec un joueur anonyme." : " avec **" + usernameOf(him) + "**."), 0x5BC8D8);
        }
        return new Result(null, c);
    }

    /** Helper : crée un groupe de chat dont il est le premier membre. */
    public static Result createGroup(ServerPlayerEntity staff, String rawTitle) {
        MinecraftServer server = staff.getServer();
        if (server == null) return Result.err("Serveur indisponible.");
        if (!Staff.isStaff(staff)) return Result.err("Réservé au staff.");
        String title = clean(rawTitle, TITLE_MAX);
        if (title.length() < 2) return Result.err("Nom de groupe trop court (2 caractères min.).");
        int open = 0;
        for (Chat c : store.chats) if (GROUP.equals(c.kind) && !c.closed) open++;
        if (open >= 40) return Result.err("Trop de groupes ouverts : fermez-en avant d'en créer un nouveau.");
        Chat c = new Chat();
        c.id = store.nextId++;
        c.kind = GROUP;
        c.title = title;
        c.createdBy = staff.getGameProfile().getName();
        c.createdMs = System.currentTimeMillis();
        c.members.add(staff.getUuidAsString());
        store.chats.add(c);
        dirty = true;
        systemLine(server, c, "Groupe « " + title + " » créé par " + c.createdBy + ".");
        syncTo(staff);
        ServerPlayNetworking.send(staff, new Net.ChatOpen(c.id));
        sendHistory(staff, c.id);
        return new Result(null, c);
    }

    /** Helper membre du chat : ajoute un joueur ; celui-ci reçoit la popup avec la touche pour rejoindre. */
    public static String addMember(ServerPlayerEntity staff, int id, UUID target) {
        MinecraftServer server = staff.getServer();
        if (server == null) return "Serveur indisponible.";
        if (!Staff.isStaff(staff)) return "Réservé au staff.";
        Chat c = get(id);
        if (c == null || !canSee(c, staff)) return "Chat introuvable.";
        if (c.closed) return "Ce chat est fermé.";
        if (HELPER.equals(c.kind)) return "Le chat helper est réservé aux helpers.";
        if (!isKnown(server, target)) return "Joueur inconnu.";
        String t = target.toString();
        if (c.members.contains(t)) return "Déjà membre de ce chat.";
        if (c.members.size() >= 30) return "Chat complet (30 membres max.).";
        c.members.add(t);
        dirty = true;
        String by = staff.getGameProfile().getName();
        systemLine(server, c, label(c, t) + " a été ajouté par " + by + ".");
        // un direct à 3+ membres devient un échange de groupe : le titre dynamique liste les autres membres
        invite(server, c, target, by, "Vous avez été ajouté à un chat");
        syncMembers(server, c);
        return null;
    }

    /** Helper membre : ferme le chat. Il disparaît pour les joueurs ; les helpers le gardent en lecture seule. */
    public static String close(ServerPlayerEntity staff, int id) {
        MinecraftServer server = staff.getServer();
        if (server == null) return "Serveur indisponible.";
        if (!Staff.isStaff(staff)) return "Réservé au staff.";
        Chat c = get(id);
        if (c == null || !canSee(c, staff)) return "Chat introuvable.";
        if (HELPER.equals(c.kind)) return "Le chat helper ne peut pas être fermé.";
        if (c.closed) return "Déjà fermé.";
        c.closed = true;
        c.closedBy = staff.getGameProfile().getName();
        dirty = true;
        String by = c.closedBy;
        // message avant la fermeture : les joueurs le voient encore dans leur menu s'il est ouvert
        Line l = add(c, null, "Chat fermé par " + by + ".", true);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!isMember(c, p)) continue;
            if (Staff.isStaff(p)) ServerPlayNetworking.send(p, new Net.ChatPush(c.id, lineData(c, l, p)));
            else p.sendMessage(Msg.info("Le chat « " + titleFor(c, p) + " » a été fermé par le staff."), false);
        }
        syncMembers(server, c);
        return null;
    }

    /**
     * Gère SON exemplaire d'un chat (les autres membres ne sont pas touchés) :
     *  - « archive » / « unarchive » : consultation seule (on peut relire, pas écrire), plus de popup ni de non-lu ;
     *  - « delete » : on quitte la conversation, elle disparaît définitivement de sa liste (effacée du serveur s'il ne reste personne).
     * Le chat helper ne peut ni être archivé ni supprimé.
     * @return message d'erreur ou null
     */
    public static String manage(ServerPlayerEntity p, int id, String action) {
        MinecraftServer server = p.getServer();
        Chat c = get(id);
        if (server == null || c == null || !canSee(c, p)) return "Ce chat n'existe plus.";
        if (HELPER.equals(c.kind)) return "Le chat helper ne peut pas être archivé ni supprimé.";
        String me = p.getUuidAsString();
        switch (action) {
            case "archive" -> c.archived.add(me);
            case "unarchive" -> c.archived.remove(me);
            case "delete" -> {
                String who = label(c, me);
                if (!c.closed) systemLine(server, c, who + " a quitté la conversation.");
                c.members.remove(me);
                c.archived.remove(me);
                c.pendingInvite.remove(me);
                if (c.members.isEmpty()) store.chats.remove(c);
            }
            default -> {
                return "Action inconnue.";
            }
        }
        dirty = true;
        syncTo(p);
        return null;
    }

    /** Le ticket lié vient d'être désanonymisé : le joueur n'est plus masqué dans son chat. */
    public static void onDeanon(MinecraftServer server, Ticket t) {
        if (t.chatId <= 0) return;
        Chat c = get(t.chatId);
        if (c == null) return;
        if (c.anon.remove(t.playerUuid)) {
            dirty = true;
            if (!c.closed) systemLine(server, c, "L'anonymat du ticket #" + t.id + " a été levé.");
            syncMembers(server, c);
        }
    }

    /** Joueurs en ligne pour les sélecteurs (helper uniquement). */
    public static List<Net.PlayerEntry> onlinePlayers(MinecraftServer server, UUID except) {
        List<Net.PlayerEntry> out = new ArrayList<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.getUuid().equals(except)) continue;
            String rp = RpGame.currentOrPrevious(p.getUuid());
            out.add(new Net.PlayerEntry(p.getUuid(), p.getGameProfile().getName(), rp == null ? "" : rp));
        }
        out.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return out;
    }
}
