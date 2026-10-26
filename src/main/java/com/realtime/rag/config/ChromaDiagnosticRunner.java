package com.realtime.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;

@Configuration
@ConditionalOnProperty(
    name = "app.chroma.diagnostic.enabled", 
    havingValue = "true", 
    matchIfMissing = false
)
public class ChromaDiagnosticRunner {

    private static final Logger log = LoggerFactory.getLogger(ChromaDiagnosticRunner.class);

    @Bean
    CommandLineRunner verifyChromaState(ChromaApi chromaApi, VectorStore vectorStore) {
        return args -> {
            log.info("========== CHROMADB COLLECTION INSPECTION ==========");

            if (vectorStore instanceof ChromaVectorStore cvs) {
                try {
                    Field colIdField = ChromaVectorStore.class.getDeclaredField("collectionId");
                    colIdField.setAccessible(true);
                    String collectionId = (String) colIdField.get(cvs);

                    Field colNameField = ChromaVectorStore.class.getDeclaredField("collectionName");
                    colNameField.setAccessible(true);
                    String collectionName = (String) colNameField.get(cvs);

                    log.info("Target Collection Name : {}", collectionName);
                    log.info("Target Collection ID   : {}", collectionId);

                    Long count = chromaApi.countEmbeddings("SpringAiTenant", "SpringAiDatabase", collectionId);
                    log.info("Total Documents in ChromaDB : {}", count);
                } catch (Exception e) {
                    log.warn("Could not read internal collection properties: {}", e.getMessage());
                }
            }

            log.info("====================================================");
        };
    }
}