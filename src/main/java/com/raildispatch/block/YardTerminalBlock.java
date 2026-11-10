package com.raildispatch.block;

import com.mojang.serialization.MapCodec;
import com.raildispatch.api.RailServices;
import com.raildispatch.api.VehicleHandle;
import com.raildispatch.block.entity.YardTerminalBlockEntity;
import com.raildispatch.data.YardAssignments;
import com.raildispatch.menu.YardTerminalMenu;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Legacy vehicle-mounted terminal block, kept loadable for worlds that placed it. The hand-held
 * terminal is the item players use now; this block simply opens the same per-vehicle panel.
 */
public class YardTerminalBlock extends Block implements EntityBlock {

    public static final MapCodec<YardTerminalBlock> CODEC = simpleCodec(YardTerminalBlock::new);
    private static final VoxelShape SHAPE = box(0.0D, 0.0D, 0.0D, 16.0D, 12.0D, 16.0D);

    public YardTerminalBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new YardTerminalBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        YardTerminalMenu.open(serverPlayer, server);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
