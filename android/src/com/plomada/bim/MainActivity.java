package com.plomada.bim;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Contenedor de la app web Plomada BIM.
 * - Sirve la app desde assets/www bajo un origen https ficticio (necesario para fetch y WebAssembly).
 * - El botón «Abrir modelo» abre el selector del sistema (almacenamiento local) o el de Google Drive.
 * - Recibe archivos por «Abrir con» y «Compartir» desde otras apps.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/";
    private static final String DRIVE_PKG = "com.google.android.apps.docs";
    private static final int REQ_PICK = 1001;
    private static final int REQ_SAVE = 1002;
    private static final int REQ_PHOTO = 1003;
    private String pendingSave;
    private Uri cameraUri;

    private WebView web;
    private ValueCallback<Uri[]> pending;
    private volatile String source = "local";
    private final List<Uri> incoming = new ArrayList<>();
    private final List<Uri> picked = new ArrayList<>();
    private Aps aps;
    private int flushed = 0;
    private boolean pageReady = false;
    private long lastBack = 0;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFF11151B);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // vistas e incidencias guardadas
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);

        // Eventos del cliente de Autodesk (subida, conversión, listo, error) → página
        aps = new Aps(this, o -> runOnUiThread(() -> {
            String st = o.optString("stage");
            if ("upload".equals(st) || "translate".equals(st)) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (web != null) web.evaluateJavascript("window.plomadaAps&&window.plomadaAps(" + o + ")", null);
        }));
        web.addJavascriptInterface(new Bridge(), "PlomadaNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                return serve(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                if (HOST.equals(req.getUrl().getHost())) return false;
                // Los enlaces externos (p. ej. Autodesk Viewer) se abren en el navegador de la tablet, nunca dentro de la app
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl()));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "No hay navegador para abrir el enlace", Toast.LENGTH_LONG).show();
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                flushIncoming();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pending != null) pending.onReceiveValue(null);
                pending = cb;
                boolean image = false;
                String[] types = p.getAcceptTypes();
                if (types != null) for (String t : types) if (t != null && t.startsWith("image/")) image = true;
                if (image ? !launchPhoto() : !launchPicker("drive".equals(source))) {
                    pending = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl(BASE + "index.html");
        handleIntent(getIntent());
    }

    /* ───── Servidor de archivos interno ───── */
    private WebResourceResponse serve(Uri u) {
        String host = u.getHost();
        // Único tráfico de red permitido: el visor y los servicios de Autodesk, para los modelos NWD
        if (host != null && host.endsWith(".autodesk.com") && "https".equals(u.getScheme())) return null;
        if (!HOST.equals(host)) return notFound();
        String path = u.getPath();
        if (path == null || path.equals("/")) path = "/index.html";
        try {
            if (path.startsWith("/_incoming/")) {
                Uri src;
                synchronized (incoming) { src = incoming.get(Integer.parseInt(path.substring(11))); }
                return new WebResourceResponse("application/octet-stream", null, getContentResolver().openInputStream(src));
            }
            if (path.contains("..")) return notFound();
            InputStream in = getAssets().open("www" + path);
            String mime = mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text/") || mime.endsWith("javascript") ? "utf-8" : null, in);
        } catch (Exception e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    /* ───── Selector de archivos: almacenamiento local o Google Drive ───── */
    private boolean launchPicker(boolean drive) {
        Intent i;
        if (drive && hasDrive()) {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setPackage(DRIVE_PKG);
        } else {
            // Selector del sistema: memoria interna, SD, descargas y cuentas de Drive en el menú lateral
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            if (drive) Toast.makeText(this, "Elige «Drive» en el menú lateral del selector", Toast.LENGTH_LONG).show();
        }
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");   // los .ifc no tienen un tipo MIME estándar
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
            return true;
        } catch (ActivityNotFoundException e) {
            if (drive && i.getPackage() != null) return launchPicker(false);
            Toast.makeText(this, "No hay ninguna app para elegir archivos", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /* ───── Fotos de incidencias: cámara o galería ───── */
    private boolean launchPhoto() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        Intent chooser = Intent.createChooser(pick, "Foto de la incidencia");
        cameraUri = null;
        // La cámara escribe la foto en la galería (Pictures/Plomada). Desde Android 10 no hace falta ningún permiso;
        // en versiones anteriores la inserción falla y solo se ofrece la galería.
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "plomada-" + System.currentTimeMillis() + ".jpg");
            v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (android.os.Build.VERSION.SDK_INT >= 29) v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Plomada");
            cameraUri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (cameraUri != null) {
                Intent cam = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                cam.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri);
                cam.setClipData(ClipData.newRawUri("foto", cameraUri));
                cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Parcelable[]{cam});
            }
        } catch (Exception e) {
            cameraUri = null;
        }
        try {
            startActivityForResult(chooser, REQ_PHOTO);
            return true;
        } catch (ActivityNotFoundException e) {
            dropCameraUri();
            Toast.makeText(this, "No hay ninguna app para elegir fotos", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void dropCameraUri() {
        if (cameraUri != null) try { getContentResolver().delete(cameraUri, null, null); } catch (Exception e) { /* nada que limpiar */ }
        cameraUri = null;
    }

    private void onPhotoResult(int res, Intent data) {
        if (pending == null) { dropCameraUri(); return; }
        Uri[] out = null;
        if (res == RESULT_OK) {
            ClipData clip = data == null ? null : data.getClipData();
            if (data != null && data.getData() != null) out = new Uri[]{data.getData()};            // una foto de la galería
            else if (clip != null && clip.getItemCount() > 0) {                                      // varias fotos de la galería
                out = new Uri[clip.getItemCount()];
                for (int k = 0; k < out.length; k++) out[k] = clip.getItemAt(k).getUri();
            } else if (cameraUri != null) { out = new Uri[]{cameraUri}; cameraUri = null; }          // foto recién tomada
        }
        dropCameraUri();   // si no se usó la cámara, se borra la entrada vacía de la galería
        pending.onReceiveValue(out);
        pending = null;
    }

    private boolean hasDrive() {
        try {
            return getPackageManager().getApplicationInfo(DRIVE_PKG, 0).enabled;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_SAVE) {   // informe CSV: se escribe donde el usuario eligió (tablet o Drive)
            String text = pendingSave;
            pendingSave = null;
            if (res != RESULT_OK || data == null || data.getData() == null || text == null) return;
            try (java.io.OutputStream o = getContentResolver().openOutputStream(data.getData())) {
                o.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Toast.makeText(this, "Informe guardado", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo guardar el informe", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (req == REQ_PHOTO) { onPhotoResult(res, data); return; }
        if (req != REQ_PICK) { super.onActivityResult(req, res, data); return; }
        if (pending == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                out = new Uri[clip.getItemCount()];
                for (int k = 0; k < out.length; k++) out[k] = clip.getItemAt(k).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
        }
        if (out != null) synchronized (picked) { picked.clear(); for (Uri u : out) picked.add(u); }
        pending.onReceiveValue(out);
        pending = null;
    }

    /* ───── «Abrir con» y «Compartir» desde otras apps ───── */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent in) {
        if (in == null || in.getAction() == null) return;
        List<Uri> got = new ArrayList<>();
        String a = in.getAction();
        if (Intent.ACTION_VIEW.equals(a) && in.getData() != null) {
            got.add(in.getData());
        } else if (Intent.ACTION_SEND.equals(a)) {
            Parcelable p = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) got.add((Uri) p);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Parcelable> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) for (Parcelable p : l) if (p instanceof Uri) got.add((Uri) p);
        }
        if (got.isEmpty()) return;
        synchronized (incoming) { incoming.addAll(got); }
        if (pageReady) flushIncoming();
    }

    private void flushIncoming() {
        JSONArray arr = new JSONArray();
        synchronized (incoming) {
            for (; flushed < incoming.size(); flushed++) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("url", BASE + "_incoming/" + flushed);
                    o.put("name", displayName(incoming.get(flushed)));
                    arr.put(o);
                } catch (Exception ignored) { }
            }
        }
        if (arr.length() > 0)
            web.evaluateJavascript("window.plomadaIncoming&&window.plomadaIncoming(" + arr + ")", null);
    }

    private String displayName(Uri u) {
        String name = null;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) { }
        if (name == null) name = u.getLastPathSegment();
        return name == null ? "modelo" : name;
    }

    /* ───── Puente con la página ───── */
    private class Bridge {
        @JavascriptInterface
        public void setSource(String s) { source = s; }

        @JavascriptInterface
        public boolean hasDrive() { return MainActivity.this.hasDrive(); }

        /** Guarda un texto (informe CSV) con el diálogo «Guardar como» del sistema. */
        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String text) {
            runOnUiThread(() -> {
                pendingSave = text;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, REQ_SAVE);
                } catch (ActivityNotFoundException e) {
                    pendingSave = null;
                    Toast.makeText(MainActivity.this, "No hay ninguna app para guardar archivos", Toast.LENGTH_LONG).show();
                }
            });
        }

        /* Navisworks (NWD) a través de Autodesk Platform Services */
        @JavascriptInterface
        public boolean apsHasCredentials() { return aps.hasCredentials(); }

        @JavascriptInterface
        public void apsSetCredentials(String id, String secret) { aps.setCredentials(id, secret); }

        @JavascriptInterface
        public void apsCheck() { aps.check(); }

        @JavascriptInterface
        public String apsRecent() { return aps.recentJson(); }

        @JavascriptInterface
        public void apsForget(String urn) { aps.forget(urn); }

        @JavascriptInterface
        public String apsViewerToken() { return aps.viewerTokenJson(); }

        @JavascriptInterface
        public void apsOpenRecent(String urn, String name) { aps.resume(urn, name); }

        /** Sube y convierte el archivo que el usuario acaba de elegir (se localiza por su nombre). */
        @JavascriptInterface
        public boolean apsStart(String name) {
            List<Uri> all = new ArrayList<>();
            synchronized (picked) { all.addAll(picked); }
            synchronized (incoming) { all.addAll(incoming); }
            for (Uri u : all) {
                if (!name.equals(displayName(u))) continue;
                long size = -1;
                try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst() && !c.isNull(0)) size = c.getLong(0);
                } catch (Exception ignored) { }
                aps.start(u, name, size);
                return true;
            }
            return false;
        }
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) { super.onBackPressed(); return; }
        lastBack = now;
        Toast.makeText(this, "Pulsa atrás otra vez para salir", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
