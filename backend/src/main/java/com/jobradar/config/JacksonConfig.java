package com.jobradar.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.jobradar.common.TextUtil;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {
    /** PostgreSQL rejects NUL in text columns, so it is removed from every string in a request body. */
    @Bean
    Module stripNulFromStrings() {
        SimpleModule module = new SimpleModule("strip-nul");
        module.addDeserializer(String.class, new StringDeserializer() {
            @Override
            public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return TextUtil.stripNul(super.deserialize(p, ctxt));
            }
        });
        return module;
    }
}
