package com.channellink.domain.port.out;

import com.channellink.domain.model.DailyInventory;
import com.channellink.domain.model.Hotel;
import com.channellink.domain.model.RoomType;
import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.model.StayOffer;
import com.channellink.domain.type.SupplierCode;

import java.util.List;

/**
 * Port — Supplier 연동 어댑터의 계약
 */
public interface SupplierClient {

    SupplierCode supplierCode();

    /** 숙소 목록 */
    HotelCatalog fetchHotelCatalog();

    /** 재고·요금 조회 */
    AvailabilityResult searchAvailability(List<String> hotelCodes, SearchCriteria criteria);

    record HotelCatalog(List<Hotel> hotels, List<RoomType> roomTypes) {
    }

    record AvailabilityResult(List<DailyInventory> dailyInventories, List<StayOffer> stayOffers) {
    }
}
