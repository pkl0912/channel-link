package com.channellink.adapter.out.supplier;

import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.port.out.SupplierPort;
import com.channellink.domain.type.SupplierCode;
import reactor.core.publisher.Mono;

import java.util.List;

interface ReactiveSupplierSearch {

    SupplierCode supplierCode();

    Mono<SupplierPort.AvailabilityResult> searchSupplierAvailability(List<String> hotelCodes, SearchCriteria criteria);
}
