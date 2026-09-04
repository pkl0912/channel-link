package com.channellink.adapter.out.supplier;

import com.channellink.domain.model.DailyInventory;
import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.model.StayOffer;
import com.channellink.domain.exception.SupplierCallException;
import com.channellink.domain.port.out.SupplierPort;
import com.channellink.domain.port.out.SupplierSearchPort;
import com.channellink.domain.type.SupplierCode;
import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Component
@AllArgsConstructor
public class SupplierSearchPortAdapter implements SupplierSearchPort {

    private static final int MAX_HOTEL_CODES_PER_CALL = 50;

    private final List<ReactiveSupplierSearch> reactiveSearches;

    // 등록된 모든 공급사를 Flux로 동시에 호출하고, 결과를 한 번에 모아서 반환
    @Override
    public List<SupplierSearchOutcome> searchAll(Map<SupplierCode, List<String>> hotelCodesBySupplier, SearchCriteria criteria) {
        return Flux.fromIterable(reactiveSearches)
                .flatMap(client -> searchOneSupplier(
                        client, hotelCodesBySupplier.getOrDefault(client.supplierCode(), List.of()), criteria))
                .collectList()
                .block();
    }

    // 공급사 하나를 배치 단위로 순차 호출하고, 실패하면 SupplierSearchOutcome의 failed=true로 흡수
    private Mono<SupplierSearchOutcome> searchOneSupplier(ReactiveSupplierSearch client, List<String> hotelCodes, SearchCriteria criteria) {
        if (hotelCodes.isEmpty()) {
            return Mono.just(new SupplierSearchOutcome(client.supplierCode(), List.of(), List.of(), false));
        }

        // 배치 여러 개를 이 Supplier 안에서는 순차로 이어 붙인다 — 다른 Supplier와는 병렬
        return Flux.fromIterable(partition(hotelCodes, MAX_HOTEL_CODES_PER_CALL))
                .concatMap(batch -> client.searchSupplierAvailability(batch, criteria))
                .collectList()
                .map(batchResults -> merge(client.supplierCode(), batchResults))
                .onErrorResume(SupplierCallException.class,
                        e -> Mono.just(new SupplierSearchOutcome(client.supplierCode(), List.of(), List.of(), true)));
    }

    // 배치별로 따로 온 결과를 공급사 하나의 결과로 다시 합친다
    private SupplierSearchOutcome merge(SupplierCode supplierCode, List<SupplierPort.AvailabilityResult> batchResults) {
        List<DailyInventory> dailyInventories = new ArrayList<>();
        List<StayOffer> stayOffers = new ArrayList<>();
        for (SupplierPort.AvailabilityResult result : batchResults) {
            dailyInventories.addAll(result.dailyInventories());
            stayOffers.addAll(result.stayOffers());
        }
        return new SupplierSearchOutcome(supplierCode, dailyInventories, stayOffers, false);
    }

    // 숙소 코드 목록을 공급사 API 1회 호출 제한(50개)만큼씩 나눈다
    private static List<List<String>> partition(List<String> source, int size) {
        List<List<String>> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i += size) {
            result.add(source.subList(i, Math.min(i + size, source.size())));
        }
        return result;
    }
}
