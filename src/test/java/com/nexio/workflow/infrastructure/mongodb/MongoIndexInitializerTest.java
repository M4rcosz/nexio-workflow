package com.nexio.workflow.infrastructure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.nexio.workflow.AbstractMongoIntegrationTest;
import com.nexio.workflow.infrastructure.config.MongoConfig;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Teste do {@link MongoIndexInitializer} contra um MongoDB real.
 *
 * <p>Fora de dev e teste os indices nao sao mais criados na subida do contexto, entao este
 * componente e o unico caminho que os garante. Duas coisas precisam valer: ele cria mesmo os
 * indices declarados nas entidades, partindo de colecoes que nem existem, e rodar de novo nao
 * quebra nem duplica nada -- ele executa em toda instancia, a cada subida.</p>
 */
@DataMongoTest
@Import({MongoConfig.class, MongoIndexInitializer.class})
class MongoIndexInitializerTest extends AbstractMongoIntegrationTest {

    @Autowired
    private MongoIndexInitializer initializer;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void createsDeclaredIndexesAndIsSafeToRunAgain() {
        mongoTemplate.getDb().getCollection(DEFINITIONS_COLLECTION).drop();
        mongoTemplate.getDb().getCollection(EXECUTIONS_COLLECTION).drop();

        initializer.run(new DefaultApplicationArguments());

        assertThat(indexNames(DEFINITIONS_COLLECTION))
                .containsExactlyInAnyOrder("_id_", "def_enabled_trigger", "def_trigger_type");
        assertThat(indexNames(EXECUTIONS_COLLECTION))
                .containsExactlyInAnyOrder("_id_", "exec_workflow_created_id", "exec_status_created");

        assertThatCode(() -> initializer.run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        assertThat(indexNames(DEFINITIONS_COLLECTION)).hasSize(3);
        assertThat(indexNames(EXECUTIONS_COLLECTION)).hasSize(3);
    }

    private List<String> indexNames(String collection) {
        List<String> names = new ArrayList<>();
        for (Document index : mongoTemplate.getCollection(collection).listIndexes()) {
            names.add(index.getString("name"));
        }
        return names;
    }
}
