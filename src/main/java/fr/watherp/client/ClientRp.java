package fr.watherp.client;

import dev.doctor4t.wathe.cca.GameWorldComponent;
import fr.watherp.StaffRecord;
import fr.watherp.Ticket;
import fr.watherp.net.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.*;

/** État côté client : noms connus, proposition en cours, alertes staff, tickets. */
public final class ClientRp {
    private ClientRp() {}

    public static Map<UUID, String> known = new HashMap<>();
    public static String own = "";
    public static boolean confirmed = false;
    /** Staff ayant activé /r voirpseudo (envoyé par le serveur) : voit les vrais pseudos en partie. */
    public static boolean seePseudo = false;
    /** Personnes à qui je me suis présenté sous pseudonyme (je peux leur révéler mon vrai nom : menu du centre des notifications). */
    public static List<Net.AliasEntry> aliased = new ArrayList<>();
    /** Faux tant que la première synchro de la session n'est pas arrivée (évite de notifier les présentations déjà faites à la reconnexion). */
    private static boolean aliasSynced = false;

    /** Proposition de présentation reçue : expire à endMs, popup visible jusqu'à popupEndMs, suspendue pendant la saisie d'un pseudonyme. */
    public static final class Prop {
        public final Net.Proposal p;
        public long endMs, popupEndMs, pausedAt;
        public final long popupTotalMs;

        Prop(Net.Proposal p, long now) {
            this.p = p;
            this.endMs = now + p.seconds() * 1000L;
            this.popupTotalMs = Math.min(p.popupSeconds(), p.seconds()) * 1000L;
            this.popupEndMs = now + popupTotalMs;
        }

        public boolean paused() {
            return pausedAt != 0;
        }

        public boolean expired(long now) {
            return !paused() && now > endMs;
        }
    }

    public static final List<Prop> props = new ArrayList<>();

    public record AlertCard(Net.Alert alert, long endMs) {}

    public static final List<AlertCard> alerts = new ArrayList<>();

    /** Carnet de contacts (renvoyé par le serveur à l'ouverture du carnet). */
    public static List<Net.ContactEntry> contacts = new ArrayList<>();
    /** Débrief de la dernière partie (envoyé à la fin de la partie ou à la demande). */
    public static Net.Debrief debrief = null;

    public static List<Ticket> tickets = new ArrayList<>();
    public static List<StaffRecord> records = new ArrayList<>();
    public static boolean wantPanel = false;

    /** Outils helper disponibles (envoyé par le serveur, qui revérifie chaque action). */
    public static boolean helper = false;

    // ---- chat intégré
    public static List<Net.ChatSummary> chats = new ArrayList<>();
    public static final Map<Integer, List<Net.ChatLineData>> history = new HashMap<>();
    public static final Map<Integer, Integer> unread = new HashMap<>();
    /** Chat affiché dans le menu de chat (pour ne pas compter ses messages comme non lus). */
    public static int viewingChat = -1;
    /** Action à exécuter quand la liste de joueurs demandée arrive (sélecteur). */
    public static java.util.function.Consumer<List<Net.PlayerEntry>> pendingPlayers = null;

    /** Popup de chat (invitation ou nouveau message) affichée dans le HUD avec la touche pour ouvrir. */
    public static final class Toast {
        public final int chat;
        public final String title, line1, line2;
        public final long endMs;
        public final boolean invite;

        Toast(int chat, String title, String line1, String line2, long endMs, boolean invite) {
            this.chat = chat;
            this.title = title;
            this.line1 = line1;
            this.line2 = line2;
            this.endMs = endMs;
            this.invite = invite;
        }
    }

    public static final List<Toast> toasts = new ArrayList<>();

    // ---- préférences de notifications (sauvegardées côté client)
    /** Popups des chats (invitations, nouveaux messages). Désactivées : rien à l'écran, tout reste dans le centre des notifications. */
    public static boolean notifChat = true;
    /** Alertes d'aide des staff (nouveaux tickets). */
    public static boolean notifStaff = true;
    /** Nombre max de popups visibles en même temps ; les plus anciennes sont retirées. */
    public static final int MAX_TOASTS = 3;
    private static boolean prefsLoaded = false;

    /** Entrée du centre des notifications. kind : chat | invite | staff. */
    public record Notif(String kind, int chat, String title, String text, long ms) {}

    public static final List<Notif> notifs = new ArrayList<>();
    public static int unreadNotifs = 0;

    public static void addNotif(String kind, int chat, String title, String text) {
        notifs.add(0, new Notif(kind, chat, title, text, System.currentTimeMillis()));
        while (notifs.size() > 50) notifs.remove(notifs.size() - 1);
        unreadNotifs++;
    }

    private static java.nio.file.Path prefsPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("watherp-client.properties");
    }

    public static void loadPrefs() {
        if (prefsLoaded) return;
        prefsLoaded = true;
        try {
            java.nio.file.Path p = prefsPath();
            if (!java.nio.file.Files.exists(p)) return;
            java.util.Properties pr = new java.util.Properties();
            try (java.io.Reader r = java.nio.file.Files.newBufferedReader(p)) {
                pr.load(r);
            }
            notifChat = !"false".equals(pr.getProperty("notifChat"));
            notifStaff = !"false".equals(pr.getProperty("notifStaff"));
        } catch (Exception ignored) {
        }
    }

    public static void savePrefs() {
        try {
            java.util.Properties pr = new java.util.Properties();
            pr.setProperty("notifChat", String.valueOf(notifChat));
            pr.setProperty("notifStaff", String.valueOf(notifStaff));
            try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(prefsPath())) {
                pr.store(w, "Wathe RP - preferences client");
            }
        } catch (Exception ignored) {
        }
    }

    private static void addToast(Toast t) {
        toasts.add(t);
        while (toasts.size() > MAX_TOASTS) toasts.remove(0); // les plus anciennes disparaissent
    }
    public static void onChatInvite(Net.ChatInvite i) {
        addNotif("invite", i.chat(), i.title(), i.reason() + (i.by().isEmpty() ? "" : " (" + i.by() + ")"));
        if (!notifChat) return;
        toasts.removeIf(t -> t.chat == i.chat());
        addToast(new Toast(i.chat(), i.title(), i.reason(), i.by().isEmpty() ? "" : "par " + i.by(), System.currentTimeMillis() + 20_000L, true));
        playPing(1.4f);
    }

    public static void onChatPush(Net.ChatPush p) {
        history.computeIfAbsent(p.chat(), k -> new ArrayList<>()).add(p.line());
        List<Net.ChatLineData> h = history.get(p.chat());
        while (h.size() > 300) h.remove(0);
        if (p.line().mine() || p.line().system()) return;
        boolean inMenu = MinecraftClient.getInstance().currentScreen instanceof ChatsScreen;
        if (inMenu && viewingChat == p.chat()) return; // déjà sous les yeux
        unread.merge(p.chat(), 1, Integer::sum);
        String title = "Message";
        for (Net.ChatSummary c : chats) if (c.id() == p.chat()) title = c.title();
        String line = p.line().label() + " : " + p.line().text();
        addNotif("chat", p.chat(), title, line);
        // pas de popup si le menu de chat est ouvert (la liste montre déjà les non-lus) ou si le joueur les a coupées
        if (inMenu || !notifChat) return;
        toasts.removeIf(t -> t.chat == p.chat() && !t.invite);
        addToast(new Toast(p.chat(), title, line, "", System.currentTimeMillis() + 6_000L, false));
        playPing(1.0f);
    }

    public static int totalUnread() {
        int n = 0;
        for (int v : unread.values()) n += v;
        return n;
    }

    /** Popup la plus récente encore affichée (la touche Messagerie ouvre directement ce chat). */
    public static Toast latestToast() {
        long now = System.currentTimeMillis();
        toasts.removeIf(t -> now > t.endMs);
        return toasts.isEmpty() ? null : toasts.get(toasts.size() - 1);
    }

    /** Historique des commandes tapées dans les menus de commande (panneau helper + menu commandes), partagé. */
    public static final List<String> cmdHistory = new ArrayList<>();
    /** Derniers messages du serveur reçus pendant que le panneau est ouvert (retour des commandes tapées dans le panneau). */
    public static final java.util.ArrayDeque<String> feedback = new java.util.ArrayDeque<>();

    public static void pushFeedback(String line) {
        for (String l : line.split("\\R")) {
            if (!l.isBlank()) feedback.add(l);
        }
        while (feedback.size() > 12) feedback.removeFirst();
    }

    public static boolean gameRunning() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return false;
        try {
            return GameWorldComponent.KEY.get(mc.world).isRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Vrai si ce joueur est affiché comme un inconnu : en partie, pas présenté, et on n'a pas /r voirpseudo. */
    public static boolean isHidden(PlayerEntity target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || target == mc.player || !gameRunning()) return false;
        return !seePseudo && !known.containsKey(target.getUuid());
    }

    /**
     * Nom affiché pour un autre joueur, ou null pour laisser Minecraft/Wathe faire.
     * - présenté : son nom RP / pseudonyme ;
     * - pas présenté : RIEN (texte vide) ;
     * - staff avec /r voirpseudo : vrai pseudo (+ nom RP s'il est connu).
     */
    public static Text displayName(PlayerEntity target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || target == mc.player || !gameRunning()) return null;
        String rp = known.get(target.getUuid());
        if (seePseudo) {
            String mcName = target.getGameProfile().getName();
            return Text.literal(rp != null ? rp + " (" + mcName + ")" : mcName);
        }
        if (rp != null) return Text.literal(rp);
        return Text.empty();
    }

    public static void onProposal(Net.Proposal p) {
        long now = System.currentTimeMillis();
        props.removeIf(x -> x.p.id() == p.id());
        props.add(new Prop(p, now));
        // la demande reste consultable (et réponse possible) dans le centre des notifications jusqu'à son expiration
        addNotif("proposal", p.id(), p.fromName() + " souhaite se présenter", "Accepter · Sous pseudonyme · Refuser");
        playPing(1.2f);
    }

    /** Nouvelle liste des pseudonymes révélables : une notification invite à révéler son vrai nom pour chaque nouvelle entrée. */
    public static void onAliasList(List<Net.AliasEntry> list) {
        if (aliasSynced) {
            Set<UUID> before = new HashSet<>();
            for (Net.AliasEntry e : aliased) before.add(e.target());
            for (Net.AliasEntry e : list) {
                if (!before.contains(e.target())) {
                    addNotif("reveal", 0, "Présenté à " + e.targetName() + " sous « " + e.alias() + " »", "Clic : révéler votre vrai nom");
                }
            }
        }
        aliasSynced = true;
        aliased = new ArrayList<>(list);
    }

    /** Quelqu'un à qui je m'étais présenté sous pseudonyme m'a révélé son vrai nom (je viens de l'apprendre). */
    public static void onRevealed(Net.Revealed r) {
        addNotif("revealed", 0, r.alias() + " vous révèle son vrai nom", "C'était : " + r.realName());
        playPing(1.3f);
    }

    public static void onProposalGone(int id) {
        if (id == -1) props.clear();
        else removeProposal(id);
    }

    public static void removeProposal(int id) {
        props.removeIf(x -> x.p.id() == id);
    }

    /** Proposition encore valide (non expirée) pour cet id, sinon null. */
    public static Prop prop(int id) {
        long now = System.currentTimeMillis();
        props.removeIf(x -> x.expired(now));
        for (Prop x : props) if (x.p.id() == id) return x;
        return null;
    }

    public static void pauseProp(Prop x, boolean sendToServer) {
        if (x == null || x.paused()) return;
        x.pausedAt = System.currentTimeMillis();
        if (sendToServer) net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Net.ProposalAction(x.p.id(), "pause"));
    }

    public static void resumeProp(int id) {
        for (Prop x : props) {
            if (x.p.id() == id && x.paused()) {
                long d = System.currentTimeMillis() - x.pausedAt;
                x.endMs += d;
                x.pausedAt = 0;
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Net.ProposalAction(id, "resume"));
            }
        }
    }

    public static void onAlert(Net.Alert a) {
        addNotif("staff", 0, "Aide #" + a.id() + " · " + a.type().toUpperCase(),
            (a.anonymous() ? "Anonyme" : a.player()) + (a.message().isEmpty() ? "" : " : " + a.message()));
        if (!notifStaff) return;
        alerts.add(new AlertCard(a, System.currentTimeMillis() + 8_000L));
        while (alerts.size() > MAX_TOASTS) alerts.remove(0);
        playPing(0.8f);
    }

    public static void playPing(float pitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, pitch, 1.0f));
    }

    public static Net.Proposal activeProposal() {
        long now = System.currentTimeMillis();
        props.removeIf(x -> x.expired(now));
        for (int i = props.size() - 1; i >= 0; i--) {
            Prop x = props.get(i);
            if (!x.paused() && now <= x.popupEndMs) return x.p;
        }
        return null;
    }

    public static void reset() {
        known = new HashMap<>();
        own = "";
        confirmed = false;
        seePseudo = false;
        aliased = new ArrayList<>();
        aliasSynced = false;
        contacts = new ArrayList<>();
        debrief = null;
        props.clear();
        alerts.clear();
        tickets = new ArrayList<>();
        records = new ArrayList<>();
        feedback.clear();
        helper = false;
        chats = new ArrayList<>();
        history.clear();
        unread.clear();
        toasts.clear();
        notifs.clear();
        unreadNotifs = 0;
        viewingChat = -1;
        pendingPlayers = null;
    }
}
