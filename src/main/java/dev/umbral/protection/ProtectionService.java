// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Mutations validate the authoritative ledger and current inventory, never a client price or capacity. */
public final class ProtectionService {
    public static String install(ServerPlayer player){
        if(!player.isAlive()||player.isSpectator())return "invalid";var data=ProtectionData.get((ServerLevel)player.level());
        if(data.matrices(player.getUUID())>=ProtectionEconomy.MAX_MATRICES)return "matrix_limit";
        ItemStack input=player.getMainHandItem();if(!input.is(UmbralContent.PROTECTION_MATRIX)){
            input=ItemStack.EMPTY;for(int n=0;n<player.getInventory().getContainerSize();n++)if(player.getInventory().getItem(n).is(UmbralContent.PROTECTION_MATRIX)){input=player.getInventory().getItem(n);break;}
        }
        if(input.isEmpty())return "need_matrix";if(!data.install(player.getUUID()))return "matrix_limit";
        input.shrink(1);player.getInventory().setChanged();player.containerMenu.broadcastChanges();return "installed";
    }
    public static String apply(ServerPlayer player,UUID id,long revision,UUID parentId,ProtectionShape shape,String label){
        return apply(player,id,revision,parentId,shape,label,false);
    }
    public static int availableMatrices(net.minecraft.world.entity.player.Player player){
        int count=0;for(int n=0;n<player.getInventory().getContainerSize();n++){var s=player.getInventory().getItem(n);if(s.is(UmbralContent.PROTECTION_MATRIX))count+=s.getCount();}return count;
    }
    /** Validate the whole edit before transferring any material, then commit on the server thread. */
    public static String apply(ServerPlayer player,UUID id,long revision,UUID parentId,ProtectionShape shape,String label,boolean acquireCapacity){
        return apply(player,id,revision,parentId,shape,label,acquireCapacity,ProtectionEconomy.MAX_MATRICES);
    }
    public static String apply(ServerPlayer player,UUID id,long revision,UUID parentId,ProtectionShape shape,String label,boolean acquireCapacity,int approvedMatrices){
        if(!player.isAlive()||player.isSpectator())return "invalid";var level=(ServerLevel)player.level();var data=ProtectionData.get(level);var old=id.equals(ProtectionData.NONE)?null:data.region(id);
        if(!id.equals(ProtectionData.NONE)&&(old==null||!old.owner().equals(player.getUUID())||old.revision()!=revision||!old.dimension().equals(level.dimension().identifier())))return "changed";
        if(shape.empty())return "empty";String invalid=validBounds(level,shape);if(invalid!=null)return invalid;
        if(old==null&&(data.all().size()>=1024||data.owned(player.getUUID()).size()>=64))return "region_limit";
        Optional<UUID> parent=old==null?(parentId.equals(ProtectionData.NONE)?Optional.empty():Optional.of(parentId)):old.parent();
        if(parent.isPresent()){
            var root=data.region(parent.get());if(root==null||root.parent().isPresent()||!root.owner().equals(player.getUUID())||!root.dimension().equals(level.dimension().identifier())||!root.shape().contains(shape))return "room_outside";
        }
        for(var r:data.all()){
            if(!r.dimension().equals(level.dimension().identifier())||r.id().equals(id))continue;
            if(!r.owner().equals(player.getUUID())&&r.shape().intersects(shape))return "foreign_overlap";
            if(r.owner().equals(player.getUUID())){
                if(parent.isEmpty()&&r.parent().isEmpty()&&r.shape().intersects(shape))return "own_overlap";
                if(parent.isPresent()&&r.parent().equals(parent)&&r.shape().intersects(shape))return "room_overlap";
                if(parent.isEmpty()&&r.parent().isPresent()&&r.parent().get().equals(id)&&!shape.contains(r.shape()))return "room_outside";
            }
        }
        long cost=data.used(player.getUUID())-(old!=null&&old.parent().isEmpty()?old.shape().cost():0)+(parent.isEmpty()?shape.cost():0);
        int needed=ProtectionEconomy.additionalMatrices(data.matrices(player.getUUID()),cost);
        if(needed<0)return "matrix_limit";
        if(needed>0){if(!acquireCapacity)return "capacity";if(approvedMatrices<needed)return "quote_changed";if(!data.canInstall(player.getUUID(),needed))return "matrix_limit";if(availableMatrices(player)<needed)return "need_matrices";}
        ProtectionShape added=old==null?shape:shape.subtract(old.shape());
        if(!added.empty()){
            var chunks=new HashSet<Long>();for(var b:added.boxes())for(int x=b.x0()>>4;x<=(b.x1()-1)>>4;x++)for(int z=b.z0()>>4;z<=(b.z1()-1)>>4;z++)chunks.add(net.minecraft.world.level.ChunkPos.pack(x,z));
            for(long key:chunks){int cx=net.minecraft.world.level.ChunkPos.getX(key),cz=net.minecraft.world.level.ChunkPos.getZ(key);if(!level.isLoaded(new BlockPos(cx<<4,level.getMinY(),cz<<4)))return "unloaded";
                var chunk=level.getChunkSource().getChunkNow(cx,cz);if(chunk==null)return "unloaded";
                for(var be:chunk.getBlockEntities().values())if(added.contains(be.getBlockPos().getX(),be.getBlockPos().getY(),be.getBlockPos().getZ())||be instanceof DeviceBlockEntity d&&d.kind()==dev.umbral.block.DeviceKind.DOOR&&added.contains(be.getBlockPos().getX(),be.getBlockPos().getY()+1,be.getBlockPos().getZ())){
                    if(be instanceof DeviceBlockEntity device&&device.owner()!=null&&!device.ownedBy(player.getUUID()))return "foreign_device";
                    var seal=be.components().get(UmbralContent.STOCK_SEAL);if(seal!=null&&!seal.owner().equals(player.getUUID()))return "foreign_device";
                }
            }
        }
        label=ProtectionData.clean(label,48);if(label.isEmpty())label=parent.isPresent()?"Sala":"Área";
        if(needed>0){
            // No validation, callbacks or world loads occur after this point and before the ledger edit.
            data.install(player.getUUID(),needed);int remaining=needed;
            for(int n=0;n<player.getInventory().getContainerSize()&&remaining>0;n++){var s=player.getInventory().getItem(n);if(s.is(UmbralContent.PROTECTION_MATRIX)){int take=Math.min(remaining,s.getCount());s.shrink(take);remaining-=take;}}
            player.getInventory().setChanged();
        }
        if(old==null)data.put(new ProtectionData.Region(UUID.randomUUID(),player.getUUID(),level.dimension().identifier(),label,parent,shape,List.of(),ProtectionData.ALL,1));else data.put(old.edited(shape,label));
        if(needed>0)player.containerMenu.broadcastChanges();
        return "applied";
    }
    public static String validBounds(ServerLevel level,ProtectionShape shape){
        var b=shape.bounds();if(b==null)return "empty";
        if(b.y0()<level.getMinY()||b.y1()>level.getMaxY()+1||Math.abs((long)b.x0())>29999984||Math.abs((long)b.x1())>29999984||Math.abs((long)b.z0())>29999984||Math.abs((long)b.z1())>29999984)return "bounds";
        if(!level.getWorldBorder().isWithinBounds(new BlockPos(b.x0(),b.y0(),b.z0()))||!level.getWorldBorder().isWithinBounds(new BlockPos(b.x1()-1,b.y1()-1,b.z1()-1)))return "bounds";
        return null;
    }
    public static String member(ServerPlayer player,UUID id,long revision,UUID member,String name,int permissions,boolean revoke){
        var data=ProtectionData.get((ServerLevel)player.level());var old=data.region(id);if(old==null||!old.owner().equals(player.getUUID())||old.revision()!=revision)return "changed";
        if(member.equals(ProtectionData.NONE)||member.equals(player.getUUID()))return "invalid";
        // The server resolves names only from authenticated online profiles or existing grants.
        var target=player.level().getServer().getPlayerList().getPlayer(member);String actual=target==null?old.members().stream().filter(m->m.player().equals(member)).map(ProtectionData.Member::name).findFirst().orElse(null):target.getGameProfile().name();
        if(actual==null&&!revoke)return "player_missing";var members=new ArrayList<>(old.members());members.removeIf(m->m.player().equals(member));
        if(!revoke){if(members.size()>=32)return "member_limit";members.add(new ProtectionData.Member(member,actual,permissions));}
        data.put(new ProtectionData.Region(old.id(),old.owner(),old.dimension(),old.label(),old.parent(),old.shape(),members,old.flags(),old.revision()+1));return "permissions";
    }
    public static String flags(ServerPlayer player,UUID id,long revision,int flags){var data=ProtectionData.get((ServerLevel)player.level());var old=data.region(id);if(old==null||!old.owner().equals(player.getUUID())||old.revision()!=revision||old.parent().isPresent())return "changed";data.put(new ProtectionData.Region(old.id(),old.owner(),old.dimension(),old.label(),old.parent(),old.shape(),old.members(),flags,old.revision()+1));return "flags";}
    public static String delete(ServerPlayer player,UUID id,long revision){var data=ProtectionData.get((ServerLevel)player.level());var old=data.region(id);if(old==null||!old.owner().equals(player.getUUID())||old.revision()!=revision)return "changed";data.remove(id);return "deleted";}
    private ProtectionService(){}
}
