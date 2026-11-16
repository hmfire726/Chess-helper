package com.chesshelper;

/**
 * 테스트용 가짜 게임 화면. 체스판과 기물(도형으로 그린 그림)을 픽셀 배열에 그린다.
 */
final class FakeScreen implements BoardDetector.Pixels {
    final int w, h;
    final int[] px;

    // 실제 앱과 비슷한 색: 초록 테마 판, 흰 기물은 밝은 바탕칸과 색이 매우 비슷함(어려운 경우)
    static final int LIGHT = rgb(238, 238, 210);
    static final int DARK = rgb(118, 150, 86);
    static final int HIGHLIGHT_LIGHT = rgb(246, 246, 130);
    static final int HIGHLIGHT_DARK = rgb(186, 202, 68);
    static final int WHITE_FILL = rgb(240, 240, 235);
    static final int BLACK_FILL = rgb(60, 58, 55);
    static final int OUTLINE = rgb(25, 25, 25);

    FakeScreen(int w, int h, int background) {
        this.w = w;
        this.h = h;
        px = new int[w * h];
        java.util.Arrays.fill(px, background);
    }

    static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    @Override
    public int width() {
        return w;
    }

    @Override
    public int height() {
        return h;
    }

    @Override
    public int rgb(int x, int y) {
        return px[y * w + x];
    }

    void fill(int x0, int y0, int x1, int y1, int color) {
        for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) px[y * w + x] = color;
        }
    }

    /** 판 그리기. placement는 a1=0 형식, highlight는 강조할 판 칸들. */
    void drawBoard(int left, int top, int size, int[] placement, boolean whiteBottom, int... highlight) {
        float sq = size / 8f;
        for (int i = 0; i < 64; i++) {
            int row = i >> 3, col = i & 7;
            int square = BoardDetector.screenToSquare(i, whiteBottom);
            boolean light = ((row + col) & 1) == 0;
            boolean hl = false;
            for (int s : highlight) hl |= s == square;
            int color = hl ? (light ? HIGHLIGHT_LIGHT : HIGHLIGHT_DARK) : (light ? LIGHT : DARK);
            int x0 = Math.round(left + col * sq), y0 = Math.round(top + row * sq);
            int x1 = Math.round(left + (col + 1) * sq), y1 = Math.round(top + (row + 1) * sq);
            fill(x0, y0, x1, y1, color);
            // 판 가장자리 좌표 글자 흉내 (작은 점)
            if (col == 0) fill(x0 + 3, y0 + 3, x0 + 9, y0 + 12, light ? DARK : LIGHT);
            int p = placement[square];
            if (p != 0) drawPiece(p, x0, y0, x1 - x0);
        }
    }

    /** 추천 테두리 흉내: 칸 바깥 1%~5% 띠에 빨간 선. */
    void drawMarkFrame(int left, int top, int size, int screenIndex) {
        float sq = size / 8f;
        int row = screenIndex >> 3, col = screenIndex & 7;
        int x0 = Math.round(left + col * sq), y0 = Math.round(top + row * sq);
        int s = Math.round(sq);
        int a = Math.round(s * 0.01f), b = Math.round(s * 0.05f);
        int red = rgb(229, 57, 53);
        fill(x0 + a, y0 + a, x0 + s - a, y0 + b, red);
        fill(x0 + a, y0 + s - b, x0 + s - a, y0 + s - a, red);
        fill(x0 + a, y0 + a, x0 + b, y0 + s - a, red);
        fill(x0 + s - b, y0 + a, x0 + s - a, y0 + s - a, red);
    }

    void drawPiece(int piece, int x0, int y0, int size) {
        int type = Math.abs(piece);
        int fillColor = piece > 0 ? WHITE_FILL : BLACK_FILL;
        int outline = Math.max(2, size / 40);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!inside(type, x, y, size)) continue;
                boolean edge = false;
                for (int k = 0; k < 4 && !edge; k++) {
                    int nx = x + (k == 0 ? outline : k == 1 ? -outline : 0);
                    int ny = y + (k == 2 ? outline : k == 3 ? -outline : 0);
                    edge = !inside(type, nx, ny, size);
                }
                int xx = x0 + x, yy = y0 + y;
                if (xx >= 0 && xx < w && yy >= 0 && yy < h) px[yy * w + xx] = edge ? OUTLINE : fillColor;
            }
        }
    }

    /** 기물 모양 (칸 좌표를 0~1로 바꿔서 판단). */
    static boolean inside(int type, int x, int y, int size) {
        if (x < 0 || y < 0 || x >= size || y >= size) return false;
        double u = (x + 0.5) / size, v = (y + 0.5) / size;
        boolean base = v >= 0.78 && v <= 0.88 && Math.abs(u - 0.5) <= 0.32;
        switch (type) {
            case Board.PAWN:
                return circle(u, v, 0.5, 0.36, 0.12)
                        || (v >= 0.46 && v <= 0.78 && Math.abs(u - 0.5) <= 0.10 + (v - 0.46) * 0.5)
                        || base;
            case Board.KNIGHT:
                return (u >= 0.42 && u <= 0.72 && v >= 0.28 && v <= 0.78)
                        || (u >= 0.20 && u <= 0.60 && v >= 0.30 && v <= 0.48)
                        || (u >= 0.52 && u <= 0.64 && v >= 0.16 && v <= 0.30)
                        || base;
            case Board.BISHOP: {
                boolean body = ellipse(u, v, 0.5, 0.50, 0.17, 0.24) && Math.abs((u - 0.5) + (v - 0.45)) > 0.03;
                return body || circle(u, v, 0.5, 0.20, 0.06) || base;
            }
            case Board.ROOK:
                return (u >= 0.30 && u <= 0.70 && v >= 0.30 && v <= 0.78)
                        || (v >= 0.16 && v <= 0.30 && ((u >= 0.26 && u <= 0.38) || (u >= 0.44 && u <= 0.56) || (u >= 0.62 && u <= 0.74)))
                        || base;
            case Board.QUEEN: {
                boolean body = v >= 0.30 && v <= 0.78 && Math.abs(u - 0.5) <= 0.10 + (v - 0.30) * 0.45;
                boolean crown = false;
                for (int i = 0; i < 5; i++) crown |= circle(u, v, 0.22 + i * 0.14, 0.24, 0.055);
                return body || crown || base;
            }
            case Board.KING: {
                boolean body = v >= 0.34 && v <= 0.78 && Math.abs(u - 0.5) <= 0.20 + (v - 0.34) * 0.10;
                boolean cross = (Math.abs(u - 0.5) <= 0.045 && v >= 0.14 && v <= 0.34)
                        || (Math.abs(u - 0.5) <= 0.13 && v >= 0.19 && v <= 0.26);
                return body || cross || base;
            }
            default:
                return false;
        }
    }

    private static boolean circle(double u, double v, double cx, double cy, double r) {
        return (u - cx) * (u - cx) + (v - cy) * (v - cy) <= r * r;
    }

    private static boolean ellipse(double u, double v, double cx, double cy, double rx, double ry) {
        double a = (u - cx) / rx, b = (v - cy) / ry;
        return a * a + b * b <= 1;
    }
}
