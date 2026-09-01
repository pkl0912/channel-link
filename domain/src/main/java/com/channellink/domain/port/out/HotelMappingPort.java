package com.channellink.domain.port.out;

import com.channellink.domain.mapping.HotelMapping;
import com.channellink.domain.mapping.RoomTypeMapping;
import com.channellink.domain.type.SupplierCode;

public interface HotelMappingPort {

    HotelMapping resolveOrCreateHotel(SupplierCode supplierCode, String supplierHotelCode);

    RoomTypeMapping resolveOrCreateRoomType(SupplierCode supplierCode, String supplierHotelCode, String supplierRoomTypeCode);

}
