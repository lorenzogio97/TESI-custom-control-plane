package it.lorenzogiorgi.tesi.envoy;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class SnapshotInstance {
    private Snapshot lastSnapshot;
    private long lastVersion;

    /**
     * Create an empty configuration and set the lastVersion value
     */
    public SnapshotInstance() {
        this.lastSnapshot = Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),"1");
        this.lastVersion = 1;
    }


    public Snapshot getLastSnapshot() {
        return lastSnapshot;
    }

    public void setLastSnapshot(Snapshot lastSnapshot) {
        this.lastSnapshot = lastSnapshot;
    }

    public long getLastVersion() {
        return lastVersion;
    }

    public void setLastVersion(long lastVersion) {
        this.lastVersion = lastVersion;
    }

    /**
     * Add the resource to the configuration snapshot
     * @param resource the resource to add
     * @return true if the resource has been added correcty, false otherwise
     */
    public synchronized boolean addResource(com.google.protobuf.GeneratedMessageV3 resource) {
        if(!((resource instanceof Cluster) || (resource instanceof Listener) || (resource instanceof RouteConfiguration)))
            return false;

        ArrayList<Cluster> clusterList = new ArrayList<>(lastSnapshot.clusters().resources().values());
        List<Listener> listenerList= new ArrayList<>(lastSnapshot.listeners().resources().values());
        List<RouteConfiguration> routeConfigurationList= new ArrayList<>((lastSnapshot.routes().resources().values()));

        if(resource instanceof Cluster && !clusterList.contains(resource))
            clusterList.add((Cluster) resource);
        if(resource instanceof Listener && !listenerList.contains(resource))
            listenerList.add((Listener) resource);
        if(resource instanceof RouteConfiguration && !routeConfigurationList.contains(resource)) {
            RouteConfiguration routeConfigurationToAdd = (RouteConfiguration) resource;
            String routeConfigNameToAdd = routeConfigurationToAdd.getName();
            //search of there is an existing configuration with that configname
            Optional<RouteConfiguration> existentOptionalRouteConfiguration =
                    routeConfigurationList.stream().filter(r -> r.getName().equals(routeConfigNameToAdd)).findFirst();
            if(!existentOptionalRouteConfiguration.isPresent()) {
                routeConfigurationList.add((RouteConfiguration) resource);
                lastSnapshot = Snapshot.create(
                        clusterList,
                        ImmutableList.of(),
                        listenerList,
                        routeConfigurationList,
                        ImmutableList.of(),
                        String.valueOf(++lastVersion)
                );
                return true;
            }

            //we have to check if the found RouteConfiguration has the same virtual host
            String domainToAdd = routeConfigurationToAdd.getVirtualHosts(0).getName();
            RouteConfiguration existentRouteConfiguration = existentOptionalRouteConfiguration.get();
            Optional<VirtualHost> existentOptionalVirtualHost = existentRouteConfiguration.getVirtualHostsList()
                    .stream().filter(v -> v.getName().equals(domainToAdd)).findFirst();
            if(!existentOptionalVirtualHost.isPresent()) {
                List<VirtualHost> newVirtualHostList = existentRouteConfiguration.getVirtualHostsList();
                newVirtualHostList.add(routeConfigurationToAdd.getVirtualHosts(0));
                RouteConfiguration newRouteConfiguration = RouteConfiguration.newBuilder()
                        .setName(routeConfigNameToAdd)
                        .addAllVirtualHosts(newVirtualHostList)
                        .build();
                routeConfigurationList = routeConfigurationList
                        .stream()
                        .filter(r -> !r.equals(existentRouteConfiguration))
                        .collect(Collectors.toList());
                routeConfigurationList.add(newRouteConfiguration);
                lastSnapshot = Snapshot.create(
                        clusterList,
                        ImmutableList.of(),
                        listenerList,
                        routeConfigurationList,
                        ImmutableList.of(),
                        String.valueOf(++lastVersion)
                );
                return true;
            }

            //if we arrive there it means that we already have a configuration with the same configName and related
            //to the same domain. So we need to add only the specific route leaving other stuff untouched
            Route routeToAdd = routeConfigurationToAdd.getVirtualHosts(0).getRoutes(0);
            List<VirtualHost> newVirtualHostList = existentRouteConfiguration.getVirtualHostsList();
            VirtualHost existentVirtualHost = existentOptionalVirtualHost.get();
            List<Route> newRouteList = existentVirtualHost.getRoutesList();
            //replace if the same route is present
            newRouteList = newRouteList
                    .stream()
                    .filter(r -> !r.getName().equals(routeToAdd.getName()))
                    .collect(Collectors.toList());
            newRouteList.add(routeToAdd);

            //recreate the VirtualHost with the new Route list
            VirtualHost newVirtualHost = VirtualHost.newBuilder()
                    .setName(existentVirtualHost.getName())
                    .addDomains(existentVirtualHost.getDomains(0))
                    .addAllRoutes(newRouteList)
                    .build();

            //replace the virtual host in the VirtualHost list
            newVirtualHostList = newVirtualHostList
                    .stream()
                    .filter(vh -> !vh.equals(existentVirtualHost))
                    .collect(Collectors.toList());
            newVirtualHostList.add(newVirtualHost);

            RouteConfiguration newRouteConfiguration = RouteConfiguration.newBuilder()
                    .setName(routeConfigNameToAdd)
                    .addAllVirtualHosts(newVirtualHostList)
                    .build();

            routeConfigurationList = routeConfigurationList
                    .stream()
                    .filter(rc -> !rc.equals(existentRouteConfiguration))
                    .collect(Collectors.toList());

            routeConfigurationList.add(newRouteConfiguration);

        }


        lastSnapshot = Snapshot.create(
                clusterList,
                ImmutableList.of(),
                listenerList,
                routeConfigurationList,
                ImmutableList.of(),
                String.valueOf(++lastVersion)
                );
        return true;
    }

}
