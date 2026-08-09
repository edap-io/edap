package io.edap.container.mw;

public class DeployInfo {

    private DeployMeta current;
    private DeployMeta staging;
    private DeployMeta previous;

    public DeployMeta getCurrent() {
        return current;
    }

    public void setCurrent(DeployMeta current) {
        this.current = current;
    }

    public DeployMeta getStaging() {
        return staging;
    }

    public void setStaging(DeployMeta staging) {
        this.staging = staging;
    }

    public DeployMeta getPrevious() {
        return previous;
    }

    public void setPrevious(DeployMeta previous) {
        this.previous = previous;
    }
}
