package it.lorenzogiorgi.tesi;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.google.gson.Gson;
import it.lorenzogiorgi.tesi.api.LoginRequest;
import it.lorenzogiorgi.tesi.api.LoginResponse;
import it.lorenzogiorgi.tesi.api.LogoutRequest;
import it.lorenzogiorgi.tesi.api.LogoutResponse;
import it.lorenzogiorgi.tesi.common.*;
import it.lorenzogiorgi.tesi.common.Service;
import it.lorenzogiorgi.tesi.dns.DNSUpdater;
import it.lorenzogiorgi.tesi.envoy.EnvoyConfigurationServer;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Orchestrator {
    public static EnvoyConfigurationServer envoyConfigurationServer;

    public static Gson gson;


    public static void main(String[] arg) {
        gson = new Gson();
        envoyConfigurationServer = new EnvoyConfigurationServer();

        Spark.ipAddress(Configuration.ORCHESTRATOR_API_IP);
        Spark.port(Configuration.ORCHESTRATOR_API_PORT);
        Spark.post("/login", ((request, response) -> login(request, response)));
        Spark.post("/logout", ((request, response) -> logout(request, response)));
        Spark.get("/envoyconfiguration/:envoyNodeID", (request, response)  -> serveEnvoyConfiguration(request, response));
        Spark.get("/test", ((request, response) -> "TEST"));
        Spark.awaitInitialization();

        //initialize envoy instances
        initializeEnvoyInstances();

        //initialize Authenitication Server (DNS mapping with Auth URL of all applications)
        inizializeAutheniticationServers();

        envoyConfigurationServer.awaitTermination();
    }

    private static void inizializeAutheniticationServers() {
        for(String applicationName: Configuration.applications.keySet()) {
            Application application = Configuration.applications.get(applicationName);
            DNSUpdater.updateDNSRecord(application.getDomain(), application.getAuthDomain(), "A", 600, Configuration.ORCHESTRATOR_API_IP);
        }
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

    private static void initializeEnvoyInstances() {
        HashMap<String, MECNode> mecNodesMap = Configuration.mecNodes;
        HashMap<String, Application> applicationsMap = Configuration.applications;


        //
        for(String mecId: mecNodesMap.keySet()) {
            DockerClient dockerClient = getDockerClient(mecNodesMap.get(mecId));

            //obtain container list
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowSize(true)
                    .withShowAll(true)
                    .exec();

            //kill and remove all container
            for(Container container: containers) {
                if(!container.getState().equals("exited")) {
                    dockerClient.killContainerCmd(container.getId()).exec();
                }
                dockerClient.removeContainerCmd(container.getId()).exec();
            }


        }


        for(String applicationName: applicationsMap.keySet()) {
            Application application = applicationsMap.get(applicationName);
            List<String> allowedMECId = application.getAllowedMECId()
                    .stream()
                    .filter(mecNodesMap::containsKey)
                    .collect(Collectors.toList());

            for(String mecId: allowedMECId) {
                DockerClient dockerClient = getDockerClient(mecNodesMap.get(mecId));

                //create Dockerfile
                String configurationFileUrl= "http://"+Configuration.ORCHESTRATOR_API_IP+":"
                        +Configuration.ORCHESTRATOR_API_PORT+"/envoyconfiguration/"+mecId+"-"+applicationName;
                String dockerfileString =
                        "FROM envoyproxy/envoy-dev:latest\n" +
                                "\n" +
                                "ENV DEBIAN_FRONTEND=noninteractive\n" +
                                "\n" +
                                "RUN apt-get update -y\n" +
                                "RUN apt-get -qq update \\\n" +
                                "    && apt-get -qq install --no-install-recommends -y curl nano\\\n" +
                                "    && apt-get -qq autoremove -y \\\n" +
                                "    && apt-get clean \\\n" +
                                "    && rm -rf /tmp/* /var/tmp/* /var/lib/apt/lists/* \n" +
                                "RUN curl "+ configurationFileUrl +" -o envoy-configuration.yaml\n" +
                                "RUN mv ./envoy-configuration.yaml /etc/front-envoy.yaml\n" +
                                "RUN chmod go+r /etc/front-envoy.yaml\n" +
                                "CMD [\"/usr/local/bin/envoy\", \"-c\", \"/etc/front-envoy.yaml\", \"--service-cluster\", \"front-proxy\"]";

                String imageId;
                try {
                    File dockerfile= new File("./configuration/Dockerfile-"+mecId+applicationName);
                    try (FileWriter writer = new FileWriter(dockerfile)) {
                        writer.write(dockerfileString);
                        writer.flush();
                    }

                    //create custom envoy image
                    imageId = dockerClient.buildImageCmd()
                            .withDockerfile(dockerfile)
                            .withPull(true)
                            .withNoCache(true)
                            .withTags(Stream.of("envoy:"+applicationName).collect(Collectors.toSet()))
                            .exec(new BuildImageResultCallback())
                            .awaitImageId();
                    dockerfile.delete();

                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                //create Envoy container
                CreateContainerResponse container = dockerClient
                        .createContainerCmd(imageId)
                        .withName(mecId+"-"+applicationName)
                        .withExposedPorts(ExposedPort.tcp(80))
                        .withExposedPorts(new ArrayList<>(Arrays.asList(
                                ExposedPort.tcp(80),
                                ExposedPort.tcp(10000)
                        )))
                        .withHostConfig(HostConfig.newHostConfig()
                                .withPortBindings(new ArrayList<PortBinding>(Arrays.asList(
                                        PortBinding.parse(mecNodesMap.get(mecId).getDockerIpAddress()+":80/tcp:80"),
                                        PortBinding.parse(mecNodesMap.get(mecId).getDockerIpAddress()+":10000/tcp:10000"))
                                        )
                                )
                        )
                        .exec();

                // start the container
                dockerClient.startContainerCmd(container.getId()).exec();

                //update data structures
                MECNode mecNode = Configuration.mecNodes.get(mecId);
                if(mecNode.getFrontProxies()==null) {
                    mecNode.setFrontProxy(new HashMap<>());
                }
                String proxyId = mecId+"-"+applicationName;
                mecNode.getFrontProxies().put(applicationName, new EnvoyProxy(mecNode.getDockerIpAddress(), proxyId));

                //update DNS entry for Edge Proxy (useful for redirect)
                String proxyDomain = proxyId+"."+application.getDomain();
                DNSUpdater.updateDNSRecord(application.getDomain(), proxyDomain, "A",  600, mecNode.getFrontProxies().get(applicationName).getIpAddress());

                //cleanup dangling images
                List<Image> images = dockerClient.listImagesCmd()
                        .withDanglingFilter(true).exec();
                for(Image image:images) {
                    dockerClient.removeImageCmd(image.getId()).exec();
                }

            }

        }
        System.out.println("Envoy nodes initialized");
    }

    private static List<String> findNearestMECNode() {
        List<String> mecNodes  = new ArrayList<>(Configuration.mecNodes.keySet());
        Collections.shuffle(mecNodes);
        return mecNodes;
    }

    private static boolean allocateUserResources(String applicationName, String username, String authCookie, String userDomainName)  {
        //set a preference order between the MECNode, as the AMS API could do
        List<String> mecNodeIds = findNearestMECNode();

        //filter MECNode that are allowed for application, it keeps order provided by AMS
        List<String> allowedMECId = Configuration.applications.get(applicationName).getAllowedMECId();
        List<String> mecNodeIDList = mecNodeIds
                .stream()
                .filter(allowedMECId::contains)
                .collect(Collectors.toList());

        //get User resource to allocate
        User user = Configuration.users
                .get(applicationName)
                .stream()
                .filter(u -> u.getUsername().equals(username))
                .findFirst()
                .get();

        Application application = Configuration.applications.get(applicationName);
        List<Service> serviceList = application.getServices();


        //syncronized block to avoid TOCTOU concurrency problem
        synchronized (Orchestrator.class) {
            //compute user requirement
            double memoryRequirement = serviceList.stream().map(a-> a.getMaxMemory()).reduce((double) 0, (a, b) -> a + b);
            double cpuRequirement = serviceList.stream().map(a-> a.getMaxCPU()).reduce((double) 0, (a, b) -> a + b);

            //find the best (e.g. nearest, computed by the AMS) edge that can fit user requirement
            String selectedMECId=null;
            for (int i = 0; i < mecNodeIDList.size(); i++) {
                String mecId = mecNodeIDList.get(i);
                MECNode node = Configuration.mecNodes.get(mecId);
                System.out.println(node.getAvailableCPU());
                System.out.println(node.getAvailableMemory());
                System.out.println(i);
                if (node.getAvailableCPU() >= cpuRequirement && node.getAvailableMemory() >= memoryRequirement) {
                    selectedMECId=mecId;
                    break;
                }
            }

            //no MEC are able to satisfy user requirements
            if(selectedMECId==null) return false;
            System.out.println("MECNode che soddisfa i requirement: "+selectedMECId);

            //get the selected MECnode
            MECNode mecNode = Configuration.mecNodes.get(selectedMECId);


            //connect to Docker demon on the target MECNode
            DockerClient dockerClient = getDockerClient(mecNode);

            //pull required images
            for(Service service:serviceList) {
                System.out.println("Pulling "+service.getImageName()+" for user "+user.getUsername());
                try {
                    dockerClient.pullImageCmd(service.getImageName())
                            .withTag(service.getImageTag())
                            .exec(new PullImageResultCallback())
                            .awaitCompletion();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            //run containers with the options specified
            for (Service service: serviceList) {
                CreateContainerResponse container = dockerClient
                        .createContainerCmd(service.getImageName()+":"+service.getImageTag())
                        .withName(user.getUsername()+"-"+service.getName())
                        .withHostName(user.getUsername()+"-"+service.getName())
                        .withHostConfig(HostConfig.newHostConfig()
                                .withCpuPeriod(100000L)
                                .withCpuQuota((long) (service.getMaxCPU()*100000))
                                .withMemory((long) service.getMaxMemory()*1000*1000))
                        .exec();


                // start the container
                dockerClient.startContainerCmd(container.getId()).exec();

                //get ip of the created container
                InspectContainerResponse inspectContainerResponse = dockerClient.inspectContainerCmd(container.getId()).exec();
                String ip = inspectContainerResponse.getNetworkSettings().getNetworks().get("bridge").getIpAddress();

                //get Envoy front proxy ID
                String proxyID = selectedMECId+"-"+applicationName;

                //set envoy clusters and routes
                envoyConfigurationServer.addListenerToProxy(proxyID, "default", "0.0.0.0", 80);

                envoyConfigurationServer.addRouteToProxy(proxyID, user.getUsername(), application.getDomain(),
                        "default", service.getEndpointMapping(), authCookie, service.getName());

                envoyConfigurationServer.addClusterToProxy(proxyID, user.getUsername(), service.getName(), ip, service.getExposedPort());


            }

            //set DNS domain
            DNSUpdater.updateDNSRecord(application.getDomain(), userDomainName, "A",  5, mecNode.getFrontProxies().get(applicationName).getIpAddress());

            //substract requested resources
            mecNode.setAvailableCPU(mecNode.getAvailableCPU()-cpuRequirement);
            mecNode.setTotalMemory(mecNode.getAvailableMemory()-memoryRequirement);

            //set MECId in User data structure
            if(user.getCurrentMECId()!=null) {
                user.setFormerMECId(user.getCurrentMECId());
                user.setCurrentMECId(selectedMECId);
            }

            return true;

        }

    }

    private static DockerClient getDockerClient(MECNode mecNode) {
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(URI.create("tcp://"+ mecNode.getDockerIpAddress()+":"+ mecNode.getDockerPort()))
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();


        DockerClientConfig custom = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("tcp://"+ mecNode.getDockerIpAddress()+":"+ mecNode.getDockerPort())
                .build();
        return DockerClientImpl.getInstance(custom, httpClient);
    }

    private static String login(Request request, Response response) {
        String requestBody = request.body();
        LoginRequest loginRequest = gson.fromJson(requestBody, LoginRequest.class);
        String applicationName = loginRequest.getApplicationName();
        Application application = Configuration.applications.get(applicationName);

        //check username and password
        List<User> userList = Configuration.users.get(applicationName);
        Optional<User> optionalUser = Optional.ofNullable(userList).orElse(Collections.emptyList())
                .stream()
                .filter(a -> a.getUsername().equals(loginRequest.getUsername()) &&
                        a.getPassword().equals(loginRequest.getPassword()))
                .findFirst();
        if(!optionalUser.isPresent()) {
            response.status(401);
            response.type("application/json");
            return gson.toJson(new LoginResponse(false, null));
        }

        //login successful
        User user = optionalUser.get();

        //compute authId cookie
        byte[] array= new byte[32];
        new Random().nextBytes(array);
        String authId= toHexString(array);
        System.out.println(authId);

        //compute domain name for the user
        String domainName = user.getUsername()+"."+application.getDomain();

        //resource allocation
        boolean allocated = allocateUserResources(applicationName, user.getUsername(), authId, domainName);

        if(!allocated) {
            response.status(503);
            response.type("application/json");
            return gson.toJson(new LoginResponse(false, null));
        }


        response.status(200);
        response.type("application/json");
        response.cookie("."+application.getDomain(), "/", "authID", authId, 3600, false, false);
        return gson.toJson(new LoginResponse(true, domainName));
    }

    private static String logout(Request request, Response response) {
        String userCookie = request.cookie("authID");
        String requestBody = request.body();
        LogoutRequest logoutRequest = gson.fromJson(requestBody, LogoutRequest.class);
        String applicationName= logoutRequest.getApplicationName();
        String username = logoutRequest.getUsername();


        List<User> userList = Configuration.users.get(applicationName);
        Optional<User> optionalUser = Optional.ofNullable(userList).orElse(Collections.emptyList())
                .stream()
                .filter(u -> u.getUsername().equals(username) && u.getCookie()!=null && u.getCookie().equals(userCookie))
                .findFirst();

        if(!optionalUser.isPresent()) {
            response.status(401);
            response.type("application/json");
            return gson.toJson(new LogoutResponse());
        }


        deallocateUserResources(applicationName, optionalUser.get());
        response.status(200);
        return gson.toJson(new LogoutResponse());
    }

    private static void deallocateUserResources(String applicationName, User user) {
        String proxyId = user.getCurrentMECId()+"-"+applicationName;

        //delete route and cluster related to a user
        envoyConfigurationServer.deleteAllUserResourcesFromProxy(user.getUsername(), proxyId);
        if(user.getFormerMECId()!=null) {
            proxyId = user.getFormerMECId()+"-"+applicationName;
            envoyConfigurationServer.deleteAllUserResourcesFromProxy(user.getUsername(), proxyId);
        }


        //delete all container related to the user
        DockerClient dockerClient = getDockerClient(Configuration.mecNodes.get(user.getCurrentMECId()));

        //obtain container user list
        List<Container> containers = dockerClient.listContainersCmd()
                .withShowSize(true)
                .withShowAll(true)
                .withNameFilter(Collections.singletonList(user.getUsername() + "-"))
                .exec();

        //kill and remove all user container
        for(Container container: containers) {
            if(!container.getState().equals("exited")) {
                dockerClient.killContainerCmd(container.getId()).exec();
            }
            dockerClient.removeContainerCmd(container.getId()).exec();
        }

        user.setCurrentMECId(null);
        user.setFormerMECId(null);
        user.setCookie(null);

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
