# Master Thesis - Platform Orchestrator

This repository contains the code related to the Orchestrator for the edge platform developed during my master thesis.
This software is responsible for creating all software infrastructure for the edge platform at startup, receiving login/logout request from client and migration request from Mobility Management.
All those requests triggers modification in resource allocated on Edge nodes, both in terms of Docker container executing client related application and Envoy rules installed on Edge Envoy proxies.

**Note:** TLS/mTLS certificates and private keys not included for security reasons. See Security/TLS section.

---
## Run inside IDE
- Import the project into Intellij IDEA
- Edit configuration.json file inside configuration folder properly (see Configuration explaination below)
- Run

## Run without ide
- Create package file using "mvn package" command (required Maven installed)
- Execute the generated JAR using `java -jar package.jar` (be careful to relative folder related to configuration and TLS/mTLS)

--- 

## Configuration file

The configuration file is placed inside /configuration folder.
```
  "ENABLE_DNS": (bool) enable support for DNS based migration solution (only for comparison testing purposes)
  "DNS_USER_TTL": (int) DNS TTL for DNS based migration solution (only for comparison testing purposes)
  "PERFORMANCE_TRACING": (bool) enable time measurement of login/logout/migrate/userResourceAllocation (for performance testing)
  "DNS_API_IP": (str) IPv4 of DNS server of the platform (in this implementation, recursive and authoritative DNS are reachable using the same IP e.g. they are on the same host)
  "DNS_API_PORT": (int) Port of PowerDNS API to update DNS configuration
  "DNS_API_SERVER_ID": (str) server ID assigned to the PowerDNS server (see PowerDNS documentation for more detail)
  "DNS_API_KEY": (str) secret API key to edit DNS configuration
  "ENVOY_CONFIGURATION_SERVER_PORT": (int) Port where gRPC xDS server is listening to serve Envoy configurations to proxies
  "ENVOY_CONFIGURATION_SERVER_IP": (str) IPv4 where gRPC xDS server is listening to serve Envoy configurations to proxies
  "ORCHESTRATOR_API_PORT": (int) Port on which Orchestrator is listening
  "ORCHESTRATOR_API_IP": (str) IPv4 on which Orchestrator is listening
  "ORCHESTRATOR_USER_GARBAGE_DELAY": (int) Period of time between two user garbage collector execution
  "PLATFORM_DOMAIN": (str) base domain related to the platform (e.g. example.com). It is used for DNS zone identification and virtual host setting inside Envoy route configuration
  "PLATFORM_CLOUD_DOMAIN": (str) domain that is served to client to make requests (e.g. compute.example.com)
  "PLATFORM_ENVOY_CONF_SERVER_DOMAIN": (str) domain that points to Envoy gRPC configuration server
  "PLATFORM_ORCHESTRATOR_DOMAIN": (str) domain that points to Orchestrator API
  "PLATFORM_NODE_BASE_DOMAIN": (str) base domain to be used for each edge node (e.g. edge.example.com, so each edge will obtain a domain like edge1.edge.example.com, edge2.edge.example.com ecc.)
  "CLIENT_AUTHENTICATION_TOKEN_LENGTH": (int) byte length of the authentication token used by client to access the services
  "CLIENT_SESSION_DURATION": (int) Maximum duration of a client session. Client that wants to extend their session can login again before expiration.
  "CRYPTO_TOKEN_LENGTH": (int) byte length of the authentication token used by Edge node during startup to fetch TLS/mTLS data
  "CRYPTO_TOKEN_SECONDS_VALIDITY": (int) maximum validity of cryto token to fetch TLS/mTLS data. It is just an upper bound, since token are invalidated when Edge proxy creation is completed.
  "cloudNode": (ComputeNode) object that represents the envoy cloud node 
  "edgeNodes": (List<ComputeNode>) object that represents the list of envoy edge node 
  "applications": (List<Application>) object that represent the list of application that user can obtain (if configured) using the platform
```
---

## File tree
```
./src/main/
├── java
│   └── it
│       └── lorenzogiorgi
│           └── tesi
│               ├── api
│               │   ├── LoginRequest.java
│               │   ├── LoginResponse.java
│               │   ├── LogoutRequest.java
│               │   ├── LogoutResponse.java
│               │   └── MigrationRequest.java
│               ├── configuration
│               │   ├── Application.java
│               │   ├── CloudNode.java
│               │   ├── ComputeNode.java
│               │   ├── Configuration.java
│               │   ├── EdgeNode.java
│               │   ├── Microservice.java
│               │   ├── User.java
│               │   └── UserStatus.java
│               ├── dns
│               │   ├── Comment.java
│               │   ├── DNSManagement.java
│               │   ├── Record.java
│               │   ├── RRset.java
│               │   └── Zone.java
│               ├── envoy
│               │   ├── EnvoyConfigurationServer.java
│               │   └── SnapshotInstance.java
│               ├── Orchestrator.java
│               └── utiliy
│                   ├── FileUtility.java
│                   ├── TestUtility.java
│                   └── TokenUtiliy.java
└── resources
    ├── docker-client-certificate
    │   ├── ca.pem
    │   ├── cert.pem
    │   └── key.pem
    ├── log4j2.xml  //log4j configuration file
    ├── mTLS-Envoy //server side Envoy mTLS resources
    │   ├── ca.crt
    │   ├── servercert.pem
    │   └── serverkey.pem
    ├── server.keystore
    └── tls //under this folder are stored all resources that are server to Envoy clients.  
        ├── envoy-mtls //mTLS resources for Envoy client
        │   ├── ca.crt
        │   ├── clientcert.pem
        │   └── clientkey.pem
        └── platform-tls //TLS resources of the domain in use. See Security/TLS sections for more detail
            ├── servercert.pem
            └── serverkey.pem

```

---

## Security/TLS
Both Envoy and Docker uses mTLS to provide encryption and authentication.

Docker server side cert/key must be manually installed on the target edge node, see [Protect Docker daemon socket](https://docs.docker.com/engine/security/protect-access/#use-tls-https-to-protect-the-docker-daemon-socket).
Client cert/key must be placed inside **resouces/docker-client-certificate with names ca.pem, cert.pem, and key.pem**.

Envoy server side mTLS resouces must be placed inside **resouces/mTLS-Envoy with names ca.pem, cert.pem, and key.pem**, 
while client's inside **resources/tls/envoy-mtls**, according to the file tree structure. 

TLS cert and key used during experiment were CA-signed certificate. In order to use this software, you have to provide 
valid TLS resources for your domain (that you have configure into configuration file). Since a single cert/key is used for
all subdomain, I suggest to use a wildcard certificate with all necessary Subject Alternative Name ([SAN](https://en.wikipedia.org/wiki/Subject_Alternative_Name))

Orchestrator API are server using HTTPS. To make them work correctly, you need to create a valid keystone for your domain
as reported [here](https://stackoverflow.com/questions/906402/how-to-import-an-existing-x-509-certificate-and-private-key-in-java-keystore-to).
