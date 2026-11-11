// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import java.time.Duration;

import static dev.umbral.client.ConsoleStyle.*;

/** Native button input/narration, with UMBRAL visuals instead of vanilla stone sprites. */
final class ConsoleButton extends Button {
    private final boolean primary;
    private Component description;

    ConsoleButton(int x, int y, int width, int height, Component message, boolean primary, OnPress action) {
        super(x, y, width, height, message, action, DEFAULT_NARRATION);
        this.primary = primary;
        setTooltipDelay(Duration.ofMillis(350));
    }

    void describe(Component value) {
        if (!value.equals(description)) {
            description = value;
            setTooltip(Tooltip.create(value));
        }
    }

    @Override protected MutableComponent createNarrationMessage() {
        MutableComponent narration = super.createNarrationMessage();
        return description == null ? narration : narration.append(". ").append(description);
    }

    @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean highlighted = active && isHoveredOrFocused();
        int edge = active ? INK : LINE;
        int background = !active ? LCD : primary ? highlighted ? 0xFF182C1C : INK : highlighted ? LCD_LIGHT : SURFACE;
        cutPanel(g, x, y, w, h, edge);
        cutPanel(g, x + 1, y + 1, w - 2, h - 2, background);
        if (active && !primary) g.fill(x + 3, y + 5, x + 4, y + h - 5, INK);
        if (highlighted) g.fill(x + 4, y + h - 3, x + w - 4, y + h - 2, primary ? ORANGE : INK);
        var font = Minecraft.getInstance().font;
        String label = fit(font, getMessage().getString(), w - (w < 28 ? 6 : 14));
        g.text(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2,
                !active ? DIM : primary ? LCD_LIGHT : INK, false);
        if (isFocused() && active) corners(g, x - 2, y - 2, w + 4, h + 4, 4, INK);
    }
}
