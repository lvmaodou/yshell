package com.yshell.terminal;

import javafx.scene.paint.Color;

/**
 * 调色板与颜色桥接：
 * <ul>
 *   <li>16 色 ANSI + xterm 256 色（显式索引取值）</li>
 *   <li>{@link com.jediterm.core.Color} 与 JavaFX {@link Color} 互转</li>
 * </ul>
 */
public final class AnsiPalette {

    private AnsiPalette() {
    }

    // ===== 8 种常规色（低强度） =====
    private static final int[] NORMAL = {
            0x000000, 0xCD0000, 0x00CD00, 0xCDCD00,
            0x1E90FF, 0xCD00CD, 0x00CDCD, 0xE5E5E5
    };

    // ===== 8 种明亮色（高强度 / bold 时的颜色） =====
    private static final int[] BRIGHT = {
            0x4D4D4D, 0xFF0000, 0x00FF00, 0xFFFF00,
            0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF
    };

    /**
     * 取 ANSI 16 色的常规色（0-7）。
     */
    public static Color getNormal(int index) {
        return fromArgb(NORMAL[index & 7]);
    }

    /**
     * 取 ANSI 16 色的明亮色（0-7）。
     */
    public static Color getBright(int index) {
        return fromArgb(BRIGHT[index & 7]);
    }

    /**
     * 256 色索引：0-15 上面定义，16-231 是 6x6x6 cube，232-255 是灰阶。
     */
    public static Color get256(int index) {
        if (index < 0) index = 0;
        if (index > 255) index = 255;
        if (index < 16) {
            return index < 8 ? getNormal(index) : getBright(index - 8);
        }
        if (index >= 232) {
            int gray = 8 + (index - 232) * 10;
            return Color.rgb(gray, gray, gray);
        }
        int i = index - 16;
        int r = (i / 36) % 6;
        int g = (i / 6) % 6;
        int b = i % 6;
        int[] v = {0, 95, 135, 175, 215, 255};
        return Color.rgb(v[r], v[g], v[b]);
    }

    /**
     * 从 0xAARRGGBB 整数解出 JavaFX Color。
     */
    public static Color fromArgb(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        if ((argb & 0xFF000000) == 0) {
            a = 0xFF;
        }
        return Color.rgb(r, g, b, a / 255.0);
    }

    /**
     * 把 JediTerm 的 {@link com.jediterm.core.Color} 转成 JavaFX {@link Color}。
     * 输入为 null 时返回 null。
     */
    public static Color toFxColor(com.jediterm.core.Color c) {
        if (c == null) return null;
        int a = c.getAlpha() & 0xFF;
        return Color.rgb(c.getRed() & 0xFF,
                c.getGreen() & 0xFF,
                c.getBlue() & 0xFF,
                a / 255.0);
    }

    /**
     * 安全地将 TerminalColor 转换为 JavaFX Color。
     * 处理索引颜色的情况。
     */
    public static Color toFxColorSafe(com.jediterm.terminal.TerminalColor tc) {
        if (tc == null) return null;
        if (tc.isIndexed()) {
            int index = tc.getColorIndex();
            if (index < 16) {
                return index < 8 ? getNormal(index) : getBright(index - 8);
            }
            return get256(index);
        }
        try {
            com.jediterm.core.Color c = tc.toColor();
            return toFxColor(c);
        } catch (IllegalArgumentException e) {
            // 处理索引颜色的情况 - 使用默认前景色
            return Color.rgb(220, 220, 220);
        }
    }
}
