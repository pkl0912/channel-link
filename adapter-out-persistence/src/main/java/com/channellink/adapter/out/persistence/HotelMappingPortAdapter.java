package com.channellink.adapter.out.persistence;

import com.channellink.adapter.out.persistence.entity.HotelMappingJpaEntity;
import com.channellink.adapter.out.persistence.entity.HotelMappingJpaRepository;
import com.channellink.adapter.out.persistence.entity.RoomTypeMappingJpaEntity;
import com.channellink.adapter.out.persistence.entity.RoomTypeMappingJpaRepository;
import com.channellink.domain.mapping.HotelMapping;
import com.channellink.domain.mapping.RoomTypeMapping;
import com.channellink.domain.port.out.HotelMappingPort;
import com.channellink.domain.type.SupplierCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import lombok.AllArgsConstructor;

@Component
@AllArgsConstructor
public class HotelMappingPortAdapter implements HotelMappingPort {

    private final HotelMappingJpaRepository hotelMappingJpaRepository;
    private final RoomTypeMappingJpaRepository roomTypeMappingJpaRepository;

    @Override
    @Transactional
    public HotelMapping resolveOrCreateHotel(SupplierCode supplierCode, String hotelCode) {
        Optional<HotelMappingJpaEntity> existing =
                hotelMappingJpaRepository.findBySupplierCodeAndHotelCode(supplierCode, hotelCode);
        if (existing.isPresent()) {
            return toDomain(existing.get());
        }

        try {
            HotelMappingJpaEntity saved = hotelMappingJpaRepository.save(
                    new HotelMappingJpaEntity(UUID.randomUUID().toString(), supplierCode, hotelCode));
            return toDomain(saved);
        } catch (DataIntegrityViolationException raceLost) {
            return hotelMappingJpaRepository.findBySupplierCodeAndHotelCode(supplierCode, hotelCode)
                    .map(this::toDomain)
                    .orElseThrow(() -> raceLost);
        }
    }

    @Override
    @Transactional
    public RoomTypeMapping resolveOrCreateRoomType(SupplierCode supplierCode, String hotelCode, String roomTypeCode) {
        Optional<RoomTypeMappingJpaEntity> existing = roomTypeMappingJpaRepository
                .findBySupplierCodeAndHotelCodeAndRoomTypeCode(supplierCode, hotelCode, roomTypeCode);
        if (existing.isPresent()) {
            return toDomain(existing.get());
        }

        try {
            RoomTypeMappingJpaEntity saved = roomTypeMappingJpaRepository.save(new RoomTypeMappingJpaEntity(
                    UUID.randomUUID().toString(), supplierCode, hotelCode, roomTypeCode));
            return toDomain(saved);
        } catch (DataIntegrityViolationException raceLost) {
            return roomTypeMappingJpaRepository
                    .findBySupplierCodeAndHotelCodeAndRoomTypeCode(supplierCode, hotelCode, roomTypeCode)
                    .map(this::toDomain)
                    .orElseThrow(() -> raceLost);
        }
    }

    private HotelMapping toDomain(HotelMappingJpaEntity entity) {
        return new HotelMapping(entity.getSupplierCode(), entity.getHotelCode(), entity.getInternalHotelId());
    }

    private RoomTypeMapping toDomain(RoomTypeMappingJpaEntity entity) {
        return new RoomTypeMapping(
                entity.getSupplierCode(), entity.getHotelCode(), entity.getRoomTypeCode(), entity.getInternalRoomTypeId());
    }
}
