package com.chesshelper;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** XML 레이아웃 없이 코드로 화면을 만드는 작은 도우미들. */
final class Ui {
    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** 둥근 글자 버튼. */
    static TextView button(Context c, String s, float sp, int textColor, int bg, View.OnClickListener l) {
        TextView t = text(c, s, sp, textColor, true);
        t.setGravity(Gravity.CENTER);
        int ph = dp(c, 10), pv = dp(c, 6);
        t.setPadding(ph, pv, ph, pv);
        t.setBackground(round(bg, dp(c, 8)));
        t.setOnClickListener(l);
        return t;
    }

    static LinearLayout.LayoutParams weighted(Context c, float marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        int m = dp(c, marginDp);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    /** 여러 개 중 하나를 고르는 버튼 줄. */
    static final class Segmented {
        final LinearLayout row;
        private final TextView[] items;
        private final int[] colors; // {글자, 버튼, 선택된 버튼}

        Segmented(Context c, String[] labels, int selected, float sp, int[] colors, IntConsumer onPick) {
            this.colors = colors;
            row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            items = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                items[i] = button(c, labels[i], sp, colors[0], colors[1], v -> {
                    select(idx);
                    onPick.accept(idx);
                });
                row.addView(items[i], weighted(c, 2));
            }
            select(selected);
        }

        void select(int idx) {
            for (int i = 0; i < items.length; i++) {
                GradientDrawable d = (GradientDrawable) items[i].getBackground();
                d.setColor(i == idx ? colors[2] : colors[1]);
                items[i].setTextColor(i == idx ? 0xFFFFFFFF : colors[0]);
            }
        }

        void setEnabled(int idx, boolean enabled) {
            items[idx].setEnabled(enabled);
            items[idx].setAlpha(enabled ? 1f : 0.35f);
        }
    }

    static int indexOf(int[] arr, int v, int def) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return def;
    }
}
