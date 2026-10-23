package com.demo.upimesh.bank;

import com.demo.upimesh.crypto.Hashing;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.security.MessageDigest;

/** Only {@link BankService} mutates balances, inside its settlement lock and a DB transaction. */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private String vpa;

    @Column(nullable = false)
    private String holderName;

    @Column(nullable = false, length = 32)
    private byte[] pinHash;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    // Defense in depth: if anything ever bypassed the settlement lock, a stale write fails
    // instead of silently overwriting a balance.
    @Version
    private Long version;

    protected Account() {
    }

    Account(String vpa, String holderName, String pin, BigDecimal openingBalance) {
        this.vpa = vpa;
        this.holderName = holderName;
        this.pinHash = Hashing.pinHash(vpa, pin);
        this.balance = openingBalance;
    }

    boolean pinMatches(String pin) {
        return pin != null && MessageDigest.isEqual(pinHash, Hashing.pinHash(vpa, pin));
    }

    String vpa() {
        return vpa;
    }

    String holderName() {
        return holderName;
    }

    BigDecimal balance() {
        return balance;
    }

    void debit(BigDecimal amount) {
        balance = balance.subtract(amount);
    }

    void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }
}
