// SPDX-License-Identifier: MPL-2.0

package dev.umbral.item;

import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.block.DeviceKind;
import dev.umbral.block.SecurityDoorBlock;
import dev.umbral.access.DoorTarget;
import dev.umbral.camera.CameraDirectory;
import dev.umbral.network.NetworkLink;
import dev.umbral.network.NetworkProtocol;
import dev.umbral.network.NetworkService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class ConfiguratorItem extends Item {
    public ConfiguratorItem(Properties properties) { super(properties); }

    @Override public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
        }
        refreshSelection(player);
        if (!player.isAlive() || player.isSpectator()) return InteractionResult.FAIL;
        if(dev.umbral.protection.ProtectionSelections.active(player.getUUID()))return dev.umbral.protection.ProtectionSelections.point(player,context.getClickedPos());
        DeviceBlockEntity device = SecurityDoorBlock.device(level, context.getClickedPos());
        if(device==null){
            if(player.isShiftKeyDown()&&dev.umbral.logistics.MotaService.container(level.getBlockEntity(context.getClickedPos()))){
                var stock=dev.umbral.logistics.MotaService.seal(player,context.getClickedPos(),context.getItemInHand().get(UmbralContent.SELECTED_NETWORK));
                if(stock==null)return message(player,"mota.umbral.stock_failed");
                context.getItemInHand().set(UmbralContent.SELECTED_STOCK,stock);return message(player,"mota.umbral.stock_selected");
            }return InteractionResult.PASS;
        }
        // Operator-placed, unowned devices may be claimed explicitly with this tool.
        if (device.owner() == null) device.initializeOwner(player.getUUID(), "");
        if (!device.ownedBy(player.getUUID())) return message(player, "message.umbral.not_owner");

        if (player.isShiftKeyDown()) {
            if (device.kind() == DeviceKind.DOOR) {
                if (device.link() == null) return message(player, "message.umbral.select_core_first");
                context.getItemInHand().set(UmbralContent.SELECTED_DOOR, device.asDoorTarget());
                return message(player, "access.umbral.door_selected");
            }
            var inspection = NetworkService.inspect(level, device);
            player.sendOverlayMessage(Component.translatable("message.umbral.inspection", Component.translatable(inspection.status().key())));
            if (device.kind() == DeviceKind.CORE || device.kind() == DeviceKind.RELAY) {
                ServerPlayNetworking.send(player, new NetworkProtocol.Coverage(device.getBlockPos(), inspection.online()));
            }
            return InteractionResult.SUCCESS;
        }
        if (device.kind() == DeviceKind.CORE) {
            if (device.link() == null) return message(player, "message.umbral.invalid_core");
            context.getItemInHand().set(UmbralContent.SELECTED_NETWORK, device.link());
            context.getItemInHand().remove(UmbralContent.SELECTED_DOOR);
            context.getItemInHand().remove(UmbralContent.SELECTED_STOCK);
            return message(player, "message.umbral.selected");
        }

        var stock=context.getItemInHand().get(UmbralContent.SELECTED_STOCK);
        if(device.kind()==DeviceKind.DOCK&&stock!=null){
            if(!dev.umbral.logistics.MotaService.bindStock(player,device,stock))return message(player,"mota.umbral.stock_failed");
            context.getItemInHand().remove(UmbralContent.SELECTED_STOCK);return message(player,"mota.umbral.stock_bound");
        }
        DoorTarget target = context.getItemInHand().get(UmbralContent.SELECTED_DOOR);
        if(device.kind()==DeviceKind.FILTER&&target!=null){String result=dev.umbral.filter.FilterService.link(player,device,target);if(result.equals("door_linked"))context.getItemInHand().remove(UmbralContent.SELECTED_DOOR);return message(player,"filter.umbral.result."+result);}
        if (device.kind() == DeviceKind.READER && target != null) {
            if (!target.dimension().equals(level.dimension().identifier()) || !level.isLoaded(target.position())
                    || !(level.getBlockEntity(target.position()) instanceof DeviceBlockEntity door)
                    || !door.identity().equals(target.identity()) || !device.targetDoor(player.getUUID(), door)) {
                return message(player, "access.umbral.bind_failed");
            }
            context.getItemInHand().remove(UmbralContent.SELECTED_DOOR);
            return message(player, "access.umbral.door_bound");
        }

        NetworkLink selected = context.getItemInHand().get(UmbralContent.SELECTED_NETWORK);
        if (selected == null) return message(player, "message.umbral.select_core_first");
        if (!selected.dimension().equals(level.dimension().identifier())) return message(player, "message.umbral.wrong_dimension");
        if (!level.isLoaded(selected.core())) return message(player, "message.umbral.core_unloaded");
        if (!(level.getBlockEntity(selected.core()) instanceof DeviceBlockEntity core) || !selected.equals(core.link())
                || !core.ownedBy(player.getUUID())) return message(player, "message.umbral.invalid_core");
        if (device.kind() == DeviceKind.CAMERA && !CameraDirectory.get(level).hasRoom(selected, device.getBlockPos())) {
            return message(player, "message.umbral.camera_limit");
        }
        if(device.kind()==DeviceKind.FENCE)return message(player,"nexo.umbral.result."+dev.umbral.fence.FenceService.pair(player,device,core));
        if (!device.bind(player.getUUID(), core)) return message(player, "message.umbral.invalid_core");
        var inspection = NetworkService.inspect(level, device);
        player.sendOverlayMessage(Component.translatable("message.umbral.linked", Component.translatable(inspection.status().key())));
        return InteractionResult.SUCCESS;
    }

    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if(player instanceof ServerPlayer serverPlayer){
            refreshSelection(serverPlayer);
            if(!serverPlayer.isAlive()||serverPlayer.isSpectator())return InteractionResult.FAIL;
        }
        if(player instanceof ServerPlayer p&&dev.umbral.protection.ProtectionSelections.active(p.getUUID())){
            if(p.isShiftKeyDown()&&dev.umbral.protection.ProtectionSelections.draft(p).mode()==dev.umbral.protection.ProtectionSelections.Mode.FOOTPRINT)dev.umbral.protection.ProtectionSelections.cancel(p);
            else dev.umbral.protection.ProtectionSelections.finish(p);return InteractionResult.SUCCESS;
        }
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide()) {
                player.getItemInHand(hand).remove(UmbralContent.SELECTED_NETWORK);
                player.getItemInHand(hand).remove(UmbralContent.SELECTED_STOCK);
                player.getItemInHand(hand).remove(UmbralContent.SELECTED_DOOR);
                player.sendOverlayMessage(Component.translatable("message.umbral.selection_cleared"));
            }
            return InteractionResult.SUCCESS;
        }
        if(player instanceof ServerPlayer p)dev.umbral.protection.ProtectionProtocol.openTool(p);
        return InteractionResult.SUCCESS;
    }

    private InteractionResult message(Player player, String key) {
        player.sendOverlayMessage(Component.translatable(key));
        return InteractionResult.SUCCESS;
    }

    /** Validate an expiring world selection before deciding what this click means. */
    private static void refreshSelection(ServerPlayer player){
        boolean selecting=dev.umbral.protection.ProtectionSelections.active(player.getUUID());
        dev.umbral.protection.ProtectionSelections.draft(player);
        if(selecting&&!dev.umbral.protection.ProtectionSelections.active(player.getUUID())&&player.connection!=null)
            dev.umbral.protection.ProtectionProtocol.sendTool(player,false,"selection_expired");
    }
}
