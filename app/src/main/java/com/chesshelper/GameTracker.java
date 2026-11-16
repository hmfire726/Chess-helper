package com.chesshelper;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;

/**
 * 화면에서 읽은 기물 배치가 바뀔 때마다 "어떤 수가 두어졌는지" 찾아서 대국을 따라간다.
 *
 * <p>체스는 오목과 달리 돌 개수로 차례를 알 수 없으므로, 직전 판에서 합법수 1~2개로 새 판이 되는지
 * 찾아서 차례·캐슬링 권리·앙파상을 이어 간다. 찾지 못하면 새 판으로 보고 차례를 추정한다.
 */
public final class GameTracker {
    public static final int SAME = 0, MOVED = 1, NEW_POSITION = 2, TAKEBACK = 3;

    private static final int MAX_PAST = 40;

    private Board board;
    private boolean turnGuessed;
    private String lastMoves = "";
    /** 무르기(되돌리기)를 알아보기 위한 이전 판들. 맨 앞이 가장 최근. */
    private final ArrayDeque<Board> past = new ArrayDeque<>();

    public Board board() {
        return board;
    }

    /** 차례를 추정으로 정했으면 true (사용자가 고칠 수 있게 표시). */
    public boolean turnGuessed() {
        return turnGuessed;
    }

    /** 마지막으로 찾아낸 수 (예: "e4" 또는 "Nf3 Nc6"), 없으면 빈 문자열. */
    public String lastMoves() {
        return lastMoves;
    }

    public void reset() {
        board = null;
        turnGuessed = false;
        lastMoves = "";
        past.clear();
    }

    /** 사용자가 차례를 직접 바꿈. */
    public void flipTurn() {
        if (board == null) return;
        Board nb = board.copy();
        nb.forceSide(board.side ^ 1);
        board = nb;
        turnGuessed = false;
        lastMoves = "";
        past.clear();
    }

    /**
     * @param placement 화면에서 읽은 배치 (a1=0 … h8=63, 이미 {@link Board#validatePlacement}로 확인된 것)
     * @param guessSide 처음 보는 판일 때 추정할 차례
     */
    public int update(int[] placement, int guessSide) {
        if (board != null && Arrays.equals(board.sq, placement)) return SAME;

        if (board != null) {
            int[] path = findPath(board, placement);
            if (path != null) {
                apply(board, path);
                return MOVED;
            }
            if (turnGuessed) {
                // 차례 추정이 틀렸을 수도 있다 → 반대쪽이 둔 것으로 다시 찾기
                Board flipped = board.copy();
                flipped.forceSide(board.side ^ 1);
                int[] p2 = findPath(flipped, placement);
                if (p2 != null) {
                    apply(flipped, p2);
                    turnGuessed = false;
                    return MOVED;
                }
            }
            for (Iterator<Board> it = past.iterator(); it.hasNext(); ) {
                Board old = it.next();
                if (Arrays.equals(old.sq, placement)) {
                    // 무르기: 그 판으로 돌아간다 (그보다 최근 판들은 버림)
                    while (past.peekFirst() != old) past.pollFirst();
                    past.pollFirst();
                    board = old;
                    lastMoves = "";
                    return TAKEBACK;
                }
            }
        }

        boolean start = Board.isStartPlacement(placement);
        int side = start ? Board.WHITE : guessSide;
        Board nb = Board.fromPlacement(placement, side);
        // 차례가 아닌 쪽 킹이 체크 상태일 수는 없다 → 체크당한 쪽의 차례
        if (nb.isAttacked(nb.kingSq[side ^ 1], side)) {
            side ^= 1;
            nb = Board.fromPlacement(placement, side);
            turnGuessed = false;
        } else {
            turnGuessed = !start;
        }
        board = nb;
        lastMoves = "";
        past.clear();
        return NEW_POSITION;
    }

    private void apply(Board from, int[] path) {
        Board b = from.copy();
        StringBuilder sb = new StringBuilder();
        for (int m : path) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(b.san(m));
            b.make(m);
        }
        past.addFirst(board);
        while (past.size() > MAX_PAST) past.pollLast();
        board = b.copy();
        lastMoves = sb.toString();
    }

    /** 합법수 1개 또는 2개(한 수를 놓친 경우)로 target 배치가 되는 수순. 없으면 null. */
    static int[] findPath(Board b0, int[] target) {
        Board b = b0.copy();
        int[] first = b.legalMoves();
        for (int m : first) {
            b.make(m);
            boolean ok = Arrays.equals(b.sq, target);
            b.unmake(m);
            if (ok) return new int[]{m};
        }
        // 두 수 차이: 바뀐 칸이 너무 많으면 볼 필요 없음
        int diff = 0;
        for (int s = 0; s < 64; s++) if (b.sq[s] != target[s]) diff++;
        if (diff > 8) return null;
        for (int m : first) {
            b.make(m);
            for (int m2 : b.legalMoves()) {
                b.make(m2);
                boolean ok = Arrays.equals(b.sq, target);
                b.unmake(m2);
                if (ok) {
                    b.unmake(m);
                    return new int[]{m, m2};
                }
            }
            b.unmake(m);
        }
        return null;
    }
}
