package com.vlcplayer.app;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

public class AppLanguageManager {
    private static final String PREF = "app_lang";
    private static final String KEY = "language";

    public static final String[][] LANGUAGES = {
        {"Tiếng Việt",  "vi"},
        {"English",     "en"},
        {"中文",         "zh"},
        {"日本語",       "ja"},
        {"한국어",       "ko"},
        {"Français",    "fr"},
        {"Español",     "es"},
        {"Deutsch",     "de"},
        {"Русский",     "ru"},
        {"ภาษาไทย",    "th"},
    };

    public static String getSavedLanguage(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "vi");
    }

    public static void saveLanguage(Context ctx, String langCode) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, langCode).apply();
    }

    public static Context applyLanguage(Context ctx) {
        String lang = getSavedLanguage(ctx);
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration(ctx.getResources().getConfiguration());
        config.setLocale(locale);
        return ctx.createConfigurationContext(config);
    }

    public static String getLanguageName(Context ctx) {
        String code = getSavedLanguage(ctx);
        for (String[] l : LANGUAGES) { if (l[1].equals(code)) return l[0]; }
        return "Tiếng Việt";
    }
}
