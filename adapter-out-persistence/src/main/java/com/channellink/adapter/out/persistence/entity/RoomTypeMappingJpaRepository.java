package com.channellink.adapter.out.persistence.entity;

import com.channellink.domain.type.SupplierCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoomTypeMappingJpaRepository extends JpaRepository<RoomTypeMappingJpaEntity, String> {

    Optional<RoomTypeMappingJpaEntity> findBySupplierCodeAndHotelCodeAndRoomTypeCode(
            SupplierCode supplierCode, String hotelCode, String roomTypeCode);
}
