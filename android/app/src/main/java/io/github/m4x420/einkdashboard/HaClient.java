package io.github.m4x420.einkdashboard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Blockierender REST-Client. Nur aus einem Hintergrund-Thread aufrufen. */
final class HaClient {
    private static final int TIMEOUT_MS = 10000;

    private final String baseUrl;
    private final String token;

    HaClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    /** Wirft, wenn HA nicht erreichbar oder der Token ungueltig ist. */
    void checkApi() throws IOException {
        request("GET", "/api/", null);
    }

    /** Liefert die States zurueck, die sich WAEHREND des Calls geaendert haben (oft leer bei Zigbee & Co.). */
    List<EntityState> callService(String domain, String service, JSONObject data) throws IOException, JSONException {
        JSONArray arr = new JSONArray(request("POST", "/api/services/" + domain + "/" + service, data.toString()));
        List<EntityState> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            result.add(EntityState.fromRest(arr.getJSONObject(i)));
        }
        return result;
    }

    private String request(String method, String path, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setUseCaches(false);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            if (json != null) {
                byte[] bytes = json.getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                OutputStream os = c.getOutputStream();
                try {
                    os.write(bytes);
                } finally {
                    os.close();
                }
            }

            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                // Android < 4.1: ein 401 ohne WWW-Authenticate-Header wirft hier, statt 401 zu liefern.
                String msg = e.getMessage();
                if (msg != null && msg.contains("challenge")) {
                    throw new IOException("HTTP 401 - Token falsch?");
                }
                throw e;
            }

            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : readAll(in);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " " + path);
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    // StandardCharsets gibt es erst ab API 19 -> Charset-Name als String.
    private static String readAll(InputStream in) throws IOException {
        try {
            Reader r = new InputStreamReader(in, "UTF-8");
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[2048];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            in.close();
        }
    }
}
