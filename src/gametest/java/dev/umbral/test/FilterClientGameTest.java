// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import dev.umbral.UmbralContent;
import dev.umbral.access.*;
import dev.umbral.block.*;
import dev.umbral.client.*;
import dev.umbral.filter.*;
import dev.umbral.network.*;
import dev.umbral.installation.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("UnstableApiUsage")
public final class FilterClientGameTest implements FabricClientGameTest {
    static final Path OUTPUT=Path.of("../../visual-checks/1.6.1");
    static final BlockPos CORE=new BlockPos(-5,-60,1),NODE=new BlockPos(-3,-60,1),FILTER=new BlockPos(-1,-60,0),DOOR=new BlockPos(0,-60,3),READER=new BlockPos(1,-60,2),NEXO=new BlockPos(-3,-60,-2);
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    static DeviceBlockEntity d(ServerLevel l,BlockPos p){return (DeviceBlockEntity)l.getBlockEntity(p);}
    static void capture(ClientGameTestContext c,String name){c.getInput().setCursorPos(2,2);c.runOnClient(mc->mc.gui.toastManager().clear());c.waitTicks(3);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    static void click(ClientGameTestContext c,String key){c.waitFor(mc->mc.gui.screen()!=null&&mc.gui.screen().children().stream().anyMatch(v->v instanceof Button b&&b.active&&b.getMessage().getString().equals(Component.translatable(key).getString())));c.clickScreenButton(key);c.waitTicks(7);}
    static EditBox filterField(Screen screen,int index){return screen.children().stream().filter(v->v instanceof EditBox).map(v->(EditBox)v).toList().get(index);}
    static void selectNexo(ClientGameTestContext c,String label){
        click(c,"nexo.umbral.list.all");
        c.runOnClient(mc->{check(mc.gui.screen() instanceof NexoScreen,"Device search uses the real NEXO console");var search=mc.gui.screen().children().stream().filter(v->v instanceof net.minecraft.client.gui.components.EditBox).map(v->(net.minecraft.client.gui.components.EditBox)v).findFirst().orElseThrow();search.setValue(label);});
        click(c,label);
    }
    static void inventoryItem(ClientGameTestContext c,int index){
        c.waitFor(mc->{var cells=mc.gui.screen().children().stream().filter(v->v instanceof Button).map(v->(Button)v).filter(b->b.getMessage().getString().isEmpty()&&b.getWidth()<=21).sorted(Comparator.comparingInt(Button::getY).thenComparingInt(Button::getX)).toList();return cells.size()>index&&cells.get(index).active;});
        var at=c.computeOnClient(mc->{var cells=mc.gui.screen().children().stream().filter(v->v instanceof Button).map(v->(Button)v).filter(b->b.getMessage().getString().isEmpty()&&b.getWidth()<=21).sorted(Comparator.comparingInt(Button::getY).thenComparingInt(Button::getX)).toList();var b=cells.get(index);double scale=mc.getWindow().getGuiScale();return new double[]{(b.getX()+b.getWidth()/2d)*scale,(b.getY()+b.getHeight()/2d)*scale};});
        c.getInput().setCursorPos(at[0],at[1]);c.getInput().pressMouse(1);c.waitTicks(7);
    }
    static void inventoryNext(ClientGameTestContext c){
        var at=c.computeOnClient(mc->{var b=mc.gui.screen().children().stream().filter(v->v instanceof Button).map(v->(Button)v).filter(v->v.getX()>mc.gui.screen().width/2&&v.getMessage().getString().equals(">")&&v.active).findFirst().orElseThrow();double scale=mc.getWindow().getGuiScale();return new double[]{(b.getX()+b.getWidth()/2d)*scale,(b.getY()+b.getHeight()/2d)*scale};});
        c.getInput().setCursorPos(at[0],at[1]);c.getInput().pressMouse(1);c.waitTicks(3);
    }
    static void aim(ClientGameTestContext c,Vec3 target){var a=c.computeOnClient(mc->{var v=target.subtract(mc.player.getEyePosition());return new float[]{(float)Math.toDegrees(Math.atan2(-v.x,v.z)),(float)-Math.toDegrees(Math.atan2(v.y,v.horizontalDistance()))};});c.getInput().lookAt(a[0],a[1]);}
    private static void useFilter(ClientGameTestContext c){
        aim(c,Vec3.atLowerCornerOf(FILTER).add(.7,.65,.35));
        // Rotation and native picking are updated separately. Do not interact
        // with the previous frame's target after teleporting or turning.
        c.waitFor(mc->mc.hitResult instanceof BlockHitResult hit&&hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(FILTER));
        c.getInput().pressKey(o->o.keyUse);c.waitForScreen(FilterScreen.class);
    }
    static void layout(ClientGameTestContext c){c.runOnClient(mc->{var s=mc.gui.screen();var buttons=s.children().stream().filter(v->v instanceof Button).map(v->(Button)v).toList();for(var a:buttons){check(a.getX()>=0&&a.getY()>=0&&a.getRight()<=s.width&&a.getBottom()<=s.height,"FILTRO controls fit viewport");for(var b:buttons)if(a!=b)check(a.getRight()<=b.getX()||b.getRight()<=a.getX()||a.getBottom()<=b.getY()||b.getBottom()<=a.getY(),"FILTRO buttons do not overlap");}});}
    static DeviceBlockEntity put(ServerPlayer p,BlockPos pos,DeviceBlock block,String label){p.level().setBlockAndUpdate(pos,block.defaultBlockState());var d=d((ServerLevel)p.level(),pos);d.initializeOwner(p.getUUID(),label);return d;}
    private static void opticalFeedback(){
        try{
            var alert=javax.imageio.ImageIO.read(OUTPUT.resolve("umbral-filtro-laser-alert.png").toFile());var idle=javax.imageio.ImageIO.read(OUTPUT.resolve("umbral-filtro-laser-idle.png").toFile());int changed=0;
            for(int y=0;y<alert.getHeight();y++)for(int x=0;x<alert.getWidth();x++){
                int a=alert.getRGB(x,y),b=idle.getRGB(x,y),r=(a>>16)&255,g=(a>>8)&255,blue=a&255,R=(b>>16)&255,G=(b>>8)&255,B=b&255;
                if(r>=170&&r-g>=60&&r-blue>=50&&G>=170&&G-R>=12&&B-R>=4)changed++;
            }
            check(changed>=80,"A real restricted visit changes visible rays from red back to mint, pixels="+changed);
            Files.writeString(OUTPUT.resolve("filter-optics-feedback.json"),"{\"version\":\"1.6.1\",\"real_reentry\":true,\"red_to_mint_pixels\":"+changed+"}");
        }catch(java.io.IOException e){throw new RuntimeException(e);}
    }
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);var reload=c.computeOnClient(mc->{mc.options.renderDistance().set(4);mc.options.simulationDistance().set(5);mc.options.guiScale().set(2);mc.options.fov().set(60);mc.options.setCameraType(CameraType.FIRST_PERSON);mc.resizeGui();mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->reload.isDone()&&mc.gui.overlay()==null,1000);
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();String playerName=c.computeOnClient(mc->mc.player.getGameProfile().name());AtomicReference<ServerPlayer> visitor=new AtomicReference<>();AtomicReference<ItemStack> card=new AtomicReference<>();world.getServer().runCommand("gamemode creative @a");world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server->{var l=conn.getServerLevel();var p=conn.getServerPlayer();for(int x=-7;x<=6;x++)for(int z=-7;z<=6;z++)l.setBlockAndUpdate(new BlockPos(x,-61,z),(x%3==0?Blocks.POLISHED_DEEPSLATE:Blocks.SMOOTH_STONE).defaultBlockState());for(int x=-4;x<=4;x++)for(int y=-60;y<=-56;y++)if(x!=0||y>-59)l.setBlockAndUpdate(new BlockPos(x,y,3),(y==-56?Blocks.POLISHED_DEEPSLATE:Blocks.SMOOTH_STONE).defaultBlockState());
                var core=put(p,CORE,UmbralContent.CORE,"Núcleo de entrada");l.setBlockAndUpdate(CORE.east(),UmbralContent.CABLE.defaultBlockState());put(p,NODE,UmbralContent.NODE,"Nodo de entrada").bind(p.getUUID(),core);p.setPos(-.5,-60,-2.5);p.setYRot(0);p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.FILTER));var floor=FILTER.below();UmbralContent.FILTER.asItem().useOn(new UseOnContext(p,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(floor).add(0,.5,0),Direction.UP,floor,false)));var f=d(l,FILTER);check(FilterBlock.complete(f),"Actual kit creates complete arch");f.bind(p.getUUID(),core);check(NetworkService.inspect(l,f).online(),"Arch has local radio");
                put(p,DOOR,UmbralContent.DOOR,"Puerta de entrada");l.setBlockAndUpdate(DOOR.above(),UmbralContent.DOOR.defaultBlockState().setValue(SecurityDoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));d(l,DOOR).bind(p.getUUID(),core);var r=put(p,READER,UmbralContent.READER,"Lector de entrada");r.bind(p.getUUID(),core);r.targetDoor(p.getUUID(),d(l,DOOR));put(p,NEXO,UmbralContent.NEXO,"NEXO · Entrada").bind(p.getUUID(),core);
                check(FilterService.link(p,f,d(l,DOOR).asDoorTarget()).equals("door_linked"),"Aligned real door binds");p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.ACCESS_CARD));check(AccessService.issue(p,r)==AccessService.Result.ISSUED,"Owner issues actual pass");for(int n=0;n<36;n++)if(p.getInventory().getItem(n).has(UmbralContent.CARD_ID))card.set(p.getInventory().getItem(n).copy());p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);p.getInventory().setItem(4,new ItemStack(Items.TNT,6));p.getInventory().setItem(5,new ItemStack(Items.IRON_SWORD));
                // Same embedded connection as Minecraft's native mock-player helper. No external account/service.
                var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"Visitante"),false);var guest=new ServerPlayer(server,l,cookie.gameProfile(),cookie.clientInformation()){@Override public GameType gameMode(){return GameType.SURVIVAL;}@Override public boolean isClientAuthoritative(){return false;}};var connection=new Connection(PacketFlow.SERVERBOUND);new EmbeddedChannel(connection);server.getPlayerList().placeNewPlayer(connection,guest,cookie);guest.setPos(.5,-60,-3);guest.getInventory().setItem(0,new ItemStack(Items.TNT,3));visitor.set(guest);
            });
            world.getServer().runCommand("tp "+playerName+" -0.5 -60 -2.6 0 0");conn.waitForClientboundPackets();useFilter(c);
            c.runOnClient(mc->check(mc.gui.screen().children().stream().noneMatch(v->v instanceof net.minecraft.client.gui.components.EditBox),"Basic FILTRO starts with visual selection, without technical identifiers"));
            inventoryItem(c,5);world.getServer().runOnServer(s->check(d(conn.getServerLevel(),FILTER).filter().rules().stream().anyMatch(r->r.selector().equals("minecraft:iron_sword")&&r.minimum()==1),"Clicking the visible inventory sword adds a real one-item rule"));
            click(c,"filter.umbral.preset");layout(c);capture(c,"umbral-filtro-rules");click(c,"filter.umbral.advanced");layout(c);capture(c,"umbral-filtro-advanced");
            var refreshProbe=new AtomicReference<Button>();
            c.runOnClient(mc->{var edit=filterField(mc.gui.screen(),0);refreshProbe.set((Button)mc.gui.screen().children().getFirst());edit.setValue("minecraft:iron_sord");filterField(mc.gui.screen(),1).setValue("2");mc.gui.screen().setFocused(edit);edit.moveCursorTo(16,false);});
            c.waitFor(mc->!mc.gui.screen().children().contains(refreshProbe.get()));c.runOnClient(mc->{var edit=filterField(mc.gui.screen(),0);check(mc.gui.screen().getFocused()==edit&&edit.getValue().equals("minecraft:iron_sord")&&edit.getCursorPosition()==16&&edit.getHighlighted().isEmpty(),"Polling preserves the edited field, text and collapsed mid-string caret");});
            c.getInput().typeChars("w");c.runOnClient(mc->{var edit=filterField(mc.gui.screen(),0);check(edit.getValue().equals("minecraft:iron_sword")&&edit.getCursorPosition()==17&&edit.getHighlighted().isEmpty(),"Typing after telemetry inserts at the caret and preserves the identifier suffix");});
            c.runOnClient(mc->{var count=filterField(mc.gui.screen(),1);refreshProbe.set((Button)mc.gui.screen().children().getFirst());count.setValue("12");mc.gui.screen().setFocused(count);count.moveCursorTo(0,false);count.moveCursorTo(1,true);});
            c.waitFor(mc->!mc.gui.screen().children().contains(refreshProbe.get()));c.runOnClient(mc->{var count=filterField(mc.gui.screen(),1);check(mc.gui.screen().getFocused()==count&&count.getValue().equals("12")&&count.getCursorPosition()==1&&count.getHighlighted().equals("1"),"Polling preserves the numeric field and its selected range");});
            c.getInput().typeChars("2");c.runOnClient(mc->{var count=filterField(mc.gui.screen(),1);check(count.getValue().equals("22")&&count.getCursorPosition()==1&&count.getHighlighted().isEmpty(),"Typing replaces only the selected numeric range");});c.getInput().pressKey(InputConstants.KEY_BACKSPACE);c.runOnClient(mc->check(filterField(mc.gui.screen(),1).getValue().equals("2"),"Native backspace leaves the intended two-item threshold"));
            click(c,"filter.umbral.save");world.getServer().runOnServer(s->check(d(conn.getServerLevel(),FILTER).filter().rules().stream().anyMatch(r->r.selector().equals("minecraft:iron_sword")&&r.minimum()==2),"Native form saves an actual threshold rule"));
            click(c,"filter.umbral.test");layout(c);capture(c,"umbral-filtro-test");world.getServer().runOnServer(s->check(d(conn.getServerLevel(),FILTER).filter().latest().test()&&d(conn.getServerLevel(),FILTER).filter().latest().outcome()==Inspection.Outcome.MATCH,"Native owner-test detects six TNT without exemption"));
            click(c,"filter.umbral.tab.1");layout(c);capture(c,"umbral-filtro-exceptions");click(c,"filter.umbral.tab.0");click(c,"filter.umbral.mode.inform");layout(c);capture(c,"umbral-filtro-intentions");click(c,"filter.umbral.intent.access");world.getServer().runOnServer(s->check(d(conn.getServerLevel(),FILTER).filter().mode()==FilterState.Mode.ACCESS,"Explicit entry choice selects the intended mode directly"));click(c,"filter.umbral.arm");c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);
            var flag=OUTPUT.resolve("filter-recording.flag");try{Files.createDirectories(OUTPUT);Files.writeString(flag,"FILTRO actual inspection and scan sweep");}catch(Exception e){throw new RuntimeException(e);}
            try{
                world.getServer().runOnServer(s->visitor.get().setPos(.5,-60,.5));c.waitTicks(12);world.getServer().runOnServer(s->{var l=conn.getServerLevel();check(d(l,FILTER).filter().latest().name().equals("Visitante"),"Actual visitor crossing is recorded");check(AccessService.present(visitor.get(),d(l,READER),card.get())==AccessService.Result.SCAN_BLOCKED,"Real issued pass cannot bypass TNT scan");check(!d(l,DOOR).getBlockState().getValue(SecurityDoorBlock.OPEN),"Restricted entry keeps real door closed");});
                c.runOnClient(mc->{if(!mc.gui.hud.isHidden())mc.gui.hud.toggle();mc.options.fov().set(55);});world.getServer().runCommand("tp "+playerName+" 2.7 -60 -4.2 0 0");conn.waitForClientboundPackets();aim(c,new Vec3(.5,-58.7,.5));c.waitTicks(5);capture(c,"umbral-filtro-restricted");
                world.getServer().runOnServer(s->visitor.get().setPos(.5,-60,-5));c.waitTicks(25);
                world.getServer().runOnServer(s->visitor.get().setPos(.5,-60,.5));c.waitTicks(7);world.getServer().runOnServer(s->visitor.get().setPos(.5,-60,-5));conn.waitForClientboundPackets();capture(c,"umbral-filtro-laser-alert");c.waitTicks(35);capture(c,"umbral-filtro-laser-idle");opticalFeedback();
                c.waitTicks(4);world.getServer().runOnServer(s->visitor.get().setPos(.5,-60,-5));c.waitTicks(8);capture(c,"umbral-filtro-arch");world.getServer().runOnServer(s->{var l=conn.getServerLevel();l.setBlockAndUpdate(new BlockPos(-2,-60,1),UmbralContent.CABLE.defaultBlockState());l.setBlockAndUpdate(new BlockPos(-2,-60,0),UmbralContent.CABLE.defaultBlockState());});c.waitTicks(8);world.getServer().runOnServer(s->check(NetworkService.inspect(conn.getServerLevel(),d(conn.getServerLevel(),FILTER)).status()==NetworkService.Status.WIRED,"Physical cable route reaches scanner base"));capture(c,"umbral-filtro-cable");
                world.getServer().runOnServer(s->{visitor.get().setPos(.5,-60,.5);visitor.get().getInventory().setItem(0,ItemStack.EMPTY);check(AccessService.present(visitor.get(),d(conn.getServerLevel(),READER),card.get())==AccessService.Result.GRANTED,"Clear visitor and valid pass opens real door");});c.waitTicks(15);capture(c,"umbral-filtro-clear-entry");
            }finally{try{Files.deleteIfExists(flag);}catch(Exception e){throw new RuntimeException(e);}}
            world.getServer().runOnServer(s->{visitor.get().setPos(.5,-60,-3);visitor.get().getInventory().setItem(0,new ItemStack(Items.TNT,3));});c.runOnClient(mc->{if(mc.gui.hud.isHidden())mc.gui.hud.toggle();mc.options.fov().set(60);});world.getServer().runCommand("tp "+playerName+" -0.5 -60 -2.6 0 0");conn.waitForClientboundPackets();useFilter(c);click(c,"filter.umbral.tab.2");layout(c);capture(c,"umbral-filtro-history");
            c.getInput().resizeWindow(640,480);c.waitTicks(5);layout(c);capture(c,"umbral-filtro-small");click(c,"filter.umbral.tab.0");click(c,"filter.umbral.advanced");layout(c);capture(c,"umbral-filtro-rules-small");click(c,"filter.umbral.basic");layout(c);capture(c,"umbral-filtro-basic-small");
            c.runOnClient(mc->check(mc.gui.screen().children().stream().noneMatch(v->v instanceof net.minecraft.client.gui.components.EditBox),"Small basic view hides IDs and thresholds"));
            for(int n=0;n<3;n++)inventoryNext(c);layout(c);capture(c,"umbral-filtro-inventory-last-page");c.runOnClient(mc->check(mc.gui.screen().children().stream().filter(v->v instanceof Button).map(v->(Button)v).anyMatch(b->b.getX()>mc.gui.screen().width/2&&b.getMessage().getString().equals(">")&&!b.active),"Small inventory pages all 36 slots and stops at the last page"));
            var en=c.computeOnClient(mc->{mc.getLanguageManager().setSelected("en_us");mc.options.languageCode="en_us";return mc.reloadResourcePacks();});c.waitFor(mc->en.isDone()&&mc.gui.overlay()==null,1000);layout(c);capture(c,"umbral-filtro-english-small");click(c,"filter.umbral.mode.access");layout(c);capture(c,"umbral-filtro-intentions-english-small");click(c,"filter.umbral.mode_back");c.getInput().resizeWindow(1280,800);var es=c.computeOnClient(mc->{mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->es.isDone()&&mc.gui.overlay()==null,1000);c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);
            world.getServer().runOnServer(s->NexoProtocol.open(conn.getServerPlayer(),d(conn.getServerLevel(),NEXO)));c.waitForScreen(NexoScreen.class);click(c,"nexo.umbral.history");capture(c,"umbral-filtro-nexo-notice");click(c,"nexo.umbral.devices");selectNexo(c,"FILTRO");capture(c,"umbral-filtro-nexo-status");click(c,"nexo.umbral.action.configure_filter");c.waitForScreen(FilterScreen.class);c.clickScreenButton("?");c.waitForScreen(ManualScreen.class);capture(c,"umbral-filtro-manual");c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(FilterScreen.class);c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(NexoScreen.class);c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);
            world.getServer().runOnServer(s->{var f=d(conn.getServerLevel(),FILTER);FilterService.arm(f,false);FilterService.mode(f,FilterState.Mode.INFORM);check(FilterService.unlink(f).equals("door_unlinked"),"Owner can explicitly remove inspection association");FilterProtocol.open(conn.getServerPlayer(),f,f);});c.waitForScreen(FilterScreen.class);click(c,"filter.umbral.mode.inform");layout(c);capture(c,"umbral-filtro-mode-needs-door");
            c.runOnClient(mc->check(mc.gui.screen().children().stream().filter(v->v instanceof Button).map(v->(Button)v).anyMatch(b->b.getMessage().getString().equals(Component.translatable("filter.umbral.intent.access").getString())&&!b.active),"Entry choice explains and disables the missing-door setup"));click(c,"filter.umbral.mode_back");c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);
            world.getServer().runOnServer(s->s.getPlayerList().remove(visitor.get()));
            try{Files.writeString(OUTPUT.resolve("filter-proof.json"),"{\"version\":\"1.6.1\",\"actual_frame_parts\":7,\"visitor\":\"Visitante\",\"restricted_item\":\"minecraft:tnt\",\"restricted_count\":3,\"valid_card_with_tnt\":\"SCAN_BLOCKED\",\"clear_valid_card\":\"GRANTED\",\"native_ui\":true,\"small_es_en\":true,\"basic_inventory_rule\":true,\"advanced_focus_preserved\":true,\"explicit_modes\":true,\"small_inventory_paging\":true,\"missing_door_choice_disabled\":true}");}catch(Exception e){throw new RuntimeException(e);}
        }finally{c.runOnClient(mc->{mc.options.fov().set(70);if(mc.gui.hud.isHidden())mc.gui.hud.toggle();});}
    }
}
