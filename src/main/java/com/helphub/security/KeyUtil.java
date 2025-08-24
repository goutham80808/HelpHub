// src/main/java/com/helphub/security/KeyUtil.java
package com.helphub.security;

import java.io.FileOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
// --- START: ADD THESE IMPORTS ---
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import javax.security.auth.x500.X500Principal;
// --- END: ADD THESE IMPORTS ---

// --- FIX: You must import the BouncyCastleProvider ---
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V3CertificateGenerator;


/**
 * A utility to generate a self-signed JKS keystore for the server's SSL/TLS encryption.
 * This needs to be run once to create the 'helphub.keystore' file.
 * NOTE: This requires the Bouncy Castle provider, which must be added to pom.xml.
 */
public class KeyUtil {
    private static final String KEYSTORE_FILE = "helphub.keystore";
    private static final String KEYSTORE_PASSWORD = "HelpHubPassword"; // Change this in a real deployment
    private static final String ALIAS = "helphub";

    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        // --- FIX: Register Bouncy Castle as a security provider ---
        Security.addProvider(new BouncyCastleProvider());

        System.out.println("Generating a new self-signed keystore...");

        // Generate a 2048-bit RSA key pair
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        // Create a self-signed certificate
        X509V3CertificateGenerator certGen = new X509V3CertificateGenerator();
        X500Principal dnName = new X500Principal("CN=HelpHubServer");

        certGen.setSerialNumber(java.math.BigInteger.valueOf(System.currentTimeMillis()));
        certGen.setSubjectDN(dnName);
        certGen.setIssuerDN(dnName);
        certGen.setNotBefore(new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000));
        certGen.setNotAfter(new Date(System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L)); // 1 year validity
        certGen.setPublicKey(keyPair.getPublic());
        certGen.setSignatureAlgorithm("SHA256WithRSAEncryption");

        // The second argument "BC" tells the generator to use the Bouncy Castle provider
        X509Certificate cert = certGen.generate(keyPair.getPrivate(), "BC");

        // Store the key and certificate in a new JKS Keystore
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