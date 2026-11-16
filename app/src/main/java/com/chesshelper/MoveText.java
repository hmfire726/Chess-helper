package com.chesshelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 추천 수를 사람이 읽기 좋은 글로 바꾼다. (예: "Nxe5  +1.30  나이트 잡기 · 체크") */
public final class MoveText {
    private MoveText() {
    }

    static final String[] PIECE_KO = {"", "폰", "나이트", "비숍", "룩", "퀸", "킹"};

    public static String sideName(int side) {
        return side == Board.WHITE ? "백" : "흑";
    }

    /** 둘 차례인 쪽 기준 점수. 메이트는 M3 / -M2 처럼. */
    public static String score(Search.Line l) {
        int mate = l.mateIn();
        if (mate > 0) return "M" + mate;
        if (mate < 0) return "-M" + (-mate);
        return String.format(Locale.US, "%+.2f", l.score / 100.0);
    }

    /** 이 수를 추천하는 이유 (짧은 꼬리표들). */
    public static String reason(Board b, Search.Line l) {
        int m = l.move;
        List<String> tags = new ArrayList<>();
        int mate = l.mateIn();
        if (mate == 1) tags.add("체크메이트!");
        else if (mate > 1) tags.add("메이트 " + mate + "수");
        else if (mate < 0) tags.add("어떻게 둬도 " + (-mate) + "수 뒤 메이트");

        if (Board.flag(m) == Board.FLAG_CASTLE) tags.add("캐슬링");
        if (b.isCapture(m)) {
            int victim = Board.flag(m) == Board.FLAG_EP ? Board.PAWN : Math.abs(b.sq[Board.to(m)]);
            tags.add(PIECE_KO[victim] + " 잡기" + (Board.flag(m) == Board.FLAG_EP ? "(앙파상)" : ""));
        }
        if (Board.promo(m) != 0) tags.add("승진→" + PIECE_KO[Board.promo(m)]);
        if (mate != 1) {
            Board c = b.copy();
            c.make(m);
            if (c.inCheck()) tags.add("체크");
        }
        if (tags.isEmpty()) {
            if (l.score >= 150) tags.add("우세 유지");
            else if (l.score <= -150) tags.add("최선의 방어");
            else tags.add("균형");
        }
        return String.join(" · ", tags);
    }

    /** 출발→도착 칸 (예: g1→f3). */
    public static String squares(int m) {
        return Board.squareName(Board.from(m)) + "→" + Board.squareName(Board.to(m));
    }

    /** 예상 수순을 SAN으로 (최대 max수). */
    public static String pv(Board b, int[] pv, int max) {
        Board c = b.copy();
        StringBuilder sb = new StringBuilder();
        int n = Math.min(max, pv.length);
        for (int i = 0; i < n; i++) {
            int m = pv[i];
            boolean legal = false;
            for (int x : c.legalMoves()) legal |= x == m;
            if (!legal) break;
            if (sb.length() > 0) sb.append(' ');
            sb.append(c.san(m));
            c.make(m);
        }
        if (pv.length > n) sb.append(" …");
        return sb.toString();
    }
}
