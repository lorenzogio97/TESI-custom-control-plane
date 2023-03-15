package it.lorenzogiorgi.tesi.common;

public class MECNode {
    private EnvoyProxy frontProxy;
    private String dockerIpAddress;
    private int dockerPort;
    private double availableMemory;
    private double totalMemory;
    private double availableCPU;
    private double totalCPU;
    private String MECId;

    public EnvoyProxy getFrontProxy() {
        return frontProxy;
    }

    public void setFrontProxy(EnvoyProxy frontProxy) {
        this.frontProxy = frontProxy;
    }

    public String getDockerIpAddress() {
        return dockerIpAddress;
    }

    public void setDockerIpAddress(String dockerIpAddress) {
        this.dockerIpAddress = dockerIpAddress;
    }

    public double getAvailableMemory() {
        return availableMemory;
    }

    public void setAvailableMemory(double availableMemory) {
        this.availableMemory = availableMemory;
    }

    public double getTotalMemory() {
        return totalMemory;
    }

    public void setTotalMemory(double totalMemory) {
        this.totalMemory = totalMemory;
    }

    public double getAvailableCPU() {
        return availableCPU;
    }

    public void setAvailableCPU(double availableCPU) {
        this.availableCPU = availableCPU;
    }

    public double getTotalCPU() {
        return totalCPU;
    }

    public void setTotalCPU(double totalCPU) {
        this.totalCPU = totalCPU;
    }

    public String getMECId() {
        return MECId;
    }

    public void setMECId(String MECId) {
        this.MECId = MECId;
    }

    public int getDockerPort() {
        return dockerPort;
    }

    public void setDockerPort(int dockerPort) {
        this.dockerPort = dockerPort;
    }

    @Override
    public String toString() {
        return "MECNode{" +
                "frontProxy=" + frontProxy +
                ", ipAddress='" + dockerIpAddress + '\'' +
                ", availableMemory=" + availableMemory +
                ", totalMemory=" + totalMemory +
                ", availableCPU=" + availableCPU +
                ", totalCPU=" + totalCPU +
                ", MECId='" + MECId + '\'' +
                '}';
    }
}
