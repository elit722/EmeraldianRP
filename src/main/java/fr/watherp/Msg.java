package fr.watherp;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Helpers de messages. */
public final class Msg {
    private Msg() {}

    public static final String PREFIX = "[RP] ";

    public static MutableText prefix() {
        return Text.literal(PREFIX).formatted(Formatting.GOLD);
    }

    public static Text info(String s) {
        return prefix().append(Text.literal(s).formatted(Formatting.GRAY));
    }

    public static Text ok(String s) {
        return prefix().append(Text.literal(s).formatted(Formatting.GREEN));
    }

    public static Text err(String s) {
        return prefix().append(Text.literal(s).formatted(Formatting.RED));
    }

    public static MutableText button(String label, String command, Formatting color, String hover) {
        return Text.literal("[" + label + "]").styled(st -> st
            .withColor(color)
            .withBold(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover))));
    }

    /** Bouton cliquable dont le libellé affiche la touche ACTUELLEMENT assignée (résolue côté client : suit les réglages Minecraft). */
    private static MutableText keyButton(String keyId, String label, String command, Formatting color, String hover) {
        return Text.literal("[").append(Text.keybind(keyId)).append(Text.literal("] " + label)).styled(st -> st
            .withColor(color)
            .withBold(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover + " (clic ou touche)"))));
    }

    public static Text proposal(String fromShown, int id, int seconds) {
        return prefix()
            .append(Text.literal(fromShown).formatted(Formatting.YELLOW, Formatting.BOLD))
            .append(Text.literal(" souhaite se présenter à vous (" + seconds + " s). ").formatted(Formatting.GRAY))
            .append(keyButton("key.watherp.accept", "Se présenter", "/rpintro accept " + id, Formatting.GREEN, "Se présenter sous votre nom RP"))
            .append(Text.literal(" "))
            .append(keyButton("key.watherp.alias", "Sous pseudonyme", "/rpintro alias " + id, Formatting.AQUA, "Choisir un pseudonyme RP"));
    }
}
