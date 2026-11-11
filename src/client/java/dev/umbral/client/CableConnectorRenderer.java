// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.util.GsonHelper;
import java.io.IOException;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.*;

/** Physical leads inside the equipment's cell, using its canonical fixed housing. */
final class CableConnectorRenderer {
    record Vertex(float x,float y,float z,float u,float v,float nx,float ny,float nz){}
    record Box(float x0,float y0,float z0,float x1,float y1,float z1,String material,String name,List<Vertex> vertices){}
    record Batch(RenderType type,List<Vertex> vertices){}
    private record Mesh(List<Box> boxes,List<Batch> batches,int quads){}
    private static final Mesh EMPTY=new Mesh(List.of(),List.of(),0);
    private static final List<String> MATERIALS=List.of("graphite","copper","steel");
    private static final Direction[] DIRECTIONS=Direction.values();
    static final class State {
        float rotation;
        List<Box> boxes=List.of();
        List<Batch> batches=List.of();
        int quads;
    }
    private Map<String,Mesh> geometry;
    // At most 17 profiles × 64 adjacency masks. No world positions or entities are retained.
    private final Map<String,Mesh[]> combinations=new HashMap<>();
    private final BlockPos.MutableBlockPos neighbor=new BlockPos.MutableBlockPos();
    private int cachedMeshes;
    private long meshBuilds;
    int cachedMeshes(){return cachedMeshes;}
    long meshBuilds(){return meshBuilds;}
    private static Mesh mesh(List<Box> boxes){
        var batches=new ArrayList<Batch>(3);int quads=0;
        for(String material:MATERIALS){
            var vertices=new ArrayList<Vertex>();
            for(var box:boxes)if(box.material.equals(material)&&!box.vertices.isEmpty())vertices.addAll(box.vertices);
            if(!vertices.isEmpty()){
                quads+=vertices.size()/4;
                batches.add(new Batch(RenderTypes.entitySolid(UmbralContent.id("textures/block/metal_"+material+".png")),List.copyOf(vertices)));
            }
        }
        // Empty authoring boxes remain available to source/port verification, never to draw loops.
        return new Mesh(List.copyOf(boxes),List.copyOf(batches),quads);
    }
    private Map<String,Mesh> geometry(){
        if(geometry!=null)return geometry;
        var result=new HashMap<String,Mesh>();
        try(var reader=Minecraft.getInstance().getResourceManager().getResource(UmbralContent.id("geometry/cable_connections.json")).orElseThrow().openAsReader()){
            var json=GsonHelper.parse(reader);int schema=GsonHelper.getAsInt(json,"schema");if(schema<1||schema>3)throw new IllegalArgumentException("Unsupported cable geometry");
            for(var entry:json.getAsJsonObject("groups").entrySet()){
                var boxes=new ArrayList<Box>();var authored=schema==3?entry.getValue().getAsJsonObject().getAsJsonArray("boxes"):entry.getValue().getAsJsonArray();for(var value:authored){
                    var box=value.getAsJsonObject();var a=box.getAsJsonArray("from");var b=box.getAsJsonArray("to");String material=GsonHelper.getAsString(box,"material");
                    if(!Set.of("graphite","copper","steel").contains(material))throw new IllegalArgumentException("Unknown cable material");
                    Quaternionf rotation=new Quaternionf();float ox=0,oy=0,oz=0;
                    if(box.has("rotation")){var r=box.getAsJsonObject("rotation");var origin=r.getAsJsonArray("origin");ox=origin.get(0).getAsFloat();oy=origin.get(1).getAsFloat();oz=origin.get(2).getAsFloat();String axis=GsonHelper.getAsString(r,"axis");float angle=GsonHelper.getAsFloat(r,"angle");if(!Set.of("x","y","z").contains(axis)||!Float.isFinite(angle)||Math.abs(angle)>45)throw new IllegalArgumentException("Invalid connector rotation");rotation.rotationAxis((float)Math.toRadians(angle),axis.equals("x")?1:0,axis.equals("y")?1:0,axis.equals("z")?1:0);}
                    if(schema==3)boxes.add(new Box(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat(),b.get(0).getAsFloat(),b.get(1).getAsFloat(),b.get(2).getAsFloat(),material,GsonHelper.getAsString(box,"name",""),List.of()));
                    else boxes.add(bake(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat(),b.get(0).getAsFloat(),b.get(1).getAsFloat(),b.get(2).getAsFloat(),material,GsonHelper.getAsString(box,"name",""),rotation,ox,oy,oz));
                }
                if(schema==3)for(var value:entry.getValue().getAsJsonObject().getAsJsonArray("surfaces")){
                    var surface=value.getAsJsonObject();String material=GsonHelper.getAsString(surface,"material");
                    if(!Set.of("graphite","copper","steel").contains(material))throw new IllegalArgumentException("Unknown cable surface material");
                    var normal=surface.getAsJsonArray("normal");float nx=normal.get(0).getAsFloat(),ny=normal.get(1).getAsFloat(),nz=normal.get(2).getAsFloat();
                    var vertices=new ArrayList<Vertex>(4);var points=surface.getAsJsonArray("vertices");if(points.size()!=4)throw new IllegalArgumentException("A cable surface must be a quad");
                    for(var point:points){var p=point.getAsJsonArray();float x=p.get(0).getAsFloat(),y=p.get(1).getAsFloat(),z=p.get(2).getAsFloat();float u=(Math.abs(nx)>Math.abs(nz)?z:x)/16,v=(Math.abs(ny)>.5?z:16-y)/16;vertices.add(new Vertex(x/16,y/16,z/16,u,v,nx,ny,nz));}
                    boxes.add(new Box(0,0,0,0,0,0,material,"exterior",List.copyOf(vertices)));
                }
                result.put(entry.getKey(),mesh(boxes));
            }
        }catch(IOException e){throw new IllegalStateException("Cannot read UMBRAL cable connections",e);}
        return geometry=Map.copyOf(result);
    }
    private Mesh connection(String profile,int mask){
        if(mask==0)return EMPTY;
        var variants=combinations.computeIfAbsent(profile,ignored->new Mesh[64]);
        if(variants[mask]!=null)return variants[mask];
        var ports=geometry();Mesh result;
        if(Integer.bitCount(mask)==1){
            var direction=DIRECTIONS[Integer.numberOfTrailingZeros(mask)];
            result=ports.getOrDefault(profile+"__"+direction.getName(),EMPTY);
        }else{
            var boxes=new ArrayList<Box>();
            for(var direction:DIRECTIONS)if((mask&(1<<direction.ordinal()))!=0)boxes.addAll(ports.getOrDefault(profile+"__"+direction.getName(),EMPTY).boxes);
            result=mesh(boxes);
        }
        variants[mask]=result;cachedMeshes++;meshBuilds++;return result;
    }
    private static String profile(DeviceBlockEntity device){
        var block=device.getBlockState();
        if(block.getBlock() instanceof CameraBlock)return switch(block.getValue(CameraBlock.MOUNT)){
            case FLOOR->"security_camera_floor";case WALL->"security_camera_wall";case CEILING->"security_camera_ceiling";
        };
        if(block.getBlock() instanceof TerminalBlock)return block.getValue(TerminalBlock.MOUNT)==TerminalBlock.Mount.FLOOR?"terminal_floor":"terminal_wall";
        return device.kind().id;
    }
    void extract(DeviceBlockEntity device,State state){
        state.boxes=List.of();state.batches=List.of();state.quads=0;if(device.getLevel()==null)return;
        var block=device.getBlockState();if(block.getBlock() instanceof FilterBlock&&!FilterBlock.base(block))return;Direction facing=block.getValue(DeviceBlock.FACING);state.rotation=180-facing.toYRot();
        if(block.getBlock() instanceof ElectricFenceBlock&&!block.getValue(ElectricFenceBlock.FEEDER))return;
        int mask=0,turns=Math.floorMod(Math.round(state.rotation/90),4);var pos=device.getBlockPos();
        for(Direction world:DIRECTIONS){
            // Re-read all six neighbors so removal, reattachment and chunk changes apply immediately.
            neighbor.set(pos.getX()+world.getStepX(),pos.getY()+world.getStepY(),pos.getZ()+world.getStepZ());
            if(!(device.getLevel().getBlockState(neighbor).getBlock() instanceof CopperCableBlock))continue;
            Direction local=world;
            if(world.getAxis().isHorizontal())for(int n=0;n<turns;n++)local=local.getClockWise();
            mask|=1<<local.ordinal();
        }
        var mesh=connection(profile(device),mask);state.boxes=mesh.boxes;state.batches=mesh.batches;state.quads=mesh.quads;
    }
    void submit(State state,PoseStack poses,SubmitNodeCollector collector,int light){
        if(state.batches.isEmpty())return;
        poses.pushPose();poses.translate(.5,0,.5);poses.rotateDegrees(Axis.YP,state.rotation);poses.translate(-.5,0,-.5);
        for(var batch:state.batches){
            collector.submitCustomGeometry(poses,batch.type,(pose,out)->{for(var vertex:batch.vertices)draw(pose,out,vertex,light);});
        }
        poses.popPose();
    }
    /** Bake cuboid rotations once when reading the editable resource, not once per frame. */
    private static Box bake(float x,float y,float z,float X,float Y,float Z,String material,String name,Quaternionf rotation,float ox,float oy,float oz){
        var vertices=new ArrayList<Vertex>(24);
        face(vertices,rotation,ox,oy,oz,0,0,-1,x,y,z,x,Y,z,X,Y,z,X,y,z);
        face(vertices,rotation,ox,oy,oz,0,0,1,X,y,Z,X,Y,Z,x,Y,Z,x,y,Z);
        face(vertices,rotation,ox,oy,oz,-1,0,0,x,y,Z,x,Y,Z,x,Y,z,x,y,z);
        face(vertices,rotation,ox,oy,oz,1,0,0,X,y,z,X,Y,z,X,Y,Z,X,y,Z);
        face(vertices,rotation,ox,oy,oz,0,1,0,x,Y,z,x,Y,Z,X,Y,Z,X,Y,z);
        face(vertices,rotation,ox,oy,oz,0,-1,0,x,y,Z,x,y,z,X,y,z,X,y,Z);
        return new Box(x,y,z,X,Y,Z,material,name,List.copyOf(vertices));
    }
    private static void face(List<Vertex> vertices,Quaternionf rotation,float ox,float oy,float oz,int nx,int ny,int nz,float...points){
        var normal=new Vector3f(nx,ny,nz).rotate(rotation);
        for(int n=0;n<points.length;n+=3){float x=points[n],y=points[n+1],z=points[n+2];float u=(nx==0?x:z)/16,v=(ny==0?16-y:z)/16;var p=new Vector3f(x-ox,y-oy,z-oz).rotate(rotation).add(ox,oy,oz);vertices.add(new Vertex(p.x/16,p.y/16,p.z/16,u,v,normal.x,normal.y,normal.z));}
    }
    private static void draw(PoseStack.Pose pose,VertexConsumer out,Vertex v,int light){
        out.addVertex(pose,v.x,v.y,v.z).setColor(-1).setUv(v.u,v.v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose,v.nx,v.ny,v.nz);
    }
}
