// SPDX-License-Identifier: MPL-2.0

package dev.umbral.airlock;
import dev.umbral.UmbralContent;
import dev.umbral.access.*;
import dev.umbral.block.*;
import dev.umbral.filter.*;
import dev.umbral.installation.*;
import dev.umbral.network.*;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.*;
import java.util.*;

/** Physical two-door interlock. A valid pass admits one identified visitor, not everyone in the chamber. */
public final class AirlockService {
    public static DeviceBlockEntity resolve(ServerLevel level,DoorTarget ref){
        if(ref==null||!ref.dimension().equals(level.dimension().identifier())||!level.isLoaded(ref.position()))return null;
        return level.getBlockEntity(ref.position()) instanceof DeviceBlockEntity d&&d.identity().equals(ref.identity())?d:null;
    }
    public static DeviceBlockEntity outer(DeviceBlockEntity root){return root.getLevel() instanceof ServerLevel l?resolve(l,root.airlock().outer()):null;}
    public static DeviceBlockEntity inner(DeviceBlockEntity root){return root.getLevel() instanceof ServerLevel l?FilterService.door(l,root):null;}
    private static DeviceBlockEntity controller(ServerLevel level,DeviceBlockEntity door){var link=door.airlockLink();if(link==null)return null;var d=resolve(level,link.controller());return d!=null&&FilterService.scanner(d)&&d.airlock().enabled()&&Objects.equals(d.owner(),door.owner())&&Objects.equals(d.link(),door.link())?d:null;}
    public static boolean closed(ServerLevel l,DeviceBlockEntity d){return d!=null&&d.kind()==DeviceKind.DOOR&&!d.getBlockState().getValue(SecurityDoorBlock.OPEN)&&!d.doorMotion().needsResume()&&d.doorMotion().state().position(l.getGameTime())<=0;}
    /** All opening paths, including owner control and anti-crush reopening, obey this physical gate. */
    public static boolean permitsOpening(ServerLevel l,DeviceBlockEntity door){
        var link=door.airlockLink();if(link==null)return true;var ref=link.other();if(!ref.dimension().equals(l.dimension().identifier())||!l.isLoaded(ref.position()))return false;
        // A destroyed opposite door cannot physically be open. Admissions still fail identity validation.
        if(!(l.getBlockState(ref.position()).getBlock() instanceof SecurityDoorBlock))return true;
        return l.getBlockEntity(ref.position()) instanceof DeviceBlockEntity other&&other.kind()==DeviceKind.DOOR&&closed(l,other);
    }
    public static AABB chamber(DeviceBlockEntity root){
        var out=root.airlock().outer();var in=root.filter().door();if(out==null||in==null)return new AABB(root.getBlockPos());
        Vec3 a=Vec3.atCenterOf(out.position()),b=Vec3.atCenterOf(in.position());double half=root.airlock().width()/2d;boolean alongZ=root.getBlockState().getValue(DeviceBlock.FACING).getAxis()==Direction.Axis.Z;
        return alongZ?new AABB(a.x-half,out.position().getY(),Math.min(a.z,b.z)+.2,a.x+half,out.position().getY()+3,Math.max(a.z,b.z)-.2):new AABB(Math.min(a.x,b.x)+.2,out.position().getY(),a.z-half,Math.max(a.x,b.x)-.2,out.position().getY()+3,a.z+half);
    }
    private static boolean roomLoaded(ServerLevel l,DeviceBlockEntity root){var box=chamber(root);for(int x=net.minecraft.util.Mth.floor(box.minX)>>4;x<=(net.minecraft.util.Mth.floor(box.maxX)>>4);x++)for(int z=net.minecraft.util.Mth.floor(box.minZ)>>4;z<=(net.minecraft.util.Mth.floor(box.maxZ)>>4);z++)if(!l.isLoaded(new BlockPos(x*16,root.getBlockPos().getY(),z*16)))return false;return true;}
    private static boolean pairValid(DeviceBlockEntity root,DeviceBlockEntity out,DeviceBlockEntity in){return out!=null&&in!=null&&out!=in&&out.kind()==DeviceKind.DOOR&&in.kind()==DeviceKind.DOOR&&Objects.equals(root.owner(),out.owner())&&Objects.equals(root.owner(),in.owner())&&Objects.equals(root.link(),out.link())&&Objects.equals(root.link(),in.link());}
    public static String configure(ServerPlayer p,DeviceBlockEntity root,DoorTarget outside,DoorTarget inside,int width){
        if(!FilterService.scanner(root)||!root.ownedBy(p.getUUID())||root.getLevel()!=p.level()||width!=1&&width!=3&&width!=5)return "invalid";if(root.airlock().enabled())return "airlock_disable_first";
        var l=(ServerLevel)p.level();var out=resolve(l,outside);var in=resolve(l,inside);if(!pairValid(root,out,in))return "airlock_doors";
        var facing=root.getBlockState().getValue(DeviceBlock.FACING);var center=root.getBlockPos().relative(facing.getClockWise());var delta=out.getBlockPos().subtract(center);
        int ahead=delta.getX()*facing.getStepX()+delta.getZ()*facing.getStepZ(),side=delta.getX()*facing.getClockWise().getStepX()+delta.getZ()*facing.getClockWise().getStepZ();
        if(delta.getY()!=0||side!=0||ahead<1||ahead>4||out.getBlockState().getValue(DeviceBlock.FACING)!=facing)return "airlock_alignment";
        if(out.getBlockPos().distManhattan(in.getBlockPos())<3)return "airlock_tight";
        if(out.airlockLink()!=null||in.airlockLink()!=null)return "airlock_busy";
        if(!Objects.equals(root.filter().door(),inside)){String result=FilterService.link(p,root,inside);if(!result.equals("door_linked"))return result;}
        root.airlock().outer(outside);root.airlock().width(width);root.filter().changed();root.setChanged();return "airlock_saved";
    }
    public static String enable(DeviceBlockEntity root,boolean enabled){
        if(!FilterService.scanner(root)||!(root.getLevel() instanceof ServerLevel l))return "invalid";var out=outer(root);var in=inner(root);var s=root.airlock();
        if(!enabled){
            if(s.outer()!=null&&!l.isLoaded(s.outer().position())||root.filter().door()!=null&&!l.isLoaded(root.filter().door().position()))return "airlock_unloaded";
            for(var d:Arrays.asList(out,in))if(d!=null&&d.airlockLink()!=null&&d.airlockLink().controller().equals(root.asDoorTarget()))d.airlockLink(null);
            s.enabled(false);root.filter().changed();root.setChanged();return "airlock_disabled";
        }
        if(!pairValid(root,out,in)||!roomLoaded(l,root))return "airlock_doors";
        if(!closed(l,out)||!closed(l,in))return "airlock_close_doors";
        if(out.airlockLink()!=null&&!out.airlockLink().controller().equals(root.asDoorTarget())||in.airlockLink()!=null&&!in.airlockLink().controller().equals(root.asDoorTarget()))return "airlock_busy";
        if(!FilterBlock.complete(root)||root.filter().rules().isEmpty())return "airlock_filter";
        if(!NetworkService.inspect(l,root).online()||!NetworkService.inspect(l,out).online()||!NetworkService.inspect(l,in).online())return "offline";
        for(var entry:InstallationDirectory.get(l).entries(root.owner(),root.link())){var other=InstallationDirectory.loaded(l,entry);if(other!=null&&other!=root&&FilterService.scanner(other)&&other.airlock().enabled()&&chamber(other).intersects(chamber(root)))return "airlock_overlap";}
        String mode=FilterService.mode(root,FilterState.Mode.ACCESS);if(!Set.of("mode_saved","ready").contains(mode))return mode;String armed=FilterService.arm(root,true);if(!armed.equals("armed"))return armed;
        out.airlockLink(new AirlockLink(root.asDoorTarget(),in.asDoorTarget(),true));in.airlockLink(new AirlockLink(root.asDoorTarget(),out.asDoorTarget(),false));s.enabled(true);root.filter().changed();root.setChanged();return "airlock_enabled";
    }
    public static boolean recover(ServerPlayer p,DeviceBlockEntity door){
        if(!door.ownedBy(p.getUUID())||door.airlockLink()==null)return false;var l=(ServerLevel)p.level();var link=door.airlockLink();if(!l.isLoaded(link.other().position()))return false;
        var other=resolve(l,link.other());if(other!=null&&(!other.ownedBy(p.getUUID())||other.airlockLink()!=null&&!other.airlockLink().controller().equals(link.controller())))return false;
        var root=resolve(l,link.controller());if(root!=null&&FilterService.scanner(root)&&root.ownedBy(p.getUUID())){root.airlock().enabled(false);root.filter().changed();root.setChanged();}
        door.airlockLink(null);if(other!=null&&other.airlockLink()!=null)other.airlockLink(null);return true;
    }
    private static boolean ready(ServerLevel l,DeviceBlockEntity root,DeviceBlockEntity out,DeviceBlockEntity in){return pairValid(root,out,in)&&out.airlockLink()!=null&&in.airlockLink()!=null&&out.airlockLink().controller().equals(root.asDoorTarget())&&in.airlockLink().controller().equals(root.asDoorTarget())&&out.airlockLink().other().equals(in.asDoorTarget())&&in.airlockLink().other().equals(out.asDoorTarget())&&out.airlockLink().outer()&&!in.airlockLink().outer()&&roomLoaded(l,root)&&root.filter().armed()&&root.filter().mode()==FilterState.Mode.ACCESS&&FilterBlock.complete(root)&&NetworkService.inspect(l,root).online()&&NetworkService.inspect(l,out).online()&&NetworkService.inspect(l,in).online();}
    public static AccessService.Result card(ServerPlayer p,DeviceBlockEntity door,UUID pass){
        if(door.airlockLink()==null)return null;var l=(ServerLevel)p.level();var root=controller(l,door);if(root==null||!ready(l,root,outer(root),inner(root)))return AccessService.Result.AIRLOCK_UNAVAILABLE;
        var s=root.airlock();boolean inside=chamber(root).intersects(p.getBoundingBox());
        if(door.airlockLink().outer()&&inside){s.returning=true;return AccessService.Result.AIRLOCK_QUEUED;}
        if(s.subject!=null&&!s.subject.equals(p.getUUID()))return AccessService.Result.AIRLOCK_BUSY;
        if(!door.airlockLink().outer())return s.subject!=null&&s.subject.equals(p.getUUID())?AccessService.Result.AIRLOCK_QUEUED:AccessService.Result.AIRLOCK_DENIED;
        if(!inside){s.begin(p.getUUID(),pass,false,l.getGameTime());SecurityDoorBlock.open(l,door);return AccessService.Result.AIRLOCK_QUEUED;}return AccessService.Result.AIRLOCK_DENIED;
    }
    public static boolean manual(ServerPlayer p,DeviceBlockEntity door){
        if(door.airlockLink()==null)return false;var l=(ServerLevel)p.level();if(!AccessService.mayOpenManually(p,door)){message(p,"airlock_card");return true;}
        var root=controller(l,door);
        if(root==null||!ready(l,root,outer(root),inner(root))){if(!SecurityDoorBlock.open(l,door))message(p,"airlock_interlock");return true;}
        var s=root.airlock();boolean inside=chamber(root).intersects(p.getBoundingBox());
        if(inside){if(door.airlockLink().outer()){s.returning=true;message(p,"airlock_returning");}else if(door.ownedBy(p.getUUID())){s.begin(p.getUUID(),null,false,l.getGameTime());s.entered=true;}else message(p,"airlock_card");}
        else if(!door.airlockLink().outer()){if(s.subject!=null&&!s.subject.equals(p.getUUID())||l.getEntitiesOfClass(ServerPlayer.class,chamber(root),v->v.isAlive()&&!v.isSpectator()&&!v.getUUID().equals(p.getUUID())).size()>0)message(p,"airlock_busy");else{s.begin(p.getUUID(),null,true,l.getGameTime());SecurityDoorBlock.open(l,door);}}
        else if(door.ownedBy(p.getUUID())){s.begin(p.getUUID(),null,false,l.getGameTime());SecurityDoorBlock.open(l,door);}else message(p,"airlock_card");return true;
    }
    private static boolean credential(ServerLevel l,DeviceBlockEntity root,ServerPlayer p){if(root.ownedBy(p.getUUID()))return true;var id=root.airlock().pass;if(id==null||!AccessRegistry.get(l).accepts(id,root.owner(),root.link()))return false;for(int n=0;n<36;n++){var stack=p.getInventory().getItem(n);if(stack.is(UmbralContent.ACCESS_CARD)&&id.equals(stack.get(UmbralContent.CARD_ID)))return true;}var off=p.getOffhandItem();return off.is(UmbralContent.ACCESS_CARD)&&id.equals(off.get(UmbralContent.CARD_ID));}
    public static void tick(ServerLevel l,DeviceBlockEntity root){
        var s=root.airlock();long now=l.getGameTime();if(!s.enabled()||now%5!=0)return;var out=outer(root);var in=inner(root);
        if(!ready(l,root,out,in)){if(in!=null)SecurityDoorBlock.closeIfClear(l,in);phase(l,root,"offline");return;}
        var people=l.getEntitiesOfClass(ServerPlayer.class,chamber(root),p->p.isAlive()&&!p.isSpectator());s.occupants=people.size();var person=s.subject==null?null:l.getServer().getPlayerList().getPlayer(s.subject);boolean subjectInside=person!=null&&chamber(root).intersects(person.getBoundingBox());
        if(s.subject==null&&!s.returning){if(people.isEmpty()){SecurityDoorBlock.closeIfClear(l,out);SecurityDoorBlock.closeIfClear(l,in);phase(l,root,"idle");return;}s.returning=true;}
        if(s.subject!=null&&(person==null||!person.isAlive()||now>s.deadline)){s.returning=true;phase(l,root,"timeout");}
        if(!s.exiting&&people.size()>1||s.exiting&&people.stream().anyMatch(v->!v.getUUID().equals(s.subject))){s.returning=true;phase(l,root,"multiple");}
        if(s.returning){
            SecurityDoorBlock.closeIfClear(l,in);if(closed(l,in)&&!people.isEmpty())SecurityDoorBlock.open(l,out);
            if(people.isEmpty()&&!SecurityDoorBlock.occupied(l,out.getBlockPos())){SecurityDoorBlock.closeIfClear(l,out);if(closed(l,out))s.reset();}
            if(!Set.of("multiple","restricted","timeout").contains(s.phase))phase(l,root,"returning");return;
        }
        if(s.exiting){
            if(!s.entered){SecurityDoorBlock.closeIfClear(l,out);if(closed(l,out))SecurityDoorBlock.open(l,in);if(subjectInside)s.entered=true;phase(l,root,"exiting");return;}
            SecurityDoorBlock.closeIfClear(l,in);if(closed(l,in)&&!people.isEmpty())SecurityDoorBlock.open(l,out);phase(l,root,"leaving");
            if(!subjectInside&&people.isEmpty()&&!SecurityDoorBlock.occupied(l,out.getBlockPos())){SecurityDoorBlock.closeIfClear(l,out);if(closed(l,out))s.reset();}return;
        }
        if(!s.entered){if(subjectInside)s.entered=true;else{SecurityDoorBlock.closeIfClear(l,in);if(closed(l,in))SecurityDoorBlock.open(l,out);phase(l,root,"entering");return;}}
        if(!subjectInside){SecurityDoorBlock.closeIfClear(l,in);SecurityDoorBlock.closeIfClear(l,out);if(people.isEmpty()&&closed(l,in)&&closed(l,out))s.reset();return;}
        if(!credential(l,root,person)){s.returning=true;phase(l,root,"restricted");return;}
        SecurityDoorBlock.closeIfClear(l,out);if(!closed(l,out)){phase(l,root,"closing_outer");return;}
        var gate=FilterService.checkDoor(person,in);
        if(gate==FilterService.Gate.ALLOW){SecurityDoorBlock.open(l,in);phase(l,root,"admitted");}
        else if(gate==FilterService.Gate.REQUIRED)phase(l,root,"scanning");else if(gate==FilterService.Gate.BLOCKED){s.returning=true;phase(l,root,"restricted");}else phase(l,root,"offline");
    }
    public static void returnOutside(DeviceBlockEntity root){root.airlock().returning=true;}
    private static void phase(ServerLevel l,DeviceBlockEntity root,String value){var s=root.airlock();s.phase=value;if(!s.lastNotice.equals(value)){s.lastNotice=value;if(Set.of("multiple","restricted","offline").contains(value))NexoJournal.get(l).notice(l,root,"airlock_"+value,s.occupants,"","");}}
    private static void message(ServerPlayer p,String key){if(p.connection!=null)p.sendOverlayMessage(Component.translatable("nexo.umbral.result."+key));}
    private AirlockService(){}
}
