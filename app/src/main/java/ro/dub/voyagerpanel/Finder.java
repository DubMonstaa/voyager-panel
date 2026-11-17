package ro.dub.voyagerpanel;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Finds Voyager on the tablet's local network: every address of the network the tablet is on (its /24, the
 * usual home network) is tried on Voyager's port, and those that answer with Voyager's page are kept.
 * Blocking, a few seconds: call it off the main thread.
 */
final class Finder {
    private Finder() {
    }

    static List<String> find(int port) {
        Set<String> prefixes = new LinkedHashSet<>();   // "192.168.0." for each local IPv4 network
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    prefixes.add(ip.substring(0, ip.lastIndexOf('.') + 1));
                }
            }
        } catch (Exception ignored) {
        }

        List<String> found = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(48);
        List<Future<?>> jobs = new ArrayList<>();
        for (String prefix : prefixes) {
            for (int i = 1; i < 255; i++) {
                final String host = prefix + i;
                jobs.add(pool.submit(() -> {
                    if (portOpen(host, port) && isVoyager("http://" + host + ":" + port))
                        found.add("http://" + host + ":" + port);
                }));
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();

        List<String> out = new ArrayList<>(found);
        // in address order: 192.168.0.9 before 192.168.0.10
        out.sort((x, y) -> Long.compare(order(x), order(y)));
        return out;
    }

    private static long order(String url) {
        try {
            String[] p = url.replaceAll("^https?://", "").replaceAll(":.*$", "").split("\\.");
            long v = 0;
            for (String s : p) v = v * 256 + Integer.parseInt(s);
            return v;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean portOpen(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** True when the page at this address is Voyager UI (its title or script names it). */
    private static boolean isVoyager(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address + "/").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2000);
            if (c.getResponseCode() != 200) return false;
            byte[] buf = new byte[8192];
            int n = 0;
            try (InputStream in = c.getInputStream()) {
                int r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8).toLowerCase().contains("voyager");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
