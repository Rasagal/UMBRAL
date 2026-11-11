// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.google.gson.JsonObject;
import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.client.DeviceRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.*;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Full extended bounds, actual camera culling and all 64 connector cache transitions. */
@SuppressWarnings("UnstableApiUsage")
public final class RenderCullingClientGameTest implements FabricClientGameTest {
    private static final BlockPos NODE=new BlockPos(0,-60,0),DOOR=new BlockPos(3,-60,0);
    private static final Path OUTPUT=Path.of("../../visual-checks/1.6.1");
    private static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
    private static Object field(Object value,String name){try{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}}
    private static Frustum boxFrustum(double x,double y,double z){var f=new Frustum(new Matrix4f(),new Matrix4f());f.prepare(x,y,z);return f;}
    private static JsonObject resource(String path){try(var reader=Minecraft.getInstance().getResourceManager().getResource(UmbralContent.id(path)).orElseThrow().openAsReader()){return GsonHelper.parse(reader);}catch(java.io.IOException e){throw new RuntimeException(e);}}
    private static void bounds(){
        var upperOnly=boxFrustum(3.5,-57.98,.5);
        check(!upperOnly.isVisible(new AABB(DOOR)),"The controller cell is deliberately outside this frustum");
        check(DeviceRenderer.visible(upperOnly,UmbralContent.DOOR.defaultBlockState(),DOOR),"Visible upper leaves keep their lower renderer eligible");
        for(var facing:Direction.Plane.HORIZONTAL){
            var root=new BlockPos(6,-60,0);var right=facing.getClockWise();
            var farColumn=boxFrustum(root.getX()+.5+right.getStepX()*2.1,-58.5,root.getZ()+.5+right.getStepZ()*2.1);
            check(!farColumn.isVisible(new AABB(root)),"The scanner controller lies outside the visible far column "+facing);
            check(DeviceRenderer.visible(farColumn,UmbralContent.FILTER.defaultBlockState().setValue(DeviceBlock.FACING,facing),root),"Scanner upper/far column and beams remain eligible "+facing);
        }
        check(!DeviceRenderer.visible(boxFrustum(0,-59.5,-4),UmbralContent.NODE.defaultBlockState(),NODE),"A fully outside apparatus is rejected");
        sweptHead("security_camera_head_on",true);
        for(String name:List.of("baluarte_head_loaded_on","centella_head_copper_on","centella_head_iron_on","centella_head_gold_on"))sweptHead(name,false);
    }
    private static void sweptHead(String name,boolean camera){
        var data=resource("models/block/"+name+".json");var state=(camera?UmbralContent.CAMERA:name.startsWith("baluarte")?UmbralContent.TURRET:UmbralContent.CENTELLA).defaultBlockState();
        var bound=DeviceRenderer.renderBounds(state,BlockPos.ZERO);
        for(var value:data.getAsJsonArray("elements")){
            var cube=value.getAsJsonObject();var a=cube.getAsJsonArray("from");var b=cube.getAsJsonArray("to");
            for(int corner=0;corner<8;corner++){
                var source=new Vector3f((corner&1)==0?a.get(0).getAsFloat()/16:b.get(0).getAsFloat()/16,(corner&2)==0?a.get(1).getAsFloat()/16:b.get(1).getAsFloat()/16,(corner&4)==0?a.get(2).getAsFloat()/16:b.get(2).getAsFloat()/16);
                if(cube.has("rotation")){
                    var rotation=cube.getAsJsonObject("rotation");var o=rotation.getAsJsonArray("origin");var origin=new Vector3f(o.get(0).getAsFloat()/16,o.get(1).getAsFloat()/16,o.get(2).getAsFloat()/16);String axis=rotation.get("axis").getAsString();
                    source.sub(origin).rotate(new Quaternionf().rotationAxis((float)Math.toRadians(rotation.get("angle").getAsFloat()),axis.equals("x")?1:0,axis.equals("y")?1:0,axis.equals("z")?1:0)).add(origin);
                }
                source.sub(.5f,camera?.625f:.5f,.5f);
                for(int yaw=0;yaw<360;yaw+=30)for(int pitch=-90;pitch<=90;pitch+=30)for(float recoil:new float[]{0,camera?0:.0625f}){
                    var p=new Vector3f(source).add(0,0,recoil).rotateX((float)Math.toRadians(-pitch)).rotateY((float)Math.toRadians(yaw)).add(.5f,camera?.625f:.5f,.5f);
                    check(bound.contains(p.x,p.y,p.z),"Swept culling bounds contain authored moving vertex "+name+" at "+yaw+"/"+pitch);
                }
            }
        }
    }
    private static DeviceRenderer renderer(){return (DeviceRenderer)Minecraft.getInstance().getBlockEntityRenderDispatcher().<DeviceBlockEntity,DeviceRenderer.State>getRenderer((DeviceBlockEntity)Minecraft.getInstance().level.getBlockEntity(NODE));}
    private static DeviceRenderer.State extract(){var mc=Minecraft.getInstance();var device=(DeviceBlockEntity)mc.level.getBlockEntity(NODE);var renderer=renderer();var state=renderer.createRenderState();renderer.extractRenderState(device,state,0,Vec3.ZERO,null);return state;}
    private static void capture(ClientGameTestContext c,String name){c.waitTicks(4);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);c.runOnClient(mc->{mc.options.renderDistance().set(4);mc.options.fov().set(60);bounds();});
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("gamemode spectator @a");world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server->{var level=conn.getServerLevel();for(int x=-5;x<=7;x++)for(int z=-5;z<=4;z++)level.setBlockAndUpdate(new BlockPos(x,-61,z),Blocks.SMOOTH_STONE.defaultBlockState());level.setBlockAndUpdate(NODE,UmbralContent.NODE.defaultBlockState());level.setBlockAndUpdate(DOOR,UmbralContent.DOOR.defaultBlockState());level.setBlockAndUpdate(DOOR.above(),UmbralContent.DOOR.defaultBlockState().setValue(SecurityDoorBlock.HALF,DoubleBlockHalf.UPPER));});
            conn.waitForClientboundPackets();c.waitFor(mc->mc.level.getBlockEntity(NODE) instanceof DeviceBlockEntity);
            int[] quads=c.computeOnClient(mc->{var groups=resource("geometry/cable_connections.json").getAsJsonObject("groups");int[] result=new int[6];for(var face:Direction.values())result[face.ordinal()]=groups.getAsJsonObject("sector_node__"+face.getName()).getAsJsonArray("surfaces").size();return result;});
            for(int mask=0;mask<64;mask++){
                final int selected=mask;
                world.getServer().runOnServer(server->{var level=conn.getServerLevel();for(var face:Direction.values())level.setBlockAndUpdate(NODE.relative(face),((selected&(1<<face.ordinal()))==0?Blocks.AIR:UmbralContent.CABLE).defaultBlockState());});conn.waitForClientboundPackets();
                c.runOnClient(mc->{
                    var first=extract();int expected=0;for(var face:Direction.values())if((selected&(1<<face.ordinal()))!=0)expected+=quads[face.ordinal()];
                    check(DeviceRenderer.connectorQuads(first)==expected,"All exposed connector quads preserved for adjacency mask "+selected);
                    check(DeviceRenderer.connectorBatches(first)==(selected==0?0:3),"Only nonempty material batches are submitted");
                    for(String unused:List.of("camera","door","filter","turret"))check(field(first,unused)==null,"Static apparatus does not allocate unused animated state "+unused);
                    Object mesh=field(field(first,"cables"),"boxes");long builds=renderer().cableMeshBuilds();var again=extract();
                    check(field(field(again,"cables"),"boxes")==mesh&&renderer().cableMeshBuilds()==builds,"Unchanged adjacency reuses the immutable mesh without rebuilding lists");
                });
            }
            world.getServer().runOnServer(server->{var l=conn.getServerLevel();for(var face:Direction.values())l.setBlockAndUpdate(NODE.relative(face),Blocks.AIR.defaultBlockState());l.setBlockAndUpdate(NODE.below(),Blocks.SMOOTH_STONE.defaultBlockState());l.setBlockAndUpdate(NODE.west(),UmbralContent.CABLE.defaultBlockState());});conn.waitForClientboundPackets();
            c.runOnClient(mc->{var one=extract();check(DeviceRenderer.connectorQuads(one)==quads[Direction.WEST.ordinal()],"Removing five neighbors switches to exactly one current port");});
            world.getServer().runCommand("tp @a 1.5 -60 -4 0 0");conn.waitForClientboundPackets();FilterClientGameTest.aim(c,new Vec3(.5,-59.7,.5));c.waitTicks(4);
            c.runOnClient(mc->{var d=(DeviceBlockEntity)mc.level.getBlockEntity(NODE);check(renderer().shouldRender(d,mc.gameRenderer.mainCamera().position()),"A device inside the real camera frustum stays eligible");});conn.waitForChunksRender();capture(c,"umbral-render-culling-front");
            c.getInput().lookAt(180,0);c.waitTicks(4);
            c.runOnClient(mc->{var d=(DeviceBlockEntity)mc.level.getBlockEntity(NODE);check(!renderer().shouldRender(d,mc.gameRenderer.mainCamera().position()),"A device behind the real camera is rejected before state extraction");});
            c.runOnClient(mc->{
                int cached=renderer().cachedCableMeshes();check(cached>=63&&cached<=17*64,"Immutable cache is bounded by profile and adjacency, not world positions");
                try{Files.createDirectories(OUTPUT);Files.writeString(OUTPUT.resolve("render-culling-proof.json"),"{\"version\":\"1.6.1\",\"adjacency_masks\":64,\"max_material_batches\":3,\"cached_meshes\":"+cached+",\"same_mesh_on_cache_hit\":true,\"metadata_preserved\":true,\"door_upper_outside_controller\":true,\"filter_far_column_four_facings\":true,\"moving_heads_contained\":true,\"real_camera_front_kept_back_rejected\":true}");}catch(java.io.IOException e){throw new RuntimeException(e);}
            });
        }finally{c.runOnClient(mc->mc.options.fov().set(70));}
    }
}
