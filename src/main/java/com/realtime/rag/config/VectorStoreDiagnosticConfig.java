
package com.realtime.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Configuration
@Profile("!test")
public class VectorStoreDiagnosticConfig {

    private static final Logger log =
            LoggerFactory.getLogger(VectorStoreDiagnosticConfig.class);

    @Bean
    CommandLineRunner checkVectors(VectorStore vectorStore) {
        return args -> {
            try {
                List<Document> results = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query("knowledge")
                                .topK(3)
                                .build()
                );

                log.info(
                        "=== Vector Store Sample Records Found: {} ===",
                        results.size()
                );

                results.forEach(doc -> {
                    log.info("ID: {}", doc.getId());
                    log.info("Content: {}", doc.getText());
                    log.info("Metadata: {}", doc.getMetadata());
                    log.info("-----------------------------------");
                });

            } catch (Exception ex) {
                log.warn(
                        "Vector store diagnostic check encountered an issue: {}",
                        ex.getMessage()
                );
            }
        };
    }
}
