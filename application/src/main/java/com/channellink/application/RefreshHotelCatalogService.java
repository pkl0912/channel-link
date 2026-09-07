package com.channellink.application;

import com.channellink.domain.exception.SupplierCallException;
import com.channellink.domain.model.Hotel;
import com.channellink.domain.model.RoomType;
import com.channellink.domain.port.in.RefreshHotelCatalogPort;
import com.channellink.domain.port.out.HotelMappingPort;
import com.channellink.domain.port.out.SupplierPort;
import com.channellink.domain.type.SupplierCode;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;

/**
 * 공급사 숙소 목록 조회 및 매핑
 */
@AllArgsConstructor
public class RefreshHotelCatalogService implements RefreshHotelCatalogPort {

    private final List<SupplierPort> supplierPorts;
    private final HotelMappingPort mappingPort;

    // 등록된 모든 공급사의 숙소 목록을 조회해서 숙소·객실 타입 매핑 테이블을 채운다.
    // 공급사 하나가 실패해도 예외를 그대로 던지지 않고 흡수
    @Override
    public List<SupplierCode> refresh() {
        List<SupplierCode> failedSuppliers = new ArrayList<>();
        for (SupplierPort client : supplierPorts) {
            try {
                SupplierPort.HotelCatalog catalog = client.fetchHotelCatalog();
                for (Hotel hotel : catalog.hotels()) {
                    mappingPort.resolveOrCreateHotel(hotel.supplierCode(), hotel.hotelCode());
                }
                for (RoomType roomType : catalog.roomTypes()) {
                    mappingPort.resolveOrCreateRoomType(roomType.supplierCode(), roomType.hotelCode(), roomType.roomTypeCode());
                }
            } catch (SupplierCallException e) {
                failedSuppliers.add(client.supplierCode());
            }
        }
        return failedSuppliers;
    }
}
