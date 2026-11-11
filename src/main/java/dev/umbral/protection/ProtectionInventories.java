// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

import dev.umbral.mixin.ProtectionCompoundAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;

public final class ProtectionInventories {
    private record Position(Level level,BlockPos pos){}
    private static List<Position> positions(Container c,int depth){
        if(c==null||depth>3)return List.of();
        if(c instanceof BlockEntity be&&be.getLevel()!=null)return List.of(new Position(be.getLevel(),be.getBlockPos()));
        if(c instanceof Entity e)return List.of(new Position(e.level(),e.blockPosition()));
        if(c instanceof CompoundContainer){var a=(ProtectionCompoundAccess)c;var out=new ArrayList<>(positions(a.umbral$first(),depth+1));out.addAll(positions(a.umbral$second(),depth+1));return out;}
        return List.of();
    }
    public static boolean transfer(Container from,Container to){for(var a:positions(from,0))for(var b:positions(to,0))if(a.level!=b.level||!ProtectionPolicy.automation(a.level,a.pos,b.pos,ProtectionData.CONTAINERS,"automation"))return false;return true;}
    public static boolean pickup(Container to,Entity item){for(var b:positions(to,0))if(item.level()!=b.level||!ProtectionPolicy.automation(b.level,item.blockPosition(),b.pos,ProtectionData.CONTAINERS,"automation"))return false;return true;}
    private ProtectionInventories(){}
}
