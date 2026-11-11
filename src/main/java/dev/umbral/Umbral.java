// SPDX-License-Identifier: MPL-2.0

package dev.umbral;

import net.fabricmc.api.ModInitializer;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.block.SecurityDoorBlock;
import dev.umbral.access.AccessProtocol;
import dev.umbral.turret.TurretProtocol;
import dev.umbral.camera.CameraProtocol;
import dev.umbral.network.NetworkProtocol;
import dev.umbral.network.NetworkService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Umbral implements ModInitializer {
    public static final String MOD_ID = "umbral";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        UmbralContent.initialize();
        dev.umbral.manual.ManualProtocol.initialize();
        dev.umbral.manual.ManualDelivery.initialize();
        dev.umbral.airlock.AirlockProtocol.initialize();
        dev.umbral.filter.FilterProtocol.initialize();
        NetworkProtocol.initialize();
        CameraProtocol.initialize();
        AccessProtocol.initialize();
        dev.umbral.access.AccessSetupProtocol.initialize();
        TurretProtocol.initialize();
        dev.umbral.installation.NexoProtocol.initialize();
        dev.umbral.protection.ProtectionProtocol.initialize();
        dev.umbral.protection.ProtectionPolicy.initialize();
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            DeviceBlockEntity device = blockEntity instanceof DeviceBlockEntity ownPart?ownPart:SecurityDoorBlock.device(level, pos);
            var area=dev.umbral.protection.ProtectionPolicy.region(level,pos);
            boolean landOwner=area!=null&&area.owner().equals(player.getUUID());
            if (device != null && device.owner() != null && !device.ownedBy(player.getUUID())&&!landOwner) {
                if(!(player instanceof net.minecraft.server.level.ServerPlayer sp)||sp.connection!=null)player.sendOverlayMessage(Component.translatable("message.umbral.not_owner"));
                return false;
            }
            return true;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            dev.umbral.manual.ManualProtocol.clear();
            dev.umbral.airlock.AirlockProtocol.clear();
            dev.umbral.filter.FilterProtocol.clear();
            NetworkService.clear();
            NetworkProtocol.clear();
            CameraProtocol.clear();
            AccessProtocol.clear();
            dev.umbral.access.AccessSetupProtocol.clear();
            TurretProtocol.clear();
            dev.umbral.installation.NexoProtocol.clear();
            dev.umbral.protection.ProtectionProtocol.clear();
        });
        LOGGER.info("UMBRAL: iniciando la red de instalaciones");
    }
}
