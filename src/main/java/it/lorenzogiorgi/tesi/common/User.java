package it.lorenzogiorgi.tesi.common;

import java.util.List;

public class User {
    private String username;
    private String password;
    private List<String> applications;
    private String cookie;
    private String currentMECId;

    private String formerMECId;

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

    public String getCurrentMECId() {
        return currentMECId;
    }

    public void setCurrentMECId(String currentMECId) {
        this.currentMECId = currentMECId;
    }

    public String getFormerMECId() {
        return formerMECId;
    }

    public void setFormerMECId(String formerMECId) {
        this.formerMECId = formerMECId;
    }


    @Override
    public String toString() {
        return "User{" +
                "username='" + username + '\'' +
                ", password='" + password + '\'' +
                ", cookie='" + cookie + '\'' +
                ", proxyId='" + currentMECId + '\'' +
                '}';
    }
}
