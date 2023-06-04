package it.lorenzogiorgi.tesi;

import com.google.gson.Gson;
import it.lorenzogiorgi.tesi.api.*;
import it.lorenzogiorgi.tesi.common.*;
import it.lorenzogiorgi.tesi.dns.DNSManagement;
import it.lorenzogiorgi.tesi.envoy.EnvoyConfigurationServer;
import it.lorenzogiorgi.tesi.utiliy.FileUtility;
import it.lorenzogiorgi.tesi.utiliy.TokenUtiliy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class Orchestrator {
    public static EnvoyConfigurationServer envoyConfigurationServer= new EnvoyConfigurationServer();
    public static Gson gson=new Gson();
    public static ConcurrentHashMap<String, Long> securityTokenMap = new ConcurrentHashMap<>();
    private static final Logger logger = LogManager.getLogger(Orchestrator.class.getName());


    public static void main(String[] arg) {

        //initialize DNS for Auth, Orchestrator and Envoy conf server.
        initializeDNS();
        // keystone creation from certificate: https://stackoverflow.com/questions/906402/how-to-import-an-existing-x-509-certificate-and-private-key-in-java-keystore-to
        Spark.secure("./src/main/resources/server.keystore", "lorenzo", null, null);
        Spark.ipAddress(Configuration.ORCHESTRATOR_API_IP);
        Spark.port(Configuration.ORCHESTRATOR_API_PORT);
        Spark.staticFileLocation("/tls");
        Spark.get("/envoyconfiguration/:envoyNodeID", Orchestrator::serveEnvoyConfiguration);
        Spark.get("/platform-tls/:what/:token", Orchestrator::servePlatformTls);
        Spark.get("/envoy-mtls/:what/:token", Orchestrator::serveEnvoymTls);
        Spark.after((request, response) -> {
            logger.info(String.format("%s %s %s", request.requestMethod(), request.url(), request.body()));
        });

        Spark.awaitInitialization();

        //initialize edge nodes
        initializeEdgeNodes();

        //initialize cloud Envoy node
        initializeCloudNode();

        //API for user needs to be available after Edgenode initialization
        Spark.post("/login", (Orchestrator::login));
        Spark.post("/logout", (Orchestrator::logout));
        Spark.post("/migrate/:username",(Orchestrator::migrate));
        Spark.post("/migration_feedback/:username/:edgeNodeId",(Orchestrator::finalizeMigration));

        envoyConfigurationServer.awaitTermination();
    }


    private static void initializeCloudNode() {
        CloudNode cloudNode = Configuration.cloudNode;
        cloudNode.initialize();
        DNSManagement.updateDNSRecord(Configuration.PLATFORM_DOMAIN, Configuration.PLATFORM_CLOUD_DOMAIN, "A", 3600, cloudNode.getIpAddress());
        logger.info("Cloud node initialized");
    }


    private static void initializeDNS() {
        DNSManagement.updateDNSRecord(Configuration.PLATFORM_DOMAIN, Configuration.PLATFORM_ORCHESTRATOR_DOMAIN, "A", 600, Configuration.ORCHESTRATOR_API_IP);
        DNSManagement.updateDNSRecord(Configuration.PLATFORM_DOMAIN, Configuration.PLATFORM_ENVOY_CONF_SERVER_DOMAIN, "A", 600, Configuration.ENVOY_CONFIGURATION_SERVER_IP);
    }

    private static String serveEnvoymTls(Request request, Response response) {
        String what = request.params(":what");
        String token = request.params(":token");

        if(!(Objects.equals(what, "cert") || Objects.equals(what, "key") || Objects.equals(what, "ca")) ||
                token == null) {
            return "";
        }

        //check if the token is valid
        if(securityTokenMap.getOrDefault(token, 0L)<System.currentTimeMillis()) {
            response.status(401);
            return "";
        }


        String returnContent = null;
        switch (what) {
            case "cert":
                returnContent = FileUtility.readTextFile("./src/main/resources/tls/envoy-mtls/clientcert.pem");
                break;
            case "key":
                returnContent = FileUtility.readTextFile("./src/main/resources/tls/envoy-mtls/clientkey.pem");
                break;
            case "ca":
                returnContent = FileUtility.readTextFile("./src/main/resources/tls/envoy-mtls/ca.crt");
                break;
        }

        response.type("text/plain");
        response.status(200);
        return returnContent;
    }

    private static String servePlatformTls(Request request, Response response) {
        String what = request.params(":what");
        String token = request.params(":token");

        if(!(Objects.equals(what, "cert") || Objects.equals(what, "key")) || token == null) {
            response.status(400);
            return "";
        }

        //check if the token is valid
        if(securityTokenMap.getOrDefault(token, 0L)<System.currentTimeMillis()) {
            response.status(401);
            return "";
        }


        String returnContent = null;
        switch (what) {
            case "cert":
                returnContent = FileUtility.readTextFile("./src/main/resources/tls/platform-tls/servercert.pem");
                break;

            case "key":
                returnContent = FileUtility.readTextFile("./src/main/resources/tls/platform-tls/serverkey.pem");
                break;
        }

        response.type("text/plain");
        response.status(200);
        return returnContent;
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
                "                address: "+Configuration.PLATFORM_ORCHESTRATOR_DOMAIN+"\n" +
                "                port_value: "+Configuration.ENVOY_CONFIGURATION_SERVER_PORT+"\n" +
                "    http2_protocol_options: {}\n" +
                "    name: xds_cluster\n" +
                "    type: STRICT_DNS\n"+
                "    transport_socket:\n" +
                "      name: envoy.transport_sockets.tls\n" +
                "      typed_config:\n" +
                "        \"@type\": type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext\n" +
                "        common_tls_context:\n" +
                "          tls_certificates:\n" +
                "            certificate_chain:\n" +
                "              filename: /etc/certs/envoy-mtls/clientcert.pem\n"+
                "            private_key:\n" +
                "              filename: /etc/certs/envoy-mtls/clientkey.pem\n"+
                "          validation_context:\n" +
                "            trusted_ca:\n" +
                "              filename: /etc/certs/envoy-mtls/ca.crt\n"+
                "  - connect_timeout: 5s\n" +
                "    load_assignment:\n" +
                "      cluster_name: orchestrator_cluster\n" +
                "      endpoints:\n" +
                "      - lb_endpoints:\n" +
                "        - endpoint:\n" +
                "            address:\n" +
                "              socket_address:\n" +
                "                address: "+Configuration.PLATFORM_ORCHESTRATOR_DOMAIN+"\n" +
                "                port_value: "+Configuration.ORCHESTRATOR_API_PORT+"\n" +
                "    name: orchestrator_cluster\n" +
                "    type: STRICT_DNS\n"+
                "    transport_socket:\n" +
                "      name: envoy.transport_sockets.tls\n" +
                "      typed_config:\n" +
                "        \"@type\": type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext\n" +
                "        common_tls_context:\n" +
                "          validation_context:\n" +
                "            trusted_ca:\n" +
                "              filename: /etc/ssl/certs/ca-certificates.crt";
    }

    private static void initializeEdgeNodes() {
        List<Thread> threadList = new ArrayList<>();
        for(EdgeNode edgeNode: Configuration.edgeNodes.values()) {
            Thread t = new Thread(edgeNode::initialize);
            t.setName(edgeNode.getId());
            threadList.add(t);
            t.start();
        }

        for (Thread thread : threadList) {
            try {
                thread.join(180000);
            } catch (InterruptedException e) {
                String edgeId = thread.getName();
                //since edge node has not been successfully initialized, it is removed from available ones.
                Configuration.edgeNodes.remove(edgeId);
                logger.warn("EdgeNode "+edgeId+ " has not been initialized correctly");
            }
        }
        logger.info("Edge nodes initialized");
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

        //check if user exists
        if(user==null) {
            response.status(401);
            response.type("application/json");
            return gson.toJson(new LoginResponse(null));
        }

        synchronized(user) {
            //check password
            if(user.getPassword()==null || !user.getPassword().equals(loginRequest.getPassword())) {
                response.status(401);
                response.type("application/json");
                return gson.toJson(new LoginResponse(null));
            }

            //if user was already logged, we need to destroy all previously allocated resources
            if (user.getCurrentEdgeNodeId() != null) {
                Configuration.edgeNodes.get(user.getCurrentEdgeNodeId()).deallocateUserResources(user.getUsername());
            }
            if (user.getFormerEdgeNodeId() != null) {
                Configuration.edgeNodes.get(user.getCurrentEdgeNodeId()).deallocateUserResources(user.getUsername());
            }
            user.setCurrentEdgeNodeId(null);
            user.setFormerEdgeNodeId(null);
            user.setCookie(null);

            //compute authId cookie
            String authId = TokenUtiliy.generateRandomHexString(Configuration.CLIENT_AUTHENTICATION_TOKEN_LENGTH);

            //get nearest MECNode
            List<String> nearestEdgeNodeIDs = findNearestMECNode();

            //resource allocation
            boolean allocated = false;
            for (String edgeId : nearestEdgeNodeIDs) {
                EdgeNode edgeNode = Configuration.edgeNodes.get(edgeId);
                allocated = edgeNode.allocateUserResources(user.getUsername(), authId, false);
                if (allocated) {
                    //set EdgeNodeId and cookie in User data structure
                    user.setCurrentEdgeNodeId(edgeId);
                    user.setCookie(authId);
                    break;
                }
            }

            //allocation failed on all near edge node
            if (!allocated) {
                response.status(503);
                response.type("application/json");
                return gson.toJson(new LoginResponse(null));
            }


            //domain name of edge assigned to user
            String domainName= user.getCurrentEdgeNodeId()+"."+Configuration.PLATFORM_NODE_BASE_DOMAIN;

            envoyConfigurationServer.addAltSvcRedirectRouteToProxy("cloud", user.getUsername(), authId, domainName);

            response.status(200);
            response.type("application/json");
            response.header("Alt-Svc", "h2=\""+domainName+":443\";");
            response.cookie("." + Configuration.PLATFORM_DOMAIN, "/", "authID", authId, 60*60*6, false, false);
            return gson.toJson(new LoginResponse(Configuration.PLATFORM_CLOUD_DOMAIN));
        }
    }

    private static String logout(Request request, Response response) {
        String userCookie = request.cookie("authID");
        String requestBody = request.body();
        LogoutRequest logoutRequest = gson.fromJson(requestBody, LogoutRequest.class);
        String username = logoutRequest.getUsername();

        User user = Configuration.users.get(username);

        //check if user exists
        if (user == null) {
            response.status(401);
            response.type("application/json");
            return gson.toJson(new LogoutResponse());
        }

        synchronized (user) {
            //check if user is logged and the request has the correct cookie
            if (user.getCookie() == null || !user.getCookie().equals(userCookie)) {
                response.status(401);
                response.type("application/json");
                return gson.toJson(new LogoutResponse());
            }

            //delete route and cluster related to a user
            Orchestrator.envoyConfigurationServer.deleteAllUserResourcesFromProxy(user.getUsername(), user.getCurrentEdgeNodeId());
            if (user.getFormerEdgeNodeId() != null) {
                Orchestrator.envoyConfigurationServer.deleteAllUserResourcesFromProxy(user.getUsername(), user.getFormerEdgeNodeId());
            }

            //remove containers
            Configuration.edgeNodes.get(user.getCurrentEdgeNodeId()).deallocateUserResources(user.getUsername());

            //deleteAltSvrRedirect on cloud Envoy node
            envoyConfigurationServer.deleteRouteByNameFromProxy("cloud", user.getUsername()+"-user");

            //reset fields in user data structure
            user.setCurrentEdgeNodeId(null);
            user.setFormerEdgeNodeId(null);
            user.setCookie(null);
            user.setStatus(null);

            response.status(200);
            response.header("Alt-Svc", "clear");
            return gson.toJson(new LogoutResponse());
        }
    }


    private static String migrate(Request request, Response response) {
        String username = request.params(":username");
        MigrationRequest migrationRequest = gson.fromJson(request.body(), MigrationRequest.class);
        List<String> edgeNodeIDs = migrationRequest.getEdgeNodeList();
        logger.info("Migration request for user:"+username+ " EdgeNodeList:"+edgeNodeIDs);

        //find user to migrate
        User user = Configuration.users.get(username);

        //check if user exists
        if(user==null ) {
            response.status(401);
            response.type("application/json");
            return "";
        }

        synchronized (user) {
            //check if user is not logged (cookie is null)
            if(user.getCookie()==null) {
                response.status(401);
                response.type("application/json");
                return "";
            }

            //if user is already on the best MEC, no need to migrate
            if (user.getCurrentEdgeNodeId().equals(edgeNodeIDs.get(0))) {
                response.status(204);
                return "";
            }

            if (user.getStatus()==UserStatus.MIGRATING) {
                //remove Alt-Svc from route of the node
                envoyConfigurationServer.convertRouteToStableByUserFromProxy(user.getUsername(),user.getFormerEdgeNodeId());

                // We need to fix the Alt-Svc redirect on the envoy cloud instance to point to the former one
                String domainName= user.getFormerEdgeNodeId()+"."+Configuration.PLATFORM_NODE_BASE_DOMAIN;
                envoyConfigurationServer.addAltSvcRedirectRouteToProxy("cloud", user.getUsername(), user.getCookie(), domainName);

                //remove all resources to the migration destination node
                envoyConfigurationServer.deleteAllUserResourcesFromProxy(user.getUsername(), user.getCurrentEdgeNodeId());
                Configuration.edgeNodes.get(user.getCurrentEdgeNodeId()).deallocateUserResources(user.getUsername());

                //former node became current one
                user.setCurrentEdgeNodeId(user.getFormerEdgeNodeId());
                user.setFormerEdgeNodeId(null);


            }

            //resource allocation
            boolean allocated = false;
            for (String edgeId : edgeNodeIDs) {
                // node id is the same that I'm trying to migrate to/I'm now
                if (user.getCurrentEdgeNodeId().equals(edgeId)) {
                    user.setStatus(UserStatus.STABLE);
                    response.status(204);
                    return "";
                }

                EdgeNode edgeNode = Configuration.edgeNodes.get(edgeId);
                if (edgeNode == null) continue;
                allocated = Configuration.edgeNodes.get(edgeId).allocateUserResources(user.getUsername(), user.getCookie(), true);
                if (allocated) {
                    //set EdgeNodeId and cookie in User data structure
                    user.setFormerEdgeNodeId(user.getCurrentEdgeNodeId());
                    user.setCurrentEdgeNodeId(edgeId);
                    break;
                }
            }

            //migration failed, user remain on the same node without migrate
            if (!allocated) {
                response.status(503);
                response.type("application/json");
                return "";
            }

            //signal that user is migrating
            user.setStatus(UserStatus.MIGRATING);

            // We need to fix the Alt-Svc redirect on the envoy cloud instance to point to the new edge
            String domainName= user.getCurrentEdgeNodeId()+"."+Configuration.PLATFORM_NODE_BASE_DOMAIN;
            envoyConfigurationServer.addAltSvcRedirectRouteToProxy("cloud", user.getUsername(), user.getCookie(), domainName);

            //redirect route to new Edge node
            envoyConfigurationServer.convertRouteToMigratingByUserFromProxy(username, user.getFormerEdgeNodeId(), user.getCurrentEdgeNodeId());

            response.status(204);
            return "";
        }

    }


    private static String finalizeMigration(Request request, Response response) {
        String username = request.params(":username");
        String edgeNodeId = request.params(":edgeNodeId");

        logger.info("Migration feedback for user "+username+" from EdgeNode "+edgeNodeId);

        //find user to migrate
        User user = Configuration.users.get(username);

        //check if user exists
        if(user==null ) {
            response.status(401);
            response.type("application/json");
            return "";
        }

        synchronized (user) {
            //check if user is not logged (cookie is null)
            if (user.getCookie() == null || user.getStatus()!=UserStatus.MIGRATING ||
                    !user.getCurrentEdgeNodeId().equals(edgeNodeId)) {
                logger.trace("Migration feedback not correct (may be a duplicate)");
                response.status(401);
                response.type("application/json");
                return "";
            }

            //remove feedback from route on the new edge node
            envoyConfigurationServer.removeFeedbackFromRouteByUserFromProxy(username, edgeNodeId);

            envoyConfigurationServer.deleteAllUserResourcesFromProxy(username, user.getFormerEdgeNodeId());
            Configuration.edgeNodes.get(user.getFormerEdgeNodeId()).deallocateUserResources(username);
            user.setStatus(UserStatus.STABLE);


            //no need to maintain former edgeId after migration feedback
            user.setFormerEdgeNodeId(null);

        }

        logger.info("Migration of user "+username+" to Edge Node "+ edgeNodeId + " completed");
        response.status(204);
        return "";
    }


}
