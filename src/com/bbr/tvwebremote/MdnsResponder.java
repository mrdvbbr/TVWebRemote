package com.bbr.tvwebremote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

public class MdnsResponder {
    private static final String TAG = "MdnsResponder";
    private static final String MDNS_GROUP = "224.0.0.251";
    private static final int MDNS_PORT = 5353;
    private static final int HTTP_PORT = 8080;

    private static final Set<String> SUPPORTED_HOSTNAMES = new HashSet<>(Arrays.asList(
            "tv.local",
            "pult.local",
            "tvbox.local"
    ));

    private final Context context;
    private WifiManager.MulticastLock multicastLock;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener nsdListener;
    private volatile boolean running = false;
    private Thread workerThread;
    private MulticastSocket socket;

    public MdnsResponder(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        // 1. Acquire Android MulticastLock
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("TVWebRemote::MdnsLock");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                Log.i(TAG, "MulticastLock acquired");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire MulticastLock: " + e.getMessage());
        }

        // 2. Register Android NsdManager service (_http._tcp)
        registerNsdService();

        // 3. Start custom mDNS UDP responder for tv.local & pult.local
        workerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runResponderLoop();
            }
        }, "tv-mdns-responder");
        workerThread.setDaemon(true);
        workerThread.start();
        Log.i(TAG, "mDNS responder started for tv.local and pult.local");
    }

    public synchronized void stop() {
        running = false;
        unregisterNsdService();

        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {}
            socket = null;
        }

        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }

        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
                Log.i(TAG, "MulticastLock released");
            } catch (Exception ignored) {}
            multicastLock = null;
        }
    }

    private void registerNsdService() {
        try {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;

            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName("TV Pult");
            serviceInfo.setServiceType("_http._tcp.");
            serviceInfo.setPort(HTTP_PORT);

            nsdListener = new NsdManager.RegistrationListener() {
                @Override
                public void onServiceRegistered(NsdServiceInfo nsdServiceInfo) {
                    Log.i(TAG, "NSD service registered: " + nsdServiceInfo.getServiceName());
                }

                @Override
                public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "NSD registration failed: code " + errorCode);
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo arg0) {
                    Log.i(TAG, "NSD service unregistered");
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "NSD unregistration failed: code " + errorCode);
                }
            };

            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception e) {
            Log.w(TAG, "Error registering NSD service: " + e.getMessage());
        }
    }

    private void unregisterNsdService() {
        if (nsdManager != null && nsdListener != null) {
            try {
                nsdManager.unregisterService(nsdListener);
            } catch (Exception ignored) {}
            nsdListener = null;
            nsdManager = null;
        }
    }

    private void runResponderLoop() {
        byte[] buffer = new byte[1500];
        InetAddress groupAddr = null;

        while (running) {
            try {
                groupAddr = InetAddress.getByName(MDNS_GROUP);
                socket = new MulticastSocket(MDNS_PORT);
                socket.setReuseAddress(true);
                socket.setTimeToLive(255);
                socket.joinGroup(groupAddr);

                Log.i(TAG, "Bound to 224.0.0.251:5353, sending announcement...");
                sendGratuitousAnnouncement(socket, groupAddr);

                while (running) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    handlePacket(socket, groupAddr, packet);
                }
            } catch (IOException e) {
                if (!running) break;
                Log.w(TAG, "mDNS socket loop exception, retrying in 3s: " + e.getMessage());
                try {
                    if (socket != null && groupAddr != null) {
                        try { socket.leaveGroup(groupAddr); } catch (Exception ignored) {}
                        try { socket.close(); } catch (Exception ignored) {}
                    }
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    private void handlePacket(MulticastSocket sock, InetAddress groupAddr, DatagramPacket packet) {
        byte[] data = packet.getData();
        int len = packet.getLength();
        if (len < 12) return;

        // Flags
        int flags = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        boolean isQuery = (flags & 0x8000) == 0;
        if (!isQuery) return; // Ignore response packets

        int qdCount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        if (qdCount <= 0) return;

        int[] offsetRef = new int[]{12};
        for (int q = 0; q < qdCount && q < 10; q++) {
            if (offsetRef[0] >= len) break;
            String queryName = parseDnsName(data, offsetRef, len);
            if (offsetRef[0] + 4 > len) break;

            int qType = ((data[offsetRef[0]] & 0xFF) << 8) | (data[offsetRef[0] + 1] & 0xFF);
            int qClass = ((data[offsetRef[0] + 2] & 0xFF) << 8) | (data[offsetRef[0] + 3] & 0xFF);
            offsetRef[0] += 4;

            if (SUPPORTED_HOSTNAMES.contains(queryName)) {
                // A query (1), AAAA query (28), or ANY query (255)
                if (qType == 1 || qType == 28 || qType == 255) {
                    byte[] ip = getActiveIpv4Bytes();
                    if (ip != null) {
                        byte[] response = buildAResponse(queryName, ip);
                        if (response != null) {
                            sendResponse(sock, groupAddr, packet, response);
                        }
                    }
                }
            } else if ("_http._tcp.local".equals(queryName)) {
                // DNS-SD PTR query
                if (qType == 12 || qType == 255) {
                    byte[] ip = getActiveIpv4Bytes();
                    if (ip != null) {
                        byte[] response = buildDnsSdResponse("tv.local", ip);
                        if (response != null) {
                            sendResponse(sock, groupAddr, packet, response);
                        }
                    }
                }
            }
        }
    }

    private void sendResponse(MulticastSocket sock, InetAddress groupAddr, DatagramPacket queryPacket, byte[] resp) {
        try {
            // Multicast response to group 224.0.0.251:5353
            DatagramPacket mcast = new DatagramPacket(resp, resp.length, groupAddr, MDNS_PORT);
            sock.send(mcast);

            // Unicast response directly to query sender
            if (queryPacket != null && queryPacket.getAddress() != null) {
                DatagramPacket ucast = new DatagramPacket(resp, resp.length, queryPacket.getAddress(), queryPacket.getPort());
                sock.send(ucast);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error sending mDNS response: " + e.getMessage());
        }
    }

    private void sendGratuitousAnnouncement(MulticastSocket sock, InetAddress groupAddr) {
        try {
            byte[] ip = getActiveIpv4Bytes();
            if (ip == null) return;
            byte[] resp1 = buildAResponse("tv.local", ip);
            if (resp1 != null) {
                sock.send(new DatagramPacket(resp1, resp1.length, groupAddr, MDNS_PORT));
            }
            byte[] resp2 = buildAResponse("pult.local", ip);
            if (resp2 != null) {
                sock.send(new DatagramPacket(resp2, resp2.length, groupAddr, MDNS_PORT));
            }
        } catch (Exception ignored) {}
    }

    private static String parseDnsName(byte[] buf, int[] offsetRef, int maxLen) {
        StringBuilder sb = new StringBuilder();
        int idx = offsetRef[0];
        boolean jumped = false;
        int nextOffsetAfterFirstJump = -1;
        int jumps = 0;

        while (idx < maxLen && jumps < 10) {
            int len = buf[idx] & 0xFF;
            if (len == 0) {
                idx++;
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                if (idx + 1 >= maxLen) break;
                int ptr = ((len & 0x3F) << 8) | (buf[idx + 1] & 0xFF);
                if (!jumped) {
                    nextOffsetAfterFirstJump = idx + 2;
                    jumped = true;
                }
                idx = ptr;
                jumps++;
                continue;
            }
            idx++;
            if (idx + len > maxLen) break;
            if (sb.length() > 0) sb.append(".");
            for (int i = 0; i < len; i++) {
                sb.append((char) buf[idx + i]);
            }
            idx += len;
        }

        if (jumped) {
            offsetRef[0] = nextOffsetAfterFirstJump;
        } else {
            offsetRef[0] = idx;
        }

        return sb.toString().toLowerCase();
    }

    private static void writeDnsName(DataOutputStream dos, String name) throws IOException {
        String[] parts = name.split("\\.");
        for (String part : parts) {
            byte[] bytes = part.getBytes("UTF-8");
            dos.writeByte(bytes.length);
            dos.write(bytes);
        }
        dos.writeByte(0);
    }

    private static byte[] buildAResponse(String hostname, byte[] ipv4) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeShort(0);       // ID: 0 in mDNS responses
            dos.writeShort(0x8400);  // Flags: Response, Authoritative Answer
            dos.writeShort(0);       // Questions: 0
            dos.writeShort(2);       // Answers: 2 (A record + NSEC negative proof for AAAA)
            dos.writeShort(0);       // Authority RRs: 0
            dos.writeShort(0);       // Additional RRs: 0

            // 1. A Record
            writeDnsName(dos, hostname);
            dos.writeShort(1);       // Type A (1)
            dos.writeShort(0x8001);  // Class IN, Cache-flush bit (0x8001)
            dos.writeInt(120);       // TTL: 120 seconds
            dos.writeShort(4);       // Data length: 4 bytes
            dos.write(ipv4);

            // 2. NSEC Record (asserts only Type 1 exists for this host, eliminates IPv6 query delay)
            writeDnsName(dos, hostname);
            dos.writeShort(47);      // Type NSEC (47)
            dos.writeShort(0x8001);  // Class IN, Cache-flush bit
            dos.writeInt(120);       // TTL: 120 seconds

            ByteArrayOutputStream nsecData = new ByteArrayOutputStream();
            DataOutputStream nsecDos = new DataOutputStream(nsecData);
            writeDnsName(nsecDos, hostname); // Next domain name is self
            nsecDos.writeByte(0);            // Window block: 0 (types 0-255)
            nsecDos.writeByte(1);            // Bitmap length: 1 byte (types 0-7)
            nsecDos.writeByte(0x40);         // Bitmap: bit 1 set (Type 1 = A)

            byte[] nsecBytes = nsecData.toByteArray();
            dos.writeShort(nsecBytes.length);
            dos.write(nsecBytes);

            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] buildDnsSdResponse(String hostTarget, byte[] ipv4) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeShort(0);
            dos.writeShort(0x8400);
            dos.writeShort(0);
            dos.writeShort(3);       // PTR, SRV, TXT
            dos.writeShort(0);
            dos.writeShort(1);       // Additional: A record

            // 1. PTR record: _http._tcp.local -> TV Pult._http._tcp.local
            writeDnsName(dos, "_http._tcp.local");
            dos.writeShort(12);      // PTR
            dos.writeShort(0x0001);  // Class IN (shared record, no cache flush)
            dos.writeInt(120);
            ByteArrayOutputStream ptrData = new ByteArrayOutputStream();
            DataOutputStream ptrDos = new DataOutputStream(ptrData);
            writeDnsName(ptrDos, "TV Pult._http._tcp.local");
            byte[] ptrBytes = ptrData.toByteArray();
            dos.writeShort(ptrBytes.length);
            dos.write(ptrBytes);

            // 2. SRV record: TV Pult._http._tcp.local -> hostTarget:8080
            writeDnsName(dos, "TV Pult._http._tcp.local");
            dos.writeShort(33);      // SRV
            dos.writeShort(0x8001);  // Class IN, Cache-flush
            dos.writeInt(120);
            ByteArrayOutputStream srvData = new ByteArrayOutputStream();
            DataOutputStream srvDos = new DataOutputStream(srvData);
            srvDos.writeShort(0);    // Priority
            srvDos.writeShort(0);    // Weight
            srvDos.writeShort(HTTP_PORT); // Port 8080
            writeDnsName(srvDos, hostTarget);
            byte[] srvBytes = srvData.toByteArray();
            dos.writeShort(srvBytes.length);
            dos.write(srvBytes);

            // 3. TXT record: path=/
            writeDnsName(dos, "TV Pult._http._tcp.local");
            dos.writeShort(16);      // TXT
            dos.writeShort(0x8001);  // Class IN, Cache-flush
            dos.writeInt(120);
            byte[] txtAttr = "path=/".getBytes("UTF-8");
            dos.writeShort(txtAttr.length + 1);
            dos.writeByte(txtAttr.length);
            dos.write(txtAttr);

            // Additional: A record for hostTarget
            writeDnsName(dos, hostTarget);
            dos.writeShort(1);       // A
            dos.writeShort(0x8001);  // Class IN, Cache-flush
            dos.writeInt(120);
            dos.writeShort(4);
            dos.write(ipv4);

            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] getActiveIpv4Bytes() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
