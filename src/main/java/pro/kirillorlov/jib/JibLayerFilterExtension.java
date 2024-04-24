package pro.kirillorlov.jib;

import com.google.cloud.tools.jib.api.buildplan.ContainerBuildPlan;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.FileEntry;
import com.google.cloud.tools.jib.api.buildplan.LayerObject;
import com.google.cloud.tools.jib.maven.extension.JibMavenPluginExtension;
import com.google.cloud.tools.jib.maven.extension.MavenData;
import com.google.cloud.tools.jib.plugins.extension.ExtensionLogger;
import com.google.cloud.tools.jib.plugins.extension.ExtensionLogger.LogLevel;
import com.google.cloud.tools.jib.plugins.extension.JibPluginExtensionException;
import com.google.common.base.Verify;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.project.*;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.Dependency;

@Named
@Singleton
public class JibLayerFilterExtension implements JibMavenPluginExtension<Configuration> {
    @Inject
    ProjectDependenciesResolver dependencyResolver;

    @Override
    public Optional<Class<Configuration>> getExtraConfigType() {
        return Optional.of(Configuration.class);
    }

    @Override
    public ContainerBuildPlan extendContainerBuildPlan(
            ContainerBuildPlan buildPlan,
            Map<String, String> properties,
            Optional<Configuration> config,
            MavenData mavenData,
            ExtensionLogger logger)
            throws JibPluginExtensionException {
        logger.log(LogLevel.LIFECYCLE, "Running Advanced Jib Layer Filter Extension");
        if (!config.isPresent()) {
            logger.log(LogLevel.WARN, "Nothing configured for Advanced Jib Layer Filter Extension");
            return buildPlan;
        }

        // Calculate new layers collection
        List<String> originalLayerNames = buildPlan.getLayers().stream().map(LayerObject::getName).collect(Collectors.toList());

        ContainerBuildPlan.Builder newPlanBuilder = buildPlan.toBuilder();
        Map<String, FileEntriesLayer.Builder> newLayersMap = new HashMap<>();
        LinkedList<FileEntriesLayer.Builder> newLayersList = new LinkedList<>();

        for (Configuration.LayerFilter filter : config.get().filters) {
            filter.throwIfInvalid();

            if (!filter.layer.isEmpty() && originalLayerNames.contains(filter.layer)) {
                throw new JibPluginExtensionException(JibLayerFilterExtension.class,
                        String.format("moving files into existing layer '%s' is prohibited; specify a new layer name in '<toLayer>'.", filter.layer));
            }

            if (!newLayersMap.containsKey(filter.layer)) {
                FileEntriesLayer.Builder builder = newLayersMap.computeIfAbsent(filter.layer, layerName -> FileEntriesLayer.builder().setName(layerName));
                newLayersList.add(builder);
            }
        }
        for (String originalLayerName : originalLayerNames) {
            FileEntriesLayer.Builder builder = newLayersMap.computeIfAbsent(originalLayerName, layerName -> FileEntriesLayer.builder().setName(layerName));
            newLayersList.add(builder);
        }

        newPlanBuilder.setLayers(Collections.emptyList());

        Map<File, List<Artifact>> projectDependencies = getDependenciesMap(mavenData);

        List<FileEntriesLayer> originalLayers = (List<FileEntriesLayer>) buildPlan.getLayers();
        Map<FileEntry, String> fileToLayer = originalLayers.stream()
                .flatMap(t -> t.getEntries().stream().map(u -> Pair.of(u, t.getName())))
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue));

        for (Map.Entry<FileEntry, String> fileLayer : fileToLayer.entrySet()) {
            FileEntry file = fileLayer.getKey();
            String layerName = fileLayer.getValue();

            for(Configuration.LayerFilter filter: config.get().filters) {
                Path pathInContainer = Paths.get(file.getExtractionPath().toString());
                List<Artifact> artifacts = projectDependencies.getOrDefault(file.getSourceFile().toFile(), Collections.emptyList());
                Artifact artifact = artifacts.isEmpty() ? null : artifacts.get(0);
                if (filter.matches(artifact, pathInContainer)) {
                    layerName = filter.layer;
                }
            }

            if (!StringUtils.isEmpty(layerName)) {
                newLayersMap.get(layerName).addEntry(file);
            }
        }

        for (FileEntriesLayer.Builder builder : newLayersList) {
            FileEntriesLayer fileEntriesLayer = builder.build();
            if (!fileEntriesLayer.getEntries().isEmpty()) {
                newPlanBuilder.addLayer(fileEntriesLayer);
            }
        }

        return newPlanBuilder.build();
    }

    private Map<File, List<Artifact>> getDependenciesMap(MavenData mavenData) throws JibPluginExtensionException {
        try {
            DefaultDependencyResolutionRequest dependencyResolutionRequest = new DefaultDependencyResolutionRequest();
            dependencyResolutionRequest.setMavenProject(mavenData.getMavenProject());
            dependencyResolutionRequest.setRepositorySession(mavenData.getMavenSession().getRepositorySession());

            DependencyResolutionResult resolvedArtifacts = dependencyResolver.resolve(dependencyResolutionRequest);

            return resolvedArtifacts
                    .getResolvedDependencies()
                    .stream()
                    .map(Dependency::getArtifact)
                    .filter(Objects::nonNull)
                    .collect(Collectors.groupingBy(Artifact::getFile));
        } catch (DependencyResolutionException e) {
            throw new JibPluginExtensionException(JibLayerFilterExtension.class, "Failed to resolve dependencies", e);
        }
    }

}