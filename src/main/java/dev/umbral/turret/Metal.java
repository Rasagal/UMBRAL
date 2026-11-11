// SPDX-License-Identifier: MPL-2.0

package dev.umbral.turret;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.*;

/** In-world feedstock. Damage follows vanilla arrow flight; cadence balances material cost. */
public enum Metal implements StringRepresentable {
    EMPTY(null,0,30), COPPER(Items.COPPER_NUGGET,2,20), IRON(Items.IRON_NUGGET,3,30), GOLD(Items.GOLD_NUGGET,4,40);
    public final Item item;
    public final double damage;
    public final int reload;
    Metal(Item item,double damage,int reload) {this.item=item;this.damage=damage;this.reload=reload;}
    public static Metal of(ItemStack stack) {for(var v:values())if(v.item!=null&&stack.is(v.item))return v;return EMPTY;}
    public String getSerializedName(){return name().toLowerCase(java.util.Locale.ROOT);}
    public String key(){return "centella.umbral.material."+getSerializedName();}
}
