package com.brover.thorlock;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Настройки. Чтобы открыть, нужно ввести текущий рисунок/комбинацию. */
public class MainActivity extends Activity {

    private static final long VERIFY_WINDOW_MS = 5 * 60_000L;
    private static long verifiedUntil = 0;

    private static final int[] DELAYS = {0, 15, 30, 60, 120, 300, 600, 1800};

    private static final String[] DISPLAY_VALUES = {
            Prefs.DISPLAY_AUTO, Prefs.DISPLAY_SECONDARY, Prefs.DISPLAY_PRIMARY};

    private static final String[] LANG_VALUES = {Prefs.LANG_AUTO, "en", "ru", "et"};

    private Prefs prefs;
    private Unlocker verifyUnlocker;
    private LinearLayout list;

    public static void resetVerification() {
        verifiedUntil = 0;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Самовосстановление: защита включена, а служба не запущена.
        if (prefs.isEnabled() && !LockEngine.isRunning()) {
            LockEngine.send(this, LockEngine.ACTION_REFRESH);
        }
        String mode = prefs.getMode();
        long now = SystemClock.elapsedRealtime();
        if (prefs.hasSecret(mode) && now > verifiedUntil) {
            showVerify(mode);
        } else {
            verifiedUntil = now + VERIFY_WINDOW_MS;
            showSettings();
        }
    }

    // ---------- проверка владельца ----------

    private void showVerify(String mode) {
        verifyUnlocker = new Unlocker(this, mode, () -> {
            verifiedUntil = SystemClock.elapsedRealtime() + VERIFY_WINDOW_MS;
            verifyUnlocker = null;
            showSettings();
        });
        LockPanel panel = new LockPanel(this, verifyUnlocker, true, t(R.string.verify_title), false);
        setContentView(panel);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (verifyUnlocker != null && e.getKeyCode() != KeyEvent.KEYCODE_BACK
                && verifyUnlocker.handleKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (verifyUnlocker != null && verifyUnlocker.handleMotion(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    // ---------- экран настроек ----------

    private void showSettings() {
        verifyUnlocker = null;
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);

        TextView title = text("🔒 Thor Lock  v" + VERSION, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(title);
        String stateText;
        if (!prefs.isEnabled()) stateText = t(R.string.state_off);
        else if (a11yRunning()) stateText = t(R.string.state_a11y);
        else if (LockEngine.isRunning()) stateText = t(R.string.state_service);
        else stateText = t(R.string.state_starting);
        TextView state = text(stateText, 15);
        state.setTextColor(prefs.isEnabled() ? 0xFF66BB6A : 0xFFFF8A65);
        list.addView(state);

        // --- язык ---
        section(t(R.string.sec_language));
        String[] langLabels = {t(R.string.lang_auto), "English", "Русский", "Eesti"};
        Spinner lang = spinner(langLabels);
        lang.setSelection(indexOf(LANG_VALUES, prefs.getLanguage()));
        lang.setOnItemSelectedListener(new SimpleSelect(pos -> {
            if (LANG_VALUES[pos].equals(prefs.getLanguage())) return;
            prefs.setLanguage(LANG_VALUES[pos]);
            list.post(this::showSettings);
        }));
        list.addView(lang);

        // --- разрешения ---
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean notifOk = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        PowerManager pm = getSystemService(PowerManager.class);
        boolean batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());

        section(t(R.string.sec_permissions));
        if (a11yRunning()) {
            list.addView(text(t(R.string.a11y_on), 15));
        } else {
            list.addView(button(t(R.string.a11y_enable), v -> {
                toast(t(R.string.a11y_toast));
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }));
            list.addView(text(t(R.string.a11y_restricted), 13));
            list.addView(button(t(R.string.open_app_info), v ->
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())))));
        }
        if (overlayOk) list.addView(text(t(R.string.overlay_ok), 15));
        else list.addView(button(t(R.string.overlay_ask), v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))));
        if (batteryOk) list.addView(text(t(R.string.battery_ok), 15));
        else list.addView(button(t(R.string.battery_ask), v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ex) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }));
        if (notifOk) list.addView(text(t(R.string.notif_ok), 15));
        else list.addView(button(t(R.string.notif_ask), v ->
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1)));

        // --- способ разблокировки ---
        section(t(R.string.sec_method));
        String mode = prefs.getMode();
        RadioGroup rg = new RadioGroup(this);
        RadioButton rbPattern = new RadioButton(this);
        rbPattern.setId(View.generateViewId());
        rbPattern.setText(t(R.string.method_pattern, mark(Prefs.MODE_PATTERN)));
        RadioButton rbPad = new RadioButton(this);
        rbPad.setId(View.generateViewId());
        rbPad.setText(t(R.string.method_gamepad, mark(Prefs.MODE_GAMEPAD)));
        rg.addView(rbPattern);
        rg.addView(rbPad);
        rg.check(Prefs.MODE_PATTERN.equals(mode) ? rbPattern.getId() : rbPad.getId());
        rg.setOnCheckedChangeListener((g, id) -> {
            String m = id == rbPattern.getId() ? Prefs.MODE_PATTERN : Prefs.MODE_GAMEPAD;
            prefs.setMode(m);
            if (!prefs.hasSecret(m) && prefs.isEnabled()) {
                prefs.setEnabled(false);
                LockService.stop(this);
                toast(t(R.string.toast_mode_no_secret));
            }
            list.post(this::showSettings);
        });
        list.addView(rg);

        list.addView(button(t(R.string.btn_set_pattern), v -> openSetup(Prefs.MODE_PATTERN)));
        list.addView(button(t(R.string.btn_set_combo), v -> openSetup(Prefs.MODE_GAMEPAD)));

        // --- задержка ---
        section(t(R.string.sec_delay));
        String[] delayLabels = {t(R.string.delay_now), t(R.string.delay_sec, 15), t(R.string.delay_sec, 30),
                t(R.string.delay_min1), t(R.string.delay_min2), t(R.string.delay_min, 5),
                t(R.string.delay_min, 10), t(R.string.delay_min, 30)};
        Spinner delay = spinner(delayLabels);
        delay.setSelection(indexOf(DELAYS, prefs.getDelaySec(), 3));
        delay.setOnItemSelectedListener(new SimpleSelect(pos -> prefs.setDelaySec(DELAYS[pos])));
        list.addView(delay);
        list.addView(text(t(R.string.delay_hint), 13));

        // --- экран для рисунка ---
        section(t(R.string.sec_pattern_display));
        String[] dispLabels = {t(R.string.disp_auto), t(R.string.disp_bottom), t(R.string.disp_top)};
        Spinner disp = spinner(dispLabels);
        disp.setSelection(indexOf(DISPLAY_VALUES, prefs.getPatternDisplay()));
        disp.setOnItemSelectedListener(new SimpleSelect(pos -> prefs.setPatternDisplay(DISPLAY_VALUES[pos])));
        list.addView(disp);


        // --- защита игры ---
        section(t(R.string.sec_game_return));
        CheckBox autoReturn = new CheckBox(this);
        autoReturn.setText(t(R.string.auto_return));
        autoReturn.setChecked(prefs.isAutoReturn());
        autoReturn.setOnCheckedChangeListener((v, on) -> {
            prefs.setAutoReturn(on);
            EventLog.add(this, "Settings: auto return game " + (on ? "ON" : "OFF"));
        });
        list.addView(autoReturn);
        list.addView(text(t(R.string.auto_return_hint), 13));

        // --- фон блокировки ---
        section(t(R.string.sec_background));
        list.addView(text(t(R.string.bg_file, fileName(prefs.getBgUri(), R.string.bg_none)), 14));
        list.addView(button(t(R.string.bg_pick), v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            try {
                startActivityForResult(i, REQ_BG);
            } catch (Exception e) {
                toast(t(R.string.video_no_picker));
            }
        }));
        if (prefs.getBgUri() != null) {
            list.addView(button(t(R.string.bg_remove), v -> {
                prefs.setBgUri(null);
                EventLog.add(this, "Settings: background removed");
                showSettings();
            }));
        }
        CheckBox dim = new CheckBox(this);
        dim.setText(t(R.string.bg_dim));
        dim.setChecked(prefs.isBgDim());
        dim.setOnCheckedChangeListener((v, on) -> prefs.setBgDim(on));
        list.addView(dim);
        list.addView(text(t(R.string.bg_hint), 13));

        // --- видео ---
        section(t(R.string.sec_video));
        CheckBox videoOn = new CheckBox(this);
        videoOn.setText(t(R.string.video_show));
        videoOn.setChecked(prefs.isVideoEnabled());
        videoOn.setOnCheckedChangeListener((v, on) -> {
            if (on && prefs.getVideoUri() == null) {
                toast(t(R.string.video_pick_first));
                v.setChecked(false);
                return;
            }
            prefs.setVideoEnabled(on);
        });
        list.addView(videoOn);
        list.addView(text(t(R.string.video_file, videoName()), 14));
        list.addView(button(t(R.string.video_pick), v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            try {
                startActivityForResult(i, REQ_VIDEO);
            } catch (Exception e) {
                toast(t(R.string.video_no_picker));
            }
        }));
        CheckBox mute = new CheckBox(this);
        mute.setText(t(R.string.video_mute));
        mute.setChecked(prefs.isVideoMuted());
        mute.setOnCheckedChangeListener((v, on) -> prefs.setVideoMuted(on));
        list.addView(mute);
        list.addView(button(t(R.string.video_test), v -> {
            if (!prefs.isVideoEnabled() || prefs.getVideoUri() == null) {
                toast(t(R.string.video_test_need));
                return;
            }
            if (!Settings.canDrawOverlays(this) && !a11yRunning()) {
                toast(t(R.string.need_a11y_or_overlay));
                return;
            }
            LockEngine.send(this, LockEngine.ACTION_TEST_VIDEO);
        }));
        list.addView(text(t(R.string.video_hint), 13));

        // --- прочее ---
        section(t(R.string.sec_other));
        CheckBox boot = new CheckBox(this);
        boot.setText(t(R.string.lock_on_boot));
        boot.setChecked(prefs.isLockOnBoot());
        boot.setOnCheckedChangeListener((v, on) -> prefs.setLockOnBoot(on));
        list.addView(boot);

        Switch sw = new Switch(this);
        sw.setText(t(R.string.protection_switch));
        sw.setTextSize(18);
        sw.setPadding(0, dp(12), 0, dp(12));
        sw.setChecked(prefs.isEnabled());
        sw.setOnCheckedChangeListener((v, on) -> {
            if (on) {
                if (!Settings.canDrawOverlays(this) && !a11yRunning()) {
                    toast(t(R.string.toast_need_perm_first));
                    v.setChecked(false);
                    return;
                }
                if (!prefs.hasSecret(prefs.getMode())) {
                    toast(t(R.string.toast_set_secret_first));
                    v.setChecked(false);
                    return;
                }
                prefs.setEnabled(true);
                LockEngine.send(this, LockEngine.ACTION_REFRESH);
                list.postDelayed(this::showSettings, 500);
            } else {
                prefs.setEnabled(false);
                LockService.stop(this);
            }
        });
        list.addView(sw);

        list.addView(button(t(R.string.btn_test_lock), v -> {
            if (!Settings.canDrawOverlays(this) && !a11yRunning()) {
                toast(t(R.string.toast_no_perm));
                return;
            }
            if (!prefs.hasSecret(prefs.getMode())) {
                toast(t(R.string.toast_set_password_first));
                return;
            }
            LockEngine.send(this, LockEngine.ACTION_TEST_LOCK);
        }));

        section(t(R.string.sec_forgot));
        list.addView(text(t(R.string.forgot_text), 14));

        section(t(R.string.sec_log));
        String logText = EventLog.get(this);
        TextView log = text(logText.isEmpty() ? t(R.string.log_empty) : logText, 11);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        LinearLayout row = new LinearLayout(this);
        row.addView(button(t(R.string.btn_refresh), v -> showSettings()));
        row.addView(button(t(R.string.btn_clear), v -> {
            EventLog.clear(this);
            showSettings();
        }));
        list.addView(row);
        list.addView(log);

        setContentView(scroll);
    }

    private static final int REQ_VIDEO = 42;
    private static final int REQ_BG = 43;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != REQ_VIDEO && req != REQ_BG) || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            EventLog.add(this, "Could not keep file access: " + e);
        }
        if (req == REQ_VIDEO) {
            prefs.setVideoUri(uri.toString());
            prefs.setVideoEnabled(true);
            toast(t(R.string.video_selected));
        } else {
            prefs.setBgUri(uri.toString());
            EventLog.add(this, "Settings: background set");
            toast(t(R.string.bg_selected));
        }
        // После выбора файла снова проверять пароль не нужно.
        verifiedUntil = SystemClock.elapsedRealtime() + VERIFY_WINDOW_MS;
    }

    private String videoName() {
        return fileName(prefs.getVideoUri(), R.string.video_none);
    }

    private String fileName(String u, int noneRes) {
        if (u == null) return t(noneRes);
        try (Cursor c = getContentResolver().query(Uri.parse(u),
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return t(R.string.video_noname);
    }

    private boolean a11yRunning() {
        LockEngine e = LockEngine.current;
        return e != null && e.accessibility;
    }

    private String mark(String mode) {
        return prefs.hasSecret(mode) ? t(R.string.mark_set) : t(R.string.mark_unset);
    }

    private void openSetup(String mode) {
        startActivity(new Intent(this, SetSecretActivity.class).putExtra(SetSecretActivity.EXTRA_MODE, mode));
    }

    // ---------- helpers ----------

    static final String VERSION = "0.3.0";

    private String t(int id) {
        return L.t(this, id);
    }

    private String t(int id, Object... args) {
        return L.t(this, id, args);
    }

    private void section(String s) {
        TextView t = text(s, 13);
        t.setTextColor(0xFF4FC3F7);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(4));
        list.addView(t);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        return s;
    }

    private static int indexOf(int[] arr, int v, int def) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return def;
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private interface IntConsumer {
        void accept(int v);
    }

    private static class SimpleSelect implements AdapterView.OnItemSelectedListener {
        private final IntConsumer c;

        SimpleSelect(IntConsumer c) { this.c = c; }

        @Override
        public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { c.accept(pos); }

        @Override
        public void onNothingSelected(AdapterView<?> p) {}
    }
}
