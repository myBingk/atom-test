package io.github.atom.test.loader;

import com.google.common.collect.Maps;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.bootstrap.builders.ReferenceBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Dubbo上下文装载器
 *
 * @author Zhang Kangkang
 * @version 1.0
 */
public class DubboContextLoader implements TestContextLoader {

    private static final Map<String, Object> DUBBO_SIMPLE_CACHE = new HashMap<>();

    @Override
    public boolean canHandle(AnnotationConfigApplicationContext context, String name, Class<?> targetClass, Class<?>[] annotations) {
        if (annotations == null) {
            return false;
        }
        for (Class<?> annotationClass : annotations) {
            if (DubboReference.class == annotationClass) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object getOrCreate(AnnotationConfigApplicationContext context, String name, Class<?> targetClass, Class<?>[] annotations) {

        Object referenceConfigCache = DUBBO_SIMPLE_CACHE.get(targetClass.getSimpleName());
        if (Objects.nonNull(referenceConfigCache)) {
            return referenceConfigCache;
        }

        ReferenceConfig reference =
                ReferenceBuilder.newBuilder()
                        .interfaceClass(targetClass)
                        .build();
        ConfigurableEnvironment env = context.getEnvironment();

        ApplicationConfig applicationConfig = new ApplicationConfig();
        applicationConfig.setName(env.getProperty("dubbo.application.name"));

        ConsumerConfig consumerConfig = new ConsumerConfig();
        consumerConfig.setVersion(env.getProperty("dubbo.consumer.version"));
        consumerConfig.setTimeout(Integer.parseInt(Objects.requireNonNull(env.getProperty("dubbo.consumer.timeout"))));
        consumerConfig.setCheck(Boolean.parseBoolean(env.getProperty("dubbo.consumer.check")));

        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setAddress(env.getProperty("dubbo.registry.address"));
        registryConfig.setGroup(env.getProperty("spring.cloud.nacos.discovery.group"));
        Map<String, String> paramaeters = Maps.newHashMap();
        paramaeters.put("namespace", env.getProperty("spring.cloud.nacos.discovery.namespace"));
        registryConfig.setParameters(paramaeters);
        registryConfig.setVersion(env.getProperty("dubbo.provider.version"));

        DubboBootstrap.getInstance()
                .application(applicationConfig)
                .registry(registryConfig)
                .protocol(new ProtocolConfig(env.getProperty("dubbo.protocol.name"),
                        Integer.parseInt(Objects.requireNonNull(env.getProperty("dubbo.protocol.port")))))
                .consumer(
                        consumerConfig
                )
                .reference(reference).start();
        Object referenceObject = reference.get();
        DUBBO_SIMPLE_CACHE.put(targetClass.getSimpleName(), referenceObject);
        return referenceObject;
    }

}
