// SPDX-License-Identifier: MPL-2.0

package dev.umbral.item;

import dev.umbral.block.*;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Reserve the complete frame before touching the world; neighbor updates start after assembly. */
public final class FilterBlockItem extends BlockItem {
    public FilterBlockItem(Block block,Properties properties){super(block,properties);}
    @Override protected boolean placeBlock(BlockPlaceContext c,BlockState state){
        var level=c.getLevel();var root=c.getClickedPos();var facing=state.getValue(DeviceBlock.FACING);var old=new LinkedHashMap<net.minecraft.core.BlockPos,BlockState>();
        for(var part:FilterBlock.Part.values()){var at=FilterBlock.partPos(root,facing,part);old.put(at,level.getBlockState(at));}
        for(var part:FilterBlock.Part.values())if(!level.setBlock(FilterBlock.partPos(root,facing,part),FilterBlock.withFittings(level,FilterBlock.partPos(root,facing,part),state.setValue(FilterBlock.PART,part)),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE)){
            old.forEach((at,before)->level.setBlock(at,before,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE));return false;
        }return true;
    }
}
