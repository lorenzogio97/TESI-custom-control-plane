package it.lorenzogiorgi.tesi.configuration;


import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.RestartPolicy;
import it.lorenzogiorgi.tesi.Orchestrator;
import it.lorenzogiorgi.tesi.utiliy.TestUtility;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class EdgeNode extends ComputeNode{

    public void initialize() {
        cleanupContainer();
        boolean initialized = initializeFrontProxy();
        cleanupDanglingImages();

        if(!initialized) {
            logger.error("Edge Envoy node initialization failed");
            Configuration.edgeNodes.remove(id);
            return;
        }

        Orchestrator.envoyConfigurationServer.addPublicRouteToProxy(this.id, "/orchestrator", "orchestrator_cluster");

    }


    public boolean allocateUserResources(String username, String authCookie, boolean migration)  {
        // Performance Evaluation
        long t0, t1, t2, t3 = 0, t4 = 0, t5 = 0, t6;
        t0 = System.currentTimeMillis();

        logger.info("Resource allocation on EdgeNode: "+ id + " for user: "+username);
        //get User resource to allocate
        User user = Configuration.users.get(username);

        List<Application> applicationList = user.getApplications()
                .stream()
                .map(a ->Configuration.applications.get(a))
                .collect(Collectors.toList());


        //connect to Docker daemon on the target MECNode
        DockerClient dockerClient = getDockerClient();

        t1 = System.currentTimeMillis();

        logger.info("Pulling images on EdgeNode: "+ id + " for user: "+username);
        //pull required images
        for (Application application: applicationList) {
            for (Microservice microservice : application.getMicroservices()) {
                logger.trace("Pulling " + microservice.getImageName() + " on EdgeNode: "+ id +" for user: " + user.getUsername());
                try {
                    dockerClient.pullImageCmd(microservice.getImageName())
                            .withTag(microservice.getImageTag())
                            .exec(new PullImageResultCallback())
                            .awaitCompletion();
                } catch (Exception e) {
                    logger.warn("Pulling images on EdgeNode: "+ id + " for user: "+username+ " failed. Cleanup.");
                    cleanupDanglingImages();
                    return false;
                }
            }
        }

        t2 = System.currentTimeMillis();

        logger.info("Containers creation on EdgeNode: "+ id + " for user: "+username);
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
                                    .withDns(Configuration.DNS_API_IP)
                                    .withRestartPolicy(RestartPolicy.unlessStoppedRestart())
                                    .withCpuPeriod(100000L)
                                    .withCpuQuota((long) (microservice.getMaxCPU() * 100000))
                                    .withMemory((long) microservice.getMaxMemory() * 1000 * 1000))
                            .exec();
                    // start the container
                    dockerClient.startContainerCmd(container.getId()).exec();
                } catch (Exception e) {
                    logger.warn("Containers creation failed on EdgeNode: "+ id + " for user: "+username);
                    deallocateUserResources(username);
                    return false;
                }

                t3 = System.currentTimeMillis();

                //get ip of the created container
                InspectContainerResponse inspectContainerResponse = dockerClient.inspectContainerCmd(container.getId()).exec();
                String ip = inspectContainerResponse.getNetworkSettings().getNetworks().get("bridge").getIpAddress();

                t4 = System.currentTimeMillis();

                //get Envoy front proxy ID
                String proxyID = this.getId();

                //compute the endpoint for the microservice
                String endpoint = "/"+application.getName()+"/"+microservice.getName();

                //set envoy clusters and routes
                Orchestrator.envoyConfigurationServer.addClusterToProxy(proxyID, user.getUsername(), microservice.getName(), ip, microservice.getExposedPort());

                t5 = System.currentTimeMillis();

                if(migration) {
                    Orchestrator.envoyConfigurationServer.addFeedbackRouteToProxy(proxyID, user.getUsername(), endpoint,
                            authCookie, microservice.getName());
                } else {
                    Orchestrator.envoyConfigurationServer.addUserRouteToProxy(proxyID, user.getUsername(), endpoint, authCookie, microservice.getName());
                }

            }
        }

        t6 = System.currentTimeMillis();
        if (Configuration.PERFORMANCE_TRACING)
            TestUtility.writeExperimentData("allocateUserResource", new String[]{String.valueOf(t1-t0), String.valueOf(t2-t1),
                    String.valueOf(t3-t2), String.valueOf(t4-t3), String.valueOf(t5-t4), String.valueOf(t6-t5)});


        logger.info("Containers created on EdgeNode: "+ id + " for user: "+username);
        return true;

    }

    public void deallocateUserResources(String username) {
        logger.info("Containers deallocation on EdgeNode: "+ id + " for user: "+username);
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
        logger.info("Containers deallocation completed on EdgeNode: "+ id + " for user: "+username);
    }
}
