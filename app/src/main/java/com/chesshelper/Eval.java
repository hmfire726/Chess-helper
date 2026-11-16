package com.chesshelper;

/**
 * 정적 평가 함수. 결과는 "지금 둘 차례인 쪽" 기준 점수(센티폰, 100 = 폰 1개).
 *
 * <p>중반(mg)과 종반(eg) 점수를 따로 계산한 뒤, 남은 기물 양(phase)에 따라 섞는다.
 * 위치 점수표는 수식으로 만든다: 나이트·비숍은 가운데일수록, 킹은 중반엔 구석·종반엔 가운데일수록 좋다.
 */
public final class Eval {
    private Eval() {
    }

    static final int[] MAT_MG = {0, 100, 320, 335, 480, 950, 0};
    static final int[] MAT_EG = {0, 120, 300, 320, 520, 960, 0};
    private static final int[] PHASE = {0, 0, 1, 1, 2, 4, 0};
    private static final int MAX_PHASE = 24;

    /** [기물 종류][칸] — 백 기준. 흑은 칸을 위아래로 뒤집어(s ^ 56) 쓴다. */
    static final int[][] PST_MG = new int[7][64];
    static final int[][] PST_EG = new int[7][64];

    /** 통과한 폰 보너스 (상대 쪽으로 몇 줄 나아갔는지 0~7). */
    private static final int[] PASSED_MG = {0, 0, 5, 10, 18, 32, 55, 0};
    private static final int[] PASSED_EG = {0, 0, 12, 22, 40, 70, 115, 0};

    private static final int[] MOB_MG = {0, 0, 4, 5, 2, 1, 0};
    private static final int[] MOB_EG = {0, 0, 4, 5, 4, 2, 0};
    private static final int[] MOB_BASE = {0, 0, 4, 6, 6, 12, 0};

    private static final long FILE_A = 0x0101010101010101L;
    private static final long FILE_H = FILE_A << 7;
    private static final long[] FILE_MASK = new long[8];
    private static final long[] ADJ_FILES = new long[8];
    /** [색][칸] 이 폰 앞쪽(같은 파일 + 양옆 파일)의 칸들. 여기에 상대 폰이 없으면 통과한 폰. */
    private static final long[][] PASSED_MASK = new long[2][64];
    /** [색][칸] 이 폰 앞쪽 같은 파일. 여기 내 폰이 또 있으면 겹폰. */
    private static final long[][] FRONT_MASK = new long[2][64];

    private static final int TEMPO = 12;

    static {
        for (int f = 0; f < 8; f++) {
            FILE_MASK[f] = FILE_A << f;
        }
        for (int f = 0; f < 8; f++) {
            ADJ_FILES[f] = (f > 0 ? FILE_MASK[f - 1] : 0) | (f < 7 ? FILE_MASK[f + 1] : 0);
        }
        for (int s = 0; s < 64; s++) {
            int f = s & 7, r = s >> 3;
            long span = FILE_MASK[f] | ADJ_FILES[f];
            long ahead = 0, behind = 0;
            for (int rr = r + 1; rr < 8; rr++) ahead |= 0xFFL << (rr * 8);
            for (int rr = r - 1; rr >= 0; rr--) behind |= 0xFFL << (rr * 8);
            PASSED_MASK[0][s] = span & ahead;
            PASSED_MASK[1][s] = span & behind;
            FRONT_MASK[0][s] = FILE_MASK[f] & ahead;
            FRONT_MASK[1][s] = FILE_MASK[f] & behind;
        }
        buildTables();
    }

    private static void buildTables() {
        int[] pawnRankMg = {0, 0, 3, 8, 14, 25, 45, 0};
        int[] pawnRankEg = {0, 0, 5, 12, 22, 38, 60, 0};
        int[] kingFileMg = {8, 20, 12, -5, 0, -10, 25, 15};
        int[] kingRankMg = {0, -20, -40, -60, -70, -80, -80, -80};
        for (int s = 0; s < 64; s++) {
            int f = s & 7, r = s >> 3;
            double cf = Math.abs(f - 3.5), cr = Math.abs(r - 3.5);
            int md = (int) Math.round(cf + cr); // 가운데까지 맨해튼 거리 1~7
            boolean centerFile = f == 3 || f == 4;
            boolean nearCenterFile = f == 2 || f == 5;

            int pmg = pawnRankMg[r];
            if (centerFile) pmg += r == 3 ? 18 : r == 4 ? 14 : r == 2 ? 8 : r == 1 ? -6 : 0;
            else if (nearCenterFile) pmg += r == 3 ? 6 : r == 2 ? 3 : 0;
            else if (r == 1) pmg += 4;
            PST_MG[Board.PAWN][s] = (r == 0 || r == 7) ? 0 : pmg;
            PST_EG[Board.PAWN][s] = (r == 0 || r == 7) ? 0 : pawnRankEg[r];

            PST_MG[Board.KNIGHT][s] = 30 - 9 * md - (r == 0 ? 10 : 0);
            PST_EG[Board.KNIGHT][s] = 20 - 7 * md;

            PST_MG[Board.BISHOP][s] = 15 - 4 * md - (r == 0 ? 8 : 0) + ((f == r || f + r == 7) ? 8 : 0);
            PST_EG[Board.BISHOP][s] = 10 - 4 * md;

            PST_MG[Board.ROOK][s] = (r == 6 ? 18 : 0) + (centerFile ? 6 : nearCenterFile ? 3 : 0) - ((f == 0 || f == 7) ? 2 : 0);
            PST_EG[Board.ROOK][s] = r == 6 ? 12 : 0;

            PST_MG[Board.QUEEN][s] = (r == 0 ? 0 : 4) - 2 * (int) Math.max(cf, cr);
            PST_EG[Board.QUEEN][s] = 20 - 6 * md;

            PST_MG[Board.KING][s] = kingFileMg[f] + kingRankMg[r];
            PST_EG[Board.KING][s] = 30 - 10 * md;
        }
    }

    /** 지금 둘 차례인 쪽 기준 점수. */
    public static int evaluate(Board b) {
        int[] sq = b.sq;
        int mg = 0, eg = 0, phase = 0;
        long wp = 0, bp = 0, wOcc = 0, bOcc = 0;
        int wBishops = 0, bBishops = 0;
        int wNonPawn = 0, bNonPawn = 0;
        for (int s = 0; s < 64; s++) {
            int p = sq[s];
            if (p == 0) continue;
            if (p > 0) {
                mg += MAT_MG[p] + PST_MG[p][s];
                eg += MAT_EG[p] + PST_EG[p][s];
                phase += PHASE[p];
                wOcc |= 1L << s;
                if (p == Board.PAWN) wp |= 1L << s;
                else if (p != Board.KING) wNonPawn += MAT_MG[p];
                if (p == Board.BISHOP) wBishops++;
            } else {
                int t = -p, ms = s ^ 56;
                mg -= MAT_MG[t] + PST_MG[t][ms];
                eg -= MAT_EG[t] + PST_EG[t][ms];
                phase += PHASE[t];
                bOcc |= 1L << s;
                if (t == Board.PAWN) bp |= 1L << s;
                else if (t != Board.KING) bNonPawn += MAT_MG[t];
                if (t == Board.BISHOP) bBishops++;
            }
        }

        long wPawnAtt = ((wp << 9) & ~FILE_A) | ((wp << 7) & ~FILE_H);
        long bPawnAtt = ((bp >>> 7) & ~FILE_A) | ((bp >>> 9) & ~FILE_H);

        for (int s = 0; s < 64; s++) {
            int p = sq[s];
            if (p == 0) continue;
            int t = Math.abs(p);
            boolean white = p > 0;
            int sign = white ? 1 : -1;
            int f = s & 7;
            switch (t) {
                case Board.PAWN: {
                    long own = white ? wp : bp, enemy = white ? bp : wp;
                    int c = white ? 0 : 1;
                    if ((own & FRONT_MASK[c][s]) != 0) {
                        mg -= 12 * sign;
                        eg -= 20 * sign;
                    }
                    if ((own & ADJ_FILES[f]) == 0) {
                        mg -= 12 * sign;
                        eg -= 10 * sign;
                    }
                    if ((enemy & PASSED_MASK[c][s]) == 0) {
                        int adv = white ? (s >> 3) : 7 - (s >> 3);
                        int bonusEg = PASSED_EG[adv];
                        int front = white ? s + 8 : s - 8;
                        if (front >= 0 && front < 64 && sq[front] != 0) bonusEg /= 2;
                        mg += PASSED_MG[adv] * sign;
                        eg += bonusEg * sign;
                    }
                    break;
                }
                case Board.KNIGHT: {
                    long own = white ? wOcc : bOcc, enemyPawnAtt = white ? bPawnAtt : wPawnAtt;
                    int cnt = 0;
                    for (int to : Board.KNIGHT_T[s]) {
                        long bit = 1L << to;
                        if ((own & bit) == 0 && (enemyPawnAtt & bit) == 0) cnt++;
                    }
                    mg += MOB_MG[t] * (cnt - MOB_BASE[t]) * sign;
                    eg += MOB_EG[t] * (cnt - MOB_BASE[t]) * sign;
                    break;
                }
                case Board.BISHOP:
                case Board.ROOK:
                case Board.QUEEN: {
                    int d0 = t == Board.BISHOP ? 4 : 0, d1 = t == Board.ROOK ? 4 : 8;
                    int cnt = 0;
                    for (int d = d0; d < d1; d++) {
                        for (int to : Board.RAY[s][d]) {
                            int q = sq[to];
                            if (q == 0) {
                                cnt++;
                            } else {
                                if ((q > 0) != white) cnt++;
                                break;
                            }
                        }
                    }
                    mg += MOB_MG[t] * (cnt - MOB_BASE[t]) * sign;
                    eg += MOB_EG[t] * (cnt - MOB_BASE[t]) * sign;
                    if (t == Board.ROOK) {
                        long own = white ? wp : bp, enemy = white ? bp : wp;
                        if ((own & FILE_MASK[f]) == 0) {
                            if ((enemy & FILE_MASK[f]) == 0) {
                                mg += 20 * sign;
                                eg += 10 * sign;
                            } else {
                                mg += 10 * sign;
                                eg += 5 * sign;
                            }
                        }
                    }
                    break;
                }
                case Board.KING: {
                    int rel = white ? (s >> 3) : 7 - (s >> 3);
                    if (rel <= 1) {
                        long own = white ? wp : bp;
                        int shield = 0;
                        for (int ff = Math.max(0, f - 1); ff <= Math.min(7, f + 1); ff++) {
                            int r1 = white ? (s >> 3) + 1 : (s >> 3) - 1;
                            int r2 = white ? (s >> 3) + 2 : (s >> 3) - 2;
                            if ((own & (1L << (r1 * 8 + ff))) != 0) shield += 12;
                            else if (r2 >= 0 && r2 < 8 && (own & (1L << (r2 * 8 + ff))) != 0) shield += 6;
                            if ((own & FILE_MASK[ff]) == 0) shield -= 15;
                        }
                        mg += shield * sign;
                    }
                    break;
                }
                default:
                    break;
            }
        }

        if (wBishops >= 2) {
            mg += 30;
            eg += 45;
        }
        if (bBishops >= 2) {
            mg -= 30;
            eg -= 45;
        }

        if (phase > MAX_PHASE) phase = MAX_PHASE;
        int score = (mg * phase + eg * (MAX_PHASE - phase)) / MAX_PHASE;

        // 폰 없이 마이너 하나 차이 정도로는 이기기 어렵다 → 점수를 줄인다
        if (score > 0 && wp == 0 && wNonPawn - bNonPawn <= 340) score /= 4;
        else if (score < 0 && bp == 0 && bNonPawn - wNonPawn <= 340) score /= 4;

        // 이기는 종반: 상대 킹을 구석으로 몰고 내 킹을 가까이
        if (phase <= 8 && Math.abs(score) > 250) {
            boolean whiteWinning = score > 0;
            long loserPawns = whiteWinning ? bp : wp;
            if (loserPawns == 0) {
                int lk = b.kingSq[whiteWinning ? Board.BLACK : Board.WHITE];
                int wk = b.kingSq[whiteWinning ? Board.WHITE : Board.BLACK];
                if (lk >= 0 && wk >= 0) {
                    int lf = lk & 7, lr = lk >> 3;
                    int edge = Math.max(3 - lf, lf - 4) + Math.max(3 - lr, lr - 4);
                    int dist = Math.abs(lf - (wk & 7)) + Math.abs(lr - (wk >> 3));
                    int bonus = 10 * edge + 5 * (14 - dist);
                    score += whiteWinning ? bonus : -bonus;
                }
            }
        }

        score += b.side == Board.WHITE ? TEMPO : -TEMPO;
        return b.side == Board.WHITE ? score : -score;
    }

    /** 수 순서 정렬, 잡기 이유 표시에 쓰는 대략적인 기물 값. */
    public static int pieceValue(int type) {
        return MAT_MG[type];
    }
}
