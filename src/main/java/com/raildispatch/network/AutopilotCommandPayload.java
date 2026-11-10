package com.raildispatch.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A command from the autopilot console's screen: set a destination and start, stop, or clear.
 *
 * <p>Client to server, because the destination is typed on the client. The server never trusts the text
 * as anything but a name to look up - it is clamped for length and resolved against the track network,
 * which is the same treatment a station name in a Create schedule receives.
 */
public record AutopilotCommandPayload(BlockPos console, String destination, int action, int clock)
        implements CustomPacketPayload {

    public static final Type<AutopilotCommandPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("raildispatch", "autopilot_command"));

    public static final StreamCodec<ByteBuf, AutopilotCommandPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, AutopilotCommandPayload::console,
            ByteBufCodecs.stringUtf8(96), AutopilotCommandPayload::destination,
            ByteBufCodecs.VAR_INT, AutopilotCommandPayload::action,
            ByteBufCodecs.VAR_INT, AutopilotCommandPayload::clock,
            AutopilotCommandPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
