package fr.watherp;

import dev.doctor4t.wathe.cca.GameWorldComponent;
import fr.watherp.net.Net;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** État RP de la partie en cours : noms confirmés, présentations, propositions. */
public final class RpGame {
    private RpGame() {}

    public record Known(String name, boolean alias) {}

    public static final class Proposal {
        public final int id;
        public final UUID from, to;
        public final String fromShown;
        public final boolean fromAlias;
        public int ticksLeft;
        /** Pendant que le destinataire écrit son pseudonyme, l'expiration est suspendue (au plus 3 minutes, par sécurité). */
        public boolean paused;
        public int pausedTicks;

        Proposal(int id, UUID from, UUID to, String fromShown, boolean fromAlias, int ticks) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.fromShown = fromShown;
            this.fromAlias = fromAlias;
            this.ticksLeft = ticks;
        }
    }

    /** nom RP confirmé pour cette partie */
    private static final Map<UUID, String> names = new HashMap<>();
    /** observateur -> (cible -> nom affiché) */
    private static final Map<UUID, Map<UUID, Known>> knowledge = new HashMap<>();
    private static final Map<Integer, Proposal> proposals = new HashMap<>();
    private static final Map<UUID, Long> lastIntro = new HashMap<>();
    /** nom RP qu'avait un joueur avant un /r reset name (pour mettre à jour les présentations déjà faites) */
    private static final Map<UUID, String> oldNames = new HashMap<>();
    private static int nextProposal = 1;
    private static int promptTimer = -1;
    private static int reminderTimer = 0;

    /** Prénom RP : lettres, espace, ' . - uniquement (AUCUN chiffre), commence par une lettre. */
    private static final Pattern NAME_OK = Pattern.compile("^[\\p{L}][\\p{L} '.\\-]*$");
    /** Pseudonyme de présentation : les chiffres restent permis. */
    private static final Pattern ALIAS_OK = Pattern.compile("^[\\p{L}][\\p{L}\\p{N} '.\\-]*$");
    /** Une partie est en cours côté mod (le menu de nom RP est alors impossible à fermer avec Échap). */
    private static boolean gameActive = false;
    /** Ticks consécutifs sans partie « running » alors que gameActive est vrai (filet si l'évènement de fin de partie est manqué). */
    private static int notRunningTicks = 0;

    // ---------------------------------------------------------------- état de partie

    public static boolean isRunning(MinecraftServer server) {
        for (ServerWorld w : server.getWorlds()) {
            try {
                if (GameWorldComponent.KEY.get(w).isRunning()) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static GameWorldComponent component(ServerWorld w) {
        try {
            return GameWorldComponent.KEY.get(w);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Au démarrage du serveur : si une partie est en cours, on restaure les noms verrouillés (sinon on purge). */
    public static void onServerStarted(MinecraftServer server) {
        names.clear();
        gameActive = isRunning(server);
        if (isRunning(server)) {
            for (Map.Entry<String, String> e : RpData.INSTANCE.gameNames.entrySet()) {
                try {
                    names.put(UUID.fromString(e.getKey()), e.getValue());
                } catch (IllegalArgumentException ignored) {
                }
            }
        } else if (!RpData.INSTANCE.gameNames.isEmpty()) {
            RpData.INSTANCE.gameNames.clear();
            RpData.save();
        }
    }

    private static void clearGameNames() {
        names.clear();
        oldNames.clear();
        if (!RpData.INSTANCE.gameNames.isEmpty()) {
            RpData.INSTANCE.gameNames.clear();
            RpData.save();
        }
    }

    public static void onGameInit(MinecraftServer server) {
        gameActive = true;
        clearGameNames();
        knowledge.clear();
        proposals.clear();
        lastIntro.clear();
        promptTimer = Math.max(1, RpConfig.INSTANCE.promptDelayTicks);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) syncKnowledge(p);
    }

    public static void onGameEnd(MinecraftServer server) {
        gameActive = false;
        clearGameNames();
        knowledge.clear();
        proposals.clear();
        promptTimer = -1;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            syncKnowledge(p);
            ServerPlayNetworking.send(p, new Net.ProposalGone(-1));
            ServerPlayNetworking.send(p, new Net.CloseName()); // le menu de nom RP (non fermable avec Échap) ne doit pas rester après la partie
        }
    }

    /** Joueurs concernés par la partie (rôles Wathe) ; à défaut, tous les joueurs en survie. */
    public static List<ServerPlayerEntity> participants(MinecraftServer server) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.isSpectator() || p.isCreative()) continue;
            out.add(p);
        }
        return out;
    }

    public static void tick(MinecraftServer server) {
        boolean running = isRunning(server);
        if (running) notRunningTicks = 0;
        else if (gameActive && ++notRunningTicks > 400) gameActive = false;
        if (promptTimer > 0 && --promptTimer == 0) {
            promptTimer = -1;
            for (ServerPlayerEntity p : participants(server)) promptName(p, "");
            reminderTimer = 0;
        }
        if (running && ++reminderTimer >= 60 * 20) {
            reminderTimer = 0;
            for (ServerPlayerEntity p : participants(server)) {
                if (!names.containsKey(p.getUuid())) promptName(p, "");
            }
        }
        if (!proposals.isEmpty()) {
            Iterator<Proposal> it = proposals.values().iterator();
            while (it.hasNext()) {
                Proposal pr = it.next();
                if (pr.paused) {
                    if (++pr.pausedTicks > 3 * 60 * 20) pr.paused = false; // filet : client déconnecté pendant la saisie
                    continue;
                }
                if (--pr.ticksLeft <= 0) {
                    it.remove();
                    ServerPlayerEntity to = server.getPlayerManager().getPlayer(pr.to);
                    if (to != null) ServerPlayNetworking.send(to, new Net.ProposalGone(pr.id));
                }
            }
        }
    }

    // ---------------------------------------------------------------- noms

    public static String confirmedName(UUID id) {
        return names.get(id);
    }

    public static boolean hasConfirmed(UUID id) {
        return names.containsKey(id);
    }

    public static String previousName(UUID id) {
        return RpData.INSTANCE.lastNames.getOrDefault(id.toString(), "");
    }

    /** Nom RP "courant" : celui de la partie, sinon le dernier connu. */
    public static String currentOrPrevious(UUID id) {
        String n = names.get(id);
        return n != null ? n : RpData.INSTANCE.lastNames.get(id.toString());
    }

    /**
     * Ouvre le menu de nom RP. « Obligatoire » (Échap bloqué) seulement pendant une partie et pour un vrai participant :
     * hors partie, ou pour un spectateur / créatif, le joueur peut toujours fermer le menu.
     */
    public static void promptName(ServerPlayerEntity p, String error) {
        MinecraftServer server = p.getServer();
        boolean mandatory = server != null && (gameActive || isRunning(server)) && !p.isSpectator() && !p.isCreative();
        ServerPlayNetworking.send(p, new Net.OpenName(previousName(p.getUuid()), error == null ? "" : error, mandatory));
    }

    public static String fold(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replace('_', ' ').trim().replaceAll("\\s+", " ");
    }

    public static String clean(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    /**
     * @param checkUnique true pour un NOM RP (unique dans la partie, AUCUN chiffre autorisé), false pour un pseudonyme de présentation
     * @return message d'erreur, ou null si le nom est valide.
     */
    public static String validate(MinecraftServer server, UUID owner, String name, boolean checkUnique) {
        RpConfig c = RpConfig.INSTANCE;
        if (name.length() < c.minNameLength) return "Nom trop court (min. " + c.minNameLength + " caractères).";
        if (name.length() > c.maxNameLength) return "Nom trop long (max. " + c.maxNameLength + " caractères).";
        if (checkUnique) {
            for (int i = 0; i < name.length(); ) {
                int cp = name.codePointAt(i);
                if (Character.isDigit(cp) || Character.getType(cp) == Character.LETTER_NUMBER || Character.getType(cp) == Character.OTHER_NUMBER)
                    return "Les chiffres sont interdits dans le prénom RP.";
                i += Character.charCount(cp);
            }
            if (!NAME_OK.matcher(name).matches()) return "Caractères non autorisés (lettres, espace, ' - . ; commence par une lettre, sans chiffre).";
        } else if (!ALIAS_OK.matcher(name).matches()) {
            return "Caractères non autorisés (lettres, chiffres, espace, ' - . ; commence par une lettre).";
        }
        String f = fold(name);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!p.getUuid().equals(owner) && fold(p.getGameProfile().getName()).equals(f)) {
                return "Ce nom ressemble au pseudo d'un joueur connecté.";
            }
        }
        if (checkUnique) {
            for (Map.Entry<UUID, String> e : names.entrySet()) {
                if (!e.getKey().equals(owner) && fold(e.getValue()).equals(f)) return "Ce nom RP est déjà pris dans cette partie.";
            }
        }
        return null;
    }

    /**
     * Définit le nom RP (validé en amont), le verrouille pour la partie et le journalise dans l'historique du Worker.
     * @param source "player" (saisi) | "kept" (ancien nom conservé) | "staff" (/r forcechange)
     */
    public static void setName(MinecraftServer server, UUID id, String name, String source, String setBy) {
        String previousStored = RpData.INSTANCE.lastNames.get(id.toString());
        String old = names.put(id, name);
        String replaced = old != null ? old : oldNames.remove(id);
        RpData.INSTANCE.lastNames.put(id.toString(), name);
        RpData.INSTANCE.gameNames.put(id.toString(), name);
        RpData.save();
        RpHistory.recordName(server, id, name, replaced != null ? replaced : previousStored, source, setBy);
        // met à jour les présentations faites sous le vrai nom RP
        if (replaced != null && !replaced.equals(name)) {
            for (Map<UUID, Known> m : knowledge.values()) {
                Known k = m.get(id);
                if (k != null && !k.alias() && k.name().equals(replaced)) m.put(id, new Known(name, false));
            }
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) syncKnowledge(p);
        } else {
            ServerPlayerEntity self = server.getPlayerManager().getPlayer(id);
            if (self != null) syncKnowledge(self);
        }
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(id);
        if (online != null) ServerPlayNetworking.send(online, new Net.CloseName());
    }

    public static void resetName(MinecraftServer server, ServerPlayerEntity target) {
        resetName(server, target.getUuid());
    }

    /** Réinitialise le nom RP d'un joueur, en ligne OU hors ligne (le menu s'ouvrira à sa reconnexion via le rappel périodique). */
    public static void resetName(MinecraftServer server, UUID id) {
        String old = names.remove(id);
        // on garde l'ancien nom : quand le joueur en choisira un nouveau, les gens à qui il s'était présenté le verront à jour
        if (old != null) oldNames.put(id, old);
        if (RpData.INSTANCE.gameNames.remove(id.toString()) != null) RpData.save();
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(id);
        if (target != null) {
            syncKnowledge(target);
            promptName(target, "");
        }
    }

    /**
     * Joueurs (en ligne ou non) ayant ce nom RP : d'abord ceux de la partie en cours, à défaut le dernier nom connu.
     * Sert à viser un joueur parti (« fuyard ») avec le seul nom que le staff voit en partie.
     */
    public static List<UUID> offlineByRpName(String token) {
        String f = fold(token);
        List<UUID> out = new ArrayList<>();
        if (f.isEmpty()) return out;
        for (Map.Entry<UUID, String> e : names.entrySet()) if (fold(e.getValue()).equals(f)) out.add(e.getKey());
        if (!out.isEmpty()) return out;
        for (Map.Entry<String, String> e : RpData.INSTANCE.lastNames.entrySet()) {
            if (!fold(e.getValue()).equals(f)) continue;
            try {
                out.add(UUID.fromString(e.getKey()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- présentations

    public static Map<UUID, Known> knowledgeOf(UUID observer) {
        return knowledge.getOrDefault(observer, Map.of());
    }

    public static void syncKnowledge(ServerPlayerEntity p) {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Known> e : knowledgeOf(p.getUuid()).entrySet()) out.put(e.getKey(), e.getValue().name());
        String own = names.getOrDefault(p.getUuid(), "");
        ServerPlayNetworking.send(p, new Net.Sync(out, own, names.containsKey(p.getUuid()), Staff.isSeeingPseudo(p), aliasedTo(p.getUuid())));
    }

    /** Vrai si A et B se sont déjà présentés l'un à l'autre (présentation menée au bout : acceptée par les deux côtés). */
    public static boolean isIntroduced(UUID a, UUID b) {
        return knowledgeOf(a).containsKey(b) || knowledgeOf(b).containsKey(a);
    }

    /** Proposition encore en attente entre ces deux joueurs (dans un sens ou dans l'autre), sinon null. */
    private static Proposal proposalBetween(UUID a, UUID b) {
        for (Proposal pr : proposals.values()) {
            if ((pr.from.equals(a) && pr.to.equals(b)) || (pr.from.equals(b) && pr.to.equals(a))) return pr;
        }
        return null;
    }

    /** Une présentation vient d'être menée au bout : les éventuelles propositions croisées devenues sans objet disparaissent. */
    private static void dropProposalsBetween(MinecraftServer server, UUID a, UUID b) {
        Iterator<Proposal> it = proposals.values().iterator();
        while (it.hasNext()) {
            Proposal pr = it.next();
            if (!((pr.from.equals(a) && pr.to.equals(b)) || (pr.from.equals(b) && pr.to.equals(a)))) continue;
            it.remove();
            ServerPlayerEntity recipient = server.getPlayerManager().getPlayer(pr.to);
            if (recipient != null) ServerPlayNetworking.send(recipient, new Net.ProposalGone(pr.id));
        }
    }

    /**
     * Personnes à qui {@code me} s'est présenté SOUS PSEUDONYME et qui ne connaissent donc pas son vrai nom RP
     * (triées par nom). Sert au menu « Révéler mon nom » du centre des notifications.
     */
    public static List<Net.AliasEntry> aliasedTo(UUID me) {
        List<Net.AliasEntry> out = new ArrayList<>();
        Map<UUID, Known> mine = knowledgeOf(me);
        for (Map.Entry<UUID, Map<UUID, Known>> e : knowledge.entrySet()) {
            Known k = e.getValue().get(me);
            if (k == null || !k.alias()) continue;
            Known back = mine.get(e.getKey());
            out.add(new Net.AliasEntry(e.getKey(), back != null ? back.name() : "?", k.name()));
        }
        out.sort(Comparator.comparing((Net.AliasEntry a) -> fold(a.targetName())));
        return out;
    }

    /** {@code me} révèle son vrai nom RP à {@code observer}, à qui il s'était présenté sous pseudonyme. */
    public static void reveal(ServerPlayerEntity me, UUID observer) {
        MinecraftServer server = me.getServer();
        if (server == null) return;
        String real = names.get(me.getUuid());
        if (real == null) {
            me.sendMessage(Msg.err("Définissez d'abord votre nom RP."), true);
            promptName(me, "");
            return;
        }
        Map<UUID, Known> theirs = knowledge.get(observer);
        Known k = theirs == null ? null : theirs.get(me.getUuid());
        if (k == null || !k.alias()) {
            me.sendMessage(Msg.err("Vous ne vous êtes pas présenté sous pseudonyme à cette personne."), true);
            syncKnowledge(me); // la liste du menu est peut-être périmée
            return;
        }
        String alias = k.name();
        Known back = knowledgeOf(me.getUuid()).get(observer);
        theirs.put(me.getUuid(), new Known(real, false));
        syncKnowledge(me);
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(observer);
        if (target != null) {
            syncKnowledge(target);
            ServerPlayNetworking.send(target, new Net.Revealed(alias, real));
            target.sendMessage(Msg.ok(alias + " vous révèle son vrai nom : " + real + "."), true);
        }
        me.sendMessage(Msg.ok("Vous avez révélé votre vrai nom (" + real + ") à " + (back != null ? back.name() : "cette personne") + "."), true);
    }

    private static void learn(UUID observer, UUID target, Known k) {
        knowledge.computeIfAbsent(observer, x -> new HashMap<>()).put(target, k);
    }

    /** A propose à B de se présenter. */
    public static void propose(ServerPlayerEntity from, UUID toId, boolean alias, String aliasText) {
        MinecraftServer server = from.getServer();
        if (server == null) return;
        if (!isRunning(server)) {
            from.sendMessage(Msg.err("Les présentations ne sont disponibles que pendant une partie."), true);
            return;
        }
        ServerPlayerEntity to = server.getPlayerManager().getPlayer(toId);
        if (to == null || to == from || to.isSpectator() || from.isSpectator()) return;
        // une présentation menée au bout (acceptée) ne se refait pas ; tant qu'elle n'a pas abouti (refusée, expirée) on peut réessayer
        if (isIntroduced(from.getUuid(), toId)) {
            from.sendMessage(Msg.err("Vous vous êtes déjà présentés l'un à l'autre."), true);
            return;
        }
        if (proposalBetween(from.getUuid(), toId) != null) {
            from.sendMessage(Msg.err("Une présentation est déjà en attente avec cette personne."), true);
            return;
        }
        if (from.getWorld() != to.getWorld() || from.squaredDistanceTo(to) > 8 * 8) {
            from.sendMessage(Msg.err("Cette personne est trop loin."), true);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastIntro.get(from.getUuid());
        if (last != null && now - last < 2000) return;
        lastIntro.put(from.getUuid(), now);

        String shown;
        if (alias) {
            shown = clean(aliasText);
            String err = validate(server, from.getUuid(), shown, false);
            if (err != null) {
                from.sendMessage(Msg.err(err), true);
                return;
            }
        } else {
            shown = names.get(from.getUuid());
            if (shown == null) {
                from.sendMessage(Msg.err("Définissez d'abord votre nom RP."), true);
                promptName(from, "");
                return;
            }
        }
        int ticks = RpConfig.INSTANCE.proposalSeconds * 20;
        Proposal pr = new Proposal(nextProposal++, from.getUuid(), toId, shown, alias, ticks);
        proposals.put(pr.id, pr);
        ServerPlayNetworking.send(to, new Net.Proposal(pr.id, from.getUuid(), shown, RpConfig.INSTANCE.proposalSeconds, RpConfig.INSTANCE.proposalPopupSeconds));
        to.sendMessage(Msg.proposal(shown, pr.id, RpConfig.INSTANCE.proposalSeconds), false);
        from.sendMessage(Msg.info("Présentation envoyée, en attente de réponse (" + RpConfig.INSTANCE.proposalSeconds + " s)."), true);
    }

    /** B répond à la proposition : la présentation devient mutuelle. */
    public static void reply(ServerPlayerEntity to, int id, boolean alias, String aliasText) {
        MinecraftServer server = to.getServer();
        if (server == null) return;
        Proposal pr = proposals.get(id);
        if (pr == null || !pr.to.equals(to.getUuid())) {
            to.sendMessage(Msg.err("Cette proposition a expiré."), true);
            return;
        }
        if (isIntroduced(to.getUuid(), pr.from)) {
            proposals.remove(id);
            ServerPlayNetworking.send(to, new Net.ProposalGone(id));
            to.sendMessage(Msg.err("Vous vous êtes déjà présentés l'un à l'autre."), true);
            return;
        }
        String shown;
        if (alias) {
            shown = clean(aliasText);
            String err = validate(server, to.getUuid(), shown, false);
            if (err != null) {
                pr.paused = false; // pseudonyme refusé : le compte à rebours reprend
                to.sendMessage(Msg.err(err), true);
                return;
            }
        } else {
            shown = names.get(to.getUuid());
            if (shown == null) {
                pr.paused = false;
                to.sendMessage(Msg.err("Définissez d'abord votre nom RP."), true);
                promptName(to, "");
                return;
            }
        }
        proposals.remove(id);
        ServerPlayNetworking.send(to, new Net.ProposalGone(id));
        learn(to.getUuid(), pr.from, new Known(pr.fromShown, pr.fromAlias));
        learn(pr.from, to.getUuid(), new Known(shown, alias));
        dropProposalsBetween(server, to.getUuid(), pr.from); // présentation menée au bout : plus de demande croisée en attente
        // historique : seuls les PSEUDONYMES sont journalisés ici (le nom RP réel l'est déjà via setName)
        if (alias) RpHistory.recordAlias(server, to.getUuid(), shown, pr.from);
        if (pr.fromAlias) RpHistory.recordAlias(server, pr.from, pr.fromShown, to.getUuid());
        syncKnowledge(to);
        ServerPlayerEntity from = server.getPlayerManager().getPlayer(pr.from);
        if (from != null) {
            syncKnowledge(from);
            from.sendMessage(Msg.ok("Vous connaissez maintenant " + shown + "."), true);
        }
        to.sendMessage(Msg.ok("Vous connaissez maintenant " + pr.fromShown + "."), true);
    }

    public static void alias(ServerPlayerEntity to, int id) {
        Proposal pr = proposals.get(id);
        if (pr == null || !pr.to.equals(to.getUuid())) {
            to.sendMessage(Msg.err("Cette proposition a expiré."), true);
            return;
        }
        pr.paused = true; // l'expiration est suspendue tant que le joueur écrit son pseudonyme
        pr.pausedTicks = 0;
        ServerPlayNetworking.send(to, new Net.OpenAlias(id));
    }

    /** Action du destinataire depuis le centre des notifications / la popup : decline | pause | resume. */
    public static void proposalAction(ServerPlayerEntity to, int id, String action) {
        MinecraftServer server = to.getServer();
        Proposal pr = proposals.get(id);
        if (server == null || pr == null || !pr.to.equals(to.getUuid())) return;
        switch (action) {
            case "pause" -> {
                pr.paused = true;
                pr.pausedTicks = 0;
            }
            case "resume" -> pr.paused = false;
            case "decline" -> {
                proposals.remove(id);
                ServerPlayNetworking.send(to, new Net.ProposalGone(id));
                ServerPlayerEntity from = server.getPlayerManager().getPlayer(pr.from);
                if (from != null) from.sendMessage(Msg.info("Votre présentation n'a pas été acceptée."), true);
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- résolution de noms (commandes)

    /**
     * Retrouve un joueur par nom RP / pseudonyme.
     * Un joueur normal ne peut désigner que des personnes qu'il connaît (ou lui-même) ;
     * le staff et la console peuvent utiliser n'importe quel nom RP.
     */
    public static ServerPlayerEntity resolve(MinecraftServer server, ServerPlayerEntity self, boolean privileged, String token) {
        String f = fold(token);
        if (f.isEmpty()) return null;
        if (self != null) {
            String own = currentOrPrevious(self.getUuid());
            if (own != null && fold(own).equals(f)) return self;
            for (Map.Entry<UUID, Known> e : knowledgeOf(self.getUuid()).entrySet()) {
                if (fold(e.getValue().name()).equals(f)) {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
                    if (p != null) return p;
                }
            }
        }
        if (privileged) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                String n = currentOrPrevious(p.getUuid());
                if (n != null && fold(n).equals(f)) return p;
            }
            for (Map<UUID, Known> m : knowledge.values()) {
                for (Map.Entry<UUID, Known> e : m.entrySet()) {
                    if (fold(e.getValue().name()).equals(f)) {
                        ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
                        if (p != null) return p;
                    }
                }
            }
        }
        return null;
    }
}
