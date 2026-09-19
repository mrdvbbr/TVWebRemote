package com.bbr.tvwebremote;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpServer {
    private static final String TAG = "HttpServer";
    private final Context context;
    private final int port;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private byte[] cachedIndexHtml;
    private byte[] cachedIconPng;

    public HttpServer(Context context, int port) {
        this.context = context;
        this.port = port;
        loadAssets();
    }

    private void loadAssets() {
        try (InputStream is = context.getAssets().open("index.html");
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            cachedIndexHtml = bos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load index.html from assets", e);
            cachedIndexHtml = "<html><body><h1>TV Remote</h1></body></html>".getBytes();
        }

        try (InputStream is = context.getAssets().open("icon.png");
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            cachedIconPng = bos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "Failed to load icon.png from assets: " + e.getMessage());
            cachedIconPng = new byte[0];
        }
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(port);
                    Log.i(TAG, "HttpServer listening on port " + port);
                    while (isRunning && !serverSocket.isClosed()) {
                        final Socket client = serverSocket.accept();
                        threadPool.execute(new Runnable() {
                            @Override
                            public void run() {
                                handleClient(client);
                            }
                        });
                    }
                } catch (Exception e) {
                    if (isRunning) {
                        Log.e(TAG, "Server error: " + e.getMessage());
                    }
                }
            }
        });
    }

    public synchronized void stop() {
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    private void handleClient(Socket socket) {
        try (InputStream is = socket.getInputStream();
             OutputStream os = socket.getOutputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String method = parts[0];
            String uri = parts[1];

            // Read headers
            String headerLine;
            int contentLength = 0;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                if (headerLine.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(headerLine.substring(15).trim());
                    } catch (Exception ignored) {}
                }
            }

            // Read body if present
            String body = "";
            if (contentLength > 0 && contentLength < 8192) {
                char[] bodyChars = new char[contentLength];
                reader.read(bodyChars, 0, contentLength);
                body = new String(bodyChars);
            }

            // Parse URL and Query Parameters
            String path = uri;
            Map<String, String> queryParams = new HashMap<>();
            int qIdx = uri.indexOf('?');
            if (qIdx != -1) {
                path = uri.substring(0, qIdx);
                String qStr = uri.substring(qIdx + 1);
                String[] pairs = qStr.split("&");
                for (String p : pairs) {
                    String[] kv = p.split("=");
                    if (kv.length == 2) {
                        queryParams.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
                    } else if (kv.length == 1) {
                        queryParams.put(URLDecoder.decode(kv[0], "UTF-8"), "");
                    }
                }
            }

            // Route Requests
            if (path.equals("/") || path.equals("/index.html")) {
                sendResponse(os, 200, "text/html; charset=utf-8", cachedIndexHtml);
            } else if (path.equals("/icon.png") || path.equals("/apple-touch-icon.png")
                    || path.equals("/apple-touch-icon-precomposed.png") || path.equals("/favicon.ico")) {
                sendResponse(os, 200, "image/png", cachedIconPng);
            } else if (path.equals("/api/key")) {
                String keyName = queryParams.get("name");
                if (keyName == null && !body.isEmpty()) {
                    keyName = body;
                }
                int code = KeyDispatcher.nameToCode(keyName);
                if (code > 0) {
                    KeyDispatcher.sendKey(code);
                    sendResponse(os, 200, "application/json", "{\"success\":true}".getBytes("UTF-8"));
                } else {
                    sendResponse(os, 400, "application/json", "{\"error\":\"Unknown key\"}".getBytes("UTF-8"));
                }
            } else if (path.equals("/api/tune")) {
                String num = queryParams.get("num");
                if (num == null && !body.isEmpty()) {
                    num = body;
                }
                if (num != null && !num.isEmpty()) {
                    KeyDispatcher.tuneChannel(context, num);
                    sendResponse(os, 200, "application/json", "{\"success\":true}".getBytes("UTF-8"));
                } else {
                    sendResponse(os, 400, "application/json", "{\"error\":\"Missing channel number\"}".getBytes("UTF-8"));
                }
            } else if (path.equals("/api/launch")) {
                String pkg = queryParams.get("pkg");
                if (pkg == null) pkg = queryParams.get("app");
                if (pkg != null && !pkg.isEmpty()) {
                    KeyDispatcher.launchApp(context, pkg);
                    sendResponse(os, 200, "application/json", "{\"success\":true}".getBytes("UTF-8"));
                } else {
                    sendResponse(os, 400, "application/json", "{\"error\":\"Missing pkg\"}".getBytes("UTF-8"));
                }
            } else if (path.equals("/api/status")) {
                sendResponse(os, 200, "application/json", "{\"status\":\"running\",\"port\":8080}".getBytes("UTF-8"));
            } else {
                sendResponse(os, 404, "text/plain", "Not Found".getBytes("UTF-8"));
            }

        } catch (Exception e) {
            Log.e(TAG, "Client error: " + e.getMessage());
        }
    }

    private void sendResponse(OutputStream os, int statusCode, String contentType, byte[] data) throws Exception {
        String statusText = (statusCode == 200) ? "OK" : (statusCode == 404) ? "Not Found" : "Bad Request";
        String header = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: public, max-age=86400\r\n" +
                "Connection: close\r\n\r\n";
        os.write(header.getBytes("UTF-8"));
        os.write(data);
        os.flush();
    }
}
