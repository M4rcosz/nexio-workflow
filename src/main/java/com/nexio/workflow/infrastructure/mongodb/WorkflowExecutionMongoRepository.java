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
     * <p><b>O desempate por {@code _id} faz parte do contrato, e nao e detalhe.</b>
     * {@code createdAt} nao e unico: varios disparos do mesmo workflow caem no mesmo instante, e
     * ordenacao com empate nao tem ordem definida. Como esta consulta e paginada por
     * {@code skip}/{@code limit}, duas paginas consecutivas poderiam repetir uma execucao e pular
     * outra -- e o cliente nao teria como perceber a perda. O {@code _id} torna a ordem total.</p>
     *
     * <p>A ordenacao casa exatamente com o indice composto {@code exec_workflow_created_id}
     * ({@code {workflowId: 1, createdAt: -1, _id: -1}}) declarado na entidade, entao a consulta e
     * coberta pelo indice e nao exige ordenacao em memoria. Tirar o {@code _id} de um dos dois
     * lados -- da ordem ou do indice -- troca a leitura ordenada por ordenacao em memoria.</p>
     *
     * @param workflowId identificador da definicao de workflow
     * @param pageable   recorte da consulta
     * @return lista de execucoes ordenada por {@code createdAt} decrescente e {@code _id}
     *         decrescente, vazia quando nao ha registros
     */
    List<WorkflowExecution> findByWorkflowIdOrderByCreatedAtDescIdDesc(String workflowId, Pageable pageable);

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
