package com.channellink.adapter.out.supplier;

import com.channellink.domain.port.out.SupplierPort;
import com.channellink.domain.type.SupplierCode;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * SupplierPort 구현체를 supplierCode로 찾아 쓰는 레지스트리.
 * Spring이 이 모듈의 @Component(SupplierAClient, SupplierBClient, ...)를 전부 List로 모아서
 * 생성자에 넣어준다 — 신규 Supplier를 추가할 때 여기 코드를 고칠 필요가 없다,
 * 클라이언트 클래스 하나만 새로 @Component로 등록하면 자동으로 registry에 들어온다 (OCP).
 */
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
