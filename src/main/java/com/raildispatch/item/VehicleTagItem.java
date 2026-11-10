package com.raildispatch.item;

import com.raildispatch.RDConfig;
import com.raildispatch.api.RailServices;
import com.raildispatch.api.VehicleHandle;
import com.raildispatch.block.entity.PhysicsCabBlockEntity;
import com.raildispatch.data.RailTags;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Names a vehicle.
 *
 * <p>Rename it in an anvil, then right-click the vehicle (or the cab mounted on it) to bind the name
 * to that vehicle's physics body. Once bound, a schedule entry can address the vehicle by name,
 * which is what makes "couple that vehicle" and "run to that vehicle" possible at all - Create's own
 * destinations only understand stations.
 */
public class VehicleTagItem extends Item {

    public VehicleTagItem(Properties properties) {
        super(properties);
    }

    /** The tag written on this item, or an empty string when it has not been renamed. */
    public static String tagName(ItemStack stack) {
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        if (custom == null) {
            return "";
        }
        String name = custom.getString().trim();
        int limit = RDConfig.maxTagLength();
        return name.length() > limit ? name.substring(0, limit) : name;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel server) || context.getPlayer() == null) {
            return InteractionResult.PASS;
        }
        ItemStack stack = context.getItemInHand();
        String tag = tagName(stack);
        if (tag.isEmpty()) {
            context.getPlayer().displayClientMessage(
                    Component.translatable("raildispatch.vehicle_tag.unnamed").withStyle(ChatFormatting.YELLOW),
                    true);
            return InteractionResult.FAIL;
        }

        BlockPos pos = context.getClickedPos();
        if (server.getBlockEntity(pos) instanceof PhysicsCabBlockEntity cab) {
            cab.setVehicleTag(tag);
            context.getPlayer().displayClientMessage(Component.translatable(
                    "item.raildispatch.vehicle_tag.bound_to_cab", tag).withStyle(ChatFormatting.GREEN), true);
            return finish(context, stack);
        }

        Optional<VehicleHandle> vehicle = RailServices.physics().vehicleOf(server, pos);
        if (vehicle.isPresent()) {
            RailTags.get(server).bindVehicle(tag, vehicle.get().id());
            context.getPlayer().displayClientMessage(Component.translatable(
                    "item.raildispatch.vehicle_tag.bound", tag, vehicle.get().name())
                    .withStyle(ChatFormatting.GREEN), true);
            return finish(context, stack);
        }

        context.getPlayer().displayClientMessage(
                Component.translatable("item.raildispatch.vehicle_tag.unbound", tag)
                        .withStyle(ChatFormatting.RED),
                true);
        return InteractionResult.FAIL;
    }

    private InteractionResult finish(UseOnContext context, ItemStack stack) {
        if (RDConfig.consumeTags() && context.getPlayer() != null
                && !context.getPlayer().getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.SUCCESS;
    }
}
