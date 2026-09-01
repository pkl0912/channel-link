package com.channellink.domain.model;

import com.channellink.domain.type.SupplierCode;
/**
 * 객실 타입
 */
public record RoomType(
        SupplierCode supplierCode,
        String hotelCode,
        String roomTypeCode,
        String roomTypeName,
        int maxOccupancy) {
}
