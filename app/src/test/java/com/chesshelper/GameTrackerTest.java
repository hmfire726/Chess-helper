package com.chesshelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GameTrackerTest {

    /** 실제 대국을 진행하면서 화면 배치만 넘겨 주는 도우미. */
    private static final class Game {
        final Board real = Board.fromFen(Board.START_FEN);

        int[] play(String... ucis) {
            for (String u : ucis) {
                int m = real.parseUci(u);
                if (m == 0) throw new IllegalArgumentException("illegal " + u);
                real.make(m);
            }
            return real.sq.clone();
        }
    }

    @Test
    public void followsOpeningMoves() {
        Game g = new Game();
        GameTracker t = new GameTracker();
        assertEquals(GameTracker.NEW_POSITION, t.update(g.play(), Board.BLACK));
        assertEquals("시작 배치는 백 차례로 확정", Board.WHITE, t.board().side);
        assertFalse(t.turnGuessed());

        assertEquals(GameTracker.MOVED, t.update(g.play("e2e4"), Board.WHITE));
        assertEquals("e4", t.lastMoves());
        assertEquals(Board.BLACK, t.board().side);
        assertEquals("앙파상 칸도 이어받음", Board.parseSquare("e3"), t.board().ep);

        assertEquals(GameTracker.SAME, t.update(g.play(), Board.WHITE));

        t.update(g.play("c7c5"), Board.WHITE);
        t.update(g.play("g1f3"), Board.WHITE);
        assertEquals(Board.BLACK, t.board().side);
        assertEquals("Nf3", t.lastMoves());
        assertEquals(g.real.toFen(), t.board().toFen());
    }

    @Test
    public void castlingAndEnPassantAndPromotion() {
        Game g = new Game();
        GameTracker t = new GameTracker();
        t.update(g.play(), 0);
        String[] moves = {"e2e4", "g8f6", "e4e5", "d7d5", "e5d6", "e7d6", "g1f3", "f8e7", "f1c4", "e8g8", "e1g1"};
        for (String u : moves) assertEquals(u, GameTracker.MOVED, t.update(g.play(u), 0));
        assertEquals(g.real.toFen(), t.board().toFen());
        assertEquals("O-O", t.lastMoves());

        Board promo = Board.fromFen("8/4P1k1/8/8/8/8/6K1/8 w - - 0 1");
        t.reset();
        t.update(promo.sq.clone(), Board.WHITE);
        promo.make(promo.parseUci("e7e8n"));
        assertEquals(GameTracker.MOVED, t.update(promo.sq.clone(), Board.WHITE));
        assertEquals("e8=N+", t.lastMoves());
        assertEquals(Board.BLACK, t.board().side);
    }

    @Test
    public void catchesUpWhenOneMoveWasMissed() {
        Game g = new Game();
        GameTracker t = new GameTracker();
        t.update(g.play(), 0);
        t.update(g.play("d2d4"), 0);
        // 상대가 바로 두어서 두 수가 한 번에 바뀜
        assertEquals(GameTracker.MOVED, t.update(g.play("d7d5", "c2c4"), 0));
        assertEquals("d5 c4", t.lastMoves());
        assertEquals(Board.BLACK, t.board().side);
    }

    @Test
    public void wrongTurnGuessIsCorrected() {
        // 중간부터 보기 시작 → 백 차례로 잘못 추정
        Board mid = Board.fromFen("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 2 2");
        GameTracker t = new GameTracker();
        t.update(mid.sq.clone(), Board.WHITE);
        assertTrue(t.turnGuessed());
        assertEquals(Board.WHITE, t.board().side);
        mid.make(mid.parseUci("g8f6")); // 실제로는 흑이 둠
        assertEquals(GameTracker.MOVED, t.update(mid.sq.clone(), Board.WHITE));
        assertFalse(t.turnGuessed());
        assertEquals(Board.WHITE, t.board().side);
        assertEquals("Nf6", t.lastMoves());
    }

    @Test
    public void sideInCheckMustBeToMove() {
        // 흑 킹이 체크 → 흑 차례일 수밖에 없다
        Board b = Board.fromFen("4k3/8/8/8/8/8/4Q3/4K3 b - - 0 1");
        GameTracker t = new GameTracker();
        t.update(b.sq.clone(), Board.WHITE);
        assertEquals(Board.BLACK, t.board().side);
        assertFalse(t.turnGuessed());
    }

    @Test
    public void takebackReturnsToEarlierPosition() {
        Game g = new Game();
        GameTracker t = new GameTracker();
        int[] start = g.play();
        t.update(start, 0);
        int[] afterE4 = g.play("e2e4");
        t.update(afterE4, 0);
        t.update(g.play("e7e5"), 0);
        assertEquals(GameTracker.TAKEBACK, t.update(afterE4, 0));
        assertEquals(Board.BLACK, t.board().side);
    }

    @Test
    public void manualFlip() {
        GameTracker t = new GameTracker();
        t.update(Board.fromFen("4k3/8/8/8/8/8/3P4/4K3 w - - 0 1").sq.clone(), Board.WHITE);
        t.flipTurn();
        assertEquals(Board.BLACK, t.board().side);
        assertFalse(t.turnGuessed());
    }
}
