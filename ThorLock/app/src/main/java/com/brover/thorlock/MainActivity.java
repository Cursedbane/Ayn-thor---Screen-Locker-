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
    private static final String[] DELAY_LABELS = {
            "Сразу", "15 секунд", "30 секунд", "1 минута", "2 минуты", "5 минут", "10 минут", "30 минут"};

    private static final String[] DISPLAY_VALUES = {
            Prefs.DISPLAY_AUTO, Prefs.DISPLAY_SECONDARY, Prefs.DISPLAY_PRIMARY};
    private static final String[] DISPLAY_LABELS = {
            "Авто (нижний, если есть)", "Нижний экран", "Верхний экран"};

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
        LockPanel panel = new LockPanel(this, verifyUnlocker, true, "Подтверди, что это ты", false);
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

        TextView title = text("🔒 Thor Lock", 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(title);
        String stateText;
        if (!prefs.isEnabled()) stateText = "○ Защита выключена";
        else if (a11yRunning()) stateText = "● Защита работает (через спецвозможности — надёжно)";
        else if (LockEngine.isRunning()) stateText = "● Защита работает (фоновая служба — может умирать, включи спецвозможность)";
        else stateText = "● Защита включена, запускается…";
        TextView state = text(stateText, 15);
        state.setTextColor(prefs.isEnabled() ? 0xFF66BB6A : 0xFFFF8A65);
        list.addView(state);

        // --- разрешения ---
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean notifOk = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        PowerManager pm = getSystemService(PowerManager.class);
        boolean batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());

        section("Разрешения");
        if (a11yRunning()) {
            list.addView(text("✓ Спецвозможность Thor Lock включена", 15));
        } else {
            list.addView(button("Включить спецвозможность Thor Lock (ВАЖНО: без неё защита отключается, когда закрываешь приложение)", v -> {
                toast("Найди «Thor Lock» в списке (может быть в «Установленные приложения») и включи");
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }));
            list.addView(text("Если переключатель серый и пишет «Ограниченная настройка»: открой сведения о приложении → ⋮ в правом верхнем углу → «Разрешить ограниченные настройки», затем включи снова.", 13));
            list.addView(button("Открыть сведения о приложении", v ->
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())))));
        }
        if (overlayOk) list.addView(text("✓ Показ поверх других окон", 15));
        else list.addView(button("Разрешить «Поверх других окон» (нужно без спецвозможности)", v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))));
        if (batteryOk) list.addView(text("✓ Без ограничений батареи", 15));
        else list.addView(button("Отключить оптимизацию батареи (рекомендуется)", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ex) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }));
        if (notifOk) list.addView(text("✓ Уведомления", 15));
        else list.addView(button("Разрешить уведомления (для фоновой работы)", v ->
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1)));

        // --- способ разблокировки ---
        section("Способ разблокировки");
        String mode = prefs.getMode();
        RadioGroup rg = new RadioGroup(this);
        RadioButton rbPattern = new RadioButton(this);
        rbPattern.setId(View.generateViewId());
        rbPattern.setText("Рисунок по 9 точкам " + mark(Prefs.MODE_PATTERN));
        RadioButton rbPad = new RadioButton(this);
        rbPad.setId(View.generateViewId());
        rbPad.setText("Комбинация кнопок геймпада " + mark(Prefs.MODE_GAMEPAD));
        rg.addView(rbPattern);
        rg.addView(rbPad);
        rg.check(Prefs.MODE_PATTERN.equals(mode) ? rbPattern.getId() : rbPad.getId());
        rg.setOnCheckedChangeListener((g, id) -> {
            String m = id == rbPattern.getId() ? Prefs.MODE_PATTERN : Prefs.MODE_GAMEPAD;
            prefs.setMode(m);
            if (!prefs.hasSecret(m) && prefs.isEnabled()) {
                prefs.setEnabled(false);
                LockService.stop(this);
                toast("Защита выключена: для этого способа ещё не задан пароль");
            }
            list.post(this::showSettings);
        });
        list.addView(rg);

        list.addView(button("Задать / изменить рисунок", v -> openSetup(Prefs.MODE_PATTERN)));
        list.addView(button("Задать / изменить комбинацию кнопок", v -> openSetup(Prefs.MODE_GAMEPAD)));

        // --- задержка ---
        section("Блокировать, если экран выключен дольше");
        Spinner delay = spinner(DELAY_LABELS);
        delay.setSelection(indexOf(DELAYS, prefs.getDelaySec(), 3));
        delay.setOnItemSelectedListener(new SimpleSelect(pos -> prefs.setDelaySec(DELAYS[pos])));
        list.addView(delay);
        list.addView(text("Для проверки поставь «Сразу», закрой крышку на пару секунд и открой.", 13));

        // --- экран для рисунка ---
        section("Где рисовать узор");
        Spinner disp = spinner(DISPLAY_LABELS);
        disp.setSelection(indexOf(DISPLAY_VALUES, prefs.getPatternDisplay()));
        disp.setOnItemSelectedListener(new SimpleSelect(pos -> prefs.setPatternDisplay(DISPLAY_VALUES[pos])));
        list.addView(disp);

        // --- видео ---
        section("Видео при разблокировке (как на Steam Deck)");
        CheckBox videoOn = new CheckBox(this);
        videoOn.setText("Показывать видео после разблокировки");
        videoOn.setChecked(prefs.isVideoEnabled());
        videoOn.setOnCheckedChangeListener((v, on) -> {
            if (on && prefs.getVideoUri() == null) {
                toast("Сначала выбери видео");
                v.setChecked(false);
                return;
            }
            prefs.setVideoEnabled(on);
        });
        list.addView(videoOn);
        list.addView(text("Файл: " + videoName(), 14));
        list.addView(button("Выбрать видео", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            try {
                startActivityForResult(i, REQ_VIDEO);
            } catch (Exception e) {
                toast("Не нашёл приложение для выбора файлов");
            }
        }));
        CheckBox mute = new CheckBox(this);
        mute.setText("Без звука");
        mute.setChecked(prefs.isVideoMuted());
        mute.setOnCheckedChangeListener((v, on) -> prefs.setVideoMuted(on));
        list.addView(mute);
        list.addView(button("Проверить видео", v -> {
            if (!prefs.isVideoEnabled() || prefs.getVideoUri() == null) {
                toast("Выбери видео и включи галочку");
                return;
            }
            if (!Settings.canDrawOverlays(this) && !a11yRunning()) {
                toast("Нужна спецвозможность или «Поверх других окон»");
                return;
            }
            LockEngine.send(this, LockEngine.ACTION_TEST_VIDEO);
        }));
        list.addView(text("Видео идёт на верхнем экране, пропустить — тап или любая кнопка. Лучше всего mp4 (H.264), короткое, 3–15 секунд.", 13));

        // --- прочее ---
        section("Прочее");
        CheckBox boot = new CheckBox(this);
        boot.setText("Блокировать после включения устройства");
        boot.setChecked(prefs.isLockOnBoot());
        boot.setOnCheckedChangeListener((v, on) -> prefs.setLockOnBoot(on));
        list.addView(boot);

        Switch sw = new Switch(this);
        sw.setText("Защита включена");
        sw.setTextSize(18);
        sw.setPadding(0, dp(12), 0, dp(12));
        sw.setChecked(prefs.isEnabled());
        sw.setOnCheckedChangeListener((v, on) -> {
            if (on) {
                if (!Settings.canDrawOverlays(this) && !a11yRunning()) {
                    toast("Сначала включи спецвозможность или «Поверх других окон»");
                    v.setChecked(false);
                    return;
                }
                if (!prefs.hasSecret(prefs.getMode())) {
                    toast("Сначала задай рисунок или комбинацию");
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

        list.addView(button("Проверить блокировку (сама снимется через 60 с)", v -> {
            if (!Settings.canDrawOverlays(this) && !a11yRunning()) {
                toast("Нет разрешения «Поверх других окон» и спецвозможность выключена");
                return;
            }
            if (!prefs.hasSecret(prefs.getMode())) {
                toast("Сначала задай пароль");
                return;
            }
            LockEngine.send(this, LockEngine.ACTION_TEST_LOCK);
        }));

        section("Если забыл пароль");
        list.addView(text("Перезагрузи Thor в безопасный режим (зажми «Перезагрузить» в меню питания) — "
                + "сторонние приложения там не запускаются. Затем удали или очисти данные Thor Lock "
                + "в настройках Android.", 14));

        section("Журнал событий");
        TextView log = text(EventLog.get(this), 11);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        LinearLayout row = new LinearLayout(this);
        row.addView(button("Обновить", v -> showSettings()));
        row.addView(button("Очистить", v -> {
            EventLog.clear(this);
            showSettings();
        }));
        list.addView(row);
        list.addView(log);

        setContentView(scroll);
    }

    private static final int REQ_VIDEO = 42;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VIDEO || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            EventLog.add(this, "Видео: не удалось сохранить доступ к файлу: " + e);
        }
        prefs.setVideoUri(uri.toString());
        prefs.setVideoEnabled(true);
        // После выбора файла снова проверять пароль не нужно.
        verifiedUntil = SystemClock.elapsedRealtime() + VERIFY_WINDOW_MS;
        toast("Видео выбрано");
    }

    private String videoName() {
        String u = prefs.getVideoUri();
        if (u == null) return "не выбран";
        try (Cursor c = getContentResolver().query(Uri.parse(u),
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return "выбран (нет доступа к имени — выбери заново, если не играет)";
    }

    private boolean a11yRunning() {
        LockEngine e = LockEngine.current;
        return e != null && e.accessibility;
    }

    private String mark(String mode) {
        return prefs.hasSecret(mode) ? "(задан ✓)" : "(не задан)";
    }

    private void openSetup(String mode) {
        startActivity(new Intent(this, SetSecretActivity.class).putExtra(SetSecretActivity.EXTRA_MODE, mode));
    }

    // ---------- helpers ----------

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
