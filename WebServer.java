package com.tigeriptv.studio;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * Tiny embedded HTTP server. Serves the web UI, proxies IPTV requests (no CORS issues),
 * stores app state and writes the final M3U to every storage the receiver can read.
 * Pure Java so it can be tested on a desktop JVM; Android specifics live behind Host.
 */
public class WebServer {

    public interface Host {
        InputStream openAsset(String path) throws IOException;
        File dataDir();
        /** Each entry is a list of candidate directories for one storage volume; first writable wins. */
        List<File[]> exportTargets();
        String appsJson();
        boolean launch(String pkg);
        boolean play(String url);
        boolean hasStoragePermission();
        void requestStoragePermission();
        String deviceName();
        /** Android 10+: save into Download/ through MediaStore (works without storage permission). Returns path or null. */
        String mediaStoreSave(String name, byte[] data);
        /** Opens the system file picker; the chosen file is copied to cacheFile(id). */
        boolean pickFile(String id, File target);
        String pickStatus(String id);
        /** {"name":"1.0.9","code":9} */
        String appVersionJson();
        /** Hands a downloaded APK to the system installer. */
        boolean installApk(File apk);
    }

    private final Map<String, Object> upd = new java.util.concurrent.ConcurrentHashMap<>();

    public static final String VERSION = "1.0.0";
    static final String UA = "VLC/3.0.20 LibVLC/3.0.20";

    private final Host host;
    private ServerSocket server;
    private int port = -1;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public WebServer(Host host) { this.host = host; }

    public synchronized int start(int... ports) {
        if (server != null) return port;
        for (int p : ports) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                server = s;
                port = p;
                break;
            } catch (IOException ignored) { }
        }
        if (server == null) return -1;
        Thread t = new Thread(() -> {
            while (server != null && !server.isClosed()) {
                try {
                    final Socket c = server.accept();
                    pool.execute(() -> handle(c));
                } catch (IOException e) { /* closed */ }
            }
        }, "tiger-http");
        t.setDaemon(true);
        t.start();
        return port;
    }

    public int getPort() { return port; }

    public synchronized void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
    }

    // ------------------------------------------------------------------ request handling

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(120000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            Map<String, String> headers = new HashMap<>();
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int i = h.indexOf(':');
                if (i > 0) headers.put(h.substring(0, i).trim().toLowerCase(Locale.ROOT), h.substring(i + 1).trim());
            }
            byte[] body = new byte[0];
            String cl = headers.get("content-length");
            if (cl != null) {
                int len = Integer.parseInt(cl);
                body = new byte[len];
                int off = 0;
                while (off < len) {
                    int r = in.read(body, off, len - off);
                    if (r < 0) break;
                    off += r;
                }
            }
            String path = target;
            Map<String, String> q = new HashMap<>();
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                for (String kv : target.substring(qi + 1).split("&")) {
                    if (kv.isEmpty()) continue;
                    int e = kv.indexOf('=');
                    String k = e < 0 ? kv : kv.substring(0, e);
                    String v = e < 0 ? "" : kv.substring(e + 1);
                    q.put(dec(k), dec(v));
                }
            }
            path = dec(path);
            if ("OPTIONS".equals(method)) { send(out, 204, "text/plain", new byte[0]); return; }
            route(method, path, q, body, out);
            out.flush();
        } catch (Exception ignored) { }
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private void route(String method, String path, Map<String, String> q, byte[] body, OutputStream out) throws IOException {
        switch (path) {
            case "/api/ping":
                json(out, "{\"ok\":true,\"version\":\"" + VERSION + "\"}");
                return;
            case "/api/info":
                json(out, infoJson());
                return;
            case "/api/state": {
                File f = new File(host.dataDir(), "state.json");
                if ("POST".equals(method)) {
                    writeAtomic(f, body);
                    json(out, "{\"ok\":true}");
                } else {
                    send(out, 200, "application/json; charset=utf-8", f.exists() ? readAll(f) : "{}".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            case "/api/fetch":
                proxy(q.get("url"), q.get("cache"), out);
                return;
            case "/api/cache": {
                File f = cacheFile(q.get("id"));
                if (f == null || !f.exists()) { send(out, 404, "text/plain; charset=utf-8", bytes("no cache")); return; }
                streamFile(out, f, "text/plain; charset=utf-8");
                return;
            }
            case "/api/upload": {
                File f = cacheFile(q.get("id"));
                if (f == null) { send(out, 400, "text/plain; charset=utf-8", bytes("id")); return; }
                writeAtomic(f, body);
                json(out, "{\"ok\":true,\"size\":" + body.length + "}");
                return;
            }
            case "/api/files":
                json(out, listPlaylists());
                return;
            case "/api/importfile": {
                File src = new File(q.get("path") == null ? "" : q.get("path"));
                File f = cacheFile(q.get("id"));
                String n = src.getName().toLowerCase(Locale.ROOT);
                if (f == null || !src.isFile() || !(n.endsWith(".m3u") || n.endsWith(".m3u8") || n.endsWith(".txt"))) {
                    send(out, 400, "text/plain; charset=utf-8", bytes("ملف غير صالح"));
                    return;
                }
                writeAtomic(f, readAll(src));
                json(out, "{\"ok\":true,\"size\":" + src.length() + "}");
                return;
            }
            case "/api/ls":
                json(out, listDir(q.get("path")));
                return;
            case "/api/pick": {
                File f = cacheFile(q.get("id"));
                json(out, "{\"ok\":" + (f != null && host.pickFile(q.get("id"), f)) + "}");
                return;
            }
            case "/api/pickstatus":
                json(out, "{\"state\":" + js(host.pickStatus(q.get("id"))) + "}");
                return;
            case "/api/update": {
                final String url = q.get("url");
                if (url == null || !url.startsWith("https://")) { send(out, 400, "text/plain", bytes("url")); return; }
                if ("downloading".equals(upd.get("state"))) { json(out, "{\"ok\":true}"); return; }
                upd.put("state", "downloading"); upd.put("got", 0L); upd.put("total", -1L);
                new Thread(() -> {
                    File f = new File(host.dataDir(), "update.apk");
                    try {
                        HttpURLConnection c = open(url, 20000, 60000);
                        if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
                        upd.put("total", (long) c.getContentLength());
                        try (InputStream is = c.getInputStream(); OutputStream fo = new FileOutputStream(f)) {
                            byte[] buf = new byte[65536]; int r; long got = 0;
                            while ((r = is.read(buf)) != -1) { fo.write(buf, 0, r); got += r; upd.put("got", got); }
                        }
                        c.disconnect();
                        upd.put("state", host.installApk(f) ? "installing" : "error");
                    } catch (Exception e) { upd.put("state", "error"); upd.put("error", String.valueOf(e.getMessage())); }
                }, "updater").start();
                json(out, "{\"ok\":true}");
                return;
            }
            case "/api/updstatus":
                json(out, "{\"state\":" + js(String.valueOf(upd.getOrDefault("state", "idle"))) + ",\"got\":" + upd.getOrDefault("got", 0L)
                        + ",\"total\":" + upd.getOrDefault("total", -1L) + ",\"error\":" + js((String) upd.get("error")) + "}");
                return;
            case "/api/probe":
                json(out, probe(q.get("url")));
                return;
            case "/api/export":
                json(out, export(q.get("name"), body));
                return;
            case "/api/apps":
                json(out, host.appsJson());
                return;
            case "/api/launch":
                json(out, "{\"ok\":" + host.launch(q.get("pkg")) + "}");
                return;
            case "/api/play":
                json(out, "{\"ok\":" + host.play(q.get("url")) + "}");
                return;
            case "/api/permission":
                host.requestStoragePermission();
                json(out, "{\"ok\":true}");
                return;
            case "/playlist.m3u":
            case "/tiger.m3u": {
                File f = new File(host.dataDir(), "playlist.m3u");
                if (!f.exists()) { send(out, 404, "text/plain; charset=utf-8", bytes("#EXTM3U\n")); return; }
                streamFile(out, f, "audio/x-mpegurl; charset=utf-8");
                return;
            }
            default:
                serveAsset(path, out);
        }
    }

    // ------------------------------------------------------------------ features

    private String infoJson() {
        StringBuilder ips = new StringBuilder("[");
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            boolean first = true;
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (Enumeration<InetAddress> as = ni.getInetAddresses(); as.hasMoreElements(); ) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if (!first) ips.append(',');
                        ips.append(js(a.getHostAddress()));
                        first = false;
                    }
                }
            }
        } catch (Exception ignored) { }
        ips.append(']');
        StringBuilder dirs = new StringBuilder("[");
        boolean first = true;
        for (File[] group : host.exportTargets()) {
            if (group.length == 0) continue;
            if (!first) dirs.append(',');
            dirs.append(js(group[0].getAbsolutePath()));
            first = false;
        }
        dirs.append(']');
        File pl = new File(host.dataDir(), "playlist.m3u");
        return "{\"version\":" + js(VERSION) + ",\"port\":" + port + ",\"ips\":" + ips + ",\"exportDirs\":" + dirs
                + ",\"storage\":" + host.hasStoragePermission() + ",\"device\":" + js(host.deviceName())
                + ",\"playlist\":" + (pl.exists() ? pl.length() : -1) + ",\"app\":" + host.appVersionJson() + "}";
    }

    private File cacheFile(String id) {
        if (id == null || id.isEmpty()) return null;
        String safe = id.replaceAll("[^A-Za-z0-9_-]", "_");
        File d = new File(host.dataDir(), "cache");
        d.mkdirs();
        return new File(d, safe + ".m3u");
    }

    private HttpURLConnection open(String url, int connectTimeout, int readTimeout) throws IOException {
        String cur = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(connectTimeout);
            c.setReadTimeout(readTimeout);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "gzip");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("redirect without location");
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("too many redirects");
    }

    private static InputStream bodyStream(HttpURLConnection c) throws IOException {
        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) is = new ByteArrayInputStream(new byte[0]);
        if ("gzip".equalsIgnoreCase(c.getContentEncoding())) is = new GZIPInputStream(is);
        return is;
    }

    private void proxy(String url, String cacheId, OutputStream out) throws IOException {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            send(out, 400, "text/plain; charset=utf-8", bytes("رابط غير صالح"));
            return;
        }
        HttpURLConnection c;
        try {
            c = open(url, 20000, 90000);
        } catch (Exception e) {
            send(out, 502, "text/plain; charset=utf-8", bytes("تعذر الاتصال بالسيرفر: " + e.getMessage()));
            return;
        }
        int code = c.getResponseCode();
        if (code >= 400) {
            send(out, 502, "text/plain; charset=utf-8", bytes("السيرفر رد بالخطأ " + code));
            c.disconnect();
            return;
        }
        File cache = code == 200 ? cacheFile(cacheId) : null;
        File tmp = cache == null ? null : new File(cache.getPath() + ".tmp");
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\nAccess-Control-Expose-Headers: X-Length\r\nX-Length: "
                + ("gzip".equalsIgnoreCase(c.getContentEncoding()) ? -1 : c.getContentLength())
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        boolean complete = false;
        try (InputStream is = bodyStream(c); OutputStream fo = tmp == null ? null : new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = is.read(buf)) != -1) {
                out.write(buf, 0, r);
                if (fo != null) fo.write(buf, 0, r);
            }
            complete = true;
        } finally {
            c.disconnect();
            if (tmp != null) {
                if (complete) { cache.delete(); tmp.renameTo(cache); } else tmp.delete();
            }
        }
    }

    private String probe(String url) {
        long t0 = System.currentTimeMillis();
        try {
            HttpURLConnection c = open(url, 8000, 8000);
            int code = c.getResponseCode();
            String type = c.getContentType();
            int got = 0;
            try (InputStream is = bodyStream(c)) {
                byte[] buf = new byte[4096];
                int r;
                while (got < 8192 && (r = is.read(buf)) != -1) got += r;
            } catch (Exception ignored) { }
            c.disconnect();
            boolean ok = code >= 200 && code < 400 && got > 0;
            return "{\"ok\":" + ok + ",\"code\":" + code + ",\"type\":" + js(type) + ",\"bytes\":" + got
                    + ",\"ms\":" + (System.currentTimeMillis() - t0) + "}";
        } catch (Exception e) {
            return "{\"ok\":false,\"code\":0,\"error\":" + js(String.valueOf(e.getMessage())) + ",\"ms\":"
                    + (System.currentTimeMillis() - t0) + "}";
        }
    }

    private String listDir(String path) {
        StringBuilder b = new StringBuilder();
        if (path == null || path.isEmpty()) {
            b.append("{\"path\":\"\",\"parent\":null,\"items\":[");
            Set<String> seen = new LinkedHashSet<>();
            for (File[] g : host.exportTargets()) {
                if (g.length == 0) continue;
                File r = g[0];
                String p = r.getAbsolutePath();
                if (p.endsWith("/Download") || p.endsWith("/M3U")) continue;
                seen.add(p);
            }
            File[] st = new File("/storage").listFiles();
            if (st != null) for (File f : st) {
                String n = f.getName();
                if (f.isDirectory() && !n.equals("self") && !n.equals("emulated")) seen.add(f.getAbsolutePath());
            }
            boolean first = true;
            for (String p : seen) {
                File f = new File(p);
                if (!first) b.append(',');
                first = false;
                boolean internal = p.contains("emulated");
                b.append("{\"name\":").append(js(internal ? "الذاكرة الداخلية" : "USB / " + f.getName()))
                 .append(",\"path\":").append(js(p)).append(",\"dir\":true,\"root\":true,\"readable\":").append(f.canRead() && f.listFiles() != null).append('}');
            }
            return b.append("]}").toString();
        }
        File d = new File(path);
        File[] fs = d.listFiles();
        if (fs == null) return "{\"path\":" + js(path) + ",\"error\":\"denied\",\"items\":[]}";
        Arrays.sort(fs, (x, y) -> x.isDirectory() != y.isDirectory() ? (x.isDirectory() ? -1 : 1) : x.getName().compareToIgnoreCase(y.getName()));
        File par = d.getParentFile();
        b.append("{\"path\":").append(js(d.getAbsolutePath())).append(",\"parent\":")
         .append(par == null || d.getAbsolutePath().matches("/storage/[^/]+|/storage/emulated/0") ? "\"\"" : js(par.getAbsolutePath()))
         .append(",\"items\":[");
        boolean first = true;
        int n = 0;
        for (File f : fs) {
            String nm = f.getName();
            if (nm.startsWith(".")) continue;
            String l = nm.toLowerCase(Locale.ROOT);
            boolean dir = f.isDirectory();
            if (!dir && !(l.endsWith(".m3u") || l.endsWith(".m3u8") || l.endsWith(".txt"))) continue;
            if (++n > 500) break;
            if (!first) b.append(',');
            first = false;
            b.append("{\"name\":").append(js(nm)).append(",\"path\":").append(js(f.getAbsolutePath()))
             .append(",\"dir\":").append(dir).append(",\"size\":").append(dir ? 0 : f.length()).append('}');
        }
        return b.append("]}").toString();
    }

    private String listPlaylists() {
        List<File> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File[] group : host.exportTargets())
            for (File d : group) scan(d, 0, found, seen);
        Collections.sort(found, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < found.size() && i < 200; i++) {
            File f = found.get(i);
            if (i > 0) b.append(',');
            b.append("{\"path\":").append(js(f.getAbsolutePath())).append(",\"name\":").append(js(f.getName()))
             .append(",\"size\":").append(f.length()).append(",\"time\":").append(f.lastModified()).append('}');
        }
        return b.append(']').toString();
    }

    private void scan(File dir, int depth, List<File> found, Set<String> seen) {
        if (depth > 3 || found.size() > 400) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (n.equals("Android") || n.equals("DCIM") || n.equals("LOST.DIR")) continue;
                scan(f, depth + 1, found, seen);
            } else {
                String l = n.toLowerCase(Locale.ROOT);
                if ((l.endsWith(".m3u") || l.endsWith(".m3u8")) && f.length() < 300L * 1024 * 1024) {
                    try { if (seen.add(f.getCanonicalPath())) found.add(f); } catch (IOException ignored) { }
                }
            }
        }
    }

    private String export(String name, byte[] body) {
        if (name == null || name.trim().isEmpty()) name = "tiger";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".m3u")) name += ".m3u";
        StringBuilder saved = new StringBuilder("[");
        StringBuilder failed = new StringBuilder("[");
        boolean fs = true, ff = true;
        try { writeAtomic(new File(host.dataDir(), "playlist.m3u"), body); } catch (IOException ignored) { }
        Set<String> done = new HashSet<>();
        for (File[] group : host.exportTargets()) {
            String okPath = null;
            String err = null;
            for (File dir : group) {
                try {
                    if (!dir.exists() && !dir.mkdirs() && !dir.exists()) throw new IOException("mkdir failed");
                    File f = new File(dir, name);
                    String key = f.getCanonicalPath();
                    if (done.contains(key)) { okPath = ""; break; }
                    try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(body); fo.getFD().sync(); }
                    done.add(key);
                    okPath = f.getAbsolutePath();
                    break;
                } catch (Exception e) {
                    if (err == null) err = dir.getAbsolutePath() + " — " + e.getMessage();
                }
            }
            if (okPath != null) {
                if (!okPath.isEmpty()) { if (!fs) saved.append(','); saved.append(js(okPath)); fs = false; }
            } else if (err != null) {
                if (!ff) failed.append(','); failed.append(js(err)); ff = false;
            }
        }
        boolean inDownload = false;
        for (String d : done) if (d.contains("/Download/")) inDownload = true;
        if (!inDownload) {
            String ms = null;
            try { ms = host.mediaStoreSave(name, body); } catch (Exception ignored) { }
            if (ms != null) { if (!fs) saved.append(','); saved.append(js(ms)); fs = false; }
        }
        saved.append(']');
        failed.append(']');
        return "{\"ok\":true,\"name\":" + js(name) + ",\"size\":" + body.length + ",\"saved\":" + saved + ",\"failed\":" + failed + "}";
    }

    // ------------------------------------------------------------------ static files

    private void serveAsset(String path, OutputStream out) throws IOException {
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..")) { send(out, 403, "text/plain", bytes("forbidden")); return; }
        String rel = path.substring(1);
        InputStream is;
        try { is = host.openAsset(rel); } catch (IOException e) { is = null; }
        if (is == null) { send(out, 404, "text/plain; charset=utf-8", bytes("not found")); return; }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream in = is) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) != -1) b.write(buf, 0, r);
        }
        send(out, 200, mime(rel), b.toByteArray());
    }

    private static String mime(String p) {
        String l = p.toLowerCase(Locale.ROOT);
        if (l.endsWith(".html")) return "text/html; charset=utf-8";
        if (l.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (l.endsWith(".css")) return "text/css; charset=utf-8";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ helpers

    private static void streamFile(OutputStream out, File f, String type) throws IOException {
        String head = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + f.length()
                + "\r\nX-Length: " + f.length() + "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Expose-Headers: X-Length\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        try (InputStream is = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = is.read(buf)) != -1) out.write(buf, 0, r);
        }
    }

    private static void json(OutputStream out, String s) throws IOException {
        send(out, 200, "application/json; charset=utf-8", bytes(s));
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 204 ? "No Content" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = is.read(buf)) != -1) b.write(buf, 0, r);
            return b.toByteArray();
        }
    }

    private static void writeAtomic(File f, byte[] data) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(data); }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("rename failed");
        }
    }

    public static String js(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
