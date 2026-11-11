// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.network.NetworkProtocol;
import dev.umbral.network.NetworkService;
import dev.umbral.network.NetworkTopology;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import static dev.umbral.client.ConsoleStyle.*;

/** A read-only console: telemetry is refreshed by authenticated server responses. */
public final class NetworkStatusScreen extends Screen {
    private NetworkProtocol.Status status;
    private int ticks;
    private int lastUpdate;
    private int left, top, panelWidth, panelHeight;

    public NetworkStatusScreen(NetworkProtocol.Status status) {
        super(Component.translatable("screen.umbral.title"));
        this.status = status;
    }

    public boolean matches(NetworkProtocol.Status update) { return status.position().equals(update.position()); }
    public void update(NetworkProtocol.Status update) { status = update; lastUpdate = ticks; }

    @Override protected void init() {
        clearWidgets();
        panelWidth = Math.min(460, width - 20);
        panelHeight = Math.min(270, height - 20);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        if (status.kind().equals("terminal")) {
            addRenderableWidget(new ConsoleButton(left + 16, top + panelHeight - 34, 116, 22,
                    Component.translatable("screen.umbral.cameras"), true,
                    button -> minecraft.gui.setScreen(new CameraDirectoryScreen(status))));
        }
        if(status.kind().equals("terminal")||status.kind().equals("network_core")){
            addRenderableWidget(new ConsoleButton(left+140,top+panelHeight-34,Math.min(100,panelWidth-248),22,Component.literal("NEXO"),true,b->ClientPlayNetworking.send(new dev.umbral.installation.NexoProtocol.Open(status.position()))));
        }
        addRenderableWidget(new ConsoleButton(left + panelWidth - 96, top + panelHeight - 34, 80, 22,
                Component.translatable("gui.done"), false, button -> onClose()));
        addRenderableWidget(ManualScreen.help(this,left+panelWidth-30,top+31,dev.umbral.manual.ManualCatalog.device(status.kind())));
    }

    @Override public void tick() {
        if (minecraft.player == null || minecraft.level == null || status.position().distToCenterSqr(minecraft.player.position()) > 64) {
            onClose();
            return;
        }
        if (++ticks % 20 == 0) ClientPlayNetworking.send(new NetworkProtocol.Request(status.position()));
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        backdrop(g, width, height);
        frame(g, font, left, top, panelWidth, panelHeight, title, Component.translatable("ui.umbral.local_network").getString());
        boolean stale = ticks - lastUpdate > 60;
        boolean online = !stale && (status.state().equals("WIRED") || status.state().equals("WIRELESS"));
        int color = online ? INK : ALERT;
        int bodyY = top + 56, bodyHeight = panelHeight - 102;
        int deviceWidth = panelWidth >= 380 ? 106 : 76;
        int detailX = left + 24 + deviceWidth, detailWidth = panelWidth - deviceWidth - 40;
        int overviewHeight = bodyHeight - 49;
        Component deviceName = status.label().isEmpty() ? Component.translatable("block.umbral." + status.kind()) : Component.literal(status.label());

        card(g, left + 16, bodyY, deviceWidth, bodyHeight);
        text(g, font, Component.translatable("ui.umbral.device"), left + 25, bodyY + 10, deviceWidth - 18, MUTED);
        int iconScale = deviceWidth >= 100 && bodyHeight >= 145 ? 4 : 3;
        int iconSize = iconScale * 16;
        int iconX = left + 16 + (deviceWidth - iconSize) / 2;
        int iconY = bodyY + 25 + Math.max(0, (bodyHeight - 94 - iconSize) / 2);
        device(g, iconX, iconY, status.kind(), iconScale, online);
        corners(g, iconX - 4, iconY - 2, iconSize + 8, iconSize + 8, 5, LINE);
        text(g, font, Component.translatable("ui.umbral.kind." + status.kind()), left + 25, bodyY + bodyHeight - 31, deviceWidth - 18, INK);
        String coordinates = NetworkService.coordinates(status.position());
        text(g, font, Component.literal(deviceWidth < 100 ? coordinates.replace(" ", "") : coordinates),
                left + 25, bodyY + bodyHeight - 17, deviceWidth - 18, MUTED);

        card(g, detailX, bodyY, detailWidth, overviewHeight);
        text(g, font, deviceName, detailX + 11, bodyY + 10, detailWidth - 22, INK);
        Component network = status.networkName().isEmpty() ? Component.translatable("ui.umbral.unpaired") : Component.literal(status.networkName());
        text(g, font, network, detailX + 11, bodyY + 24, detailWidth - 22, MUTED);
        int statusY = bodyY + overviewHeight - 27;
        g.fill(detailX + 8, statusY, detailX + detailWidth - 8, statusY + 20, LCD);
        statusLight(g, detailX + 15, statusY + 7, color);
        String statusKey = stale ? "screen.umbral.stale" : "status.umbral." + status.state().toLowerCase(java.util.Locale.ROOT);
        Component connection = Component.translatable(statusKey);
        text(g, font, connection, detailX + 28, statusY + 6, detailWidth - 39, color);
        // Only reported counts are displayed; stale telemetry never looks current.
        int metricY = bodyY + overviewHeight + 7;
        int metricWidth = (detailWidth - 7) / 2;
        metric(g, detailX, metricY, metricWidth, stale ? "—" : Integer.toString(status.nodes()), "ui.umbral.nodes", online ? INK : MUTED);
        metric(g, detailX + metricWidth + 7, metricY, detailWidth - metricWidth - 7,
                Integer.toString(NetworkTopology.RADIO_RADIUS), "ui.umbral.radius", INK);
        if (mouseX >= detailX && mouseX < detailX + detailWidth) {
            if (mouseY >= statusY && mouseY < statusY + 20 && font.width(connection) > detailWidth - 39)
                g.setTooltipForNextFrame(font, connection, mouseX, mouseY);
            else if (mouseY >= bodyY + 7 && mouseY < bodyY + 22 && font.width(deviceName) > detailWidth - 22)
                g.setTooltipForNextFrame(font, deviceName, mouseX, mouseY);
            else if (mouseY >= bodyY + 22 && mouseY < bodyY + 36 && font.width(network) > detailWidth - 22)
                g.setTooltipForNextFrame(font, network, mouseX, mouseY);
            else if (mouseX >= detailX + metricWidth + 7 && mouseY >= metricY && mouseY < metricY + 42)
                g.setTooltipForNextFrame(font, Component.translatable("screen.umbral.radius").append(": ")
                        .append(Component.translatable("screen.umbral.blocks", NetworkTopology.RADIO_RADIUS)), mouseX, mouseY);
        } else if (mouseX >= left + 16 && mouseX < detailX - 8 && mouseY >= bodyY + bodyHeight - 36 && mouseY < bodyY + bodyHeight) {
            g.setComponentTooltipForNextFrame(font, java.util.List.of(Component.translatable("block.umbral." + status.kind()),
                    Component.literal(coordinates)), mouseX, mouseY);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void metric(GuiGraphicsExtractor g, int x, int y, int w, String value, String label, int color) {
        card(g, x, y, w, 42);
        g.pose().pushMatrix();
        g.pose().translate(x + 10, y + 5); g.pose().scale(2, 2);
        text(g, font, Component.literal(value), 0, 0, (w - 20) / 2, color);
        g.pose().popMatrix();
        text(g, font, Component.translatable(label), x + 10, y + 24, w - 20, MUTED);
    }
}
