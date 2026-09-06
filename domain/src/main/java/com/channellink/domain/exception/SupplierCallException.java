package com.channellink.domain.exception;

import com.channellink.domain.type.SupplierCode;

/**
 * Supplier 연동 실패를 나타내는 통일된 예외
 */
public class SupplierCallException extends RuntimeException {

    private final SupplierCode supplierCode;
    private final boolean retryable;

    public SupplierCallException(SupplierCode supplierCode, String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.supplierCode = supplierCode;
        this.retryable = retryable;
    }

    public SupplierCallException(SupplierCode supplierCode, String message, boolean retryable) {
        this(supplierCode, message, null, retryable);
    }

    public SupplierCode supplierCode() {
        return supplierCode;
    }

    // 타임아웃/커넥션 오류/5xx처럼 일시적일 수 있는 실패만 true — 4xx나 공급사의 명백한 비즈니스 실패는 false
    public boolean retryable() {
        return retryable;
    }
}
