// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.filter.*;
import dev.umbral.filter.FilterProtocol.*;
import dev.umbral.block.DeviceBlockEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static dev.umbral.client.ConsoleStyle.*;

/** Inspection settings share NEXO's metal frame, green LCD, copper fittings and keyboard navigation. */
public final class FilterScreen extends Screen {
    private Snapshot data;
    private final Screen parent;
    private int left,top,w,h,tab,page,profilePage,rows,ticks,lastUpdate,sentAt;
    private String selected="",notice="ready",draft="",minimum="1";
    private EditBox selector,count;
    private boolean pending,advanced,modePicker;
    private int inventoryPage,inventoryFrom,inventorySlots,inventoryX,inventoryY,inventorySize,inventoryPages=1;
    private long nonce;
    private final List<ConsoleButton> controls=new ArrayList<>();
    private final Map<ConsoleButton,BooleanSupplier> availability=new IdentityHashMap<>();
    public FilterScreen(Snapshot initial,Screen parent){super(tr("title"));data=initial;this.parent=parent;if(!data.rules().isEmpty())selected=data.rules().getFirst().selector();}
    public Screen parent(){return parent;}
    private static Component tr(String key,Object...args){return Component.translatable("filter.umbral."+key,args);}
    private FilterRule rule(){return data.rules().stream().filter(r->r.selector().equals(selected)).findFirst().orElse(null);}
    private List<Profile> profiles(){var map=new LinkedHashMap<UUID,Profile>();var rule=rule();if(rule!=null)rule.exemptions().forEach(e->map.put(e.id(),new Profile(e.id(),e.name())));data.profiles().forEach(p->map.put(p.id(),p));return List.copyOf(map.values());}
    private boolean fresh(){return data.valid()&&ticks-lastUpdate<60;}
    private ConsoleButton button(int x,int y,int width,Component label,boolean accent,Runnable action){return addRenderableWidget(new ConsoleButton(x,y,width,18,label,accent,b->action.run()));}
    private ConsoleButton control(int x,int y,int width,Component label,Runnable action){var b=button(x,y,width,label,false,action);controls.add(b);return b;}
    private ConsoleButton controlIf(int x,int y,int width,Component label,BooleanSupplier allowed,Runnable action){var b=control(x,y,width,label,action);availability.put(b,allowed);return b;}
    @Override protected void init(){
        EditBox previousSelector=selector,previousCount=count;
        boolean focusSelector=selector!=null&&getFocused()==selector,focusCount=count!=null&&getFocused()==count;
        if((modePicker||tab!=0||!advanced)&&(focusSelector||focusCount))setFocused(null);
        clearWidgets();controls.clear();availability.clear();selector=null;count=null;inventorySlots=0;inventoryPages=1;
        w=Math.min(590,width-16);h=Math.min(350,height-16);left=(width-w)/2;top=(height-h)/2;rows=Math.max(1,(h-184)/22);
        if(modePicker){initModePicker();updateControls();return;}
        int tw=(w-102)/3;for(int t=0;t<3;t++){final int target=t;button(left+12+t*(tw+3),top+55,tw,tr("tab."+t),tab==t,()->{saveDraft();tab=target;page=0;init();});}
        control(left+w-78,top+55,66,tr("preset"),()->send(Operation.PRESET,"",0,null)).describe(tr("preset_hint"));
        if(tab==0)button(left+w-102,top+77,90,tr(advanced?"basic":"advanced"),advanced,()->{saveDraft();advanced=!advanced;inventoryPage=0;init();}).describe(tr(advanced?"basic_hint":"advanced_hint"));
        int bodyY=top+99,lw=(w-32)*44/100,x=left+20+lw,rw=w-lw-32;
        if(tab!=2){
            page=Math.clamp(page,0,Math.max(0,(data.rules().size()-1)/rows));int first=page*rows;
            for(int n=first;n<Math.min(first+rows,data.rules().size());n++){
                var r=data.rules().get(n);Component label=display(r.selector()).copy();if(advanced||r.minimum()>1)label=label.copy().append(" ≥ "+r.minimum());
                button(left+12,bodyY+(n-first)*22,lw,label,r.selector().equals(selected),()->{selected=r.selector();draft=selected;minimum=""+r.minimum();init();}).describe(Component.literal(r.selector()).append(" · ").append(tr("exceptions_count",r.exemptions().size())));
            }
            textHint=data.rules().isEmpty()?tr("empty"):null;
            var prev=button(left+12,top+h-81,20,Component.literal("<"),false,()->{page--;init();});prev.active=page>0;
            var next=button(left+36,top+h-81,20,Component.literal(">"),false,()->{page++;init();});next.active=(page+1)*rows<data.rules().size();
            if(tab==0){
                controlIf(left+60,top+h-81,lw-48,tr("remove"),()->rule()!=null,()->send(Operation.REMOVE,selected,0,null)).describe(tr("remove_hint"));
                if(advanced){
                    selector=editor(previousSelector,x+4,bodyY+4,rw-8,"selector",draft,128,v->draft=v);
                    count=editor(previousCount,x+4,bodyY+29,37,"minimum",minimum,4,v->minimum=v);
                    control(x+49,bodyY+25,rw-49,tr("save"),()->{try{send(Operation.RULE,draft.strip(),Integer.parseInt(minimum),null);}catch(NumberFormatException e){notice="bad_rule";}}).describe(tr("minimum_hint"));
                    int size=Math.min(21,rw/9),space=top+h-104-(bodyY+55);
                    if(size>=17&&space>=4*size)addInventory(x,bodyY+55,size,0,36);
                    else heldButton(x,bodyY+49,rw);
                }else{
                    int size=Math.min(21,rw/9),space=top+h-(h>=280?104:62)-(bodyY+43),visibleRows=Math.clamp(space/Math.max(1,size),1,4);
                    int slots=visibleRows*9,pages=(36+slots-1)/slots;inventoryPages=pages;inventoryPage=Math.clamp(inventoryPage,0,pages-1);
                    heldButton(x,bodyY+21,pages>1?rw-58:rw);
                    if(pages>1){
                        var previous=button(x+rw-52,bodyY+21,23,Component.literal("<"),false,()->{inventoryPage--;init();});previous.active=inventoryPage>0;previous.describe(tr("inventory_page",inventoryPage+1,pages));
                        var following=button(x+rw-25,bodyY+21,23,Component.literal(">"),false,()->{inventoryPage++;init();});following.active=inventoryPage<pages-1;following.describe(tr("inventory_page",inventoryPage+1,pages));
                    }
                    addInventory(x,bodyY+43,size,inventoryPage*slots,Math.min(slots,36-inventoryPage*slots));
                }
            }else{
                var known=profiles();int profiles=Math.max(1,(h-184)/22);profilePage=Math.clamp(profilePage,0,Math.max(0,(known.size()-1)/profiles));var selectedRule=rule();
                for(int n=profilePage*profiles;n<Math.min(known.size(),(profilePage+1)*profiles);n++){var p=known.get(n);boolean exempt=selectedRule!=null&&selectedRule.bypasses(p.id());controlIf(x,bodyY+(n%profiles)*22,rw,Component.literal((exempt?"✓ ":"+ ")+p.name()),()->rule()!=null,()->send(Operation.EXEMPT,selected,0,p.id())).describe(tr(exempt?"exception_remove":"exception_add",p.name()));}
                var prevP=button(x,top+h-81,23,Component.literal("<"),false,()->{profilePage--;init();});prevP.active=profilePage>0;var nextP=button(x+27,top+h-81,23,Component.literal(">"),false,()->{profilePage++;init();});nextP.active=(profilePage+1)*profiles<known.size();
            }
        }else{
            int historyRows=Math.max(1,(h-180)/28);page=Math.clamp(page,0,Math.max(0,(data.history().size()-1)/historyRows));
            var prev=button(left+12,top+h-81,23,Component.literal("<"),false,()->{page--;init();});prev.active=page>0;var next=button(left+39,top+h-81,23,Component.literal(">"),false,()->{page++;init();});next.active=(page+1)*historyRows<data.history().size();
        }
        int bw=(w-36)/3;
        control(left+12,top+h-55,bw,tr(data.armed()?"disarm":"arm"),()->send(data.armed()?Operation.DISARM:Operation.ARM,"",0,null));
        control(left+18+bw,top+h-55,bw,tr("mode."+data.mode().id()),()->{saveDraft();modePicker=true;init();}).describe(tr("intent."+data.mode().id()+"_hint"));
        control(left+24+2*bw,top+h-55,bw,tr("test"),()->{tab=2;send(Operation.TEST,"",0,null);}).describe(tr("test_hint"));
        if(data.door()!=null)control(left+12,top+h-29,Math.min(115,w-110),tr("unlink"),()->send(Operation.UNLINK,"",0,null)).describe(tr("linked_door",data.doorName().isEmpty()?data.door().position().toShortString():data.doorName()));
        button(left+w-92,top+h-29,80,Component.translatable("gui.done"),false,this::onClose);
        addRenderableWidget(ManualScreen.help(this,left+w-30,top+31,dev.umbral.manual.ManualCatalog.Entry.FILTER));
        if(focusSelector&&selector!=null)setFocused(selector);if(focusCount&&count!=null)setFocused(count);updateControls();
    }
    /** Polling must retain the widget's selection anchor, scroll and composition state as well as its caret. */
    private EditBox editor(EditBox current,int x,int y,int width,String key,String value,int maxLength,Consumer<String> responder){
        if(current==null){
            current=new EditBox(font,x,y,width,14,tr(key));current.setBordered(false);current.setTextShadow(false);current.setTextColor(INK);current.setMaxLength(maxLength);current.setValue(value);current.setResponder(responder);
        }else{
            if(current.getWidth()!=width){current.setWidth(width);current.setCursorPosition(current.getCursorPosition());}
            current.setX(x);current.setY(y);
            if(!current.getValue().equals(value))current.setValue(value);
        }
        return addRenderableWidget(current);
    }
    private void heldButton(int x,int y,int width){
        controlIf(x,y,width,tr("held"),()->minecraft.player!=null&&!minecraft.player.getMainHandItem().isEmpty(),()->{if(minecraft.player!=null){String id=BuiltInRegistries.ITEM.getKey(minecraft.player.getMainHandItem().getItem()).toString();send(Operation.RULE,id,1,null);}}).describe(tr("held_hint"));
    }
    private void addInventory(int x,int y,int size,int first,int amount){
        if(minecraft.player==null)return;
        inventoryX=x;inventoryY=y;inventorySize=size;inventoryFrom=first;inventorySlots=amount;
        for(int n=0;n<amount;n++){
            final int slot=first+n;var stack=minecraft.player.getInventory().getItem(slot);
            controlIf(x+n%9*size,y+n/9*size,size-1,Component.empty(),()->minecraft.player!=null&&!minecraft.player.getInventory().getItem(slot).isEmpty(),()->send(Operation.RULE,"",-slot-1,null)).describe(stack.isEmpty()?tr("slot_empty"):tr("basic.add",stack.getHoverName()));
        }
    }
    private void initModePicker(){
        int y=top+82;
        for(var mode:FilterState.Mode.values()){
            var b=button(left+12,y+mode.ordinal()*37,w-24,tr("intent."+mode.id()),mode==data.mode(),()->{
                modePicker=false;init();if(mode!=data.mode())send(Operation.MODE,"",mode.ordinal(),null);
            });controls.add(b);availability.put(b,()->mode!=FilterState.Mode.ACCESS||data.door()!=null);b.describe(mode==FilterState.Mode.ACCESS?tr("intent."+mode.id()+"_hint").copy().append(" ").append(tr("intent.access_requirement")):tr("intent."+mode.id()+"_hint"));
        }
        button(left+12,top+h-29,w-24,tr("mode_back"),false,()->{modePicker=false;init();});
        addRenderableWidget(ManualScreen.help(this,left+w-30,top+31,dev.umbral.manual.ManualCatalog.Entry.FILTER));
    }
    private Component textHint;
    private void saveDraft(){if(selector!=null)draft=selector.getValue();if(count!=null)minimum=count.getValue();}
    private void updateControls(){controls.forEach(b->b.active=fresh()&&!pending&&availability.getOrDefault(b,()->true).getAsBoolean());}
    private void send(Operation op,String selector,int value,UUID player){if(pending||op!=Operation.QUERY&&!fresh())return;pending=true;sentAt=ticks;nonce=ThreadLocalRandom.current().nextLong();ClientPlayNetworking.send(new Request(data.root(),data.identity(),data.link(),data.target(),data.targetIdentity(),nonce,data.revision(),op,selector,value,player));if(op!=Operation.QUERY)notice="updating";updateControls();}
    public void accept(Snapshot s){if(!s.root().equals(data.root())||!s.identity().equals(data.identity())||!Objects.equals(s.link(),data.link())||!s.target().equals(data.target())||!s.targetIdentity().equals(data.targetIdentity())||!pending||s.nonce()!=nonce)return;pending=false;if(s.result().equals("busy")){notice="busy";updateControls();return;}if(!s.valid()){onClose();return;}saveDraft();data=s;lastUpdate=ticks;if(!s.result().equals("ready"))notice=s.result();if(!selected.isEmpty()&&rule()==null)selected="";if(selected.isEmpty()&&!data.rules().isEmpty())selected=data.rules().getFirst().selector();init();}
    @Override public void tick(){if(minecraft.player==null||minecraft.level==null||!minecraft.player.isAlive()||minecraft.player.isSpectator()||data.root().distToCenterSqr(minecraft.player.position())>64||!minecraft.level.isLoaded(data.root())||!(minecraft.level.getBlockEntity(data.root()) instanceof DeviceBlockEntity)){onClose();return;}ticks++;if(pending&&ticks-sentAt>60){pending=false;lastUpdate=ticks-61;notice="stale";}if(ticks%20==0&&!pending)send(Operation.QUERY,"",0,null);updateControls();}
    @Override public void onClose(){if(modePicker){modePicker=false;init();}else minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    public static Component display(String id){if(id.startsWith("#"))return Component.literal(id);var parsed=Identifier.tryParse(id);return parsed==null?Component.literal(id):BuiltInRegistries.ITEM.getValue(parsed).getDefaultInstance().getHoverName();}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        backdrop(g,width,height);frame(g,font,left,top,w,h,title,"FILTRO / INSPECCION");
        if(modePicker){
            text(g,font,tr("intent.title"),left+16,top+58,w-32,INK);
            text(g,font,tr("intent.prompt",tr("intent."+data.mode().id())),left+16,top+70,w-32,MUTED);
            for(var mode:FilterState.Mode.values())text(g,font,tr(mode==FilterState.Mode.ACCESS&&data.door()==null?"intent.access_requirement":"intent."+mode.id()+"_summary"),left+19,top+103+mode.ordinal()*37,w-38,MUTED);
            super.extractRenderState(g,mx,my,delta);return;
        }
        text(g,font,tr(data.online()?(data.armed()?"active":"idle"):"offline").copy().append(" · ").append(tr("rules_count",data.rules().size())),left+13,top+80,tab==0?w-123:w-26,data.online()?INK:ALERT);
        int lw=(w-32)*44/100,x=left+20+lw,rw=w-lw-32,bodyY=top+99;
        if(tab==0&&advanced){card(g,x,bodyY,rw,22);if(selector!=null&&selector.getValue().isEmpty()&&!selector.isFocused())text(g,font,tr("selector"),x+4,bodyY+4,rw-8,MUTED);card(g,x,bodyY+25,43,22);}
        else if(tab==0){text(g,font,tr("basic.choose"),x+4,bodyY+4,rw-48,INK);if(inventoryPages>1)text(g,font,Component.literal((inventoryPage+1)+"/"+inventoryPages),x+rw-32,bodyY+4,32,MUTED);}
        if(tab!=2&&textHint!=null)text(g,font,textHint,left+16,bodyY+6,lw-8,MUTED);
        if(tab==2){int hr=Math.max(1,(h-180)/28),first=page*hr;for(int n=first;n<Math.min(data.history().size(),first+hr);n++){var r=data.history().get(n);int y=bodyY+(n-first)*28;card(g,left+12,y,w-24,25);Component line=Component.literal(r.name()+" · ").append(tr("scan."+r.outcome().name().toLowerCase(Locale.ROOT)));if(r.test())line=line.copy().append(" · ").append(tr("test_label"));text(g,font,line,left+20,y+3,w-40,r.outcome()==Inspection.Outcome.MATCH?ALERT:INK);Component items=r.matches().isEmpty()?tr("no_matches"):display(r.matches().getFirst().item()).copy().append(" × "+r.matches().getFirst().count());text(g,font,items,left+20,y+14,w-40,MUTED);if(mx>left+12&&mx<left+w-12&&my>=y&&my<y+25){var lines=new ArrayList<Component>();lines.add(line);r.matches().forEach(m->lines.add(display(m.item()).copy().append(" × "+m.count())));g.setComponentTooltipForNextFrame(font,lines,mx,my);}}if(data.history().isEmpty())text(g,font,tr("history_empty"),left+20,bodyY+8,w-40,MUTED);}
        if(h>=280)text(g,font,Component.translatable(!fresh()?"screen.umbral.stale":"filter.umbral.result."+notice),left+15,top+h-96,w-30,MUTED);
        super.extractRenderState(g,mx,my,delta);
        if(tab==0&&inventorySlots>0&&minecraft.player!=null)for(int n=0;n<inventorySlots;n++){var item=minecraft.player.getInventory().getItem(inventoryFrom+n);if(!item.isEmpty())g.item(item,inventoryX+n%9*inventorySize+(inventorySize-16)/2,inventoryY+n/9*inventorySize+(inventorySize-16)/2);}
    }
}
