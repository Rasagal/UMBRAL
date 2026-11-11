// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

import java.util.*;

/** Exact block-grid geometry, independent of world loading and Minecraft client classes. */
public final class ProtectionShape {
    public static final int MAX_PARTS=128, MAX_SPAN=256, MAX_POINTS=32;
    public record Point(int x,int z){}
    /** Half-open bounds: a selected block at the upper corner is included by adding one. */
    public record Box(int x0,int y0,int z0,int x1,int y1,int z1){
        public Box{if(x0>=x1||y0>=y1||z0>=z1)throw new IllegalArgumentException("Empty protection box");}
        public boolean contains(int x,int y,int z){return x>=x0&&x<x1&&y>=y0&&y<y1&&z>=z0&&z<z1;}
        public boolean intersects(Box b){return x0<b.x1&&x1>b.x0&&y0<b.y1&&y1>b.y0&&z0<b.z1&&z1>b.z0;}
        public Box intersection(Box b){return intersects(b)?new Box(Math.max(x0,b.x0),Math.max(y0,b.y0),Math.max(z0,b.z0),Math.min(x1,b.x1),Math.min(y1,b.y1),Math.min(z1,b.z1)):null;}
        public List<Box> subtract(Box b){
            var i=intersection(b);if(i==null)return List.of(this);var out=new ArrayList<Box>();
            add(out,x0,y0,z0,i.x0,y1,z1);add(out,i.x1,y0,z0,x1,y1,z1);
            add(out,i.x0,y0,z0,i.x1,i.y0,z1);add(out,i.x0,i.y1,z0,i.x1,y1,z1);
            add(out,i.x0,i.y0,z0,i.x1,i.y1,i.z0);add(out,i.x0,i.y0,i.z1,i.x1,i.y1,z1);return out;
        }
        public long volume(){return (long)(x1-x0)*(y1-y0)*(z1-z0);}
        private static void add(List<Box> out,int a,int b,int c,int d,int e,int f){if(a<d&&b<e&&c<f)out.add(new Box(a,b,c,d,e,f));}
    }
    private final List<Box> boxes;
    private volatile long cachedCost=-1;
    public ProtectionShape(){boxes=List.of();}
    public ProtectionShape(List<Box> input){
        if(input.size()>MAX_PARTS)throw new IllegalArgumentException("Too many parts");
        List<Box> out=new ArrayList<>();for(var box:input){var incoming=new ArrayList<>(List.of(box));for(var old:out){var next=new ArrayList<Box>();for(var part:incoming)next.addAll(part.subtract(old));incoming=next;}out.addAll(incoming);if(out.size()>MAX_PARTS*6)throw new IllegalArgumentException("Too complex");}
        merge(out);if(out.size()>MAX_PARTS)throw new IllegalArgumentException("Too many parts");
        out.sort(Comparator.comparingInt(Box::x0).thenComparingInt(Box::z0).thenComparingInt(Box::y0));boxes=List.copyOf(out);
        var b=bounds();if(b!=null&&((long)b.x1-b.x0>MAX_SPAN||(long)b.z1-b.z0>MAX_SPAN))throw new IllegalArgumentException("Protection span exceeds limit");
    }
    public List<Box> boxes(){return boxes;}
    public boolean empty(){return boxes.isEmpty();}
    public boolean contains(int x,int y,int z){return boxes.stream().anyMatch(b->b.contains(x,y,z));}
    public boolean intersects(ProtectionShape other){for(var a:boxes)for(var b:other.boxes)if(a.intersects(b))return true;return false;}
    public boolean contains(ProtectionShape other){return other.subtract(this).empty();}
    public ProtectionShape add(ProtectionShape other){var out=new ArrayList<>(boxes);out.addAll(other.boxes);return new ProtectionShape(out);}
    public ProtectionShape subtract(ProtectionShape other){
        var out=new ArrayList<>(boxes);for(var cutter:other.boxes){var next=new ArrayList<Box>();for(var box:out)next.addAll(box.subtract(cutter));out=next;if(out.size()>MAX_PARTS*6)throw new IllegalArgumentException("Too complex");}return new ProtectionShape(out);
    }
    public Box bounds(){if(boxes.isEmpty())return null;int a=Integer.MAX_VALUE,b=a,c=a,d=Integer.MIN_VALUE,e=d,f=d;for(var v:boxes){a=Math.min(a,v.x0);b=Math.min(b,v.y0);c=Math.min(c,v.z0);d=Math.max(d,v.x1);e=Math.max(e,v.y1);f=Math.max(f,v.z1);}return new Box(a,b,c,d,e,f);}
    public long volume(){return boxes.stream().mapToLong(Box::volume).sum();}
    /** Horizontal surface dominates price; vertical length is charged once per column. */
    public long cost(){
        if(cachedCost>=0)return cachedCost;
        if(empty())return 0;var xs=new TreeSet<Integer>();for(var b:boxes){xs.add(b.x0);xs.add(b.x1);}var x=new ArrayList<>(xs);long total=0;
        for(int i=0;i<x.size()-1;i++){
            int low=x.get(i),high=x.get(i+1);var active=boxes.stream().filter(b->b.x0<=low&&b.x1>=high).toList();var zs=new TreeSet<Integer>();for(var b:active){zs.add(b.z0);zs.add(b.z1);}var z=new ArrayList<>(zs);
            for(int j=0;j<z.size()-1;j++){
                int near=z.get(j),far=z.get(j+1);var vertical=active.stream().filter(b->b.z0<=near&&b.z1>=far).sorted(Comparator.comparingInt(Box::y0)).toList();
                long height=0;int end=Integer.MIN_VALUE;for(var b:vertical){height+=Math.max(0,b.y1-Math.max(b.y0,end));end=Math.max(end,b.y1);}
                if(height>0)total+=(long)(high-low)*(far-near)*(4+Math.max(0,height-16+15)/16);
            }
        }return cachedCost=total;
    }
    public static ProtectionShape rectangle(int ax,int ay,int az,int bx,int by,int bz){return new ProtectionShape(List.of(new Box(Math.min(ax,bx),Math.min(ay,by),Math.min(az,bz),Math.max(ax,bx)+1,Math.max(ay,by)+1,Math.max(az,bz)+1)));}
    public static ProtectionShape polygon(List<Point> points,int floor,int ceiling){
        if(points.size()<3||points.size()>MAX_POINTS||floor>=ceiling)throw new IllegalArgumentException("Invalid contour");
        long area=0;for(int n=0;n<points.size();n++){
            var a=points.get(n);var b=points.get((n+1)%points.size());if(a.equals(b))throw new IllegalArgumentException("Repeated vertex");area+=(long)a.x*b.z-(long)b.x*a.z;
            for(int j=n+1;j<points.size();j++){
                if(j==n+1||n==0&&j==points.size()-1)continue;var c=points.get(j);var d=points.get((j+1)%points.size());if(segments(a,b,c,d))throw new IllegalArgumentException("Crossing contour");
            }
        }if(area==0)throw new IllegalArgumentException("Empty contour");
        int x0=points.stream().mapToInt(Point::x).min().orElseThrow(),x1=points.stream().mapToInt(Point::x).max().orElseThrow(),z0=points.stream().mapToInt(Point::z).min().orElseThrow(),z1=points.stream().mapToInt(Point::z).max().orElseThrow();
        if((long)x1-x0>MAX_SPAN||(long)z1-z0>MAX_SPAN)throw new IllegalArgumentException("Contour too large");
        var out=new ArrayList<Box>();for(int z=z0;z<z1;z++){
            int run=Integer.MIN_VALUE;for(int x=x0;x<=x1;x++){
                boolean inside=x<x1&&inside(points,x+.5,z+.5);if(inside&&run==Integer.MIN_VALUE)run=x;
                if(!inside&&run!=Integer.MIN_VALUE){out.add(new Box(run,floor,z,x,ceiling,z+1));run=Integer.MIN_VALUE;}
            }
        }merge(out);return new ProtectionShape(out);
    }
    private static boolean inside(List<Point> p,double x,double z){boolean result=false;for(int i=0,j=p.size()-1;i<p.size();j=i++){
        var a=p.get(i);var b=p.get(j);if((a.z>z)!=(b.z>z)&&x<(double)(b.x-a.x)*(z-a.z)/(b.z-a.z)+a.x)result=!result;
    }return result;}
    private static long cross(Point a,Point b,Point c){return (long)(b.x-a.x)*(c.z-a.z)-(long)(b.z-a.z)*(c.x-a.x);}
    private static boolean on(Point a,Point b,Point p){return cross(a,b,p)==0&&p.x>=Math.min(a.x,b.x)&&p.x<=Math.max(a.x,b.x)&&p.z>=Math.min(a.z,b.z)&&p.z<=Math.max(a.z,b.z);}
    private static boolean segments(Point a,Point b,Point c,Point d){long x=cross(a,b,c),y=cross(a,b,d),z=cross(c,d,a),w=cross(c,d,b);return on(a,b,c)||on(a,b,d)||on(c,d,a)||on(c,d,b)||(x>0)!=(y>0)&&(z>0)!=(w>0);}
    private static void merge(List<Box> values){boolean again;do{again=false;outer:for(int i=0;i<values.size();i++)for(int j=i+1;j<values.size();j++){
        var a=values.get(i);var b=values.get(j);Box joined=null;
        if(a.y0==b.y0&&a.y1==b.y1&&a.z0==b.z0&&a.z1==b.z1&&(a.x1==b.x0||b.x1==a.x0))joined=new Box(Math.min(a.x0,b.x0),a.y0,a.z0,Math.max(a.x1,b.x1),a.y1,a.z1);
        else if(a.x0==b.x0&&a.x1==b.x1&&a.z0==b.z0&&a.z1==b.z1&&(a.y1==b.y0||b.y1==a.y0))joined=new Box(a.x0,Math.min(a.y0,b.y0),a.z0,a.x1,Math.max(a.y1,b.y1),a.z1);
        else if(a.x0==b.x0&&a.x1==b.x1&&a.y0==b.y0&&a.y1==b.y1&&(a.z1==b.z0||b.z1==a.z0))joined=new Box(a.x0,a.y0,Math.min(a.z0,b.z0),a.x1,a.y1,Math.max(a.z1,b.z1));
        if(joined!=null){values.set(i,joined);values.remove(j);again=true;break outer;}
    }}while(again);}
    @Override public boolean equals(Object o){return o instanceof ProtectionShape s&&boxes.equals(s.boxes);}
    @Override public int hashCode(){return boxes.hashCode();}
}
