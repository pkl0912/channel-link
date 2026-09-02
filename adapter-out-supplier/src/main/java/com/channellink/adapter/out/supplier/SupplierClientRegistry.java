package com.channellink.adapter.out.supplier;

import com.channellink.domain.port.out.SupplierClient;
import com.channellink.domain.type.SupplierCode;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class SupplierClientRegistry {

    private final Map<SupplierCode, SupplierClient> clientsByCode;

    public SupplierClientRegistry(List<SupplierClient> supplierClients) {
        this.clientsByCode = new EnumMap<>(SupplierCode.class);
        for (SupplierClient client : supplierClients) {
            clientsByCode.put(client.supplierCode(), client);
        }
    }

    public SupplierClient get(SupplierCode supplierCode) {
        SupplierClient client = clientsByCode.get(supplierCode);
        if (client == null) {
            throw new IllegalStateException("no SupplierClient for code: " + supplierCode);
        }
        return client;
    }

    public Collection<SupplierClient> all() {
        return clientsByCode.values();
    }
}
