package vn.edu.notary;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bộ kiểm thử không cần thư viện ngoài. Chạy: java -cp out vn.edu.notary.SelfTest */
public final class SelfTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  [OK]   " + name); }
        else { failed++; System.out.println("  [FAIL] " + name); }
    }

    static int statusOf(Runnable r) {
        try { r.run(); return 200; } catch (NotaryException e) { return e.status; }
    }

    static String h(String s) { return Crypto.sha256Hex(s); }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("notary-test");
        System.out.println("1) Hash SHA-256");
        check("SHA-256('abc') đúng vector chuẩn NIST",
                Crypto.sha256Hex("abc").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        check("Đổi 1 ký tự -> hash khác hoàn toàn", !h("hop dong A").equals(h("hop dong B")));

        System.out.println("2) Đăng ký, owner, timestamp");
        WalletStore ws = new WalletStore(tmp.resolve("wallets"));
        Wallet alice = ws.create("alice"), bob = ws.create("bob");
        Ledger l = new Ledger(tmp.resolve("ledger.tsv"));
        long before = System.currentTimeMillis();
        Block v1 = l.register(h("v1"), "hop-dong.pdf", 1234, alice, "bản gốc");
        long after = System.currentTimeMillis();
        check("Đăng ký thành công, phiên bản 1", v1.version() == 1);
        check("Owner là địa chỉ ví alice", v1.owner().equals(alice.address()));
        check("Timestamp nằm trong khoảng thời gian gọi", v1.timestamp() >= before && v1.timestamp() <= after);
        check("Đăng ký trùng hash bị từ chối (409)", statusOf(() -> l.register(h("v1"), "x", 1, bob, "")) == 409);
        check("Hash sai định dạng bị từ chối (400)", statusOf(() -> l.register("abc", "x", 1, alice, "")) == 400);

        System.out.println("3) Lịch sử phiên bản");
        Block v2 = l.addVersion(v1.docId(), h("v2"), "hop-dong.pdf", 1300, alice, "sửa điều khoản");
        Block v3 = l.addVersion(v1.docId(), h("v3"), "hop-dong.pdf", 1310, alice, "bản ký cuối");
        check("Phiên bản tăng dần 2, 3", v2.version() == 2 && v3.version() == 3);
        check("Người khác không được thêm phiên bản (403)",
                statusOf(() -> l.addVersion(v1.docId(), h("v4"), "x", 1, bob, "")) == 403);
        check("Tài liệu không tồn tại (404)", statusOf(() -> l.addVersion("doc-xxx", h("v5"), "x", 1, alice, "")) == 404);
        check("Lịch sử có đủ 3 phiên bản, bản cũ còn nguyên", l.history(v1.docId()).size() == 3
                && l.history(v1.docId()).get(0).fileHash().equals(h("v1")));

        System.out.println("4) Verify");
        check("Tìm thấy file theo hash", l.findByHash(h("v2")).isPresent());
        check("File lạ không có trong sổ cái", l.findByHash(h("khong-ton-tai")).isEmpty());
        check("Sổ cái hợp lệ", l.validate() == null);

        System.out.println("5) Lưu trữ và chống sửa dữ liệu");
        Ledger reloaded = new Ledger(tmp.resolve("ledger.tsv"));
        check("Đọc lại từ file giữ nguyên dữ liệu", reloaded.blocks().size() == l.blocks().size() && reloaded.validate() == null);
        Path f = tmp.resolve("ledger.tsv");
        List<String> lines = new ArrayList<>(Files.readAllLines(f));
        String[] cols = lines.get(2).split("\t", -1);
        cols[9] = "hacked";
        lines.set(2, String.join("\t", cols));
        Files.write(f, lines);
        String err = new Ledger(f).validate();
        check("Sửa một khối cũ bị phát hiện: " + err, err != null && err.contains("không khớp"));

        System.out.println("6) API HTTP + giao diện");
        Path d2 = Files.createTempDirectory("notary-http");
        WalletStore ws2 = new WalletStore(d2.resolve("wallets"));
        ws2.create("demo");
        Server s = new Server(new Ledger(d2.resolve("ledger.tsv")), ws2, "127.0.0.1", 0);
        s.start();
        try {
            String base = "http://127.0.0.1:" + s.port();
            HttpClient c = HttpClient.newHttpClient();
            String page = get(c, base + "/").body();
            check("Trang chủ có vùng kéo-thả", page.contains("id=\"drop\""));
            String hash = h("noi dung file that");
            var reg = post(c, base + "/api/register", "hash=" + hash + "&fileName=a.pdf&size=10&wallet=demo&note=ban+goc");
            check("POST /api/register trả 200", reg.statusCode() == 200 && reg.body().contains("\"version\":1"));
            var ver = get(c, base + "/api/verify?hash=" + hash);
            check("GET /api/verify tìm thấy", ver.body().contains("\"found\":true") && ver.body().contains("\"isLatest\":true"));
            var no = get(c, base + "/api/verify?hash=" + h("file bi sua"));
            check("Verify file đã sửa -> found:false", no.body().contains("\"found\":false"));
            var dup = post(c, base + "/api/register", "hash=" + hash + "&wallet=demo");
            check("Đăng ký trùng qua API -> 409", dup.statusCode() == 409);
            var chain = get(c, base + "/api/chain");
            check("GET /api/chain hợp lệ", chain.body().contains("\"valid\":true"));
        } finally {
            s.stop();
        }

        System.out.println("\nKết quả: " + passed + " đạt, " + failed + " lỗi");
        System.exit(failed > 0 ? 1 : 0);
    }

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(HttpClient c, String url, String form) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
