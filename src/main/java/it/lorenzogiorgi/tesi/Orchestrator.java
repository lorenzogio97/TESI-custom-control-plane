package it.lorenzogiorgi.tesi;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.google.gson.Gson;
import it.lorenzogiorgi.tesi.api.LoginRequest;
import it.lorenzogiorgi.tesi.api.LoginResponse;
import it.lorenzogiorgi.tesi.common.*;
import it.lorenzogiorgi.tesi.dns.DNSUpdater;
import it.lorenzogiorgi.tesi.envoy.EnvoyConfigurationServer;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class Orchestrator {
    public static EnvoyConfigurationServer envoyConfigurationServer;

    public static Gson gson;


    public static void main(String[] arg) throws IOException, InterruptedException {
        gson = new Gson();
        envoyConfigurationServer = new EnvoyConfigurationServer(18000);


        Spark.ipAddress("127.0.0.1");
        Spark.port(8080);
        Spark.post("/login", ((request, response) -> login(request, response)));
        Spark.get("/test", ((request, response) -> "TEST"));

        envoyConfigurationServer.awaitTermination();
    }


    private static boolean allocateUserResources(String username, String authCookie, String zoneName, String domainName)  {
        //set a preference order between the MECNode, as the AMS API could do
        List<MECNode> mecNodeList = new ArrayList<>(Configuration.mecNodes);
        Collections.shuffle(mecNodeList);

        //get User resource to allocate
        User user = Configuration.users.stream().filter(u -> u.getUsername().equals(username)).findFirst().get();
        List<Service> serviceList = user.getServices();



        //syncronized block to avoid TOCTOU concurrency problem
        synchronized (Orchestrator.class) {
            //compute user requirement
            double memoryRequirement = serviceList.stream().map(a-> a.getMaxMemory()).reduce((double) 0, (a, b) -> a + b);
            double cpuRequirement = serviceList.stream().map(a-> a.getMaxCPU()).reduce((double) 0, (a, b) -> a + b);

            //find the best edge that can fit user requirement
            int edgeNumber=-1;
            for (int i = 0; i < mecNodeList.size(); i++) {
                MECNode node = mecNodeList.get(i);
                if (node.getAvailableCPU() <= cpuRequirement && node.getAvailableMemory() <= memoryRequirement) {
                    edgeNumber=i;
                    break;
                }
            }
            //no MEC are able to satisfy user requirements
            if(edgeNumber==-1) return false;

            //get the selected MECnode
            MECNode mecNode = mecNodeList.get(edgeNumber);

            //substract requested resources
            mecNode.setAvailableCPU(mecNode.getAvailableCPU()-cpuRequirement);
            mecNode.setTotalMemory(mecNode.getAvailableMemory()-memoryRequirement);

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
                CreateContainerResponse container = dockerClient.createContainerCmd(service.getImageName()+":"+service.getImageTag())
                        .withName(user.getUsername()+"-"+service.getName())
                        .withHostName(user.getUsername()+"-"+service.getName())
                        .withHostConfig(HostConfig.newHostConfig()
                                .withCpuPeriod(100000L)
                                .withCpuQuota((long) (service.getMaxCPU()*100000))
                                .withMemory((long) service.getMaxMemory()*1000*1000))
                        .exec();


                dockerClient.startContainerCmd(container.getId()).exec();
                //get ip of the created container
                InspectContainerResponse inspectContainerResponse = dockerClient.inspectContainerCmd(container.getId()).exec();
                String ip = inspectContainerResponse.getNetworkSettings().getNetworks().get("bridge").getIpAddress();

                //set envoy clusters and routes
                envoyConfigurationServer.addListenerToProxy(mecNode.getFrontProxy().getId(), "default", "0.0.0.0", 80);

                envoyConfigurationServer.addRouteToProxy(user.getUsername(), mecNode.getFrontProxy().getId(), "lorenzogiorgi.com",
                        "default", service.getEndpointMapping(), authCookie, service.getName());

                envoyConfigurationServer.addClusterToProxy(user.getUsername(), mecNode.getFrontProxy().getId(), service.getName(), ip, service.getExposedPort());

                //setDNS domain
                DNSUpdater.updateDNSRecord("lorenzogiorgi.com", domainName, "A",  5, mecNode.getFrontProxy().getIpAddress());

            }

            return true;


        }

    }
    private static String login(Request request, Response response) {
        String requestBody = request.body();
        LoginRequest loginRequest = gson.fromJson(requestBody, LoginRequest.class);

        //check username and password
        List<User> userList = Configuration.users;
        Optional<User> optionalUser = userList.stream().filter(a -> a.getUsername().equals(loginRequest.getUsername()) &&
                a.getPassword().equals(loginRequest.getPassword())).findFirst();
        if(!optionalUser.isPresent()) {
            response.status(401);
            response.type("application/json");
            return gson.toJson(new LoginResponse(false, null));
        }

        //login successful
        User user = optionalUser.get();

        //compute authId cookie
        String authId= "1000";

        //compute domain name for the user
        String domainName = user.getUsername()+".lorenzogiorgi.com";

        //resource allocation
        boolean allocated = allocateUserResources(user.getUsername(), authId, "lorenzogiorgi.com",
                domainName);

        if(!allocated) {
            response.status(503);
            response.type("application/json");
            return gson.toJson(new LoginResponse(false, null));
        }

        response.status(200);
        response.type("application/json");
        response.cookie("authID", authId);
        return gson.toJson(new LoginResponse(true, domainName));
    }


}
