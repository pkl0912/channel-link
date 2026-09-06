package com.channellink.domain.port.out;

import com.channellink.domain.model.DailyInventory;
import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.model.StayOffer;
import com.channellink.domain.type.SupplierCode;

import java.util.List;
import java.util.Map;

public interface SupplierSearchPort {

    // 공급사별 숙소 코드 목록을 받아 모든 공급사를 동시에 조회하고, 성공/실패를 한데 모아 반환한다
    List<SupplierSearchOutcome> searchAll(Map<SupplierCode, List<String>> hotelCodesBySupplier, SearchCriteria criteria);

    record SupplierSearchOutcome(
            SupplierCode supplierCode, List<DailyInventory> dailyInventories, List<StayOffer> stayOffers, boolean failed) {
    }
}
