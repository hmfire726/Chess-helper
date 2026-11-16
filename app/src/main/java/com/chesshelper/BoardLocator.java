package com.chesshelper;

/**
 * 화면에서 체스판(8×8 체크무늬)의 위치를 자동으로 찾는다.
 *
 * <p>후보 정사각형마다 64칸의 "모서리 근처 색"을 읽어서, 밝은 칸끼리·어두운 칸끼리는 색이 비슷하고
 * 두 무리의 색 차이는 큰지를 점수로 매긴다. 작게 줄인 화면에서 넓게 찾은 뒤, 원래 크기에서 좁게 다듬는다.
 */
public final class BoardLocator {
    private BoardLocator() {
    }

    /** 판을 찾으면 {left, top, right, bottom} (화면 픽셀), 못 찾으면 null. */
    public static float[] locate(BoardDetector.Pixels px) {
        int W = px.width(), H = px.height();
        if (W < 64 || H < 64) return null;
        int f = Math.max(1, Math.min(W, H) / 100);
        int w = W / f, h = H / f;
        int[] small = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) small[y * w + x] = px.rgb(x * f + f / 2, y * f + f / 2);
        }
        Img img = new Img(small, w, h);

        // 1단계: 줄인 화면에서 모든 위치·크기
        int minS = Math.min(w, h) * 2 / 5;
        float bestScore = Float.NEGATIVE_INFINITY;
        int bx = 0, by = 0, bs = 0;
        for (int s = Math.min(w, h); s >= minS; s--) {
            for (int y0 = 0; y0 + s <= h; y0++) {
                for (int x0 = 0; x0 + s <= w; x0++) {
                    float sc = score(img, x0, y0, s, 0.15f);
                    if (sc > bestScore) {
                        bestScore = sc;
                        bx = x0;
                        by = y0;
                        bs = s;
                    }
                }
            }
        }
        if (bestScore < 15) return null;

        // 2단계: 원래 크기에서 주변을 촘촘히
        Img full = new Img(px);
        float X = bx * f, Y = by * f, S = bs * f;
        float[] r = refine(full, X, Y, S, f + 1, 1, 0.06f);
        r = refine(full, r[0], r[1], r[2], 3, 1, 0.025f);
        if (score(full, r[0], r[1], r[2], 0.06f) < 15) return null;
        return new float[]{r[0], r[1], r[0] + r[2], r[1] + r[2]};
    }

    private static float[] refine(Img img, float X, float Y, float S, int range, int step, float inset) {
        float best = Float.NEGATIVE_INFINITY;
        float rx = X, ry = Y, rs = S;
        for (int ds = -range; ds <= range; ds += step) {
            float s = S + ds;
            if (s < 16) continue;
            for (int dy = -range; dy <= range; dy += step) {
                float y0 = Y + dy;
                if (y0 < 0 || y0 + s > img.h) continue;
                for (int dx = -range; dx <= range; dx += step) {
                    float x0 = X + dx;
                    if (x0 < 0 || x0 + s > img.w) continue;
                    float sc = score(img, x0, y0, s, inset);
                    if (sc > best) {
                        best = sc;
                        rx = x0;
                        ry = y0;
                        rs = s;
                    }
                }
            }
        }
        return new float[]{rx, ry, rs};
    }

    /** 체크무늬 점수: (두 색 무리의 차이) − 2.5 × (무리 안의 흩어짐). */
    static float score(Img img, float x0, float y0, float size, float inset) {
        float sq = size / 8f;
        int[] cr = new int[64], cg = new int[64], cb = new int[64];
        long[] sum = new long[6];
        float a = inset * sq, z = (1 - inset) * sq;
        for (int r = 0; r < 8; r++) {
            float ty = y0 + r * sq;
            for (int c = 0; c < 8; c++) {
                float tx = x0 + c * sq;
                int c1 = img.at(tx + a, ty + a), c2 = img.at(tx + z, ty + a);
                int c3 = img.at(tx + a, ty + z), c4 = img.at(tx + z, ty + z);
                int i = r * 8 + c;
                cr[i] = (((c1 >> 16) & 255) + ((c2 >> 16) & 255) + ((c3 >> 16) & 255) + ((c4 >> 16) & 255)) >> 2;
                cg[i] = (((c1 >> 8) & 255) + ((c2 >> 8) & 255) + ((c3 >> 8) & 255) + ((c4 >> 8) & 255)) >> 2;
                cb[i] = ((c1 & 255) + (c2 & 255) + (c3 & 255) + (c4 & 255)) >> 2;
                int k = ((r + c) & 1) * 3;
                sum[k] += cr[i];
                sum[k + 1] += cg[i];
                sum[k + 2] += cb[i];
            }
        }
        float lr = sum[0] / 32f, lg = sum[1] / 32f, lb = sum[2] / 32f;
        float dr = sum[3] / 32f, dg = sum[4] / 32f, db = sum[5] / 32f;
        float sep = Math.abs(lr - dr) + Math.abs(lg - dg) + Math.abs(lb - db);
        if (sep < 25) return Float.NEGATIVE_INFINITY;
        float dev = 0;
        for (int i = 0; i < 64; i++) {
            boolean light = (((i >> 3) + (i & 7)) & 1) == 0;
            dev += light
                    ? Math.abs(cr[i] - lr) + Math.abs(cg[i] - lg) + Math.abs(cb[i] - lb)
                    : Math.abs(cr[i] - dr) + Math.abs(cg[i] - dg) + Math.abs(cb[i] - db);
        }
        dev /= 64f;
        return sep - 2.5f * dev;
    }

    static final class Img {
        final int[] data;
        final BoardDetector.Pixels px;
        final int w, h;

        Img(int[] data, int w, int h) {
            this.data = data;
            this.px = null;
            this.w = w;
            this.h = h;
        }

        Img(BoardDetector.Pixels px) {
            this.data = null;
            this.px = px;
            this.w = px.width();
            this.h = px.height();
        }

        int at(float x, float y) {
            int ix = Math.max(0, Math.min(w - 1, (int) x));
            int iy = Math.max(0, Math.min(h - 1, (int) y));
            return data != null ? data[iy * w + ix] : px.rgb(ix, iy);
        }
    }
}
