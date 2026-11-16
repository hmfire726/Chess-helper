package com.chesshelper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BoardTest {

    // 널리 알려진 perft 값 (chessprogramming wiki의 표준 검증 위치)
    @Test
    public void perftStartPosition() {
        Board b = Board.fromFen(Board.START_FEN);
        assertEquals(20, b.perft(1));
        assertEquals(400, b.perft(2));
        assertEquals(8902, b.perft(3));
        assertEquals(197281, b.perft(4));
    }

    @Test
    public void perftKiwipete() {
        Board b = Board.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        assertEquals(48, b.perft(1));
        assertEquals(2039, b.perft(2));
        assertEquals(97862, b.perft(3));
        assertEquals(4085603, b.perft(4));
    }

    @Test
    public void perftEndgameWithEnPassant() {
        Board b = Board.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1");
        assertEquals(14, b.perft(1));
        assertEquals(191, b.perft(2));
        assertEquals(2812, b.perft(3));
        assertEquals(43238, b.perft(4));
    }

    @Test
    public void perftPromotionsAndChecks() {
        Board b = Board.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1");
        assertEquals(6, b.perft(1));
        assertEquals(264, b.perft(2));
        assertEquals(9467, b.perft(3));
    }

    @Test
    public void perftPosition5() {
        Board b = Board.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8");
        assertEquals(44, b.perft(1));
        assertEquals(1486, b.perft(2));
        assertEquals(62379, b.perft(3));
    }

    @Test
    public void makeUnmakeRestoresEverything() {
        Board b = Board.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        String fen = b.toFen();
        long hash = b.hash;
        for (int m : b.legalMoves()) {
            b.make(m);
            // 증분 해시가 처음부터 계산한 값과 같은지
            Board fresh = Board.fromFen(b.toFen());
            assertEquals(Board.uci(m), fresh.hash, b.hash);
            b.unmake(m);
            assertEquals(fen, b.toFen());
            assertEquals(hash, b.hash);
        }
    }

    @Test
    public void fenRoundTrip() {
        String fen = "rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2";
        assertEquals(fen, Board.fromFen(fen).toFen());
    }

    @Test
    public void sanNotation() {
        Board b = Board.fromFen(Board.START_FEN);
        assertEquals("Nf3", b.san(b.parseUci("g1f3")));
        assertEquals("e4", b.san(b.parseUci("e2e4")));

        // 같은 칸으로 갈 수 있는 룩 두 개 → 파일로 구분
        Board r = Board.fromFen("4k3/8/8/8/8/8/4K3/R6R w - - 0 1");
        assertEquals("Rad1", r.san(r.parseUci("a1d1")));
        assertEquals("Rhd1", r.san(r.parseUci("h1d1")));
        Board c = Board.fromFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1");
        assertEquals("O-O", c.san(c.parseUci("e1g1")));
        assertEquals("O-O-O", c.san(c.parseUci("e1c1")));

        Board ep = Board.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2");
        assertEquals("exd6", ep.san(ep.parseUci("e5d6")));

        Board promo = Board.fromFen("8/4P2k/8/8/8/8/8/4K3 w - - 0 1");
        assertEquals("e8=Q", promo.san(promo.parseUci("e7e8q")));

        Board mate = Board.fromFen("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1");
        assertEquals("Ra8#", mate.san(mate.parseUci("a1a8")));
    }

    @Test
    public void repetitionDetected() {
        Board b = Board.fromFen(Board.START_FEN);
        String[] moves = {"g1f3", "g8f6", "f3g1", "f6g8"};
        for (String u : moves) b.make(b.parseUci(u));
        assertTrue(b.isRepetition());
        Board c = b.copy();
        assertTrue("복사본도 반복 기록을 유지", c.isRepetition());
    }

    @Test
    public void placementFromScreen() {
        int[] start = Board.startPlacement();
        assertTrue(Board.isStartPlacement(start));
        assertNull(Board.validatePlacement(start));
        Board b = Board.fromPlacement(start, Board.WHITE);
        assertEquals(15, b.castle);
        assertEquals(Board.fromFen(Board.START_FEN).hash, b.hash);

        int[] twoKings = start.clone();
        twoKings[20] = Board.KING;
        assertNotEquals(null, Board.validatePlacement(twoKings));

        int[] noCastle = start.clone();
        noCastle[7] = 0;
        assertEquals(Board.CASTLE_WQ | Board.CASTLE_BK | Board.CASTLE_BQ, Board.fromPlacement(noCastle, 0).castle);
        assertFalse(Board.isStartPlacement(noCastle));
        assertArrayEquals(start, Board.fromFen(Board.START_FEN).sq);
    }

    @Test
    public void insufficientMaterial() {
        assertTrue(Board.fromFen("8/8/4k3/8/8/2N5/4K3/8 w - - 0 1").insufficientMaterial());
        assertFalse(Board.fromFen("8/8/4k3/8/8/2R5/4K3/8 w - - 0 1").insufficientMaterial());
    }
}
