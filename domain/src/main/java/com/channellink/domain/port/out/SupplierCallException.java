package com.channellink.domain.port.out;

import com.channellink.domain.type.SupplierCode;

/**
 * Supplier 연동 실패를 나타내는 통일된 예외
 */
public class SupplierCallException extends RuntimeException {

    private final SupplierCode supplierCode;

    public SupplierCallException(SupplierCode supplierCode, String message, Throwable cause) {
        super(message, cause);
        this.supplierCode = supplierCode;
    }

    public SupplierCallException(SupplierCode supplierCode, String message) {
        this(supplierCode, message, null);
    }

    public SupplierCode supplierCode() {
        return supplierCode;
    }
}
