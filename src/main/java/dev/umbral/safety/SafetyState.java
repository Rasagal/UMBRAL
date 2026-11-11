// SPDX-License-Identifier: MPL-2.0

package dev.umbral.safety;

import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.*;

/** Private inventory/configuration; only alarm/armed pilot flags are block states. */
public final class SafetyState {
    private boolean armed;
    private int range=6,cooldown,hold;
    private ItemStack charges=ItemStack.EMPTY;
    public boolean armed(){return armed;}
    public int range(){return range;}
    public int count(){return charges.getCount();}
    public int cooldown(){return cooldown;}
    public boolean alarm(){return hold>0;}
    public void arm(boolean value){armed=value;if(!value)hold=0;}
    public void cycleRange(){range=range==4?6:range==6?8:4;}
    public void detect(){hold=40;}
    public void tick(){if(cooldown>0)cooldown--;if(hold>0)hold--;}
    public void pulse(){charges.shrink(1);cooldown=40;hold=12;}
    public int load(ItemStack source){
        if(!source.is(Items.REDSTONE)||(!charges.isEmpty()&&!ItemStack.isSameItemSameComponents(source,charges)))return 0;
        int n=Math.min(16-count(),source.getCount());if(n<=0)return 0;
        if(charges.isEmpty())charges=source.copyWithCount(n);else charges.grow(n);source.shrink(n);return n;
    }
    public ItemStack unload(){var result=charges;charges=ItemStack.EMPTY;arm(false);return result;}
    public void save(ValueOutput out){out.putInt("safety_schema",1);out.putBoolean("safety_armed",armed);out.putInt("safety_range",range);out.putInt("safety_cooldown",cooldown);out.store("pulse_charges",ItemStack.OPTIONAL_CODEC,charges);}
    public void load(ValueInput in){
        if(in.getIntOr("safety_schema",0)!=1)return;
        armed=in.getBooleanOr("safety_armed",false);int r=in.getIntOr("safety_range",6);range=r==4||r==8?r:6;
        cooldown=Math.clamp(in.getIntOr("safety_cooldown",0),0,40);hold=0;
        charges=in.read("pulse_charges",ItemStack.OPTIONAL_CODEC).filter(s->s.isEmpty()||s.is(Items.REDSTONE)&&s.getCount()<=16).orElse(ItemStack.EMPTY);
    }
}
