package com.channellink.domain.port.in;

import com.channellink.domain.type.SupplierCode;

import java.util.List;

public interface RefreshHotelCatalogPort {

    List<SupplierCode> refresh();
}
