package fr.watherp;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;

import java.util.UUID;

/**
 * Contenu d'un billet de train (ancien « papier d'identité »). Stocké dans le composant « custom_data » de l'objet (clé {@code watherp_id}) :
 * le titulaire (UUID + pseudo, sert à afficher son skin) et six champs libres.
 * Un papier « vierge » n'a pas de titulaire ({@link #NONE}).
 */
public record Identity(UUID owner, String ownerMc, String name, String age, String profession,
                       String provenance, String destination, String mark) {

    public static final UUID NONE = new UUID(0L, 0L);
    public static final int NAME_MAX = 40, AGE_MAX = 12, JOB_MAX = 40, FROM_MAX = 40, DEST_MAX = 40, MARK_MAX = 80;
    private static final String KEY = "watherp_id";

    /** Provenance imprimée sur tous les billets (config, « PARIS » par défaut). */
    public static String defaultFrom() {
        String v = RpConfig.INSTANCE.ticketFrom;
        return v == null || v.isBlank() ? "PARIS" : clip(v, FROM_MAX);
    }

    /** Destination imprimée sur tous les billets (config, « VIENNERAUDIA » par défaut). */
    public static String defaultDestination() {
        String v = RpConfig.INSTANCE.ticketDest;
        return v == null || v.isBlank() ? "VIENNERAUDIA" : clip(v, DEST_MAX);
    }

    /** Billet vierge : sans titulaire, mais avec la provenance et la destination imposées. */
    public static Identity blank() {
        return new Identity(NONE, "", "", "", "", defaultFrom(), defaultDestination(), "");
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

    private static String orDefault(String v, String def) {
        return v == null || v.isEmpty() ? def : v;
    }

    /** Même billet avec un champ modifié (« age », « profession », « signe », « provenance », « destination ») ; valeur vide = remise à zéro. */
    public Identity withField(String field, String value) {
        return switch (field) {
            case "age" -> of(owner, ownerMc, name, value, profession, provenance, destination, mark);
            case "profession" -> of(owner, ownerMc, name, age, value, provenance, destination, mark);
            case "signe" -> of(owner, ownerMc, name, age, profession, provenance, destination, value);
            case "provenance" -> of(owner, ownerMc, name, age, profession, value, destination, mark);
            case "destination" -> of(owner, ownerMc, name, age, profession, provenance, value, mark);
            default -> this;
        };
    }

    public Identity withOwner(UUID newOwner, String newOwnerMc, String newName) {
        return of(newOwner, newOwnerMc, newName, age, profession, provenance, destination, mark);
    }

    public static Identity of(UUID owner, String ownerMc, String name, String age, String profession,
                              String provenance, String destination, String mark) {
        return new Identity(owner == null ? NONE : owner, ownerMc == null ? "" : ownerMc,
            clip(name, NAME_MAX), clip(age, AGE_MAX), clip(profession, JOB_MAX),
            orDefault(clip(provenance, FROM_MAX), defaultFrom()), orDefault(clip(destination, DEST_MAX), defaultDestination()),
            clip(mark, MARK_MAX));
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
            t.getString("from_o"), t.getString("to_o"), t.getString("mark"));
    }

    public void writeTo(ItemStack stack) {
        NbtCompound t = new NbtCompound();
        t.putUuid("owner", owner);
        t.putString("mc", ownerMc);
        t.putString("name", name);
        t.putString("age", age);
        t.putString("job", profession);
        // seules les exceptions décidées par un admin sont stockées ; sinon la valeur imposée (config) s'applique, même si elle change
        t.putString("from_o", provenance.equals(defaultFrom()) ? "" : provenance);
        t.putString("to_o", destination.equals(defaultDestination()) ? "" : destination);
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
