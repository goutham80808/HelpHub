package com.helphub.common;

import java.io.InputStream;
import java.util.Properties;

/**
 * A utility class for loading and accessing configuration properties.
 * <p>
 * This class loads the {@code config.properties} file from the classpath upon application startup.
 * It provides static, type-safe methods to retrieve configuration values, with support for default values
 * to ensure the application can run even if the properties file is missing or a key is not present.
 */
public class Config {

    /** A {@link Properties} object to hold the key-value pairs from the config file. */
    private static final Properties properties = new Properties();

    /**
     * A static initializer block that runs once when the class is first loaded by the JVM.
     * It finds the {@code config.properties} file in the classpath, loads it, and populates
     * the {@code properties} object.
     */
    static {
        try (InputStream input = Config.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                // This is a warning, not a fatal error. The application can proceed with defaults.
                System.err.println("WARNING: config.properties file not found. Using default values.");
            } else {
                properties.load(input);
            }
        } catch (Exception ex) {
            // A failure here is more serious, indicating a potential classpath or permissions issue.
            System.err.println("FATAL: Could not load config.properties file.");
            ex.printStackTrace();
        }
    }

    /**
     * Retrieves an integer property for a given key.
     * If the key is not found or the value is not a valid integer, it returns the specified default value.
     *
     * @param key The name of the property.
     * @param defaultValue The value to return if the key is not found or invalid.
     * @return The integer value of the property, or the default value.
     */
    public static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            // This handles cases where the property value is present but not a valid integer.
            return defaultValue;
        }
    }

    /**
     * Retrieves a double property for a given key.
     * If the key is not found or the value is not a valid double, it returns the specified default value.
     *
     * @param key The name of the property.
     * @param defaultValue The value to return if the key is not found or invalid.
     * @return The double value of the property, or the default value.
     */
    public static double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            // This handles cases where the property value is present but not a valid double.
            return defaultValue;
        }
    }
}