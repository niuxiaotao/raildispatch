package com.raildispatch.registry;

import com.raildispatch.train.DispatchCenterPoint;
import com.simibubi.create.content.trains.graph.EdgePointType;
import net.minecraft.resources.ResourceLocation;

/**
 * Create edge point types added by this mod.
 *
 * <p>{@link EdgePointType#register} is Create's public extension point for "a thing that lives on the
 * track graph", and it is what makes signal-style placement possible: the placement item stores a
 * selected track on itself and the block entity then writes a {@code TrackTargetingBehaviour} that
 * resolves to a point of this type.
 *
 * <p>Registration must happen before any track graph is loaded, because a graph resolves its points by
 * looking their type up by id. {@link #register()} exists purely to force this class to initialise at
 * mod construction time instead of leaving it to whenever the item happens to be created.
 */
public final class RDPoints {

    private RDPoints() {}

    public static final EdgePointType<DispatchCenterPoint> DISPATCH_CENTER =
            EdgePointType.register(
                    ResourceLocation.parse("raildispatch:dispatch_center"),
                    DispatchCenterPoint::new);

    public static final EdgePointType<com.raildispatch.train.RetarderPoint> RETARDER =
            EdgePointType.register(
                    ResourceLocation.parse("raildispatch:retarder"),
                    com.raildispatch.train.RetarderPoint::new);

    public static final EdgePointType<com.raildispatch.train.AutomaticUncouplerPoint> AUTOMATIC_UNCOUPLER =
            EdgePointType.register(
                    ResourceLocation.parse("raildispatch:automatic_uncoupler"),
                    com.raildispatch.train.AutomaticUncouplerPoint::new);

    public static final EdgePointType<com.raildispatch.train.SmartRetarderPoint> SMART_RETARDER =
            EdgePointType.register(
                    ResourceLocation.parse("raildispatch:smart_retarder"),
                    com.raildispatch.train.SmartRetarderPoint::new);

    /** Forces class initialisation; see the class comment for why that has to happen early. */
    public static void register() {
        if (DISPATCH_CENTER == null || RETARDER == null || AUTOMATIC_UNCOUPLER == null
                || SMART_RETARDER == null) {
            throw new IllegalStateException("rail dispatch edge point type failed to register");
        }
    }
}
