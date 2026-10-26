/*package com.realtime.rag.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String question, String asOf) {
}
*/


package com.realtime.rag.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank String question,
        String asOf,
        String selectedDocumentId
) {
}
