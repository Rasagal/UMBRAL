// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.turret.*;
import dev.umbral.block.DeviceBlockEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import static dev.umbral.client.ConsoleStyle.*;

public final class TurretScreen extends Screen {
    private TurretProtocol.Snapshot data;
    private ConsoleButton load,unload,arm,range,preference;
    private int left,top,w,h,ticks,lastUpdate,sentAt,noticeUntil;
    private long nonce;
    private boolean pending;
    private Component notice;
    public TurretScreen(TurretProtocol.Snapshot value){super(Component.translatable(value.metal()?"centella.umbral.title":"turret.umbral.title"));data=value;}
    private boolean fresh(){return data.valid()&&ticks-lastUpdate<=60;}
    private boolean actionable(){return fresh()&&!pending;}
    @Override protected void init(){
        w=Math.min(500,width-20);h=Math.min(290,height-20);left=(width-w)/2;top=(height-h)/2;
        int half=(w-40)/2,third=(w-48)/3;
        int supplyWidth=data.metal()?third:half;
        load=addRenderableWidget(new ConsoleButton(left+16,top+h-60,supplyWidth,20,Component.translatable(data.metal()?(w<350?"centella.umbral.load_short":"centella.umbral.load"):"turret.umbral.load"),false,b->send(TurretProtocol.Operation.LOAD)));
        load.describe(Component.translatable(data.metal()?"centella.umbral.load_hint":"turret.umbral.load_hint"));
        unload=addRenderableWidget(new ConsoleButton(left+24+supplyWidth,top+h-60,supplyWidth,20,Component.translatable("turret.umbral.unload"),false,b->send(TurretProtocol.Operation.UNLOAD)));
        unload.describe(Component.translatable("turret.umbral.unload_hint"));
        preference=data.metal()?addRenderableWidget(new ConsoleButton(left+32+third*2,top+h-60,third,20,Component.empty(),false,b->send(TurretProtocol.Operation.PREFERENCE))):null;
        if(preference!=null)preference.describe(Component.translatable("centella.umbral.preference_hint"));
        arm=addRenderableWidget(new ConsoleButton(left+16,top+h-34,third,22,Component.empty(),true,b->send(data.armed()?TurretProtocol.Operation.DISARM:TurretProtocol.Operation.ARM)));
        arm.describe(Component.translatable("turret.umbral.arm_hint"));
        range=addRenderableWidget(new ConsoleButton(left+24+third,top+h-34,third,22,Component.empty(),false,b->send(TurretProtocol.Operation.RANGE)));
        range.describe(Component.translatable("turret.umbral.range_hint"));
        addRenderableWidget(new ConsoleButton(left+32+third*2,top+h-34,third,22,Component.translatable("gui.done"),false,b->onClose()));
        addRenderableWidget(ManualScreen.help(this,left+w-30,top+31,data.metal()?dev.umbral.manual.ManualCatalog.Entry.CENTELLA:dev.umbral.manual.ManualCatalog.Entry.BALUARTE));
        updateButtons();
    }
    private void updateButtons(){
        load.active=actionable()&&data.canLoad();unload.active=actionable()&&data.ammunition()>0;
        arm.setMessage(Component.translatable(data.armed()?"turret.umbral.disarm":"turret.umbral.arm"));
        arm.active=actionable()&&(data.armed()||data.status()!=TurretService.Status.OFFLINE&&data.ammunition()>0);
        range.setMessage(Component.translatable("turret.umbral.range_button",data.range()));range.active=actionable();
        if(preference!=null){preference.setMessage(Component.translatable("centella.umbral.preference_button",Component.translatable(data.preference().key()+".short")));preference.active=actionable();}
    }
    private void send(TurretProtocol.Operation operation){
        if(operation!=TurretProtocol.Operation.QUERY&&!actionable())return;
        nonce=ThreadLocalRandom.current().nextLong();pending=true;sentAt=ticks;
        ClientPlayNetworking.send(new TurretProtocol.Request(data.position(),data.identity(),data.link(),nonce,operation));
        if(operation!=TurretProtocol.Operation.QUERY){notice=Component.translatable("ui.umbral.updating");noticeUntil=ticks+80;}
        updateButtons();
    }
    public void accept(TurretProtocol.Snapshot value){
        if(!data.position().equals(value.position())||!data.identity().equals(value.identity())||!Objects.equals(data.link(),value.link())||!pending||nonce!=value.nonce())return;
        pending=false;
        if(value.result()==TurretProtocol.Result.BUSY){notice=Component.translatable(value.result().key());noticeUntil=ticks+60;updateButtons();return;}
        data=value;lastUpdate=ticks;
        if(value.result()!=TurretProtocol.Result.READY){notice=Component.translatable(value.result().key());noticeUntil=ticks+80;}
        updateButtons();
    }
    @Override public void tick(){
        if(minecraft.player==null||minecraft.level==null||!minecraft.player.isAlive()||minecraft.player.isSpectator()||!data.valid()
                ||data.position().distToCenterSqr(minecraft.player.position())>64||!minecraft.level.isLoaded(data.position())
                ||!(minecraft.level.getBlockEntity(data.position()) instanceof DeviceBlockEntity d)||!d.identity().equals(data.identity())){onClose();return;}
        ticks++;if(pending&&ticks-sentAt>60){pending=false;lastUpdate=ticks-61;}
        if(ticks%20==0&&!pending)send(TurretProtocol.Operation.QUERY);updateButtons();
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){
        backdrop(g,width,height);frame(g,font,left,top,w,h,title,Component.translatable("turret.umbral.module").getString());
        text(g,font,data.label().isEmpty()?title:Component.literal(data.label()),left+17,top+58,w-34,INK);
        text(g,font,Component.translatable(fresh()?data.status().key():"screen.umbral.stale"),left+17,top+72,w-34,data.status()==TurretService.Status.OFFLINE?ALERT:INK);
        int art=w>=400?112:76,bodyHeight=h-160;
        card(g,left+16,top+84,art,bodyHeight);
        int scale=bodyHeight>=90?4:3;
        device(g,left+16+(art-16*scale)/2,top+88,data.metal()?"centella_turret":"baluarte_turret",scale,data.armed());
        if(bodyHeight>=110&&minecraft.level!=null&&minecraft.level.getBlockEntity(data.position()) instanceof DeviceBlockEntity turret){
            var pose=turret.turret().motion();long now=minecraft.level.getGameTime();
            text(g,font,Component.translatable("turret.umbral.pan",Math.round(pose.yawAt(now))),left+24,top+160,art-16,MUTED);
            text(g,font,Component.translatable("turret.umbral.tilt",Math.round(pose.pitchAt(now))),left+24,top+174,art-16,MUTED);
        }
        if(bodyHeight>=90)text(g,font,Component.translatable("turret.umbral.arc"),left+24,top+bodyHeight+66,art-16,MUTED);
        int x=left+24+art,bw=w-art-40;card(g,x,top+84,bw,bodyHeight);
        text(g,font,Component.translatable(data.metal()?"centella.umbral.ammunition":"turret.umbral.ammunition",data.ammunition(),64),x+8,top+90,bw-16,INK);
        g.fill(x+8,top+104,x+bw-8,top+111,LINE);
        g.fill(x+9,top+105,x+9+(bw-18)*data.ammunition()/64,top+110,INK);
        text(g,font,Component.translatable("turret.umbral.range",data.range()),x+8,top+117,bw-16,MUTED);
        Component target=data.target().isEmpty()?Component.translatable(data.metal()?data.material().key():"turret.umbral.no_target"):Component.translatable(data.target());
        text(g,font,Component.translatable("turret.umbral.target",target),x+8,top+131,bw-16,MUTED);
        if(data.metal()&&bodyHeight>=100)text(g,font,Component.translatable(data.material().key()),x+8,top+146,bw-16,INK);
        text(g,font,!fresh()?Component.translatable("screen.umbral.stale"):notice!=null&&ticks<noticeUntil?notice:Component.translatable("turret.umbral.policy"),left+17,top+h-76,w-34,INK);
        super.extractRenderState(g,mouseX,mouseY,delta);
    }
}
