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
        name = "hotel_mapping",
        uniqueConstraints = @UniqueConstraint(columnNames = {"supplier_code", "hotel_code"}))
public class HotelMappingJpaEntity {

    @Id
    @Column(name = "internal_hotel_id")
    private String internalHotelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "supplier_code", nullable = false)
    private SupplierCode supplierCode;

    @Column(name = "hotel_code", nullable = false)
    private String hotelCode;

}
