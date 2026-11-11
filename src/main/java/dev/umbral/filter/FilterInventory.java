// SPDX-License-Identifier: MPL-2.0

package dev.umbral.filter;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import java.util.*;

/** Main inventory (including the selected hand), offhand and armor, plus carried vanilla containers. */
public final class FilterInventory {
    public static final int MAX_STACKS=4096,MAX_DEPTH=8;
    private record Pending(ItemInstance stack,int depth,long multiplier){}
    private static final Pending EMPTY=new Pending(ItemStack.EMPTY,0,1);
    private static final EquipmentSlot[] EQUIPMENT={EquipmentSlot.OFFHAND,EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET};
    public static Inspection.Result inspect(Player player,List<FilterRule> rules){
        var queue=new ArrayDeque<Pending>(64);
        // Empty roots still occupy their original BFS positions and queue budget.
        for(int n=0;n<36;n++)root(queue,player.getInventory().getItem(n));
        for(var slot:EQUIPMENT)root(queue,player.getItemBySlot(slot));
        var items=new HashMap<Item,Long>();int visited=0;
        while(!queue.isEmpty()){
            var next=queue.removeFirst();var stack=next.stack;if(stack.count()<=0||stack.is(Items.AIR))continue;
            if(++visited>MAX_STACKS||next.depth>MAX_DEPTH)return Inspection.TOO_COMPLEX;
            long amount=Math.min(Inspection.MAX_COUNT,next.multiplier*Math.min(Inspection.MAX_COUNT,stack.count()));
            items.merge(stack.typeHolder().value(),amount,(a,b)->Math.min(Inspection.MAX_COUNT,a+b));
            var container=stack.get(DataComponents.CONTAINER);if(container!=null&&!children(queue,container.nonEmptyItems(),next.depth+1,amount))return Inspection.TOO_COMPLEX;
            var bundle=stack.get(DataComponents.BUNDLE_CONTENTS);if(bundle!=null&&!children(queue,bundle.items(),next.depth+1,amount))return Inspection.TOO_COMPLEX;
        }
        // Resolve each encountered item identifier and each tag selector once per fresh scan.
        if(rules.isEmpty())return Inspection.CLEAR;
        Map<String,TagKey<Item>> tags=Map.of();for(var rule:rules)if(rule.selector().charAt(0)=='#'){
            if(tags.isEmpty())tags=new HashMap<>();String tag=rule.selector().substring(1);tags.computeIfAbsent(tag,id->TagKey.create(Registries.ITEM,Identifier.parse(id)));
        }
        var counts=new HashMap<String,Long>(items.size());Map<String,Item> types=tags.isEmpty()?Map.of():new HashMap<>(items.size());
        for(var entry:items.entrySet()){String id=BuiltInRegistries.ITEM.getKey(entry.getKey()).toString();counts.put(id,entry.getValue());if(!tags.isEmpty())types.put(id,entry.getKey());}
        var selectedTags=tags;
        return Inspection.evaluate(player.getUUID(),counts,rules,(tag,item)->types.get(item).builtInRegistryHolder().is(selectedTags.get(tag)));
    }
    private static void root(ArrayDeque<Pending> queue,ItemStack stack){queue.add(stack.isEmpty()?EMPTY:new Pending(stack,0,1));}
    private static boolean children(ArrayDeque<Pending> queue,Iterable<ItemStackTemplate> contents,int depth,long multiplier){
        for(var child:contents){
            // Plain immutable templates need no mutable ItemStack copy. Preserve vanilla's
            // strict component validation for every customized or container-bearing child.
            ItemInstance item=plain(child)?child:child.create();queue.add(new Pending(item,depth,multiplier));
            if(queue.size()>MAX_STACKS)return false;
        }
        return true;
    }
    private static boolean plain(ItemStackTemplate item){
        int maximum=item.getMaxStackSize();
        return item.components().isEmpty()&&item.count()<=maximum
                &&(item.get(DataComponents.MAX_DAMAGE)==null||maximum<=1)
                &&item.get(DataComponents.CONTAINER)==null&&item.get(DataComponents.BUNDLE_CONTENTS)==null&&item.get(DataComponents.CHARGED_PROJECTILES)==null;
    }
    public static boolean knownSelector(String value){
        if(!FilterRule.validSelector(value))return false;var id=Identifier.parse(value.startsWith("#")?value.substring(1):value);
        return value.startsWith("#")?BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM,id)).isPresent():BuiltInRegistries.ITEM.containsKey(id)&&!id.equals(Identifier.withDefaultNamespace("air"));
    }
    private FilterInventory(){}
}
