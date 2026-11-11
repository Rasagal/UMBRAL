// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

/** A quiet-building debounce and rate limit. Idle construction never repeats its notice. */
public final class ProtectionNotice {
    private long lastSent=-600,through=Long.MIN_VALUE;
    public boolean ready(long now,long newestPlacement){return newestPlacement>through&&now-newestPlacement>=40&&now-lastSent>=600;}
    public void sent(long now,long newestPlacement){lastSent=now;through=newestPlacement;}
}
