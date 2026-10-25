package ru.russkoetv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "RusskoeTV";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final String SERVER = BuildConfig.UPDATE_SERVER;
    private static final String CHANNELS_FILE = "channels.json";
    /** Отмечаем перед запросом разрешения: Android закрывает приложение, когда его выдают. */
    private static final String UPDATE_AFTER_PERMISSION = "update_after_permission";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ExoPlayer player;
    private View panel;
    private ListView list;
    private TextView info;
    private ArrayAdapter<String> adapter;
    private SharedPreferences prefs;

    /** Каналы: { название, адрес трансляции }. */
    private String[][] channels;
    /** Текст списка каналов, из которого загружен channels, — чтобы не применять одно и то же дважды. */
    private String channelsJson;

    private int current;
    /** На экране название канала — его убираем, когда канал загрузился. */
    private boolean titleShown;
    private String digits = "";
    private long lastBackPress;

    private final Runnable hideInfo = () -> info.setVisibility(View.GONE);
    private final Runnable applyDigits = () -> {
        int number = Integer.parseInt(digits);
        digits = "";
        if (number >= 1 && number <= channels.length) {
            play(number - 1);
        } else {
            showInfo("Нет канала с номером " + number, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        panel = findViewById(R.id.panel);
        list = findViewById(R.id.list);
        info = findViewById(R.id.info);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        loadChannels();
        adapter = new ArrayAdapter<>(this, R.layout.item_channel, channelNames());
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            play(position);
            hideList();
        });
        findViewById(R.id.update).setOnClickListener(v -> checkUpdates(true));

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http))
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && titleShown) {
                    handler.removeCallbacks(hideInfo);
                    handler.postDelayed(hideInfo, 3000);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Отстали от прямого эфира — просто возвращаемся к его концу
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                showInfo(channelTitle(current) + "\nКанал сейчас недоступен. Выберите другой.", 0);
            }
        });
        PlayerView playerView = findViewById(R.id.player);
        playerView.setPlayer(player);

        current = Math.min(prefs.getInt("channel", 0), channels.length - 1);
        showList();
        // При запуске: продолжаем обновление, если ради него выдали разрешение,
        // иначе тихо берём свежий список каналов и подсказываем, если вышла новая версия
        checkUpdates(resumeUpdateAfterPermission());
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // Если Android не закрыл приложение после выдачи разрешения
        if (resumeUpdateAfterPermission()) {
            checkUpdates(true);
        }
    }

    private boolean resumeUpdateAfterPermission() {
        if (!prefs.getBoolean(UPDATE_AFTER_PERMISSION, false)) {
            return false;
        }
        prefs.edit().remove(UPDATE_AFTER_PERMISSION).apply();
        return Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls();
    }

    @Override
    protected void onStart() {
        super.onStart();
        play(current);
    }

    @Override
    protected void onStop() {
        super.onStop();
        player.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        player.release();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        int key = event.getKeyCode();

        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
            if (digits.length() < 3) {
                digits += (key - KeyEvent.KEYCODE_0);
            }
            showInfo("Канал " + digits, 0);
            handler.removeCallbacks(applyDigits);
            handler.postDelayed(applyDigits, 1500);
            return true;
        }
        if (key == KeyEvent.KEYCODE_CHANNEL_UP) {
            play(current + 1);
            return true;
        }
        if (key == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            play(current - 1);
            return true;
        }

        if (panel.getVisibility() == View.VISIBLE) {
            // Список открыт: стрелки и OK обрабатывают сам список и кнопка «Обновить»
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                hideList();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                play(current - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                play(current + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MENU:
                showList();
                return true;
            case KeyEvent.KEYCODE_BACK:
                long now = System.currentTimeMillis();
                if (now - lastBackPress < 2500) {
                    finish();
                } else {
                    lastBackPress = now;
                    showInfo("Нажмите «Назад» ещё раз, чтобы выйти", 2500);
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void play(int index) {
        int count = channels.length;
        current = (index % count + count) % count;
        prefs.edit().putInt("channel", current).apply();
        list.setItemChecked(current, true);

        player.setMediaItem(MediaItem.fromUri(channels[current][1]));
        player.prepare();
        player.play();
        showInfo(channelTitle(current), 0);
        titleShown = true;
    }

    private void showList() {
        panel.setVisibility(View.VISIBLE);
        list.setItemChecked(current, true);
        list.setSelection(current);
        list.requestFocus();
    }

    private void hideList() {
        panel.setVisibility(View.GONE);
    }

    /** durationMs = 0 — держать надпись, пока канал не загрузится. */
    private void showInfo(String text, long durationMs) {
        info.setText(text);
        info.setVisibility(View.VISIBLE);
        titleShown = false;
        handler.removeCallbacks(hideInfo);
        if (durationMs > 0) {
            handler.postDelayed(hideInfo, durationMs);
        }
    }

    private String channelTitle(int index) {
        return (index + 1) + ". " + channels[index][0];
    }

    private List<String> channelNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < channels.length; i++) {
            names.add((i + 1) + ".  " + channels[i][0]);
        }
        return names;
    }

    // ---------- Список каналов ----------

    /** Последний скачанный с сервера список, а если его нет — встроенный в приложение. */
    private void loadChannels() {
        File cached = new File(getFilesDir(), CHANNELS_FILE);
        if (cached.exists()) {
            try (InputStream in = new FileInputStream(cached)) {
                String json = readText(in);
                channels = parseChannels(json);
                channelsJson = json;
                return;
            } catch (IOException | JSONException ignored) {
                // Файл испорчен — берём встроенный список
            }
        }
        try (InputStream in = getAssets().open(CHANNELS_FILE)) {
            channelsJson = readText(in);
            channels = parseChannels(channelsJson);
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Встроенный список каналов повреждён", e);
        }
    }

    /** Применяет новый список, стараясь не прерывать канал, который сейчас идёт. */
    private void applyChannels(String json, String[][] fresh) {
        try (OutputStream out = new FileOutputStream(new File(getFilesDir(), CHANNELS_FILE))) {
            out.write(json.getBytes("UTF-8"));
        } catch (IOException ignored) {
            // Не сохранилось — список всё равно применится до перезапуска
        }
        String[] playing = channels[current];
        channels = fresh;
        channelsJson = json;
        adapter.clear();
        adapter.addAll(channelNames());

        int index = indexOf(playing[1], 1);
        if (index >= 0) {
            current = index;
            list.setItemChecked(current, true);
        } else {
            // Адрес канала поменяли (например, починили) — переключаемся на новый
            play(Math.max(indexOf(playing[0], 0), 0));
        }
    }

    private int indexOf(String value, int field) {
        for (int i = 0; i < channels.length; i++) {
            if (channels[i][field].equals(value)) {
                return i;
            }
        }
        return -1;
    }

    private static String[][] parseChannels(String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        if (array.length() == 0) {
            throw new JSONException("Пустой список каналов");
        }
        String[][] result = new String[array.length()][];
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            result[i] = new String[]{item.getString("name"), item.getString("url")};
        }
        return result;
    }

    // ---------- Обновления ----------

    /** manual = true — нажата кнопка «Обновить»: сразу ставим новую версию и сообщаем результат. */
    private void checkUpdates(boolean manual) {
        if (manual) {
            showInfo("Проверяю обновления…", 0);
        }
        io.execute(() -> {
            String json = fetchOrNull(SERVER + CHANNELS_FILE);
            String version = fetchOrNull(SERVER + "version.json");
            runOnUiThread(() -> onUpdateInfo(manual, json, version));
        });
    }

    private void onUpdateInfo(boolean manual, String json, String version) {
        if (isFinishing()) {
            return;
        }
        if (json != null && !json.equals(channelsJson)) {
            try {
                applyChannels(json, parseChannels(json));
            } catch (JSONException ignored) {
                // На сервере испорченный список — оставляем текущий
            }
        }

        int latest = -1;
        String apk = null;
        if (version != null) {
            try {
                JSONObject v = new JSONObject(version);
                latest = v.getInt("versionCode");
                apk = v.getString("apk");
            } catch (JSONException ignored) {
                // Нет сведений о версии — обновлять нечего
            }
        }

        Log.i(TAG, "Проверка обновлений: на сервере версия " + latest + ", установлена " + BuildConfig.VERSION_CODE
                + (json == null ? ", список каналов не получен" : ""));
        if (latest > BuildConfig.VERSION_CODE) {
            if (manual) {
                installUpdate(apk);
            } else {
                showInfo("Вышла новая версия. Откройте список каналов и нажмите «Обновить»", 8000);
            }
        } else if (manual) {
            if (json == null && version == null) {
                showInfo("Сервер обновлений недоступен. Попробуйте позже.", 5000);
            } else {
                showInfo("Всё обновлено. Каналов: " + channels.length
                        + ". Версия " + BuildConfig.VERSION_NAME + " — последняя.", 5000);
            }
        }
    }

    private void installUpdate(String apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            // Android 8+ один раз спрашивает разрешение на установку обновлений
            Log.i(TAG, "Нет разрешения на установку — открываю настройки");
            showInfo("Включите «Русское ТВ», затем снова откройте приложение — обновление продолжится само", 10000);
            prefs.edit().putBoolean(UPDATE_AFTER_PERMISSION, true).apply();
            try {
                // Отдельной задачей: иначе после перезапуска из меню телевизора снова откроются настройки
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                prefs.edit().remove(UPDATE_AFTER_PERMISSION).apply();
                showInfo("Разрешите «Русскому ТВ» установку из неизвестных источников в настройках телевизора, "
                        + "затем снова нажмите «Обновить»", 15000);
            }
            return;
        }

        // До Android 7 установщик открывает только файлы из общей папки
        File dir = Build.VERSION.SDK_INT >= 24 ? new File(getCacheDir(), "updates") : getExternalCacheDir();
        if (dir == null) {
            showInfo("Не удалось сохранить обновление: нет места для файла", 5000);
            return;
        }
        File file = new File(dir, "update.apk");
        showInfo("Скачиваю обновление…", 0);
        io.execute(() -> {
            boolean ok;
            try {
                dir.mkdirs();
                download(SERVER + apk, file);
                ok = true;
            } catch (IOException e) {
                Log.w(TAG, "Не удалось скачать обновление", e);
                ok = false;
            }
            boolean downloaded = ok;
            runOnUiThread(() -> {
                if (downloaded) {
                    startInstaller(file);
                } else {
                    showInfo("Не удалось скачать обновление. Попробуйте позже.", 5000);
                }
            });
        });
    }

    private void startInstaller(File apk) {
        Uri uri = Build.VERSION.SDK_INT >= 24
                ? FileProvider.getUriForFile(this, getPackageName() + ".files", apk)
                : Uri.fromFile(apk);
        // Без NEW_TASK: иначе может всплыть старое окно установщика из прошлого обновления
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            Log.i(TAG, "Открываю установщик: " + uri);
            showInfo("Подтвердите установку обновления", 5000);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Нет установщика приложений", e);
            showInfo("Телевизор не смог открыть установщик приложений", 5000);
        }
    }

    // ---------- Сеть ----------

    private static String fetchOrNull(String url) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            HttpURLConnection connection = open(url);
            try (InputStream in = connection.getInputStream()) {
                copy(in, buffer);
            } finally {
                connection.disconnect();
            }
            return buffer.toString("UTF-8");
        } catch (IOException e) {
            Log.w(TAG, "Не удалось загрузить " + url, e);
            return null;
        }
    }

    /** Скачивает файл целиком; при обрыве связи начинает заново, до 5 попыток. */
    private static void download(String url, File target) throws IOException {
        IOException failure = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                HttpURLConnection connection = open(url);
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                    copy(in, out);
                    return;
                } finally {
                    connection.disconnect();
                }
            } catch (IOException e) {
                Log.w(TAG, "Обрыв при скачивании " + url + ", попытка " + attempt + ": " + e);
                failure = e;
            }
        }
        throw failure;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("HTTP " + code);
        }
        return connection;
    }

    private static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        copy(in, buffer);
        return buffer.toString("UTF-8");
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] chunk = new byte[16384];
        int n;
        while ((n = in.read(chunk)) != -1) {
            out.write(chunk, 0, n);
        }
    }
}
