package com.channellink.adapter.out.persistence.entity;

import com.channellink.domain.type.SupplierCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "room_type_mapping",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"supplier_code", "hotel_code", "room_type_code"}))
public class RoomTypeMappingJpaEntity {

    @Id
    @Column(name = "internal_room_type_id")
    private String internalRoomTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "supplier_code", nullable = false)
    private SupplierCode supplierCode;

    @Column(name = "hotel_code", nullable = false)
    private String hotelCode;

    @Column(name = "room_type_code", nullable = false)
    private String roomTypeCode;

}
