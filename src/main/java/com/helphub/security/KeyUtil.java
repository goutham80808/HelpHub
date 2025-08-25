package com.helphub.security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V3CertificateGenerator;

import javax.security.auth.x500.X500Principal;
import java.io.FileOutputStream;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * A one-time utility to generate a self-signed JKS (Java KeyStore) for the server's SSL/TLS encryption.
 * <p>
 * This class is not part of the main application runtime. It is intended to be run once by the
 * administrator during the initial setup of the HelpHub server. It uses the Bouncy Castle
 * security provider to create a certificate, which is required because Java's built-in tools
 * for this can be cumbersome to use programmatically.
 */
public class KeyUtil {
    /** The name of the keystore file that will be generated. */
    private static final String KEYSTORE_FILE = "helphub.keystore";
    /** The password used to protect both the keystore file and the private key within it. */
    private static final String KEYSTORE_PASSWORD = "HelpHubPassword"; // IMPORTANT: Change in a real deployment
    /** The alias (a unique name) used to identify the key entry within the keystore. */
    private static final String ALIAS = "helphub";

    /**
     * The main entry point for the utility.
     * When run, it generates a new {@code helphub.keystore} file in the current directory.
     *
     * @param args Command-line arguments (not used).
     * @throws Exception if any part of the key or certificate generation fails.
     */
    @SuppressWarnings("deprecation") // Suppress warnings for the deprecated Bouncy Castle certificate generator
    public static void main(String[] args) throws Exception {
        // Programmatically register Bouncy Castle as a security provider.
        // This makes its cryptographic algorithms available to the Java Security API.
        Security.addProvider(new BouncyCastleProvider());

        System.out.println("Generating a new self-signed keystore...");

        // 1. Generate a new RSA key pair (public and private key).
        // A 2048-bit key is a strong, standard choice for security.
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        // 2. Create a self-signed X.509 certificate.
        // A self-signed certificate is one where the issuer is the same as the subject.
        X509V3CertificateGenerator certGen = new X509V3CertificateGenerator();
        X500Principal dnName = new X500Principal("CN=HelpHubServer"); // CN = Common Name

        // Set the certificate's properties
        certGen.setSerialNumber(java.math.BigInteger.valueOf(System.currentTimeMillis()));
        certGen.setSubjectDN(dnName);
        certGen.setIssuerDN(dnName); // Issuer is the same as the subject
        certGen.setNotBefore(new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000)); // Valid from yesterday
        certGen.setNotAfter(new Date(System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L)); // Valid for 1 year
        certGen.setPublicKey(keyPair.getPublic());
        certGen.setSignatureAlgorithm("SHA256WithRSAEncryption");

        // Generate the certificate, signing it with our own private key and specifying "BC" as the provider.
        X509Certificate cert = certGen.generate(keyPair.getPrivate(), "BC");

        // 3. Store the private key and the certificate in a new JKS Keystore.
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null); // Initialize a new, empty keystore
        keyStore.setKeyEntry(ALIAS, keyPair.getPrivate(), KEYSTORE_PASSWORD.toCharArray(), new java.security.cert.Certificate[]{cert});

        // 4. Write the newly created keystore to a file.
        try (FileOutputStream fos = new FileOutputStream(KEYSTORE_FILE)) {
            keyStore.store(fos, KEYSTORE_PASSWORD.toCharArray());
        }

        System.out.println("Keystore '" + KEYSTORE_FILE + "' created successfully.");
        System.out.println("Use this password to run the server: " + KEYSTORE_PASSWORD);
    }
}