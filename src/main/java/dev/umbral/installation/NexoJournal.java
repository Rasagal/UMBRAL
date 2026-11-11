// SPDX-License-Identifier: MPL-2.0

package dev.umbral.installation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.logistics.MotaService;
import dev.umbral.network.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.*;
import java.util.*;

/** Bounded transition journal. Repeated scans of the same condition never create repeated notices. */
public final class NexoJournal extends SavedData {
    public record Event(UUID owner,NetworkLink link,UUID source,String label,String code,int amount,long tick,String subject,String item){
        public Event(UUID owner,NetworkLink link,UUID source,String label,String code,int amount,long tick){this(owner,link,source,label,code,amount,tick,"","");}
        public static final Codec<Event> CODEC=RecordCodecBuilder.create(i->i.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Event::owner),NetworkLink.CODEC.fieldOf("link").forGetter(Event::link),UUIDUtil.CODEC.fieldOf("source").forGetter(Event::source),
                Codec.STRING.fieldOf("label").forGetter(Event::label),Codec.STRING.fieldOf("code").forGetter(Event::code),Codec.INT.fieldOf("amount").forGetter(Event::amount),Codec.LONG.fieldOf("tick").forGetter(Event::tick),Codec.STRING.optionalFieldOf("subject","").forGetter(Event::subject),Codec.STRING.optionalFieldOf("item","").forGetter(Event::item)).apply(i,Event::new));
    }
    public record Observed(UUID source,NetworkLink link,String code){
        public static final Codec<Observed> CODEC=RecordCodecBuilder.create(i->i.group(UUIDUtil.CODEC.fieldOf("source").forGetter(Observed::source),NetworkLink.CODEC.fieldOf("link").forGetter(Observed::link),Codec.STRING.fieldOf("code").forGetter(Observed::code)).apply(i,Observed::new));
    }
    public static final Codec<NexoJournal> CODEC=RecordCodecBuilder.create(i->i.group(Event.CODEC.listOf().fieldOf("events").forGetter(j->List.copyOf(j.events)),Observed.CODEC.listOf().fieldOf("observed").forGetter(j->List.copyOf(j.observed.values()))).apply(i,NexoJournal::new));
    public static final SavedDataType<NexoJournal> TYPE=new SavedDataType<>(UmbralContent.id("nexo_journal"),NexoJournal::new,CODEC,DataFixTypes.LEVEL);
    private final List<Event> events=new ArrayList<>();
    private final Map<UUID,Observed> observed=new LinkedHashMap<>();
    public NexoJournal(){}
    private NexoJournal(List<Event> events,List<Observed> observed){events.stream().skip(Math.max(0,events.size()-2048)).forEach(this.events::add);observed.stream().limit(4096).forEach(o->this.observed.put(o.source,o));}
    public static NexoJournal get(ServerLevel level){return level.getDataStorage().computeIfAbsent(TYPE);}
    public List<Event> events(UUID owner,NetworkLink link){return events.reversed().stream().filter(e->e.owner.equals(owner)&&e.link.equals(link)).limit(16).toList();}
    public void observe(ServerLevel level,DeviceBlockEntity d){
        if(d.owner()==null||d.link()==null)return;String code="normal";int amount=0;
        if(!NetworkService.inspect(level,d).online())code="offline";
        else if(d.kind().turret()){amount=d.turret().count();code=amount==0?"empty":amount<=8?"low_ammo":"normal";}
        else if(d.kind()==DeviceKind.SENSOR)code=d.safety().alarm()?"alarm":"normal";
        else if(d.kind()==DeviceKind.PULSE_MINE){amount=d.safety().count();code=d.safety().alarm()?"pulse":amount==0?"empty":"normal";}
        else if(d.kind()==DeviceKind.DOCK){amount=d.dock().delivered();var actor=MotaService.actor(level,d);code=actor!=null&&actor.busy()?actor.condition():d.dock().result();if(!Set.of("complete","cancelled","target_changed","blocked","stock_missing","no_stock","full","paused","ammo_mismatch").contains(code))code="normal";}
        var next=new Observed(d.identity(),d.link(),code);if(next.equals(observed.get(d.identity())))return;
        observed.put(d.identity(),next);if(observed.size()>4096)observed.remove(observed.keySet().iterator().next());setDirty();
        if(code.equals("normal"))return;
        if(events.stream().filter(e->e.link.equals(d.link())).count()>=16){for(int n=0;n<events.size();n++)if(events.get(n).link.equals(d.link())){events.remove(n);break;}}
        events.add(new Event(d.owner(),d.link(),d.identity(),d.label(),code,amount,level.getGameTime()));while(events.size()>2048)events.removeFirst();
    }
    public void inspection(ServerLevel level,DeviceBlockEntity scanner,net.minecraft.server.level.ServerPlayer player,dev.umbral.filter.Inspection.Result result){
        if(scanner.owner()==null||scanner.link()==null)return;var first=result.matches().isEmpty()?null:result.matches().getFirst();
        if(events.stream().filter(e->e.link.equals(scanner.link())).count()>=16){for(int n=0;n<events.size();n++)if(events.get(n).link.equals(scanner.link())){events.remove(n);break;}}
        events.add(new Event(scanner.owner(),scanner.link(),scanner.identity(),scanner.label().isEmpty()?"FILTRO":scanner.label(),first==null?"inspection_complex":"inspection_match",first==null?0:(int)first.count(),level.getGameTime(),player.getGameProfile().name(),first==null?"":first.item()));while(events.size()>2048)events.removeFirst();setDirty();
    }
    public void notice(ServerLevel level,DeviceBlockEntity d,String code,int amount,String subject,String item){
        if(d.owner()==null||d.link()==null)return;
        if(events.stream().filter(e->e.link.equals(d.link())).count()>=16){for(int n=0;n<events.size();n++)if(events.get(n).link.equals(d.link())){events.remove(n);break;}}
        events.add(new Event(d.owner(),d.link(),d.identity(),d.label().isEmpty()?d.kind().id:d.label(),code,amount,level.getGameTime(),subject.substring(0,Math.min(32,subject.length())),item));while(events.size()>2048)events.removeFirst();setDirty();
    }
    public void forget(UUID id){if(observed.remove(id)!=null)setDirty();}
}
