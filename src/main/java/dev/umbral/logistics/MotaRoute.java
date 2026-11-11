// SPDX-License-Identifier: MPL-2.0

package dev.umbral.logistics;

import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** Bounded 3D search through loaded, dry collision space. Every path is rechecked during movement. */
public final class MotaRoute {
    public static final double HALF_WIDTH=.54,HEIGHT=.66;
    public static final int MAX_NODES=4096,HORIZONTAL=24,VERTICAL=8;
    private static final int WIDTH=HORIZONTAL*2+1,LAYERS=VERTICAL*2+1;
    private static final int[] DX={0,0,0,0,-1,1},DY={-1,1,0,0,0,0},DZ={0,0,-1,1,0,0};
    private static final int[][] AXES={{0,1,2},{1,0,2},{2,1,0},{1,2,0},{0,2,1},{2,0,1}};
    /** Optional per-call counters for profiling. Never retained and never used as world state. */
    public static final class Metrics {
        private int expanded,clearanceChecks,clearanceHits,discovered,peakFrontier,worldBlockReads,blockCacheHits,worldChunkReads;
        public int expanded(){return expanded;}
        public int clearanceChecks(){return clearanceChecks;}
        public int clearanceHits(){return clearanceHits;}
        public int discovered(){return discovered;}
        public int peakFrontier(){return peakFrontier;}
        public int worldBlockReads(){return worldBlockReads;}
        public int blockCacheHits(){return blockCacheHits;}
        public int worldChunkReads(){return worldChunkReads;}
        private void reset(){expanded=clearanceChecks=clearanceHits=discovered=peakFrontier=worldBlockReads=blockCacheHits=worldChunkReads=0;}
    }
    /** One synchronous search sees each loaded block/shape once; no cached world facts escape find(). */
    private static final class SearchCollision {
        private final ServerLevel level;
        private final Metrics metrics;
        private final Long2ObjectOpenHashMap<CollisionCell[]> pages=new Long2ObjectOpenHashMap<>(32);
        private long lastPageKey;
        private CollisionCell[] lastPage;
        private final Long2ObjectOpenHashMap<BlockGetter> chunks=new Long2ObjectOpenHashMap<>(8);
        private final BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        private static final class CollisionCell {
            final BlockState state;final boolean loaded,available;
            VoxelShape local,moved;
            CollisionCell(BlockState state,boolean loaded,boolean available){this.state=state;this.loaded=loaded;this.available=available;}
        }
        SearchCollision(ServerLevel level,Metrics metrics){this.level=level;this.metrics=metrics;}
        private CollisionCell cell(int x,int y,int z){
            // Adjacent collision samples usually stay in the same 4³ page. Keep the
            // exact per-voxel facts while avoiding a long-key hash lookup per sample.
            long pageKey=BlockPos.asLong(x>>2,y>>2,z>>2);CollisionCell[] page;
            if(lastPage!=null&&lastPageKey==pageKey)page=lastPage;
            else{
                page=pages.get(pageKey);if(page==null){page=new CollisionCell[64];pages.put(pageKey,page);}
                lastPage=page;lastPageKey=pageKey;
            }
            int index=((x&3)<<4)|((y&3)<<2)|(z&3);var known=page[index];
            if(known!=null){if(metrics!=null)metrics.blockCacheHits++;return known;}
            long chunkKey=ChunkPos.pack(x>>4,z>>4);var chunk=chunks.get(chunkKey);
            if(chunk==null&&!chunks.containsKey(chunkKey)){
                chunk=level.getChunkForCollisions(x>>4,z>>4);chunks.put(chunkKey,chunk);if(metrics!=null)metrics.worldChunkReads++;
            }
            pos.set(x,y,z);boolean loaded=level.isLoaded(pos);var state=chunk==null?Blocks.AIR.defaultBlockState():chunk.getBlockState(pos);
            var found=new CollisionCell(state,loaded,chunk!=null);page[index]=found;if(metrics!=null)metrics.worldBlockReads++;return found;
        }
        boolean clear(double x,double y,double z){return clear(new AABB(x-HALF_WIDTH,y,z-HALF_WIDTH,x+HALF_WIDTH,y+HEIGHT,z+HALF_WIDTH));}
        boolean clear(AABB box){
            for(int x=net.minecraft.util.Mth.floor(box.minX);x<=net.minecraft.util.Mth.floor(box.maxX);x++)
                for(int y=net.minecraft.util.Mth.floor(box.minY);y<=net.minecraft.util.Mth.floor(box.maxY);y++)
                    for(int z=net.minecraft.util.Mth.floor(box.minZ);z<=net.minecraft.util.Mth.floor(box.maxZ);z++){
                        var c=cell(x,y,z);if(!c.loaded||!c.available||!c.state.getFluidState().isEmpty())return false;
                    }
            // Match BlockCollisions' halo, edge filters and empty collision context, including
            // protruding shapes and moving pistons. Only the repeated world reads are cached.
            int x0=net.minecraft.util.Mth.floor(box.minX-1e-7)-1,x1=net.minecraft.util.Mth.floor(box.maxX+1e-7)+1;
            int y0=net.minecraft.util.Mth.floor(box.minY-1e-7)-1,y1=net.minecraft.util.Mth.floor(box.maxY+1e-7)+1;
            int z0=net.minecraft.util.Mth.floor(box.minZ-1e-7)-1,z1=net.minecraft.util.Mth.floor(box.maxZ+1e-7)+1;
            VoxelShape query=null;
            for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++){
                int boundary=(x==x0||x==x1?1:0)+(y==y0||y==y1?1:0)+(z==z0||z==z1?1:0);if(boundary==3)continue;
                var c=cell(x,y,z);var state=c.state;
                if(!c.available||boundary==1&&!state.hasLargeCollisionShape()||boundary==2&&!state.is(Blocks.MOVING_PISTON))continue;
                if(c.local==null){pos.set(x,y,z);c.local=CollisionContext.empty().getCollisionShape(state,level,pos);}
                if(c.local==Shapes.block()){
                    if(box.intersects(x,y,z,x+1d,y+1d,z+1d))return false;
                }else if(!c.local.isEmpty()){
                    if(c.moved==null)c.moved=c.local.move(x,y,z);
                    if(query==null)query=Shapes.create(box);
                    if(Shapes.joinIsNotEmpty(c.moved,query,BooleanOp.AND))return false;
                }
            }return true;
        }
    }
    public static boolean clear(ServerLevel level,Vec3 feet){
        return finite(feet)&&clear(level,feet.x,feet.y,feet.z);
    }
    private static boolean clear(ServerLevel level,double x,double y,double z){
        return clear(level,new AABB(x-HALF_WIDTH,y,z-HALF_WIDTH,x+HALF_WIDTH,y+HEIGHT,z+HALF_WIDTH));
    }
    private static boolean clear(ServerLevel level,AABB box){
        var p=new BlockPos.MutableBlockPos();
        for(int bx=net.minecraft.util.Mth.floor(box.minX);bx<=net.minecraft.util.Mth.floor(box.maxX);bx++)
            for(int by=net.minecraft.util.Mth.floor(box.minY);by<=net.minecraft.util.Mth.floor(box.maxY);by++)
                for(int bz=net.minecraft.util.Mth.floor(box.minZ);bz<=net.minecraft.util.Mth.floor(box.maxZ);bz++){
                    p.set(bx,by,bz);if(!level.isLoaded(p)||!level.getFluidState(p).isEmpty())return false;
                }
        return !level.getBlockCollisions(null,box).iterator().hasNext();
    }
    public static List<Vec3> find(ServerLevel level,Vec3 start,Vec3 finish){
        return find(level,start,finish,null);
    }
    public static List<Vec3> find(ServerLevel level,Vec3 start,Vec3 finish,Metrics metrics){
        if(metrics!=null)metrics.reset();
        if(!finite(start)||!finite(finish)||start.distanceToSqr(finish)>32*32)return List.of();
        BlockPos origin=BlockPos.containing(start),goal=BlockPos.containing(finish);
        int gx=goal.getX()-origin.getX(),gy=goal.getY()-origin.getY(),gz=goal.getZ()-origin.getZ();
        if(Math.abs(gx)>HORIZONTAL||Math.abs(gy)>VERTICAL||Math.abs(gz)>HORIZONTAL)return List.of();
        var world=new SearchCollision(level,metrics);
        if(metrics!=null)metrics.clearanceChecks++;
        if(!world.clear(start.x,start.y,start.z))return List.of();
        if(metrics!=null)metrics.clearanceChecks++;
        if(!world.clear(finish.x,finish.y,finish.z))return List.of();
        int first=index(0,0,0),last=index(gx,gy,gz);
        var costs=new Int2IntOpenHashMap(128);costs.defaultReturnValue(Integer.MAX_VALUE);costs.put(first,0);
        var parents=new Int2IntOpenHashMap(128);parents.defaultReturnValue(-1);
        var clearance=new Int2ByteOpenHashMap(256);
        var verticalClearance=new Int2ByteOpenHashMap(128);
        var entrances=new Int2ObjectOpenHashMap<List<Vec3>>(6);
        var frontier=new LongHeapPriorityQueue(128);
        frontier.enqueue(priority(first,0,Math.abs(gx)+Math.abs(gy)+Math.abs(gz)));
        if(metrics!=null){metrics.discovered=1;metrics.peakFrontier=1;}
        while(!frontier.isEmpty()){
            // Once the discovery budget is full, an undiscovered goal can no longer
            // enter the frontier. Draining existing entries cannot produce a route.
            if(costs.size()>=MAX_NODES&&costs.get(last)==Integer.MAX_VALUE)return List.of();
            long next=frontier.dequeueLong();int stepCell=(int)(next&65535),estimate=(int)(next>>>16&127),stepCost=(int)(next>>>23)-estimate;
            if(costs.get(stepCell)!=stepCost)continue;
            if(metrics!=null)metrics.expanded++;
            if(stepCell==last){
                if(first==last){
                    if(start.distanceToSqr(finish)<.00000001)return List.of(start,finish);
                    var connection=entrance(world,start,finish,metrics);if(connection.isEmpty())return List.of();
                    var path=new ArrayList<Vec3>(connection.size()+1);path.add(start);path.addAll(connection);return List.copyOf(path);
                }
                return reconstruct(start,finish,origin,first,last,parents,entrances);
            }
            int x=stepCell/(LAYERS*WIDTH)-HORIZONTAL,y=stepCell/WIDTH%LAYERS-VERTICAL,z=stepCell%WIDTH-HORIZONTAL;
            for(int n=0;n<6;n++){
                int nx=x+DX[n],ny=y+DY[n],nz=z+DZ[n];
                if(Math.abs(nx)>HORIZONTAL||Math.abs(ny)>VERTICAL||Math.abs(nz)>HORIZONTAL)continue;
                int cell=index(nx,ny,nz),cost=stepCost+1,old=costs.get(cell);if(cost>=old)continue;
                if(old==Integer.MAX_VALUE&&costs.size()>=MAX_NODES)continue;
                byte pass=clearance.get(cell);
                if(pass==0){
                    if(metrics!=null)metrics.clearanceChecks++;
                    pass=(byte)(world.clear(origin.getX()+nx+.5,origin.getY()+ny+.15,origin.getZ()+nz+.5)?1:2);clearance.put(cell,pass);
                }else if(metrics!=null)metrics.clearanceHits++;
                if(pass==2)continue;
                if(stepCell==first){
                    var entrance=entrance(world,start,new Vec3(origin.getX()+nx+.5,origin.getY()+ny+.15,origin.getZ()+nz+.5),metrics);
                    if(entrance.isEmpty())continue;entrances.put(cell,entrance);
                }else if(DY[n]!=0){
                    int edge=DY[n]>0?stepCell:cell;byte open=verticalClearance.get(edge);
                    if(open==0){
                        if(metrics!=null)metrics.clearanceChecks++;
                        double bx=origin.getX()+nx+.5,by=origin.getY()+Math.min(y,ny)+.15,bz=origin.getZ()+nz+.5;
                        open=(byte)(world.clear(new AABB(bx-HALF_WIDTH,by,bz-HALF_WIDTH,bx+HALF_WIDTH,by+HEIGHT+1,bz+HALF_WIDTH))?1:2);verticalClearance.put(edge,open);
                    }else if(metrics!=null)metrics.clearanceHits++;
                    if(open==2)continue;
                }
                costs.put(cell,cost);parents.put(cell,stepCell);
                frontier.enqueue(priority(cell,cost,Math.abs(gx-nx)+Math.abs(gy-ny)+Math.abs(gz-nz)));
                if(metrics!=null){metrics.discovered=costs.size();metrics.peakFrontier=Math.max(metrics.peakFrontier,frontier.size());}
            }
        }return List.of();
    }
    /** Natural long ordering equals the former comparator: f, then h, then cell.
     * Cells occupy 0..40816 (16 bits), Manhattan h is at most 112 (7 bits),
     * and f remains nonnegative under the strict 4096-node discovery budget. */
    private static long priority(int cell,int cost,int estimate){return (long)(cost+estimate)<<23|(long)estimate<<16|cell;}
    private static int index(int x,int y,int z){return ((x+HORIZONTAL)*LAYERS+y+VERTICAL)*WIDTH+z+HORIZONTAL;}
    private static boolean finite(Vec3 p){return p!=null&&Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z);}
    /** Joining the grid by checked axis segments avoids repeatedly cutting a newly blocked corner. */
    private static List<Vec3> entrance(SearchCollision world,Vec3 start,Vec3 finish,Metrics metrics){
        for(var order:AXES){
            Vec3 from=start;var points=new ArrayList<Vec3>(3);boolean open=true;
            for(int axis:order){
                Vec3 to=axis==0?new Vec3(finish.x,from.y,from.z):axis==1?new Vec3(from.x,finish.y,from.z):new Vec3(from.x,from.y,finish.z);
                if(to.distanceToSqr(from)<.00000001)continue;
                if(metrics!=null)metrics.clearanceChecks++;
                if(!world.clear(new AABB(Math.min(from.x,to.x)-HALF_WIDTH,Math.min(from.y,to.y),Math.min(from.z,to.z)-HALF_WIDTH,Math.max(from.x,to.x)+HALF_WIDTH,Math.max(from.y,to.y)+HEIGHT,Math.max(from.z,to.z)+HALF_WIDTH))){open=false;break;}
                points.add(to);from=to;
            }
            if(open)return List.copyOf(points);
        }return List.of();
    }
    private static List<Vec3> reconstruct(Vec3 start,Vec3 finish,BlockPos origin,int first,int last,Int2IntOpenHashMap parents,Int2ObjectOpenHashMap<List<Vec3>> entrances){
        var route=new ArrayList<Vec3>();route.add(finish);
        for(int cell=last;cell!=first;cell=parents.get(cell)){
            if(parents.get(cell)==first){var entrance=entrances.get(cell);for(int n=entrance.size()-1;n>=0;n--)route.add(entrance.get(n));}
            else route.add(new Vec3(origin.getX()+cell/(LAYERS*WIDTH)-HORIZONTAL+.5,origin.getY()+cell/WIDTH%LAYERS-VERTICAL+.15,origin.getZ()+cell%WIDTH-HORIZONTAL+.5));
        }
        route.add(start);Collections.reverse(route);return List.copyOf(route);
    }
    public static Vec3 approach(BlockPos pos){return new Vec3(pos.getX()+.5,pos.getY()+1.25,pos.getZ()+.5);}
    private MotaRoute(){}
}
