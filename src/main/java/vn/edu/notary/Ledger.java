package vn.edu.notary;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Sổ cái dạng chuỗi khối cục bộ (permissioned hash chain).
 * - Mỗi khối chứa hash khối trước, nên sửa một khối cũ sẽ làm hỏng toàn bộ chuỗi phía sau.
 * - Mỗi bản ghi được ký bằng khóa riêng của chủ sở hữu; chỉ chủ sở hữu mới thêm được phiên bản mới.
 * - Chỉ lưu hash của file, không lưu file gốc.
 */
final class Ledger {
    record DocSummary(String docId, String owner, String fileName, int latestVersion, long createdAt) {}

    private final Path file;
    private final List<Block> chain = new ArrayList<>();
    private final Map<String, Block> byHash = new HashMap<>();

    Ledger(Path file) {
        this.file = file;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) chain.add(decode(line));
                }
            }
            if (chain.isEmpty()) {
                append(genesis());
            } else {
                for (Block b : chain) if (!b.isGenesis()) byHash.putIfAbsent(b.fileHash(), b);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được sổ cái: " + file, e);
        }
    }

    // ---------- Nghiệp vụ ----------

    synchronized Block register(String fileHash, String fileName, long size, Wallet w, String note) {
        requireHash(fileHash);
        Block existing = byHash.get(fileHash);
        if (existing != null)
            throw new NotaryException(409, "Hash này đã được đăng ký (docId " + existing.docId()
                    + ", phiên bản " + existing.version() + ")");
        long ts = System.currentTimeMillis();
        String docId = "doc-" + Crypto.sha256Hex(fileHash + "|" + w.address() + "|" + ts).substring(0, 12);
        return seal(docId, 1, fileHash, fileName, size, w, note, ts);
    }

    synchronized Block addVersion(String docId, String newHash, String fileName, long size, Wallet w, String note) {
        requireHash(newHash);
        List<Block> h = history(docId);
        if (h.isEmpty()) throw new NotaryException(404, "Không tìm thấy tài liệu " + docId);
        if (!h.get(0).owner().equals(w.address()))
            throw new NotaryException(403, "Chỉ chủ sở hữu (" + h.get(0).owner() + ") mới được thêm phiên bản");
        Block existing = byHash.get(newHash);
        if (existing != null)
            throw new NotaryException(409, "Hash này đã được đăng ký (docId " + existing.docId()
                    + ", phiên bản " + existing.version() + ")");
        int next = h.get(h.size() - 1).version() + 1;
        return seal(docId, next, newHash, fileName, size, w, note, System.currentTimeMillis());
    }

    synchronized Optional<Block> findByHash(String hash) {
        return Optional.ofNullable(byHash.get(hash));
    }

    synchronized List<Block> history(String docId) {
        List<Block> out = new ArrayList<>();
        for (Block b : chain) if (!b.isGenesis() && b.docId().equals(docId)) out.add(b);
        return out;
    }

    synchronized List<DocSummary> docs() {
        Map<String, DocSummary> m = new LinkedHashMap<>();
        for (Block b : chain) {
            if (b.isGenesis()) continue;
            DocSummary old = m.get(b.docId());
            m.put(b.docId(), new DocSummary(b.docId(), b.owner(), b.fileName(), b.version(),
                    old == null ? b.timestamp() : old.createdAt()));
        }
        return new ArrayList<>(m.values());
    }

    synchronized List<Block> blocks() {
        return List.copyOf(chain);
    }

    /** Trả về null nếu chuỗi hợp lệ, ngược lại là mô tả lỗi đầu tiên tìm thấy. */
    synchronized String validate() {
        Set<String> seenHashes = new HashSet<>();
        Map<String, String> docOwner = new HashMap<>();
        Map<String, Integer> docVersion = new HashMap<>();
        for (int i = 0; i < chain.size(); i++) {
            Block b = chain.get(i);
            if (b.index() != i) return "Khối #" + i + ": sai số thứ tự";
            if (!b.hash().equals(b.computeHash())) return "Khối #" + i + ": hash không khớp nội dung (đã bị sửa)";
            if (i == 0) {
                if (!b.prevHash().equals(Block.ZERO)) return "Khối #0: prevHash không hợp lệ";
                continue;
            }
            if (!b.prevHash().equals(chain.get(i - 1).hash())) return "Khối #" + i + ": đứt liên kết với khối trước";
            if (!Crypto.isSha256Hex(b.fileHash())) return "Khối #" + i + ": fileHash không hợp lệ";
            if (!seenHashes.add(b.fileHash())) return "Khối #" + i + ": trùng fileHash";
            if (!Crypto.address(Base64.getDecoder().decode(b.ownerPub())).equals(b.owner()))
                return "Khối #" + i + ": địa chỉ ví không khớp khóa công khai";
            if (!Crypto.verify(b.ownerPub(), Block.signedMessage(b.docId(), b.version(), b.fileHash(), b.note()),
                    b.signature()))
                return "Khối #" + i + ": chữ ký chủ sở hữu không hợp lệ";
            String o = docOwner.putIfAbsent(b.docId(), b.owner());
            if (o != null && !o.equals(b.owner())) return "Khối #" + i + ": phiên bản do người không phải chủ sở hữu thêm";
            int expected = docVersion.getOrDefault(b.docId(), 0) + 1;
            if (b.version() != expected) return "Khối #" + i + ": số phiên bản không liên tục";
            docVersion.put(b.docId(), expected);
        }
        return null;
    }

    // ---------- Nội bộ ----------

    private Block seal(String docId, int version, String fileHash, String fileName, long size,
                       Wallet w, String note, long ts) {
        String cleanNote = clean(note, 200);
        String sig = Crypto.sign(w.keys().getPrivate(), Block.signedMessage(docId, version, fileHash, cleanNote));
        Block prev = chain.get(chain.size() - 1);
        Block draft = new Block(chain.size(), ts, docId, version, fileHash, clean(fileName, 255), Math.max(0, size),
                w.address(), w.publicKeyBase64(), cleanNote, sig, prev.hash(), "");
        Block b = new Block(draft.index(), draft.timestamp(), draft.docId(), draft.version(), draft.fileHash(),
                draft.fileName(), draft.fileSize(), draft.owner(), draft.ownerPub(), draft.note(),
                draft.signature(), draft.prevHash(), draft.computeHash());
        append(b);
        byHash.put(b.fileHash(), b);
        return b;
    }

    private static Block genesis() {
        Block d = new Block(0, 0L, "GENESIS", 0, Block.ZERO, "genesis", 0, "-", "-", "Khối khởi tạo", "-",
                Block.ZERO, "");
        return new Block(0, 0L, "GENESIS", 0, Block.ZERO, "genesis", 0, "-", "-", "Khối khởi tạo", "-",
                Block.ZERO, d.computeHash());
    }

    private static void requireHash(String h) {
        if (!Crypto.isSha256Hex(h))
            throw new NotaryException(400, "Hash phải là chuỗi SHA-256 gồm 64 ký tự hex viết thường");
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\p{Cntrl}", " ").strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private void append(Block b) {
        chain.add(b);
        try {
            Files.writeString(file, encode(b) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            chain.remove(chain.size() - 1);
            throw new NotaryException(500, "Không ghi được sổ cái: " + e.getMessage());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String encode(Block b) {
        return String.join("\t", String.valueOf(b.index()), String.valueOf(b.timestamp()), enc(b.docId()),
                String.valueOf(b.version()), b.fileHash(), enc(b.fileName()), String.valueOf(b.fileSize()),
                b.owner(), b.ownerPub(), enc(b.note()), b.signature(), b.prevHash(), b.hash());
    }

    private static Block decode(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 13) throw new IllegalStateException("Dòng sổ cái sai định dạng");
        return new Block(Integer.parseInt(f[0]), Long.parseLong(f[1]), dec(f[2]), Integer.parseInt(f[3]), f[4],
                dec(f[5]), Long.parseLong(f[6]), f[7], f[8], dec(f[9]), f[10], f[11], f[12]);
    }
}
