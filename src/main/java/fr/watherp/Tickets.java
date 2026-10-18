package fr.watherp;

import fr.watherp.net.Net;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.*;

/** Logique des tickets d'aide : création, notification, prise en charge, clôture. */
public final class Tickets {
    private Tickets() {}

    private static final Map<UUID, Long> cooldown = new HashMap<>();

    public static int staffOnline(MinecraftServer server) {
        int n = 0;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) if (Staff.isStaff(p)) n++;
        return n;
    }

    /** @return null si OK, sinon message d'erreur */
    public static String create(ServerPlayerEntity player, String type, String message) {
        return create(player, type, message, false, false);
    }

    /**
     * @param anonymous ticket anonymisé : pseudo, nom RP et position cachés aux helpers et à Discord (recommandé pour les questions de rôle)
     * @param wantChat  le joueur demande qu'un helper lui ouvre un chat privé
     * @return null si OK, sinon message d'erreur
     */
    public static String create(ServerPlayerEntity player, String type, String message, boolean anonymous, boolean wantChat) {
        MinecraftServer server = player.getServer();
        if (server == null) return "Serveur indisponible.";
        RpConfig cfg = RpConfig.INSTANCE;
        long now = System.currentTimeMillis();
        Long last = cooldown.get(player.getUuid());
        if (last != null && now - last < cfg.helpCooldownSeconds * 1000L) {
            long left = (cfg.helpCooldownSeconds * 1000L - (now - last) + 999) / 1000;
            return "Patientez encore " + left + " s avant une nouvelle demande d'aide.";
        }
        cooldown.put(player.getUuid(), now);

        Ticket t = new Ticket();
        t.id = RpData.INSTANCE.nextTicket++;
        t.playerUuid = player.getUuidAsString();
        t.playerName = player.getGameProfile().getName();
        String rp = RpGame.currentOrPrevious(player.getUuid());
        t.rpName = rp == null ? "" : rp;
        t.type = type.toLowerCase(Locale.ROOT);
        t.message = message == null ? "" : message.trim();
        t.createdMs = now;
        t.world = player.getWorld().getRegistryKey().getValue().toString();
        t.x = player.getX();
        t.y = player.getY();
        t.z = player.getZ();
        t.inGame = RpGame.isRunning(server);
        t.anonymous = anonymous;
        t.wantChat = wantChat;
        RpData.INSTANCE.tickets.add(t);
        RpData.save();

        // 1. notification à l'écran des staff actifs
        int notified = 0;
        for (ServerPlayerEntity s : server.getPlayerManager().getPlayerList()) {
            if (!Staff.isStaff(s) || Staff.isInactive(s)) continue;
            notified++;
            ServerPlayNetworking.send(s, new Net.Alert(t.id, t.type, t.anonymous ? "" : t.playerName, t.anonymous ? "" : t.rpName, t.message, t.anonymous, t.wantChat));
            s.sendMessage(staffLine(t), false);
        }
        // 2. ticket Discord
        Discord.send(t, staffOnline(server), server.getPlayerManager().getCurrentPlayerCount(), server.getMaxPlayerCount());

        player.sendMessage(Msg.ok("Demande d'aide #" + t.id + " envoyée (" + t.type + ")"
            + (anonymous ? ", anonyme" : "")
            + (wantChat ? ", chat privé demandé" : "") + "."
            + (notified == 0 ? " Aucun staff actif en ligne : l'équipe est prévenue via Discord." : "")), false);
        return null;
    }

    private static Text staffLine(Ticket t) {
        String who = t.anonymous ? "Anonyme" : t.playerName + (t.rpName.isEmpty() ? "" : " (" + t.rpName + ")");
        MutableText m = Msg.prefix()
            .append(Text.literal("Aide #" + t.id + " ").formatted(Formatting.RED, Formatting.BOLD))
            .append(Text.literal("[" + t.type + "] ").formatted(Formatting.YELLOW))
            .append(Text.literal(who).formatted(t.anonymous ? Formatting.GOLD : Formatting.WHITE))
            .append(Text.literal(t.message.isEmpty() ? "" : " : " + t.message).formatted(Formatting.GRAY))
            .append(Text.literal(" "))
            .append(Msg.button("Prendre", "/r ticket claim " + t.id, Formatting.GREEN, "Prendre en charge"));
        if (t.wantChat) {
            m.append(Text.literal(" ")).append(Msg.button("Chat privé", "/r ticket chat " + t.id, Formatting.LIGHT_PURPLE, "Le joueur demande un chat privé : l'ouvrir"));
        }
        if (!t.anonymous) {
            m.append(Text.literal(" ")).append(Msg.button("TP", "/r ticket tp " + t.id, Formatting.AQUA, "Se téléporter au joueur"));
        }
        return m;
    }

    /** Liste envoyée au panneau staff : des COPIES, où un ticket anonyme ne contient aucune donnée d'identité. */
    public static List<Ticket> visibleFor(MinecraftServer server) {
        List<Ticket> open = new ArrayList<>();
        List<Ticket> closed = new ArrayList<>();
        List<Ticket> all = RpData.INSTANCE.tickets;
        for (int i = all.size() - 1; i >= 0; i--) {
            Ticket t = all.get(i);
            if (Ticket.CLOSED.equals(t.status)) {
                if (closed.size() < 25) closed.add(t.forClient());
            } else if (open.size() < 100) {
                open.add(t.forClient());
            }
        }
        open.addAll(closed);
        return open;
    }

    public static void sendList(ServerPlayerEntity staff) {
        if (staff.getServer() == null) return;
        ServerPlayNetworking.send(staff, new Net.Tickets(visibleFor(staff.getServer())));
        ServerPlayNetworking.send(staff, new Net.Records(Records.recent(200)));
    }

    /** @return message d'erreur ou null */
    public static String act(ServerPlayerEntity staff, int id, String action) {
        if (!Staff.isStaff(staff)) return "Réservé au staff.";
        MinecraftServer server = staff.getServer();
        if (server == null) return "Serveur indisponible.";
        Ticket t = RpData.INSTANCE.ticket(id);
        if (t == null) return "Ticket #" + id + " introuvable.";
        String who = staff.getGameProfile().getName();
        ServerPlayerEntity author = server.getPlayerManager().getPlayer(t.uuid());
        switch (action) {
            case "claim" -> {
                if (Ticket.CLOSED.equals(t.status)) return "Ticket déjà clos.";
                t.status = Ticket.CLAIMED;
                t.claimedBy = who;
                if (author != null) author.sendMessage(Msg.ok("Votre demande #" + t.id + " est prise en charge par " + who + "."), false);
            }
            case "close" -> {
                t.status = Ticket.CLOSED;
                if (t.claimedBy.isEmpty()) t.claimedBy = who;
                if (author != null) author.sendMessage(Msg.info("Votre demande #" + t.id + " a été clôturée par " + who + "."), false);
            }
            case "reopen" -> {
                t.status = Ticket.OPEN;
                t.claimedBy = "";
            }
            case "delete" -> {
                RpData.INSTANCE.tickets.remove(t);
                RpData.save();
                Discord.delete(t);
                Discord.log("Ticket supprimé", "Ticket #" + t.id + " (" + t.type + ")" + (t.anonymous ? ", anonyme," : "") + " supprimé par " + who + ".", 0x95A5A6);
                return null;
            }
            case "deanon" -> {
                if (!t.anonymous) return "Ce ticket n'est pas anonyme.";
                t.anonymous = false;
                t.deanonBy = who;
                RpData.save();
                Chats.onDeanon(server, t);
                // transparence : le joueur est prévenu que son anonymat a été levé
                if (author != null) author.sendMessage(Msg.info("L'anonymat de votre demande #" + t.id + " a été levé par " + who + "."), false);
                // le journal Discord ne contient volontairement PAS l'identité du joueur
                Discord.log("Ticket désanonymisé", "Ticket #" + t.id + " (" + t.type + ") : anonymat levé par " + who + ".", 0xE67E22);
                staff.sendMessage(Msg.info("Ticket #" + t.id + " : joueur " + t.playerName
                    + (t.rpName.isEmpty() ? "" : " (" + t.rpName + ")") + "."), false);
            }
            case "chat" -> {
                UUID target = t.uuid();
                Chats.Result r = Chats.openDirect(staff, target, t.id, t.anonymous);
                if (r.error() != null) return r.error();
                t.chatId = r.chat().id;
                if (Ticket.OPEN.equals(t.status)) { // ouvrir un chat = prendre le ticket en charge
                    t.status = Ticket.CLAIMED;
                    t.claimedBy = who;
                }
            }
            case "tp" -> {
                if (t.anonymous) return "Ticket anonyme : téléportation impossible tant que l'anonymat n'est pas levé.";
                if (!RpConfig.INSTANCE.helperCanTeleport && !Staff.isOp(staff)) return "Téléportation réservée aux administrateurs.";
                if (author != null) {
                    staff.teleport(author.getServerWorld(), author.getX(), author.getY(), author.getZ(), staff.getYaw(), staff.getPitch());
                } else {
                    // auteur hors ligne (parti ?) : on va à l'endroit où il a fait sa demande
                    ServerWorld w = null;
                    Identifier wid = Identifier.tryParse(t.world);
                    if (wid != null) w = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, wid));
                    if (w == null) return t.playerName + " n'est pas connecté et le lieu de sa demande est introuvable.";
                    staff.teleport(w, t.x, t.y, t.z, staff.getYaw(), staff.getPitch());
                    staff.sendMessage(Msg.info(t.playerName + " est hors ligne : téléporté là où la demande #" + t.id + " a été faite."), true);
                }
                return null;
            }
            default -> {
                return "Action inconnue.";
            }
        }
        RpData.save();
        Discord.update(t, staffOnline(server), server.getPlayerManager().getCurrentPlayerCount(), server.getMaxPlayerCount());
        return null;
    }
}
