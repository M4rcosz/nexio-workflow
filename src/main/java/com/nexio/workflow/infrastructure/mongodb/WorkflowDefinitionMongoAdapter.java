package com.nexio.workflow.infrastructure.mongodb;

import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import java.util.List;
import java.util.Optional;
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

    @Override
    public WorkflowDefinition save(WorkflowDefinition definition) {
        return repository.save(definition);
    }

    @Override
    public Optional<WorkflowDefinition> findById(String id) {
        return repository.findById(id);
    }

    @Override
    public List<WorkflowDefinition> findAll() {
        return repository.findAll();
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
