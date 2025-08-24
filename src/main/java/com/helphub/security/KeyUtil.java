// FILE: src/main/java/com/helphub/security/KeyUtil.java
package com.helphub.security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V3CertificateGenerator;

import javax.security.auth.x500.X500Principal;
import java.io.FileOutputStream;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;

public class KeyUtil {
    private static final String KEYSTORE_FILE = "helphub.keystore";
    private static final String KEYSTORE_PASSWORD = "HelpHubPassword";
    private static final String ALIAS = "helphub";

    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        System.out.println("Generating a new self-signed keystore...");

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        X509V3CertificateGenerator certGen = new X509V3CertificateGenerator();
        X500Principal dnName = new X500Principal("CN=HelpHubServer");

        certGen.setSerialNumber(java.math.BigInteger.valueOf(System.currentTimeMillis()));
        certGen.setSubjectDN(dnName);
        certGen.setIssuerDN(dnName);
        certGen.setNotBefore(new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000));
        certGen.setNotAfter(new Date(System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L));
        certGen.setPublicKey(keyPair.getPublic());
        certGen.setSignatureAlgorithm("SHA256WithRSAEncryption");

        X509Certificate cert = certGen.generate(keyPair.getPrivate(), "BC");

        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry(ALIAS, keyPair.getPrivate(), KEYSTORE_PASSWORD.toCharArray(), new java.security.cert.Certificate[]{cert});

        try (FileOutputStream fos = new FileOutputStream(KEYSTORE_FILE)) {
            keyStore.store(fos, KEYSTORE_PASSWORD.toCharArray());
        }

        System.out.println("Keystore '" + KEYSTORE_FILE + "' created successfully.");
        System.out.println("Use this password to run the server: " + KEYSTORE_PASSWORD);
    }
}