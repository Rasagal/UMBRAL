// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;

import dev.umbral.access.AccessService;
import dev.umbral.access.DoorMotion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;
import java.util.Map;

/** Two retracting leaves; FACING is the exterior. Only the lower half owns state and identity. */
public final class SecurityDoorBlock extends DeviceBlock {
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final int HOLD_TICKS = 80;
    private static final VoxelShape POSTS = Shapes.or(Block.box(0, 0, 6, 2, 16, 10), Block.box(14, 0, 6, 16, 16, 10));
    // A fixed 1/32-block threshold covers a buried cable even while the leaves are open.
    private static final Map<Direction, VoxelShape> OPEN_LOWER = Shapes.rotateHorizontal(Shapes.or(POSTS, Block.box(0, 0, 0, 16, .5, 16)));
    private static final Map<Direction, VoxelShape> OPEN_UPPER = Shapes.rotateHorizontal(Shapes.or(POSTS, Block.box(0, 14, 6, 16, 16, 10)));

    public SecurityDoorBlock(Properties properties) {
        super(DeviceKind.DOOR, properties);
        registerDefaultState(defaultBlockState().setValue(HALF, DoubleBlockHalf.LOWER).setValue(OPEN, false));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder); builder.add(HALF, OPEN);
    }
    public static BlockPos lower(BlockPos pos, BlockState state) {
        return state.getBlock() instanceof SecurityDoorBlock && state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }
    public static DeviceBlockEntity device(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        if(level.getBlockState(pos).getBlock() instanceof FilterBlock)return FilterBlock.controller(level,pos);
        BlockPos base = lower(pos, level.getBlockState(pos));
        return level.isLoaded(base) && level.getBlockEntity(base) instanceof DeviceBlockEntity d ? d : null;
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? super.newBlockEntity(pos, state) : null;
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        return pos.getY() < context.getLevel().getMaxY() && context.getLevel().getBlockState(pos.above()).canBeReplaced(context)
                ? super.getStateForPlacement(context) : null;
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        level.setBlockAndUpdate(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER));
    }
    @Override protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                               Direction direction, BlockPos neighborPos, BlockState neighbor, RandomSource random) {
        DoubleBlockHalf half = state.getValue(HALF);
        if (direction == (half == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN)) {
            return neighbor.is(this) && neighbor.getValue(HALF) != half ? neighbor.setValue(HALF, half) : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighbor, random);
    }
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.preventsBlockDrops() && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos base = pos.below();
            if (level.getBlockState(base).is(this)) level.setBlock(base, Blocks.AIR.defaultBlockState(), 35);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockPos base = lower(pos, state);
        float progress = level.getBlockEntity(base) instanceof DeviceBlockEntity door
                ? door.doorMotion().state().position(level instanceof Level world ? world.getGameTime() : 0) : state.getValue(OPEN) ? 1 : 0;
        return passageShape(state, progress);
    }
    public static VoxelShape passageShape(BlockState state, float progress) {
        float p = Math.max(0, Math.min(1, progress));
        VoxelShape frame = (state.getValue(HALF) == DoubleBlockHalf.LOWER ? OPEN_LOWER : OPEN_UPPER).get(state.getValue(FACING));
        double edge = 8 - 6.5 * p;
        if (edge <= 2) return frame;
        VoxelShape panels = Shapes.or(Block.box(2, 0, 6, edge, 16, 10), Block.box(16 - edge, 0, 6, 14, 16, 10));
        return Shapes.or(frame, Shapes.rotateHorizontal(panels).get(state.getValue(FACING)));
    }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return type != PathComputationType.WATER && state.getValue(OPEN);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            DeviceBlockEntity door = device(level, pos);
            if(door!=null&&dev.umbral.airlock.AirlockService.manual(serverPlayer,door))return InteractionResult.SUCCESS;
            if (door != null && AccessService.mayOpenManually(serverPlayer, door)) open((ServerLevel) level, door);
            else player.sendOverlayMessage(Component.translatable("access.umbral.exterior_locked"));
        }
        return InteractionResult.SUCCESS;
    }
    public static boolean open(ServerLevel level, DeviceBlockEntity door) {
        if(!dev.umbral.airlock.AirlockService.permitsOpening(level,door))return false;
        setOpen(level, door, true);
        door.setCloseAt(level.getGameTime() + door.doorMotion().state().remaining(level.getGameTime()) + HOLD_TICKS);return true;
    }
    public static void autoClose(ServerLevel level, DeviceBlockEntity door) {
        long now = level.getGameTime(); DoorMotion motion = door.doorMotion();
        if (motion.needsResume()) {
            boolean resumeOpen = motion.savedGoal()&&dev.umbral.airlock.AirlockService.permitsOpening(level,door); motion.request(resumeOpen, now); door.resumeDoorHold(now);
        }
        if (!door.getBlockState().getValue(OPEN) && motion.state().position(now) <= 0) return;
        if (door.getBlockState().getValue(OPEN) && now < door.closeAt()) return;
        boolean occupied = occupied(level, door.getBlockPos());
        if (motion.state().target() == 0 && motion.state().position(now) > 0 && occupied) { open(level, door); return; }
        if (!door.getBlockState().getValue(OPEN) || now < door.closeAt() || motion.state().moving(now) || occupied) return;
        setOpen(level, door, false); door.setCloseAt(0);
    }
    public static boolean closeIfClear(ServerLevel level,DeviceBlockEntity door){
        if(!occupied(level,door.getBlockPos())){setOpen(level,door,false);door.setCloseAt(0);}
        return dev.umbral.airlock.AirlockService.closed(level,door);
    }
    public static boolean occupied(ServerLevel level, BlockPos pos) {
        AABB sensor = new AABB(pos.getX() - .35, pos.getY(), pos.getZ() - .35, pos.getX() + 1.35, pos.getY() + 2, pos.getZ() + 1.35);
        return !level.getEntities((Entity) null, sensor, e -> e.isAlive() && !e.isSpectator()).isEmpty();
    }
    private static void setOpen(ServerLevel level, DeviceBlockEntity door, boolean open) {
        BlockState state = door.getBlockState();
        boolean changed = door.doorMotion().request(open, level.getGameTime());
        if (state.getValue(OPEN) != open) level.setBlock(door.getBlockPos(), state.setValue(OPEN, open), Block.UPDATE_ALL);
        if (!changed) return;
        door.syncDoor();
        level.playSound(null, door.getBlockPos(), open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, .6f, .85f);
        level.gameEvent(null, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, door.getBlockPos());
    }
}
