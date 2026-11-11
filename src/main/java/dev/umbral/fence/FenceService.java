// SPDX-License-Identifier: MPL-2.0

package dev.umbral.fence;
import dev.umbral.UmbralContent;
import dev.umbral.access.*;
import dev.umbral.block.*;
import dev.umbral.installation.*;
import dev.umbral.network.*;
import dev.umbral.safety.SafetyService;
import dev.umbral.turret.TurretBolt;
import net.minecraft.core.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.*;
import java.util.*;

/** A bounded physical conductor graph, shared contact cooldown and no forced chunk loads. */
public final class FenceService {
    public static final int MAX_SEGMENTS=128,SHOCK_TICKS=20;
    public record Line(List<DeviceBlockEntity> pieces,boolean limit,boolean unloaded){}
    public static boolean head(DeviceBlockEntity d){return d.kind()==DeviceKind.FENCE&&d.asDoorTarget().equals(d.fence().source());}
    public static DeviceBlockEntity controller(DeviceBlockEntity d){
        if(d.kind()!=DeviceKind.FENCE||!(d.getLevel() instanceof ServerLevel l))return null;if(head(d))return d;var ref=d.fence().source();
        if(ref==null||!ref.dimension().equals(l.dimension().identifier())||!l.isLoaded(ref.position()))return null;
        return l.getBlockEntity(ref.position()) instanceof DeviceBlockEntity root&&head(root)&&root.identity().equals(ref.identity())&&Objects.equals(root.owner(),d.owner())&&Objects.equals(root.link(),d.link())?root:null;
    }
    public static boolean same(DeviceBlockEntity a,DeviceBlockEntity b){return a.kind()==DeviceKind.FENCE&&b.kind()==DeviceKind.FENCE&&Objects.equals(a.fence().source(),b.fence().source())&&a.fence().source()!=null;}
    public static Line collect(ServerLevel level,DeviceBlockEntity start,boolean pairing){
        var pending=new ArrayDeque<net.minecraft.core.BlockPos>();var seen=new HashSet<net.minecraft.core.BlockPos>();var pieces=new ArrayList<DeviceBlockEntity>();pending.add(start.getBlockPos());boolean unloaded=false;
        while(!pending.isEmpty()){
            var pos=pending.removeFirst();if(!seen.add(pos))continue;if(!level.isLoaded(pos)){unloaded=true;continue;}
            if(!(level.getBlockEntity(pos) instanceof DeviceBlockEntity d)||d.kind()!=DeviceKind.FENCE||!Objects.equals(d.owner(),start.owner()))continue;
            if(!pairing&&d.link()!=null&&!Objects.equals(d.link(),start.link()))continue;
            if(!pairing&&d.fence().source()!=null&&!d.fence().source().equals(start.asDoorTarget()))continue;
            if(pieces.size()>=MAX_SEGMENTS)return new Line(List.copyOf(pieces),true,unloaded);pieces.add(d);
            for(var dir:Direction.values()){var at=pos.relative(dir);if(!level.isLoaded(at)){unloaded=true;continue;}if(level.getBlockState(at).getBlock() instanceof ElectricFenceBlock)pending.add(at);}
        }return new Line(List.copyOf(pieces),false,unloaded);
    }
    public static String pair(ServerPlayer player,DeviceBlockEntity start,DeviceBlockEntity core){
        if(start.kind()!=DeviceKind.FENCE||!start.ownedBy(player.getUUID())||!core.ownedBy(player.getUUID())||core.kind()!=DeviceKind.CORE||start.getLevel()!=core.getLevel())return "invalid";
        var level=(ServerLevel)start.getLevel();var line=collect(level,start,true);if(line.limit)return "fence_limit";if(line.unloaded)return "fence_unloaded";
        if(!InstallationDirectory.get(level).hasRoom(core.link(),start.getBlockPos()))return "invalid";
        for(var d:line.pieces)if(d.fence().armed())return "fence_disarm_first";
        var ref=start.asDoorTarget();for(var d:line.pieces){d.fence().source(ref);d.fence().armed(false);d.fence().seen=level.getGameTime();if(!d.bind(player.getUUID(),core))throw new IllegalStateException("Validated fence circuit could not pair");setState(level,d,false,d==start);}
        start.fence().segments=line.pieces.size();return "fence_paired";
    }
    public static String arm(DeviceBlockEntity root,boolean on){if(!head(root)||!(root.getLevel() instanceof ServerLevel l))return "offline";if(on&&!NetworkService.inspect(l,root).online())return "offline";if(on&&collect(l,root,false).limit())return "fence_limit";root.fence().armed(on);root.setChanged();if(!on)for(var d:collect(l,root,false).pieces)setState(l,d,false,d==root);return on?"armed":"disarmed";}
    public static void setState(ServerLevel l,DeviceBlockEntity d,boolean energy,boolean feeder){var s=d.getBlockState();var updated=ElectricFenceBlock.refresh(d.getBlockPos(),s.setValue(ElectricFenceBlock.ENERGIZED,energy).setValue(ElectricFenceBlock.FEEDER,feeder));if(s!=updated)l.setBlock(d.getBlockPos(),updated,Block.UPDATE_ALL);}
    public static boolean protectedPlayer(ServerLevel l,DeviceBlockEntity root,Player p){if(SafetyService.authorized(l,root,p))return true;var stack=p.getOffhandItem();var id=stack.get(UmbralContent.CARD_ID);return stack.is(UmbralContent.ACCESS_CARD)&&id!=null&&root.link()!=null&&AccessRegistry.get(l).accepts(id,root.owner(),root.link());}
    public static boolean eligible(ServerLevel l,DeviceBlockEntity root,LivingEntity e){if(!e.isAlive()||e.isSpectator())return false;if(e instanceof Player p)return l.isPvpAllowed()&&root.fence().target()==FenceState.Target.INTRUDERS&&!p.isCreative()&&!protectedPlayer(l,root,p);return TurretBolt.eligible(e,l.getPlayerByUUID(root.owner()));}
    public static boolean touching(DeviceBlockEntity d,LivingEntity e){var offset=Vec3.atLowerCornerOf(d.getBlockPos());return ElectricFenceBlock.body(d.getBlockState(),false).toAabbs().stream().anyMatch(box->box.move(offset).inflate(.08).intersects(e.getBoundingBox()));}
    public static void tick(ServerLevel l,DeviceBlockEntity d){
        long now=l.getGameTime();if(now%5!=0)return;
        if(!head(d)){if(now-d.fence().seen>10)setState(l,d,false,false);return;}
        var s=d.fence();var line=collect(l,d,false);int previous=s.segments;s.segments=line.limit?MAX_SEGMENTS+1:line.pieces.size();if(line.limit&&previous!=s.segments)NexoJournal.get(l).notice(l,d,"fence_limit",MAX_SEGMENTS,"","");boolean online=NetworkService.inspect(l,d).online();boolean energy=s.armed()&&online&&!line.limit&&(!s.redstone()||signal(l,d.getBlockPos()));
        DeviceBlockEntity core=d.link()!=null&&l.isLoaded(d.link().core())&&l.getBlockEntity(d.link().core()) instanceof DeviceBlockEntity c?c:null;
        for(var part:line.pieces){
            if(part.link()==null&&core!=null){part.fence().source(d.asDoorTarget());part.bind(d.owner(),core);}
            part.fence().seen=now;setState(l,part,energy,part==d);
            if(!energy)continue;
            for(var e:l.getEntitiesOfClass(LivingEntity.class,new AABB(part.getBlockPos()).expandTowards(0,.3,0).inflate(.15),v->eligible(l,d,v)&&touching(part,v))){
                if(s.shocks.getOrDefault(e.getUUID(),-1L)>now)continue;s.shocks.put(e.getUUID(),now+SHOCK_TICKS);
                if(e.hurtServer(l,l.damageSources().magic(),4)){
                    if(s.alarmUntil<=now)NexoJournal.get(l).notice(l,d,"fence_contact",4,e.getName().getString(),"");s.alarmUntil=now+40;
                    l.sendParticles(ParticleTypes.ELECTRIC_SPARK,e.getX(),e.getY()+e.getBbHeight()*.5,e.getZ(),10,.2,.3,.2,.03);l.playSound(null,part.getBlockPos(),SoundEvents.AMETHYST_BLOCK_CHIME,SoundSource.BLOCKS,.7f,1.5f);
                }
            }
        }
        s.shocks.entrySet().removeIf(e->e.getValue()<now);if(s.shocks.size()>256)s.shocks.clear();
        if(d.getBlockState().getValue(DeviceBlock.ONLINE)!=online)l.setBlock(d.getBlockPos(),d.getBlockState().setValue(DeviceBlock.ONLINE,online),Block.UPDATE_CLIENTS);
    }
    private static boolean signal(ServerLevel l,BlockPos pos){
        // Vanilla conductor/wire queries can inspect neighboring cells. Preflight those chunks first.
        for(int x=(pos.getX()-2)>>4;x<=((pos.getX()+2)>>4);x++)for(int z=(pos.getZ()-2)>>4;z<=((pos.getZ()+2)>>4);z++)if(!l.isLoaded(new BlockPos(x*16,pos.getY(),z*16)))return false;
        return l.hasNeighborSignal(pos);
    }
    private FenceService(){}
}
