package com.channellink.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    //http://localhost:8080/swagger-ui/index.html
    @Bean
    public OpenAPI channelLinkOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("channel-link API")
                        .description("여러 숙박 상품 공급사(Supplier)의 상품을 하나의 표준 모델로 통합하는 연동 백엔드")
                        .version("v0.1"));
    }
}
