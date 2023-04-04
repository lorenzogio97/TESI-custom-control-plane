package it.lorenzogiorgi.tesi.common;

import java.util.List;

public class Application {
    private String authDomain;
    private String domain;

    private List<String> allowedMECId;
    private List<Service> services;

    public String getAuthDomain() {
        return authDomain;
    }

    public void setAuthDomain(String authDomain) {
        this.authDomain = authDomain;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public List<Service> getServices() {
        return services;
    }

    public void setServices(List<Service> services) {
        this.services = services;
    }

    public List<String> getAllowedMECId() {
        return allowedMECId;
    }

    public void setAllowedMECId(List<String> allowedMECId) {
        this.allowedMECId = allowedMECId;
    }
}
