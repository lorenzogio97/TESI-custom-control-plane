package it.lorenzogiorgi.tesi.api;

public class LoginResponse {
    private boolean logged;

    private String domainName;

    public LoginResponse(boolean logged, String domainName) {
        this.logged = logged;
        this.domainName = domainName;
    }

    public boolean isLogged() {
        return logged;
    }

    public void setLogged(boolean logged) {
        this.logged = logged;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }
}
