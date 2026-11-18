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
        ensureServer(getApplicationContext());
        // keep the server alive while the user works from a phone and the TV shows another app
        try {
            Intent svc = new Intent(this, ServerService.class);
            startService(svc);   // app is in the foreground here; the service promotes itself with startForeground()
        } catch (Throwable ignored) { }

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

    static synchronized void ensureServer(Context app) {
        if (server == null) {
            server = new WebServer(new AppHost(app));
            server.start(8686, 8687, 8688, 18686, 28686);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        backDown = false;
        current = this;
        if (web != null) web.evaluateJavascript("window.onAppResume&&window.onAppResume()", null);
        if (PlayerActivity.sendRequested && web != null) {
            PlayerActivity.sendRequested = false;
            web.evaluateJavascript("window.onPlayerDone&&window.onPlayerDone({send:true})", null);
        }
    }

    /** Native text box: reliable TV keyboard (also works with phone remote apps). */
    class Bridge {
        /** "tv" for remote-driven devices (TV boxes, receivers, smart TVs), "mobile" for touch phones/tablets. */
        @JavascriptInterface
        public String deviceKind() {
            try {
                android.app.UiModeManager um = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
                if (um != null && um.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return "tv";
                PackageManager pm = getPackageManager();
                if (pm.hasSystemFeature("android.software.leanback")) return "tv";
                if (!pm.hasSystemFeature("android.hardware.touchscreen")) return "tv";
            } catch (Throwable ignored) { }
            return "mobile";
        }

        @JavascriptInterface
        public boolean openPlayer(String json) {
            PlayerActivity.pending = json;
            PlayerActivity.sendRequested = false;
            runOnUiThread(() -> { try { startActivity(new Intent(MainActivity.this, PlayerActivity.class)); } catch (Throwable t) { } });
            return true;
        }

        @JavascriptInterface
        public String getMode() { return getSharedPreferences("m3u", MODE_PRIVATE).getString("mode", ""); }

        @JavascriptInterface
        public void setMode(String m) { getSharedPreferences("m3u", MODE_PRIVATE).edit().putString("mode", m == null ? "" : m).apply(); }

        @JavascriptInterface
        public String clipboard() {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                    return t == null ? "" : t.toString();
                }
            } catch (Throwable ignored) { }
            return "";
        }

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

        /** "ok" = may reopen itself after updates, "need" = user can allow it, "na" = not available on this device. */
        @JavascriptInterface
        public String overlayState() {
            try {
                if (Build.VERSION.SDK_INT < 29) return "ok";
                if (Settings.canDrawOverlays(MainActivity.this)) return "ok";
                Intent t = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                if (t.resolveActivity(getPackageManager()) != null) return "need";
                t = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                if (t.resolveActivity(getPackageManager()) != null) return "need";
            } catch (Throwable ignored) { }
            return "na";
        }

        @JavascriptInterface
        public boolean overlayRequest() {
            Intent[] tries = new Intent[] {
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            };
            for (Intent t : tries) {
                try { t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(t); return true; } catch (Throwable ignored) { }
            }
            return false;
        }

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
            relaunch(context);
        }
    }

    /** Reopen the app after an update. Android 10+ blocks direct starts from the
     *  background, but an activity PendingIntent fired by the system AlarmManager is allowed. */
    static void relaunch(Context context) {
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? 0x04000000 : 0);
            PendingIntent pi = PendingIntent.getActivity(context, 7713, i, fl);
            android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            long at = System.currentTimeMillis() + 700;
            if (am != null) {
                if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi);
                else am.setExact(android.app.AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable ignored) { }
        try { context.startActivity(i); } catch (Throwable ignored) { }
    }

    /** Result of the in-app updater (PackageInstaller). */
    public static class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (status == PackageInstaller.STATUS_SUCCESS) { relaunch(context); return; }
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

        @Override public void scanFile(String path) {
            try { android.media.MediaScannerConnection.scanFile(ctx, new String[]{path}, new String[]{"audio/x-mpegurl"}, null); } catch (Throwable ignored) { }
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

    public static class ServerService extends android.app.Service {
        private static final String CH = "m3u_server";

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            MainActivity.ensureServer(getApplicationContext());
            try {
                android.app.Notification.Builder b;
                if (Build.VERSION.SDK_INT >= 26) {
                    android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) nm.createNotificationChannel(new android.app.NotificationChannel(CH, "M3U", android.app.NotificationManager.IMPORTANCE_MIN));
                    b = new android.app.Notification.Builder(this, CH);
                } else {
                    b = new android.app.Notification.Builder(this);
                }
                Intent open = new Intent(this, MainActivity.class);
                PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
                b.setContentTitle("M3U").setContentText("جاهز للتحكم من الجوال").setSmallIcon(getApplicationInfo().icon)
                 .setContentIntent(pi).setOngoing(true);
                startForeground(8686, b.build());
            } catch (Throwable ignored) { }
            return START_STICKY;
        }

        @Override
        public android.os.IBinder onBind(Intent intent) { return null; }
    }

    /* ======================================================================= built-in player (Media3 / ExoPlayer) */
    public static class PlayerActivity extends Activity {
        static String pending;                 // JSON handed over by the web UI (can be very large)
        static volatile boolean sendRequested; // user pressed "send" inside the player

        private androidx.media3.exoplayer.ExoPlayer player;
        private androidx.media3.ui.PlayerView pv;
        private final ArrayList<String[]> items = new ArrayList<>();   // n, name, url, pack, dead
        private final ArrayList<String> packs = new ArrayList<>();
        private int idx = 0, total = 0, retries = 0;
        private boolean preview = true, ar = true, portrait = false;
        private android.graphics.Typeface tf;
        private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

        private FrameLayout root, videoBox;
        private LinearLayout osd, listPanel, phoneBody;
        private TextView tNum, tName, tMeta, tQ, tState, tUp, tDown, tBanner, tClock, tMsg, tDigits, tLive;
        private android.widget.ListView list, phoneList;
        private android.widget.ProgressBar spin;
        private LinearLayout tabs, phoneTabs;
        private String listPack = null;          // pack shown in the channel list
        private final ArrayList<Integer> listIdx = new ArrayList<>();
        private String digits = "";
        private float sx, sy;

        private final Runnable hideOsd = () -> { if (osd != null && !portrait) osd.animate().alpha(0f).setDuration(250).start(); if (tBanner != null && !portrait) tBanner.animate().alpha(0f).setDuration(250).start(); };
        private final Runnable tick = new Runnable() { public void run() { if (tClock != null) tClock.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date())); h.postDelayed(this, 15000); } };
        private final Runnable digitGo = () -> { if (!digits.isEmpty()) { int n = 0; try { n = Integer.parseInt(digits); } catch (Exception ignored) { } digits = ""; tDigits.setVisibility(View.GONE); jumpToNumber(n); } };

        private String L(String a, String e) { return ar ? a : e; }
        private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            try {
                JSONObject j = new JSONObject(pending == null ? "{}" : pending);
                org.json.JSONArray a = j.optJSONArray("items");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String pk = o.optString("pack");
                    items.add(new String[]{String.valueOf(o.optInt("n", i + 1)), o.optString("name"), o.optString("url"), pk, o.optBoolean("dead") ? "1" : ""});
                    if (!packs.contains(pk)) packs.add(pk);
                }
                idx = Math.max(0, Math.min(items.size() - 1, j.optInt("start", 0)));
                preview = j.optBoolean("preview", true);
                ar = !"en".equals(j.optString("lang", "ar"));
                total = j.optInt("total", items.size());
            } catch (Exception e) { }
            pending = null;
            if (items.isEmpty()) { finish(); return; }
            tf = loadFont();
            buildUi();
            h.post(tick);
        }

        /** El Messiri is bundled inside app.css (base64) so the web UI and the player share one font file. */
        private android.graphics.Typeface loadFont() {
            try {
                File f = new File(getCacheDir(), "messiri.ttf");
                if (!f.exists() || f.length() < 10000) {
                    StringBuilder sb = new StringBuilder();
                    try (InputStream is = getAssets().open("web/app.css")) {
                        byte[] buf = new byte[65536]; int r; java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        while ((r = is.read(buf)) > 0) { bo.write(buf, 0, r); if (bo.size() > 400000) break; }
                        sb.append(new String(bo.toByteArray(), "UTF-8"));
                    }
                    String s = sb.toString(); String key = "base64,";
                    int a = s.indexOf("font/ttf;" + key); if (a < 0) return null;
                    a += ("font/ttf;" + key).length(); int e = s.indexOf(')', a);
                    byte[] font = android.util.Base64.decode(s.substring(a, e), android.util.Base64.DEFAULT);
                    try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(font); }
                }
                return android.graphics.Typeface.createFromFile(f);
            } catch (Throwable t) { return null; }
        }

        private TextView tv(float sp, int color, boolean bold) {
            TextView t = new TextView(this);
            t.setTextSize(sp); t.setTextColor(color);
            if (tf != null) t.setTypeface(tf, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            else if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            return t;
        }
        private android.graphics.drawable.GradientDrawable pill(int fill, int stroke, float r) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(fill); g.setCornerRadius(dp(r)); if (stroke != 0) g.setStroke(dp(1), stroke); return g;
        }

        private void buildUi() {
            portrait = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
            root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
            root.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            videoBox = new FrameLayout(this);
            pv = new androidx.media3.ui.PlayerView(this);
            pv.setUseController(false); pv.setFocusable(false); pv.setKeepScreenOn(true);
            pv.setShutterBackgroundColor(Color.BLACK);
            videoBox.addView(pv, new FrameLayout.LayoutParams(-1, -1));
            spin = new android.widget.ProgressBar(this); spin.setIndeterminate(true);
            videoBox.addView(spin, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));
            tMsg = tv(17, Color.WHITE, true); tMsg.setGravity(Gravity.CENTER); tMsg.setBackground(pill(0xCC0B1220, 0x55FFFFFF, 14));
            tMsg.setPadding(dp(18), dp(10), dp(18), dp(10)); tMsg.setVisibility(View.GONE);
            videoBox.addView(tMsg, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            tDigits = tv(44, Color.WHITE, true); tDigits.setBackground(pill(0xCC0B1220, 0x8822D3EE, 16)); tDigits.setPadding(dp(22), dp(6), dp(22), dp(6)); tDigits.setVisibility(View.GONE);
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END); dl.setMargins(dp(24), dp(24), dp(24), dp(24));
            videoBox.addView(tDigits, dl);

            // top: clock + live + preview banner
            LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
            tLive = tv(13, Color.WHITE, true); tLive.setText("● " + L("مباشر", "LIVE")); tLive.setBackground(pill(0x8C050812, 0x22FFFFFF, 20)); tLive.setPadding(dp(12), dp(5), dp(12), dp(5));
            tClock = tv(15, Color.WHITE, true); tClock.setBackground(pill(0x8C050812, 0x22FFFFFF, 20)); tClock.setPadding(dp(12), dp(5), dp(12), dp(5));
            View sp = new View(this);
            top.addView(tLive); top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f)); top.addView(tClock);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); tl.setMargins(dp(18), dp(14), dp(18), 0);
            videoBox.addView(top, tl);
            tBanner = tv(13, 0xFFE9D5FF, true); tBanner.setBackground(pill(0x407C3AED, 0x8CA78BFA, 10)); tBanner.setPadding(dp(12), dp(5), dp(12), dp(5));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START); bl.setMargins(dp(18), dp(52), dp(18), 0);
            videoBox.addView(tBanner, bl); if (!preview) tBanner.setVisibility(View.GONE);

            // bottom info bar
            osd = new LinearLayout(this); osd.setOrientation(LinearLayout.HORIZONTAL); osd.setGravity(Gravity.CENTER_VERTICAL);
            osd.setPadding(dp(22), dp(36), dp(22), dp(18));
            android.graphics.drawable.GradientDrawable grad = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF203060E, 0xB303060E, 0x0003060E});
            osd.setBackground(grad);
            tNum = tv(portrait ? 30 : 44, Color.WHITE, true); tNum.setPadding(0, 0, dp(16), 0);
            LinearLayout mid = new LinearLayout(this); mid.setOrientation(LinearLayout.VERTICAL);
            tName = tv(portrait ? 18 : 26, Color.WHITE, true);
            LinearLayout meta = new LinearLayout(this); meta.setOrientation(LinearLayout.HORIZONTAL); meta.setGravity(Gravity.CENTER_VERTICAL);
            tQ = tv(12, 0xFF67E8F9, true); tQ.setBackground(pill(0x2622D3EE, 0x6622D3EE, 6)); tQ.setPadding(dp(7), dp(1), dp(7), dp(1)); tQ.setVisibility(View.GONE);
            tState = tv(12, 0xFF6EE7B7, true); tState.setPadding(dp(8), 0, dp(8), 0);
            tMeta = tv(13, 0xFFA9B8D8, false);
            meta.addView(tQ); meta.addView(tState); meta.addView(tMeta, new LinearLayout.LayoutParams(0, -2, 1f));
            mid.addView(tName); mid.addView(meta);
            osd.addView(tNum); osd.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
            if (!portrait) {
                LinearLayout nx = new LinearLayout(this); nx.setOrientation(LinearLayout.VERTICAL);
                tUp = tv(13, 0xFFCBD5E1, true); tDown = tv(13, 0xFFCBD5E1, true);
                for (TextView t : new TextView[]{tUp, tDown}) { t.setBackground(pill(0x0DFFFFFF, 0x14FFFFFF, 10)); t.setPadding(dp(10), dp(5), dp(10), dp(5)); t.setMaxWidth(dp(260)); }
                LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(-2, -2); g.topMargin = dp(5);
                nx.addView(tUp); nx.addView(tDown, g);
                osd.addView(nx);
            }
            videoBox.addView(osd, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

            if (portrait) {
                // phone portrait: video on top, channels below, send button at the bottom
                LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
                int w = getResources().getDisplayMetrics().widthPixels;
                col.addView(videoBox, new LinearLayout.LayoutParams(-1, w * 9 / 16 + dp(40)));
                phoneBody = new LinearLayout(this); phoneBody.setOrientation(LinearLayout.VERTICAL); phoneBody.setBackgroundColor(0xFF05070F);
                phoneBody.setPadding(dp(12), dp(10), dp(12), dp(10));
                android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
                phoneTabs = new LinearLayout(this); hs.addView(phoneTabs);
                phoneBody.addView(hs);
                phoneList = new android.widget.ListView(this); phoneList.setDivider(null); phoneList.setDividerHeight(dp(6));
                phoneList.setSelector(new android.graphics.drawable.ColorDrawable(0));
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 0, 1f); ll.topMargin = dp(8);
                phoneBody.addView(phoneList, ll);
                if (preview) {
                    TextView send = tv(16, Color.WHITE, true); send.setGravity(Gravity.CENTER);
                    send.setText("🚀  " + L("الترتيب تمام — أرسل", "Looks good — send"));
                    android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT, new int[]{0xFF0891B2, 0xFF4F46E5});
                    sg.setCornerRadius(dp(16)); send.setBackground(sg); send.setPadding(0, dp(14), 0, dp(14));
                    send.setOnClickListener(v -> { sendRequested = true; finish(); });
                    LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(8);
                    phoneBody.addView(send, sl);
                }
                col.addView(phoneBody, new LinearLayout.LayoutParams(-1, 0, 1f));
                root.addView(col, new FrameLayout.LayoutParams(-1, -1));
                listPack = items.get(idx)[3];
                fillTabs(phoneTabs); fillList(phoneList);
            } else {
                root.addView(videoBox, new FrameLayout.LayoutParams(-1, -1));
                // side channel list (opens with OK / tap)
                listPanel = new LinearLayout(this); listPanel.setOrientation(LinearLayout.VERTICAL);
                listPanel.setPadding(dp(18), dp(16), dp(18), dp(16));
                android.graphics.drawable.GradientDrawable pg = new android.graphics.drawable.GradientDrawable(ar ? android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT : android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{0xF703060E, 0xE603060E, 0x9903060E});
                listPanel.setBackground(pg);
                TextView hd = tv(20, Color.WHITE, true); hd.setText(L("قنواتك", "Your channels") + "  ·  " + total);
                listPanel.addView(hd);
                android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.setFocusable(false);
                tabs = new LinearLayout(this); hs.addView(tabs);
                LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2); hl.topMargin = dp(10); hl.bottomMargin = dp(8);
                listPanel.addView(hs, hl);
                list = new android.widget.ListView(this); list.setDivider(null); list.setDividerHeight(dp(4));
                list.setSelector(pill(0x2422D3EE, 0xFF22D3EE, 12)); list.setDrawSelectorOnTop(false); list.setItemsCanFocus(false);
                listPanel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
                int pw = Math.min(dp(470), (int) (getResources().getDisplayMetrics().widthPixels * 0.62f));
                root.addView(listPanel, new FrameLayout.LayoutParams(pw, -1, Gravity.START));
                listPanel.setVisibility(View.GONE);
            }
            setContentView(root);
            if (!portrait) {
                videoBox.setOnTouchListener((v, e) -> touch(e));
            } else {
                videoBox.setOnTouchListener((v, e) -> touch(e));
            }
            makePlayer();
            play(idx);
        }

        private boolean touch(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: sx = e.getX(); sy = e.getY(); return true;
                case android.view.MotionEvent.ACTION_UP:
                    float dx = e.getX() - sx, dy = e.getY() - sy;
                    if (Math.abs(dy) > dp(60) && Math.abs(dy) > Math.abs(dx) * 1.3f) { zap(dy < 0 ? 1 : -1); }
                    else if (Math.abs(dx) < dp(12) && Math.abs(dy) < dp(12)) {
                        if (listPanel != null && listPanel.getVisibility() == View.VISIBLE) listPanel.setVisibility(View.GONE);
                        else if (listPanel != null && osd.getAlpha() > 0.5f) openList();
                        else showOsd();
                    }
                    return true;
            }
            return true;
        }

        private void makePlayer() {
            androidx.media3.datasource.DefaultHttpDataSource.Factory http = new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setUserAgent(WebServer.UA).setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(15000);
            androidx.media3.exoplayer.DefaultLoadControl lc = new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(3000, 30000, 1000, 2000).build();
            player = new androidx.media3.exoplayer.ExoPlayer.Builder(this)
                    .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http))
                    .setLoadControl(lc).build();
            pv.setPlayer(player);
            player.addListener(new androidx.media3.common.Player.Listener() {
                @Override public void onPlaybackStateChanged(int st) {
                    spin.setVisibility(st == androidx.media3.common.Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                    if (st == androidx.media3.common.Player.STATE_READY) { retries = 0; tMsg.setVisibility(View.GONE); tState.setTextColor(0xFF6EE7B7); tState.setText("● " + L("تعمل", "Working")); }
                }
                @Override public void onPlayerError(androidx.media3.common.PlaybackException err) {
                    if (retries < 1) { retries++; h.postDelayed(() -> play(idx), 1500); return; }
                    spin.setVisibility(View.GONE);
                    tMsg.setText(L("القناة لا تعمل الآن", "This channel is not working now")); tMsg.setVisibility(View.VISIBLE);
                    tState.setTextColor(0xFFFF8FA3); tState.setText("● " + L("متوقفة", "Stopped"));
                    showOsd();
                }
                @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize vs) {
                    int hgt = Math.min(vs.height, vs.width > 0 ? vs.width : vs.height);
                    if (vs.height <= 0) return;
                    String q = vs.height >= 2000 ? "4K" : vs.height >= 1000 ? "FHD" : vs.height >= 700 ? "HD" : "SD";
                    tQ.setText(q); tQ.setVisibility(View.VISIBLE);
                }
            });
        }

        private void play(int i) {
            if (items.isEmpty()) return;
            idx = (i % items.size() + items.size()) % items.size();
            String[] c = items.get(idx);
            tNum.setText(c[0]); tName.setText(c[1]);
            int inPack = 0, packTotal = 0;
            for (int k = 0; k < items.size(); k++) if (items.get(k)[3].equals(c[3])) { packTotal++; if (k <= idx) inPack++; }
            tMeta.setText(c[3] + "  ·  " + L("القناة ", "Channel ") + inPack + L(" من ", " of ") + packTotal);
            tQ.setVisibility(View.GONE); tMsg.setVisibility(View.GONE);
            tState.setTextColor(0xFFA9B8D8); tState.setText("● " + L("جاري التشغيل…", "Loading…"));
            tBanner.setText("⧉ " + L("معاينة الملف قبل الإرسال", "Previewing your list") + "  ·  " + c[0] + " / " + total);
            if (tUp != null) {
                String[] nx = items.get((idx + 1) % items.size()), pr = items.get((idx - 1 + items.size()) % items.size());
                tUp.setText("▲ " + nx[0] + "   " + nx[1]); tDown.setText("▼ " + pr[0] + "   " + pr[1]);
            }
            showOsd();
            String url = c[2];
            androidx.media3.common.MediaItem.Builder mb = new androidx.media3.common.MediaItem.Builder().setUri(url);
            String lu = url.toLowerCase(java.util.Locale.US);
            if (lu.contains(".m3u8")) mb.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8);
            try {
                player.stop();
                player.setMediaItem(mb.build());
                player.prepare();
                player.setPlayWhenReady(true);
            } catch (Exception e) { }
            if (phoneList != null) { if (!c[3].equals(listPack)) { listPack = c[3]; fillTabs(phoneTabs); } fillList(phoneList); }
        }

        private void zap(int d) { retries = 0; play(idx + d); }

        private void jumpToNumber(int n) {
            for (int k = 0; k < items.size(); k++) if (items.get(k)[0].equals(String.valueOf(n))) { retries = 0; play(k); return; }
            tMsg.setText(L("لا توجد قناة بالرقم ", "No channel number ") + n); tMsg.setVisibility(View.VISIBLE);
            h.postDelayed(() -> tMsg.setVisibility(View.GONE), 1800);
        }

        private void showOsd() {
            osd.animate().cancel(); osd.setAlpha(1f);
            if (preview) { tBanner.animate().cancel(); tBanner.setAlpha(1f); }
            h.removeCallbacks(hideOsd); h.postDelayed(hideOsd, 4500);
        }

        private void fillTabs(LinearLayout row) {
            row.removeAllViews();
            for (String p : packs) {
                TextView t = tv(14, p.equals(listPack) ? Color.WHITE : 0xFFCBD5E1, true);
                t.setText(p); t.setPadding(dp(14), dp(7), dp(14), dp(7));
                if (p.equals(listPack)) {
                    android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT, new int[]{0xFF0891B2, 0xFF4F46E5});
                    g.setCornerRadius(dp(20)); t.setBackground(g);
                } else t.setBackground(pill(0x0FFFFFFF, 0x1AFFFFFF, 20));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMarginEnd(dp(7));
                final String pk = p;
                t.setOnClickListener(v -> { listPack = pk; fillTabs(row); fillList(row == tabs ? list : phoneList); });
                row.addView(t, lp);
            }
        }

        private void fillList(android.widget.ListView lv) {
            listIdx.clear();
            int sel = 0;
            for (int k = 0; k < items.size(); k++) if (items.get(k)[3].equals(listPack)) { if (k == idx) sel = listIdx.size(); listIdx.add(k); }
            final ArrayList<Integer> ix = new ArrayList<>(listIdx);
            lv.setAdapter(new android.widget.BaseAdapter() {
                public int getCount() { return ix.size(); }
                public Object getItem(int p) { return ix.get(p); }
                public long getItemId(int p) { return p; }
                public View getView(int p, View cv, android.view.ViewGroup parent) {
                    LinearLayout r = cv instanceof LinearLayout ? (LinearLayout) cv : null;
                    TextView n, nm;
                    if (r == null) {
                        r = new LinearLayout(PlayerActivity.this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
                        r.setPadding(dp(12), dp(10), dp(12), dp(10));
                        n = tv(16, 0xFF7DD3FC, true); n.setMinWidth(dp(44)); n.setGravity(Gravity.CENTER);
                        nm = tv(17, 0xFFE2E8F0, true); nm.setPadding(dp(10), 0, dp(10), 0);
                        r.addView(n); r.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
                    } else { n = (TextView) r.getChildAt(0); nm = (TextView) r.getChildAt(1); }
                    String[] c = items.get(ix.get(p));
                    n.setText(c[0]);
                    nm.setText((ix.get(p) == idx ? "▶  " : "") + c[1]);
                    nm.setTextColor(c[4].isEmpty() ? (ix.get(p) == idx ? 0xFF22D3EE : 0xFFE2E8F0) : 0xFFFF8FA3);
                    r.setBackground(ix.get(p) == idx ? pill(0x2222D3EE, 0x8822D3EE, 12) : (lv == phoneList ? pill(0x09FFFFFF, 0x0FFFFFFF, 12) : null));
                    return r;
                }
            });
            lv.setOnItemClickListener((a, v, p, id) -> { retries = 0; play(ix.get(p)); if (lv == list) { listPanel.setVisibility(View.GONE); } });
            lv.setSelection(Math.max(0, sel - 2));
            if (lv == list) { lv.requestFocus(); lv.setSelection(sel); }
        }

        private void openList() {
            if (listPanel == null) return;
            listPack = items.get(idx)[3];
            fillTabs(tabs); fillList(list);
            listPanel.setVisibility(View.VISIBLE);
            list.requestFocus();
        }

        private void switchTab(int d) {
            int i = packs.indexOf(listPack); if (i < 0) i = 0;
            i = (i + d + packs.size()) % packs.size(); listPack = packs.get(i);
            fillTabs(tabs); fillList(list);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            int k = e.getKeyCode();
            boolean listOpen = listPanel != null && listPanel.getVisibility() == View.VISIBLE;
            if (e.getAction() != KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_BACK) return true;
                return listOpen ? super.dispatchKeyEvent(e) : true;
            }
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                if (listOpen) { listPanel.setVisibility(View.GONE); return true; }
                finish(); return true;
            }
            if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
                digits += (char) ('0' + (k - KeyEvent.KEYCODE_0)); if (digits.length() > 5) digits = digits.substring(digits.length() - 5);
                tDigits.setText(digits); tDigits.setVisibility(View.VISIBLE);
                h.removeCallbacks(digitGo); h.postDelayed(digitGo, 1600); return true;
            }
            if (k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_PAGE_UP) { zap(1); return true; }
            if (k == KeyEvent.KEYCODE_CHANNEL_DOWN || k == KeyEvent.KEYCODE_PAGE_DOWN) { zap(-1); return true; }
            if (listOpen) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) { switchTab(ar ? 1 : -1); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { switchTab(ar ? -1 : 1); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    int p = list.getSelectedItemPosition();
                    if (p >= 0 && p < listIdx.size()) { retries = 0; play(listIdx.get(p)); }
                    listPanel.setVisibility(View.GONE); return true;
                }
                return super.dispatchKeyEvent(e);
            }
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP: zap(1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: zap(-1); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (portrait) showOsd(); else openList(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_MENU:
                    showOsd(); return true;
            }
            return super.dispatchKeyEvent(e);
        }

        @Override public void onConfigurationChanged(android.content.res.Configuration c) {
            super.onConfigurationChanged(c);
            boolean p = c.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
            if (p != portrait) { releasePlayer(); buildUi(); }
        }
        private void releasePlayer() { if (player != null) { try { player.release(); } catch (Exception ignored) { } player = null; } }
        @Override protected void onStop() { super.onStop(); if (player != null) player.setPlayWhenReady(false); }
        @Override protected void onStart() { super.onStart(); if (player != null) { player.setPlayWhenReady(true); if (player.getPlaybackState() == androidx.media3.common.Player.STATE_IDLE) play(idx); } }
        @Override protected void onDestroy() { super.onDestroy(); h.removeCallbacksAndMessages(null); releasePlayer(); }
    }

}
