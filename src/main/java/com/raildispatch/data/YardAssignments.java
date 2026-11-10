package com.raildispatch.data;

import com.raildispatch.api.TerminalInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persistent destination/consist/service assignment made with the hand-held yard terminal.
 *
 * <p>The old Simulated nameplate was a physical label on one car. A hand-held terminal can program
 * several vehicles without fitting a block to each, so assignments live in world save data keyed by the
 * vehicle's sub-level UUID.
 */
public class YardAssignments extends SavedData {

    private static final String DATA_NAME = "raildispatch_yard";

    private final Map<UUID, TerminalInfo> assignments = new HashMap<>();

    public static YardAssignments get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(YardAssignments::new, YardAssignments::load), DATA_NAME);
    }

    public TerminalInfo get(UUID vehicle) {
        return assignments.getOrDefault(vehicle, new TerminalInfo("", "", "", ""));
    }

    public void set(UUID vehicle, TerminalInfo info) {
        assignments.put(vehicle, info);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, TerminalInfo> entry : assignments.entrySet()) {
            TerminalInfo info = entry.getValue();
            if (info.destination().isBlank() && info.consist().isBlank() && info.service().isBlank()
                    && info.type().isBlank()) {
                continue;
            }
            CompoundTag row = new CompoundTag();
            row.putUUID("Vehicle", entry.getKey());
            row.putString("Destination", info.destination());
            row.putString("Consist", info.consist());
            row.putString("Service", info.service());
            row.putString("Type", info.type());
            list.add(row);
        }
        tag.put("Assignments", list);
        return tag;
    }

    private static YardAssignments load(CompoundTag tag, HolderLookup.Provider registries) {
        YardAssignments data = new YardAssignments();
        ListTag list = tag.getList("Assignments", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            if (!row.hasUUID("Vehicle")) {
                continue;
            }
            data.assignments.put(row.getUUID("Vehicle"), new TerminalInfo(
                    row.getString("Destination"), row.getString("Consist"),
                    row.getString("Service"), row.getString("Type")));
        }
        return data;
    }
}
