// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Shared authoritative decisions. No chunk lookup is required to enforce a saved area. */
public final class ProtectionPolicy {
    private static final ThreadLocal<Player> ACTOR=new ThreadLocal<>();
    public static Player actor(){return ACTOR.get();}
    public static Player enter(Player player){var old=ACTOR.get();ACTOR.set(player);return old;}
    public static void leave(Player previous){if(previous==null)ACTOR.remove();else ACTOR.set(previous);}
    public static ProtectionData.Region region(Level level,BlockPos pos){return level instanceof ServerLevel s?ProtectionData.get(s).at(level.dimension().identifier(),pos):null;}
    public static boolean permits(Level level,BlockPos pos,UUID player,int permission){return !(level instanceof ServerLevel s)||ProtectionData.get(s).permits(region(level,pos),player,permission);}
    public static boolean player(Player p,BlockPos pos,int permission,String action){
        if(!(p.level() instanceof ServerLevel level))return true;var data=ProtectionData.get(level);var r=data.at(level.dimension().identifier(),pos);
        if(data.permits(r,p.getUUID(),permission))return true;
        data.denied(r,action,p.getName().getString(),pos,level.getServer().overworld().getGameTime());if(!(p instanceof net.minecraft.server.level.ServerPlayer sp)||sp.connection!=null)p.sendOverlayMessage(Component.translatable("protection.umbral.denied",r.label()));return false;
    }
    public static boolean environmental(Level level,BlockPos pos,int flag,String action){
        if(!(level instanceof ServerLevel s))return false;var data=ProtectionData.get(s);var r=data.root(data.at(level.dimension().identifier(),pos));
        if(r==null||(r.flags()&flag)==0)return false;data.denied(r,action,"",pos,s.getServer().overworld().getGameTime());return true;
    }
    public static boolean entityChange(Level level,BlockPos pos,Entity entity){
        if(entity instanceof net.minecraft.world.entity.projectile.Projectile projectile)entity=projectile.getOwner();
        if(entity instanceof Player p)return player(p,pos,ProtectionData.BUILD,"break");
        return entity==null||!environmental(level,pos,ProtectionData.MOBS,"mob");
    }
    public static boolean automation(Level level,BlockPos from,BlockPos to,int flag,String action){
        if(!(level instanceof ServerLevel s))return true;var data=ProtectionData.get(s);var a=data.root(data.at(level.dimension().identifier(),from));var b=data.root(data.at(level.dimension().identifier(),to));
        if(a==null&&b==null||a!=null&&b!=null&&a.owner().equals(b.owner()))return true;
        var denied=a!=null&&(a.flags()&flag)!=0?a:b!=null&&(b.flags()&flag)!=0?b:null;if(denied==null)return true;
        data.denied(denied,action,"",to,s.getServer().overworld().getGameTime());return false;
    }
    public static boolean mutation(Level level,BlockPos pos,BlockState state){
        var actor=ACTOR.get();if(actor==null||level.isClientSide()||actor.level()!=level)return true;
        // Stateful mechanisms can be used without granting structural changes.
        boolean same=level.getBlockState(pos).getBlock()==state.getBlock();
        if(same&&state.getBlock() instanceof DeviceBlock)return true;
        boolean allowed=permits(level,pos,actor.getUUID(),ProtectionData.BUILD)||same&&permits(level,pos,actor.getUUID(),ProtectionData.USE);
        if(!allowed)player(actor,pos,same?ProtectionData.USE:ProtectionData.BUILD,same?"use":"place");return allowed;
    }
    public static void initialize(){
        PlayerBlockBreakEvents.BEFORE.register((level,p,pos,state,be)->{
            if(!player(p,pos,ProtectionData.BUILD,"break"))return false;
            if(state.getBlock() instanceof DoorBlock||state.getBlock() instanceof SecurityDoorBlock||state.getBlock() instanceof DoublePlantBlock){var other=state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER?pos.above():pos.below();if(!player(p,other,ProtectionData.BUILD,"break"))return false;}
            if(state.getBlock() instanceof BedBlock){var facing=state.getValue(BedBlock.FACING);var other=pos.relative(state.getValue(BedBlock.PART)==net.minecraft.world.level.block.state.properties.BedPart.FOOT?facing:facing.getOpposite());if(!player(p,other,ProtectionData.BUILD,"break"))return false;}
            return true;
        });
        UseBlockCallback.EVENT.register((p,level,hand,hit)->{
            if(level.isClientSide())return net.minecraft.world.InteractionResult.PASS;var stack=p.getItemInHand(hand);var pos=hit.getBlockPos();var state=level.getBlockState(pos);
            if(stack.is(UmbralContent.CONFIGURATOR)&&ProtectionSelections.active(p.getUUID())&&p instanceof net.minecraft.server.level.ServerPlayer sp){var result=ProtectionSelections.point(sp,pos);if(result!=InteractionResult.PASS)return result;}
            // These mechanisms retain their own bearer-card, owner and interior-egress authorization.
            if(state.getBlock() instanceof DeviceBlock&&(stack.isEmpty()||stack.is(UmbralContent.ACCESS_CARD)||stack.is(UmbralContent.CONFIGURATOR)||stack.is(UmbralContent.MOTA)))return net.minecraft.world.InteractionResult.PASS;
            boolean inventory=level.getBlockEntity(pos) instanceof Container;int required=inventory?ProtectionData.INVENTORY:ProtectionData.USE;
            if(!player(p,pos,required,inventory?"inventory":"use"))return net.minecraft.world.InteractionResult.FAIL;
            if(state.getBlock() instanceof ChestBlock&&state.getValue(ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE){var other=pos.relative(ChestBlock.getConnectedDirection(state));if(!player(p,other,ProtectionData.INVENTORY,"inventory"))return net.minecraft.world.InteractionResult.FAIL;}
            return net.minecraft.world.InteractionResult.PASS;
        });
    }
    private ProtectionPolicy(){}
}
