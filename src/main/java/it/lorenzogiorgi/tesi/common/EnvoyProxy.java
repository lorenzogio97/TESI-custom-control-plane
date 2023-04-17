package it.lorenzogiorgi.tesi.common;

public class EnvoyProxy {
    private String ipAddress;
    private String id;


    public EnvoyProxy(String ipAddress, String id) {
        this.ipAddress = ipAddress;
        this.id = id;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "EnvoyProxy{" +
                "ipAddress='" + ipAddress + '\'' +
                ", id='" + id + '\'' +
                '}';
    }
}
