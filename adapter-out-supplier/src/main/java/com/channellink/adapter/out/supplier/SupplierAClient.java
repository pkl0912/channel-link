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
 * Supplier A 어댑터
 */
@Component
public class SupplierAClient implements SupplierPort, ReactiveSupplierSearch {

	private static final String API_KEY_HEADER = "X-Api-Key";
	private static final String HOTELS_PATH = "/a/v1/hotels";
	private static final String AVAILABILITY_PATH = "/a/v1/availability";

	private final WebClient webClient;
	private final String apiKey;

	public SupplierAClient(
		WebClient.Builder webClientBuilder,
		@Value("${channel-link.supplier.a.base-url}") String baseUrl,
		@Value("${channel-link.supplier.api-key}") String apiKey) {
		this.webClient = webClientBuilder.baseUrl(baseUrl).build();
		this.apiKey = apiKey;
	}

	@Override
	public SupplierCode supplierCode() {
		return SupplierCode.SUPPLIER_A;
	}

	// Supplier A의 숙소 목록을 조회
	@Override
	public HotelCatalog fetchHotelCatalog() {
		HotelsResponse response = callAndBlock(webClient.get()
			.uri(HOTELS_PATH)
			.header(API_KEY_HEADER, apiKey)
			.retrieve()
			.bodyToMono(HotelsResponse.class));

		List<Hotel> hotels = new ArrayList<>();
		List<RoomType> roomTypes = new ArrayList<>();
		for (HotelItem item : response.items()) {
			hotels.add(new Hotel(SupplierCode.SUPPLIER_A, item.hotelCode(), item.hotelName()));
			for (RoomTypeItem roomType : item.roomTypes()) {
				roomTypes.add(new RoomType(
					SupplierCode.SUPPLIER_A,
					item.hotelCode(),
					roomType.roomTypeCode(),
					roomType.roomTypeName(),
					roomType.maxOccupancy()));
			}
		}
		return new HotelCatalog(hotels, roomTypes);
	}


	// Supplier A의 재고·요금을 조회
	@Override
	@CircuitBreaker(name = "supplierA", fallbackMethod = "availabilityFallback")
	@Retry(name = "supplierA", fallbackMethod = "availabilityFallback")
	public Mono<AvailabilityResult> searchSupplierAvailability(List<String> hotelCodes, SearchCriteria criteria) {
		return webClient.get()
			.uri(uriBuilder -> uriBuilder.path(AVAILABILITY_PATH)
				.queryParam("hotelCodes", String.join(",", hotelCodes))
				.queryParam("checkIn", criteria.checkIn())
				.queryParam("checkOut", criteria.checkOut())
				.queryParam("adults", criteria.adults())
				.queryParam("children", criteria.children())
				.build())
			.header(API_KEY_HEADER, apiKey)
			.retrieve()
			.bodyToMono(AvailabilityResponse.class)
			.switchIfEmpty(Mono.error(new SupplierCallException(SupplierCode.SUPPLIER_A, "empty response body", true)))
			.map(this::toAvailabilityResult)
			// 재시도 대상 - 5xx
			.onErrorMap(WebClientResponseException.class, e -> new SupplierCallException(
				SupplierCode.SUPPLIER_A,
				"HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(),
				e,
				e.getStatusCode().is5xxServerError()))
			// 재시도 대상 - 타임아웃·커넥션 실패 등 일시적 오류
			.onErrorMap(
				e -> !(e instanceof SupplierCallException),
				e -> new SupplierCallException(SupplierCode.SUPPLIER_A, "call failed: " + e.getMessage(), e, true));
	}

	// 재시도가 소진됐거나 서킷이 OPEN이라 호출 자체가 막힌 경우 — 어떤 원인이든 SupplierCallException으로 통일
	private Mono<AvailabilityResult> availabilityFallback(List<String> hotelCodes, SearchCriteria criteria, Throwable t) {
		if (t instanceof SupplierCallException supplierCallException) {
			return Mono.error(supplierCallException);
		}
		return Mono.error(new SupplierCallException(SupplierCode.SUPPLIER_A, "circuit open or retries exhausted: " + t.getMessage(), t, false));
	}

	// A의 원본 응답을 표준 모델로 정규화
	private AvailabilityResult toAvailabilityResult(AvailabilityResponse response) {
		List<DailyInventory> dailyInventories = new ArrayList<>();
		List<StayOffer> stayOffers = new ArrayList<>();
		for (RoomOfferItem item : response.items()) {
			long totalPrice = 0;
			for (DailyRateItem dailyRate : item.dailyRates()) {
				dailyInventories.add(new DailyInventory(
					SupplierCode.SUPPLIER_A,
					item.hotelCode(),
					item.roomTypeCode(),
					LocalDate.parse(dailyRate.date()),
					dailyRate.remainingRooms()));
				// nightlyRate는 세금 별도
				totalPrice += dailyRate.nightlyRate() + dailyRate.taxAmount();
			}
			stayOffers.add(new StayOffer(
				SupplierCode.SUPPLIER_A,
				item.hotelCode(),
				item.hotelName(),
				item.roomTypeCode(),
				item.roomTypeName(),
				item.maxOccupancy(),
				new Money(totalPrice, item.currency()),
				item.breakfastIncluded()));
		}
		return new AvailabilityResult(dailyInventories, stayOffers);
	}

	// HTTP 상태 코드 실패를 SupplierCallException으로 통일
	private <T> T callAndBlock(Mono<T> mono) {
		try {
			T result = mono.block();
			if (result == null) {
				throw new SupplierCallException(SupplierCode.SUPPLIER_A, "empty response body", true);
			}
			return result;
		} catch (WebClientResponseException e) {
			throw new SupplierCallException(
				SupplierCode.SUPPLIER_A,
				"HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(),
				e,
				e.getStatusCode().is5xxServerError());
		} catch (RuntimeException e) {
			throw new SupplierCallException(SupplierCode.SUPPLIER_A, "call failed: " + e.getMessage(), e, true);
		}
	}

	private record HotelsResponse(List<HotelItem> items) {
	}

	private record HotelItem(
		String hotelCode,
		String hotelName,
		List<RoomTypeItem> roomTypes
	) {
	}

	private record RoomTypeItem(
		String roomTypeCode,
		String roomTypeName,
		int maxOccupancy
	) {
	}

	private record AvailabilityResponse
		(List<RoomOfferItem> items
		) {
	}

	private record RoomOfferItem(
		String hotelCode,
		String hotelName,
		String roomTypeCode,
		String roomTypeName,
		int maxOccupancy,
		boolean breakfastIncluded,
		String currency,
		List<DailyRateItem> dailyRates) {
	}

	private record DailyRateItem(
        String date,
        int remainingRooms,
        int nightlyRate,
        int taxAmount) {
	}
}
