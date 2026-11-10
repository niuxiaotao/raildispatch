package com.raildispatch.menu;

import com.raildispatch.api.Schedules;
import com.raildispatch.block.entity.AutopilotConsoleBlockEntity;
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
 * The autopilot console's screen container: no slots, only the live status.
 *
 * <p>A container with no slots looks unusual, but it is the right vehicle for the status sync: vanilla
 * mirrors {@link ContainerData} for an open menu without a custom packet, and the player inventory shown
 * beside the screen is what lets the player keep a station name in hand while typing it.
 */
public class AutopilotConsoleMenu extends AbstractContainerMenu {

    private final AutopilotConsoleBlockEntity console;
    private final IItemHandler handler;
    private final ContainerData data;
    private final BlockPos pos;

    /** Used by the menu type: only the block position travels over the wire. */
    public AutopilotConsoleMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, playerInventory, extraData.readBlockPos());
    }

    private AutopilotConsoleMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(RDMenus.AUTOPILOT_CONSOLE.get(), containerId);
        this.pos = pos;
        this.console = resolve(playerInventory, pos);
        this.handler = console != null ? console.inventory() : new ItemStackHandler(1);
        this.data = console != null
                ? console.statusData()
                : new SimpleContainerData(AutopilotConsoleBlockEntity.DATA_COUNT);
        addSlot(new SlotItemHandler(handler, AutopilotConsoleBlockEntity.SLOT_SCHEDULE, 8, 18) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return Schedules.isScheduleItem(stack);
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, column + row * 9 + 9, 46 + column * 18, 140 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column, 46 + column * 18, 198));
        }
        addDataSlots(data);
    }

    public AutopilotConsoleMenu(int containerId, Inventory playerInventory, AutopilotConsoleBlockEntity console) {
        super(RDMenus.AUTOPILOT_CONSOLE.get(), containerId);
        this.pos = console == null ? BlockPos.ZERO : console.getBlockPos();
        this.console = console;
        this.handler = console != null ? console.inventory() : new ItemStackHandler(1);
        this.data = console != null
                ? console.statusData()
                : new SimpleContainerData(AutopilotConsoleBlockEntity.DATA_COUNT);
        addSlot(new SlotItemHandler(handler, AutopilotConsoleBlockEntity.SLOT_SCHEDULE, 8, 18) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return Schedules.isScheduleItem(stack);
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, column + row * 9 + 9, 46 + column * 18, 140 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column, 46 + column * 18, 198));
        }
        addDataSlots(data);
    }

    private static AutopilotConsoleBlockEntity resolve(Inventory playerInventory, BlockPos pos) {
        Level level = playerInventory.player.level();
        return level.getBlockEntity(pos) instanceof AutopilotConsoleBlockEntity found ? found : null;
    }

    public AutopilotConsoleBlockEntity console() {
        return console;
    }

    public BlockPos pos() {
        return pos;
    }

    public int status(int index) {
        return data.get(index);
    }

    @Override
    public boolean stillValid(Player player) {
        // Deliberately no distance check. An assembled structure's blocks live in a plot far from the
        // origin, so `player.distanceToSqr(pos)` is always enormous and the ordinary check would close the
        // screen the tick it opened. The block entity being present is the honest test here; the screen
        // offers a destination and a start button, nothing a distant player could exploit.
        //
        // A null console (the block entity was not found on this side) keeps the menu open instead of
        // closing it: the screen can still send commands, which the server applies to the block entity it
        // does have. Closing would turn a client-side lookup miss into "the console does nothing".
        return console == null || (!console.isRemoved() && console.getLevel() != null);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack result = stack.copy();
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
