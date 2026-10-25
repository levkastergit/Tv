package ru.russkoetv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Канал без открытого потока: открывает официальную страницу канала с его плеером, как браузер.
 * «Назад» возвращает к списку каналов.
 */
public class WebActivity extends Activity {
    static final String EXTRA_URL = "url";
    /** CSS-селектор плеера на странице: его растягиваем на весь экран поверх остального сайта. */
    static final String EXTRA_PLAYER = "player";

    /** Функции новых браузеров (Chrome 92+), которых нет в старом встроенном браузере. */
    private static final String POLYFILLS = ""
            + "if(!Object.hasOwn){Object.hasOwn=function(o,k){return Object.prototype.hasOwnProperty.call(o,k)}}"
            + "[Array,String].forEach(function(C){if(!C.prototype.at){Object.defineProperty(C.prototype,'at',{configurable:true,writable:true,"
            + "value:function(i){i=Math.trunc(i)||0;if(i<0)i+=this.length;return this[i]}})}});"
            + "if(!window.structuredClone){window.structuredClone=function(v){return v===undefined?v:JSON.parse(JSON.stringify(v))}}";

    private FrameLayout root;
    private WebView web;
    private View fullscreenVideo;
    /** Скрипт, который выполняется на странице до её собственных скриптов. */
    private String script;
    /** Чёрный экран «Загрузка…», пока на странице не появится плеер — чтобы не мелькал сайт с баннерами. */
    private TextView cover;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long coverShownAt;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.WEB_DEBUG);
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web);
        cover = new TextView(this);
        cover.setBackgroundColor(Color.BLACK);
        cover.setTextColor(Color.WHITE);
        cover.setTextSize(24);
        cover.setGravity(Gravity.CENTER);
        cover.setText("Загрузка…");
        root.addView(cover);
        setContentView(root);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(Color.BLACK);
        script = POLYFILLS + playerOnlyScript(getIntent().getStringExtra(EXTRA_PLAYER));
        boolean documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Старый браузер не умеет подставлять скрипт заранее — вставляем его в саму страницу
                if (documentStart || !request.isForMainFrame() || !"GET".equals(request.getMethod())) {
                    return null;
                }
                return pageWithScript(request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // На случай, если сайт перерисовал страницу целиком
                view.evaluateJavascript(script, null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.w("RusskoeTV", "Страница: " + message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Плееру каналов может понадобиться защищённое воспроизведение (DRM) — только его и разрешаем
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource)) {
                        request.grant(new String[]{resource});
                        return;
                    }
                }
                request.deny();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                fullscreenVideo = view;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                if (fullscreenVideo != null) {
                    root.removeView(fullscreenVideo);
                    fullscreenVideo = null;
                }
            }
        });
        if (documentStart) {
            // Встроенный браузер на телевизорах бывает старым — добавляем то, без чего сайт не запускается
            WebViewCompat.addDocumentStartJavaScript(web, script, Collections.singleton("*"));
        }
        web.loadUrl(getIntent().getStringExtra(EXTRA_URL));
        coverShownAt = SystemClock.uptimeMillis();
        String selector = getIntent().getStringExtra(EXTRA_PLAYER);
        if (selector == null || selector.isEmpty()) {
            cover.setVisibility(View.GONE);
        } else {
            waitForPlayer(selector);
        }
    }

    /** Раз в секунду проверяет, появился ли плеер; через 40 секунд показывает страницу как есть. */
    private void waitForPlayer(String selector) {
        handler.postDelayed(() -> {
            if (isFinishing()) {
                return;
            }
            web.evaluateJavascript("!!document.querySelector(" + JSONObject.quote(selector) + ")", found -> {
                if ("true".equals(found) || SystemClock.uptimeMillis() - coverShownAt > 40000) {
                    // Даём плееру растянуться, прежде чем убрать заставку
                    handler.postDelayed(() -> cover.setVisibility(View.GONE), 1000);
                } else {
                    waitForPlayer(selector);
                }
            });
        }, 1000);
    }

    /**
     * Оставляет на экране только плеер: растягивает его поверх сайта (вместе с рекламными баннерами).
     * Стиль добавляется один раз и действует и на плеер, который сайт вставит позже.
     */
    private static String playerOnlyScript(String selector) {
        if (selector == null || selector.isEmpty()) {
            return "";
        }
        String css = selector + "{position:fixed!important;left:0!important;top:0!important;width:100vw!important;"
                + "height:100vh!important;max-width:none!important;max-height:none!important;z-index:2147483647!important;"
                + "border:0!important;background:#000!important}html,body{overflow:hidden!important;background:#000!important}";
        return "(function(){function add(){if(document.getElementById('rtv-player'))return;"
                + "var s=document.createElement('style');s.id='rtv-player';s.textContent=" + JSONObject.quote(css) + ";"
                + "(document.head||document.documentElement).appendChild(s)}"
                + "if(document.documentElement)add();document.addEventListener('DOMContentLoaded',add)})();";
    }

    /** Загружает страницу сама и вставляет скрипт в начало head, до скриптов сайта. */
    private WebResourceResponse pageWithScript(WebResourceRequest request) {
        String url = request.getUrl().toString();
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            CookieManager cookies = CookieManager.getInstance();
            String cookie = cookies.getCookie(url);
            if (cookie != null) {
                connection.setRequestProperty("Cookie", cookie);
            }
            List<String> setCookies = connection.getHeaderFields().get("Set-Cookie");
            if (setCookies != null) {
                for (String value : setCookies) {
                    cookies.setCookie(url, value);
                }
            }
            String type = connection.getContentType();
            if (type == null || !type.startsWith("text/html")) {
                connection.disconnect();
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                }
            } finally {
                connection.disconnect();
            }
            String html = buffer.toString("UTF-8").replaceFirst("(?i)<head[^>]*>",
                    "$0" + Matcher.quoteReplacement("<script>" + script + "</script>"));
            return new WebResourceResponse("text/html", "UTF-8", connection.getResponseCode(), "OK",
                    Collections.<String, String>emptyMap(), new ByteArrayInputStream(html.getBytes("UTF-8")));
        } catch (IOException e) {
            Log.w("RusskoeTV", "Не удалось загрузить " + url, e);
            return null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                finish();
            }
            return true;
        }
        if ((key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) && fullscreenVideo == null) {
            // Плеер внутри чужой страницы не получает кнопки пульта — нажимаем на центр экрана, как пальцем
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                tapCenter();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void tapCenter() {
        float x = web.getWidth() / 2f, y = web.getHeight() / 2f;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
        web.dispatchTouchEvent(down);
        web.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }
}
