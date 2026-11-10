package com.raildispatch.registry;

import com.raildispatch.RailDispatch;
import com.raildispatch.item.AddressTagItem;
import com.raildispatch.item.VehicleTagItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item registry: the two tag items plus a block item for every block. */
public final class RDItems {

    private RDItems() {}

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RailDispatch.MOD_ID);

    public static final DeferredItem<VehicleTagItem> VEHICLE_TAG = ITEMS.register("vehicle_tag",
            () -> new VehicleTagItem(new net.minecraft.world.item.Item.Properties().stacksTo(16)));

    public static final DeferredItem<AddressTagItem> ADDRESS_TAG = ITEMS.register("address_tag",
            () -> new AddressTagItem(new net.minecraft.world.item.Item.Properties().stacksTo(16)));

    public static final DeferredItem<net.minecraft.world.item.BlockItem> PHYSICS_CAB_ITEM =
            ITEMS.registerSimpleBlockItem("physics_cab", RDBlocks.PHYSICS_CAB);

    public static final DeferredItem<net.minecraft.world.item.BlockItem> AUTOPILOT_CONSOLE_ITEM =
            ITEMS.registerSimpleBlockItem("autopilot_console", RDBlocks.AUTOPILOT_CONSOLE);

    /**
     * The dispatch centre is placed the way a Create train signal is, so its item is Create's
     * {@link TrackTargetingBlockItem} bound to our edge point type: right-click a rail to select it,
     * then place the block to link it to that track.
     */
    public static final DeferredItem<com.simibubi.create.content.trains.track.TrackTargetingBlockItem>
            DISPATCH_CENTER_ITEM = ITEMS.register("dispatch_center",
                    () -> new com.simibubi.create.content.trains.track.TrackTargetingBlockItem(
                            RDBlocks.DISPATCH_CENTER.get(),
                            new net.minecraft.world.item.Item.Properties(),
                            RDPoints.DISPATCH_CENTER));

    public static final DeferredItem<net.minecraft.world.item.BlockItem> ADDRESS_PLATE_ITEM =
            ITEMS.registerSimpleBlockItem("address_plate", RDBlocks.ADDRESS_PLATE);

    /** Track devices: select a rail first, exactly like a Train Station or Train Signal. */
    public static final DeferredItem<com.simibubi.create.content.trains.track.TrackTargetingBlockItem>
            RETARDER_ITEM = ITEMS.register("retarder",
                    () -> new com.simibubi.create.content.trains.track.TrackTargetingBlockItem(
                            RDBlocks.RETARDER.get(),
                            new net.minecraft.world.item.Item.Properties(),
                            RDPoints.RETARDER));

    public static final DeferredItem<com.simibubi.create.content.trains.track.TrackTargetingBlockItem>
            AUTOMATIC_UNCOUPLER_ITEM = ITEMS.register("automatic_uncoupler",
                    () -> new com.simibubi.create.content.trains.track.TrackTargetingBlockItem(
                            RDBlocks.AUTOMATIC_UNCOUPLER.get(),
                            new net.minecraft.world.item.Item.Properties(),
                            RDPoints.AUTOMATIC_UNCOUPLER));

    public static final DeferredItem<com.simibubi.create.content.trains.track.TrackTargetingBlockItem>
            SMART_RETARDER_ITEM = ITEMS.register("smart_retarder",
                    () -> new com.simibubi.create.content.trains.track.TrackTargetingBlockItem(
                            RDBlocks.SMART_RETARDER.get(),
                            new net.minecraft.world.item.Item.Properties(),
                            RDPoints.SMART_RETARDER));

    /** Hand-held yard terminal; right-click a vehicle to open its destination/consist/service panel. */
    public static final DeferredItem<com.raildispatch.item.YardTerminalItem> YARD_TERMINAL_ITEM =
            ITEMS.register("yard_terminal",
                    () -> new com.raildispatch.item.YardTerminalItem(
                            new net.minecraft.world.item.Item.Properties().stacksTo(1)));

    public static final DeferredItem<net.minecraft.world.item.BlockItem> BALLAST_ITEM =
            ITEMS.registerSimpleBlockItem("ballast", RDBlocks.BALLAST);

    public static final DeferredItem<net.minecraft.world.item.BlockItem> BALLAST_STAIRS_ITEM =
            ITEMS.registerSimpleBlockItem("ballast_stairs", RDBlocks.BALLAST_STAIRS);

    public static final DeferredItem<net.minecraft.world.item.BlockItem> BALLAST_SLAB_ITEM =
            ITEMS.registerSimpleBlockItem("ballast_slab", RDBlocks.BALLAST_SLAB);

    public static final DeferredItem<net.minecraft.world.item.BlockItem> BALLAST_STEP_ITEM =
            ITEMS.registerSimpleBlockItem("ballast_step", RDBlocks.BALLAST_STEP);

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
