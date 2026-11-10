package com.raildispatch.menu;

import com.raildispatch.api.RailServices;
import com.raildispatch.api.TerminalInfo;
import com.raildispatch.api.VehicleHandle;
import com.raildispatch.data.YardAssignments;
import com.raildispatch.registry.RDMenus;
import com.raildispatch.train.YardState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The terminal table. It lists every physics vehicle and every physically coupled consist, so the
 * player can type a destination per vehicle and a service number per consist without clicking a car.
 */
public class YardTerminalMenu extends AbstractContainerMenu {

    public record VehicleRow(UUID vehicle, String label, String group, String destination, String type) {
    }

    public record ConsistRow(String group, String label, int cars, String service) {
    }

    private final List<VehicleRow> vehicles;
    private final List<ConsistRow> consists;

    public YardTerminalMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf data) {
        super(RDMenus.YARD_TERMINAL.get(), containerId);
        int vehicleCount = data.readVarInt();
        List<VehicleRow> vehicleRows = new ArrayList<>(vehicleCount);
        for (int i = 0; i < vehicleCount; i++) {
            vehicleRows.add(new VehicleRow(data.readUUID(), data.readUtf(32), data.readUtf(32),
                    data.readUtf(48), data.readUtf(24)));
        }
        int consistCount = data.readVarInt();
        List<ConsistRow> consistRows = new ArrayList<>(consistCount);
        for (int i = 0; i < consistCount; i++) {
            consistRows.add(new ConsistRow(data.readUtf(32), data.readUtf(32), data.readVarInt(),
                    data.readUtf(32)));
        }
        this.vehicles = List.copyOf(vehicleRows);
        this.consists = List.copyOf(consistRows);
        addPlayerSlots(inventory);
    }

    private YardTerminalMenu(int containerId, Inventory inventory, List<VehicleRow> vehicles,
                             List<ConsistRow> consists) {
        super(RDMenus.YARD_TERMINAL.get(), containerId);
        this.vehicles = vehicles;
        this.consists = consists;
        addPlayerSlots(inventory);
    }

    private void addPlayerSlots(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 166 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 224));
        }
    }

    public static void open(ServerPlayer player, ServerLevel level) {
        List<VehicleHandle> vehicles = new ArrayList<>(RailServices.physics().vehicles(level));
        Map<UUID, List<UUID>> groups = new LinkedHashMap<>();
        for (VehicleHandle vehicle : vehicles) {
            List<UUID> ids = new ArrayList<>();
            for (VehicleHandle handle : YardState.handles(level, vehicle)) {
                ids.add(handle.id());
            }
            ids.sort(UUID::compareTo);
            groups.putIfAbsent(ids.get(0), ids);
        }

        Map<UUID, String> groupOf = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<UUID>> entry : groups.entrySet()) {
            // A single car is a vehicle, not a consist: it gets no consist row and no service number.
            String group = entry.getValue().size() > 1 ? shortId(entry.getKey()) : "";
            for (UUID id : entry.getValue()) {
                groupOf.put(id, group);
            }
        }

        List<VehicleRow> vehicleRows = new ArrayList<>();
        for (VehicleHandle vehicle : vehicles) {
            TerminalInfo info = YardAssignments.get(level).get(vehicle.id());
            String name = YardState.nameplate(level, vehicle).orElse(label(vehicle));
            if (name.length() > 32) {
                name = name.substring(0, 32);
            }
            vehicleRows.add(new VehicleRow(vehicle.id(), name, groupOf.getOrDefault(vehicle.id(), ""),
                    info.destination(), info.type().isBlank() ? "wagon" : info.type()));
        }

        Set<String> usedServices = new HashSet<>();
        for (List<UUID> ids : groups.values()) {
            if (ids.size() < 2) {
                continue;
            }
            for (UUID id : ids) {
                String service = YardAssignments.get(level).get(id).service();
                if (!service.isBlank()) {
                    usedServices.add(service);
                }
            }
        }
        List<ConsistRow> consistRows = new ArrayList<>();
        int autoService = 1;
        for (Map.Entry<UUID, List<UUID>> entry : groups.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            String service = "";
            for (UUID id : entry.getValue()) {
                TerminalInfo info = YardAssignments.get(level).get(id);
                if (!info.service().isBlank()) {
                    service = info.service();
                    break;
                }
            }
            if (service.isBlank()) {
                String generated;
                do {
                    generated = String.format(java.util.Locale.ROOT, "S%03d", autoService++);
                } while (usedServices.contains(generated));
                service = generated;
                usedServices.add(service);
            }
            consistRows.add(new ConsistRow(shortId(entry.getKey()),
                    shortId(entry.getKey()) + " x" + entry.getValue().size(),
                    entry.getValue().size(), service));
        }

        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, p) -> new YardTerminalMenu(id, inventory, vehicleRows, consistRows),
                        Component.translatable("raildispatch.terminal.title")),
                buffer -> {
                    buffer.writeVarInt(vehicleRows.size());
                    for (VehicleRow row : vehicleRows) {
                        buffer.writeUUID(row.vehicle());
                        buffer.writeUtf(row.label(), 32);
                        buffer.writeUtf(row.group(), 32);
                        buffer.writeUtf(row.destination(), 48);
                        buffer.writeUtf(row.type(), 24);
                    }
                    buffer.writeVarInt(consistRows.size());
                    for (ConsistRow row : consistRows) {
                        buffer.writeUtf(row.group(), 32);
                        buffer.writeUtf(row.label(), 32);
                        buffer.writeVarInt(row.cars());
                        buffer.writeUtf(row.service(), 32);
                    }
                });
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String label(VehicleHandle vehicle) {
        var position = vehicle.position();
        return String.format(java.util.Locale.ROOT, "%.1f/%.1f", position.x, position.z);
    }

    public List<VehicleRow> vehicles() {
        return vehicles;
    }

    public List<ConsistRow> consists() {
        return consists;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
