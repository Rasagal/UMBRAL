// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.client.DeviceRenderer;
import dev.umbral.network.NetworkService;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Real render extraction, all facing/input combinations and visible attach/remove/restore. */
@SuppressWarnings("UnstableApiUsage")
public final class CableConnectionsClientGameTest implements FabricClientGameTest {
    private static final Path OUTPUT=Path.of("../../visual-checks/1.6.1");
    private static final BlockPos NODE=new BlockPos(0,-60,0);
    private static Object field(Object value,String name){try{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}}
    private static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
    private static List<?> boxes(ClientGameTestContext c){return c.computeOnClient(mc->{var d=(DeviceBlockEntity)mc.level.getBlockEntity(NODE);var r=mc.getBlockEntityRenderDispatcher().<DeviceBlockEntity,DeviceRenderer.State>getRenderer(d);var s=r.createRenderState();r.extractRenderState(d,s,0,Vec3.ZERO,null);return (List<?>)field(field(s,"cables"),"boxes");});}
    private static void capture(ClientGameTestContext c,String name){c.waitTicks(4);var path=Path.of(name);var folder=path.getParent()==null?OUTPUT:OUTPUT.resolve(path.getParent());try{Files.createDirectories(folder);}catch(java.io.IOException e){throw new RuntimeException(e);}c.takeScreenshot(TestScreenshotOptions.of(path.getFileName().toString()).disableCounterPrefix().withDestinationDir(folder));}
    private static DeviceBlockEntity place(ServerLevel level,BlockPos p,Block block,UUID owner){level.setBlockAndUpdate(p,block.defaultBlockState());var d=(DeviceBlockEntity)level.getBlockEntity(p);d.initializeOwner(owner,"");return d;}
    private static void aim(ClientGameTestContext c,Vec3 target){var angles=c.computeOnClient(mc->{var v=target.subtract(mc.player.getEyePosition());return new float[]{(float)Math.toDegrees(Math.atan2(-v.x,v.z)),(float)-Math.toDegrees(Math.atan2(v.y,v.horizontalDistance()))};});c.getInput().lookAt(angles[0],angles[1]);}
    private static BlockPos galleryPos(int n){return new BlockPos(4+(n%5)*4,-60,4+(n/5)*4);}
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);c.runOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();mc.options.renderDistance().set(5);mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);mc.options.fov().set(70);});
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("gamemode spectator @a");world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server->{var level=conn.getServerLevel();for(int x=-8;x<=26;x++)for(int z=-6;z<=22;z++)level.setBlockAndUpdate(new BlockPos(x,-61,z),(x%3==0?Blocks.POLISHED_DEEPSLATE:Blocks.SMOOTH_STONE).defaultBlockState());place(level,NODE,UmbralContent.NODE,conn.getServerPlayer().getUUID());});conn.waitForClientboundPackets();c.waitFor(mc->mc.level.getBlockEntity(NODE) instanceof DeviceBlockEntity);check(boxes(c).isEmpty(),"An apparatus without adjacent cable has no fake lead");
            for(var facing:Direction.Plane.HORIZONTAL)for(var incoming:Direction.values()){
                world.getServer().runOnServer(server->{var level=conn.getServerLevel();for(var direction:Direction.values())level.setBlockAndUpdate(NODE.relative(direction),Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(NODE,UmbralContent.NODE.defaultBlockState().setValue(DeviceBlock.FACING,facing));level.setBlockAndUpdate(NODE.relative(incoming),UmbralContent.CABLE.defaultBlockState());});conn.waitForClientboundPackets();c.waitTicks(3);
                c.runOnClient(mc->{var d=(DeviceBlockEntity)mc.level.getBlockEntity(NODE);var r=mc.getBlockEntityRenderDispatcher().<DeviceBlockEntity,DeviceRenderer.State>getRenderer(d);var s=r.createRenderState();r.extractRenderState(d,s,0,Vec3.ZERO,null);var cable=field(s,"cables");var mesh=(List<?>)field(cable,"boxes");check(!mesh.isEmpty(),"One physical inlet exists for "+facing+"/"+incoming);var gland=mesh.stream().filter(v->((String)field(v,"name")).equals("collar_entrada_0")).findFirst().orElseThrow();double rotation=Math.toRadians((float)field(cable,"rotation"));double x=((float)field(gland,"x0")+(float)field(gland,"x1"))/2d-8,y=((float)field(gland,"y0")+(float)field(gland,"y1"))/2d,z=((float)field(gland,"z0")+(float)field(gland,"z1"))/2d-8;double wx=8+x*Math.cos(rotation)+z*Math.sin(rotation),wz=8-x*Math.sin(rotation)+z*Math.cos(rotation);double[] point={wx,y,wz};int axis=incoming.getAxis()==Direction.Axis.X?0:incoming.getAxis()==Direction.Axis.Y?1:2;for(int n=0;n<3;n++)check(Math.abs(point[n]-(n==axis?(incoming.getAxisDirection()==Direction.AxisDirection.POSITIVE?15.825:.175):(n==1?1.5:8)))<.002,"Gland meets the actual cable face after renderer rotation: "+facing+"/"+incoming);});
            }
            world.getServer().runOnServer(server->{var level=conn.getServerLevel();for(var direction:Direction.values())level.setBlockAndUpdate(NODE.relative(direction),Blocks.AIR.defaultBlockState());var owner=conn.getServerPlayer().getUUID();var core=place(level,new BlockPos(-4,-60,0),UmbralContent.CORE,owner);var node=place(level,NODE,UmbralContent.NODE,owner);node.bind(owner,core);for(int x=-3;x<0;x++)level.setBlockAndUpdate(new BlockPos(x,-60,0),UmbralContent.CABLE.defaultBlockState());NetworkService.invalidate(level);});conn.waitForClientboundPackets();c.waitTicks(6);world.getServer().runOnServer(server->check(NetworkService.inspect(conn.getServerLevel(),(DeviceBlockEntity)conn.getServerLevel().getBlockEntity(NODE)).status()==NetworkService.Status.WIRED,"Visible cable is the actual functional backbone"));
            c.runOnClient(mc->{if(!mc.gui.hud.isHidden())mc.gui.hud.toggle();});world.getServer().runCommand("tp @a -1.7 -60.3 -2.2 0 0");conn.waitForClientboundPackets();aim(c,new Vec3(.05,-59.72,.5));c.waitFor(mc->mc.level.getBlockState(NODE).getValue(DeviceBlock.ONLINE));conn.waitForChunksRender();capture(c,"umbral-cable-node-connected");c.runOnClient(mc->mc.options.fov().set(35));world.getServer().runCommand("tp @a -1.2 -60.2 -1.5 0 0");conn.waitForClientboundPackets();aim(c,new Vec3(-.05,-59.72,.5));conn.waitForChunksRender();capture(c,"umbral-cable-node-detail");c.runOnClient(mc->mc.options.fov().set(70));world.getServer().runCommand("tp @a -1.7 -60.3 -2.2 0 0");conn.waitForClientboundPackets();aim(c,new Vec3(.05,-59.72,.5));
            Path flag=OUTPUT.resolve("cable-recording.flag");try{Files.createDirectories(OUTPUT);Files.writeString(flag,"Actual UMBRAL cable attachment, removal and reconnection");}catch(java.io.IOException e){throw new RuntimeException(e);}
            try{c.waitTicks(25);world.getServer().runOnServer(server->conn.getServerLevel().setBlockAndUpdate(NODE.west(),Blocks.AIR.defaultBlockState()));conn.waitForClientboundPackets();c.waitTicks(5);check(boxes(c).isEmpty(),"Removing the cable also removes the adapter immediately");world.getServer().runOnServer(server->check(!NetworkService.inspect(conn.getServerLevel(),(DeviceBlockEntity)conn.getServerLevel().getBlockEntity(NODE)).online(),"A removed lead is a real signal interruption"));capture(c,"umbral-cable-node-removed");c.waitTicks(25);world.getServer().runOnServer(server->conn.getServerLevel().setBlockAndUpdate(NODE.west(),UmbralContent.CABLE.defaultBlockState()));conn.waitForClientboundPackets();c.waitTicks(5);check(!boxes(c).isEmpty(),"Replacing the cable restores its physical connector");capture(c,"umbral-cable-node-restored");c.waitTicks(30);}finally{try{Files.deleteIfExists(flag);}catch(java.io.IOException e){throw new RuntimeException(e);}}
            world.getServer().runOnServer(server->conn.getServerLevel().setBlockAndUpdate(NODE,conn.getServerLevel().getBlockState(NODE).setValue(DeviceBlock.FACING,Direction.WEST)));conn.waitForClientboundPackets();c.waitTicks(4);capture(c,"umbral-cable-node-front");
            // A visible low wired installation over a complete floor, with real wall/ceiling supports.
            var galleryRoot=new BlockPos(4,-60,4);
            world.getServer().runOnServer(server->{
                var level=conn.getServerLevel();var owner=conn.getServerPlayer().getUUID();
                level.setBlockAndUpdate(NODE.below(),Blocks.SMOOTH_STONE.defaultBlockState());
                var states=List.of(UmbralContent.CORE.defaultBlockState(),UmbralContent.NODE.defaultBlockState(),UmbralContent.TERMINAL.defaultBlockState(),UmbralContent.TERMINAL.defaultBlockState().setValue(TerminalBlock.MOUNT,TerminalBlock.Mount.WALL),UmbralContent.CAMERA.defaultBlockState(),UmbralContent.CAMERA.defaultBlockState().setValue(CameraBlock.MOUNT,CameraBlock.Mount.WALL),UmbralContent.CAMERA.defaultBlockState().setValue(CameraBlock.MOUNT,CameraBlock.Mount.CEILING),UmbralContent.TURRET.defaultBlockState(),UmbralContent.CENTELLA.defaultBlockState(),UmbralContent.READER.defaultBlockState(),UmbralContent.SENSOR.defaultBlockState(),UmbralContent.PULSE_MINE.defaultBlockState(),UmbralContent.NEXO.defaultBlockState(),UmbralContent.DOCK.defaultBlockState(),UmbralContent.DOOR.defaultBlockState(),UmbralContent.FENCE.defaultBlockState(),UmbralContent.FILTER.defaultBlockState());
                for(int n=0;n<states.size();n++){
                    var pos=galleryPos(n);var state=states.get(n);level.setBlockAndUpdate(pos,state);((DeviceBlockEntity)level.getBlockEntity(pos)).initializeOwner(owner,"");
                    if(state.getBlock() instanceof SecurityDoorBlock)level.setBlockAndUpdate(pos.above(),state.setValue(SecurityDoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
                    boolean wall=n==3||n==5||n==9||n==10||n==12;
                    if(wall)for(int x=-1;x<=1;x++)for(int y=0;y<3;y++)level.setBlockAndUpdate(pos.offset(x,y,1),Blocks.SMOOTH_STONE.defaultBlockState());
                    if(n==6)for(int x=-1;x<=1;x++)for(int z=0;z<=1;z++)level.setBlockAndUpdate(pos.offset(x,1,z),Blocks.SMOOTH_STONE.defaultBlockState());
                    level.setBlockAndUpdate(pos.west(),UmbralContent.CABLE.defaultBlockState());level.setBlockAndUpdate(pos.west().north(),UmbralContent.CABLE.defaultBlockState());
                }
                var core=(DeviceBlockEntity)level.getBlockEntity(galleryRoot);
                var player=conn.getServerPlayer();player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                var filter=galleryPos(16);level.setBlockAndUpdate(filter,Blocks.AIR.defaultBlockState());player.setPos(Vec3.atLowerCornerOf(filter).add(.5,0,-2));player.setYRot(0);player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(UmbralContent.FILTER));var floor=filter.below();UmbralContent.FILTER.asItem().useOn(new net.minecraft.world.item.context.UseOnContext(player,net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(floor).add(0,.5,0),Direction.UP,floor,false)));check(FilterBlock.complete((DeviceBlockEntity)level.getBlockEntity(filter)),"Gallery uses a complete real scanner kit");player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,net.minecraft.world.item.ItemStack.EMPTY);
                place(level,galleryPos(15).east(),UmbralContent.FENCE,owner);place(level,galleryPos(15).east(2),UmbralContent.FENCE,owner);
                for(int n=1;n<states.size();n++){var d=(DeviceBlockEntity)level.getBlockEntity(galleryPos(n));if(d.kind()==DeviceKind.FENCE)check(dev.umbral.fence.FenceService.pair(player,d,core).equals("fence_paired"),"Gallery fence has an actual feeder");else check(d.bind(owner,core),"Every gallery apparatus pairs with the real core");}
                player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                level.setBlockAndUpdate(galleryRoot.below(),Blocks.SMOOTH_STONE.defaultBlockState());
                for(int z=2;z<=14;z++)level.setBlockAndUpdate(new BlockPos(2,-60,z),UmbralContent.CABLE.defaultBlockState());
                for(int z:new int[]{2,6,10,14})for(int x=2;x<=19;x++)level.setBlockAndUpdate(new BlockPos(x,-60,z),UmbralContent.CABLE.defaultBlockState());
                var reader=(DeviceBlockEntity)level.getBlockEntity(galleryPos(9));var door=(DeviceBlockEntity)level.getBlockEntity(galleryPos(14));check(reader.targetDoor(owner,door),"The displayed reader is assigned to the nearby door");
                NetworkService.invalidate(level);
                for(int n=0;n<states.size();n++)check(NetworkService.inspect(level,(DeviceBlockEntity)level.getBlockEntity(galleryPos(n))).status()==NetworkService.Status.WIRED,"Every displayed device has a complete cable path: "+n);
            });conn.waitForClientboundPackets();c.waitTicks(25);
            c.waitFor(mc->{for(int n=0;n<17;n++)if(!mc.level.getBlockState(galleryPos(n)).getValue(DeviceBlock.ONLINE))return false;return true;});
            c.runOnClient(mc->{for(int n=0;n<17;n++){var device=(DeviceBlockEntity)mc.level.getBlockEntity(galleryPos(n));var renderer=mc.getBlockEntityRenderDispatcher().<DeviceBlockEntity,DeviceRenderer.State>getRenderer(device);var state=renderer.createRenderState();renderer.extractRenderState(device,state,0,Vec3.ZERO,null);check(!((List<?>)field(field(state,"cables"),"boxes")).isEmpty(),"Each supported housing/mount renders its actual plug");}});
            world.getServer().runCommand("tp @a 12 -51 -5 0 0");conn.waitForClientboundPackets();aim(c,new Vec3(12,-59.45,8));conn.waitForChunksRender();capture(c,"umbral-cable-device-gallery");
            c.runOnClient(mc->mc.options.fov().set(50));
            String[] names={"01-nucleo","02-nodo","03-terminal-suelo","04-terminal-pared","05-vigia-suelo","06-vigia-pared","07-vigia-techo","08-baluarte","09-centella","10-lector","11-sensor","12-mina","13-nexo","14-estacion-mota","15-puerta","16-cerco","17-filtro"};
            for(int n=0;n<17;n++){
                var pos=galleryPos(n);boolean tall=n==0||n==7||n==8||n==14||n==16;double y=n==6?-61.1:tall?-60.05:-60.4;
                world.getServer().runCommand("tp @a "+(pos.getX()-1.9)+" "+y+" "+(pos.getZ()-2.2)+" 0 0");conn.waitForClientboundPackets();
                aim(c,new Vec3(pos.getX()+.25,-60+(n==14?1:n==0?.7:n==7||n==8?.65:n==6?.65:.38),pos.getZ()+.5));
                c.waitTicks(4);capture(c,"gallery/"+names[n]);
            }
            c.runOnClient(mc->mc.options.fov().set(70));
            for(int row=0;row<4;row++){
                world.getServer().runCommand("tp @a 12 -54.6 "+(4+row*4-11)+" 0 0");conn.waitForClientboundPackets();aim(c,new Vec3(12,-59.45,4+row*4+.5));capture(c,"gallery/00-fila-"+(row+1));
            }
            // This door is outside the core's own radius but within the cabled node's local sphere.
            c.runOnClient(mc->mc.options.fov().set(50));
            var doorPos=galleryPos(14);world.getServer().runOnServer(server->{var level=conn.getServerLevel();level.setBlockAndUpdate(doorPos.west(),Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(doorPos.west().below(),Blocks.SMOOTH_STONE.defaultBlockState());NetworkService.invalidate(level);var door=(DeviceBlockEntity)level.getBlockEntity(doorPos);check(NetworkService.inspect(level,door).status()==NetworkService.Status.WIRELESS,"Door really works through the cabled node's wireless coverage");check(dev.umbral.access.AccessService.readiness(level,(DeviceBlockEntity)level.getBlockEntity(galleryPos(9)))==dev.umbral.access.AccessService.Result.READY,"Assigned reader accepts a wireless door");});conn.waitForClientboundPackets();c.waitTicks(4);
            world.getServer().runCommand("tp @a 19.5 -60.05 10.2 0 0");conn.waitForClientboundPackets();aim(c,new Vec3(20.5,-59,12.5));capture(c,"gallery/16-puerta-inalambrica-cerrada");
            world.getServer().runOnServer(server->{var player=conn.getServerPlayer();player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);var level=conn.getServerLevel();var reader=(DeviceBlockEntity)level.getBlockEntity(galleryPos(9));var core=(DeviceBlockEntity)level.getBlockEntity(galleryRoot);var grant=dev.umbral.access.AccessRegistry.get(level).issue(player.getUUID(),core.link(),"Demostración inalámbrica");var card=new net.minecraft.world.item.ItemStack(UmbralContent.ACCESS_CARD);card.set(UmbralContent.CARD_ID,grant.id());check(dev.umbral.access.AccessService.present(player,reader,card)==dev.umbral.access.AccessService.Result.GRANTED,"A valid card opens the wireless door using actual access rules");player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);});conn.waitForClientboundPackets();c.waitTicks(16);capture(c,"gallery/17-puerta-inalambrica-abierta");
            try{Files.createDirectories(OUTPUT.resolve("gallery"));Files.writeString(OUTPUT.resolve("gallery/proof.json"),"{\"version\":\"1.6.1\",\"wired_profiles\":17,\"cable_diameter_units\":1.5,\"horizontal_height_units\":1.5,\"floor_clearance_units\":0.75,\"facets\":8,\"door_without_cable\":\"WIRELESS\",\"reader\":\"READY\",\"card_result\":\"GRANTED\",\"reader_door_distance\":4,\"node_door_distance_squared\":208,\"core_door_distance_squared\":320}");}catch(java.io.IOException e){throw new RuntimeException(e);}


        }finally{c.runOnClient(mc->mc.options.fov().set(70));}
    }
}
