package com.raildispatch.menu;

import com.raildispatch.api.Schedules;
import com.raildispatch.block.entity.PhysicsCabBlockEntity;
import com.raildispatch.registry.RDMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * The cab's screen: one slot for a Create train schedule, and the live status of the vehicle
 * mirrored through {@link ContainerData} so it needs no packet of its own.
 */
public class PhysicsCabMenu extends AbstractContainerMenu {

    private static final int SCHEDULE_SLOT_X = 80;
    private static final int SCHEDULE_SLOT_Y = 36;

    private final PhysicsCabBlockEntity cab;
    private final IItemHandler handler;
    private final ContainerData data;

    /** Used by the menu type: the block position is the only thing that travels over the wire. */
    public PhysicsCabMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, playerInventory, resolveFromPayload(playerInventory, extraData));
    }

    private static PhysicsCabBlockEntity resolveFromPayload(Inventory playerInventory,
                                                            RegistryFriendlyByteBuf extraData) {
        if (extraData.readableBytes() < Long.BYTES) {
            // A caller opened the menu through Player#openMenu(MenuProvider), which writes no payload.
            // Degrading to an empty menu beats throwing inside the menu factory on the client, where
            // the only visible symptom would be "right-click does nothing".
            com.raildispatch.RailDispatch.LOGGER.warn(
                    "Physics cab menu was opened without a block position in its payload");
            return null;
        }
        return resolve(playerInventory, extraData.readBlockPos());
    }

    public PhysicsCabMenu(int containerId, Inventory playerInventory, PhysicsCabBlockEntity cab) {
        super(RDMenus.PHYSICS_CAB.get(), containerId);
        this.cab = cab;
        this.handler = cab != null ? cab.inventory() : new ItemStackHandler(1);
        this.data = cab != null ? cab.statusData() : new SimpleContainerData(PhysicsCabBlockEntity.DATA_COUNT);

        addSlot(new SlotItemHandler(handler, PhysicsCabBlockEntity.SLOT_SCHEDULE, SCHEDULE_SLOT_X, SCHEDULE_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return Schedules.isScheduleItem(stack);
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, 84 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column, 8 + column * 18, 142));
        }
        addDataSlots(data);
    }

    private static PhysicsCabBlockEntity resolve(Inventory playerInventory, BlockPos pos) {
        Level level = playerInventory.player.level();
        if (level.getBlockEntity(pos) instanceof PhysicsCabBlockEntity found) {
            return found;
        }
        return null;
    }

    public PhysicsCabBlockEntity cab() {
        return cab;
    }

    public int status(int index) {
        return data.get(index);
    }

    @Override
    public boolean stillValid(Player player) {
        // No distance check, for the same reason as the autopilot console's menu: an assembled
        // structure's blocks live in a plot far from the origin, so `player.distanceToSqr(pos)` is
        // enormous and the vanilla check would close this screen on the tick it opened. The block entity
        // still existing is the honest test.
        return cab == null || (!cab.isRemoved() && cab.getLevel() != null);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();

        int machineSlots = 1;
        if (index < machineSlots) {
            if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (Schedules.isScheduleItem(stack)) {
            if (!moveItemStackTo(stack, 0, machineSlots, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }
}
