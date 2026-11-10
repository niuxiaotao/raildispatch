package com.raildispatch.api;

/**
 * What a yard terminal assignment says for one vehicle: destination, consist, service number and a
 * user-definable vehicle type (for example "locomotive", "boxcar", "brake van").
 */
public record TerminalInfo(String destination, String consist, String service, String type) {

    public TerminalInfo {
        destination = destination == null ? "" : destination;
        consist = consist == null ? "" : consist;
        service = service == null ? "" : service;
        type = type == null ? "" : type;
    }
}
