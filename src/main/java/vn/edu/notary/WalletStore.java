package vn.edu.notary;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.regex.Pattern;

/** Lưu ví demo dưới dạng file trong thư mục data/wallets. */
final class WalletStore {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private final Path dir;

    WalletStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    synchronized Wallet create(String name) {
        if (name == null || !NAME.matcher(name).matches())
            throw new IllegalArgumentException("Tên ví chỉ gồm chữ, số, '-' hoặc '_' (tối đa 32 ký tự)");
        Path f = dir.resolve(name + ".key");
        if (Files.exists(f)) throw new IllegalStateException("Ví đã tồn tại: " + name);
        KeyPair kp = Crypto.newKeyPair();
        String content = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()) + "\n"
                + Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()) + "\n";
        try {
            Files.writeString(f, content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new Wallet(name, kp);
    }

    synchronized Optional<Wallet> get(String name) {
        if (name == null || !NAME.matcher(name).matches()) return Optional.empty();
        Path f = dir.resolve(name + ".key");
        if (!Files.exists(f)) return Optional.empty();
        try {
            List<String> lines = Files.readAllLines(f);
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(lines.get(0))));
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(lines.get(1))));
            return Optional.of(new Wallet(name, new KeyPair(pub, priv)));
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Không đọc được ví " + name, e);
        }
    }

    synchronized List<Wallet> list() {
        List<Wallet> out = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".key")).sorted().forEach(p -> {
                String n = p.getFileName().toString();
                get(n.substring(0, n.length() - 4)).ifPresent(out::add);
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
