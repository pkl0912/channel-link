package com.channellink.adapter.out.supplier;

import com.channellink.domain.model.DailyInventory;
import com.channellink.domain.model.Hotel;
import com.channellink.domain.model.Money;
import com.channellink.domain.model.RoomType;
import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.model.StayOffer;
import com.channellink.domain.port.out.SupplierCallException;
import com.channellink.domain.port.out.SupplierClient;
import com.channellink.domain.type.SupplierCode;
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
public class SupplierBClient implements SupplierClient {

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

    @Override
    public AvailabilityResult searchAvailability(List<String> hotelCodes, SearchCriteria criteria) {
        SearchData data = callAndUnwrap(webClient.get()
                .uri(uriBuilder -> uriBuilder.path(SEARCH_PATH)
                        .queryParam("propertyIds", String.join(",", hotelCodes))
                        .queryParam("checkIn", criteria.checkIn())
                        .queryParam("checkOut", criteria.checkOut())
                        .queryParam("adults", criteria.adults())
                        .queryParam("children", criteria.children())
                        .build())
                .header(API_KEY_HEADER, apiKey)
                .retrieve()
                .bodyToMono(searchEnvelopeType()));

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
                    item.roomId(),
                    new Money(item.totalPrice(), item.currency()),
                    item.breakfastIncluded()));
        }
        return new AvailabilityResult(dailyInventories, stayOffers);
    }

    /**
     * B는 실패해도 HTTP 200을 준다 -> resultCode 직접 확인해서 예외 throw
     */
    private <T> T callAndUnwrap(Mono<EnvelopeB<T>> mono) {
        EnvelopeB<T> envelope;
        try {
            envelope = mono.block();
        } catch (WebClientResponseException e) {
            throw new SupplierCallException(
                    SupplierCode.SUPPLIER_B,
                    "HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(),
                    e);
        } catch (RuntimeException e) {
            throw new SupplierCallException(SupplierCode.SUPPLIER_B, "call failed: " + e.getMessage(), e);
        }

        if (envelope == null) {
            throw new SupplierCallException(SupplierCode.SUPPLIER_B, "empty response body");
        }
        if (!SUCCESS_RESULT_CODE.equals(envelope.resultCode())) {
            throw new SupplierCallException(
                    SupplierCode.SUPPLIER_B,
                    "resultCode=" + envelope.resultCode() + " (" + envelope.resultMessage() + ")");
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
