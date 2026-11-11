// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;

import dev.umbral.turret.Metal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.*;

public final class CentellaBlock extends BaluarteBlock {
    public static final EnumProperty<Metal> METAL=EnumProperty.create("metal",Metal.class);
    public CentellaBlock(Properties properties){super(DeviceKind.CENTELLA,properties);registerDefaultState(defaultBlockState().setValue(METAL,Metal.EMPTY));}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){super.createBlockStateDefinition(b);b.add(METAL);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return Shapes.or(Block.box(1,0,1,15,4,15),Block.box(1,6,1,16,16,15),Block.box(4,4,4,12,6,12));}
}
