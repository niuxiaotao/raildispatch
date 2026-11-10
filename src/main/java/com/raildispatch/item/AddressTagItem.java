package com.raildispatch.item;

import com.raildispatch.RDConfig;
import com.raildispatch.api.RailServices;
import com.raildispatch.api.VehicleHandle;
import com.raildispatch.block.entity.AddressPlateBlockEntity;
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
 * A destination code for hump shunting.
 *
 * <p>Right-click a wagon with it to say where that wagon has to go; right-click an address plate
 * with it to say which track carries that destination. The hump controller then reads each wagon's
 * code and sends it down the matching track.
 */
public class AddressTagItem extends Item {

    public AddressTagItem(Properties properties) {
        super(properties);
    }

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
                    Component.translatable("raildispatch.address_tag.unnamed").withStyle(ChatFormatting.YELLOW),
                    true);
            return InteractionResult.FAIL;
        }

        BlockPos pos = context.getClickedPos();
        if (server.getBlockEntity(pos) instanceof AddressPlateBlockEntity plate) {
            plate.setAddress(tag);
            context.getPlayer().displayClientMessage(
                    Component.translatable("item.raildispatch.address_tag.plate_set", tag)
                            .withStyle(ChatFormatting.GREEN),
                    true);
            return finish(context, stack);
        }

        Optional<VehicleHandle> vehicle = RailServices.physics().vehicleOf(server, pos);
        if (vehicle.isPresent()) {
            RailTags.get(server).setVehicleAddress(vehicle.get().id(), tag);
            context.getPlayer().displayClientMessage(
                    Component.translatable("item.raildispatch.address_tag.written", tag, vehicle.get().name())
                            .withStyle(ChatFormatting.GREEN),
                    true);
            return finish(context, stack);
        }

        context.getPlayer().displayClientMessage(
                Component.translatable("item.raildispatch.address_tag.no_target", tag)
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
