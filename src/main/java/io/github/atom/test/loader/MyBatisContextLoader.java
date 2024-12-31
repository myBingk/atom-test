package io.github.atom.test.loader;

import com.google.common.collect.Sets;
import io.github.atom.test.FastDynamicBeanLoadingTest;
import io.github.atom.test.utils.TestClassUtil;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;

/**
 * MyBatis上下文装载器
 *
 * @author Zhang Kangkang
 * @version 1.0
 */
public class MyBatisContextLoader implements TestContextLoader {

    /**
     * mapper包
     */
    static final Set<String> MAPPER_PACKAGE_SET = Sets.newHashSet();

    /**
     * 判断能否处理bean
     *
     * @param context     上下文
     * @param name        beanName
     * @param targetClass beanClass
     * @param annotations bean依赖注解
     * @return 能否处理依赖
     */
    @Override
    public boolean canHandle(AnnotationConfigApplicationContext context,
                             String name,
                             Class<?> targetClass,
                             Class<?>[] annotations) {

        return targetClass.isInterface() && containPackage(targetClass);
    }

    /**
     * 是否包含mapper包路径
     *
     * @param clazz 类
     * @return 是否包含mapper包路径
     */
    private boolean containPackage(Class<?> clazz) {

        for (String mapperPackage : MAPPER_PACKAGE_SET) {
            if (clazz.isInterface() && clazz.getPackage().getName().startsWith(mapperPackage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取bean
     *
     * @param context     上下文
     * @param name        beanName
     * @param targetClass beanClass
     * @param annotations bean依赖注解
     * @return bean
     */
    @Override
    public Object getOrCreate(AnnotationConfigApplicationContext context,
                              String name,
                              Class<?> targetClass,
                              Class<?>[] annotations) {

        this.addSeataConfig(context);
        Class<?> sqlSessionFactoryClass = TestClassUtil.tryGetClass("org.apache.ibatis.session.SqlSessionFactory");
        List<Class<?>> classDependencyConfigurationList =
            FastDynamicBeanLoadingTest.BEAN_CLASS_DEPENDENCY_CONFIGURATION_CLASSES.getOrDefault(sqlSessionFactoryClass,
                Collections.emptyList()
            );
        for (Class<?> dependencyConfiguration : classDependencyConfigurationList) {
            context.register(dependencyConfiguration);
        }
        return FastDynamicBeanLoadingTest.refreshAndGet(context, name, targetClass);
    }

    /**
     * 添加Seata配置
     *
     * @param context 上下文
     */
    private void addSeataConfig(AnnotationConfigApplicationContext context) {

        Class<?> globalTransactionScanner =
            TestClassUtil.tryGetClass("io.seata.spring.annotation.GlobalTransactionScanner");
        if (Objects.isNull(globalTransactionScanner)) {
            return;
        }

        List<String> environmentPostProcessorList =
            SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader());
        SpringApplication springApplication = new SpringApplication(FastDynamicBeanLoadingTest.TEST_MAIN_RUN_CLASS);
        for (String environmentPostProcessorName : environmentPostProcessorList) {
            Class<?> factoryImplementationClass = TestClassUtil.tryGetClass(environmentPostProcessorName);
            Object instance = TestClassUtil.tryInstance(factoryImplementationClass);
            if (Objects.isNull(instance)) {
                continue;
            }
            ((EnvironmentPostProcessor)instance).postProcessEnvironment(context.getEnvironment(), springApplication);
        }

        List<Class<?>> classDependencyConfigurationList =
            FastDynamicBeanLoadingTest.BEAN_CLASS_DEPENDENCY_CONFIGURATION_CLASSES.getOrDefault(globalTransactionScanner,
                Collections.emptyList()
            );
        for (Class<?> dependencyConfiguration : classDependencyConfigurationList) {
            context.register(dependencyConfiguration);
        }
    }

    /**
     * 添加mapper扫描配置
     *
     * @param configurationClass        配置类
     * @param mapperScannerConfigMethod 扫描方法
     */
    public static void addMapperScannerConfig(Class<?> configurationClass, Method mapperScannerConfigMethod) {

        try {
            Class<? extends Annotation> mapperScanClass =
                TestClassUtil.tryGetAnnotation("org.mybatis.spring.annotation.MapperScan");
            if (Objects.nonNull(mapperScanClass)) {
                Annotation mapperScan = configurationClass.getAnnotation(mapperScanClass);
                if (Objects.nonNull(mapperScan)) {
                    Class<? extends Annotation> annotationType = mapperScan.annotationType();
                    Method method = annotationType.getDeclaredMethod("basePackages");
                    String[] value = (String[])method.invoke(mapperScan);
                    if (TestClassUtil.isArrayNotEmpty(value)) {
                        MAPPER_PACKAGE_SET.addAll(Arrays.asList(value));
                    }
                }
            }
            Class<?> mapperScannerConfigurerClass =
                TestClassUtil.tryGetClass("org.mybatis.spring.mapper.MapperScannerConfigurer");
            if (Objects.nonNull(mapperScannerConfigurerClass) && mapperScannerConfigMethod.getReturnType()
                .equals(mapperScannerConfigurerClass)) {
                Object instance = configurationClass.newInstance();
                Object invoke = mapperScannerConfigMethod.invoke(instance);
                Field basePackageField = mapperScannerConfigurerClass.getDeclaredField("basePackage");
                basePackageField.setAccessible(true);
                MAPPER_PACKAGE_SET.add((String)basePackageField.get(invoke));
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * 是否getBaseMapper方法
     *
     * @param configClass 配置类
     * @param methodName  方法名
     * @return 是否getBaseMapper方法
     */
    public static boolean isGetBaseMapperMethod(Class<?> configClass, String methodName) {

        String baseMapperMethodName = "getBaseMapper";
        return configClass.isInterface() && TestClassUtil.hasInterface(configClass,
            "com.baomidou.mybatisplus.extension.service.impl.ServiceImpl"
        ) && methodName.equals(baseMapperMethodName);
    }

    /**
     * 获取baseMapper对象
     *
     * @param rootClass 类
     * @param field     字段
     * @return baseMapper对象
     */
    public static Class<?> getBaseMapperRawFiled(Class<?> rootClass, Field field) {

        String baseMapperFiledName = "baseMapper";
        if (TestClassUtil.hasInterface(rootClass, "com.baomidou.mybatisplus.extension.service.impl.ServiceImpl")
            && field.getName().equals(baseMapperFiledName)
            && field.getType().equals(TestClassUtil.tryGetClass("com.baomidou.mybatisplus.core.mapper.BaseMapper"))) {
            Type genericSuperclass = rootClass.getGenericSuperclass();
            genericSuperclass = Objects.requireNonNull(TestClassUtil.tryGetClass(genericSuperclass.getTypeName()))
                .getGenericSuperclass();
            return TestClassUtil.getActualClass(genericSuperclass, null);
        }
        return null;
    }

}
