package it.lorenzogiorgi.tesi.common;

public class Service {
    private String name;
    private String imageName;
    private String imageTag;
    private String endpointMapping;
    private int exposedPort;
    private double maxCPU;
    private double maxMemory;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

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

    public double getMaxCPU() {
        return maxCPU;
    }

    public void setMaxCPU(double maxCPU) {
        this.maxCPU = maxCPU;
    }

    public double getMaxMemory() {
        return maxMemory;
    }

    public void setMaxMemory(double maxMemory) {
        this.maxMemory = maxMemory;
    }

    public String getImageTag() {
        return imageTag;
    }

    public void setImageTag(String imageTag) {
        this.imageTag = imageTag;
    }

    public int getExposedPort() {
        return exposedPort;
    }

    public void setExposedPort(int exposedPort) {
        this.exposedPort = exposedPort;
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
