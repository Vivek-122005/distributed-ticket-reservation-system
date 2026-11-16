package com.vivek.ticketreservation.inventory.api;

/** One seat of a hold with the price it will be sold at (minor units, BR-05). */
public record HeldSeat(String seatCode, long priceMinor) { }
