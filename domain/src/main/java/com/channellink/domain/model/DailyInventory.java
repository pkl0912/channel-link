package com.channellink.domain.model;

import java.time.LocalDate;

import com.channellink.domain.type.SupplierCode;

/**
 * 재고
 */
public record DailyInventory(
        SupplierCode supplierCode,
        String hotelCode,
        String roomTypeCode,
        LocalDate date,
        int remainingRooms) {
}
