package com.chesshelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 화면 캡처 → 판 인식 → 수 추적 → 엔진 분석 → 오버레이 표시를 반복하는 포그라운드 서비스.
 *
 * <p>스레드: 메인(오버레이), 캡처 스레드(이미지 받기·인식·추적), 엔진 스레드(탐색).
 */
public class CaptureService extends Service implements OverlayController.Listener {
    static final String ACTION_START = "com.chesshelper.START";
    static final String ACTION_STOP = "com.chesshelper.STOP";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";

    private static final String TAG = "ChessHelper";
    private static final String CHANNEL = "capture";
    private static final int NOTIFY_ID = 1;
    private static final long TICK_MS = 600;

    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread capThread;
    private Handler cap;
    private ExecutorService engine;
    private final Search search = new Search(19);
    private final AtomicInteger generation = new AtomicInteger();

    private Prefs prefs;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private OverlayController overlay;
    private int screenW, screenH;

    // ---- 캡처 스레드에서만 쓰는 상태
    private Image held;
    private int[] lastScreen;
    private int[] confirmedScreen;
    private boolean forceAnalyze;
    private boolean whiteBottom = true;
    private final GameTracker tracker = new GameTracker();
    private String lastStatus;

    // ---- 여러 스레드에서 읽는 상태
    private volatile float[] rect;
    private volatile BoardDetector.Model model;
    private volatile boolean calibrating;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection != null) return START_NOT_STICKY; // 이미 실행 중
        startInForeground();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = data != null ? mpm.getMediaProjection(code, data) : null;
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        prefs = new Prefs(this);
        rect = prefs.rect();
        model = BoardDetector.Model.parse(prefs.model());
        if (model != null) whiteBottom = model.whiteBottom;

        capThread = new HandlerThread("capture");
        capThread.start();
        cap = new Handler(capThread.getLooper());
        engine = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "engine");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                main.post(CaptureService.this::stopSelf);
            }
        }, main);

        Context windowCtx = this;
        if (Build.VERSION.SDK_INT >= 30) {
            Display d = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            windowCtx = createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        WindowManager wm = windowCtx.getSystemService(WindowManager.class);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            screenW = b.width();
            screenH = b.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }
        int dpi = getResources().getDisplayMetrics().densityDpi;

        reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(r -> {
            Image img;
            try {
                img = r.acquireLatestImage();
            } catch (IllegalStateException e) {
                return;
            }
            if (img == null) return;
            if (held != null) held.close();
            held = img;
        }, cap);
        display = projection.createVirtualDisplay("ChessHelper", screenW, screenH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, cap);

        overlay = new OverlayController(windowCtx, wm, prefs, screenW, screenH, this);
        overlay.show();
        overlay.setStatus(rect == null ? "[판 맞추기]로 판 위치를 지정하세요."
                : model == null ? "게임 시작 배치가 보일 때 [학습]을 누르세요." : "판을 읽는 중…");
        cap.postDelayed(tick, 400);
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "화면 감지", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("체스 도우미 실행 중")
                .setContentText("화면의 체스판을 읽고 있어요")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "중지", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFY_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        generation.incrementAndGet();
        search.stop();
        if (overlay != null) overlay.hide();
        if (cap != null) {
            cap.removeCallbacksAndMessages(null);
            cap.post(() -> {
                if (held != null) held.close();
                held = null;
                if (display != null) display.release();
                if (reader != null) reader.close();
                capThread.quitSafely();
            });
        }
        if (engine != null) engine.shutdownNow();
        if (projection != null) projection.stop();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 캡처 스레드

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                process();
            } catch (RuntimeException e) {
                Log.e(TAG, "process", e);
            }
            cap.postDelayed(this, TICK_MS);
        }
    };

    /** 캡처한 화면을 rgb()로 읽게 해 주는 포장. */
    private static final class ImagePixels implements BoardDetector.Pixels {
        private final ByteBuffer buf;
        private final int w, h, rowStride, pixelStride;

        ImagePixels(Image img) {
            Image.Plane p = img.getPlanes()[0];
            buf = p.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
            w = img.getWidth();
            h = img.getHeight();
            rowStride = p.getRowStride();
            pixelStride = p.getPixelStride();
        }

        @Override
        public int width() {
            return w;
        }

        @Override
        public int height() {
            return h;
        }

        @Override
        public int rgb(int x, int y) {
            int v = buf.getInt(y * rowStride + x * pixelStride); // 바이트 순서 R,G,B,A
            return ((v & 0xFF) << 16) | (v & 0xFF00) | ((v >> 16) & 0xFF);
        }
    }

    private void status(String s) {
        if (s.equals(lastStatus)) return;
        lastStatus = s;
        main.post(() -> {
            if (overlay != null) overlay.setStatus(s);
        });
    }

    private void process() {
        if (calibrating || held == null) return;
        float[] r = rect;
        BoardDetector.Model m = model;
        if (r == null) {
            status("[판 맞추기]로 판 위치를 지정하세요.");
            return;
        }
        if (m == null) {
            status("게임 시작 배치가 보일 때 [학습]을 누르세요.");
            return;
        }
        boolean panelOver = Rect.intersects(overlay.panelRect, new Rect((int) r[0], (int) r[1], (int) r[2], (int) r[3]));
        BoardDetector.Frame frame = BoardDetector.read(new ImagePixels(held), r[0], r[1], r[2], r[3]);
        int[] screen = BoardDetector.classify(frame, m);
        if (screen == null) {
            lastScreen = null;
            status(panelOver ? "⚠ 패널이 판을 가리고 있어요. 제목을 끌어 판 밖으로 옮겨 주세요."
                    : "판이 보이지 않아요 (가려졌거나 판 위치가 바뀜 → [판 맞추기]).");
            return;
        }
        // 같은 판이 두 번 연속 읽혀야 확정 (기물이 움직이는 애니메이션 중간 화면 무시)
        if (!Arrays.equals(screen, lastScreen)) {
            lastScreen = screen;
            return;
        }
        if (!forceAnalyze && Arrays.equals(screen, confirmedScreen)) return;
        forceAnalyze = false;
        confirmedScreen = screen;

        boolean wb = decideOrientation(screen);
        int[] placement = BoardDetector.toBoard(screen, wb);
        String problem = Board.validatePlacement(placement);
        if (problem != null) {
            status("판을 잘못 읽은 것 같아요 (" + problem + "). " + (panelOver ? "패널을 판 밖으로 옮겨 주세요." : "필요하면 [학습]을 다시 하세요."));
            main.post(() -> {
                if (overlay != null) {
                    overlay.clearMarks();
                    overlay.setLines("");
                }
            });
            return;
        }
        int me = prefs.me();
        int guess = me == Prefs.ME_WHITE ? Board.WHITE : me == Prefs.ME_BLACK ? Board.BLACK : (wb ? Board.WHITE : Board.BLACK);
        tracker.update(placement, guess);
        analyze(r, wb);
    }

    private boolean decideOrientation(int[] screen) {
        int o = prefs.orient();
        if (o == Prefs.ORIENT_WHITE_BOTTOM) return true;
        if (o == Prefs.ORIENT_BLACK_BOTTOM) return false;
        if (Board.isStartPlacement(BoardDetector.toBoard(screen, true))) {
            whiteBottom = true;
        } else if (Board.isStartPlacement(BoardDetector.toBoard(screen, false))) {
            whiteBottom = false;
        } else {
            int s = BoardDetector.orientationScore(screen);
            if (whiteBottom && s <= -12) whiteBottom = false;
            else if (!whiteBottom && s >= 12) whiteBottom = true;
        }
        return whiteBottom;
    }

    /** 현재 판을 엔진에 맡긴다 (캡처 스레드에서 호출). */
    private void analyze(float[] r, boolean wb) {
        Board b = tracker.board();
        if (b == null) return;
        final int gen = generation.incrementAndGet();
        search.stop();
        final Board pos = b.copy();
        final String turnText = "차례: " + MoveText.sideName(pos.side) + (tracker.turnGuessed() ? "?" : "");
        final String last = tracker.lastMoves();
        int me = prefs.me();
        int mySide = me == Prefs.ME_WHITE ? Board.WHITE : me == Prefs.ME_BLACK ? Board.BLACK : -1;
        final int multiPv = prefs.multiPv();
        final int thinkMs = prefs.thinkMs();

        if (mySide >= 0 && pos.side != mySide) {
            lastStatus = null;
            main.post(() -> {
                if (overlay == null) return;
                overlay.setTurn(turnText);
                overlay.clearMarks();
                overlay.setLines("");
                overlay.setStatus("상대 차례 — 기다리는 중" + (last.isEmpty() ? "" : "  (마지막 수: " + last + ")"));
                overlay.setDetail(tracker.turnGuessed() ? "차례가 틀렸으면 위의 [차례] 칩을 누르세요." : "");
            });
            return;
        }
        main.post(() -> {
            if (overlay == null) return;
            overlay.setTurn(turnText);
            overlay.setStatus(MoveText.sideName(pos.side) + " 차례 — 생각하는 중…" + (last.isEmpty() ? "" : "  (마지막 수: " + last + ")"));
        });
        lastStatus = null;
        engine.execute(() -> {
            if (gen != generation.get()) return;
            Search.Result res = search.analyze(pos, multiPv, thinkMs, 64, partial -> {
                if (gen != generation.get()) search.stop(); // 그사이 판이 바뀜 → 이 탐색은 그만
                else if (partial.depth >= 2) show(gen, pos, r, wb, partial, last, false);
            });
            if (gen == generation.get()) show(gen, pos, r, wb, res, last, true);
        });
    }

    /** 엔진 결과를 오버레이에 그린다 (엔진 스레드에서 호출). */
    private void show(int gen, Board pos, float[] r, boolean wb, Search.Result res, String last, boolean done) {
        if (res.lines.isEmpty()) {
            String msg = res.checkmated ? "체크메이트 — " + MoveText.sideName(pos.side ^ 1) + " 승리" : "스테일메이트 (무승부)";
            main.post(() -> {
                if (overlay == null || gen != generation.get()) return;
                overlay.clearMarks();
                overlay.setLines("");
                overlay.setStatus(msg);
                overlay.setDetail("");
            });
            return;
        }
        List<Search.Line> lines = res.lines;
        int[][] marks = new int[lines.size()][];
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            Search.Line l = lines.get(i);
            marks[i] = new int[]{
                    BoardDetector.squareToScreen(Board.from(l.move), wb),
                    BoardDetector.squareToScreen(Board.to(l.move), wb)};
            if (i > 0) sb.append('\n');
            int start = sb.length();
            sb.append("● ");
            sb.setSpan(new ForegroundColorSpan(overlay.markColor(i)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int s2 = sb.length();
            sb.append(pos.san(l.move));
            sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), s2, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("  ").append(MoveText.score(l)).append("  ").append(MoveText.squares(l.move))
                    .append("  ").append(MoveText.reason(pos, l));
        }
        Search.Line best = lines.get(0);
        String detail = "예상 수순: " + MoveText.pv(pos, best.pv, 8) + "\n"
                + String.format(Locale.US, "깊이 %d · %.1f초 · %,d수 검토%s",
                res.depth, res.millis / 1000.0, res.nodes, done ? "" : " (계속 생각 중)");
        String statusText = MoveText.sideName(pos.side) + " 차례" + (last.isEmpty() ? "" : "  (마지막 수: " + last + ")");
        main.post(() -> {
            if (overlay == null || gen != generation.get()) return;
            overlay.setMarks(r, marks);
            overlay.setLines(sb);
            overlay.setDetail(detail);
            overlay.setStatus(statusText);
        });
    }

    // ---------------------------------------------------------------- 오버레이 버튼 (메인 스레드)

    @Override
    public void onCalibrateRequested() {
        calibrating = true;
        generation.incrementAndGet();
        search.stop();
        overlay.clearMarks();
        overlay.setLines("");
        overlay.setPanelVisible(false); // 패널이 판을 가리지 않게
        // 표시가 사라진 화면이 캡처될 때까지 잠깐 기다렸다가 판을 자동으로 찾는다
        cap.postDelayed(() -> {
            float[] found = held != null ? BoardLocator.locate(new ImagePixels(held)) : null;
            float[] saved = rect;
            float[] init = found != null ? found : saved != null ? saved : defaultRect();
            main.post(() -> {
                if (overlay == null) return;
                overlay.startCalibration(init);
                overlay.setStatus(found != null ? "판을 자동으로 찾았어요. 확인 후 [저장]." : "판을 못 찾았어요. 직접 맞춰 주세요.");
            });
        }, 300);
    }

    private float[] defaultRect() {
        float size = screenW * 0.96f;
        float left = (screenW - size) / 2f, top = (screenH - size) / 2f;
        return new float[]{left, top, left + size, top + size};
    }

    @Override
    public void onAutoLocateRequested() {
        overlay.setCalibrationVisible(false);
        cap.postDelayed(() -> {
            float[] found = held != null ? BoardLocator.locate(new ImagePixels(held)) : null;
            main.post(() -> {
                if (overlay == null) return;
                overlay.setCalibrationVisible(true);
                if (found != null) overlay.updateCalibration(found);
            });
        }, 300);
    }

    @Override
    public void onCalibrationDone(float[] r) {
        if (r == null) {
            cap.post(() -> {
                calibrating = false;
                forceAnalyze = true;
                confirmedScreen = null;
            });
            return;
        }
        rect = r;
        prefs.setRect(r);
        overlay.setStatus("판 위치를 저장했어요.");
        cap.post(() -> {
            calibrating = false;
            resetReading();
        });
        // 지금 화면이 시작 배치라면 바로 학습
        cap.postDelayed(() -> learn(true), 300);
    }

    @Override
    public void onLearnRequested() {
        overlay.clearMarks();
        cap.postDelayed(() -> learn(false), 300);
    }

    /** 캡처 스레드에서: 지금 화면을 시작 배치로 학습. */
    private void learn(boolean silent) {
        float[] r = rect;
        if (held == null || r == null) {
            if (!silent) status("먼저 [판 맞추기]를 해 주세요.");
            return;
        }
        BoardDetector.Model m = BoardDetector.learn(BoardDetector.read(new ImagePixels(held), r[0], r[1], r[2], r[3]));
        if (m == null) {
            if (!silent || model == null) status("시작 배치(처음 배치)가 보일 때 [학습]을 눌러 주세요.");
            return;
        }
        model = m;
        prefs.setModel(m.serialize());
        whiteBottom = m.whiteBottom;
        resetReading();
        status("학습 완료! (" + (m.whiteBottom ? "백" : "흑") + "이 아래) 이제 대국을 시작하세요.");
    }

    private void resetReading() {
        tracker.reset();
        lastScreen = null;
        confirmedScreen = null;
        forceAnalyze = true;
    }

    @Override
    public void onTurnFlipRequested() {
        cap.post(() -> {
            tracker.flipTurn();
            float[] r = rect;
            if (r != null && tracker.board() != null) analyze(r, whiteBottom);
        });
    }

    @Override
    public void onSettingsChanged() {
        cap.post(() -> {
            forceAnalyze = true;
            confirmedScreen = null;
        });
    }

    @Override
    public void onPresetLoaded(int index) {
        if (!prefs.loadPreset(index)) return;
        rect = prefs.rect();
        model = BoardDetector.Model.parse(prefs.model());
        overlay.setStatus("프리셋 " + (index + 1) + "을 불러왔어요.");
        cap.post(() -> {
            if (model != null) whiteBottom = model.whiteBottom;
            resetReading();
        });
    }

    @Override
    public void onPresetSaveRequested(int index) {
        prefs.savePreset(index, rect, prefs.model());
        overlay.setStatus("지금 판 위치와 학습을 프리셋 " + (index + 1) + "에 저장했어요.");
    }

    @Override
    public void onStopRequested() {
        stopSelf();
    }
}
