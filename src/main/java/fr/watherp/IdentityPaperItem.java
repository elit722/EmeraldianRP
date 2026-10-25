package fr.watherp;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/**
 * Papiers d'identité.
 *  - clic droit dans le vide : les consulter (et les remplir / modifier si ce sont les siens ou s'ils sont vierges) ;
 *  - clic droit sur un joueur : lui montrer ses papiers (il voit la carte, avec le skin du titulaire à gauche).
 */
public class IdentityPaperItem extends Item {
    public IdentityPaperItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity sp) IdentityItems.open(sp, stack);
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
        if (!(entity instanceof PlayerEntity)) return ActionResult.PASS;
        boolean client = user.getWorld().isClient;
        if (!client && user instanceof ServerPlayerEntity sp && entity instanceof ServerPlayerEntity target) {
            IdentityItems.showTo(sp, target, stack);
        }
        return ActionResult.success(client);
    }

    @Override
    public Text getName(ItemStack stack) {
        Identity id = Identity.read(stack);
        if (id.isBlank() || id.name().isEmpty()) return super.getName(stack);
        return Text.translatable(this.getTranslationKey()).append(Text.literal(" · " + id.name()));
    }

    @Override
    public void appendTooltip(ItemStack stack, Item.TooltipContext context, List<Text> tooltip, TooltipType type) {
        Identity id = Identity.read(stack);
        if (id.isBlank()) {
            tooltip.add(Text.literal("Vierges : clic droit pour les remplir.").formatted(Formatting.GRAY));
        } else {
            tooltip.add(Text.literal("Clic droit : consulter / modifier.").formatted(Formatting.GRAY));
        }
        tooltip.add(Text.literal("Clic droit sur un joueur : lui montrer.").formatted(Formatting.DARK_GRAY));
    }
}
