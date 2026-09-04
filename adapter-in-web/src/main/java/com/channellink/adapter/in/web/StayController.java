package com.channellink.adapter.in.web;

import com.channellink.adapter.in.web.dto.StaySearchResponse;
import com.channellink.domain.model.SearchCriteria;
import com.channellink.domain.port.in.SearchStayPort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class StayController {

    private final SearchStayPort searchStayPort;

    // 검색 조건을 받아 통합 검색
    @GetMapping("/api/v1/stays/search")
    public StaySearchResponse search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
            @RequestParam int adults,
            @RequestParam(defaultValue = "0") int children) {

        SearchStayPort.SearchResult result =
                searchStayPort.search(new SearchCriteria(checkIn, checkOut, adults, children));
        return StaySearchResponse.from(result);
    }
}
