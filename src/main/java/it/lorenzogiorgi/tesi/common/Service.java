package it.lorenzogiorgi.tesi.common;

public class Service {
    private String imageName;
    private String endpointMapping;
    private float maxCPU;
    private float maxMemory;

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public String getEndpointMapping() {
        return endpointMapping;
    }

    public void setEndpointMapping(String endpointMapping) {
        this.endpointMapping = endpointMapping;
    }

    public float getMaxCPU() {
        return maxCPU;
    }

    public void setMaxCPU(float maxCPU) {
        this.maxCPU = maxCPU;
    }

    public float getMaxMemory() {
        return maxMemory;
    }

    public void setMaxMemory(float maxMemory) {
        this.maxMemory = maxMemory;
    }

    @Override
    public String toString() {
        return "Service{" +
                "imageName='" + imageName + '\'' +
                ", endpointMapping='" + endpointMapping + '\'' +
                ", maxCPU=" + maxCPU +
                ", maxMemory=" + maxMemory +
                '}';
    }
}
