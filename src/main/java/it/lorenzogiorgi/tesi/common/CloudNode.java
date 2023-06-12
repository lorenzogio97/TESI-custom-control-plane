package it.lorenzogiorgi.tesi.common;

import it.lorenzogiorgi.tesi.Orchestrator;
import it.lorenzogiorgi.tesi.dns.DNSManagement;

public class CloudNode extends ComputeNode{

    public void initialize() {
        cleanupContainer();
        if(!initializeFrontProxy()) {
            logger.fatal("Cloud Envoy node initialization failed");
            logger.fatal("Orchestrator will now exit");
            System.exit(1);
        }
        cleanupDanglingImages();

        //insert route for the Orchestrator (cluster already configured by static configuration)
        Orchestrator.envoyConfigurationServer.addPublicRouteToProxy("cloud", "/orchestrator", "orchestrator_cluster");
    }
}
