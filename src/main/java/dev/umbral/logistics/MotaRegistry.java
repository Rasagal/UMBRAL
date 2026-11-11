// SPDX-License-Identifier: MPL-2.0

package dev.umbral.logistics;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.umbral.UmbralContent;
import dev.umbral.network.NetworkLink;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.*;
import java.util.*;

/** Installation lifecycle survives chunk unload: absence from the entity lookup never creates a new unit. */
public final class MotaRegistry extends SavedData {
    public record Unit(UUID station,BlockPos home,UUID owner,NetworkLink link,UUID actor,boolean active,boolean cancel) {
        public static final Codec<Unit> CODEC=RecordCodecBuilder.create(i->i.group(
                UUIDUtil.CODEC.fieldOf("station").forGetter(Unit::station),BlockPos.CODEC.fieldOf("home").forGetter(Unit::home),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Unit::owner),NetworkLink.CODEC.fieldOf("link").forGetter(Unit::link),
                UUIDUtil.CODEC.fieldOf("actor").forGetter(Unit::actor),Codec.BOOL.fieldOf("active").forGetter(Unit::active),Codec.BOOL.optionalFieldOf("cancel",false).forGetter(Unit::cancel)).apply(i,Unit::new));
    }
    public static final Codec<MotaRegistry> CODEC=Unit.CODEC.listOf().xmap(MotaRegistry::new,r->List.copyOf(r.units.values()));
    public static final SavedDataType<MotaRegistry> TYPE=new SavedDataType<>(UmbralContent.id("mota_registry"),MotaRegistry::new,CODEC,DataFixTypes.LEVEL);
    private final Map<UUID,Unit> units=new HashMap<>();
    public MotaRegistry(){}
    private MotaRegistry(List<Unit> saved){saved.stream().limit(4096).forEach(u->units.put(u.station,u));}
    public static MotaRegistry get(ServerLevel level){return level.getDataStorage().computeIfAbsent(TYPE);}
    public Unit unit(UUID station){return units.get(station);}
    public boolean install(Unit u){var old=units.get(u.station);if(old!=null&&old.active||old==null&&units.size()>=4096)return false;units.put(u.station,u);setDirty();return true;}
    public boolean deactivate(UUID station,UUID actor){var u=units.get(station);if(u==null||!u.actor.equals(actor)||!u.active)return false;units.put(station,new Unit(u.station,u.home,u.owner,u.link,u.actor,false,false));setDirty();return true;}
    public boolean cancel(UUID station,boolean value){var u=units.get(station);if(u==null||!u.active)return false;units.put(station,new Unit(u.station,u.home,u.owner,u.link,u.actor,true,value));setDirty();return true;}
    public void forget(UUID station,UUID actor){var u=units.get(station);if(u!=null&&u.actor.equals(actor)){units.remove(station);setDirty();}}
}
