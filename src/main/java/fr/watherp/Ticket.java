package fr.watherp;

import net.minecraft.network.PacketByteBuf;

import java.util.UUID;

/** Ticket d'aide. Sérialisé en JSON (stockage) et via le réseau (panneau staff). */
public class Ticket {
    public static final String OPEN = "OPEN", CLAIMED = "CLAIMED", CLOSED = "CLOSED";

    public int id;
    public String playerUuid = "";
    public String playerName = "";
    public String rpName = "";
    public String type = "";
    public String message = "";
    public long createdMs;
    public String world = "";
    public double x, y, z;
    public String status = OPEN;
    public String claimedBy = "";
    public boolean inGame;
    public String discordMessageId = "";
    /** Ticket anonymisé : ni le pseudo, ni le nom RP, ni la position ne sont montrés aux helpers (ni sur Discord) tant qu'il n'est pas désanonymisé. */
    public boolean anonymous;
    /** Le joueur demande l'ouverture d'un chat privé. */
    public boolean wantChat;
    /** Chat privé lié à ce ticket (0 = aucun). */
    public int chatId;
    /** Helper qui a levé l'anonymat (vide si jamais fait). */
    public String deanonBy = "";

    public UUID uuid() {
        try {
            return UUID.fromString(playerUuid);
        } catch (Exception e) {
            return new UUID(0, 0);
        }
    }

    /** Copie envoyée aux clients staff : un ticket anonyme n'embarque AUCUNE donnée d'identité ni de position. */
    public Ticket forClient() {
        Ticket c = new Ticket();
        c.id = id;
        c.type = type;
        c.message = message;
        c.createdMs = createdMs;
        c.status = status;
        c.claimedBy = claimedBy;
        c.inGame = inGame;
        c.anonymous = anonymous;
        c.wantChat = wantChat;
        c.chatId = chatId;
        c.deanonBy = deanonBy;
        if (!anonymous) {
            c.playerUuid = playerUuid;
            c.playerName = playerName;
            c.rpName = rpName;
            c.world = world;
            c.x = x;
            c.y = y;
            c.z = z;
        }
        return c;
    }

    /** Nom à afficher pour le staff : « Anonyme » tant que le ticket est anonymisé. */
    public String shownName() {
        if (anonymous) return "Anonyme";
        return rpName.isEmpty() ? playerName : rpName;
    }

    public void write(PacketByteBuf b) {
        b.writeVarInt(id);
        b.writeString(playerUuid);
        b.writeString(playerName);
        b.writeString(rpName);
        b.writeString(type);
        b.writeString(message, 1024);
        b.writeLong(createdMs);
        b.writeString(world);
        b.writeDouble(x);
        b.writeDouble(y);
        b.writeDouble(z);
        b.writeString(status);
        b.writeString(claimedBy);
        b.writeBoolean(inGame);
        b.writeBoolean(anonymous);
        b.writeBoolean(wantChat);
        b.writeVarInt(chatId);
        b.writeString(deanonBy);
    }

    public static Ticket read(PacketByteBuf b) {
        Ticket t = new Ticket();
        t.id = b.readVarInt();
        t.playerUuid = b.readString();
        t.playerName = b.readString();
        t.rpName = b.readString();
        t.type = b.readString();
        t.message = b.readString(1024);
        t.createdMs = b.readLong();
        t.world = b.readString();
        t.x = b.readDouble();
        t.y = b.readDouble();
        t.z = b.readDouble();
        t.status = b.readString();
        t.claimedBy = b.readString();
        t.inGame = b.readBoolean();
        t.anonymous = b.readBoolean();
        t.wantChat = b.readBoolean();
        t.chatId = b.readVarInt();
        t.deanonBy = b.readString();
        return t;
    }
}
