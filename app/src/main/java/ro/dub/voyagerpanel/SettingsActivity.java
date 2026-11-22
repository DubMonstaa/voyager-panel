package ro.dub.voyagerpanel;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * The panel's own settings: the printers (Voyager addresses) and the display options. The screen is a page in
 * the app (assets/settings.html) drawn like Voyager UI's own settings; it talks to the app through {@link Bridge}.
 */
public class SettingsActivity extends Activity {
    private Prefs prefs;
    private WebView web;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(22, 24, 28));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setAllowFileAccess(false);          // file:///android_asset stays readable
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return true;                  // the page has no links to follow
            }
        });
        web.addJavascriptInterface(new Bridge(), "Panel");
        setContentView(web);
        web.loadUrl("file:///android_asset/settings.html");
    }

    private void js(String code) {
        runOnUiThread(() -> {
            if (!isFinishing()) web.evaluateJavascript(code, null);
        });
    }

    /** What settings.html can call (on a WebView thread). */
    private final class Bridge {
        @JavascriptInterface
        public String state() {
            try {
                JSONObject o = new JSONObject();
                List<String> urls = prefs.urls();
                o.put("urls", new JSONArray(urls));
                o.put("first", urls.isEmpty());
                o.put("startPrinters", prefs.startOnPrinters());
                o.put("autoSwitch", prefs.autoSwitch());
                o.put("rendering", prefs.rendering());
                o.put("graphFix", prefs.graphFix());
                o.put("fullscreen", prefs.fullscreen());
                o.put("keepOn", prefs.keepScreenOn());
                o.put("landscape", prefs.landscape());
                String v = "";
                try {
                    v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (Exception ignored) {
                }
                o.put("version", v);
                return o.toString();
            } catch (Exception e) {
                return "{\"urls\":[]}";
            }
        }

        @JavascriptInterface
        public String normalize(String address) {
            return Prefs.normalizeUrl(address);
        }

        @JavascriptInterface
        public void check(String address) {
            new Thread(() -> {
                boolean ok = MainActivity.reachable(address);
                js("onCheck(" + JSONObject.quote(address) + "," + ok + ")");
            }).start();
        }

        @JavascriptInterface
        public void find() {
            new Thread(() -> {
                List<String> found = Finder.find(Prefs.DEFAULT_PORT);
                js("onFound(" + new JSONArray(found) + ")");
            }).start();
        }

        @JavascriptInterface
        public void save(String json) {
            try {
                JSONObject o = new JSONObject(json);
                JSONArray a = o.getJSONArray("urls");
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < a.length(); i++) b.append(a.getString(i)).append('\n');
                prefs.save(b.toString(), o.optBoolean("fullscreen", true), o.optBoolean("keepOn", true),
                        o.optBoolean("landscape", true), o.optBoolean("startPrinters", true),
                        o.optBoolean("autoSwitch", true), o.optString("rendering", "normal"),
                        o.optString("graphFix", "sharp"));
            } catch (Exception ignored) {
            }
            runOnUiThread(() -> {
                setResult(RESULT_OK);
                finish();
            });
        }

        @JavascriptInterface
        public void cancel() {
            runOnUiThread(SettingsActivity.this::finish);
        }
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
