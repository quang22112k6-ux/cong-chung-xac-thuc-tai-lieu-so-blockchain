package vn.edu.notary;

/**
 * Một khối trong sổ cái. Mỗi khối ghi một bản đăng ký (hoặc một phiên bản mới) của tài liệu.
 * Chỉ lưu HASH của file, không lưu nội dung file.
 */
record Block(
        int index,
        long timestamp,      // mili-giây (UTC) tại thời điểm khối được tạo
        String docId,        // định danh cố định của tài liệu
        int version,         // số phiên bản, bắt đầu từ 1
        String fileHash,     // SHA-256 của file (64 ký tự hex)
        String fileName,
        long fileSize,
        String owner,        // địa chỉ ví của chủ sở hữu
        String ownerPub,     // khóa công khai (Base64) để kiểm tra chữ ký
        String note,
        String signature,    // chữ ký ECDSA của chủ sở hữu trên nội dung bản ghi
        String prevHash,
        String hash) {

    static final String ZERO = "0".repeat(64);

    /** Nội dung mà chủ sở hữu ký. */
    static String signedMessage(String docId, int version, String fileHash, String note) {
        return String.join("\n", "NOTARY-V1", docId, String.valueOf(version), fileHash, note);
    }

    String computeHash() {
        return Crypto.sha256Hex(String.join("\n",
                String.valueOf(index), String.valueOf(timestamp), docId, String.valueOf(version),
                fileHash, fileName, String.valueOf(fileSize), owner, ownerPub, note, signature, prevHash));
    }

    boolean isGenesis() {
        return index == 0;
    }
}
