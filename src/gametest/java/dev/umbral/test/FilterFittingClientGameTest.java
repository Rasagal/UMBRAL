// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.filter.FilterService;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import java.nio.file.*;

/** Native before/after presentation and real server + client collision for automatic masonry fittings. */
@SuppressWarnings("UnstableApiUsage")
public final class FilterFittingClientGameTest implements FabricClientGameTest {
    private static final Path OUTPUT=Path.of("../../visual-checks/1.6.1");
    private static final BlockPos ROOT=new BlockPos(-1,-60,0),CORE=new BlockPos(-5,-60,2);
    private static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
    private static DeviceBlockEntity root(ServerLevel l){return (DeviceBlockEntity)l.getBlockEntity(ROOT);}
    private static void capture(ClientGameTestContext c,String name){c.getInput().setCursorPos(2,2);c.runOnClient(mc->mc.gui.toastManager().clear());c.waitTicks(4);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    private static void aim(ClientGameTestContext c,Vec3 target){var a=c.computeOnClient(mc->{var v=target.subtract(mc.player.getEyePosition());return new float[]{(float)Math.toDegrees(Math.atan2(-v.x,v.z)),(float)-Math.toDegrees(Math.atan2(v.y,v.horizontalDistance()))};});c.getInput().lookAt(a[0],a[1]);}
    private static void fits(ServerLevel level,boolean side,boolean top){
        for(var part:FilterBlock.Part.values()){var state=level.getBlockState(FilterBlock.partPos(ROOT,Direction.NORTH,part));check(state.getValue(FilterBlock.SEALED_SIDE)==(side&&(FilterBlock.leftColumn(part)||FilterBlock.rightColumn(part))),"Server side fitting is truthful");check(state.getValue(FilterBlock.SEALED_TOP)==(top&&FilterBlock.topRow(part)),"Server ceiling fitting is truthful");}
        check(FilterBlock.complete(root(level)),"Fitting keeps the seven-part assembly complete");
    }
    private static int laserRows(Path path){
        try{
            var image=javax.imageio.ImageIO.read(path.toFile());int groups=0;boolean previous=false;
            for(int y=0;y<image.getHeight();y++){
                int count=0;for(int x=image.getWidth()*35/100;x<image.getWidth()*65/100;x++){int rgb=image.getRGB(x,y),r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255;if(g>=170&&g-r>=12&&b-r>=4)count++;}
                boolean line=count>=30;if(line&&!previous)groups++;previous=line;
            }return groups;
        }catch(java.io.IOException e){throw new RuntimeException(e);}
    }
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);var reload=c.computeOnClient(mc->{mc.options.renderDistance().set(4);mc.options.simulationDistance().set(5);mc.options.guiScale().set(2);mc.options.fov().set(55);mc.options.setCameraType(CameraType.FIRST_PERSON);mc.resizeGui();return mc.reloadResourcePacks();});c.waitFor(mc->reload.isDone()&&mc.gui.overlay()==null,1000);
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();String name=c.computeOnClient(mc->mc.player.getGameProfile().name());world.getServer().runCommand("gamemode creative @a");world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server->{var level=conn.getServerLevel();var p=conn.getServerPlayer();
                for(int x=-8;x<=7;x++)for(int z=-8;z<=7;z++)level.setBlockAndUpdate(new BlockPos(x,-61,z),(x%3==0?Blocks.POLISHED_DEEPSLATE:Blocks.SMOOTH_STONE).defaultBlockState());
                // The distant rear wall gives the empty arch a real backdrop without providing side support.
                for(int x=-5;x<=5;x++)for(int y=-60;y<=-55;y++)level.setBlockAndUpdate(new BlockPos(x,y,4),(y==-55?Blocks.POLISHED_DEEPSLATE:Blocks.STONE_BRICKS).defaultBlockState());
                level.setBlockAndUpdate(CORE,UmbralContent.CORE.defaultBlockState());var core=(DeviceBlockEntity)level.getBlockEntity(CORE);core.initializeOwner(p.getUUID(),"Núcleo de demostración");p.setPos(-.5,-60,-2.5);p.setYRot(0);p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.FILTER));var floor=ROOT.below();UmbralContent.FILTER.asItem().useOn(new UseOnContext(p,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(floor).add(0,.5,0),Direction.UP,floor,false)));var f=root(level);check(f!=null&&f.bind(p.getUUID(),core),"Real kit places and pairs the arch");f.filter().rule("minecraft:tnt",1);check(FilterService.arm(f,true).equals("armed"),"Original inspection still arms");p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);fits(level,false,false);
            });
            world.getServer().runCommand("tp "+name+" 3.2 -60 -4.4 0 0");conn.waitForClientboundPackets();c.runOnClient(mc->{if(!mc.gui.hud.isHidden())mc.gui.hud.toggle();});aim(c,new Vec3(.5,-58.65,.5));c.waitTicks(25);capture(c,"umbral-filtro-freestanding");
            var flag=OUTPUT.resolve("filter-fitting-recording.flag");try{Files.createDirectories(OUTPUT);Files.writeString(flag,"Automatic fitting against real wall and ceiling");}catch(Exception e){throw new RuntimeException(e);}
            try{
                world.getServer().runOnServer(server->{var level=conn.getServerLevel();for(int x=-4;x<=4;x++)for(int y=-60;y<=-55;y++)if(x< -1||x>1||y>=-57)level.setBlockAndUpdate(new BlockPos(x,y,0),(y==-55||x==-4||x==4?Blocks.POLISHED_DEEPSLATE:Blocks.STONE_BRICKS).defaultBlockState());fits(level,true,true);});
                conn.waitForClientboundPackets();c.waitTicks(8);c.runOnClient(mc->{var state=mc.level.getBlockState(ROOT);check(state.getValue(FilterBlock.SEALED_SIDE),"Client receives the side fitting state");check(state.getCollisionShape(mc.level,ROOT).toAabbs().stream().anyMatch(b->b.contains(1/16d,8/16d,8/16d)),"Rendered fitting has matching client collision");});capture(c,"umbral-filtro-wall-sealed");c.waitTicks(22);
                world.getServer().runOnServer(server->{var level=conn.getServerLevel();level.setBlockAndUpdate(new BlockPos(-2,-59,0),Blocks.AIR.defaultBlockState());check(!level.getBlockState(ROOT.above()).getValue(FilterBlock.SEALED_SIDE),"Removing one brick retracts only its segment");check(level.getBlockState(ROOT).getValue(FilterBlock.SEALED_SIDE),"Remaining bricks retain their fitting");});conn.waitForClientboundPackets();c.waitTicks(7);capture(c,"umbral-filtro-wall-retracted");c.waitTicks(15);
                world.getServer().runOnServer(server->{var level=conn.getServerLevel();level.setBlockAndUpdate(new BlockPos(-2,-59,0),Blocks.STONE_BRICKS.defaultBlockState());level.setBlockAndUpdate(new BlockPos(0,-57,0),Blocks.AIR.defaultBlockState());check(!level.getBlockState(ROOT.east().above(2)).getValue(FilterBlock.SEALED_TOP),"Removing the ceiling retracts its matching cap");});conn.waitForClientboundPackets();c.waitTicks(7);capture(c,"umbral-filtro-ceiling-retracted");c.waitTicks(15);
                world.getServer().runOnServer(server->{var level=conn.getServerLevel();level.setBlockAndUpdate(new BlockPos(0,-57,0),Blocks.STONE_BRICKS.defaultBlockState());fits(level,true,true);check(level.getBlockState(ROOT.east()).isAir()&&level.getBlockState(ROOT.east().above()).isAir(),"The passage remains real air");});conn.waitForClientboundPackets();c.waitTicks(7);capture(c,"umbral-filtro-wall-restored");c.waitTicks(12);
            }finally{try{Files.deleteIfExists(flag);}catch(Exception e){throw new RuntimeException(e);}}
            world.getServer().runCommand("tp "+name+" -2.6 -60 3.5 180 0");conn.waitForClientboundPackets();aim(c,new Vec3(.5,-58.65,.5));c.waitTicks(8);capture(c,"umbral-filtro-wall-back");
            world.getServer().runCommand("tp "+name+" 0.5 -60 -4.4 0 0");conn.waitForClientboundPackets();aim(c,new Vec3(.5,-58.65,.5));c.waitTicks(12);capture(c,"umbral-filtro-laser-front");
            int rows=laserRows(OUTPUT.resolve("umbral-filtro-laser-front.png"));check(rows==5,"Native framebuffer shows five separate laser rows, got "+rows);
            world.getServer().runOnServer(server->check(FilterService.arm(root(conn.getServerLevel()),false).equals("disarmed"),"Filter disarms normally"));conn.waitForClientboundPackets();c.waitTicks(8);capture(c,"umbral-filtro-laser-disabled");check(laserRows(OUTPUT.resolve("umbral-filtro-laser-disabled.png"))==0,"Disarming removes all optical rays");
            world.getServer().runOnServer(server->{check(FilterService.arm(root(conn.getServerLevel()),true).equals("armed"),"Filter rearms normally");conn.getServerLevel().setBlockAndUpdate(CORE,Blocks.AIR.defaultBlockState());});conn.waitForClientboundPackets();c.waitTicks(25);capture(c,"umbral-filtro-laser-offline");check(laserRows(OUTPUT.resolve("umbral-filtro-laser-offline.png"))==0,"Loss of network removes optical rays");

            try{Files.writeString(OUTPUT.resolve("filter-fitting-proof.json"),"{\"version\":\"1.6.1\",\"real_kit\":true,\"side_panels\":6,\"ceiling_panels\":3,\"dynamic_removal\":true,\"central_passage_air\":true,\"client_collision\":true,\"inspection_armed\":true,\"visible_laser_rows\":5,\"lasers_off_when_disarmed\":true,\"lasers_off_when_offline\":true}");}catch(Exception e){throw new RuntimeException(e);}
        }finally{c.runOnClient(mc->{mc.options.fov().set(70);if(mc.gui.hud.isHidden())mc.gui.hud.toggle();});}
    }
}
