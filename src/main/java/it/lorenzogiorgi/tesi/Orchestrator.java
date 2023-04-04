package it.lorenzogiorgi.tesi;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Config;
import com.github.dockerjava.api.model.HostConfig;
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
import it.lorenzogiorgi.tesi.dns.DNSUpdater;
import it.lorenzogiorgi.tesi.envoy.EnvoyConfigurationServer;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class Orchestrator {
    public static EnvoyConfigurationServer envoyConfigurationServer;

    public static Gson gson;


    public static void main(String[] arg) throws IOException, InterruptedException {
        gson = new Gson();
        envoyConfigurationServer = new EnvoyConfigurationServer();

        //initialize envoy instances


        Spark.ipAddress(Configuration.ORCHESTRATOR_API_IP);
        Spark.port(Configuration.ORCHESTRATOR_API_PORT);
        Spark.post("/login", ((request, response) -> login(request, response)));
        Spark.post("/logout", ((request, response) -> logout(request, response)));
        Spark.get("/test", ((request, response) -> "TEST"));

        envoyConfigurationServer.awaitTermination();
    }

    private static List<String> findNearestMECNode() {
        List<String> mecNodes  = new ArrayList<>(Configuration.mecNodes.keySet());
        Collections.shuffle(mecNodes);
        return mecNodes;
    }

    private static boolean allocateUserResources(String applicationName, String username, String authCookie, String userDomainName)  {
        //set a preference order between the MECNode, as the AMS API could do
        List<String> mecNodeID = findNearestMECNode();

        //filter MECNode that are allowed for application, it keeps order provided by AMS
        List<String> allowedMECId = Configuration.applications.get(applicationName).getAllowedMECId();
        List<MECNode> mecNodeList = mecNodeID
                .stream()
                .filter(allowedMECId::contains)
                .map(id -> Configuration.mecNodes.get(id))
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

            //find the best edge that can fit user requirement
            int edgeNumber=-1;
            for (int i = 0; i < mecNodeList.size(); i++) {
                MECNode node = mecNodeList.get(i);
                System.out.println(node.getAvailableCPU());
                System.out.println(node.getAvailableMemory());
                System.out.println(i);
                if (node.getAvailableCPU() >= cpuRequirement && node.getAvailableMemory() >= memoryRequirement) {
                    edgeNumber=i;
                    break;
                }
            }

            //no MEC are able to satisfy user requirements
            if(edgeNumber==-1) return false;
            System.out.println("Edge che soddisfa i requirement: "+edgeNumber);

            //get the selected MECnode
            MECNode mecNode = mecNodeList.get(edgeNumber);


            //connect to Docker demon on the target MECNode
            DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(URI.create("tcp://"+mecNode.getDockerIpAddress()+":"+mecNode.getDockerPort()))
                    .maxConnections(100)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .responseTimeout(Duration.ofSeconds(45))
                    .build();


            DockerClientConfig custom = DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost("tcp://"+mecNode.getDockerIpAddress()+":"+mecNode.getDockerPort())
                    .build();
            DockerClient dockerClient = DockerClientImpl.getInstance(custom, httpClient);

            //pull required images
            for(Service service:serviceList) {
                System.out.println("Pulling "+service.getImageName()+" for user "+user.getUsername());
                dockerClient.pullImageCmd(service.getImageName())
                        .withTag(service.getImageTag())
                        .exec(new PullImageResultCallback());
            }

            //run containers with the options specified
            for (Service service: serviceList) {
                System.out.println(service.getMaxMemory());
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
                String proxyID = mecNode.getFrontProxies().get(applicationName).getId();

                //set envoy clusters and routes
                envoyConfigurationServer.addListenerToProxy(proxyID, "default", "0.0.0.0", 80);

                envoyConfigurationServer.addRouteToProxy(proxyID, user.getUsername(), application.getDomain(),
                        "default", service.getEndpointMapping(), authCookie, service.getName());

                envoyConfigurationServer.addClusterToProxy(proxyID, user.getUsername(), service.getName(), ip, service.getExposedPort());

                //set DNS domain
                DNSUpdater.updateDNSRecord(application.getDomain(), userDomainName, "A",  5, mecNode.getFrontProxies().get(applicationName).getIpAddress());


            }

            //substract requested resources
            mecNode.setAvailableCPU(mecNode.getAvailableCPU()-cpuRequirement);
            mecNode.setTotalMemory(mecNode.getAvailableMemory()-memoryRequirement);

            return true;

        }

    }
    private static String login(Request request, Response response) {
        String requestBody = request.body();
        LoginRequest loginRequest = gson.fromJson(requestBody, LoginRequest.class);
        String applicationName = loginRequest.getAppName();
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

        //TODO: check if the usercookie correspond to the one assigned to the logged user
        List<User> userList = Configuration.users.get(applicationName);
        Optional<User> optionalUser = Optional.ofNullable(userList).orElse(Collections.emptyList())
                .stream()
                .filter(u -> u.getUsername().equals(username) && u.getCookie().equals(userCookie))
                .findFirst();

        if(!optionalUser.isPresent()) {
            response.status(401);
            response.type("application/json");
            return gson.toJson(new LogoutResponse());
        }


        deallocateUserResources(applicationName, username);
        response.status(200);
        return gson.toJson(new LogoutResponse());
    }

    private static void deallocateUserResources(String applicationName, String username) {

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
