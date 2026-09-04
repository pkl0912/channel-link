package com.channellink.domain.port.out;

import com.channellink.domain.model.DailyInventory;
import com.channellink.domain.model.Hotel;
import com.channellink.domain.model.RoomType;
import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.model.StayOffer;
import com.channellink.domain.type.SupplierCode;

import java.util.List;


public interface SupplierPort {

    // 이 어댑터가 담당하는 공급사가 어디인지
    SupplierCode supplierCode();

    /** 숙소 목록 */
    HotelCatalog fetchHotelCatalog();

    record HotelCatalog(
        List<Hotel> hotels,
        List<RoomType> roomTypes) {
    }

    record AvailabilityResult(
        List<DailyInventory> dailyInventories,
        List<StayOffer> stayOffers) {
    }
}
