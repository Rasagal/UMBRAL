// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.airlock.AirlockProtocol.*;
import dev.umbral.access.DoorTarget;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.manual.ManualCatalog;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static dev.umbral.client.ConsoleStyle.*;

/** Live two-door diagram; configuring a chamber reuses the installed doors and FILTRO. */
public final class AirlockScreen extends Screen {
    private Snapshot data;
    private final Screen parent;
    private DoorTarget outer,inner;
    private int roomWidth,left,top,w,h,ticks,lastUpdate,sentAt;
    private boolean pending,dirty;
    private long nonce;
    private String notice="ready";
    private final List<ConsoleButton> controls=new ArrayList<>();
    public AirlockScreen(Snapshot data,Screen parent){super(tr("title"));this.data=data;this.parent=parent;outer=data.outer();inner=data.inner();roomWidth=data.width();}
    public Screen parent(){return parent;}
    private static Component tr(String key,Object... args){return Component.translatable("airlock.umbral."+key,args);}
    private boolean fresh(){return data.valid()&&ticks-lastUpdate<60;}
    private ConsoleButton button(int x,int y,int width,Component label,Runnable action){return addRenderableWidget(new ConsoleButton(x,y,width,20,label,false,b->action.run()));}
    private ConsoleButton control(int x,int y,int width,Component label,Runnable action){var b=button(x,y,width,label,action);controls.add(b);return b;}
    private String doorName(DoorTarget ref){if(ref==null)return tr("choose").getString();return data.doors().stream().filter(d->d.ref().equals(ref)).findFirst().map(d->d.name().isEmpty()?ref.position().toShortString():d.name()).orElse(ref.position().toShortString());}
    private DoorTarget cycle(DoorTarget current){if(data.doors().isEmpty())return null;int index=-1;for(int i=0;i<data.doors().size();i++)if(data.doors().get(i).ref().equals(current))index=i;return data.doors().get((index+1)%data.doors().size()).ref();}
    @Override protected void init(){
        clearWidgets();controls.clear();w=Math.min(570,width-16);h=Math.min(335,height-16);left=(width-w)/2;top=(height-h)/2;
        int bw=(w-30)/2;
        var o=control(left+12,top+74,bw,tr("outer_value",doorName(outer)),()->{outer=cycle(outer);dirty=true;init();});o.describe(tr("alignment"));
        var i=control(left+18+bw,top+74,bw,tr("inner_value",doorName(inner)),()->{inner=cycle(inner);dirty=true;init();});i.describe(tr("alignment"));
        control(left+12,top+101,bw,tr("width",roomWidth),()->{roomWidth=roomWidth==5?1:roomWidth+2;dirty=true;init();}).describe(tr("width_hint"));
        control(left+18+bw,top+101,bw,tr("save"),()->send(Operation.SAVE)).describe(tr("save_hint"));
        int aw=(w-36)/3;
        control(left+12,top+h-58,aw,tr(data.enabled()?"disable":"enable"),()->send(data.enabled()?Operation.DISABLE:Operation.ENABLE));
        control(left+18+aw,top+h-58,aw,tr("return"),()->send(Operation.RETURN)).describe(tr("return_hint"));
        control(left+24+aw*2,top+h-58,aw,tr("filter"),()->{minecraft.gui.setScreen(parent);if(parent instanceof NexoScreen nexo)nexo.configureFilter();});
        button(left+w-92,top+h-30,80,Component.translatable("gui.done"),this::onClose);
        addRenderableWidget(ManualScreen.help(this,left+w-30,top+31,ManualCatalog.Entry.AIRLOCK));updateControls();
    }
    private void updateControls(){for(int n=0;n<controls.size();n++){boolean config=n<4;controls.get(n).active=fresh()&&!pending&&(!config||!data.enabled())&&(n!=4||data.enabled()||!dirty&&outer!=null&&inner!=null)&&(n!=5||data.enabled());}}
    private void send(Operation op){if(pending||op!=Operation.QUERY&&!fresh())return;pending=true;sentAt=ticks;nonce=ThreadLocalRandom.current().nextLong();ClientPlayNetworking.send(new Request(data.root(),data.rootId(),data.link(),data.scanner(),data.scannerId(),nonce,data.revision(),op,outer,inner,roomWidth));if(op!=Operation.QUERY)notice="updating";updateControls();}
    public void accept(Snapshot s){if(!s.root().equals(data.root())||!s.rootId().equals(data.rootId())||!Objects.equals(s.link(),data.link())||!s.scanner().equals(data.scanner())||!s.scannerId().equals(data.scannerId())||!pending||s.nonce()!=nonce)return;pending=false;if(s.result().equals("busy")){notice="busy";updateControls();return;}if(!s.valid()){onClose();return;}data=s;lastUpdate=ticks;if(!s.result().equals("ready"))notice=s.result();if(s.result().equals("airlock_saved")||s.enabled()){dirty=false;outer=s.outer();inner=s.inner();roomWidth=s.width();}init();}
    @Override public void tick(){if(minecraft.player==null||minecraft.level==null||!minecraft.player.isAlive()||minecraft.player.isSpectator()||data.root().distToCenterSqr(minecraft.player.position())>64||!minecraft.level.isLoaded(data.root())||!(minecraft.level.getBlockEntity(data.root()) instanceof DeviceBlockEntity)){onClose();return;}ticks++;if(pending&&ticks-sentAt>60){pending=false;lastUpdate=ticks-61;notice="stale";}if(ticks%20==0&&!pending)send(Operation.QUERY);updateControls();}
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
        backdrop(g,width,height);frame(g,font,left,top,w,h,title,"NEXO / ESCLUSA");text(g,font,tr(data.enabled()?"active":"inactive"),left+14,top+57,w-28,data.enabled()?INK:MUTED);
        int dy=top+125,dh=Math.max(18,h-226),dx=left+16,dw=w-32;card(g,dx,dy,dw,dh);
        int doorW=12,doorH=Math.max(8,Math.min(43,dh-14)),cy=dy+(dh-doorH)/2;int a=dx+dw/5,b=dx+dw*4/5;
        g.fill(a-3,cy-3,a+doorW+3,cy+doorH+3,STEEL);g.fill(b-3,cy-3,b+doorW+3,cy+doorH+3,STEEL);
        for(int d=0;d<2;d++){int x=d==0?a:b;float progress=d==0?data.outerProgress():data.innerProgress();g.fill(x,cy,x+doorW,cy+doorH,SURFACE);int leaf=Math.max(1,Math.round(doorW/2f*(1-progress)));for(int side=0;side<2;side++){int edge=side==0?x:x+doorW-leaf;g.fill(edge,cy,edge+leaf,cy+doorH,INK);g.fill(edge,cy+doorH/2,edge+leaf,cy+doorH/2+2,ORANGE);}}
        int scanner=dx+dw/2;g.fill(scanner-2,cy,scanner+2,cy+doorH,LINE);for(int y=cy+4;y<cy+doorH;y+=7)g.fill(scanner-10,y,scanner+10,y+1,data.phase().equals("restricted")?ALERT:INK);
        if(dh>=40){text(g,font,tr("outside"),dx+6,dy+dh-11,dw/4,INK);text(g,font,tr("base"),b+17,dy+dh-11,dw/5,INK);}
        int sy=dy+dh+8;text(g,font,tr("phase."+data.phase()),left+16,sy,w-32,Set.of("restricted","multiple","offline").contains(data.phase())?ALERT:INK);
        if(h>=310)text(g,font,tr("occupants",data.occupants(),data.subject().isEmpty()?"—":data.subject()),left+16,sy+14,w-32,MUTED);
        text(g,font,Component.translatable(!fresh()?"screen.umbral.stale":"nexo.umbral.result."+notice),left+15,top+h-22,w-118,MUTED);
        super.extractRenderState(g,mx,my,dt);
    }
}
