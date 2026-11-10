package com.raildispatch.train;

import com.simibubi.create.content.trains.signal.SingleBlockEntityEdgePoint;

/**
 * The dispatch centre's position on Create's track graph.
 *
 * <p>It exists so the block can be placed the way a train signal is: the item selects a track, and the
 * block is then linked to that track through a {@code TrackTargetingBehaviour} pointing at this edge
 * point type. That is also what lets a dispatch centre know which piece of railway it stands next to,
 * rather than guessing from a radius.
 *
 * <p>All the behaviour comes from {@link SingleBlockEntityEdgePoint}, which already implements the
 * whole {@code TrackEdgePoint} contract for "an edge point that is backed by one block entity" -
 * including the block position and dimension and their persistence. There is deliberately nothing to
 * add here: an empty, concrete subclass is the correct amount of code.
 */
public class DispatchCenterPoint extends SingleBlockEntityEdgePoint {

    public DispatchCenterPoint() {
    }
}
