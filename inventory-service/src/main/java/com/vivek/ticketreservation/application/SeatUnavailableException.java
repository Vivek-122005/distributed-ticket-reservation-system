package com.vivek.ticketreservation.application;

public class SeatUnavailableException extends RuntimeException {
    public SeatUnavailableException() { super("One or more requested seats are unavailable"); }
}
