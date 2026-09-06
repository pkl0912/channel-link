package com.channellink.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SearchCriteriaTest {

    private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 1);
    private static final LocalDate CHECK_OUT = LocalDate.of(2026, 9, 4);

    @Test
    void 모든_필드가_유효하면_정상_생성된다() {
        SearchCriteria criteria = new SearchCriteria(CHECK_IN, CHECK_OUT, 2, 1);

        assertThat(criteria.checkIn()).isEqualTo(CHECK_IN);
        assertThat(criteria.checkOut()).isEqualTo(CHECK_OUT);
    }

    @Test
    void 체크아웃이_체크인과_같으면_예외가_발생한다() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SearchCriteria(CHECK_IN, CHECK_IN, 2, 0));
    }

    @Test
    void 체크아웃이_체크인보다_이전이면_예외가_발생한다() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SearchCriteria(CHECK_OUT, CHECK_IN, 2, 0));
    }

    @Test
    void 성인_수가_1명_미만이면_예외가_발생한다() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SearchCriteria(CHECK_IN, CHECK_OUT, 0, 0));
    }

    @Test
    void 어린이_수가_음수이면_예외가_발생한다() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SearchCriteria(CHECK_IN, CHECK_OUT, 2, -1));
    }
}
