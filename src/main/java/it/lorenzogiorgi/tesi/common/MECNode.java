package it.lorenzogiorgi.tesi.common;

public class MECNode {
    private EnvoyProxy frontProxy;
    private String ipAddress;
    private float availableMemory;
    private float totalMemory;
    private float availableCPU;
    private float totalCPU;
    private String MECId;

    public EnvoyProxy getFrontProxy() {
        return frontProxy;
    }

    public void setFrontProxy(EnvoyProxy frontProxy) {
        this.frontProxy = frontProxy;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public float getAvailableMemory() {
        return availableMemory;
    }

    public void setAvailableMemory(float availableMemory) {
        this.availableMemory = availableMemory;
    }

    public float getTotalMemory() {
        return totalMemory;
    }

    public void setTotalMemory(float totalMemory) {
        this.totalMemory = totalMemory;
    }

    public float getAvailableCPU() {
        return availableCPU;
    }

    public void setAvailableCPU(float availableCPU) {
        this.availableCPU = availableCPU;
    }

    public float getTotalCPU() {
        return totalCPU;
    }

    public void setTotalCPU(float totalCPU) {
        this.totalCPU = totalCPU;
    }

    public String getMECId() {
        return MECId;
    }

    public void setMECId(String MECId) {
        this.MECId = MECId;
    }

    @Override
    public String toString() {
        return "MECNode{" +
                "frontProxy=" + frontProxy +
                ", ipAddress='" + ipAddress + '\'' +
                ", availableMemory=" + availableMemory +
                ", totalMemory=" + totalMemory +
                ", availableCPU=" + availableCPU +
                ", totalCPU=" + totalCPU +
                ", MECId='" + MECId + '\'' +
                '}';
    }
}
