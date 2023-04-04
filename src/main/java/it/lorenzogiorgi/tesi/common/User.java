package it.lorenzogiorgi.tesi.common;

import java.util.List;

public class User {
    private String username;
    private String password;
    private String cookie;
    private String currentProxyId;

    private String formerProxyId;

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


    public String getCookie() {
        return cookie;
    }

    public void setCookie(String cookie) {
        this.cookie = cookie;
    }

    public String getCurrentProxyId() {
        return currentProxyId;
    }

    public void setCurrentProxyId(String currentProxyId) {
        this.currentProxyId = currentProxyId;
    }

    public String getFormerProxyId() {
        return formerProxyId;
    }

    public void setFormerProxyId(String formerProxyId) {
        this.formerProxyId = formerProxyId;
    }


    @Override
    public String toString() {
        return "User{" +
                "username='" + username + '\'' +
                ", password='" + password + '\'' +
                ", cookie='" + cookie + '\'' +
                ", proxyId='" + currentProxyId + '\'' +
                '}';
    }
}
