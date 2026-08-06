package com.nexio.workflow.infrastructure.mongodb;

import com.nexio.workflow.domain.model.ExecutionStep;
import com.nexio.workflow.domain.model.MapSanitizer;
import com.nexio.workflow.domain.model.WorkflowExecution;
import java.util.List;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

/**
 * Aplica as invariantes de escrita de {@link WorkflowExecution} em todo save.
 *
 * <p>Vale para o payload do gatilho e para o output de cada passo, que sao os mapas livres da
 * execucao, e para o teto de passos: {@code addStep} sozinho nao basta, porque a lista pode ter
 * sido montada por outro caminho.</p>
 *
 * <p>So roda na escrita, de proposito: a hidratacao continua leniente, senao um unico documento
 * malformado derrubaria toda consulta de listagem da colecao. Ver {@link MapSanitizer}.</p>
 */
@Component
public class WorkflowExecutionWriteValidationCallback implements BeforeConvertCallback<WorkflowExecution> {

    @Override
    public WorkflowExecution onBeforeConvert(WorkflowExecution execution, String collection) {
        MapSanitizer.validate(execution.getTriggerPayload(), "triggerPayload");
        List<ExecutionStep> steps = execution.getSteps();
        if (steps.size() > WorkflowExecution.MAX_STEPS) {
            throw new IllegalArgumentException(
                    "Limite de " + WorkflowExecution.MAX_STEPS + " passos excedido na execucao '"
                            + execution.getId() + "': " + steps.size());
        }
        for (int i = 0; i < steps.size(); i++) {
            MapSanitizer.validate(steps.get(i).output(), "steps[" + i + "].output");
        }
        return execution;
    }
}
