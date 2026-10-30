package vn.edu.notary;

import java.security.KeyPair;

/** Ví demo: tên + cặp khóa. Trong hệ thống thật, người dùng ký bằng ví của họ (ví dụ MetaMask). */
record Wallet(String name, KeyPair keys) {
    String address() {
        return Crypto.address(keys.getPublic().getEncoded());
    }

    String publicKeyBase64() {
        return java.util.Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
    }
}
