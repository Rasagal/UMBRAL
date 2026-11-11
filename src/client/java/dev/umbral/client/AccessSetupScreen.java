// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.access.*;
import dev.umbral.access.AccessSetupProtocol.*;
import dev.umbral.block.DeviceKind;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static dev.umbral.client.ConsoleStyle.*;

/** One objective at a time; the server reviews all physical references again on confirmation. */
public final class AccessSetupScreen extends Screen {
    private Snapshot data;
    private final Screen parent;
    private DoorTarget door,reader,filter;
    private int step,page,rows,left,top,w,h,ticks,sentAt,lastUpdate;
    private boolean pending,reviewed,completed;
    private long nonce;
    private Operation sentOperation=Operation.QUERY;
    private String notice="choose_door";
    private final List<ConsoleButton> controls=new ArrayList<>();
    public AccessSetupScreen(Snapshot data,Screen parent){super(tr("title"));this.data=data;this.parent=parent;}
    public Screen parent(){return parent;}
    private static Component tr(String key,Object...args){return Component.translatable("setup.umbral."+key,args);}
    private DeviceKind kind(){return step==0?DeviceKind.DOOR:step==1?DeviceKind.READER:DeviceKind.FILTER;}
    private DoorTarget selected(){return step==0?door:step==1?reader:filter;}
    private List<Choice> choices(){return data.choices().stream().filter(c->c.kind()==kind()).toList();}
    private boolean fresh(){return data.valid()&&ticks-lastUpdate<80;}
    private ConsoleButton button(int x,int y,int width,Component label,boolean accent,Runnable action){var b=addRenderableWidget(new ConsoleButton(x,y,width,20,label,accent,v->action.run()));controls.add(b);return b;}
    @Override protected void init(){
        clearWidgets();controls.clear();w=Math.min(560,width-16);h=Math.min(330,height-16);left=(width-w)/2;top=(height-h)/2;rows=Math.clamp((h-170)/28,1,5);
        if(step<3&&!completed){
            var choices=choices();page=Math.clamp(page,0,Math.max(0,(choices.size()-1)/rows));
            int first=page*rows;
            for(int n=first;n<Math.min(first+rows,choices.size());n++){
                var choice=choices.get(n);String name=choice.label().isEmpty()?Component.translatable("block.umbral."+choice.kind().id).getString():choice.label();
                var b=button(left+12,top+86+(n-first)*28,w-24,Component.literal(name+" · "+choice.reference().position().toShortString()),Objects.equals(selected(),choice.reference()),()->select(choice.reference()));
                b.active=!pending&&fresh()&&choice.reason().equals("ready");b.describe(tr("result."+choice.reason()));
            }
            int y=top+h-78;
            if(step==2)button(left+12,y,Math.min(150,w-90),tr("without_filter"),filter==null,()->{filter=null;step=3;page=0;reviewed=false;send(Operation.REVIEW);});
            var prev=button(left+w-70,y,24,Component.literal("<"),false,()->{page--;init();});prev.active=page>0&&!pending;
            var next=button(left+w-42,y,24,Component.literal(">"),false,()->{page++;init();});next.active=(page+1)*rows<choices.size()&&!pending;
        }else if(!completed){
            var save=button(left+12,top+h-55,w-24,tr("save"),true,()->send(Operation.APPLY));save.active=fresh()&&!pending&&reviewed&&data.ready();
        }else if(filter!=null){
            var configure=button(left+12,top+h-55,w-24,tr("configure_filter"),true,()->send(Operation.CONFIGURE_FILTER));configure.active=!pending&&fresh();
        }
        if(!completed&&step>0){var back=button(left+12,top+h-29,85,tr("back"),false,()->{step--;page=0;reviewed=false;init();});back.active=!pending;}
        if(!completed&&step<3){var refresh=button(left+104,top+h-29,Math.min(110,w-212),tr("refresh"),false,()->send(Operation.QUERY));refresh.active=!pending;}
        button(left+w-92,top+h-29,80,Component.translatable("gui.done"),false,this::onClose);
        addRenderableWidget(ManualScreen.help(this,left+w-30,top+31,dev.umbral.manual.ManualCatalog.Entry.ENTRANCE));
    }
    private void select(DoorTarget ref){
        if(step==0){if(!Objects.equals(door,ref)){reader=null;filter=null;}door=ref;step=1;}
        else if(step==1){reader=ref;step=2;}else{filter=ref;step=3;}
        page=0;reviewed=false;send(step==3?Operation.REVIEW:Operation.QUERY);
    }
    private void send(Operation op){
        if(pending||op!=Operation.QUERY&&!fresh())return;
        pending=true;nonce=ThreadLocalRandom.current().nextLong();sentAt=ticks;sentOperation=op;
        ClientPlayNetworking.send(new Request(data.root(),data.identity(),data.link(),nonce,op,door,reader,filter));notice="updating";init();
    }
    public void accept(Snapshot s){
        if(!pending||s.nonce()!=nonce||!data.root().equals(s.root())||!data.identity().equals(s.identity())||!data.link().equals(s.link()))return;
        pending=false;
        if(s.result().equals("busy")){notice="busy";init();return;}
        if(!s.valid()){onClose();return;}
        data=s;lastUpdate=ticks;if(!completed||sentOperation!=Operation.QUERY||Set.of("changed","offline").contains(s.result()))notice=s.result();
        if(s.result().equals("review"))reviewed=s.ready();
        if(s.result().equals("saved")||s.result().equals("saved_offline")){completed=true;step=4;}
        init();
    }
    @Override public void tick(){
        if(minecraft.player==null||minecraft.level==null||!minecraft.player.isAlive()||minecraft.player.isSpectator()||!minecraft.level.dimension().identifier().equals(data.link().dimension())||!minecraft.level.isLoaded(data.root())||data.root().distToCenterSqr(minecraft.player.position())>64){onClose();return;}
        ticks++;
        if(pending&&ticks-sentAt>60){pending=false;lastUpdate=ticks-81;notice="stale";init();}
        if(ticks%40==0&&!pending)send(fresh()&&step==3&&!reviewed?Operation.REVIEW:Operation.QUERY);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){minecraft.gui.setScreen(parent);if(parent instanceof NexoScreen nexo)nexo.refreshAfterChild();}
    private Component label(DoorTarget ref){if(ref==null)return tr("without_filter");return data.choices().stream().filter(c->c.reference().equals(ref)).findFirst().map(c->Component.literal(c.label().isEmpty()?Component.translatable("block.umbral."+c.kind().id).getString():c.label()).append(" · ").append(c.reference().position().toShortString())).orElse(Component.literal(ref.position().toShortString()));}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        backdrop(g,width,height);frame(g,font,left,top,w,h,title,"UMBRAL / ACCESO");
        text(g,font,tr("step."+step),left+14,top+56,w-28,INK);
        if(step<3&&!completed){
            if(choices().isEmpty())text(g,font,tr("none."+kind().id),left+16,top+92,w-32,MUTED);
            int pages=Math.max(1,(choices().size()+rows-1)/rows);text(g,font,Component.literal((page+1)+" / "+pages),left+w-120,top+h-72,45,MUTED);
        }else{
            card(g,left+12,top+83,w-24,Math.max(60,h-165));
            text(g,font,tr("door").copy().append(": ").append(label(door)),left+20,top+91,w-40,INK);
            text(g,font,tr("reader").copy().append(": ").append(label(reader)),left+20,top+108,w-40,INK);
            text(g,font,tr("filter").copy().append(": ").append(label(filter)),left+20,top+125,w-40,INK);
            if(h>=285){var explanation=font.split(tr(completed?(filter==null?"next_card":"next_filter"):"review_hint"),w-44);for(int n=0;n<Math.min(3,explanation.size());n++)g.text(font,explanation.get(n),left+22,top+149+n*10,MUTED,false);}
        }
        int feedbackY=top+h-(step<3&&!completed?98:78);
        var feedback=tr("result."+(!fresh()?"stale":notice));text(g,font,feedback,left+14,feedbackY,w-28,notice.equals("saved")?INK:MUTED);
        if(mx>=left+12&&mx<left+w-12&&my>=feedbackY-4&&my<feedbackY+14)g.setTooltipForNextFrame(font,feedback,mx,my);
        if(step>=3&&mx>=left+12&&mx<left+w-12)for(int n=0;n<3;n++)if(my>=top+89+n*17&&my<top+105+n*17)g.setTooltipForNextFrame(font,label(n==0?door:n==1?reader:filter),mx,my);
        super.extractRenderState(g,mx,my,delta);
    }
}
