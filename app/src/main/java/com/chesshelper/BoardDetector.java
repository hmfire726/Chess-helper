package com.chesshelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * 화면 이미지에서 체스 기물을 읽는다.
 *
 * <p>오목과 달리 체스는 "무슨 기물인지"까지 알아야 하므로, 게임의 <b>시작 배치</b>를 한 번 보여 주면
 * 각 칸의 모양을 기억(학습)해 두고, 이후에는 각 칸을 기억한 모양들과 비교해서 가장 닮은 기물로 판단한다.
 * 기물 그림은 앱·테마마다 다르지만 같은 앱 안에서는 늘 같으므로 이 방법이 잘 맞는다.
 *
 * <p>칸 하나의 특징(feature): 칸 안쪽(10%~90%)을 16×16 격자로 나눠, 각 격자의 평균 밝기와
 * "기물 픽셀인가"를 저장한다. 기물 픽셀 = 그 칸의 바탕색과도, 판의 밝은 칸·어두운 칸 색과도 다른 픽셀.
 * 이렇게 정하면 같은 기물은 밝은 칸 위든 어두운 칸 위든, 마지막 수 강조색 위든 같은 모양으로 읽힌다.
 * 추천 표시(테두리)는 칸 바깥 6% 안에만 그리므로 특징을 읽는 영역과 겹치지 않는다.
 */
public final class BoardDetector {
    private BoardDetector() {
    }

    /** 화면 픽셀 읽기. rgb()는 0xRRGGBB. */
    public interface Pixels {
        int width();

        int height();

        int rgb(int x, int y);
    }

    public static final int G = 16;
    public static final int CELLS = G * G;
    static final float IN0 = 0.10f, IN1 = 0.90f;
    /** 추천 테두리는 칸 크기의 이 비율 안쪽(바깥 가장자리)에만 그려야 인식에 영향이 없다. */
    public static final float MARK_BAND = 0.06f;
    static final int FG_THRESHOLD = 40;
    static final int UNKNOWN = 99;

    private static final int MISMATCH = 80;
    private static final int NEIGHBOR_PENALTY = 40;
    /** 칸당 평균 비용이 이보다 크면 "모르는 것"으로 본다. */
    private static final int UNKNOWN_PER_CELL = 250;

    // ---------------------------------------------------------------- 특징 추출

    /** 화면에서 읽은 판 한 장. 순서는 화면 기준(위 줄 왼쪽부터). */
    public static final class Frame {
        /** 칸별 특징. 값: 밝기(짝수 0~254) | 기물 픽셀이면 1. */
        public final byte[][] feats;
        /** 칸별 바탕색 0xRRGGBB. */
        public final int[] bg;
        /** 화면에서 (줄+칸)이 짝수인 칸 / 홀수인 칸의 대표 색. */
        public final int[] squareColor;

        Frame(byte[][] feats, int[] bg, int[] squareColor) {
            this.feats = feats;
            this.bg = bg;
            this.squareColor = squareColor;
        }
    }

    public static Frame read(Pixels px, float left, float top, float right, float bottom) {
        float sw = (right - left) / 8f, sh = (bottom - top) / 8f;
        int[] bgs = new int[64];
        for (int i = 0; i < 64; i++) {
            bgs[i] = background(px, left + (i & 7) * sw, top + (i >> 3) * sh, sw, sh);
        }
        int[] squareColor = parityColors(bgs);
        byte[][] feats = new byte[64][];
        for (int i = 0; i < 64; i++) {
            feats[i] = squareFeature(px, left + (i & 7) * sw, top + (i >> 3) * sh, sw, sh, bgs[i], squareColor);
        }
        return new Frame(feats, bgs, squareColor);
    }

    private static int parity(int screenIndex) {
        return ((screenIndex >> 3) + (screenIndex & 7)) & 1;
    }

    /** 짝수 칸·홀수 칸 바탕색의 중앙값 (강조된 칸 몇 개는 무시된다). */
    static int[] parityColors(int[] bgs) {
        int[][] byParity = new int[2][32];
        int[] cnt = new int[2];
        for (int i = 0; i < 64; i++) {
            int p = parity(i);
            byParity[p][cnt[p]++] = bgs[i];
        }
        return new int[]{medianColor(byParity[0]), medianColor(byParity[1])};
    }

    /** 격자 한 칸마다 3×3 점을 본다. 하나라도 기물 픽셀이면 그 격자는 기물. */
    static byte[] squareFeature(Pixels px, float x0, float y0, float sw, float sh, int bg, int[] squareColor) {
        int c0 = squareColor[0], c1 = squareColor[1];
        byte[] f = new byte[CELLS];
        float cw = (IN1 - IN0) * sw / G, ch = (IN1 - IN0) * sh / G;
        float ox = x0 + IN0 * sw, oy = y0 + IN0 * sh;
        for (int gy = 0; gy < G; gy++) {
            for (int gx = 0; gx < G; gx++) {
                float cx = ox + (gx + 0.5f) * cw, cy = oy + (gy + 0.5f) * ch;
                int pieceN = 0, pieceLum = 0, allLum = 0;
                for (int ky = -1; ky <= 1; ky++) {
                    for (int kx = -1; kx <= 1; kx++) {
                        int c = sample(px, cx + kx * cw / 3f, cy + ky * ch / 3f);
                        int l = lum(c);
                        allLum += l;
                        if (colorDist(c, bg) > FG_THRESHOLD && colorDist(c, c0) > FG_THRESHOLD
                                && colorDist(c, c1) > FG_THRESHOLD) {
                            pieceN++;
                            pieceLum += l;
                        }
                    }
                }
                // 기물 격자의 밝기는 기물 픽셀만으로 → 바탕 칸 색이 섞이지 않는다
                int l = pieceN > 0 ? pieceLum / pieceN : allLum / 9;
                f[gy * G + gx] = (byte) ((Math.min(254, l) & 0xFE) | (pieceN > 0 ? 1 : 0));
            }
        }
        return f;
    }

    private static final float[][] BG_POINTS;

    static {
        List<float[]> pts = new ArrayList<>();
        for (int i = 0; i < 8; i++) pts.add(new float[]{0.13f + i * (0.74f / 7f), 0.13f});
        for (int i = 0; i < 4; i++) {
            float y = 0.22f + i * 0.09f;
            pts.add(new float[]{0.13f, y});
            pts.add(new float[]{0.87f, y});
        }
        BG_POINTS = pts.toArray(new float[0][]);
    }

    /** 칸 바탕색: 위쪽·양옆 위쪽 가장자리 16점의 채널별 중앙값. 기물은 보통 칸 아래·가운데에 있다. */
    static int background(Pixels px, float x0, float y0, float sw, float sh) {
        int n = BG_POINTS.length;
        int[] cs = new int[n];
        for (int i = 0; i < n; i++) cs[i] = sample(px, x0 + BG_POINTS[i][0] * sw, y0 + BG_POINTS[i][1] * sh);
        return medianColor(cs);
    }

    static int sample(Pixels px, float x, float y) {
        int ix = Math.max(0, Math.min(px.width() - 1, (int) x));
        int iy = Math.max(0, Math.min(px.height() - 1, (int) y));
        return px.rgb(ix, iy);
    }

    static int lum(int c) {
        return (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000;
    }

    static int colorDist(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                + Math.abs((a & 255) - (b & 255));
    }

    private static int medianColor(int[] colors) {
        int n = colors.length;
        int[] r = new int[n], g = new int[n], b = new int[n];
        for (int i = 0; i < n; i++) {
            r[i] = (colors[i] >> 16) & 255;
            g[i] = (colors[i] >> 8) & 255;
            b[i] = colors[i] & 255;
        }
        Arrays.sort(r);
        Arrays.sort(g);
        Arrays.sort(b);
        int h = n / 2;
        return (((r[h - 1] + r[h]) / 2) << 16) | (((g[h - 1] + g[h]) / 2) << 8) | ((b[h - 1] + b[h]) / 2);
    }

    static int fgCount(byte[] f) {
        int n = 0;
        for (byte v : f) n += v & 1;
        return n;
    }

    // ---------------------------------------------------------------- 학습 (시작 배치)

    /** 학습 결과: 칸 모양 템플릿들과 그 기물 코드. */
    public static final class Model {
        public final int[] codes;
        public final byte[][] feats;
        /** 학습할 때 백이 화면 아래쪽에 있었는가. */
        public final boolean whiteBottom;
        /** 학습할 때의 짝수 칸 / 홀수 칸 색. */
        final int[] squareColor;

        Model(int[] codes, byte[][] feats, boolean whiteBottom, int[] squareColor) {
            this.codes = codes;
            this.feats = feats;
            this.whiteBottom = whiteBottom;
            this.squareColor = squareColor;
        }

        public String serialize() {
            StringBuilder sb = new StringBuilder("CH2|").append(whiteBottom ? 1 : 0).append('|')
                    .append(squareColor[0]).append(',').append(squareColor[1]).append('|');
            byte[] all = new byte[codes.length * CELLS];
            for (int i = 0; i < codes.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(codes[i]);
                System.arraycopy(feats[i], 0, all, i * CELLS, CELLS);
            }
            sb.append('|').append(Base64.getEncoder().encodeToString(all));
            return sb.toString();
        }

        /** 저장된 문자열에서 복원. 형식이 틀리면 null. */
        public static Model parse(String s) {
            if (s == null || !s.startsWith("CH2|")) return null;
            try {
                String[] p = s.split("\\|");
                boolean wb = p[1].equals("1");
                String[] sc = p[2].split(",");
                int[] colors = {Integer.parseInt(sc[0]), Integer.parseInt(sc[1])};
                String[] cs = p[3].split(",");
                byte[] all = Base64.getDecoder().decode(p[4]);
                if (all.length != cs.length * CELLS) return null;
                int[] codes = new int[cs.length];
                byte[][] feats = new byte[cs.length][];
                for (int i = 0; i < cs.length; i++) {
                    codes[i] = Integer.parseInt(cs[i]);
                    feats[i] = Arrays.copyOfRange(all, i * CELLS, (i + 1) * CELLS);
                }
                return new Model(codes, feats, wb, colors);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * 시작 배치 화면으로 학습한다.
     *
     * @return 시작 배치처럼 보이지 않으면 null
     */
    public static Model learn(Frame frame) {
        byte[][] feats = frame.feats;
        long topLum = 0, botLum = 0;
        for (int i = 0; i < 64; i++) {
            int row = i >> 3;
            double ratio = fgCount(feats[i]) / (double) CELLS;
            boolean occupied = row <= 1 || row >= 6;
            if (occupied && ratio < 0.05) return null;
            if (!occupied && ratio > 0.04) return null;
            if (!occupied) continue;
            // 흰 기물은 속이 밝고 검은 기물은 속이 어둡다 (양쪽 모두 밝은 칸 8개, 어두운 칸 8개 위에 있음)
            long sum = 0;
            for (byte v : feats[i]) sum += v & 0xFE;
            if (row <= 1) topLum += sum;
            else botLum += sum;
        }
        boolean whiteBottom = botLum > topLum;
        int[] start = Board.startPlacement();
        int[] codes = new int[64];
        byte[][] tf = new byte[64][];
        for (int i = 0; i < 64; i++) {
            codes[i] = start[screenToSquare(i, whiteBottom)];
            tf[i] = feats[i].clone();
        }
        return new Model(codes, tf, whiteBottom, frame.squareColor.clone());
    }

    /**
     * 판이 가려졌는지: 칸 바탕색이 자기 색보다 반대 색에 더 가까운 칸이 많으면 팝업이나 어두운 막이 덮은 것.
     * (마지막 수 강조 2칸, 체크 1칸 정도는 그럴 수 있으므로 4칸까지는 허용)
     */
    public static boolean looksCovered(Frame frame, Model m) {
        int bad = 0;
        for (int i = 0; i < 64; i++) {
            int p = parity(i);
            if (colorDist(frame.bg[i], m.squareColor[p]) > colorDist(frame.bg[i], m.squareColor[1 - p])) bad++;
        }
        return bad > 4;
    }

    // ---------------------------------------------------------------- 판별

    /** @return 화면 순서의 기물 코드 64개. 판이 가려졌거나 하나라도 알아볼 수 없으면 null. */
    public static int[] classify(Frame frame, Model model) {
        if (looksCovered(frame, model)) return null;
        int[] out = new int[64];
        for (int i = 0; i < 64; i++) {
            int c = classifyOne(frame.feats[i], model);
            if (c == UNKNOWN) return null;
            out[i] = c;
        }
        return out;
    }

    static int classifyOne(byte[] f, Model m) {
        // 기본값: 아무것도 없는 칸 (기물 픽셀 수만큼 비용)
        long best = (long) fgCount(f) * (MISMATCH + 200);
        int bestCode = 0;
        if (best == 0) return 0;
        // 1단계: 모든 템플릿과 그대로 비교, 가까운 후보 몇 개만 남김
        int n = m.codes.length;
        long[] d = new long[n];
        int[] top = new int[TOP_K];
        int topN = 0;
        int worst = 0;
        for (int t = 0; t < n; t++) {
            long limit = topN < TOP_K ? Long.MAX_VALUE : d[top[worst]];
            d[t] = distance(f, m.feats[t], 0, 0, limit);
            if (topN < TOP_K) {
                top[topN++] = t;
            } else if (d[t] < d[top[worst]]) {
                top[worst] = t;
            } else {
                continue;
            }
            worst = 0;
            for (int k = 1; k < topN; k++) if (d[top[k]] > d[top[worst]]) worst = k;
        }
        // 2단계: 후보들은 템플릿을 ±1칸 통째로 밀어서도 비교 (판 위치가 조금 어긋난 경우)
        for (int k = 0; k < topN; k++) {
            int t = top[k];
            long dt = d[t];
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    long lim = dt - SHIFT_PENALTY;
                    if (lim <= 0) continue;
                    long ds = distance(f, m.feats[t], dx, dy, lim) + SHIFT_PENALTY;
                    if (ds < dt) dt = ds;
                }
            }
            if (dt < best) {
                best = dt;
                bestCode = m.codes[t];
            }
        }
        int inner = (G - 2) * (G - 2);
        if (bestCode != 0 && best > (long) inner * UNKNOWN_PER_CELL) return UNKNOWN;
        return bestCode;
    }

    private static final int TOP_K = 5;
    private static final int SHIFT_PENALTY = 150;

    /**
     * 두 칸 모양의 차이 (작을수록 닮음). 템플릿은 (dx, dy) 격자만큼 밀어서 비교한다.
     * 기물 픽셀마다 상대편의 같은 자리 ±1칸 안에서 가장 잘 맞는 기물 픽셀을 찾는다(챔퍼 매칭).
     * 그래서 외곽선만 보이는 얇은 모양도 판 위치가 조금 어긋나도 맞춰진다. limit을 넘으면 중간에 그만둔다.
     */
    static long distance(byte[] a, byte[] t, int dx, int dy, long limit) {
        long cost = 0;
        for (int y = 1; y < G - 1; y++) {
            for (int x = 1; x < G - 1; x++) {
                int av = a[y * G + x];
                int tv = cell(t, x + dx, y + dy, av);
                if ((av & 1) != 0) cost += nearest(av, t, x + dx, y + dy, tv);
                if ((tv & 1) != 0) cost += nearest(tv, a, x, y, av);
            }
            if (cost >= limit) return cost;
        }
        return cost;
    }

    /** 범위 밖이면 "바탕, 밝기는 fallback과 같음"으로 본다. */
    private static int cell(byte[] f, int x, int y, int fallback) {
        if (x < 0 || y < 0 || x >= G || y >= G) return fallback & 0xFE;
        return f[y * G + x];
    }

    private static int nearest(int v, byte[] other, int x, int y, int same) {
        int lum = v & 0xFE;
        int best = Integer.MAX_VALUE;
        for (int ky = -1; ky <= 1; ky++) {
            int yy = y + ky;
            if (yy < 0 || yy >= G) continue;
            for (int kx = -1; kx <= 1; kx++) {
                int xx = x + kx;
                if (xx < 0 || xx >= G) continue;
                int o = other[yy * G + xx];
                if ((o & 1) == 0) continue;
                int c = Math.abs(lum - (o & 0xFE)) * 3 / 2 + (kx == 0 && ky == 0 ? 0 : NEIGHBOR_PENALTY);
                if (c < best) best = c;
            }
        }
        // 주변에 기물 픽셀이 없으면 같은 자리의 바탕과 비교
        if (best == Integer.MAX_VALUE) best = Math.abs(lum - (same & 0xFE)) * 3 / 2 + MISMATCH;
        return best;
    }

    // ---------------------------------------------------------------- 화면 ↔ 판 좌표

    /** 화면 칸(위 줄부터 0~63) → 판 칸(a1=0). 흑이 아래면 판이 180도 돌아가 있다. */
    public static int screenToSquare(int screenIndex, boolean whiteBottom) {
        int row = screenIndex >> 3, col = screenIndex & 7;
        return whiteBottom ? (7 - row) * 8 + col : row * 8 + (7 - col);
    }

    /** 판 칸 → 화면 칸 (screenToSquare의 역). */
    public static int squareToScreen(int square, boolean whiteBottom) {
        int rank = square >> 3, file = square & 7;
        return whiteBottom ? (7 - rank) * 8 + file : rank * 8 + (7 - file);
    }

    public static int[] toBoard(int[] screen, boolean whiteBottom) {
        int[] b = new int[64];
        for (int i = 0; i < 64; i++) b[screenToSquare(i, whiteBottom)] = screen[i];
        return b;
    }

    /**
     * 백이 아래쪽인 정도. 양수면 백이 아래, 음수면 흑이 아래일 가능성이 높다.
     * (폰과 킹은 대부분 자기 진영 쪽에 있다는 점을 이용)
     */
    public static int orientationScore(int[] screen) {
        int score = 0;
        for (int i = 0; i < 64; i++) {
            int v = 2 * (i >> 3) - 7; // 위 -7 … 아래 +7
            switch (screen[i]) {
                case Board.PAWN: score += v; break;
                case -Board.PAWN: score -= v; break;
                case Board.KING: score += 2 * v; break;
                case -Board.KING: score -= 2 * v; break;
                default: break;
            }
        }
        return score;
    }
}
