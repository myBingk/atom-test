package io.github.atom.test.annonation;

import java.lang.annotation.*;

/**
 * 动态按需加载测试主类
 *
 * @author Zhang Kangkang
 * @version 1.0
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface DynamicBeanLoading {

    /**
     * 程序运行主类
     */
    Class<?> mainClass();

    /**
     * 测试用例运行所需的配置文件，需在target/classes里能找到
     */
    String[] properties();

    /**
     * 是否读取nacos配置
     */
    boolean nacosEnabled() default false;

    /**
     * 测试用例运行所需装载的静态类
     */
    Class<?>[] staticClasses() default {};

}
