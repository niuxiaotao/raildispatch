package com.raildispatch.registry;

import com.raildispatch.RailDispatch;
import com.raildispatch.block.entity.AddressPlateBlockEntity;
import com.raildispatch.block.entity.AutomaticUncouplerBlockEntity;
import com.raildispatch.block.entity.AutopilotConsoleBlockEntity;
import com.raildispatch.block.entity.DispatchCenterBlockEntity;
import com.raildispatch.block.entity.PhysicsCabBlockEntity;
import com.raildispatch.block.entity.RetarderBlockEntity;
import com.raildispatch.block.entity.SmartRetarderBlockEntity;
import com.raildispatch.block.entity.YardTerminalBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block entity types. */
public final class RDBlockEntities {

    private RDBlockEntities() {}

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, RailDispatch.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PhysicsCabBlockEntity>> PHYSICS_CAB =
            BLOCK_ENTITIES.register("physics_cab", () -> BlockEntityType.Builder
                    .of(PhysicsCabBlockEntity::new, RDBlocks.PHYSICS_CAB.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AutopilotConsoleBlockEntity>>
            AUTOPILOT_CONSOLE = BLOCK_ENTITIES.register("autopilot_console", () -> BlockEntityType.Builder
                    .of(AutopilotConsoleBlockEntity::new, RDBlocks.AUTOPILOT_CONSOLE.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DispatchCenterBlockEntity>> DISPATCH_CENTER =
            BLOCK_ENTITIES.register("dispatch_center", () -> BlockEntityType.Builder
                    .of(DispatchCenterBlockEntity::new, RDBlocks.DISPATCH_CENTER.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AddressPlateBlockEntity>> ADDRESS_PLATE =
            BLOCK_ENTITIES.register("address_plate", () -> BlockEntityType.Builder
                    .of(AddressPlateBlockEntity::new, RDBlocks.ADDRESS_PLATE.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RetarderBlockEntity>> RETARDER =
            BLOCK_ENTITIES.register("retarder", () -> BlockEntityType.Builder
                    .of(RetarderBlockEntity::new, RDBlocks.RETARDER.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AutomaticUncouplerBlockEntity>>
            AUTOMATIC_UNCOUPLER = BLOCK_ENTITIES.register("automatic_uncoupler", () -> BlockEntityType.Builder
                    .of(AutomaticUncouplerBlockEntity::new, RDBlocks.AUTOMATIC_UNCOUPLER.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SmartRetarderBlockEntity>>
            SMART_RETARDER = BLOCK_ENTITIES.register("smart_retarder", () -> BlockEntityType.Builder
                    .of(SmartRetarderBlockEntity::new, RDBlocks.SMART_RETARDER.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<YardTerminalBlockEntity>>
            YARD_TERMINAL = BLOCK_ENTITIES.register("yard_terminal", () -> BlockEntityType.Builder
                    .of(YardTerminalBlockEntity::new, RDBlocks.YARD_TERMINAL.get())
                    .build(null));

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
