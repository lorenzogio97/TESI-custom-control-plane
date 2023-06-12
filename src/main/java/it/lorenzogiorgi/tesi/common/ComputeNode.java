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
import it.lorenzogiorgi.tesi.utiliy.TokenUtiliy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
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
        String dockerHost;
        if(this instanceof CloudNode) {
            dockerHost = "tcp://"+ Configuration.PLATFORM_CLOUD_DOMAIN +":"+ this.getDockerPort();
        } else {
            dockerHost = "tcp://"+ id+"."+Configuration.PLATFORM_NODE_BASE_DOMAIN+":"+ this.getDockerPort();
        }
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .withDockerTlsVerify(true)
                .withDockerCertPath("./src/main/resources/docker-client-certificate")
                .build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(10))
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
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

    protected boolean initializeFrontProxy() {
        logger.info("Envoy front proxy creation of node "+ id + " started");
        DockerClient dockerClient = getDockerClient();

        //token creation
        String token = TokenUtiliy.generateRandomHexString(Configuration.CRYPTO_TOKEN_LENGTH);

        //insert token into authorized one
        Orchestrator.securityTokenMap.put(token, System.currentTimeMillis()+Configuration.CRYPTO_TOKEN_SECONDS_VALIDITY * 1000L);

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
                        "RUN mkdir /etc/certs/platform-tls/ \n"+
                        "RUN mkdir /etc/certs/envoy-mtls/ \n"+

                        // TLS certificate and key for platform domain
                        "RUN curl "+ tlsConfigurationUrl+"/platform-tls/cert/"+token + " -o servercert.pem\n" +
                        "RUN mv ./servercert.pem /etc/certs/platform-tls/servercert.pem\n" +
                        "RUN curl "+ tlsConfigurationUrl+"/platform-tls/key/"+token +" -o serverkey.pem\n" +
                        "RUN mv ./serverkey.pem /etc/certs/platform-tls/serverkey.pem\n" +
                        "RUN chmod a+r /etc/certs/platform-tls/servercert.pem \\\n" +
                        "    && chmod a+r /etc/certs/platform-tls/serverkey.pem\n"+

                        //Envoy mTLS
                        "RUN curl "+ tlsConfigurationUrl+"/envoy-mtls/cert/"+token + " -o clientcert.pem\n" +
                        "RUN mv ./clientcert.pem /etc/certs/envoy-mtls/clientcert.pem\n" +
                        "RUN curl "+ tlsConfigurationUrl+"/envoy-mtls/key/"+ token + " -o clientkey.pem\n" +
                        "RUN mv ./clientkey.pem /etc/certs/envoy-mtls/clientkey.pem\n" +
                        "RUN curl "+ tlsConfigurationUrl+"/envoy-mtls/ca/"+token + " -o ca.crt\n" +
                        "RUN mv ./ca.crt /etc/certs/envoy-mtls/ca.crt\n" +
                        "RUN chmod a+r /etc/certs/envoy-mtls/clientcert.pem \\\n" +
                        "    && chmod a+r /etc/certs/envoy-mtls/clientkey.pem \\\n"+
                        "    && chmod a+r /etc/certs/envoy-mtls/ca.crt\n"+

                        //run Envoy
                        "CMD [\"/usr/local/bin/envoy\", \"-c\", \"/etc/front-envoy.yaml\", \"--service-cluster\", \"front-proxy\"]";

        String imageId;

        File dockerfile= new File("./configuration/Dockerfile-"+this.id);
        try {
            //write Dockerfile
            FileWriter writer = new FileWriter(dockerfile);
            writer.write(dockerfileString);
            writer.flush();
            writer.close();

            //create custom envoy image using dockerfile
            imageId = dockerClient.buildImageCmd()
                    .withDockerfile(dockerfile)
                    .withForcerm(true)
                    .withPull(true)
                    .withNoCache(true)
                    .withTags(Stream.of("envoy").collect(Collectors.toSet()))
                    .exec(new BuildImageResultCallback())
                    .awaitImageId();
            dockerfile.delete();
            logger.info("Envoy custom image build of node "+ id + " done");
        } catch (Exception e) {
            logger.error("Envoy custom image build of node "+ id +" failed");
            logger.error(e.getMessage());
            return false;
        } finally {
            Orchestrator.securityTokenMap.remove(token);
        }

        //create Envoy container
        logger.info("Envoy container creation of node "+ id + " started");
        try {
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
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Envoy container creation of node "+ id + " failed");
            return false;
        }

        logger.info("Envoy container creation of node "+ id + " done");

        Orchestrator.envoyConfigurationServer.addTLSListenerToProxy(this.id, "default", "0.0.0.0");
        Orchestrator.envoyConfigurationServer.addDefaultRouteToProxy(this.id);

        logger.info("Envoy front proxy creation of node "+ id + " done");
        return true;
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
