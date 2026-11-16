package com.chesshelper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 첫 화면: 권한, AI 설정, 시작/중지. 화면은 XML 없이 코드로 만든다. */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private static final int REQ_NOTIFY = 2;

    private static final int BG = 0xFFF5F3EE;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TEXT = 0xFF202124;
    private static final int SUB = 0xFF5F6368;
    private static final int BTN = 0xFFE8EAED;
    private static final int ACCENT = 0xFF2E7D32;

    private Prefs prefs;
    private TextView overlayState;
    private TextView runState;
    private TextView startBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, Ui.dp(this, 28), pad, pad);
        scroll.addView(root);

        root.addView(Ui.text(this, "♞ 체스 도우미", 26, TEXT, true));
        TextView intro = Ui.text(this, "화면에 보이는 체스판을 읽어서, 다음에 둘 좋은 수를 게임 화면 위에 표시해 줍니다.", 14, SUB, false);
        intro.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        root.addView(intro);

        // ---- AI 설정
        LinearLayout ai = card(root, "AI 설정");
        int[] seg = {TEXT, BTN, ACCENT};
        ai.addView(label("생각 시간"));
        ai.addView(new Ui.Segmented(this, new String[]{"0.5초", "1.5초", "3초"},
                Ui.indexOf(Prefs.THINK_MS, prefs.thinkMs(), 1), 14, seg, i -> prefs.setThinkMs(Prefs.THINK_MS[i])).row);
        ai.addView(label("추천 개수"));
        ai.addView(new Ui.Segmented(this, new String[]{"1개", "3개", "5개"},
                Ui.indexOf(Prefs.MULTI_PV, prefs.multiPv(), 1), 14, seg, i -> prefs.setMultiPv(Prefs.MULTI_PV[i])).row);
        ai.addView(label("내 색"));
        ai.addView(new Ui.Segmented(this, new String[]{"자동", "백", "흑"}, prefs.me(), 14, seg, prefs::setMe).row);
        TextView meHelp = Ui.text(this, "자동: 지금 차례인 쪽의 추천 · 백/흑: 내 차례일 때만 추천", 12, SUB, false);
        meHelp.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), 0, 0);
        ai.addView(meHelp);

        // ---- 시작
        LinearLayout run = card(root, "실행");
        overlayState = Ui.text(this, "", 14, TEXT, false);
        run.addView(overlayState);
        run.addView(Ui.button(this, "다른 앱 위에 표시 권한 열기", 15, TEXT, BTN, v -> openOverlaySettings()), fullWidth());
        runState = Ui.text(this, "", 14, TEXT, false);
        runState.setPadding(0, Ui.dp(this, 10), 0, 0);
        run.addView(runState);
        startBtn = Ui.button(this, "", 16, 0xFFFFFFFF, ACCENT, v -> toggle());
        run.addView(startBtn, fullWidth());

        // ---- 사용법
        LinearLayout how = card(root, "사용 방법");
        how.addView(Ui.text(this,
                "1. [화면 감지 시작] → 화면 녹화를 허용합니다 (\"전체 화면\" 선택).\n"
                        + "2. 체스 앱을 열고, 떠 있는 패널의 [판 맞추기]를 누릅니다. 판을 자동으로 찾아 주니 확인하고 [저장].\n"
                        + "3. 게임 시작 배치가 보일 때 [학습]을 누릅니다. (기물 그림을 기억 — 앱/테마마다 한 번)\n"
                        + "4. 이제 판이 바뀔 때마다 추천 칸에 테두리가 나타납니다.\n"
                        + "   빨강 = 1순위, 주황 = 2순위, 파랑 = 3순위 이하. ┌ ┘ 모서리 = 움직일 기물, □ 테두리 = 갈 칸.\n"
                        + "5. 패널의 [차례] 칩으로 차례를, [나] 칩으로 내 색을 바꿀 수 있습니다.\n"
                        + "※ 패널은 제목을 끌어서 판 밖으로 옮겨 두세요 (판을 가리면 인식이 안 됩니다).",
                13, TEXT, false));

        TextView caution = Ui.text(this,
                "연습, AI 대전, 기보 공부용으로 만들었습니다. 사람과 하는 온라인 대국에서 쓰면 상대에게 불공정하고, "
                        + "대부분의 체스 사이트 이용약관 위반이라 계정이 정지될 수 있습니다.", 12, SUB, false);
        caution.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 20));
        root.addView(caution);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private LinearLayout card(LinearLayout root, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 14);
        c.setPadding(p, p, p, p);
        c.setBackground(Ui.round(CARD, Ui.dp(this, 16)));
        c.addView(Ui.text(this, title, 17, TEXT, true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, Ui.dp(this, 12));
        root.addView(c, lp);
        return c;
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 13, SUB, false);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, Ui.dp(this, 2));
        return t;
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 8), 0, 0);
        return lp;
    }

    private void refresh() {
        boolean canDraw = Settings.canDrawOverlays(this);
        overlayState.setText(canDraw ? "✅ 다른 앱 위에 표시 권한: 허용됨" : "⚠ 다른 앱 위에 표시 권한이 필요해요");
        boolean running = CaptureService.running;
        runState.setText(running ? "● 화면 감지 중" : "○ 멈춤");
        startBtn.setText(running ? "중지" : "화면 감지 시작");
        startBtn.setGravity(Gravity.CENTER);
    }

    private void openOverlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private void toggle() {
        if (CaptureService.running) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            startBtn.postDelayed(this::refresh, 300);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 '다른 앱 위에 표시' 권한을 허용해 주세요.", Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            return; // 결과를 받은 뒤 이어서 시작
        }
        requestCapture();
    }

    private void requestCapture() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFY) requestCapture(); // 알림을 거절해도 동작은 한다
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "화면 녹화를 허용해야 판을 읽을 수 있어요.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent svc = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(svc);
        Toast.makeText(this, "체스 앱을 열고 패널의 [판 맞추기]를 누르세요.", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }
}
