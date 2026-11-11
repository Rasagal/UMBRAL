// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.UmbralContent;
import dev.umbral.manual.ManualCatalog;
import dev.umbral.manual.ManualCatalog.*;
import dev.umbral.manual.ManualProtocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static dev.umbral.client.ConsoleStyle.*;

/** The physical book and every device's help use this one, read-only field manual. */
public final class ManualScreen extends Screen {
    public enum Tab { VISUAL, STEPS, RECIPE }
    private final Screen back;
    private Entry entry;
    private Group group;
    private Entry related;
    private Tab tab;
    private String query="";
    private int left,top,w,h,nav,cx,cw,bodyY,bodyH,rows,navPage,textPage,ticks,requested=-100;
    private long nonce;
    private boolean received;
    private Optional<RecipeDisplay> recipe=Optional.empty();
    private final ManualFigures figures=new ManualFigures();
    private record Line(FormattedCharSequence text,int color){}
    public ManualScreen(Screen back,Entry entry){this(back,entry,Tab.VISUAL);}
    public ManualScreen(Screen back,Entry entry,Tab tab){super(tr("title"));this.back=back;this.entry=entry;this.group=entry.group;this.tab=tab;}
    public Entry entry(){return entry;}
    public Tab tab(){return tab;}
    public static Screen underlying(Screen screen){return screen instanceof ManualScreen manual?underlying(manual.back):screen;}
    static ConsoleButton help(Screen back,int x,int y,Entry entry){
        var b=new ConsoleButton(x,y,18,14,Component.literal("?"),false,v->{if(back instanceof CameraDirectoryScreen directory)directory.showNotice(null);Minecraft.getInstance().gui.setScreen(new ManualScreen(back,entry));});
        b.describe(Component.translatable("manual.umbral.context",title(entry)));return b;
    }
    public static Component title(Entry e){return Component.translatable(e.key("title"));}
    private static Component tr(String key,Object...args){return Component.translatable("manual.umbral."+key,args);}
    static ItemStack item(Entry entry){return new ItemStack(BuiltInRegistries.ITEM.getValue(UmbralContent.id(entry.recipe)));}
    private List<Entry> entries(){
        return Arrays.stream(Entry.values()).filter(e->query.isBlank()?(related==null?e.group==group:ManualCatalog.related(related).contains(e)):ManualCatalog.matches(query,title(e).getString()+" "+Component.translatable(e.key("summary")).getString()+" "+Component.translatable(e.key("tags")).getString())).toList();
    }
    private void choose(Entry next){entry=next;textPage=0;recipe=Optional.empty();received=false;requested=-100;init();if(tab==Tab.RECIPE)request();}
    @Override protected void init(){
        clearWidgets();w=Math.min(610,width-12);h=Math.min(370,height-12);left=(width-w)/2;top=(height-h)/2;
        nav=w<420?100:142;cx=left+nav+24;cw=w-nav-40;bodyY=top+99;bodyH=h-163;rows=Math.max(1,(h-160)/21);
        var search=addRenderableWidget(new EditBox(font,left+19,top+60,nav-10,14,tr("search")));search.setMaxLength(80);search.setBordered(false);search.setTextColor(INK);search.setTextShadow(false);search.setValue(query);search.setHint(tr("search").copy().withStyle(style->style.withColor(MUTED)));
        search.setResponder(value->{query=value;navPage=0;boolean focus=search.isFocused();int cursor=search.getCursorPosition();init();if(focus&&children().getFirst() instanceof EditBox box){setFocused(box);box.setCursorPosition(cursor);}});
        var groupButton=addRenderableWidget(new ConsoleButton(left+14,top+78,nav,18,related!=null?tr("related"):tr("group."+group.name().toLowerCase(Locale.ROOT)),false,b->{related=null;query="";group=Group.values()[(group.ordinal()+1)%Group.values().length];navPage=0;init();}));groupButton.describe(tr("group_hint"));
        var list=entries();int pages=Math.max(1,(list.size()+rows-1)/rows);navPage=Math.clamp(navPage,0,pages-1);
        for(int n=navPage*rows;n<Math.min(list.size(),(navPage+1)*rows);n++){
            var e=list.get(n);var button=addRenderableWidget(new ConsoleButton(left+14,top+101+(n-navPage*rows)*21,nav,19,title(e),entry==e,b->choose(e)));
            button.describe(title(e).copy().append(". ").append(Component.translatable(e.key("summary"))));
        }
        var prev=addRenderableWidget(new ConsoleButton(left+14,top+h-60,25,18,Component.literal("<"),false,b->{navPage--;init();}));prev.active=navPage>0;prev.describe(tr("previous"));
        var next=addRenderableWidget(new ConsoleButton(left+nav-11,top+h-60,25,18,Component.literal(">"),false,b->{navPage++;init();}));next.active=navPage+1<pages;next.describe(tr("next"));
        int tw=(cw-4)/3;
        for(var t:Tab.values())addRenderableWidget(new ConsoleButton(cx+t.ordinal()*(tw+2),top+76,tw,18,tr("tab."+t.name().toLowerCase(Locale.ROOT)),tab==t,b->{tab=t;textPage=0;init();if(t==Tab.RECIPE)request();}));
        if(tab==Tab.STEPS){int count=Math.max(1,(lines().size()+lineCount()-1)/lineCount());textPage=Math.clamp(textPage,0,count-1);
            var p=addRenderableWidget(new ConsoleButton(cx,top+h-60,25,18,Component.literal("<"),false,b->{textPage--;init();}));p.active=textPage>0;p.describe(tr("previous"));
            var n=addRenderableWidget(new ConsoleButton(cx+cw-25,top+h-60,25,18,Component.literal(">"),false,b->{textPage++;init();}));n.active=textPage+1<count;n.describe(tr("next"));
        }
        int bw=(w-40)/3;
        addRenderableWidget(new ConsoleButton(left+14,top+h-30,bw,20,tr("back"),false,b->onClose()));
        addRenderableWidget(new ConsoleButton(left+20+bw,top+h-30,bw,20,tr("home"),false,b->{group=Group.GOALS;query="";related=null;navPage=0;tab=Tab.VISUAL;choose(Entry.START);}));
        addRenderableWidget(new ConsoleButton(left+26+2*bw,top+h-30,bw,20,tr("related"),false,b->{related=entry;query="";navPage=0;init();}));
    }
    private int lineCount(){return Math.max(1,bodyH/11);}
    private List<Line> lines(){
        var result=new ArrayList<Line>();append(result,Component.translatable(entry.key("summary")),INK);result.add(new Line(FormattedCharSequence.EMPTY,INK));
        for(int n=1;n<=3;n++){append(result,Component.literal(n+". ").append(Component.translatable(entry.key("step"+n))),INK);result.add(new Line(FormattedCharSequence.EMPTY,INK));}
        append(result,tr("note").copy().append(" ").append(Component.translatable(entry.key("tip"))),ALERT);return result;
    }
    private void append(List<Line> out,Component component,int color){for(var line:font.split(component,cw-14))out.add(new Line(line,color));}
    private void request(){
        if(minecraft.level==null||!ClientPlayNetworking.canSend(ManualProtocol.Request.TYPE))return;
        nonce=ThreadLocalRandom.current().nextLong();requested=ticks;ClientPlayNetworking.send(new ManualProtocol.Request(entry,nonce));
    }
    public void accept(ManualProtocol.Recipe value){if(value.entry()==entry&&value.nonce()==nonce){recipe=value.display();received=true;}}
    @Override public void tick(){
        if(minecraft.player==null||minecraft.level==null||!minecraft.player.isAlive()){minecraft.gui.setScreen(null);return;}
        ticks++;if(tab==Tab.RECIPE&&ticks-requested>=(received?100:25))request();
    }
    @Override public void onClose(){minecraft.gui.setScreen(back);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        backdrop(g,width,height);frame(g,font,left,top,w,h,title,tr("module").getString());
        card(g,left+14,top+55,nav,20);text(g,font,title(entry),cx,top+58,cw,INK);
        if(mx>=cx&&mx<cx+cw&&my>=top+55&&my<top+72)g.setTooltipForNextFrame(font,title(entry),mx,my);
        g.fill(cx-7,top+55,cx-6,top+h-42,LINE);
        var list=entries();int pages=Math.max(1,(list.size()+rows-1)/rows);
        text(g,font,Component.literal((navPage+1)+"/"+pages),left+45,top+h-55,nav-55,MUTED);
        if(list.isEmpty())text(g,font,tr("no_results"),left+17,top+104,nav-6,MUTED);
        if(tab==Tab.VISUAL){
            int figureH=bodyH>=150?bodyH-51:bodyH;
            figures.draw(g,font,entry,cx,bodyY,cw,figureH,ticks,mx,my);
            if(bodyH>=150){var summary=font.split(Component.translatable(entry.key("summary")),cw-8);for(int n=0;n<Math.min(3,summary.size());n++)g.text(font,summary.get(n),cx+4,bodyY+figureH+8+n*11,INK,false);}
            text(g,font,tr("visual_hint"),cx,top+h-55,cw,MUTED);
        }else if(tab==Tab.STEPS){
            card(g,cx,bodyY,cw,bodyH);var lines=lines();int start=textPage*lineCount();
            for(int n=start;n<Math.min(start+lineCount(),lines.size());n++){var l=lines.get(n);g.text(font,l.text,cx+7,bodyY+3+(n-start)*11,l.color,false);}
            text(g,font,tr("page",textPage+1,Math.max(1,(lines.size()+lineCount()-1)/lineCount())),cx+30,top+h-55,cw-60,MUTED);
        }else drawRecipe(g,mx,my);
        super.extractRenderState(g,mx,my,delta);
    }
    private void drawRecipe(GuiGraphicsExtractor g,int mx,int my){
        card(g,cx,bodyY,cw,bodyH);
        if(!received||recipe.isEmpty()){
            var message=font.split(tr(!received?"recipe_loading":"recipe_missing"),cw-14);for(int n=0;n<Math.min(bodyH/11,message.size());n++)g.text(font,message.get(n),cx+7,bodyY+7+n*11,INK,false);
            text(g,font,tr("recipe_live"),cx,top+h-55,cw,MUTED);return;
        }
        var display=recipe.get();List<SlotDisplay> ingredients;int columns;Component method=tr("crafting");
        if(display instanceof ShapedCraftingRecipeDisplay shaped){ingredients=shaped.ingredients();columns=shaped.width();}
        else if(display instanceof ShapelessCraftingRecipeDisplay shapeless){ingredients=shapeless.ingredients();columns=Math.min(3,Math.max(1,ingredients.size()));method=tr("shapeless");}
        else if(display instanceof FurnaceRecipeDisplay furnace){ingredients=List.of(furnace.ingredient(),furnace.fuel());columns=1;method=tr("furnace");}
        else if(display instanceof StonecutterRecipeDisplay stone){ingredients=List.of(stone.input());columns=1;method=tr("stonecutter");}
        else if(display instanceof SmithingRecipeDisplay smith){ingredients=List.of(smith.template(),smith.base(),smith.addition());columns=3;method=tr("smithing");}
        else {text(g,font,tr("recipe_station"),cx+7,bodyY+8,cw-14,INK);slot(g,display.result(),cx+cw/2-9,bodyY+bodyH/2-9,mx,my);text(g,font,tr("recipe_live"),cx,top+h-55,cw,MUTED);return;}
        int rr=Math.max(1,(ingredients.size()+columns-1)/columns),gridW=columns*20,gridH=rr*20;
        int scale=Math.clamp(Math.min((cw-14)/(gridW+54),(bodyH-(bodyH>=110?32:4))/gridH),1,2);gridW*=scale;gridH*=scale;
        int gx=cx+(cw-gridW-54*scale)/2,gy=bodyY+(bodyH-gridH)/2;
        for(int n=0;n<ingredients.size();n++)slot(g,ingredients.get(n),gx+(n%columns)*20*scale,gy+(n/columns)*20*scale,mx,my,scale);
        text(g,font,Component.literal(">"),gx+gridW+10*scale,gy+gridH/2-3,12,INK);slot(g,display.result(),gx+gridW+30*scale,gy+gridH/2-9*scale,mx,my,scale);
        if(bodyH>=110){text(g,font,method,cx+7,bodyY+8,cw-36,INK);slot(g,display.craftingStation(),cx+cw-26,bodyY+5,mx,my);}
        text(g,font,method.copy().append(" · ").append(tr("recipe_live")),cx,top+h-55,cw,MUTED);
    }
    private void slot(GuiGraphicsExtractor g,SlotDisplay display,int x,int y,int mx,int my){slot(g,display,x,y,mx,my,1);}
    private void slot(GuiGraphicsExtractor g,SlotDisplay display,int x,int y,int mx,int my,int scale){
        card(g,x,y,19*scale,19*scale);var values=display.resolveForStacks(SlotDisplayContext.fromLevel(minecraft.level));if(values.isEmpty())return;
        ItemStack value=values.get(Math.floorMod(ticks/30,values.size()));g.pose().pushMatrix();g.pose().translate(x+scale,y+scale);g.pose().scale(scale,scale);g.item(value,0,0);
        if(value.getCount()>1)g.text(font,Integer.toString(value.getCount()),17-font.width(Integer.toString(value.getCount())),10,INK,false);g.pose().popMatrix();
        if(mx>=x&&mx<x+19*scale&&my>=y&&my<y+19*scale)g.setTooltipForNextFrame(font,value.getHoverName(),mx,my);
    }
}
