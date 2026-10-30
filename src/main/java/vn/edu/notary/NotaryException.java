package vn.edu.notary;

/** Lỗi nghiệp vụ kèm mã trạng thái HTTP. */
final class NotaryException extends RuntimeException {
    final int status;

    NotaryException(int status, String message) {
        super(message);
        this.status = status;
    }
}
