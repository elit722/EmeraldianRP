package fr.watherp.net;

import fr.watherp.StaffRecord;
import fr.watherp.Ticket;
import fr.watherp.WatheRP;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tous les paquets réseau du mod. */
public final class Net {
    private Net() {}

    private static <T extends CustomPayload> CustomPayload.Id<T> pid(String path) {
        return new CustomPayload.Id<>(Identifier.of(WatheRP.MOD_ID, path));
    }

    // ============================================================ Serveur -> Client

    /** Ouvre le menu de saisie du nom RP. {@code mandatory} : en partie, Échap ne ferme pas le menu. */
    public record OpenName(String previous, String error, boolean mandatory) implements CustomPayload {
        public static final Id<OpenName> ID = pid("open_name");
        public static final PacketCodec<RegistryByteBuf, OpenName> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeString(v.previous);
                b.writeString(v.error);
                b.writeBoolean(v.mandatory);
            },
            b -> new OpenName(b.readString(), b.readString(), b.readBoolean()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Ce que ce joueur sait des autres (UUID -> nom affiché) + son propre nom + option staff /r voirpseudo. */
    public record Sync(Map<UUID, String> known, String own, boolean confirmed, boolean seePseudo) implements CustomPayload {
        public static final Id<Sync> ID = pid("sync");
        public static final PacketCodec<RegistryByteBuf, Sync> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.known.size());
                for (Map.Entry<UUID, String> e : v.known.entrySet()) {
                    b.writeUuid(e.getKey());
                    b.writeString(e.getValue());
                }
                b.writeString(v.own);
                b.writeBoolean(v.confirmed);
                b.writeBoolean(v.seePseudo);
            },
            b -> {
                int n = b.readVarInt();
                Map<UUID, String> m = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) m.put(b.readUuid(), b.readString());
                return new Sync(m, b.readString(), b.readBoolean(), b.readBoolean());
            });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Proposition de présentation reçue. */
    public record Proposal(int id, UUID from, String fromName, int seconds, int popupSeconds) implements CustomPayload {
        public static final Id<Proposal> ID = pid("proposal");
        public static final PacketCodec<RegistryByteBuf, Proposal> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.id);
                b.writeUuid(v.from);
                b.writeString(v.fromName);
                b.writeVarInt(v.seconds);
                b.writeVarInt(v.popupSeconds);
            },
            b -> new Proposal(b.readVarInt(), b.readUuid(), b.readString(), b.readVarInt(), b.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Proposition terminée (id = -1 : toutes). */
    public record ProposalGone(int id) implements CustomPayload {
        public static final Id<ProposalGone> ID = pid("proposal_gone");
        public static final PacketCodec<RegistryByteBuf, ProposalGone> CODEC = PacketCodec.of(
            (v, b) -> b.writeVarInt(v.id + 1),
            b -> new ProposalGone(b.readVarInt() - 1));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Ouvre le menu de saisie d'un pseudonyme pour répondre à une proposition. */
    public record OpenAlias(int proposalId) implements CustomPayload {
        public static final Id<OpenAlias> ID = pid("open_alias");
        public static final PacketCodec<RegistryByteBuf, OpenAlias> CODEC = PacketCodec.of(
            (v, b) -> b.writeVarInt(v.proposalId),
            b -> new OpenAlias(b.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Notification à l'écran d'un staff : nouvelle demande d'aide. */
    public record Alert(int id, String type, String player, String rp, String message, boolean anonymous, boolean wantChat) implements CustomPayload {
        public static final Id<Alert> ID = pid("alert");
        public static final PacketCodec<RegistryByteBuf, Alert> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.id);
                b.writeString(v.type);
                b.writeString(v.player);
                b.writeString(v.rp);
                b.writeString(v.message, 1024);
                b.writeBoolean(v.anonymous);
                b.writeBoolean(v.wantChat);
            },
            b -> new Alert(b.readVarInt(), b.readString(), b.readString(), b.readString(), b.readString(1024), b.readBoolean(), b.readBoolean()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Liste des tickets pour le panneau d'assistance. */
    public record Tickets(List<Ticket> list) implements CustomPayload {
        public static final Id<Tickets> ID = pid("tickets");
        public static final PacketCodec<RegistryByteBuf, Tickets> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.list.size());
                for (Ticket t : v.list) t.write(b);
            },
            b -> {
                int n = b.readVarInt();
                List<Ticket> l = new ArrayList<>();
                for (int i = 0; i < n; i++) l.add(Ticket.read(b));
                return new Tickets(l);
            });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Avertissements / notes pour le panneau d'assistance (staff). */
    public record Records(List<StaffRecord> list) implements CustomPayload {
        public static final Id<Records> ID = pid("records");
        public static final PacketCodec<RegistryByteBuf, Records> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.list.size());
                for (StaffRecord r : v.list) r.write(b);
            },
            b -> {
                int n = b.readVarInt();
                List<StaffRecord> l = new ArrayList<>();
                for (int i = 0; i < n; i++) l.add(StaffRecord.read(b));
                return new Records(l);
            });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }


    /** Ferme le menu de nom RP s'il est ouvert (fin de partie, nom imposé par le staff). */
    public record CloseName() implements CustomPayload {
        public static final Id<CloseName> ID = pid("close_name");
        public static final PacketCodec<RegistryByteBuf, CloseName> CODEC = PacketCodec.of((v, b) -> {
        }, b -> new CloseName());

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Droits du joueur côté interface (le serveur revérifie chaque action). */
    public record Caps(boolean helper) implements CustomPayload {
        public static final Id<Caps> ID = pid("caps");
        public static final PacketCodec<RegistryByteBuf, Caps> CODEC = PacketCodec.of(
            (v, b) -> b.writeBoolean(v.helper),
            b -> new Caps(b.readBoolean()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Ouvre le menu « Aide » avec les types de demande acceptés. */
    public record OpenHelp(List<String> types) implements CustomPayload {
        public static final Id<OpenHelp> ID = pid("open_help");
        public static final PacketCodec<RegistryByteBuf, OpenHelp> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.types.size());
                for (String t : v.types) b.writeString(t, 32);
            },
            b -> {
                int n = b.readVarInt();
                List<String> l = new ArrayList<>();
                for (int i = 0; i < n; i++) l.add(b.readString(32));
                return new OpenHelp(l);
            });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ---- chat intégré (données)

    /** Un chat visible par le joueur. {@code members} = libellés (« Joueur anonyme » si masqué). */
    public record ChatSummary(int id, String kind, String title, boolean closed, int ticketId, List<String> members, int count) {
        public void write(PacketByteBuf b) {
            b.writeVarInt(id);
            b.writeString(kind, 16);
            b.writeString(title, 96);
            b.writeBoolean(closed);
            b.writeVarInt(ticketId);
            b.writeVarInt(members.size());
            for (String m : members) b.writeString(m, 48);
            b.writeVarInt(count);
        }

        public static ChatSummary read(PacketByteBuf b) {
            int id = b.readVarInt();
            String kind = b.readString(16);
            String title = b.readString(96);
            boolean closed = b.readBoolean();
            int ticket = b.readVarInt();
            int n = b.readVarInt();
            List<String> members = new ArrayList<>();
            for (int i = 0; i < n; i++) members.add(b.readString(48));
            return new ChatSummary(id, kind, title, closed, ticket, members, b.readVarInt());
        }
    }

    /** Une ligne de chat, déjà « habillée » pour son destinataire (libellé anonymisé, {@code mine} résolu côté serveur). */
    public record ChatLineData(long ms, String label, String text, boolean system, boolean staff, boolean mine) {
        public void write(PacketByteBuf b) {
            b.writeLong(ms);
            b.writeString(label, 48);
            b.writeString(text, 320);
            b.writeBoolean(system);
            b.writeBoolean(staff);
            b.writeBoolean(mine);
        }

        public static ChatLineData read(PacketByteBuf b) {
            return new ChatLineData(b.readLong(), b.readString(48), b.readString(320), b.readBoolean(), b.readBoolean(), b.readBoolean());
        }
    }

    /** Joueur en ligne proposé dans les sélecteurs (nouveau chat, ajout de membre). */
    public record PlayerEntry(UUID uuid, String name, String rp) {
        public void write(PacketByteBuf b) {
            b.writeUuid(uuid);
            b.writeString(name, 32);
            b.writeString(rp, 48);
        }

        public static PlayerEntry read(PacketByteBuf b) {
            return new PlayerEntry(b.readUuid(), b.readString(32), b.readString(48));
        }
    }

    /** Liste complète des chats visibles par le joueur. */
    public record ChatSync(List<ChatSummary> chats) implements CustomPayload {
        public static final Id<ChatSync> ID = pid("chat_sync");
        public static final PacketCodec<RegistryByteBuf, ChatSync> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.chats.size());
                for (ChatSummary c : v.chats) c.write(b);
            },
            b -> {
                int n = b.readVarInt();
                List<ChatSummary> l = new ArrayList<>();
                for (int i = 0; i < n; i++) l.add(ChatSummary.read(b));
                return new ChatSync(l);
            });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Historique d'un chat (les 100 dernières lignes). */
    public record ChatHistory(int chat, List<ChatLineData> lines) implements CustomPayload {
        public static final Id<ChatHistory> ID = pid("chat_history");
        public static final PacketCodec<RegistryByteBuf, ChatHistory> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.chat);
                b.writeVarInt(v.lines.size());
                for (ChatLineData l : v.lines) l.write(b);
            },
            b -> {
                int chat = b.readVarInt();
                int n = b.readVarInt();
                List<ChatLineData> l = new ArrayList<>();
                for (int i = 0; i < n; i++) l.add(ChatLineData.read(b));
                return new ChatHistory(chat, l);
            });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Nouvelle ligne dans un chat. */
    public record ChatPush(int chat, ChatLineData line) implements CustomPayload {
        public static final Id<ChatPush> ID = pid("chat_push");
        public static final PacketCodec<RegistryByteBuf, ChatPush> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.chat);
                v.line.write(b);
            },
            b -> new ChatPush(b.readVarInt(), ChatLineData.read(b)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Popup « un chat vient de vous être ouvert / vous avez été ajouté » avec la touche pour l'ouvrir. */
    public record ChatInvite(int chat, String title, String by, String reason) implements CustomPayload {
        public static final Id<ChatInvite> ID = pid("chat_invite");
        public static final PacketCodec<RegistryByteBuf, ChatInvite> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.chat);
                b.writeString(v.title, 96);
                b.writeString(v.by, 48);
                b.writeString(v.reason, 96);
            },
            b -> new ChatInvite(b.readVarInt(), b.readString(96), b.readString(48), b.readString(96)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Ouvre directement le menu de chat sur ce chat (chat que l'on vient de créer). */
    public record ChatOpen(int chat) implements CustomPayload {
        public static final Id<ChatOpen> ID = pid("chat_open");
        public static final PacketCodec<RegistryByteBuf, ChatOpen> CODEC = PacketCodec.of(
            (v, b) -> b.writeVarInt(v.chat),
            b -> new ChatOpen(b.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Réponse à RequestPlayers : joueurs en ligne. */
    public record PlayerList(List<PlayerEntry> players) implements CustomPayload {
        public static final Id<PlayerList> ID = pid("player_list");
        public static final PacketCodec<RegistryByteBuf, PlayerList> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.players.size());
                for (PlayerEntry p : v.players) p.write(b);
            },
            b -> {
                int n = b.readVarInt();
                List<PlayerEntry> l = new ArrayList<>();
                for (int i = 0; i < n; i++) l.add(PlayerEntry.read(b));
                return new PlayerList(l);
            });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ============================================================ Client -> Serveur

    public record SubmitName(String name) implements CustomPayload {
        public static final Id<SubmitName> ID = pid("submit_name");
        public static final PacketCodec<RegistryByteBuf, SubmitName> CODEC = PacketCodec.of(
            (v, b) -> b.writeString(v.name, 64),
            b -> new SubmitName(b.readString(64)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Maj+clic : proposer de se présenter à {@code target}. */
    public record Introduce(UUID target, boolean alias, String aliasText) implements CustomPayload {
        public static final Id<Introduce> ID = pid("introduce");
        public static final PacketCodec<RegistryByteBuf, Introduce> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeUuid(v.target);
                b.writeBoolean(v.alias);
                b.writeString(v.aliasText, 64);
            },
            b -> new Introduce(b.readUuid(), b.readBoolean(), b.readString(64)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Réponse à une proposition. */
    public record Reply(int id, boolean alias, String aliasText) implements CustomPayload {
        public static final Id<Reply> ID = pid("reply");
        public static final PacketCodec<RegistryByteBuf, Reply> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.id);
                b.writeBoolean(v.alias);
                b.writeString(v.aliasText, 64);
            },
            b -> new Reply(b.readVarInt(), b.readBoolean(), b.readString(64)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record RequestTickets() implements CustomPayload {
        public static final Id<RequestTickets> ID = pid("request_tickets");
        public static final PacketCodec<RegistryByteBuf, RequestTickets> CODEC = PacketCodec.of(
            (v, b) -> {
            },
            b -> new RequestTickets());

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** action : claim | close | reopen | tp */
    public record TicketAction(int ticket, String action) implements CustomPayload {
        public static final Id<TicketAction> ID = pid("ticket_action");
        public static final PacketCodec<RegistryByteBuf, TicketAction> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.ticket);
                b.writeString(v.action, 16);
            },
            b -> new TicketAction(b.readVarInt(), b.readString(16)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }


    /** Demande l'ouverture du menu « Aide » (touche, bouton du menu pause). */
    public record RequestHelp() implements CustomPayload {
        public static final Id<RequestHelp> ID = pid("request_help");
        public static final PacketCodec<RegistryByteBuf, RequestHelp> CODEC = PacketCodec.of((v, b) -> {
        }, b -> new RequestHelp());

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Envoi d'un ticket depuis le menu « Aide ». */
    public record HelpSubmit(String type, String message, boolean anonymous, boolean wantChat) implements CustomPayload {
        public static final Id<HelpSubmit> ID = pid("help_submit");
        public static final PacketCodec<RegistryByteBuf, HelpSubmit> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeString(v.type, 32);
                b.writeString(v.message, 300);
                b.writeBoolean(v.anonymous);
                b.writeBoolean(v.wantChat);
            },
            b -> new HelpSubmit(b.readString(32), b.readString(300), b.readBoolean(), b.readBoolean()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Demande la liste des chats ; {@code chat} > 0 : renvoie aussi l'historique de ce chat. */
    public record ChatQuery(int chat) implements CustomPayload {
        public static final Id<ChatQuery> ID = pid("chat_query");
        public static final PacketCodec<RegistryByteBuf, ChatQuery> CODEC = PacketCodec.of(
            (v, b) -> b.writeVarInt(v.chat),
            b -> new ChatQuery(b.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record ChatSend(int chat, String text) implements CustomPayload {
        public static final Id<ChatSend> ID = pid("chat_send");
        public static final PacketCodec<RegistryByteBuf, ChatSend> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.chat);
                b.writeString(v.text, 300);
            },
            b -> new ChatSend(b.readVarInt(), b.readString(300)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Helper : ouvrir (ou rouvrir) un chat direct avec un joueur. */
    public record ChatOpenWith(UUID target) implements CustomPayload {
        public static final Id<ChatOpenWith> ID = pid("chat_open_with");
        public static final PacketCodec<RegistryByteBuf, ChatOpenWith> CODEC = PacketCodec.of(
            (v, b) -> b.writeUuid(v.target),
            b -> new ChatOpenWith(b.readUuid()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Helper : créer un groupe de chat. */
    public record ChatCreate(String title) implements CustomPayload {
        public static final Id<ChatCreate> ID = pid("chat_create");
        public static final PacketCodec<RegistryByteBuf, ChatCreate> CODEC = PacketCodec.of(
            (v, b) -> b.writeString(v.title, 48),
            b -> new ChatCreate(b.readString(48)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Helper : ajouter un membre à un chat. */
    public record ChatAdd(int chat, UUID target) implements CustomPayload {
        public static final Id<ChatAdd> ID = pid("chat_add");
        public static final PacketCodec<RegistryByteBuf, ChatAdd> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.chat);
                b.writeUuid(v.target);
            },
            b -> new ChatAdd(b.readVarInt(), b.readUuid()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Helper : fermer un chat (il disparaît pour les joueurs). */
    public record ChatClose(int chat) implements CustomPayload {
        public static final Id<ChatClose> ID = pid("chat_close");
        public static final PacketCodec<RegistryByteBuf, ChatClose> CODEC = PacketCodec.of(
            (v, b) -> b.writeVarInt(v.chat),
            b -> new ChatClose(b.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Action sur une proposition de présentation : decline | pause (saisie du pseudonyme) | resume. */
    public record ProposalAction(int id, String action) implements CustomPayload {
        public static final Id<ProposalAction> ID = pid("proposal_action");
        public static final PacketCodec<RegistryByteBuf, ProposalAction> CODEC = PacketCodec.of(
            (v, b) -> {
                b.writeVarInt(v.id);
                b.writeString(v.action, 16);
            },
            b -> new ProposalAction(b.readVarInt(), b.readString(16)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Helper : liste des joueurs en ligne (sélecteurs). */
    public record RequestPlayers() implements CustomPayload {
        public static final Id<RequestPlayers> ID = pid("request_players");
        public static final PacketCodec<RegistryByteBuf, RequestPlayers> CODEC = PacketCodec.of((v, b) -> {
        }, b -> new RequestPlayers());

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ============================================================ enregistrement

    public static void registerTypes() {
        PayloadTypeRegistry.playS2C().register(OpenName.ID, OpenName.CODEC);
        PayloadTypeRegistry.playS2C().register(Sync.ID, Sync.CODEC);
        PayloadTypeRegistry.playS2C().register(Proposal.ID, Proposal.CODEC);
        PayloadTypeRegistry.playS2C().register(ProposalGone.ID, ProposalGone.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenAlias.ID, OpenAlias.CODEC);
        PayloadTypeRegistry.playS2C().register(Alert.ID, Alert.CODEC);
        PayloadTypeRegistry.playS2C().register(Tickets.ID, Tickets.CODEC);
        PayloadTypeRegistry.playS2C().register(Records.ID, Records.CODEC);
        PayloadTypeRegistry.playS2C().register(CloseName.ID, CloseName.CODEC);
        PayloadTypeRegistry.playS2C().register(Caps.ID, Caps.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenHelp.ID, OpenHelp.CODEC);
        PayloadTypeRegistry.playS2C().register(ChatSync.ID, ChatSync.CODEC);
        PayloadTypeRegistry.playS2C().register(ChatHistory.ID, ChatHistory.CODEC);
        PayloadTypeRegistry.playS2C().register(ChatPush.ID, ChatPush.CODEC);
        PayloadTypeRegistry.playS2C().register(ChatInvite.ID, ChatInvite.CODEC);
        PayloadTypeRegistry.playS2C().register(ChatOpen.ID, ChatOpen.CODEC);
        PayloadTypeRegistry.playS2C().register(PlayerList.ID, PlayerList.CODEC);

        PayloadTypeRegistry.playC2S().register(SubmitName.ID, SubmitName.CODEC);
        PayloadTypeRegistry.playC2S().register(Introduce.ID, Introduce.CODEC);
        PayloadTypeRegistry.playC2S().register(Reply.ID, Reply.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestTickets.ID, RequestTickets.CODEC);
        PayloadTypeRegistry.playC2S().register(TicketAction.ID, TicketAction.CODEC);
        PayloadTypeRegistry.playC2S().register(ProposalAction.ID, ProposalAction.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestHelp.ID, RequestHelp.CODEC);
        PayloadTypeRegistry.playC2S().register(HelpSubmit.ID, HelpSubmit.CODEC);
        PayloadTypeRegistry.playC2S().register(ChatQuery.ID, ChatQuery.CODEC);
        PayloadTypeRegistry.playC2S().register(ChatSend.ID, ChatSend.CODEC);
        PayloadTypeRegistry.playC2S().register(ChatOpenWith.ID, ChatOpenWith.CODEC);
        PayloadTypeRegistry.playC2S().register(ChatCreate.ID, ChatCreate.CODEC);
        PayloadTypeRegistry.playC2S().register(ChatAdd.ID, ChatAdd.CODEC);
        PayloadTypeRegistry.playC2S().register(ChatClose.ID, ChatClose.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestPlayers.ID, RequestPlayers.CODEC);
    }
}
