package com.raildispatch.block;

import com.mojang.serialization.MapCodec;
import com.raildispatch.block.entity.SmartRetarderBlockEntity;
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
 * A smart retarder: instead of a redstone force it holds a configured gap to the wagon ahead,
 * braking the following free wagon when that gap closes. Placed like a station or signal, against a
 * selected rail.
 */
public class SmartRetarderBlock extends Block implements IBE<SmartRetarderBlockEntity> {

    public static final MapCodec<SmartRetarderBlock> CODEC = simpleCodec(SmartRetarderBlock::new);
    private static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 2.0D, 16.0D);

    public SmartRetarderBlock(Properties properties) {
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
    public Class<SmartRetarderBlockEntity> getBlockEntityClass() {
        return SmartRetarderBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends SmartRetarderBlockEntity> getBlockEntityType() {
        return RDBlockEntities.SMART_RETARDER.get();
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        IBE.onRemove(state, level, pos, newState);
    }
}
