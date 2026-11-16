package com.chesshelper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BoardDetectorTest {
    // 1080×2160 세로 화면, 판은 가로 전체에 가깝게
    static final int W = 1080, H = 2160;
    static final int LEFT = 20, TOP = 560, SIZE = 1040;
    static final int SCREEN_BG = FakeScreen.rgb(49, 46, 43);

    static final String MIDGAME = "r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP3PPP/R2QKB1R w KQ - 2 8";

    private static FakeScreen screen(int[] placement, boolean whiteBottom, int... highlight) {
        FakeScreen s = new FakeScreen(W, H, SCREEN_BG);
        // 판 위아래 UI 흉내 (플레이어 이름 칸 등)
        s.fill(40, 420, 600, 500, FakeScreen.rgb(80, 80, 80));
        s.fill(40, 1660, 700, 1740, FakeScreen.rgb(200, 200, 200));
        s.drawBoard(LEFT, TOP, SIZE, placement, whiteBottom, highlight);
        return s;
    }

    private static BoardDetector.Frame read(FakeScreen s, float dl, float dt, float dr, float db) {
        return BoardDetector.read(s, LEFT + dl, TOP + dt, LEFT + SIZE + dr, TOP + SIZE + db);
    }

    private static BoardDetector.Model learn(boolean whiteBottom) {
        BoardDetector.Model m = BoardDetector.learn(read(screen(Board.startPlacement(), whiteBottom), 0, 0, 0, 0));
        assertNotNull("시작 배치에서 학습 실패", m);
        assertEquals(whiteBottom, m.whiteBottom);
        return m;
    }

    @Test
    public void learnsAndReadsStartPosition() {
        BoardDetector.Model m = learn(true);
        int[] screen = BoardDetector.classify(read(screen(Board.startPlacement(), true), 0, 0, 0, 0), m);
        assertNotNull(screen);
        assertArrayEquals(Board.startPlacement(), BoardDetector.toBoard(screen, true));
    }

    @Test
    public void readsMiddlegameWithHighlightsAndMarks() {
        BoardDetector.Model m = learn(true);
        int[] placement = Board.fromFen(MIDGAME).sq;
        FakeScreen s = screen(placement, true, Board.parseSquare("c2"), Board.parseSquare("c4"));
        // 우리 앱이 그린 추천 테두리가 화면 캡처에 같이 찍혀도 인식이 바뀌면 안 된다
        s.drawMarkFrame(LEFT, TOP, SIZE, BoardDetector.squareToScreen(Board.parseSquare("c4"), true));
        s.drawMarkFrame(LEFT, TOP, SIZE, BoardDetector.squareToScreen(Board.parseSquare("d5"), true));
        s.drawMarkFrame(LEFT, TOP, SIZE, BoardDetector.squareToScreen(Board.parseSquare("e4"), true));
        int[] screen = BoardDetector.classify(read(s, 0, 0, 0, 0), m);
        assertNotNull(screen);
        assertArrayEquals(placement, BoardDetector.toBoard(screen, true));
    }

    @Test
    public void queenAndKingOnOtherSquareColors() {
        // 시작 배치에서 백 퀸은 밝은 칸(d1)에만 있었다 → 어두운 칸 위의 퀸도 알아봐야 한다
        BoardDetector.Model m = learn(true);
        int[] placement = Board.fromFen("3k4/8/8/2q5/3Q4/8/8/4K3 w - - 0 1").sq;
        int[] screen = BoardDetector.classify(read(screen(placement, true), 0, 0, 0, 0), m);
        assertNotNull(screen);
        assertArrayEquals(placement, BoardDetector.toBoard(screen, true));
    }

    @Test
    public void flippedBoardWhenPlayingBlack() {
        BoardDetector.Model m = learn(false);
        int[] placement = Board.fromFen(MIDGAME).sq;
        int[] screen = BoardDetector.classify(read(screen(placement, false), 0, 0, 0, 0), m);
        assertNotNull(screen);
        assertArrayEquals(placement, BoardDetector.toBoard(screen, false));
        assertTrue("흑이 아래인 화면", BoardDetector.orientationScore(screen) < 0);
    }

    @Test
    public void toleratesSlightlyOffCalibration() {
        BoardDetector.Model m = learn(true);
        int[] placement = Board.fromFen(MIDGAME).sq;
        FakeScreen s = screen(placement, true);
        int[] screen = BoardDetector.classify(read(s, 4, -3, -2, 4), m);
        assertNotNull(screen);
        assertArrayEquals(placement, BoardDetector.toBoard(screen, true));
    }

    @Test
    public void refusesToLearnFromNonStartPosition() {
        assertNull(BoardDetector.learn(read(screen(Board.fromFen(MIDGAME).sq, true), 0, 0, 0, 0)));
    }

    @Test
    public void coveredBoardIsNotReadAsPosition() {
        BoardDetector.Model m = learn(true);
        FakeScreen s = screen(Board.fromFen(MIDGAME).sq, true);
        s.fill(100, 800, 980, 1300, FakeScreen.rgb(255, 255, 255)); // 팝업이 판을 가림
        int[] screen = BoardDetector.classify(read(s, 0, 0, 0, 0), m);
        assertTrue(screen == null || Board.validatePlacement(BoardDetector.toBoard(screen, true)) != null);
    }

    @Test
    public void modelSurvivesSaveAndLoad() {
        BoardDetector.Model m = learn(false);
        BoardDetector.Model back = BoardDetector.Model.parse(m.serialize());
        assertNotNull(back);
        assertFalse(back.whiteBottom);
        assertArrayEquals(m.codes, back.codes);
        for (int i = 0; i < m.codes.length; i++) assertArrayEquals(m.feats[i], back.feats[i]);
        assertNull(BoardDetector.Model.parse("garbage"));
    }

    @Test
    public void screenSquareMappingRoundTrips() {
        for (int wb = 0; wb < 2; wb++) {
            for (int i = 0; i < 64; i++) {
                assertEquals(i, BoardDetector.squareToScreen(BoardDetector.screenToSquare(i, wb == 1), wb == 1));
            }
        }
        // 백이 아래면 화면 왼쪽 아래가 a1, 흑이 아래면 화면 왼쪽 아래가 h8
        assertEquals(0, BoardDetector.screenToSquare(56, true));
        assertEquals(63, BoardDetector.screenToSquare(56, false));
    }

    @Test
    public void locatesBoardAutomatically() {
        FakeScreen s = screen(Board.fromFen(MIDGAME).sq, true, Board.parseSquare("c2"));
        long t0 = System.currentTimeMillis();
        float[] r = BoardLocator.locate(s);
        long took = System.currentTimeMillis() - t0;
        assertNotNull(r);
        float tol = SIZE / 8f * 0.04f; // 칸 크기의 4% 이내
        assertEquals(LEFT, r[0], tol);
        assertEquals(TOP, r[1], tol);
        assertEquals(LEFT + SIZE, r[2], tol);
        assertEquals(TOP + SIZE, r[3], tol);
        System.out.println("locate: " + took + "ms → " + java.util.Arrays.toString(r));
    }

    @Test
    public void locatesSmallerOffCenterBoard() {
        FakeScreen s = new FakeScreen(W, H, SCREEN_BG);
        s.drawBoard(130, 700, 800, Board.startPlacement(), false);
        float[] r = BoardLocator.locate(s);
        assertNotNull(r);
        float tol = 800 / 8f * 0.04f;
        assertEquals(130, r[0], tol);
        assertEquals(700, r[1], tol);
        assertEquals(930, r[2], tol);
        assertEquals(1500, r[3], tol);
    }

    @Test
    public void noBoardOnScreen() {
        FakeScreen s = new FakeScreen(W, H, SCREEN_BG);
        s.fill(100, 300, 900, 900, FakeScreen.rgb(30, 90, 160));
        assertNull(BoardLocator.locate(s));
    }
}
