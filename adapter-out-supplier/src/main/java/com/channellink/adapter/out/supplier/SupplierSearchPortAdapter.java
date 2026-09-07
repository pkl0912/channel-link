package com.channellink.adapter.out.supplier;

import com.channellink.domain.model.DailyInventory;
import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.model.StayOffer;
import com.channellink.domain.exception.SupplierCallException;
import com.channellink.domain.port.out.SupplierPort;
import com.channellink.domain.port.out.SupplierSearchPort;
import com.channellink.domain.type.SupplierCode;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.AllArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;


@Component
@AllArgsConstructor
public class SupplierSearchPortAdapter implements SupplierSearchPort {

    private static final int MAX_HOTEL_CODES_PER_CALL = 50;

    // 요금/재고 캐시 TTL
    private static final Duration AVAILABILITY_CACHE_TTL = Duration.ofSeconds(45);

    private final List<ReactiveSupplierSearch> reactiveSearches;

    private final AsyncCache<AvailabilityCacheKey, SupplierPort.AvailabilityResult> availabilityCache =
            Caffeine.newBuilder()
                    .expireAfterWrite(AVAILABILITY_CACHE_TTL)
                    .maximumSize(10_000)
                    .recordStats()
                    .buildAsync();

    // 부하 테스트 전용
    public CacheStats availabilityCacheStats() {
        return availabilityCache.synchronous().stats();
    }

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

        // 배치 여러 개를 이 Supplier 안에서는 순차로 이어 붙인다
        return Flux.fromIterable(partition(hotelCodes, MAX_HOTEL_CODES_PER_CALL))
                .concatMap(batch -> searchBatchCached(client, batch, criteria))
                .collectList()
                .map(batchResults -> merge(client.supplierCode(), batchResults))
                .onErrorResume(SupplierCallException.class, //실패 시 실패한 공급사로 반환
                        e -> Mono.just(new SupplierSearchOutcome(client.supplierCode(), List.of(), List.of(), true)));
    }

    // 재고·요금 캐시 요청
    private Mono<SupplierPort.AvailabilityResult> searchBatchCached(
            ReactiveSupplierSearch client, List<String> batch, SearchCriteria criteria) {
        AvailabilityCacheKey key = new AvailabilityCacheKey(client.supplierCode(), batch, criteria);
        return Mono.fromFuture(() -> availabilityCache.get(
                        key, (k, executor) -> client.searchSupplierAvailability(batch, criteria).toFuture()))
                .onErrorMap(CompletionException.class, Throwable::getCause);
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

    // 재고·요금 캐시 키
    private record AvailabilityCacheKey(SupplierCode supplierCode, List<String> hotelCodes, SearchCriteria criteria) {
    }
}
