// SPDX-License-Identifier: MPL-2.0

package dev.umbral.logistics;

import dev.umbral.access.DoorTarget;
import net.minecraft.world.level.storage.*;

public final class DockState {
    private DoorTarget stock;
    private String result="idle";
    private int delivered;
    public DoorTarget stock(){return stock;}
    public void stock(DoorTarget value){stock=value;}
    public String result(){return result;}
    public int delivered(){return delivered;}
    public void result(String key,int count){result=key;delivered=Math.clamp(count,0,32);}
    public void save(ValueOutput out){out.storeNullable("mota_stock",DoorTarget.CODEC,stock);out.putString("mota_result",result);out.putInt("mota_delivered",delivered);}
    public void load(ValueInput in){stock=in.read("mota_stock",DoorTarget.CODEC).orElse(null);result=in.getStringOr("mota_result","idle");if(!java.util.Set.of("idle","queued","complete","cancelled","blocked","stock_missing","no_stock","full","ammo_mismatch","paused","no_unit","target_changed","returning").contains(result))result="idle";delivered=Math.clamp(in.getIntOr("mota_delivered",0),0,32);}
}
