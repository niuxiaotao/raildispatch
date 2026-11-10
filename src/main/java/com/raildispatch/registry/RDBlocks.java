package com.raildispatch.registry;

import com.raildispatch.RailDispatch;
import com.raildispatch.block.AddressPlateBlock;
import com.raildispatch.block.BallastStairBlock;
import com.raildispatch.block.BallastStepBlock;
import com.raildispatch.block.AutomaticUncouplerBlock;
import com.raildispatch.block.AutopilotConsoleBlock;
import com.raildispatch.block.DispatchCenterBlock;
import com.raildispatch.block.PhysicsCabBlock;
import com.raildispatch.block.RetarderBlock;
import com.raildispatch.block.SmartRetarderBlock;
import com.raildispatch.block.YardTerminalBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block registry. */
public final class RDBlocks {

    private RDBlocks() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RailDispatch.MOD_ID);

    public static final DeferredBlock<PhysicsCabBlock> PHYSICS_CAB = BLOCKS.register("physics_cab",
            () -> new PhysicsCabBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(3.5F, 6.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredBlock<AutopilotConsoleBlock> AUTOPILOT_CONSOLE =
            BLOCKS.register("autopilot_console", () -> new AutopilotConsoleBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(3.5F, 6.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredBlock<DispatchCenterBlock> DISPATCH_CENTER = BLOCKS.register("dispatch_center",
            () -> new DispatchCenterBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(4.0F, 8.0F)
                    .sound(SoundType.METAL)));

    public static final DeferredBlock<AddressPlateBlock> ADDRESS_PLATE = BLOCKS.register("address_plate",
            () -> new AddressPlateBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_YELLOW)
                    .strength(2.0F, 4.0F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    /** Yard retarder: redstone level 0..15 is the braking force, see {@link RetarderBlock}. */
    public static final DeferredBlock<RetarderBlock> RETARDER = BLOCKS.register("retarder",
            () -> new RetarderBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(2.0F, 5.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Smart retarder: holds a configured gap to the wagon ahead. */
    public static final DeferredBlock<SmartRetarderBlock> SMART_RETARDER = BLOCKS.register("smart_retarder",
            () -> new SmartRetarderBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(2.0F, 5.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Trackside automatic coupler opener; opens the nearest Simurail automatic coupler. */
    public static final DeferredBlock<AutomaticUncouplerBlock> AUTOMATIC_UNCOUPLER =
            BLOCKS.register("automatic_uncoupler", () -> new AutomaticUncouplerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(2.5F, 5.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Vehicle yard terminal: destination, consist and service, replacing Simulated nameplates. */
    public static final DeferredBlock<YardTerminalBlock> YARD_TERMINAL = BLOCKS.register("yard_terminal",
            () -> new YardTerminalBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Ballast: gravel-like track bed, deliberately without gravity. */
    public static final DeferredBlock<Block> BALLAST = BLOCKS.register("ballast",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(0.6F)
                    .sound(SoundType.GRAVEL)));

    public static final DeferredBlock<BallastStairBlock> BALLAST_STAIRS = BLOCKS.register("ballast_stairs",
            () -> new BallastStairBlock(BALLAST.get().defaultBlockState(), BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(0.6F)
                    .sound(SoundType.GRAVEL)));

    public static final DeferredBlock<SlabBlock> BALLAST_SLAB = BLOCKS.register("ballast_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(0.6F)
                    .sound(SoundType.GRAVEL)));

    /** Three-quarter-height ballast platform (12/16 block). */
    public static final DeferredBlock<BallastStepBlock> BALLAST_STEP = BLOCKS.register("ballast_step",
            () -> new BallastStepBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(0.6F)
                    .sound(SoundType.GRAVEL)
                    .noOcclusion()));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
