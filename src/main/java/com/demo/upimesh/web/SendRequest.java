package com.demo.upimesh.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record SendRequest(
        @NotBlank String deviceId,
        @NotBlank String senderVpa,
        @NotBlank String receiverVpa,
        @NotNull @Positive @DecimalMax("100000") BigDecimal amount,
        @NotBlank @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4-6 digits") String pin) {
}
