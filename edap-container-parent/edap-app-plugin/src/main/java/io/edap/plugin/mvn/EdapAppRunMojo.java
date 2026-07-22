package io.edap.plugin.mvn;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.util.Set;

@Mojo(name = "run",
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class EdapAppRunMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // 获取解析后的所有依赖（包括传递依赖）
        Set<Artifact> artifacts = project.getArtifacts();
        for (Artifact artifact : artifacts) {
            getLog().info("Dependency: " + artifact.getFile());
            getLog().info("artifactId: " + artifact.getArtifactId());
            getLog().info("groupId: " + artifact.getGroupId());
            getLog().info("scope: " + artifact.getScope());
        }
    }
}
