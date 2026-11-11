// SPDX-License-Identifier: MPL-2.0

package dev.umbral.network;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Resolves only the loaded cable backbone. A relay never repeats wirelessly. */
public final class NetworkTopology {
    public static final int RADIO_RADIUS = 16;
    public static final int MAX_CONDUCTORS = 4096;

    public enum Kind { EMPTY, UNLOADED, CABLE, CORE, RELAY, TERMINAL, CAMERA, DOOR, READER, TURRET }
    public enum ScanState { READY, CORE_MISSING, CORE_UNLOADED, LIMIT_REACHED }
    public enum Link { WIRED, WIRELESS, NO_SIGNAL, UNAVAILABLE, LIMIT_REACHED, CORE_MISSING }

    public record Cell(Kind kind, UUID network) {
        public static final Cell EMPTY = new Cell(Kind.EMPTY, null);
        public static final Cell UNLOADED = new Cell(Kind.UNLOADED, null);
        public static final Cell CABLE = new Cell(Kind.CABLE, null);
    }

    @FunctionalInterface
    public interface WorldView {
        /** Must not load a chunk as a side effect. */
        Cell at(GridPoint point);
    }

    public record Snapshot(ScanState state, Set<GridPoint> wired, Set<GridPoint> emitters,
                           boolean incomplete, int conductors) {
        public Snapshot {
            wired = Set.copyOf(wired);
            emitters = Set.copyOf(emitters);
        }

        public Link connectionTo(GridPoint point, boolean relay) {
            if (state == ScanState.CORE_UNLOADED) return Link.UNAVAILABLE;
            if (state == ScanState.CORE_MISSING) return Link.CORE_MISSING;
            if (state == ScanState.LIMIT_REACHED) return Link.LIMIT_REACHED;
            if (wired.contains(point)) return Link.WIRED;
            // Relays must join the backbone by cable, even when inside another emitter's range.
            if (!relay && emitters.stream().anyMatch(p -> p.distanceSquared(point) <= RADIO_RADIUS * RADIO_RADIUS)) {
                return Link.WIRELESS;
            }
            return incomplete ? Link.UNAVAILABLE : Link.NO_SIGNAL;
        }
    }

    public static Snapshot scan(UUID network, GridPoint root, WorldView world, int limit) {
        if (network == null || limit < 1) throw new IllegalArgumentException("A network and a positive scan limit are required");
        Cell core = world.at(root);
        if (core.kind == Kind.UNLOADED) return empty(ScanState.CORE_UNLOADED);
        if (core.kind != Kind.CORE || !network.equals(core.network)) return empty(ScanState.CORE_MISSING);

        Set<GridPoint> checked = new HashSet<>();
        Set<GridPoint> wired = new HashSet<>();
        Set<GridPoint> emitters = new HashSet<>();
        ArrayDeque<GridPoint> pending = new ArrayDeque<>();
        checked.add(root);
        pending.add(root);
        wired.add(root);
        emitters.add(root);
        int conductors = 0;
        boolean incomplete = false;

        while (!pending.isEmpty()) {
            if (++conductors > limit) {
                // Never report a partially scanned, over-budget network as healthy.
                return new Snapshot(ScanState.LIMIT_REACHED, Set.of(), Set.of(), incomplete, limit);
            }
            GridPoint current = pending.removeFirst();
            for (GridPoint neighbor : current.neighbors()) {
                if (!checked.add(neighbor)) continue;
                Cell cell = world.at(neighbor);
                if (cell.kind == Kind.UNLOADED) {
                    incomplete = true;
                } else if (cell.kind == Kind.CABLE) {
                    wired.add(neighbor);
                    pending.addLast(neighbor);
                } else if (network.equals(cell.network)) {
                    if (cell.kind == Kind.RELAY) {
                        wired.add(neighbor);
                        emitters.add(neighbor);
                        pending.addLast(neighbor);
                    } else if (cell.kind == Kind.TERMINAL || cell.kind == Kind.CAMERA || cell.kind == Kind.DOOR || cell.kind == Kind.READER || cell.kind == Kind.TURRET) {
                        // Endpoints may be wired, but cannot become a hidden bridge or repeater.
                        wired.add(neighbor);
                    }
                }
            }
        }
        return new Snapshot(ScanState.READY, wired, emitters, incomplete, conductors);
    }

    private static Snapshot empty(ScanState state) {
        return new Snapshot(state, Set.of(), Set.of(), false, 0);
    }

    private NetworkTopology() { }
}
