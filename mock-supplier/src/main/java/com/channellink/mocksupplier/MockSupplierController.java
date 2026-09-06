package com.channellink.mocksupplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class MockSupplierController {

    private final Map<String, String> modes = new ConcurrentHashMap<>();
    // "flaky" 모드에서 요청마다 독립적으로 실패할 확률(%) — 재시도 횟수(max-attempts) 검증용
    private final Map<String, Integer> flakyRates = new ConcurrentHashMap<>();

    @PostMapping("/control/{supplier}/mode")
    public Map<String, String> setMode(@PathVariable String supplier, @RequestParam String value) {
        modes.put(supplier, value);
        return Map.of(supplier, value);
    }

    @PostMapping("/control/{supplier}/flaky-rate")
    public Map<String, Integer> setFlakyRate(@PathVariable String supplier, @RequestParam int value) {
        flakyRates.put(supplier, value);
        return Map.of(supplier, value);
    }

    // "flaky" 모드일 때만 의미 있음 — 요청마다 독립적으로 flakyRate% 확률로 실패
    private boolean shouldFail(String supplier) {
        int rate = flakyRates.getOrDefault(supplier, 50);
        return ThreadLocalRandom.current().nextInt(100) < rate;
    }

    // Supplier A
    @GetMapping("/a/v1/hotels")
    public HotelsResponseA hotelsA() {
        return new HotelsResponseA(List.of(
                new HotelA("A-2001", "Harborview Suites", List.of(
                        new RoomTypeA("DLX-KNG", "Deluxe King", 2))),
                new HotelA("A-2002", "Cedar Peak Lodge", List.of(
                        new RoomTypeA("STD-TWN", "Standard Twin", 2)))));
    }

    @GetMapping("/a/v1/availability")
    public ResponseEntity<?> availabilityA(@RequestParam(required = false) String hotelCodes) {
        return switch (modes.getOrDefault("a", "normal")) {
            case "error" -> errorResponseA();
            case "no-response" -> sleepForever();
            case "flaky" -> shouldFail("a") ? errorResponseA() : ResponseEntity.ok(normalAvailabilityResponseA());
            default -> ResponseEntity.ok(normalAvailabilityResponseA());
        };
    }

    private ResponseEntity<ErrorResponseA> errorResponseA() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponseA("SERVICE_UNAVAILABLE", "temporarily unavailable"));
    }

    private AvailabilityResponseA normalAvailabilityResponseA() {
        return new AvailabilityResponseA(List.of(
                new RoomOfferA(
                        "A-2001", "Harborview Suites", "DLX-KNG", "Deluxe King", 2, false, "KRW",
                        List.of(
                                new DailyRateA("2026-09-01", 3, 130000, 13000),
                                new DailyRateA("2026-09-02", 1, 150000, 15000),
                                new DailyRateA("2026-09-03", 5, 130000, 13000))),
                new RoomOfferA(
                        "A-2002", "Cedar Peak Lodge", "STD-TWN", "Standard Twin", 2, false, "KRW",
                        List.of(
                                new DailyRateA("2026-09-01", 2, 90000, 9000),
                                new DailyRateA("2026-09-02", 0, 95000, 9500),
                                new DailyRateA("2026-09-03", 4, 90000, 9000)))));
    }

    // Supplier B
    @GetMapping("/b/api/properties")
    public EnvelopeB<PropertiesDataB> propertiesB() {
        return EnvelopeB.success(new PropertiesDataB(List.of(
                new PropertyB("B-9001", "Harborview Suites", List.of(
                        new RoomB("RM-11", "Deluxe King Room", 2))))));
    }

    @GetMapping("/b/api/search")
    public ResponseEntity<EnvelopeB<SearchDataB>> searchB(@RequestParam(required = false) String propertyIds) {
        // B는 장애 상황에서도 HTTP 200을 준다 — resultCode로만 실패를 알린다.
        return switch (modes.getOrDefault("b", "normal")) {
            case "error" -> ResponseEntity.ok(EnvelopeB.failure("E503", "TEMPORARILY_UNAVAILABLE"));
            case "no-response" -> sleepForever();
            case "flaky" -> ResponseEntity.ok(shouldFail("b")
                    ? EnvelopeB.failure("E503", "TEMPORARILY_UNAVAILABLE")
                    : EnvelopeB.success(normalSearchDataB()));
            default -> ResponseEntity.ok(EnvelopeB.success(normalSearchDataB()));
        };
    }

    private SearchDataB normalSearchDataB() {
        return new SearchDataB(List.of(
                new RoomOfferB(
                        "B-9001", "Harborview Suites", "RM-11", "Deluxe King Room", 2, true, "KRW",
                        447000, true,
                        List.of(
                                new InventoryB("2026-09-01", 3),
                                new InventoryB("2026-09-02", 1),
                                new InventoryB("2026-09-03", 5)))));
    }

    // 타임아웃 테스트용
    @SuppressWarnings("unchecked")
    private <T> T sleepForever() {
        try {
            Thread.sleep(600_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return (T) ResponseEntity.ok().build();
    }

    // Supplier A DTOs
    private record HotelsResponseA(List<HotelA> items) {
    }

    private record HotelA(String hotelCode, String hotelName, List<RoomTypeA> roomTypes) {
    }

    private record RoomTypeA(String roomTypeCode, String roomTypeName, int maxOccupancy) {
    }

    private record AvailabilityResponseA(List<RoomOfferA> items) {
    }

    private record RoomOfferA(
            String hotelCode,
            String hotelName,
            String roomTypeCode,
            String roomTypeName,
            int maxOccupancy,
            boolean breakfastIncluded,
            String currency,
            List<DailyRateA> dailyRates) {
    }

    private record DailyRateA(String date, int remainingRooms, int nightlyRate, int taxAmount) {
    }

    private record ErrorResponseA(String error, String message) {
    }

    // Supplier B DTOs
    private record EnvelopeB<T>(String resultCode, String resultMessage, T data) {
        static <T> EnvelopeB<T> success(T data) {
            return new EnvelopeB<>("0000", "SUCCESS", data);
        }

        static <T> EnvelopeB<T> failure(String resultCode, String resultMessage) {
            return new EnvelopeB<>(resultCode, resultMessage, null);
        }
    }

    private record PropertiesDataB(List<PropertyB> items) {
    }

    private record PropertyB(String propertyId, String propertyName, List<RoomB> rooms) {
    }

    private record RoomB(String roomId, String roomName, int maxOccupancy) {
    }

    private record SearchDataB(List<RoomOfferB> items) {
    }

    private record RoomOfferB(
            String propertyId,
            String propertyName,
            String roomId,
            String roomName,
            int maxOccupancy,
            boolean breakfastIncluded,
            String currency,
            long totalPrice,
            boolean taxIncluded,
            List<InventoryB> inventory) {
    }

    private record InventoryB(String date, int remainingRooms) {
    }
}
