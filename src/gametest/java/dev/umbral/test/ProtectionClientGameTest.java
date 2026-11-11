// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.client.*;
import dev.umbral.protection.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.lang.reflect.Field;

@SuppressWarnings("UnstableApiUsage")
public final class ProtectionClientGameTest implements FabricClientGameTest {
    private static final Path OUTPUT=Path.of("../../visual-checks/1.6.1");
    private static final BlockPos CORE=new BlockPos(-6,-60,-2),NEXO=new BlockPos(-5,-58,5);
    private static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
    private static Object field(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}}
    private static void capture(ClientGameTestContext c,String name){c.getInput().setCursorPos(2,2);c.waitTicks(3);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    private static void click(ClientGameTestContext c,String key){c.waitFor(mc->mc.gui.screen()!=null&&mc.gui.screen().children().stream().anyMatch(v->v instanceof Button b&&b.active&&b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable(key).getString())));c.clickScreenButton(key);c.waitTicks(6);}
    private static void layout(ClientGameTestContext c){c.runOnClient(mc->{var s=mc.gui.screen();var buttons=s.children().stream().filter(v->v instanceof Button).map(v->(Button)v).toList();for(var a:buttons){check(a.getX()>=0&&a.getY()>=0&&a.getRight()<=s.width&&a.getBottom()<=s.height,"Protection controls fit viewport");for(var b:buttons)if(a!=b)check(a.getRight()<=b.getX()||b.getRight()<=a.getX()||a.getBottom()<=b.getY()||b.getBottom()<=a.getY(),"Protection controls never overlap");}});}
    private static double[] point(ClientGameTestContext c,double wx,double wz){return c.computeOnClient(mc->{var s=mc.gui.screen();var map=field(s,"map");double cx=(double)field(map,"centerX"),cz=(double)field(map,"centerZ");int cells=(int)field(map,"cells"),x=(int)field(s,"mx"),y=(int)field(s,"my"),w=(int)field(s,"mw"),h=(int)field(s,"mh");double scale=Math.min(w,h)/(double)cells;double guiScale=mc.getWindow().getGuiScale();return new double[]{(x+w/2d+(wx-cx)*scale)*guiScale,(y+h/2d+(wz-cz)*scale)*guiScale};});}
    private static void rectangle(ClientGameTestContext c,int x0,int z0,int x1,int z1){var a=point(c,x0+.2,z0+.2);var b=point(c,x1+.8,z1+.8);c.getInput().setCursorPos(a[0],a[1]);c.getInput().holdMouse(0);c.waitTicks(1);c.getInput().moveCursor(b[0]-a[0],b[1]-a[1]);c.waitTicks(2);c.getInput().releaseMouse(0);c.waitTicks(3);}
    private static void panTo(ClientGameTestContext c,double x,double z){
        var a=point(c,x,z);
        var mid=c.computeOnClient(mc->{var screen=mc.gui.screen();double scale=mc.getWindow().getGuiScale();return new double[]{((int)field(screen,"mx")+(int)field(screen,"mw")/2d)*scale,((int)field(screen,"my")+(int)field(screen,"mh")/2d)*scale};});
        c.getInput().setCursorPos(mid[0],mid[1]);
        // Minecraft accumulates pointer motion until the next frame. Let the
        // positioning move finish before pressing, so it is not part of the drag.
        c.waitTicks(1);
        c.getInput().holdMouse(1);c.waitTicks(1);c.getInput().moveCursor(mid[0]-a[0],mid[1]-a[1]);c.waitTicks(2);c.getInput().releaseMouse(1);c.waitTicks(2);
        c.runOnClient(mc->{var map=field(mc.gui.screen(),"map");double actualX=(double)field(map,"centerX"),actualZ=(double)field(map,"centerZ");check(Math.abs(actualX-x)<.05&&Math.abs(actualZ-z)<.05,"Native pan reaches room seed: expected "+x+","+z+"; actual "+actualX+","+actualZ);});
    }
    private static void contourPoint(ClientGameTestContext c,double x,double z){var p=point(c,x+.2,z+.2);c.getInput().setCursorPos(p[0],p[1]);c.getInput().pressMouse(0);c.waitTicks(2);}
    private static void commit(ClientGameTestContext c){click(c,"protection.umbral.apply");c.waitFor(mc->mc.gui.screen()!=null&&mc.gui.screen().getClass().getSimpleName().equals("Review"));click(c,"protection.umbral.confirm");c.waitForScreen(ProtectionScreen.class);}
    private static void name(ClientGameTestContext c,String label){click(c,"protection.umbral.apply");c.runOnClient(mc->{var edit=(EditBox)mc.gui.screen().children().stream().filter(v->v instanceof EditBox).findFirst().orElseThrow();edit.setValue(label);});click(c,"protection.umbral.confirm");c.waitForScreen(ProtectionScreen.class);c.waitTicks(6);}
    private static void height(ClientGameTestContext c,int floor,int roof){click(c,"protection.umbral.height");c.runOnClient(mc->{var edits=mc.gui.screen().children().stream().filter(v->v instanceof EditBox).map(v->(EditBox)v).toList();edits.get(0).setValue(Integer.toString(floor));edits.get(1).setValue(Integer.toString(roof));});click(c,"protection.umbral.apply");c.waitForScreen(ProtectionScreen.class);}
    private static void aim(ClientGameTestContext c,Vec3 target){var a=c.computeOnClient(mc->{var v=target.subtract(mc.player.getEyePosition());return new float[]{(float)Math.toDegrees(Math.atan2(-v.x,v.z)),(float)-Math.toDegrees(Math.atan2(v.y,v.horizontalDistance()))};});c.getInput().lookAt(a[0],a[1]);}
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);var reload=c.computeOnClient(mc->{mc.options.renderDistance().set(4);mc.options.simulationDistance().set(5);mc.options.guiScale().set(2);mc.resizeGui();mc.options.setCameraType(CameraType.FIRST_PERSON);mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->reload.isDone()&&mc.gui.overlay()==null,1000);
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("gamemode creative @a");world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server->{var level=conn.getServerLevel();var p=conn.getServerPlayer();
                for(int x=-12;x<=12;x++)for(int z=-10;z<=10;z++)level.setBlockAndUpdate(new BlockPos(x,-61,z),Blocks.SMOOTH_STONE.defaultBlockState());
                // A real asymmetric house, side workshop and a taller section for floor/height review.
                for(int x=-4;x<=2;x++)for(int z=-2;z<=4;z++)for(int y=-60;y<=-56;y++)if(x==-4||x==2||z==-2||z==4||y==-56)level.setBlockAndUpdate(new BlockPos(x,y,z),(y==-56?Blocks.DARK_OAK_PLANKS:Blocks.QUARTZ_BLOCK).defaultBlockState());
                for(int x=2;x<=5;x++)for(int z=1;z<=5;z++)for(int y=-60;y<=-56;y++)if(x==2||x==5||z==1||z==5||y==-56)level.setBlockAndUpdate(new BlockPos(x,y,z),(y==-56?Blocks.COPPER_BLOCK.weathering().unaffected():Blocks.QUARTZ_BLOCK).defaultBlockState());
                for(int y=-59;y<=-58;y++)for(int x=-2;x<=0;x++)level.setBlockAndUpdate(new BlockPos(x,y,-2),Blocks.GLASS.defaultBlockState());
                for(int y=-55;y<=-50;y++)for(int x=0;x<=2;x++)for(int z=2;z<=4;z++)if(x==0||x==2||z==2||z==4||y==-50)level.setBlockAndUpdate(new BlockPos(x,y,z),(y==-50?Blocks.COPPER_BLOCK.weathering().unaffected():Blocks.QUARTZ_BLOCK).defaultBlockState());
                for(int y=-60;y<=-56;y++)for(int x=-7;x<=-5;x++)level.setBlockAndUpdate(new BlockPos(x,y,6),Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                level.setBlockAndUpdate(CORE,UmbralContent.CORE.defaultBlockState());var core=(DeviceBlockEntity)level.getBlockEntity(CORE);core.initializeOwner(p.getUUID(),"Casa · Núcleo");
                level.setBlockAndUpdate(NEXO,UmbralContent.NEXO.defaultBlockState());var nexo=(DeviceBlockEntity)level.getBlockEntity(NEXO);nexo.initializeOwner(p.getUUID(),"NEXO · Protección");nexo.bind(p.getUUID(),core);
                p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.CONFIGURATOR));p.getInventory().setItem(1,new ItemStack(UmbralContent.PROTECTION_MATRIX,4));
            });
            world.getServer().runCommand("tp @a -6 -60 -5 0 0");conn.waitForClientboundPackets();c.getInput().lookAt(180,-60);c.getInput().pressKey(o->o.keyUse);c.waitForScreen(ProtectionScreen.class);click(c,"protection.umbral.advanced");layout(c);capture(c,"umbral-protection-empty");
            click(c,"protection.umbral.install");world.getServer().runOnServer(server->check(ProtectionData.get(conn.getServerLevel()).capacity(conn.getServerPlayer().getUUID())==2048&&conn.getServerPlayer().getInventory().getItem(1).getCount()==3,"Native install consumes one real matrix"));
            click(c,"protection.umbral.world");c.waitForScreen(null);
            // Real client block interactions select the two corners while holding the existing tool.
            world.getServer().runCommand("tp @a -5.5 -60 -3.5 0 0");conn.waitForClientboundPackets();c.waitTicks(4);aim(c,new Vec3(-3.9,-60.95,-1.9));c.getInput().pressKey(o->o.keyUse);c.waitTicks(4);
            world.getServer().runCommand("gamemode spectator @a");world.getServer().runCommand("tp @a 3.5 -54 5.5 0 0");world.getServer().runCommand("gamemode creative @a");conn.waitForClientboundPackets();c.waitTicks(4);aim(c,new Vec3(2.1,-55.05,4.1));c.getInput().pressKey(o->o.keyUse);c.waitForScreen(ProtectionScreen.class);
            capture(c,"umbral-protection-world-selection");name(c,"Casa principal");
            world.getServer().runOnServer(server->{var d=ProtectionData.get(conn.getServerLevel());check(d.owned(conn.getServerPlayer().getUUID()).size()==1,"World selection becomes a saved claim after Apply");});
            // Edit the same area through actual pointer input on the floor plan.
            rectangle(c,2,1,5,5);capture(c,"umbral-protection-wing-preview");commit(c);
            click(c,"protection.umbral.add");rectangle(c,0,0,1,1);capture(c,"umbral-protection-courtyard-preview");commit(c);click(c,"protection.umbral.cut");
            height(c,-55,-49);rectangle(c,0,2,2,4);commit(c);capture(c,"umbral-protection-tower-height");
            height(c,-61,-56);click(c,"protection.umbral.rectangle");for(var v:new int[][]{{3,6},{5,6},{5,8},{4,8},{4,7},{3,7}})contourPoint(c,v[0],v[1]);capture(c,"umbral-protection-contour-draft");click(c,"protection.umbral.finish");commit(c);click(c,"protection.umbral.contour");
            height(c,-61,-49);click(c,"protection.umbral.model");layout(c);capture(c,"umbral-protection-model");
            var center=c.computeOnClient(mc->{var s=mc.gui.screen();double scale=mc.getWindow().getGuiScale();return new double[]{((int)field(s,"mx")+(int)field(s,"mw")/2d)*scale,((int)field(s,"my")+(int)field(s,"mh")/2d)*scale};});
            Path flag=OUTPUT.resolve("protection-recording.flag");try{Files.createDirectories(OUTPUT);Files.writeString(flag,"UMBRAL · actual protected-area editor");}catch(java.io.IOException e){throw new RuntimeException(e);}
            try{for(int n=0;n<4;n++){c.getInput().setCursorPos(center[0],center[1]);c.getInput().holdMouse(0);c.getInput().moveCursor(30,0);c.waitTicks(8);c.getInput().releaseMouse(0);c.waitTicks(8);}capture(c,"umbral-protection-model-orbit");}finally{try{Files.deleteIfExists(flag);}catch(java.io.IOException e){throw new RuntimeException(e);}}
            c.getInput().setCursorPos(center[0],center[1]);for(int n=0;n<16;n++){c.getInput().scroll(0,-1);c.waitTicks(1);}c.runOnClient(mc->check((int)field(field(mc.gui.screen(),"map"),"cells")>64,"Overview can display a large protection while terrain sampling stays bounded"));capture(c,"umbral-protection-overview");click(c,"protection.umbral.assistant");click(c,"protection.umbral.center");
            click(c,"protection.umbral.plan");height(c,-60,-57);click(c,"protection.umbral.room_button");rectangle(c,-3,-1,-2,1);name(c,"Taller");capture(c,"umbral-protection-room");
            world.getServer().runOnServer(server->{var d=ProtectionData.get(conn.getServerLevel());check(d.owned(conn.getServerPlayer().getUUID()).stream().anyMatch(r->r.parent().isPresent()),"Room is a subarea rather than another land purchase");});
            click(c,"protection.umbral.assistant");capture(c,"umbral-protection-nexo-assistant");click(c,"gui.done");
            click(c,"protection.umbral.members");capture(c,"umbral-protection-permissions");click(c,"protection.umbral.rules");capture(c,"umbral-protection-room-rules");
            c.runOnClient(mc->{mc.options.guiScale().set(3);mc.resizeGui();});layout(c);capture(c,"umbral-protection-gui3");c.getInput().resizeWindow(640,480);c.waitTicks(4);layout(c);capture(c,"umbral-protection-small");
            var english=c.computeOnClient(mc->{mc.getLanguageManager().setSelected("en_us");mc.options.languageCode="en_us";return mc.reloadResourcePacks();});c.waitFor(mc->english.isDone()&&mc.gui.overlay()==null,1000);layout(c);capture(c,"umbral-protection-english-small");
            c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);c.getInput().resizeWindow(1280,800);var spanish=c.computeOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->spanish.isDone()&&mc.gui.overlay()==null,1000);
            world.getServer().runOnServer(server->conn.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY));world.getServer().runCommand("tp @a -5.5 -59 2.5 0 0");conn.waitForClientboundPackets();c.getInput().lookAt(NEXO);c.getInput().pressKey(o->o.keyUse);c.waitForScreen(NexoScreen.class);click(c,"protection.umbral.areas");c.waitForScreen(ProtectionScreen.class);capture(c,"umbral-protection-from-nexo");
            height(c,-61,-56);panTo(c,3.5,3.5);click(c,"protection.umbral.assistant");click(c,"protection.umbral.detect_room");c.waitForScreen(ProtectionScreen.class);c.waitTicks(8);capture(c,"umbral-protection-detected-room");world.getServer().runOnServer(server->{var p=conn.getServerPlayer();check(ProtectionData.get(conn.getServerLevel()).owned(p.getUUID()).size()==2&&!ProtectionSelections.draft(p).parent().equals(ProtectionData.NONE),"Native room detection creates only an unapplied room draft");});click(c,"protection.umbral.discard");
            c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);
            world.getServer().runOnServer(server->{var p=conn.getServerPlayer();var level=conn.getServerLevel();p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.PROTECTION_MATRIX));var point=new Vec3(-4.8,-58.5,-1.5);level.explode(null,point.x,point.y,point.z,3,Level.ExplosionInteraction.MOB);});
            world.getServer().runCommand("tp @a -7 -57 -7 0 0");conn.waitForClientboundPackets();c.waitTicks(40);aim(c,new Vec3(-1,-58,1));c.runOnClient(mc->{mc.options.fov().set(45);if(!mc.gui.hud.isHidden())mc.gui.hud.toggle();});c.waitTicks(4);capture(c,"umbral-protection-house-after-blast");
            c.runOnClient(mc->{mc.options.fov().set(70);if(mc.gui.hud.isHidden())mc.gui.hud.toggle();});capture(c,"umbral-matrix-item");
        }
    }
}
