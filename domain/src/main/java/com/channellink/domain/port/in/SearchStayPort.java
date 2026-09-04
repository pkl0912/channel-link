package com.channellink.domain.port.in;

import com.channellink.domain.model.Money;
import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.type.SupplierCode;

import java.util.List;

public interface SearchStayPort {

    SearchResult search(SearchCriteria criteria);

    record SearchResult(
        List<StaySearchItem> items,
        List<SupplierCode> failedSuppliers) {
    }

    record StaySearchItem(
            String internalHotelId,
            String hotelName,
            String internalRoomTypeId,
            String roomTypeName,
            int maxOccupancy,
            int availableRooms,
            SupplierCode sourceSupplier,
            Money price,
            boolean breakfastIncluded) {
    }
}
