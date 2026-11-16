package com.chesshelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MoveTextTest {

    @Test
    public void describesMateCaptureAndCheck() {
        Board b = Board.fromFen("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1");
        Search.Result r = new Search(16).analyze(b, 1, 0, 3, null);
        Search.Line l = r.best();
        assertEquals("M1", MoveText.score(l));
        assertEquals("체크메이트!", MoveText.reason(b, l));
        assertEquals("a1→a8", MoveText.squares(l.move));
        assertEquals("Ra8#", MoveText.pv(b, l.pv, 6));

        // Rxd8 은 퀸을 잡으면서 동시에 백랭크 메이트
        Board q = Board.fromFen("3q2k1/5ppp/8/8/3R4/8/5PPP/6K1 w - - 0 1");
        Search.Line ql = new Search(16).analyze(q, 1, 0, 4, null).best();
        assertEquals("체크메이트! · 퀸 잡기", MoveText.reason(q, ql));

        // 퀸을 잡으며 체크 (메이트 아님)
        Board c = Board.fromFen("3q3k/6p1/7p/8/3R4/8/5PPP/6K1 w - - 0 1"); // Kh7 로 피할 수 있음
        int m = c.parseUci("d4d8");
        assertEquals("퀸 잡기 · 체크", MoveText.reason(c, new Search.Line(m, 900, new int[]{m})));
    }

    @Test
    public void quietMoveAndScoreFormat() {
        Board b = Board.fromFen(Board.START_FEN);
        Search.Line l = new Search(16).analyze(b, 1, 0, 3, null).best();
        assertTrue(MoveText.score(l).matches("[+-]\\d+\\.\\d\\d"));
        assertEquals("균형", MoveText.reason(b, l));
        assertEquals("백", MoveText.sideName(Board.WHITE));
    }
}
