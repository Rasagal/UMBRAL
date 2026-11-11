// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.umbral.UmbralContent;
import net.minecraft.core.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.*;
import java.util.*;

/** World-owned claims and capacity, independent of device/chunk lifetime. One account across dimensions. */
public final class ProtectionData extends SavedData {
    public static final int BUILD=1, USE=2, INVENTORY=4;
    public static final int EXPLOSIONS=1,FIRE=2,MOBS=4,FLUIDS=8,PISTONS=16,CONTAINERS=32,ALL=63;
    public static final UUID NONE=new UUID(0,0);
    public static final Codec<ProtectionShape.Box> BOX_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.fieldOf("x0").forGetter(ProtectionShape.Box::x0),Codec.INT.fieldOf("y0").forGetter(ProtectionShape.Box::y0),Codec.INT.fieldOf("z0").forGetter(ProtectionShape.Box::z0),
            Codec.INT.fieldOf("x1").forGetter(ProtectionShape.Box::x1),Codec.INT.fieldOf("y1").forGetter(ProtectionShape.Box::y1),Codec.INT.fieldOf("z1").forGetter(ProtectionShape.Box::z1)).apply(i,ProtectionShape.Box::new));
    public static final Codec<ProtectionShape> SHAPE_CODEC=BOX_CODEC.listOf().comapFlatMap(values->{try{return DataResult.success(new ProtectionShape(values));}catch(IllegalArgumentException e){return DataResult.error(e::getMessage);}},ProtectionShape::boxes);
    public record Account(UUID owner,int matrices){
        public static final Codec<Account> CODEC=RecordCodecBuilder.create(i->i.group(UUIDUtil.CODEC.fieldOf("owner").forGetter(Account::owner),Codec.intRange(0,ProtectionEconomy.MAX_MATRICES).fieldOf("matrices").forGetter(Account::matrices)).apply(i,Account::new));
    }
    public record Member(UUID player,String name,int permissions){
        public Member{name=clean(name,32);permissions&=7;}
        public static final Codec<Member> CODEC=RecordCodecBuilder.create(i->i.group(UUIDUtil.CODEC.fieldOf("player").forGetter(Member::player),Codec.STRING.fieldOf("name").forGetter(Member::name),Codec.intRange(0,7).fieldOf("permissions").forGetter(Member::permissions)).apply(i,Member::new));
    }
    public record Region(UUID id,UUID owner,Identifier dimension,String label,Optional<UUID> parent,ProtectionShape shape,List<Member> members,int flags,long revision){
        public Region{label=clean(label,48);members=List.copyOf(members);flags&=ALL;}
        public static final Codec<Region> CODEC=RecordCodecBuilder.create(i->i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Region::id),UUIDUtil.CODEC.fieldOf("owner").forGetter(Region::owner),Identifier.CODEC.fieldOf("dimension").forGetter(Region::dimension),
                Codec.STRING.fieldOf("label").forGetter(Region::label),UUIDUtil.CODEC.optionalFieldOf("parent").forGetter(Region::parent),SHAPE_CODEC.fieldOf("shape").forGetter(Region::shape),
                Member.CODEC.listOf().fieldOf("members").forGetter(Region::members),Codec.intRange(0,ALL).fieldOf("flags").forGetter(Region::flags),Codec.LONG.fieldOf("revision").forGetter(Region::revision)).apply(i,Region::new));
        public Region edited(ProtectionShape geometry,String name){return new Region(id,owner,dimension,name,parent,geometry,members,flags,revision+1);}
    }
    public record Incident(UUID owner,String area,String action,String actor,BlockPos pos,long tick){
        public Incident{area=clean(area,48);action=clean(action,24);actor=clean(actor,32);}
        public static final Codec<Incident> CODEC=RecordCodecBuilder.create(i->i.group(UUIDUtil.CODEC.fieldOf("owner").forGetter(Incident::owner),Codec.STRING.fieldOf("area").forGetter(Incident::area),Codec.STRING.fieldOf("action").forGetter(Incident::action),Codec.STRING.fieldOf("actor").forGetter(Incident::actor),BlockPos.CODEC.fieldOf("position").forGetter(Incident::pos),Codec.LONG.fieldOf("tick").forGetter(Incident::tick)).apply(i,Incident::new));
    }
    public static final Codec<ProtectionData> CODEC=RecordCodecBuilder.create(i->i.group(
            Account.CODEC.listOf().fieldOf("accounts").forGetter(d->List.copyOf(d.accounts.values())),Region.CODEC.listOf().fieldOf("regions").forGetter(d->List.copyOf(d.regions.values())),
            Incident.CODEC.listOf().optionalFieldOf("incidents",List.of()).forGetter(d->List.copyOf(d.incidents))).apply(i,ProtectionData::new));
    public static final SavedDataType<ProtectionData> TYPE=new SavedDataType<>(UmbralContent.id("protection"),ProtectionData::new,CODEC,DataFixTypes.LEVEL);
    private final Map<UUID,Account> accounts=new HashMap<>();
    private final Map<UUID,Region> regions=new LinkedHashMap<>();
    private final List<Incident> incidents=new ArrayList<>();
    private final Map<String,Long> recent=new LinkedHashMap<>();
    private final Map<Identifier,Map<Long,List<Region>>> index=new HashMap<>();
    private boolean reindex=true;
    public ProtectionData(){}
    private ProtectionData(List<Account> accounts,List<Region> regions,List<Incident> incidents){
        accounts.stream().limit(4096).forEach(a->this.accounts.put(a.owner,a));regions.stream().limit(1024).filter(r->!r.shape.empty()&&r.members.size()<=32).forEach(r->this.regions.put(r.id,r));
        incidents.stream().skip(Math.max(0,incidents.size()-1024)).forEach(this.incidents::add);
    }
    public static ProtectionData get(ServerLevel level){return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);}
    public static String clean(String s,int max){s=s.replaceAll("[\\p{Cntrl}]","").strip();return s.substring(0,Math.min(max,s.length()));}
    public int matrices(UUID owner){var a=accounts.get(owner);return a==null?0:a.matrices;}
    public long capacity(UUID owner){return ProtectionEconomy.capacity(matrices(owner));}
    public long used(UUID owner){return regions.values().stream().filter(r->r.owner.equals(owner)&&r.parent.isEmpty()).mapToLong(r->r.shape.cost()).sum();}
    public boolean canInstall(UUID owner,int count){return count>0&&(long)matrices(owner)+count<=ProtectionEconomy.MAX_MATRICES&&(accounts.containsKey(owner)||accounts.size()<4096);}
    public boolean install(UUID owner){return install(owner,1);}
    public boolean install(UUID owner,int count){if(!canInstall(owner,count))return false;accounts.put(owner,new Account(owner,matrices(owner)+count));setDirty();return true;}
    public Region region(UUID id){return regions.get(id);}
    public List<Region> owned(UUID owner){return regions.values().stream().filter(r->r.owner.equals(owner)).toList();}
    public List<Region> all(){return List.copyOf(regions.values());}
    public void put(Region region){regions.put(region.id,region);reindex=true;setDirty();}
    public void remove(UUID id){if(regions.remove(id)!=null){regions.values().removeIf(r->r.parent.isPresent()&&r.parent.get().equals(id));reindex=true;setDirty();}}
    public Region at(Identifier dimension,BlockPos pos){
        if(regions.isEmpty())return null;if(reindex)rebuild();var map=index.get(dimension);if(map==null)return null;var found=map.get(net.minecraft.world.level.ChunkPos.pack(pos.getX()>>4,pos.getZ()>>4));if(found==null)return null;
        Region parent=null;for(var r:found)if(r.shape.contains(pos.getX(),pos.getY(),pos.getZ())){if(r.parent.isPresent())return r;parent=r;}return parent;
    }
    public Region root(Region region){if(region==null)return null;return region.parent.map(regions::get).orElse(region);}
    public boolean permits(Region r,UUID player,int permission){
        if(r==null)return true;if(r.owner.equals(player))return true;
        for(var m:r.members)if(m.player.equals(player))return (m.permissions&permission)==permission;
        return r.parent.isPresent()&&regions.containsKey(r.parent.get())&&permits(regions.get(r.parent.get()),player,permission);
    }
    public List<Incident> incidents(UUID owner){return incidents.reversed().stream().filter(i->i.owner.equals(owner)).limit(16).toList();}
    public void denied(Region r,String action,String actor,BlockPos pos,long tick){
        if(r==null)return;String key=r.id+"/"+action+"/"+actor;Long last=recent.get(key);if(last!=null&&tick>=last&&tick-last<100)return;
        recent.put(key,tick);if(recent.size()>2048)recent.remove(recent.keySet().iterator().next());
        if(incidents.stream().filter(i->i.owner.equals(r.owner)).count()>=16)for(int n=0;n<incidents.size();n++)if(incidents.get(n).owner.equals(r.owner)){incidents.remove(n);break;}
        incidents.add(new Incident(r.owner,r.label,action,actor,pos.immutable(),tick));while(incidents.size()>1024)incidents.removeFirst();setDirty();
    }
    private void rebuild(){index.clear();for(var r:regions.values()){
        var seen=new HashSet<Long>();for(var b:r.shape.boxes())for(int x=b.x0()>>4;x<=(b.x1()-1)>>4;x++)for(int z=b.z0()>>4;z<=(b.z1()-1)>>4;z++)seen.add(net.minecraft.world.level.ChunkPos.pack(x,z));
        var dim=index.computeIfAbsent(r.dimension,k->new HashMap<>());for(long key:seen)dim.computeIfAbsent(key,k->new ArrayList<>()).add(r);
    }reindex=false;}
}
