package com.channellink.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MoneyTest {

    @Test
    void 금액이_0_이상이면_정상_생성된다() {
        Money money = new Money(0, "KRW");

        assertThat(money.amount()).isZero();
    }

    @Test
    void 금액이_음수이면_예외가_발생한다() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Money(-1, "KRW"));
    }

    @Test
    void 통화가_공백이면_예외가_발생한다() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Money(1000, " "));
    }

    @Test
    void 통화가_null이면_예외가_발생한다() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Money(1000, null));
    }
}
