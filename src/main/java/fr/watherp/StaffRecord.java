package fr.watherp;

import net.minecraft.network.PacketByteBuf;

import java.util.UUID;

/** Avertissement ou note de staff sur un joueur. Sérialisé en JSON (stockage) et via le réseau (panneau staff). */
public class StaffRecord {
    public static final String WARN = "warn", NOTE = "note";
    public static final int TEXT_MAX = 400;

    public int id;
    public String kind = NOTE;
    public String targetUuid = "";
    public String targetName = "";
    public String targetRp = "";
    public String author = "";
    public String text = "";
    public long createdMs;
    /** Avertissement posé alors que le joueur était hors ligne et pas encore affiché (non transmis au panneau). */
    public boolean pending;

    public UUID uuid() {
        try {
            return UUID.fromString(targetUuid);
        } catch (Exception e) {
            return new UUID(0, 0);
        }
    }

    public void write(PacketByteBuf b) {
        b.writeVarInt(id);
        b.writeString(kind);
        b.writeString(targetUuid);
        b.writeString(targetName);
        b.writeString(targetRp);
        b.writeString(author);
        b.writeString(text, 1024);
        b.writeLong(createdMs);
    }

    public static StaffRecord read(PacketByteBuf b) {
        StaffRecord r = new StaffRecord();
        r.id = b.readVarInt();
        r.kind = b.readString();
        r.targetUuid = b.readString();
        r.targetName = b.readString();
        r.targetRp = b.readString();
        r.author = b.readString();
        r.text = b.readString(1024);
        r.createdMs = b.readLong();
        return r;
    }
}
