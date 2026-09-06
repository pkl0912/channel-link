package com.channellink.domain.port.out;

import com.channellink.domain.mapping.HotelMapping;
import com.channellink.domain.mapping.RoomTypeMapping;
import com.channellink.domain.type.SupplierCode;

import java.util.List;

public interface HotelMappingPort {

    HotelMapping resolveOrCreateHotel(SupplierCode supplierCode, String supplierHotelCode);

    RoomTypeMapping resolveOrCreateRoomType(SupplierCode supplierCode, String supplierHotelCode, String supplierRoomTypeCode);

    // 이 공급사가 보유한 숙소 매핑 전체를 조회한다 — 검색 시 "어떤 숙소 코드를 물어볼지"의 출처
    List<HotelMapping> findAllHotelMappingBySupplier(SupplierCode supplierCode);

}
