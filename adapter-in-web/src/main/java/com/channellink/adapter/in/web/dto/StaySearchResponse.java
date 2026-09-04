package com.channellink.adapter.in.web.dto;

import com.channellink.domain.port.in.SearchStayPort;
import com.channellink.domain.type.SupplierCode;

import java.util.List;

public record StaySearchResponse(
    List<StayOfferItem> items,
    List<SupplierCode> failedSuppliers
) {

    public static StaySearchResponse from(SearchStayPort.SearchResult result) {
        List<StayOfferItem> items = result.items().stream().map(StayOfferItem::from).toList();
        return new StaySearchResponse(items, result.failedSuppliers());
    }

    public record StayOfferItem(
            String hotelId,
            String hotelName,
            String roomTypeId,
            String roomTypeName,
            int maxOccupancy,
            int availableRooms,
            SupplierCode supplier,
            long price,
            String currency,
            boolean breakfastIncluded) {

        public static StayOfferItem from(SearchStayPort.StaySearchItem item) {
            return new StayOfferItem(
                    item.internalHotelId(),
                    item.hotelName(),
                    item.internalRoomTypeId(),
                    item.roomTypeName(),
                    item.maxOccupancy(),
                    item.availableRooms(),
                    item.sourceSupplier(),
                    item.price().amount(),
                    item.price().currency(),
                    item.breakfastIncluded());
        }
    }
}
