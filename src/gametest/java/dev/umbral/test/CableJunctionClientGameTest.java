// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.CopperCableBlock;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Files;
import java.nio.file.Path;

/** The complete six-direction state space, with close real-game views of its junctions. */
@SuppressWarnings("UnstableApiUsage")
public final class CableJunctionClientGameTest implements FabricClientGameTest {
    private static final String VERSION=System.getenv().getOrDefault("UMBRAL_CAPTURE_VERSION","1.6.1");
    private static final Path OUTPUT=Path.of("../../visual-checks",VERSION,"junctions");
    private static final Direction[] DIRECTIONS={Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST,Direction.UP,Direction.DOWN};
    private static final int[] MACROS={0,1,12,3,5,13,15,21,48,63};
    private static final String[] NAMES={"isolated","end","straight-x","straight-z","corner-l","junction-t","cross","corner-3d","vertical","six-way"};
    private static BlockPos center(int mask){return new BlockPos((mask%8)*4,-60,(mask/8)*4);}
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    private static void aim(ClientGameTestContext c,Vec3 target){
        var a=c.computeOnClient(mc->{var v=target.subtract(mc.player.getEyePosition());return new float[]{(float)Math.toDegrees(Math.atan2(-v.x,v.z)),(float)-Math.toDegrees(Math.atan2(v.y,v.horizontalDistance()))};});
        c.getInput().lookAt(a[0],a[1]);
    }
    private static void capture(ClientGameTestContext c,String name){
        c.waitTicks(4);
        c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));
    }
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);
        c.runOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();mc.options.renderDistance().set(5);mc.options.fov().set(70);mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);});
        try{Files.createDirectories(OUTPUT);}catch(java.io.IOException e){throw new RuntimeException(e);}
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("gamemode spectator @a");world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server->{
                var level=conn.getServerLevel();
                for(int x=-3;x<=32;x++)for(int z=-3;z<=32;z++)level.setBlockAndUpdate(new BlockPos(x,-61,z),Blocks.SMOOTH_STONE.defaultBlockState());
                for(int mask=0;mask<64;mask++){
                    var at=center(mask);level.setBlockAndUpdate(at,UmbralContent.CABLE.defaultBlockState());
                    for(int n=0;n<DIRECTIONS.length;n++)if((mask&(1<<n))!=0)level.setBlockAndUpdate(at.relative(DIRECTIONS[n]),UmbralContent.CABLE.defaultBlockState());
                }
            });conn.waitForClientboundPackets();conn.waitForChunksRender();c.waitTicks(6);
            world.getServer().runOnServer(server->{
                var level=conn.getServerLevel();
                for(int mask=0;mask<64;mask++){
                    var state=level.getBlockState(center(mask));
                    for(int n=0;n<DIRECTIONS.length;n++)check(state.getValue(CopperCableBlock.CONNECTIONS.get(DIRECTIONS[n]))==((mask&(1<<n))!=0),"Server junction state differs from mask "+mask+"/"+DIRECTIONS[n]);
                }
            });
            c.runOnClient(mc->{
                for(int mask=0;mask<64;mask++){
                    var state=mc.level.getBlockState(center(mask));check(state.is(UmbralContent.CABLE),"Client received cable fixture "+mask);
                    for(int n=0;n<DIRECTIONS.length;n++)check(state.getValue(CopperCableBlock.CONNECTIONS.get(DIRECTIONS[n]))==((mask&(1<<n))!=0),"Client junction state differs from mask "+mask+"/"+DIRECTIONS[n]);
                }
                if(!mc.gui.hud.isHidden())mc.gui.hud.toggle();
            });
            world.getServer().runOnServer(server->conn.getServerPlayer().teleportTo(14,-39,-12));conn.waitForClientboundPackets();aim(c,new Vec3(14.5,-59.9,14.5));capture(c,"all-64-junction-states");
            var flag=OUTPUT.getParent().resolve("junction-recording.flag");
            try{Files.writeString(flag,"Real cable L/T/cross and vertical junctions viewed from opposite sides");}catch(java.io.IOException e){throw new RuntimeException(e);}
            try{
                c.runOnClient(mc->mc.options.fov().set(40));
                for(int n=0;n<MACROS.length;n++){
                    var at=center(MACROS[n]);var target=Vec3.atLowerCornerOf(at).add(.5,1.5/16,.5);
                    for(int view=0;view<2;view++){
                        double dx=view==0?-2.3:2.3,dz=view==0?-2.6:2.6;
                        world.getServer().runOnServer(server->conn.getServerPlayer().teleportTo(at.getX()+.5+dx,-60.6,at.getZ()+.5+dz));
                        conn.waitForClientboundPackets();aim(c,target);capture(c,NAMES[n]+"-"+(view+1));
                    }
                }
                c.runOnClient(mc->mc.options.fov().set(30));
                for(int mask:new int[]{5,13,15,21}){
                    var at=center(mask);world.getServer().runOnServer(server->conn.getServerPlayer().teleportTo(at.getX()-.6,-61.15,at.getZ()-.7));conn.waitForClientboundPackets();aim(c,Vec3.atLowerCornerOf(at).add(.5,1.5/16,.5));capture(c,"detail-mask-"+mask);
                }
            }finally{try{Files.deleteIfExists(flag);}catch(java.io.IOException e){throw new RuntimeException(e);}}
            try{Files.writeString(OUTPUT.resolve("proof.json"),"{\"version\":\""+VERSION+"\",\"server_masks_verified\":64,\"client_masks_verified\":64,\"macro_cases\":10,\"opposite_views_per_case\":2,\"detail_views\":4,\"horizontal_center_units\":1.5,\"diameter_units\":1.5,\"floor_clearance_units\":0.75}");}catch(java.io.IOException e){throw new RuntimeException(e);}
        }finally{c.runOnClient(mc->{mc.options.fov().set(70);if(mc.gui.hud.isHidden())mc.gui.hud.toggle();});}
    }
}
