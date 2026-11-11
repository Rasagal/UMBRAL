// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.UmbralContent;
import dev.umbral.manual.ManualCatalog.*;
import dev.umbral.protection.ProtectionShape;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import static dev.umbral.client.ConsoleStyle.*;

/** Native item models, not reinterpretations of the equipment artwork. */
final class ManualFigures {
    private final ProtectionMap map=new ProtectionMap();
    private static final ProtectionShape BASE=new ProtectionShape(List.of(new ProtectionShape.Box(0,0,0,8,5,8)));
    private static final ProtectionShape EXTENSION=BASE.add(new ProtectionShape(List.of(new ProtectionShape.Box(8,0,2,13,7,7))));
    void draw(GuiGraphicsExtractor g,Font font,Entry entry,int x,int y,int w,int h,int ticks,int mx,int my){
        card(g,x,y,w,h);g.enableScissor(x+1,y+1,x+w-1,y+h-1);
        switch(entry.figure){
            case CLAIM,EXPAND->{
                map.example(g,x+2,y+2,w-4,h-20,entry.figure==Figure.EXPAND?BASE:new ProtectionShape(),EXTENSION);
                legend(g,font,x+7,y+h-14,w-14,"manual.umbral.figure.saved",INK,"manual.umbral.figure.added",ORANGE);
            }
            case CAMERA->{
                int size=Math.min(96,Math.min((w-20)/3,h-24));String[] mounts={"wall","floor","ceiling"};
                for(int n=0;n<3;n++){var stack=ManualScreen.item(Entry.VIGIA);stack.set(DataComponents.ITEM_MODEL,UmbralContent.id("manual_camera_"+mounts[n]));int px=x+(2*n+1)*w/6-size/2;
                    item(g,stack,px,y+(h-size-13)/2,size);label(g,font,Component.translatable("manual.umbral.mount."+mounts[n]),x+n*w/3,y+h-13,w/3);}
            }
            case NETWORK->flow(g,font,x,y,w,h,List.of(Entry.CORE,Entry.CABLE,Entry.NODE,Entry.VIGIA),"manual.umbral.figure.radio");
            case ACCESS->flow(g,font,x,y,w,h,List.of(Entry.CARD,Entry.READER,Entry.DOOR),"manual.umbral.figure.access");
            case INSPECTION->flow(g,font,x,y,w,h,List.of(Entry.FILTER,Entry.CARD,Entry.DOOR),"manual.umbral.figure.inspection");
            case PERIMETER->flow(g,font,x,y,w,h,List.of(Entry.NEXO,Entry.FENCE,Entry.CARD),"manual.umbral.figure.perimeter");
            case AIRLOCK->flow(g,font,x,y,w,h,List.of(Entry.DOOR,Entry.FILTER,Entry.DOOR),"manual.umbral.figure.airlock");
            case DEFENSE->flow(g,font,x,y,w,h,List.of(Entry.BALUARTE,Entry.CENTELLA),"manual.umbral.figure.defense");
            case LOGISTICS->{
                int size=Math.min(60,Math.min((w-26)/4,h-25));var stacks=List.of(new ItemStack(net.minecraft.world.item.Items.CHEST),ManualScreen.item(Entry.DOCK),ManualScreen.item(Entry.MOTA),ManualScreen.item(Entry.CENTELLA));
                for(int n=0;n<stacks.size();n++){int px=x+(2*n+1)*w/8-size/2;item(g,stacks.get(n),px,y+(h-size-18)/2,size);if(n<3)text(g,font,Component.literal(">"),x+(n+1)*w/4-3,y+h/2-8,10,INK);}
                label(g,font,Component.translatable("manual.umbral.figure.logistics"),x+5,y+h-13,w-10);
            }
            case START->flow(g,font,x,y,w,h,List.of(Entry.CONFIGURATOR,Entry.FIRST_BASE,Entry.NEXO),"manual.umbral.figure.start");
            case DIAGNOSTICS->flow(g,font,x,y,w,h,List.of(Entry.CORE,Entry.NODE,Entry.CONFIGURATOR),"manual.umbral.figure.diagnostics");
            case DEVICE->{
                int size=Math.min(130,Math.min(w-22,h-20));item(g,ManualScreen.item(entry),x+(w-size)/2,y+(h-size)/2,size);
                corners(g,x+10,y+10,w-20,h-20,5,LINE);
            }
        }
        g.disableScissor();
    }
    private void flow(GuiGraphicsExtractor g,Font font,int x,int y,int w,int h,List<Entry> entries,String caption){
        int count=entries.size(),size=Math.min(88,Math.min((w-20)/count,h-28));
        for(int n=0;n<count;n++){
            item(g,ManualScreen.item(entries.get(n)),x+(2*n+1)*w/(count*2)-size/2,y+(h-size-17)/2,size);
            if(n+1<count)text(g,font,Component.literal(">"),x+(n+1)*w/count-3,y+(h-17)/2,10,INK);
        }
        label(g,font,Component.translatable(caption),x+5,y+h-13,w-10);
    }
    private static void item(GuiGraphicsExtractor g,ItemStack stack,int x,int y,int size){g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(size/16f,size/16f);g.item(stack,0,0);g.pose().popMatrix();}
    private static void label(GuiGraphicsExtractor g,Font font,Component value,int x,int y,int w){String s=fit(font,value.getString(),w-4);g.text(font,s,x+(w-font.width(s))/2,y,INK,false);}
    private static void legend(GuiGraphicsExtractor g,Font font,int x,int y,int w,String a,int ac,String b,int bc){
        g.fill(x,y,x+5,y+7,ac);text(g,font,Component.translatable(a),x+9,y,w/2-12,INK);g.fill(x+w/2,y,x+w/2+5,y+7,bc);text(g,font,Component.translatable(b),x+w/2+9,y,w/2-10,INK);
    }
}
