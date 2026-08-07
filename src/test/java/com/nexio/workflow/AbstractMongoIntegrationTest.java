package com.nexio.workflow;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;

/**
 * Base dos testes de integracao que precisam de um MongoDB real.
 *
 * <p>O container e <b>um so</b> para toda a suite. Antes, cada classe de teste declarava o seu
 * proprio {@code @Container static}, e o ciclo de vida do JUnit sobe e derruba um container por
 * classe: a mesma imagem {@code mongo:8} era iniciada tres vezes por execucao, o que domina o tempo
 * de CI sem trazer isolamento algum que a limpeza das colecoes ja nao de.</p>
 *
 * <p>Por isso o container e iniciado a mao, num inicializador estatico, e nunca parado. Chamar
 * {@code stop()} e desnecessario: o Ryuk, que o proprio Testcontainers sobe junto, remove o
 * container quando a JVM da suite termina, inclusive se ela morrer de forma anormal.</p>
 *
 * <p>Como a base e compartilhada, a limpeza das colecoes vem para ca e roda antes de cada teste de
 * qualquer subclasse: sem isso, o que uma classe deixasse gravado apareceria nas asercoes da
 * seguinte -- e um {@code _id} repetido entre duas classes viraria erro de chave duplicada.</p>
 *
 * <p>O perfil {@code test} e ativado aqui porque e ele que liga o
 * {@code spring.data.mongodb.auto-index-creation}, desligado por padrao.</p>
 */
@ActiveProfiles("test")
public abstract class AbstractMongoIntegrationTest {

    /** Colecao das definicoes de workflow. */
    protected static final String DEFINITIONS_COLLECTION = "workflow_definitions";

    /** Colecao das execucoes de workflow. */
    protected static final String EXECUTIONS_COLLECTION = "workflow_executions";

    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    static {
        MONGO.start();
    }

    @Autowired
    private MongoTemplate sharedMongoTemplate;

    @BeforeEach
    void clearSharedCollections() {
        sharedMongoTemplate.getCollection(DEFINITIONS_COLLECTION).deleteMany(new Document());
        sharedMongoTemplate.getCollection(EXECUTIONS_COLLECTION).deleteMany(new Document());
    }
}
