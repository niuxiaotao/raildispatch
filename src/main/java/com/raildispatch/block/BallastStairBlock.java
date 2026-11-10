package com.raildispatch.block;

import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Vanilla-shaped stairs over a ballast base; only needed because StairBlock's constructor is protected. */
public class BallastStairBlock extends StairBlock {

    public BallastStairBlock(BlockState baseState, Properties properties) {
        super(baseState, properties);
    }
}
