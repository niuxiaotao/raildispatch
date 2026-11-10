package com.raildispatch.network;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Saves the whole hand-held terminal table: destination per vehicle and service per consist. */
public record YardTerminalPayload(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(UUID vehicle, String destination, String consist, String service, String type) {
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Entry::vehicle,
                ByteBufCodecs.stringUtf8(48), Entry::destination,
                ByteBufCodecs.stringUtf8(32), Entry::consist,
                ByteBufCodecs.stringUtf8(32), Entry::service,
                ByteBufCodecs.stringUtf8(24), Entry::type,
                Entry::new);
    }

    public static final Type<YardTerminalPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("raildispatch", "yard_terminal"));

    private static final StreamCodec<ByteBuf, List<Entry>> ENTRY_LIST = new StreamCodec<>() {
        @Override
        public List<Entry> decode(ByteBuf buffer) {
            int count = ByteBufCodecs.VAR_INT.decode(buffer);
            List<Entry> entries = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(Entry.STREAM_CODEC.decode(buffer));
            }
            return entries;
        }

        @Override
        public void encode(ByteBuf buffer, List<Entry> entries) {
            ByteBufCodecs.VAR_INT.encode(buffer, entries.size());
            for (Entry entry : entries) {
                Entry.STREAM_CODEC.encode(buffer, entry);
            }
        }
    };

    public static final StreamCodec<ByteBuf, YardTerminalPayload> STREAM_CODEC = StreamCodec.composite(
            ENTRY_LIST, YardTerminalPayload::entries,
            YardTerminalPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
