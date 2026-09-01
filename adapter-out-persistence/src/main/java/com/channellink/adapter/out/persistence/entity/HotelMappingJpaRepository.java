package com.channellink.adapter.out.persistence.entity;

import com.channellink.domain.type.SupplierCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HotelMappingJpaRepository extends JpaRepository<HotelMappingJpaEntity, String> {

    Optional<HotelMappingJpaEntity> findBySupplierCodeAndHotelCode(SupplierCode supplierCode, String hotelCode);
}
