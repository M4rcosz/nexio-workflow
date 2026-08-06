package com.nexio.workflow.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Configuracao de persistencia MongoDB.
 */
@Configuration
@EnableMongoAuditing
@EnableMongoRepositories(basePackages = "com.nexio.workflow.infrastructure.mongodb")
public class MongoConfig {

    /**
     * Converter customizado que remove o hint {@code _class} dos documentos gravados e neutraliza
     * pontos em chaves de mapa.
     *
     * <p>Nada no modelo e polimorfico, entao o hint so serviria para vazar nomes de classes nas
     * respostas GraphQL (os mapas {@code Map<String, Object>} sao devolvidos como JSON) e para
     * habilitar carregamento arbitrario de classes na leitura.</p>
     *
     * @param factory     fabrica de conexao com o banco
     * @param context     contexto de mapeamento das entidades
     * @param conversions conversoes customizadas registradas na aplicacao
     * @return converter configurado
     */
    @Bean
    public MappingMongoConverter mappingMongoConverter(MongoDatabaseFactory factory,
                                                       MongoMappingContext context,
                                                       MongoCustomConversions conversions) {
        DbRefResolver dbRefResolver = new DefaultDbRefResolver(factory);
        MappingMongoConverter converter = new MappingMongoConverter(dbRefResolver, context);
        converter.setCustomConversions(conversions);
        converter.setCodecRegistryProvider(factory);
        converter.setTypeMapper(new DefaultMongoTypeMapper(null));
        converter.setMapKeyDotReplacement("\\u002e");
        converter.afterPropertiesSet();
        return converter;
    }
}
