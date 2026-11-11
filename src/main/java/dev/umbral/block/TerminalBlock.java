// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.Map;

/** The same terminal monitor can sit on its keyboard base or mount on a wall. */
public final class TerminalBlock extends DeviceBlock {
    public enum Mount implements StringRepresentable { FLOOR,WALL;
        public String getSerializedName(){return name().toLowerCase(java.util.Locale.ROOT);}
    }
    public static final EnumProperty<Mount> MOUNT=EnumProperty.create("mount",Mount.class);
    private static final Map<Direction,VoxelShape> WALL=Shapes.rotateHorizontal(Block.box(1,3.4,10.5,15,13.5,16));
    public TerminalBlock(Properties properties){super(DeviceKind.TERMINAL,properties);registerDefaultState(defaultBlockState().setValue(MOUNT,Mount.FLOOR));}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){super.createBlockStateDefinition(builder);builder.add(MOUNT);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext context){
        Direction face=context.getClickedFace();if(face==Direction.DOWN)return null;
        return defaultBlockState().setValue(MOUNT,face==Direction.UP?Mount.FLOOR:Mount.WALL)
                .setValue(FACING,face==Direction.UP?context.getHorizontalDirection().getOpposite():face);
    }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        return state.getValue(MOUNT)==Mount.WALL?WALL.get(state.getValue(FACING)):Block.box(1,0,1,15,15.5,14);
    }
}
