package com.raildispatch.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Legacy vehicle-mounted terminal retained for worlds that placed the old block. Hand-held terminals
 * now store their assignment in {@link com.raildispatch.data.YardAssignments}; this block's values are
 * only a fallback for an already placed block.
 */
public class YardTerminalBlockEntity extends BlockEntity {

    private String destination = "";
    private String consist = "";
    private String service = "";

    public YardTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(com.raildispatch.registry.RDBlockEntities.YARD_TERMINAL.get(), pos, state);
    }

    public String destination() {
        return destination;
    }

    public String consist() {
        return consist;
    }

    public String service() {
        return service;
    }

    public void setData(String destination, String consist, String service) {
        this.destination = clean(destination, 48);
        this.consist = clean(consist, 32);
        this.service = clean(service, 32);
        setChanged();
    }

    private static String clean(String value, int limit) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > limit ? trimmed.substring(0, limit) : trimmed;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("Destination", destination);
        tag.putString("Consist", consist);
        tag.putString("Service", service);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        destination = tag.getString("Destination");
        consist = tag.getString("Consist");
        service = tag.getString("Service");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }
}
