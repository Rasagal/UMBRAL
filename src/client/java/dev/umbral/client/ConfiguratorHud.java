// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.logistics.MotaService;
import dev.umbral.protection.ProtectionSelections;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import java.util.*;
import static dev.umbral.client.ConsoleStyle.*;

/** Selection guidance is a read-only view of the held tool, never an authorization. */
public final class ConfiguratorHud {
    private static Component tr(String key,Object...args){return Component.translatable("configurator.umbral."+key,args);}
    private static ItemStack tool(Minecraft mc){
        if(mc.player==null)return ItemStack.EMPTY;
        if(mc.player.getMainHandItem().is(UmbralContent.CONFIGURATOR))return mc.player.getMainHandItem();
        return mc.player.getOffhandItem().is(UmbralContent.CONFIGURATOR)?mc.player.getOffhandItem():ItemStack.EMPTY;
    }
    public static boolean visible(Minecraft mc){
        return mc.level!=null&&mc.player!=null&&mc.player.isAlive()&&!mc.player.isSpectator()
                &&mc.gui.screen()==null&&!mc.gui.hud.isHidden()&&mc.getCameraEntity()==mc.player&&!tool(mc).isEmpty();
    }
    private static Component position(BlockPos pos){return Component.literal(pos.toShortString());}
    /** Kept public so native checks can verify actual client-synchronized selection state. */
    public static List<Component> lines(Minecraft mc){
        if(mc.level==null||mc.player==null)return List.of();
        var stack=tool(mc);if(stack.isEmpty())return List.of();
        var network=stack.get(UmbralContent.SELECTED_NETWORK);var door=stack.get(UmbralContent.SELECTED_DOOR);var stock=stack.get(UmbralContent.SELECTED_STOCK);
        var lines=new ArrayList<Component>();
        lines.add(network==null?tr("network_none"):tr(network.dimension().equals(mc.level.dimension().identifier())?"network":"network_other_dimension",position(network.core())));
        if(door!=null)lines.add(tr("door",position(door.position())));
        if(stock!=null)lines.add(tr("stock",position(stock.position())));
        var preview=ProtectionOverlay.latest;
        if(preview!=null&&preview.valid()&&preview.access().dimension().equals(mc.level.dimension().identifier())&&preview.selection().mode()!=ProtectionSelections.Mode.NONE){
            var d=preview.selection();lines.add(tr("marking",d.points().size()));
            lines.add(tr("use",tr("point")));
            lines.add(tr("shift_air",tr(d.mode()==ProtectionSelections.Mode.FOOTPRINT?"cancel_area":"finish_area")));
            return List.copyOf(lines);
        }
        String use="areas",shift="clear";boolean air=true;
        if(mc.hitResult instanceof BlockHitResult hit&&mc.level.isLoaded(hit.getBlockPos())){
            var state=mc.level.getBlockState(hit.getBlockPos());var pos=hit.getBlockPos();
            if(state.getBlock() instanceof FilterBlock){pos=FilterBlock.basePos(pos,state);state=mc.level.getBlockState(pos);}
            if(state.getBlock() instanceof SecurityDoorBlock){pos=SecurityDoorBlock.lower(pos,state);state=mc.level.getBlockState(pos);}
            if(state.getBlock() instanceof DeviceBlock device){
                air=false;shift="inspect";
                if(device.kind()==DeviceKind.CORE){use="select_network";shift="coverage";}
                else if(device.kind()==DeviceKind.DOOR){use=network==null?"need_network":"bind";shift="select_door";}
                else if(device.kind()==DeviceKind.DOCK&&stock!=null)use="assign_stock";
                else if(device.kind()==DeviceKind.READER&&door!=null)use="assign_door";
                else if(device.kind()==DeviceKind.FILTER&&door!=null)use="assign_filter";
                else{use=network==null?"need_network":"bind";if(device.kind()==DeviceKind.RELAY)shift="coverage";}
            }else if(MotaService.container(mc.level.getBlockEntity(pos))){air=false;use="open_container";shift=network==null?"need_network":"select_stock";}
        }
        lines.add(tr(air?"use_air":"use",tr(use)));
        lines.add(tr(air?"shift_air":"shift",tr(shift)));
        return List.copyOf(lines);
    }
    public static void render(GuiGraphicsExtractor g,DeltaTracker delta){
        var mc=Minecraft.getInstance();if(!visible(mc))return;
        var lines=lines(mc);if(lines.isEmpty())return;
        int width=Math.min(280,mc.getWindow().getGuiScaledWidth()-16),height=22+lines.size()*10,x=8,y=8;
        if(width<120||height>mc.getWindow().getGuiScaledHeight()/2-4)return;
        cutPanel(g,x,y,width,height,SHELL_DARK);cutPanel(g,x+1,y+1,width-2,height-2,SHELL);
        g.fill(x+4,y+4,x+width-4,y+14,GASKET);g.fill(x+4,y+4,x+6,y+14,ORANGE);
        text(g,mc.font,tr("title"),x+10,y+5,width-20,SHELL_LIGHT);
        g.fill(x+4,y+17,x+width-4,y+height-4,LCD);
        for(int n=0;n<lines.size();n++)text(g,mc.font,lines.get(n),x+9,y+19+n*10,width-18,INK);
    }
    private ConfiguratorHud(){}
}
