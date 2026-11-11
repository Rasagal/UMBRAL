// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.access.AccessProtocol;
import dev.umbral.access.AccessRegistry;
import dev.umbral.access.AccessService;
import dev.umbral.network.NetworkService;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static dev.umbral.client.ConsoleStyle.*;

/** The access module uses the same LCD, housing, focus and narration as the surveillance console. */
public final class ReaderAccessScreen extends Screen {
    private AccessProtocol.Snapshot data;
    private final Map<ConsoleButton, AccessProtocol.Card> revokeButtons = new HashMap<>();
    private ConsoleButton issue,clearFilter;
    private int left, top, panelWidth, panelHeight, rows, page, ticks, lastUpdate, sentAt, noticeUntil;
    private long nonce;
    private boolean pending;
    private Component notice;

    public ReaderAccessScreen(AccessProtocol.Snapshot data) {
        super(Component.translatable("access.umbral.title")); this.data = data;
    }
    private boolean fresh() { return ticks - lastUpdate <= 60 && data.state() != AccessService.Result.INVALID; }
    private boolean actionable() { return fresh() && !pending; }
    private int pages() { return Math.max(1, (data.cards().size() + Math.max(1, rows) - 1) / Math.max(1, rows)); }
    private Component name(AccessProtocol.Card card) {
        return card.label().isEmpty() ? Component.translatable("access.umbral.pass_number", card.serial()) : Component.literal(card.label());
    }
    @Override protected void init() {
        var focus = getFocused(); var focusedCard = revokeButtons.get(focus);
        Component focusedLabel = focus instanceof Button button ? button.getMessage() : null;
        clearWidgets(); revokeButtons.clear();
        panelWidth = Math.min(500, width - 20); panelHeight = Math.min(310, height - 20);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        rows = Math.max(1, (panelHeight - 156) / 32); page = Math.min(page, pages() - 1);
        for (int row = 0; row < rows && page * rows + row < data.cards().size(); row++) {
            var card = data.cards().get(page * rows + row);
            var button = addRenderableWidget(new ConsoleButton(left + panelWidth - 94, top + 88 + row * 32, 70, 22,
                    Component.translatable("access.umbral.revoke"), false, b -> send(AccessProtocol.Operation.REVOKE, card.id())));
            button.describe(Component.translatable("access.umbral.revoke_hint", name(card)));
            button.active = actionable(); revokeButtons.put(button, card);
            if (focusedCard != null && focusedCard.id().equals(card.id())) setFocused(button);
        }
        var previous = addRenderableWidget(new ConsoleButton(left + 16, top + panelHeight - 53, 22, 14,
                Component.literal("<"), false, b -> { page--; init(); }));
        previous.active = page > 0; previous.describe(Component.translatable("ui.umbral.previous"));
        var next = addRenderableWidget(new ConsoleButton(left + 42, top + panelHeight - 53, 22, 14,
                Component.literal(">"), false, b -> { page++; init(); }));
        next.active = page + 1 < pages(); next.describe(Component.translatable("ui.umbral.next"));
        clearFilter=null;
        if(data.filtered()||data.interlocked()){
            clearFilter=addRenderableWidget(new ConsoleButton(left+panelWidth-158,top+panelHeight-54,142,16,Component.translatable(data.interlocked()?"airlock.umbral.clear_guard":"filter.umbral.clear_guard"),false,b->minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(ok->{minecraft.gui.setScreen(this);if(ok)send(data.interlocked()?AccessProtocol.Operation.CLEAR_AIRLOCK:AccessProtocol.Operation.CLEAR_FILTER,null);},Component.translatable(data.interlocked()?"airlock.umbral.clear_guard":"filter.umbral.clear_guard"),Component.translatable(data.interlocked()?"airlock.umbral.clear_guard_hint":"filter.umbral.clear_guard_hint")))));
        }
        issue = addRenderableWidget(new ConsoleButton(left + 16, top + panelHeight - 34, 118, 22,
                Component.translatable("access.umbral.issue"), true, b -> send(AccessProtocol.Operation.ISSUE, null)));
        issue.active = actionable() && data.canIssue(); issue.describe(Component.translatable("access.umbral.issue_hint"));
        addRenderableWidget(new ConsoleButton(left + panelWidth - 96, top + panelHeight - 34, 80, 22,
                Component.translatable("gui.done"), false, b -> onClose()));
        addRenderableWidget(ManualScreen.help(this,left+panelWidth-30,top+31,dev.umbral.manual.ManualCatalog.Entry.READER));
        if (focusedCard == null && focusedLabel != null) children().stream()
                .filter(c -> c instanceof Button b && b.active && b.getMessage().equals(focusedLabel)).findFirst().ifPresent(this::setFocused);
    }
    private void send(AccessProtocol.Operation operation, UUID card) {
        if (operation != AccessProtocol.Operation.QUERY && !actionable()) return;
        nonce = ThreadLocalRandom.current().nextLong(); pending = true; sentAt = ticks;
        ClientPlayNetworking.send(new AccessProtocol.Request(data.reader(), data.identity(), data.link(), nonce, operation, card));
        if (operation != AccessProtocol.Operation.QUERY) { notice = Component.translatable("ui.umbral.updating"); noticeUntil = ticks + 80; }
    }
    public void accept(AccessProtocol.Snapshot value) {
        if (!data.reader().equals(value.reader()) || !data.identity().equals(value.identity())
                || !Objects.equals(data.link(), value.link()) || !pending || nonce != value.nonce()) return;
        pending = false; lastUpdate = ticks;
        if (value.result() == AccessService.Result.BUSY) {
            notice = Component.translatable(value.result().key()); noticeUntil = ticks + 80; return;
        }
        boolean changed = !data.cards().equals(value.cards())||data.filtered()!=value.filtered()||data.interlocked()!=value.interlocked(); data = value;
        if (value.result() != AccessService.Result.READY) { notice = Component.translatable(value.result().key()); noticeUntil = ticks + 80; }
        if (changed) init();
    }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.level == null || !minecraft.player.isAlive() || minecraft.player.isSpectator()
                || data.reader().distToCenterSqr(minecraft.player.position()) > 64) { onClose(); return; }
        ticks++;
        if (pending && ticks - sentAt > 60) { pending = false; lastUpdate = ticks - 61; }
        if (ticks % 20 == 0 && !pending) send(AccessProtocol.Operation.QUERY, null);
        issue.active = actionable() && data.canIssue();if(clearFilter!=null)clearFilter.active=actionable();
        revokeButtons.keySet().forEach(b -> b.active = actionable() && data.link() != null);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        backdrop(g, width, height); frame(g, font, left, top, panelWidth, panelHeight, title, "ACCESO");
        Component target = data.door() == null ? Component.translatable("access.umbral.link_hint")
                : data.doorName().isEmpty() ? Component.translatable("access.umbral.door_at", NetworkService.coordinates(data.door().position()))
                : Component.literal(data.doorName());
        text(g, font, target, left + 17, top + 58, panelWidth - 34, MUTED);
        text(g, font, Component.translatable("access.umbral.active_passes", data.cards().size(), AccessRegistry.MAX_PASSES), left + 17, top + 73, panelWidth - 34, INK);
        for (int row = 0; row < rows && page * rows + row < data.cards().size(); row++) {
            var entry = data.cards().get(page * rows + row); int y = top + 84 + row * 32;
            card(g, left + 16, y, panelWidth - 32, 30);
            device(g, left + 24, y + 7, "access_card", 1, true);
            text(g, font, name(entry), left + 47, y + 6, panelWidth - 149, INK);
            text(g, font, Component.translatable("access.umbral.bearer"), left + 47, y + 18, panelWidth - 149, MUTED);
            if (mouseX >= left + 20 && mouseX < left + panelWidth - 100 && mouseY >= y && mouseY < y + 30)
                g.setComponentTooltipForNextFrame(font, List.of(name(entry), Component.translatable("access.umbral.card_hint")), mouseX, mouseY);
        }
        if (data.cards().isEmpty()) {
            card(g, left + 16, top + 84, panelWidth - 32, rows * 32 - 2);
            device(g, left + 27, top + 96, "access_card", 2, false);
            text(g, font, Component.translatable("access.umbral.empty"), left + 70, top + 99, panelWidth - 98, INK);
            text(g, font, Component.translatable("access.umbral.empty_hint"), left + 70, top + 115, panelWidth - 98, MUTED);
        }
        Component status = data.state() == AccessService.Result.INVALID ? Component.translatable(data.state().key())
                : !fresh() ? Component.translatable("screen.umbral.stale") : notice != null && ticks < noticeUntil ? notice
                : Component.translatable(data.state() == AccessService.Result.READY && !data.canIssue() && data.cards().isEmpty()
                    ? "access.umbral.no_blank" : data.state().key());
        text(g, font, status, left + 17, top + panelHeight - 65, panelWidth - 34,
                data.state() == AccessService.Result.READY ? INK : ALERT);
        text(g, font, Component.translatable("ui.umbral.page", page + 1, pages()), left + 74, top + panelHeight - 49, data.filtered()||data.interlocked()?panelWidth-244:panelWidth - 94, MUTED);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }
}
