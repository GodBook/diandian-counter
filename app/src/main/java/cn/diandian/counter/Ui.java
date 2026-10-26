package cn.diandian.counter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(247, 246, 242), INK = Color.rgb(38, 43, 39), MUTED = Color.rgb(116, 121, 113);
    static final int GREEN = Color.rgb(53, 103, 81), LINE = Color.rgb(228, 229, 222), RED = Color.rgb(159, 64, 55);
    static final int[] COLORS = {0xffE5EDDF, 0xffE5ECF0, 0xffF3EBDD, 0xffEEE5EB, 0xffE8E8E1};
    static final String[] COLOR_NAMES = {"苔绿", "雾蓝", "麦黄", "浅莓", "岩灰"};
    static int dp(Context c, float n) { return Math.round(c.getResources().getDisplayMetrics().density * n); }
    static TextView text(Context c, String content, float size, int color, boolean bold) {
        TextView v = new TextView(c); v.setText(content); v.setTextSize(size); v.setTextColor(color);
        v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        v.setIncludeFontPadding(false); return v;
    }
    static GradientDrawable shape(Context c, int color, float radius, boolean border) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, radius));
        if (border) d.setStroke(dp(c, 1), LINE); return d;
    }
    static void clickable(View view, int color, float radius, boolean border) {
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x16000000), shape(view.getContext(), color, radius, border), null));
        view.setClickable(true); view.setFocusable(true);
    }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static LinearLayout row(Context c) { LinearLayout v = new LinearLayout(c); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    static TextView button(Context c, String label, boolean primary, Runnable click) {
        TextView b = text(c, label, 15, primary ? Color.WHITE : INK, true);
        b.setGravity(Gravity.CENTER); b.setMinHeight(dp(c, 50)); b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        clickable(b, primary ? INK : Color.WHITE, 8, !primary); b.setOnClickListener(v -> click.run()); return b;
    }
    static View space(Context c, int height) { View v = new View(c); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, height))); return v; }
    static LinearLayout.LayoutParams match(Context c, int height) { return new LinearLayout.LayoutParams(-1, height < 0 ? height : dp(c, height)); }

    static final class Icon extends View {
        private final String name;
        private final int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        public Icon(Context context) { this(context, "grid", INK); }
        Icon(Context context, String name, int color) { super(context); this.name = name; this.color = color; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.save();
            float scale = Math.min(getWidth(), getHeight()) / 24f;
            canvas.translate((getWidth() - scale * 24) / 2f, (getHeight() - scale * 24) / 2f); canvas.scale(scale, scale);
            paint.setColor(color); paint.setStrokeWidth(1.8f); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            switch (name) {
                case "plus" -> { canvas.drawLine(12, 5, 12, 19, paint); canvas.drawLine(5, 12, 19, 12, paint); }
                case "grid" -> {
                    canvas.drawRoundRect(4, 4, 10, 10, 1, 1, paint); canvas.drawRoundRect(14, 4, 20, 10, 1, 1, paint);
                    canvas.drawRoundRect(4, 14, 10, 20, 1, 1, paint); canvas.drawRoundRect(14, 14, 20, 20, 1, 1, paint);
                }
                case "history" -> { canvas.drawRoundRect(5, 3, 19, 21, 2, 2, paint); canvas.drawLine(9, 8, 15, 8, paint); canvas.drawLine(9, 12, 15, 12, paint); canvas.drawLine(9, 16, 13, 16, paint); }
                case "more" -> { paint.setStyle(Paint.Style.FILL); for (int y = 6; y <= 18; y += 6) canvas.drawCircle(12, y, 1.5f, paint); }
                case "clock" -> { canvas.drawCircle(12, 12, 8, paint); canvas.drawLine(12, 7, 12, 12, paint); canvas.drawLine(12, 12, 16, 14, paint); }
                case "export" -> { canvas.drawLine(12, 3, 12, 15, paint); canvas.drawLine(8, 11, 12, 15, paint); canvas.drawLine(16, 11, 12, 15, paint); path.reset(); path.moveTo(5, 15); path.lineTo(5, 20); path.lineTo(19, 20); path.lineTo(19, 15); canvas.drawPath(path, paint); }
                case "settings" -> { canvas.drawLine(5, 7, 19, 7, paint); canvas.drawLine(5, 17, 19, 17, paint); paint.setStyle(Paint.Style.FILL); canvas.drawCircle(9, 7, 3, paint); canvas.drawCircle(15, 17, 3, paint); }
                case "reset" -> { canvas.drawArc(5, 5, 20, 20, -90, 285, false, paint); canvas.drawLine(5, 5, 5, 11, paint); canvas.drawLine(5, 5, 11, 5, paint); }
                case "check" -> { canvas.drawLine(6, 12, 10, 16, paint); canvas.drawLine(10, 16, 18, 7, paint); }
                default -> { canvas.drawLine(9, 7, 14, 12, paint); canvas.drawLine(14, 12, 9, 17, paint); }
            }
            canvas.restore();
        }
    }
}
