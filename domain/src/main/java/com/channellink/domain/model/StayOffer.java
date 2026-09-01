package com.channellink.domain.model;

import com.channellink.domain.type.SupplierCode;

/**
 * 요금 조건
 */
public record StayOffer(
        SupplierCode supplierCode,
        String hotelCode,
        String roomTypeCode,
        Money totalPrice,
        boolean breakfastIncluded) {
}
