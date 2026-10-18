package fr.watherp.mixin;

import fr.watherp.RpConfig;
import net.minecraft.command.EntitySelectorReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Vanilla refuse tout nom de joueur de plus de 16 caractères dans un sélecteur (/tp, /msg, /give...) :
 * « string.length() > 16 » dans EntitySelectorReader#readRegular lève « Nom ou UUID invalide ».
 * Les noms RP peuvent aller jusqu'à 32 caractères : on relève cette limite pour que les commandes vanilla
 * (et les suggestions côté client) acceptent ces noms. Ensuite EntitySelectorMixin les résout en vrai joueur.
 *
 * Ce mixin est chargé des deux côtés : le client analyse aussi la commande (coloration, suggestions).
 * Syntaxe d'un nom avec espaces : Jean_Pierre ou "Jean Pierre" ; accents : "Hélène" (entre guillemets).
 */
@Mixin(EntitySelectorReader.class)
public abstract class EntitySelectorReaderMixin {
    @ModifyConstant(method = "readRegular", constant = @Constant(intValue = 16), require = 0)
    private int watherp$longerNames(int vanillaMax) {
        return Math.max(vanillaMax, RpConfig.MAX_NAME_HARD);
    }
}
