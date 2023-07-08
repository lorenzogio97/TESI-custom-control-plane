package it.lorenzogiorgi.tesi.envoy;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import it.lorenzogiorgi.tesi.configuration.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class SnapshotInstance {
    private Snapshot lastSnapshot;

    List<Listener> listenerList;
    List<Cluster> clusterList;
    List<Route> routeList;
    private long lastVersion;

    /**
     * Create an empty configuration and set the lastVersion value
     */
    public SnapshotInstance() {
        this.lastSnapshot = Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), "1");
        this.lastVersion = 1;
        this.routeList = new ArrayList<>();
        this.clusterList = new ArrayList<>();
        this.listenerList = new ArrayList<>();
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


    public synchronized void addRoute(Route route) {
        //find if some route with the same name is present
        routeList.removeIf(r -> r.getName().equals(route.getName()));

        //front inserting to leave match all default in last position
        routeList.add(0, route);

        commit();
    }


    public synchronized void addCluster(Cluster cluster) {
        //remove if some cluster with the same name is present
        clusterList.removeIf(c -> c.getName().equals(cluster.getName()));

        clusterList.add(cluster);

        commit();
    }

    public synchronized void addListener(Listener listener) {
        //remove if some listener with the same name is present
        listenerList.removeIf(c -> c.getName().equals(listener.getName()));

        listenerList.add(listener);

        commit();
    }


    public synchronized void deleteRouteByName(String routeName) {
        routeList.removeIf(r -> r.getName().equals(routeName));
        commit();
    }

    public synchronized void deleteRoutesByUser(String username) {
        //filter out all routes of a specific user
        routeList.removeIf(r -> r.getName().split("-", 2)[0].equals(username));
        commit();
    }

    public synchronized void deleteClusterByName(String clusterName) {
        clusterList.removeIf(c -> c.getName().equals(clusterName));
        commit();
    }

    public synchronized void deleteClustersByUser(String username) {
        clusterList.removeIf(c -> c.getName().split("-", 2)[0].equals(username));
        commit();
    }


    public synchronized void convertRouteToMigratingByUser(String username, String destinationProxyId) {
        routeList = routeList.stream()
                .map(r -> {
                            if (r.getName().split("-", 2)[0].equals(username)) {
                                return Route.newBuilder()
                                        .setName(r.getName())
                                        .setMatch(
                                                RouteMatch.newBuilder()
                                                        .setPathSeparatedPrefix(r.getMatch().getPathSeparatedPrefix())
                                                        .addAllHeaders(r.getMatch().getHeadersList()))
                                        .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                                                .setHeader(HeaderValue.newBuilder()
                                                        .setKey("Alt-Svc")
                                                        .setValue("h2=\"" + destinationProxyId + "." + Configuration.PLATFORM_NODE_BASE_DOMAIN + ":443\";")
                                                )
                                                .setAppendAction(HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD)
                                        )
                                        .setRoute(r.getRoute())
                                        .build();
                            }
                            return r;
                        }
                ).collect(Collectors.toList());

        commit();
    }

    /**
     * Method that convert all routes belonging to user identified by username and make them STABLE.
     * This method is used to remove Alt-Svc annunciation from a redirect route (possible in the case of double migration)
     * or to remove feedback script from a feedback route.
     * @param username username of the user to be modified
     */
    public synchronized void convertRouteToStableByUser(String username) {
        routeList = routeList.stream()
                .map(r -> {
                            if (r.getName().split("-", 2)[0].equals(username)) {
                                return Route.newBuilder()
                                        .setName(r.getName())
                                        .setMatch(
                                                RouteMatch.newBuilder()
                                                        .setPathSeparatedPrefix(r.getMatch().getPathSeparatedPrefix())
                                                        .addAllHeaders(r.getMatch().getHeadersList()))
                                        .setRoute(r.getRoute())
                                        .build();
                            }
                            return r;
                        }
                ).collect(Collectors.toList());

        commit();
    }

    /**
     * Method that update lastSnapshot. It must be called after every modification of routeList, listenerList or
     * clusterList to update lastSnapshot. It takes care to create a single Route Configuration containing all routes.
     */
    private void commit() {
        RouteConfiguration routeConfiguration = RouteConfiguration.newBuilder()
                .setName("default")
                .addVirtualHosts(
                        VirtualHost.newBuilder()
                                .setName(Configuration.PLATFORM_DOMAIN)
                                .addDomains("*." + Configuration.PLATFORM_DOMAIN)
                                .addAllRoutes(routeList)
                )
                .build();

        lastSnapshot = Snapshot.create(
                clusterList,
                ImmutableList.of(),
                listenerList,
                ImmutableList.of(routeConfiguration),
                ImmutableList.of(),
                String.valueOf(++lastVersion)
        );
    }


}

