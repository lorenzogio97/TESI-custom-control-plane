package it.lorenzogiorgi.tesi.api;

import java.util.List;

public class MigrationRequest {
    List<String> edgeNodeList;
    String username;

    public List<String> getEdgeNodeList() {
        return edgeNodeList;
    }

    public String getUsername() {
        return username;
    }
}
