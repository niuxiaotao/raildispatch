package com.raildispatch.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * One Simurail automatic coupler on a vehicle. {@code end} is the plot-space jaw position from the
 * coupler's {@code globalEndPos}; {@code worldEnd} is the same point transformed into world space.
 */
public record CouplerInfo(BlockPos pos, Vec3 end, Vec3 worldEnd, Vec3 outward, boolean connected) {
}
