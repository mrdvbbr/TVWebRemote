package com.bbr.tvwebremote;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KeyDispatcher {
    private static final String TAG = "KeyDispatcher";
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    public static int nameToCode(String name) {
        if (name == null) return 0;
        switch (name.toLowerCase().trim()) {
            case "up": return 19;
            case "down": return 20;
            case "left": return 21;
            case "right": return 22;
            case "ok":
            case "center":
            case "select": return 23;
            case "enter": return 66;
            case "back": return 4;
            case "home": return 3;
            case "menu": return 82;
            case "volup":
            case "volume_up": return 24;
            case "voldown":
            case "volume_down": return 25;
            case "mute": return 164;
            case "power": return 26;
            case "playpause": return 85;
            default:
                try {
                    return Integer.parseInt(name);
                } catch (Exception e) {
                    return 0;
                }
        }
    }

    public static void sendKey(final int code) {
        if (code <= 0) return;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean sent = sendViaUdp(String.valueOf(code));
                if (!sent) {
                    sendViaAdb("input keyevent " + code);
                }
            }
        });
    }

    public static void tuneChannel(final Context context, final String channelNum) {
        if (channelNum == null || channelNum.isEmpty()) return;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // Ensure TelecomTV is running
                launchApp(context, "uz.telecom.telecomtv");

                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {}

                // Build sequence: 7 + digit for each character, then 66 (Enter)
                StringBuilder sb = new StringBuilder("SEQ:");
                for (int i = 0; i < channelNum.length(); i++) {
                    char c = channelNum.charAt(i);
                    if (Character.isDigit(c)) {
                        sb.append(7 + (c - '0')).append(" ");
                    }
                }
                sb.append("66");

                boolean sent = sendViaUdp(sb.toString());
                if (!sent) {
                    for (int i = 0; i < channelNum.length(); i++) {
                        char c = channelNum.charAt(i);
                        if (Character.isDigit(c)) {
                            sendViaAdb("input keyevent " + (7 + (c - '0')));
                            try { Thread.sleep(40); } catch (Exception ignored) {}
                        }
                    }
                    sendViaAdb("input keyevent 66");
                }
            }
        });
    }

    private static boolean sendViaUdp(String payload) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(50);
            byte[] data = payload.getBytes("UTF-8");
            DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), 7777);
            socket.send(packet);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    public static void launchApp(final Context context, final String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Native launch failed, falling back to ADB monkey: " + e.getMessage());
                }
                sendViaAdb("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
            }
        });
    }

    public static void ensureTvKeyDaemon(final Context context) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String cmd = "if ! pgrep tvkey >/dev/null 2>&1; then " +
                             "chmod 755 /data/local/tmp/tvkey 2>/dev/null; " +
                             "nohup /data/local/tmp/tvkey --daemon 7777 >/dev/null 2>&1 & " +
                             "fi";
                sendViaAdb(cmd);
            }
        });
    }

    public static void sendViaAdb(String command) {
        Socket socket = null;
        try {
            socket = new Socket("127.0.0.1", 6060);
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Send A_CNXN
            byte[] hostData = "host::\0".getBytes("UTF-8");
            sendPacket(out, 0x4e584e43, 0x01000000, 4096, hostData);

            // Read CNXN response
            byte[] header = new byte[24];
            int read = in.read(header);
            if (read < 24) return;

            ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int cmd = bb.getInt(0);
            int dataLen = bb.getInt(12);
            if (dataLen > 0) {
                byte[] junk = new byte[dataLen];
                in.read(junk);
            }

            // Send A_OPEN
            byte[] cmdData = ("shell:" + command + "\0").getBytes("UTF-8");
            sendPacket(out, 0x4e45504f, 1, 0, cmdData);

        } catch (Exception e) {
            try {
                Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", command});
            } catch (Exception ex) {
                Log.e(TAG, "Execution failed", ex);
            }
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void sendPacket(OutputStream out, int command, int arg0, int arg1, byte[] data) throws Exception {
        int length = (data != null) ? data.length : 0;
        int crc = 0;
        if (data != null) {
            for (byte b : data) {
                crc += (b & 0xFF);
            }
        }
        int magic = command ^ 0xFFFFFFFF;

        ByteBuffer buf = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(command);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(length);
        buf.putInt(crc);
        buf.putInt(magic);
        if (data != null) {
            buf.put(data);
        }

        out.write(buf.array());
        out.flush();
    }
}
