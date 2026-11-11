// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

/** Installed capacity is permanent; editing only changes how much of it is assigned. */
public final class ProtectionEconomy {
    public static final int MAX_MATRICES=4096;
    public static long capacity(int matrices){
        int n=Math.clamp(matrices,0,MAX_MATRICES);return (long)Math.min(n,8)*2048+(long)Math.clamp(n-8,0,24)*1024+(long)Math.max(0,n-32)*512;
    }
    /** -1 means the technical account limit cannot accommodate the proposal. */
    public static int additionalMatrices(int installed,long required){
        int current=Math.clamp(installed,0,MAX_MATRICES);
        if(required<=capacity(current))return 0;
        if(required>capacity(MAX_MATRICES))return -1;
        int low=current,high=MAX_MATRICES;
        while(low<high){int mid=(low+high)>>>1;if(capacity(mid)>=required)high=mid;else low=mid+1;}
        return low-current;
    }
    private ProtectionEconomy(){}
}
