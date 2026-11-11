// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.time.Duration;

import static dev.umbral.client.ConsoleStyle.*;

/** Compact, inverted LCD instruments for the camera's monitor, rather than full console panels. */
final class CameraHudStyle {
    static final int GLASS = 0xF21B251E, EDGE = 0xFF616E5B, TEXT = LCD_LIGHT, SECONDARY = 0xFFA8B597;

    static void rail(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        cutPanel(g, x, y, w, h, EDGE);
        cutPanel(g, x + 1, y + 1, w - 2, h - 2, GLASS);
        g.fill(x + 2, y + 5, x + 3, y + h - 5, ORANGE);
        g.fill(x + 4, y + 1, x + w - 4, y + 2, 0x80616E5B);
        g.fill(x + 4, y + h - 2, x + w - 4, y + h - 1, 0x80525B57);
    }

    static void viewport(GuiGraphicsExtractor g, int width, int height) {
        // The brackets sit at the edges of the actual clear area, away from the centre of the image.
        corners(g, 9, 76, width - 18, height - 112, 8, 0xA0C0CF95);
        g.fill(9, 76, 12, 77, ORANGE);
        g.fill(width - 12, height - 37, width - 9, height - 36, ORANGE);
    }

    static int controlWidth(Font font, Component label, String shortcut) {
        return font.width(label) + font.width(shortcut) + 20;
    }

    /** Native mouse, focus, sound and narration with compact shortcut keycaps. */
    static final class Control extends Button {
        private final Component label;
        private final String shortcut;
        private boolean selected;

        Control(int x, int y, int w, Component label, String shortcut, Component description, OnPress action) {
            super(x, y, w, 20, description, action, DEFAULT_NARRATION);
            this.label = label;
            this.shortcut = shortcut;
            setTooltipDelay(Duration.ofMillis(350));
            describe(description);
        }

        void describe(Component description) {
            setMessage(description);
            setTooltip(Tooltip.create(description));
        }

        void selected(boolean value) { selected = value; }
        Component label() { return label; }

        @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            Font font = Minecraft.getInstance().font;
            int x = getX(), y = getY(), w = getWidth();
            boolean focused = isHoveredOrFocused();
            cutPanel(g, x, y, w, 20, focused ? LCD_LIGHT : EDGE);
            cutPanel(g, x + 1, y + 1, w - 2, 18, focused ? 0xFF35442F : 0xFF222F23);
            if (label.getString().isEmpty()) {
                g.text(font, shortcut, x + (w - font.width(shortcut)) / 2, y + 6, active ? TEXT : DIM, false);
                if (isFocused()) corners(g, x - 2, y - 2, w + 4, 24, 3, ORANGE);
                return;
            }
            int keyWidth = font.width(shortcut) + 6;
            g.fill(x + 4, y + 4, x + 4 + keyWidth, y + 16, selected || focused ? LCD_LIGHT : LCD);
            g.text(font, shortcut, x + 7, y + 6, INK, false);
            text(g, font, label, x + keyWidth + 9, y + 6, w - keyWidth - 13, TEXT);
            if (selected) g.fill(x + keyWidth + 9, y + 17, x + w - 4, y + 18, ORANGE);
            if (isFocused()) corners(g, x - 2, y - 2, w + 4, 24, 3, ORANGE);
        }
    }

    private CameraHudStyle() { }
}
