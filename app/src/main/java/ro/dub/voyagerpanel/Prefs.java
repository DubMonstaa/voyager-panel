package ro.dub.voyagerpanel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The Voyager addresses and display options. Every printer serves its own copy of Voyager, and Voyager keeps
 * its own printer list (its printer menu connects to the other printers' Moonraker from the same page). The app
 * opens the first address in the list that answers, so a printer that is switched off doesn't block the panel.
 */
final class Prefs {
    /** Voyager's default port; added when an address is typed without scheme and port. */
    static final int DEFAULT_PORT = 1977;

    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("voyager_panel", Context.MODE_PRIVATE);
    }

    /** The Voyager addresses in order of preference; empty until the user adds one. */
    List<String> urls() {
        // older versions kept a single address ("url"); it becomes the list
        String raw = sp.getString("urls", null);
        if (raw == null) raw = sp.getString("url", "");
        List<String> out = new ArrayList<>();
        for (String line : raw.split("[\\n,; ]+")) {
            String u = normalizeUrl(line);
            if (!u.isEmpty() && !out.contains(u)) out.add(u);
        }
        return out;
    }

    /** The preferred (first) address, or null when there is none yet. */
    String url() {
        List<String> l = urls();
        return l.isEmpty() ? null : l.get(0);
    }

    boolean fullscreen() {
        return sp.getBoolean("fullscreen", true);
    }

    boolean keepScreenOn() {
        return sp.getBoolean("keep_on", true);
    }

    boolean landscape() {
        return sp.getBoolean("landscape", true);
    }

    /** Switch to the next address that answers when the open one stops answering. */
    boolean autoSwitch() {
        return sp.getBoolean("auto_switch", true);
    }

    /**
     * How the page is drawn: "normal" (the WebView's own GPU drawing), "layer" (into a hardware layer first, which
     * avoids stray coloured specks some tablet GPUs leave on graphs) or "software" (by the CPU: slowest, always
     * clean).
     */
    String rendering() {
        String r = sp.getString("rendering", "normal");
        return "layer".equals(r) || "software".equals(r) ? r : "normal";
    }

    /** Open Voyager's "All printers" page at start instead of the dashboard. */
    boolean startOnPrinters() {
        return sp.getBoolean("start_printers", true);
    }

    /** The page the app opens on a Voyager address: the address plus its hash route. */
    String startUrl(String base) {
        return base + (startOnPrinters() ? "/#/fleet" : "/#/dashboard");
    }

    void save(String urls, boolean fullscreen, boolean keepOn, boolean landscape, boolean startOnPrinters,
              boolean autoSwitch, String rendering) {
        List<String> list = new ArrayList<>();
        for (String line : (urls == null ? "" : urls).split("[\\n,; ]+")) {
            String u = normalizeUrl(line);
            if (!u.isEmpty() && !list.contains(u)) list.add(u);
        }
        sp.edit()
                .putString("urls", String.join("\n", list))
                .remove("url")
                .putBoolean("start_printers", startOnPrinters)
                .putBoolean("fullscreen", fullscreen)
                .putBoolean("keep_on", keepOn)
                .putBoolean("landscape", landscape)
                .putBoolean("auto_switch", autoSwitch)
                .putString("rendering", rendering == null ? "normal" : rendering)
                .apply();
    }

    /**
     * Adds http:// when no scheme was typed, and then Voyager's port when none was typed either ("192.168.1.50"
     * becomes "http://192.168.1.50:1977"); drops a trailing slash. An address typed with http:// is kept as is.
     */
    static String normalizeUrl(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.isEmpty()) return u;
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            String hostPart = u.contains("/") ? u.substring(0, u.indexOf('/')) : u;
            if (!hostPart.contains(":")) u = hostPart + ":" + DEFAULT_PORT + u.substring(hostPart.length());
            u = "http://" + u;
        }
        return u;
    }
}
