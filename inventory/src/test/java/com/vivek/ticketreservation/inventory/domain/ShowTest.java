package com.vivek.ticketreservation.inventory.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShowTest {

    @Test
    void onlyOnSaleShowsAcceptHolds() {
        assertThat(new Show(UUID.randomUUID(), ShowStatus.ON_SALE, "INR").isOnSale()).isTrue();
        assertThat(new Show(UUID.randomUUID(), ShowStatus.SCHEDULED, "INR").isOnSale()).isFalse();
        assertThat(new Show(UUID.randomUUID(), ShowStatus.SOLD_OUT, "INR").isOnSale()).isFalse();
    }

    @Test
    void closedShowCannotBeReopened() {
        Show show = new Show(UUID.randomUUID(), ShowStatus.SCHEDULED, "INR");
        show.openSale();
        show.closeSale();
        assertThatThrownBy(show::openSale).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(show.status()).isEqualTo(ShowStatus.CLOSED);
    }
}
