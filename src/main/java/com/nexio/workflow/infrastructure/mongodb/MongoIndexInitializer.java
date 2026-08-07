package com.nexio.workflow.infrastructure.mongodb;

import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowExecution;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.stereotype.Component;

/**
 * Garante, depois que a aplicacao sobe, os indices declarados nas entidades.
 *
 * <p>Substitui o {@code spring.data.mongodb.auto-index-creation} fora de dev e teste. A criacao
 * automatica roda dentro da subida do contexto, o que tem tres consequencias ruins em producao: a
 * aplicacao passa a precisar de Mongo acessivel e de privilegio de indice so para bootar; a subida
 * fica presa enquanto o indice e construido numa colecao ja populada; e qualquer alteracao futura
 * numa definicao de {@code @CompoundIndex} sem trocar o nome do indice derruba <b>toda</b>
 * instancia no meio de um rollout, com {@code IndexOptionsConflict}.</p>
 *
 * <p>Aqui a resolucao e a mesma ({@link IndexResolver} le as anotacoes das entidades, entao a
 * declaracao continua sendo uma so, na propria entidade), mas o momento e outro e a falha nao e
 * fatal: cada indice e tratado isoladamente e um erro vira log, nao instancia morta. Um indice
 * ausente degrada consulta; uma instancia que nao sobe tira a aplicacao do ar.</p>
 *
 * <p>{@code ensureIndex} e idempotente: repetir a chamada com a mesma definicao e o mesmo nome nao
 * faz nada. Rodar a cada subida, em toda instancia, e seguro.</p>
 */
@Component
@ConditionalOnProperty(name = "nexio.mongodb.ensure-indexes", havingValue = "true", matchIfMissing = true)
public class MongoIndexInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MongoIndexInitializer.class);

    private static final List<Class<?>> INDEXED_ENTITIES = List.of(
            WorkflowDefinition.class,
            WorkflowExecution.class);

    private final MongoOperations mongoOperations;
    private final IndexResolver indexResolver;

    /**
     * Cria o inicializador com injecao por construtor.
     *
     * @param mongoOperations operacoes do Spring Data usadas para falar com o banco
     * @param mappingContext  contexto de mapeamento de onde as anotacoes de indice sao lidas
     */
    public MongoIndexInitializer(MongoOperations mongoOperations, MongoMappingContext mappingContext) {
        this.mongoOperations = mongoOperations;
        this.indexResolver = IndexResolver.create(mappingContext);
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Class<?> entityType : INDEXED_ENTITIES) {
            ensureIndexesFor(entityType);
        }
    }

    private void ensureIndexesFor(Class<?> entityType) {
        String entityName = entityType.getSimpleName();
        IndexOperations indexOperations = mongoOperations.indexOps(entityType);
        Set<String> existing = existingIndexNames(indexOperations, entityName);
        if (existing == null) {
            return;
        }
        for (IndexDefinition definition : indexResolver.resolveIndexFor(entityType)) {
            ensureIndex(indexOperations, definition, existing, entityName);
        }
    }

    private Set<String> existingIndexNames(IndexOperations indexOperations, String entityName) {
        try {
            Set<String> names = new HashSet<>();
            for (IndexInfo info : indexOperations.getIndexInfo()) {
                names.add(info.getName());
            }
            return names;
        } catch (DataAccessException error) {
            LOG.error("Nao foi possivel listar os indices de {}: os indices declarados nao foram garantidos",
                    entityName, error);
            return null;
        }
    }

    private void ensureIndex(IndexOperations indexOperations, IndexDefinition definition,
                             Set<String> existing, String entityName) {
        try {
            String name = indexOperations.ensureIndex(definition);
            if (existing.contains(name)) {
                LOG.debug("Indice '{}' de {} ja existia", name, entityName);
            } else {
                LOG.info("Indice '{}' de {} criado", name, entityName);
            }
        } catch (DataAccessException error) {
            LOG.error("Falha ao garantir indice de {} com chaves {}: a aplicacao segue no ar, mas as "
                            + "consultas cobertas por ele farao varredura de colecao",
                    entityName, definition.getIndexKeys().toJson(), error);
        }
    }
}
