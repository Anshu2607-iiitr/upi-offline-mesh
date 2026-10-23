package com.demo.upimesh.bank;

import java.math.BigDecimal;

public record AccountView(String vpa, String holderName, BigDecimal balance) {
}
