package fr.watherp;

import fr.watherp.net.Net;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.*;

/**
 * Carnet de contacts : tous ceux que le joueur connaît (présentation menée au bout), avec le nom ou pseudonyme sous lequel
 * il les connaît, ce qu'ils savent de lui, les autres noms qu'ils lui ont montrés, et ses notes PRIVÉES.
 * Tout est calculé côté serveur à partir de l'état RP de la partie : un joueur ne voit jamais que ce que son personnage sait.
 */
public final class ContactBook {
    private ContactBook() {}

    public static final int NOTE_MAX = 500;

    private static String clipNote(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\r') b.append('\n');
            else if (!Character.isISOControl(cp)) b.appendCodePoint(cp);
        }
        String s = b.toString().replaceAll("\n{3,}", "\n\n").strip();
        if (s.codePointCount(0, s.length()) > NOTE_MAX) s = s.substring(0, s.offsetByCodePoints(0, NOTE_MAX));
        return s;
    }

    public static final int NICK_MAX = 32;

    /** Surnom que {@code observer} a donné à {@code target}, ou null. */
    public static String nick(UUID observer, UUID target) {
        Map<String, String> m = RpData.INSTANCE.contactNicks.get(observer.toString());
        return m == null ? null : m.get(target.toString());
    }

    /** Tous les surnoms de {@code observer} (contact -> surnom), pour l'affichage en jeu côté client. */
    public static Map<UUID, String> nicksOf(UUID observer) {
        Map<UUID, String> out = new LinkedHashMap<>();
        Map<String, String> m = RpData.INSTANCE.contactNicks.get(observer.toString());
        if (m == null) return out;
        for (Map.Entry<String, String> e : m.entrySet()) {
            try {
                out.put(UUID.fromString(e.getKey()), e.getValue());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    /**
     * Donne (ou, texte vide, retire) un surnom à une personne que {@code p} connaît.
     * @return message d'erreur, ou null si c'est enregistré
     */
    public static String setNick(ServerPlayerEntity p, UUID target, String raw) {
        MinecraftServer server = p.getServer();
        if (server == null) return "Serveur indisponible.";
        if (!RpGame.knowledgeOf(p.getUuid()).containsKey(target)) return "Vous ne connaissez pas cette personne.";
        String n = RpGame.clean(raw);
        Map<String, String> m = RpData.INSTANCE.contactNicks.computeIfAbsent(p.getUuidAsString(), k -> new LinkedHashMap<>());
        if (n.isEmpty()) {
            m.remove(target.toString());
        } else {
            if (n.length() > NICK_MAX) return "Surnom trop long (max. " + NICK_MAX + " caractères).";
            String err = RpGame.validate(server, p.getUuid(), n, false);
            if (err != null) return err;
            // pas deux fois le même surnom chez un même joueur : on ne saurait plus qui est qui
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (!e.getKey().equals(target.toString()) && RpGame.fold(e.getValue()).equals(RpGame.fold(n))) return "Vous avez déjà un contact avec ce surnom.";
            }
            m.put(target.toString(), n);
        }
        if (m.isEmpty()) RpData.INSTANCE.contactNicks.remove(p.getUuidAsString());
        RpData.save();
        return null;
    }

    private static String note(UUID observer, UUID target) {
        Map<String, String> m = RpData.INSTANCE.contactNotes.get(observer.toString());
        if (m == null) return "";
        String n = m.get(target.toString());
        return n == null ? "" : n;
    }

    /** Autres noms que {@code target} a montrés à {@code me} pendant la partie (avant son nom actuel), séparés par « , ». */
    private static String otherNames(UUID me, UUID target, String current) {
        String meS = me.toString(), tS = target.toString();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (RpData.GameEvent e : RpData.INSTANCE.gameEvents) {
            if (!tS.equals(e.actor) || !meS.equals(e.other)) continue;
            if (!e.shown.isBlank()) seen.add(e.shown);
            if ("reveal".equals(e.kind) && !e.real.isBlank()) seen.add(e.real);
        }
        String cur = RpGame.fold(current);
        StringBuilder out = new StringBuilder();
        for (String n : seen) {
            if (RpGame.fold(n).equals(cur)) continue;
            if (out.length() > 0) out.append(", ");
            out.append(n);
        }
        String s = out.toString();
        return s.length() > 290 ? s.substring(0, 289) + "…" : s;
    }

    public static List<Net.ContactEntry> build(MinecraftServer server, ServerPlayerEntity p) {
        UUID me = p.getUuid();
        List<Net.ContactEntry> out = new ArrayList<>();
        for (Map.Entry<UUID, RpGame.Known> e : RpGame.knowledgeOf(me).entrySet()) {
            UUID id = e.getKey();
            RpGame.Known k = e.getValue();
            RpGame.Known back = RpGame.knowledgeOf(id).get(me);
            out.add(new Net.ContactEntry(id, k.name(), k.alias(),
                back == null ? "" : back.name(), back != null && back.alias(),
                otherNames(me, id, k.name()), note(me, id), server.getPlayerManager().getPlayer(id) != null,
                Objects.requireNonNullElse(nick(me, id), "")));
        }
        out.sort(Comparator.comparing((Net.ContactEntry c) -> RpGame.fold(c.nick().isEmpty() ? c.name() : c.nick())));
        return out;
    }

    public static void send(ServerPlayerEntity p) {
        MinecraftServer server = p.getServer();
        if (server == null) return;
        ServerPlayNetworking.send(p, new Net.Contacts(build(server, p)));
    }

    /** Enregistre (ou efface) la note privée de {@code p} sur {@code target}. Réservé aux personnes que {@code p} connaît. */
    public static void saveNote(ServerPlayerEntity p, UUID target, String text) {
        if (!RpGame.knowledgeOf(p.getUuid()).containsKey(target)) return;
        String n = clipNote(text);
        Map<String, String> m = RpData.INSTANCE.contactNotes.computeIfAbsent(p.getUuidAsString(), k -> new LinkedHashMap<>());
        if (n.isEmpty()) m.remove(target.toString());
        else m.put(target.toString(), n);
        if (m.isEmpty()) RpData.INSTANCE.contactNotes.remove(p.getUuidAsString());
        RpData.save();
    }
}
