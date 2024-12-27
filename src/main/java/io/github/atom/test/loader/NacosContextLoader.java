package io.github.atom.test.loader;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.NacosConfigProperties;
import com.alibaba.cloud.nacos.client.NacosPropertySourceLocator;
import io.github.atom.test.utils.TestClassUtil;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Nacos上下文装载器
 *
 * @author Zhang Kangkang
 * @version 1.0
 */
public class NacosContextLoader {

    /**
     * nacos加载线程
     */
    private static final ExecutorService NACOS_LOAD_POOL = Executors.newFixedThreadPool(1);

    /**
     * nacos加载标识
     */
    private static final CountDownLatch NACOS_CONFIG_LOAD_COUNT_DOWN = new CountDownLatch(1);

    /**
     * 读取nacos配置信息
     *
     * @param context 上下文
     */
    public static void read(AnnotationConfigApplicationContext context) {

        Class<?> nacosConfigPropertiesClass =
            TestClassUtil.tryGetClass("com.alibaba.cloud.nacos.NacosConfigProperties");
        if (Objects.isNull(nacosConfigPropertiesClass)) {
            return;
        }
        NACOS_LOAD_POOL.execute(() -> {
            try {
                Class<?> refreshAuto =
                    TestClassUtil.tryGetClass("org.springframework.cloud.autoconfigure.RefreshAutoConfiguration");
                if (Objects.nonNull(refreshAuto)) {
                    context.register(refreshAuto);
                }
                Binder binder = Binder.get(context.getEnvironment());
                NacosConfigProperties nacosConfig = new NacosConfigProperties();
                binder.bind(NacosConfigProperties.PREFIX, Bindable.ofInstance(nacosConfig));
                NacosConfigManager nacosConfigManager = new NacosConfigManager(nacosConfig);
                NacosPropertySourceLocator bean = new NacosPropertySourceLocator(nacosConfigManager);
                PropertySource<?> locate = bean.locate(context.getEnvironment());
                ConfigurableEnvironment environment = context.getEnvironment();
                environment.getPropertySources().addFirst(locate);
            } finally {
                loaded();
            }
        });
    }

    /**
     * 等待加载
     */
    public static void await() {

        try {
            NACOS_CONFIG_LOAD_COUNT_DOWN.await();
        } catch (Exception e) {
            throw new RuntimeException("nacos load failed", e);
        }
    }

    /**
     * 加载完成
     */
    public static void loaded() {

        NACOS_CONFIG_LOAD_COUNT_DOWN.countDown();
    }

}
