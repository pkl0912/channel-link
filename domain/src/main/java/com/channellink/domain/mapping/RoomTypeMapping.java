package com.channellink.domain.mapping;

import com.channellink.domain.type.SupplierCode;

/**
 * 객실 타입 매핑
 * (supplierCode, supplierHotelCode, supplierRoomTypeCode) -> internalRoomTypeId
 */
public record RoomTypeMapping(SupplierCode supplierCode, String supplierHotelCode, String supplierRoomTypeCode,
							  String internalRoomTypeId) {
}
