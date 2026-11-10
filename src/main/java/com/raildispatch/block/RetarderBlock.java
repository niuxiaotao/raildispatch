package com.raildispatch.block;

import com.mojang.serialization.MapCodec;
import com.raildispatch.block.entity.RetarderBlockEntity;
import com.raildispatch.registry.RDBlockEntities;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A track retarder. It is placed like a train station or signal: right-click a rail with the item to
 * select the track, then place the block against it. The block's {@code TrackTargetingBehaviour} binds
 * it to a {@link com.raildispatch.train.RetarderPoint} on that track, so the brake acts at the selected
 * point instead of on a guessed radius.
 */
public class RetarderBlock extends Block implements IBE<RetarderBlockEntity> {

    public static final MapCodec<RetarderBlock> CODEC = simpleCodec(RetarderBlock::new);
    private static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 2.0D, 16.0D);

    public RetarderBlock(Properties properties) {
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

    @Override
    public Class<RetarderBlockEntity> getBlockEntityClass() {
        return RetarderBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends RetarderBlockEntity> getBlockEntityType() {
        return RDBlockEntities.RETARDER.get();
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        IBE.onRemove(state, level, pos, newState);
    }
}
