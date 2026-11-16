package com.chesshelper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 게임 화면 위에 띄우는 창들 (메인 스레드에서만 사용).
 * <ul>
 *   <li>추천 표시 층: 화면 전체, 터치는 그대로 게임으로 통과. 추천 칸에 색 테두리를 그린다.</li>
 *   <li>조작 패널: 끌어서 옮길 수 있는 작은 창. 추천 수·이유·설정.</li>
 *   <li>판 맞추기 화면: 판의 두 모서리를 맞추는 전체 화면 창.</li>
 * </ul>
 */
final class OverlayController {

    interface Listener {
        void onCalibrateRequested();

        void onAutoLocateRequested();

        /** @param rect 화면 좌표 {left, top, right, bottom}, 취소면 null */
        void onCalibrationDone(float[] rect);

        void onLearnRequested();

        void onTurnFlipRequested();

        void onSettingsChanged();

        void onPresetLoaded(int index);

        void onPresetSaveRequested(int index);

        void onStopRequested();
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Prefs prefs;
    private final Listener listener;
    private final int screenW, screenH;

    private MarkerView markers;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView meChip, turnChip, statusView, linesView, detailView, collapseBtn;
    private LinearLayout body, settingsBox;
    private boolean collapsed, settingsOpen;

    private FrameLayout calibRoot;
    private CalibrationView calibView;

    private CharSequence lines = "";
    private String status = "", detail = "", turn = "차례: ?";

    /** 패널이 화면에서 차지하는 영역 (캡처 스레드에서 읽음). */
    volatile Rect panelRect = new Rect();

    OverlayController(Context ctx, WindowManager wm, Prefs prefs, int screenW, int screenH, Listener listener) {
        this.ctx = ctx;
        this.wm = wm;
        this.prefs = prefs;
        this.screenW = screenW;
        this.screenH = screenH;
        this.listener = listener;
    }

    // ---------------------------------------------------------------- 창 만들기 / 없애기

    private static int overlayType() {
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    private static void allowCutout(WindowManager.LayoutParams lp) {
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    void show() {
        markers = new MarkerView(ctx);
        WindowManager.LayoutParams mlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        // 안드로이드 12부터는 불투명도 0.8 이하인 창만 터치를 아래 앱으로 넘겨 준다
        mlp.alpha = 0.8f;
        mlp.gravity = Gravity.TOP | Gravity.START;
        allowCutout(mlp);
        wm.addView(markers, mlp);

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = prefs.panelX() >= 0 ? prefs.panelX() : Ui.dp(ctx, 8);
        panelLp.y = prefs.panelY() >= 0 ? prefs.panelY() : Ui.dp(ctx, 40);
        buildPanel();
        wm.addView(panel, panelLp);
    }

    void hide() {
        removeQuietly(markers);
        removeQuietly(panel);
        removeQuietly(calibRoot);
        markers = null;
        panel = null;
        calibRoot = null;
    }

    private void removeQuietly(View v) {
        if (v == null) return;
        try {
            wm.removeView(v);
        } catch (RuntimeException ignored) {
            // 이미 없어진 창
        }
    }

    // ---------------------------------------------------------------- 표시 내용

    void setStatus(String s) {
        status = s;
        if (statusView != null) {
            statusView.setText(s);
            statusView.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    void setLines(CharSequence s) {
        lines = s;
        if (linesView != null) {
            linesView.setText(s);
            linesView.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
        }
    }

    void setDetail(String s) {
        detail = s;
        if (detailView != null) {
            detailView.setText(s);
            detailView.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    void setTurn(String s) {
        turn = s;
        if (turnChip != null) turnChip.setText(s);
    }

    /** @param marks 각 원소 {출발 화면칸, 도착 화면칸}, 순위 순서 */
    void setMarks(float[] rect, int[][] marks) {
        if (markers == null) return;
        markers.rect = rect;
        markers.marks = marks;
        markers.invalidate();
    }

    void clearMarks() {
        setMarks(null, null);
    }

    int markColor(int rank) {
        return Themes.markColor(prefs.markTheme(), rank);
    }

    // ---------------------------------------------------------------- 패널

    private int[] colors() {
        return Themes.panel(prefs.panelTheme());
    }

    private float textSp() {
        return Themes.TEXT_SP[Math.max(0, Math.min(2, prefs.markSize()))];
    }

    @SuppressLint("ClickableViewAccessibility")
    private void buildPanel() {
        int[] c = colors();
        float sp = textSp();
        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 10);
        panel.setPadding(pad, Ui.dp(ctx, 8), pad, Ui.dp(ctx, 8));
        panel.setBackground(Ui.round(c[0], Ui.dp(ctx, 14)));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(ctx, "♞ 체스 도우미", sp + 1, c[1], true);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        meChip = Ui.button(ctx, meLabel(), sp - 1, c[1], c[3], v -> {
            prefs.setMe((prefs.me() + 1) % 3);
            meChip.setText(meLabel());
            listener.onSettingsChanged();
        });
        header.addView(meChip, chipLp());
        turnChip = Ui.button(ctx, turn, sp - 1, c[1], c[3], v -> listener.onTurnFlipRequested());
        header.addView(turnChip, chipLp());
        collapseBtn = Ui.button(ctx, collapsed ? "+" : "–", sp, c[1], c[3], v -> {
            collapsed = !collapsed;
            collapseBtn.setText(collapsed ? "+" : "–");
            body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            panel.setMinimumWidth(collapsed ? 0 : panelWidth());
        });
        header.addView(collapseBtn, chipLp());
        panel.addView(header);

        // 제목을 끌어서 패널 옮기기
        View.OnTouchListener drag = new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        ox = panelLp.x;
                        oy = panelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLp.x = Math.max(0, Math.min(screenW - panel.getWidth(), ox + Math.round(e.getRawX() - sx)));
                        panelLp.y = Math.max(0, Math.min(screenH - Ui.dp(ctx, 40), oy + Math.round(e.getRawY() - sy)));
                        wm.updateViewLayout(panel, panelLp);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        prefs.setPanelPos(panelLp.x, panelLp.y);
                        return true;
                    default:
                        return false;
                }
            }
        };
        title.setOnTouchListener(drag);

        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        statusView = Ui.text(ctx, status, sp - 1, c[2], false);
        linesView = Ui.text(ctx, "", sp + 1, c[1], false);
        linesView.setLineSpacing(0, 1.1f);
        detailView = Ui.text(ctx, detail, sp - 2, c[2], false);
        body.addView(statusView);
        body.addView(linesView);
        body.addView(detailView);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(Ui.button(ctx, "판 맞추기", sp - 1, c[1], c[3], v -> listener.onCalibrateRequested()), Ui.weighted(ctx, 2));
        buttons.addView(Ui.button(ctx, "학습", sp - 1, c[1], c[3], v -> listener.onLearnRequested()), Ui.weighted(ctx, 2));
        buttons.addView(Ui.button(ctx, "설정", sp - 1, c[1], c[3], v -> {
            settingsOpen = !settingsOpen;
            settingsBox.setVisibility(settingsOpen ? View.VISIBLE : View.GONE);
        }), Ui.weighted(ctx, 2));
        buttons.addView(Ui.button(ctx, "종료", sp - 1, c[1], c[3], v -> listener.onStopRequested()), Ui.weighted(ctx, 2));
        body.addView(buttons);

        settingsBox = buildSettings(c, sp);
        settingsBox.setVisibility(settingsOpen ? View.VISIBLE : View.GONE);
        body.addView(settingsBox);
        panel.addView(body);

        setStatus(status);
        setLines(lines);
        setDetail(detail);

        panel.setMinimumWidth(collapsed ? 0 : panelWidth());
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updatePanelRect());
    }

    private int panelWidth() {
        return Math.min(screenW - Ui.dp(ctx, 16), Ui.dp(ctx, 380));
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(ctx, 4), 0, 0, 0);
        return lp;
    }

    private void updatePanelRect() {
        if (panel == null) return;
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        panelRect = new Rect(loc[0], loc[1], loc[0] + panel.getWidth(), loc[1] + panel.getHeight());
    }

    private String meLabel() {
        switch (prefs.me()) {
            case Prefs.ME_WHITE: return "나: 백";
            case Prefs.ME_BLACK: return "나: 흑";
            default: return "나: 자동";
        }
    }

    private LinearLayout buildSettings(int[] c, float sp) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        int[] seg = {c[1], c[3], c[4]};
        float s = sp - 2;

        addRow(box, "내 색", new Ui.Segmented(ctx, new String[]{"자동", "백", "흑"}, prefs.me(), s, seg, i -> {
            prefs.setMe(i);
            meChip.setText(meLabel());
            listener.onSettingsChanged();
        }).row, c, s);
        addRow(box, "판 방향", new Ui.Segmented(ctx, new String[]{"자동", "백이 아래", "흑이 아래"}, prefs.orient(), s, seg, i -> {
            prefs.setOrient(i);
            listener.onSettingsChanged();
        }).row, c, s);
        addRow(box, "추천 개수", new Ui.Segmented(ctx, new String[]{"1개", "3개", "5개"},
                Ui.indexOf(Prefs.MULTI_PV, prefs.multiPv(), 1), s, seg, i -> {
            prefs.setMultiPv(Prefs.MULTI_PV[i]);
            listener.onSettingsChanged();
        }).row, c, s);
        addRow(box, "생각 시간", new Ui.Segmented(ctx, new String[]{"0.5초", "1.5초", "3초"},
                Ui.indexOf(Prefs.THINK_MS, prefs.thinkMs(), 1), s, seg, i -> {
            prefs.setThinkMs(Prefs.THINK_MS[i]);
            listener.onSettingsChanged();
        }).row, c, s);

        // 판 프리셋: 저장 / 불러오기
        LinearLayout save = new LinearLayout(ctx);
        LinearLayout load = new LinearLayout(ctx);
        TextView[] loadBtns = new TextView[Prefs.PRESETS];
        for (int i = 0; i < Prefs.PRESETS; i++) {
            final int idx = i;
            save.addView(Ui.button(ctx, "저장 " + (i + 1), s, c[1], c[3], v -> {
                listener.onPresetSaveRequested(idx);
                loadBtns[idx].setEnabled(true);
                loadBtns[idx].setAlpha(1f);
            }), Ui.weighted(ctx, 2));
            loadBtns[i] = Ui.button(ctx, "불러오기 " + (i + 1), s, c[1], c[3], v -> listener.onPresetLoaded(idx));
            boolean has = prefs.hasPreset(i);
            loadBtns[i].setEnabled(has);
            loadBtns[i].setAlpha(has ? 1f : 0.35f);
            load.addView(loadBtns[i], Ui.weighted(ctx, 2));
        }
        addRow(box, "판 프리셋", save, c, s);
        addRow(box, "", load, c, s);

        addRow(box, "표시 크기", new Ui.Segmented(ctx, Themes.SIZE_NAMES, prefs.markSize(), s, seg, i -> {
            prefs.setMarkSize(i);
            rebuildPanel();
            if (markers != null) markers.invalidate();
        }).row, c, s);

        LinearLayout colorRow = new LinearLayout(ctx);
        colorRow.addView(Ui.button(ctx, "추천 색: " + Themes.MARK_NAMES[Math.floorMod(prefs.markTheme(), Themes.MARK.length)] + " ▶",
                s, c[1], c[3], v -> {
                    prefs.setMarkTheme((prefs.markTheme() + 1) % Themes.MARK.length);
                    rebuildPanel();
                    listener.onSettingsChanged();
                }), Ui.weighted(ctx, 2));
        colorRow.addView(Ui.button(ctx, "패널 색: " + Themes.PANEL_NAMES[Math.floorMod(prefs.panelTheme(), Themes.PANEL.length)] + " ▶",
                s, c[1], c[3], v -> {
                    prefs.setPanelTheme((prefs.panelTheme() + 1) % Themes.PANEL.length);
                    rebuildPanel();
                }), Ui.weighted(ctx, 2));
        addRow(box, "색", colorRow, c, s);
        return box;
    }

    private void addRow(LinearLayout box, String label, View content, int[] c, float sp) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(ctx, label, sp, c[2], false);
        row.addView(t, new LinearLayout.LayoutParams(Ui.dp(ctx, 64), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(content, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);
    }

    /** 색·글자 크기를 바꿨을 때 패널을 다시 만든다. */
    private void rebuildPanel() {
        if (panel == null) return;
        removeQuietly(panel);
        buildPanel();
        wm.addView(panel, panelLp);
    }

    void setPanelVisible(boolean visible) {
        if (panel != null) panel.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- 판 맞추기

    boolean isCalibrating() {
        return calibRoot != null;
    }

    /** @param initial 처음 보여 줄 판 위치 (화면 좌표) */
    void startCalibration(float[] initial) {
        if (calibRoot != null) return;
        clearMarks();
        if (panel != null) panel.setVisibility(View.GONE);
        int[] c = colors();
        calibRoot = new FrameLayout(ctx);
        calibView = new CalibrationView(ctx, initial.clone());
        calibRoot.addView(calibView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 10);
        bar.setPadding(pad, pad, pad, pad);
        bar.setBackground(Ui.round(c[0], Ui.dp(ctx, 14)));
        bar.addView(Ui.text(ctx, "판의 왼쪽 위 모서리와 오른쪽 아래 모서리에 점을 맞추세요.\n"
                + "점 근처를 끌면 그 점이, 판 가운데를 끌면 전체가 움직입니다. 화살표로 1픽셀씩 조절.", 13, c[1], false));
        LinearLayout nudge = new LinearLayout(ctx);
        nudge.addView(Ui.button(ctx, "◀", 14, c[1], c[3], v -> calibView.nudge(-1, 0)), Ui.weighted(ctx, 2));
        nudge.addView(Ui.button(ctx, "▲", 14, c[1], c[3], v -> calibView.nudge(0, -1)), Ui.weighted(ctx, 2));
        nudge.addView(Ui.button(ctx, "▼", 14, c[1], c[3], v -> calibView.nudge(0, 1)), Ui.weighted(ctx, 2));
        nudge.addView(Ui.button(ctx, "▶", 14, c[1], c[3], v -> calibView.nudge(1, 0)), Ui.weighted(ctx, 2));
        bar.addView(nudge);
        LinearLayout actions = new LinearLayout(ctx);
        actions.addView(Ui.button(ctx, "자동 찾기", 14, c[1], c[3], v -> listener.onAutoLocateRequested()), Ui.weighted(ctx, 2));
        actions.addView(Ui.button(ctx, "취소", 14, c[1], c[3], v -> finishCalibration(false)), Ui.weighted(ctx, 2));
        actions.addView(Ui.button(ctx, "저장", 14, 0xFFFFFFFF, c[4], v -> finishCalibration(true)), Ui.weighted(ctx, 2));
        bar.addView(actions);

        boolean boardLow = (initial[1] + initial[3]) / 2 > screenH / 2f;
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                boardLow ? Gravity.TOP : Gravity.BOTTOM);
        int m = Ui.dp(ctx, 8);
        blp.setMargins(m, Ui.dp(ctx, 32), m, Ui.dp(ctx, 32));
        calibRoot.addView(bar, blp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        allowCutout(lp);
        wm.addView(calibRoot, lp);
    }

    /** 자동 찾기 결과로 점을 옮긴다. */
    void updateCalibration(float[] rect) {
        if (calibView != null && rect != null) calibView.setScreenRect(rect);
    }

    /** 자동 찾기 중에는 우리 격자선이 캡처에 찍히지 않게 잠시 숨긴다. */
    void setCalibrationVisible(boolean visible) {
        if (calibRoot != null) calibRoot.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    private void finishCalibration(boolean save) {
        float[] r = save && calibView != null ? calibView.screenRect() : null;
        removeQuietly(calibRoot);
        calibRoot = null;
        calibView = null;
        if (panel != null) panel.setVisibility(View.VISIBLE);
        listener.onCalibrationDone(r);
    }

    // ---------------------------------------------------------------- 그리기용 뷰

    /** 추천 칸 테두리. 칸 바깥쪽 띠(6%) 안에만 그려서 기물 인식을 방해하지 않는다. */
    private final class MarkerView extends View {
        float[] rect;
        int[][] marks;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] loc = new int[2];

        MarkerView(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.SQUARE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (rect == null || marks == null) return;
            getLocationOnScreen(loc);
            canvas.save();
            canvas.translate(-loc[0], -loc[1]);
            float sw = (rect[2] - rect[0]) / 8f, sh = (rect[3] - rect[1]) / 8f;
            float sq = Math.min(sw, sh);
            float base = Themes.STROKE[Math.max(0, Math.min(2, prefs.markSize()))] * sq;
            for (int i = marks.length - 1; i >= 0; i--) {
                float stroke = i == 0 ? base : base * 0.7f;
                paint.setStrokeWidth(stroke);
                paint.setColor(markColor(i));
                float inset = 0.01f * sq + stroke / 2f;
                RectF to = cell(marks[i][1], sw, sh, inset);
                canvas.drawRect(to, paint);
                RectF from = cell(marks[i][0], sw, sh, inset);
                float len = 0.28f * sq;
                canvas.drawLine(from.left, from.top, from.left + len, from.top, paint);
                canvas.drawLine(from.left, from.top, from.left, from.top + len, paint);
                canvas.drawLine(from.right, from.top, from.right - len, from.top, paint);
                canvas.drawLine(from.right, from.top, from.right, from.top + len, paint);
                canvas.drawLine(from.left, from.bottom, from.left + len, from.bottom, paint);
                canvas.drawLine(from.left, from.bottom, from.left, from.bottom - len, paint);
                canvas.drawLine(from.right, from.bottom, from.right - len, from.bottom, paint);
                canvas.drawLine(from.right, from.bottom, from.right, from.bottom - len, paint);
            }
            canvas.restore();
        }

        private RectF cell(int screenIndex, float sw, float sh, float inset) {
            int row = screenIndex >> 3, col = screenIndex & 7;
            float l = rect[0] + col * sw, t = rect[1] + row * sh;
            return new RectF(l + inset, t + inset, l + sw - inset, t + sh - inset);
        }
    }

    /** 판 맞추기: 두 모서리 점과 8×8 격자. 내부 좌표는 이 뷰 기준, 저장할 때 화면 좌표로 바꾼다. */
    private final class CalibrationView extends View {
        private final float[] screen; // 화면 좌표
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint();
        private final int[] loc = new int[2];
        private int active = 0; // 0 왼쪽 위, 1 오른쪽 아래, 2 전체
        private float lx, ly;

        CalibrationView(Context c, float[] screenRect) {
            super(c);
            screen = screenRect;
            grid.setStyle(Paint.Style.STROKE);
            grid.setColor(0xFF00E5FF);
            grid.setStrokeWidth(Math.max(2f, Ui.dp(c, 1.5f)));
            handle.setColor(0xFFFF4081);
            handle.setStrokeWidth(Math.max(2f, Ui.dp(c, 2)));
            shade.setColor(0x55000000);
        }

        float[] screenRect() {
            return screen.clone();
        }

        void setScreenRect(float[] r) {
            System.arraycopy(r, 0, screen, 0, 4);
            invalidate();
        }

        void nudge(int dx, int dy) {
            move(dx, dy);
            invalidate();
        }

        private void move(float dx, float dy) {
            if (active == 0 || active == 2) {
                screen[0] += dx;
                screen[1] += dy;
            }
            if (active == 1 || active == 2) {
                screen[2] += dx;
                screen[3] += dy;
            }
            float min = Ui.dp(getContext(), 80);
            if (screen[2] - screen[0] < min) {
                if (active == 0) screen[0] = screen[2] - min;
                else screen[2] = screen[0] + min;
            }
            if (screen[3] - screen[1] < min) {
                if (active == 0) screen[1] = screen[3] - min;
                else screen[3] = screen[1] + min;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            getLocationOnScreen(loc);
            float l = screen[0] - loc[0], t = screen[1] - loc[1], r = screen[2] - loc[0], b = screen[3] - loc[1];
            float w = getWidth(), h = getHeight();
            canvas.drawRect(0, 0, w, t, shade);
            canvas.drawRect(0, b, w, h, shade);
            canvas.drawRect(0, t, l, b, shade);
            canvas.drawRect(r, t, w, b, shade);
            for (int i = 0; i <= 8; i++) {
                float x = l + (r - l) * i / 8f, y = t + (b - t) * i / 8f;
                canvas.drawLine(x, t, x, b, grid);
                canvas.drawLine(l, y, r, y, grid);
            }
            float rad = Ui.dp(getContext(), 16);
            drawHandle(canvas, l, t, rad, active == 0);
            drawHandle(canvas, r, b, rad, active == 1);
        }

        private void drawHandle(Canvas canvas, float x, float y, float rad, boolean on) {
            handle.setStyle(Paint.Style.FILL);
            handle.setAlpha(on ? 120 : 60);
            canvas.drawCircle(x, y, rad, handle);
            handle.setStyle(Paint.Style.STROKE);
            handle.setAlpha(255);
            canvas.drawCircle(x, y, rad, handle);
            canvas.drawLine(x - rad * 1.6f, y, x + rad * 1.6f, y, handle);
            canvas.drawLine(x, y - rad * 1.6f, x, y + rad * 1.6f, handle);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getRawX(), y = e.getRawY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float d0 = (float) Math.hypot(x - screen[0], y - screen[1]);
                    float d1 = (float) Math.hypot(x - screen[2], y - screen[3]);
                    float near = Ui.dp(getContext(), 90);
                    boolean inside = x > screen[0] && x < screen[2] && y > screen[1] && y < screen[3];
                    if (Math.min(d0, d1) < near || !inside) active = d0 <= d1 ? 0 : 1;
                    else active = 2;
                    lx = x;
                    ly = y;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    move(x - lx, y - ly);
                    lx = x;
                    ly = y;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }
}
