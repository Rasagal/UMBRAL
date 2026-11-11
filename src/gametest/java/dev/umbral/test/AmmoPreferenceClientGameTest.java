// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.client.TurretScreen;
import dev.umbral.turret.TurretState;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;

/** Native local console: learned gold, explicit choice, empty magazine and ES/EN small layout. */
@SuppressWarnings("UnstableApiUsage")
public final class AmmoPreferenceClientGameTest implements FabricClientGameTest {
    private static final Path OUTPUT=Path.of("../../visual-checks",System.getenv().getOrDefault("UMBRAL_CAPTURE_VERSION","1.6.1"));
    private static final BlockPos CORE=new BlockPos(-3,-60,1),TARGET=new BlockPos(0,-60,0);
    private static DeviceBlockEntity device(ServerLevel l,BlockPos p){return (DeviceBlockEntity)l.getBlockEntity(p);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void capture(ClientGameTestContext c,String name){c.getInput().setCursorPos(2,2);c.waitTicks(3);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    private static void click(ClientGameTestContext c,String key){c.waitFor(mc->mc.gui.screen() instanceof TurretScreen&&mc.gui.screen().children().stream().anyMatch(v->v instanceof Button b&&b.active&&b.getMessage().getString().equals(Component.translatable(key).getString())));c.clickScreenButton(key);c.waitTicks(6);}
    private static String label(ClientGameTestContext c,TurretState.Preference preference){return c.computeOnClient(mc->Component.translatable("centella.umbral.preference_button",Component.translatable(preference.key()+".short")).getString());}
    private static void waitPreference(ClientGameTestContext c,TurretState.Preference preference){String expected=label(c,preference);c.waitFor(mc->mc.gui.screen()!=null&&mc.gui.screen().children().stream().anyMatch(v->v instanceof Button b&&b.active&&b.getMessage().getString().equals(expected)));}
    private static void layout(ClientGameTestContext c){c.runOnClient(mc->{var s=mc.gui.screen();var buttons=s.children().stream().filter(v->v instanceof Button).map(v->(Button)v).toList();for(var a:buttons){check(a.getX()>=0&&a.getY()>=0&&a.getRight()<=s.width&&a.getBottom()<=s.height,"Preference controls stay inside the viewport");for(var b:buttons)if(a!=b)check(a.getRight()<=b.getX()||b.getRight()<=a.getX()||a.getBottom()<=b.getY()||b.getBottom()<=a.getY(),"Preference controls do not overlap");}});}
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);
        var reload=c.computeOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();mc.options.renderDistance().set(4);mc.options.simulationDistance().set(5);mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->reload.isDone()&&mc.gui.overlay()==null,1000);
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("gamemode creative @a");world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server->{var l=conn.getServerLevel();var p=conn.getServerPlayer();for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)l.setBlockAndUpdate(new BlockPos(x,-61,z),Blocks.SMOOTH_STONE.defaultBlockState());
                l.setBlockAndUpdate(CORE,UmbralContent.CORE.defaultBlockState());device(l,CORE).initializeOwner(p.getUUID(),"NEXO · Reposición");l.setBlockAndUpdate(TARGET,UmbralContent.CENTELLA.defaultBlockState());device(l,TARGET).initializeOwner(p.getUUID(),"CENTELLA · Patio");device(l,TARGET).bind(p.getUUID(),device(l,CORE));p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);p.getInventory().setItem(1,new ItemStack(Items.GOLD_NUGGET,2));
            });world.getServer().runCommand("tp @a 2.5 -60 -2.5 0 0");conn.waitForClientboundPackets();c.getInput().lookAt(TARGET);c.waitTicks(12);conn.waitForChunksRender();c.getInput().pressKey(o->o.keyUse);c.waitForScreen(TurretScreen.class);
            click(c,"centella.umbral.load");waitPreference(c,TurretState.Preference.GOLD);layout(c);capture(c,"umbral-ammo-preference-gold");
            world.getServer().runOnServer(server->check(device(conn.getServerLevel(),TARGET).turret().count()==2&&device(conn.getServerLevel(),TARGET).turret().preference()==TurretState.Preference.GOLD,"Real manual gold teaches a persistent preference"));
            var previous=TurretState.Preference.GOLD;
            for(var next:new TurretState.Preference[]{TurretState.Preference.AUTOMATIC,TurretState.Preference.COPPER,TurretState.Preference.IRON,TurretState.Preference.GOLD}){
                click(c,label(c,previous));waitPreference(c,next);previous=next;
            }
            click(c,"turret.umbral.unload");waitPreference(c,TurretState.Preference.GOLD);
            world.getServer().runOnServer(server->{var p=conn.getServerPlayer();var state=device(conn.getServerLevel(),TARGET).turret();int gold=0;for(int n=0;n<p.getInventory().getContainerSize();n++)if(p.getInventory().getItem(n).is(Items.GOLD_NUGGET))gold+=p.getInventory().getItem(n).getCount();check(state.count()==0&&state.preference()==TurretState.Preference.GOLD&&!state.armed()&&gold==2,"Unload returns the real gold, keeps preference and never arms");});
            capture(c,"umbral-ammo-preference-empty");c.getInput().resizeWindow(640,480);c.waitTicks(6);layout(c);capture(c,"umbral-ammo-preference-small-es");
            var en=c.computeOnClient(mc->{mc.getLanguageManager().setSelected("en_us");mc.options.languageCode="en_us";return mc.reloadResourcePacks();});c.waitFor(mc->en.isDone()&&mc.gui.overlay()==null,1000);waitPreference(c,TurretState.Preference.GOLD);layout(c);capture(c,"umbral-ammo-preference-small-en");
            try{Files.createDirectories(OUTPUT);Files.writeString(OUTPUT.resolve("ammo-preference-proof.json"),"{\"version\":\""+System.getenv().getOrDefault("UMBRAL_CAPTURE_VERSION","1.6.1")+"\",\"manual_gold\":2,\"unload_returned_gold\":2,\"preferred_after_empty\":\"gold\",\"native_choice_cycle\":true,\"small_es_en\":true,\"implicit_arming\":false}");}catch(java.io.IOException e){throw new RuntimeException(e);}
        }finally{c.getInput().resizeWindow(1280,800);var es=c.computeOnClient(mc->{mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->es.isDone()&&mc.gui.overlay()==null,1000);}
    }
}
