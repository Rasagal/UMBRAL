// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.camera.CameraAccess;
import dev.umbral.camera.CameraProtocol;
import dev.umbral.network.NetworkProtocol;
import dev.umbral.network.NetworkService;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static dev.umbral.client.ConsoleStyle.*;

public final class CameraDirectoryScreen extends net.minecraft.client.gui.screens.Screen {
    private final NetworkProtocol.Status terminal;
    private List<CameraProtocol.CameraEntry> entries = List.of();
    private CameraAccess.State state;
    private Component notice;
    private long listRequest;
    private Long openRequest;
    private long nextOpenAt;
    private boolean fresh;
    private int ticks, lastUpdate, openTick, page, rows;
    private int left, top, panelWidth, panelHeight;
    private final Map<ConsoleButton, CameraProtocol.CameraEntry> viewButtons = new HashMap<>();

    public CameraDirectoryScreen(NetworkProtocol.Status terminal) {
        super(Component.translatable("screen.umbral.surveillance"));
        this.terminal = terminal;
    }

    @Override public void added() { fresh = false; requestList(); }

    @Override protected void init() {
        var oldFocus = getFocused();
        var focusedEntry = viewButtons.get(oldFocus);
        Component focusedLabel = oldFocus instanceof Button button ? button.getMessage() : null;
        clearWidgets(); viewButtons.clear();
        panelWidth = Math.min(500, width - 20); panelHeight = Math.min(310, height - 20);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        rows = Math.max(1, Math.min(6, (panelHeight - 134) / 40));
        page = Math.min(page, pages() - 1);
        for (int row = 0; row < rows && page * rows + row < entries.size(); row++) {
            var entry = entries.get(page * rows + row);
            ConsoleButton button = addRenderableWidget(new ConsoleButton(left + panelWidth - 83, top + 82 + row * 40, 58, 24,
                    Component.translatable("screen.umbral.view"), true, b -> open(entry)));
            button.active = canOpen(entry);
            button.describe(cameraName(entry.label(), entry.position()).copy().append("\n").append(entryStatus(entry)));
            viewButtons.put(button, entry);
            if (focusedEntry != null && focusedEntry.position().equals(entry.position())) setFocused(button);
        }
        ConsoleButton previous = addRenderableWidget(new ConsoleButton(left + 16, top + panelHeight - 34, 24, 22,
                Component.literal("<"), false, b -> { page--; init(); }));
        previous.describe(Component.translatable("ui.umbral.previous"));
        previous.active = page > 0;
        ConsoleButton next = addRenderableWidget(new ConsoleButton(left + 44, top + panelHeight - 34, 24, 22,
                Component.literal(">"), false, b -> { page++; init(); }));
        next.describe(Component.translatable("ui.umbral.next"));
        next.active = page + 1 < pages();
        addRenderableWidget(new ConsoleButton(left + panelWidth - 96, top + panelHeight - 34, 80, 22,
                Component.translatable("screen.umbral.back"), false, b -> onClose()));
        addRenderableWidget(ManualScreen.help(this,left+panelWidth-30,top+31,dev.umbral.manual.ManualCatalog.Entry.VIGIA));
        if (focusedEntry == null && focusedLabel != null) children().stream()
                .filter(child -> child instanceof Button button && button.active && button.getMessage().equals(focusedLabel))
                .findFirst().ifPresent(this::setFocused);
    }

    private int pages() { return Math.max(1, (entries.size() + Math.max(1, rows) - 1) / Math.max(1, rows)); }
    private boolean canOpen(CameraProtocol.CameraEntry entry) {
        return fresh && minecraft.level != null && minecraft.level.getGameTime() >= nextOpenAt
                && state == CameraAccess.State.LIVE && entry.state() == CameraAccess.State.LIVE
                && ticks - lastUpdate <= 60 && openRequest == null && CameraViewScreen.videoAvailable(minecraft, entry.position());
    }
    private void requestList() {
        listRequest = ThreadLocalRandom.current().nextLong();
        ClientPlayNetworking.send(new CameraProtocol.ListRequest(terminal.position(), listRequest));
    }
    private void open(CameraProtocol.CameraEntry entry) {
        if (!canOpen(entry)) return;
        openRequest = ThreadLocalRandom.current().nextLong(); openTick = ticks;
        nextOpenAt = minecraft.level.getGameTime() + 12;
        notice = Component.translatable("camera.umbral.connecting");
        ClientPlayNetworking.send(new CameraProtocol.OpenRequest(terminal.position(), entry.position(), openRequest));
    }

    public void accept(CameraProtocol.Listing listing) {
        if (!terminal.position().equals(listing.terminal()) || listRequest != listing.request()) return;
        boolean changed = !entries.equals(listing.entries()) || state != listing.state();
        entries = listing.entries(); state = listing.state(); lastUpdate = ticks; fresh = true;
        if (changed) init();
    }
    public boolean awaiting(CameraProtocol.Feed feed) {
        return openRequest != null && openRequest == feed.request() && terminal.position().equals(feed.terminal());
    }
    public void accept(CameraProtocol.Feed feed) {
        if (!awaiting(feed)) return;
        openRequest = null;
        if (feed.state() != CameraAccess.State.LIVE) { showNotice(Component.translatable(feed.state().key())); return; }
        if (!CameraViewScreen.videoAvailable(minecraft, feed.camera())) {
            ClientPlayNetworking.send(new CameraProtocol.Control(feed.session(), true));
            showNotice(Component.translatable("camera.umbral.no_video")); return;
        }
        notice = null;
        minecraft.gui.setScreen(new CameraViewScreen(this, feed));
    }
    void showNotice(Component text) { notice = text; openRequest = null; }

    @Override public void tick() {
        if (minecraft.player == null || minecraft.level == null || !minecraft.player.isAlive()
                || terminal.position().distToCenterSqr(minecraft.player.position()) > 64) {
            minecraft.gui.setScreen(null); return;
        }
        ticks++;
        if (ticks % 20 == 0) requestList();
        if (openRequest != null && ticks - openTick > 60) { openRequest = null; notice = Component.translatable("camera.umbral.expired"); }
        viewButtons.forEach((button, entry) -> {
            button.active = canOpen(entry);
            button.describe(cameraName(entry.label(), entry.position()).copy().append("\n").append(entryStatus(entry)));
        });
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(new NetworkStatusScreen(terminal)); }

    public static Component cameraName(String label, BlockPos position) {
        return label.isEmpty() ? Component.translatable("camera.umbral.default_name", NetworkService.coordinates(position)) : Component.literal(label);
    }

    private boolean telemetryCurrent() { return fresh && ticks - lastUpdate <= 60; }
    private boolean ready(CameraProtocol.CameraEntry entry) {
        return telemetryCurrent() && state == CameraAccess.State.LIVE && entry.state() == CameraAccess.State.LIVE
                && CameraViewScreen.videoAvailable(minecraft, entry.position());
    }
    private Component entryStatus(CameraProtocol.CameraEntry entry) {
        if (ticks - lastUpdate > 60) return Component.translatable("camera.umbral.expired");
        if (!telemetryCurrent()) return Component.translatable("camera.umbral.loading");
        if (state != CameraAccess.State.LIVE && state != null) return Component.translatable(state.key());
        if (entry.state() != CameraAccess.State.LIVE) return Component.translatable(entry.state().key());
        return Component.translatable(CameraViewScreen.videoAvailable(minecraft, entry.position())
                ? "camera.umbral.ready" : "camera.umbral.no_video");
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        backdrop(g, width, height);
        frame(g, font, left, top, panelWidth, panelHeight, title, "VIGÍA");
        Component network = terminal.networkName().isEmpty() ? Component.translatable("ui.umbral.unpaired") : Component.literal(terminal.networkName());
        text(g, font, network, left + 17, top + 58, panelWidth - 142, MUTED);
        long readyCount = entries.stream().filter(this::ready).count();
        Component count = telemetryCurrent() ? Component.translatable("ui.umbral.available", readyCount, entries.size())
                : Component.translatable("ui.umbral.updating");
        text(g, font, count, left + panelWidth - 134, top + 58, 117, telemetryCurrent() ? INK : DIM);
        for (int row = 0; row < rows && page * rows + row < entries.size(); row++) {
            var entry = entries.get(page * rows + row);
            int y = top + 76 + row * 40;
            boolean online = ready(entry);
            card(g, left + 16, y, panelWidth - 32, 36);
            g.fill(left + 17, y + 5, left + 19, y + 31, online ? INK : ALERT);
            g.fill(left + 24, y + 5, left + 50, y + 31, LCD);
            device(g, left + 29, y + 9, "camera", 1, online);
            text(g, font, cameraName(entry.label(), entry.position()), left + 59, y + 7, panelWidth - 152, INK);
            statusLight(g, left + 60, y + 23, online ? INK : ALERT);
            text(g, font, entryStatus(entry), left + 70, y + 21, panelWidth - 163, online ? INK : ALERT);
            if (mouseX >= left + 20 && mouseX < left + panelWidth - 90 && mouseY >= y && mouseY < y + 36) {
                g.setComponentTooltipForNextFrame(font, List.of(cameraName(entry.label(), entry.position()),
                        Component.literal(NetworkService.coordinates(entry.position())), entryStatus(entry)), mouseX, mouseY);
            }
        }
        if (entries.isEmpty()) {
            int emptyHeight = panelHeight - 133;
            card(g, left + 16, top + 76, panelWidth - 32, emptyHeight);
            int center = left + panelWidth / 2;
            device(g, center - 16, top + 82, "camera", 2, false);
            Component empty = Component.translatable(!telemetryCurrent() ? "camera.umbral.loading"
                    : state == CameraAccess.State.LIVE ? "ui.umbral.no_cameras" : "ui.umbral.catalog_offline");
            String label = fit(font, empty.getString(), panelWidth - 52);
            g.text(font, label, center - font.width(label) / 2, top + 121, INK, false);
            if (emptyHeight >= 100 && telemetryCurrent() && state == CameraAccess.State.LIVE) {
                int lineY = top + 140;
                for (var line : font.split(Component.translatable("camera.umbral.empty"), panelWidth - 64)) {
                    g.text(font, line, center - font.width(line) / 2, lineY, MUTED, false); lineY += 11;
                }
            }
        }
        Component footer = ticks - lastUpdate > 60 ? Component.translatable("camera.umbral.expired")
                : state != null && state != CameraAccess.State.LIVE ? Component.translatable(state.key()) : notice;
        if (footer == null) footer = Component.translatable(entries.isEmpty() ? "camera.umbral.empty" : "ui.umbral.select_camera");
        text(g, font, footer, left + 17, top + panelHeight - 51, panelWidth - 34, notice != null || state != CameraAccess.State.LIVE ? ALERT : MUTED);
        text(g, font, Component.translatable("ui.umbral.page", page + 1, pages()),
                left + 78, top + panelHeight - 27, panelWidth - 185, MUTED);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }
}
