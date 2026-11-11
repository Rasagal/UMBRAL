// SPDX-License-Identifier: MPL-2.0

package dev.umbral.airlock;
import dev.umbral.access.DoorTarget;
import net.minecraft.world.level.storage.*;
import java.util.UUID;
/** Configuration persists; an entry authorization is transient and never restored from disk. */
public final class AirlockState {
    private DoorTarget outer;
    private int width=3;
    private boolean enabled;
    public String phase="idle";
    public UUID subject,pass;
    public boolean exiting,entered,returning;
    public int occupants;
    public long deadline;
    String lastNotice="";
    public DoorTarget outer(){return outer;}public void outer(DoorTarget value){outer=value;}
    public int width(){return width;}public void width(int value){width=value;}
    public boolean enabled(){return enabled;}public void enabled(boolean value){enabled=value;reset();}
    public void reset(){subject=null;pass=null;exiting=false;entered=false;returning=false;deadline=0;phase="idle";occupants=0;}
    public void begin(UUID player,UUID card,boolean exit,long now){reset();subject=player;pass=card;exiting=exit;deadline=now+600;phase=exit?"exiting":"entering";}
    public void save(ValueOutput out){out.storeNullable("airlock_outer",DoorTarget.CODEC,outer);out.putInt("airlock_width",width);out.putBoolean("airlock_enabled",enabled);}
    public void load(ValueInput in){outer=in.read("airlock_outer",DoorTarget.CODEC).orElse(null);int w=in.getIntOr("airlock_width",3);width=w==1||w==3||w==5?w:3;enabled=in.getBooleanOr("airlock_enabled",false);reset();}
}
