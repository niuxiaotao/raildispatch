package com.raildispatch.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Driving parameters edited on the autopilot console's screen. */
public record AutopilotTuningPayload(BlockPos console, double maxSpeed, double acceleration,
                                     double brakeDecel, double approach, double smoothing)
        implements CustomPacketPayload {

    public static final Type<AutopilotTuningPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("raildispatch", "autopilot_tuning"));

    public static final StreamCodec<ByteBuf, AutopilotTuningPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                BlockPos.STREAM_CODEC.encode(buf, payload.console());
                buf.writeDouble(payload.maxSpeed());
                buf.writeDouble(payload.acceleration());
                buf.writeDouble(payload.brakeDecel());
                buf.writeDouble(payload.approach());
                buf.writeDouble(payload.smoothing());
            },
            buf -> new AutopilotTuningPayload(BlockPos.STREAM_CODEC.decode(buf),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
