package com.channellink.adapter.out.supplier;

import com.channellink.domain.exception.SupplierCallException;

import java.util.function.Predicate;

// resilience4j retry-exception-predicate로 등록
// SupplierCallException 중 retryable=true인 것만 재시도
public class RetryableSupplierExceptionPredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof SupplierCallException supplierCallException) {
            return supplierCallException.retryable();
        }
        return true;
    }
}
