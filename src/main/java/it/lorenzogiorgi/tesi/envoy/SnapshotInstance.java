package it.lorenzogiorgi.tesi.envoy;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

import java.util.ArrayList;
import java.util.List;

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
        if(resource instanceof RouteConfiguration && !routeConfigurationList.contains(resource))
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

}
