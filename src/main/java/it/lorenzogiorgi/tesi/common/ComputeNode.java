package it.lorenzogiorgi.tesi.common;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import it.lorenzogiorgi.tesi.Orchestrator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class ComputeNode {
    protected final static Logger logger = LogManager.getLogger(ComputeNode.class.getName());
    protected String id;
    protected String ipAddress;
    protected int dockerPort;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }


    public int getDockerPort() {
        return dockerPort;
    }

    public void setDockerPort(int dockerPort) {
        this.dockerPort = dockerPort;
    }

    protected DockerClient getDockerClient() {
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(URI.create("tcp://"+ this.getIpAddress()+":"+ this.getDockerPort()))
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(5))
                //.responseTimeout(Duration.ofSeconds(120))
                .build();


        DockerClientConfig custom = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("tcp://"+ this.getIpAddress()+":"+ this.getDockerPort())
                .build();
        return DockerClientImpl.getInstance(custom, httpClient);
    }

    public void cleanupContainer() {
        logger.info("Cleanup container of node "+ id + " started");

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
            } catch (ConflictException ce) {
                logger.trace("Container ID:"+container.getId() + " not killed (already not running)");
            }
        }

        logger.info("Cleanup container of node "+ id + " done");
    }

    public void cleanupDanglingImages() {
        logger.info("Cleanup images of node "+ id +" started");
        DockerClient dockerClient = getDockerClient();

        //cleanup dangling images
        List<Image> images = dockerClient.listImagesCmd()
                .withDanglingFilter(true).exec();
        for(Image image:images) {
            try {
                dockerClient.removeImageCmd(image.getId()).exec();
            } catch (NotFoundException nfe) {
                logger.trace("Imgage ID:"+image.getId() + " no removed (not found)");
            } catch (ConflictException ce) {
                logger.trace("Imgage ID:"+image.getId() + " no removed (maybe still in use)");
            }
        }

        logger.info("Cleanup container of node "+ id + " done");
    }

    protected abstract void initialize();

    protected void initializeFrontProxy() {
        logger.info("Envoy front proxy creation of node "+ id + " started");
        DockerClient dockerClient = getDockerClient();

        //create Dockerfile
        String configurationFileUrl= "https://"+Configuration.PLATFORM_ORCHESTRATOR_DOMAIN+":"
                +Configuration.ORCHESTRATOR_API_PORT+"/envoyconfiguration/"+id;
        String tlsConfigurationUrl = "https://"+Configuration.PLATFORM_ORCHESTRATOR_DOMAIN+":"
                +Configuration.ORCHESTRATOR_API_PORT;
        String dockerfileString =
                "FROM envoyproxy/envoy-dev:latest\n" +
                        "\n" +
                        "ENV DEBIAN_FRONTEND=noninteractive\n" +
                        "\n" +
                        "RUN apt-get -qq update \n" +
                        "RUN apt-get -qq install --no-install-recommends -y ca-certificates curl nano\\\n" +
                        "    && apt-get -qq autoremove -y \\\n" +
                        "    && apt-get clean \\\n" +
                        "    && rm -rf /tmp/* /var/tmp/* /var/lib/apt/lists/* \n" +
                        "RUN curl "+ configurationFileUrl +" -o envoy-configuration.yaml\n" +
                        "RUN mv ./envoy-configuration.yaml /etc/front-envoy.yaml\n" +
                        "RUN chmod go+r /etc/front-envoy.yaml\n" +
                        "RUN mkdir /etc/certs/ \n"+
                        "RUN curl "+ tlsConfigurationUrl+"/servercert.pem" +" -o servercert.pem\n" +
                        "RUN mv ./servercert.pem /etc/certs/servercert.pem\n" +
                        "RUN curl "+ tlsConfigurationUrl+"/serverkey.pem" +" -o serverkey.pem\n" +
                        "RUN mv ./serverkey.pem /etc/certs/serverkey.pem\n" +
                        "RUN chmod a+r /etc/certs/servercert.pem\n" +
                        "RUN chmod a+r /etc/certs/serverkey.pem\n"+
                        "CMD [\"/usr/local/bin/envoy\", \"-c\", \"/etc/front-envoy.yaml\", \"--service-cluster\", \"front-proxy\"]";

        String imageId;
        try {
            File dockerfile= new File("./configuration/Dockerfile-"+this.id);
            try (FileWriter writer = new FileWriter(dockerfile)) {
                writer.write(dockerfileString);
                writer.flush();
            }

            //create custom envoy image
            imageId = dockerClient.buildImageCmd()
                    .withDockerfile(dockerfile)
                    .withForcerm(true)
                    .withPull(true)
                    //.withNoCache(true)
                    .withTags(Stream.of("envoy").collect(Collectors.toSet()))
                    .exec(new BuildImageResultCallback())
                    .awaitImageId();
            dockerfile.delete();
            logger.info("Envoy custom image build of node "+ id + " done");
        } catch (IOException e) {
            logger.error("Envoy custom image build of node "+ id +" failed");
            throw new RuntimeException(e);
        }

        //create Envoy container
        logger.info("Envoy container creation of node "+ id + " started");

        CreateContainerResponse container = dockerClient
                .createContainerCmd(imageId)
                .withName("envoy-"+ id)
                .withExposedPorts(new ArrayList<>(Arrays.asList(
                        ExposedPort.tcp(443),
                        ExposedPort.tcp(80),
                        ExposedPort.tcp(10000)
                )))
                .withHostConfig(HostConfig.newHostConfig()
                        .withDns(Configuration.DNS_API_IP)
                        .withRestartPolicy(RestartPolicy.unlessStoppedRestart())
                        .withPortBindings(new ArrayList<>(Arrays.asList(
                                        PortBinding.parse(this.getIpAddress()+":80/tcp:80"),
                                        PortBinding.parse(this.getIpAddress()+":443/tcp:443"),
                                        PortBinding.parse(this.getIpAddress()+":10000/tcp:10000"))
                                )
                        )
                )
                .exec();

        // start the container
        dockerClient.startContainerCmd(container.getId()).exec();

        logger.info("Envoy container creation of node "+ id + " done");


        Orchestrator.envoyConfigurationServer.addTLSListenerToProxy(this.id, "default", "0.0.0.0");
        Orchestrator.envoyConfigurationServer.addDefaultRouteToProxy(this.id);

        logger.info("Envoy front proxy creation of node "+ id + " done");
    }


    @Override
    public String toString() {
        return "ComputeNode{" +
                "edgeId='" + id + '\'' +
                ", ipAddress='" + ipAddress + '\'' +
                ", dockerPort=" + dockerPort +
                '}';
    }
}
