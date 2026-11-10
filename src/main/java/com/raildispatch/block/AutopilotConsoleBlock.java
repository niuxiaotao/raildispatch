package com.raildispatch.block;

import com.mojang.serialization.MapCodec;
import com.raildispatch.api.Schedules;
import com.raildispatch.block.entity.AutopilotConsoleBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The autopilot console: a destination, a route, and a speed loop.
 *
 * <p>It is the block a player uses when they want a vehicle to go somewhere without authoring a Create
 * train schedule. It carries its own two Drive By Wire channels - forward and the bogies' brake - so
 * wiring it up is the same gesture as wiring any other controller, and the vehicle keeps being driven by
 * its own drivetrain rather than by anything this mod reaches into.
 *
 * <p><b>One direction only.</b> Placement turns the block's screen towards the player, so the console
 * drives and reads signals <b>away from its screen face</b> - the way the operator faces when standing
 * at it - and offers no reverse channel. A destination behind it is refused rather than reversed into;
 * a vehicle that must work both ways carries two consoles, one at each end, exactly like a double-ended
 * locomotive. This is a deliberate simplification: reversing continuously while following a route
 * requires a second direction of thrust, a second set of signal sides and a polarity that depends on the
 * player's wiring - three sources of wrong-way running for a feature that a second console solves
 * outright.
 *
 * <p>The channels deliberately do not reuse the physics cab's. Both blocks can be mounted on one vehicle
 * without either one's idle zero fighting the other's command, and a player can wire them to different
 * gearboxes if they want to.
 */
public class AutopilotConsoleBlock extends HorizontalDirectionalBlock implements EntityBlock,
        edn.stratodonut.drivebywire.wire.MultiChannelWireSource {

    public static final String CHANNEL_FORWARD = "raildispatch:autopilot_forward";
    public static final String CHANNEL_BRAKE = "raildispatch:autopilot_brake";
    /** High for one second when a Create schedule entry asks the vehicle to uncouple. */
    public static final String CHANNEL_UNCOUPLE = "raildispatch:autopilot_uncouple";

    /**
     * Two channels, deliberately.
     *
     * <p>This console drives one direction only - away from its screen face - so there is no reverse
     * channel to offer. Bidirectional working is two consoles, one at each end, exactly like a locomotive
     * with a cab at both ends.
     */
    public static final java.util.List<String> CHANNELS =
            java.util.List.of(CHANNEL_FORWARD, CHANNEL_BRAKE, CHANNEL_UNCOUPLE);

    @Override
    public java.util.List<String> wire$getChannels() {
        return CHANNELS;
    }

    @Override
    public String wire$nextChannel(String current, boolean forward) {
        int index = CHANNELS.indexOf(current);
        if (index < 0) {
            return CHANNELS.get(0);
        }
        return CHANNELS.get(Math.floorMod(index + (forward ? 1 : -1), CHANNELS.size()));
    }

    public static final MapCodec<AutopilotConsoleBlock> CODEC = simpleCodec(AutopilotConsoleBlock::new);

    /** Lit while the console is actually driving its vehicle. */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public AutopilotConsoleBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWERED, Boolean.FALSE));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED);
    }

    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                         Direction direction) {
        // Only the uncouple pulse is a vanilla redstone signal. Forward/brake are Drive By Wire
        // channels; emitting them here powered the bogie directly above the console, whose local
        // redstone input is a brake input - that is why brakeStrength stayed 1.0 while forward=8.
        return level.getBlockEntity(pos) instanceof AutopilotConsoleBlockEntity console
                ? console.couplerSignal(level instanceof Level serverLevel ? serverLevel : null) : 0;
    }

    @Override
    public int getDirectSignal(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                               Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AutopilotConsoleBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof AutopilotConsoleBlockEntity console) {
                console.serverTick();
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof AutopilotConsoleBlockEntity console)) {
            return InteractionResult.PASS;
        }
        // Sneaking clears the destination and stops the vehicle. It is the same "sneak to take it back"
        // gesture the cab uses for its schedule, and the only way to cancel a journey from outside the
        // screen.
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                if (console.hasSchedule()) {
                    console.ejectSchedule(player);
                } else {
                    console.handleCommand(player, "", AutopilotConsoleBlockEntity.ACTION_CLEAR, -1);
                }
            }
            return InteractionResult.SUCCESS;
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            // The extra-data overload: the menu type is created with IMenuTypeExtension, so its client
            // factory reads the block position out of the buffer. The plain overload writes nothing and
            // the client then fails to construct the menu, which looks like "right-click does nothing".
            serverPlayer.openMenu(console, buffer -> buffer.writeBlockPos(pos));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof AutopilotConsoleBlockEntity console)
                || !Schedules.isScheduleItem(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // Create's schedule item consumes a plain right-click to open its editor; it passes while sneaking,
        // which is the gesture that installs it here - the same as the physics cab.
        if (!level.isClientSide) {
            console.installSchedule(stack, player);
        }
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AutopilotConsoleBlockEntity console) {
            console.onNeighborChanged();
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof AutopilotConsoleBlockEntity console) {
            return console.comparatorOutput();
        }
        return 0;
    }
}
