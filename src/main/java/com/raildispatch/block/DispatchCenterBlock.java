package com.raildispatch.block;

import com.mojang.serialization.MapCodec;
import com.raildispatch.block.entity.DispatchCenterBlockEntity;
import com.raildispatch.registry.RDBlockEntities;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The dispatch centre. When one is in range of a cab, it becomes the authority for routes: it
 * calculates the path over Create's track graph, locks the switches along it and hands out one route
 * per vehicle. Without one, cabs fall back to locking their own route locally.
 *
 * <p>It is placed the way a train signal is, through {@link IBE} plus a track targeting edge point:
 * the item first selects a rail, then the block is placed against it and linked to that track through
 * {@code TrackTargetingBehaviour}. That is why this block has no {@code newBlockEntity} or ticker of
 * its own - {@link IBE} supplies both, and the block entity is a Create {@code SmartBlockEntity}.
 */
public class DispatchCenterBlock extends Block implements IBE<DispatchCenterBlockEntity> {

    public static final MapCodec<DispatchCenterBlock> CODEC = simpleCodec(DispatchCenterBlock::new);

    public DispatchCenterBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public Class<DispatchCenterBlockEntity> getBlockEntityClass() {
        return DispatchCenterBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends DispatchCenterBlockEntity> getBlockEntityType() {
        return RDBlockEntities.DISPATCH_CENTER.get();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof DispatchCenterBlockEntity centre) {
            if (!level.isClientSide) {
                centre.reportTo(player);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        // Exactly what Create's own signal block does: this tears the edge point off the track graph.
        // Skipping it would leave a phantom dispatch centre on the graph forever, because the graph
        // persists its points independently of the block. Note that super is deliberately not called,
        // mirroring SignalBlock.
        IBE.onRemove(state, level, pos, newState);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof DispatchCenterBlockEntity centre) {
            // 0..15 lit lamps, three per granted route, capped for readability.
            return Math.min(15, centre.activeRoutes() * 3);
        }
        return 0;
    }
}
