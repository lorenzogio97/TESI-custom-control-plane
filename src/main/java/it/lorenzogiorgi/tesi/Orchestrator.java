package it.lorenzogiorgi.tesi;

import com.google.gson.Gson;
import it.lorenzogiorgi.tesi.api.*;
import it.lorenzogiorgi.tesi.configuration.*;
import it.lorenzogiorgi.tesi.dns.DNSManagement;
import it.lorenzogiorgi.tesi.envoy.EnvoyConfigurationServer;
import it.lorenzogiorgi.tesi.utiliy.FileUtility;
import it.lorenzogiorgi.tesi.utiliy.TestUtility;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Orchestrator {
    public static EnvoyConfigurationServer envoyConfigurationServer;
    public static Gson gson=new Gson();
    public static ConcurrentHashMap<String, Long> securityTokenMap = new ConcurrentHashMap<>();
    private static final Logger logger = LogManager.getLogger(Orchestrator.class.getName());
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public static void main(String[] arg) {

        long t0 = System.currentTimeMillis();
        //initialize DNS for Auth, Orchestrator and Envoy conf server.
        initializeDNS();
        long t1 = System.currentTimeMillis();

        envoyConfigurationServer = new EnvoyConfigurationServer();
        long t2 = System.currentTimeMillis();

        // keystone creation from certificate: https://stackoverflow.com/questions/906402/how-to-import-an-existing-x-509-certificate-and-private-key-in-java-keystore-to
        Spark.secure("./src/main/resources/server.keystore", "lorenzo", null, null);
        Spark.ipAddress(Configuration.ORCHESTRATOR_API_IP);
        Spark.port(Configuration.ORCHESTRATOR_API_PORT);

        Spark.get("/envoyconfiguration/:envoyNodeID", Orchestrator::serveEnvoyConfiguration);
        Spark.get("/platform-tls/:what/:token", Orchestrator::servePlatformTls);
        Spark.get("/envoy-mtls/:what/:token", Orchestrator::serveEnvoymTls);
        Spark.after((request, response) -> {
            logger.trace(String.format("%s %s %s", request.requestMethod(), request.url(), request.body()));
        });
        Spark.awaitInitialization();
        long t3 = System.currentTimeMillis();

        //initialize node nodes
        initializeNodes();
        long t4 = System.currentTimeMillis();

        //API for user needs to be available after Edgenode initialization
        Spark.post("/login", (Orchestrator::login));
        Spark.post("/logout", (Orchestrator::logout));
        Spark.post("/migrate", (Orchestrator::migrate));
        Spark.post("/migration_feedback/:username/:edgeNodeId", (Orchestrator::finalizeMigration));

        //schedule userGarbageCollector execution
        scheduler.scheduleWithFixedDelay(Orchestrator::userGarbageCollector, Configuration.CLIENT_SESSION_DURATION,
                Configuration.ORCHESTRATOR_USER_GARBAGE_DELAY, TimeUnit.SECONDS);

        long t5 = System.currentTimeMillis();

        if (Configuration.PERFORMANCE_TRACING) {
            TestUtility.writeExperimentData("startup", new String[]{String.valueOf(t1 - t0),
                    String.valueOf(t2 - t1), String.valueOf(t3 - t2), String.valueOf(t4 - t3), String.valueOf(t5 - t4)});

            // set TTL for bach DNS experiment
            Spark.get("/dns/:ttl", (Orchestrator::setDNSTTL));
        }
        /*
        System.out.println("Record DNS: " + (t1 - t0));
        System.out.println("xDS API: " + (t2 - t1));
        System.out.println("Conf REST API: " + (t3 - t2));
        System.out.println("Node initialization: " + (t4 - t3));
        System.out.println("User REST API: " + (t5 - t4));
        */

        envoyConfigurationServer.awaitTermination();
    }


    private static void userGarbageCollector() {
        Long checkTime = System.currentTimeMillis();
        logger.info("Start userGarbageCollector execution");
        for(User user: Configuration.users.values()) {
            synchronized (user) {
                //user not logged, continue
                if (user.getCookie() == null && user.getCurrentEdgeNodeId() == null) continue;
                //user logged, check the timestamp
                if (user.getSessionExpiration() < checkTime) {
                    //remove all user resources
                    if (user.getCurrentEdgeNodeId() != null) {
                        Configuration.edgeNodes.get(user.getCurrentEdgeNodeId()).deallocateUserResources(user.getUsername());
                        envoyConfigurationServer.deleteAllUserResourcesFromProxy(user.getUsername(), user.getCurrentEdgeNodeId());
                    }
                    if (user.getFormerEdgeNodeId() != null) {
                        Configuration.edgeNodes.get(user.getFormerEdgeNodeId()).deallocateUserResources(user.getUsername());
                        envoyConfigurationServer.deleteAllUserResourcesFromProxy(user.getUsername(), user.getFormerEdgeNodeId());
                    }

                    //reset user data structure
                    user.setCurrentEdgeNodeId(null);
                    user.setFormerEdgeNodeId(null);
                    user.setCookie(null);
                    user.setStatus(null);
                    user.setSessionExpiration(null);
                    logger.trace("User "+user.getUsername()+ " garbage collected");

                }
            }
        }
        logger.info("End userGarbageCollector execution");
    }

    private static void initializeNodes() {
        List<Thread> threadList = new ArrayList<>();
        //edge nodes
        for(EdgeNode edgeNode: Configuration.edgeNodes.values()) {
            Thread t = new Thread(edgeNode::initialize);
            t.setName(edgeNode.getId());
            threadList.add(t);
            t.start();
        }

        //cloud node
        CloudNode cloudNode = Configuration.cloudNode;
        Thread t = new Thread(cloudNode::initialize);
        t.setName(cloudNode.getId());
        threadList.add(t);
        t.start();

        //wait all thread to finish
        for (Thread thread : threadList) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                String edgeId = thread.getName();
                //since edge node has not been successfully initialized, it is removed from available ones.
                Configuration.edgeNodes.remove(edgeId);
                logger.warn("Node "+edgeId+ " has not been initialized correctly");
            }
        }
        logger.info("Compute nodes initialized");
    }

    private static void initializeDNS() {
        DNSManagement.updateDNSRecord(Configuration.PLATFORM_DOMAIN, Configuration.PLATFORM_ORCHESTRATOR_DOMAIN, "A", 3600, Configuration.ORCHESTRATOR_API_IP);
        DNSManagement.updateDNSRecord(Configuration.PLATFORM_DOMAIN, Configuration.PLATFORM_ENVOY_CONF_SERVER_DOMAIN, "A", 3600, Configuration.ENVOY_CONFIGURATION_SERVER_IP);

        DNSManagement.updateDNSRecord(Configuration.PLATFORM_DOMAIN, Configuration.PLATFORM_CLOUD_DOMAIN, "A", 3600, Configuration.cloudNode.getIpAddress());
        for(EdgeNode edgeNode: Configuration.edgeNodes.values()) {
            //update DNS entry for Edge Proxy
            String id = edgeNode.getId();
            String proxyDomain = id +"."+Configuration.PLATFORM_NODE_BASE_DOMAIN;
            if(DNSManagement.updateDNSRecord(Configuration.PLATFORM_DOMAIN, proxyDomain, "A",  3600, edgeNode.getIpAddress())) {
                logger.info("DNS record for node "+ id + " added to DNS Server");
            } else {
                logger.warn("Error during DNS record add/update for node "+ id);
            }
        }
    }

    private static String setDNSTTL(Request request, Response response) {
        String ttl_str = request.params(":ttl");

        if (ttl_str.equals("no")) {
            Configuration.ENABLE_DNS = false;
            logger.info("Platform disable DNS, Alt-Svc is used instead");
            return "";
        }

        Integer ttl = null;
        try {
            ttl = Integer.parseInt(ttl_str);
        } catch (Exception nfe) {
            nfe.printStackTrace();
            response.status(500);
            return "";
        }

        Configuration.ENABLE_DNS = true;
        Configuration.DNS_USER_TTL = ttl;
        logger.info("DNS migration enabled, DNS TTL="+ttl);
        return "";

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
                "                address: "+Configuration.PLATFORM_ENVOY_CONF_SERVER_DOMAIN+"\n" +
                "                port_value: "+Configuration.ENVOY_CONFIGURATION_SERVER_PORT+"\n" +
                "    name: xds_cluster\n" +
                "    type: STRICT_DNS\n"+
                "    typed_extension_protocol_options:\n" +
                "      envoy.extensions.upstreams.http.v3.HttpProtocolOptions:\n" +
                "        \"@type\": type.googleapis.com/envoy.extensions.upstreams.http.v3.HttpProtocolOptions\n" +
                "        common_http_protocol_options:\n" +
                "          idle_timeout: 0s\n"+
                "        explicit_http_config:\n"+
                "          http2_protocol_options:\n"+
                "            connection_keepalive:\n"+
                "              interval: 75s\n"+
                "              timeout: 10s\n"+
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

    private static List<String> findNearestEdgeNode() {
        List<String> edgeNodes  = new ArrayList<>(Configuration.edgeNodes.keySet());
        Collections.shuffle(edgeNodes);
        return edgeNodes;
    }


    private static String login(Request request, Response response) {
        // Performance Evaluation
        long t0,t1,t2,t3;
        t0 = System.currentTimeMillis();

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

            //if user was already logged, we have its resources already allocated, so we only need to extend its session
            if(user.getCurrentEdgeNodeId()!=null && user.getCookie()!=null) {
                return extendLogin(request, response);
            }

            t1 = System.currentTimeMillis();

            user.setCurrentEdgeNodeId(null);
            user.setFormerEdgeNodeId(null);
            user.setCookie(null);

            //compute authId cookie
            String authId = TokenUtiliy.generateRandomHexString(Configuration.CLIENT_AUTHENTICATION_TOKEN_LENGTH);

            //get nearest EdgeNode
            List<String> nearestEdgeNodeIDs = findNearestEdgeNode();

            //resource allocation
            boolean allocated = false;
            for (String edgeId : nearestEdgeNodeIDs) {
                EdgeNode edgeNode = Configuration.edgeNodes.get(edgeId);
                allocated = edgeNode.allocateUserResources(user.getUsername(), authId, false);
                if (allocated) {
                    //set EdgeNodeId, and session expiration in User data structure
                    user.setCurrentEdgeNodeId(edgeId);
                    user.setCookie(authId);
                    user.setSessionExpiration(System.currentTimeMillis()+Configuration.CLIENT_SESSION_DURATION*1000L);
                    break;
                }
            }

            //allocation failed on all near edge node
            if (!allocated) {
                response.status(503);
                response.type("application/json");
                return gson.toJson(new LoginResponse(null));
            }

            t2 = System.currentTimeMillis();

            //domain name of edge assigned to user
            String domainName= user.getCurrentEdgeNodeId()+"."+Configuration.PLATFORM_NODE_BASE_DOMAIN;

            envoyConfigurationServer.addAltSvcRedirectRouteToProxy("cloud", user.getUsername(), authId, domainName);

            //TEST
            if (Configuration.ENABLE_DNS) {
                String userDomain = "lorenzo.user.lorenzogiorgi.com";
                String edgeIPAddress = Configuration.edgeNodes.get(user.getCurrentEdgeNodeId()).getIpAddress();
                DNSManagement.updateDNSRecord("lorenzogiorgi.com", userDomain, "A", Configuration.DNS_USER_TTL, edgeIPAddress);
            }

            t3 = System.currentTimeMillis();

            if (Configuration.PERFORMANCE_TRACING)
                TestUtility.writeExperimentData("login", new String[]{String.valueOf(t1-t0),
                        String.valueOf(t2-t1), String.valueOf(t3-t2)});

            response.status(200);
            response.type("application/json");
            response.header("Alt-Svc", "h2=\""+domainName+":443\";");
            response.cookie("." + Configuration.PLATFORM_DOMAIN, "/", "authID", authId,
                    Configuration.CLIENT_SESSION_DURATION, false, false);

            //TEST
            if (Configuration.ENABLE_DNS) {
                String userDomain = "lorenzo.user.lorenzogiorgi.com";
                return gson.toJson(new LoginResponse(userDomain));
            }

            return gson.toJson(new LoginResponse(Configuration.PLATFORM_CLOUD_DOMAIN));
        }
    }

    private static String extendLogin(Request request, Response response) {
        String requestBody = request.body();
        LoginRequest loginRequest = gson.fromJson(requestBody, LoginRequest.class);

        //check username and password
        User user= Configuration.users.get(loginRequest.getUsername());

        //check if user exists
        if(user==null) {
            response.status(400);
            response.type("application/json");
            return "";
        }

        synchronized(user) {
            //check password and cookie
            if(user.getPassword()==null || !user.getPassword().equals(loginRequest.getPassword()) ||
                    user.getCookie()==null) {
                response.status(401);
                response.type("application/json");
                return gson.toJson(new LoginResponse(null));
            }

            //extend session
            user.setSessionExpiration(System.currentTimeMillis()+Configuration.CLIENT_SESSION_DURATION*1000L);

            String domainName= user.getCurrentEdgeNodeId()+"."+Configuration.PLATFORM_NODE_BASE_DOMAIN;

            response.status(200);
            response.type("application/json");
            response.header("Alt-Svc", "h2=\""+domainName+":443\";");
            response.cookie("." + Configuration.PLATFORM_DOMAIN, "/", "authID", user.getCookie(),
                    Configuration.CLIENT_SESSION_DURATION , false, false);

            //TEST
            if (Configuration.ENABLE_DNS) {
                String userDomain = "lorenzo.user.lorenzogiorgi.com";
                return gson.toJson(new LoginResponse(userDomain));
            }

            return gson.toJson(new LoginResponse(Configuration.PLATFORM_CLOUD_DOMAIN));
        }
    }

    private static String logout(Request request, Response response) {
        // Performance Evaluation
        long t0,t1,t2,t3,t4,t5;
        t0 = System.currentTimeMillis();

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

            t1 = System.currentTimeMillis();

            //delete route and cluster related to a user
            Orchestrator.envoyConfigurationServer.deleteAllUserResourcesFromProxy(user.getUsername(), user.getCurrentEdgeNodeId());
            if (user.getFormerEdgeNodeId() != null) {
                Orchestrator.envoyConfigurationServer.deleteAllUserResourcesFromProxy(user.getUsername(), user.getFormerEdgeNodeId());
            }

            t2= System.currentTimeMillis();

            //remove containers
            Configuration.edgeNodes.get(user.getCurrentEdgeNodeId()).deallocateUserResources(user.getUsername());
            if (user.getFormerEdgeNodeId() != null) {
                Configuration.edgeNodes.get(user.getFormerEdgeNodeId()).deallocateUserResources(user.getUsername());
            }
            t3 = System.currentTimeMillis();

            //deleteAltSvrRedirect on cloud Envoy node
            envoyConfigurationServer.deleteRouteByNameFromProxy("cloud", user.getUsername()+"-user");

            t4 = System.currentTimeMillis();

            //reset fields in user data structure
            user.setCurrentEdgeNodeId(null);
            user.setFormerEdgeNodeId(null);
            user.setCookie(null);
            user.setStatus(null);
            user.setSessionExpiration(null);

            t5 = System.currentTimeMillis();
            if (Configuration.PERFORMANCE_TRACING)
                TestUtility.writeExperimentData("logout", new String[]{String.valueOf(t1-t0),
                        String.valueOf(t2-t1), String.valueOf(t3-t2), String.valueOf(t4-t3), String.valueOf(t5-t4)});

            if (Configuration.ENABLE_DNS) {
                String userDomain = "lorenzo.user.lorenzogiorgi.com";
                DNSManagement.deleteDNSRecord("lorenzogiorgi.com", userDomain, "A");
            }

            response.status(200);
            response.header("Alt-Svc", "clear");
            return gson.toJson(new LogoutResponse());
        }
    }

    private static String migrate(Request request, Response response) {
        // Performance Evaluation
        long t0,t1,t2,t3;
        t0 = System.currentTimeMillis();


        MigrationRequest migrationRequest = gson.fromJson(request.body(), MigrationRequest.class);
        List<String> edgeNodeIDs = migrationRequest.getEdgeNodeList();
        String username = migrationRequest.getUsername();
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

            t1 = System.currentTimeMillis();

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

                //TEST
                if (Configuration.ENABLE_DNS) {
                    String userDomain = "lorenzo.user.lorenzogiorgi.com";
                    String edgeIPAddress = Configuration.edgeNodes.get(user.getCurrentEdgeNodeId()).getIpAddress();
                    DNSManagement.updateDNSRecord("lorenzogiorgi.com", userDomain, "A", Configuration.DNS_USER_TTL, edgeIPAddress);
                }

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

            t2 = System.currentTimeMillis();

            //signal that user is migrating
            user.setStatus(UserStatus.MIGRATING);

            // We need to fix the Alt-Svc redirect on the envoy cloud instance to point to the new edge
            String domainName= user.getCurrentEdgeNodeId()+"."+Configuration.PLATFORM_NODE_BASE_DOMAIN;
            envoyConfigurationServer.addAltSvcRedirectRouteToProxy("cloud", user.getUsername(), user.getCookie(), domainName);

            //redirect route to new Edge node
            envoyConfigurationServer.convertRouteToMigratingByUserFromProxy(username, user.getFormerEdgeNodeId(), user.getCurrentEdgeNodeId());

            t3 = System.currentTimeMillis();
            if (Configuration.PERFORMANCE_TRACING)
                TestUtility.writeExperimentData("migrate", new String[]{String.valueOf(t1-t0),
                        String.valueOf(t2-t1), String.valueOf(t3-t2)});

            //TEST
            if (Configuration.ENABLE_DNS) {
                String userDomain = "lorenzo.user.lorenzogiorgi.com";
                String edgeIPAddress = Configuration.edgeNodes.get(user.getCurrentEdgeNodeId()).getIpAddress();
                DNSManagement.updateDNSRecord("lorenzogiorgi.com", userDomain, "A", Configuration.DNS_USER_TTL, edgeIPAddress);
            }

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
