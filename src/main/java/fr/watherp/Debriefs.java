package fr.watherp;

import fr.watherp.net.Net;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Débrief de fin de partie : vrais noms, pseudonymes utilisés, qui a menti à qui, qui s'est révélé.
 * Tout vient de l'état RP de la partie (noms confirmés + « qui connaît qui » + journal des présentations) :
 * {@link #build} doit donc être appelé AVANT que RpGame n'efface l'état de la partie.
 */
public final class Debriefs {
    private Debriefs() {}

    private static String realName(UUID id) {
        String n = RpGame.namesView().get(id);
        if (n != null) return n;
        n = RpData.INSTANCE.lastNames.get(id.toString());
        return n == null || n.isBlank() ? "?" : n;
    }

    private static String mcName(MinecraftServer server, UUID id) {
        if (server != null) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p != null) return p.getGameProfile().getName();
        }
        String n = RpData.INSTANCE.usernames.get(id.toString());
        return n == null || n.isBlank() ? id.toString().substring(0, 8) : n;
    }

    private static Net.DbRef ref(MinecraftServer server, UUID id) {
        return new Net.DbRef(id, realName(id), mcName(server, id));
    }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static int minutes(long ms, long start) {
        return (int) Math.max(0, (ms - start) / 60000L);
    }

    /** Construit le débrief de la partie en cours ; null s'il n'y a rien à raconter (aucun joueur identifié). */
    public static Net.Debrief build(MinecraftServer server) {
        List<RpData.GameEvent> events = new ArrayList<>(RpData.INSTANCE.gameEvents);
        Map<UUID, String> names = RpGame.namesView();

        LinkedHashSet<UUID> who = new LinkedHashSet<>(names.keySet());
        for (RpData.GameEvent e : events) {
            UUID a = parse(e.actor), b = parse(e.other);
            if (a != null) who.add(a);
            if (b != null) who.add(b);
        }
        if (who.isEmpty()) return null;

        long start = RpData.INSTANCE.gameStartMs;
        if (start <= 0) start = events.isEmpty() ? System.currentTimeMillis() : events.get(0).ms;
        int totalMin = minutes(System.currentTimeMillis(), start);

        // ---- mensonges : présentations sous pseudonyme
        List<Net.DbLie> lies = new ArrayList<>();
        List<Net.DbReveal> reveals = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            RpData.GameEvent e = events.get(i);
            UUID actor = parse(e.actor), other = parse(e.other);
            if (actor == null || other == null) continue;
            if ("reveal".equals(e.kind)) {
                reveals.add(new Net.DbReveal(ref(server, actor), ref(server, other), e.shown, minutes(e.ms, start)));
                continue;
            }
            if (!"intro".equals(e.kind) || !e.alias) continue;
            String real = e.real.isBlank() ? realName(actor) : e.real;
            String f = RpGame.fold(e.shown);
            if (f.equals(RpGame.fold(real))) continue; // le « pseudonyme » est en fait son vrai nom : pas un mensonge
            int revealedAt = -1;
            for (int j = i + 1; j < events.size(); j++) {
                RpData.GameEvent r = events.get(j);
                if ("reveal".equals(r.kind) && r.actor.equals(e.actor) && r.other.equals(e.other)) {
                    revealedAt = minutes(r.ms, start);
                    break;
                }
            }
            String note = "";
            for (UUID u : who) {
                if (!u.equals(actor) && f.equals(RpGame.fold(realName(u)))) {
                    note = "a pris le nom de " + realName(u);
                    break;
                }
            }
            lies.add(new Net.DbLie(ref(server, actor), ref(server, other), e.shown, minutes(e.ms, start), revealedAt, note));
        }

        // ---- fiches joueurs
        Map<UUID, Map<UUID, RpGame.Known>> know = RpGame.knowledgeView();
        List<Net.DbPlayer> players = new ArrayList<>();
        for (UUID id : who) {
            int real = 0, alias = 0, rev = 0;
            for (RpData.GameEvent e : events) {
                if (!id.toString().equals(e.actor)) continue;
                if ("reveal".equals(e.kind)) rev++;
                else if (e.alias) alias++;
                else real++;
            }
            players.add(new Net.DbPlayer(ref(server, id), real, alias, rev, know.getOrDefault(id, Map.of()).size()));
        }
        players.sort(Comparator.comparing((Net.DbPlayer p) -> RpGame.fold(p.p().rp())));
        return new Net.Debrief(totalMin, players, lies, reveals, false);
    }

    // ---------------------------------------------------------------- Discord

    private static String d(Net.DbRef r) {
        return "**" + Discord.esc(r.mc()) + "** (*" + Discord.esc(r.rp()) + "*)";
    }

    private static String t(int min) {
        return "t+" + min + " min";
    }

    /** Envoie le débrief sur Discord (embeds sans mention, espacés pour rester sous la limite d'envoi du webhook). */
    public static void postToDiscord(Net.Debrief deb) {
        RpConfig c = RpConfig.INSTANCE;
        if (!c.postDebriefToDiscord) return;
        String url = Discord.debriefUrl();
        if (url == null) return;
        String user = c.serverName + " · Débrief";

        int introsReal = 0, introsAlias = 0;
        for (Net.DbPlayer p : deb.players()) {
            introsReal += p.realIntros();
            introsAlias += p.aliasIntros();
        }

        List<String> sum = new ArrayList<>();
        sum.add("⏱️ **" + deb.minutes() + " min** · **" + deb.players().size() + "** joueurs · **" + (introsReal + introsAlias)
            + "** présentations (dont **" + introsAlias + "** sous pseudonyme) · **" + deb.reveals().size() + "** révélations");
        sum.add("");
        sum.add("**Vrais noms**");
        for (Net.DbPlayer p : deb.players()) {
            sum.add("• " + d(p.p()) + " — " + p.realIntros() + " vrai(s) nom(s) donné(s), " + p.aliasIntros() + " pseudonyme(s), "
                + p.reveals() + " révélation(s), connaît " + p.contacts() + " personne(s)");
        }

        List<String> lies = new ArrayList<>();
        if (deb.lies().isEmpty()) lies.add("Personne n'a utilisé de pseudonyme.");
        for (Net.DbLie l : deb.lies()) {
            lies.add("• " + d(l.liar()) + " → " + d(l.victim()) + " : « " + Discord.esc(l.claimed()) + " » (" + t(l.atMin()) + ") — "
                + (l.revealedAtMin() >= 0 ? "révélé à " + t(l.revealedAtMin()) : "**jamais révélé**")
                + (l.note().isEmpty() ? "" : " ⚠️ " + Discord.esc(l.note())));
        }

        List<String> rev = new ArrayList<>();
        if (deb.reveals().isEmpty()) rev.add("Personne ne s'est révélé.");
        for (Net.DbReveal r : deb.reveals()) {
            rev.add("• " + d(r.who()) + " a révélé son vrai nom à " + d(r.to()) + " (connu sous « " + Discord.esc(r.alias()) + " ») — " + t(r.atMin()));
        }

        later(0, () -> Discord.postLines(url, user, "🏁 Débrief de la partie", sum, 0xF1C40F, 2));
        later(900, () -> Discord.postLines(url, user, "🎭 Qui a menti à qui", lies, 0xE74C3C, 2));
        later(1800, () -> Discord.postLines(url, user, "🔓 Révélations de nom", rev, 0x2ECC71, 2));
    }

    private static void later(int ms, Runnable r) {
        if (ms <= 0) r.run();
        else CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS).execute(r);
    }

    /** Le débrief est envoyé à un joueur qui le redemande (/rpdebrief). */
    public static boolean sendLast(ServerPlayerEntity p) {
        Net.Debrief d = RpGame.lastDebrief;
        if (d == null) return false;
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, d.withAuto(false));
        return true;
    }
}
