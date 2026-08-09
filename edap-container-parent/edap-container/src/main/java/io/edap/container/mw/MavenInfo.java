package io.edap.container.mw;

public class MavenInfo {

    private String artifactId;
    private String groupId;
    private String version;

    public String getArtifact() {
        return groupId + ":" + artifactId + ":" + version;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
