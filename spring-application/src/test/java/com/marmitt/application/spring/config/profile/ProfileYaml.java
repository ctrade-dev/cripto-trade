package com.marmitt.application.spring.config.profile;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Le os {@code application*.yml} como o Spring os combina por perfil, sem subir contexto. */
final class ProfileYaml {

    private ProfileYaml() {
    }

    /** Ultimo arquivo tem precedencia, como na ordem de perfis do Spring. */
    static Binder binder(String... files) throws IOException {
        return binder(Map.of(), files);
    }

    /** {@code env} faz o papel das variaveis de ambiente: vence os arquivos. */
    static Binder binder(Map<String, Object> env, String... files) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String file : files) {
            List<PropertySource<?>> loaded = loader.load(file, new ClassPathResource(file));
            loaded.forEach(sources::addFirst);
        }
        sources.addFirst(new MapPropertySource("test-env", env));
        return Binder.get(environment);
    }
}
