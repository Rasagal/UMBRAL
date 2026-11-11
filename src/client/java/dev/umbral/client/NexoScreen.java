// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.block.*;
import dev.umbral.installation.DeviceLabels;
import dev.umbral.installation.NexoProtocol.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static dev.umbral.client.ConsoleStyle.*;

/** Local installation assistant: current facts, actionable diagnoses and visible device status. */
public final class NexoScreen extends Screen {
    private Snapshot data;
    private UUID selected;
    private int page,rowsPerPage,left,top,w,h,listWidth,ticks,lastUpdate,sentAt,lastQuery=-20,queryAt=-1;
    private boolean pending,history,attentionOnly;
    private long nonce;
    private String notice="ready",query="";
    private EditBox search;
    private ConsoleButton setupControl,settingsControl;
    private final Map<ConsoleButton,Operation> actions=new LinkedHashMap<>();
    public NexoScreen(Snapshot initial){
        super(Component.translatable("nexo.umbral.title"));data=initial;
        selected=initial.rows().stream().filter(r->r.position().equals(initial.root())).map(Row::identity).findFirst().orElse(null);
        attentionOnly=initial.rows().stream().anyMatch(NexoScreen::needsAttention);
    }
    private static MutableComponent tr(String key,Object...args){return Component.translatable("nexo.umbral."+key,args);}
    public static boolean needsAttention(Row row){return switch(row.reading()){
        case OFFLINE,UNLOADED,REPLACED,LOW_AMMO,EMPTY,ALARM,PAUSED,NO_UNIT,BLOCKED,STOCK_MISSING,INSPECTION_ALERT,INCOMPLETE->true;
        default->false;
    };}
    private static String alias(DeviceKind kind){return switch(kind){
        case CAMERA->"camara camera vigia";case FILTER->"filtro escaner scanner inspeccion";case TURRET->"torreta baluarte ballista";case CENTELLA->"torreta centella pepitas nuggets";
        case DOCK->"mota estacion dock dron drone";case CORE->"nucleo core";case RELAY->"nodo relay repetidor";case READER->"lector reader tarjeta";case DOOR->"puerta door";default->kind.id;
    };}
    private String label(Row row){return row.label().isEmpty()?Component.translatable("ui.umbral.kind."+row.kind().id).getString():row.label();}
    private List<Row> visibleRows(){
        String key=DeviceLabels.searchKey(query);String[] words=key.isEmpty()?new String[0]:key.split(" ");
        return data.rows().stream().filter(r->!attentionOnly||needsAttention(r)).filter(r->{
            String haystack=DeviceLabels.searchKey(label(r)+" "+Component.translatable("block.umbral."+r.kind().id).getString()+" "+alias(r.kind())+" "+r.position().toShortString());
            return Arrays.stream(words).allMatch(haystack::contains);
        }).sorted(Comparator.comparing((Row r)->!needsAttention(r)).thenComparing(r->DeviceLabels.searchKey(label(r))).thenComparingLong(r->r.position().asLong())).toList();
    }
    private Row selected(){var rows=visibleRows();return rows.stream().filter(r->r.identity().equals(selected)).findFirst().orElse(rows.isEmpty()?null:rows.getFirst());}
    private Row row(UUID id){return data.rows().stream().filter(r->r.identity().equals(id)).findFirst().orElse(null);}
    private boolean fresh(){return data.valid()&&ticks-lastUpdate<60;}
    private boolean local(Row row){return row.position().equals(data.root());}
    private boolean online(Row row){return row!=null&&row.reading()!=Reading.OFFLINE&&row.reading()!=Reading.UNLOADED&&row.reading()!=Reading.REPLACED;}
    private boolean editable(Row row){return row!=null&&row.reading()!=Reading.UNLOADED&&row.reading()!=Reading.REPLACED&&(online(row)||local(row));}
    private boolean central(){return data.rows().stream().anyMatch(r->r.position().equals(data.root())&&(r.kind()==DeviceKind.CORE||r.kind()==DeviceKind.NEXO||r.kind()==DeviceKind.TERMINAL));}
    private boolean controlOnline(){return data.rows().stream().filter(r->r.position().equals(data.root())).anyMatch(this::online);}
    @Override protected void init(){
        EditBox previousSearch=search;boolean focused=search!=null&&getFocused()==search;
        clearWidgets();actions.clear();search=null;setupControl=null;settingsControl=null;
        w=Math.min(600,width-16);h=Math.min(390,height-16);left=(width-w)/2;top=(height-h)/2;listWidth=Math.max(118,w*44/100);
        rowsPerPage=Math.clamp((h-210)/28,1,6);
        var visible=visibleRows();int count=history?data.events().size():visible.size();int pages=Math.max(1,(count+rowsPerPage-1)/rowsPerPage);page=Math.clamp(page,0,pages-1);
        int first=page*rowsPerPage,start=top+80;
        if(!history){
            // Keeping the same input preserves cursor, selection anchor and horizontal
            // scroll together. Restoring only the cursor can select the suffix by mistake.
            search=previousSearch!=null&&previousSearch.getValue().equals(query)?previousSearch:null;
            if(search==null){
                search=new EditBox(font,left+16,top+59,listWidth-16,12,tr("search"));
                search.setBordered(false);search.setTextColor(INK);search.setTextShadow(false);search.setMaxLength(80);search.setValue(query);
                search.setResponder(v->{query=v;page=0;init();});
            }
            search.setX(left+16);search.setY(top+59);search.setWidth(listWidth-16);search.setHint(tr("search"));addRenderableWidget(search);
            int x=left+listWidth+15,bw=w-listWidth-27,half=(bw-4)/2;
            addRenderableWidget(new ConsoleButton(x,top+55,half,18,tr("list.all"),!attentionOnly,b->{attentionOnly=false;page=0;init();}));
            long issues=data.rows().stream().filter(NexoScreen::needsAttention).count();
            addRenderableWidget(new ConsoleButton(x+half+4,top+55,bw-half-4,18,tr(w<400?"list.attention_short":"list.attention",issues),attentionOnly,b->{attentionOnly=true;page=0;init();})).describe(tr("list.attention_hint",issues));
            for(int n=first;n<Math.min(first+rowsPerPage,visible.size());n++){
                Row value=visible.get(n);int y=start+(n-first)*28;
                var b=addRenderableWidget(new ConsoleButton(left+12,y,listWidth-8,18,Component.literal(label(value)),value.identity().equals(selected),button->{selected=value.identity();init();}));
                b.describe(tr("reading."+value.reading().name().toLowerCase(Locale.ROOT)).append(" · ").append(Component.literal(value.position().toShortString())));
            }
        }
        int navY=start+rowsPerPage*28;
        var previous=addRenderableWidget(new ConsoleButton(left+12,navY,24,16,Component.literal("<"),false,b->{page--;init();}));previous.active=page>0;
        var next=addRenderableWidget(new ConsoleButton(left+(history?w:listWidth)-36,navY,24,16,Component.literal(">"),false,b->{page++;init();}));next.active=page<pages-1;
        int footer=(w-42)/4,y=top+h-29;
        addRenderableWidget(new ConsoleButton(left+12,y,footer,20,tr(history?"devices":"history"),false,b->{history=!history;page=0;init();}));
        addRenderableWidget(new ConsoleButton(left+18+footer,y,footer,20,Component.translatable("protection.umbral.areas"),false,b->ClientPlayNetworking.send(new dev.umbral.protection.ProtectionProtocol.Open(false,data.root()))));
        var setup=addRenderableWidget(new ConsoleButton(left+24+footer*2,y,footer,20,tr(w<400?"setup_short":"setup"),false,b->ClientPlayNetworking.send(new dev.umbral.access.AccessSetupProtocol.Open(data.root()))));
        setupControl=setup;setup.active=fresh()&&!pending&&central()&&controlOnline();setup.describe(tr(central()?"setup_hint":"setup_central"));
        addRenderableWidget(new ConsoleButton(left+30+footer*3,y,footer,20,Component.translatable("gui.done"),false,b->onClose()));
        Row value=selected();int bw=(w-36)/3;int actionsY=top+h-57;
        if(value!=null&&!history){
            selected=value.identity();
            boolean defense=value.kind().turret()||value.kind()==DeviceKind.SENSOR||value.kind()==DeviceKind.PULSE_MINE||value.kind()==DeviceKind.FILTER||value.kind()==DeviceKind.FENCE;
            if(defense)action(left+12,actionsY,bw,value.armed()?"disarm":"arm",value.armed()?Operation.DISARM:Operation.ARM);
            if(value.kind()==DeviceKind.FILTER){action(left+18+bw,actionsY,bw,"configure_filter",Operation.CONFIGURE_FILTER);action(left+24+bw*2,actionsY,bw,"configure_airlock",Operation.CONFIGURE_AIRLOCK);}
            if(value.kind()==DeviceKind.FENCE){action(left+18+bw,actionsY,bw,"fence_target",Operation.RANGE);action(left+24+bw*2,actionsY,bw,"fence_control",Operation.FENCE_CONTROL);}
            if(value.kind().turret()||value.kind()==DeviceKind.SENSOR)action(left+18+bw,actionsY,bw,"range",Operation.RANGE);
            if(value.kind().turret())action(left+24+bw*2,actionsY,bw,"replenish",Operation.REPLENISH);
            if(value.kind()==DeviceKind.PULSE_MINE&&local(value)){action(left+18+bw,actionsY,bw,"load",Operation.LOAD);action(left+24+bw*2,actionsY,bw,"unload",Operation.UNLOAD);}
            if(value.kind()==DeviceKind.DOCK){action(left+12,actionsY,bw,"cancel",Operation.CANCEL);if(local(value))action(left+18+bw,actionsY,bw,"recover",Operation.RECOVER);}
            int x=left+listWidth+15,detailW=w-listWidth-27;
            var settings=addRenderableWidget(new ConsoleButton(x+detailW-23,start+3,19,18,Component.literal("…"),false,b->minecraft.gui.setScreen(new DeviceScreen(value.identity()))));
            settingsControl=settings;settings.active=fresh()&&!pending&&editable(value);settings.describe(tr("rename.open"));
        }
        addRenderableWidget(ManualScreen.help(this,left+w-30,top+31,dev.umbral.manual.ManualCatalog.Entry.NEXO));
        if(focused&&search!=null)setFocused(search);
        updateActions();
    }
    private void action(int x,int y,int width,String key,Operation op){var b=addRenderableWidget(new ConsoleButton(x,y,width,20,tr("action."+key),key.equals("replenish"),button->send(op)));actions.put(b,op);}
    private String disabledReason(Row row,Operation operation){
        if(!fresh())return "blocked.stale";if(pending)return "blocked.pending";if(row==null)return "blocked.selection";
        if(operation==Operation.REPLENISH){if(!online(row))return "blocked.offline";if(row.refill()<=0)return "refill."+refillReason(row.transport());}
        if(!online(row)&&!local(row))return "blocked.offline";
        return switch(operation){case ARM->row.armed()?"blocked.armed":row.reading()==Reading.INCOMPLETE?"blocked.incomplete":row.kind()==DeviceKind.FILTER&&row.amount()==0?"blocked.no_rules":row.kind()!=DeviceKind.SENSOR&&row.amount()==0?"blocked.empty":"blocked.offline";case DISARM->"blocked.disarmed";case LOAD->"blocked.full";case UNLOAD->"blocked.empty";case CANCEL->"blocked.no_job";case RECOVER->"blocked.recover";default->"blocked.offline";};
    }
    private static String refillReason(String state){return Set.of("no_unit","paused","busy","offline","stock_missing","no_stock","full","blocked","ammo_mismatch").contains(state)?state:"no_stock";}
    private void updateActions(){
        Row value=selected();if(setupControl!=null){setupControl.active=fresh()&&!pending&&central()&&controlOnline();setupControl.describe(tr(!fresh()?"blocked.stale":pending?"blocked.pending":!central()?"setup_central":!controlOnline()?"blocked.offline":"setup_hint"));}if(settingsControl!=null){settingsControl.active=fresh()&&!pending&&editable(value);settingsControl.describe(tr(!fresh()?"blocked.stale":pending?"blocked.pending":!editable(value)?"blocked.offline":"rename.open"));}for(var e:actions.entrySet()){
            boolean allowed=value!=null&&switch(e.getValue()){
                case ARM->online(value)&&value.reading()!=Reading.INCOMPLETE&&!value.armed()&&(value.kind()==DeviceKind.SENSOR||value.amount()>0);
                case DISARM->value.armed()&&(online(value)||local(value));
                case CONFIGURE_FILTER,CONFIGURE_AIRLOCK,FENCE_CONTROL,RANGE->online(value)||local(value);
                case LOAD->local(value)&&value.amount()<16;case UNLOAD->local(value)&&value.amount()>0;
                case REPLENISH->online(value)&&value.refill()>0;
                case CANCEL->Set.of(Reading.COLLECTING,Reading.DELIVERING,Reading.RETURNING,Reading.BLOCKED,Reading.PAUSED).contains(value.reading());
                case RECOVER->local(value)&&(value.reading()==Reading.IDLE||value.reading()==Reading.STOCK_MISSING);
                default->true;
            };
            e.getKey().active=fresh()&&!pending&&allowed;
            e.getKey().describe(e.getKey().active?tr("hint."+switch(e.getValue()){case REPLENISH->"replenish";case CONFIGURE_FILTER->"configure_filter";case CONFIGURE_AIRLOCK->"configure_airlock";case FENCE_CONTROL->"fence_control";case RANGE->value!=null&&value.kind()==DeviceKind.FENCE?"fence_target":"range";default->e.getValue().name().toLowerCase(Locale.ROOT);}):tr(disabledReason(value,e.getValue())));
        }
    }
    public void configureFilter(){send(Operation.CONFIGURE_FILTER);}
    private void send(Operation operation){Row value=selected();if(value!=null)send(operation,value,"");}
    private void send(Operation operation,Row value,String text){
        if(operation!=Operation.QUERY&&(!fresh()||pending))return;
        if(operation==Operation.QUERY&&pending)return;
        pending=true;sentAt=ticks;if(operation==Operation.QUERY)lastQuery=ticks;nonce=ThreadLocalRandom.current().nextLong();
        ClientPlayNetworking.send(new Request(data.root(),data.identity(),data.link(),nonce,operation,value==null?data.root():value.position(),value==null?data.identity():value.identity(),text));
        if(operation!=Operation.QUERY)notice="updating";updateActions();
    }
    public void refreshAfterChild(){pending=false;lastUpdate=ticks-61;queryAt=Math.max(ticks,lastQuery+10);if(queryAt<=ticks){queryAt=-1;send(Operation.QUERY,null,"");}}
    public static Screen underlying(Screen screen){return screen instanceof NexoScreen.DeviceScreen child?child.parent():screen;}
    public void accept(Snapshot snapshot){
        if(!data.root().equals(snapshot.root())||!data.identity().equals(snapshot.identity())||!Objects.equals(data.link(),snapshot.link())||!pending||snapshot.nonce()!=nonce)return;
        pending=false;if(snapshot.result().equals("busy")){notice="busy";updateActions();return;}if(!snapshot.valid()){onClose();return;}
        data=snapshot;lastUpdate=ticks;if(!snapshot.result().equals("ready"))notice=snapshot.result();
        if(snapshot.result().equals("renamed")){query="";attentionOnly=false;history=false;var rows=visibleRows();for(int n=0;n<rows.size();n++)if(rows.get(n).identity().equals(selected)){page=n/Math.max(1,rowsPerPage);break;}}
        init();
    }
    @Override public void tick(){
        if(minecraft.player==null||minecraft.level==null||!minecraft.player.isAlive()||minecraft.player.isSpectator()||data.root().distToCenterSqr(minecraft.player.position())>64||!minecraft.level.isLoaded(data.root())||!(minecraft.level.getBlockEntity(data.root()) instanceof DeviceBlockEntity device)){onClose();return;}
        // Only animated devices publish their physical ID to clients. Core/terminal IDs
        // are private; their current identity is authenticated on every server request.
        if((device.kind()==DeviceKind.CAMERA||device.kind()==DeviceKind.DOOR||device.kind()==DeviceKind.FILTER||device.kind().turret())&&!device.identity().equals(data.identity())){onClose();return;}
        ticks++;if(pending&&ticks-sentAt>60){pending=false;lastUpdate=ticks-61;notice="stale";}
        if(!pending&&(queryAt>=0?ticks>=queryAt:ticks%20==0)){queryAt=-1;send(Operation.QUERY,null,"");}updateActions();
    }
    @Override public boolean isPauseScreen(){return false;}
    private static String readingKey(Row row){return "reading."+row.reading().name().toLowerCase(Locale.ROOT);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        Component feedback=Component.translatable(!fresh()?"screen.umbral.stale":"nexo.umbral.result."+notice);
        backdrop(g,width,height);frame(g,font,left,top,w,h,!notice.equals("ready")?feedback:title,"UMBRAL / NEXO");
        int start=top+80,navY=start+rowsPerPage*28;
        if(history){
            text(g,font,tr("history"),left+16,top+60,w-32,INK);
            for(int n=page*rowsPerPage;n<Math.min(data.events().size(),(page+1)*rowsPerPage);n++){
                var event=data.events().get(n);int y=start+(n%rowsPerPage)*28;card(g,left+12,y,w-24,25);
                String name=event.label().isEmpty()?"UMBRAL":event.label();Component line=Component.literal(name+" · ").append(tr("event."+event.code(),event.amount(),event.subject(),event.item().isEmpty()?Component.empty():FilterScreen.display(event.item())));
                text(g,font,line,left+20,y+4,w-112,INK);text(g,font,tr("ago",Math.max(0,(data.observedAt()-event.tick())/20)),left+w-84,y+4,64,MUTED);
                if(mx>=left+12&&mx<left+w-12&&my>=y&&my<y+25)g.setTooltipForNextFrame(font,line,mx,my);
            }
            if(data.events().isEmpty())text(g,font,tr("no_events"),left+18,start+7,w-36,MUTED);
            text(g,font,Component.literal((page+1)+" / "+Math.max(1,(data.events().size()+rowsPerPage-1)/rowsPerPage)),left+42,navY+4,w-100,MUTED);
            super.extractRenderState(g,mx,my,delta);return;
        }
        card(g,left+12,top+55,listWidth-8,18);
        var rows=visibleRows();for(int n=page*rowsPerPage;n<Math.min(rows.size(),(page+1)*rowsPerPage);n++){
            Row value=rows.get(n);text(g,font,tr(readingKey(value)),left+17,start+(n%rowsPerPage)*28+19,listWidth-18,needsAttention(value)?ALERT:MUTED);
        }
        if(rows.isEmpty())text(g,font,tr("list.empty"),left+16,start+8,listWidth-16,MUTED);
        text(g,font,Component.literal((page+1)+" / "+Math.max(1,(rows.size()+rowsPerPage-1)/rowsPerPage)),left+42,navY+4,listWidth-92,MUTED);
        Row value=selected();int x=left+listWidth+15,bw=w-listWidth-27,body=rowsPerPage*28+16;card(g,x,start,bw,body);
        if(value!=null){
            int scale=w<400?1:2,tx=x+(scale==1?29:47);device(g,x+7,start+7,value.kind().id,scale,online(value));
            text(g,font,Component.literal(label(value)),tx,start+7,bw-(tx-x)-28,INK);
            text(g,font,Component.translatable(fresh()?"nexo.umbral."+readingKey(value):"screen.umbral.stale"),tx,start+22,bw-(tx-x)-7,needsAttention(value)?ALERT:INK);
            if(body>=65){Component amount=value.kind()==DeviceKind.FENCE?Component.translatable("fence.umbral.segments",value.amount(),value.capacity()):value.kind()==DeviceKind.DOCK?tr("delivered",value.amount()):value.kind()==DeviceKind.FILTER?Component.translatable("filter.umbral.rules_count",value.amount()):value.capacity()>0?tr("stock",value.amount(),value.capacity()):Component.literal(value.position().toShortString());text(g,font,amount,x+8,start+49,bw-16,MUTED);}
            if(body>=86&&(value.kind()==DeviceKind.FENCE||value.kind()==DeviceKind.FILTER||value.kind()==DeviceKind.CENTELLA||value.range()>0)){
                Component detail=value.kind()==DeviceKind.FENCE?Component.translatable("fence.umbral.target."+value.detail()):value.kind()==DeviceKind.FILTER?Component.translatable("filter.umbral.mode."+value.detail()):value.kind()==DeviceKind.CENTELLA?tr("preferred",preference(value)):value.range()>0?Component.translatable("turret.umbral.range",value.range()):Component.literal(value.position().toShortString());text(g,font,detail,x+8,start+65,bw-16,MUTED);
            }
            if(value.kind().turret()&&body>=130){text(g,font,tr("plan",value.refill()),x+8,start+100,bw-16,INK);text(g,font,tr("transport."+value.transport()),x+8,start+114,bw-16,MUTED);if(body>=156)text(g,font,Component.literal(value.source()),x+8,start+130,bw-16,MUTED);}
            int ay=top+h-100;card(g,left+12,ay,w-24,35);Component diagnosis=diagnosis(value);
            var lines=font.split(diagnosis,w-44);for(int n=0;n<Math.min(3,lines.size());n++)g.text(font,lines.get(n),left+22,ay+5+n*10,INK,false);
            if(mx>=left+12&&mx<left+w-12&&my>=ay&&my<ay+35)g.setTooltipForNextFrame(font,diagnosis,mx,my);
        }
        if(mx>=left+50&&mx<left+w-30&&my>=top+30&&my<top+45)g.setTooltipForNextFrame(font,feedback,mx,my);
        super.extractRenderState(g,mx,my,delta);
    }
    private static Component preference(Row row){return Component.translatable("centella.umbral.preference."+(Set.of("automatic","copper","iron","gold").contains(row.preference())?row.preference():"automatic"));}
    private Component diagnosis(Row row){
        String name=label(row);if(!fresh())return tr("say.stale");
        if(row.reading()==Reading.REPLACED)return tr("diagnosis.replaced",name);
        if(row.reading()==Reading.UNLOADED)return tr("diagnosis.unloaded",name);
        if(row.reading()==Reading.OFFLINE){String reason=Set.of("unbound","core_missing","unavailable","limit_reached","no_signal").contains(row.connection())?row.connection():"no_signal";return tr("diagnosis."+reason,name);}
        if(row.kind()==DeviceKind.FENCE&&row.amount()>row.capacity())return tr("say.fence_limit",name,row.amount());
        if(row.kind()==DeviceKind.FENCE&&row.reading()==Reading.PAUSED)return tr("say.fence_wait",name,row.amount());
        if(row.reading()==Reading.PAUSED)return tr("say.paused",name,row.amount());
        if(row.kind().turret()&&row.amount()<=8)return row.refill()>0?tr("say.ammo",name,row.amount()):tr("diagnosis.refill",name,tr("refill."+refillReason(row.transport())));
        if(row.kind()==DeviceKind.DOCK&&row.reading()==Reading.BLOCKED)return tr("refill.blocked");
        if(row.kind()==DeviceKind.DOCK)return tr(row.reading()==Reading.NO_UNIT?"say.no_unit":row.reading()==Reading.STOCK_MISSING?"say.stock_missing":"say.mota",name,row.amount());
        if(row.kind()==DeviceKind.FENCE)return tr("say.fence",name,row.amount());if(row.kind()==DeviceKind.FILTER)return tr("say.filter",name,row.amount());
        if(row.kind()==DeviceKind.PULSE_MINE)return tr("say.mine",name,row.amount());if(row.kind()==DeviceKind.SENSOR)return tr(row.reading()==Reading.ALARM?"say.alarm":"say.sensor",name,row.amount());return tr("say.ready",name,row.amount());
    }
    /** Edit controls live in their own page, so incoming telemetry never steals an input's focus. */
    private final class DeviceScreen extends Screen {
        private final UUID identity;
        private EditBox name;
        private String draft;
        private int x,y,sw,sh;
        private boolean submitted;
        private ConsoleButton save,ammo;
        DeviceScreen(UUID identity){super(tr("rename.open"));this.identity=identity;Row value=row(identity);draft=value==null?"":label(value);}
        Screen parent(){return NexoScreen.this;}
        @Override protected void init(){
            boolean focused=name==null||getFocused()==name;clearWidgets();sw=Math.min(360,width-16);sh=Math.min(204,height-16);x=(width-sw)/2;y=(height-sh)/2;
            if(name==null){name=new EditBox(font,x+16,y+62,sw-32,16,tr("rename.name"));name.setBordered(false);name.setTextColor(INK);name.setTextShadow(false);name.setMaxLength(48);name.setValue(draft);name.setResponder(v->draft=v);}
            name.setX(x+16);name.setY(y+62);name.setWidth(sw-32);addRenderableWidget(name);
            save=addRenderableWidget(new ConsoleButton(x+12,y+87,sw-24,20,tr("rename.save"),true,b->{Row target=row(identity);if(target!=null){submitted=true;send(Operation.RENAME,target,draft);}}));
            if(row(identity)!=null&&row(identity).kind()==DeviceKind.CENTELLA){ammo=addRenderableWidget(new ConsoleButton(x+12,y+133,sw-24,20,tr("action.ammo_preference"),false,b->{Row target=row(identity);if(target!=null)send(Operation.CYCLE_AMMO,target,"");}));ammo.describe(tr("hint.ammo_preference"));}else ammo=null;
            addRenderableWidget(new ConsoleButton(x+sw-92,y+sh-29,80,20,Component.translatable("gui.done"),false,b->onClose()));
            if(focused)setFocused(name);
        }
        @Override public void tick(){NexoScreen.this.tick();if(minecraft.gui.screen()!=this)return;Row target=row(identity);save.active=fresh()&&!pending&&editable(target)&&!DeviceLabels.clean(draft).isEmpty();if(ammo!=null)ammo.active=fresh()&&!pending&&editable(target);if(submitted&&!pending&&notice.equals("renamed"))onClose();}
        @Override public boolean isPauseScreen(){return false;}
        @Override public void onClose(){minecraft.gui.setScreen(NexoScreen.this);refreshAfterChild();}
        @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
            backdrop(g,width,height);frame(g,font,x,y,sw,sh,title,"NEXO / EQUIPO");card(g,x+12,y+57,sw-24,25);
            Row target=row(identity);if(target!=null&&target.kind()==DeviceKind.CENTELLA)text(g,font,tr("preferred",preference(target)),x+15,y+117,sw-30,INK);
            if(!pending&&!notice.equals("ready")&&!notice.equals("updating"))text(g,font,tr("result."+notice),x+15,y+sh-45,sw-30,MUTED);
            super.extractRenderState(g,mx,my,delta);
        }
    }
}
