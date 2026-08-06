package com.nexio.workflow.infrastructure.mongodb;

import com.nexio.workflow.domain.model.WorkflowExecution;
import com.nexio.workflow.domain.model.enums.ExecutionStatus;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Contrato de persistencia do Spring Data para {@link WorkflowExecution}.
 *
 * <p>Somente consultas derivadas: nenhuma regra de negocio mora aqui. A traducao do vocabulario
 * de dominio para estes metodos e feita por {@link WorkflowExecutionMongoAdapter}.</p>
 */
public interface WorkflowExecutionMongoRepository extends MongoRepository<WorkflowExecution, String> {

    /**
     * Lista uma pagina das execucoes de uma definicao, da mais recente para a mais antiga.
     *
     * <p>Ordena por {@code createdAt}, e nao por {@code startedAt}: {@code startedAt} so e
     * preenchido em {@code markRunning}, e como o nulo ordena como menor valor no MongoDB, uma
     * execucao recem criada (PENDING) apareceria por ultimo na lista de mais recentes.</p>
     *
     * <p>A ordenacao casa exatamente com o indice composto {@code exec_workflow_created}
     * ({@code {workflowId: 1, createdAt: -1}}) declarado na entidade, entao a consulta e coberta
     * pelo indice e nao exige ordenacao em memoria.</p>
     *
     * @param workflowId identificador da definicao de workflow
     * @param pageable   recorte da consulta
     * @return lista de execucoes ordenada por {@code createdAt} decrescente, vazia quando nao ha registros
     */
    List<WorkflowExecution> findByWorkflowIdOrderByCreatedAtDesc(String workflowId, Pageable pageable);

    /**
     * Lista as execucoes em um determinado estado.
     *
     * @param status estado da execucao
     * @return lista de execucoes no estado informado, vazia quando nao ha registros
     */
    List<WorkflowExecution> findByStatus(ExecutionStatus status);

    /**
     * Remove todas as execucoes de uma definicao e devolve quantos documentos foram apagados.
     *
     * <p>Consulta derivada de exclusao: uma unica ida ao banco, com a contagem relatada pelo
     * proprio MongoDB, em vez de listar os documentos para depois apaga-los.</p>
     *
     * @param workflowId identificador da definicao de workflow
     * @return quantidade de documentos removidos, {@code 0} quando nada casou
     */
    long deleteByWorkflowId(String workflowId);
}
