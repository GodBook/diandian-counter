package cn.diandian.counter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static cn.diandian.counter.Ui.*;

public final class MainActivity extends Activity {
    private CounterStore store;
    private LinearLayout root, content, historyList;
    private ScrollView scroll;
    private TextView totalLabel, counterSummary, historySummary, loadMore;
    private final Map<Long, TextView> valueLabels = new HashMap<>();
    private List<Counter> counters = new ArrayList<>();
    private int page, historyCategory;
    private long historyCounter = -1, historyBefore;
    private int historyShown;
    private long shownRevision;
    private boolean active, exporting;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!active) return;
            try {
                if (store.nextAlarm() > 0 && store.nextAlarm() <= System.currentTimeMillis()) {
                    store.reconcile(System.currentTimeMillis());
                    ResetScheduler.schedule(MainActivity.this);
                }
                if (shownRevision != store.revision()) refresh();
            } catch (RuntimeException e) { message("自动重置暂未完成，稍后重试"); }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        store = ((CounterApp) getApplication()).store();
        if (saved != null) { page = saved.getInt("page"); historyCounter = saved.getLong("counter", -1); historyCategory = saved.getInt("category"); }
        store.record("APP_OPEN", "打开点点计数 1.0.0");
        render();
    }

    @Override protected void onResume() {
        super.onResume(); active = true;
        store.reconcile(System.currentTimeMillis()); ResetScheduler.schedule(this); refresh();
        handler.removeCallbacks(ticker); handler.postDelayed(ticker, 1000);
    }
    @Override protected void onPause() { active = false; handler.removeCallbacks(ticker); super.onPause(); }
    @Override protected void onStop() {
        store.record("APP_BACKGROUND", "数据已保存在本机"); super.onStop();
    }
    @Override protected void onDestroy() { handler.removeCallbacks(ticker); files.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("page", page); out.putLong("counter", historyCounter); out.putInt("category", historyCategory); super.onSaveInstanceState(out);
    }

    private void refresh() {
        int position = scroll == null ? 0 : scroll.getScrollY();
        render(); if (scroll != null) scroll.post(() -> scroll.scrollTo(0, position));
    }

    private void render() {
        counters = store.counters(); valueLabels.clear();
        shownRevision = store.revision();
        root = column(this); root.setBackgroundColor(BG);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
        LinearLayout mast = row(this); mast.setPadding(dp(this, 24), dp(this, 12), dp(this, 14), dp(this, 8));
        Ui.Icon brand = new Ui.Icon(this, "grid", GREEN); mast.addView(brand, new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24)));
        TextView title = text(this, "点点计数", 17, INK, true); LinearLayout.LayoutParams brandTitle = new LinearLayout.LayoutParams(0, -2, 1); brandTitle.leftMargin = dp(this, 10); mast.addView(title, brandTitle);
        mast.addView(iconButton("settings", "设置与使用说明", this::settings), new LinearLayout.LayoutParams(dp(this, 48), dp(this, 48)));
        root.addView(mast);
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        content = column(this); content.setPadding(dp(this, 24), dp(this, 16), dp(this, 24), dp(this, 24));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (page == 0) home(); else history();
        View line = new View(this); line.setBackgroundColor(LINE); root.addView(line, new LinearLayout.LayoutParams(-1, dp(this, 1)));
        LinearLayout nav = row(this); nav.setPadding(dp(this, 32), dp(this, 6), dp(this, 32), dp(this, 6));
        nav.addView(navItem("grid", "计数器", 0), new LinearLayout.LayoutParams(0, dp(this, 58), 1));
        nav.addView(navItem("history", "操作记录", 1), new LinearLayout.LayoutParams(0, dp(this, 58), 1));
        root.addView(nav);
    }

    private View navItem(String icon, String label, int target) {
        LinearLayout item = column(this); item.setGravity(Gravity.CENTER);
        int color = target == page ? GREEN : MUTED;
        item.addView(new Ui.Icon(this, icon, color), new LinearLayout.LayoutParams(dp(this, 22), dp(this, 22)));
        TextView text = text(this, label, 12, color, target == page); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.topMargin = dp(this, 5); item.addView(text, p);
        item.setContentDescription(label); clickable(item, target == page ? 0xffEBEEE6 : BG, 8, false);
        item.setOnClickListener(v -> { if (page != target) { page = target; store.record(page == 0 ? "VIEW_COUNTERS" : "VIEW_HISTORY", "切换页面"); render(); } }); return item;
    }

    private View iconButton(String icon, String label, Runnable action) {
        FrameLayout button = new FrameLayout(this); Ui.Icon image = new Ui.Icon(this, icon, INK);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER); button.addView(image, p);
        button.setContentDescription(label); clickable(button, Color.TRANSPARENT, 8, false); button.setOnClickListener(v -> action.run()); return button;
    }

    private void home() {
        TextView date = text(this, DateTimeFormatter.ofPattern("M 月 d 日  EEEE", Locale.CHINA).format(java.time.LocalDate.now()), 12, MUTED, false);
        content.addView(date); content.addView(space(this, 12));
        content.addView(text(this, "我的计数器", 30, INK, true)); content.addView(space(this, 8));
        content.addView(text(this, "轻触卡片，记下一次。", 14, MUTED, false)); content.addView(space(this, 28));
        LinearLayout summary = row(this);
        LinearLayout figures = column(this);
        totalLabel = text(this, "", 28, INK, true); totalLabel.setTypeface(Typeface.MONOSPACE);
        totalLabel.setMaxLines(1); totalLabel.setAutoSizeTextTypeUniformWithConfiguration(12, 28, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        counterSummary = text(this, "", 12, MUTED, false); figures.addView(totalLabel); figures.addView(space(this, 6)); figures.addView(counterSummary);
        summary.addView(figures, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = button(this, "+  新建", true, () -> editor(null)); add.setContentDescription("新建计数器"); summary.addView(add);
        content.addView(summary); content.addView(space(this, 24)); updateSummary();
        if (counters.isEmpty()) {
            LinearLayout empty = column(this); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(this, 24), dp(this, 48), dp(this, 24), dp(this, 44));
            empty.setBackground(shape(this, Color.WHITE, 12, true));
            empty.addView(new Ui.Icon(this, "grid", GREEN), new LinearLayout.LayoutParams(dp(this, 46), dp(this, 46)));
            empty.addView(space(this, 22)); empty.addView(text(this, "从第一次开始", 21, INK, true)); empty.addView(space(this, 12));
            TextView hint = text(this, "喝水、阅读、运动，或任何你想记住的事。\n创建一个计数器，轻触就能 +1。", 14, MUTED, false); hint.setGravity(Gravity.CENTER); hint.setLineSpacing(dp(this, 6), 1); empty.addView(hint);
            empty.addView(space(this, 24)); empty.addView(button(this, "创建第一个计数器", true, () -> editor(null)));
            content.addView(empty, match(this, -2));
        } else {
            boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
            LinearLayout pair = null;
            for (int i = 0; i < counters.size(); i++) {
                if (wide) {
                    if (i % 2 == 0) { pair = row(this); content.addView(pair, match(this, -2)); }
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.bottomMargin = dp(this, 14);
                    if (i % 2 == 0) p.rightMargin = dp(this, 7); else p.leftMargin = dp(this, 7);
                    pair.addView(card(counters.get(i)), p);
                    if (i == counters.size() - 1 && i % 2 == 0) pair.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
                } else {
                    LinearLayout.LayoutParams p = match(this, -2); p.bottomMargin = dp(this, 14); content.addView(card(counters.get(i)), p);
                }
            }
            TextView note = text(this, "每次点击都自动保存 · 长按卡片可管理", 12, MUTED, false); note.setGravity(Gravity.CENTER); content.addView(space(this, 8)); content.addView(note);
        }
    }

    private View card(Counter c) {
        LinearLayout card = column(this); card.setPadding(dp(this, 20), dp(this, 10), dp(this, 16), dp(this, 18));
        clickable(card, COLORS[c.color], 12, true);
        LinearLayout top = row(this);
        TextView name = text(this, c.name, 18, INK, true); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        View menu = iconButton("more", "管理计数器：" + c.name, () -> menu(c.id, card)); top.addView(menu, new LinearLayout.LayoutParams(dp(this, 48), dp(this, 48))); card.addView(top);
        TextView value = text(this, format(c.value), 60, INK, false); value.setTypeface(Typeface.MONOSPACE); value.setMaxLines(1);
        value.setAutoSizeTextTypeUniformWithConfiguration(22, 60, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        value.setMinHeight(dp(this, 80)); value.setGravity(Gravity.CENTER_VERTICAL); card.addView(value, match(this, 88)); valueLabels.put(c.id, value);
        LinearLayout bottom = row(this);
        LinearLayout rule = column(this);
        rule.addView(text(this, c.rule.type == ResetRule.NONE ? "手动记录 · 随时归零" : c.rule.describe() + "归零", 12, MUTED, false));
        if (c.nextReset > 0) {
            rule.addView(space(this, 5)); rule.addView(text(this, "下次 " + time(c.nextReset), 11, MUTED, false));
        }
        bottom.addView(rule, new LinearLayout.LayoutParams(0, -2, 1));
        TextView plus = text(this, "+1", 16, GREEN, true); plus.setGravity(Gravity.CENTER); plus.setBackground(shape(this, 0x99FFFFFF, 8, false));
        plus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bottom.addView(plus, new LinearLayout.LayoutParams(dp(this, 44), dp(this, 38))); card.addView(bottom);
        card.setContentDescription(c.name + "，当前 " + c.value + "，点按加一");
        card.setOnClickListener(v -> run(() -> {
            long now = System.currentTimeMillis();
            boolean changed = store.reconcile(now) || shownRevision != store.revision();
            Counter updated = store.increment(c.id, now);
            for (int i = 0; i < counters.size(); i++) if (counters.get(i).id == c.id) counters.set(i, updated);
            value.setText(format(updated.value)); card.setContentDescription(updated.name + "，当前 " + updated.value + "，点按加一");
            if (getPreferences(MODE_PRIVATE).getBoolean("haptic", true)) card.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (changed) refresh(); else { shownRevision = store.revision(); updateSummary(); }
            ResetScheduler.schedule(this);
        }));
        card.setOnLongClickListener(v -> { menu(c.id, card); return true; }); return card;
    }

    private void updateSummary() {
        BigInteger total = BigInteger.ZERO;
        for (Counter c : counters) total = total.add(BigInteger.valueOf(c.value));
        if (totalLabel != null) totalLabel.setText(String.format(Locale.CHINA, "%,d", total));
        if (counterSummary != null) counterSummary.setText(getString(R.string.counter_summary, counters.size()));
    }
    private String format(long value) { return String.format(Locale.CHINA, "%,d", value); }
    private String time(long value) { return DateTimeFormatter.ofPattern("M/d HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value)); }
    private void message(String content) { Toast.makeText(this, content, Toast.LENGTH_SHORT).show(); }
    private void run(Runnable task) { try { task.run(); } catch (RuntimeException e) { message(e.getMessage() == null ? "操作未完成，请重试" : e.getMessage()); } }

    private void menu(long id, View anchor) {
        store.record("UI", "打开计数器管理菜单；ID=" + id);
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        menu.getMenu().add(0, 1, 0, "修改名称与周期");
        menu.getMenu().add(0, 2, 1, "重置为 0");
        menu.getMenu().add(0, 3, 2, "查看操作记录");
        menu.getMenu().add(0, 4, 3, "删除计数器");
        menu.setOnMenuItemClickListener(item -> {
            run(() -> {
                Counter c = store.counter(id);
                switch (item.getItemId()) {
                    case 1 -> editor(c);
                    case 2 -> new AlertDialog.Builder(this).setTitle("重置“" + c.name + "”？")
                            .setMessage("当前为 " + c.value + "，重置后为 0。此次操作会保留在记录中。")
                            .setNegativeButton("取消", null).setPositiveButton("重置", (d, w) -> run(() -> {
                                store.reset(id, System.currentTimeMillis()); refresh(); ResetScheduler.schedule(this); message("已重置");
                            })).show();
                    case 3 -> { page = 1; historyCounter = id; historyCategory = 0; store.record("VIEW_HISTORY", "查看“" + c.name + "”的记录"); render(); }
                    case 4 -> new AlertDialog.Builder(this).setTitle("删除“" + c.name + "”？")
                            .setMessage("计数器将被移除，已有的全部操作记录仍可查看和导出。")
                            .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> run(() -> {
                                store.delete(id, System.currentTimeMillis()); refresh(); ResetScheduler.schedule(this); message("已删除，历史记录已保留");
                            })).show();
                    default -> { }
                }
            }); return true;
        }); menu.show();
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
        spinner.setBackground(shape(this, Color.WHITE, 8, true)); spinner.setMinimumHeight(dp(this, 48)); return spinner;
    }

    private EditText input(String text, String hint, boolean numeric) {
        EditText edit = new EditText(this); edit.setTextSize(16); edit.setTextColor(INK); edit.setHintTextColor(MUTED);
        edit.setText(text); edit.setHint(hint); edit.setSingleLine(true);
        edit.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12)); edit.setBackground(shape(this, Color.WHITE, 8, true));
        if (numeric) edit.setInputType(InputType.TYPE_CLASS_NUMBER); else edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return edit;
    }

    private void field(LinearLayout parent, String label, View input) {
        parent.addView(text(this, label, 13, MUTED, true)); parent.addView(space(this, 8));
        parent.addView(input, match(this, -2)); parent.addView(space(this, 20));
    }

    private void editor(Counter existing) {
        store.record("UI", existing == null ? "打开新建计数器" : "打开编辑计数器；ID=" + existing.id);
        Counter draft = existing == null ? new Counter() : existing.copy();
        if (existing == null) draft.color = counters.size() % COLORS.length;
        ScrollView formScroll = new ScrollView(this); LinearLayout form = column(this);
        form.setPadding(dp(this, 24), dp(this, 12), dp(this, 24), dp(this, 8)); formScroll.addView(form);
        EditText name = input(draft.name, "例如：喝水、阅读、深蹲", false);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        name.setContentDescription("计数器名称"); field(form, "名称", name);
        LinearLayout colors = row(this);
        View[] swatches = new View[5];
        for (int i = 0; i < COLORS.length; i++) {
            final int index = i;
            FrameLayout swatch = new FrameLayout(this); swatch.setBackground(shape(this, COLORS[i], 8, true));
            swatch.setContentDescription(COLOR_NAMES[i] + "色"); swatch.setFocusable(true); swatch.setClickable(true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(this, 44), 1); if (i < 4) p.rightMargin = dp(this, 8); colors.addView(swatch, p);
            Ui.Icon check = new Ui.Icon(this, "check", GREEN); swatch.addView(check, new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
            check.setVisibility(draft.color == i ? View.VISIBLE : View.INVISIBLE); swatches[i] = check;
            swatch.setOnClickListener(v -> { draft.color = index; for (int j = 0; j < 5; j++) swatches[j].setVisibility(j == index ? View.VISIBLE : View.INVISIBLE); });
        }
        field(form, "卡片颜色", colors);
        Spinner mode = spinner(new String[]{"不自动重置", "每天", "每周", "每月", "固定间隔"});
        mode.setContentDescription("自动重置周期"); mode.setSelection(draft.rule.type); field(form, "自动重置", mode);
        LinearLayout calendar = column(this), weekly = column(this), monthly = column(this), interval = column(this);
        Spinner weekdays = spinner(new String[]{"周一", "周二", "周三", "周四", "周五", "周六", "周日"});
        weekdays.setSelection(draft.rule.weekDay - 1); field(weekly, "星期", weekdays); form.addView(weekly);
        EditText monthDay = input(Integer.toString(draft.rule.monthDay), "1–31", true); field(monthly, "每月几号", monthDay);
        TextView monthHelp = text(this, "当月没有这一天时，在该月最后一天重置。", 12, MUTED, false); monthly.addView(monthHelp); monthly.addView(space(this, 16)); form.addView(monthly);
        TextView clock = button(this, String.format(Locale.CHINA, "%02d:%02d", draft.rule.hour, draft.rule.minute), false, () -> { });
        clock.setContentDescription("选择自动重置时间"); clock.setOnClickListener(v -> new TimePickerDialog(this, (picker, hour, minute) -> {
            draft.rule.hour = hour; draft.rule.minute = minute; clock.setText(String.format(Locale.CHINA, "%02d:%02d", hour, minute));
        }, draft.rule.hour, draft.rule.minute, true).show()); field(calendar, "重置时间", clock); form.addView(calendar);
        int unit = draft.rule.intervalMinutes % 1440 == 0 ? 2 : draft.rule.intervalMinutes % 60 == 0 ? 1 : 0;
        long divisor = unit == 2 ? 1440 : unit == 1 ? 60 : 1;
        EditText amount = input(Long.toString(draft.rule.intervalMinutes / divisor), "周期长度", true); amount.setContentDescription("间隔数值");
        Spinner units = spinner(new String[]{"分钟", "小时", "天"}); units.setContentDescription("间隔单位"); units.setSelection(unit);
        LinearLayout intervalRow = row(this); LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(0, -2, 1); numberParams.rightMargin = dp(this, 10);
        intervalRow.addView(amount, numberParams); intervalRow.addView(units, new LinearLayout.LayoutParams(dp(this, 110), -2));
        field(interval, "每隔多久", intervalRow); form.addView(interval);
        TextView note = text(this, "", 12, MUTED, false); note.setLineSpacing(dp(this, 5), 1); form.addView(note);
        Runnable update = () -> {
            int type = mode.getSelectedItemPosition();
            weekly.setVisibility(type == ResetRule.WEEKLY ? View.VISIBLE : View.GONE);
            monthly.setVisibility(type == ResetRule.MONTHLY ? View.VISIBLE : View.GONE);
            calendar.setVisibility(type >= 1 && type <= 3 ? View.VISIBLE : View.GONE);
            interval.setVisibility(type == ResetRule.INTERVAL ? View.VISIBLE : View.GONE);
            note.setText(type == 0 ? "只在你手动重置时归零，所有操作都会保存。"
                    : type == 4 ? "从保存周期时开始计算。手动重置不会改变周期；错过的重置会在恢复运行时补记。"
                    : "按手机本地时间归零。原计数会写入操作记录，错过的重置会自动补记。");
        };
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { update.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        }); update.run();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "新建计数器" : "编辑计数器")
                .setView(formScroll).setNegativeButton("取消", (d, w) -> store.record("UI", "取消计数器编辑"))
                .setPositiveButton("保存", null).create();
        dialog.setOnCancelListener(d -> store.record("UI", "关闭计数器编辑"));
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                draft.name = name.getText().toString(); draft.rule.type = mode.getSelectedItemPosition();
                draft.rule.weekDay = weekdays.getSelectedItemPosition() + 1;
                if (draft.rule.type == ResetRule.MONTHLY) draft.rule.monthDay = Integer.parseInt(monthDay.getText().toString());
                if (draft.rule.type == ResetRule.INTERVAL) {
                    long number = Long.parseLong(amount.getText().toString());
                    draft.rule.intervalMinutes = Math.multiplyExact(number, units.getSelectedItemPosition() == 2 ? 1440L : units.getSelectedItemPosition() == 1 ? 60L : 1L);
                }
                store.save(draft, System.currentTimeMillis()); ResetScheduler.schedule(this);
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(name.getWindowToken(), 0);
                dialog.dismiss(); refresh(); message(existing == null ? "已创建，轻触卡片即可计数" : "已保存");
            } catch (NumberFormatException | ArithmeticException invalid) { message("请输入有效的周期数字，间隔支持 1 分钟至 10 年"); }
            catch (IllegalArgumentException invalid) { name.setError(Strings.strip(draft.name).isEmpty() ? "请输入名称" : null); message(invalid.getMessage()); }
        })); dialog.show();
    }

    private void history() {
        LinearLayout heading = row(this);
        heading.addView(text(this, "操作记录", 30, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(iconButton("export", "导出全部操作记录", this::export), new LinearLayout.LayoutParams(dp(this, 48), dp(this, 48))); content.addView(heading);
        content.addView(space(this, 8)); content.addView(text(this, "每次变化，都有迹可循。", 14, MUTED, false)); content.addView(space(this, 24));
        List<Counter> choices = store.historyCounters(); String[] names = new String[choices.size() + 1]; names[0] = "全部计数器";
        int selected = 0;
        for (int i = 0; i < choices.size(); i++) { names[i + 1] = choices.get(i).name; if (choices.get(i).id == historyCounter) selected = i + 1; }
        if (selected == 0) historyCounter = -1;
        Spinner counterFilter = spinner(names); counterFilter.setContentDescription("按计数器筛选"); counterFilter.setSelection(selected);
        Spinner category = spinner(new String[]{"全部操作", "点击计数", "重置记录", "编辑与管理", "应用操作"}); category.setContentDescription("按操作类型筛选"); category.setSelection(historyCategory);
        LinearLayout filters = row(this); LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1); left.rightMargin = dp(this, 10);
        filters.addView(counterFilter, left); filters.addView(category, new LinearLayout.LayoutParams(0, -2, 1)); content.addView(filters);
        content.addView(space(this, 18)); historySummary = text(this, "", 12, MUTED, false); content.addView(historySummary); content.addView(space(this, 10));
        historyList = column(this); content.addView(historyList);
        loadMore = button(this, "加载更多记录", false, this::moreHistory); content.addView(space(this, 14)); content.addView(loadMore, match(this, -2));
        AdapterView.OnItemSelectedListener changed = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                long chosen = counterFilter.getSelectedItemPosition() == 0 ? -1 : choices.get(counterFilter.getSelectedItemPosition() - 1).id;
                int selectedCategory = category.getSelectedItemPosition();
                if (chosen != historyCounter || selectedCategory != historyCategory) {
                    historyCounter = chosen; historyCategory = selectedCategory;
                    store.record("FILTER", "计数器 ID=" + chosen + "；类型=" + category.getSelectedItem()); reloadHistory();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        counterFilter.setOnItemSelectedListener(changed); category.setOnItemSelectedListener(changed); reloadHistory();
    }

    private void reloadHistory() { historyBefore = 0; historyShown = 0; historyList.removeAllViews(); moreHistory(); }
    private void moreHistory() {
        List<AuditEvent> events = store.events(historyCounter, historyCategory, historyBefore, 80);
        for (AuditEvent e : events) { historyList.addView(eventRow(e), match(this, -2)); historyBefore = e.id; historyShown++; }
        long count = store.eventCount(historyCounter, historyCategory);
        historySummary.setText(getString(R.string.history_summary, count, historyShown));
        loadMore.setVisibility(events.size() == 80 && historyShown < count ? View.VISIBLE : View.GONE);
        if (historyShown == 0) {
            TextView empty = text(this, "这里还没有记录\n计数或修改设置后，操作会出现在这里。", 14, MUTED, false);
            empty.setGravity(Gravity.CENTER); empty.setLineSpacing(dp(this, 8), 1); empty.setPadding(0, dp(this, 48), 0, dp(this, 48)); historyList.addView(empty);
        }
    }

    private View eventRow(AuditEvent e) {
        LinearLayout row = column(this); row.setPadding(0, dp(this, 17), 0, 0);
        LinearLayout first = Ui.row(this);
        TextView name = text(this, e.name, 15, INK, true); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        first.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        String action = "INCREMENT".equals(e.action) ? "+1" : AuditEvent.label(e.action);
        TextView badge = text(this, action, 12, "DELETE".equals(e.action) ? RED : GREEN, true);
        badge.setPadding(dp(this, 8), dp(this, 5), dp(this, 8), dp(this, 5)); badge.setBackground(shape(this, 0xffEAEFE5, 4, false));
        LinearLayout.LayoutParams badgeP = new LinearLayout.LayoutParams(-2, -2); badgeP.leftMargin = dp(this, 10); first.addView(badge, badgeP); row.addView(first);
        row.addView(space(this, 8));
        String detail = e.before != null || e.after != null ? (e.before == null ? "—" : format(e.before)) + " → " + (e.after == null ? "—" : format(e.after)) : "";
        if (!"INCREMENT".equals(e.action)) detail += (detail.isEmpty() ? "" : "  ·  ") + e.details;
        TextView detailView = text(this, detail, 13, MUTED, false); detailView.setLineSpacing(dp(this, 4), 1); row.addView(detailView);
        row.addView(space(this, 8));
        String stamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.CHINA).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(e.at));
        row.addView(text(this, stamp + "  ·  #" + e.id, 11, MUTED, false)); row.addView(space(this, 17));
        View line = new View(this); line.setBackgroundColor(LINE); row.addView(line, new LinearLayout.LayoutParams(-1, dp(this, 1))); return row;
    }

    private void export() {
        if (exporting) { message("正在导出，请稍候"); return; }
        store.record("UI", "选择操作记录导出位置");
        String date = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss", Locale.CHINA).format(java.time.LocalDateTime.now());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "点点计数_全部操作记录_" + date + ".csv");
        try { startActivityForResult(intent, 42); } catch (ActivityNotFoundException e) { message("请先安装或启用系统文件管理器"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 42) return;
        if (result != RESULT_OK || data == null || data.getData() == null) { store.record("UI", "取消操作记录导出"); return; }
        Uri uri = data.getData(); exporting = true; message("正在导出全部记录…");
        files.execute(() -> {
            try {
                OutputStream stream = getContentResolver().openOutputStream(uri, "wt");
                if (stream == null) throw new java.io.IOException("无法打开目标文件");
                try (Writer writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) { store.export(writer); }
                store.record("EXPORT", "已导出全部计数器的全部操作记录（CSV）");
                handler.post(() -> { exporting = false; if (!isDestroyed()) { message("已导出全部记录"); refresh(); } });
            } catch (Exception e) {
                handler.post(() -> { exporting = false; if (!isDestroyed()) message("导出失败，请检查目标文件夹后重试"); });
            }
        });
    }

    private void settings() {
        store.record("VIEW_SETTINGS", "查看设置与使用说明");
        ScrollView scroll = new ScrollView(this); LinearLayout body = column(this);
        body.setPadding(dp(this, 24), dp(this, 8), dp(this, 24), dp(this, 20)); scroll.addView(body);
        body.addView(text(this, "每一次，都算数。", 22, INK, true)); body.addView(space(this, 8)); body.addView(text(this, "点点计数  1.0.0", 13, MUTED, false)); body.addView(space(this, 24));
        Switch haptic = new Switch(this); haptic.setText("轻触震动"); haptic.setTextSize(15); haptic.setTextColor(INK); haptic.setMinHeight(dp(this, 48));
        haptic.setChecked(getPreferences(MODE_PRIVATE).getBoolean("haptic", true));
        haptic.setOnCheckedChangeListener((button, checked) -> { getPreferences(MODE_PRIVATE).edit().putBoolean("haptic", checked).apply(); store.record("SETTING", "轻触震动：" + (checked ? "开启" : "关闭")); });
        body.addView(haptic); body.addView(space(this, 20));
        body.addView(text(this, "自动重置", 15, INK, true)); body.addView(space(this, 8));
        TextView alarm = text(this, ResetScheduler.precise(this) ? "已启用精确闹钟。系统会在设定的时间唤醒应用执行归零。"
                : "开启“闹钟和提醒”权限，可以提高后台重置的准时性。未开启时，系统可能延后执行，重新打开应用也会自动补记。", 13, MUTED, false);
        alarm.setLineSpacing(dp(this, 5), 1); body.addView(alarm); body.addView(space(this, 14));
        if (Build.VERSION.SDK_INT >= 31) body.addView(button(this, "管理闹钟和提醒权限", false, () -> {
            store.record("SETTING", "打开系统闹钟和提醒权限页");
            try { startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException unavailable) { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
        }), match(this, -2));
        body.addView(space(this, 22)); body.addView(text(this, "关于记录", 15, INK, true)); body.addView(space(this, 8));
        TextView description = text(this, "新建、点击、改名、改色、修改周期、手动或自动重置、删除，以及页面访问、筛选和设置操作都会记录。\n\n所有数据仅存于本机，无需账号和网络权限。日志不会自动清理，删除计数器仍会保留历史。可在操作记录页面导出全部 CSV。\n\n卸载应用或清除应用数据会移除本机记录，建议定期导出。手机关机、系统强行停止或限制后台运行时，重置可能延后，恢复运行后会逐次补记。", 13, MUTED, false);
        description.setLineSpacing(dp(this, 5), 1); body.addView(description);
        new AlertDialog.Builder(this).setTitle("设置与说明").setView(scroll).setPositiveButton("知道了", null).show();
    }
}
