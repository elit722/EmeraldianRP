package fr.watherp;

import fr.watherp.net.Net;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/** Réception des paquets client -> serveur. */
public final class ServerNet {
    private ServerNet() {}

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(Net.SubmitName.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            MinecraftServer server = p.getServer();
            if (server == null) return;
            if (RpGame.hasConfirmed(p.getUuid())) {
                p.sendMessage(Msg.err("Votre nom RP est déjà défini pour cette partie. Contactez le staff pour le changer."), true);
                return;
            }
            String name = RpGame.clean(payload.name());
            String err = RpGame.validate(server, p.getUuid(), name, true);
            if (err != null) {
                RpGame.promptName(p, err);
                return;
            }
            // "kept" = le joueur a gardé son nom de la partie précédente (utile pour l'historique)
            String source = RpGame.fold(name).equals(RpGame.fold(RpGame.previousName(p.getUuid()))) ? "kept" : "player";
            RpGame.setName(server, p.getUuid(), name, source, null);
            p.sendMessage(Msg.ok("Votre nom RP pour cette partie : " + name), false);
        });

        ServerPlayNetworking.registerGlobalReceiver(Net.Introduce.ID, (payload, ctx) ->
            RpGame.propose(ctx.player(), payload.target(), payload.alias(), payload.aliasText()));

        ServerPlayNetworking.registerGlobalReceiver(Net.ProposalAction.ID, (payload, ctx) ->
            RpGame.proposalAction(ctx.player(), payload.id(), payload.action()));

        ServerPlayNetworking.registerGlobalReceiver(Net.Reply.ID, (payload, ctx) ->
            RpGame.reply(ctx.player(), payload.id(), payload.alias(), payload.aliasText()));

        ServerPlayNetworking.registerGlobalReceiver(Net.RequestTickets.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            if (!Staff.isStaff(p)) {
                p.sendMessage(Msg.err("Le panneau d'assistance est réservé au staff."), true);
                return;
            }
            Tickets.sendList(p);
        });

        ServerPlayNetworking.registerGlobalReceiver(Net.TicketAction.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            String err = Tickets.act(p, payload.ticket(), payload.action());
            if (err != null) p.sendMessage(Msg.err(err), true);
            Tickets.sendList(p);
        });

        // ---------------------------------------------------------------- menu « Aide »
        ServerPlayNetworking.registerGlobalReceiver(Net.RequestHelp.ID, (payload, ctx) ->
            ServerPlayNetworking.send(ctx.player(), new Net.OpenHelp(new java.util.ArrayList<>(RpConfig.INSTANCE.helpTypes))));

        ServerPlayNetworking.registerGlobalReceiver(Net.HelpSubmit.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            String type = payload.type() == null ? "" : payload.type().toLowerCase(java.util.Locale.ROOT).trim();
            if (!RpConfig.INSTANCE.helpTypes.contains(type)) {
                p.sendMessage(Msg.err("Type de demande inconnu."), true);
                return;
            }
            String err = Tickets.create(p, type, RpGame.clean(payload.message()), payload.anonymous(), payload.wantChat());
            if (err != null) p.sendMessage(Msg.err(err), true);
        });

        // ---------------------------------------------------------------- chat intégré
        ServerPlayNetworking.registerGlobalReceiver(Net.ChatQuery.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            Chats.syncTo(p);
            if (payload.chat() > 0) Chats.sendHistory(p, payload.chat());
        });

        ServerPlayNetworking.registerGlobalReceiver(Net.ChatSend.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            String err = Chats.send(p, payload.chat(), payload.text());
            if (err != null) p.sendMessage(Msg.err(err), true);
        });

        ServerPlayNetworking.registerGlobalReceiver(Net.ChatOpenWith.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            Chats.Result r = Chats.openDirect(p, payload.target(), 0, false);
            if (r.error() != null) p.sendMessage(Msg.err(r.error()), true);
        });

        ServerPlayNetworking.registerGlobalReceiver(Net.ChatCreate.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            Chats.Result r = Chats.createGroup(p, payload.title());
            if (r.error() != null) p.sendMessage(Msg.err(r.error()), true);
        });

        ServerPlayNetworking.registerGlobalReceiver(Net.ChatAdd.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            String err = Chats.addMember(p, payload.chat(), payload.target());
            if (err != null) p.sendMessage(Msg.err(err), true);
        });

        ServerPlayNetworking.registerGlobalReceiver(Net.ChatClose.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            String err = Chats.close(p, payload.chat());
            if (err != null) p.sendMessage(Msg.err(err), true);
        });

        ServerPlayNetworking.registerGlobalReceiver(Net.RequestPlayers.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            if (!Staff.isStaff(p) || p.getServer() == null) return;
            ServerPlayNetworking.send(p, new Net.PlayerList(Chats.onlinePlayers(p.getServer(), p.getUuid())));
        });
    }
}
