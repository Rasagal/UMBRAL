// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

import java.util.ArrayList;

/** Shared block-grid editing rules for the guided tool, preview and server. */
public final class ProtectionEdits {
    public static ProtectionShape footprint(int ax,int ay,int az,int bx,int by,int bz,int minY,int maxY){
        int low=Math.max(minY,Math.min(ay,by)-2),high=Math.min(maxY+1,Math.max(ay,by)+14);
        return ProtectionShape.rectangle(ax,low,az,bx,high-1,bz);
    }
    /** Only the pending addition changes height. Saved geometry is never redrawn or stretched. */
    public static ProtectionShape reheightAdded(ProtectionShape saved,ProtectionShape draft,int low,int high){
        if(low>=high)throw new IllegalArgumentException("Empty height");
        var parts=new ArrayList<ProtectionShape.Box>();
        for(var b:draft.subtract(saved).boxes())parts.add(new ProtectionShape.Box(b.x0(),low,b.z0(),b.x1(),high,b.z1()));
        return saved.add(new ProtectionShape(parts));
    }
    public static boolean thinAddition(ProtectionShape saved,ProtectionShape draft){
        return draft.subtract(saved).boxes().stream().anyMatch(b->b.y1()-b.y0()==1);
    }
    private ProtectionEdits(){}
}
