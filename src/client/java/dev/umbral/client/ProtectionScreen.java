// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.UmbralContent;
import dev.umbral.protection.*;
import dev.umbral.protection.ProtectionProtocol.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static dev.umbral.client.ConsoleStyle.*;

/** Owner's transactional area editor. The same exact geometry drives 2D, 3D, price and enforcement. */
public final class ProtectionScreen extends Screen {
    private static boolean lastAdvanced;
    private boolean advanced=lastAdvanced;
    private Snapshot data;
    private UUID selected=ProtectionData.NONE,parent=ProtectionData.NONE;
    private long revision;
    private ProtectionShape draft=new ProtectionShape();
    private String label="",notice="ready";
    private int floor,ceiling,tab,left,top,w,h,mx,my,mw,mh,ticks,lastUpdate,sentAt;
    private long nonce;
    private Action pendingAction=Action.QUERY;
    private boolean pending,dirty,cut,contour,dragging;
    private ProtectionShape.Point start;
    private final List<ProtectionShape.Point> vertices=new ArrayList<>();
    private final ProtectionMap map=new ProtectionMap();
    private final List<ConsoleButton> mutations=new ArrayList<>();
    private ConsoleButton apply,finish,install,mode;
    private int journalPage;
    private int profileIndex,permissions=ProtectionData.USE;
    public static void receive(Snapshot update){
        var current=ManualScreen.underlying(net.minecraft.client.Minecraft.getInstance().gui.screen());
        ProtectionScreen target=current instanceof ProtectionScreen p?p:current instanceof Form f?f.parent:current instanceof Assistant a?a.parent:current instanceof Picker p?p.parent:current instanceof Review r?r.parent:null;
        if(target!=null){target.accept(update);if(current instanceof Assistant a&&net.minecraft.client.Minecraft.getInstance().gui.screen()==a)a.init();}
    }
    public ProtectionScreen(Snapshot initial){super(Component.translatable("protection.umbral.title"));data=initial;
        var player=net.minecraft.client.Minecraft.getInstance().player;floor=player==null?0:Math.max(player.level().getMinY(),player.blockPosition().getY()-3);ceiling=player==null?16:Math.min(player.level().getMaxY()+1,floor+16);
        if(!initial.selection().base().empty()||initial.selection().mode()!=ProtectionSelections.Mode.NONE){loadSelection(initial.selection());if(!advanced&&initial.selection().mode()==ProtectionSelections.Mode.NONE&&!draft.empty())tab=1;}
        else if(!initial.regions().isEmpty())load(initial.regions().getFirst());else map.center(draft,player==null?0:player.blockPosition().getX(),player==null?0:player.blockPosition().getZ());
    }
    private ProtectionData.Region region(){return data.regions().stream().filter(r->r.id().equals(selected)).findFirst().orElse(null);}
    private boolean fresh(){return data.valid()&&ticks-lastUpdate<60;}
    private boolean valid(){
        if(minecraft==null||minecraft.level==null||minecraft.player==null||!minecraft.player.isAlive()||minecraft.player.isSpectator()||!data.access().dimension().equals(minecraft.level.dimension().identifier()))return false;
        if(data.access().tool())return minecraft.player.getMainHandItem().is(UmbralContent.CONFIGURATOR)||minecraft.player.getOffhandItem().is(UmbralContent.CONFIGURATOR);
        return data.access().position().distToCenterSqr(minecraft.player.position())<=64&&minecraft.level.isLoaded(data.access().position());
    }
    private void load(ProtectionData.Region r){selected=r.id();revision=r.revision();parent=r.parent().orElse(ProtectionData.NONE);draft=r.shape();label=r.label();permissions=effectivePermissions();dirty=false;vertices.clear();var b=draft.bounds();floor=b.y0();ceiling=b.y1();recenter();}
    private void loadSelection(ProtectionSelections.Draft d){selected=d.region();revision=d.revision();parent=d.parent();draft=d.base();label=d.label();floor=d.floor();ceiling=d.ceiling();cut=d.cut();dirty=true;recenter();}
    private void recenter(){var p=net.minecraft.client.Minecraft.getInstance().player;map.center(draft,p==null?0:p.blockPosition().getX(),p==null?0:p.blockPosition().getZ());}
    private void create(boolean room){var r=region();parent=room&&r!=null?(r.parent().orElse(r.id())):ProtectionData.NONE;selected=ProtectionData.NONE;revision=0;draft=new ProtectionShape();label="";dirty=true;vertices.clear();tab=0;notice=room?"new_room":"new";init();}
    @Override protected void init(){
        clearWidgets();mutations.clear();apply=null;finish=null;install=null;w=Math.min(welcome()?500:680,width-16);h=Math.min(welcome()?260:390,height-16);left=(width-w)/2;top=(height-h)/2;
        mx=left+12;my=top+101;mw=w-24;mh=h-178;
        int row=top+55,available=w-24,toggle=76;
        mode=addRenderableWidget(new ConsoleButton(left+w-12-toggle,row,toggle,19,tr(advanced?"simple":"advanced"),false,b->{advanced=!advanced;lastAdvanced=advanced;if(!advanced&&tab>2)tab=0;if(!dirty){cut=false;contour=false;}init();}));mode.describe(tr("mode_hint"));mutations.add(mode);
        addRenderableWidget(ManualScreen.help(this,left+w-30,top+31,region()==null?dev.umbral.manual.ManualCatalog.Entry.FIRST_BASE:tab>=2?dev.umbral.manual.ManualCatalog.Entry.ROOMS:dev.umbral.manual.ManualCatalog.Entry.EXPANSION));
        if(welcome()){
            int bw=(w-32)/2;
            var protect=addRenderableWidget(new ConsoleButton(left+12,top+h-70,bw,22,tr("protect_base"),true,b->{create(false);beginQuick();}));mutations.add(protect);
            addRenderableWidget(new ConsoleButton(left+20+bw,top+h-70,bw,22,tr("recipe"),false,b->minecraft.gui.setScreen(new ManualScreen(this,dev.umbral.manual.ManualCatalog.Entry.MATRIX,ManualScreen.Tab.RECIPE))));
            addRenderableWidget(new ConsoleButton(left+12,top+h-42,w-24,20,Component.translatable("gui.done"),false,b->onClose()));updateButtons();return;
        }
        if(advanced){
            int remaining=available-toggle-4,slot=Math.max(38,(remaining-12)/6),picker=remaining-slot*3-12;
            areaPicker(left+12,row,picker);
            addRenderableWidget(new ConsoleButton(left+16+picker,row,slot,19,tr("new_button"),false,b->create(false)));
            var room=addRenderableWidget(new ConsoleButton(left+20+picker+slot,row,slot,19,tr("room_button"),false,b->create(true)));room.active=region()!=null;
            install=addRenderableWidget(new ConsoleButton(left+24+picker+slot*2,row,slot,19,tr("install"),true,b->send(Action.INSTALL)));install.describe(Component.translatable("protection.umbral.capacity",data.used(),data.capacity(),ProtectionEconomy.capacity(data.matrices()+1)-data.capacity()));
        }else areaPicker(left+12,row,available-toggle-4);
        int count=advanced?5:3,tw=(w-24-(count-1)*2)/count;String[] tabs={"plan","model","members","rules","journal"};
        for(int n=0;n<count;n++){final int index=n;var t=addRenderableWidget(new ConsoleButton(left+12+n*(tw+2),top+78,tw,18,tr(tabs[n]),tab==n,b->{tab=index;init();}));if(n==2)t.active=region()!=null;}
        int toolY=top+h-70,bw=(w-52)/5;
        if(tab<=1&&!advanced){
            int width=(w-36)/3;
            var mark=addRenderableWidget(new ConsoleButton(left+12,toolY,width,19,tr(region()==null?"mark_base":"expand"),true,b->beginQuick()));mutations.add(mark);
            addRenderableWidget(new ConsoleButton(left+18+width,toolY,width,19,tr(dirty&&!draft.empty()&&!cut?"height_addition":"floor_view"),false,b->minecraft.gui.setScreen(new Form(this,true))));
            mutations.add(addRenderableWidget(new ConsoleButton(left+24+width*2,toolY,width,19,tr(data.hints().isEmpty()?"assistant":"assistant_hint"),false,b->minecraft.gui.setScreen(new Assistant(this)))));
        }else if(tab<=1){
            addRenderableWidget(new ConsoleButton(left+12,toolY,bw,19,tr(cut?"cut":"add"),cut,b->{cut=!cut;init();}));
            addRenderableWidget(new ConsoleButton(left+19+bw,toolY,bw,19,tr(contour?"contour":"rectangle"),false,b->{contour=!contour;vertices.clear();init();}));
            finish=addRenderableWidget(new ConsoleButton(left+26+bw*2,toolY,bw,19,tr(vertices.size()>=3?"finish":"world"),false,b->{if(vertices.size()>=3)finishContour();else send(contour?Action.BEGIN_CONTOUR:Action.BEGIN_BOX);}));mutations.add(finish);
            addRenderableWidget(new ConsoleButton(left+33+bw*3,toolY,bw,19,tr("height"),false,b->minecraft.gui.setScreen(new Form(this,true))));
            mutations.add(addRenderableWidget(new ConsoleButton(left+40+bw*4,toolY,bw,19,tr("assistant"),false,b->minecraft.gui.setScreen(new Assistant(this)))));
        }else if(tab==2){
            int y=top+106;var profiles=profiles();var profile=profiles.isEmpty()?null:profiles.get(Math.floorMod(profileIndex,profiles.size()));
            addRenderableWidget(new ConsoleButton(mx+6,y,mw-12,20,Component.literal(profile==null?tr("no_players").getString():profile.name()),false,b->{profileIndex++;permissions=effectivePermissions();init();}));
            int cw=(mw-24)/3;for(int n=0;n<3;n++){int bit=1<<n;String name=n==0?"build":n==1?"use":"inventory";addRenderableWidget(new ConsoleButton(mx+6+n*(cw+6),y+26,cw,20,tr(name),(permissions&bit)!=0,b->{permissions^=bit;init();}));}
            var grant=addRenderableWidget(new ConsoleButton(mx+6,toolY,(mw-18)/2,19,tr("grant"),true,b->send(Action.GRANT)));grant.active=region()!=null&&profile!=null;mutations.add(grant);
            var revoke=addRenderableWidget(new ConsoleButton(mx+12+(mw-18)/2,toolY,(mw-18)/2,19,tr("revoke"),false,b->send(Action.REVOKE)));revoke.active=region()!=null&&profile!=null;mutations.add(revoke);
        }else if(tab==3){
            var r=region();var rules=r==null?null:r.parent().isEmpty()?r:data.regions().stream().filter(v->v.id().equals(r.parent().get())).findFirst().orElse(null);String[] names={"explosions","fire","mobs","fluids","pistons","containers"};int cols=w>=480?3:2,rows=(6+cols-1)/cols,cw=(mw-12-(cols-1)*5)/cols,ch=Math.min(26,Math.max(16,(mh-10-(rows-1)*5)/rows));
            for(int n=0;n<6;n++){int bit=1<<n;var button=addRenderableWidget(new ConsoleButton(mx+6+(n%cols)*(cw+5),my+5+(n/cols)*(ch+5),cw,ch,tr(names[n]),rules!=null&&(rules.flags()&bit)!=0,b->{if(region()!=null)sendValue(Action.FLAGS,region().flags()^bit);}));button.active=r!=null&&r.parent().isEmpty();if(r!=null&&r.parent().isPresent())button.describe(tr("rules_inherit"));mutations.add(button);}
        }
        if(tab==4){int size=Math.max(1,mh/25),pages=Math.max(1,(data.incidents().size()+size-1)/size);journalPage=Math.clamp(journalPage,0,pages-1);
            var prev=addRenderableWidget(new ConsoleButton(mx,toolY,35,19,Component.literal("<"),false,b->{journalPage--;init();}));prev.active=journalPage>0;
            var next=addRenderableWidget(new ConsoleButton(mx+41,toolY,35,19,Component.literal(">"),false,b->{journalPage++;init();}));next.active=journalPage<pages-1;
        }
        int by=top+h-45,b=(w-40)/3;
        apply=addRenderableWidget(new ConsoleButton(left+12,by,b,20,tr(advanced?"apply":"review"),true,z->openReview()));apply.describe(tr(advanced?"apply":"review"));mutations.add(apply);
        mutations.add(addRenderableWidget(new ConsoleButton(left+20+b,by,b,20,tr("discard"),false,z->discard())));
        var close=addRenderableWidget(new ConsoleButton(left+28+b*2,by,b,20,dirty?tr("exit_draft"):Component.translatable("gui.done"),false,z->onClose()));if(dirty)close.describe(tr("exit_draft"));updateButtons();
    }
    private boolean welcome(){return !advanced&&data.regions().isEmpty()&&draft.empty()&&!dirty;}
    private ProtectionShape saved(){var r=region();return r==null?new ProtectionShape():r.shape();}
    private void areaPicker(int x,int y,int width){var p=addRenderableWidget(new ConsoleButton(x,y,width,19,Component.literal(label.isEmpty()?tr("pick").getString():label),false,b->minecraft.gui.setScreen(new Picker(this))));p.describe(Component.literal(label.isEmpty()?tr("pick").getString():label));}
    private String defaultName(){if(!parent.equals(ProtectionData.NONE))return Component.translatable("protection.umbral.default_room",data.regions().stream().filter(r->r.parent().isPresent()).count()+1).getString();long n=data.regions().stream().filter(r->r.parent().isEmpty()).count();return Component.translatable("protection.umbral."+(n==0?"default_name":"default_number"),n+1).getString();}
    private void beginQuick(){
        if(dirty&&!draft.empty()){openReview();return;}
        if(label.isBlank())label=defaultName();cut=false;contour=false;send(Action.BEGIN_AREA);
    }
    private void openReview(){if(draft.empty()||pending)return;try{draft.subtract(saved());saved().subtract(draft);}catch(IllegalArgumentException complex){notice="complex";return;}if(label.isBlank())label=defaultName();minecraft.gui.setScreen(new Review(this));}
    private void discard(){
        var r=region();if(r==null&&!parent.equals(ProtectionData.NONE))r=data.regions().stream().filter(v->v.id().equals(parent)).findFirst().orElse(null);
        if(r==null)r=data.regions().stream().filter(v->v.parent().isEmpty()).findFirst().orElse(null);
        if(r!=null)load(r);else{selected=ProtectionData.NONE;parent=ProtectionData.NONE;draft=new ProtectionShape();label="";dirty=false;vertices.clear();}
        send(Action.CANCEL);init();
    }
    private int needed(){return ProtectionEconomy.additionalMatrices(data.matrices(),proposed());}
    private Component costSummary(){int n=needed();return n<0?tr("result.matrix_limit"):n==0?tr("cost_ready"):Component.translatable("protection.umbral."+(n==1?"cost_matrix":"cost_matrices"),n);}
    private void height(int low,int high){
        if(!advanced&&dirty&&!draft.empty()&&!cut){draft=ProtectionEdits.reheightAdded(saved(),draft,low,high);notice="preview";}
        floor=low;ceiling=high;
    }
    private static void lcdInput(EditBox box){box.setBordered(false);box.setTextColor(INK);box.setTextColorUneditable(MUTED);box.setX(box.getX()+5);box.setY(box.getY()+5);box.setWidth(box.getWidth()-10);box.setHeight(14);}
    private static Component tr(String key){return Component.translatable("protection.umbral."+key);}
    private List<Profile> profiles(){var result=new LinkedHashMap<UUID,Profile>();for(var p:data.players())result.put(p.id(),p);var r=region();if(r!=null)for(var m:r.members())result.putIfAbsent(m.player(),new Profile(m.player(),m.name()));return List.copyOf(result.values());}
    private int effectivePermissions(){var list=profiles();var r=region();if(list.isEmpty()||r==null)return ProtectionData.USE;var id=list.get(Math.floorMod(profileIndex,list.size())).id();for(var m:r.members())if(m.player().equals(id))return m.permissions();if(r.parent().isPresent()){var root=data.regions().stream().filter(v->v.id().equals(r.parent().get())).findFirst().orElse(null);if(root!=null)for(var m:root.members())if(m.player().equals(id))return m.permissions();}return 0;}
    private long proposed(){var r=region();return data.used()-(r!=null&&r.parent().isEmpty()?r.shape().cost():0)+(parent.equals(ProtectionData.NONE)?draft.cost():0);}
    private void updateButtons(){if(install!=null)install.active=fresh()&&!pending&&data.matrices()<ProtectionEconomy.MAX_MATRICES;for(var b:mutations)b.active=fresh()&&!pending;
        if(welcome())return;
        if(apply!=null)apply.active=fresh()&&!pending&&dirty&&!draft.empty();
        if(tab==2){boolean allowed=region()!=null&&!profiles().isEmpty();for(var b:mutations)if(b!=apply&&b!=mode)b.active&=allowed;}
        if(tab==3){boolean allowed=region()!=null&&region().parent().isEmpty();for(var b:mutations)if(b!=apply&&b!=mode)b.active&=allowed;}
    }
    private void send(Action action){sendValue(action,permissions);}
    private void sendValue(Action action,int value){
        if(pending||!valid())return;nonce=ThreadLocalRandom.current().nextLong();pending=true;pendingAction=action;sentAt=ticks;
        if(action==Action.BEGIN_BOX||action==Action.BEGIN_CONTOUR||action==Action.BEGIN_AREA)value=cut?1:0;
        var profiles=profiles();UUID member=profiles.isEmpty()?ProtectionData.NONE:profiles.get(Math.floorMod(profileIndex,profiles.size())).id();
        ProtectionShape payload=draft;if(action==Action.DETECT_ROOM)payload=ProtectionShape.rectangle((int)Math.floor(map.centerX),floor+1,(int)Math.floor(map.centerZ),(int)Math.floor(map.centerX),floor+1,(int)Math.floor(map.centerZ));
        ClientPlayNetworking.send(new Request(data.access(),nonce,action,selected,revision,parent,payload,label,member,value,floor,ceiling));if(action!=Action.QUERY)notice="updating";updateButtons();
    }
    public void accept(Snapshot update){
        if(!data.access().equals(update.access())||!pending||nonce!=update.nonce())return;pending=false;if(!update.valid()){minecraft.gui.setScreen(null);return;}data=update;lastUpdate=ticks;
        if(!update.result().equals("ready"))notice=update.result();
        if(notice.startsWith("marking")){onClose();return;}
        if(update.result().equals("proposal")||update.result().equals("detected")){loadSelection(update.selection());tab=0;}
        else if(update.result().equals("applied")){var r=data.regions().stream().filter(v->v.id().equals(selected)).findFirst().orElse(data.regions().isEmpty()?null:data.regions().getLast());if(r!=null)load(r);}
        else if(update.result().equals("permissions")||update.result().equals("flags")){var r=region();if(r!=null&&!dirty)load(r);else if(r!=null)revision=r.revision();}
        else if(update.result().equals("deleted")){selected=ProtectionData.NONE;draft=new ProtectionShape();label="";dirty=false;tab=0;}
        init();if(update.result().equals("applied")&&minecraft.gui.screen() instanceof Review)minecraft.gui.setScreen(this);
    }
    @Override public void tick(){if(!valid()){minecraft.gui.setScreen(null);return;}ticks++;map.tick();if(pending&&ticks-sentAt>60){pending=false;notice="stale";lastUpdate=ticks-61;}if(ticks%20==0&&!pending)send(Action.QUERY);updateButtons();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){
        if(!welcome()&&tab<=1&&event.x()>=mx&&event.x()<mx+mw&&event.y()>=my&&event.y()<my+mh){
            if(tab==1||event.button()==1){dragging=true;start=null;return true;}
            var point=new ProtectionShape.Point(map.worldX(event.x(),mx,mw,mh),map.worldZ(event.y(),my,mw,mh));
            if(contour){if(vertices.size()<32)vertices.add(point);init();}else{start=point;dragging=true;}return true;
        }return super.mouseClicked(event,doubleClick);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){if(dragging){if(tab==1){map.yaw+=dx*.012;map.pitch=Math.clamp(map.pitch+dy*.008,.15,1.4);}else if(start==null){map.centerX-=dx*map.cells/Math.min(mw,mh);map.centerZ-=dy*map.cells/Math.min(mw,mh);}return true;}return super.mouseDragged(event,dx,dy);}
    @Override public boolean mouseReleased(MouseButtonEvent event){if(dragging){dragging=false;if(tab==0&&start!=null){var end=new ProtectionShape.Point(map.worldX(event.x(),mx,mw,mh),map.worldZ(event.y(),my,mw,mh));try{var selection=ProtectionShape.rectangle(start.x(),floor,start.z(),end.x(),ceiling-1,end.z());draft=cut?draft.subtract(selection):draft.add(selection);dirty=true;notice="preview";}catch(IllegalArgumentException e){notice="complex";}start=null;init();}return true;}return super.mouseReleased(event);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){if(!welcome()&&tab<=1&&x>=mx&&x<mx+mw&&y>=my&&y<my+mh){int step=map.cells>=64?16:4;map.cells=Math.clamp(map.cells+(vertical>0?-step:step),8,320);return true;}return super.mouseScrolled(x,y,horizontal,vertical);}
    private void finishContour(){try{var shape=ProtectionShape.polygon(vertices,floor,ceiling);draft=cut?draft.subtract(shape):draft.add(shape);dirty=true;notice="preview";vertices.clear();}catch(IllegalArgumentException e){notice="complex";}init();}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){
        backdrop(g,width,height);frame(g,font,left,top,w,h,title,advanced?Component.translatable("protection.umbral.meter",data.used(),data.capacity()).getString():tr(dirty?"draft_badge":"terrain_badge").getString());
        if(welcome()){
            text(g,font,tr("welcome_heading"),left+14,top+60,w-110,INK);
            card(g,mx,my,mw,mh);
            var lines=font.split(tr("welcome_text"),mw-20);for(int n=0;n<Math.min(Math.max(1,(mh-12)/11),lines.size());n++)g.text(font,lines.get(n),mx+10,my+7+n*11,INK,false);
        }else if(tab<=1){
            map.render(g,mx,my,mw,mh,tab==1,floor,ceiling,data.regions(),selected,draft,cut,vertices);
            text(g,font,Component.translatable("protection.umbral.layer",floor,ceiling-1),mx+5,my+5,mw-10,INK);
            if(mw>420&&mouseX>=mx&&mouseX<mx+mw&&mouseY>=my&&mouseY<my+mh&&tab==0){var cursor=Component.literal("X="+map.worldX(mouseX,mx,mw,mh)+" · Z="+map.worldZ(mouseY,my,mw,mh));text(g,font,cursor,mx+mw-124,my+5,119,INK);}
            if(mh>70)text(g,font,tr(advanced?(tab==1?"model_hint":"plan_hint"):"legend"),mx+5,my+mh-14,mw-10,INK);
            if(map.cells>32)text(g,font,tr("local_detail"),mx+5,my+19,mw-10,MUTED);else if(tab==1&&map.missing()>0)text(g,font,tr("missing_terrain"),mx+5,my+19,mw-10,MUTED);
        }else if(tab==2){
            var r=region();if(mh>75&&r!=null){text(g,font,tr(r.parent().isPresent()?"inherit":"owner_only"),mx+7,my+61,mw-14,MUTED);int row=0;for(var member:r.members()){if(my+77+row*12>my+mh-12)break;text(g,font,Component.literal(member.name()+" · "+permissionText(member.permissions())),mx+7,my+77+row++*12,mw-14,INK);}}
        }else if(tab==4){int row=0;for(var incident:data.incidents().stream().skip((long)journalPage*Math.max(1,mh/25)).toList()){if(row*25+20>mh)break;int y=my+row++*25;card(g,mx,y,mw,23);Component line=Component.literal(incident.area()+" · ").append(Component.translatable("protection.umbral.incident."+incident.action()));text(g,font,line,mx+6,y+3,mw-12,INK);text(g,font,Component.literal(incident.actor()+" · "+incident.pos().toShortString()),mx+6,y+13,mw-12,MUTED);}if(row==0)text(g,font,tr("no_incidents"),mx+8,my+10,mw-16,MUTED);}
        Component status=welcome()?tr("network_separate"):advanced?Component.translatable("protection.umbral.budget",proposed(),data.capacity()).append(" · ").append(Component.translatable("protection.umbral.result."+notice)):costSummary().copy().append(" · ").append(Component.translatable("protection.umbral.result."+notice));
        text(g,font,status,left+13,top+h-16,w-26,proposed()>data.capacity()?ALERT:MUTED);
        if(mouseY>=top+h-20)g.setTooltipForNextFrame(font,status,mouseX,mouseY);
        super.extractRenderState(g,mouseX,mouseY,delta);
    }
    private static String permissionText(int bits){var list=new ArrayList<String>();for(int i=0;i<3;i++)if((bits&(1<<i))!=0)list.add(tr(i==0?"build":i==1?"use":"inventory").getString());return String.join(" / ",list);}
    private static final class Review extends Screen {
        final ProtectionScreen parent;
        private EditBox name;
        private ConsoleButton confirm,acknowledge;
        private boolean thin,ack,details,removes;
        private int x,y,w,h;
        Review(ProtectionScreen p){super(tr("review_title"));parent=p;}
        private void remember(){if(name!=null)parent.label=ProtectionData.clean(name.getValue(),48);}
        @Override protected void init(){
            clearWidgets();w=Math.min(480,width-20);h=Math.min(270,height-20);x=(width-w)/2;y=(height-h)/2;
            name=addRenderableWidget(new EditBox(font,x+16,y+57,w-32,20,tr("name")));name.setMaxLength(48);name.setValue(parent.label);lcdInput(name);
            try{thin=ProtectionEdits.thinAddition(parent.saved(),parent.draft);removes=!parent.saved().subtract(parent.draft).empty();}catch(IllegalArgumentException complex){thin=true;removes=parent.cut;}
            if(thin)acknowledge=addRenderableWidget(new ConsoleButton(x+16,y+h-83,w-32,20,tr(ack?"thin_accepted":"thin_accept"),ack,b->{remember();ack=!ack;init();}));
            else addRenderableWidget(new ConsoleButton(x+16,y+h-83,w-32,20,tr(details?"details_hide":"details"),false,b->{remember();details=!details;init();}));
            int bw=(w-40)/2;
            addRenderableWidget(new ConsoleButton(x+16,y+h-57,bw,20,tr("height"),false,b->{remember();minecraft.gui.setScreen(new Form(parent,true,this));}));
            addRenderableWidget(new ConsoleButton(x+24+bw,y+h-57,bw,20,tr("recipe"),false,b->{remember();minecraft.gui.setScreen(new ManualScreen(this,dev.umbral.manual.ManualCatalog.Entry.MATRIX,ManualScreen.Tab.RECIPE));}));
            confirm=addRenderableWidget(new ConsoleButton(x+16,y+h-31,bw,20,tr("confirm"),true,b->{remember();parent.sendValue(Action.APPLY_WITH_MATRICES,parent.needed());}));
            addRenderableWidget(new ConsoleButton(x+24+bw,y+h-31,bw,20,tr("back_plan"),false,b->onClose()));
            addRenderableWidget(ManualScreen.help(this,x+w-30,y+31,dev.umbral.manual.ManualCatalog.Entry.MATRIX));refresh();
        }
        private void refresh(){
            boolean committing=parent.pending&&parent.pendingAction!=Action.QUERY;name.setEditable(!committing);
            for(var child:children())if(child instanceof Button button&&button!=confirm)button.active=!committing;
            int needed=parent.needed();confirm.active=parent.fresh()&&!parent.pending&&parent.dirty&&!parent.draft.empty()&&needed>=0&&ProtectionService.availableMatrices(minecraft.player)>=needed&&(!thin||ack)&&!name.getValue().isBlank();
            confirm.describe(needed>0?Component.translatable("protection.umbral."+(needed==1?"consume_confirm_one":"consume_confirm"),needed):tr("no_new_matrices"));
        }
        @Override public void tick(){parent.tick();if(minecraft.gui.screen()==this)refresh();}
        @Override public void onClose(){if(parent.pending&&parent.pendingAction!=Action.QUERY)return;remember();minecraft.gui.setScreen(parent);}
        @Override public boolean isPauseScreen(){return false;}
        @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
            backdrop(g,width,height);frame(g,font,x,y,w,h,title,tr("draft_badge").getString());
            card(g,x+16,y+57,w-32,20);
            var added=parent.draft;try{var only=parent.draft.subtract(parent.saved());if(!only.empty())added=only;}catch(IllegalArgumentException ignored){}var b=added.bounds();text(g,font,Component.translatable("protection.umbral.review_height",b.y0(),b.y1()-1),x+17,y+83,w-34,INK);
            text(g,font,parent.costSummary(),x+17,y+99,w-34,parent.needed()>0?ALERT:INK);
            text(g,font,details?Component.translatable("protection.umbral.budget",parent.proposed(),parent.data.capacity()):Component.translatable("protection.umbral.matrix_inventory",ProtectionService.availableMatrices(minecraft.player),Math.max(0,parent.needed())),x+17,y+112,w-34,MUTED);
            Component scope=thin?tr("thin_warning"):removes?tr("review_remove"):tr(parent.selected.equals(ProtectionData.NONE)?(parent.parent.equals(ProtectionData.NONE)?"review_private":"review_room"):"review_preserve");
            var lines=font.split(scope,w-34);int max=Math.max(1,(h-213)/11);for(int n=0;n<Math.min(Math.max(1,max),lines.size());n++)g.text(font,lines.get(n),x+17,y+126+n*11,thin?ALERT:MUTED,false);
            if(my>=y+80&&my<y+h-85){var info=parent.costSummary().copy().append(". ").append(scope);if(details)info.append(". ").append(Component.translatable("protection.umbral.budget",parent.proposed(),parent.data.capacity()));g.setTooltipForNextFrame(font,info,mx,my);}
            if(!parent.notice.equals("ready")&&!parent.notice.equals("preview"))text(g,font,Component.translatable("protection.umbral.result."+parent.notice),x+17,y+h-11,w-34,ALERT);
            super.extractRenderState(g,mx,my,delta);
        }
    }
    private static final class Assistant extends Screen {
        final ProtectionScreen parent;int x,y,w,h,hintIndex;
        final Map<ConsoleButton,Boolean> networkButtons=new HashMap<>();
        Assistant(ProtectionScreen p){super(tr("assistant"));parent=p;}
        @Override protected void init(){clearWidgets();networkButtons.clear();w=Math.min(430,width-20);h=Math.min(250,height-20);x=(width-w)/2;y=(height-h)/2;int bw=(w-40)/2;
            if(!parent.data.hints().isEmpty()){
                var hints=parent.data.hints();var hint=hints.get(Math.floorMod(hintIndex,hints.size()));
                var review=addRenderableWidget(new ConsoleButton(x+16,y+103,w-62,20,Component.translatable("protection.umbral."+(hint.blocks()==1?"hint_area_one":"hint_area"),hint.label(),hint.blocks()),true,b->{var r=parent.data.regions().stream().filter(v->v.id().equals(hint.area())).findFirst().orElse(null);if(r!=null&&!parent.dirty){parent.load(r);minecraft.gui.setScreen(parent);parent.send(Action.SUGGEST);}}));networkButtons.put(review,!parent.dirty);
                var next=addRenderableWidget(new ConsoleButton(x+w-40,y+103,24,20,Component.literal(">"),false,b->{hintIndex++;init();}));next.active=hints.size()>1;
            }
            addRenderableWidget(new ConsoleButton(x+16,y+h-82,bw,20,tr("center"),false,b->{parent.recenter();onClose();}));
            var suggest=addRenderableWidget(new ConsoleButton(x+24+bw,y+h-82,bw,20,tr("suggest"),true,b->{minecraft.gui.setScreen(parent);parent.send(Action.SUGGEST);}));suggest.active=parent.region()!=null&&!parent.dirty;networkButtons.put(suggest,suggest.active);
            var room=addRenderableWidget(new ConsoleButton(x+16,y+h-56,bw,20,tr(parent.advanced?"detect_room":"ignore_hint"),false,b->{if(!parent.advanced&&!parent.data.hints().isEmpty()){var id=parent.data.hints().get(Math.floorMod(hintIndex,parent.data.hints().size())).area();var target=parent.data.regions().stream().filter(v->v.id().equals(id)).findFirst().orElse(null);if(target!=null)parent.load(target);}minecraft.gui.setScreen(parent);parent.send(parent.advanced?Action.DETECT_ROOM:Action.IGNORE_SUGGESTION);}));room.active=parent.region()!=null&&!parent.dirty&&(parent.advanced||!parent.data.hints().isEmpty());networkButtons.put(room,room.active);
            addRenderableWidget(new ConsoleButton(x+24+bw,y+h-56,bw,20,tr("name"),false,b->minecraft.gui.setScreen(new Form(parent,false))));
            addRenderableWidget(new ConsoleButton(x+16,y+h-30,w-32,20,Component.translatable("gui.done"),false,b->onClose()));
        }
        @Override public void onClose(){minecraft.gui.setScreen(parent);}
        @Override public void tick(){parent.tick();networkButtons.forEach((button,allowed)->button.active=allowed&&parent.fresh()&&!parent.pending);}
        @Override public boolean isPauseScreen(){return false;}
        @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){backdrop(g,width,height);frame(g,font,x,y,w,h,title,"UMBRAL / NEXO");
            var message=parent.data.hints().isEmpty()?parent.costSummary().copy().append(". ").append(tr("assistant_help")):tr("assistant_works");
            var lines=font.split(message,w-36);for(int n=0;n<Math.min(4,lines.size());n++)g.text(font,lines.get(n),x+18,y+59+n*11,INK,false);
            super.extractRenderState(g,mx,my,delta);
        }
    }
    private static final class Form extends Screen {
        final ProtectionScreen parent;final boolean heights,commit;final Screen returnTo;EditBox first,second;int x,y,w,h;
        Form(ProtectionScreen p,boolean heights){this(p,heights,false,p);}
        Form(ProtectionScreen p,boolean heights,Screen back){this(p,heights,false,back);}
        Form(ProtectionScreen p,boolean heights,boolean commit){this(p,heights,commit,p);}
        Form(ProtectionScreen p,boolean heights,boolean commit,Screen back){super(tr(heights?(!p.advanced&&p.dirty&&!p.draft.empty()&&!p.cut?"height_addition":"height_next"):"name"));parent=p;this.heights=heights;this.commit=commit;this.returnTo=back;}
        @Override protected void init(){w=Math.min(330,width-20);h=180;x=(width-w)/2;y=(height-h)/2;first=addRenderableWidget(new EditBox(font,x+16,y+70,w-32,20,title));first.setMaxLength(heights?6:48);first.setValue(heights?Integer.toString(parent.floor):parent.label);lcdInput(first);
            if(heights){second=addRenderableWidget(new EditBox(font,x+16,y+105,w-32,20,tr("ceiling")));second.setMaxLength(6);second.setValue(Integer.toString(parent.ceiling-1));lcdInput(second);}
            addRenderableWidget(new ConsoleButton(x+16,y+h-34,(w-40)/2,20,tr("apply"),true,b->{if(heights){try{int a=Integer.parseInt(first.getValue()),c=Integer.parseInt(second.getValue());if(a>c||a<minecraft.level.getMinY()||c>minecraft.level.getMaxY())return;try{parent.height(a,c+1);}catch(IllegalArgumentException invalid){return;}}catch(NumberFormatException e){return;}}else{parent.label=ProtectionData.clean(first.getValue(),48);if(parent.label.isEmpty())return;parent.dirty=true;}minecraft.gui.setScreen(returnTo);if(commit)parent.openReview();}));
            addRenderableWidget(new ConsoleButton(x+24+(w-40)/2,y+h-34,(w-40)/2,20,Component.translatable("gui.cancel"),false,b->onClose()));setInitialFocus(first);}
        @Override public void onClose(){minecraft.gui.setScreen(returnTo);}
        @Override public void tick(){parent.tick();}
        @Override public boolean isPauseScreen(){return false;}
        @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){backdrop(g,width,height);frame(g,font,x,y,w,h,title,"UMBRAL / NEXO");card(g,x+16,y+70,w-32,20);if(heights){card(g,x+16,y+105,w-32,20);text(g,font,tr("floor"),x+16,y+58,w-32,INK);text(g,font,tr("ceiling"),x+16,y+93,w-32,INK);}super.extractRenderState(g,mx,my,delta);}
    }
    private static final class Picker extends Screen {
        final ProtectionScreen parent;int page,x,y,w,h;
        Picker(ProtectionScreen p){super(tr("pick"));parent=p;}
        @Override protected void init(){clearWidgets();w=Math.min(400,width-20);h=Math.min(300,height-20);x=(width-w)/2;y=(height-h)/2;int size=Math.max(1,(h-139)/24);int pages=Math.max(1,(parent.data.regions().size()+size-1)/size);page=Math.clamp(page,0,pages-1);
            addRenderableWidget(new ConsoleButton(x+16,y+55,w-32,20,tr("another_area"),true,b->{parent.create(false);minecraft.gui.setScreen(parent);}));
            for(int n=page*size;n<Math.min((page+1)*size,parent.data.regions().size());n++){var r=parent.data.regions().get(n);addRenderableWidget(new ConsoleButton(x+16,y+80+(n-page*size)*24,w-32,21,Component.literal((r.parent().isPresent()?"  └ ":"")+r.label()),false,b->{parent.load(r);minecraft.gui.setScreen(parent);}));}
            var prev=addRenderableWidget(new ConsoleButton(x+16,y+h-48,28,18,Component.literal("<"),false,b->{page--;init();}));prev.active=page>0;var next=addRenderableWidget(new ConsoleButton(x+48,y+h-48,28,18,Component.literal(">"),false,b->{page++;init();}));next.active=page<pages-1;
            addRenderableWidget(new ConsoleButton(x+w-96,y+h-48,80,18,Component.translatable("gui.done"),false,b->onClose()));
            var delete=addRenderableWidget(new ConsoleButton(x+16,y+h-25,w-32,18,tr("delete"),false,b->{var r=parent.region();if(r!=null)minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(ok->{minecraft.gui.setScreen(parent);if(ok)parent.send(Action.DELETE);},tr("delete"),Component.translatable("protection.umbral.delete_hint",r.label())));}));delete.active=parent.region()!=null;
        }
        @Override public void onClose(){minecraft.gui.setScreen(parent);}
        @Override public void tick(){parent.tick();}
        @Override public boolean isPauseScreen(){return false;}
        @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){backdrop(g,width,height);frame(g,font,x,y,w,h,title,"UMBRAL / NEXO");super.extractRenderState(g,mx,my,delta);}
    }
}
