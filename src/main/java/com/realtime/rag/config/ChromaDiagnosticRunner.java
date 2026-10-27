package com.realtime.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(
    name = "app.vectorstore.diagnostic.enabled", 
    havingValue = "true", 
    matchIfMissing = true
)
public class ChromaDiagnosticRunner {

    private static final Logger log = LoggerFactory.getLogger(ChromaDiagnosticRunner.class);

    @Bean
    CommandLineRunner verifyVectorStoreState(VectorStore vectorStore) {
        return args -> {
            log.info("========== VECTOR STORE INSPECTION ==========");
            log.info("Active VectorStore Implementation: {}", vectorStore.getClass().getSimpleName());
            log.info("=============================================");
        };
    }
}