package com.llmdiscovery.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmdiscovery.common.AppProperties;
import com.llmdiscovery.common.SecretCipher;
import com.llmdiscovery.discovery.MetadataRepository;
import com.llmdiscovery.discovery.TargetDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClassificationConfig {

    @Bean
    public LlmClient llmClient(AppProperties p) {
        return new LlmClient(p.getLlmBaseUrl(), p.getLlmApiKey(), p.getLlmModel(), p.getLlmTimeout(),
                p.getLlmMaxRetries(), 2, p.isLlmJsonMode(), 500);
    }

    @Bean
    public ClassificationService classificationService(MetadataRepository metadata, TargetDatabase targetDatabase,
            ClassificationResultRepository results, SecretCipher cipher, LlmClient llm, AppProperties settings,
            ObjectMapper mapper) {
        return new ClassificationService(metadata, targetDatabase, results, cipher, llm, settings, mapper);
    }
}
