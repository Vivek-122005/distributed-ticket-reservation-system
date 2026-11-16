package com.vivek.ticketreservation.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the module boundaries of the modular monolith (ADR 002, docs/lld/class-diagrams.md §5).
 * A violation fails {@code mvn verify}, so boundaries are checked in CI instead of by convention.
 */
@AnalyzeClasses(packages = "com.vivek.ticketreservation", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    private static final String[] INVENTORY_INTERNALS = {
            "..inventory.application..", "..inventory.domain..", "..inventory.infrastructure..", "..inventory.web.."};
    private static final String[] PAYMENT_INTERNALS = {
            "..payment.application..", "..payment.domain..", "..payment.infrastructure..", "..payment.web.."};

    /** Rule 1: booking may use other modules only through their published {@code ..api} packages. */
    @ArchTest
    static final ArchRule bookingUsesOnlyPublishedApis = noClasses()
            .that().resideInAPackage("..booking..")
            .should().dependOnClassesThat().resideInAnyPackage(concat(INVENTORY_INTERNALS, PAYMENT_INTERNALS))
            .allowEmptyShould(true)
            .because("modules talk through published ports so they can be extracted later (ADR 002)");

    /** Rule 1b: payment and inventory do not reach into each other's internals either. */
    @ArchTest
    static final ArchRule paymentDoesNotUseInventoryInternals = noClasses()
            .that().resideInAPackage("..payment..")
            .should().dependOnClassesThat().resideInAnyPackage(INVENTORY_INTERNALS)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule inventoryDoesNotUsePaymentInternals = noClasses()
            .that().resideInAPackage("..inventory..")
            .should().dependOnClassesThat().resideInAnyPackage(PAYMENT_INTERNALS);

    /** Rule 2: inventory and payment never depend on booking; booking reacts to their events. */
    @ArchTest
    static final ArchRule noDependencyOnBooking = noClasses()
            .that().resideInAnyPackage("..inventory..", "..payment..")
            .should().dependOnClassesThat().resideInAPackage("..booking..")
            .because("the module graph must stay acyclic");

    /** Rule 3: the domain imports no framework or persistence code (SOLID.md deviation #3, fixed in P1.3). */
    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "com.fasterxml..", "jakarta.persistence..", "java.sql..", "javax.sql..")
            .because("business rules must be testable without Spring or a database (DIP)");

    /** Rule 4: common is a leaf library and knows no module. */
    @ArchTest
    static final ArchRule commonKnowsNoModule = noClasses()
            .that().resideInAPackage("..common..")
            .should().dependOnClassesThat().resideInAnyPackage("..inventory..", "..booking..", "..payment..");

    /** Hexagonal layering inside a module: inner layers never depend on outer ones. */
    @ArchTest
    static final ArchRule domainDoesNotDependOnOuterLayers = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..", "..web..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnWeb = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..web..")
            .because("use cases must not know about HTTP DTOs (fixes SOLID.md deviation #1)");

    /** Use cases reach the outside world only through ports in application.port (SOLID.md #2, fixed in P1.3). */
    @ArchTest
    static final ArchRule applicationDependsOnPortsNotAdapters = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .because("use cases depend on port interfaces; adapters implement them (DIP)");

    @ArchTest
    static final ArchRule publishedApiIsSelfContained = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..domain..", "..infrastructure..", "..web..")
            .because("the published API must not leak internal types to other modules");

    private static String[] concat(String[] a, String[] b) {
        String[] result = new String[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
