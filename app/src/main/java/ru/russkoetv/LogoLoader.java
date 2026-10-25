package ru.russkoetv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Логотипы каналов: из приложения (assets), из сохранённых ранее файлов
 * или, для новых каналов, с сервера обновлений.
 */
final class LogoLoader {
    private final Context context;
    private final String server;
    private final File cacheDir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> memory = new LruCache<>(80);

    LogoLoader(Context context, String server) {
        this.context = context.getApplicationContext();
        this.server = server;
        this.cacheDir = new File(context.getFilesDir(), "logos");
    }

    void load(String logo, ImageView view) {
        view.setTag(logo);
        if (logo == null) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = memory.get(logo);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        io.execute(() -> {
            Bitmap bitmap = read(logo);
            if (bitmap == null) {
                return;
            }
            memory.put(logo, bitmap);
            main.post(() -> {
                // Строку списка могли уже отдать другому каналу
                if (logo.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    void shutdown() {
        io.shutdownNow();
    }

    private Bitmap read(String logo) {
        try (InputStream in = context.getAssets().open(logo)) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException ignored) {
            // Нет в приложении — канал добавлен позже
        }
        File file = new File(cacheDir, new File(logo).getName());
        if (!file.exists()) {
            try {
                cacheDir.mkdirs();
                HttpURLConnection connection = (HttpURLConnection) new URL(server + logo).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        out.write(chunk, 0, n);
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (IOException e) {
                file.delete();
                return null;
            }
        }
        return BitmapFactory.decodeFile(file.getPath());
    }
}
