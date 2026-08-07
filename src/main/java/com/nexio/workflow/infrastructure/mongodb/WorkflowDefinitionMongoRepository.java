package com.nexio.workflow.infrastructure.mongodb;

import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.enums.TriggerType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Contrato de persistencia do Spring Data para {@link WorkflowDefinition}.
 *
 * <p>Somente consultas derivadas: nenhuma regra de negocio mora aqui. A traducao do vocabulario
 * de dominio para estes metodos e feita por {@link WorkflowDefinitionMongoAdapter}.</p>
 */
public interface WorkflowDefinitionMongoRepository extends MongoRepository<WorkflowDefinition, String> {

    /**
     * Lista uma pagina das definicoes cadastradas.
     *
     * <p>Devolve {@code List} e nao {@code Page} de proposito: o {@code Page} obriga o Spring Data a
     * emitir um {@code count} sobre a colecao inteira alem da consulta em si, e o unico consumidor
     * da porta descarta esse total. Era uma segunda ida ao banco paga em toda listagem para nada.</p>
     *
     * @param pageable recorte da consulta
     * @return lista de definicoes do recorte, vazia quando nao ha registros
     */
    List<WorkflowDefinition> findBy(Pageable pageable);

    /**
     * Lista as definicoes habilitadas.
     *
     * <p>Casa com o indice composto {@code def_enabled_trigger} declarado na entidade.</p>
     *
     * @return lista de definicoes habilitadas, vazia quando nao ha registros
     */
    List<WorkflowDefinition> findByEnabledTrue();

    /**
     * Lista uma pagina das definicoes habilitadas.
     *
     * <p>O filtro continua servido pelo prefixo {@code enabled} do indice composto
     * {@code def_enabled_trigger}: paginar nao custa um indice novo.</p>
     *
     * @param pageable recorte da consulta
     * @return lista de definicoes habilitadas do recorte, vazia quando nao ha registros
     */
    List<WorkflowDefinition> findByEnabledTrue(Pageable pageable);

    /**
     * Lista as definicoes de um determinado tipo de gatilho.
     *
     * @param type tipo do gatilho
     * @return lista de definicoes com o gatilho informado, vazia quando nao ha registros
     */
    List<WorkflowDefinition> findByTriggerConfigType(TriggerType type);

    /**
     * Remove a definicao pelo identificador e devolve quantos documentos foram apagados.
     *
     * <p>Nao usa {@code deleteById} herdado de proposito: aquele metodo devolve {@code void} e
     * obrigaria a um {@code existsById} previo para descobrir se o documento existia, o que custa
     * duas idas ao banco e ainda deixa uma janela de corrida entre a verificacao e a remocao. Esta
     * consulta derivada de exclusao resolve tudo em uma unica operacao, devolvendo a contagem
     * relatada pelo proprio MongoDB.</p>
     *
     * @param id identificador da definicao
     * @return quantidade de documentos removidos, {@code 0} quando nada casou
     */
    long deleteWorkflowDefinitionById(String id);
}
