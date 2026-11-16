package com.chesshelper;

/** 추천 표시 색, 패널 색, 표시 크기. */
final class Themes {
    private Themes() {
    }

    static final String[] MARK_NAMES = {"기본", "네온", "파스텔", "고대비"};
    /** 1순위, 2순위, 3순위 이하 색. */
    static final int[][] MARK = {
            {0xFFE53935, 0xFFFB8C00, 0xFF1E88E5},
            {0xFFFF1744, 0xFF00E676, 0xFF00B0FF},
            {0xFFFF8A80, 0xFFFFD180, 0xFF82B1FF},
            {0xFFFF00FF, 0xFFFFEA00, 0xFF00E5FF},
    };

    static final String[] PANEL_NAMES = {"다크", "라이트", "네이비", "반투명"};
    /** {배경, 글자, 흐린 글자, 버튼 배경, 선택된 버튼 배경} */
    static final int[][] PANEL = {
            {0xF0202124, 0xFFFFFFFF, 0xFFB0B0B0, 0xFF3C4043, 0xFF1E88E5},
            {0xF5FFFFFF, 0xFF202124, 0xFF5F6368, 0xFFE8EAED, 0xFF1A73E8},
            {0xF00D1B2A, 0xFFE0F2F1, 0xFF90A4AE, 0xFF1B263B, 0xFF26A69A},
            {0x99000000, 0xFFFFFFFF, 0xFFDDDDDD, 0x66FFFFFF, 0xCC1E88E5},
    };

    static final String[] SIZE_NAMES = {"작게", "보통", "크게"};
    /** 추천 테두리 굵기 (칸 크기 대비). 모두 BoardDetector.MARK_BAND(6%) 안에 들어가야 한다. */
    static final float[] STROKE = {0.025f, 0.035f, 0.045f};
    static final float[] TEXT_SP = {11f, 13f, 15f};

    static int markColor(int theme, int rank) {
        int[] c = MARK[Math.floorMod(theme, MARK.length)];
        return c[Math.min(rank, c.length - 1)];
    }

    static int[] panel(int theme) {
        return PANEL[Math.floorMod(theme, PANEL.length)];
    }
}
