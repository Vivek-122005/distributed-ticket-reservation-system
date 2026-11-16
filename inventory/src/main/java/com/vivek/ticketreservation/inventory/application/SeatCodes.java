package com.vivek.ticketreservation.inventory.application;

import java.util.Comparator;
import java.util.List;

/** The one normalisation rule for seat codes: trimmed, upper case, distinct, sorted. */
final class SeatCodes {
    private SeatCodes() { }

    static List<String> normalize(List<String> codes) {
        return codes.stream().map(String::trim).map(String::toUpperCase).distinct().sorted(Comparator.naturalOrder()).toList();
    }
}
