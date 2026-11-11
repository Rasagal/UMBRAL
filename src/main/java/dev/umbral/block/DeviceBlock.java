// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;

import dev.umbral.UmbralContent;
import dev.umbral.camera.CameraDirectory;
import dev.umbral.network.NetworkProtocol;
import dev.umbral.network.NetworkService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class DeviceBlock extends BaseEntityBlock {
    public static final BooleanProperty ONLINE = BooleanProperty.create("online");
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    private final DeviceKind kind;

    public DeviceBlock(DeviceKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(ONLINE, false).setValue(FACING, Direction.NORTH));
    }

    public DeviceKind kind() { return kind; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ONLINE, FACING);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new DeviceBlockEntity(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, UmbralContent.DEVICE_ENTITY, DeviceBlockEntity::tick);
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (by instanceof Player player && level.getBlockEntity(pos) instanceof DeviceBlockEntity device) {
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            device.initializeOwner(player.getUUID(), name == null ? "" : name.getString());
        }
        NetworkService.invalidate(level);
    }

    @Override protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                    Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.is(UmbralContent.CONFIGURATOR)) return InteractionResult.PASS;
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof DeviceBlockEntity device && player instanceof ServerPlayer serverPlayer) {
            if (!device.ownedBy(player.getUUID())) {
                player.sendOverlayMessage(Component.translatable("message.umbral.not_owner"));
            } else {
                NetworkProtocol.sendStatus(serverPlayer, device, true);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        dev.umbral.installation.InstallationDirectory.get(level).remove(pos);
        if(level.getBlockEntity(pos) instanceof DeviceBlockEntity d)dev.umbral.installation.NexoJournal.get(level).forget(d.identity());
        if (kind == DeviceKind.CAMERA) CameraDirectory.get(level).remove(pos);
        NetworkService.invalidate(level);
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (kind) {
            case CORE -> Block.box(1, 0, 1, 15, 16, 15);
            case RELAY -> Block.box(2, 0, 2, 14, 10.5, 14);
            case TERMINAL -> Block.box(1, 0, 1, 15, 14, 15);
            case FENCE, FILTER, CAMERA, DOOR, READER, TURRET, CENTELLA, SENSOR, PULSE_MINE, NEXO, DOCK -> throw new IllegalStateException("Specialized devices define their own shapes");
        };
    }
}
