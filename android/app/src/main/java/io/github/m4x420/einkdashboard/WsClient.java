package io.github.m4x420.einkdashboard;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

/**
 * Minimaler RFC-6455-Client (nur ws://, nur Text-Frames) – ohne Abhaengigkeiten,
 * laeuft ab API 8 (android.util.Base64). Ein Objekt = eine Verbindung; fuer Reconnect neu erzeugen.
 * Alle Listener-Callbacks kommen auf dem Reader-Thread.
 */
final class WsClient {

    interface Listener {
        void onText(WsClient ws, String text);

        /** Lese-Timeout ohne Daten: guter Moment fuer ein Ping. */
        void onIdle(WsClient ws);

        void onClosed(WsClient ws, Exception cause);
    }

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int MAX_MESSAGE = 512 * 1024;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final String host;
    private final int port;
    private final String path;
    private final Listener listener;
    private final Random random = new Random();
    private volatile Socket socket;
    private volatile boolean closed;
    private OutputStream out; // guarded by this

    WsClient(String host, int port, String path, Listener listener) {
        this.host = host;
        this.port = port;
        this.path = path;
        this.listener = listener;
    }

    void connect() {
        new Thread(this::run, "ws-reader").start();
    }

    void close() {
        closed = true;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes("UTF-8"));
    }

    private void run() {
        Exception cause = null;
        try {
            Socket s = new Socket();
            socket = s;
            if (closed) throw new IOException("geschlossen");
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            synchronized (this) {
                out = new BufferedOutputStream(s.getOutputStream());
            }
            handshake(in);
            readLoop(in);
        } catch (Exception e) {
            cause = e;
        } finally {
            close();
        }
        listener.onClosed(this, cause);
    }

    private void handshake(InputStream in) throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        synchronized (this) {
            out.write(req.getBytes("ISO-8859-1"));
            out.flush();
        }

        String statusLine = readLine(in);
        if (!statusLine.startsWith("HTTP/1.1 101")) {
            throw new IOException("Handshake abgelehnt: " + statusLine);
        }
        String accept = null;
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) {
                accept = line.substring(colon + 1).trim();
            }
        }
        if (!expectedAccept(key).equals(accept)) {
            throw new IOException("Ungueltiger Sec-WebSocket-Accept");
        }
    }

    private void readLoop(InputStream in) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int idleTimeouts = 0;
        while (!closed) {
            int b0;
            try {
                b0 = in.read();
            } catch (SocketTimeoutException e) {
                // Erster Timeout: Ping schicken. Zweiter in Folge: Verbindung ist tot (z.B. WLAN weg).
                if (++idleTimeouts > 1) throw new IOException("Keine Antwort von HA");
                listener.onIdle(this);
                continue;
            }
            if (b0 < 0) throw new EOFException("Server hat Verbindung geschlossen");
            idleTimeouts = 0;

            int b1 = readByte(in);
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
            }
            if (len > MAX_MESSAGE) throw new IOException("Frame zu gross: " + len);

            byte[] mask = null;
            if ((b1 & 0x80) != 0) {
                mask = new byte[4];
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }

            switch (opcode) {
                case 0x1: // Text: neue Nachricht
                    message.reset();
                    // fall through
                case 0x0: // Fortsetzung
                    message.write(payload, 0, payload.length);
                    if (message.size() > MAX_MESSAGE) throw new IOException("Nachricht zu gross");
                    if (fin) {
                        listener.onText(this, message.toString("UTF-8"));
                        message.reset();
                    }
                    break;
                case 0x8: // Close
                    try {
                        sendFrame(0x8, new byte[0]);
                    } catch (IOException ignored) {
                    }
                    throw new EOFException("Close-Frame vom Server");
                case 0x9: // Ping -> Pong (HA/aiohttp schickt Heartbeats)
                    sendFrame(0xA, payload);
                    break;
                default: // Pong, Binary: ignorieren
                    break;
            }
        }
    }

    /** Client-Frames muessen maskiert sein (RFC 6455). */
    private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        if (out == null) throw new IOException("nicht verbunden");
        int n = payload.length;
        ByteArrayOutputStream f = new ByteArrayOutputStream(n + 14);
        f.write(0x80 | opcode);
        if (n < 126) {
            f.write(0x80 | n);
        } else if (n <= 0xFFFF) {
            f.write(0x80 | 126);
            f.write(n >>> 8);
            f.write(n & 0xFF);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write(i >= 4 ? 0 : (n >>> (8 * i)) & 0xFF);
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < n; i++) f.write(payload[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    private static String expectedAccept(String key) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + GUID).getBytes("ISO-8859-1"));
            return Base64.encodeToString(digest, Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e.toString());
        }
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("Header-Zeile zu lang");
        }
        throw new EOFException("Verbindung waehrend Handshake geschlossen");
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }
}
