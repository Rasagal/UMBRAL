// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import net.fabricmc.api.ClientModInitializer;
import dev.umbral.network.NetworkProtocol;
import dev.umbral.network.NetworkTopology;
import dev.umbral.camera.CameraAccess;
import dev.umbral.camera.CameraProtocol;
import dev.umbral.access.AccessProtocol;
import dev.umbral.turret.TurretProtocol;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import dev.umbral.UmbralContent;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.particles.DustParticleOptions;

public final class UmbralClient implements ClientModInitializer {
    private NetworkProtocol.Coverage coverage;
    private int coverageTicks;

    @Override
    public void onInitializeClient() {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementAfter(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.HOTBAR,UmbralContent.id("configurator"),ConfiguratorHud::render);
        ClientPlayNetworking.registerGlobalReceiver(dev.umbral.access.AccessSetupProtocol.Snapshot.TYPE,(p,c)->{
            if(p.open()&&p.valid())c.client().gui.setScreen(new AccessSetupScreen(p,c.client().gui.screen()));
            else {var screen=ManualScreen.underlying(c.client().gui.screen());if(screen instanceof FilterScreen filter)screen=filter.parent();if(screen instanceof AccessSetupScreen setup)setup.accept(p);}
        });
        ClientPlayNetworking.registerGlobalReceiver(dev.umbral.manual.ManualProtocol.Open.TYPE,(p,c)->c.client().gui.setScreen(new ManualScreen(null,p.entry())));
        ClientPlayNetworking.registerGlobalReceiver(dev.umbral.manual.ManualProtocol.Recipe.TYPE,(p,c)->{if(c.client().gui.screen() instanceof ManualScreen screen)screen.accept(p);});
        BlockEntityRenderers.register(UmbralContent.DEVICE_ENTITY, DeviceRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(dev.umbral.protection.ProtectionProtocol.Snapshot.TYPE,(payload,context)->{
            if(payload.valid())ProtectionOverlay.latest=payload;
            if(payload.open()&&payload.valid())context.client().gui.setScreen(new ProtectionScreen(payload));
            else ProtectionScreen.receive(payload);
        });
        ClientTickEvents.END_CLIENT_TICK.register(ProtectionOverlay::tick);
        ClientPlayNetworking.registerGlobalReceiver(dev.umbral.filter.FilterProtocol.Snapshot.TYPE,(p,c)->{
            if(p.open()&&p.valid())c.client().gui.setScreen(new FilterScreen(p,ManualScreen.underlying(c.client().gui.screen())));
            else if(ManualScreen.underlying(c.client().gui.screen()) instanceof FilterScreen screen)screen.accept(p);
        });
        ClientPlayNetworking.registerGlobalReceiver(dev.umbral.airlock.AirlockProtocol.Snapshot.TYPE,(p,c)->{
            if(p.open()&&p.valid())c.client().gui.setScreen(new AirlockScreen(p,ManualScreen.underlying(c.client().gui.screen())));
            else if(ManualScreen.underlying(c.client().gui.screen()) instanceof AirlockScreen screen)screen.accept(p);
        });
        EntityRendererRegistry.register(UmbralContent.MOTA_ENTITY,MotaRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(dev.umbral.installation.NexoProtocol.Snapshot.TYPE,(p,c)->{
            if(p.open()&&p.valid())c.client().gui.setScreen(new NexoScreen(p));
            else {var screen=ManualScreen.underlying(c.client().gui.screen());for(int n=0;n<4;n++){if(screen instanceof FilterScreen filter)screen=filter.parent();else if(screen instanceof AirlockScreen airlock)screen=airlock.parent();else if(screen instanceof AccessSetupScreen setup)screen=setup.parent();else break;}screen=NexoScreen.underlying(screen);if(screen instanceof NexoScreen nexo)nexo.accept(p);}
        });
        EntityRendererRegistry.register(UmbralContent.TURRET_BOLT,TurretBoltRenderer::new);
        EntityRendererRegistry.register(UmbralContent.NUGGET_BOLT,c -> new net.minecraft.client.renderer.entity.ThrownItemRenderer<>(c,.45f,false));
        ClientPlayNetworking.registerGlobalReceiver(TurretProtocol.Snapshot.TYPE,(payload,context)->{
            if(payload.open()&&payload.valid())context.client().gui.setScreen(new TurretScreen(payload));
            else if(ManualScreen.underlying(context.client().gui.screen()) instanceof TurretScreen screen)screen.accept(payload);
        });
        ClientPlayNetworking.registerGlobalReceiver(AccessProtocol.Snapshot.TYPE, (payload, context) -> {
            if (payload.open()) context.client().gui.setScreen(new ReaderAccessScreen(payload));
            else if (ManualScreen.underlying(context.client().gui.screen()) instanceof ReaderAccessScreen reader) reader.accept(payload);
        });
        ClientPlayNetworking.registerGlobalReceiver(CameraProtocol.Listing.TYPE, (payload, context) -> {
            if (ManualScreen.underlying(context.client().gui.screen()) instanceof CameraDirectoryScreen directory) directory.accept(payload);
        });
        ClientPlayNetworking.registerGlobalReceiver(CameraProtocol.Feed.TYPE, (payload, context) -> {
            var screen = context.client().gui.screen();
            if (screen instanceof CameraViewScreen view && view.matches(payload)) view.accept(payload);
            else if (screen instanceof CameraDirectoryScreen directory && directory.awaiting(payload)) directory.accept(payload);
            else if (payload.state() == CameraAccess.State.LIVE) ClientPlayNetworking.send(new CameraProtocol.Control(payload.session(), true));
        });
        ClientPlayNetworking.registerGlobalReceiver(NetworkProtocol.Status.TYPE, (payload, context) -> {
            var screen = ManualScreen.underlying(context.client().gui.screen());
            if (screen instanceof NetworkStatusScreen statusScreen && statusScreen.matches(payload)) {
                statusScreen.update(payload);
            } else if (payload.open()) {
                context.client().gui.setScreen(new NetworkStatusScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(NetworkProtocol.Coverage.TYPE, (payload, context) -> {
            coverage = payload;
            coverageTicks = 100;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) { coverage = null; coverageTicks = 0; return; }
            if (coverage == null || coverageTicks-- <= 0 || coverageTicks % 10 != 0) return;
            var center = net.minecraft.world.phys.Vec3.atCenterOf(coverage.position());
            int color = coverage.active() ? 0x55C7BC : 0xDEA34D;
            var particle = new DustParticleOptions(color, 1.2f);
            for (int step = 0; step < 64; step++) {
                double angle = step * Math.PI * 2 / 64;
                double a = Math.cos(angle) * NetworkTopology.RADIO_RADIUS;
                double b = Math.sin(angle) * NetworkTopology.RADIO_RADIUS;
                client.level.addParticle(particle, center.x + a, center.y, center.z + b, 0, 0, 0);
                client.level.addParticle(particle, center.x + a, center.y + b, center.z, 0, 0, 0);
                client.level.addParticle(particle, center.x, center.y + a, center.z + b, 0, 0, 0);
            }
        });
    }
}
