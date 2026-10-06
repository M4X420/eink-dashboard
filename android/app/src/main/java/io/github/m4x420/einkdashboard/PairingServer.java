package io.github.m4x420.einkdashboard;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;

/**
 * Mini-HTTP-Server, ueber den Home Assistant den Tolino koppelt.
 *
 * GET  /api/info    Geraeteinfos, ohne Auth (enthaelt nichts Geheimes)
 * POST /api/pair    {"code", "token", "port"?, "host"?} – ungekoppelt: Code vom Display noetig,
 *                   gekoppelt: nur mit aktuellem Token (Authorization: Bearer) zum Neu-Koppeln
 * POST /api/unpair  nur mit aktuellem Token
 *
 * Verbindungen werden nacheinander abgearbeitet; zusammen mit der Pause nach falschem Code
 * bremst das Durchprobieren des 6-stelligen Codes aus.
 */
final class PairingServer {

    interface Listener {
        /** Main-Thread. */
        void onPairingChanged();
    }

    private static final String TAG = "eink-dashboard";
    private static final int SOCKET_TIMEOUT_MS = 5000;
    private static final int MAX_BODY = 8192;
    private static final long WRONG_CODE_DELAY_MS = 2000;
    private static final int MAX_WRONG_CODES = 10;

    private final int port;
    private final DeviceConfig config;
    private final JSONObject deviceInfo;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private int wrongCodes; // nur Server-Thread

    PairingServer(int port, DeviceConfig config, JSONObject deviceInfo, Listener listener) {
        this.port = port;
        this.config = config;
        this.deviceInfo = deviceInfo;
        this.listener = listener;
    }

    void start() {
        running = true;
        new Thread(this::acceptLoop, "pairing-server").start();
    }

    void stop() {
        running = false;
        ServerSocket s = serverSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(port));
                serverSocket = s;
                Log.i(TAG, "Pairing-Server lauscht auf Port " + port);
                while (running) {
                    Socket client = s.accept();
                    try {
                        handle(client);
                    } catch (Exception e) {
                        Log.w(TAG, "Pairing-Anfrage fehlgeschlagen", e);
                    } finally {
                        try {
                            client.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                if (!running) return;
                Log.w(TAG, "Pairing-Server neu in 5 s: " + e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void handle(Socket client) throws IOException {
        client.setSoTimeout(SOCKET_TIMEOUT_MS);
        InputStream in = new BufferedInputStream(client.getInputStream());
        String[] requestLine = WsClient.readLine(in).split(" ");
        if (requestLine.length < 2) return;
        String method = requestLine[0];
        String path = requestLine[1];

        int contentLength = 0;
        String auth = null;
        for (String line = WsClient.readLine(in); !line.isEmpty(); line = WsClient.readLine(in)) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Content-Length")) contentLength = Integer.parseInt(value);
            else if (name.equalsIgnoreCase("Authorization")) auth = value;
        }

        int status;
        JSONObject response = new JSONObject();
        try {
            if (contentLength > MAX_BODY) {
                status = 413;
                response.put("error", "body_too_large");
            } else {
                byte[] body = new byte[contentLength];
                WsClient.readFully(in, body);
                String remote = client.getInetAddress().getHostAddress();
                status = route(method, path, auth, new String(body, "UTF-8"), remote, response);
            }
        } catch (JSONException e) {
            status = 400;
            response = new JSONObject();
            try {
                response.put("error", "invalid_json");
            } catch (JSONException ignored) {
            }
        }
        Log.i(TAG, "Pairing-Server: " + method + " " + path + " -> " + status);
        writeResponse(client.getOutputStream(), status, response.toString());
    }

    private int route(String method, String path, String auth, String body, String remote, JSONObject out)
            throws JSONException {
        if ("GET".equals(method) && "/api/info".equals(path)) {
            JSONObject info = new JSONObject(deviceInfo.toString());
            info.put("device_id", config.deviceId());
            info.put("paired", config.isPaired());
            copy(info, out);
            return 200;
        }
        if ("POST".equals(method) && "/api/pair".equals(path)) {
            return pair(auth, body, remote, out);
        }
        if ("POST".equals(method) && "/api/unpair".equals(path)) {
            if (!isAuthorized(auth)) {
                out.put("error", "unauthorized");
                return 401;
            }
            config.unpair();
            ui.post(listener::onPairingChanged);
            out.put("ok", true);
            return 200;
        }
        out.put("error", "not_found");
        return 404;
    }

    private int pair(String auth, String body, String remote, JSONObject out) throws JSONException {
        JSONObject req = new JSONObject(body);
        if (config.isPaired()) {
            if (!isAuthorized(auth)) {
                out.put("error", "already_paired");
                return 409;
            }
        } else if (!config.pairingCode().equals(req.optString("code"))) {
            if (++wrongCodes >= MAX_WRONG_CODES) {
                wrongCodes = 0;
                config.rotatePairingCode();
                ui.post(listener::onPairingChanged);
            }
            sleep(WRONG_CODE_DELAY_MS);
            out.put("error", "invalid_code");
            return 403;
        }

        String token = req.optString("token", "");
        if (token.isEmpty()) {
            out.put("error", "token_missing");
            return 400;
        }
        // Ohne "host" nehmen wir die Adresse, von der HA gerade anfragt.
        String host = req.optString("host", remote);
        int port = req.optInt("port", 8123);

        // Erst pruefen, dann speichern: ein falscher Token soll den Tolino nicht lahmlegen.
        try {
            new HaClient("http://" + host + ":" + port, token).checkApi();
        } catch (Exception e) {
            out.put("error", "ha_check_failed");
            out.put("detail", String.valueOf(e.getMessage()));
            return 400;
        }

        wrongCodes = 0;
        config.pair(host, port, token);
        ui.post(listener::onPairingChanged);
        out.put("device_id", config.deviceId());
        out.put("host", host);
        out.put("port", port);
        return 200;
    }

    private boolean isAuthorized(String auth) {
        String token = config.token();
        if (auth == null || token == null) return false;
        try {
            return MessageDigest.isEqual(auth.getBytes("UTF-8"), ("Bearer " + token).getBytes("UTF-8"));
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeResponse(OutputStream os, int status, String json) throws IOException {
        byte[] body = json.getBytes("UTF-8");
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        os.write(head.getBytes("ISO-8859-1"));
        os.write(body);
        os.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            default: return "Error";
        }
    }

    private static void copy(JSONObject from, JSONObject to) throws JSONException {
        for (java.util.Iterator<String> it = from.keys(); it.hasNext(); ) {
            String k = it.next();
            to.put(k, from.get(k));
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
