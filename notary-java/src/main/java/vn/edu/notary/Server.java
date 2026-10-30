package vn.edu.notary;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;

/** Máy chủ HTTP nhỏ (JDK có sẵn, không cần thư viện ngoài): giao diện web + REST API. */
final class Server {
    private final Ledger ledger;
    private final WalletStore wallets;
    private final HttpServer http;
    private final java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(8);

    Server(Ledger ledger, WalletStore wallets, String host, int port) throws IOException {
        this.ledger = ledger;
        this.wallets = wallets;
        this.http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.createContext("/", this::staticFile);
        http.createContext("/api/", this::api);
        http.setExecutor(pool);
    }

    void start() {
        http.start();
    }

    void stop() {
        http.stop(0);
        pool.shutdownNow();
    }

    int port() {
        return http.getAddress().getPort();
    }

    // ---------- Giao diện web ----------

    private void staticFile(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (!p.equals("/") && !p.equals("/index.html")) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        try (InputStream in = Server.class.getResourceAsStream("/web/index.html")) {
            byte[] data = in != null ? in.readAllBytes()
                    : Files.readAllBytes(Path.of("src/main/resources/web/index.html"));
            send(ex, 200, "text/html; charset=utf-8", data);
        }
    }

    // ---------- REST API ----------

    private void api(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = parseForm(ex.getRequestURI().getRawQuery());
            Map<String, String> body = method.equals("POST") ? parseForm(readBody(ex)) : Map.of();
            String json;
            switch (method + " " + path) {
                case "GET /api/wallets" -> json = walletsJson();
                case "POST /api/wallets" -> {
                    try {
                        wallets.create(body.get("name"));
                    } catch (IllegalArgumentException e) {
                        throw new NotaryException(400, e.getMessage());
                    } catch (IllegalStateException e) {
                        throw new NotaryException(409, e.getMessage());
                    }
                    json = walletsJson();
                }
                case "POST /api/register" -> {
                    Wallet w = wallet(body.get("wallet"));
                    Block b = ledger.register(body.get("hash"), body.getOrDefault("fileName", ""),
                            parseLong(body.get("size")), w, body.getOrDefault("note", ""));
                    json = "{\"ok\":true,\"block\":" + blockJson(b) + "}";
                }
                case "POST /api/version" -> {
                    Wallet w = wallet(body.get("wallet"));
                    Block b = ledger.addVersion(body.get("docId"), body.get("hash"),
                            body.getOrDefault("fileName", ""), parseLong(body.get("size")), w,
                            body.getOrDefault("note", ""));
                    json = "{\"ok\":true,\"block\":" + blockJson(b) + "}";
                }
                case "GET /api/verify" -> json = verifyJson(q.get("hash"));
                case "GET /api/doc" -> json = docJson(q.get("id"));
                case "GET /api/docs" -> json = docsJson();
                case "GET /api/chain" -> json = chainJson();
                default -> throw new NotaryException(404, "Không có API: " + method + " " + path);
            }
            send(ex, 200, "application/json; charset=utf-8", json);
        } catch (NotaryException e) {
            send(ex, e.status, "application/json; charset=utf-8", "{\"error\":" + str(e.getMessage()) + "}");
        } catch (Exception e) {
            send(ex, 500, "application/json; charset=utf-8", "{\"error\":" + str("Lỗi máy chủ: " + e) + "}");
        }
    }

    private Wallet wallet(String name) {
        return wallets.get(name).orElseThrow(() -> new NotaryException(400, "Chưa chọn ví hợp lệ"));
    }

    private String walletsJson() {
        StringBuilder sb = new StringBuilder("{\"wallets\":[");
        boolean first = true;
        for (Wallet w : wallets.list()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(str(w.name())).append(",\"address\":").append(str(w.address())).append('}');
        }
        return sb.append("]}").toString();
    }

    private String verifyJson(String hash) {
        if (!Crypto.isSha256Hex(hash))
            throw new NotaryException(400, "Hash phải là chuỗi SHA-256 gồm 64 ký tự hex viết thường");
        Optional<Block> found = ledger.findByHash(hash);
        if (found.isEmpty()) return "{\"found\":false}";
        Block b = found.get();
        List<Block> h = ledger.history(b.docId());
        int latest = h.get(h.size() - 1).version();
        return "{\"found\":true,\"isLatest\":" + (b.version() == latest) + ",\"latestVersion\":" + latest
                + ",\"block\":" + blockJson(b) + "}";
    }

    private String docJson(String id) {
        List<Block> h = ledger.history(id);
        if (h.isEmpty()) throw new NotaryException(404, "Không tìm thấy tài liệu " + id);
        StringBuilder sb = new StringBuilder("{\"docId\":").append(str(id)).append(",\"owner\":")
                .append(str(h.get(0).owner())).append(",\"versions\":[");
        for (int i = 0; i < h.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(blockJson(h.get(i)));
        }
        return sb.append("]}").toString();
    }

    private String docsJson() {
        StringBuilder sb = new StringBuilder("{\"docs\":[");
        boolean first = true;
        for (Ledger.DocSummary d : ledger.docs()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"docId\":").append(str(d.docId())).append(",\"owner\":").append(str(d.owner()))
                    .append(",\"fileName\":").append(str(d.fileName())).append(",\"latestVersion\":")
                    .append(d.latestVersion()).append(",\"createdAt\":").append(d.createdAt()).append('}');
        }
        return sb.append("]}").toString();
    }

    private String chainJson() {
        String err = ledger.validate();
        List<Block> bs = ledger.blocks();
        StringBuilder sb = new StringBuilder("{\"valid\":").append(err == null).append(",\"error\":")
                .append(err == null ? "null" : str(err)).append(",\"length\":").append(bs.size()).append(",\"blocks\":[");
        for (int i = 0; i < bs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(blockJson(bs.get(i)));
        }
        return sb.append("]}").toString();
    }

    private static String blockJson(Block b) {
        return "{\"index\":" + b.index() + ",\"timestamp\":" + b.timestamp() + ",\"docId\":" + str(b.docId())
                + ",\"version\":" + b.version() + ",\"fileHash\":" + str(b.fileHash()) + ",\"fileName\":"
                + str(b.fileName()) + ",\"fileSize\":" + b.fileSize() + ",\"owner\":" + str(b.owner())
                + ",\"note\":" + str(b.note()) + ",\"signature\":" + str(b.signature()) + ",\"prevHash\":"
                + str(b.prevHash()) + ",\"hash\":" + str(b.hash()) + "}";
    }

    // ---------- Tiện ích ----------

    static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(65_537);
            if (data.length > 65_536) throw new NotaryException(413, "Yêu cầu quá lớn (chỉ gửi hash, không gửi file)");
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    static Map<String, String> parseForm(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String part : s.split("&")) {
            int i = part.indexOf('=');
            String k = i < 0 ? part : part.substring(0, i);
            String v = i < 0 ? "" : part.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        send(ex, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, data.length);
        try (var out = ex.getResponseBody()) {
            out.write(data);
        }
    }
}
