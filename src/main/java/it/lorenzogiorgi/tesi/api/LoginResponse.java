package it.lorenzogiorgi.tesi.api;

public class LoginResponse {

    private String domainName;

    public LoginResponse(String domainName) {
        this.domainName = domainName;
    }


    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }
}
