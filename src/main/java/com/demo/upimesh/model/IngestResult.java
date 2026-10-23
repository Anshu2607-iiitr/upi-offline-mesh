package com.demo.upimesh.model;

public record IngestResult(String packetId, String bridgeId, Outcome outcome, String message) {
}
