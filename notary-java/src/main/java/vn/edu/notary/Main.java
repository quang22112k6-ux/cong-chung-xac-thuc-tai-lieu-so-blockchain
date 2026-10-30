package vn.edu.notary;

import java.nio.file.Path;

/**
 * Cách chạy:  java -cp out vn.edu.notary.Main [cổng]
 * Biến môi trường: PORT (mặc định 8080), HOST (mặc định 127.0.0.1), NOTARY_DATA (mặc định ./data)
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args.length > 0 ? args[0] : System.getenv().getOrDefault("PORT", "8080"));
        String host = System.getenv().getOrDefault("HOST", "127.0.0.1");
        Path data = Path.of(System.getenv().getOrDefault("NOTARY_DATA", "data"));

        WalletStore wallets = new WalletStore(data.resolve("wallets"));
        if (wallets.list().isEmpty()) wallets.create("demo");
        Ledger ledger = new Ledger(data.resolve("ledger.tsv"));
        String err = ledger.validate();

        Server server = new Server(ledger, wallets, host, port);
        server.start();
        System.out.println("Blockchain Notary đang chạy: http://" + (host.equals("0.0.0.0") ? "localhost" : host) + ":" + port);
        System.out.println("Dữ liệu: " + data.toAbsolutePath() + " | Sổ cái: " + (err == null ? "hợp lệ" : "LỖI: " + err));
        System.out.println("Nhấn Ctrl+C để dừng.");
    }
}
