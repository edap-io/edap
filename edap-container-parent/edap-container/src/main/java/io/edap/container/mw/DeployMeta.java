package io.edap.container.mw;

public class DeployMeta {

    private String earName;
    private String deployTime;
    private String onlineTime;
    private String previousEarName;
    private String deployer;
    private String onliner;
    private String artifactVersion;
    private String buildTime;
    private String commitMsg;
    private String committer;
    private String commitTime;

    public String getEarName() {
        return earName;
    }

    public void setEarName(String earName) {
        this.earName = earName;
    }

    public String getDeployTime() {
        return deployTime;
    }

    public void setDeployTime(String deployTime) {
        this.deployTime = deployTime;
    }

    public String getPreviousEarName() {
        return previousEarName;
    }

    public void setPreviousEarName(String previousEarName) {
        this.previousEarName = previousEarName;
    }

    public String getDeployer() {
        return deployer;
    }

    public void setDeployer(String deployer) {
        this.deployer = deployer;
    }

    public String getArtifactVersion() {
        return artifactVersion;
    }

    public void setArtifactVersion(String artifactVersion) {
        this.artifactVersion = artifactVersion;
    }

    public String getBuildTime() {
        return buildTime;
    }

    public void setBuildTime(String buildTime) {
        this.buildTime = buildTime;
    }

    public String getCommitMsg() {
        return commitMsg;
    }

    public void setCommitMsg(String commitMsg) {
        this.commitMsg = commitMsg;
    }

    public String getCommitter() {
        return committer;
    }

    public void setCommitter(String committer) {
        this.committer = committer;
    }

    public String getCommitTime() {
        return commitTime;
    }

    public void setCommitTime(String commitTime) {
        this.commitTime = commitTime;
    }

    public String getOnlineTime() {
        return onlineTime;
    }

    public void setOnlineTime(String onlineTime) {
        this.onlineTime = onlineTime;
    }

    public String getOnliner() {
        return onliner;
    }

    public void setOnliner(String onliner) {
        this.onliner = onliner;
    }
}
