// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;

import dev.umbral.installation.NexoProtocol;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

public final class SafetyBlock extends DeviceBlock {
    public static final BooleanProperty ARMED=BooleanProperty.create("armed"),ALARM=BooleanProperty.create("alarm");
    private static final java.util.Map<Direction,VoxelShape> SENSOR_SHAPES=Shapes.rotateHorizontal(Block.box(3,2,10,13,14,16));
    @Override public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext c){
        if(kind()==DeviceKind.SENSOR&&c.getClickedFace().getAxis().isHorizontal())return defaultBlockState().setValue(FACING,c.getClickedFace());
        return super.getStateForPlacement(c);
    }
    public SafetyBlock(DeviceKind kind,Properties properties){super(kind,properties);registerDefaultState(defaultBlockState().setValue(ARMED,false).setValue(ALARM,false));}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){super.createBlockStateDefinition(b);b.add(ARMED,ALARM);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return kind()==DeviceKind.SENSOR?SENSOR_SHAPES.get(state.getValue(FACING)):Block.box(1,0,1,15,3.3,15);}
    @Override protected boolean isSignalSource(BlockState state){return kind()==DeviceKind.SENSOR;}
    @Override protected int getSignal(BlockState state,BlockGetter level,BlockPos pos,Direction direction){return kind()==DeviceKind.SENSOR&&state.getValue(ALARM)?15:0;}
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(player instanceof ServerPlayer p&&level.getBlockEntity(pos) instanceof DeviceBlockEntity d)NexoProtocol.open(p,d);return InteractionResult.SUCCESS;
    }
}
