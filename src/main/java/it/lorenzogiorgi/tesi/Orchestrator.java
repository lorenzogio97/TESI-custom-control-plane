package it.lorenzogiorgi.tesi;

import com.google.gson.Gson;
import it.lorenzogiorgi.tesi.api.*;
import it.lorenzogiorgi.tesi.common.*;
import it.lorenzogiorgi.tesi.dns.DNSUpdater;
import it.lorenzogiorgi.tesi.envoy.EnvoyConfigurationServer;
import org.apache.logging.log4j.*;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.util.*;

public class Orchestrator {
    public static EnvoyConfigurationServer envoyConfigurationServer;

    public static Gson gson;

    private static Logger logger = LogManager.getLogger();

    public static void main(String[] arg) {
        gson = new Gson();
        envoyConfigurationServer = new EnvoyConfigurationServer();
        logger.info("Envoy server initialized");
        Spark.ipAddress(Configuration.ORCHESTRATOR_API_IP);
        Spark.port(Configuration.ORCHESTRATOR_API_PORT);
        Spark.post("/login", (Orchestrator::login));
        Spark.post("/logout", (Orchestrator::logout));
        Spark.post("/migrate/:user",(Orchestrator::migrate));
        Spark.get("/envoyconfiguration/:envoyNodeID", Orchestrator::serveEnvoyConfiguration);

        Spark.get("/test", ((request, response) -> "TEST"));
        Spark.awaitInitialization();

        //initialize edge nodes
        initializeEdgeNodes();

        //initialize Authenitication Server (DNS mapping with Auth URL)
        inizializeAutheniticationServers();

        envoyConfigurationServer.awaitTermination();
    }


    private static void inizializeAutheniticationServers() {
        DNSUpdater.updateDNSRecord(Configuration.PLATFORM_DOMAIN, Configuration.PLATFORM_AUTHENTICATION_DOMAIN, "A", 600, Configuration.ORCHESTRATOR_API_IP);
    }



    private static String serveEnvoyConfiguration(Request request, Response response) {
        String proxyId = request.params(":envoyNodeID");

        response.type("application/yaml");
        return "admin:\n" +
                "  access_log_path: /dev/null\n" +
                "  address:\n" +
                "    socket_address:\n" +
                "      address: 0.0.0.0\n" +
                "      port_value: 10000\n" +
                "dynamic_resources:\n" +
                "  cds_config:\n" +
                "    resource_api_version: V3\n" +
                "    api_config_source:\n" +
                "      api_type: DELTA_GRPC\n" +
                "      transport_api_version: V3\n" +
                "      grpc_services:\n" +
                "      - envoy_grpc:\n" +
                "          cluster_name: xds_cluster\n" +
                "      set_node_on_first_message_only: false\n" +
                "  lds_config:\n" +
                "    resource_api_version: V3\n" +
                "    api_config_source:\n" +
                "      api_type: DELTA_GRPC\n" +
                "      transport_api_version: V3\n" +
                "      grpc_services:\n" +
                "      - envoy_grpc:\n" +
                "          cluster_name: xds_cluster\n" +
                "      set_node_on_first_message_only: false\n" +
                "node:\n" +
                "  cluster: test-cluster\n" +
                "  id: "+proxyId+"\n" +
                "static_resources:\n" +
                "  clusters:\n" +
                "  - connect_timeout: 5s\n" +
                "    load_assignment:\n" +
                "      cluster_name: xds_cluster\n" +
                "      endpoints:\n" +
                "      - lb_endpoints:\n" +
                "        - endpoint:\n" +
                "            address:\n" +
                "              socket_address:\n" +
                "                address: "+Configuration.ENVOY_CONFIGURATION_SERVER_HOSTNAME+"\n" +
                "                port_value: "+Configuration.ENVOY_CONFIGURATION_SERVER_PORT+"\n" +
                "    http2_protocol_options: {}\n" +
                "    name: xds_cluster\n" +
                "    type: STRICT_DNS";
    }

    private static void initializeEdgeNodes() {
        Configuration.edgeNodes.values().forEach(EdgeNode::initialize);
        System.out.println("Edge nodes initialized");
    }

    private static List<String> findNearestMECNode() {
        List<String> mecNodes  = new ArrayList<>(Configuration.edgeNodes.keySet());
        Collections.shuffle(mecNodes);
        return mecNodes;
    }



    private static String login(Request request, Response response) {
        String requestBody = request.body();
        LoginRequest loginRequest = gson.fromJson(requestBody, LoginRequest.class);

        //check username and password
        User user= Configuration.users.get(loginRequest.getUsername());
        if(user==null || user.getPassword()==null || !user.getPassword().equals(loginRequest.getPassword())) {
            response.status(401);
            response.type("application/json");
            return gson.toJson(new LoginResponse(false, null));
        }

        //if user was already logged, we need to destroy all previously allocated resources
        if(user.getCurrentEdgeNodeId()!=null) {
            Configuration.edgeNodes.get(user.getCurrentEdgeNodeId()).deallocateUserResources(user);
        }
        if(user.getFormerEdgeNodeId()!=null) {
            Configuration.edgeNodes.get(user.getCurrentEdgeNodeId()).deallocateUserResources(user);
        }
        user.setCurrentEdgeNodeId(null);
        user.setFormerEdgeNodeId(null);
        user.setCookie(null);


        //compute authId cookie
        byte[] array= new byte[32];
        new Random().nextBytes(array);
        String authId= toHexString(array);
        System.out.println(authId);

        //compute domain name for the user
        String domainName = user.getUsername()+"."+Configuration.PLATFORM_USER_BASE_DOMAIN;

        //get nearest MECNode
        List<String> nearestEdgeNodeIDs = findNearestMECNode();

        //resource allocation
        boolean allocated = false;
        for(String edgeId: nearestEdgeNodeIDs) {
            EdgeNode edgeNode = Configuration.edgeNodes.get(edgeId);
            allocated = edgeNode.allocateUserResources(user.getUsername(), authId);
            if(allocated) {
                //set DNS domain
                DNSUpdater.updateDNSRecord(Configuration.PLATFORM_DOMAIN, domainName, "A",  5, edgeNode.getIpAddress());
                //set EdgeNodeId and cookie in User data structure
                user.setCurrentEdgeNodeId(edgeId);
                user.setCookie(authId);
                break;
            }
        }

        //allocation failed on all near edge node
        if(!allocated) {
            response.status(503);
            response.type("application/json");
            return gson.toJson(new LoginResponse(false, null));
        }


        response.status(200);
        response.type("application/json");
        response.cookie("."+Configuration.PLATFORM_DOMAIN, "/", "authID", authId, 3600, false, false);
        return gson.toJson(new LoginResponse(true, domainName));
    }

    private static String logout(Request request, Response response) {
        String userCookie = request.cookie("authID");
        String requestBody = request.body();
        LogoutRequest logoutRequest = gson.fromJson(requestBody, LogoutRequest.class);
        String username = logoutRequest.getUsername();

        User user = Configuration.users.get(username);
        if(user==null || user.getCookie()==null || !user.getCookie().equals(userCookie)) {
            response.status(401);
            response.type("application/json");
            return gson.toJson(new LogoutResponse());
        }

        //delete route and cluster related to a user
        Orchestrator.envoyConfigurationServer.deleteAllUserResourcesFromProxy(user.getUsername(), user.getCurrentEdgeNodeId());
        if(user.getFormerEdgeNodeId()!=null) {
            Orchestrator.envoyConfigurationServer.deleteAllUserResourcesFromProxy(user.getUsername(), user.getFormerEdgeNodeId());
        }

        //remove containers
        Configuration.edgeNodes.get(user.getCurrentEdgeNodeId()).deallocateUserResources(user);

        //reset fields in user data structure
        user.setCurrentEdgeNodeId(null);
        user.setFormerEdgeNodeId(null);
        user.setCookie(null);

        response.status(200);
        return gson.toJson(new LogoutResponse());
    }


    private static String migrate(Request request, Response response) {
        String username = request.params(":username");

        MigrationRequest migrationRequest = gson.fromJson(request.body(), MigrationRequest.class);

        List<String> edgeNodeIDs = migrationRequest.getEdgeNodeList();

        //find user to migrate
        User user = Configuration.users.get(username);

        //user not found or not logged (cookie is null)
        if(user==null || user.getCookie()==null) {
            response.status(401);
            response.type("application/json");
            return "";
        }

        //if user is already on the best MEC, no need to migrate
        if(user.getCurrentEdgeNodeId().equals(edgeNodeIDs.get(0))) {
            response.status(204);
            return "";
        }

        String domainName = user.getUsername() + "." + Configuration.PLATFORM_USER_BASE_DOMAIN;

        //resource allocation
        boolean allocated = false;
        for(String edgeId: edgeNodeIDs) {
            EdgeNode edgeNode = Configuration.edgeNodes.get(edgeId);
            allocated = Configuration.edgeNodes.get(edgeId).allocateUserResources(user.getUsername(), user.getCookie());
            if(allocated) {
                //set DNS domain
                DNSUpdater.updateDNSRecord(Configuration.PLATFORM_DOMAIN, domainName, "A",  5, edgeNode.getIpAddress());
                //set EdgeNodeId and cookie in User data structure
                user.setFormerEdgeNodeId(user.getCurrentEdgeNodeId());
                user.setCurrentEdgeNodeId(edgeId);
                break;
            }
        }

        if(!allocated) {
            response.status(503);
            response.type("application/json");
            return "";
        }
        //redirect route to new Edge node
        envoyConfigurationServer.convertRouteToRedirect(username, user.getFormerEdgeNodeId(), user.getCurrentEdgeNodeId());

        //delete user resources on old node
        Configuration.edgeNodes.get(user.getFormerEdgeNodeId()).deallocateUserResources(user);

        response.status(204);
        return "";

    }




    private static String toHexString(byte[] bytes) {
        char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for ( int j = 0; j < bytes.length; j++ ) {
            v = bytes[j] & 0xFF;
            hexChars[j*2] = hexArray[v/16];
            hexChars[j*2 + 1] = hexArray[v%16];
        }
        return new String(hexChars);
    }

}
