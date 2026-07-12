package com.yshell.terminal;

import com.sun.jna.*;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

/**
 * Windows IMM32 API 接口，用于控制输入法候选框位置。
 */
public interface Imm32 extends Library {
    Imm32 INSTANCE = Native.load("imm32", Imm32.class);

    // CFS_CANDIDATEPOS - 设置候选窗口位置
    int CFS_CANDIDATEPOS = 0x0040;

    /**
     * 获取输入法上下文
     */
    Pointer ImmGetContext(WinDef.HWND hWnd);

    /**
     * 释放输入法上下文
     */
    void ImmReleaseContext(WinDef.HWND hWnd, Pointer hIMC);

    /**
     * 设置输入法候选窗口位置
     */
    void ImmSetCompositionWindow(Pointer hIMC, COMPOSITIONFORM lpCompForm);

    /**
     * COMPOSITIONFORM 结构体
     */
    @Structure.FieldOrder({"dwStyle", "ptCurrentPos", "rcArea"})
    class COMPOSITIONFORM extends Structure {
        public int dwStyle;
        public POINT ptCurrentPos;
        public RECT rcArea;

        public COMPOSITIONFORM() {
            ptCurrentPos = new POINT();
            rcArea = new RECT();
        }
    }

    /**
     * POINT 结构体
     */
    @Structure.FieldOrder({"x", "y"})
    class POINT extends Structure {
        public int x;
        public int y;
    }

    /**
     * RECT 结构体
     */
    @Structure.FieldOrder({"left", "top", "right"})
    class RECT extends Structure {
        public int left;
        public int top;
        public int right;
    }

    /**
     * 设置输入法候选框位置（便捷方法）
     *
     * @param screenX 屏幕坐标 X
     * @param screenY 屏幕坐标 Y
     */
    static void setCompositionWindowPosition(int screenX, int screenY) {
        if (!Platform.isWindows()) {
            return;
        }

        WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();
        if (hwnd == null) {
            return;
        }

        Pointer hIMC = INSTANCE.ImmGetContext(hwnd);
        if (hIMC == null) {
            return;
        }

        try {
            COMPOSITIONFORM form = new COMPOSITIONFORM();
            form.dwStyle = CFS_CANDIDATEPOS;
            form.ptCurrentPos.x = screenX;
            form.ptCurrentPos.y = screenY;
            INSTANCE.ImmSetCompositionWindow(hIMC, form);
        } finally {
            INSTANCE.ImmReleaseContext(hwnd, hIMC);
        }
    }
}