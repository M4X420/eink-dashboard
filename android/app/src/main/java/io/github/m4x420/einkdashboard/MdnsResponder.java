package io.github.m4x420.einkdashboard;

import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.Locale;

/**
 * Minimaler mDNS-Responder (RFC 6762/6763), damit Home Assistant das Geraet per Zeroconf findet.
 * Android 2.3 hat noch kein NsdManager (erst API 16).
 *
 * Bietet _eink-dashboard._tcp mit Port des Pairing-Servers an und beantwortet Anfragen nach
 * PTR (Dienst), SRV/TXT (Instanz), A (Hostname). Antworten gehen immer per Multicast.
 */
final class MdnsResponder {
    private static final String TAG = "eink-dashboard";
    private static final String GROUP = "224.0.0.251";
    private static final int MDNS_PORT = 5353;
    private static final String SERVICE = "_eink-dashboard._tcp.local";
    private static final String SERVICES_META = "_services._dns-sd._udp.local";
    private static final int TYPE_A = 1;
    private static final int TYPE_PTR = 12;
    private static final int TYPE_TXT = 16;
    private static final int TYPE_SRV = 33;
    private static final int TYPE_ANY = 255;
    private static final int CLASS_IN = 1;
    private static final int CACHE_FLUSH = 0x8000;
    private static final int TTL_HOST = 120;
    private static final int TTL_SERVICE = 4500;

    private final WifiManager wifi;
    private final DeviceConfig config;
    private final int servicePort;
    private final String model;
    private final String appVersion;
    private final String instance;
    private final String hostName;
    private final WifiManager.MulticastLock multicastLock;
    private volatile boolean running;
    private volatile MulticastSocket socket;

    MdnsResponder(WifiManager wifi, DeviceConfig config, int servicePort, String model, String appVersion) {
        this.wifi = wifi;
        this.config = config;
        this.servicePort = servicePort;
        this.model = model;
        this.appVersion = appVersion;
        String id = config.deviceId();
        this.instance = "E-Ink Dashboard " + id.substring(0, 4) + "." + SERVICE;
        this.hostName = "eink-" + id.substring(0, 8) + ".local";
        // Ohne Lock verwirft der WLAN-Treiber Multicast-Pakete, um Strom zu sparen.
        this.multicastLock = wifi.createMulticastLock("eink-dashboard-mdns");
        this.multicastLock.setReferenceCounted(false);
    }

    void start() {
        running = true;
        multicastLock.acquire();
        new Thread(this::loop, "mdns").start();
    }

    void stop() {
        running = false;
        closeSocket();
        if (multicastLock.isHeld()) multicastLock.release();
    }

    /** Neu beitreten und ankuendigen, z.B. nach WLAN-Wechsel oder geaenderter Kopplung (TXT "paired"). */
    void reannounce() {
        closeSocket();
    }

    private void closeSocket() {
        MulticastSocket s = socket;
        if (s != null) s.close();
    }

    private void loop() {
        while (running) {
            try {
                MulticastSocket s = new MulticastSocket(MDNS_PORT);
                s.setTimeToLive(255);
                s.joinGroup(InetAddress.getByName(GROUP));
                socket = s;
                Log.i(TAG, "mDNS: biete " + instance + " an");
                announce(s);
                byte[] buf = new byte[9000];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    try {
                        handle(s, p.getData(), p.getLength());
                    } catch (IOException e) {
                        // kaputtes oder fremdes Paket: ignorieren
                    }
                }
            } catch (IOException e) {
                if (!running) return;
                // Normal nach reannounce() (Socket geschlossen) oder ohne WLAN.
                sleep(socket == null ? 10000 : 500);
                socket = null;
            }
        }
    }

    /** Unaufgefordert ankuendigen, zweimal im Abstand von 1 s (RFC 6762, 8.3). */
    private void announce(MulticastSocket s) throws IOException {
        for (int i = 0; i < 2 && running; i++) {
            byte[] packet = buildResponse(true, true, true, false);
            if (packet != null) send(s, packet);
            sleep(1000);
        }
    }

    private void handle(MulticastSocket s, byte[] d, int len) throws IOException {
        if (len < 12) return;
        if ((u16(d, 2) & 0x8000) != 0) return; // Antwort, keine Anfrage
        int questions = u16(d, 4);
        String instanceLower = instance.toLowerCase(Locale.US);
        String hostLower = hostName.toLowerCase(Locale.US);
        boolean ptr = false, srvTxt = false, a = false, meta = false;
        int pos = 12;
        for (int i = 0; i < questions; i++) {
            StringBuilder name = new StringBuilder();
            pos = readName(d, pos, len, name);
            if (pos + 4 > len) return;
            int type = u16(d, pos);
            pos += 4;
            String n = name.toString().toLowerCase(Locale.US);
            if (n.equals(SERVICE) && (type == TYPE_PTR || type == TYPE_ANY)) ptr = true;
            else if (n.equals(SERVICES_META) && (type == TYPE_PTR || type == TYPE_ANY)) meta = true;
            else if (n.equals(instanceLower) && (type == TYPE_SRV || type == TYPE_TXT || type == TYPE_ANY)) srvTxt = true;
            else if (n.equals(hostLower) && (type == TYPE_A || type == TYPE_ANY)) a = true;
        }
        if (!(ptr || srvTxt || a || meta)) return;
        byte[] packet = buildResponse(ptr, srvTxt || ptr, a || srvTxt || ptr, meta);
        if (packet != null) send(s, packet);
    }

    /** @return null ohne IP-Adresse (kein WLAN) */
    private byte[] buildResponse(boolean ptr, boolean srvTxt, boolean a, boolean meta) throws IOException {
        int ip = wifi.getConnectionInfo().getIpAddress();
        if (ip == 0) return null;
        byte[] addr = {(byte) ip, (byte) (ip >> 8), (byte) (ip >> 16), (byte) (ip >>> 24)};

        ByteArrayOutputStream records = new ByteArrayOutputStream();
        int count = 0;
        if (meta) {
            writeRecord(records, SERVICES_META, TYPE_PTR, CLASS_IN, TTL_SERVICE, nameBytes(SERVICE));
            count++;
        }
        if (ptr) {
            writeRecord(records, SERVICE, TYPE_PTR, CLASS_IN, TTL_SERVICE, nameBytes(instance));
            count++;
        }
        if (srvTxt) {
            ByteArrayOutputStream srv = new ByteArrayOutputStream();
            srv.write(new byte[]{0, 0, 0, 0}); // Prioritaet, Gewicht
            srv.write(servicePort >> 8);
            srv.write(servicePort & 0xFF);
            srv.write(nameBytes(hostName));
            writeRecord(records, instance, TYPE_SRV, CLASS_IN | CACHE_FLUSH, TTL_HOST, srv.toByteArray());
            writeRecord(records, instance, TYPE_TXT, CLASS_IN | CACHE_FLUSH, TTL_SERVICE, txt());
            count += 2;
        }
        if (a) {
            writeRecord(records, hostName, TYPE_A, CLASS_IN | CACHE_FLUSH, TTL_HOST, addr);
            count++;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, 0);       // ID
        writeU16(out, 0x8400);  // Antwort, autoritativ
        writeU16(out, 0);       // Fragen
        writeU16(out, count);   // Antworten
        writeU16(out, 0);
        writeU16(out, 0);
        records.writeTo(out);
        return out.toByteArray();
    }

    private byte[] txt() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String entry : new String[]{
                "device_id=" + config.deviceId(),
                "paired=" + (config.isPaired() ? "1" : "0"),
                "model=" + model,
                "app=" + appVersion}) {
            byte[] b = entry.getBytes("UTF-8");
            out.write(Math.min(b.length, 255));
            out.write(b, 0, Math.min(b.length, 255));
        }
        return out.toByteArray();
    }

    private void send(MulticastSocket s, byte[] packet) throws IOException {
        s.send(new DatagramPacket(packet, packet.length, InetAddress.getByName(GROUP), MDNS_PORT));
    }

    private static void writeRecord(ByteArrayOutputStream out, String name, int type, int cls, int ttl, byte[] rdata)
            throws IOException {
        out.write(nameBytes(name));
        writeU16(out, type);
        writeU16(out, cls);
        writeU16(out, ttl >>> 16);
        writeU16(out, ttl & 0xFFFF);
        writeU16(out, rdata.length);
        out.write(rdata);
    }

    /** "a.b.local" -> Labels ohne Kompression. Der Instanzname darf daher keinen Punkt enthalten. */
    private static byte[] nameBytes(String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String label : name.split("\\.")) {
            byte[] b = label.getBytes("UTF-8");
            out.write(b.length);
            out.write(b);
        }
        out.write(0);
        return out.toByteArray();
    }

    /** Liest einen (ggf. komprimierten) Namen, liefert die Position direkt dahinter. */
    private static int readName(byte[] d, int pos, int len, StringBuilder out) throws IOException {
        int end = -1;
        int jumps = 0;
        while (true) {
            if (pos >= len) throw new IOException("Name abgeschnitten");
            int l = d[pos] & 0xFF;
            if (l == 0) {
                pos++;
                break;
            }
            if ((l & 0xC0) == 0xC0) {
                if (pos + 1 >= len || ++jumps > 20) throw new IOException("Ungueltiger Zeiger");
                if (end < 0) end = pos + 2;
                pos = ((l & 0x3F) << 8) | (d[pos + 1] & 0xFF);
                continue;
            }
            if (pos + 1 + l > len) throw new IOException("Label abgeschnitten");
            if (out.length() > 0) out.append('.');
            out.append(new String(d, pos + 1, l, "UTF-8"));
            pos += 1 + l;
        }
        return end >= 0 ? end : pos;
    }

    private static int u16(byte[] d, int pos) {
        return ((d[pos] & 0xFF) << 8) | (d[pos + 1] & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
