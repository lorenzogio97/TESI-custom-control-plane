package it.lorenzogiorgi.tesi.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;


import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
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
     * Envoy control plane server configuration
     */
    public static int ENVOY_CONFIGURATION_SERVER_PORT;
    public static String ENVOY_CONFIGURATION_SERVER_HOSTNAME;

    /**
     * Orchestrator API configuration
     */
    public static int ORCHESTRATOR_API_PORT;
    public static String ORCHESTRATOR_API_IP;

    /**
     * Platform domain configuration
     */
    public static String PLATFORM_DOMAIN;
    public static String PLATFORM_AUTHENTICATION_DOMAIN;
    public static String PLATFORM_USER_BASE_DOMAIN;
    public static String PLATFORM_NODE_BASE_DOMAIN;

    /**
     * Applications information
     */
    public static HashMap<String, Application> applications;


    /**
     * Edge node information
     */
    public static HashMap<String, EdgeNode> edgeNodes;

    /**
     * Sample user data
     */
    public static HashMap<String, User> users;

    /*
      Load configuration from file
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
        loadUsers();

        //initialize availableCPU and RAM to totalCPU and RAM
        for (EdgeNode edgeNode : edgeNodes.values()) {
            edgeNode.setAvailableCPU(edgeNode.getTotalCPU());
            edgeNode.setAvailableMemory(edgeNode.getTotalMemory());
        }
    }

    /**
     * separate load function for User. This allows the possibility to modify data source for user in the future.
     */
    public static void loadUsers() {
        byte[] encoded;
        try {
            encoded = Files.readAllBytes(Paths.get("./configuration/userList.json"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String jsonString = new String(encoded, StandardCharsets.UTF_8);
        Type hasmapObject = new TypeToken<HashMap<String, List<User>>>() {}.getType();
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.excludeFieldsWithModifiers(Modifier.TRANSIENT);
        Gson gson = gsonBuilder.create();
        users = gson.fromJson(jsonString, hasmapObject);
    }
}
