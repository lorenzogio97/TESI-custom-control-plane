package it.lorenzogiorgi.tesi.envoy;

import com.google.protobuf.Any;
import com.google.protobuf.Duration;
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
import io.envoyproxy.envoy.extensions.filters.http.lua.v3.Lua;
import io.envoyproxy.envoy.extensions.filters.http.lua.v3.LuaPerRoute;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.netty.NettyServerBuilder;
import it.lorenzogiorgi.tesi.common.CloudNode;
import it.lorenzogiorgi.tesi.common.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class EnvoyConfigurationServer {
    private static SimpleCache<String> globalCache;
    private final Server server;
    private final ConcurrentHashMap<String, SnapshotInstance> proxiesSnapshot;

    private final Logger logger = LogManager.getLogger(EnvoyConfigurationServer.class.getName());
    public EnvoyConfigurationServer() {
        proxiesSnapshot = new ConcurrentHashMap<>();
        // la lamda server per definire come ottenere il node-group dal node id,  in pratica è il criterio per
        // creare il group identifier che condivide la configurazione.
        globalCache = new SimpleCache<>(Node::getId);
        V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(globalCache);


        try {
            File certChain= new File("./src/main/resources/mTLS-Envoy/servercert.pem");
            File privateKey = new File("./src/main/resources/mTLS-Envoy/serverkey.pem");
            File clientCAFile = new File("./src/main/resources/mTLS-Envoy/ca.crt");

            ServerCredentials creds = TlsServerCredentials.newBuilder()
                    .keyManager(certChain, privateKey)
                    .trustManager(clientCAFile)
                    .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                    .build();

            ServerBuilder<NettyServerBuilder> builder =
                    NettyServerBuilder.forPort(Configuration.ENVOY_CONFIGURATION_SERVER_PORT, creds)
                            .permitKeepAliveTime(60, TimeUnit.SECONDS)
                            .permitKeepAliveWithoutCalls(true)
                            .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                            .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                            .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                            .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl());

            server = builder.build();

            server.start();
        } catch (IOException e) {
            logger.fatal("Envoy configuration server not started");
            throw new RuntimeException(e);
        }

        //initialize proxy snapshots
        for(String edgeId: Configuration.edgeNodes.keySet()){
            proxiesSnapshot.put(edgeId, new SnapshotInstance());
        }
        CloudNode cloudNode = Configuration.cloudNode;
        proxiesSnapshot.put(cloudNode.getId(), new SnapshotInstance());

        logger.info("Envoy configuration server started successfully");
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

    public void addTLSListenerToProxy(String proxyId, String routeConfigName, String bindIPAddress) {
        logger.info("Add listener to Proxy: "+proxyId+" on port "+443);
        ConfigSource.Builder configSourceBuilder = ConfigSource.newBuilder().setResourceApiVersion(ApiVersion.V3);

        ConfigSource rdsSource = configSourceBuilder
                .setApiConfigSource(
                        ApiConfigSource.newBuilder()
                                .setTransportApiVersion(ApiVersion.V3)
                                .setApiType(ApiConfigSource.ApiType.DELTA_GRPC)
                                .addGrpcServices(
                                        GrpcService.newBuilder()
                                                .setEnvoyGrpc(
                                                        GrpcService.EnvoyGrpc.newBuilder()
                                                                .setClusterName("xds_cluster"))))
                .build();

        Lua lua= Lua.newBuilder()
                .setDefaultSourceCode(DataSource.newBuilder()
                        .setInlineString(
                                "function envoy_on_response(response_handle) " +
                                        //"  length = response_handle:body():length() " +
                                        //"  response_handle:headers():add('response_body_size', length) " +
                                        "end"
                        )
                        .build())
                .build();

        HttpConnectionManager manager =
                HttpConnectionManager.newBuilder()
                        .setCodecType(HttpConnectionManager.CodecType.AUTO)
                        .setStatPrefix("http-"+bindIPAddress + "-" + 443)
                        .setRds(
                                io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds
                                        .newBuilder()
                                        .setConfigSource(rdsSource)
                                        .setRouteConfigName(routeConfigName))
                        .addHttpFilters(
                                io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
                                        .newBuilder()
                                        .setName("envoy.filters.http.lua")
                                        .setTypedConfig(Any.pack(lua))
                        )
                        .addHttpFilters(
                                io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
                                        .newBuilder()
                                        .setName("envoy.filters.http.router")
                                        .setTypedConfig(Any.pack(Router.newBuilder().build()))
                        )
                        .build();

        DownstreamTlsContext downstreamTlsContext =
                DownstreamTlsContext.newBuilder()
                        .setCommonTlsContext(CommonTlsContext.newBuilder()
                                .addTlsCertificates(TlsCertificate.newBuilder()
                                        .setCertificateChain(DataSource.newBuilder()
                                                .setFilename("/etc/certs/platform-tls/servercert.pem")
                                        )
                                        .setPrivateKey(DataSource.newBuilder()
                                                .setFilename("/etc/certs/platform-tls/serverkey.pem")
                                        )
                                )
                                .addAlpnProtocols("h2,http/1.1")
                                .build())
                        .build();

        Listener newListener = Listener.newBuilder()
                .setName("http-"+bindIPAddress + "-" + 443)
                .setAddress(
                        Address.newBuilder()
                                .setSocketAddress(
                                        SocketAddress.newBuilder()
                                                .setAddress(bindIPAddress)
                                                .setPortValue(443)))
                .addFilterChains(
                        FilterChain.newBuilder()
                                .setTransportSocket(TransportSocket.newBuilder()
                                        .setName("envoy.transport_sockets.tls")
                                        .setTypedConfig(Any.pack(downstreamTlsContext))
                                        .build())
                                .addFilters(
                                        Filter.newBuilder()
                                                .setName("envoy.http_connection_manager")
                                                .setTypedConfig(Any.pack(manager))
                                )
                )
                .build();

        proxiesSnapshot.get(proxyId).addListener(newListener);
        updateProxyCacheSnapshot(proxyId);

    }

    public void addUserRouteToProxy(String proxyId, String username, String prefix,
                                    String userCookie, String destinationCluster) {

        Route route = Route.newBuilder()
                .setName(username+"-"+prefix)// useful for delete
                .setMatch(
                        RouteMatch.newBuilder()
                                .setPathSeparatedPrefix(prefix)
                                .addHeaders(HeaderMatcher.newBuilder().setName("cookie").setStringMatch(StringMatcher.newBuilder().setContains("authID="+userCookie))))
                .setRoute(
                        RouteAction.newBuilder()
                                .setPrefixRewrite("/")
                                .setCluster(username+"-"+destinationCluster))
                .build();


        proxiesSnapshot.get(proxyId).addRoute(route);
        updateProxyCacheSnapshot(proxyId);
    }

    public void addPublicRouteToProxy(String proxyId, String prefix, String destinationCluster) {

        Route route = Route.newBuilder()
                .setName(destinationCluster+"-"+prefix)
                .setMatch(
                        RouteMatch.newBuilder()
                                .setPathSeparatedPrefix(prefix)
                )
                .setRoute(
                        RouteAction.newBuilder()
                                .setTimeout(Duration.newBuilder()
                                        .setSeconds(0))
                                .setPrefixRewrite("/")
                                .setCluster(destinationCluster)
                )
                .build();


        proxiesSnapshot.get(proxyId).addRoute(route);
        updateProxyCacheSnapshot(proxyId);
    }

    public void addDefaultRouteToProxy(String proxyId) {

        Route route = Route.newBuilder()
                .setName("default")
                .setMatch(
                        RouteMatch.newBuilder()
                                .setPrefix("")
                )
                .setDirectResponse(DirectResponseAction.newBuilder()
                        .setStatus(421)
                )
                .build();


        proxiesSnapshot.get(proxyId).addRoute(route);
        updateProxyCacheSnapshot(proxyId);
    }

    public void addFeedbackRouteToProxy(String proxyId, String username, String prefix,
                                   String userCookie, String destinationCluster) {

        LuaPerRoute lua= LuaPerRoute.newBuilder()
                .setSourceCode(DataSource.newBuilder()

                        .setInlineString(
                                "function envoy_on_request(request_handle)" +
                                "  request_handle:httpCall(" +
                                "\"orchestrator_cluster\"," +
                                "{" +
                                "[\":method\"] = 'POST'," +
                                "[\":path\"] = "+"'/migration_feedback/"+username+"/"+proxyId+"'," +
                                "[\":authority\"] = '"+Configuration.PLATFORM_ORCHESTRATOR_DOMAIN+"'" +
                                "}," +
                                "\"\", "+
                                "0,"+
                                "true) " +
                                "end"
                        )
                        .build())
                .build();

        Route route = Route.newBuilder()
                .setName(username+"-"+prefix)
                .setMatch(
                        RouteMatch.newBuilder()
                                .setPathSeparatedPrefix(prefix)
                                .addHeaders(HeaderMatcher.newBuilder().setName("cookie").setStringMatch(StringMatcher.newBuilder().setContains("authID="+userCookie))))
                .setRoute(
                        RouteAction.newBuilder()
                                .setPrefixRewrite("/")
                                .setCluster(username+"-"+destinationCluster))
                .putTypedPerFilterConfig("envoy.filters.http.lua", Any.pack(lua))
                .build();


        proxiesSnapshot.get(proxyId).addRoute(route);

        updateProxyCacheSnapshot(proxyId);
    }


    public void addAltSvcRedirectRouteToProxy(String proxyId, String username, String userCookie, String destinationEdgeHost) {

        Route route = Route.newBuilder()
                .setName(username+"-user")
                .setMatch(
                        RouteMatch.newBuilder()
                                .setPrefix("")
                                .addHeaders(HeaderMatcher.newBuilder().setName("cookie").setStringMatch(StringMatcher.newBuilder().setContains("authID="+userCookie))))
                .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                        .setHeader(HeaderValue.newBuilder()
                                .setKey("Alt-Svc")
                                .setValue("h2=\""+destinationEdgeHost+":443\";")
                                .build())
                        .build())
                .setRedirect(RedirectAction.newBuilder()
                        .setHostRedirect(destinationEdgeHost)
                        .setPortRedirect(443)
                        .setResponseCodeValue(RedirectAction.RedirectResponseCode.TEMPORARY_REDIRECT_VALUE)
                )
                .build();

        proxiesSnapshot.get(proxyId).addRoute(route);
        updateProxyCacheSnapshot(proxyId);
    }


    public void addClusterToProxy(String proxyId, String username, String clusterName, String ip, int port) {

        Cluster newCluster =  Cluster.newBuilder()
                .setName(username+"-"+clusterName)
                .setConnectTimeout(Durations.fromSeconds(5))
                .setType(Cluster.DiscoveryType.STRICT_DNS)
                .setLoadAssignment(
                        ClusterLoadAssignment.newBuilder()
                                .setClusterName(username+"-"+clusterName)
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

        proxiesSnapshot.get(proxyId).addCluster(newCluster);
        updateProxyCacheSnapshot(proxyId);
    }

    public void convertRouteToMigratingByUserFromProxy(String username, String sourceProxyId, String destinationProxyId) {
        proxiesSnapshot.get(sourceProxyId).convertRouteToMigratingByUser(username,destinationProxyId);
        updateProxyCacheSnapshot(sourceProxyId);
    }

    public void convertRouteToStableByUserFromProxy(String username, String proxyId) {
        proxiesSnapshot.get(proxyId).convertRouteToStableByUser(username);
        updateProxyCacheSnapshot(proxyId);
    }

    public void deleteAllUserResourcesFromProxy(String username, String proxyId) {
        SnapshotInstance snapshotInstance = proxiesSnapshot.get(proxyId);
        snapshotInstance.deleteRoutesByUser(username);
        snapshotInstance.deleteClustersByUser(username);
        updateProxyCacheSnapshot(proxyId);
    }

    public void removeFeedbackFromRouteByUserFromProxy(String username, String proxyId) {
        proxiesSnapshot.get(proxyId).convertRouteToStableByUser(username);
        updateProxyCacheSnapshot(proxyId);
    }

    public void deleteClusterByNameFromProxy(String proxyId, String clusterName) {
        proxiesSnapshot.get(proxyId).deleteClusterByName(clusterName);
        updateProxyCacheSnapshot(proxyId);
    }

    public void deleteRouteByNameFromProxy(String proxyId, String routeName) {
        proxiesSnapshot.get(proxyId).deleteRouteByName(routeName);
        updateProxyCacheSnapshot(proxyId);
    }

}
