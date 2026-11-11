// SPDX-License-Identifier: MPL-2.0

package dev.umbral.network;

import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlockEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Read-only telemetry. All requests are checked on the authoritative server. */
public final class NetworkProtocol {
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();

    public record Request(BlockPos position) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(UmbralContent.id("status_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Request::position, Request::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Coverage(BlockPos position, boolean active) implements CustomPacketPayload {
        public static final Type<Coverage> TYPE = new Type<>(UmbralContent.id("coverage"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Coverage> CODEC = new StreamCodec<>() {
            @Override public Coverage decode(RegistryFriendlyByteBuf b) { return new Coverage(b.readBlockPos(), b.readBoolean()); }
            @Override public void encode(RegistryFriendlyByteBuf b, Coverage value) { b.writeBlockPos(value.position); b.writeBoolean(value.active); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Status(BlockPos position, String kind, String label, String networkName,
                         String state, int nodes, int conductors, boolean open) implements CustomPacketPayload {
        public static final Type<Status> TYPE = new Type<>(UmbralContent.id("status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Status> CODEC = new StreamCodec<>() {
            @Override public Status decode(RegistryFriendlyByteBuf b) {
                return new Status(b.readBlockPos(), b.readUtf(32), b.readUtf(48), b.readUtf(64), b.readUtf(32),
                        b.readVarInt(), b.readVarInt(), b.readBoolean());
            }
            @Override public void encode(RegistryFriendlyByteBuf b, Status value) {
                b.writeBlockPos(value.position); b.writeUtf(value.kind, 32); b.writeUtf(value.label, 48);
                b.writeUtf(value.networkName, 64); b.writeUtf(value.state, 32);
                b.writeVarInt(value.nodes); b.writeVarInt(value.conductors); b.writeBoolean(value.open);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static boolean mayInspect(ServerPlayer player, DeviceBlockEntity device) {
        return !player.isSpectator() && device.getLevel() == player.level() && device.ownedBy(player.getUUID())
                && device.getBlockPos().distToCenterSqr(player.position()) <= 64;
    }

    public static void sendStatus(ServerPlayer player, DeviceBlockEntity device, boolean open) {
        if (!mayInspect(player, device) || !(device.getLevel() instanceof ServerLevel level)) return;
        var inspection = NetworkService.inspect(level, device);
        ServerPlayNetworking.send(player, new Status(device.getBlockPos(), device.kind().id, device.label(),
                inspection.networkName(), inspection.status().name(), inspection.nodes(), inspection.conductors(), open));
    }

    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(Status.TYPE, Status.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Coverage.TYPE, Coverage.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerLevel level = (ServerLevel) player.level();
            long now = level.getGameTime();
            Long previous = LAST_REQUEST.get(player.getUUID());
            if (previous != null && now >= previous && now - previous < 10) return;
            LAST_REQUEST.put(player.getUUID(), now);
            if (payload.position.distToCenterSqr(player.position()) > 64 || !level.isLoaded(payload.position)) return;
            if (level.getBlockEntity(payload.position) instanceof DeviceBlockEntity device) sendStatus(player, device, false);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_REQUEST.remove(handler.getPlayer().getUUID()));
    }

    public static void clear() { LAST_REQUEST.clear(); }
    private NetworkProtocol() { }
}
