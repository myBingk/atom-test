package io.github.atom.test.loader;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 测试上下文加载器
 *
 * @author Zhang Kangkang
 * @version 1.0
 */
public interface TestContextLoader {

    /**
     * 判断能否处理bean
     *
     * @param context     上下文
     * @param name        beanName
     * @param targetClass beanClass
     * @param annotations bean依赖注解
     * @return 能否处理依赖
     */
    boolean canHandle(AnnotationConfigApplicationContext context, String name, Class<?> targetClass, Class<?>[] annotations);

    /**
     * 获取bean
     *
     * @param context     上下文
     * @param name        beanName
     * @param targetClass beanClass
     * @param annotations bean依赖注解
     * @return bean
     */
    Object getOrCreate(AnnotationConfigApplicationContext context, String name, Class<?> targetClass, Class<?>[] annotations);

}
