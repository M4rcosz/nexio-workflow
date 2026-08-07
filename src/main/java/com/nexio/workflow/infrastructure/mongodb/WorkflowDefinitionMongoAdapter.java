package com.nexio.workflow.infrastructure.mongodb;

import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.domain.exception.WorkflowConcurrentlyModifiedException;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

/**
 * Adaptador que implementa {@link WorkflowDefinitionPort} sobre o Spring Data MongoDB.
 *
 * <p>Este e o unico ponto do sistema que conhece {@link WorkflowDefinitionMongoRepository}: a
 * assinatura dos metodos publicos so expoe tipos do JDK e do dominio, entao nada do Spring Data
 * vaza para a camada de aplicacao.</p>
 */
@Repository
public class WorkflowDefinitionMongoAdapter implements WorkflowDefinitionPort {

    private final WorkflowDefinitionMongoRepository repository;

    /**
     * Cria o adaptador com injecao por construtor.
     *
     * @param repository repositorio Spring Data das definicoes
     */
    public WorkflowDefinitionMongoAdapter(WorkflowDefinitionMongoRepository repository) {
        this.repository = repository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A falha de bloqueio otimista e traduzida aqui, e nao repassada: a porta promete nao vazar
     * tipo do Spring Data, e {@code OptimisticLockingFailureException} e exatamente isso. Sem a
     * traducao, duas atualizacoes concorrentes davam ao perdedor um erro interno -- resposta que
     * nenhum cliente tenta de novo -- para uma situacao que se resolve relendo e reenviando.</p>
     *
     * <p>A mensagem original fica de fora de proposito: ela carrega o nome da colecao e o filtro
     * BSON cru da atualizacao que falhou, e o destino desta excecao e a resposta ao cliente.</p>
     */
    @Override
    public WorkflowDefinition save(WorkflowDefinition definition) {
        try {
            return repository.save(definition);
        } catch (OptimisticLockingFailureException e) {
            throw new WorkflowConcurrentlyModifiedException(definition.getId(), e);
        }
    }

    @Override
    public Optional<WorkflowDefinition> findById(String id) {
        return repository.findById(id);
    }

    /**
     * {@inheritDoc}
     *
     * <p>O recorte do dominio vira um {@link OffsetPageable} aqui dentro: nenhum tipo do Spring
     * Data aparece na assinatura da porta.</p>
     */
    @Override
    public List<WorkflowDefinition> findAll(PageQuery page) {
        return repository.findBy(OffsetPageable.of(page));
    }

    @Override
    public List<WorkflowDefinition> findEnabled(PageQuery page) {
        return repository.findByEnabledTrue(OffsetPageable.of(page));
    }

    @Override
    public List<WorkflowDefinition> findEnabled() {
        return repository.findByEnabledTrue();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Usa a consulta derivada de exclusao para descobrir se algo foi removido: a contagem vem
     * da propria operacao de remocao, sem o {@code existsById} previo que custaria uma segunda ida
     * ao banco e abriria janela de corrida.</p>
     */
    @Override
    public boolean deleteById(String id) {
        return repository.deleteWorkflowDefinitionById(id) > 0;
    }

    @Override
    public boolean existsById(String id) {
        return repository.existsById(id);
    }
}
