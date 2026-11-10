package com.raildispatch.block.entity;

import com.raildispatch.RailDispatch;
import com.raildispatch.data.RailTags;
import com.raildispatch.registry.RDBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stores the destination code of one track. The hump controller matches a wagon's address tag
 * against these plates to decide which way to throw the switches.
 */
public class AddressPlateBlockEntity extends BlockEntity {

    private String address = "";

    public AddressPlateBlockEntity(BlockPos pos, BlockState state) {
        super(RDBlockEntities.ADDRESS_PLATE.get(), pos, state);
    }

    public String address() {
        return address;
    }

    public boolean hasAddress() {
        return !address.isEmpty();
    }

    public void setAddress(String address) {
        this.address = address == null ? "" : address;
        setChanged();
        registerWithBook();
        sync();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        registerWithBook();
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel server) {
            RailTags.get(server).unregisterPlate(address, GlobalPos.of(server.dimension(), worldPosition));
        }
        super.setRemoved();
    }

    private void registerWithBook() {
        if (level instanceof ServerLevel server && !address.isEmpty()) {
            RailTags.get(server).registerPlate(address, GlobalPos.of(server.dimension(), worldPosition));
        }
    }

    private void sync() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void reportTo(Player player) {
        if (hasAddress()) {
            player.displayClientMessage(Component.translatable("raildispatch.address_plate.status", address), false);
        } else {
            player.displayClientMessage(Component.translatable("raildispatch.address_plate.empty"), false);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("Address", address);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        address = tag.getString("Address");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Logs an unexpected state once per session, to keep the log readable. */
    static void warnOnce(String message) {
        RailDispatch.LOGGER.warn(message);
    }
}
