# Master Thesis - Platform Orchestrator
---

This repository contains the code related to the Orchestrator for the edge platform developed during my master thesis.
This software is responsible for creating all software infrastructure for the edge platform at startup, receiving login/logout request from client and migration request from Mobility Management.
All those requests triggers modification in resource allocated on Edge nodes, both in terms of Docker container executing client related application and Envoy rules installed on Edge Envoy proxies.

**Note:** TLS/mTLS certificates and private keys not included for security reasons.

---
## Run inside IDE
- Import the project into Intellij IDEA
- Edit configuration.json file inside configuration folder properly (see Configuration explaination below)
- Run

## Run without ide
- Create package file using maven package command (required Manven installed)
- Execute the generated JAR using `java -jar package.jar` (be careful to relative folder related to configuration and TLS/mTLS)

--- 

#Configuration file

The configuration file is placed inside /configuration folder.

