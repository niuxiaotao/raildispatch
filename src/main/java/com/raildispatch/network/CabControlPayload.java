package com.raildispatch.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The movement keys a player is holding while they have taken control of a cab, sent client to server.
 *
 * <p>This packet has to exist. In 1.21.1 the server has no idea which movement keys a player is pressing -
 * {@code net.minecraft.world.entity.player.Input} does not exist in this version and a player's position is
 * driven directly by the position packets - so W/S control cannot be read server side. Reading
 * {@code Player#zza} was tried first and measured at zero in game while W was held.
 */
public record CabControlPayload(BlockPos cab, boolean forward, boolean backward, boolean brake)
        implements CustomPacketPayload {

    public static final Type<CabControlPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("raildispatch", "cab_control"));

    public static final StreamCodec<ByteBuf, CabControlPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CabControlPayload::cab,
            ByteBufCodecs.BOOL, CabControlPayload::forward,
            ByteBufCodecs.BOOL, CabControlPayload::backward,
            ByteBufCodecs.BOOL, CabControlPayload::brake,
            CabControlPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
