package com.chesshelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 탐색 엔진: 반복 심화 + 알파-베타(PVS) + 치환표 + 정지 탐색(잡기만 끝까지).
 *
 * <p>여러 후보(MultiPV)를 순위대로 돌려준다. 한 인스턴스는 한 번에 한 스레드에서만 쓴다.
 * 다른 스레드에서는 {@link #stop()}만 불러도 된다.
 */
public final class Search {
    public static final int INF = 32000;
    public static final int MATE = 30000;
    /** 이보다 큰 점수는 "몇 수 만에 메이트". */
    public static final int MATE_BOUND = MATE - 512;
    private static final int MAX_PLY = 96;

    private static final int TT_EXACT = 1, TT_LOWER = 2, TT_UPPER = 3;

    /** 후보 수 하나. 점수는 둘 차례인 쪽 기준. */
    public static final class Line {
        public final int move;
        public final int score;
        public final int[] pv;

        Line(int move, int score, int[] pv) {
            this.move = move;
            this.score = score;
            this.pv = pv;
        }

        public boolean isMate() {
            return Math.abs(score) >= MATE_BOUND;
        }

        /** 메이트까지 남은 수 (내가 메이트하면 양수, 당하면 음수). 메이트가 아니면 0. */
        public int mateIn() {
            if (score >= MATE_BOUND) return (MATE - score + 1) / 2;
            if (score <= -MATE_BOUND) return -((MATE + score) / 2);
            return 0;
        }
    }

    public static final class Result {
        public final List<Line> lines;
        public final int depth;
        public final long nodes;
        public final long millis;
        /** 둘 수가 없을 때: 체크메이트면 true, 스테일메이트면 false. lines가 비어 있을 때만 의미 있음. */
        public final boolean checkmated;

        Result(List<Line> lines, int depth, long nodes, long millis, boolean checkmated) {
            this.lines = lines;
            this.depth = depth;
            this.nodes = nodes;
            this.millis = millis;
            this.checkmated = checkmated;
        }

        public Line best() {
            return lines.isEmpty() ? null : lines.get(0);
        }
    }

    public interface Listener {
        /** 한 깊이 탐색이 끝날 때마다 (탐색 스레드에서) 불린다. */
        void onIteration(Result r);
    }

    private final long[] ttKeys;
    private final long[] ttData;
    private final int ttMask;

    private volatile boolean stopRequested;
    private boolean stopped;
    private boolean canTimeout;
    private long deadline;
    private long nodes;

    private final int[][] killers = new int[MAX_PLY + 2][2];
    private final int[][] history = new int[64][64];
    private final int[][] pvTable = new int[MAX_PLY + 2][MAX_PLY + 2];
    private final int[] pvLen = new int[MAX_PLY + 2];
    private final int[][] moveBuf = new int[MAX_PLY + 2][256];
    private final int[][] orderBuf = new int[MAX_PLY + 2][256];
    private static final int[][] LMR = new int[64][64];

    static {
        for (int d = 1; d < 64; d++) {
            for (int i = 1; i < 64; i++) {
                LMR[d][i] = (int) (0.6 + Math.log(d) * Math.log(i) / 2.3);
            }
        }
    }

    /** @param ttBits 치환표 크기 = 2^ttBits 칸 (칸당 16바이트) */
    public Search(int ttBits) {
        ttKeys = new long[1 << ttBits];
        ttData = new long[1 << ttBits];
        ttMask = (1 << ttBits) - 1;
    }

    public void stop() {
        stopRequested = true;
    }

    public void clearHash() {
        java.util.Arrays.fill(ttKeys, 0);
        java.util.Arrays.fill(ttData, 0);
    }

    /**
     * @param multiPv  돌려받을 후보 수 개수
     * @param timeMs   생각 시간 (0 이하면 제한 없음 → maxDepth까지)
     * @param maxDepth 최대 깊이
     */
    public Result analyze(Board start, int multiPv, long timeMs, int maxDepth, Listener listener) {
        Board b = start.copy();
        stopRequested = false;
        stopped = false;
        canTimeout = false;
        nodes = 0;
        long t0 = System.currentTimeMillis();
        deadline = timeMs > 0 ? t0 + timeMs : Long.MAX_VALUE;
        for (int[] k : killers) {
            k[0] = 0;
            k[1] = 0;
        }
        for (int[] h : history) {
            for (int i = 0; i < 64; i++) h[i] >>= 3;
        }

        int[] root = b.legalMoves();
        int n = root.length;
        if (n == 0) {
            return new Result(Collections.<Line>emptyList(), 0, 0, 0, b.inCheck());
        }
        int k = Math.max(1, Math.min(multiPv, n));
        orderRoot(b, root);

        Result best = null;
        int[] scores = new int[n];
        int[][] pvs = new int[n][];
        for (int depth = 1; depth <= maxDepth; depth++) {
            canTimeout = depth > 1;
            int[] top = new int[k];
            int topCount = 0;
            boolean complete = true;
            for (int i = 0; i < n; i++) {
                int m = root[i];
                int bound = topCount >= k ? top[k - 1] : -INF;
                b.make(m);
                int score;
                if (bound == -INF) {
                    score = -search(b, depth - 1, -INF, INF, 1, true);
                } else {
                    score = -search(b, depth - 1, -bound - 1, -bound, 1, true);
                    if (!stopped && score > bound) score = -search(b, depth - 1, -INF, -bound, 1, true);
                }
                b.unmake(m);
                if (stopped) {
                    complete = false;
                    break;
                }
                scores[i] = score;
                if (score > bound) {
                    int[] pv = new int[pvLen[1]];
                    pv[0] = m;
                    if (pvLen[1] > 1) System.arraycopy(pvTable[1], 1, pv, 1, pvLen[1] - 1);
                    pvs[i] = pv;
                    // top[]은 내림차순으로 유지
                    int j = Math.min(topCount, k - 1);
                    while (j > 0 && top[j - 1] < score) {
                        top[j] = top[j - 1];
                        j--;
                    }
                    top[j] = score;
                    if (topCount < k) topCount++;
                } else {
                    pvs[i] = new int[]{m};
                }
            }
            if (!complete) break;

            sortRoot(root, scores, pvs);
            List<Line> lines = new ArrayList<>();
            for (int i = 0; i < k; i++) lines.add(new Line(root[i], scores[i], pvs[i]));
            best = new Result(lines, depth, nodes, System.currentTimeMillis() - t0, false);
            if (listener != null) listener.onIteration(best);

            if (stopRequested) break;
            int bestScore = scores[0];
            if (Math.abs(bestScore) >= MATE_BOUND && depth >= (MATE - Math.abs(bestScore)) + 2 && k == 1) break;
            if (timeMs > 0 && System.currentTimeMillis() - t0 > timeMs * 55 / 100) break;
        }
        if (best == null) {
            // 시간이 너무 짧아 깊이 1도 못 끝낸 경우 (거의 없음)
            List<Line> lines = new ArrayList<>();
            for (int i = 0; i < k; i++) lines.add(new Line(root[i], 0, new int[]{root[i]}));
            best = new Result(lines, 0, nodes, System.currentTimeMillis() - t0, false);
        }
        return best;
    }

    private void orderRoot(Board b, int[] root) {
        int[] keys = new int[root.length];
        for (int i = 0; i < root.length; i++) keys[i] = captureOrder(b, root[i]);
        for (int i = 1; i < root.length; i++) {
            int m = root[i], kk = keys[i], j = i - 1;
            while (j >= 0 && keys[j] < kk) {
                root[j + 1] = root[j];
                keys[j + 1] = keys[j];
                j--;
            }
            root[j + 1] = m;
            keys[j + 1] = kk;
        }
    }

    /** 점수 내림차순 안정 정렬 (같은 점수면 이전 순서 유지). */
    private static void sortRoot(int[] root, int[] scores, int[][] pvs) {
        for (int i = 1; i < root.length; i++) {
            int m = root[i], s = scores[i];
            int[] pv = pvs[i];
            int j = i - 1;
            while (j >= 0 && scores[j] < s) {
                root[j + 1] = root[j];
                scores[j + 1] = scores[j];
                pvs[j + 1] = pvs[j];
                j--;
            }
            root[j + 1] = m;
            scores[j + 1] = s;
            pvs[j + 1] = pv;
        }
    }

    private void checkTime() {
        if (stopRequested) {
            stopped = true;
        } else if (canTimeout && System.currentTimeMillis() >= deadline) {
            stopped = true;
        }
    }

    private int search(Board b, int depth, int alpha, int beta, int ply, boolean allowNull) {
        pvLen[ply] = ply;
        if ((++nodes & 2047) == 0) checkTime();
        if (stopped) return 0;

        if (b.halfmove >= 100 || b.isRepetition() || b.insufficientMaterial()) return 0;
        // 메이트 거리 가지치기
        alpha = Math.max(alpha, -MATE + ply);
        beta = Math.min(beta, MATE - ply - 1);
        if (alpha >= beta) return alpha;

        boolean inCheck = b.inCheck();
        if (inCheck) depth++;
        if (depth <= 0) return quiesce(b, alpha, beta, ply);
        if (ply >= MAX_PLY) return Eval.evaluate(b);

        boolean pvNode = beta - alpha > 1;
        int ttMove = 0;
        int idx = (int) b.hash & ttMask;
        long data = ttData[idx];
        if (data != 0 && ttKeys[idx] == b.hash) {
            ttMove = (int) (data & 0x3FFFF);
            int tScore = (int) ((data >>> 18) & 0xFFFF) - 32768;
            int tDepth = (int) ((data >>> 34) & 0xFF);
            int tFlag = (int) ((data >>> 42) & 3);
            if (tScore >= MATE_BOUND) tScore -= ply;
            else if (tScore <= -MATE_BOUND) tScore += ply;
            if (!pvNode && tDepth >= depth) {
                if (tFlag == TT_EXACT) return tScore;
                if (tFlag == TT_LOWER && tScore >= beta) return tScore;
                if (tFlag == TT_UPPER && tScore <= alpha) return tScore;
            }
        }

        int staticEval = inCheck ? -INF : Eval.evaluate(b);

        if (!pvNode && !inCheck) {
            // 역 퓨틸리티: 이미 한참 이기고 있으면 더 볼 필요 없음
            if (depth <= 3 && Math.abs(beta) < MATE_BOUND && staticEval - 120 * depth >= beta) return staticEval;
            // 널 무브: 한 번 쉬어도 beta 이상이면 잘라 냄
            if (allowNull && depth >= 3 && staticEval >= beta && b.hasNonPawnMaterial(b.side)) {
                int r = 2 + depth / 4;
                b.makeNull();
                int score = -search(b, depth - 1 - r, -beta, -beta + 1, ply + 1, false);
                b.unmakeNull();
                if (stopped) return 0;
                if (score >= beta) return score >= MATE_BOUND ? beta : score;
            }
        }

        int[] moves = moveBuf[ply];
        int[] order = orderBuf[ply];
        int n = b.genMoves(moves, false);
        for (int i = 0; i < n; i++) order[i] = moveOrder(b, moves[i], ttMove, ply);

        int origAlpha = alpha;
        int best = -INF, bestMove = 0, legal = 0;
        for (int i = 0; i < n; i++) {
            pickNext(moves, order, i, n);
            int m = moves[i];
            boolean quiet = !b.isCapture(m) && Board.promo(m) == 0;
            b.make(m);
            if (b.leftKingInCheck()) {
                b.unmake(m);
                continue;
            }
            legal++;
            boolean givesCheck = b.inCheck();
            if (!pvNode && !inCheck && !givesCheck && quiet && depth <= 2 && legal > 1
                    && staticEval + 100 + 150 * depth <= alpha) {
                b.unmake(m);
                continue;
            }
            int score;
            if (legal == 1) {
                score = -search(b, depth - 1, -beta, -alpha, ply + 1, true);
            } else {
                int r = 0;
                if (depth >= 3 && quiet && !inCheck && !givesCheck && legal > 3
                        && m != killers[ply][0] && m != killers[ply][1]) {
                    r = LMR[Math.min(depth, 63)][Math.min(legal, 63)];
                    if (pvNode) r--;
                    r = Math.max(0, Math.min(r, depth - 2));
                }
                score = -search(b, depth - 1 - r, -alpha - 1, -alpha, ply + 1, true);
                if (score > alpha && r > 0) score = -search(b, depth - 1, -alpha - 1, -alpha, ply + 1, true);
                if (score > alpha && score < beta) score = -search(b, depth - 1, -beta, -alpha, ply + 1, true);
            }
            b.unmake(m);
            if (stopped) return 0;

            if (score > best) {
                best = score;
                bestMove = m;
                if (score > alpha) {
                    alpha = score;
                    pvTable[ply][ply] = m;
                    int len = pvLen[ply + 1];
                    for (int j = ply + 1; j < len; j++) pvTable[ply][j] = pvTable[ply + 1][j];
                    pvLen[ply] = Math.max(len, ply + 1);
                    if (score >= beta) {
                        if (quiet) {
                            if (killers[ply][0] != m) {
                                killers[ply][1] = killers[ply][0];
                                killers[ply][0] = m;
                            }
                            int[] h = history[Board.from(m)];
                            h[Board.to(m)] += depth * depth;
                            if (h[Board.to(m)] > (1 << 20)) {
                                for (int[] row : history) for (int j = 0; j < 64; j++) row[j] >>= 1;
                            }
                        }
                        break;
                    }
                }
            }
        }

        if (legal == 0) return inCheck ? -MATE + ply : 0;
        if (best == -INF) return alpha; // 모든 수가 퓨틸리티로 잘림

        int flag = best >= beta ? TT_LOWER : best > origAlpha ? TT_EXACT : TT_UPPER;
        ttStore(b.hash, bestMove, best, depth, flag, ply);
        return best;
    }

    private int quiesce(Board b, int alpha, int beta, int ply) {
        pvLen[ply] = ply;
        if ((++nodes & 2047) == 0) checkTime();
        if (stopped) return 0;
        if (ply >= MAX_PLY) return Eval.evaluate(b);

        boolean inCheck = b.inCheck();
        int best = -INF;
        int stand = 0;
        if (!inCheck) {
            stand = Eval.evaluate(b);
            if (stand >= beta) return stand;
            if (stand > alpha) alpha = stand;
            best = stand;
        }

        int[] moves = moveBuf[ply];
        int[] order = orderBuf[ply];
        int n = b.genMoves(moves, !inCheck);
        for (int i = 0; i < n; i++) order[i] = captureOrder(b, moves[i]);
        int legal = 0;
        for (int i = 0; i < n; i++) {
            pickNext(moves, order, i, n);
            int m = moves[i];
            if (!inCheck && Board.promo(m) == 0) {
                int victim = Board.flag(m) == Board.FLAG_EP ? Board.PAWN : Math.abs(b.sq[Board.to(m)]);
                if (stand + Eval.pieceValue(victim) + 200 < alpha) continue; // 델타 가지치기
            }
            b.make(m);
            if (b.leftKingInCheck()) {
                b.unmake(m);
                continue;
            }
            legal++;
            int score = -quiesce(b, -beta, -alpha, ply + 1);
            b.unmake(m);
            if (stopped) return 0;
            if (score > best) {
                best = score;
                if (score > alpha) {
                    alpha = score;
                    pvTable[ply][ply] = m;
                    int len = pvLen[ply + 1];
                    for (int j = ply + 1; j < len; j++) pvTable[ply][j] = pvTable[ply + 1][j];
                    pvLen[ply] = Math.max(len, ply + 1);
                    if (score >= beta) return score;
                }
            }
        }
        if (inCheck && legal == 0) return -MATE + ply;
        return best == -INF ? alpha : best;
    }

    private static void pickNext(int[] moves, int[] order, int i, int n) {
        int bi = i;
        for (int j = i + 1; j < n; j++) if (order[j] > order[bi]) bi = j;
        if (bi != i) {
            int t = moves[i];
            moves[i] = moves[bi];
            moves[bi] = t;
            t = order[i];
            order[i] = order[bi];
            order[bi] = t;
        }
    }

    private int moveOrder(Board b, int m, int ttMove, int ply) {
        if (m == ttMove) return 1 << 30;
        if (b.isCapture(m) || Board.promo(m) != 0) return captureOrder(b, m);
        if (m == killers[ply][0]) return 80_000_000;
        if (m == killers[ply][1]) return 79_000_000;
        return history[Board.from(m)][Board.to(m)];
    }

    /** 잡는 수: 비싼 기물을 싼 기물로 잡는 것부터 (MVV-LVA). */
    private static int captureOrder(Board b, int m) {
        int score = 0;
        if (Board.promo(m) == Board.QUEEN) score += 90_000_000;
        if (b.isCapture(m)) {
            int victim = Board.flag(m) == Board.FLAG_EP ? Board.PAWN : Math.abs(b.sq[Board.to(m)]);
            int attacker = Math.abs(b.sq[Board.from(m)]);
            score += 100_000_000 + victim * 16 - attacker;
        }
        return score;
    }

    private void ttStore(long key, int move, int score, int depth, int flag, int ply) {
        if (score >= MATE_BOUND) score += ply;
        else if (score <= -MATE_BOUND) score -= ply;
        int idx = (int) key & ttMask;
        ttKeys[idx] = key;
        ttData[idx] = (move & 0x3FFFFL)
                | ((long) (score + 32768) << 18)
                | ((long) Math.min(255, Math.max(0, depth)) << 34)
                | ((long) flag << 42);
    }
}
