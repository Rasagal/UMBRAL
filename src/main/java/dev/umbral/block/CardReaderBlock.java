// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;

import dev.umbral.UmbralContent;
import dev.umbral.access.AccessProtocol;
import dev.umbral.access.AccessService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;
import java.util.Map;

public final class CardReaderBlock extends DeviceBlock {
    public enum Feedback implements StringRepresentable {
        IDLE, GRANTED, DENIED;
        public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
    public static final EnumProperty<Feedback> FEEDBACK = EnumProperty.create("feedback", Feedback.class);
    private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(Block.box(4, 2, 13.5, 12, 14, 16));
    public CardReaderBlock(Properties properties) {
        super(DeviceKind.READER, properties); registerDefaultState(defaultBlockState().setValue(FEEDBACK, Feedback.IDLE));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder); builder.add(FEEDBACK);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }
    @Override protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                   Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(UmbralContent.ACCESS_CARD)) return super.useItemOn(stack, state, level, pos, player, hand, hit);
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof DeviceBlockEntity reader) {
            AccessService.Result result = AccessService.present(serverPlayer, reader, stack);
            boolean granted = result == AccessService.Result.GRANTED;
            level.setBlock(pos, state.setValue(FEEDBACK, granted ? Feedback.GRANTED : Feedback.DENIED), Block.UPDATE_CLIENTS);
            level.scheduleTick(pos, this, 20);
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.BLOCKS, .35f, granted ? 1.5f : .6f);
            if(result!=AccessService.Result.SCAN_REQUIRED&&result!=AccessService.Result.SCAN_BLOCKED&&result!=AccessService.Result.SCAN_UNAVAILABLE)player.sendOverlayMessage(Component.translatable(result.key()));
        }
        return InteractionResult.SUCCESS;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof DeviceBlockEntity reader) {
            if (reader.ownedBy(player.getUUID())) AccessProtocol.open(serverPlayer, reader);
            else player.sendOverlayMessage(Component.translatable("access.umbral.present_card"));
        }
        return InteractionResult.SUCCESS;
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        level.setBlock(pos, state.setValue(FEEDBACK, Feedback.IDLE), Block.UPDATE_CLIENTS);
    }
}
