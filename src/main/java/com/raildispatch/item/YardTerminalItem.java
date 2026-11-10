package com.raildispatch.item;

import com.raildispatch.menu.YardTerminalMenu;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Hand-held yard terminal. Right-click anywhere to open the whole yard table: every vehicle with a
 * destination field, and every physically coupled consist with a service-number field.
 */
public class YardTerminalItem extends Item {

    public YardTerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        open(level, player);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        open(context.getLevel(), context.getPlayer());
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    private static void open(Level level, Player player) {
        if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) {
            YardTerminalMenu.open(serverPlayer, server);
        }
    }
}
