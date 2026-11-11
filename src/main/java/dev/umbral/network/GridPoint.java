// SPDX-License-Identifier: MPL-2.0

package dev.umbral.network;

import java.util.List;

/** Integer world coordinates; no client or Minecraft state is required to resolve a network. */
public record GridPoint(int x, int y, int z) {
    public List<GridPoint> neighbors() {
        return List.of(new GridPoint(x + 1, y, z), new GridPoint(x - 1, y, z),
                new GridPoint(x, y + 1, z), new GridPoint(x, y - 1, z),
                new GridPoint(x, y, z + 1), new GridPoint(x, y, z - 1));
    }

    public long distanceSquared(GridPoint other) {
        long dx = (long) x - other.x;
        long dy = (long) y - other.y;
        long dz = (long) z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
