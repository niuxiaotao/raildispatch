package com.raildispatch.block;

import com.mojang.serialization.MapCodec;
import com.raildispatch.block.entity.AutomaticUncouplerBlockEntity;
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
 * A track automatic uncoupler. Like a station or signal it is bound to a selected rail through a
 * {@link com.raildispatch.train.AutomaticUncouplerPoint}; the selected point, not the block's own
 * radius, decides which vehicle it acts on.
 */
public class AutomaticUncouplerBlock extends Block implements IBE<AutomaticUncouplerBlockEntity> {

    public static final MapCodec<AutomaticUncouplerBlock> CODEC = simpleCodec(AutomaticUncouplerBlock::new);
    private static final VoxelShape SHAPE = Block.box(3.0D, 0.0D, 3.0D, 13.0D, 14.0D, 13.0D);

    public AutomaticUncouplerBlock(Properties properties) {
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
    public Class<AutomaticUncouplerBlockEntity> getBlockEntityClass() {
        return AutomaticUncouplerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends AutomaticUncouplerBlockEntity> getBlockEntityType() {
        return RDBlockEntities.AUTOMATIC_UNCOUPLER.get();
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        IBE.onRemove(state, level, pos, newState);
    }
}
