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
import android.widget.ListView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "RusskoeTV";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    /**
     * Для API RUTUBE представляемся браузером на ПК: часть каналов (например, Первый) не отдаёт эфир
     * телевизорам, а компьютерам отдаёт. Сам поток после этого качается как обычно.
     */
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String SERVER = BuildConfig.UPDATE_SERVER;
    private static final String CHANNELS_FILE = "channels.json";
    private static final String EPG_FILE = "epg.json";
    private static final long EPG_REFRESH_MS = 30 * 60 * 1000;
    /** Отмечаем перед запросом разрешения: Android закрывает приложение, когда его выдают. */
    private static final String UPDATE_AFTER_PERMISSION = "update_after_permission";
    /** Порядок каналов, выбранный пользователем: список id. */
    private static final String ORDER = "order";
    private static final String CURRENT_CHANNEL = "channel_id";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Отдельный поток для ссылок RUTUBE — чтобы переключение канала не ждало загрузку программы. */
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    /** Уже полученные ссылки на эфиры RUTUBE: видео → поток. */
    private final Map<String, String> rutubeStreams = new HashMap<>();

    private ExoPlayer player;
    private View panel;
    private ListView list;
    private TextView info;
    private TextView hint;
    private ChannelAdapter adapter;
    private LogoLoader logos;
    private SharedPreferences prefs;

    /** Каналы в порядке, выбранном пользователем. */
    private List<Channel> channels;
    /** Текст списка каналов, из которого загружен channels, — чтобы не применять одно и то же дважды. */
    private String channelsJson;
    private Epg epg = Epg.EMPTY;

    /** Канал, который сейчас показывается (всегда с видеопотоком, не страница). */
    private int current;
    /** На экране название канала — его убираем, когда канал загрузился. */
    private boolean titleShown;
    private String digits = "";
    /** Перестановка каналов: позиция переставляемого канала или -1. */
    private int moving = -1;

    private final Runnable hideInfo = () -> info.setVisibility(View.GONE);
    private final Runnable applyDigits = () -> {
        int number = Integer.parseInt(digits);
        digits = "";
        if (number >= 1 && number <= channels.size()) {
            play(number - 1);
        } else {
            showInfo("Нет канала с номером " + number, 2000);
        }
    };
    /** Раз в минуту обновляем «что идёт сейчас» в открытом списке. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (panel.getVisibility() == View.VISIBLE) {
                adapter.notifyDataSetChanged();
            }
            handler.postDelayed(this, 60 * 1000);
        }
    };
    private final Runnable refreshEpg = new Runnable() {
        @Override
        public void run() {
            fetchEpg();
            handler.postDelayed(this, EPG_REFRESH_MS);
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
        hint = findViewById(R.id.hint);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        logos = new LogoLoader(this, SERVER);

        loadChannels();
        adapter = new ChannelAdapter(getLayoutInflater(), logos, channels);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            play(position);
            hideList();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            startMoving(position);
            return true;
        });
        findViewById(R.id.update).setOnClickListener(v -> checkUpdates(true));

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000);
        // Всегда самое высокое качество, которое тянет телевизор, и быстрый старт: играем, набрав 1 секунду
        DefaultTrackSelector tracks = new DefaultTrackSelector(this);
        tracks.setParameters(tracks.buildUponParameters().setForceHighestSupportedBitrate(true));
        DefaultLoadControl loading = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 50000, 1000, 2000)
                .build();
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http))
                .setTrackSelector(tracks)
                .setLoadControl(loading)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && titleShown) {
                    handler.removeCallbacks(hideInfo);
                    handler.postDelayed(hideInfo, 4000);
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
                Channel failed = channels.get(current);
                Log.w(TAG, "Не воспроизводится " + failed.name, error);
                if (failed.rutube != null) {
                    // Ссылка могла устареть — в следующий раз спросим RUTUBE заново
                    rutubeStreams.remove(failed.rutube);
                }
                showInfo(channelTitle(current) + "\nКанал сейчас недоступен. Выберите другой.", 0);
            }
        });
        PlayerView playerView = findViewById(R.id.player);
        playerView.setPlayer(player);

        current = indexOfId(prefs.getString(CURRENT_CHANNEL, null));
        if (current < 0) {
            current = 0;
        }
        loadCachedEpg();
        showList();
        handler.post(tick);
        handler.post(refreshEpg);
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
        resolver.shutdownNow();
        logos.shutdown();
        player.release();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (moving >= 0) {
            // Перестановка: стрелки двигают канал, OK или «Назад» — готово
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    moveBy(-1);
                } else if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    moveBy(1);
                } else if ((key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                        || key == KeyEvent.KEYCODE_BACK) && event.getRepeatCount() == 0) {
                    stopMoving();
                }
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
            if (digits.length() < 3) {
                digits += (key - KeyEvent.KEYCODE_0);
            }
            showInfo("Канал " + digits, 0);
            handler.removeCallbacks(applyDigits);
            handler.postDelayed(applyDigits, 2000);
            return true;
        }
        if (key == KeyEvent.KEYCODE_CHANNEL_UP) {
            play(neighbour(1));
            return true;
        }
        if (key == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            play(neighbour(-1));
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
                play(neighbour(-1));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                play(neighbour(1));
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MENU:
                showList();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void play(int index) {
        Channel channel = channels.get(index);
        current = index;
        prefs.edit().putString(CURRENT_CHANNEL, channel.id).apply();
        list.setItemChecked(current, true);
        if (panel.getVisibility() == View.VISIBLE && list.getSelectedItemPosition() != current) {
            list.setSelection(current);
        }

        showChannelInfo();

        String known = channel.rutube == null ? channel.url : rutubeStreams.get(channel.rutube);
        if (known != null) {
            start(known);
            return;
        }
        // Адрес эфира на RUTUBE узнаём у его открытого API — так же, как официальный плеер на сайте канала
        player.stop();
        player.clearMediaItems();
        resolver.execute(() -> {
            String stream = resolveRutube(channel.rutube);
            runOnUiThread(() -> {
                if (isFinishing() || !channels.get(current).id.equals(channel.id)) {
                    return;
                }
                if (stream == null) {
                    showInfo(channelTitle(current) + "\nКанал сейчас недоступен. Выберите другой.", 0);
                    return;
                }
                rutubeStreams.put(channel.rutube, stream);
                start(stream);
            });
        });
    }

    private void start(String url) {
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
    }

    /** Ссылка на прямой эфир видео RUTUBE или null, если RUTUBE его не отдал (например, вне России). */
    private static String resolveRutube(String videoId) {
        String json = fetchOrNull("https://rutube.ru/api/play/options/" + videoId + "/?no_404=true", DESKTOP_USER_AGENT);
        if (json == null) {
            return null;
        }
        try {
            JSONObject live = new JSONObject(json).optJSONObject("live_streams");
            JSONArray hls = live == null ? null : live.optJSONArray("hls");
            if (hls == null || hls.length() == 0) {
                Log.w(TAG, "RUTUBE не отдал эфир " + videoId + ": " + json);
                return null;
            }
            return hls.getJSONObject(0).getString("url");
        } catch (JSONException e) {
            Log.w(TAG, "Непонятный ответ RUTUBE для " + videoId, e);
            return null;
        }
    }

    /** Соседний канал по кругу. */
    private int neighbour(int step) {
        int count = channels.size();
        return ((current + step) % count + count) % count;
    }

    private void showChannelInfo() {
        String now = epg.nowPlaying(channels.get(current).epg, System.currentTimeMillis());
        showInfo(channelTitle(current) + (now == null ? "" : "\nСейчас: " + now), 0);
        titleShown = true;
    }

    private void showList() {
        panel.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();
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
        return (index + 1) + ". " + channels.get(index).name;
    }

    // ---------- Перестановка каналов ----------

    private void startMoving(int position) {
        moving = position;
        adapter.setMoving(position);
        hint.setText(R.string.hint_moving);
    }

    private void moveBy(int step) {
        int target = moving + step;
        if (target < 0 || target >= channels.size()) {
            return;
        }
        String playing = channels.get(current).id;
        Collections.swap(channels, moving, target);
        moving = target;
        current = indexOfId(playing);
        adapter.setMoving(moving);
        list.setItemChecked(current, true);
        list.setSelection(moving);
    }

    private void stopMoving() {
        moving = -1;
        adapter.setMoving(-1);
        hint.setText(R.string.hint_list);
        JSONArray order = new JSONArray();
        for (Channel channel : channels) {
            order.put(channel.id);
        }
        prefs.edit().putString(ORDER, order.toString()).apply();
        showInfo("Порядок каналов сохранён", 2000);
    }

    /** Расставляет каналы в сохранённом порядке; новые каналы — в конец. */
    private List<Channel> applyOrder(List<Channel> fresh) {
        List<Channel> ordered = new ArrayList<>();
        try {
            JSONArray order = new JSONArray(prefs.getString(ORDER, "[]"));
            for (int i = 0; i < order.length(); i++) {
                for (Channel channel : fresh) {
                    if (channel.id.equals(order.getString(i))) {
                        ordered.add(channel);
                        break;
                    }
                }
            }
        } catch (JSONException ignored) {
            // Порядок испорчен — оставляем как на сервере
        }
        for (Channel channel : fresh) {
            if (!ordered.contains(channel)) {
                ordered.add(channel);
            }
        }
        return ordered;
    }

    private int indexOfId(String id) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    // ---------- Список каналов ----------

    /** Последний скачанный с сервера список, а если его нет — встроенный в приложение. */
    private void loadChannels() {
        File cached = new File(getFilesDir(), CHANNELS_FILE);
        if (cached.exists()) {
            try (InputStream in = new FileInputStream(cached)) {
                String json = readText(in);
                channels = applyOrder(Channel.parse(json));
                channelsJson = json;
                return;
            } catch (IOException | JSONException ignored) {
                // Файл испорчен или от старой версии — берём встроенный список
            }
        }
        try (InputStream in = getAssets().open(CHANNELS_FILE)) {
            channelsJson = readText(in);
            channels = applyOrder(Channel.parse(channelsJson));
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Встроенный список каналов повреждён", e);
        }
    }

    /** Применяет новый список, стараясь не прерывать канал, который сейчас идёт. */
    private void applyChannels(String json, List<Channel> fresh) {
        writeText(new File(getFilesDir(), CHANNELS_FILE), json);
        Channel playing = channels.get(current);
        channels = applyOrder(fresh);
        channelsJson = json;
        adapter.setChannels(channels);

        int index = indexOfId(playing.id);
        if (index >= 0) {
            current = index;
            list.setItemChecked(current, true);
            Channel updated = channels.get(index);
            if (!updated.url.equals(playing.url) || !String.valueOf(updated.rutube).equals(String.valueOf(playing.rutube))) {
                // Адрес канала поменяли (например, починили) — переключаемся на новый
                play(index);
            }
        } else {
            play(0);
        }
    }

    // ---------- Телепрограмма ----------

    private void loadCachedEpg() {
        File cached = new File(getFilesDir(), EPG_FILE);
        if (!cached.exists()) {
            return;
        }
        try (InputStream in = new FileInputStream(cached)) {
            epg = Epg.parse(readText(in));
            adapter.setEpg(epg);
        } catch (IOException | JSONException ignored) {
            // Испорченный файл заменится свежим с сервера
        }
    }

    private void fetchEpg() {
        io.execute(() -> {
            String json = fetchOrNull(SERVER + EPG_FILE);
            if (json == null) {
                return;
            }
            try {
                Epg fresh = Epg.parse(json);
                writeText(new File(getFilesDir(), EPG_FILE), json);
                runOnUiThread(() -> {
                    epg = fresh;
                    adapter.setEpg(fresh);
                });
            } catch (JSONException e) {
                Log.w(TAG, "Испорченная телепрограмма", e);
            }
        });
    }

    private static void writeText(File file, String text) {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        } catch (IOException ignored) {
            // Не сохранилось — данные всё равно используются до перезапуска
        }
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
                applyChannels(json, Channel.parse(json));
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
                showInfo("Всё обновлено. Каналов: " + channels.size()
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
        return fetchOrNull(url, USER_AGENT);
    }

    private static String fetchOrNull(String url, String userAgent) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            HttpURLConnection connection = open(url, userAgent);
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
                HttpURLConnection connection = open(url, USER_AGENT);
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

    private static HttpURLConnection open(String url, String userAgent) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", userAgent);
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
