
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
    CommandLineRunner checkChromaBeans(
            ApplicationContext context,
            VectorStore vectorStore) {

        return args -> {
            System.out.println("=== CHROMA BEAN CHECK ===");

            System.out.println(
                    "VectorStore beans: " +
                    Arrays.toString(
                            context.getBeanNamesForType(
                                    VectorStore.class))
            );

            System.out.println(
                    "Chroma-related beans: " +
                    Arrays.toString(
                            Arrays.stream(
                                    context.getBeanDefinitionNames())
                                    .filter(name ->
                                            name.toLowerCase()
                                                .contains("chroma"))
                                    .toArray())
            );

            System.out.println("=== CHROMA WRITE TEST ===");

            try {
                Document document = new Document(
                        "Diagnostic document for testing ChromaDB writes."
                );

                vectorStore.add(List.of(document));

                System.out.println("CHROMA WRITE: SUCCESS");

            } catch (Exception e) {
                System.out.println("CHROMA WRITE: FAILED");
                e.printStackTrace();
            }
        };
    }
}
