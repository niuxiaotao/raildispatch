package com.raildispatch.block;

import com.mojang.serialization.MapCodec;
import com.raildispatch.api.Schedules;
import com.raildispatch.block.entity.PhysicsCabBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
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
 * The physics cab: it holds a Create train schedule, watches the signals ahead and drives the
 * vehicle's physics bogies. It is the block a player interacts with to start automated running.
 */
public class PhysicsCabBlock extends HorizontalDirectionalBlock implements EntityBlock,
        edn.stratodonut.drivebywire.wire.MultiChannelWireSource {

    /**
     * Wire channel carrying traction towards the destination.
     *
     * <p>Drive By Wire has no notion of direction on a channel - a channel is just a name and a value, and
     * the receiving side collapses several inputs with a maximum - so direction has to live in the channel
     * name. That is the same trick the mod itself uses for its controller axes ({@code axisLeftY+} beside
     * {@code axisLeftY-}).
     */
    public static final String CHANNEL_FORWARD = "raildispatch:forward";

    /** Wire channel carrying traction in the opposite direction. */
    public static final String CHANNEL_BACKWARD = "raildispatch:backward";

    /**
     * Wire channel carrying the bogies' own brake, as a plain 0..15.
     *
     * <p>Separate from the throttle channels because it drives a different thing: those set the drivetrain's speed
     * through a gearbox, whereas this asks the bogies themselves to brake. It exists so the last stretch of a stop
     * can be given full braking effort even after the drivetrain has been reversed, which is what closes the
     * remaining stopping-distance error.
     */
    public static final String CHANNEL_BOGIE_BRAKE = "raildispatch:bogie_brake";

    public static final java.util.List<String> CHANNELS =
            java.util.List.of(CHANNEL_FORWARD, CHANNEL_BACKWARD, CHANNEL_BOGIE_BRAKE);

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

    public static final MapCodec<PhysicsCabBlock> CODEC = simpleCodec(PhysicsCabBlock::new);

    /** Lit while the cab is actively driving its vehicle. */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public PhysicsCabBlock(Properties properties) {
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
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PhysicsCabBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof PhysicsCabBlockEntity cab) {
                cab.serverTick();
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof PhysicsCabBlockEntity cab) {
            // Sneaking with an empty hand takes the schedule back out, so install and eject are a
            // symmetric pair of gestures on the same block.
            if (player.isShiftKeyDown() && cab.hasSchedule()) {
                if (!level.isClientSide) {
                    cab.ejectSchedule(player);
                }
                return InteractionResult.SUCCESS;
            }
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                // The extra-data overload, not Player#openMenu(MenuProvider): our menu type is built with
                // IMenuTypeExtension.create, so its client factory receives a RegistryFriendlyByteBuf and
                // reads the block position out of it. The plain overload writes nothing, which makes the
                // client read an empty buffer and fail to construct the menu - the screen then never
                // appears and the click looks like it did nothing.
                serverPlayer.openMenu(cab, buffer -> buffer.writeBlockPos(pos));
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof PhysicsCabBlockEntity cab)
                || !Schedules.isScheduleItem(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // Create's ScheduleItem claims a plain right-click to open its own editor and returns success,
        // so the click never reaches this block. It does return pass while the player sneaks, which is
        // what makes shift-right-click the gesture that can install a schedule here - and it matches
        // Create's own "sneak to interact with the block" idiom.
        if (!level.isClientSide) {
            cab.installSchedule(stack, player);
        }
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof PhysicsCabBlockEntity cab) {
            cab.onNeighborChanged();
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof PhysicsCabBlockEntity cab) {
            cab.dropContents();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof PhysicsCabBlockEntity cab) {
            return cab.comparatorOutput();
        }
        return 0;
    }
}
