package com.channellink.domain.mapping;

import com.channellink.domain.type.SupplierCode;

/**
 * 호텔 매핑
 * (supplierCode, supplierHotelCode) -> internalHotelId
 */
public record HotelMapping(SupplierCode supplierCode, String supplierHotelCode, String internalHotelId) {
}
