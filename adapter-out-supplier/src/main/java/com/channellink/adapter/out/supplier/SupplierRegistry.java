package com.channellink.adapter.out.supplier;

import com.channellink.domain.port.out.SupplierPort;
import com.channellink.domain.type.SupplierCode;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class SupplierRegistry {

    private final Map<SupplierCode, SupplierPort> clientsByCode;

    public SupplierRegistry(List<SupplierPort> supplierPorts) {
        this.clientsByCode = new EnumMap<>(SupplierCode.class);
        for (SupplierPort client : supplierPorts) {
            clientsByCode.put(client.supplierCode(), client);
        }
    }

    public SupplierPort get(SupplierCode supplierCode) {
        SupplierPort client = clientsByCode.get(supplierCode);
        if (client == null) {
            throw new IllegalStateException("no SupplierPort registered for " + supplierCode);
        }
        return client;
    }

    public Collection<SupplierPort> all() {
        return clientsByCode.values();
    }
}
