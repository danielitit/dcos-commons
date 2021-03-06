package com.mesosphere.sdk.scheduler.uninstall;

import com.mesosphere.sdk.offer.*;
import com.mesosphere.sdk.scheduler.OfferResources;
import com.mesosphere.sdk.state.StateStore;
import org.apache.mesos.Protos;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Records to persistent storage the result of uninstalling/destroying resources in the process of installing the
 * service by marking them with a tombstone id, then notifying the uninstall plan of the changes.
 */
public class UninstallRecorder {

    private final Logger logger = LoggingUtils.getLogger(getClass());
    private final StateStore stateStore;
    private final Collection<ResourceCleanupStep> resourceSteps;

    public UninstallRecorder(StateStore stateStore, Collection<ResourceCleanupStep> resourceSteps) {
        this.stateStore = stateStore;
        this.resourceSteps = resourceSteps;
    }

    /**
     * Used in the case of decommissioning when we are proactively removing offered resources via offer evaluation.
     */
    public void recordRecommendations(Collection<OfferRecommendation> offerRecommendations) throws Exception {
        // Group the recommendations by offer id:
        Map<Protos.OfferID, OfferResources> byOfferId = new HashMap<>();
        for (OfferRecommendation offerRecommendation : offerRecommendations) {
            if (!(offerRecommendation instanceof UninstallRecommendation)) {
                continue;
            }
            OfferResources offerResources = byOfferId.get(offerRecommendation.getOffer().getId());
            if (offerResources == null) {
                offerResources = new OfferResources(offerRecommendation.getOffer());
                byOfferId.put(offerRecommendation.getOffer().getId(), offerResources);
            }
            offerResources.add(((UninstallRecommendation) offerRecommendation).getResource());
        }
        recordResources(byOfferId.values());
    }

    /**
     * Used by both decommissioning and uninstalling to record resources that are about to be unreserved.
     */
    public void recordResources(Collection<OfferResources> offerResources) throws Exception {
        Set<String> allResourceIds = new HashSet<>();
        for (OfferResources offerResource : offerResources) {
            allResourceIds.addAll(offerResource.getResources().stream()
                    .map(ResourceUtils::getResourceId)
                    .filter(resourceId -> resourceId.isPresent())
                    .map(resourceId -> resourceId.get())
                    .collect(Collectors.toSet()));
        }
        if (allResourceIds.isEmpty()) {
            return;
        }

        // Optimizations:
        // - Only one StateStore read.
        // - Only one StateStore write, which only updates modified tasks.
        // - Rebuild each task object at most once.
        // - Avoid modifying tasks which aren't affected.

        Collection<Protos.TaskInfo> updatedTasks = withRemovedResources(stateStore.fetchTasks(), allResourceIds);

        logger.info("{} resourceId{}{} to prune were found in {} task{}{}",
                allResourceIds.size(),
                allResourceIds.size() == 1 ? "" : "s",
                allResourceIds,
                updatedTasks.size(),
                updatedTasks.size() == 1 ? "" : "s",
                updatedTasks.stream().map(t -> t.getName()).collect(Collectors.toList()));

        // Store the updated tasks with pruned resources in the state store.
        if (!updatedTasks.isEmpty()) {
            stateStore.storeTasks(updatedTasks);
        }

        // Notify the resource steps in the uninstall plan or decommission plan about these resource ids.
        resourceSteps.forEach(step -> step.updateResourceStatus(allResourceIds));
    }

    /**
     * Returns an updated copy of any {@code taskInfos} which omit resources matching the provided {@code resourceIds}.
     */
    private static Collection<Protos.TaskInfo> withRemovedResources(
            Collection<Protos.TaskInfo> taskInfos, Set<String> resourceIdsToRemove) {
        Collection<Protos.TaskInfo> updatedTasks = new ArrayList<>();
        for (Protos.TaskInfo taskInfo : taskInfos) {
            Collection<Protos.Resource> updatedTaskResources =
                    filterResources(taskInfo.getResourcesList(), resourceIdsToRemove);
            Collection<Protos.Resource> updatedExecutorResources =
                    filterResources(taskInfo.getExecutor().getResourcesList(), resourceIdsToRemove);
            if (updatedTaskResources == null && updatedExecutorResources == null) {
                // This task doesn't have any of the targeted resource ids. Ignore it and move on to the next task.
                continue;
            }

            Protos.TaskInfo.Builder taskBuilder = taskInfo.toBuilder();

            if (updatedTaskResources != null) {
                // Update task-level resources.
                taskBuilder
                        .clearResources()
                        .addAllResources(updatedTaskResources);
            }

            if (updatedExecutorResources != null) {
                // Update executor-level resources.
                taskBuilder.getExecutorBuilder()
                        .clearResources()
                        .addAllResources(updatedExecutorResources);
            }

            updatedTasks.add(taskBuilder.build());
        }
        return updatedTasks;
    }

    /**
     * Returns the provided {@code resources} with any holding matching {@code resourceIdsToRemove} omitted. Returns
     * {@code null} if no changes were made.
     */
    private static Collection<Protos.Resource> filterResources(
            Collection<Protos.Resource> resources, Set<String> resourceIdsToRemove) {
        if (resources.isEmpty()) {
            return null;
        }
        boolean anyUpdates = false;
        Collection<Protos.Resource> filteredResources = new ArrayList<>();
        for (Protos.Resource resource : resources) {
            Optional<String> resourceId = ResourceUtils.getResourceId(resource);
            if (resourceId.isPresent() && resourceIdsToRemove.contains(resourceId.get())) {
                // Matching resource found. Omit it from the filtered list.
                anyUpdates = true;
            } else {
                filteredResources.add(resource);
            }
        }
        return anyUpdates ? filteredResources : null;
    }
}
