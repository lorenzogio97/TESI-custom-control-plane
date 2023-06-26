package it.lorenzogiorgi.tesi.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import it.lorenzogiorgi.tesi.utiliy.FileUtility;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class that contains the configuration of the application. The configuration is read from
 * file every time application is started.
 */

public class Configuration {
    /**
     * Flags to enable functions for performance/comparative testing
     */
    public static boolean ENABLE_DNS;
    public static boolean PERFORMANCE_TRACING;


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
    public static String ENVOY_CONFIGURATION_SERVER_IP;

    /**
     * Orchestrator configuration
     */
    public static int ORCHESTRATOR_API_PORT;
    public static String ORCHESTRATOR_API_IP;
    public static int ORCHESTRATOR_USER_GARBAGE_DELAY;

    /**
     * Platform domain configuration
     */
    public static String PLATFORM_DOMAIN;
    public static String PLATFORM_CLOUD_DOMAIN;
    public static String PLATFORM_ENVOY_CONF_SERVER_DOMAIN;
    public static String PLATFORM_ORCHESTRATOR_DOMAIN;
    public static String PLATFORM_NODE_BASE_DOMAIN;

    /**
     * Security Token
     */

    public static int CRYPTO_TOKEN_SECONDS_VALIDITY;
    public static int CRYPTO_TOKEN_LENGTH;

    /**
     * Client session configuration
     */
    public static int CLIENT_AUTHENTICATION_TOKEN_LENGTH;
    public static int CLIENT_SESSION_DURATION;

    /**
     * Applications information
     */
    public static HashMap<String, Application> applications;

    /**
     * Cloud node information
     */
    public static CloudNode cloudNode;

    /**
     * Edge node information
     */
    public static ConcurrentHashMap<String, EdgeNode> edgeNodes;

    /**
     * Sample user data
     */
    public static HashMap<String, User> users;


    /*
      Load configuration from file
     */
    static {
        String jsonString = FileUtility.readTextFile("./configuration/configuration.json");
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.excludeFieldsWithModifiers(Modifier.TRANSIENT);
        Gson gson = gsonBuilder.create();
        gson.fromJson(jsonString, Configuration.class);
        loadUsers();

    }

    /**
     * separate load function for User. This allows the possibility to modify data source for user in the future.
     */
    private static void loadUsers() {
        String jsonString = FileUtility.readTextFile("./configuration/userList.json");
        Type hashmapObject = new TypeToken<HashMap<String, User>>() {}.getType();
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.excludeFieldsWithModifiers(Modifier.TRANSIENT);
        Gson gson = gsonBuilder.create();
        users = gson.fromJson(jsonString, hashmapObject);
    }
}
