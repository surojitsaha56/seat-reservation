package com.seatreservation.system.api;

public record CountsDto(int total, int available, int held, int confirmed) {
}
