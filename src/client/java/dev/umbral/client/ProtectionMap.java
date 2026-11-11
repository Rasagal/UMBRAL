// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.umbral.client.mixin.GuiStateAccessor;
import dev.umbral.protection.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.core.BlockPos;
import org.joml.Matrix3x2f;
import java.util.*;
import static dev.umbral.client.ConsoleStyle.*;

/** Bounded, loaded-client terrain model. Claims remain visible independently of terrain availability. */
final class ProtectionMap {
    record Quad(float[] xy,int color){}
    private record Mesh(Matrix3x2f pose,List<Quad> quads,ScreenRectangle scissorArea,ScreenRectangle bounds) implements GuiElementRenderState{
        public RenderPipeline pipeline(){return RenderPipelines.GUI;}
        public TextureSetup textureSetup(){return TextureSetup.noTexture();}
        public void buildVertices(VertexConsumer out){for(int i=0;i<quads.size();i++){var q=quads.get(i);for(int n=0;n<4;n++)out.addVertexWith2DPose(pose,q.xy[n*2],q.xy[n*2+1]).setColor(q.color);}}
    }
    private record Face(double[][] points,int color,double cx,double cy,double cz){}
    private final List<Face> terrain=new ArrayList<>();
    private final Map<Long,Integer> plan=new HashMap<>();
    private static final int[][] DIRECTIONS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static final int[][] EDGES={{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
    private ClientLevel cachedLevel;
    private int[] cachedColors,sampleColors;
    // Private diagnostics let native tests measure real cache work without exposing a gameplay API.
    private long terrainRefreshes,terrainBuilds,projectionBuilds,projectionHits,modelBuilds,terrainVersion;
    private long projectedVersion=-1;
    private double projectedCenterX,projectedCenterZ,projectedYaw,projectedPitch,projectedOriginY,projectedScale;
    private int projectedX,projectedY,projectedW,projectedH,projectedCells;
    private List<Quad> projectedTerrain=List.of(),modelQuads=List.of(),modelTerrain;
    private long modelProjection=-1;
    private List<ProtectionData.Region> modelRegions=List.of();
    private UUID modelSelected;
    private ProtectionShape modelSaved,modelAddition,modelRemoval;
    private Mesh submitted;
    private static final ProtectionShape EMPTY=new ProtectionShape();
    private ProtectionShape previousSaved,previousDraft;
    private ProtectionShape addition=new ProtectionShape(),removal=new ProtectionShape();
    private void changes(ProtectionShape saved,ProtectionShape draft){
        if(Objects.equals(saved,previousSaved)&&Objects.equals(draft,previousDraft))return;previousSaved=saved;previousDraft=draft;
        try{addition=draft.subtract(saved);removal=saved.subtract(draft);}catch(IllegalArgumentException complex){addition=draft;removal=new ProtectionShape();}
    }
    private int cachedX=Integer.MIN_VALUE,cachedZ,cachedFloor,cachedCeiling,age,missing;
    double centerX,centerZ,yaw=-.65,pitch=.65;
    int cells=32;
    void center(ProtectionShape shape,int playerX,int playerZ){var b=shape.bounds();centerX=b==null?playerX+.5:(b.x0()+b.x1())/2d;centerZ=b==null?playerZ+.5:(b.z0()+b.z1())/2d;cells=b==null?32:Math.clamp(Math.max(b.x1()-b.x0(),b.z1()-b.z0())+6,8,320);age=100;}
    void tick(){age++;}
    int missing(){return missing;}
    private static long key(int x,int z){return (long)x<<32^(z&0xffffffffL);}
    private static int tint(int rgb){int r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255;int value=(r*3+g*6+b)/10;return 0xff000000|((value*3+80)/4)<<16|((value*3+112)/4)<<8|((value*2+56)/3);}
    private static int shade(int color,double factor){return 0xff000000|((int)(((color>>16)&255)*factor))<<16|((int)(((color>>8)&255)*factor))<<8|(int)((color&255)*factor);}
    private void refresh(int floor,int ceiling){
        var level=Minecraft.getInstance().level;
        if(level==null){if(cachedLevel!=null){cachedLevel=null;cachedColors=null;sampleColors=null;cachedX=Integer.MIN_VALUE;terrain.clear();plan.clear();missing=0;terrainVersion++;}return;}
        int sx=(int)Math.floor(centerX)-16,sz=(int)Math.floor(centerZ)-16;
        int low=Math.max(level.getMinY(),floor-1),high=Math.min(level.getMaxY()+1,Math.min(ceiling,low+32));if(high<=low)high=low+1;
        boolean sameWindow=cachedLevel==level&&cachedX==sx&&cachedZ==sz&&cachedFloor==low&&cachedCeiling==high;
        if(age<30&&sameWindow)return;
        age=0;terrainRefreshes++;missing=0;
        int height=high-low,size=32*32*height;int[] colors=sampleColors!=null&&sampleColors.length==size?sampleColors:new int[size];Arrays.fill(colors,0);var pos=new BlockPos.MutableBlockPos();
        for(int x=0;x<32;x++)for(int z=0;z<32;z++){
            if(!level.isLoaded(pos.set(sx+x,low,sz+z))){missing++;continue;}
            for(int y=0;y<height;y++){var state=level.getBlockState(pos.set(sx+x,low+y,sz+z));if(!state.isAir()){
                int color=tint(state.getMapColor(level,pos).col);colors[(y*32+z)*32+x]=color;
            }}
        }
        if(sameWindow&&Arrays.equals(colors,cachedColors)){sampleColors=colors;return;}
        sampleColors=cachedColors;cachedColors=colors;cachedLevel=level;cachedX=sx;cachedZ=sz;cachedFloor=low;cachedCeiling=high;terrainVersion++;terrainBuilds++;terrain.clear();plan.clear();
        for(int x=0;x<32;x++)for(int z=0;z<32;z++)for(int y=height-1;y>=0;y--){int color=colors[(y*32+z)*32+x];if(color!=0){plan.put(key(sx+x,sz+z),color);break;}}
        for(int y=0;y<height;y++)for(int z=0;z<32;z++)for(int x=0;x<32;x++){
            int color=colors[(y*32+z)*32+x];if(color==0)continue;
            for(int side=0;side<6;side++){
                int nx=x+DIRECTIONS[side][0],ny=y+DIRECTIONS[side][1],nz=z+DIRECTIONS[side][2];if(nx>=0&&nx<32&&ny>=0&&ny<height&&nz>=0&&nz<32&&colors[(ny*32+nz)*32+nx]!=0)continue;
                double a=sx+x,b=low+y,c=sz+z;double[][] face=switch(side){
                    case 0->new double[][]{{a+1,b,c},{a+1,b+1,c},{a+1,b+1,c+1},{a+1,b,c+1}};
                    case 1->new double[][]{{a,b,c+1},{a,b+1,c+1},{a,b+1,c},{a,b,c}};
                    case 2->new double[][]{{a,b+1,c},{a,b+1,c+1},{a+1,b+1,c+1},{a+1,b+1,c}};
                    case 3->new double[][]{{a,b,c+1},{a,b,c},{a+1,b,c},{a+1,b,c+1}};
                    case 4->new double[][]{{a+1,b,c+1},{a+1,b+1,c+1},{a,b+1,c+1},{a,b,c+1}};
                    default->new double[][]{{a,b,c},{a,b+1,c},{a+1,b+1,c},{a+1,b,c}};
                };
                terrain.add(new Face(face,shade(color,side==2?1:side==3?.45:side<2?.7:.85),a+.5,b+.5,c+.5));if(terrain.size()>=12000)return;
            }
        }
    }
    int worldX(double mouse,int x,int w,int h){double scale=Math.min(w,h)/(double)cells;return (int)Math.floor(centerX+(mouse-(x+w/2d))/scale);}
    int worldZ(double mouse,int y,int w,int h){double scale=Math.min(w,h)/(double)cells;return (int)Math.floor(centerZ+(mouse-(y+h/2d))/scale);}
    void render(GuiGraphicsExtractor g,int x,int y,int w,int h,boolean three,int floor,int ceiling,List<ProtectionData.Region> regions,UUID selected,ProtectionShape draft,boolean cut,List<ProtectionShape.Point> points){
        ProtectionData.Region current=null;for(int n=0;n<regions.size();n++){var r=regions.get(n);if(r.id().equals(selected)){current=r;break;}}
        var saved=current==null?EMPTY:current.shape();changes(saved,draft);
        refresh(floor,ceiling);card(g,x,y,w,h);g.enableScissor(x+1,y+1,x+w-1,y+h-1);
        if(!three){
            double scale=Math.min(w,h)/(double)cells;int ox=(int)Math.floor(centerX)-cells/2,oz=(int)Math.floor(centerZ)-cells/2;
            int step=Math.max(1,(cells+63)/64); // Overview remains bounded to at most 64² terrain samples.
            for(int xx=ox;xx<ox+cells;xx+=step)for(int zz=oz;zz<oz+cells;zz+=step){
                int px=(int)Math.floor(x+w/2d+(xx-centerX)*scale),py=(int)Math.floor(y+h/2d+(zz-centerZ)*scale);int color=plan.getOrDefault(key(xx+step/2,zz+step/2),((Math.floorDiv(xx,step)+Math.floorDiv(zz,step))&1)==0?0xff91a46c:0xff879b65);
                g.fill(px,py,(int)Math.ceil(px+step*scale),(int)Math.ceil(py+step*scale),color);
            }
            for(var r:regions)if(!r.id().equals(selected))shape2D(g,x,y,w,h,r.shape(),floor,r.parent().isPresent()?0x2255c7bc:0x223c5c38,0xff617547);
            shape2D(g,x,y,w,h,saved,floor,0x44597c45,0xff466037);
            shape2D(g,x,y,w,h,addition,floor,0x77d19a63,ORANGE);
            shape2D(g,x,y,w,h,removal,floor,0x77c84b3d,RED);
            var quads=new ArrayList<Quad>();for(int i=1;i<points.size();i++){var a=points.get(i-1);var b=points.get(i);line(quads,(float)(x+w/2d+(a.x()-centerX)*scale),(float)(y+h/2d+(a.z()-centerZ)*scale),(float)(x+w/2d+(b.x()-centerX)*scale),(float)(y+h/2d+(b.z()-centerZ)*scale),ORANGE,1.5f);}submit(g,quads,x,y,w,h);
        }else{
            double scale=Math.min(w,h)*.65/cells,originY=(floor+Math.min(ceiling,floor+32))/2d;
            submit(g,model(originY,x,y,w,h,scale,regions,selected,saved),x,y,w,h);
        }
        g.disableScissor();
    }
    private List<Quad> projected(double originY,int x,int y,int w,int h,double scale){
        if(projectedVersion==terrainVersion&&projectedCenterX==centerX&&projectedCenterZ==centerZ&&projectedYaw==yaw&&projectedPitch==pitch&&projectedOriginY==originY&&projectedScale==scale&&projectedX==x&&projectedY==y&&projectedW==w&&projectedH==h&&projectedCells==cells){projectionHits++;return projectedTerrain;}
        projectionBuilds++;projectedVersion=terrainVersion;projectedCenterX=centerX;projectedCenterZ=centerZ;projectedYaw=yaw;projectedPitch=pitch;projectedOriginY=originY;projectedScale=scale;projectedX=x;projectedY=y;projectedW=w;projectedH=h;projectedCells=cells;
        var ordered=new ArrayList<>(terrain);ordered.sort(Comparator.comparingDouble(f->depth(f.cx-centerX,f.cy-originY,f.cz-centerZ)));
        var quads=new ArrayList<Quad>(ordered.size());for(var face:ordered){
            if(Math.abs(face.cx-centerX)>cells*.55||Math.abs(face.cz-centerZ)>cells*.55)continue;
            float[] xy=new float[8];for(int n=0;n<4;n++)project(xy,n*2,face.points[n][0],face.points[n][1],face.points[n][2],originY,x,y,w,h,scale);quads.add(new Quad(xy,face.color));
        }
        return projectedTerrain=List.copyOf(quads);
    }
    private static boolean sameRegions(List<ProtectionData.Region> a,List<ProtectionData.Region> b){
        if(a.size()!=b.size())return false;for(int n=0;n<a.size();n++){var first=a.get(n);var second=b.get(n);if(!first.id().equals(second.id())||!first.shape().equals(second.shape()))return false;}return true;
    }
    private List<Quad> model(double originY,int x,int y,int w,int h,double scale,List<ProtectionData.Region> regions,UUID selected,ProtectionShape saved){
        var terrainQuads=projected(originY,x,y,w,h,scale);
        if(modelProjection==projectionBuilds&&modelTerrain==terrainQuads&&Objects.equals(modelSelected,selected)&&Objects.equals(modelSaved,saved)&&modelAddition==addition&&modelRemoval==removal&&sameRegions(modelRegions,regions))return modelQuads;
        var quads=new ArrayList<Quad>(terrainQuads.size()+48);quads.addAll(terrainQuads);
        for(var r:regions)if(!r.id().equals(selected))wire(quads,r.shape(),0x99728553,originY,x,y,w,h,scale);
        wire(quads,saved,0xff6a8a46,originY,x,y,w,h,scale);wire(quads,addition,0xffe4a66c,originY,x,y,w,h,scale);wire(quads,removal,RED,originY,x,y,w,h,scale);
        modelBuilds++;modelProjection=projectionBuilds;modelTerrain=terrainQuads;modelRegions=List.copyOf(regions);modelSelected=selected;modelSaved=saved;modelAddition=addition;modelRemoval=removal;return modelQuads=List.copyOf(quads);
    }
    private void shape2D(GuiGraphicsExtractor g,int x,int y,int w,int h,ProtectionShape shape,int floor,int fill,int border){
        double scale=Math.min(w,h)/(double)cells;for(var b:shape.boxes())if(floor>=b.y0()&&floor<b.y1()&&b.x1()>=centerX-cells/2d&&b.x0()<=centerX+cells/2d&&b.z1()>=centerZ-cells/2d&&b.z0()<=centerZ+cells/2d){
            int a=(int)Math.floor(x+w/2d+(b.x0()-centerX)*scale),c=(int)Math.floor(y+h/2d+(b.z0()-centerZ)*scale),d=(int)Math.ceil(x+w/2d+(b.x1()-centerX)*scale),e=(int)Math.ceil(y+h/2d+(b.z1()-centerZ)*scale);
            g.fill(a,c,d,e,fill);g.outline(a,c,Math.max(1,d-a),Math.max(1,e-c),border);
        }
    }
    /** Static teaching diagram: the same projection as the editor, with no world/chunk reads. */
    void example(GuiGraphicsExtractor g,int x,int y,int w,int h,ProtectionShape saved,ProtectionShape draft){
        changes(saved,draft);center(draft,0,0);var b=draft.bounds();double scale=Math.min(w/22d,h/15d),originY=(b.y0()+b.y1())/2d;
        var quads=new ArrayList<Quad>();wire(quads,saved,INK,originY,x,y,w,h,scale);wire(quads,addition,ORANGE,originY,x,y,w,h,scale);submit(g,quads,x,y,w,h);
    }
    private double depth(double x,double y,double z){double z1=x*Math.sin(yaw)+z*Math.cos(yaw);return z1*Math.cos(pitch)+y*Math.sin(pitch);}
    private void project(float[] out,int offset,double x,double y,double z,double originY,int sx,int sy,int w,int h,double scale){x-=centerX;z-=centerZ;y-=originY;double xx=x*Math.cos(yaw)-z*Math.sin(yaw),zz=x*Math.sin(yaw)+z*Math.cos(yaw);out[offset]=(float)(sx+w/2d+xx*scale);out[offset+1]=(float)(sy+h/2d+(zz*Math.sin(pitch)-y*Math.cos(pitch))*scale);}
    private void wire(List<Quad> out,ProtectionShape shape,int color,double originY,int x,int y,int w,int h,double scale){for(var b:shape.boxes()){
        if(b.x1()<centerX-cells||b.x0()>centerX+cells||b.z1()<centerZ-cells||b.z0()>centerZ+cells)continue;
        double[][] p={{b.x0(),b.y0(),b.z0()},{b.x1(),b.y0(),b.z0()},{b.x1(),b.y0(),b.z1()},{b.x0(),b.y0(),b.z1()},{b.x0(),b.y1(),b.z0()},{b.x1(),b.y1(),b.z0()},{b.x1(),b.y1(),b.z1()},{b.x0(),b.y1(),b.z1()}};
        float[] xy=new float[16];for(int n=0;n<8;n++)project(xy,n*2,p[n][0],p[n][1],p[n][2],originY,x,y,w,h,scale);
        for(int[] edge:EDGES)line(out,xy[edge[0]*2],xy[edge[0]*2+1],xy[edge[1]*2],xy[edge[1]*2+1],color,1.2f);
    }}
    private static void line(List<Quad> out,float ax,float ay,float bx,float by,int color,float width){float dx=bx-ax,dy=by-ay,len=(float)Math.sqrt(dx*dx+dy*dy);if(len<.01)return;float px=-dy/len*width/2,py=dx/len*width/2;out.add(new Quad(new float[]{ax+px,ay+py,bx+px,by+py,bx-px,by-py,ax-px,ay-py},color));}
    private void submit(GuiGraphicsExtractor g,List<Quad> quads,int x,int y,int w,int h){
        if(quads.isEmpty())return;
        if(submitted==null||submitted.quads!=quads||submitted.bounds.left()!=x+1||submitted.bounds.top()!=y+1||submitted.bounds.width()!=w-2||submitted.bounds.height()!=h-2||!submitted.pose.equals(g.pose())){
            var rectangle=new ScreenRectangle(x+1,y+1,w-2,h-2);submitted=new Mesh(new Matrix3x2f(g.pose()),List.copyOf(quads),rectangle,rectangle);
        }
        ((GuiStateAccessor)g).umbral$guiState().addGuiElement(submitted);
    }
}
