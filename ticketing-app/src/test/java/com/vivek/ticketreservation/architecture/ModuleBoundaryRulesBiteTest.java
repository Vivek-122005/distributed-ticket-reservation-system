package com.vivek.ticketreservation.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves the boundary rule actually fails on a violation, so a green build is meaningful. */
class ModuleBoundaryRulesBiteTest {

    @Test
    void bookingRuleRejectsAccessToInventoryInternals() {
        JavaClasses violation = new ClassFileImporter()
                .importPackages("com.vivek.ticketreservation.booking.violation");

        assertThatThrownBy(() -> ModuleBoundaryTest.bookingUsesOnlyPublishedApis.check(violation))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("inventory.domain.Hold");
    }
}
