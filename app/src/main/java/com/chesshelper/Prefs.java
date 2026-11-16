package com.chesshelper;

import android.content.Context;
import android.content.SharedPreferences;

/** 설정과 판 프리셋 저장. */
final class Prefs {
    static final int ME_AUTO = 0, ME_WHITE = 1, ME_BLACK = 2;
    static final int ORIENT_AUTO = 0, ORIENT_WHITE_BOTTOM = 1, ORIENT_BLACK_BOTTOM = 2;
    static final int[] THINK_MS = {500, 1500, 3000};
    static final int[] MULTI_PV = {1, 3, 5};
    static final int PRESETS = 3;

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("chess_helper", Context.MODE_PRIVATE);
    }

    int thinkMs() { return sp.getInt("think_ms", 1500); }

    void setThinkMs(int v) { sp.edit().putInt("think_ms", v).apply(); }

    int multiPv() { return sp.getInt("multi_pv", 3); }

    void setMultiPv(int v) { sp.edit().putInt("multi_pv", v).apply(); }

    int me() { return sp.getInt("me", ME_AUTO); }

    void setMe(int v) { sp.edit().putInt("me", v).apply(); }

    int orient() { return sp.getInt("orient", ORIENT_AUTO); }

    void setOrient(int v) { sp.edit().putInt("orient", v).apply(); }

    int markSize() { return sp.getInt("mark_size", 1); }

    void setMarkSize(int v) { sp.edit().putInt("mark_size", v).apply(); }

    int markTheme() { return sp.getInt("mark_theme", 0); }

    void setMarkTheme(int v) { sp.edit().putInt("mark_theme", v).apply(); }

    int panelTheme() { return sp.getInt("panel_theme", 0); }

    void setPanelTheme(int v) { sp.edit().putInt("panel_theme", v).apply(); }

    int panelX() { return sp.getInt("panel_x", -1); }

    int panelY() { return sp.getInt("panel_y", -1); }

    void setPanelPos(int x, int y) { sp.edit().putInt("panel_x", x).putInt("panel_y", y).apply(); }

    /** 판 위치 {left, top, right, bottom} (화면 픽셀). 없으면 null. */
    float[] rect() { return parseRect(sp.getString("rect", null)); }

    void setRect(float[] r) { sp.edit().putString("rect", rectString(r)).apply(); }

    String model() { return sp.getString("model", null); }

    void setModel(String m) { sp.edit().putString("model", m).apply(); }

    // ---------------------------------------------------------------- 프리셋

    boolean hasPreset(int i) { return sp.contains("preset_rect_" + i); }

    void savePreset(int i, float[] rect, String model) {
        sp.edit().putString("preset_rect_" + i, rectString(rect)).putString("preset_model_" + i, model).apply();
    }

    /** 프리셋을 현재 판 위치·학습으로 불러온다. 성공하면 true. */
    boolean loadPreset(int i) {
        float[] r = parseRect(sp.getString("preset_rect_" + i, null));
        if (r == null) return false;
        sp.edit().putString("rect", rectString(r)).putString("model", sp.getString("preset_model_" + i, null)).apply();
        return true;
    }

    private static String rectString(float[] r) {
        return r == null ? null : r[0] + "," + r[1] + "," + r[2] + "," + r[3];
    }

    private static float[] parseRect(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 4) return null;
        try {
            return new float[]{Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
