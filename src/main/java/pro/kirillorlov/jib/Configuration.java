package pro.kirillorlov.jib;

import com.google.cloud.tools.jib.plugins.extension.JibPluginExtensionException;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

public class Configuration {
    public List<LayerFilter> filters = new ArrayList<>();

    public static class LayerFilter {
        public String fileGlob = "";
        public String gavcGlob = "";
        public String layer = "";

        public void throwIfInvalid() throws JibPluginExtensionException {
            if (fileGlob.isEmpty() && gavcGlob.isEmpty()) {
                throw new JibPluginExtensionException(JibLayerFilterExtension.class, "either file glob or maven gavc glob should be specified");
            }
        }

        public PathMatcher globMatcher() {
            if (fileGlob.isEmpty())
                return null;
            return FileSystems.getDefault().getPathMatcher("glob:" + fileGlob);
        }

        public DefaultArtifact gavcArtifact() {
            if (gavcGlob.isEmpty())
                return null;
            return new DefaultArtifact(gavcGlob);
        }

        public boolean matches(Artifact artifact, Path pathInContainer) {
            boolean result = true;
            PathMatcher pathMatcher = globMatcher();
            if (pathMatcher != null) {
                result &= pathMatcher.matches(pathInContainer);
            }

            DefaultArtifact gavcPattern = gavcArtifact();
            if (gavcPattern != null) {
                if (artifact == null) {
                    return false;
                }
                result &= matches(gavcPattern.getGroupId(), artifact.getGroupId()) &&
                        matches(gavcPattern.getArtifactId(), artifact.getArtifactId()) &&
                        matches(gavcPattern.getVersion(), artifact.getVersion());
            }
            return result;
        }

        private boolean matches(String pattern, String value) {
            if (pattern.isEmpty())
                return true;

            return FilenameUtils.wildcardMatch(value, pattern);
        }
    }
}