package com.channellink.adapter.in.web;

import com.channellink.adapter.in.web.dto.StaySearchResponse;
import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.port.in.SearchStayPort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Tag(name = "숙소 API", description = "숙소 검색 관련 API 입니다.")
public class StayController {

    private final SearchStayPort searchStayPort;

    // 검색 조건을 받아 통합 검색
    @GetMapping("/api/v1/stays/search")
    @Operation(summary = "숙소 통합 검색 API", description = "날짜와 인원으로 숙소를 통합 검색합니다.")
    public StaySearchResponse search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Parameter(description = "YYYY-MM-DD") LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Parameter(description = "YYYY-MM-DD") LocalDate checkOut,
            @RequestParam int adults,
            @RequestParam(defaultValue = "0") int children) {

        SearchStayPort.SearchResult result =
                searchStayPort.search(new SearchCriteria(checkIn, checkOut, adults, children));
        return StaySearchResponse.from(result);
    }
}
