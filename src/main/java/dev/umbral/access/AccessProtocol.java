// SPDX-License-Identifier: MPL-2.0

package dev.umbral.access;

import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.network.NetworkLink;
import dev.umbral.network.NetworkService;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

public final class AccessProtocol {
    public enum Operation { QUERY, ISSUE, REVOKE, CLEAR_FILTER, CLEAR_AIRLOCK }
    public record Request(BlockPos reader, UUID identity, NetworkLink link, long nonce, Operation operation, UUID card) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(UmbralContent.id("access_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
            public Request decode(RegistryFriendlyByteBuf b) {
                return new Request(b.readBlockPos(), b.readUUID(), readLink(b), b.readLong(), b.readEnum(Operation.class), b.readBoolean() ? b.readUUID() : null);
            }
            public void encode(RegistryFriendlyByteBuf b, Request p) {
                b.writeBlockPos(p.reader); b.writeUUID(p.identity); writeLink(b, p.link); b.writeLong(p.nonce);
                b.writeEnum(p.operation); b.writeBoolean(p.card != null); if (p.card != null) b.writeUUID(p.card);
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Card(UUID id, String label, long serial) { }
    public record Snapshot(BlockPos reader, UUID identity, NetworkLink link, long nonce, boolean open, String label,
                           AccessService.Result state, boolean canIssue, DoorTarget door, String doorName, boolean filtered, boolean interlocked, List<Card> cards,
                           AccessService.Result result) implements CustomPacketPayload {
        public Snapshot { cards = List.copyOf(cards); }
        public static final Type<Snapshot> TYPE = new Type<>(UmbralContent.id("access_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = new StreamCodec<>() {
            public Snapshot decode(RegistryFriendlyByteBuf b) {
                BlockPos reader = b.readBlockPos(); UUID identity = b.readUUID(); NetworkLink link = readLink(b);
                long nonce = b.readLong(); boolean open = b.readBoolean(); String label = b.readUtf(48);
                var state = b.readEnum(AccessService.Result.class); boolean canIssue = b.readBoolean();
                DoorTarget door = b.readBoolean() ? DoorTarget.STREAM_CODEC.decode(b) : null;
                String doorName = b.readUtf(48); boolean filtered=b.readBoolean(),interlocked=b.readBoolean(); int count = b.readVarInt();
                if (count < 0 || count > AccessRegistry.MAX_PASSES) throw new IllegalArgumentException("Invalid pass count");
                List<Card> cards = new ArrayList<>(count);
                for (int n = 0; n < count; n++) cards.add(new Card(b.readUUID(), b.readUtf(32), b.readLong()));
                return new Snapshot(reader, identity, link, nonce, open, label, state, canIssue, door, doorName, filtered, interlocked, cards, b.readEnum(AccessService.Result.class));
            }
            public void encode(RegistryFriendlyByteBuf b, Snapshot p) {
                b.writeBlockPos(p.reader); b.writeUUID(p.identity); writeLink(b, p.link); b.writeLong(p.nonce); b.writeBoolean(p.open);
                b.writeUtf(p.label, 48); b.writeEnum(p.state); b.writeBoolean(p.canIssue); b.writeBoolean(p.door != null);
                if (p.door != null) DoorTarget.STREAM_CODEC.encode(b, p.door);
                b.writeUtf(p.doorName, 48); b.writeBoolean(p.filtered); b.writeBoolean(p.interlocked); b.writeVarInt(p.cards.size());
                for (Card c : p.cards) { b.writeUUID(c.id); b.writeUtf(c.label, 32); b.writeLong(c.serial); }
                b.writeEnum(p.result);
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    private static final Map<UUID, Long> QUERIES = new HashMap<>(), ACTIONS = new HashMap<>();
    private static NetworkLink readLink(RegistryFriendlyByteBuf b) { return b.readBoolean() ? NetworkLink.STREAM_CODEC.decode(b) : null; }
    private static void writeLink(RegistryFriendlyByteBuf b, NetworkLink link) {
        b.writeBoolean(link != null); if (link != null) NetworkLink.STREAM_CODEC.encode(b, link);
    }
    public static Snapshot snapshot(ServerPlayer player, DeviceBlockEntity reader, long nonce, boolean open, AccessService.Result result) {
        ServerLevel level = (ServerLevel) player.level();
        var registry = AccessRegistry.get(level);
        var door = AccessService.door(level, reader);
        boolean canIssue = NetworkService.inspect(level, reader).online() && !AccessService.blank(player).isEmpty()
                && registry.hasRoom(reader.owner(), reader.link());
        List<Card> cards = reader.link() == null ? List.of() : registry.list(reader.owner(), reader.link()).stream()
                .map(g -> new Card(g.id(), g.label(), g.serial())).toList();
        return new Snapshot(reader.getBlockPos(), reader.identity(), reader.link(), nonce, open, reader.label(),
                AccessService.readiness(level, reader), canIssue, reader.doorTarget(), door == null ? "" : door.label(), door!=null&&door.filterGuard()!=null, door!=null&&door.airlockLink()!=null, cards, result);
    }
    public static void open(ServerPlayer player, DeviceBlockEntity reader) {
        if (AccessService.manages(player, reader)) ServerPlayNetworking.send(player, snapshot(player, reader, 0, true, AccessService.Result.READY));
    }
    /** Exposed for server tests; transport rate limiting is separate from authority checks. */
    public static Snapshot handle(ServerPlayer player, Request request) {
        if (!player.level().isLoaded(request.reader)
                || !(player.level().getBlockEntity(request.reader) instanceof DeviceBlockEntity reader)
                || !reader.identity().equals(request.identity) || !Objects.equals(reader.link(), request.link)
                || !AccessService.manages(player, reader)) return denied(request, AccessService.Result.INVALID);
        AccessService.Result result = switch (request.operation) {
            case QUERY -> AccessService.Result.READY;
            case ISSUE -> AccessService.issue(player, reader);
            case CLEAR_AIRLOCK -> {var door=AccessService.door((ServerLevel)player.level(),reader);yield door!=null&&dev.umbral.airlock.AirlockService.recover(player,door)?AccessService.Result.AIRLOCK_CLEARED:AccessService.Result.INVALID;}
            case CLEAR_FILTER -> {var door=AccessService.door((ServerLevel)player.level(),reader);yield door!=null&&dev.umbral.filter.FilterService.clearGuard(player,door)?AccessService.Result.FILTER_CLEARED:AccessService.Result.INVALID;}
            case REVOKE -> AccessService.revoke(player, reader, request.card);
        };
        return snapshot(player, reader, request.nonce, false, result);
    }
    private static Snapshot denied(Request request, AccessService.Result result) {
        return new Snapshot(request.reader, request.identity, request.link, request.nonce, false, "", result, false, null, "", false, false, List.of(), result);
    }
    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Snapshot.TYPE, Snapshot.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (request, context) -> {
            ServerPlayer player = context.player();
            long now = ((ServerLevel) player.level()).getServer().overworld().getGameTime();
            var limiter = request.operation == Operation.QUERY ? QUERIES : ACTIONS;
            Long last = limiter.get(player.getUUID());
            if (last != null && now >= last && now - last < (request.operation == Operation.QUERY ? 10 : 5)) {
                if (request.operation != Operation.QUERY) ServerPlayNetworking.send(player, denied(request, AccessService.Result.BUSY));
                return;
            }
            limiter.put(player.getUUID(), now);
            ServerPlayNetworking.send(player, handle(player, request));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            QUERIES.remove(handler.player.getUUID()); ACTIONS.remove(handler.player.getUUID());
        });
    }
    public static void clear() { QUERIES.clear(); ACTIONS.clear(); }
    private AccessProtocol() { }
}
