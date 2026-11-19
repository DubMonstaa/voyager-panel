package ro.dub.voyagerpanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voyager UI full screen in a WebView. The Back button goes back inside Voyager; when there is
 * nothing to go back to, it opens a small menu (all printers, another printer, reload, settings, exit).
 *
 * The app knows several Voyager addresses (every printer serves its own copy). At start it opens the first one
 * that answers; while none answers, a panel says so and keeps looking. While a page is open, the address is
 * checked every few seconds; after about 30 seconds without an answer the app moves to the next address that
 * answers (Settings can turn that off).
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 1;
    private static final int REQ_SETTINGS = 2;
    private static final long RETRY_MS = 5000;
    private static final long HEALTH_MS = 10000;     // how often the open address is checked
    private static final int HEALTH_FAILS = 3;       // misses in a row before moving on (about 30 s)

    private Prefs prefs;
    private WebView web;
    private LinearLayout offline;
    private TextView offlineText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> fileCallback;
    private String loadedUrl;
    private String base;              // the Voyager address that is open (or being opened)
    private boolean mainFrameError;
    private boolean resumed;
    private int healthFails;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final Runnable retry = () -> findAndOpen(null);

    private final Runnable health = this::checkHealth;

    /**
     * Voyager saves some files from the page itself (settings backup, several files as one zip, timelapse frames):
     * a link to a blob: or data: address with a download name, clicked from script. A WebView can't download
     * those, so this script, run on every page, hands such a file to {@link Saver} (name, type, base64 content).
     * createObjectURL is wrapped so the file is still at hand when the page frees its address right after the click.
     */
    private String deviceJs; // see deviceScript()
    private static final String DOWNLOAD_HOOK = "(function(){if(window.__panelDl||!window.PanelDl)return;window.__panelDl=1;"
            + "var blobs=new Map(),oc=URL.createObjectURL,orv=URL.revokeObjectURL;"
            + "URL.createObjectURL=function(o){var u=oc.call(URL,o);if(o instanceof Blob)blobs.set(u,o);return u;};"
            + "URL.revokeObjectURL=function(u){setTimeout(function(){blobs.delete(u);},60000);return orv.call(URL,u);};"
            + "function inPage(a){return a&&a.hasAttribute('download')&&/^(blob|data):/.test(a.href);}"
            + "function grab(a){var name=a.getAttribute('download')||'download';"
            + "var p=blobs.has(a.href)?Promise.resolve(blobs.get(a.href)):fetch(a.href).then(function(r){return r.blob();});"
            + "p.then(function(b){var fr=new FileReader();fr.onload=function(){var s=fr.result;"
            + "PanelDl.save(s.slice(s.indexOf(',')+1),name,b.type||'application/octet-stream');};fr.readAsDataURL(b);})"
            + ".catch(function(e){PanelDl.failed(name,String(e));});}"
            + "var click=HTMLAnchorElement.prototype.click;"
            + "HTMLAnchorElement.prototype.click=function(){if(inPage(this)){grab(this);return;}return click.call(this);};"
            + "document.addEventListener('click',function(e){var a=e.target&&e.target.closest&&e.target.closest('a[download]');"
            + "if(inPage(a)){e.preventDefault();grab(a);}},true);})();";

    /**
     * Voyager gives every browser its own dashboard layout, under an id kept in the page's local storage. That
     * storage is per address (each printer's page has its own) and is wiped with the app, so the tablet was a new
     * device on every printer and after every reinstall. The app hands Voyager one id instead, from the tablet's
     * Android ID (the same for every printer, and kept across reinstalls). The first time, the layout the page
     * had under its old id is copied to the new one on that printer, then the page reloads once.
     */
    private String deviceScript() {
        String aid = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        if (aid == null || aid.isEmpty()) return null;
        String id = "panel-" + aid.replaceAll("[^A-Za-z0-9_-]", "");
        String name = (Build.MODEL == null || Build.MODEL.isEmpty() ? "Tablet" : Build.MODEL) + " (Voyager Panel)";
        // fin: false while the page starts (only hand over the id), true once it has loaded (copy the old layout,
        // then reload once so Voyager runs with the new id)
        return "(function(fin){try{if(!/^https?:/.test(location.protocol))return;"
                + "var K='voyager-ui-device-layout',ID=" + org.json.JSONObject.quote(id) + ",NM=" + org.json.JSONObject.quote(name) + ";"
                + "var o=null;try{o=JSON.parse(localStorage.getItem(K)||'null')}catch(e){}"
                + "if(!o||o.id!==ID){if(o&&o.id&&!sessionStorage.getItem('panel-old-id'))sessionStorage.setItem('panel-old-id',o.id);"
                + "localStorage.setItem(K,JSON.stringify({id:ID,name:NM}));sessionStorage.setItem('panel-dev-new','1')}"
                + "if(!fin||!sessionStorage.getItem('panel-dev-new'))return;sessionStorage.removeItem('panel-dev-new');"
                + "var old=sessionStorage.getItem('panel-old-id'),tok='';sessionStorage.removeItem('panel-old-id');"
                + "try{tok=(JSON.parse(localStorage.getItem('voyager-ui-auth')||'{}').token)||''}catch(e){}"
                + "var h=tok?{Authorization:'Bearer '+tok}:{},NS='voyager-ui-layouts';"
                + "var it=function(k){return fetch('/server/database/item?namespace='+NS+'&key='+encodeURIComponent(k),{headers:h,cache:'no-store'})"
                + ".then(function(r){return r.ok?r.json():null}).then(function(j){return j&&j.result&&j.result.value})};"
                + "var done=function(){location.reload()};"
                + "if(!old||!/^[A-Za-z0-9_-]+$/.test(old))return done();"
                + "Promise.all([it(old),it(ID)]).then(function(v){if(!v[0]||!v[0].data||v[1])return;"
                + "return fetch('/server/database/item',{method:'POST',headers:Object.assign({'Content-Type':'application/json'},h),"
                + "body:JSON.stringify({namespace:NS,key:ID,value:{name:NM,t:Date.now(),data:v[0].data}})})})"
                + ".catch(function(){}).then(done,done)}catch(e){}})";
    }

    /** Saves files handed over by {@link #DOWNLOAD_HOOK} to the Downloads folder. */
    private final class Saver {
        @JavascriptInterface
        public void save(String base64, String name, String mime) {
            new Thread(() -> {
                String file = name == null || name.trim().isEmpty() ? "download" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
                String msg;
                try {
                    byte[] data = Base64.decode(base64, Base64.DEFAULT);
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Downloads.DISPLAY_NAME, file);
                        v.put(MediaStore.Downloads.MIME_TYPE, mime);
                        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                        if (uri == null) throw new IllegalStateException("no space in Downloads");
                        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                            out.write(data);
                        }
                        msg = "Saved to Downloads: " + file;
                    } else {
                        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        File f = new File(dir, file);
                        try (FileOutputStream out = new FileOutputStream(f)) {
                            out.write(data);
                        }
                        msg = "Saved: " + f.getAbsolutePath();
                    }
                } catch (Exception e) {
                    msg = "Could not save " + file + ": " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show());
            }).start();
        }

        @JavascriptInterface
        public void failed(String name, String error) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Could not save " + name + ": " + error,
                    Toast.LENGTH_LONG).show());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(16, 20, 24));

        web = new WebView(this);
        setupWebView(web);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildOfflinePanel(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        applyDisplayOptions();
        if (prefs.urls().isEmpty()) openSettings();   // first start: ask for the printers
        findAndOpen(null);
    }

    /** True when Voyager answers at this address (any HTTP answer from the web server counts). */
    static boolean reachable(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address + "/").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2500);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            return code > 0 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String label(String address) {
        Uri u = Uri.parse(address);
        return u.getHost() == null ? address : u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    /**
     * Opens the first address, in the order of the settings, that answers, leaving out {@code skip} (null: none).
     * When none answers, the offline panel stays and the search repeats every few seconds.
     */
    private void findAndOpen(String skip) {
        handler.removeCallbacks(retry);
        List<String> list = prefs.urls();
        if (list.isEmpty()) {
            base = null;
            showOffline(true, "No printer address yet.\n\nOpen Settings to add one, or to find the printers on "
                    + "this network.");
            return;
        }
        if (base == null && offline.getVisibility() != View.VISIBLE) showOffline(true, "Looking for a printer…");
        io.execute(() -> {
            String found = null;
            for (String u : list) {
                if (u.equals(skip)) continue;
                if (reachable(u)) {
                    found = u;
                    break;
                }
            }
            final String f = found;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (f == null) {
                    if (skip == null) {
                        showOffline(true, null);
                        handler.postDelayed(retry, RETRY_MS);
                    }
                    return;
                }
                if (skip != null)
                    Toast.makeText(this, label(skip) + " isn't answering. Switched to " + label(f) + ".",
                            Toast.LENGTH_LONG).show();
                openBase(f);
            });
        });
    }

    private void openBase(String address) {
        base = address;
        healthFails = 0;
        loadUrl(prefs.startUrl(address));
        scheduleHealth();
    }

    private void scheduleHealth() {
        handler.removeCallbacks(health);
        if (resumed) handler.postDelayed(health, HEALTH_MS);
    }

    /** Checks the open address; after several misses in a row moves to the next address that answers. */
    private void checkHealth() {
        final String b = base;
        if (b == null || !resumed || offline.getVisibility() == View.VISIBLE) {
            scheduleHealth();
            return;
        }
        io.execute(() -> {
            boolean ok = reachable(b);
            handler.post(() -> {
                if (!b.equals(base)) return;
                healthFails = ok ? 0 : healthFails + 1;
                if (!ok && healthFails >= HEALTH_FAILS && prefs.autoSwitch() && prefs.urls().size() > 1) {
                    healthFails = 0;
                    findAndOpen(b);      // stays on this one when no other answers
                }
                scheduleHealth();
            });
        });
    }

    private void setupWebView(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);        // Voyager keeps its settings and printer list here
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // webcams start on their own
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setTextZoom(100);                  // ignore the system font size, Voyager scales itself
        s.setSupportZoom(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setBackgroundColor(Color.rgb(16, 20, 24));

        deviceJs = deviceScript();
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                // Voyager itself and the printers stay in the app; other links (docs, GitHub) open outside
                if ((base != null && u.toString().startsWith(base)) || isLocalAddress(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                mainFrameError = false;
                // usually early enough for Voyager to start with the app's device id; onPageFinished catches the rest
                if (deviceJs != null) view.evaluateJavascript(deviceJs + "(false)", null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(DOWNLOAD_HOOK, null);
                if (deviceJs != null) view.evaluateJavascript(deviceJs + "(true)", null);
                if (!mainFrameError) showOffline(false);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    mainFrameError = true;
                    base = null;           // look again, from the first address
                    showOffline(true, null);
                    handler.removeCallbacks(retry);
                    handler.postDelayed(retry, RETRY_MS);
                }
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent i = params.createIntent();
                    i.setType("*/*");   // G-code files have no registered type on most tablets
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE)
                        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        w.addJavascriptInterface(new Saver(), "PanelDl");
        w.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (!url.startsWith("http")) {
                Toast.makeText(this, "This download can't be saved from the app", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setMimeType(mimetype);
                r.addRequestHeader("User-Agent", userAgent);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) r.addRequestHeader("Cookie", cookies);
                r.setTitle(name);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static boolean isLocalAddress(String host) {
        if (host == null) return false;
        return host.endsWith(".local") || host.startsWith("192.168.") || host.startsWith("10.")
                || host.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*") || host.equals("localhost");
    }

    private View buildOfflinePanel() {
        offline = new LinearLayout(this);
        offline.setOrientation(LinearLayout.VERTICAL);
        offline.setGravity(Gravity.CENTER);
        offline.setBackgroundColor(Color.rgb(16, 20, 24));
        offline.setVisibility(View.GONE);
        offline.setClickable(true);

        offlineText = new TextView(this);
        offlineText.setTextColor(Color.rgb(229, 231, 235));
        offlineText.setTextSize(20);
        offlineText.setGravity(Gravity.CENTER);
        offlineText.setPadding(48, 0, 48, 32);
        offline.addView(offlineText);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        Button now = new Button(this);
        now.setText("Retry now");
        now.setOnClickListener(v -> findAndOpen(null));
        Button settings = new Button(this);
        settings.setText("Settings");
        settings.setOnClickListener(v -> openSettings());
        buttons.addView(now);
        buttons.addView(settings);
        offline.addView(buttons);
        return offline;
    }

    private void showOffline(boolean show) {
        showOffline(show, null);
    }

    /** The offline panel; {@code text} null: the standard "can't reach" message with the addresses tried. */
    private void showOffline(boolean show, String text) {
        if (show) {
            if (text == null) {
                StringBuilder b = new StringBuilder("Can't reach Voyager at");
                for (String u : prefs.urls()) b.append('\n').append(label(u));
                text = b + "\n\nThe printers may be off or starting up. Retrying every few seconds…";
            }
            offlineText.setText(text);
            offline.setVisibility(View.VISIBLE);
        } else {
            handler.removeCallbacks(retry);
            offline.setVisibility(View.GONE);
        }
    }

    private void loadUrl(String url) {
        loadedUrl = url;
        handler.removeCallbacks(retry);
        web.loadUrl(url);
    }

    private void applyDisplayOptions() {
        if (prefs.keepScreenOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setRequestedOrientation(prefs.landscape()
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        applyFullscreen();
        applyRendering();
    }

    private void applyRendering() {
        if (web == null) return;
        String r = prefs.rendering();
        web.setLayerType("software".equals(r) ? View.LAYER_TYPE_SOFTWARE
                : "layer".equals(r) ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
    }

    @SuppressWarnings("deprecation")
    private void applyFullscreen() {
        boolean full = prefs.fullscreen();
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(!full);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (full) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            View d = getWindow().getDecorView();
            d.setSystemUiVisibility(full
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                      | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                      | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (offline.getVisibility() != View.VISIBLE && web.canGoBack()) {
            web.goBack();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setItems(new CharSequence[]{"All printers", "Open another printer…", "Reload", "Settings", "Exit"},
                        (d, which) -> {
                            if (which == 0) {
                                String b = base != null ? base : prefs.url();
                                if (b != null) loadUrl(b + "/#/fleet");
                                else openSettings();
                            }
                            else if (which == 1) pickPrinter();
                            else if (which == 2) web.reload();
                            else if (which == 3) openSettings();
                            else finish();
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Lets the user open Voyager from one of the addresses in the settings. */
    private void pickPrinter() {
        List<String> list = prefs.urls();
        CharSequence[] names = new CharSequence[list.size()];
        for (int i = 0; i < list.size(); i++)
            names[i] = label(list.get(i)) + (list.get(i).equals(base) ? "  (open)" : "");
        new AlertDialog.Builder(this)
                .setTitle("Open Voyager from")
                .setItems(names, (d, which) -> openBase(list.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (fileCallback == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        } else if (requestCode == REQ_SETTINGS) {
            applyDisplayOptions();
            // the open address was removed from the list: look again
            if (base == null || !prefs.urls().contains(base)) {
                base = null;
                findAndOpen(null);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        resumed = true;
        scheduleHealth();
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(health);
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        web.destroy();
        super.onDestroy();
    }
}
