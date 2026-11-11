// SPDX-License-Identifier: MPL-2.0

package dev.umbral.access;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.network.NetworkService;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/** All authority lives here on the server; interaction packets only express intent. */
public final class AccessService {
    public enum Result {
        READY, GRANTED, DENIED, AIRLOCK_QUEUED, AIRLOCK_BUSY, AIRLOCK_DENIED, AIRLOCK_UNAVAILABLE, AIRLOCK_CLEARED, SCAN_REQUIRED, SCAN_BLOCKED, SCAN_UNAVAILABLE, FILTER_CLEARED, OFFLINE, NO_DOOR, DOOR_UNAVAILABLE, DOOR_OFFLINE, ISSUED, DROPPED, REVOKED, NO_BLANK, LIMIT, INVALID, BUSY;
        public String key() { return "access.umbral." + name().toLowerCase(java.util.Locale.ROOT); }
    }
    public static boolean near(ServerPlayer player, DeviceBlockEntity device) {
        return player.isAlive() && !player.isSpectator() && device.getLevel() == player.level()
                && device.getBlockPos().distToCenterSqr(player.position()) <= 64
                && player.level().isLoaded(device.getBlockPos()) && player.level().getBlockEntity(device.getBlockPos()) == device;
    }
    public static boolean manages(ServerPlayer player, DeviceBlockEntity reader) {
        return reader.kind() == DeviceKind.READER && reader.ownedBy(player.getUUID()) && near(player, reader);
    }
    public static DeviceBlockEntity door(ServerLevel level, DeviceBlockEntity reader) {
        DoorTarget target = reader.doorTarget();
        if (target == null || !target.dimension().equals(level.dimension().identifier()) || !level.isLoaded(target.position())
                || reader.getBlockPos().distSqr(target.position()) > 16) return null;
        if (!(level.getBlockEntity(target.position()) instanceof DeviceBlockEntity door) || door.kind() != DeviceKind.DOOR
                || !door.identity().equals(target.identity()) || reader.owner() == null || !reader.owner().equals(door.owner())
                || reader.link() == null || !reader.link().equals(door.link())) return null;
        return door;
    }
    public static Result readiness(ServerLevel level, DeviceBlockEntity reader) {
        if (!NetworkService.inspect(level, reader).online()) return Result.OFFLINE;
        if (reader.doorTarget() == null) return Result.NO_DOOR;
        DeviceBlockEntity door = door(level, reader);
        if (door == null) return Result.DOOR_UNAVAILABLE;
        return NetworkService.inspect(level, door).online() ? Result.READY : Result.DOOR_OFFLINE;
    }
    public static Result present(ServerPlayer player, DeviceBlockEntity reader, ItemStack card) {
        if (reader.kind() != DeviceKind.READER || !near(player, reader) || !card.is(UmbralContent.ACCESS_CARD)) return Result.DENIED;
        ServerLevel level = (ServerLevel) player.level();
        Result ready = readiness(level, reader);
        if (ready != Result.READY) return ready;
        UUID id = card.get(UmbralContent.CARD_ID);
        if (id == null || !AccessRegistry.get(level).accepts(id, reader.owner(), reader.link())) return Result.DENIED;
        DeviceBlockEntity target=door(level,reader);var interlock=dev.umbral.airlock.AirlockService.card(player,target,id);if(interlock!=null)return interlock;var inspection=dev.umbral.filter.FilterService.checkDoor(player,target);
        if(inspection!=dev.umbral.filter.FilterService.Gate.ALLOW)return switch(inspection){case REQUIRED->Result.SCAN_REQUIRED;case BLOCKED->Result.SCAN_BLOCKED;default->Result.SCAN_UNAVAILABLE;};
        SecurityDoorBlock.open(level, target); return Result.GRANTED;
    }
    public static boolean mayOpenManually(ServerPlayer player, DeviceBlockEntity door) {
        if (door.kind() != DeviceKind.DOOR || !near(player, door)) return false;
        if (door.ownedBy(player.getUUID())) return true;
        var facing = door.getBlockState().getValue(DeviceBlock.FACING);
        double dx = player.getX() - (door.getBlockPos().getX() + .5);
        double dz = player.getZ() - (door.getBlockPos().getZ() + .5);
        return dx * facing.getStepX() + dz * facing.getStepZ() < -.25;
    }
    public static ItemStack blank(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item.is(UmbralContent.ACCESS_CARD) && !item.has(UmbralContent.CARD_ID)) return item;
        }
        return ItemStack.EMPTY;
    }
    public static Result issue(ServerPlayer player, DeviceBlockEntity reader) {
        if (!manages(player, reader)) return Result.INVALID;
        ServerLevel level = (ServerLevel) player.level();
        if (!NetworkService.inspect(level, reader).online()) return Result.OFFLINE;
        ItemStack blank = blank(player);
        if (blank.isEmpty()) return Result.NO_BLANK;
        var registry = AccessRegistry.get(level);
        if (!registry.hasRoom(reader.owner(), reader.link())) return Result.LIMIT;
        Component customName = blank.get(DataComponents.CUSTOM_NAME);
        var grant = registry.issue(reader.owner(), reader.link(), customName == null ? "" : customName.getString());
        if (grant == null) return Result.LIMIT;
        ItemStack issued = new ItemStack(UmbralContent.ACCESS_CARD);
        issued.set(UmbralContent.CARD_ID, grant.id());
        issued.set(DataComponents.MAX_STACK_SIZE, 1);
        issued.set(DataComponents.CUSTOM_NAME, name(grant));
        blank.shrink(1);
        // Inventory.add silently discards overflow in creative; explicitly preserve the physical pass.
        int free = player.getInventory().getFreeSlot();
        if (free >= 0) player.getInventory().setItem(free, issued);
        else player.drop(issued, false, net.minecraft.util.Prediction.SERVER_ONLY);
        player.getInventory().setChanged();
        return free >= 0 ? Result.ISSUED : Result.DROPPED;
    }
    public static Component name(AccessRegistry.Grant grant) {
        return grant.label().isEmpty() ? Component.translatable("access.umbral.pass_number", grant.serial()) : Component.literal(grant.label());
    }
    public static Result revoke(ServerPlayer player, DeviceBlockEntity reader, UUID id) {
        if (!manages(player, reader) || id == null || reader.link() == null) return Result.INVALID;
        return AccessRegistry.get((ServerLevel) player.level()).revoke(id, reader.owner(), reader.link()) ? Result.REVOKED : Result.INVALID;
    }
    private AccessService() { }
}
