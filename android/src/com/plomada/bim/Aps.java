package com.plomada.bim;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Cliente de Autodesk Platform Services (APS) para abrir archivos de Navisworks (NWD/NWC):
 * token de aplicación → bucket → subida por partes → conversión a SVF2 → URN para el visor.
 * Las credenciales las escribe el usuario y se guardan solo en el almacenamiento privado de la app.
 */
final class Aps {
    interface Listener { void event(JSONObject o); }

    private static final String API = "https://developer.api.autodesk.com";
    private static final String SCOPES = "data:read data:write data:create bucket:create bucket:read viewables:read";
    private static final int PART = 10 * 1024 * 1024;   // 10 MB por parte (mínimo de S3: 5 MB)

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Listener out;
    private String token, viewerToken;
    private long tokenExp, viewerTokenExp;
    private volatile boolean busy;

    Aps(Context c, Listener l) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("aps", Context.MODE_PRIVATE);
        out = l;
    }

    /* ───── Credenciales y tokens ───── */
    boolean hasCredentials() {
        return !prefs.getString("id", "").isEmpty() && !prefs.getString("secret", "").isEmpty();
    }

    synchronized void setCredentials(String id, String secret) {
        prefs.edit().putString("id", id.trim()).putString("secret", secret.trim()).apply();
        token = null;
        viewerToken = null;
    }

    private String[] fetchToken(String scopes) throws Exception {
        String basic = Base64.encodeToString((prefs.getString("id", "") + ":" + prefs.getString("secret", ""))
                .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String body = "grant_type=client_credentials&scope=" + URLEncoder.encode(scopes, "UTF-8");
        Resp r = http("POST", API + "/authentication/v2/token", "Basic " + basic,
                "application/x-www-form-urlencoded", body.getBytes(StandardCharsets.UTF_8));
        if (r.code == 400 || r.code == 401 || r.code == 403)
            throw new ApsError("Autodesk rechazó las credenciales (" + r.code + "). Revisa el Client ID y el Client Secret.", true);
        if (r.code != 200) throw new ApsError("No se pudo obtener el acceso (" + r.code + "): " + brief(r.body), false);
        JSONObject o = new JSONObject(r.body);
        return new String[]{o.getString("access_token"), String.valueOf(o.optInt("expires_in", 3000))};
    }

    private synchronized String token() throws Exception {
        if (token == null || System.currentTimeMillis() > tokenExp) {
            String[] t = fetchToken(SCOPES);
            token = t[0];
            tokenExp = System.currentTimeMillis() + (Long.parseLong(t[1]) - 120) * 1000;
        }
        return token;
    }

    /** Token de solo lectura para el visor de la página. Devuelve JSON {token, expires} o {error}. */
    synchronized String viewerTokenJson() {
        JSONObject o = new JSONObject();
        try {
            if (viewerToken == null || System.currentTimeMillis() > viewerTokenExp - 300000) {
                String[] t = fetchToken("viewables:read");
                viewerToken = t[0];
                viewerTokenExp = System.currentTimeMillis() + Long.parseLong(t[1]) * 1000;
            }
            o.put("token", viewerToken);
            o.put("expires", Math.max(60, (viewerTokenExp - System.currentTimeMillis()) / 1000));
        } catch (Exception e) {
            try { o.put("error", message(e)); } catch (Exception ignored) { }
        }
        return o.toString();
    }

    void check() {
        new Thread(() -> {
            try {
                synchronized (this) { token = null; }
                token();
                emit("cred", "ok", true);
            } catch (Exception e) {
                emit("cred", "ok", false, "msg", message(e));
            }
        }).start();
    }

    /* ───── Modelos ya subidos ───── */
    String recentJson() { return prefs.getString("recent", "[]"); }

    private String cachedUrn(String key) {
        try {
            JSONArray a = new JSONArray(recentJson());
            for (int i = 0; i < a.length(); i++)
                if (key.equals(a.getJSONObject(i).optString("key"))) return a.getJSONObject(i).getString("urn");
        } catch (Exception ignored) { }
        return null;
    }

    private void remember(String key, String name, long size, String urn) {
        try {
            JSONArray old = new JSONArray(recentJson()), a = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("key", key).put("name", name).put("size", size).put("urn", urn).put("when", System.currentTimeMillis());
            a.put(o);
            for (int i = 0; i < old.length() && a.length() < 12; i++)
                if (!key.equals(old.getJSONObject(i).optString("key"))) a.put(old.getJSONObject(i));
            prefs.edit().putString("recent", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    void forget(String urn) {
        try {
            JSONArray old = new JSONArray(recentJson()), a = new JSONArray();
            for (int i = 0; i < old.length(); i++)
                if (!urn.equals(old.getJSONObject(i).optString("urn"))) a.put(old.getJSONObject(i));
            prefs.edit().putString("recent", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    boolean isBusy() { return busy; }

    /* ───── Flujo completo: subir (si hace falta), convertir y avisar ───── */
    void start(final Uri uri, final String name, final long sizeHint) {
        if (busy) { emit("error", "msg", "Ya hay un modelo en proceso. Espera a que termine."); return; }
        busy = true;
        new Thread(() -> {
            File spool = null;
            try {
                long size = sizeHint;
                if (size <= 0) {   // el proveedor no dice el tamaño: se copia a caché para conocerlo
                    emit("upload", "pct", 0, "msg", "Preparando el archivo…");
                    spool = new File(ctx.getCacheDir(), "aps-upload.tmp");
                    try (InputStream in = ctx.getContentResolver().openInputStream(uri); OutputStream o = new FileOutputStream(spool)) {
                        byte[] b = new byte[1 << 16];
                        int n;
                        while ((n = in.read(b)) > 0) o.write(b, 0, n);
                    }
                    size = spool.length();
                }
                String key = size + "|" + name;
                String urn = cachedUrn(key);
                if (urn != null && manifestStatus(urn) != null) {   // ya está en la nube
                    waitTranslation(urn, name);
                    return;
                }
                emit("upload", "pct", 0, "msg", "Conectando con Autodesk…");
                String bucket = ensureBucket();
                String objectKey = enc(size + "-" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
                String objectId;
                try (InputStream in = spool != null ? new FileInputStream(spool) : ctx.getContentResolver().openInputStream(uri)) {
                    objectId = upload(bucket, objectKey, in, size);
                }
                urn = Base64.encodeToString(objectId.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                remember(key, name, size, urn);
                startJob(urn);
                waitTranslation(urn, name);
            } catch (Exception e) {
                emitError(e);
            } finally {
                if (spool != null) spool.delete();
                busy = false;
            }
        }).start();
    }

    /** Reabre un modelo que ya se subió antes (sin volver a subirlo). */
    void resume(final String urn, final String name) {
        if (busy) { emit("error", "msg", "Ya hay un modelo en proceso. Espera a que termine."); return; }
        busy = true;
        new Thread(() -> {
            try {
                if (manifestStatus(urn) == null) {
                    forget(urn);
                    throw new ApsError("Este modelo ya no está en la nube de Autodesk (los archivos se guardan 30 días). Vuelve a abrir el archivo NWD.", false);
                }
                waitTranslation(urn, name);
            } catch (Exception e) {
                emitError(e);
            } finally {
                busy = false;
            }
        }).start();
    }

    private String ensureBucket() throws Exception {
        String bucket = "plomada-" + prefs.getString("id", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (bucket.length() > 60) bucket = bucket.substring(0, 60);
        JSONObject b = new JSONObject().put("bucketKey", bucket).put("policyKey", "temporary");   // se borra a los 30 días
        Resp r = http("POST", API + "/oss/v2/buckets", "Bearer " + token(), "application/json", b.toString().getBytes(StandardCharsets.UTF_8));
        if (r.code != 200 && r.code != 409) throw new ApsError("No se pudo crear el espacio de almacenamiento (" + r.code + "): " + brief(r.body), false);
        return bucket;
    }

    private String upload(String bucket, String objectKey, InputStream in, long size) throws Exception {
        String base = API + "/oss/v2/buckets/" + bucket + "/objects/" + objectKey + "/signeds3upload";
        int parts = (int) Math.max(1, (size + PART - 1) / PART);
        String uploadKey = null;
        byte[] buf = new byte[PART];
        long sent = 0;
        JSONArray urls = null;
        int urlsFirst = 0;
        for (int p = 1; p <= parts; p++) {
            if (urls == null || p - urlsFirst >= urls.length()) {   // se piden de 20 en 20, válidas 60 min
                int n = Math.min(20, parts - p + 1);
                String q = base + "?parts=" + n + "&firstPart=" + p + "&minutesExpiration=60" + (uploadKey != null ? "&uploadKey=" + enc(uploadKey) : "");
                Resp r = http("GET", q, "Bearer " + token(), null, null);
                if (r.code != 200) throw new ApsError("Autodesk no autorizó la subida (" + r.code + "): " + brief(r.body), false);
                JSONObject o = new JSONObject(r.body);
                uploadKey = o.getString("uploadKey");
                urls = o.getJSONArray("urls");
                urlsFirst = p;
            }
            int len = 0;
            while (len < PART) {
                int n = in.read(buf, len, PART - len);
                if (n < 0) break;
                len += n;
            }
            if (len == 0 && p > 1) break;
            String url = urls.getString(p - urlsFirst);
            IOException last = null;
            boolean ok = false;
            for (int attempt = 0; attempt < 4 && !ok; attempt++) {
                try {
                    if (attempt > 0) Thread.sleep(2000L * attempt);
                    int code = put(url, buf, len);
                    if (code == 200) ok = true;
                    else last = new IOException("HTTP " + code);
                } catch (IOException e) {
                    last = e;
                }
            }
            if (!ok) throw new ApsError("Se interrumpió la subida en la parte " + p + " de " + parts + " (" + (last == null ? "" : last.getMessage()) + "). Revisa la conexión y vuelve a intentarlo.", false);
            sent += len;
            emit("upload", "pct", (int) (sent * 100 / Math.max(1, size)), "msg",
                    "Subiendo a Autodesk: " + (sent >> 20) + " de " + (size >> 20) + " MB");
        }
        JSONObject done = new JSONObject().put("uploadKey", uploadKey);
        Resp r = http("POST", base, "Bearer " + token(), "application/json", done.toString().getBytes(StandardCharsets.UTF_8));
        if (r.code != 200) throw new ApsError("No se pudo completar la subida (" + r.code + "): " + brief(r.body), false);
        return new JSONObject(r.body).getString("objectId");
    }

    private void startJob(String urn) throws Exception {
        JSONObject job = new JSONObject()
                .put("input", new JSONObject().put("urn", urn))
                .put("output", new JSONObject().put("formats", new JSONArray().put(
                        new JSONObject().put("type", "svf2").put("views", new JSONArray().put("3d")))));
        Resp r = http("POST", API + "/modelderivative/v2/designdata/job", "Bearer " + token(), "application/json", job.toString().getBytes(StandardCharsets.UTF_8));
        if (r.code != 200 && r.code != 201) throw new ApsError("Autodesk no aceptó la conversión (" + r.code + "): " + brief(r.body), false);
    }

    /** null si el modelo no existe en la nube; si no, el manifiesto. */
    private JSONObject manifestStatus(String urn) throws Exception {
        Resp r = http("GET", API + "/modelderivative/v2/designdata/" + urn + "/manifest", "Bearer " + token(), null, null);
        if (r.code == 404) return null;
        if (r.code != 200) throw new ApsError("No se pudo consultar la conversión (" + r.code + "): " + brief(r.body), false);
        return new JSONObject(r.body);
    }

    private void waitTranslation(String urn, String name) throws Exception {
        boolean restarted = false;
        for (int i = 0; ; i++) {
            JSONObject m = manifestStatus(urn);
            if (m == null) {   // subido pero sin conversión iniciada
                if (restarted) throw new ApsError("Autodesk no encuentra el archivo subido. Vuelve a abrir el NWD.", false);
                startJob(urn);
                restarted = true;
            } else {
                String st = m.optString("status"), pr = m.optString("progress");
                if ("success".equals(st)) {
                    emit("ready", "urn", urn, "name", name);
                    return;
                }
                if ("failed".equals(st) || "timeout".equals(st))
                    throw new ApsError("Autodesk no pudo convertir el modelo (" + st + "). " + brief(m.optJSONArray("derivatives") == null ? "" : m.optJSONArray("derivatives").toString()), false);
                emit("translate", "msg", "Autodesk está convirtiendo el modelo" + (pr.isEmpty() ? "…" : ": " + pr.replace("complete", "completado")),
                        "urn", urn, "name", name);
            }
            Thread.sleep(i < 12 ? 5000 : 15000);
        }
    }

    /* ───── HTTP ───── */
    private static final class Resp { int code; String body; }

    private static final class ApsError extends Exception {
        final boolean credentials;
        ApsError(String m, boolean c) { super(m); credentials = c; }
    }

    private static Resp http(String method, String url, String auth, String ctype, byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(30000);
            c.setReadTimeout(120000);
            if (auth != null) c.setRequestProperty("Authorization", auth);
            if (body != null) {
                c.setDoOutput(true);
                if (ctype != null) c.setRequestProperty("Content-Type", ctype);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream o = c.getOutputStream()) { o.write(body); }
            }
            Resp r = new Resp();
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? "" : readAll(in);
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static int put(String url, byte[] data, int len) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("PUT");
            c.setConnectTimeout(30000);
            c.setReadTimeout(300000);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(len);
            try (OutputStream o = c.getOutputStream()) { o.write(data, 0, len); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) readAll(in);
            return code;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = i.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    private static String brief(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 240 ? s.substring(0, 240) + "…" : s;
    }

    private static String message(Exception e) {
        if (e instanceof ApsError) return e.getMessage();
        if (e instanceof IOException) return "Sin conexión con Autodesk. Revisa el acceso a internet de la tablet. (" + e.getClass().getSimpleName() + ")";
        return "Error inesperado: " + e;
    }

    private void emitError(Exception e) {
        emit("error", "msg", message(e), "cred", e instanceof ApsError && ((ApsError) e).credentials);
    }

    private void emit(String stage, Object... kv) {
        try {
            JSONObject o = new JSONObject().put("stage", stage);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
            out.event(o);
        } catch (Exception ignored) { }
    }
}
