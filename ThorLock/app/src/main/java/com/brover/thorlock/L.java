package com.brover.thorlock;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/** Строки на выбранном в приложении языке (или как в системе). */
public final class L {
    private static String cachedLang;
    private static Resources cachedRes;

    private L() {}

    public static synchronized Resources res(Context c) {
        Context app = c.getApplicationContext();
        String lang = new Prefs(app).getLanguage();
        if (Prefs.LANG_AUTO.equals(lang)) return app.getResources();
        if (cachedRes == null || !lang.equals(cachedLang)) {
            Configuration conf = new Configuration(app.getResources().getConfiguration());
            conf.setLocale(Locale.forLanguageTag(lang));
            cachedRes = app.createConfigurationContext(conf).getResources();
            cachedLang = lang;
        }
        return cachedRes;
    }

    public static String t(Context c, int id) {
        return res(c).getString(id);
    }

    public static String t(Context c, int id, Object... args) {
        return res(c).getString(id, args);
    }
}
