package com.chesshelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class SearchTest {

    private static Search.Result analyze(String fen, int multiPv, int depth) {
        return new Search(16).analyze(Board.fromFen(fen), multiPv, 0, depth, null);
    }

    private static String bestUci(String fen, int depth) {
        return Board.uci(analyze(fen, 1, depth).best().move);
    }

    @Test
    public void mateInOneBackRank() {
        Search.Result r = analyze("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1", 1, 4);
        assertEquals("a1a8", Board.uci(r.best().move));
        assertEquals(1, r.best().mateIn());
    }

    @Test
    public void mateInTwo() {
        // 퀸 희생 뒤 비숍 메이트: 1.Qg6+ hxg6 2.Bxg6#
        Search.Result r = analyze("r2qk2r/pb4pp/1n2Pb2/2B2Q2/p1p5/2P5/2B2PPP/RN2R1K1 w - - 1 1", 1, 5);
        assertEquals("f5g6", Board.uci(r.best().move));
        assertEquals(2, r.best().mateIn());
        assertEquals(3, r.best().pv.length);
    }

    @Test
    public void blackMateInTwoWithRookSacrifice() {
        // 1...Rg1+ 2.Kxg1 Rxf1#
        Search.Result r = analyze("6k1/pp4p1/2p5/2bp4/8/P5Pb/1P3rrP/2BRRN1K b - - 0 1", 1, 5);
        assertEquals("g2g1", Board.uci(r.best().move));
        assertEquals(2, r.best().mateIn());
    }

    @Test
    public void blackFindsMateToo() {
        Search.Result r = analyze("r5k1/8/8/8/8/8/5PPP/6K1 b - - 0 1", 1, 4);
        assertEquals("a8a1", Board.uci(r.best().move));
        assertTrue(r.best().score > Search.MATE_BOUND);
    }

    @Test
    public void takesHangingQueen() {
        assertEquals("d4d8", bestUci("3q2k1/5ppp/8/8/3R4/8/5PPP/6K1 w - - 0 1", 4));
    }

    @Test
    public void knightForkWinsQueen() {
        // Nc7+ 이 킹과 룩을 동시에 공격 (포크)
        assertEquals("b5c7", bestUci("r3k3/8/8/1N6/8/8/8/4K3 w - - 0 1", 5));
    }

    @Test
    public void escapesFromMateThreat() {
        // 백 퀸이 g7 메이트를 노리는 중 → 흑은 막아야 한다
        Search.Result r = analyze("6k1/5p1p/6p1/8/8/5Q2/8/1q4K1 b - - 0 1", 1, 5);
        assertFalse("흑이 지는 수를 고르면 안 됨", r.best().score <= -Search.MATE_BOUND);
    }

    @Test
    public void multiPvReturnsDistinctSortedMoves() {
        Search.Result r = analyze(Board.START_FEN, 3, 4);
        assertEquals(3, r.lines.size());
        Set<Integer> seen = new HashSet<>();
        for (Search.Line l : r.lines) assertTrue(seen.add(l.move));
        assertTrue(r.lines.get(0).score >= r.lines.get(1).score);
        assertTrue(r.lines.get(1).score >= r.lines.get(2).score);
    }

    @Test
    public void principalVariationIsLegal() {
        Search.Result r = analyze("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3", 1, 5);
        Board b = Board.fromFen("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3");
        for (int m : r.best().pv) {
            boolean legal = false;
            for (int x : b.legalMoves()) legal |= x == m;
            assertTrue("수순에 불법 수 " + Board.uci(m), legal);
            b.make(m);
        }
    }

    @Test
    public void noMovesMeansMateOrStalemate() {
        Search.Result mated = analyze("R5k1/5ppp/8/8/8/8/5PPP/6K1 b - - 0 1", 1, 3);
        assertTrue(mated.lines.isEmpty());
        assertTrue(mated.checkmated);
        Search.Result stale = analyze("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1", 1, 3);
        assertTrue(stale.lines.isEmpty());
        assertFalse(stale.checkmated);
    }

    @Test
    public void avoidsStalematingWhenWinning() {
        // Qf7 은 스테일메이트. 이기는 쪽이 비기는 수를 두면 안 된다
        String fen = "7k/8/6K1/8/8/8/8/5Q2 w - - 0 1";
        Search.Result r = analyze(fen, 1, 5);
        assertNotEquals("f1f7", Board.uci(r.best().move));
        assertTrue(r.best().score > 500);
    }

    @Test
    public void timeLimitIsRespected() {
        Search s = new Search(16);
        long t0 = System.currentTimeMillis();
        Search.Result r = s.analyze(Board.fromFen(Board.START_FEN), 3, 300, 64, null);
        long took = System.currentTimeMillis() - t0;
        assertTrue("took " + took, took < 1500);
        assertTrue(r.depth >= 3);
    }

    @Test
    public void stopFromAnotherThread() throws Exception {
        final Search s = new Search(16);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
            s.stop();
        });
        t.start();
        long t0 = System.currentTimeMillis();
        Search.Result r = s.analyze(Board.fromFen(Board.START_FEN), 1, 0, 64, null);
        t.join();
        assertTrue(System.currentTimeMillis() - t0 < 3000);
        assertTrue(r.best() != null);
    }
}
