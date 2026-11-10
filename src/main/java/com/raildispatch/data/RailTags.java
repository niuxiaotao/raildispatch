package com.raildispatch.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The server-wide tag book. It is the addressing layer of the mod:
 *
 * <ul>
 *   <li>a <b>vehicle tag</b> maps a name to one physical vehicle, which is how a schedule entry
 *       says "couple <i>that</i> vehicle" or "run to <i>that</i> vehicle";</li>
 *   <li>an <b>address tag</b> is a destination code: written onto a wagon it says where that wagon
 *       has to go, registered on an address plate it says which track carries that destination.</li>
 * </ul>
 *
 * <p>It is stored once, in the overworld's data storage, so every dimension sees the same book.
 */
public class RailTags extends SavedData {

    public static final String FILE_ID = "raildispatch_tags";

    private final Map<String, UUID> vehicleTags = new LinkedHashMap<>();
    private final Map<UUID, String> vehicleAddresses = new HashMap<>();
    private final Map<String, List<GlobalPos>> plates = new HashMap<>();

    public static SavedData.Factory<RailTags> factory() {
        return new SavedData.Factory<>(RailTags::new, RailTags::load);
    }

    /** The tag book of a server. Safe to call from the server side only. */
    public static RailTags get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    // ---------------------------------------------------------------- vehicle tags

    public void bindVehicle(String tag, UUID vehicle) {
        vehicleTags.put(tag, vehicle);
        setDirty();
    }

    public void unbindVehicle(String tag) {
        if (vehicleTags.remove(tag) != null) {
            setDirty();
        }
    }

    public Optional<UUID> vehicleFor(String tag) {
        return Optional.ofNullable(vehicleTags.get(tag));
    }

    /** The tag a vehicle answers to, if any. Ties resolve to the first bound name. */
    public Optional<String> tagOf(UUID vehicle) {
        for (Map.Entry<String, UUID> entry : vehicleTags.entrySet()) {
            if (entry.getValue().equals(vehicle)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    public void forgetVehicle(UUID vehicle) {
        boolean changed = vehicleTags.values().removeIf(vehicle::equals);
        changed |= vehicleAddresses.remove(vehicle) != null;
        if (changed) {
            setDirty();
        }
    }

    public Set<String> vehicleTagNames() {
        return Set.copyOf(vehicleTags.keySet());
    }

    // ---------------------------------------------------------------- address tags

    /** Writes a destination code onto a wagon. */
    public void setVehicleAddress(UUID vehicle, String address) {
        if (address == null || address.isEmpty()) {
            if (vehicleAddresses.remove(vehicle) != null) {
                setDirty();
            }
        } else {
            vehicleAddresses.put(vehicle, address);
            setDirty();
        }
    }

    public Optional<String> addressOf(UUID vehicle) {
        return Optional.ofNullable(vehicleAddresses.get(vehicle));
    }

    public void clearVehicleAddress(UUID vehicle) {
        if (vehicleAddresses.remove(vehicle) != null) {
            setDirty();
        }
    }

    /** Registers a track as the destination carrying {@code address}. */
    public void registerPlate(String address, GlobalPos pos) {
        if (address == null || address.isEmpty()) {
            return;
        }
        List<GlobalPos> list = plates.computeIfAbsent(address, key -> new ArrayList<>());
        if (!list.contains(pos)) {
            list.add(pos);
            setDirty();
        }
    }

    public void unregisterPlate(String address, GlobalPos pos) {
        List<GlobalPos> list = plates.get(address);
        if (list != null && list.remove(pos)) {
            if (list.isEmpty()) {
                plates.remove(address);
            }
            setDirty();
        }
    }

    public List<GlobalPos> platesFor(String address) {
        return List.copyOf(plates.getOrDefault(address, List.of()));
    }

    public Set<String> knownAddresses() {
        return Set.copyOf(plates.keySet());
    }

    // ---------------------------------------------------------------- persistence

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag vehicles = new ListTag();
        vehicleTags.forEach((name, id) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Tag", name);
            entry.putUUID("Vehicle", id);
            vehicles.add(entry);
        });
        tag.put("VehicleTags", vehicles);

        ListTag addresses = new ListTag();
        vehicleAddresses.forEach((id, address) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Vehicle", id);
            entry.putString("Address", address);
            addresses.add(entry);
        });
        tag.put("VehicleAddresses", addresses);

        ListTag plateList = new ListTag();
        plates.forEach((address, positions) -> {
            for (GlobalPos pos : positions) {
                CompoundTag entry = new CompoundTag();
                entry.putString("Address", address);
                entry.putString("Dimension", pos.dimension().location().toString());
                entry.putLong("Pos", pos.pos().asLong());
                plateList.add(entry);
            }
        });
        tag.put("Plates", plateList);
        return tag;
    }

    public static RailTags load(CompoundTag tag, HolderLookup.Provider registries) {
        RailTags data = new RailTags();

        ListTag vehicles = tag.getList("VehicleTags", Tag.TAG_COMPOUND);
        for (int i = 0; i < vehicles.size(); i++) {
            CompoundTag entry = vehicles.getCompound(i);
            if (entry.hasUUID("Vehicle")) {
                data.vehicleTags.put(entry.getString("Tag"), entry.getUUID("Vehicle"));
            }
        }

        ListTag addresses = tag.getList("VehicleAddresses", Tag.TAG_COMPOUND);
        for (int i = 0; i < addresses.size(); i++) {
            CompoundTag entry = addresses.getCompound(i);
            if (entry.hasUUID("Vehicle")) {
                data.vehicleAddresses.put(entry.getUUID("Vehicle"), entry.getString("Address"));
            }
        }

        ListTag plateList = tag.getList("Plates", Tag.TAG_COMPOUND);
        for (int i = 0; i < plateList.size(); i++) {
            CompoundTag entry = plateList.getCompound(i);
            ResourceLocation dimension = ResourceLocation.tryParse(entry.getString("Dimension"));
            if (dimension == null) {
                continue;
            }
            GlobalPos pos = GlobalPos.of(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, dimension), BlockPos.of(entry.getLong("Pos")));
            data.plates.computeIfAbsent(entry.getString("Address"), key -> new ArrayList<>()).add(pos);
        }
        return data;
    }
}
