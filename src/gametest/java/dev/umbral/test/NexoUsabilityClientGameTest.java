// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.client.NexoScreen;
import dev.umbral.installation.NexoProtocol;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;

/** Real snapshots, owner rename and search input while the installation remains live. */
@SuppressWarnings("UnstableApiUsage")
public final class NexoUsabilityClientGameTest implements FabricClientGameTest {
    private static final BlockPos CORE=new BlockPos(-3,-60,1),CAMERA=new BlockPos(0,-60,0),CENTELLA=new BlockPos(3,-60,0);
    private static final Path OUTPUT=Path.of("../../visual-checks",System.getenv().getOrDefault("UMBRAL_CAPTURE_VERSION","1.6.1"));
    private static DeviceBlockEntity d(ServerLevel l,BlockPos p){return (DeviceBlockEntity)l.getBlockEntity(p);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void click(ClientGameTestContext c,String key){
        c.waitTicks(5);
        c.waitFor(mc->mc.gui.screen()!=null&&mc.gui.screen().children().stream().anyMatch(v->v instanceof Button b&&b.active&&b.getMessage().getString().equals(Component.translatable(key).getString())));
        c.clickScreenButton(key);c.waitTicks(7);
    }
    private static EditBox edit(net.minecraft.client.gui.screens.Screen s){return s.children().stream().filter(v->v instanceof EditBox).map(v->(EditBox)v).findFirst().orElseThrow();}
    private static void input(ClientGameTestContext c,String value,int caret){c.runOnClient(mc->{edit(mc.gui.screen()).setValue(value);var field=edit(mc.gui.screen());mc.gui.screen().setFocused(field);field.moveCursorTo(caret,false);});}
    private static void draftSurvives(ClientGameTestContext c,String value,int caret){c.waitTicks(24);c.runOnClient(mc->check(mc.gui.screen().getFocused() instanceof EditBox field&&field.getValue().equals(value)&&field.getCursorPosition()==caret&&field.getHighlighted().isEmpty(),"One-second telemetry preserves the focused field, query and collapsed caret without selecting its suffix"));}
    private static void layout(ClientGameTestContext c){c.runOnClient(mc->{var s=mc.gui.screen();var buttons=s.children().stream().filter(v->v instanceof Button).map(v->(Button)v).toList();for(var a:buttons){check(a.getX()>=0&&a.getY()>=0&&a.getRight()<=s.width&&a.getBottom()<=s.height,"NEXO controls fit the viewport");for(var b:buttons)if(a!=b)check(a.getRight()<=b.getX()||b.getRight()<=a.getX()||a.getBottom()<=b.getY()||b.getBottom()<=a.getY(),"NEXO buttons never overlap");}});}
    private static void capture(ClientGameTestContext c,String name){c.getInput().setCursorPos(2,2);c.waitTicks(3);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);
        var reload=c.computeOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();mc.options.renderDistance().set(4);mc.options.simulationDistance().set(5);mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->reload.isDone()&&mc.gui.overlay()==null,1000);
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("gamemode creative @a");
            world.getServer().runOnServer(server->{var l=conn.getServerLevel();var p=conn.getServerPlayer();for(int x=-5;x<=5;x++)for(int z=-3;z<=3;z++)l.setBlockAndUpdate(new BlockPos(x,-61,z),Blocks.SMOOTH_STONE.defaultBlockState());
                l.setBlockAndUpdate(CORE,UmbralContent.CORE.defaultBlockState());var core=d(l,CORE);core.initializeOwner(p.getUUID(),"Núcleo casa");
                l.setBlockAndUpdate(CAMERA,UmbralContent.CAMERA.defaultBlockState());d(l,CAMERA).initializeOwner(p.getUUID(),"Cámara jardín");d(l,CAMERA).bind(p.getUUID(),core);
                l.setBlockAndUpdate(CENTELLA,UmbralContent.CENTELLA.defaultBlockState());d(l,CENTELLA).initializeOwner(p.getUUID(),"CENTELLA patio");d(l,CENTELLA).bind(p.getUUID(),core);
                for(int n=1;n<=4;n++){var at=new BlockPos(n-2,-60,3);l.setBlockAndUpdate(at,UmbralContent.SENSOR.defaultBlockState());d(l,at).initializeOwner(p.getUUID(),"Zona "+n);d(l,at).bind(p.getUUID(),core);}
                var rows=NexoProtocol.snapshot(p,core,0,false,"ready").rows();check(rows.size()==7,"The live installation fixture contains exactly seven discoverable devices");check(rows.stream().filter(NexoScreen::needsAttention).count()==1,"Only the empty CENTELLA needs attention");
            });world.getServer().runCommand("tp @a -1.5 -60 -2.5 0 0");conn.waitForClientboundPackets();c.waitTicks(12);conn.waitForChunksRender();c.waitFor(mc->mc.level!=null&&mc.level.getBlockEntity(CORE) instanceof DeviceBlockEntity device&&device.kind()==DeviceKind.CORE);
            world.getServer().runOnServer(server->NexoProtocol.open(conn.getServerPlayer(),d(conn.getServerLevel(),CORE)));c.waitForScreen(NexoScreen.class);c.waitTicks(4);c.runOnClient(mc->check(mc.gui.screen() instanceof NexoScreen,"NEXO stays open after the first validated client ticks"));layout(c);
            c.runOnClient(mc->{check(mc.gui.screen().children().stream().anyMatch(v->v instanceof Button b&&b.getMessage().getString().equals("CENTELLA patio")),"Empty CENTELLA is visible in the default Attention filter");check(mc.gui.screen().children().stream().noneMatch(v->v instanceof Button b&&b.getMessage().getString().equals("Cámara jardín")),"A healthy camera does not drown out actionable issues");});capture(c,"umbral-nexo-attention");
            click(c,"nexo.umbral.list.all");capture(c,"umbral-nexo-all");
            click(c,">");c.waitTicks(24);c.runOnClient(mc->check(mc.gui.screen().children().stream().anyMatch(v->v instanceof Button b&&b.getMessage().getString().equals("Zona 4"))&&mc.gui.screen().children().stream().noneMatch(v->v instanceof Button b&&b.getMessage().getString().equals("Cámara jardín")),"Live snapshots retain the current device page"));input(c,"CÁMARA",3);draftSurvives(c,"CÁMARA",3);
            c.runOnClient(mc->{edit(mc.gui.screen()).insertText("X");check(edit(mc.gui.screen()).getValue().equals("CÁMXARA"),"Typing after a telemetry refresh inserts at the caret without deleting the suffix");});
            input(c,"CÁMARA",1);c.runOnClient(mc->edit(mc.gui.screen()).moveCursorTo(4,true));c.waitTicks(24);
            c.runOnClient(mc->{var field=edit(mc.gui.screen());check(mc.gui.screen().getFocused()==field&&field.getCursorPosition()==4&&field.getHighlighted().equals("ÁMA"),"An explicit search selection also survives live snapshots");field.insertText("X");check(edit(mc.gui.screen()).getValue().equals("CXRA"),"Typing replaces only the intended selected range");});input(c,"CÁMARA",3);
            c.runOnClient(mc->check(mc.gui.screen().children().stream().anyMatch(v->v instanceof Button b&&b.getMessage().getString().equals("Cámara jardín")),"Search matches names and device kinds without case or accent friction"));capture(c,"umbral-nexo-search");
            input(c,"sin coincidencias",4);world.getServer().runOnServer(server->check(d(conn.getServerLevel(),CAMERA).rename(conn.getServerPlayer().getUUID(),"Cámara taller"),"Fixture owner renames a live camera"));draftSurvives(c,"sin coincidencias",4);input(c,"",0);
            click(c,"Cámara taller");click(c,"…");c.waitFor(mc->NexoScreen.underlying(mc.gui.screen()) instanceof NexoScreen&&!(mc.gui.screen() instanceof NexoScreen));input(c,"Vigía del jardín",5);draftSurvives(c,"Vigía del jardín",5);layout(c);capture(c,"umbral-nexo-device-name");
            c.runOnClient(mc->{edit(mc.gui.screen()).insertText(" nueva");check(edit(mc.gui.screen()).getValue().equals("Vigía nueva del jardín"),"Typing into the live rename form retains the name suffix");});input(c,"Vigía del jardín",0);c.runOnClient(mc->edit(mc.gui.screen()).moveCursorTo(5,true));c.getInput().resizeWindow(1000,720);c.waitTicks(6);
            c.runOnClient(mc->{var field=edit(mc.gui.screen());check(field.getValue().equals("Vigía del jardín")&&field.getHighlighted().equals("Vigía"),"Resizing the rename form preserves its draft and explicit selection");field.insertText("Vigía");check(field.getValue().equals("Vigía del jardín")&&field.getHighlighted().isEmpty(),"Replacing the selected name prefix preserves the remaining name");});c.getInput().resizeWindow(1280,800);c.waitTicks(6);layout(c);
            click(c,"nexo.umbral.rename.save");c.waitForScreen(NexoScreen.class);world.getServer().runOnServer(server->check(d(conn.getServerLevel(),CAMERA).label().equals("Vigía del jardín"),"The native form updates the real owned block"));
            input(c,"centella",3);click(c,"CENTELLA patio");click(c,"…");click(c,"nexo.umbral.action.ammo_preference");world.getServer().runOnServer(server->check(d(conn.getServerLevel(),CENTELLA).turret().preferenceId().equals("copper")&&d(conn.getServerLevel(),CENTELLA).turret().count()==0&&!d(conn.getServerLevel(),CENTELLA).turret().armed(),"The NEXO selector changes resupply policy without creating rounds or arming"));click(c,"gui.done");c.waitForScreen(NexoScreen.class);
            click(c,"nexo.umbral.history");layout(c);click(c,"nexo.umbral.devices");layout(c);capture(c,"umbral-nexo-overview");
            c.getInput().resizeWindow(640,480);c.runOnClient(mc->{mc.options.guiScale().set(3);mc.resizeGui();});c.waitTicks(6);layout(c);capture(c,"umbral-nexo-small-es");
            var en=c.computeOnClient(mc->{mc.getLanguageManager().setSelected("en_us");mc.options.languageCode="en_us";return mc.reloadResourcePacks();});c.waitFor(mc->en.isDone()&&mc.gui.overlay()==null,1000);layout(c);capture(c,"umbral-nexo-small-en");
            input(c,"centella",3);draftSurvives(c,"centella",3);click(c,"CENTELLA patio");click(c,"…");layout(c);capture(c,"umbral-nexo-device-small-en");
            try{Files.createDirectories(OUTPUT);Files.writeString(OUTPUT.resolve("nexo-usability-proof.json"),"{\"version\":\""+System.getenv().getOrDefault("UMBRAL_CAPTURE_VERSION","1.6.1")+"\",\"attention_default\":true,\"accent_search\":true,\"search_caret_survives_live_snapshots\":true,\"caret_insertion_preserves_suffix\":true,\"selection_survives_live_snapshots\":true,\"rename_selection_survives_resize\":true,\"no_result_query_refreshes\":true,\"rename_draft_survives_live_snapshots\":true,\"real_rename\":true,\"nexo_ammo_choice\":\"copper\",\"small_es_en_gui3\":true}");}catch(java.io.IOException e){throw new RuntimeException(e);}
        }finally{c.getInput().resizeWindow(1280,800);var es=c.computeOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->es.isDone()&&mc.gui.overlay()==null,1000);}
    }
}
