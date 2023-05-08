package it.lorenzogiorgi.tesi.envoy;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.*;
import it.lorenzogiorgi.tesi.common.Configuration;

import java.util.*;
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

        System.out.println("last version before "+lastVersion);
        List<Cluster> clusterList = new ArrayList<>(lastSnapshot.clusters().resources().values());
        List<Listener> listenerList= new ArrayList<>(lastSnapshot.listeners().resources().values());
        List<RouteConfiguration> routeConfigurationList= new ArrayList<>((lastSnapshot.routes().resources().values()));

        if(resource instanceof Cluster && !clusterList.contains(resource))
            //TODO: better check equal resource (forse qua non necessario perché i cluster sono privati dell'utente, quindi dovrebbero avere anche stesso nome
            clusterList.add((Cluster) resource);
        if(resource instanceof Listener && !listenerList.contains(resource))
            //TODO: qua in teoria è possibile avere duplicati con nomi differenti, ma dipende se ammettiamo di avere più listeners
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
                System.out.println("last version after "+lastVersion);
                return true;
            }

            //we have to check if the found RouteConfiguration has the same virtual host
            String domainToAdd = routeConfigurationToAdd.getVirtualHosts(0).getName();
            RouteConfiguration existentRouteConfiguration = existentOptionalRouteConfiguration.get();
            Optional<VirtualHost> existentOptionalVirtualHost = existentRouteConfiguration.getVirtualHostsList()
                    .stream().filter(v -> v.getName().equals(domainToAdd)).findFirst();
            if(!existentOptionalVirtualHost.isPresent()) {
                List<VirtualHost> newVirtualHostList = new ArrayList<>(existentRouteConfiguration.getVirtualHostsList());
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


    public synchronized void deleteRoutesByUser(String username) {
        ArrayList<RouteConfiguration> routeConfigurationList = new ArrayList<>(lastSnapshot.routes().resources().values());
        ArrayList<Cluster> clusterList = new ArrayList<>(lastSnapshot.clusters().resources().values());
        ArrayList<Listener> listenerList = new ArrayList<>(lastSnapshot.listeners().resources().values());


        List<RouteConfiguration> newRouteConfigurationList = new ArrayList<>();
        for(RouteConfiguration rc : routeConfigurationList) {
            RouteConfiguration.Builder newRouteConfigurationBuilder = RouteConfiguration.newBuilder()
                    .setName(rc.getName());
            for(VirtualHost vh: rc.getVirtualHostsList()) {
                VirtualHost.Builder newVirtualHost=VirtualHost.newBuilder()
                        .setName(vh.getName())
                        .addAllDomains(vh.getDomainsList());
                for(Route r: vh.getRoutesList()) {
                    if (!r.getName().split("-", 2)[0].equals(username)) {
                        newVirtualHost.addRoutes(r);
                    }
                }
            }
            newRouteConfigurationList.add(newRouteConfigurationBuilder.build());
        }

        lastSnapshot = Snapshot.create(
                clusterList,
                ImmutableList.of(),
                listenerList,
                newRouteConfigurationList,
                ImmutableList.of(),
                String.valueOf(++lastVersion)
        );
    }

    public synchronized void deleteClustersByUser(String username) {
        List<Cluster> clusterList = lastSnapshot
                .clusters()
                .resources()
                .values()
                .stream()
                .filter(c -> !c.getName().split("-", 2)[0].equals(username))
                .collect(Collectors.toList());

        Collection<RouteConfiguration> routeConfigurationList = lastSnapshot.routes().resources().values();
        Collection<Listener> listenerList = lastSnapshot.listeners().resources().values();

        lastSnapshot = Snapshot.create(
                clusterList,
                ImmutableList.of(),
                listenerList,
                routeConfigurationList,
                ImmutableList.of(),
                String.valueOf(++lastVersion)
        );
    }


    public synchronized void redirectProxyRoutesByUser(String username, String destinationProxyId) {
        ArrayList<RouteConfiguration> routeConfigurationList = new ArrayList<>(lastSnapshot.routes().resources().values());
        ArrayList<Cluster> clusterList = new ArrayList<>(lastSnapshot.clusters().resources().values());
        ArrayList<Listener> listenerList = new ArrayList<>(lastSnapshot.listeners().resources().values());

        List<RouteConfiguration> newRouteConfigurationList = new ArrayList<>();
        for(RouteConfiguration rc : routeConfigurationList) {
            RouteConfiguration.Builder newRouteConfigurationBuilder = RouteConfiguration.newBuilder()
                    .setName(rc.getName());
            for(VirtualHost vh: rc.getVirtualHostsList()) {
                VirtualHost.Builder newVirtualHost=VirtualHost.newBuilder()
                                .setName(vh.getName())
                                .addAllDomains(vh.getDomainsList());
                for(Route r: vh.getRoutesList()) {
                    if (r.getName().split("-", 2)[0].equals(username)) {
                        newVirtualHost.addRoutes(
                                Route.newBuilder()
                                        .setName(r.getName())
                                        .setMatch(
                                                RouteMatch.newBuilder()
                                                        .setPathSeparatedPrefix(r.getMatch().getPathSeparatedPrefix())
                                                        .addAllHeaders(r.getMatch().getHeadersList()))
                                        .setRedirect(RedirectAction.newBuilder().setHostRedirect(destinationProxyId +"."+ Configuration.PLATFORM_NODE_BASE_DOMAIN).setPortRedirect(80))

                        );
                    } else {
                        newVirtualHost.addRoutes(r);
                    }
                }
                newRouteConfigurationBuilder.addVirtualHosts(newVirtualHost);
            }
            newRouteConfigurationList.add(newRouteConfigurationBuilder.build());
        }

        lastSnapshot = Snapshot.create(
                clusterList,
                ImmutableList.of(),
                listenerList,
                newRouteConfigurationList,
                ImmutableList.of(),
                String.valueOf(++lastVersion)
        );
    }


}
