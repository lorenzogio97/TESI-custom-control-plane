package it.lorenzogiorgi.tesi.configuration;

import java.util.List;

public class User {
    private String username;
    private String password;
    private List<String> applications;
    private String cookie;
    private String currentEdgeNodeId;

    private String formerEdgeNodeId;
    private UserStatus status;
    private Long sessionExpiration;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public List<String> getApplications() {
        return applications;
    }

    public void setApplications(List<String> applications) {
        this.applications = applications;
    }

    public String getCookie() {
        return cookie;
    }

    public void setCookie(String cookie) {
        this.cookie = cookie;
    }

    public String getCurrentEdgeNodeId() {
        return currentEdgeNodeId;
    }

    public void setCurrentEdgeNodeId(String currentEdgeNodeId) {
        this.currentEdgeNodeId = currentEdgeNodeId;
    }

    public String getFormerEdgeNodeId() {
        return formerEdgeNodeId;
    }

    public void setFormerEdgeNodeId(String formerEdgeNodeId) {
        this.formerEdgeNodeId = formerEdgeNodeId;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public Long getSessionExpiration() {
        return sessionExpiration;
    }

    public void setSessionExpiration(Long sessionExpiration) {
        this.sessionExpiration = sessionExpiration;
    }

    @Override
    public String toString() {
        return "User{" +
                "username='" + username + '\'' +
                ", password='" + password + '\'' +
                ", applications=" + applications +
                ", cookie='" + cookie + '\'' +
                ", currentEdgeNodeId='" + currentEdgeNodeId + '\'' +
                ", formerEdgeNodeId='" + formerEdgeNodeId + '\'' +
                '}';
    }
}
