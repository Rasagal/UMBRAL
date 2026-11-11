// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** UMBRAL's pixel-aligned LCD display, grey housing and copper fittings. */
final class ConsoleStyle {
    static final int SHELL = 0xFFB2B4AC, SHELL_LIGHT = 0xFFD7D9CD, SHELL_DARK = 0xFF525B57;
    static final int ORANGE = 0xFFC57B45, RED = 0xFFC84B3D;
    static final int LCD = 0xFFA6B77A, SURFACE = 0xFFB5C48B, LCD_LIGHT = 0xFFC0CF95;
    static final int INK = 0xFF253825, MUTED = 0xFF37472D, LINE = 0xFF6A8052, DIM = 0xFF66794D;
    static final int ALERT = 0xFF6C3D27;
    static final int STEEL = 0xFF7D8989, GASKET = 0xFF172126;

    static void backdrop(GuiGraphicsExtractor g, int width, int height) {
        g.fill(0, 0, width, height, 0xB5121815);
    }

    static void frame(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, Component subtitle, String module) {
        cutPanel(g, x - 3, y + 4, w + 6, h + 2, 0x80050907);
        cutPanel(g, x, y, w, h, SHELL_DARK);
        cutPanel(g, x + 1, y + 1, w - 2, h - 2, SHELL);
        g.fill(x + 11, y + 2, x + w - 11, y + 4, SHELL_LIGHT);
        g.fill(x + 2, y + 11, x + 4, y + h - 11, SHELL_LIGHT);
        g.fill(x + 11, y + h - 4, x + w - 11, y + h - 2, 0xFF7B8479);
        // A recessed LCD sits inside the grey housing; copper belongs to its physical fittings.
        cutPanel(g, x + 7, y + 47, w - 14, h - 54, STEEL);
        cutPanel(g, x + 8, y + 48, w - 16, h - 56, GASKET);
        g.fill(x + 10, y + 50, x + w - 10, y + h - 10, LCD);
        g.fill(x + 10, y + 50, x + w - 10, y + 52, LINE);
        g.fill(x + 10, y + 50, x + 12, y + h - 10, LINE);
        g.fill(x + 12, y + h - 11, x + w - 10, y + h - 10, LCD_LIGHT);
        for (int dx : new int[]{0, w - 11}) for (int dy : new int[]{0, h - 11}) {
            g.fill(x + dx + 2, y + dy + 2, x + dx + 9, y + dy + 9, ORANGE);
            g.fill(x + dx + 2, y + dy + 2, x + dx + 9, y + dy + 3, 0xFFE0A77A);
            g.fill(x + dx + 4, y + dy + 4, x + dx + 8, y + dy + 8, GASKET);
            g.fill(x + dx + 4, y + dy + 4, x + dx + 7, y + dy + 7, STEEL);
            g.fill(x + dx + 4, y + dy + 4, x + dx + 7, y + dy + 5, SHELL_LIGHT);
            g.fill(x + dx + 5, y + dy + 5, x + dx + 7, y + dy + 6, GASKET);
        }
        mark(g, x + 18, y + 16, 22, SHELL_DARK);
        g.fill(x + 26, y + 24, x + 32, y + 30, ORANGE);
        wordmark(g, x + 50, y + 13, 0xFF303C35);
        text(g, font, subtitle, x + 50, y + 32, w - 84, 0xFF39463C);
        powerLamp(g, x + w - 27, y + 19);
        if (w >= 330) {
            int tagWidth = font.width(module) + 16;
            cutPanel(g, x + w - tagWidth - 38, y + 16, tagWidth, 14, SHELL_DARK);
            g.text(font, module, x + w - tagWidth - 30, y + 19, SHELL_LIGHT, false);
        }
        g.fill(x + 50, y + 44, x + 120, y + 46, ORANGE);
        for (int i = 0; i < 4; i++) g.fill(x + w - 45 + i * 7, y + h - 7, x + w - 41 + i * 7, y + h - 5, SHELL_DARK);
        g.fill(x + 16, y + h - 38, x + w - 16, y + h - 37, LINE);
        g.fill(x + w - 40, y + h - 38, x + w - 16, y + h - 37, INK);
    }

    /** Hardware power pilot, separate from network status and recording capabilities. */
    static void powerLamp(GuiGraphicsExtractor g, int x, int y) {
        cutPanel(g, x - 1, y - 1, 9, 9, SHELL_DARK);
        cutPanel(g, x, y, 7, 7, RED);
        g.fill(x + 1, y + 1, x + 3, y + 3, 0xFFF09A79);
    }

    static void cutPanel(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        if (w < 4 || h < 4) return;
        g.fill(x + 2, y, x + w - 2, y + 2, color);
        g.fill(x, y + 2, x + w, y + h - 2, color);
        g.fill(x + 2, y + h - 2, x + w - 2, y + h, color);
    }

    static void card(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        cutPanel(g, x, y, w, h, LINE);
        cutPanel(g, x + 1, y + 1, w - 2, h - 2, SURFACE);
        g.fill(x + 3, y + 1, x + w - 3, y + 2, LCD_LIGHT);
    }

    static void corners(GuiGraphicsExtractor g, int x, int y, int w, int h, int length, int color) {
        for (int sideX : new int[]{0, 1}) for (int sideY : new int[]{0, 1}) {
            int cx = x + sideX * (w - length), cy = y + sideY * (h - length);
            g.fill(cx, cy + sideY * (length - 1), cx + length, cy + sideY * (length - 1) + 1, color);
            g.fill(cx + sideX * (length - 1), cy, cx + sideX * (length - 1) + 1, cy + length, color);
        }
    }

    static void mark(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        corners(g, x, y, size, size, size / 3, color);
        int inner = size / 3, offset = (size - inner) / 2;
        g.fill(x + offset, y + offset, x + offset + inner, y + offset + inner, color);
        g.fill(x + offset + 2, y + offset + 2, x + offset + inner - 2, y + offset + inner - 2, LCD_LIGHT);
    }

    /** Original block-letter wordmark, deliberately crisp at every Minecraft GUI scale. */
    private static void wordmark(GuiGraphicsExtractor g, int x, int y, int color) {
        String[] letters = {
                "10001100011000110001100011000101110", // U
                "10001110111010110101100011000110001", // M
                "11110100011000111110100011000111110", // B
                "11110100011000111110101001001010001", // R
                "01110100011000111111100011000110001", // A
                "10000100001000010000100001000011111"  // L
        };
        for (int letter = 0; letter < letters.length; letter++) {
            String pixels = letters[letter];
            for (int i = 0; i < pixels.length(); i++) if (pixels.charAt(i) == '1') {
                int px = x + letter * 12 + i % 5 * 2, py = y + i / 5 * 2;
                g.fill(px, py, px + 2, py + 2, color);
            }
        }
    }

    /** LCD silhouettes retain the devices' shapes using the display's green tones. */
    static void device(GuiGraphicsExtractor g, int x, int y, String kind, int scale, boolean online) {
        g.pose().pushMatrix();
        g.pose().translate(x, y); g.pose().scale(scale, scale);
        int light = online ? LCD_LIGHT : LINE;
        switch (kind) {
            case "inventory_scanner" -> {g.fill(1,1,15,4,INK);g.fill(1,3,4,16,INK);g.fill(12,3,15,16,INK);g.fill(2,4,3,13,light);g.fill(13,4,14,13,light);g.fill(4,8,12,9,online?DIM:LINE);g.fill(0,14,5,16,MUTED);g.fill(11,14,16,16,MUTED);}

            case "centella_turret" -> {
                g.fill(1,13,15,16,INK);g.fill(3,10,13,13,MUTED);g.fill(2,1,12,10,LCD_LIGHT);
                g.fill(1,3,9,10,INK);g.fill(2,4,8,9,MUTED);g.fill(4,5,6,7,light);
                g.fill(11,7,16,10,INK);g.fill(12,6,15,8,MUTED);g.fill(9,3,10,4,INK);
            }
            case "baluarte_turret" -> {
                g.fill(2,13,14,16,INK);g.fill(4,10,12,13,MUTED);
                g.fill(2,2,7,10,INK);g.fill(9,2,14,10,INK);
                g.fill(3,2,6,8,LCD_LIGHT);g.fill(10,2,13,8,LCD_LIGHT);
                g.fill(0,8,3,10,MUTED);g.fill(13,8,16,10,MUTED);g.fill(1,9,15,10,INK);
                g.fill(7,8,9,10,MUTED);g.fill(11,4,12,5,INK);g.fill(11,6,12,7,light);
                g.fill(6,11,7,12,light);g.fill(9,11,10,12,light);g.fill(4,14,11,15,LCD_LIGHT);
            }
            case "presence_sensor" -> {g.fill(3,1,13,15,INK);g.fill(4,2,5,14,LCD_LIGHT);g.fill(11,2,12,14,LCD_LIGHT);g.fill(5,5,11,11,MUTED);g.fill(6,6,10,10,light);}
            case "pulse_mine", "mota_dock" -> {g.fill(1,9,15,13,INK);g.fill(3,6,13,10,LCD_LIGHT);g.fill(6,7,10,9,light);g.fill(1,9,3,13,MUTED);g.fill(13,9,15,13,MUTED);}
            case "nexo_terminal" -> {g.fill(1,1,15,15,INK);g.fill(3,3,13,12,LCD_LIGHT);g.fill(4,5,12,6,INK);g.fill(4,8,10,9,MUTED);g.fill(11,13,13,14,ALERT);}
            case "access_card" -> {
                g.fill(1, 2, 15, 13, INK); g.fill(2, 3, 14, 12, LCD_LIGHT);
                g.fill(2, 4, 14, 6, INK); g.fill(4, 8, 7, 10, MUTED);
                g.fill(10, 8, 12, 10, online ? INK : DIM);
            }
            case "card_reader" -> {
                g.fill(4, 1, 12, 15, INK); g.fill(5, 2, 11, 14, LCD_LIGHT);
                g.fill(6, 4, 10, 8, INK); g.fill(7, 5, 9, 7, light);
                g.fill(6, 10, 10, 12, INK); g.fill(4, 1, 6, 3, MUTED); g.fill(10, 13, 12, 15, MUTED);
            }
            case "electric_fence" -> {
                g.fill(1,1,2,16,INK);g.fill(14,1,15,16,INK);g.fill(1,2,15,3,ORANGE);g.fill(1,14,15,15,MUTED);
                for(int row=4;row<14;row++)for(int col=3;col<14;col++)if((row+col)%4==0||Math.floorMod(row-col,4)==0)g.fill(col,row,col+1,row+1,DIM);
                g.fill(1,10,4,14,INK);g.fill(2,11,3,12,online?ORANGE:DIM);
            }
            case "security_door" -> {
                g.fill(2, 0, 14, 16, INK); g.fill(4, 2, 7, 15, LCD_LIGHT);
                g.fill(9, 2, 12, 15, LCD_LIGHT); g.fill(3, 1, 13, 2, light);
                g.fill(5, 3, 7, 7, MUTED); g.fill(9, 3, 11, 7, MUTED);
            }
            case "camera", "security_camera" -> {
                g.fill(2, 4, 13, 11, INK);
                g.fill(1, 3, 14, 5, MUTED);
                g.fill(2, 6, 7, 10, LCD);
                g.fill(3, 6, 6, 9, INK);
                g.fill(10, 7, 12, 9, light);
                g.fill(9, 11, 11, 13, MUTED);
                g.fill(10, 13, 15, 15, MUTED);
                g.fill(14, 10, 16, 16, MUTED);
            }
            case "core", "network_core" -> {
                g.fill(3, 1, 13, 15, INK);
                for (int row = 0; row < 3; row++) {
                    g.fill(4, 3 + row * 4, 12, 6 + row * 4, LCD);
                    g.fill(9, 4 + row * 4, 11, 5 + row * 4, online ? INK : DIM);
                }
                g.fill(2, 1, 4, 4, MUTED); g.fill(12, 12, 14, 15, MUTED);
            }
            case "node", "sector_node" -> {
                g.fill(4, 0, 5, 8, MUTED); g.fill(11, 0, 12, 8, MUTED);
                g.fill(3, 7, 13, 9, MUTED);
                g.fill(3, 8, 13, 14, INK);
                g.fill(2, 13, 14, 15, MUTED);
                g.fill(6, 10, 10, 12, light);
            }
            default -> {
                g.fill(1, 2, 15, 12, INK);
                g.fill(2, 3, 14, 11, MUTED);
                g.fill(3, 4, 13, 10, light);
                g.fill(5, 5, 11, 6, INK); g.fill(5, 7, 9, 8, INK);
                g.fill(7, 12, 9, 14, MUTED);
                g.fill(4, 14, 12, 15, MUTED);
            }
        }
        g.pose().popMatrix();
    }

    static void statusLight(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x - 2, y - 2, x + 7, y + 7, (color & 0xFFFFFF) | 0x20000000);
        g.fill(x, y, x + 5, y + 5, color);
    }

    static String fit(Font font, String value, int width) {
        if (width <= 0) return "";
        if (font.width(value) <= width) return value;
        String suffix = "…";
        if (font.width(suffix) > width) return "";
        return font.plainSubstrByWidth(value, width - font.width(suffix)) + suffix;
    }

    static void text(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int width, int color) {
        g.text(font, fit(font, text.getString(), width), x, y, color, false);
    }

    private ConsoleStyle() { }
}
