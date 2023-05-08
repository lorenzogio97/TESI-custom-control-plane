package it.lorenzogiorgi.tesi.common;


import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import it.lorenzogiorgi.tesi.Orchestrator;
import it.lorenzogiorgi.tesi.dns.DNSManagement;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EdgeNode {
    private static final Logger logger = LogManager.getLogger(EdgeNode.class.getName());
    private String edgeId;
    private String ipAddress;
    private int dockerPort;
    private double totalMemory;
    private double totalCPU;


    public String getEdgeId() {
        return edgeId;
    }

    public void setEdgeId(String edgeId) {
        this.edgeId = edgeId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }


    public double getTotalMemory() {
        return totalMemory;
    }

    public void setTotalMemory(double totalMemory) {
        this.totalMemory = totalMemory;
    }

    public double getTotalCPU() {
        return totalCPU;
    }

    public void setTotalCPU(double totalCPU) {
        this.totalCPU = totalCPU;
    }



    public int getDockerPort() {
        return dockerPort;
    }

    public void setDockerPort(int dockerPort) {
        this.dockerPort = dockerPort;
    }

    @Override
    public String toString() {
        return "EdgeNode{" +
                "edgeId='" + edgeId + '\'' +
                ", ipAddress='" + ipAddress + '\'' +
                ", dockerPort=" + dockerPort +
                ", totalMemory=" + totalMemory +
                ", totalCPU=" + totalCPU +
                '}';
    }


    public boolean allocateUserResources(String username, String authCookie)  {
        logger.info("Resource allocation on EdgeNode: "+edgeId+ " for user: "+username);
        //get User resource to allocate
        User user = Configuration.users.get(username);

        List<Application> applicationList = user.getApplications()
                .stream()
                .map(a ->Configuration.applications.get(a))
                .collect(Collectors.toList());


        //TODO: remove required resource computation
        //compute user requirement
        double memoryRequirement = applicationList
                .stream()
                .flatMap(a -> a.getMicroservices().stream())
                .map(Microservice::getMaxMemory)
                .reduce((double) 0, Double::sum);
        double cpuRequirement = applicationList
                .stream()
                .flatMap(a -> a.getMicroservices().stream())
                .map(Microservice::getMaxCPU)
                .reduce((double) 0, Double::sum);


        //connect to Docker demon on the target MECNode
        DockerClient dockerClient = getDockerClient();

        logger.info("Pulling images on EdgeNode: "+edgeId+ " for user: "+username);
        //pull required images
        for (Application application: applicationList) {
            for (Microservice microservice : application.getMicroservices()) {
                logger.trace("Pulling " + microservice.getImageName() + " on EdgeNode: "+edgeId+" for user: " + user.getUsername());
                try {
                    dockerClient.pullImageCmd(microservice.getImageName())
                            .withTag(microservice.getImageTag())
                            .exec(new PullImageResultCallback())
                            .awaitCompletion();
                } catch (InterruptedException e) {
                    logger.warn("Pulling images on EdgeNode: "+edgeId+ " for user: "+username+ " failed. Cleanup.");
                    cleanupDanglingImages();
                    return false;
                }
            }
        }
        logger.info("Containers creation on EdgeNode: "+edgeId+ " for user: "+username);
        //run containers with the options specified
        for (Application application: applicationList) {
            for (Microservice microservice : application.getMicroservices()) {
                CreateContainerResponse container= null;
                try {
                    container = dockerClient
                            .createContainerCmd(microservice.getImageName() + ":" + microservice.getImageTag())
                            .withName(user.getUsername() + "-" + microservice.getName())
                            .withHostName(user.getUsername() + "-" + microservice.getName())
                            .withHostConfig(HostConfig.newHostConfig()
                                    .withCpuPeriod(100000L)
                                    .withCpuQuota((long) (microservice.getMaxCPU() * 100000))
                                    .withMemory((long) microservice.getMaxMemory() * 1000 * 1000))
                            .exec();
                    // start the container
                    dockerClient.startContainerCmd(container.getId()).exec();
                } catch (Exception e) {
                    logger.warn("Containers creation failed on EdgeNode: "+edgeId+ " for user: "+username);
                    deallocateUserResources(username);
                    return false;
                }



                //get ip of the created container
                InspectContainerResponse inspectContainerResponse = dockerClient.inspectContainerCmd(container.getId()).exec();
                String ip = inspectContainerResponse.getNetworkSettings().getNetworks().get("bridge").getIpAddress();

                //get Envoy front proxy ID
                String proxyID = this.getEdgeId();

                //compute the endpoint for the microservice
                String endpoint = "/"+application.getName()+"/"+microservice.getName();

                //set envoy clusters and routes
                Orchestrator.envoyConfigurationServer.addListenerToProxy(proxyID, "default", "0.0.0.0", 80);
                Orchestrator.envoyConfigurationServer.addClusterToProxy(proxyID, user.getUsername(), microservice.getName(), ip, microservice.getExposedPort());
                Orchestrator.envoyConfigurationServer.addRouteToProxy(proxyID, user.getUsername(), Configuration.PLATFORM_DOMAIN,
                        "default", endpoint, authCookie, microservice.getName());

            }
        }
        logger.info("Containers created on EdgeNode: "+edgeId+ " for user: "+username);
        return true;

    }

    private DockerClient getDockerClient() {
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(URI.create("tcp://"+ this.getIpAddress()+":"+ this.getDockerPort()))
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();


        DockerClientConfig custom = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("tcp://"+ this.getIpAddress()+":"+ this.getDockerPort())
                .build();
        return DockerClientImpl.getInstance(custom, httpClient);
    }

    public void cleanupContainer() {
        logger.info("Cleanup container of node "+edgeId+ " started");

        DockerClient dockerClient = getDockerClient();

        //obtain container list
        List<Container> containers = dockerClient.listContainersCmd()
                .withShowSize(true)
                .withShowAll(true)
                .exec();


        //kill and remove all container
        for(Container container: containers) {
            try {
                if (!container.getState().equals("exited")) {
                    dockerClient.killContainerCmd(container.getId()).exec();
                }
                dockerClient.removeContainerCmd(container.getId()).exec();
            } catch (NotFoundException nfe) {
                logger.trace("Container ID:"+container.getId() + " not found");
            }
        }

        logger.info("Cleanup container of node "+edgeId+ " done");
    }

    public void cleanupDanglingImages() {
        logger.info("Cleanup images of node "+edgeId+" started");
        DockerClient dockerClient = getDockerClient();

        //cleanup dangling images
        List<Image> images = dockerClient.listImagesCmd()
                .withDanglingFilter(true).exec();
        for(Image image:images) {
            try {
                dockerClient.removeImageCmd(image.getId()).exec();
            } catch (NotFoundException nfe) {
                nfe.printStackTrace();
            }
        }

        logger.info("Cleanup container of node "+edgeId + " done");
    }

    public void initializeFrontProxy() {
        logger.info("Envoy front proxy creation of node "+edgeId+ " started");
        DockerClient dockerClient = getDockerClient();

        //create Dockerfile
        String configurationFileUrl= "http://"+Configuration.ORCHESTRATOR_API_IP+":"
                +Configuration.ORCHESTRATOR_API_PORT+"/envoyconfiguration/"+this.edgeId;
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
            File dockerfile= new File("./configuration/Dockerfile-"+this.edgeId);
            try (FileWriter writer = new FileWriter(dockerfile)) {
                writer.write(dockerfileString);
                writer.flush();
            }

            //create custom envoy image
            imageId = dockerClient.buildImageCmd()
                    .withDockerfile(dockerfile)
                    .withPull(true)
                    .withNoCache(true)
                    .withTags(Stream.of("envoy").collect(Collectors.toSet()))
                    .exec(new BuildImageResultCallback())
                    .awaitImageId();
            dockerfile.delete();
            logger.info("Envoy custom image build of node "+edgeId+ " done");
        } catch (IOException e) {
            logger.error("Envoy custom image build of node "+ edgeId+" failed");
            throw new RuntimeException(e);
        }

        //create Envoy container
        logger.info("Envoy container creation of node "+edgeId+ " started");

        CreateContainerResponse container = dockerClient
                .createContainerCmd(imageId)
                .withName("envoy-"+edgeId)
                //.withExposedPorts(ExposedPort.tcp(80))
                .withExposedPorts(new ArrayList<>(Arrays.asList(
                        ExposedPort.tcp(80),
                        ExposedPort.tcp(10000)
                )))
                .withHostConfig(HostConfig.newHostConfig()
                        .withRestartPolicy(RestartPolicy.unlessStoppedRestart())
                        .withPortBindings(new ArrayList<>(Arrays.asList(
                                        PortBinding.parse(this.getIpAddress()+":80/tcp:80"),
                                        PortBinding.parse(this.getIpAddress()+":10000/tcp:10000"))
                                )
                        )
                )
                .exec();

        // start the container
        dockerClient.startContainerCmd(container.getId()).exec();

        logger.info("Envoy container creation of node "+edgeId+ " done");

        //update DNS entry for Edge Proxy (useful for redirect)
        String proxyDomain = edgeId+"."+Configuration.PLATFORM_NODE_BASE_DOMAIN;
        if(DNSManagement.updateDNSRecord(Configuration.PLATFORM_DOMAIN, proxyDomain, "A",  600, this.getIpAddress())) {
            logger.info("DNS record for node "+edgeId+ " added to DNS Server");
        } else {
            logger.warn("Error during DNS record add/update for node "+edgeId);
        }

        logger.info("Envoy front proxy creation of node "+edgeId+ " done");
    }

    public void initialize() {
        cleanupContainer();
        initializeFrontProxy();
        cleanupDanglingImages();
    }

    public void deallocateUserResources(String username) {
        logger.info("Containers deallocation on EdgeNode: "+edgeId+ " for user: "+username);
        //delete all container related to the user
        DockerClient dockerClient = getDockerClient();

        //obtain container user list
        List<Container> containers = dockerClient.listContainersCmd()
                .withShowSize(true)
                .withShowAll(true)
                .withNameFilter(Collections.singletonList(username + "-"))
                .exec();

        //kill and remove all user container
        for(Container container: containers) {
            if(!container.getState().equals("exited")) {
                dockerClient.killContainerCmd(container.getId()).exec();
            }
            dockerClient.removeContainerCmd(container.getId()).exec();
        }
        logger.info("Containers deallocation completed on EdgeNode: "+edgeId+ " for user: "+username);
    }
}
