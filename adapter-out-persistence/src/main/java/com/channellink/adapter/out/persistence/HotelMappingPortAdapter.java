package com.channellink.adapter.out.persistence;

import com.channellink.adapter.out.persistence.entity.HotelMappingJpaEntity;
import com.channellink.adapter.out.persistence.entity.HotelMappingJpaRepository;
import com.channellink.adapter.out.persistence.entity.RoomTypeMappingJpaEntity;
import com.channellink.adapter.out.persistence.entity.RoomTypeMappingJpaRepository;
import com.channellink.domain.mapping.HotelMapping;
import com.channellink.domain.mapping.RoomTypeMapping;
import com.channellink.adapter.out.persistence.support.UuidV7;
import com.channellink.domain.port.out.HotelMappingPort;
import com.channellink.domain.type.SupplierCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import lombok.AllArgsConstructor;

@Component
@AllArgsConstructor
public class HotelMappingPortAdapter implements HotelMappingPort {

    private final HotelMappingJpaRepository hotelMappingJpaRepository;
    private final RoomTypeMappingJpaRepository roomTypeMappingJpaRepository;

    // 숙소 매핑을 조회하고, 없으면 내부 식별자를 발급해 저장
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
                    new HotelMappingJpaEntity(UuidV7.generate().toString(), supplierCode, hotelCode));
            return toDomain(saved);
        } catch (DataIntegrityViolationException raceLost) {
            return hotelMappingJpaRepository.findBySupplierCodeAndHotelCode(supplierCode, hotelCode)
                    .map(this::toDomain)
                    .orElseThrow(() -> raceLost);
        }
    }

    // 객실 타입 매핑을 조회하고, 없으면 내부 식별자를 발급해 저장
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
                    UuidV7.generate().toString(), supplierCode, hotelCode, roomTypeCode));
            return toDomain(saved);
        } catch (DataIntegrityViolationException raceLost) {
            return roomTypeMappingJpaRepository
                    .findBySupplierCodeAndHotelCodeAndRoomTypeCode(supplierCode, hotelCode, roomTypeCode)
                    .map(this::toDomain)
                    .orElseThrow(() -> raceLost);
        }
    }

    // 이 공급사의 숙소 매핑을 DB에서 전부 조회
    @Override
    public List<HotelMapping> findAllHotelMappingBySupplier(SupplierCode supplierCode) {
        return hotelMappingJpaRepository.findAllBySupplierCode(supplierCode).stream().map(this::toDomain).toList();
    }

    private HotelMapping toDomain(HotelMappingJpaEntity entity) {
        return new HotelMapping(entity.getSupplierCode(), entity.getHotelCode(), entity.getInternalHotelId());
    }

    private RoomTypeMapping toDomain(RoomTypeMappingJpaEntity entity) {
        return new RoomTypeMapping(
                entity.getSupplierCode(), entity.getHotelCode(), entity.getRoomTypeCode(), entity.getInternalRoomTypeId());
    }
}
