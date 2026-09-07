package com.channellink.domain.port.out;

import com.channellink.domain.model.DailyInventory;
import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.model.StayOffer;
import com.channellink.domain.type.SupplierCode;

import java.util.List;
import java.util.Map;

public interface SupplierSearchPort {

    List<SupplierSearchOutcome> searchAll(Map<SupplierCode, List<String>> hotelCodesBySupplier, SearchCriteria criteria);

    record SupplierSearchOutcome(
            SupplierCode supplierCode, List<DailyInventory> dailyInventories, List<StayOffer> stayOffers, boolean failed) {
    }
}
