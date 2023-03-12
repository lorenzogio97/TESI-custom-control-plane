package it.lorenzogiorgi.tesi.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;


import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Class that contains the configuration of the application. The configuration is read from
 * file every time application is started.
 */

public class Configuration {
    /**
     * Authoritative DNS API configuration
     */
    public static String DNS_API_IP;
    public static int DNS_API_PORT;
    public static String DNS_API_SERVER_ID;
    public static String DNS_API_KEY;

    /**
     * Sample user data
     */
    public static List<User> users;

    /**
     * Envoy proxies information
     */
    public static List<EnvoyProxy> envoyProxies;


    /**
     * Load configuration from file
     */
    static {
        byte[] encoded;
        try {
            encoded = Files.readAllBytes(Paths.get("./configuration/configuration.json"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String jsonString = new String(encoded, StandardCharsets.UTF_8);
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.excludeFieldsWithModifiers(Modifier.TRANSIENT);
        Gson gson = gsonBuilder.create();
        gson.fromJson(jsonString, Configuration.class);
    }

}
