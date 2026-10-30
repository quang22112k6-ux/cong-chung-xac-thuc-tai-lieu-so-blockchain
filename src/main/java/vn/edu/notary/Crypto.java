package vn.edu.notary;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Các hàm mật mã dùng JDK có sẵn: SHA-256, khóa EC (secp256r1), chữ ký ECDSA. */
final class Crypto {
    private Crypto() {}

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String s) {
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    static boolean isSha256Hex(String s) {
        return s != null && s.matches("[0-9a-f]{64}");
    }

    static KeyPair newKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Địa chỉ ví = 0x + 20 byte cuối của SHA-256(khóa công khai), tương tự ý tưởng địa chỉ Ethereum. */
    static String address(byte[] publicKeyEncoded) {
        String h = sha256Hex(publicKeyEncoded);
        return "0x" + h.substring(h.length() - 40);
    }

    static String sign(PrivateKey sk, String msg) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(sk);
            s.update(msg.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean verify(String pubB64, String msg, String sigB64) {
        try {
            PublicKey pk = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pk);
            s.update(msg.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(sigB64));
        } catch (Exception e) {
            return false;
        }
    }
}
