package com.chesshelper;

import java.util.Arrays;

/**
 * 체스 판 상태, 수 생성, 두기/무르기.
 *
 * <p>칸 번호는 a1 = 0, b1 = 1, … h1 = 7, a2 = 8, … h8 = 63.
 * 기물은 부호 있는 정수: 백은 +1(폰)…+6(킹), 흑은 -1…-6, 빈칸은 0.
 *
 * <p>수(move)는 int 하나에 담는다:
 * 출발 칸 6비트 | 도착 칸 6비트 | 승진 기물 3비트 | 특수 플래그 3비트.
 */
public final class Board {
    public static final int WHITE = 0, BLACK = 1;
    public static final int PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;

    public static final int CASTLE_WK = 1, CASTLE_WQ = 2, CASTLE_BK = 4, CASTLE_BQ = 8;

    public static final int FLAG_NONE = 0, FLAG_DOUBLE = 1, FLAG_EP = 2, FLAG_CASTLE = 3;

    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    /** 시작 배치의 첫 줄 (a~h 파일). */
    static final int[] BACK_RANK = {ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK};

    // ---------------------------------------------------------------- 수 인코딩

    public static int move(int from, int to) {
        return from | (to << 6);
    }

    public static int move(int from, int to, int promo, int flag) {
        return from | (to << 6) | (promo << 12) | (flag << 15);
    }

    public static int from(int m) { return m & 63; }

    public static int to(int m) { return (m >>> 6) & 63; }

    public static int promo(int m) { return (m >>> 12) & 7; }

    public static int flag(int m) { return (m >>> 15) & 7; }

    // ---------------------------------------------------------------- 미리 계산한 표

    static final int[][] KNIGHT_T = new int[64][];
    static final int[][] KING_T = new int[64][];
    /** RAY[칸][방향] = 그 방향으로 차례대로 만나는 칸들. 방향 0~3은 직선(룩), 4~7은 대각선(비숍). */
    static final int[][][] RAY = new int[64][8][];
    private static final int[] DIR_DF = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final int[] DIR_DR = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] CASTLE_MASK = new int[64];

    private static final long[][] Z_PIECE = new long[13][64];
    private static final long[] Z_CASTLE = new long[16];
    private static final long[] Z_EP = new long[64];
    private static final long Z_SIDE;

    static {
        int[][] kn = {{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}};
        int[][] kg = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
        for (int s = 0; s < 64; s++) {
            int f = s & 7, r = s >> 3;
            KNIGHT_T[s] = targets(f, r, kn);
            KING_T[s] = targets(f, r, kg);
            for (int d = 0; d < 8; d++) {
                int[] tmp = new int[8];
                int n = 0;
                int ff = f + DIR_DF[d], rr = r + DIR_DR[d];
                while (ff >= 0 && ff < 8 && rr >= 0 && rr < 8) {
                    tmp[n++] = rr * 8 + ff;
                    ff += DIR_DF[d];
                    rr += DIR_DR[d];
                }
                RAY[s][d] = Arrays.copyOf(tmp, n);
            }
            CASTLE_MASK[s] = 15;
        }
        CASTLE_MASK[0] = 15 & ~CASTLE_WQ;
        CASTLE_MASK[4] = 15 & ~(CASTLE_WK | CASTLE_WQ);
        CASTLE_MASK[7] = 15 & ~CASTLE_WK;
        CASTLE_MASK[56] = 15 & ~CASTLE_BQ;
        CASTLE_MASK[60] = 15 & ~(CASTLE_BK | CASTLE_BQ);
        CASTLE_MASK[63] = 15 & ~CASTLE_BK;

        long seed = 0x1234_5678_9ABC_DEF1L;
        for (int p = 0; p < 13; p++) {
            for (int s = 0; s < 64; s++) {
                seed = splitMix(seed);
                Z_PIECE[p][s] = mix(seed);
            }
        }
        for (int i = 0; i < 16; i++) {
            seed = splitMix(seed);
            Z_CASTLE[i] = mix(seed);
        }
        for (int i = 0; i < 64; i++) {
            seed = splitMix(seed);
            Z_EP[i] = mix(seed);
        }
        seed = splitMix(seed);
        Z_SIDE = mix(seed);
    }

    private static int[] targets(int f, int r, int[][] offsets) {
        int[] tmp = new int[8];
        int n = 0;
        for (int[] o : offsets) {
            int ff = f + o[0], rr = r + o[1];
            if (ff >= 0 && ff < 8 && rr >= 0 && rr < 8) tmp[n++] = rr * 8 + ff;
        }
        return Arrays.copyOf(tmp, n);
    }

    private static long splitMix(long x) {
        return x + 0x9E3779B97F4A7C15L;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long zp(int piece, int s) {
        return Z_PIECE[piece + 6][s];
    }

    // ---------------------------------------------------------------- 상태

    public final int[] sq = new int[64];
    public int side = WHITE;
    public int castle;
    /** 앙파상으로 잡을 수 있는 칸, 없으면 -1. */
    public int ep = -1;
    public int halfmove;
    public int fullmove = 1;
    public long hash;
    public final int[] kingSq = {-1, -1};

    private static final int MAX_HIST = 1024;
    private int hply;
    private final long[] histHash = new long[MAX_HIST];
    private final int[] histCap = new int[MAX_HIST];
    private final int[] histCastle = new int[MAX_HIST];
    private final int[] histEp = new int[MAX_HIST];
    private final int[] histHalf = new int[MAX_HIST];

    public Board() {
    }

    /** 반복 판정에 필요한 기록(최근 halfmove 수)만 들고 가는 복사본. 복사본은 그 이전으로 무를 수 없다. */
    public Board copy() {
        Board b = new Board();
        System.arraycopy(sq, 0, b.sq, 0, 64);
        b.side = side;
        b.castle = castle;
        b.ep = ep;
        b.halfmove = halfmove;
        b.fullmove = fullmove;
        b.hash = hash;
        b.kingSq[0] = kingSq[0];
        b.kingSq[1] = kingSq[1];
        int n = Math.min(Math.min(halfmove, hply), 200);
        System.arraycopy(histHash, hply - n, b.histHash, 0, n);
        b.hply = n;
        return b;
    }

    // ---------------------------------------------------------------- FEN

    public static Board fromFen(String fen) {
        Board b = new Board();
        String[] p = fen.trim().split("\\s+");
        int r = 7, f = 0;
        for (char c : p[0].toCharArray()) {
            if (c == '/') {
                r--;
                f = 0;
            } else if (c >= '1' && c <= '8') {
                f += c - '0';
            } else {
                b.sq[r * 8 + f] = pieceFromChar(c);
                f++;
            }
        }
        b.side = p.length > 1 && p[1].equals("b") ? BLACK : WHITE;
        b.castle = 0;
        if (p.length > 2) {
            for (char c : p[2].toCharArray()) {
                if (c == 'K') b.castle |= CASTLE_WK;
                else if (c == 'Q') b.castle |= CASTLE_WQ;
                else if (c == 'k') b.castle |= CASTLE_BK;
                else if (c == 'q') b.castle |= CASTLE_BQ;
            }
        }
        b.ep = p.length > 3 && !p[3].equals("-") ? parseSquare(p[3]) : -1;
        b.halfmove = p.length > 4 ? Integer.parseInt(p[4]) : 0;
        b.fullmove = p.length > 5 ? Integer.parseInt(p[5]) : 1;
        b.refresh();
        return b;
    }

    /** 화면에서 읽은 기물 배치로 판을 만든다. 캐슬링 권리는 킹·룩이 제자리에 있으면 있다고 본다. */
    public static Board fromPlacement(int[] placement, int side) {
        Board b = new Board();
        System.arraycopy(placement, 0, b.sq, 0, 64);
        b.side = side;
        b.castle = 0;
        if (b.sq[4] == KING) {
            if (b.sq[7] == ROOK) b.castle |= CASTLE_WK;
            if (b.sq[0] == ROOK) b.castle |= CASTLE_WQ;
        }
        if (b.sq[60] == -KING) {
            if (b.sq[63] == -ROOK) b.castle |= CASTLE_BK;
            if (b.sq[56] == -ROOK) b.castle |= CASTLE_BQ;
        }
        b.ep = -1;
        b.refresh();
        return b;
    }

    /** 킹 위치와 해시를 처음부터 다시 계산한다. */
    void refresh() {
        kingSq[0] = kingSq[1] = -1;
        long h = 0;
        for (int s = 0; s < 64; s++) {
            int p = sq[s];
            if (p == 0) continue;
            h ^= zp(p, s);
            if (p == KING) kingSq[WHITE] = s;
            else if (p == -KING) kingSq[BLACK] = s;
        }
        h ^= Z_CASTLE[castle];
        if (ep >= 0) h ^= Z_EP[ep];
        if (side == BLACK) h ^= Z_SIDE;
        hash = h;
        hply = 0;
    }

    /** 차례를 강제로 바꾼다 (사용자가 차례를 고칠 때). */
    public void forceSide(int newSide) {
        side = newSide;
        ep = -1;
        refresh();
    }

    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                int p = sq[r * 8 + f];
                if (p == 0) {
                    empty++;
                } else {
                    if (empty > 0) sb.append(empty);
                    empty = 0;
                    sb.append(pieceChar(p));
                }
            }
            if (empty > 0) sb.append(empty);
            if (r > 0) sb.append('/');
        }
        sb.append(side == WHITE ? " w " : " b ");
        if (castle == 0) sb.append('-');
        if ((castle & CASTLE_WK) != 0) sb.append('K');
        if ((castle & CASTLE_WQ) != 0) sb.append('Q');
        if ((castle & CASTLE_BK) != 0) sb.append('k');
        if ((castle & CASTLE_BQ) != 0) sb.append('q');
        sb.append(' ').append(ep >= 0 ? squareName(ep) : "-");
        sb.append(' ').append(halfmove).append(' ').append(fullmove);
        return sb.toString();
    }

    public static int pieceFromChar(char c) {
        int t = "PNBRQK".indexOf(Character.toUpperCase(c)) + 1;
        if (t == 0) throw new IllegalArgumentException("bad piece " + c);
        return Character.isUpperCase(c) ? t : -t;
    }

    public static char pieceChar(int p) {
        char c = "PNBRQK".charAt(Math.abs(p) - 1);
        return p > 0 ? c : Character.toLowerCase(c);
    }

    public static String squareName(int s) {
        return "" + (char) ('a' + (s & 7)) + (char) ('1' + (s >> 3));
    }

    public static int parseSquare(String s) {
        return (s.charAt(1) - '1') * 8 + (s.charAt(0) - 'a');
    }

    // ---------------------------------------------------------------- 공격 판정

    /** 칸 s를 by 쪽이 공격하고 있는가. */
    public boolean isAttacked(int s, int by) {
        if (s < 0) return false;
        int f = s & 7;
        if (by == WHITE) {
            if (f > 0 && s >= 9 && sq[s - 9] == PAWN) return true;
            if (f < 7 && s >= 7 && sq[s - 7] == PAWN) return true;
        } else {
            if (f > 0 && s + 7 < 64 && sq[s + 7] == -PAWN) return true;
            if (f < 7 && s + 9 < 64 && sq[s + 9] == -PAWN) return true;
        }
        int sign = by == WHITE ? 1 : -1;
        int kn = KNIGHT * sign;
        for (int t : KNIGHT_T[s]) if (sq[t] == kn) return true;
        int kg = KING * sign;
        for (int t : KING_T[s]) if (sq[t] == kg) return true;
        int rk = ROOK * sign, qn = QUEEN * sign, bs = BISHOP * sign;
        for (int d = 0; d < 4; d++) {
            for (int t : RAY[s][d]) {
                int q = sq[t];
                if (q != 0) {
                    if (q == rk || q == qn) return true;
                    break;
                }
            }
        }
        for (int d = 4; d < 8; d++) {
            for (int t : RAY[s][d]) {
                int q = sq[t];
                if (q != 0) {
                    if (q == bs || q == qn) return true;
                    break;
                }
            }
        }
        return false;
    }

    public boolean inCheck() {
        return isAttacked(kingSq[side], side ^ 1);
    }

    /** 방금 둔 쪽의 킹이 공격받고 있으면(=불법 수) true. make() 직후에 쓴다. */
    public boolean leftKingInCheck() {
        return isAttacked(kingSq[side ^ 1], side);
    }

    // ---------------------------------------------------------------- 수 생성

    /**
     * 의사 합법수(자기 킹이 체크로 남는 수 포함)를 만든다.
     *
     * @param noisyOnly true면 잡는 수와 퀸 승진만
     * @return 만든 수의 개수
     */
    public int genMoves(int[] out, boolean noisyOnly) {
        int n = 0;
        int us = side;
        int sign = us == WHITE ? 1 : -1;
        for (int s = 0; s < 64; s++) {
            int p = sq[s] * sign;
            if (p <= 0) continue;
            switch (p) {
                case PAWN:
                    n = genPawn(s, sign, out, n, noisyOnly);
                    break;
                case KNIGHT:
                    for (int t : KNIGHT_T[s]) {
                        int q = sq[t] * sign;
                        if (q < 0 || (q == 0 && !noisyOnly)) out[n++] = move(s, t);
                    }
                    break;
                case BISHOP:
                    n = genSlide(s, sign, 4, 8, out, n, noisyOnly);
                    break;
                case ROOK:
                    n = genSlide(s, sign, 0, 4, out, n, noisyOnly);
                    break;
                case QUEEN:
                    n = genSlide(s, sign, 0, 8, out, n, noisyOnly);
                    break;
                case KING:
                    for (int t : KING_T[s]) {
                        int q = sq[t] * sign;
                        if (q < 0 || (q == 0 && !noisyOnly)) out[n++] = move(s, t);
                    }
                    if (!noisyOnly) n = genCastle(s, out, n);
                    break;
                default:
                    break;
            }
        }
        return n;
    }

    private int genSlide(int s, int sign, int d0, int d1, int[] out, int n, boolean noisyOnly) {
        for (int d = d0; d < d1; d++) {
            for (int t : RAY[s][d]) {
                int q = sq[t] * sign;
                if (q == 0) {
                    if (!noisyOnly) out[n++] = move(s, t);
                } else {
                    if (q < 0) out[n++] = move(s, t);
                    break;
                }
            }
        }
        return n;
    }

    private int genPawn(int s, int sign, int[] out, int n, boolean noisyOnly) {
        int dir = 8 * sign;
        int r = s >> 3, f = s & 7;
        int startRank = sign > 0 ? 1 : 6;
        int lastRank = sign > 0 ? 7 : 0;
        int t = s + dir;
        if (t < 0 || t > 63) return n;
        boolean promote = (t >> 3) == lastRank;
        if (sq[t] == 0) {
            if (promote) {
                n = addPromos(s, t, out, n, noisyOnly);
            } else if (!noisyOnly) {
                out[n++] = move(s, t);
                int t2 = t + dir;
                if (r == startRank && sq[t2] == 0) out[n++] = move(s, t2, 0, FLAG_DOUBLE);
            }
        }
        for (int df = -1; df <= 1; df += 2) {
            int ff = f + df;
            if (ff < 0 || ff > 7) continue;
            int c = t + df;
            if (sq[c] * sign < 0) {
                if (promote) n = addPromos(s, c, out, n, noisyOnly);
                else out[n++] = move(s, c);
            } else if (c == ep && sq[c] == 0) {
                out[n++] = move(s, c, 0, FLAG_EP);
            }
        }
        return n;
    }

    private static int addPromos(int s, int t, int[] out, int n, boolean noisyOnly) {
        out[n++] = move(s, t, QUEEN, FLAG_NONE);
        if (!noisyOnly) {
            out[n++] = move(s, t, KNIGHT, FLAG_NONE);
            out[n++] = move(s, t, ROOK, FLAG_NONE);
            out[n++] = move(s, t, BISHOP, FLAG_NONE);
        }
        return n;
    }

    private int genCastle(int s, int[] out, int n) {
        if (side == WHITE) {
            if (s != 4) return n;
            if ((castle & CASTLE_WK) != 0 && sq[5] == 0 && sq[6] == 0 && sq[7] == ROOK
                    && !isAttacked(4, BLACK) && !isAttacked(5, BLACK) && !isAttacked(6, BLACK)) {
                out[n++] = move(4, 6, 0, FLAG_CASTLE);
            }
            if ((castle & CASTLE_WQ) != 0 && sq[3] == 0 && sq[2] == 0 && sq[1] == 0 && sq[0] == ROOK
                    && !isAttacked(4, BLACK) && !isAttacked(3, BLACK) && !isAttacked(2, BLACK)) {
                out[n++] = move(4, 2, 0, FLAG_CASTLE);
            }
        } else {
            if (s != 60) return n;
            if ((castle & CASTLE_BK) != 0 && sq[61] == 0 && sq[62] == 0 && sq[63] == -ROOK
                    && !isAttacked(60, WHITE) && !isAttacked(61, WHITE) && !isAttacked(62, WHITE)) {
                out[n++] = move(60, 62, 0, FLAG_CASTLE);
            }
            if ((castle & CASTLE_BQ) != 0 && sq[59] == 0 && sq[58] == 0 && sq[57] == 0 && sq[56] == -ROOK
                    && !isAttacked(60, WHITE) && !isAttacked(59, WHITE) && !isAttacked(58, WHITE)) {
                out[n++] = move(60, 58, 0, FLAG_CASTLE);
            }
        }
        return n;
    }

    /** 합법수만. */
    public int[] legalMoves() {
        int[] buf = new int[256];
        int n = genMoves(buf, false);
        int k = 0;
        for (int i = 0; i < n; i++) {
            int m = buf[i];
            make(m);
            if (!leftKingInCheck()) buf[k++] = m;
            unmake(m);
        }
        return Arrays.copyOf(buf, k);
    }

    public boolean isCapture(int m) {
        return sq[to(m)] != 0 || flag(m) == FLAG_EP;
    }

    // ---------------------------------------------------------------- 두기 / 무르기

    public void make(int m) {
        int from = from(m), to = to(m), fl = flag(m), pr = promo(m);
        int sign = side == WHITE ? 1 : -1;
        int p = sq[from];
        int cap = sq[to];
        histHash[hply] = hash;
        histCastle[hply] = castle;
        histEp[hply] = ep;
        histHalf[hply] = halfmove;
        long h = hash ^ Z_CASTLE[castle];
        if (ep >= 0) h ^= Z_EP[ep];
        if (fl == FLAG_EP) {
            int cs = to - 8 * sign;
            cap = sq[cs];
            sq[cs] = 0;
            h ^= zp(cap, cs);
        } else if (cap != 0) {
            h ^= zp(cap, to);
        }
        histCap[hply] = cap;
        hply++;
        sq[from] = 0;
        h ^= zp(p, from);
        int np = pr != 0 ? pr * sign : p;
        sq[to] = np;
        h ^= zp(np, to);
        if (fl == FLAG_CASTLE) {
            int rf = to > from ? from + 3 : from - 4;
            int rt = to > from ? from + 1 : from - 1;
            int rook = sq[rf];
            sq[rf] = 0;
            sq[rt] = rook;
            h ^= zp(rook, rf) ^ zp(rook, rt);
        }
        if (p == KING * sign) kingSq[side] = to;
        ep = fl == FLAG_DOUBLE ? (from + to) >> 1 : -1;
        castle &= CASTLE_MASK[from] & CASTLE_MASK[to];
        h ^= Z_CASTLE[castle];
        if (ep >= 0) h ^= Z_EP[ep];
        halfmove = (p == PAWN * sign || cap != 0) ? 0 : halfmove + 1;
        if (side == BLACK) fullmove++;
        side ^= 1;
        hash = h ^ Z_SIDE;
    }

    public void unmake(int m) {
        side ^= 1;
        if (side == BLACK) fullmove--;
        hply--;
        int from = from(m), to = to(m), fl = flag(m);
        int sign = side == WHITE ? 1 : -1;
        int p = promo(m) != 0 ? PAWN * sign : sq[to];
        sq[from] = p;
        int cap = histCap[hply];
        if (fl == FLAG_EP) {
            sq[to] = 0;
            sq[to - 8 * sign] = cap;
        } else {
            sq[to] = cap;
        }
        if (fl == FLAG_CASTLE) {
            int rf = to > from ? from + 3 : from - 4;
            int rt = to > from ? from + 1 : from - 1;
            sq[rf] = sq[rt];
            sq[rt] = 0;
        }
        if (p == KING * sign) kingSq[side] = from;
        castle = histCastle[hply];
        ep = histEp[hply];
        halfmove = histHalf[hply];
        hash = histHash[hply];
    }

    /** 차례만 넘기기 (탐색의 널 무브 가지치기용). */
    public void makeNull() {
        histHash[hply] = hash;
        histCastle[hply] = castle;
        histEp[hply] = ep;
        histHalf[hply] = halfmove;
        histCap[hply] = 0;
        hply++;
        long h = hash;
        if (ep >= 0) h ^= Z_EP[ep];
        ep = -1;
        halfmove = 0;
        side ^= 1;
        hash = h ^ Z_SIDE;
    }

    public void unmakeNull() {
        hply--;
        side ^= 1;
        castle = histCastle[hply];
        ep = histEp[hply];
        halfmove = histHalf[hply];
        hash = histHash[hply];
    }

    // ---------------------------------------------------------------- 무승부 판정

    /** 같은 판이 이전에 나온 적이 있으면 true (탐색에서는 한 번만 반복돼도 무승부로 본다). */
    public boolean isRepetition() {
        int limit = Math.max(0, hply - halfmove);
        for (int i = hply - 4; i >= limit; i -= 2) {
            if (histHash[i] == hash) return true;
        }
        return false;
    }

    /** 어느 쪽도 체크메이트를 할 수 없는 기물 구성 (킹만, 킹+마이너 하나). */
    public boolean insufficientMaterial() {
        int minors = 0;
        for (int s = 0; s < 64; s++) {
            int t = Math.abs(sq[s]);
            if (t == PAWN || t == ROOK || t == QUEEN) return false;
            if (t == KNIGHT || t == BISHOP) minors++;
        }
        return minors <= 1;
    }

    public boolean hasNonPawnMaterial(int color) {
        int sign = color == WHITE ? 1 : -1;
        for (int s = 0; s < 64; s++) {
            int p = sq[s] * sign;
            if (p >= KNIGHT && p <= QUEEN) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 표기

    public static String uci(int m) {
        String s = squareName(from(m)) + squareName(to(m));
        if (promo(m) != 0) s += "nbrq".charAt(promo(m) - 2);
        return s;
    }

    /** 표준 대수 기보(SAN). 예: Nf3, exd5, O-O, e8=Q+, Qh7#. */
    public String san(int m) {
        int from = from(m), to = to(m);
        int p = Math.abs(sq[from]);
        String s;
        if (flag(m) == FLAG_CASTLE) {
            s = to > from ? "O-O" : "O-O-O";
        } else {
            StringBuilder sb = new StringBuilder();
            boolean capture = isCapture(m);
            if (p == PAWN) {
                if (capture) sb.append((char) ('a' + (from & 7)));
            } else {
                sb.append("NBRQK".charAt(p - 2));
                boolean ambiguous = false, sameFile = false, sameRank = false;
                for (int o : legalMoves()) {
                    int of = from(o);
                    if (to(o) == to && of != from && Math.abs(sq[of]) == p) {
                        ambiguous = true;
                        if ((of & 7) == (from & 7)) sameFile = true;
                        if ((of >> 3) == (from >> 3)) sameRank = true;
                    }
                }
                if (ambiguous) {
                    if (!sameFile) sb.append((char) ('a' + (from & 7)));
                    else if (!sameRank) sb.append((char) ('1' + (from >> 3)));
                    else sb.append(squareName(from));
                }
            }
            if (capture) sb.append('x');
            sb.append(squareName(to));
            if (promo(m) != 0) sb.append('=').append("NBRQ".charAt(promo(m) - 2));
            s = sb.toString();
        }
        make(m);
        boolean check = inCheck();
        boolean mate = check && legalMoves().length == 0;
        unmake(m);
        return s + (mate ? "#" : check ? "+" : "");
    }

    /** uci 문자열(e2e4, e7e8q)에 해당하는 합법수, 없으면 0. */
    public int parseUci(String u) {
        for (int m : legalMoves()) if (uci(m).equals(u)) return m;
        return 0;
    }

    /** 노드 수 세기 (수 생성 검증용). */
    public long perft(int depth) {
        if (depth == 0) return 1;
        int[] buf = new int[256];
        int n = genMoves(buf, false);
        long total = 0;
        for (int i = 0; i < n; i++) {
            make(buf[i]);
            if (!leftKingInCheck()) total += perft(depth - 1);
            unmake(buf[i]);
        }
        return total;
    }

    // ---------------------------------------------------------------- 화면 인식 결과 확인

    public static boolean isStartPlacement(int[] placement) {
        for (int f = 0; f < 8; f++) {
            if (placement[f] != BACK_RANK[f] || placement[8 + f] != PAWN
                    || placement[48 + f] != -PAWN || placement[56 + f] != -BACK_RANK[f]) {
                return false;
            }
        }
        for (int s = 16; s < 48; s++) if (placement[s] != 0) return false;
        return true;
    }

    public static int[] startPlacement() {
        return fromFen(START_FEN).sq.clone();
    }

    /** 실제 대국에서 나올 수 있는 배치인지 대략 확인. 문제가 없으면 null, 있으면 이유. */
    public static String validatePlacement(int[] p) {
        int wk = 0, bk = 0, wp = 0, bp = 0, w = 0, b = 0, wks = -1, bks = -1;
        for (int s = 0; s < 64; s++) {
            int x = p[s];
            if (x > 0) w++;
            if (x < 0) b++;
            if (x == KING) {
                wk++;
                wks = s;
            }
            if (x == -KING) {
                bk++;
                bks = s;
            }
            if (x == PAWN) wp++;
            if (x == -PAWN) bp++;
            if (Math.abs(x) == PAWN && (s < 8 || s >= 56)) return "첫 줄/끝 줄에 폰";
        }
        if (wk != 1 || bk != 1) return "킹이 " + (wk + bk) + "개";
        if (wp > 8 || bp > 8) return "폰이 너무 많음";
        if (w > 16 || b > 16) return "기물이 너무 많음";
        int df = Math.abs((wks & 7) - (bks & 7)), dr = Math.abs((wks >> 3) - (bks >> 3));
        if (df <= 1 && dr <= 1) return "킹끼리 붙어 있음";
        return null;
    }
}
