package it.lorenzogiorgi.tesi.common;

import java.util.HashMap;

public class MECNode {
    private HashMap<String, EnvoyProxy> frontProxies;
    private String dockerIpAddress;
    private int dockerPort;
    private double availableMemory;
    private double totalMemory;
    private double availableCPU;
    private double totalCPU;


    public HashMap<String, EnvoyProxy> getFrontProxies() {
        return frontProxies;
    }

    public void setFrontProxy(HashMap<String, EnvoyProxy> frontProxies) {
        this.frontProxies = frontProxies;
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



    public int getDockerPort() {
        return dockerPort;
    }

    public void setDockerPort(int dockerPort) {
        this.dockerPort = dockerPort;
    }

    @Override
    public String toString() {
        return "MECNode{" +
                "frontProxy=" + frontProxies +
                ", ipAddress='" + dockerIpAddress + '\'' +
                ", availableMemory=" + availableMemory +
                ", totalMemory=" + totalMemory +
                ", availableCPU=" + availableCPU +
                ", totalCPU=" + totalCPU +
                '}';
    }
}
