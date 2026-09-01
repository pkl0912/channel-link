package com.channellink.domain.model;

import com.channellink.domain.type.SupplierCode;

/**
 * 호텔
 */
public record Hotel(SupplierCode supplierCode, String hotelCode, String hotelName) {
}
