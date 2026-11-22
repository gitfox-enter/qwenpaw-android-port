package io.agentscope.qwenpaw.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * First-run gate. It shows the real unpack/install progress reported by
 * {@link RuntimeService} and only opens the chat UI once the local server
 * answers, so the user never lands on an empty shell.
 */
public class BootActivity extends Activity {

    // Dark palette aligned with the RN theme (groupedBackground/ink/muted).
    private static final int BG = 0xFF151412;
    private static final int INK = 0xFFF6F1E9;
    private static final int MUTED = 0xFFAAA39A;
    private static final int TRACK = 0xFF3D3933;
    private static final int ACCENT = 0xFFC84A00;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ProgressBar bar;
    private TextView headline;
    private TextView percent;
    private LinearLayout steps;
    private boolean opened;
    private android.widget.Button retry;
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        // MIUI throttles long foreground work when the screen sleeps; hold the
        // CPU awake for the duration of the install.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "qwenpaw:install");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(30 * 60 * 1000L);
        }
        RuntimeService.ensureRunning(this);
    }

    @Override
    protected void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        opened = false;
        retry = null;
        poll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacksAndMessages(null);
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER);
        int pad = dp(32);
        root.setPadding(pad, pad, pad, pad);

        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(io.agentscope.qwenpaw.mobile.R.mipmap.ic_launcher);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(88), dp(88));
        lp.bottomMargin = dp(20);
        logo.setLayoutParams(lp);
        root.addView(logo);

        TextView title = new TextView(this);
        title.setText("QwenPaw");
        title.setTextSize(28);
        title.setTextColor(INK);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("正在准备本机运行环境");
        sub.setTextSize(14);
        sub.setTextColor(MUTED);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(8), 0, dp(32));
        root.addView(sub);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(TRACK));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        percent = new TextView(this);
        percent.setText("0%");
        percent.setTextSize(13);
        percent.setTextColor(ACCENT);
        percent.setGravity(Gravity.CENTER);
        percent.setPadding(0, dp(12), 0, dp(24));
        root.addView(percent);

        headline = new TextView(this);
        headline.setTextSize(15);
        headline.setTextColor(INK);
        headline.setGravity(Gravity.CENTER);
        root.addView(headline);

        steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.setPadding(0, dp(24), 0, 0);
        root.addView(steps);

        return root;
    }

    private void poll() {
        RuntimeService.State st = RuntimeService.snapshot();
        bar.setProgress((int) (st.progress * 1000));

        // Steps and percent must tell the same story: N of 5 steps done,
        // percent is the weighted progress the service reports.
        int done = 0;
        for (RuntimeService.Step s : st.steps) {
            if (s.state == RuntimeService.Step.State.DONE) done++;
        }
        percent.setText(Math.round(st.progress * 100) + "% · 第 "
                + Math.min(done + 1, st.steps.size()) + " / " + st.steps.size() + " 步");
        headline.setText(st.headline);
        renderSteps(st.steps);

        if (st.ready) {
            goToChat();
            return;
        }
        if (st.failed) {
            showRetry();
            return;
        }
        ui.postDelayed(this::poll, 400);
    }

    /** Failure escape hatch: surface the error and offer one-tap retry. */
    private void showRetry() {
        if (retry != null) return;
        String error = STATE_ERROR_PREFIX + (RuntimeService.snapshot().error == null
                ? "未知原因" : RuntimeService.snapshot().error);
        headline.setText(error);
        android.widget.Button btn = new android.widget.Button(this);
        btn.setText("重试");
        btn.setTextColor(INK);
        btn.setBackgroundColor(TRACK);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> {
            retry = null;
            removeView(btn);
            headline.setText("正在重试…");
            RuntimeService.restart(this);
            ui.postDelayed(this::poll, 800);
        });
        retry = btn;
        steps.addView(btn);
    }

    private void removeView(View v) {
        steps.removeView(v);
    }

    private static final String STATE_ERROR_PREFIX = "启动失败：";

    private void renderSteps(List<RuntimeService.Step> list) {
        if (steps.getChildCount() != list.size()) {
            steps.removeAllViews();
            for (RuntimeService.Step s : list) {
                TextView tv = new TextView(this);
                tv.setTextSize(13);
                tv.setPadding(0, dp(4), 0, dp(4));
                steps.addView(tv);
            }
        }
        // done = dimmed, active = bright with accent marker, pending = faint.
        for (int i = 0; i < steps.getChildCount(); i++) {
            TextView tv = (TextView) steps.getChildAt(i);
            RuntimeService.Step s = list.get(i);
            boolean active = s.state == RuntimeService.Step.State.ACTIVE;
            boolean done = s.state == RuntimeService.Step.State.DONE;
            tv.setText(marker(s.state) + "  " + s.label + (active ? "…" : ""));
            tv.setTextColor(done ? MUTED : active ? INK : 0x66AAA39A);
            if (active) tv.setTextColor(INK);
        }
    }

    private String marker(RuntimeService.Step.State state) {
        switch (state) {
            case DONE:
                return "\u2713";
            case ACTIVE:
                return "\u25CF";
            default:
                return "\u25CB";
        }
    }

    private void goToChat() {
        if (opened) return;
        opened = true;
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}