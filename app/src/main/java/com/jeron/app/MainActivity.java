package com.jeron.app;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_FILE = 1;
    private static final int REQ_VOICE = 2;
    private static final int REQ_STORAGE = 3;
    private static final int REQ_AUDIO = 4;
    private static final int REQ_NOTIF = 5;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingWebPerm;
    private SpeechRecognizer recognizer;
    private String pendingSttLang;
    private final Map<String, SaveJob> saves = new ConcurrentHashMap<>();
    private final Map<String, HttpURLConnection> calls = new ConcurrentHashMap<>();

    /** hosts whose files are cached on disk so libraries/models keep working without internet */
    private static boolean cacheable(String host) {
        return host != null && (host.equals("cdn.jsdelivr.net") || host.equals("cdnjs.cloudflare.com")
                || host.equals("unpkg.com") || host.equals("huggingface.co")
                || host.endsWith(".huggingface.co") || host.endsWith(".hf.co"));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);

        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return loader.shouldInterceptRequest(u);
                if ("GET".equalsIgnoreCase(request.getMethod()) && "https".equals(u.getScheme())
                        && cacheable(u.getHost())) {
                    try {
                        WebResourceResponse r = cachedResponse(u.toString());
                        if (r != null) return r;
                    } catch (Throwable ignored) {
                    }
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "Jeron"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean needsMic = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) needsMic = true;
                    }
                    if (!needsMic) {
                        request.deny();
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        pendingWebPerm = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
                    } else {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    }
                });
            }
        });

        if (state == null) {
            web.loadUrl("https://" + HOST + "/assets/index.html");
        } else {
            web.restoreState(state);
        }
    }

    /* ------------------------------------------------------------------ CDN disk cache */

    private static String sha1(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private WebResourceResponse cachedResponse(String url) throws Exception {
        File dir = new File(getFilesDir(), "cdn");
        dir.mkdirs();
        String h = sha1(url);
        File data = new File(dir, h);
        File meta = new File(dir, h + ".type");
        if (!data.exists() || !meta.exists()) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Jeron");
            int code = c.getResponseCode();
            if (code != 200) {
                c.disconnect();
                return null; // let WebView try itself
            }
            String type = c.getContentType();
            if (type == null) type = "application/octet-stream";
            File tmp = new File(dir, h + ".tmp");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                c.disconnect();
            }
            if (!tmp.renameTo(data)) {
                tmp.delete();
                return null;
            }
            try (FileOutputStream mo = new FileOutputStream(meta)) {
                mo.write(type.getBytes("UTF-8"));
            }
        }
        String type = "application/octet-stream";
        try (FileInputStream mi = new FileInputStream(meta)) {
            byte[] b = new byte[(int) meta.length()];
            int n = mi.read(b);
            if (n > 0) type = new String(b, 0, n, "UTF-8");
        }
        String mime = type.split(";")[0].trim();
        String enc = type.toLowerCase().contains("charset=") ? "utf-8" : null;
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", "max-age=31536000");
        WebResourceResponse r = new WebResourceResponse(mime, enc, new FileInputStream(data));
        if (Build.VERSION.SDK_INT >= 21) r.setResponseHeaders(headers);
        return r;
    }

    /* ------------------------------------------------------------------ lifecycle */

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        try {
            if (recognizer != null) recognizer.destroy();
        } catch (Exception ignored) {
        }
        for (HttpURLConnection c : calls.values()) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (req == REQ_AUDIO) {
            if (pendingWebPerm != null) {
                if (ok) pendingWebPerm.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                else pendingWebPerm.deny();
                pendingWebPerm = null;
            }
            if (pendingSttLang != null) {
                String l = pendingSttLang;
                pendingSttLang = null;
                if (ok) startStt(l);
                else js("window.onStt&&window.onStt('err',9)");
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE) {
            Uri[] result = null;
            if (fileCallback != null) {
                if (res == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        result = new Uri[n];
                        for (int i = 0; i < n; i++) {
                            result[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        result = new Uri[]{data.getData()};
                    }
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
        } else if (req == REQ_VOICE && res == RESULT_OK && data != null) {
            ArrayList<String> list = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (list != null && !list.isEmpty()) {
                js("window.onVoice&&window.onVoice(" + JSONObject.quote(list.get(0)) + ")");
            }
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.jeronBack&&window.jeronBack())?'1':'0'", value -> {
            if (!"\"1\"".equals(value)) finish();
        });
    }

    private void js(final String code) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    /* ------------------------------------------------------------------ speech recognition (voice chat) */

    private void startStt(final String lang) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("window.onStt&&window.onStt('err',12)");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingSttLang = lang;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rms) { js("window.onStt&&window.onStt('rms'," + rms + ")"); }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int e) { js("window.onStt&&window.onStt('err'," + e + ")"); }
            @Override public void onResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (l != null && !l.isEmpty()) js("window.onStt&&window.onStt('final'," + JSONObject.quote(l.get(0)) + ")");
                else js("window.onStt&&window.onStt('err',7)");
            }
            @Override public void onPartialResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (l != null && !l.isEmpty()) js("window.onStt&&window.onStt('part'," + JSONObject.quote(l.get(0)) + ")");
            }
            @Override public void onEvent(int t, Bundle b) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang == null || lang.isEmpty() ? "ar-IQ" : lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recognizer.startListening(i);
    }

    /* ------------------------------------------------------------------ saving big files in chunks */

    private static class SaveJob {
        OutputStream out;
        Uri uri;
        File file;
        boolean pending;
    }

    private String folderFor(String mime) {
        if (mime != null && mime.startsWith("video")) return Environment.DIRECTORY_MOVIES;
        if (mime != null && mime.startsWith("image")) return Environment.DIRECTORY_PICTURES;
        if (mime != null && mime.startsWith("audio")) return Environment.DIRECTORY_MUSIC;
        return Environment.DIRECTORY_DOWNLOADS;
    }

    private SaveJob openSave(String name, String mime) throws Exception {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) safe = "jeron";
        String folder = folderFor(mime);
        SaveJob j = new SaveJob();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, folder + "/Jeron");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection;
            if (mime != null && mime.startsWith("video")) collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            else if (mime != null && mime.startsWith("image")) collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            else if (mime != null && mime.startsWith("audio")) collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            else collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            j.uri = getContentResolver().insert(collection, v);
            if (j.uri == null) throw new Exception("insert failed");
            j.out = getContentResolver().openOutputStream(j.uri);
            j.pending = true;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(folder), "Jeron");
            dir.mkdirs();
            j.file = new File(dir, safe);
            j.out = new FileOutputStream(j.file);
        }
        if (j.out == null) throw new Exception("no stream");
        return j;
    }

    /* ------------------------------------------------------------------ JS bridge */

    private class Bridge {
        @JavascriptInterface
        public void copy(final String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Jeron", text));
            });
        }

        @JavascriptInterface
        public void startVoice() {
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-IQ");
                try {
                    startActivityForResult(i, REQ_VOICE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Voice input unavailable", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void sttStart(final String lang) {
            runOnUiThread(() -> {
                try {
                    startStt(lang);
                } catch (Exception e) {
                    js("window.onStt&&window.onStt('err',5)");
                }
            });
        }

        @JavascriptInterface
        public void sttStop() {
            runOnUiThread(() -> {
                try {
                    if (recognizer != null) recognizer.stopListening();
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void sttCancel() {
            runOnUiThread(() -> {
                try {
                    if (recognizer != null) recognizer.cancel();
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void keepAwake(final boolean on, final String text) {
            runOnUiThread(() -> {
                if (on) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                    }
                    try {
                        Intent i = new Intent(MainActivity.this, KeepAliveService.class);
                        i.putExtra("text", text);
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                        else startService(i);
                    } catch (Exception ignored) {
                    }
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    try {
                        stopService(new Intent(MainActivity.this, KeepAliveService.class));
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void notify(final String title, final String text) {
            runOnUiThread(() -> {
                try {
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm == null) return;
                    if (Build.VERSION.SDK_INT >= 26) {
                        nm.createNotificationChannel(new NotificationChannel("jeron_done", "Jeron",
                                NotificationManager.IMPORTANCE_DEFAULT));
                    }
                    Notification.Builder b = Build.VERSION.SDK_INT >= 26
                            ? new Notification.Builder(MainActivity.this, "jeron_done")
                            : new Notification.Builder(MainActivity.this);
                    b.setContentTitle(title).setContentText(text)
                            .setSmallIcon(android.R.drawable.stat_notify_chat).setAutoCancel(true);
                    nm.notify(1002, b.build());
                } catch (Exception ignored) {
                }
            });
        }

        /* ---- chunked save (big ZIP / video / audio) ---- */
        @JavascriptInterface
        public String saveBegin(String name, String mime) {
            try {
                String id = Long.toString(System.nanoTime(), 36);
                saves.put(id, openSave(name, mime));
                return id;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean saveChunk(String id, String b64) {
            SaveJob j = saves.get(id);
            if (j == null) return false;
            try {
                j.out.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                saves.remove(id);
                return false;
            }
        }

        @JavascriptInterface
        public boolean saveEnd(String id) {
            SaveJob j = saves.remove(id);
            if (j == null) return false;
            try {
                j.out.close();
                if (j.pending && j.uri != null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(j.uri, v, null, null);
                } else if (j.file != null) {
                    MediaScannerConnection.scanFile(MainActivity.this,
                            new String[]{j.file.getAbsolutePath()}, null, null);
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean save(String name, String mime, String b64) {
            String id = saveBegin(name, mime);
            if (id.isEmpty()) return false;
            if (!saveChunk(id, b64)) return false;
            return saveEnd(id);
        }

        /* ---- native HTTP (bypasses WebView CORS, streams the response back to JS) ---- */
        @JavascriptInterface
        public void http(final String id, final String method, final String url,
                         final String headersJson, final String bodyB64) {
            new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    calls.put(id, c);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(120000);
                    c.setRequestMethod(method);
                    c.setInstanceFollowRedirects(true);
                    JSONObject h = new JSONObject(headersJson == null || headersJson.isEmpty() ? "{}" : headersJson);
                    Iterator<String> it = h.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        if (k.equalsIgnoreCase("host") || k.equalsIgnoreCase("content-length")) continue;
                        c.setRequestProperty(k, h.getString(k));
                    }
                    if (bodyB64 != null && !bodyB64.isEmpty() && !method.equals("GET") && !method.equals("HEAD")) {
                        c.setDoOutput(true);
                        try (OutputStream o = c.getOutputStream()) {
                            o.write(Base64.decode(bodyB64, Base64.DEFAULT));
                        }
                    }
                    int code = c.getResponseCode();
                    String ct = c.getContentType();
                    js("window.__nh&&window.__nh('" + id + "','head'," + code + "," + JSONObject.quote(ct == null ? "" : ct) + ")");
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    if (in != null) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            String b = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP);
                            js("window.__nh&&window.__nh('" + id + "','data','" + b + "')");
                        }
                        in.close();
                    }
                    js("window.__nh&&window.__nh('" + id + "','end')");
                } catch (Exception e) {
                    String m = e.getMessage() == null ? "network error" : e.getMessage();
                    js("window.__nh&&window.__nh('" + id + "','err'," + JSONObject.quote(m) + ")");
                } finally {
                    calls.remove(id);
                    if (c != null) c.disconnect();
                }
            }).start();
        }

        @JavascriptInterface
        public void httpAbort(String id) {
            HttpURLConnection c = calls.remove(id);
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
