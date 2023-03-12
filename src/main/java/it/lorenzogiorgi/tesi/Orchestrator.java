package it.lorenzogiorgi.tesi;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.util.Durations;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
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
import it.lorenzogiorgi.tesi.dns.DNSUpdate;
import it.lorenzogiorgi.tesi.envoy.EnvoyConfigurationServer;

import java.io.IOException;
import java.util.*;

public class Orchestrator {
    static SimpleCache<String> cache;
    public static EnvoyConfigurationServer envoyConfigurationServer;
    public static void main(String[] arg) throws IOException, InterruptedException {
        //Spark.get("/hello", (req, res) -> "Hello World");
        //envoyConfigurationServer = new EnvoyConfigurationServer(19000);
        System.out.println(DNSUpdate.updateDNSRecord("lorenzogiorgi.com", "edge5.lorenzogiorgi.com", "A", 20, "192.168.1.4"));

        // la lamda server per definire come ottenere il node-group dal node id,  in pratica è il criterio per
        // creare il group identifier che condivide la configurazione.
        cache = new SimpleCache<>(node -> node.getId());

        Listener listener1 = createListener("listener1", "route1", "0.0.0.0", 8080);
        Listener listener2 = createListener("listener1", "route1", "0.0.0.0", 8080);
        RouteConfiguration route = createRoute("route1","/service/1","1000", "service1");
        Cluster cluster = createCluster("service1", "service1", 8000);

        cache.setSnapshot(
                "edge1",
                Snapshot.create(
                        ImmutableList.of(cluster),
                        ImmutableList.of(),
                        ImmutableList.of(listener1),
                        ImmutableList.of(route),
                        ImmutableList.of(),
                        "1"));

        cache.setSnapshot(
                "edge2",
                Snapshot.create(
                        ImmutableList.of(cluster),
                        ImmutableList.of(),
                        ImmutableList.of(listener2),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        "1"));


        System.out.println(cache.getSnapshot("edge2").clusters().resources());


        V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);

        ServerBuilder builder =
                NettyServerBuilder.forPort(18000)
                        .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                        .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                        .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                        .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl());

        Server server = builder.build();

        server.start();

        System.out.println("Server has started on port " + server.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));

        boolean end= false;
        while(!end) {
            System.out.println("Menu");
            System.out.println("1- redirect a user to a new edge proxy");
            System.out.println("2- delete redirect on the old proxy");
            System.out.println("3-exit");
            Scanner scanner = new Scanner(System.in);
            int command = Integer.parseInt(scanner.nextLine());
            switch (command) {
                case 1:
                    execute_migration();
                    break;
                case 2:
                    delete_redirect();
                    break;
                case 3:
                    new Thread(server::shutdown).start();
                    end= true;
            }
        }
        //Thread.sleep(100000);

        /*cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(
                                TestResources.createCluster(
                                        "cluster1", "127.0.0.1", 1235, Cluster.DiscoveryType.STATIC)),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        "2"));
*/
        server.awaitTermination();
    }


    private static void delete_redirect() {
        Listener listener1 = createListener("listener1", "route1", "0.0.0.0", 8080);
        RouteConfiguration routeConfigurationEmpty= createEmptyRouteConfiguration("route1");
        cache.setSnapshot(
                "edge1",
                Snapshot.create(
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(listener1),
                        ImmutableList.of(routeConfigurationEmpty),
                        ImmutableList.of(),
                        "3"));
        System.out.println("Redirect deleted");
    }

    private static void execute_migration() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Input the index of the migrating user:");
        int userIndex = Integer.parseInt(scanner.nextLine());
        System.out.println("Input the destination edge proxy number: ");
        int edgeNumeber= Integer.parseInt(scanner.nextLine());

        RouteConfiguration routeRedirect = createRedirectRoute("route1","/service/1", "1000", "edge2.lorenzogiorgi.com", 8080);
        RouteConfiguration route = createRoute("route1","/service/1", "1000", "service1");
        //SnapshotResources<Cluster> edge1_cluster = cache.getSnapshot("edge1").clusters();
        //SnapshotResources<Listener> edge1_listeners = cache.getSnapshot("edge1").listeners();
        //SnapshotResources<Cluster> edge2_cluster = cache.getSnapshot("edge2").clusters();
        //SnapshotResources<Listener> edge2_listeners = cache.getSnapshot("edge2").listeners();
        Listener listener1 = createListener("listener1", "route1", "0.0.0.0", 8080);
        Listener listener2 = createListener("listener1", "route1", "0.0.0.0", 8080);
        Cluster cluster = createCluster("service1", "service1", 8000);

        cache.setSnapshot(
                "edge1",
                Snapshot.create(
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(listener1),
                        ImmutableList.of(routeRedirect),
                        ImmutableList.of(),
                        "2"));

        cache.setSnapshot(
                "edge2",
                Snapshot.create(
                        ImmutableList.of(cluster),
                        ImmutableList.of(),
                        ImmutableList.of(listener2),
                        ImmutableList.of(route),
                        ImmutableList.of(),
                        "2"));

    }
    private static Listener createListener(String listenerName, String routeConfigName,  String bindIPAddress, int bindPort) {
        ConfigSource.Builder configSourceBuilder = ConfigSource.newBuilder().setResourceApiVersion(ApiVersion.V3);
        //ConfigSource rdsSource = configSourceBuilder
        //                .setAds(AggregatedConfigSource.getDefaultInstance())
        //                .setResourceApiVersion(ApiVersion.V3)
        //                .build();

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
                        .setStatPrefix("http")
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

        return Listener.newBuilder()
                .setName(listenerName)
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
    }


    public static RouteConfiguration createEmptyRouteConfiguration(String routeName) {
        return RouteConfiguration.newBuilder()
                .setName(routeName)
                .addVirtualHosts(
                        VirtualHost.newBuilder()
                                .setName("all")
                                .addDomains("*")
                )
                .build();
    }
    public static RouteConfiguration createRoute(String routeName, String prefix, String userCookie, String destinationCluster) {
        return RouteConfiguration.newBuilder()
                .setName(routeName)
                .addVirtualHosts(
                        VirtualHost.newBuilder()
                                .setName("all")
                                .addDomains("*")
                                .addRoutes(
                                        Route.newBuilder()
                                                .setMatch(
                                                        RouteMatch.newBuilder()
                                                                .setPrefix(prefix)
                                                                .addHeaders(HeaderMatcher.newBuilder().setName("cookie").setStringMatch(StringMatcher.newBuilder().setContains("authID="+userCookie))))
                                                .setRoute(RouteAction.newBuilder().setCluster(destinationCluster))

                                )
                )
                .build();
    }



    public static RouteConfiguration createRedirectRoute(String routeName, String prefix, String userCookie, String destinationHost, int destinationPort) {
        return RouteConfiguration.newBuilder()
                .setName(routeName)
                .addVirtualHosts(
                        VirtualHost.newBuilder()
                                .setName("all")
                                .addDomains("*")
                                .addRoutes(
                                        Route.newBuilder()
                                                .setMatch(
                                                        RouteMatch.newBuilder()
                                                                .setPrefix(prefix)
                                                                .addHeaders(HeaderMatcher.newBuilder().setName("cookie").setStringMatch(StringMatcher.newBuilder().setContains("authID="+userCookie))))
                                                .setRedirect(RedirectAction.newBuilder().setHostRedirect(destinationHost).setPortRedirect(destinationPort))

                                )
                )
                .build();
    }


    public static Cluster createCluster(String clusterName, String ip, int port) {
        return Cluster.newBuilder()
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
    }
}
