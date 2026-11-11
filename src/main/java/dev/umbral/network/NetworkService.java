// SPDX-License-Identifier: MPL-2.0

package dev.umbral.network;

import dev.umbral.block.CopperCableBlock;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.block.DeviceKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

public final class NetworkService {
    private static final Map<ServerLevel, Map<UUID, Cached>> CACHE = new WeakHashMap<>();
    private static final Map<ServerLevel, Long> SCANS = new WeakHashMap<>();
    private record Cached(BlockPos core, UUID owner, long tick, NetworkTopology.Snapshot snapshot) { }

    public enum Status {
        UNBOUND, WIRED, WIRELESS, NO_SIGNAL, UNAVAILABLE, CORE_MISSING, LIMIT_REACHED;
        public boolean online() { return this == WIRED || this == WIRELESS; }
        public String key() { return "status.umbral." + name().toLowerCase(java.util.Locale.ROOT); }
    }

    public record Inspection(Status status, String networkName, int nodes, int conductors) {
        public boolean online() { return status.online(); }
    }

    public static Inspection inspect(ServerLevel level, DeviceBlockEntity device) {
        return inspect(level,device,false);
    }

    /** A launch/transfer must re-read its loaded backbone, without evicting other installations. */
    public static Inspection inspectFresh(ServerLevel level,DeviceBlockEntity device){
        return inspect(level,device,true);
    }

    private static Inspection inspect(ServerLevel level,DeviceBlockEntity device,boolean fresh){
        if(fresh&&(device.getLevel()!=level||!level.isLoaded(device.getBlockPos())||level.getBlockEntity(device.getBlockPos())!=device||device.isRemoved()))return unavailable(Status.UNAVAILABLE);
        if(device.kind()==DeviceKind.FENCE&&!dev.umbral.fence.FenceService.head(device)){var head=dev.umbral.fence.FenceService.controller(device);return head!=null&&level.getGameTime()-device.fence().seen<=10?inspect(level,head,fresh):unavailable(Status.NO_SIGNAL);}
        NetworkLink link = device.link();
        if (link == null || device.owner() == null) return unavailable(Status.UNBOUND);
        if (!link.dimension().equals(level.dimension().identifier())) return unavailable(Status.CORE_MISSING);
        if (!level.isLoaded(link.core())) return unavailable(Status.UNAVAILABLE);
        if (!(level.getBlockEntity(link.core()) instanceof DeviceBlockEntity core)
                || core.kind() != DeviceKind.CORE || !device.owner().equals(core.owner())
                || !link.equals(core.link())) return unavailable(Status.CORE_MISSING);

        Map<UUID, Cached> worldCache = CACHE.computeIfAbsent(level, ignored -> new HashMap<>());
        long tick = level.getGameTime();
        if (worldCache.size() > 128) worldCache.entrySet().removeIf(entry -> tick - entry.getValue().tick >= 20);
        Cached cached = worldCache.get(link.network());
        if (fresh || cached == null || !cached.core.equals(link.core()) || !cached.owner.equals(core.owner())
                || tick < cached.tick || tick - cached.tick >= 20) {
            SCANS.merge(level,1L,Long::sum);
            var snapshot = NetworkTopology.scan(link.network(), point(link.core()),
                    p -> cell(level, p, core.owner()), NetworkTopology.MAX_CONDUCTORS);
            cached = new Cached(link.core(), core.owner(), tick, snapshot);
            worldCache.put(link.network(), cached);
        }
        var result = cached.snapshot;
        Status status = Status.valueOf(result.connectionTo(point(device.getBlockPos()), device.kind() == DeviceKind.RELAY).name());
        String name = core.label().isEmpty() ? "UMBRAL · " + coordinates(link.core()) : core.label();
        return new Inspection(status, name, Math.max(0, result.emitters().size() - 1), result.conductors());
    }

    private static NetworkTopology.Cell cell(ServerLevel level, GridPoint point, UUID owner) {
        BlockPos pos = position(point);
        if (!level.isLoaded(pos)) return NetworkTopology.Cell.UNLOADED;
        if (level.getBlockState(pos).getBlock() instanceof CopperCableBlock) return NetworkTopology.Cell.CABLE;
        if (!(level.getBlockEntity(pos) instanceof DeviceBlockEntity device) || !owner.equals(device.owner())) {
            return NetworkTopology.Cell.EMPTY;
        }
        if(device.kind()==DeviceKind.FILTER&&!dev.umbral.block.FilterBlock.base(device.getBlockState()))return NetworkTopology.Cell.EMPTY;
        if(device.kind()==DeviceKind.FENCE&&!dev.umbral.fence.FenceService.head(device))return NetworkTopology.Cell.EMPTY;
        var kind = switch (device.kind()) {
            case CORE -> NetworkTopology.Kind.CORE;
            case RELAY -> NetworkTopology.Kind.RELAY;
            case TERMINAL -> NetworkTopology.Kind.TERMINAL;
            case CAMERA -> NetworkTopology.Kind.CAMERA;
            case DOOR -> NetworkTopology.Kind.DOOR;
            case READER -> NetworkTopology.Kind.READER;
            case FENCE, FILTER, SENSOR, PULSE_MINE, NEXO, DOCK, TURRET, CENTELLA -> NetworkTopology.Kind.TURRET;
        };
        return new NetworkTopology.Cell(kind, device.link() == null ? null : device.link().network());
    }

    public static void invalidate(Level level) { if (level instanceof ServerLevel server) CACHE.remove(server); }
    public static void invalidate(Level level,NetworkLink link){
        if(level instanceof ServerLevel server&&link!=null&&link.dimension().equals(level.dimension().identifier())){
            var networks=CACHE.get(server);if(networks!=null)networks.remove(link.network());
        }
    }
    /** Diagnostic only; invalidating a cache never changes the lifetime scan count. */
    public static long topologyScans(ServerLevel level){return SCANS.getOrDefault(level,0L);}
    public static void clear() { CACHE.clear();SCANS.clear(); }
    private static Inspection unavailable(Status status) { return new Inspection(status, "", 0, 0); }
    public static GridPoint point(BlockPos pos) { return new GridPoint(pos.getX(), pos.getY(), pos.getZ()); }
    public static BlockPos position(GridPoint pos) { return new BlockPos(pos.x(), pos.y(), pos.z()); }
    public static String coordinates(BlockPos pos) { return pos.getX() + " / " + pos.getY() + " / " + pos.getZ(); }
    private NetworkService() { }
}
