package com.brover.thorlock;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Фон экрана блокировки: картинка или анимированный GIF/WebP.
 * Декодируется в фоновом потоке и уменьшается под размер экрана, чтобы не съедать память
 * (на Thor рядом работает тяжёлый эмулятор).
 */
final class LockBackground {

    interface Target {
        /** Вызывается в главном потоке. Если панель уже закрыта — вернуть false. */
        boolean onLoaded(Drawable d);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LockBackground() {}

    static boolean isSet(Context c) {
        return new Prefs(c).getBgUri() != null;
    }

    static void load(Context c, int width, int height, Target target) {
        String u = new Prefs(c).getBgUri();
        if (u == null) return;
        Context app = c.getApplicationContext();
        Uri uri = Uri.parse(u);
        int w = Math.max(1, width), h = Math.max(1, height);
        EXEC.execute(() -> {
            Drawable d = null;
            try {
                d = decode(app, uri, w, h);
            } catch (Throwable t) {
                EventLog.add(app, "Background: could not load (" + t + "). Pick the file again.");
            }
            final Drawable result = d;
            if (result == null) return;
            MAIN.post(() -> {
                boolean used = target.onLoaded(result);
                if (used && Build.VERSION.SDK_INT >= 28 && result instanceof AnimatedImageDrawable) {
                    ((AnimatedImageDrawable) result).start();
                }
            });
        });
    }

    private static Drawable decode(Context app, Uri uri, int w, int h) throws Exception {
        ContentResolver cr = app.getContentResolver();
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source src = ImageDecoder.createSource(cr, uri);
            return ImageDecoder.decodeDrawable(src, (decoder, info, source) -> {
                Size s = info.getSize();
                decoder.setTargetSampleSize(sampleSize(s.getWidth(), s.getHeight(), w, h));
            });
        }
        // Android 8–8.1: только статичная картинка.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, w, h);
        Bitmap b;
        try (InputStream in = cr.openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, opts);
        }
        return b == null ? null : new BitmapDrawable(app.getResources(), b);
    }

    /** Во сколько раз уменьшить, чтобы картинка всё ещё закрывала экран целиком. */
    private static int sampleSize(int srcW, int srcH, int dstW, int dstH) {
        int sample = 1;
        while (srcW / (sample * 2) >= dstW && srcH / (sample * 2) >= dstH) sample *= 2;
        return sample;
    }
}
