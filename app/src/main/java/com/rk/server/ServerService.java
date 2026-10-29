package com.rk.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Lightweight RK Chat backend. Designed for Android API 19 and low-memory tablets. */
public class ServerService extends Service {
    public static final String ACTION_START = "com.rk.server.START";
    public static final int PORT = 8080;
    public static volatile boolean isRunning = false;
    public static volatile String lastError = "";

    private static final int MAX_MESSAGES = 500;
    private static final int MAX_CALL_SIGNALS = 100;
    private static final int MAX_STATUSES = 100;
    private static final int MAX_HEADER_BYTES = 16384;
    private static final int MAX_BODY_BYTES = 280 * 1024;
    private static final int MAX_IMAGE_BYTES = 180 * 1024;
    // Binary media uploads are streamed to disk rather than accumulated in RAM.
    private static final int MAX_MEDIA_BYTES = 16 * 1024 * 1024;
    private static final long MAX_MEDIA_STORAGE_BYTES = 2048L * 1024L * 1024L;
    private static final int MAX_LIVE_SIGNALS = 1200;
    private static final int MAX_LIVE_COMMENTS = 1200;
    private static final int MAX_LIVE_SESSIONS = 100;
    private static final Object DATA_LOCK = new Object();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private volatile boolean stopRequested = false;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;
    private ThreadPoolExecutor workers;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private JSONArray messages = new JSONArray();
    private JSONArray users = new JSONArray();
    private JSONArray sessions = new JSONArray();
    private JSONArray callSignals = new JSONArray();
    private JSONArray statuses = new JSONArray();
    private JSONArray savedContacts = new JSONArray();
    private JSONArray liveSessions = new JSONArray();
    private JSONArray liveSignals = new JSONArray();
    private JSONArray liveComments = new JSONArray();
    private File messagesFile, usersFile, sessionsFile, callsFile, statusesFile, contactsFile, imagesDir, profilesDir, mediaDir;
    private File liveFile, liveSignalsFile, liveCommentsFile;
    private long nextSignalId = System.currentTimeMillis();
    private long nextLiveSignalId = System.currentTimeMillis();

    @Override public void onCreate() {
        super.onCreate();
        messagesFile = new File(getFilesDir(), "rk-messages.json");
        usersFile = new File(getFilesDir(), "rk-users.json");
        sessionsFile = new File(getFilesDir(), "rk-sessions.json");
        callsFile = new File(getFilesDir(), "rk-call-signals.json");
        statusesFile = new File(getFilesDir(), "rk-statuses.json");
        contactsFile = new File(getFilesDir(), "rk-contacts.json");
        imagesDir = new File(getFilesDir(), "rk-images");
        profilesDir = new File(getFilesDir(), "rk-profiles");
        mediaDir = new File(getFilesDir(), "rk-media");
        liveFile = new File(getFilesDir(), "rk-live-sessions.json");
        liveSignalsFile = new File(getFilesDir(), "rk-live-signals.json");
        liveCommentsFile = new File(getFilesDir(), "rk-live-comments.json");
        if (!imagesDir.exists()) imagesDir.mkdirs();
        if (!profilesDir.exists()) profilesDir.mkdirs();
        if (!mediaDir.exists()) mediaDir.mkdirs();
        loadAll();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(41, makeNotification());
        if (acceptThread == null || !acceptThread.isAlive()) startServer();
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        try {
            Intent again = new Intent(getApplicationContext(), ServerService.class);
            android.app.PendingIntent pi = android.os.Build.VERSION.SDK_INT >= 26
                ? android.app.PendingIntent.getForegroundService(getApplicationContext(), 7, again, android.app.PendingIntent.FLAG_ONE_SHOT | (android.os.Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0))
                : android.app.PendingIntent.getService(getApplicationContext(), 7, again, android.app.PendingIntent.FLAG_ONE_SHOT);
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 1000, pi);
        } catch (Exception ignored) { }
        super.onTaskRemoved(rootIntent);
    }

    private Notification makeNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel("rk_server_status", "Status RK Server", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Menunjukkan server lokal sedang berjalan");
                manager.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, "rk_server_status");
        } else builder = new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.rk_icon).setContentTitle("RKM Server berjalan")
                .setContentText("RKM Chat, media, status dan panggilan · port " + PORT)
                .setOngoing(true).setContentIntent(pending).setWhen(System.currentTimeMillis());
        return builder.build();
    }

    private void startServer() {
        stopRequested = false; lastError = "";
        workers = new ThreadPoolExecutor(1, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(16), new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) { wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RKServer:Cpu"); wakeLock.acquire(); }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) { wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "RKServer:Wifi"); wifiLock.acquire(); }
        } catch (Exception ignored) { }
        acceptThread = new Thread(new Runnable() { @Override public void run() {
            try {
                ServerSocket socket = new ServerSocket(); socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("0.0.0.0", PORT), 24); serverSocket = socket; isRunning = true;
                while (!stopRequested) {
                    try {
                        final Socket client = socket.accept(); client.setSoTimeout(8000);
                        try { workers.execute(new Runnable() { @Override public void run() { handleClient(client); }}); }
                        catch (Exception rejected) { closeQuietly(client); }
                    } catch (SocketException closed) { if (!stopRequested) lastError = "Koneksi server terputus"; break; }
                }
            } catch (Exception e) { if (!stopRequested) lastError = cleanError(e); }
            finally {
                isRunning = false; closeServerSocket(); releaseLocks(); ThreadPoolExecutor pool = workers;
                if (pool != null) pool.shutdownNow(); if (!stopRequested && !TextUtils.isEmpty(lastError)) stopSelf();
            }
        }}, "RKServer-Accept");
        acceptThread.setDaemon(true); acceptThread.start();
    }

    private String cleanError(Exception e) {
        String message = e.getMessage();
        if (e instanceof java.net.BindException) return "Port " + PORT + " sedang dipakai aplikasi lain";
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    private void handleClient(Socket client) {
        try {
            InputStream input = client.getInputStream(); OutputStream output = client.getOutputStream();
            byte[] headerBytes = readHeaders(input); if (headerBytes == null) return;
            String rawHeaders = new String(headerBytes, LATIN1); String[] lines = rawHeaders.split("\r\n");
            if (lines.length == 0) { sendJson(output, 400, "Bad Request", errorJson("Request tidak valid")); return; }
            String[] request = lines[0].split(" ");
            if (request.length < 2) { sendJson(output, 400, "Bad Request", errorJson("Request tidak valid")); return; }
            String method = request[0].toUpperCase(Locale.US); String rawPath = request[1];
            String query = "", path = rawPath; int qi = rawPath.indexOf('?');
            if (qi >= 0) { query = rawPath.substring(qi + 1); path = rawPath.substring(0, qi); }
            int contentLength = 0; String contentType = "application/json"; String headerToken = ""; String rangeHeader = "";
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    String headerName = lines[i].substring(0, colon).trim().toLowerCase(Locale.US);
                    String headerValue = lines[i].substring(colon + 1).trim();
                    if ("content-length".equals(headerName)) {
                        try { contentLength = Integer.parseInt(headerValue); } catch (NumberFormatException ignored) { contentLength = -1; }
                    } else if ("content-type".equals(headerName)) contentType = headerValue;
                    else if ("x-rkm-token".equals(headerName)) headerToken = headerValue;
                    else if ("range".equals(headerName)) rangeHeader = headerValue;
                }
            }
            if ("OPTIONS".equals(method)) { sendResponse(output, 204, "No Content", "text/plain; charset=utf-8", new byte[0]); return; }
            if (contentLength < 0) { sendJson(output, 400, "Bad Request", errorJson("Content-Length tidak valid")); return; }
            if ("POST".equals(method) && "/api/media/upload".equals(path)) {
                uploadMediaStream(output, input, contentLength, query, contentType, headerToken);
                return;
            }
            if (contentLength > MAX_BODY_BYTES) { sendJson(output, 413, "Payload Too Large", errorJson("Data JSON terlalu besar. Unggah video/dokumen melalui endpoint media.")); return; }
            if ("GET".equals(method) && "/".equals(path)) {
                sendResponse(output, 200, "OK", "text/html; charset=utf-8", readAsset("index.html"));
            } else if ("GET".equals(method) && "/health".equals(path)) {
                JSONObject r = new JSONObject(); r.put("ok", true); r.put("service", "RKM Server"); r.put("version", "1.3.0");
                r.put("users", users.length()); r.put("messages", getMessageCount()); sendJson(output, 200, "OK", r.toString());
            } else if ("GET".equals(method) && "/api/me".equals(path)) {
                JSONObject u = userForToken(queryParam(query, "token"));
                if (u == null) { sendJson(output, 401, "Unauthorized", errorJson("Sesi habis. Silakan masuk lagi.")); return; }
                sendJson(output, 200, "OK", userPublic(u).toString());
            } else if ("GET".equals(method) && "/api/contacts".equals(path)) {
                getContacts(output, query);
            } else if ("GET".equals(method) && "/api/users".equals(path)) {
                JSONObject me = userForToken(queryParam(query, "token"));
                if (me == null) { sendJson(output, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
                JSONObject result = new JSONObject(); result.put("ok", true); JSONArray list = new JSONArray();
                synchronized (DATA_LOCK) { for (int i=0;i<users.length();i++) { JSONObject u=users.optJSONObject(i); if (u!=null && !u.optString("phone").equals(me.optString("phone"))) list.put(userPublic(u)); } }
                result.put("users", list); sendJson(output, 200, "OK", result.toString());
            } else if ("GET".equals(method) && "/api/messages".equals(path)) {
                getMessages(output, query);
            } else if ("GET".equals(method) && "/api/calls".equals(path)) {
                getCallSignals(output, query);
            } else if ("GET".equals(method) && "/api/status".equals(path)) {
                getStatuses(output, query);
            } else if ("GET".equals(method) && "/api/image".equals(path)) {
                getImage(output, query);
            } else if ("GET".equals(method) && "/api/voice".equals(path)) {
                getVoice(output, query);
            } else if ("GET".equals(method) && "/api/avatar".equals(path)) {
                getAvatar(output, query);
            } else if ("GET".equals(method) && "/api/media".equals(path)) {
                getMedia(output, query, rangeHeader);
            } else if ("GET".equals(method) && "/api/live".equals(path)) {
                getLiveSessions(output, query);
            } else if ("GET".equals(method) && "/api/live/signals".equals(path)) {
                getLiveSignals(output, query);
            } else if ("GET".equals(method) && "/api/live/comments".equals(path)) {
                getLiveComments(output, query);
            } else if ("POST".equals(method) && (path.startsWith("/api/"))) {
                byte[] body = readBody(input, contentLength);
                if (body == null) { sendJson(output, 400, "Bad Request", errorJson("Isi permintaan tidak lengkap")); return; }
                handlePost(output, path, new String(body, UTF8));
            } else sendJson(output, 404, "Not Found", errorJson("Alamat tidak ditemukan"));
        } catch (Exception e) {
            try { sendJson(client.getOutputStream(), 500, "Internal Server Error", errorJson("Kesalahan server. Coba lagi.")); } catch (Exception ignored) { }
        } finally { closeQuietly(client); }
    }

    private void handlePost(OutputStream output, String path, String body) throws IOException {
        try {
            JSONObject data = new JSONObject(body);
            if ("/api/register".equals(path)) { register(output, data); return; }
            if ("/api/login".equals(path)) { login(output, data); return; }
            if ("/api/profile".equals(path)) { updateProfile(output, data); return; }
            if ("/api/contacts".equals(path)) { updateContact(output, data); return; }
            if ("/api/profile-photo".equals(path)) { updateProfilePhoto(output, data); return; }
            if ("/api/messages".equals(path)) { postMessage(output, data); return; }
            if ("/api/messages/edit".equals(path)) { editMessage(output, data); return; }
            if ("/api/messages/delete".equals(path)) { deleteMessage(output, data); return; }
            if ("/api/images".equals(path)) { postImage(output, data); return; }
            if ("/api/voice".equals(path)) { postVoice(output, data); return; }
            if ("/api/calls".equals(path)) { postCallSignal(output, data); return; }
            if ("/api/status".equals(path)) { postStatus(output, data); return; }
            if ("/api/live/start".equals(path)) { startLive(output, data); return; }
            if ("/api/live/stop".equals(path)) { stopLive(output, data); return; }
            if ("/api/live/join".equals(path)) { joinLive(output, data); return; }
            if ("/api/live/leave".equals(path)) { leaveLive(output, data); return; }
            if ("/api/live/heartbeat".equals(path)) { heartbeatLive(output, data); return; }
            if ("/api/live/signal".equals(path)) { postLiveSignal(output, data); return; }
            if ("/api/live/comment".equals(path)) { postLiveComment(output, data); return; }
            sendJson(output, 404, "Not Found", errorJson("API tidak ditemukan"));
        } catch (JSONException e) { sendJson(output, 400, "Bad Request", errorJson("Format data tidak valid")); }
    }

    private void register(OutputStream out, JSONObject d) throws IOException, JSONException {
        String phone = normalizePhone(d.optString("phone", "")); String name = cleanName(d.optString("name", "")); String password = d.optString("password", "");
        if (!validPhone(phone)) { sendJson(out, 400, "Bad Request", errorJson("Nomor HP harus 8–15 angka")); return; }
        if (name.length() < 1) { sendJson(out, 400, "Bad Request", errorJson("Nama wajib diisi")); return; }
        if (password.length() < 6 || password.length() > 100) { sendJson(out, 400, "Bad Request", errorJson("Kata sandi minimal 6 karakter")); return; }
        synchronized (DATA_LOCK) {
            if (findUser(phone) != null) { sendJson(out, 409, "Conflict", errorJson("Nomor ini sudah terdaftar. Silakan masuk.")); return; }
            String salt = UUID.randomUUID().toString(); JSONObject u = new JSONObject();
            u.put("phone", phone); u.put("name", name); u.put("about", "Hai, aku menggunakan RKM Chat"); u.put("salt", salt); u.put("passwordHash", hashPassword(salt, password));
            users.put(u); saveArray(usersFile, users);
            String token = createSession(phone); JSONObject result = userPublic(u); result.put("ok", true); result.put("token", token);
            sendJson(out, 201, "Created", result.toString());
        }
    }

    private void login(OutputStream out, JSONObject d) throws IOException, JSONException {
        String phone = normalizePhone(d.optString("phone", "")); String password = d.optString("password", "");
        synchronized (DATA_LOCK) {
            JSONObject u = findUser(phone);
            if (u == null) { sendJson(out, 401, "Unauthorized", errorJson("Nomor HP atau kata sandi salah")); return; }
            String salt=u.optString("salt", ""), stored=u.optString("passwordHash", "");
            boolean valid;
            if (stored.startsWith("pbkdf2$")) valid=constantTimeEquals(stored, hashPassword(salt,password));
            else valid=constantTimeEquals(stored, legacyHashPassword(salt,password));
            if (!valid) { sendJson(out, 401, "Unauthorized", errorJson("Nomor HP atau kata sandi salah")); return; }
            if (!stored.startsWith("pbkdf2$")) { u.put("passwordHash",hashPassword(salt,password)); saveArray(usersFile,users); }
            String token = createSession(phone); JSONObject result = userPublic(u); result.put("ok", true); result.put("token", token);
            sendJson(out, 200, "OK", result.toString());
        }
    }

    private void updateProfile(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject sessionUser = userForToken(d.optString("token", ""));
        if (sessionUser == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String phone = sessionUser.optString("phone"); String name = cleanName(d.optString("name", sessionUser.optString("name")));
        String about = d.optString("about", sessionUser.optString("about", ""));
        if (name.length()==0 || name.length()>40 || about.length()>120) { sendJson(out, 400, "Bad Request", errorJson("Nama harus 1–40 karakter dan info maksimal 120 karakter")); return; }
        synchronized (DATA_LOCK) { JSONObject u=findUser(phone); if(u==null){sendJson(out,404,"Not Found",errorJson("Akun tidak ditemukan"));return;} u.put("name",name);u.put("about",about);saveArray(usersFile,users);JSONObject r=userPublic(u);r.put("ok",true);sendJson(out,200,"OK",r.toString()); }
    }

    private void getContacts(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me = userForToken(queryParam(query, "token"));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String owner = me.optString("phone"); JSONArray list = new JSONArray();
        synchronized (DATA_LOCK) {
            for (int i = 0; i < savedContacts.length(); i++) {
                JSONObject c = savedContacts.optJSONObject(i); if (c == null || !samePhone(owner, c.optString("owner"))) continue;
                String phone = c.optString("phone"); JSONObject u = findUser(phone); if (u == null) continue;
                JSONObject item = new JSONObject(); item.put("phone", phone); item.put("alias", c.optString("alias", ""));
                item.put("name", u.optString("name", phone)); item.put("about", u.optString("about", "")); list.put(item);
            }
        }
        JSONObject r = new JSONObject(); r.put("ok", true); r.put("contacts", list); sendJson(out, 200, "OK", r.toString());
    }

    private void updateContact(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String owner = me.optString("phone"), phone = normalizePhone(d.optString("phone", ""));
        String action = d.optString("action", "add"), alias = cleanName(d.optString("alias", ""));
        if (!validPhone(phone) || samePhone(owner, phone)) { sendJson(out, 400, "Bad Request", errorJson("Nomor kontak tidak valid")); return; }
        synchronized (DATA_LOCK) {
            JSONArray next = new JSONArray(); boolean found = false;
            for (int i = 0; i < savedContacts.length(); i++) {
                JSONObject c = savedContacts.optJSONObject(i); if (c == null) continue;
                boolean match = samePhone(owner, c.optString("owner")) && samePhone(phone, c.optString("phone"));
                if (match) { found = true; if ("delete".equals(action)) continue; }
                if (!match) next.put(c);
            }
            if ("delete".equals(action)) {
                savedContacts = next; saveArray(contactsFile, savedContacts);
                JSONObject r = new JSONObject(); r.put("ok", true); r.put("deleted", found); sendJson(out, 200, "OK", r.toString()); return;
            }
            JSONObject u = findUser(phone);
            if (u == null) { sendJson(out, 404, "Not Found", errorJson("Nomor itu belum terdaftar di RKM Chat")); return; }
            JSONObject c = new JSONObject(); c.put("owner", owner); c.put("phone", phone); c.put("alias", alias); c.put("time", System.currentTimeMillis());
            next.put(c); savedContacts = next; saveArray(contactsFile, savedContacts);
            JSONObject r = new JSONObject(); r.put("ok", true); r.put("contact", c); sendJson(out, 200, "OK", r.toString());
        }
    }

    private boolean hasSavedContact(String owner, String phone) {
        for (int i = 0; i < savedContacts.length(); i++) {
            JSONObject c = savedContacts.optJSONObject(i);
            if (c != null && samePhone(owner, c.optString("owner")) && samePhone(phone, c.optString("phone"))) return true;
        }
        return false;
    }

    private void postMessage(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String sender = me.optString("phone"); String recipient = normalizePhone(d.optString("recipient", "")); String text = d.optString("text", "").trim();
        if (!validPhone(recipient) || samePhone(sender, recipient) || findUser(recipient)==null) { sendJson(out, 400, "Bad Request", errorJson("Pilih nomor teman yang sudah terdaftar")); return; }
        if (text.length()==0 || text.length()>2000) { sendJson(out, 400, "Bad Request", errorJson("Pesan harus 1–2000 karakter")); return; }
        JSONObject msg=newMessage(sender, recipient, text, "text", "");
        synchronized(DATA_LOCK) { appendLimited(messages, msg, MAX_MESSAGES); saveArray(messagesFile,messages); }
        JSONObject r=new JSONObject();r.put("ok",true);r.put("message",msg);sendJson(out,200,"OK",r.toString());
    }

    private void postImage(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String sender=me.optString("phone"), recipient=normalizePhone(d.optString("recipient", "")), encoded=d.optString("data", "");
        if (!validPhone(recipient) || samePhone(sender,recipient) || findUser(recipient)==null) { sendJson(out,400,"Bad Request",errorJson("Pilih teman yang sudah terdaftar")); return; }
        if (encoded.length()==0 || encoded.length()>((MAX_IMAGE_BYTES*4/3)+1000)) { sendJson(out,413,"Payload Too Large",errorJson("Foto terlalu besar")); return; }
        byte[] image;
        try { image=Base64.decode(encoded,Base64.DEFAULT); } catch(Exception e){sendJson(out,400,"Bad Request",errorJson("Data gambar tidak valid"));return;}
        if(image.length==0||image.length>MAX_IMAGE_BYTES){sendJson(out,413,"Payload Too Large",errorJson("Foto maksimal sekitar 180 KB setelah dikompres"));return;}
        String id=UUID.randomUUID().toString().replace("-","");File file=new File(imagesDir,id+".jpg");
        FileOutputStream fos=null;try{fos=new FileOutputStream(file);fos.write(image);fos.flush();fos.getFD().sync();}catch(Exception e){if(file.exists())file.delete();sendJson(out,500,"Internal Server Error",errorJson("Gagal menyimpan foto"));return;}finally{if(fos!=null)try{fos.close();}catch(Exception ignored){}}
        JSONObject msg=newMessage(sender,recipient,"","image",id);
        synchronized(DATA_LOCK){appendLimited(messages,msg,MAX_MESSAGES);saveArray(messagesFile,messages);}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("message",msg);sendJson(out,200,"OK",r.toString());
    }

    private void postVoice(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token", "")); if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String sender=me.optString("phone"), recipient=normalizePhone(d.optString("recipient", "")), encoded=d.optString("data", "");
        String mime=d.optString("mimeType", "audio/webm");
        if(!mime.equals("audio/webm")&&!mime.equals("audio/ogg")&&!mime.equals("audio/mp4")&&!mime.equals("audio/aac")){mime="audio/webm";}
        if(!validPhone(recipient)||samePhone(sender,recipient)||findUser(recipient)==null){sendJson(out,400,"Bad Request",errorJson("Pilih teman yang sudah terdaftar"));return;}
        if(encoded.length()==0||encoded.length()>((MAX_IMAGE_BYTES*4/3)+1000)){sendJson(out,413,"Payload Too Large",errorJson("Pesan suara terlalu besar"));return;}
        byte[] audio;try{audio=Base64.decode(encoded,Base64.DEFAULT);}catch(Exception e){sendJson(out,400,"Bad Request",errorJson("Data pesan suara tidak valid"));return;}
        if(audio.length==0||audio.length>MAX_IMAGE_BYTES){sendJson(out,413,"Payload Too Large",errorJson("Pesan suara maksimal sekitar 180 KB; rekam lebih singkat"));return;}
        String id=UUID.randomUUID().toString().replace("-","");File file=new File(imagesDir,id+".voice");FileOutputStream fos=null;
        try{fos=new FileOutputStream(file);fos.write(audio);fos.flush();fos.getFD().sync();}catch(Exception e){if(file.exists())file.delete();sendJson(out,500,"Internal Server Error",errorJson("Gagal menyimpan pesan suara"));return;}finally{if(fos!=null)try{fos.close();}catch(Exception ignored){}}
        JSONObject msg=newMessage(sender,recipient,"","voice","");msg.put("voiceId",id);msg.put("mimeType",mime);
        synchronized(DATA_LOCK){appendLimited(messages,msg,MAX_MESSAGES);saveArray(messagesFile,messages);}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("message",msg);sendJson(out,200,"OK",r.toString());
    }

    private void getVoice(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String id=queryParam(query,"id");if(id.length()!=32||!id.matches("[a-fA-F0-9]+")){sendJson(out,400,"Bad Request",errorJson("ID pesan suara tidak valid"));return;}
        boolean authorized=false;String mime="audio/webm";
        synchronized(DATA_LOCK){for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m!=null&&id.equals(m.optString("voiceId"))&&(samePhone(me.optString("phone"),m.optString("sender"))||samePhone(me.optString("phone"),m.optString("recipient")))){authorized=true;mime=m.optString("mimeType","audio/webm");break;}}}
        if(!authorized){sendJson(out,404,"Not Found",errorJson("Pesan suara tidak ditemukan"));return;}
        File f=new File(imagesDir,id+".voice");if(!f.exists()||f.length()>MAX_IMAGE_BYTES){sendJson(out,404,"Not Found",errorJson("Pesan suara tidak ditemukan"));return;}
        ByteArrayOutputStream buf=new ByteArrayOutputStream();FileInputStream in=null;try{in=new FileInputStream(f);byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)buf.write(b,0,n);}finally{if(in!=null)try{in.close();}catch(Exception ignored){}}
        if(!mime.equals("audio/webm")&&!mime.equals("audio/ogg")&&!mime.equals("audio/mp4")&&!mime.equals("audio/aac"))mime="audio/webm";
        sendResponseCached(out,200,"OK",mime,buf.toByteArray(),86400);
    }

    private void editMessage(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String id = d.optString("id", ""), text = d.optString("text", "").trim();
        if (id.length() == 0 || text.length() == 0 || text.length() > 2000) { sendJson(out, 400, "Bad Request", errorJson("Pesan edit tidak valid")); return; }
        synchronized (DATA_LOCK) {
            for (int i=0; i<messages.length(); i++) {
                JSONObject m=messages.optJSONObject(i);
                if (m!=null && id.equals(m.optString("id"))) {
                    if (!samePhone(me.optString("phone"), m.optString("sender"))) { sendJson(out,403,"Forbidden",errorJson("Hanya pengirim yang dapat mengedit pesan ini")); return; }
                    if (!"text".equals(m.optString("type"))) { sendJson(out,400,"Bad Request",errorJson("Hanya pesan teks yang dapat diedit")); return; }
                    m.put("text",text); m.put("edited",true); m.put("editedAt",timeNow()); saveArray(messagesFile,messages);
                    JSONObject r=new JSONObject();r.put("ok",true);r.put("message",m);sendJson(out,200,"OK",r.toString());return;
                }
            }
        }
        sendJson(out,404,"Not Found",errorJson("Pesan tidak ditemukan"));
    }

    private void deleteMessage(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String id=d.optString("id", ""); if(id.length()==0){sendJson(out,400,"Bad Request",errorJson("ID pesan kosong"));return;}
        synchronized(DATA_LOCK) {
            JSONArray next=new JSONArray(); boolean found=false;
            for(int i=0;i<messages.length();i++) {
                JSONObject m=messages.optJSONObject(i);
                if(m!=null && id.equals(m.optString("id"))) {
                    found=true;
                    if(!samePhone(me.optString("phone"),m.optString("sender"))) { sendJson(out,403,"Forbidden",errorJson("Hanya pengirim yang dapat menghapus pesan untuk semua")); return; }
                    continue;
                }
                if(m!=null) next.put(m);
            }
            if(!found){sendJson(out,404,"Not Found",errorJson("Pesan tidak ditemukan"));return;}
            messages=next;saveArray(messagesFile,messages);
        }
        JSONObject r=new JSONObject();r.put("ok",true);sendJson(out,200,"OK",r.toString());
    }

    private void updateProfilePhoto(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token", ""));
        if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String encoded=d.optString("data", ""); if(encoded.length()==0 || encoded.length()>((MAX_IMAGE_BYTES*4/3)+1000)){sendJson(out,413,"Payload Too Large",errorJson("Foto profil terlalu besar"));return;}
        byte[] image;try{image=Base64.decode(encoded,Base64.DEFAULT);}catch(Exception e){sendJson(out,400,"Bad Request",errorJson("Foto profil tidak valid"));return;}
        if(image.length==0||image.length>MAX_IMAGE_BYTES){sendJson(out,413,"Payload Too Large",errorJson("Foto profil maksimal 180 KB"));return;}
        File file=new File(profilesDir,me.optString("phone")+".jpg");FileOutputStream fos=null;
        try{fos=new FileOutputStream(file);fos.write(image);fos.flush();fos.getFD().sync();}catch(Exception e){sendJson(out,500,"Internal Server Error",errorJson("Gagal menyimpan foto profil"));return;}finally{if(fos!=null)try{fos.close();}catch(Exception ignored){}}
        JSONObject r=new JSONObject();r.put("ok",true);sendJson(out,200,"OK",r.toString());
    }

    private void getAvatar(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String phone=normalizePhone(queryParam(query,"phone"));if(!validPhone(phone)||findUser(phone)==null){sendJson(out,404,"Not Found",errorJson("Profil tidak ditemukan"));return;}
        File f=new File(profilesDir,phone+".jpg");if(!f.exists()||f.length()>MAX_IMAGE_BYTES){sendJson(out,404,"Not Found",errorJson("Foto profil belum dipasang"));return;}
        ByteArrayOutputStream buf=new ByteArrayOutputStream();FileInputStream in=null;
        try{in=new FileInputStream(f);byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)buf.write(b,0,n);}finally{if(in!=null)try{in.close();}catch(Exception ignored){}}
        sendResponseCached(out,200,"OK","image/jpeg",buf.toByteArray(),60);
    }

    private JSONObject newMessage(String sender, String recipient, String text, String type, String imageId) throws JSONException {
        JSONObject s=findUser(sender), r=findUser(recipient); JSONObject msg=new JSONObject();
        msg.put("id",UUID.randomUUID().toString());msg.put("sender",sender);msg.put("recipient",recipient);
        msg.put("senderName",s==null?sender:s.optString("name",sender));msg.put("recipientName",r==null?recipient:r.optString("name",recipient));
        msg.put("text",text);msg.put("type",type);msg.put("imageId",imageId);msg.put("time",timeNow());return msg;
    }

    private void getMessages(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token")); if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String phone=me.optString("phone"), peer=normalizePhone(queryParam(query,"peer")); JSONArray copy=new JSONArray();
        synchronized(DATA_LOCK){for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m==null)continue;String s=m.optString("sender"),r=m.optString("recipient");boolean allowed=(samePhone(s,phone)||samePhone(r,phone));if(!allowed)continue;if(peer.length()>0 && !((samePhone(s,phone)&&samePhone(r,peer))||(samePhone(s,peer)&&samePhone(r,phone))))continue;copy.put(m);}}
        JSONObject result=new JSONObject();result.put("ok",true);result.put("messages",copy);result.put("count",copy.length());sendJson(out,200,"OK",result.toString());
    }

    private void postCallSignal(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token", "")); if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String from=me.optString("phone"), to=normalizePhone(d.optString("to", "")), type=d.optString("type", ""), callId=d.optString("callId", "");
        if(!validPhone(to)||samePhone(from,to)||findUser(to)==null){sendJson(out,400,"Bad Request",errorJson("Nomor teman tidak valid"));return;}
        if(!(type.equals("offer")||type.equals("answer")||type.equals("ice")||type.equals("end")||type.equals("reject")||type.equals("busy"))){sendJson(out,400,"Bad Request",errorJson("Jenis sinyal tidak valid"));return;}
        if(callId.length()==0||callId.length()>80){sendJson(out,400,"Bad Request",errorJson("ID panggilan tidak valid"));return;}
        JSONObject payload=d.optJSONObject("payload"); if(payload==null)payload=new JSONObject();
        JSONObject sig=new JSONObject(); sig.put("id",nextSignalId());sig.put("from",from);sig.put("fromName",me.optString("name",from));sig.put("to",to);sig.put("type",type);sig.put("callId",callId);sig.put("payload",payload);sig.put("time",System.currentTimeMillis());
        synchronized(DATA_LOCK){appendLimited(callSignals,sig,MAX_CALL_SIGNALS);saveArray(callsFile,callSignals);}
        JSONObject r=new JSONObject();r.put("ok",true);sendJson(out,200,"OK",r.toString());
    }

    private void getCallSignals(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String phone=me.optString("phone");long since=0;try{since=Long.parseLong(queryParam(query,"since"));}catch(Exception ignored){}
        JSONArray resultList=new JSONArray();long now=System.currentTimeMillis();
        synchronized(DATA_LOCK){for(int i=0;i<callSignals.length();i++){JSONObject s=callSignals.optJSONObject(i);if(s==null)continue;long id=s.optLong("id",0);if(samePhone(s.optString("to"),phone)&&id>since&&now-s.optLong("time",now)<120000)resultList.put(s);}}
        JSONObject result=new JSONObject();result.put("ok",true);result.put("signals",resultList);sendJson(out,200,"OK",result.toString());
    }

    // Streaming binary endpoint: POST /api/media/upload?recipient=...&purpose=message|status
    // Binary bytes are written incrementally to disk to keep tablet memory use bounded.
    private void uploadMediaStream(OutputStream out, InputStream input, int length, String query, String contentType, String headerToken) throws IOException, JSONException {
        String token = !TextUtils.isEmpty(headerToken) ? headerToken : queryParam(query, "token");
        JSONObject me = userForToken(token);
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        if (length <= 0 || length > MAX_MEDIA_BYTES) { sendJson(out, 413, "Payload Too Large", errorJson("Ukuran media harus 1 byte sampai 16 MB per file")); return; }
        String mime = contentType == null ? "application/octet-stream" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.US);
        if (mime.length() == 0 || mime.length() > 120 || mime.contains("\\") || mime.contains("\r") || mime.contains("\n")) mime = "application/octet-stream";
        String purpose = queryParam(query, "purpose"); if (purpose.length() == 0) purpose = "message";
        if (!"message".equals(purpose) && !"status".equals(purpose)) { sendJson(out, 400, "Bad Request", errorJson("Tujuan media tidak valid")); return; }
        String statusText = queryParam(query, "text"); if ("status".equals(purpose) && statusText.length() > 240) { sendJson(out, 400, "Bad Request", errorJson("Teks status maksimal 240 karakter")); return; }
        boolean isImage = mime.startsWith("image/"); boolean isVideo = mime.startsWith("video/");
        if ("status".equals(purpose) && !isImage && !isVideo) { sendJson(out, 415, "Unsupported Media Type", errorJson("Status hanya mendukung foto atau video")); return; }
        String sender = me.optString("phone"), recipient = normalizePhone(queryParam(query, "recipient"));
        if ("message".equals(purpose) && (!validPhone(recipient) || samePhone(sender, recipient) || findUser(recipient) == null)) { sendJson(out, 400, "Bad Request", errorJson("Pilih teman yang sudah terdaftar")); return; }
        if (mediaBytesUsed() + length > MAX_MEDIA_STORAGE_BYTES) { sendJson(out, 507, "Insufficient Storage", errorJson("Penyimpanan media server hampir penuh (batas 2 GB). Hapus media lama atau kosongkan ruang tablet.")); return; }
        String id = UUID.randomUUID().toString().replace("-", ""); File file = new File(mediaDir, id + ".bin");
        FileOutputStream fos = null; int remaining = length;
        try {
            fos = new FileOutputStream(file);
            byte[] buf = new byte[8192];
            while (remaining > 0) {
                int n = input.read(buf, 0, Math.min(buf.length, remaining));
                if (n < 0) throw new IOException("Unggahan media terputus sebelum selesai");
                if (n == 0) continue;
                fos.write(buf, 0, n); remaining -= n;
            }
            fos.flush(); fos.getFD().sync();
        } catch (Exception e) {
            if (file.exists()) file.delete();
            sendJson(out, 400, "Bad Request", errorJson("Unggahan tidak lengkap atau gagal disimpan")); return;
        } finally { if (fos != null) try { fos.close(); } catch (Exception ignored) { } }
        String filename = queryParam(query, "filename");
        filename = cleanFileName(filename);
        if (filename.length() == 0) filename = "media" + safeExtension(mime);
        long now = System.currentTimeMillis();
        JSONObject result = new JSONObject();
        if ("status".equals(purpose)) {
            JSONObject st = new JSONObject();
            st.put("id", UUID.randomUUID().toString()); st.put("phone", sender); st.put("name", me.optString("name"));
            st.put("text", statusText); st.put("time", now); st.put("mediaId", id); st.put("mediaType", isVideo ? "video" : "image");
            st.put("mimeType", mime); st.put("fileName", filename); st.put("fileSize", length);
            synchronized (DATA_LOCK) { appendLimited(statuses, st, MAX_STATUSES); saveArray(statusesFile, statuses); }
            result.put("status", st);
        } else {
            String type = isVideo ? "video" : (isImage ? "image" : "file");
            JSONObject msg = newMessage(sender, recipient, "", type, "");
            msg.put("mediaId", id); msg.put("mimeType", mime); msg.put("fileName", filename); msg.put("fileSize", length);
            synchronized (DATA_LOCK) { appendLimited(messages, msg, MAX_MESSAGES); saveArray(messagesFile, messages); }
            result.put("message", msg);
        }
        result.put("ok", true); result.put("mediaId", id); result.put("mimeType", mime); result.put("fileName", filename); result.put("fileSize", length);
        sendJson(out, 201, "Created", result.toString());
    }

    private long mediaBytesUsed() {
        long total = 0; File[] files = mediaDir == null ? null : mediaDir.listFiles();
        if (files != null) for (File file : files) if (file != null && file.isFile()) total += file.length();
        return total;
    }

    private String cleanFileName(String name) {
        if (name == null) return "";
        name = name.replace('\\', '_').replace('/', '_').replace('\r', '_').replace('\n', '_').replace('"', '_').trim();
        if (name.length() > 100) name = name.substring(name.length() - 100);
        if (".".equals(name) || "..".equals(name)) return "";
        return name;
    }

    private String safeExtension(String mime) {
        if (mime == null) return ".bin";
        if ("video/mp4".equals(mime)) return ".mp4";
        if ("video/webm".equals(mime)) return ".webm";
        if ("video/quicktime".equals(mime)) return ".mov";
        if ("video/3gpp".equals(mime)) return ".3gp";
        if ("image/jpeg".equals(mime)) return ".jpg";
        if ("image/png".equals(mime)) return ".png";
        if ("image/webp".equals(mime)) return ".webp";
        if ("image/gif".equals(mime)) return ".gif";
        if ("application/pdf".equals(mime)) return ".pdf";
        if ("text/plain".equals(mime)) return ".txt";
        return ".bin";
    }

    private void getMedia(OutputStream out, String query, String rangeHeader) throws IOException, JSONException {
        JSONObject me = userForToken(queryParam(query, "token"));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String id = queryParam(query, "id");
        if (id.length() != 32 || !id.matches("[a-fA-F0-9]+")) { sendJson(out, 400, "Bad Request", errorJson("ID media tidak valid")); return; }
        boolean authorized = false, inline = false; String mime = "application/octet-stream", filename = "download.bin"; long expectedSize = 0;
        String phone = me.optString("phone");
        synchronized (DATA_LOCK) {
            for (int i=0;i<messages.length();i++) {
                JSONObject m=messages.optJSONObject(i); if(m==null) continue;
                if(id.equals(m.optString("mediaId")) || id.equals(m.optString("videoId"))) {
                    if(samePhone(phone,m.optString("sender")) || samePhone(phone,m.optString("recipient"))) {
                        authorized=true; mime=m.optString("mimeType","application/octet-stream"); filename=cleanFileName(m.optString("fileName","download"+safeExtension(mime))); expectedSize=m.optLong("fileSize",0); inline=mime.startsWith("video/")||mime.startsWith("image/")||mime.startsWith("audio/"); break;
                    }
                }
            }
            if(!authorized) for(int i=0;i<statuses.length();i++) {
                JSONObject st=statuses.optJSONObject(i); if(st==null) continue;
                if(id.equals(st.optString("mediaId")) && (samePhone(phone,st.optString("phone")) || hasSavedContact(phone,st.optString("phone")))) {
                    authorized=true; mime=st.optString("mimeType", "image/jpeg"); filename=cleanFileName(st.optString("fileName","status"+safeExtension(mime))); expectedSize=st.optLong("fileSize",0); inline=true; break;
                }
            }
        }
        if(!authorized){sendJson(out,404,"Not Found",errorJson("Media tidak ditemukan atau kamu tidak punya akses"));return;}
        File f=new File(mediaDir,id+".bin"); if(!f.exists() || (expectedSize>0 && f.length()!=expectedSize)){sendJson(out,404,"Not Found",errorJson("File media tidak tersedia"));return;}
        if(mime.length()==0 || mime.contains("\r") || mime.contains("\n")) mime="application/octet-stream";
        long start=0, end=f.length()-1; boolean partial=false;
        if(rangeHeader!=null && rangeHeader.startsWith("bytes=") && f.length()>0) {
            try {
                String spec=rangeHeader.substring(6).trim(); int dash=spec.indexOf('-');
                if(dash<0) throw new Exception();
                String a=spec.substring(0,dash).trim(), b=spec.substring(dash+1).trim();
                if(a.length()==0) { long suffix=Long.parseLong(b); if(suffix<=0) throw new Exception(); start=Math.max(0,f.length()-suffix); }
                else start=Long.parseLong(a);
                if(b.length()>0) end=Math.min(f.length()-1,Long.parseLong(b));
                if(start<0 || start>=f.length() || end<start) throw new Exception();
                partial=true;
            } catch(Exception badRange) { String h="HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"+f.length()+"\r\nContent-Length: 0\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n";out.write(h.getBytes(LATIN1));out.flush();return; }
        }
        long count=end-start+1; String disposition=inline?"inline":"attachment";
        StringBuilder h=new StringBuilder(); h.append("HTTP/1.1 ").append(partial?"206 Partial Content":"200 OK").append("\r\n");
        h.append("Content-Type: ").append(mime).append("\r\nContent-Length: ").append(count).append("\r\nAccept-Ranges: bytes\r\n");
        if(partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(f.length()).append("\r\n");
        h.append("Content-Disposition: ").append(disposition).append("; filename=\"").append(filename.replace("\"","_")).append("\"\r\n");
        h.append("X-Content-Type-Options: nosniff\r\nCache-Control: private, max-age=300\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type, X-RKM-Token, Range\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes(LATIN1));
        FileInputStream fin=null; try { fin=new FileInputStream(f); long skip=start; while(skip>0){long n=fin.skip(skip);if(n<=0){if(fin.read()<0)break;n=1;}skip-=n;}byte[] buf=new byte[8192];long remain=count;while(remain>0){int n=fin.read(buf,0,(int)Math.min(buf.length,remain));if(n<0)break;out.write(buf,0,n);remain-=n;}out.flush(); } finally { if(fin!=null)try{fin.close();}catch(Exception ignored){} }
    }

    private JSONObject findLive(String id) { if(id==null)return null; for(int i=0;i<liveSessions.length();i++){JSONObject s=liveSessions.optJSONObject(i);if(s!=null&&id.equals(s.optString("id")))return s;}return null; }
    private int liveViewerCount(JSONObject live, long now) { JSONArray a=live.optJSONArray("viewers");int n=0;if(a!=null)for(int i=0;i<a.length();i++){JSONObject v=a.optJSONObject(i);if(v!=null&&now-v.optLong("lastSeen",0)<45000)n++;}return n; }
    private JSONObject publicLive(JSONObject live, long now) throws JSONException { JSONObject r=new JSONObject();r.put("id",live.optString("id"));r.put("host",live.optString("host"));r.put("hostName",live.optString("hostName"));r.put("title",live.optString("title"));r.put("startedAt",live.optLong("startedAt"));r.put("active",live.optBoolean("active"));r.put("viewerCount",liveViewerCount(live,now));return r; }

    private void startLive(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token",""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String title=d.optString("title","Live RKM").trim();if(title.length()==0)title="Live RKM";if(title.length()>80){sendJson(out,400,"Bad Request",errorJson("Judul Live maksimal 80 karakter"));return;}
        String phone=me.optString("phone");long now=System.currentTimeMillis();JSONObject live=new JSONObject();
        synchronized(DATA_LOCK){for(int i=0;i<liveSessions.length();i++){JSONObject old=liveSessions.optJSONObject(i);if(old!=null&&phone.equals(old.optString("host"))&&old.optBoolean("active")){old.put("active",false);old.put("endedAt",now);}}
            live.put("id",UUID.randomUUID().toString().replace("-",""));live.put("host",phone);live.put("hostName",me.optString("name"));live.put("title",title);live.put("startedAt",now);live.put("lastSeen",now);live.put("active",true);live.put("viewers",new JSONArray());
            appendLimited(liveSessions,live,MAX_LIVE_SESSIONS);saveArray(liveFile,liveSessions);
        }
        JSONObject r=new JSONObject();r.put("ok",true);r.put("live",publicLive(live,now));sendJson(out,201,"Created",r.toString());
    }

    private void getLiveSessions(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        long now=System.currentTimeMillis();JSONArray list=new JSONArray();synchronized(DATA_LOCK){for(int i=0;i<liveSessions.length();i++){JSONObject live=liveSessions.optJSONObject(i);if(live==null||!live.optBoolean("active"))continue;if(now-live.optLong("lastSeen",0)>90000)continue;list.put(publicLive(live,now));}}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("lives",list);sendJson(out,200,"OK",r.toString());
    }

    private void stopLive(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token",""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}String id=d.optString("liveId","");long now=System.currentTimeMillis();
        synchronized(DATA_LOCK){JSONObject live=findLive(id);if(live==null){sendJson(out,404,"Not Found",errorJson("Sesi Live tidak ditemukan"));return;}if(!me.optString("phone").equals(live.optString("host"))){sendJson(out,403,"Forbidden",errorJson("Hanya pembuat Live yang dapat mengakhirinya"));return;}live.put("active",false);live.put("endedAt",now);saveArray(liveFile,liveSessions);}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("ended",true);sendJson(out,200,"OK",r.toString());
    }

    private void joinLive(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token",""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}String id=d.optString("liveId","");long now=System.currentTimeMillis();
        synchronized(DATA_LOCK){JSONObject live=findLive(id);if(live==null||!live.optBoolean("active")||now-live.optLong("lastSeen",0)>90000){sendJson(out,404,"Not Found",errorJson("Live sudah berakhir atau tidak ditemukan"));return;}String phone=me.optString("phone");if(phone.equals(live.optString("host"))){JSONObject r=new JSONObject();r.put("ok",true);r.put("live",publicLive(live,now));sendJson(out,200,"OK",r.toString());return;}JSONArray viewers=live.optJSONArray("viewers");if(viewers==null){viewers=new JSONArray();live.put("viewers",viewers);}boolean found=false;for(int i=0;i<viewers.length();i++){JSONObject v=viewers.optJSONObject(i);if(v!=null&&phone.equals(v.optString("phone"))){v.put("lastSeen",now);v.put("name",me.optString("name"));found=true;break;}}if(!found){if(liveViewerCount(live,now)>=6){sendJson(out,429,"Too Many Requests",errorJson("Live ini mencapai batas uji 6 penonton langsung. Gunakan server relay untuk skala lebih besar."));return;}JSONObject v=new JSONObject();v.put("phone",phone);v.put("name",me.optString("name"));v.put("lastSeen",now);viewers.put(v);}saveArray(liveFile,liveSessions);}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("joined",true);sendJson(out,200,"OK",r.toString());
    }

    private void leaveLive(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token",""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}String id=d.optString("liveId","");String phone=me.optString("phone");
        synchronized(DATA_LOCK){JSONObject live=findLive(id);if(live!=null){JSONArray old=live.optJSONArray("viewers"), next=new JSONArray();if(old!=null)for(int i=0;i<old.length();i++){JSONObject v=old.optJSONObject(i);if(v!=null&&!phone.equals(v.optString("phone")))next.put(v);}live.put("viewers",next);saveArray(liveFile,liveSessions);}}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("left",true);sendJson(out,200,"OK",r.toString());
    }

    private void heartbeatLive(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token",""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}String id=d.optString("liveId","");String phone=me.optString("phone");long now=System.currentTimeMillis();boolean found=false;
        synchronized(DATA_LOCK){JSONObject live=findLive(id);if(live!=null&&live.optBoolean("active")){if(phone.equals(live.optString("host"))){live.put("lastSeen",now);found=true;}else{JSONArray a=live.optJSONArray("viewers");if(a!=null)for(int i=0;i<a.length();i++){JSONObject v=a.optJSONObject(i);if(v!=null&&phone.equals(v.optString("phone"))){v.put("lastSeen",now);found=true;break;}}}if(found)saveArray(liveFile,liveSessions);}}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("active",found);sendJson(out,200,"OK",r.toString());
    }

    private void postLiveSignal(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token",""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}String liveId=d.optString("liveId",""),to=normalizePhone(d.optString("to","")),type=d.optString("type","");Object payload=d.opt("payload");
        if(!validPhone(to)||!("offer".equals(type)||"answer".equals(type)||"ice".equals(type)||"end".equals(type))){sendJson(out,400,"Bad Request",errorJson("Sinyal Live tidak valid"));return;}
        if(payload==null||JSONObject.NULL.equals(payload)||payload.toString().length()>90000){sendJson(out,413,"Payload Too Large",errorJson("Data sinyal terlalu besar"));return;}
        synchronized(DATA_LOCK){JSONObject live=findLive(liveId);if(live==null||!live.optBoolean("active")){sendJson(out,404,"Not Found",errorJson("Live tidak aktif"));return;}String from=me.optString("phone"),host=live.optString("host");boolean fromHost=from.equals(host);boolean toParticipant=to.equals(host);JSONArray viewers=live.optJSONArray("viewers");if(viewers!=null)for(int i=0;i<viewers.length();i++){JSONObject v=viewers.optJSONObject(i);if(v!=null&&to.equals(v.optString("phone"))&&System.currentTimeMillis()-v.optLong("lastSeen",0)<45000)toParticipant=true;}boolean fromParticipant=false;if(viewers!=null)for(int i=0;i<viewers.length();i++){JSONObject v=viewers.optJSONObject(i);if(v!=null&&from.equals(v.optString("phone"))&&System.currentTimeMillis()-v.optLong("lastSeen",0)<45000)fromParticipant=true;}if(!(fromHost||fromParticipant)||!(toParticipant||to.equals(host))){sendJson(out,403,"Forbidden",errorJson("Peserta tidak termasuk dalam sesi Live ini"));return;}
            JSONObject sig=new JSONObject();sig.put("id",++nextLiveSignalId);sig.put("liveId",liveId);sig.put("from",from);sig.put("to",to);sig.put("type",type);sig.put("payload",payload);sig.put("time",System.currentTimeMillis());appendLimited(liveSignals,sig,MAX_LIVE_SIGNALS);saveArray(liveSignalsFile,liveSignals);}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("queued",true);sendJson(out,200,"OK",r.toString());
    }

    private void getLiveSignals(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}String liveId=queryParam(query,"liveId");long since=0;try{since=Long.parseLong(queryParam(query,"since"));}catch(Exception ignored){}long now=System.currentTimeMillis();JSONArray list=new JSONArray();
        synchronized(DATA_LOCK){for(int i=0;i<liveSignals.length();i++){JSONObject sig=liveSignals.optJSONObject(i);if(sig!=null&&me.optString("phone").equals(sig.optString("to"))&&liveId.equals(sig.optString("liveId"))&&sig.optLong("id",0)>since&&now-sig.optLong("time",now)<300000)list.put(sig);}}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("signals",list);sendJson(out,200,"OK",r.toString());
    }

    private void postLiveComment(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token",""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}String id=d.optString("liveId",""),text=d.optString("text","").trim();if(text.length()==0||text.length()>280){sendJson(out,400,"Bad Request",errorJson("Komentar Live harus 1–280 karakter"));return;}JSONObject comment=new JSONObject();comment.put("id",UUID.randomUUID().toString());comment.put("liveId",id);comment.put("phone",me.optString("phone"));comment.put("name",me.optString("name"));comment.put("text",text);comment.put("time",System.currentTimeMillis());
        synchronized(DATA_LOCK){JSONObject live=findLive(id);if(live==null||!live.optBoolean("active")){sendJson(out,404,"Not Found",errorJson("Live tidak aktif"));return;}appendLimited(liveComments,comment,MAX_LIVE_COMMENTS);saveArray(liveCommentsFile,liveComments);}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("comment",comment);sendJson(out,201,"Created",r.toString());
    }

    private void getLiveComments(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}String id=queryParam(query,"liveId");long since=0;try{since=Long.parseLong(queryParam(query,"since"));}catch(Exception ignored){}JSONArray list=new JSONArray();synchronized(DATA_LOCK){for(int i=0;i<liveComments.length();i++){JSONObject c=liveComments.optJSONObject(i);if(c!=null&&id.equals(c.optString("liveId"))&&c.optLong("time",0)>since)list.put(c);}}JSONObject r=new JSONObject();r.put("ok",true);r.put("comments",list);sendJson(out,200,"OK",r.toString());
    }

    private void postStatus(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token", "")); if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String text=d.optString("text", "").trim(), encoded=d.optString("data", "");
        if(text.length()>240 || (text.length()==0 && encoded.length()==0)){sendJson(out,400,"Bad Request",errorJson("Isi teks status atau pilih foto; teks maksimal 240 karakter"));return;}
        JSONObject st=new JSONObject(); st.put("id",UUID.randomUUID().toString()); st.put("phone",me.optString("phone")); st.put("name",me.optString("name")); st.put("text",text); st.put("time",System.currentTimeMillis());
        if(encoded.length()>0){
            if(encoded.length()>((MAX_IMAGE_BYTES*4/3)+1000)){sendJson(out,413,"Payload Too Large",errorJson("Foto status terlalu besar"));return;}
            byte[] image; try{image=Base64.decode(encoded,Base64.DEFAULT);}catch(Exception e){sendJson(out,400,"Bad Request",errorJson("Data foto status tidak valid"));return;}
            if(image.length==0||image.length>MAX_IMAGE_BYTES){sendJson(out,413,"Payload Too Large",errorJson("Foto status maksimal sekitar 180 KB"));return;}
            String mediaId=UUID.randomUUID().toString().replace("-",""); File file=new File(imagesDir,mediaId+".jpg"); FileOutputStream fos=null;
            try{fos=new FileOutputStream(file);fos.write(image);fos.flush();fos.getFD().sync();}catch(Exception e){if(file.exists())file.delete();sendJson(out,500,"Internal Server Error",errorJson("Gagal menyimpan foto status"));return;}finally{if(fos!=null)try{fos.close();}catch(Exception ignored){}}
            st.put("mediaId",mediaId); st.put("mediaType","image");
        }
        synchronized(DATA_LOCK){appendLimited(statuses,st,MAX_STATUSES);saveArray(statusesFile,statuses);}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("status",st);sendJson(out,200,"OK",r.toString());
    }

    private void getStatuses(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String phone=me.optString("phone"); JSONArray list=new JSONArray();long min=System.currentTimeMillis()-24L*60L*60L*1000L;
        synchronized(DATA_LOCK){for(int i=0;i<statuses.length();i++){JSONObject st=statuses.optJSONObject(i);if(st!=null&&st.optLong("time",0)>=min&&(samePhone(phone,st.optString("phone"))||hasSavedContact(phone,st.optString("phone"))))list.put(st);}}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("statuses",list);sendJson(out,200,"OK",r.toString());
    }

    private void getImage(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String id=queryParam(query,"id");if(id.length()<16||id.length()>64||!id.matches("[a-fA-F0-9]+")){sendJson(out,400,"Bad Request",errorJson("ID foto tidak valid"));return;}
        boolean authorized=false;String phone=me.optString("phone");
        synchronized(DATA_LOCK){for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m!=null&&id.equals(m.optString("imageId"))&&(samePhone(phone,m.optString("sender"))||samePhone(phone,m.optString("recipient")))){authorized=true;break;}}if(!authorized){for(int i=0;i<statuses.length();i++){JSONObject st=statuses.optJSONObject(i);if(st!=null&&id.equals(st.optString("mediaId"))&&(samePhone(phone,st.optString("phone"))||hasSavedContact(phone,st.optString("phone")))){authorized=true;break;}}}}
        if(!authorized){sendJson(out,404,"Not Found",errorJson("Foto tidak ditemukan"));return;}
        File f=new File(imagesDir,id+".jpg");if(!f.exists()||f.length()>MAX_IMAGE_BYTES){sendJson(out,404,"Not Found",errorJson("Foto tidak ditemukan"));return;}
        FileInputStream in=null;ByteArrayOutputStream buf=new ByteArrayOutputStream();try{in=new FileInputStream(f);byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)buf.write(b,0,n);}finally{if(in!=null)try{in.close();}catch(Exception ignored){}}
        sendResponseCached(out,200,"OK","image/jpeg",buf.toByteArray(),86400);
    }

    private void loadAll(){ synchronized(DATA_LOCK){messages=loadArray(messagesFile,MAX_MESSAGES);users=loadArray(usersFile,1000);sessions=loadArray(sessionsFile,2000);callSignals=loadArray(callsFile,MAX_CALL_SIGNALS);statuses=loadArray(statusesFile,MAX_STATUSES);savedContacts=loadArray(contactsFile,5000);liveSessions=loadArray(liveFile,MAX_LIVE_SESSIONS);liveSignals=loadArray(liveSignalsFile,MAX_LIVE_SIGNALS);liveComments=loadArray(liveCommentsFile,MAX_LIVE_COMMENTS);for(int i=0;i<callSignals.length();i++){JSONObject s=callSignals.optJSONObject(i);if(s!=null)nextSignalId=Math.max(nextSignalId,s.optLong("id",0));}for(int i=0;i<liveSignals.length();i++){JSONObject s=liveSignals.optJSONObject(i);if(s!=null)nextLiveSignalId=Math.max(nextLiveSignalId,s.optLong("id",0));}} }
    private JSONArray loadArray(File file,int limit){if(file==null||!file.exists())return new JSONArray();try{JSONArray a=new JSONArray(readFile(file));JSONArray b=new JSONArray();int from=Math.max(0,a.length()-limit);for(int i=from;i<a.length();i++)b.put(a.get(i));return b;}catch(Exception e){return new JSONArray();}}
    private String readFile(File f)throws IOException{FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);in.close();return new String(out.toByteArray(),UTF8);}
    private void saveArray(File f,JSONArray a){File temp=new File(getFilesDir(),f.getName()+".tmp");try{FileOutputStream o=new FileOutputStream(temp);o.write(a.toString().getBytes(UTF8));o.flush();o.getFD().sync();o.close();if(f.exists()&&!f.delete())throw new IOException("replace failed");if(!temp.renameTo(f))throw new IOException("rename failed");}catch(Exception e){lastError="Gagal menyimpan data";try{if(temp.exists())temp.delete();}catch(Exception ignored){}}}
    private void appendLimited(JSONArray a,JSONObject item,int max)throws JSONException{JSONArray n=new JSONArray();int from=Math.max(0,a.length()-max+1);for(int i=from;i<a.length();i++)n.put(a.get(i));n.put(item);if(a==messages)messages=n;else if(a==callSignals)callSignals=n;else if(a==statuses)statuses=n;else if(a==sessions)sessions=n;else if(a==users)users=n;else if(a==liveSignals)liveSignals=n;else if(a==liveComments)liveComments=n;else if(a==liveSessions)liveSessions=n;}
    private JSONObject findUser(String phone){if(phone==null)return null;for(int i=0;i<users.length();i++){JSONObject u=users.optJSONObject(i);if(u!=null&&samePhone(phone,u.optString("phone")))return u;}return null;}
    private JSONObject userForToken(String token){if(token==null||token.length()==0)return null; synchronized(DATA_LOCK){for(int i=0;i<sessions.length();i++){JSONObject s=sessions.optJSONObject(i);if(s!=null&&constantTimeEquals(token,s.optString("token", ""))){JSONObject u=findUser(s.optString("phone"));if(u!=null)return u;}}}return null;}
    private String createSession(String phone)throws JSONException{String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");JSONObject s=new JSONObject();s.put("phone",phone);s.put("token",token);s.put("time",System.currentTimeMillis());appendLimited(sessions,s,2000);saveArray(sessionsFile,sessions);return token;}
    private JSONObject userPublic(JSONObject u)throws JSONException{JSONObject r=new JSONObject();r.put("phone",u.optString("phone"));r.put("name",u.optString("name"));r.put("about",u.optString("about",""));return r;}
    private String normalizePhone(String p){if(p==null)return "";return p.replaceAll("[^0-9]", "");}
    private boolean validPhone(String p){return p!=null&&p.matches("[0-9]{8,15}");}
    private String cleanName(String n){if(n==null)return "";n=n.trim();if(n.length()>40)n=n.substring(0,40);return n;}
    private String hashPassword(String salt,String password){try{PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt.getBytes(UTF8),30000,256);byte[] b=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();spec.clearPassword();StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02x",x&0xff));return "pbkdf2$30000$"+s.toString();}catch(Exception e){return "";}}
    private String legacyHashPassword(String salt,String password){try{MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=md.digest((salt+":"+password).getBytes(UTF8));StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02x",x&0xff));return s.toString();}catch(Exception e){return "";}}
    private boolean constantTimeEquals(String a,String b){if(a==null||b==null)return false;int diff=a.length()^b.length();int n=Math.max(a.length(),b.length());for(int i=0;i<n;i++){char x=i<a.length()?a.charAt(i):0,y=i<b.length()?b.charAt(i):0;diff|=x^y;}return diff==0;}
    private boolean samePhone(String a,String b){return a!=null&&b!=null&&a.equals(b);}
    private long nextSignalId(){nextSignalId=Math.max(nextSignalId+1,System.currentTimeMillis());return nextSignalId;}
    private String timeNow(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date());}
    private String queryParam(String query,String wanted){if(query==null||query.length()==0)return "";String[] pairs=query.split("&");for(String pair:pairs){int e=pair.indexOf('=');String k=e>=0?pair.substring(0,e):pair,v=e>=0?pair.substring(e+1):"";try{k=URLDecoder.decode(k,"UTF-8");v=URLDecoder.decode(v,"UTF-8");}catch(Exception ignored){}if(wanted.equals(k))return v;}return "";}
    private JSONObject errorJson(String msg){JSONObject o=new JSONObject();try{o.put("ok",false);o.put("error",msg);}catch(JSONException ignored){}return o;}
    private int getMessageCount(){synchronized(DATA_LOCK){return messages.length();}}
    private byte[] readHeaders(InputStream input)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int state=0;while(b.size()<MAX_HEADER_BYTES){int c=input.read();if(c<0)return null;b.write(c);if(state==0&&c=='\r')state=1;else if(state==1&&c=='\n')state=2;else if(state==2&&c=='\r')state=3;else if(state==3&&c=='\n')return b.toByteArray();else if(c=='\r')state=1;else state=0;}throw new IOException("Header terlalu besar");}
    private byte[] readBody(InputStream input,int length)throws IOException{byte[] r=new byte[length];int total=0;while(total<length){int n=input.read(r,total,length-total);if(n<0)return null;total+=n;}return r;}
    private byte[] readAsset(String name)throws IOException{InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);in.close();return out.toByteArray();}
    private void sendJson(OutputStream out,int code,String reason,String json)throws IOException{sendResponse(out,code,reason,"application/json; charset=utf-8",json.getBytes(UTF8));}
    // Build fix: accept JSONObject responses and serialize them to JSON text.
    private void sendJson(OutputStream out,int code,String reason,JSONObject json)throws IOException{sendJson(out,code,reason,json==null?"{}":json.toString());}
    private void sendResponseCached(OutputStream out,int code,String reason,String type,byte[] body,int maxAge)throws IOException{String header="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: private, max-age="+Math.max(0,maxAge)+"\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type, X-RKM-Token, Range\r\n\r\n";out.write(header.getBytes(LATIN1));out.write(body);out.flush();}
    private void sendResponse(OutputStream out,int code,String reason,String type,byte[] body)throws IOException{String header="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type, X-RKM-Token, Range\r\n\r\n";out.write(header.getBytes(LATIN1));out.write(body);out.flush();}
    private void closeServerSocket(){ServerSocket s=serverSocket;serverSocket=null;if(s!=null)try{s.close();}catch(Exception ignored){}}
    private void releaseLocks(){try{if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}catch(Exception ignored){}try{if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();}catch(Exception ignored){}wakeLock=null;wifiLock=null;}
    private void closeQuietly(Socket s){if(s!=null)try{s.close();}catch(Exception ignored){}}
    @Override public void onDestroy(){stopRequested=true;isRunning=false;closeServerSocket();releaseLocks();ThreadPoolExecutor p=workers;if(p!=null)p.shutdownNow();if(acceptThread!=null)acceptThread.interrupt();stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
