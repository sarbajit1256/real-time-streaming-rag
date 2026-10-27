
package com.realtime.rag;

import java.util.Arrays;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RealtimeStreamingRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                RealtimeStreamingRagApplication.class, args);
    }

    @Bean
    CommandLineRunner checkVectorStoreBeans(
            ApplicationContext context,
            VectorStore vectorStore) {

        return args -> {
            System.out.println("=== VECTOR STORE BEAN CHECK ===");

            System.out.println(
                    "VectorStore beans: " +
                    Arrays.toString(
                            context.getBeanNamesForType(
                                    VectorStore.class))
            );

            System.out.println(
                    "Vector-related beans: " +
                    Arrays.toString(
                            Arrays.stream(
                                    context.getBeanDefinitionNames())
                                    .filter(name ->
                                            name.toLowerCase()
                                                .contains("qdrant") ||
                                            name.toLowerCase()
                                                .contains("vector"))
                                    .toArray())
            );

            System.out.println("=== VECTOR STORE WRITE TEST ===");

            try {
                Document document = new Document(
                        "Diagnostic document for testing Qdrant writes."
                );

                vectorStore.add(List.of(document));

                System.out.println("VECTOR STORE WRITE: SUCCESS");

            } catch (Exception e) {
                System.out.println("VECTOR STORE WRITE: FAILED");
                e.printStackTrace();
            }
        };
    }
}
