// SPDX-License-Identifier: MPL-2.0

package dev.umbral.camera;

import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlock;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.network.NetworkLink;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sessions authorize viewing only. They never move a player, change game mode or load chunks. */
public final class CameraProtocol {
    private static final UUID NO_SESSION = new UUID(0, 0);
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Long> LAST_LIST = new HashMap<>();
    private static final Map<UUID, Long> LAST_OPEN = new HashMap<>();
    private static final int LEASE_TICKS = 60;

    public record ListRequest(BlockPos terminal, long request) implements CustomPacketPayload {
        public static final Type<ListRequest> TYPE = new Type<>(UmbralContent.id("camera_list_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ListRequest> CODEC = new StreamCodec<>() {
            public ListRequest decode(RegistryFriendlyByteBuf b) { return new ListRequest(b.readBlockPos(), b.readLong()); }
            public void encode(RegistryFriendlyByteBuf b, ListRequest p) { b.writeBlockPos(p.terminal); b.writeLong(p.request); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record CameraEntry(BlockPos position, String label, CameraAccess.State state) { }

    public record Listing(BlockPos terminal, long request, CameraAccess.State state, List<CameraEntry> entries) implements CustomPacketPayload {
        public Listing { entries = List.copyOf(entries); }
        public static final Type<Listing> TYPE = new Type<>(UmbralContent.id("camera_list"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Listing> CODEC = new StreamCodec<>() {
            public Listing decode(RegistryFriendlyByteBuf b) {
                BlockPos terminal = b.readBlockPos(); long request = b.readLong();
                CameraAccess.State state = b.readEnum(CameraAccess.State.class);
                int count = b.readVarInt();
                if (count < 0 || count > CameraDirectory.MAX_CAMERAS) throw new IllegalArgumentException("Invalid camera count");
                List<CameraEntry> entries = new ArrayList<>(count);
                for (int i = 0; i < count; i++) entries.add(new CameraEntry(b.readBlockPos(), b.readUtf(48), b.readEnum(CameraAccess.State.class)));
                return new Listing(terminal, request, state, entries);
            }
            public void encode(RegistryFriendlyByteBuf b, Listing p) {
                b.writeBlockPos(p.terminal); b.writeLong(p.request); b.writeEnum(p.state); b.writeVarInt(p.entries.size());
                for (CameraEntry e : p.entries) { b.writeBlockPos(e.position); b.writeUtf(e.label, 48); b.writeEnum(e.state); }
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OpenRequest(BlockPos terminal, BlockPos camera, long request) implements CustomPacketPayload {
        public static final Type<OpenRequest> TYPE = new Type<>(UmbralContent.id("camera_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRequest> CODEC = new StreamCodec<>() {
            public OpenRequest decode(RegistryFriendlyByteBuf b) { return new OpenRequest(b.readBlockPos(), b.readBlockPos(), b.readLong()); }
            public void encode(RegistryFriendlyByteBuf b, OpenRequest p) { b.writeBlockPos(p.terminal); b.writeBlockPos(p.camera); b.writeLong(p.request); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Feed(UUID session, long request, BlockPos terminal, BlockPos camera, String label,
                       Direction direction, UUID identity, CameraMotion.State motion, CameraAccess.State state) implements CustomPacketPayload {
        public static final Type<Feed> TYPE = new Type<>(UmbralContent.id("camera_feed"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Feed> CODEC = new StreamCodec<>() {
            public Feed decode(RegistryFriendlyByteBuf b) {
                return new Feed(b.readUUID(), b.readLong(), b.readBlockPos(), b.readBlockPos(), b.readUtf(48),
                        b.readEnum(Direction.class), b.readUUID(), readMotion(b), b.readEnum(CameraAccess.State.class));
            }
            public void encode(RegistryFriendlyByteBuf b, Feed p) {
                b.writeUUID(p.session); b.writeLong(p.request); b.writeBlockPos(p.terminal); b.writeBlockPos(p.camera);
                b.writeUtf(p.label, 48); b.writeEnum(p.direction); b.writeUUID(p.identity); writeMotion(b, p.motion); b.writeEnum(p.state);
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static CameraMotion.State readMotion(RegistryFriendlyByteBuf b) {
        var state = new CameraMotion.State(b.readLong(), b.readLong(), b.readVarInt(), b.readFloat(), b.readFloat(),
                b.readFloat(), b.readFloat(), b.readBoolean(), b.readVarInt(), b.readBoolean(), b.readBoolean());
        if (state.duration() < 0 || state.duration() > 600 || state.range() < 15 || state.range() > 30
                || !validAngle(state.fromYaw(), -30, 30) || !validAngle(state.yaw(), -30, 30)
                || !validAngle(state.fromPitch(), -8, 20) || !validAngle(state.pitch(), -8, 20)) throw new IllegalArgumentException("Invalid camera motion");
        return state;
    }
    private static boolean validAngle(float angle, float min, float max) { return Float.isFinite(angle) && angle >= min && angle <= max; }
    private static void writeMotion(RegistryFriendlyByteBuf b, CameraMotion.State s) {
        b.writeLong(s.revision()); b.writeLong(s.started()); b.writeVarInt(s.duration()); b.writeFloat(s.fromYaw()); b.writeFloat(s.fromPitch());
        b.writeFloat(s.yaw()); b.writeFloat(s.pitch()); b.writeBoolean(s.scan()); b.writeVarInt(s.range()); b.writeBoolean(s.active()); b.writeBoolean(s.blocked());
    }
    public record Steer(UUID session, CameraMotion.Command command) implements CustomPacketPayload {
        public static final Type<Steer> TYPE = new Type<>(UmbralContent.id("camera_steer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Steer> CODEC = new StreamCodec<>() {
            public Steer decode(RegistryFriendlyByteBuf b) { return new Steer(b.readUUID(), b.readEnum(CameraMotion.Command.class)); }
            public void encode(RegistryFriendlyByteBuf b, Steer p) { b.writeUUID(p.session); b.writeEnum(p.command); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Control(UUID session, boolean close) implements CustomPacketPayload {
        public static final Type<Control> TYPE = new Type<>(UmbralContent.id("camera_control"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = new StreamCodec<>() {
            public Control decode(RegistryFriendlyByteBuf b) { return new Control(b.readUUID(), b.readBoolean()); }
            public void encode(RegistryFriendlyByteBuf b, Control p) { b.writeUUID(p.session); b.writeBoolean(p.close); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static final class Session {
        final UUID id = UUID.randomUUID();
        final long request;
        final ServerLevel level;
        final DeviceBlockEntity terminal;
        final DeviceBlockEntity camera;
        final NetworkLink link;
        long expires;
        long lastKeepAlive;
        long lastSteer = Long.MIN_VALUE;
        Session(long request, ServerLevel level, CameraAccess.Check check, long now) {
            this.request = request; this.level = level; terminal = check.terminal(); camera = check.camera();
            link = terminal.link(); expires = now + LEASE_TICKS; lastKeepAlive = now - 10;
        }
        Feed feed(CameraAccess.State state) {
            return new Feed(id, request, terminal.getBlockPos(), camera.getBlockPos(), camera.label(),
                    camera.getBlockState().getValue(DeviceBlock.FACING), camera.identity(), camera.cameraMotion().state(), state);
        }
    }

    public static Listing list(ServerPlayer player, ListRequest request) {
        CameraAccess.State state = CameraAccess.terminal(player, request.terminal);
        if (state != CameraAccess.State.LIVE) return new Listing(request.terminal, request.request, state, List.of());
        ServerLevel level = (ServerLevel) player.level();
        DeviceBlockEntity terminal = (DeviceBlockEntity) level.getBlockEntity(request.terminal);
        List<CameraEntry> entries = CameraDirectory.get(level).cameras(player.getUUID(), terminal.link()).stream()
                .map(e -> new CameraEntry(e.position(), e.label(), CameraAccess.check(player, request.terminal, e.position()).state())).toList();
        return new Listing(request.terminal, request.request, state, entries);
    }

    private static boolean rateLimit(Map<UUID, Long> map, ServerPlayer player, long now) {
        Long previous = map.get(player.getUUID());
        if (previous != null && now >= previous && now - previous < 10) return false;
        map.put(player.getUUID(), now);
        return true;
    }

    private static long now(ServerPlayer player) { return ((ServerLevel) player.level()).getServer().overworld().getGameTime(); }

    private static CameraAccess.State validate(ServerPlayer player, Session session, long now) {
        if (now >= session.expires) return CameraAccess.State.EXPIRED;
        if (player.level() != session.level) return CameraAccess.State.INTERRUPTED;
        if (!session.level.isLoaded(session.terminal.getBlockPos()) || !session.level.isLoaded(session.camera.getBlockPos())) return CameraAccess.State.UNLOADED;
        if (session.level.getBlockEntity(session.terminal.getBlockPos()) != session.terminal
                || session.level.getBlockEntity(session.camera.getBlockPos()) != session.camera) return CameraAccess.State.REMOVED;
        if (!session.link.equals(session.terminal.link()) || !session.link.equals(session.camera.link())) return CameraAccess.State.OTHER_NETWORK;
        return CameraAccess.check(player, session.terminal.getBlockPos(), session.camera.getBlockPos()).state();
    }

    private static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (now % 5 != 0) return;
        var iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) { iterator.remove(); continue; }
            Session session = entry.getValue();
            CameraAccess.State state = validate(player, session, now);
            ServerPlayNetworking.send(player, session.feed(state));
            if (state != CameraAccess.State.LIVE) iterator.remove();
        }
    }

    public static boolean isViewing(UUID player) { return SESSIONS.containsKey(player); }

    public static Feed open(ServerPlayer player, OpenRequest request) {
        CameraAccess.Check check = CameraAccess.check(player, request.terminal, request.camera);
        if (!check.allowed()) return new Feed(NO_SESSION, request.request, request.terminal, request.camera, "", Direction.NORTH,
                NO_SESSION, new CameraMotion().state(), check.state());
        Session session = new Session(request.request, (ServerLevel) player.level(), check, now(player));
        SESSIONS.put(player.getUUID(), session);
        return session.feed(CameraAccess.State.LIVE);
    }

    public static boolean steer(ServerPlayer player, Steer request) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.id.equals(request.session)) return false;
        long now = now(player);
        if (session.lastSteer != Long.MIN_VALUE && now >= session.lastSteer && now - session.lastSteer < 5) return false;
        if (validate(player, session, now) != CameraAccess.State.LIVE) return false;
        session.lastSteer = now;
        // Revalidate the signal at the action, even if the visual indicator has not refreshed yet.
        session.camera.cameraMotion().powered(true, session.level.getGameTime());
        boolean changed = session.camera.cameraMotion().command(request.command, session.level.getGameTime(), CameraGeometry.maxPitch(session.camera.getBlockState()));
        session.camera.syncMotion();
        return changed;
    }

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(ListRequest.TYPE, ListRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(OpenRequest.TYPE, OpenRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Control.TYPE, Control.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Steer.TYPE, Steer.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Listing.TYPE, Listing.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Feed.TYPE, Feed.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ListRequest.TYPE, (request, context) -> {
            if (rateLimit(LAST_LIST, context.player(), now(context.player()))) ServerPlayNetworking.send(context.player(), list(context.player(), request));
        });
        ServerPlayNetworking.registerGlobalReceiver(OpenRequest.TYPE, (request, context) -> {
            ServerPlayer player = context.player(); long now = now(player);
            if (!rateLimit(LAST_OPEN, player, now)) return;
            ServerPlayNetworking.send(player, open(player, request));
        });
        ServerPlayNetworking.registerGlobalReceiver(Control.TYPE, (request, context) -> {
            ServerPlayer player = context.player();
            Session session = SESSIONS.get(player.getUUID());
            if (session == null || !session.id.equals(request.session)) return;
            if (request.close) { SESSIONS.remove(player.getUUID()); return; }
            long now = now(player);
            if (now >= session.lastKeepAlive && now - session.lastKeepAlive < 10) return;
            session.lastKeepAlive = now;
            // Keep-alives cannot revive an expired or invalid view.
            CameraAccess.State state = validate(player, session, now);
            if (state == CameraAccess.State.LIVE) session.expires = now + LEASE_TICKS;
            else { ServerPlayNetworking.send(player, session.feed(state)); SESSIONS.remove(player.getUUID()); }
        });
        ServerPlayNetworking.registerGlobalReceiver(Steer.TYPE, (request, context) -> {
            if (steer(context.player(), request)) {
                Session session = SESSIONS.get(context.player().getUUID());
                if (session != null) ServerPlayNetworking.send(context.player(), session.feed(CameraAccess.State.LIVE));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(CameraProtocol::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID(); SESSIONS.remove(id); LAST_LIST.remove(id); LAST_OPEN.remove(id);
        });
    }

    public static void clear() { SESSIONS.clear(); LAST_LIST.clear(); LAST_OPEN.clear(); }
    private CameraProtocol() { }
}
