package com.tigeriptv.studio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.text.InputType;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.FrameLayout;
import android.app.Dialog;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.view.Gravity;
import org.json.JSONObject;
import java.io.FileInputStream;
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
    private boolean backDown;   // only react to a BACK press that started in this app

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
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);
        web.loadUrl("http://127.0.0.1:" + server.getPort() + "/?tv=1");
        web.requestFocus(View.FOCUS_DOWN);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        backDown = false;
        current = this;
        if (web != null) web.evaluateJavascript("window.onAppResume&&window.onAppResume()", null);
    }

    /** Native text box: reliable TV keyboard (also works with phone remote apps). */
    class Bridge {
        @JavascriptInterface
        public void prompt(final String id, final String title, final String value, final boolean password) {
            runOnUiThread(() -> {
                final EditText et = new EditText(MainActivity.this);
                et.setSingleLine(true);
                et.setText(value == null ? "" : value);
                et.setSelection(et.getText().length());
                et.setInputType(password ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                        : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI));
                AlertDialog d = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(title == null || title.isEmpty() ? "M3U" : title)
                        .setView(et)
                        .setPositiveButton("OK", (dlg, w) -> send(id, et.getText().toString()))
                        .setNegativeButton("✕", null)
                        .create();
                et.setOnEditorActionListener((v, actionId, ev) -> { send(id, et.getText().toString()); d.dismiss(); return true; });
                if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                d.show();
                et.requestFocus();
            });
        }
        /** Native channel preview with real resolution — works with .ts and .m3u8 on the receiver. */
        @JavascriptInterface
        public void preview(final String json) { runOnUiThread(() -> showPreview(json)); }

        @JavascriptInterface
        public void previewClose() { runOnUiThread(() -> { if (pv != null && pv.isShowing()) pv.dismiss(); }); }

        private void send(String id, String text) {
            if (web != null) web.evaluateJavascript("window.onNativeInput&&window.onNativeInput(" + WebServer.js(id) + "," + WebServer.js(text) + ")", null);
        }
    }

    // ---------------------------------------------------------------- preview dialog
    private Dialog pv;
    private VideoView pvVideo;
    private TextView pvTitle, pvInfo;
    private Button pvKeep, pvDel, pvPrev, pvClose;
    private long pvStart, pvMs = -1;
    private int pvW, pvH;
    private String pvAlt, pvLang = "ar";
    private boolean pvTriedAlt, pvFailed, pvSilentClose;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button pvButton(String text, final String action, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(color);
        b.setFocusable(true);
        b.setOnFocusChangeListener((v, f) -> { v.setScaleX(f ? 1.08f : 1f); v.setScaleY(f ? 1.08f : 1f); v.setAlpha(f ? 1f : 0.75f); });
        b.setOnClickListener(v -> pvSend(action));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(6), 0, dp(6), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void buildPreview() {
        pv = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.setBackgroundColor(Color.parseColor("#0f1730"));
        pvTitle = new TextView(this);
        pvTitle.setTextColor(Color.WHITE);
        pvTitle.setTextSize(20);
        pvTitle.setGravity(Gravity.CENTER);
        root.addView(pvTitle);
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.58f);
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(w, w * 9 / 16);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.setMargins(0, dp(10), 0, dp(10));
        box.setLayoutParams(blp);
        pvVideo = new VideoView(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        vlp.gravity = Gravity.CENTER;
        box.addView(pvVideo, vlp);
        root.addView(box);
        pvInfo = new TextView(this);
        pvInfo.setTextColor(Color.parseColor("#9fe8ff"));
        pvInfo.setTextSize(18);
        pvInfo.setGravity(Gravity.CENTER);
        pvInfo.setPadding(0, 0, 0, dp(12));
        root.addView(pvInfo);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        pvPrev = pvButton("‹", "prev", Color.parseColor("#2a3760"));
        pvKeep = pvButton("✓", "keep", Color.parseColor("#0f8a5f"));
        pvDel = pvButton("🗑", "delete", Color.parseColor("#c0304f"));
        pvClose = pvButton("✕", "close", Color.parseColor("#2a3760"));
        row.addView(pvPrev); row.addView(pvKeep); row.addView(pvDel); row.addView(pvClose);
        root.addView(row);
        pv.setContentView(root);
        pvVideo.setOnPreparedListener(mp -> {
            mp.setOnVideoSizeChangedListener((m, vw, vh) -> { pvW = vw; pvH = vh; pvUpdate(); });
            pvW = mp.getVideoWidth(); pvH = mp.getVideoHeight(); pvUpdate();
        });
        pvVideo.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && pvMs < 0) { pvMs = SystemClock.elapsedRealtime() - pvStart; pvUpdate(); pvSend("info"); }
            return false;
        });
        pvVideo.setOnErrorListener((mp, what, extra) -> {
            if (!pvTriedAlt && pvAlt != null && !pvAlt.isEmpty()) {
                pvTriedAlt = true;
                pvVideo.setVideoURI(Uri.parse(pvAlt));
                pvVideo.start();
            } else { pvFailed = true; pvUpdate(); pvSend("info"); }
            return true;
        });
        pv.setOnDismissListener(d -> {
            try { pvVideo.stopPlayback(); } catch (Exception ignored) { }
            if (!pvSilentClose) pvSend("close");
            pvSilentClose = false;
        });
    }

    private void showPreview(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (pv == null) buildPreview();
            pvLang = o.optString("lang", "ar");
            boolean en = "en".equals(pvLang);
            pvKeep.setText(en ? "✓ Keep" : "✓ أبقِها");
            pvDel.setText(en ? "🗑 Delete" : "🗑 احذفها");
            pvPrev.setText(en ? "‹ Back" : "› السابقة");
            pvClose.setText(en ? "✕ Close" : "✕ إغلاق");
            pvTitle.setText(o.optString("name") + "    " + o.optString("pos"));
            pvW = 0; pvH = 0; pvMs = -1; pvFailed = false; pvTriedAlt = false;
            pvAlt = o.optString("alt", "");
            pvStart = SystemClock.elapsedRealtime();
            pvUpdate();
            try { pvVideo.stopPlayback(); } catch (Exception ignored) { }
            pvVideo.setVideoURI(Uri.parse(o.optString("url")));
            pvVideo.start();
            if (!pv.isShowing()) pv.show();
            pvKeep.requestFocus();
        } catch (Exception e) {
            if (web != null) web.evaluateJavascript("window.onPreviewAction&&window.onPreviewAction('close',{})", null);
        }
    }

    private void pvUpdate() {
        boolean en = "en".equals(pvLang);
        String t;
        if (pvFailed) t = en ? "✕ Not playing — the channel may be down" : "✕ لا تعمل — القناة قد تكون متوقفة";
        else if (pvH <= 0) t = en ? "⏳ Loading…" : "⏳ جاري التشغيل…";
        else {
            String q = pvH >= 2000 ? "4K UHD" : pvH >= 1000 ? "FHD 1080p" : pvH >= 700 ? "HD 720p" : "SD " + pvH + "p";
            t = pvW + "×" + pvH + "  ·  " + q;
            if (pvMs >= 0) t += "  ·  " + (en ? "started in " : "بدأت خلال ") + String.format(java.util.Locale.US, "%.1f", pvMs / 1000f) + (en ? "s" : " ث");
        }
        pvInfo.setText(t);
    }

    private void pvSend(String action) {
        String info = "{\"w\":" + pvW + ",\"h\":" + pvH + ",\"ms\":" + pvMs + ",\"fail\":" + pvFailed + "}";
        if ("close".equals(action) && pv != null && pv.isShowing()) { pvSilentClose = true; pv.dismiss(); }
        if (web != null) web.evaluateJavascript("window.onPreviewAction&&window.onPreviewAction('" + action + "'," + info + ")", null);
    }

    /** Reopens the app right after an update finished installing. */
    public static class UpdatedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                Intent i = new Intent(context, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(i);
            } catch (Exception ignored) { }
        }
    }

    /** Result of the in-app updater (PackageInstaller). */
    public static class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { context.startActivity(confirm); } catch (Exception ignored) { }
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
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
            if (event.getAction() == KeyEvent.ACTION_DOWN) { backDown = true; return true; }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (!backDown) return true;          // key-up left over from the external player: ignore
                backDown = false;
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
                // Tiger "IP channel search" reads files from Download — save only there on the receiver
                list.add(new File[]{new File(internal, "Download")});
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
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true;
            if (Build.VERSION.SDK_INT >= 23)
                return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            return true;
        }

        @Override public void requestStoragePermission() {
            final Activity a = current;
            // targetSdk 29: the normal runtime dialog grants full (legacy) storage access, also on Android 11+
            if (a != null && Build.VERSION.SDK_INT >= 23
                    && ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                a.runOnUiThread(() -> a.requestPermissions(new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1));
                return;
            }
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

        @Override public String appVersionJson() {
            try {
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                return "{\"name\":" + WebServer.js(pi.versionName) + ",\"code\":" + code + "}";
            } catch (Exception e) { return "{\"name\":\"?\",\"code\":0}"; }
        }

        @Override public boolean installApk(File apk) {
            try {
                PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                // Android 12+: silent self-update when the system allows it (app is its own installer of record)
                if (Build.VERSION.SDK_INT >= 31) {
                    try { params.getClass().getMethod("setRequireUserAction", int.class).invoke(params, 2); } catch (Exception ignored) { }
                }
                int sid = pi.createSession(params);
                PackageInstaller.Session session = pi.openSession(sid);
                try (OutputStream o = session.openWrite("m3u.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                    byte[] buf = new byte[65536]; int r;
                    while ((r = in.read(buf)) != -1) o.write(buf, 0, r);
                    session.fsync(o);
                }
                Intent i = new Intent(ctx, InstallReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0);
                PendingIntent p = PendingIntent.getBroadcast(ctx, sid, i, flags);
                session.commit(p.getIntentSender());
                session.close();
                return true;
            } catch (Exception e) { return false; }
        }

        @Override public String deviceName() {
            return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE;
        }
    }
}
