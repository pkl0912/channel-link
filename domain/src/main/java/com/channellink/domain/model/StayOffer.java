package com.channellink.domain.model;

import com.channellink.domain.type.SupplierCode;

/**
 * 기타 제공 정보
 */
public record StayOffer(
        SupplierCode supplierCode,
        String hotelCode,
        String hotelName,
        String roomTypeCode,
        String roomTypeName,
        int maxOccupancy,
        Money totalPrice,
        boolean breakfastIncluded) {
}
