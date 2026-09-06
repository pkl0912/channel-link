package com.channellink.config;

import com.channellink.application.RefreshHotelCatalogService;
import com.channellink.application.SearchStayService;
import com.channellink.domain.port.in.RefreshHotelCatalogPort;
import com.channellink.domain.port.in.SearchStayPort;
import com.channellink.domain.port.out.HotelMappingPort;
import com.channellink.domain.port.out.SupplierPort;
import com.channellink.domain.port.out.SupplierSearchPort;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class UseCaseConfig {

    // 통합 검색 유스케이스를 조립
    @Bean
    public SearchStayPort searchStaysUseCase(List<SupplierPort> supplierPorts, HotelMappingPort mappingPort, SupplierSearchPort supplierSearchPort) {
        return new SearchStayService(supplierPorts, mappingPort, supplierSearchPort);
    }

    // 숙소 목록 갱신 유스케이스를 조립
    @Bean
    public RefreshHotelCatalogPort refreshHotelCatalogUseCase(List<SupplierPort> supplierPorts, HotelMappingPort mappingPort) {
        return new RefreshHotelCatalogService(supplierPorts, mappingPort);
    }

    /**
     * 숙소 목록 갱신 시점 — "기동 시 1회 매핑 테이블을 채운다"
     */
    @Bean
    public CommandLineRunner catalogWarmup(RefreshHotelCatalogPort refreshHotelCatalogUseCase) {
        return args -> refreshHotelCatalogUseCase.refresh();
    }
}
