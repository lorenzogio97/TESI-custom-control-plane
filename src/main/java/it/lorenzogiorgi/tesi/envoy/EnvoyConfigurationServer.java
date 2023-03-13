package it.lorenzogiorgi.tesi.envoy;

import com.google.protobuf.Any;
import com.google.protobuf.util.Durations;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.*;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.*;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import it.lorenzogiorgi.tesi.common.Configuration;
import it.lorenzogiorgi.tesi.common.MECNode;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public class EnvoyConfigurationServer {
    private static SimpleCache<String> globalCache;
    private Server server;
    private ConcurrentHashMap<String, SnapshotInstance> proxiesSnapshot;

    public EnvoyConfigurationServer(int port) {
        proxiesSnapshot = new ConcurrentHashMap<>();
        // la lamda server per definire come ottenere il node-group dal node id,  in pratica è il criterio per
        // creare il group identifier che condivide la configurazione.
        globalCache = new SimpleCache<>(node -> node.getId());
        V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(globalCache);

        ServerBuilder builder =
                NettyServerBuilder.forPort(port)
                        .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                        .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                        .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                        .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl());

        server = builder.build();

        try {
            server.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        for (MECNode mecNode:Configuration.mecNodes) {
            System.out.println(mecNode);
            System.out.println(mecNode.getFrontProxy().getId()+"-"+ mecNode.getFrontProxy().getIpAddress());
            proxiesSnapshot.put(mecNode.getFrontProxy().getId(), new SnapshotInstance());
        }

        test();

    }

    public void test() {
        addListenerToProxy("edge1", "default", "0.0.0.0", 80);
        addRedirectToProxy("edge1", "*lorenzogiorgi.com", "default", "/service1", "1000", "edge2.lorenzogiorgi.com", 8080);
        addRedirectToProxy("edge1", "*lorenzogiorgi.com", "default", "/service2", "1000", "edge3.lorenzogiorgi.com", 8080);
    }

    public void awaitTermination() {
        try {
            server.awaitTermination();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
    private void updateProxyCacheSnapshot(String proxyId) {
        globalCache.setSnapshot(proxyId, proxiesSnapshot.get(proxyId).getLastSnapshot());
    }
    private boolean addListenerToProxy(String proxyId, String routeConfigName, String bindIPAddress, int bindPort) {
        ConfigSource.Builder configSourceBuilder = ConfigSource.newBuilder().setResourceApiVersion(ApiVersion.V3);

        ConfigSource rdsSource = configSourceBuilder
                .setApiConfigSource(
                        ApiConfigSource.newBuilder()
                                .setTransportApiVersion(ApiVersion.V3)
                                .setApiType(ApiConfigSource.ApiType.GRPC)
                                .addGrpcServices(
                                        GrpcService.newBuilder()
                                                .setEnvoyGrpc(
                                                        GrpcService.EnvoyGrpc.newBuilder()
                                                                .setClusterName("xds_cluster"))))
                .build();

        HttpConnectionManager manager =
                HttpConnectionManager.newBuilder()
                        .setCodecType(HttpConnectionManager.CodecType.AUTO)
                        .setStatPrefix("http-"+bindIPAddress + "-" + bindPort)
                        .setRds(
                                io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds
                                        .newBuilder()
                                        .setConfigSource(rdsSource)
                                        .setRouteConfigName(routeConfigName))
                        .addHttpFilters(
                                io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
                                        .newBuilder()
                                        .setName("envoy.filters.http.router")
                                        .setTypedConfig(Any.pack(Router.newBuilder().build())))
                        .build();

        Listener newListener = Listener.newBuilder()
                .setName("http-"+bindIPAddress + "-" + bindPort)
                .setAddress(
                        Address.newBuilder()
                                .setSocketAddress(
                                        SocketAddress.newBuilder()
                                                .setAddress(bindIPAddress)
                                                .setPortValue(bindPort)
                                                .setProtocol(SocketAddress.Protocol.TCP)))
                .addFilterChains(
                        FilterChain.newBuilder()
                                .addFilters(
                                        Filter.newBuilder()
                                                .setName("envoy.http_connection_manager")
                                                .setTypedConfig(Any.pack(manager))))
                .build();

        boolean result = proxiesSnapshot.get(proxyId).addResource(newListener);
        if(!result) return false;
        updateProxyCacheSnapshot(proxyId);
        return true;

    }


    public boolean addRouteToProxy(String proxyId, String domain, String routeConfigName, String prefix,
                                   String userCookie, String destinationCluster) {

        Route.Builder route = Route.newBuilder()
                .setName(userCookie+"-"+prefix)// sfruttare per l'eliminazione
                .setMatch(
                        RouteMatch.newBuilder()
                                .setPrefix(prefix)
                                .addHeaders(HeaderMatcher.newBuilder().setName("cookie").setStringMatch(StringMatcher.newBuilder().setContains("authID="+userCookie))))
                .setRoute(RouteAction.newBuilder().setCluster(destinationCluster));


        RouteConfiguration routeConfiguration =  RouteConfiguration.newBuilder()
                .setName(routeConfigName)
                .addVirtualHosts(
                        VirtualHost.newBuilder()
                                .setName(domain)
                                .addDomains("*"+domain)
                                .addRoutes(route)
                )
                .build();

        boolean result = proxiesSnapshot.get(proxyId).addResource(routeConfiguration);
        if(!result) return false;
        updateProxyCacheSnapshot(proxyId);
        return true;
    }



    public boolean addRedirectToProxy(String proxyId, String domain, String routeConfigName, String prefix,
                                                 String userCookie, String destinationHost, int destinationPort) {
        RouteConfiguration routeConfiguration = RouteConfiguration.newBuilder()
                .setName(routeConfigName)
                .addVirtualHosts(
                        VirtualHost.newBuilder()
                                .setName(domain)
                                .addDomains(domain)
                                .addRoutes(
                                        Route.newBuilder()
                                                .setName(userCookie+"-"+prefix)
                                                .setMatch(
                                                        RouteMatch.newBuilder()
                                                                .setPrefix(prefix)
                                                                .addHeaders(HeaderMatcher.newBuilder().setName("cookie").setStringMatch(StringMatcher.newBuilder().setContains("authID="+userCookie))))
                                                .setRedirect(RedirectAction.newBuilder().setHostRedirect(destinationHost).setPortRedirect(destinationPort))

                                )
                )
                .build();

        boolean result = proxiesSnapshot.get(proxyId).addResource(routeConfiguration);
        if(!result) return false;
        updateProxyCacheSnapshot(proxyId);
        return true;
    }


    public boolean addClusterToProxy(String proxyId, String clusterName, String ip, int port) {
        Cluster newCluster =  Cluster.newBuilder()
                .setName(clusterName)
                .setConnectTimeout(Durations.fromSeconds(5))
                .setType(Cluster.DiscoveryType.STRICT_DNS)
                .setLoadAssignment(
                        ClusterLoadAssignment.newBuilder()
                                .setClusterName(clusterName)
                                .addEndpoints(
                                        LocalityLbEndpoints.newBuilder()
                                                .addLbEndpoints(
                                                        LbEndpoint.newBuilder()
                                                                .setEndpoint(
                                                                        Endpoint.newBuilder()
                                                                                .setAddress(
                                                                                        Address.newBuilder()
                                                                                                .setSocketAddress(
                                                                                                        SocketAddress.newBuilder()
                                                                                                                .setAddress(ip)
                                                                                                                .setPortValue(port)
                                                                                                                .setProtocolValue(SocketAddress.Protocol.TCP_VALUE)))))))
                .build();

        boolean result = proxiesSnapshot.get(proxyId).addResource(newCluster);
        if(!result) return false;
        updateProxyCacheSnapshot(proxyId);
        return true;
    }
}
