package com.channellink.application;

import com.channellink.domain.mapping.HotelMapping;
import com.channellink.domain.mapping.RoomTypeMapping;
import com.channellink.domain.model.DailyInventory;
import com.channellink.domain.model.Money;
import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.model.StayOffer;
import com.channellink.domain.port.in.SearchStayPort.SearchResult;
import com.channellink.domain.port.in.SearchStayPort.StaySearchItem;
import com.channellink.domain.port.out.HotelMappingPort;
import com.channellink.domain.port.out.SupplierPort;
import com.channellink.domain.port.out.SupplierSearchPort;
import com.channellink.domain.port.out.SupplierSearchPort.SupplierSearchOutcome;
import com.channellink.domain.type.SupplierCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.channellink.domain.type.SupplierCode.SUPPLIER_A;
import static com.channellink.domain.type.SupplierCode.SUPPLIER_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

class SearchStayServiceTest {

    private static final SearchCriteria CRITERIA =
            new SearchCriteria(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4), 2, 0);

    @Mock
    private SupplierPort supplierAPort;
    @Mock
    private SupplierPort supplierBPort;
    @Mock
    private HotelMappingPort mappingPort;
    @Mock
    private SupplierSearchPort supplierSearchPort;

    private SearchStayService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(supplierAPort.supplierCode()).thenReturn(SUPPLIER_A);
        when(supplierBPort.supplierCode()).thenReturn(SUPPLIER_B);
        when(mappingPort.findAllHotelMappingBySupplier(any())).thenReturn(List.of());

        service = new SearchStayService(List.of(supplierAPort, supplierBPort), mappingPort, supplierSearchPort);

        when(mappingPort.resolveOrCreateHotel(any(), any()))
                .thenAnswer(inv -> new HotelMapping(inv.getArgument(0), inv.getArgument(1), "internal-hotel"));
        when(mappingPort.resolveOrCreateRoomType(any(), any(), any()))
                .thenAnswer(inv -> new RoomTypeMapping(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), "internal-room"));
    }

    @Test
    void 모든_날짜에_재고가_있으면_정규화된_아이템을_반환한다() {
        DailyInventory sep1 = inventory(SUPPLIER_A, LocalDate.of(2026, 9, 1), 2);
        DailyInventory sep2 = inventory(SUPPLIER_A, LocalDate.of(2026, 9, 2), 2);
        DailyInventory sep3 = inventory(SUPPLIER_A, LocalDate.of(2026, 9, 3), 2);
        StayOffer offer = offer(SUPPLIER_A);

        mockSearchAll(List.of(
                new SupplierSearchOutcome(SUPPLIER_A, List.of(sep1, sep2, sep3), List.of(offer), false),
                new SupplierSearchOutcome(SUPPLIER_B, List.of(), List.of(), false)));

        SearchResult result = service.search(CRITERIA);

        assertThat(result.failedSuppliers()).isEmpty();
        assertThat(result.items()).hasSize(1);
        StaySearchItem item = result.items().get(0);
        assertThat(item.availableRooms()).isEqualTo(2);
        assertThat(item.internalHotelId()).isEqualTo("internal-hotel");
        assertThat(item.internalRoomTypeId()).isEqualTo("internal-room");
        assertThat(item.sourceSupplier()).isEqualTo(SUPPLIER_A);
    }

    @Test
    void 기간_중_하루라도_재고가_0이면_예약가능객실수는_0이다() {
        DailyInventory sep1 = inventory(SUPPLIER_A, LocalDate.of(2026, 9, 1), 3);
        DailyInventory sep2 = inventory(SUPPLIER_A, LocalDate.of(2026, 9, 2), 0);
        DailyInventory sep3 = inventory(SUPPLIER_A, LocalDate.of(2026, 9, 3), 3);
        StayOffer offer = offer(SUPPLIER_A);

        mockSearchAll(List.of(
                new SupplierSearchOutcome(SUPPLIER_A, List.of(sep1, sep2, sep3), List.of(offer), false),
                new SupplierSearchOutcome(SUPPLIER_B, List.of(), List.of(), false)));

        SearchResult result = service.search(CRITERIA);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).availableRooms()).isZero();
    }

    @Test
    void 공급사_응답에_날짜가_누락되면_예약가능객실수는_0이다() {
        // 9/2 재고가 아예 응답에 없는 경우 — 없는 날짜는 0으로 취급해야 한다
        DailyInventory sep1 = inventory(SUPPLIER_A, LocalDate.of(2026, 9, 1), 3);
        DailyInventory sep3 = inventory(SUPPLIER_A, LocalDate.of(2026, 9, 3), 3);
        StayOffer offer = offer(SUPPLIER_A);

        mockSearchAll(List.of(
                new SupplierSearchOutcome(SUPPLIER_A, List.of(sep1, sep3), List.of(offer), false),
                new SupplierSearchOutcome(SUPPLIER_B, List.of(), List.of(), false)));

        SearchResult result = service.search(CRITERIA);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).availableRooms()).isZero();
    }

    @Test
    void 실패한_공급사는_결과에서_제외되고_failedSuppliers에_기록된다() {
        DailyInventory sep1 = inventory(SUPPLIER_A, LocalDate.of(2026, 9, 1), 1);
        DailyInventory sep2 = inventory(SUPPLIER_A, LocalDate.of(2026, 9, 2), 1);
        DailyInventory sep3 = inventory(SUPPLIER_A, LocalDate.of(2026, 9, 3), 1);
        StayOffer offerA = offer(SUPPLIER_A);

        mockSearchAll(List.of(
                new SupplierSearchOutcome(SUPPLIER_A, List.of(sep1, sep2, sep3), List.of(offerA), false),
                new SupplierSearchOutcome(SUPPLIER_B, List.of(), List.of(), true)));

        SearchResult result = service.search(CRITERIA);

        assertThat(result.failedSuppliers()).containsExactly(SUPPLIER_B);
        assertThat(result.items())
                .hasSize(1)
                .allMatch(item -> item.sourceSupplier() == SUPPLIER_A);
    }

    private void mockSearchAll(List<SupplierSearchOutcome> outcomes) {
        when(supplierSearchPort.searchAll(anyMap(), any())).thenReturn(outcomes);
    }

    private static DailyInventory inventory(SupplierCode supplierCode, LocalDate date, int remainingRooms) {
        return new DailyInventory(supplierCode, "HOTEL-1", "ROOM-1", date, remainingRooms);
    }

    private static StayOffer offer(SupplierCode supplierCode) {
        return new StayOffer(
                supplierCode, "HOTEL-1", "Harborview Suites", "ROOM-1", "Deluxe King",
                2, new Money(451_000, "KRW"), false);
    }
}
