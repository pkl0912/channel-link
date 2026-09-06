package com.channellink.application;

import com.channellink.domain.mapping.HotelMapping;
import com.channellink.domain.mapping.RoomTypeMapping;
import com.channellink.domain.model.DailyInventory;
import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.model.StayOffer;
import com.channellink.domain.port.in.SearchStayPort;
import com.channellink.domain.port.out.HotelMappingPort;
import com.channellink.domain.port.out.SupplierPort;
import com.channellink.domain.port.out.SupplierSearchPort;
import com.channellink.domain.type.SupplierCode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class SearchStayService implements SearchStayPort {

    private final List<SupplierPort> supplierPorts;
    private final HotelMappingPort mappingPort;
    private final SupplierSearchPort supplierSearchPort;


    // 통합검색
    @Override
    public SearchResult search(SearchCriteria criteria) {
        Map<SupplierCode, List<String>> hotelCodesBySupplier = new HashMap<>();

        // 매핑에서 공급사별 숙소 코드를 꺼낸다
        for (SupplierPort port : supplierPorts) {
            List<String> hotelCodes = mappingPort.findAllHotelMappingBySupplier(port.supplierCode()).stream()
                    .map(HotelMapping::supplierHotelCode)
                    .toList();
            hotelCodesBySupplier.put(port.supplierCode(), hotelCodes);
        }

        // 병렬 조회
        List<SupplierSearchPort.SupplierSearchOutcome> outcomes = supplierSearchPort.searchAll(hotelCodesBySupplier, criteria);

        // 결과 정규화
        List<StaySearchItem> items = outcomes.stream()
                .filter(outcome -> !outcome.failed())
                .flatMap(outcome -> normalize(outcome, criteria).stream())
                .toList();

        List<SupplierCode> failedSuppliers = outcomes.stream()
                .filter(SupplierSearchPort.SupplierSearchOutcome::failed)
                .map(SupplierSearchPort.SupplierSearchOutcome::supplierCode)
                .toList();

        return new SearchResult(items, failedSuppliers);
    }

    // 정규화: 공급사 하나의 원시 결과를 예약 가능 객실 수 계산 + 내부 식별자 치환까지 마친 최종 아이템으로 바꾼다
    private List<StaySearchItem> normalize(SupplierSearchPort.SupplierSearchOutcome outcome, SearchCriteria criteria) {
        List<LocalDate> requiredDates = datesBetween(criteria.checkIn(), criteria.checkOut());

        Map<RoomTypeKey, Map<LocalDate, Integer>> remainingByRoomAndDate = new HashMap<>();
        for (DailyInventory inventory : outcome.dailyInventories()) {
            remainingByRoomAndDate
                    .computeIfAbsent(RoomTypeKey.of(inventory), key -> new HashMap<>())
                    .put(inventory.date(), inventory.remainingRooms());
        }

        List<StaySearchItem> items = new ArrayList<>();
        for (StayOffer offer : outcome.stayOffers()) {

            Map<LocalDate, Integer> byDate = remainingByRoomAndDate.getOrDefault(RoomTypeKey.of(offer), Map.of());
            // 요청 기간 전체를 예약 가능한 객실 수 = 날짜별 잔여 객실 수의 최솟값 (하루라도 0이면 전체 기간 예약 불가)
            int availableRooms = requiredDates.stream().mapToInt(date -> byDate.getOrDefault(date, 0)).min().orElse(0);

            HotelMapping hotelMapping = mappingPort.resolveOrCreateHotel(offer.supplierCode(), offer.hotelCode());
            RoomTypeMapping roomTypeMapping =
                    mappingPort.resolveOrCreateRoomType(offer.supplierCode(), offer.hotelCode(), offer.roomTypeCode());

            items.add(new StaySearchItem(
                    hotelMapping.internalHotelId(),
                    offer.hotelName(),
                    roomTypeMapping.internalRoomTypeId(),
                    offer.roomTypeName(),
                    offer.maxOccupancy(),
                    availableRooms,
                    offer.supplierCode(),
                    offer.totalPrice(),
                    offer.breakfastIncluded()));
        }
        return items;
    }

    // 체크인부터 체크아웃 전날까지의 날짜 목록
    private static List<LocalDate> datesBetween(LocalDate checkIn, LocalDate checkOut) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = checkIn; date.isBefore(checkOut); date = date.plusDays(1)) {
            dates.add(date);
        }
        return dates;
    }

    private record RoomTypeKey(SupplierCode supplierCode, String hotelCode, String roomTypeCode) {
        static RoomTypeKey of(DailyInventory inventory) {
            return new RoomTypeKey(inventory.supplierCode(), inventory.hotelCode(), inventory.roomTypeCode());
        }

        static RoomTypeKey of(StayOffer offer) {
            return new RoomTypeKey(offer.supplierCode(), offer.hotelCode(), offer.roomTypeCode());
        }
    }
}
