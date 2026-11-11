// SPDX-License-Identifier: MPL-2.0

package dev.umbral.installation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Explicit merges also conserve overflow in creative mode. Input stacks are consumed in place. */
public final class InventoryTransfer {
    public static int insert(Container inventory,ItemStack stack){int before=stack.getCount();
        for(int n=0;n<inventory.getContainerSize()&&!stack.isEmpty();n++){
            ItemStack at=inventory.getItem(n);if(at.isEmpty()||!ItemStack.isSameItemSameComponents(at,stack)||!inventory.canPlaceItem(n,stack))continue;
            int count=Math.min(stack.getCount(),Math.min(inventory.getMaxStackSize(),at.getMaxStackSize())-at.getCount());if(count>0){at.grow(count);stack.shrink(count);}
        }
        for(int n=0;n<inventory.getContainerSize()&&!stack.isEmpty();n++)if(inventory.getItem(n).isEmpty()&&inventory.canPlaceItem(n,stack)){
            int count=Math.min(stack.getCount(),Math.min(inventory.getMaxStackSize(),stack.getMaxStackSize()));inventory.setItem(n,stack.split(count));
        }
        if(before!=stack.getCount())inventory.setChanged();return before-stack.getCount();
    }
    public static void give(ServerPlayer player,ItemStack stack){
        // The equipment slots are deliberately excluded from generic returned items.
        for(int n=0;n<36&&!stack.isEmpty();n++){var at=player.getInventory().getItem(n);if(!at.isEmpty()&&ItemStack.isSameItemSameComponents(at,stack)){int count=Math.min(stack.getCount(),at.getMaxStackSize()-at.getCount());if(count>0){at.grow(count);stack.shrink(count);}}}
        for(int n=0;n<36&&!stack.isEmpty();n++)if(player.getInventory().getItem(n).isEmpty()){int count=Math.min(stack.getCount(),stack.getMaxStackSize());player.getInventory().setItem(n,stack.split(count));}
        if(!stack.isEmpty())player.drop(stack.copy(),false,net.minecraft.util.Prediction.SERVER_ONLY);stack.setCount(0);player.getInventory().setChanged();player.containerMenu.broadcastChanges();
    }
    private InventoryTransfer(){}
}
