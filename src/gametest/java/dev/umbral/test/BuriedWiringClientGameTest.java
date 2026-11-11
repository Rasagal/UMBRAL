// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.mojang.authlib.GameProfile;
import dev.umbral.UmbralContent;
import dev.umbral.access.*;
import dev.umbral.block.*;
import dev.umbral.filter.*;
import dev.umbral.network.NetworkService;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** A working fully cabled entrance, with no relay or radio coverage at either endpoint. */
@SuppressWarnings("UnstableApiUsage")
public final class BuriedWiringClientGameTest implements FabricClientGameTest {
    private static final Path OUTPUT=Path.of("../../visual-checks/1.6.1");
    private static final BlockPos CORE=new BlockPos(-24,-60,0),FILTER=new BlockPos(3,-60,0),DOOR=new BlockPos(4,-60,3),READER=new BlockPos(5,-60,2),CUT=new BlockPos(-20,-62,0);
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static DeviceBlockEntity d(ServerLevel l,BlockPos p){return (DeviceBlockEntity)l.getBlockEntity(p);}
    private static DeviceBlockEntity place(ServerPlayer p,BlockPos at,DeviceBlock block){p.level().setBlockAndUpdate(at,block.defaultBlockState());var d=d((ServerLevel)p.level(),at);d.initializeOwner(p.getUUID(),"");return d;}
    private static void cable(ServerLevel l,BlockPos at){l.setBlockAndUpdate(at,UmbralContent.CABLE.defaultBlockState());}
    private static void capture(ClientGameTestContext c,String name){c.waitTicks(5);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    private static void aim(ClientGameTestContext c,Vec3 at){FilterClientGameTest.aim(c,at);}
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);
        c.runOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();mc.options.renderDistance().set(5);mc.options.fov().set(60);mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);});
        AtomicReference<ServerPlayer> visitor=new AtomicReference<>();AtomicReference<ItemStack> card=new AtomicReference<>();
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("gamemode creative @a");world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server->{
                var l=conn.getServerLevel();var p=conn.getServerPlayer();
                for(int x=-26;x<=8;x++)for(int z=-5;z<=7;z++)l.setBlockAndUpdate(new BlockPos(x,-61,z),(x%3==0?Blocks.POLISHED_DEEPSLATE:Blocks.SMOOTH_STONE).defaultBlockState());
                var core=place(p,CORE,UmbralContent.CORE);cable(l,CORE.below());
                for(int x=-24;x<=5;x++)cable(l,new BlockPos(x,-62,0));
                cable(l,FILTER.below());
                for(int z=1;z<=3;z++)cable(l,new BlockPos(4,-62,z));cable(l,DOOR.below());
                for(int z=1;z<=2;z++)cable(l,new BlockPos(5,-62,z));cable(l,READER.below());
                p.setYRot(0);p.setPos(3.5,-60,-2);p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.FILTER));var floor=FILTER.below();
                UmbralContent.FILTER.asItem().useOn(new UseOnContext(p,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(floor).add(0,.05,0),Direction.UP,floor,false)));
                var f=d(l,FILTER);check(f!=null&&FilterBlock.complete(f),"Real scanner kit fits directly over the preinstalled cable");f.bind(p.getUUID(),core);
                var door=place(p,DOOR,UmbralContent.DOOR);l.setBlockAndUpdate(DOOR.above(),door.getBlockState().setValue(SecurityDoorBlock.HALF,DoubleBlockHalf.UPPER));door.bind(p.getUUID(),core);
                var reader=place(p,READER,UmbralContent.READER);reader.bind(p.getUUID(),core);reader.targetDoor(p.getUUID(),door);
                for(int x=2;x<=6;x++)for(int y=0;y<=3;y++){
                    if(x<3||x>5||y==3)l.setBlockAndUpdate(new BlockPos(x,-60+y,0),Blocks.SMOOTH_STONE.defaultBlockState());
                    if(x!=4||y>=2)l.setBlockAndUpdate(new BlockPos(x,-60+y,3),Blocks.SMOOTH_STONE.defaultBlockState());
                }
                for(int z=1;z<=2;z++)for(int y=0;y<=3;y++)for(int x:new int[]{2,6})l.setBlockAndUpdate(new BlockPos(x,-60+y,z),Blocks.SMOOTH_STONE.defaultBlockState());
                FilterBlock.refreshFrame(l,FILTER,Direction.NORTH);NetworkService.invalidate(l);
                for(var at:List.of(FILTER,DOOR,READER))check(NetworkService.inspect(l,d(l,at)).status()==NetworkService.Status.WIRED,"Direct cable actually reaches "+at);
                check(FilterService.link(p,f,door.asDoorTarget()).equals("door_linked"),"Scanner links to the aligned door");f.filter().rule("minecraft:tnt",1);FilterService.mode(f,FilterState.Mode.ACCESS);check(FilterService.arm(f,true).equals("armed"),"Directly wired scanner arms");
                p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.ACCESS_CARD));check(AccessService.issue(p,reader)==AccessService.Result.ISSUED,"Actual physical pass is issued");
                for(int n=0;n<36;n++)if(p.getInventory().getItem(n).has(UmbralContent.CARD_ID))card.set(p.getInventory().getItem(n).copy());p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"CableTester"),false);var guest=new ServerPlayer(server,l,cookie.gameProfile(),cookie.clientInformation()){@Override public GameType gameMode(){return GameType.SURVIVAL;}@Override public boolean isClientAuthoritative(){return false;}};
                var connection=new Connection(PacketFlow.SERVERBOUND);new EmbeddedChannel(connection);server.getPlayerList().placeNewPlayer(connection,guest,cookie);guest.setPos(8,-60,-3);visitor.set(guest);p.setGameMode(GameType.SPECTATOR);
            });conn.waitForClientboundPackets();c.waitTicks(25);
            c.runOnClient(mc->{if(!mc.gui.hud.isHidden())mc.gui.hud.toggle();});
            world.getServer().runOnServer(s->conn.getServerPlayer().teleportTo(4.5,-60,-5));conn.waitForClientboundPackets();aim(c,new Vec3(4.5,-58.8,1));conn.waitForChunksRender();capture(c,"umbral-cable-buried-entrance");
            var flag=OUTPUT.resolve("buried-recording.flag");try{Files.createDirectories(OUTPUT);Files.writeString(flag,"Direct cable, full walls and sealed metal thresholds");}catch(Exception e){throw new RuntimeException(e);}
            try{
                world.getServer().runOnServer(s->{var l=conn.getServerLevel();l.setBlockAndUpdate(CUT,Blocks.AIR.defaultBlockState());NetworkService.invalidate(l);for(var at:List.of(FILTER,DOOR,READER))check(!NetworkService.inspect(l,d(l,at)).online(),"Cutting the real trunk interrupts the far endpoints without radio fallback");});c.waitTicks(23);capture(c,"umbral-cable-buried-offline");
                world.getServer().runOnServer(s->{var l=conn.getServerLevel();cable(l,CUT);NetworkService.invalidate(l);for(var at:List.of(FILTER,DOOR,READER))check(NetworkService.inspect(l,d(l,at)).status()==NetworkService.Status.WIRED,"Replacing the trunk restores wired operation");visitor.get().getInventory().setItem(0,new ItemStack(Items.TNT,3));visitor.get().setPos(4.5,-60,.5);});c.waitTicks(10);
                world.getServer().runOnServer(s->{var l=conn.getServerLevel();check(AccessService.present(visitor.get(),d(l,READER),card.get())==AccessService.Result.SCAN_BLOCKED,"Buried wiring preserves TNT inspection and real access rules");visitor.get().getInventory().setItem(0,ItemStack.EMPTY);check(AccessService.present(visitor.get(),d(l,READER),card.get())==AccessService.Result.GRANTED,"Clear visitor's physical card opens the cabled door");visitor.get().setPos(8,-60,-3);});c.waitTicks(16);capture(c,"umbral-cable-buried-open");
                world.getServer().runOnServer(s->{var guest=visitor.get();guest.setPos(4.5,-60,2.1);guest.setOnGround(true);for(int n=0;n<14;n++)guest.move(MoverType.SELF,new Vec3(0,-.08,.18));check(guest.getZ()>4.2,"A survival visitor physically steps over the thin threshold through the open leaves");guest.setPos(8,-60,6);});
            }finally{try{Files.deleteIfExists(flag);}catch(Exception e){throw new RuntimeException(e);}}
            world.getServer().runOnServer(s->conn.getServerPlayer().teleportTo(4.5,-60,6.8));conn.waitForClientboundPackets();aim(c,new Vec3(4.5,-59,3.5));capture(c,"umbral-cable-buried-door");
            world.getServer().runOnServer(s->{var l=conn.getServerLevel();for(int x=0;x<=6;x++)for(int y=-62;y<=-61;y++)l.setBlockAndUpdate(new BlockPos(x,y,-1),Blocks.AIR.defaultBlockState());conn.getServerPlayer().teleportTo(1,-63.2,-3.6);});conn.waitForClientboundPackets();aim(c,new Vec3(3.5,-61.3,.5));capture(c,"umbral-cable-buried-cutaway");
            world.getServer().runOnServer(s->{var l=conn.getServerLevel();for(int x=0;x<=6;x++)l.setBlockAndUpdate(new BlockPos(x,-61,-1),(x%3==0?Blocks.POLISHED_DEEPSLATE:Blocks.SMOOTH_STONE).defaultBlockState());s.getPlayerList().remove(visitor.get());});
            try{Files.writeString(OUTPUT.resolve("buried-wiring-proof.json"),"{\"version\":\"1.6.1\",\"endpoints\":3,\"relays\":0,\"core_distance_blocks\":27,\"signal\":\"WIRED\",\"cut\":\"NO_SIGNAL\",\"restored\":\"WIRED\",\"floor_cover_height\":0.03125,\"real_kit_over_cable\":true,\"tnt\":\"SCAN_BLOCKED\",\"clear_card\":\"GRANTED\",\"survival_walk_over_threshold\":true}");}catch(Exception e){throw new RuntimeException(e);}
        }finally{c.runOnClient(mc->{mc.options.fov().set(70);if(mc.gui.hud.isHidden())mc.gui.hud.toggle();});}
    }
}
