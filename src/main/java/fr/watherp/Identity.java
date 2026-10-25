package fr.watherp;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;

import java.util.UUID;

/**
 * Contenu d'un papier d'identité. Stocké dans le composant « custom_data » de l'objet (clé {@code watherp_id}) :
 * le titulaire (UUID + pseudo, sert à afficher son skin) et six champs libres.
 * Un papier « vierge » n'a pas de titulaire ({@link #NONE}).
 */
public record Identity(UUID owner, String ownerMc, String name, String age, String profession,
                       String provenance, String destination, String mark) {

    public static final UUID NONE = new UUID(0L, 0L);
    public static final int NAME_MAX = 40, AGE_MAX = 12, JOB_MAX = 40, FROM_MAX = 40, DEST_MAX = 40, MARK_MAX = 80;
    private static final String KEY = "watherp_id";

    public static Identity blank() {
        return new Identity(NONE, "", "", "", "", "", "", "");
    }

    /** Vrai tant que personne n'a rempli le papier. */
    public boolean isBlank() {
        return NONE.equals(owner);
    }

    /** Numéro de document à 4 chiffres, stable pour un titulaire (cosmétique). */
    public String number() {
        return String.format("%04d", Math.floorMod(owner.hashCode(), 10000));
    }

    /** Même papier avec un autre nom : le nom affiché est toujours le nom RP actuel du titulaire (il ne se saisit pas). */
    public Identity withName(String newName) {
        return new Identity(owner, ownerMc, clip(newName, NAME_MAX), age, profession, provenance, destination, mark);
    }

    // ---------------------------------------------------------------- nettoyage des saisies

    /** Texte d'une ligne : espaces normalisés, caractères de contrôle retirés, longueur bornée (en caractères Unicode). */
    public static String clip(String raw, int max) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp)) continue;
            b.appendCodePoint(cp);
        }
        String s = b.toString().trim().replaceAll("\\s+", " ");
        if (s.codePointCount(0, s.length()) > max) s = s.substring(0, s.offsetByCodePoints(0, max)).trim();
        return s;
    }

    public static Identity of(UUID owner, String ownerMc, String name, String age, String profession,
                              String provenance, String destination, String mark) {
        return new Identity(owner == null ? NONE : owner, ownerMc == null ? "" : ownerMc,
            clip(name, NAME_MAX), clip(age, AGE_MAX), clip(profession, JOB_MAX),
            clip(provenance, FROM_MAX), clip(destination, DEST_MAX), clip(mark, MARK_MAX));
    }

    // ---------------------------------------------------------------- objet

    public static Identity read(ItemStack stack) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return blank();
        NbtCompound root = comp.copyNbt();
        if (!root.contains(KEY)) return blank();
        NbtCompound t = root.getCompound(KEY);
        UUID owner = t.containsUuid("owner") ? t.getUuid("owner") : NONE;
        return of(owner, t.getString("mc"), t.getString("name"), t.getString("age"), t.getString("job"),
            t.getString("from"), t.getString("to"), t.getString("mark"));
    }

    public void writeTo(ItemStack stack) {
        NbtCompound t = new NbtCompound();
        t.putUuid("owner", owner);
        t.putString("mc", ownerMc);
        t.putString("name", name);
        t.putString("age", age);
        t.putString("job", profession);
        t.putString("from", provenance);
        t.putString("to", destination);
        t.putString("mark", mark);
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root -> root.put(KEY, t));
    }

    // ---------------------------------------------------------------- réseau

    public void write(PacketByteBuf b) {
        b.writeUuid(owner);
        b.writeString(ownerMc, 32);
        b.writeString(name, 160);
        b.writeString(age, 64);
        b.writeString(profession, 160);
        b.writeString(provenance, 160);
        b.writeString(destination, 160);
        b.writeString(mark, 320);
    }

    public static Identity read(PacketByteBuf b) {
        return new Identity(b.readUuid(), b.readString(32), b.readString(160), b.readString(64), b.readString(160),
            b.readString(160), b.readString(160), b.readString(320));
    }
}
