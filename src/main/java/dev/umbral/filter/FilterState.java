// SPDX-License-Identifier: MPL-2.0

package dev.umbral.filter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.umbral.access.DoorTarget;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.storage.*;
import java.util.*;

/** Private persisted settings/history; live approvals deliberately never survive save or reconnect. */
public final class FilterState {
    public static final UUID NONE=new UUID(0,0);
    public enum Mode { INFORM,ALARM,ACCESS;public String id(){return name().toLowerCase(Locale.ROOT);} }
    public record Record(UUID player,String name,Inspection.Outcome outcome,List<Inspection.Match> matches,long tick,boolean test){
        public Record{name=name.substring(0,Math.min(32,name.length()));matches=List.copyOf(matches);}
        private static final Codec<Inspection.Match> MATCH=RecordCodecBuilder.create(i->i.group(Codec.STRING.fieldOf("item").forGetter(Inspection.Match::item),Codec.LONG.fieldOf("count").forGetter(Inspection.Match::count)).apply(i,Inspection.Match::new));
        public static final Codec<Record> CODEC=RecordCodecBuilder.create(i->i.group(UUIDUtil.CODEC.fieldOf("player").forGetter(Record::player),Codec.STRING.fieldOf("name").forGetter(Record::name),Codec.STRING.xmap(Inspection.Outcome::valueOf,Inspection.Outcome::name).fieldOf("outcome").forGetter(Record::outcome),MATCH.listOf(0,16).fieldOf("matches").forGetter(Record::matches),Codec.LONG.fieldOf("tick").forGetter(Record::tick),Codec.BOOL.optionalFieldOf("test",false).forGetter(Record::test)).apply(i,Record::new));
    }
    private record Saved(UUID assembly,long revision,Mode mode,boolean armed,List<FilterRule> rules,Optional<DoorTarget> door,List<Record> history){
        static final Codec<Saved> CODEC=RecordCodecBuilder.create(i->i.group(UUIDUtil.CODEC.optionalFieldOf("assembly",NONE).forGetter(Saved::assembly),Codec.LONG.optionalFieldOf("revision",0L).forGetter(Saved::revision),Codec.STRING.xmap(Mode::valueOf,Mode::name).optionalFieldOf("mode",Mode.INFORM).forGetter(Saved::mode),Codec.BOOL.optionalFieldOf("armed",false).forGetter(Saved::armed),FilterRule.CODEC.listOf(0,FilterRule.MAX_RULES).optionalFieldOf("rules",List.of()).forGetter(Saved::rules),DoorTarget.CODEC.optionalFieldOf("door").forGetter(Saved::door),Record.CODEC.listOf(0,16).optionalFieldOf("history",List.of()).forGetter(Saved::history)).apply(i,Saved::new));
    }
    public record Visit(long seen,Inspection.Result result){}
    private UUID assembly=NONE;
    private long revision;
    private Mode mode=Mode.INFORM;
    private boolean armed;
    private final List<FilterRule> rules=new ArrayList<>();
    private DoorTarget door;
    private final List<Record> history=new ArrayList<>();
    final Map<UUID,Visit> visits=new HashMap<>();
    final Map<UUID,Long> approvals=new HashMap<>();
    long alarmUntil,flashAt=-100,lastSync=-100;
    boolean signal,complete;
    Inspection.Outcome flash=Inspection.Outcome.CLEAR;
    public UUID assembly(){return assembly;}
    public void assembly(UUID id){assembly=id;}
    public long revision(){return revision;}
    public Mode mode(){return mode;}
    public boolean armed(){return armed;}
    public List<FilterRule> rules(){return List.copyOf(rules);}
    public DoorTarget door(){return door;}
    public List<Record> history(){return List.copyOf(history);}
    public Record latest(){return history.isEmpty()?null:history.getLast();}
    public boolean complete(){return complete;}
    public long flashAt(){return flashAt;}
    public Inspection.Outcome flash(){return flash;}
    public boolean alarm(long now){return alarmUntil>now;}
    public void changed(){revision++;visits.clear();approvals.clear();}
    public void armed(boolean value){if(armed!=value){armed=value;changed();}}
    public void mode(Mode value){if(mode!=value){mode=value;changed();}}
    public void door(DoorTarget value){if(!Objects.equals(door,value)){door=value;changed();}}
    public String rule(String selector,int count){
        if(!FilterInventory.knownSelector(selector)||count<1||count>4096)return "bad_rule";
        for(int n=0;n<rules.size();n++)if(rules.get(n).selector().equals(selector)){var old=rules.get(n);rules.set(n,new FilterRule(selector,count,old.exemptions()));changed();return "rule_saved";}
        if(rules.size()>=FilterRule.MAX_RULES)return "full";rules.add(new FilterRule(selector,count));changed();return "rule_saved";
    }
    public String remove(String selector){if(rules.removeIf(r->r.selector().equals(selector))){changed();return "rule_removed";}return "bad_rule";}
    public String exempt(String selector,UUID id,String name){
        for(int n=0;n<rules.size();n++)if(rules.get(n).selector().equals(selector)){
            var old=rules.get(n);var next=new ArrayList<>(old.exemptions());if(!next.removeIf(e->e.id().equals(id))){if(next.size()>=FilterRule.MAX_EXEMPTIONS)return "full";next.add(new FilterRule.Exemption(id,name));}
            rules.set(n,new FilterRule(selector,old.minimum(),next));changed();return "exception_saved";
        }return "bad_rule";
    }
    public void record(Record value){history.add(value);while(history.size()>16)history.removeFirst();}
    public void save(ValueOutput out){out.store("filter",Saved.CODEC,new Saved(assembly,revision,mode,armed,List.copyOf(rules),Optional.ofNullable(door),List.copyOf(history)));}
    public void load(ValueInput in){
        var data=in.read("filter",Saved.CODEC).orElse(new Saved(NONE,0,Mode.INFORM,false,List.of(),Optional.empty(),List.of()));
        assembly=data.assembly;revision=Math.max(0,data.revision);mode=data.mode;armed=data.armed;rules.clear();rules.addAll(data.rules);door=data.door.orElse(null);history.clear();history.addAll(data.history);visits.clear();approvals.clear();alarmUntil=0;
    }
    public void publicLoad(ValueInput in){mode=Mode.values()[Math.clamp(in.getIntOr("filter_mode",0),0,Mode.values().length-1)];armed=in.getBooleanOr("filter_armed",false);complete=in.getBooleanOr("filter_complete",false);flashAt=in.getLongOr("filter_flash_at",-100);flash=Inspection.Outcome.values()[Math.clamp(in.getIntOr("filter_flash",0),0,Inspection.Outcome.values().length-1)];}
}
