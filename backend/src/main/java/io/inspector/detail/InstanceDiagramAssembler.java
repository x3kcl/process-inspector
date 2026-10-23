package io.inspector.detail;

import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.detail.InstanceDetailService.Lanes;
import io.inspector.dto.InstanceDetail.CurrentActivity;
import io.inspector.dto.InstanceDiagram;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Diagram tab. Moved from {@link InstanceDetailService} (issue #475). */
final class InstanceDiagramAssembler {

    private final InstanceDetailService host;

    InstanceDiagramAssembler(InstanceDetailService host) {
        this.host = host;
    }

    /* ================= diagram ================= */

    public InstanceDiagram diagram(String engineId, String instanceId) {
        EngineConfig engine = host.engineForRead(engineId);
        Map<String, Object> historic = host.requireHistoric(engine, instanceId);
        String definitionId = InstanceDetailService.str(historic, "processDefinitionId");
        Map<String, Object> definition =
                host.flowable.getProcessDefinition(engine, CallPriority.INTERACTIVE, definitionId);
        if (definition == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "process definition " + definitionId + " not found on " + engineId);
        }
        String deploymentId = InstanceDetailService.str(definition, "deploymentId");
        String resourceName = InstanceDetailService.resourceNameOf(definition);
        String xml = deploymentId == null || resourceName == null
                ? null
                : host.flowable.deploymentResourceData(engine, CallPriority.INTERACTIVE, deploymentId, resourceName);
        if (xml == null || xml.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "BPMN resource for definition " + definitionId + " not found on " + engineId);
        }

        boolean ended = InstanceDetailService.str(historic, "endTime") != null;
        List<String> active = ended
                ? List.of()
                : host.currentActivities(engine, instanceId).stream()
                        .map(CurrentActivity::activityId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
        Lanes lanes = ended ? Lanes.EMPTY : host.scanInstanceLanes(engine, instanceId);
        List<String> deadLetterActivities = lanes.deadLetter().stream()
                .map(job -> host.failingActivityIdOf(engine, job))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return new InstanceDiagram(xml, active, deadLetterActivities);
    }
}
