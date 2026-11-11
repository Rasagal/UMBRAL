// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;

import dev.umbral.installation.NexoProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

public final class NexoDeviceBlock extends DeviceBlock {
    @Override public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext c){if(kind()==DeviceKind.NEXO&&c.getClickedFace().getAxis().isHorizontal())return defaultBlockState().setValue(FACING,c.getClickedFace());return super.getStateForPlacement(c);}
    public NexoDeviceBlock(DeviceKind kind,Properties p){super(kind,p);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext c){return kind()==DeviceKind.DOCK?Block.box(1,0,1,15,4,15):Shapes.rotateHorizontal(Block.box(.4,1,12.2,15.6,15,16)).get(state.getValue(FACING));}
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){if(player instanceof ServerPlayer p&&level.getBlockEntity(pos) instanceof DeviceBlockEntity d)NexoProtocol.open(p,d);return InteractionResult.SUCCESS;}
}
