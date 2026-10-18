package fr.watherp;

import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Avertissements / notes du staff (stockés dans watherp-data.json). */
public final class Records {
    private Records() {}

    /** Joueurs qui viennent de se connecter avec des avertissements en attente : uuid -> ticks avant affichage. */
    private static final Map<UUID, Integer> deliverIn = new HashMap<>();

    /** Chat + titre à l'écran (le chat de Wathe est masqué pour les joueurs en vie). */
    public static void deliverWarn(ServerPlayerEntity target, StaffRecord r) {
        target.sendMessage(Msg.prefix().append(Text.literal("Avertissement du staff : ").formatted(Formatting.RED, Formatting.BOLD))
            .append(Text.literal(r.text).formatted(Formatting.WHITE)), false);
        target.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 80, 20));
        target.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("Avertissement").formatted(Formatting.RED, Formatting.BOLD)));
        target.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(r.text).formatted(Formatting.GRAY)));
    }

    private static boolean hasPending(String uuid) {
        for (StaffRecord r : RpData.INSTANCE.records) if (r.pending && r.targetUuid.equals(uuid)) return true;
        return false;
    }

    /** À la connexion : programme l'affichage des avertissements reçus pendant l'absence (3 s, le temps que le client soit prêt). */
    public static void onJoin(ServerPlayerEntity p) {
        if (hasPending(p.getUuidAsString())) deliverIn.put(p.getUuid(), 60);
    }

    public static void tick(MinecraftServer server) {
        if (deliverIn.isEmpty()) return;
        Iterator<Map.Entry<UUID, Integer>> it = deliverIn.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            int left = e.getValue() - 1;
            if (left > 0) {
                e.setValue(left);
                continue;
            }
            it.remove();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null) continue;
            boolean changed = false;
            for (StaffRecord r : RpData.INSTANCE.records) {
                if (r.pending && r.targetUuid.equals(p.getUuidAsString())) {
                    deliverWarn(p, r);
                    r.pending = false;
                    changed = true;
                }
            }
            if (changed) RpData.save();
        }
    }

    public static StaffRecord add(String kind, String targetUuid, String targetName, String author, String text) {
        StaffRecord r = new StaffRecord();
        r.id = RpData.INSTANCE.nextRecord++;
        r.kind = kind;
        r.targetUuid = targetUuid;
        r.targetName = targetName;
        String rp = null;
        try {
            rp = RpGame.currentOrPrevious(java.util.UUID.fromString(targetUuid));
        } catch (Exception ignored) {
        }
        r.targetRp = rp == null ? "" : rp;
        r.author = author;
        r.text = text.length() > StaffRecord.TEXT_MAX ? text.substring(0, StaffRecord.TEXT_MAX) : text;
        r.createdMs = System.currentTimeMillis();
        RpData.INSTANCE.records.add(r);
        RpData.save();
        return r;
    }

    /** Les {@code max} derniers dossiers, du plus récent au plus ancien. */
    public static List<StaffRecord> recent(int max) {
        List<StaffRecord> all = RpData.INSTANCE.records;
        List<StaffRecord> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) out.add(all.get(i));
        return out;
    }

    public static List<StaffRecord> forPlayer(String uuid) {
        List<StaffRecord> out = new ArrayList<>();
        List<StaffRecord> all = RpData.INSTANCE.records;
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).targetUuid.equals(uuid)) out.add(all.get(i));
        return out;
    }

    public static int count(String uuid, String kind) {
        int n = 0;
        for (StaffRecord r : RpData.INSTANCE.records) if (r.targetUuid.equals(uuid) && r.kind.equals(kind)) n++;
        return n;
    }

    public static boolean delete(int id) {
        boolean removed = RpData.INSTANCE.records.removeIf(r -> r.id == id);
        if (removed) RpData.save();
        return removed;
    }
}
