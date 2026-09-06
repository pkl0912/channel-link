package com.channellink.debug;

import com.channellink.adapter.out.supplier.SupplierSearchPortAdapter;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 부하 테스트 전용 진단 엔드포인트.
 */
@RestController
public class CacheStatsController {

    private static final String HOTEL_MAPPINGS_CACHE = "hotelMappingsBySupplier";

    private final SupplierSearchPortAdapter supplierSearchPortAdapter;
    private final CacheManager cacheManager;

    public CacheStatsController(SupplierSearchPortAdapter supplierSearchPortAdapter, CacheManager cacheManager) {
        this.supplierSearchPortAdapter = supplierSearchPortAdapter;
        this.cacheManager = cacheManager;
    }

    @GetMapping("/internal/cache-stats")
    public Map<String, Object> stats() {
        return Map.of(
                "availabilityCache", toMap(supplierSearchPortAdapter.availabilityCacheStats()),
                "hotelMappingsCache", toMap(hotelMappingsCacheStats()));
    }

    @SuppressWarnings("unchecked")
    private CacheStats hotelMappingsCacheStats() {
        Cache cache = cacheManager.getCache(HOTEL_MAPPINGS_CACHE);
        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
                (com.github.benmanes.caffeine.cache.Cache<Object, Object>) ((CaffeineCache) cache).getNativeCache();
        return nativeCache.stats();
    }

    private Map<String, Object> toMap(CacheStats stats) {
        return Map.of(
                "requestCount", stats.requestCount(),
                "hitCount", stats.hitCount(),
                "missCount", stats.missCount(),
                "hitRate", stats.hitRate(),
                "evictionCount", stats.evictionCount());
    }
}
