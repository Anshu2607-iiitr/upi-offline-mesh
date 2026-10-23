package com.demo.upimesh.crypto;

import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;

/**
 * Stand-in for the bank's HSM. The private key is generated at startup and never
 * leaves this class; phones only ever get {@link #publicKey()}.
 */
@Component
public class BankKeyStore {

    private final KeyPair keyPair;

    public BankKeyStore() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keyPair = generator.generateKeyPair();
    }

    public PublicKey publicKey() {
        return keyPair.getPublic();
    }

    public byte[] decrypt(Envelope envelope) throws GeneralSecurityException {
        return HybridCrypto.decrypt(envelope, keyPair.getPrivate());
    }
}
