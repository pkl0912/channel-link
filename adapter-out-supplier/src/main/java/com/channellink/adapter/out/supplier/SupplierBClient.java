package com.channellink.adapter.out.supplier;

import com.channellink.domain.model.DailyInventory;
import com.channellink.domain.model.Hotel;
import com.channellink.domain.model.Money;
import com.channellink.domain.model.RoomType;
import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.model.StayOffer;
import com.channellink.domain.exception.SupplierCallException;
import com.channellink.domain.port.out.SupplierPort;
import com.channellink.domain.type.SupplierCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Supplier B 어댑터
 */
@Component
public class SupplierBClient implements SupplierPort, ReactiveSupplierSearch {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final String PROPERTIES_PATH = "/b/api/properties";
    private static final String SEARCH_PATH = "/b/api/search";
    private static final String SUCCESS_RESULT_CODE = "0000";

    private final WebClient webClient;
    private final String apiKey;

    public SupplierBClient(
            WebClient.Builder webClientBuilder,
            @Value("${channel-link.supplier.b.base-url}") String baseUrl,
            @Value("${channel-link.supplier.api-key}") String apiKey) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    @Override
    public SupplierCode supplierCode() {
        return SupplierCode.SUPPLIER_B;
    }

    // Supplier B의 숙소 목록을 조회
    @Override
    public HotelCatalog fetchHotelCatalog() {
        PropertiesData data = callAndUnwrap(webClient.get()
                .uri(PROPERTIES_PATH)
                .header(API_KEY_HEADER, apiKey)
                .retrieve()
                .bodyToMono(propertiesEnvelopeType()));

        List<Hotel> hotels = new ArrayList<>();
        List<RoomType> roomTypes = new ArrayList<>();
        for (PropertyItem item : data.items()) {
            hotels.add(new Hotel(SupplierCode.SUPPLIER_B, item.propertyId(), item.propertyName()));
            for (RoomItem room : item.rooms()) {
                roomTypes.add(new RoomType(
                        SupplierCode.SUPPLIER_B, item.propertyId(), room.roomId(), room.roomName(), room.maxOccupancy()));
            }
        }
        return new HotelCatalog(hotels, roomTypes);
    }

    // Supplier B의 재고·요금을 조회
    @Override
    @CircuitBreaker(name = "supplierB", fallbackMethod = "availabilityFallback")
    @Retry(name = "supplierB", fallbackMethod = "availabilityFallback")
    public Mono<AvailabilityResult> searchSupplierAvailability(List<String> hotelCodes, SearchCriteria criteria) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path(SEARCH_PATH)
                        .queryParam("propertyIds", String.join(",", hotelCodes))
                        .queryParam("checkIn", criteria.checkIn())
                        .queryParam("checkOut", criteria.checkOut())
                        .queryParam("adults", criteria.adults())
                        .queryParam("children", criteria.children())
                        .build())
                .header(API_KEY_HEADER, apiKey)
                .retrieve()
                .bodyToMono(searchEnvelopeType())
                .switchIfEmpty(Mono.error(new SupplierCallException(SupplierCode.SUPPLIER_B, "empty response body", true)))
                // B는 장애 상황에서도 HTTP 200을 준다 — resultCode를 직접 확인해야 실패를 안다.
                .flatMap(envelope -> SUCCESS_RESULT_CODE.equals(envelope.resultCode())
                        ? Mono.just(toAvailabilityResult(envelope.data()))
                        : Mono.error(new SupplierCallException(
                                SupplierCode.SUPPLIER_B,
                                "resultCode=" + envelope.resultCode() + " (" + envelope.resultMessage() + ")",
                                false)))
                // 재시도 대상 - 5xx
                .onErrorMap(WebClientResponseException.class, e -> new SupplierCallException(
                        SupplierCode.SUPPLIER_B,
                        "HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(),
                        e,
                        e.getStatusCode().is5xxServerError()))
                // 재시도 대상 - 타임아웃·커넥션 실패 등 일시적 오류
                .onErrorMap(
                        e -> !(e instanceof SupplierCallException),
                        e -> new SupplierCallException(SupplierCode.SUPPLIER_B, "call failed: " + e.getMessage(), e, true));
    }

    // 재시도가 소진됐거나 서킷이 OPEN이라 호출 자체가 막힌 경우 — 어떤 원인이든 SupplierCallException으로 통일
    private Mono<AvailabilityResult> availabilityFallback(List<String> hotelCodes, SearchCriteria criteria, Throwable t) {
        if (t instanceof SupplierCallException supplierCallException) {
            return Mono.error(supplierCallException);
        }
        return Mono.error(new SupplierCallException(SupplierCode.SUPPLIER_B, "circuit open or retries exhausted: " + t.getMessage(), t, false));
    }

    // B의 원본 응답을 표준 모델로 정규화
    private AvailabilityResult toAvailabilityResult(SearchData data) {
        List<DailyInventory> dailyInventories = new ArrayList<>();
        List<StayOffer> stayOffers = new ArrayList<>();
        for (RoomOfferItem item : data.items()) {
            for (InventoryItem inventory : item.inventory()) {
                dailyInventories.add(new DailyInventory(
                        SupplierCode.SUPPLIER_B,
                        item.propertyId(),
                        item.roomId(),
                        LocalDate.parse(inventory.date()),
                        inventory.remainingRooms()));
            }
            // totalPrice는 이미 세금 포함
            stayOffers.add(new StayOffer(
                    SupplierCode.SUPPLIER_B,
                    item.propertyId(),
                    item.propertyName(),
                    item.roomId(),
                    item.roomName(),
                    item.maxOccupancy(),
                    new Money(item.totalPrice(), item.currency()),
                    item.breakfastIncluded()));
        }
        return new AvailabilityResult(dailyInventories, stayOffers);
    }

    // resultCode를 열어보고 SupplierCallException으로 통일
    private <T> T callAndUnwrap(Mono<EnvelopeB<T>> mono) {
        EnvelopeB<T> envelope;
        try {
            envelope = mono.block();
        } catch (WebClientResponseException e) {
            throw new SupplierCallException(
                    SupplierCode.SUPPLIER_B,
                    "HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(),
                    e,
                    e.getStatusCode().is5xxServerError());
        } catch (RuntimeException e) {
            throw new SupplierCallException(SupplierCode.SUPPLIER_B, "call failed: " + e.getMessage(), e, true);
        }

        if (envelope == null) {
            throw new SupplierCallException(SupplierCode.SUPPLIER_B, "empty response body", true);
        }
        if (!SUCCESS_RESULT_CODE.equals(envelope.resultCode())) {
            throw new SupplierCallException(
                    SupplierCode.SUPPLIER_B,
                    "resultCode=" + envelope.resultCode() + " (" + envelope.resultMessage() + ")",
                    false);
        }
        return envelope.data();
    }

    private static org.springframework.core.ParameterizedTypeReference<EnvelopeB<PropertiesData>> propertiesEnvelopeType() {
        return new org.springframework.core.ParameterizedTypeReference<>() {
        };
    }

    private static org.springframework.core.ParameterizedTypeReference<EnvelopeB<SearchData>> searchEnvelopeType() {
        return new org.springframework.core.ParameterizedTypeReference<>() {
        };
    }

    private record EnvelopeB<T>(String resultCode, String resultMessage, T data) {
    }

    private record PropertiesData(List<PropertyItem> items) {
    }

    private record PropertyItem(String propertyId, String propertyName, List<RoomItem> rooms) {
    }

    private record RoomItem(String roomId, String roomName, int maxOccupancy) {
    }

    private record SearchData(List<RoomOfferItem> items) {
    }

    private record RoomOfferItem(
            String propertyId,
            String propertyName,
            String roomId,
            String roomName,
            int maxOccupancy,
            boolean breakfastIncluded,
            String currency,
            long totalPrice,
            boolean taxIncluded,
            List<InventoryItem> inventory) {
    }

    private record InventoryItem(String date, int remainingRooms) {
    }
}
