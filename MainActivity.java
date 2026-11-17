package com.tigeriptv.studio;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.database.Cursor;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import android.content.ActivityNotFoundException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    static WebServer server;
    static volatile Activity current;
    static final Map<String, String> PICK = new ConcurrentHashMap<>();
    static volatile String pickId;
    static volatile File pickTarget;
    static final int REQ_PICK = 42;
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        if (server == null) {
            server = new WebServer(new AppHost(getApplicationContext()));
            server.start(8686, 8687, 8688, 18686, 28686);
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0b0f19"));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= 21) ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);
        web.loadUrl("http://127.0.0.1:" + server.getPort() + "/?tv=1");
        web.requestFocus(View.FOCUS_DOWN);

        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 30
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        if (web != null) web.evaluateJavascript("window.onAppResume&&window.onAppResume()", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || pickId == null) return;
        final String id = pickId;
        final File target = pickTarget;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) { PICK.put(id, "cancel"); return; }
        final Uri uri = data.getData();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(target)) {
                if (in == null) throw new IOException("no stream");
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
                PICK.put(id, "done");
            } catch (Exception e) {
                PICK.put(id, "error");
            }
        }).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Remote "back" goes to the web UI first; it decides whether to leave the app.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && web != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                web.evaluateJavascript("(window.appBack&&window.appBack())?'1':'0'", value -> {
                    if (value == null || !value.contains("1")) finish();
                });
            }
            return true;
        }
        // Map the coloured / menu keys of the remote to keyboard shortcuts in the UI.
        if (event.getAction() == KeyEvent.ACTION_DOWN && web != null) {
            String k = null;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_MENU: k = "menu"; break;
                case KeyEvent.KEYCODE_PROG_RED: k = "red"; break;
                case KeyEvent.KEYCODE_PROG_GREEN: k = "green"; break;
                case KeyEvent.KEYCODE_PROG_YELLOW: k = "yellow"; break;
                case KeyEvent.KEYCODE_PROG_BLUE: k = "blue"; break;
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_PAGE_UP: k = "pageup"; break;
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_PAGE_DOWN: k = "pagedown"; break;
                default: break;
            }
            if (k != null) {
                web.evaluateJavascript("window.remoteKey&&window.remoteKey('" + k + "')", null);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ------------------------------------------------------------------------------------

    static class AppHost implements WebServer.Host {
        private final Context ctx;

        AppHost(Context ctx) { this.ctx = ctx; }

        @Override public InputStream openAsset(String path) throws IOException {
            return ctx.getAssets().open("web/" + path);
        }

        @Override public File dataDir() { return ctx.getFilesDir(); }

        @Override public List<File[]> exportTargets() {
            List<File[]> list = new ArrayList<>();
            Set<String> roots = new HashSet<>();
            File internal = Environment.getExternalStorageDirectory();
            if (internal != null) {
                roots.add(internal.getAbsolutePath());
                list.add(new File[]{internal, new File(internal, "Download")});
                list.add(new File[]{new File(internal, "Download")});
                list.add(new File[]{new File(internal, "M3U")});
            }
            // Removable volumes (USB flash / SD). Root first, then the app folder on that volume (always writable).
            File[] appDirs = ctx.getExternalFilesDirs(null);
            if (appDirs != null) {
                for (File d : appDirs) {
                    if (d == null) continue;
                    String p = d.getAbsolutePath();
                    int i = p.indexOf("/Android/data/");
                    if (i <= 0) continue;
                    String root = p.substring(0, i);
                    if (roots.contains(root)) continue;
                    roots.add(root);
                    list.add(new File[]{new File(root), d});
                }
            }
            // Some boxes mount USB without exposing it to getExternalFilesDirs.
            for (String base : new String[]{"/storage", "/mnt/usb", "/mnt/media_rw"}) {
                File[] subs = new File(base).listFiles();
                if (subs == null) continue;
                for (File s : subs) {
                    String n = s.getName();
                    if (!s.isDirectory() || n.equals("emulated") || n.equals("self") || n.equals("sdcard0")) continue;
                    String p = s.getAbsolutePath();
                    if (roots.contains(p)) continue;
                    if (!s.canWrite()) continue;
                    roots.add(p);
                    list.add(new File[]{s});
                }
            }
            return list;
        }

        @Override public String appsJson() {
            PackageManager pm = ctx.getPackageManager();
            Set<String> seen = new HashSet<>();
            StringBuilder b = new StringBuilder("[");
            String[] cats = {"android.intent.category.LEANBACK_LAUNCHER", Intent.CATEGORY_LAUNCHER};
            for (String cat : cats) {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(cat);
                List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
                for (ResolveInfo ri : ris) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(ctx.getPackageName()) || !seen.add(pkg)) continue;
                    if (b.length() > 1) b.append(',');
                    b.append("{\"pkg\":").append(WebServer.js(pkg))
                     .append(",\"label\":").append(WebServer.js(String.valueOf(ri.loadLabel(pm)))).append('}');
                }
            }
            return b.append(']').toString();
        }

        @Override public boolean launch(String pkg) {
            if (pkg == null) return false;
            try {
                PackageManager pm = ctx.getPackageManager();
                Intent i = null;
                if (Build.VERSION.SDK_INT >= 21) i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i == null) return false;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Exception e) { return false; }
        }

        @Override public boolean play(String url) {
            if (url == null) return false;
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse(url), "video/*");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Exception e) { return false; }
        }

        @Override public boolean hasStoragePermission() {
            if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
            if (Build.VERSION.SDK_INT >= 23)
                return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            return true;
        }

        @Override public void requestStoragePermission() {
            final Activity a = current;
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + ctx.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Exception e) {
                    try {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + ctx.getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                    } catch (Exception ignored) { }
                }
            } else if (Build.VERSION.SDK_INT >= 23 && a != null) {
                a.runOnUiThread(() -> a.requestPermissions(new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1));
            }
        }

        @Override public String mediaStoreSave(String name, byte[] data) {
            if (Build.VERSION.SDK_INT < 29) return null;
            ContentResolver cr = ctx.getContentResolver();
            Uri col = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            // remove our previous copy so the name stays exactly the same
            try (Cursor c = cr.query(col, new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{name}, null)) {
                if (c != null) while (c.moveToNext()) {
                    try { cr.delete(Uri.withAppendedPath(col, String.valueOf(c.getLong(0))), null, null); } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-mpegurl");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
            Uri uri = cr.insert(col, v);
            if (uri == null) return null;
            try (OutputStream os = cr.openOutputStream(uri, "wt")) {
                if (os == null) return null;
                os.write(data);
            } catch (Exception e) { return null; }
            return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + Environment.DIRECTORY_DOWNLOADS + "/" + name;
        }

        @Override public boolean pickFile(String id, File target) {
            final Activity a = current;
            if (a == null || id == null) return false;
            target.getParentFile().mkdirs();
            pickId = id;
            pickTarget = target;
            PICK.put(id, "pending");
            a.runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { a.startActivityForResult(i, REQ_PICK); return; } catch (ActivityNotFoundException ignored) { }
                Intent g = new Intent(Intent.ACTION_GET_CONTENT);
                g.addCategory(Intent.CATEGORY_OPENABLE);
                g.setType("*/*");
                try { a.startActivityForResult(Intent.createChooser(g, "M3U"), REQ_PICK); } catch (Exception e) { PICK.put(id, "nopicker"); }
            });
            return true;
        }

        @Override public String pickStatus(String id) {
            String s = id == null ? null : PICK.get(id);
            return s == null ? "none" : s;
        }

        @Override public String deviceName() {
            return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE;
        }
    }
}
