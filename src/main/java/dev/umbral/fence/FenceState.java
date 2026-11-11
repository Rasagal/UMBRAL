// SPDX-License-Identifier: MPL-2.0

package dev.umbral.fence;
import dev.umbral.access.DoorTarget;
import net.minecraft.world.level.storage.*;
import java.util.*;

/** A circuit has one paired feeder; segment identities/ownership remain independent. */
public final class FenceState {
    public enum Target { HOSTILE,INTRUDERS;public String id(){return name().toLowerCase(Locale.ROOT);} }
    private DoorTarget source;
    private boolean armed,redstone;
    private Target target=Target.HOSTILE;
    public long seen=-100,alarmUntil;
    public int segments;
    final Map<UUID,Long> shocks=new HashMap<>();
    public DoorTarget source(){return source;}public void source(DoorTarget value){source=value;}
    public boolean armed(){return armed;}public void armed(boolean value){armed=value;}
    public boolean redstone(){return redstone;}public void redstone(boolean value){redstone=value;}
    public Target target(){return target;}public void cycle(){target=Target.values()[(target.ordinal()+1)%Target.values().length];}
    public void save(ValueOutput out){out.storeNullable("fence_source",DoorTarget.CODEC,source);out.putBoolean("fence_armed",armed);out.putBoolean("fence_redstone",redstone);out.putString("fence_target",target.name());}
    public void load(ValueInput in){source=in.read("fence_source",DoorTarget.CODEC).orElse(null);armed=in.getBooleanOr("fence_armed",false);redstone=in.getBooleanOr("fence_redstone",false);try{target=Target.valueOf(in.getStringOr("fence_target","HOSTILE"));}catch(IllegalArgumentException e){target=Target.HOSTILE;}seen=-100;shocks.clear();}
}
