package io.github.atom.test.loader;

import com.google.common.collect.Sets;
import io.github.atom.test.FastDynamicBeanLoadingTest;
import io.github.atom.test.utils.TestClassUtil;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class MyBatisContextLoader implements TestContextLoader {

    static final Set<String> MAPPER_PACKAGE_SET = Sets.newHashSet();

    @Override
    public boolean canHandle(AnnotationConfigApplicationContext context, String name, Class<?> targetClass, Class<?>[] annotations) {
        return targetClass.isInterface() && containPackage(targetClass);
    }

    private boolean containPackage(Class<?> clazz) {
        for (String mapperPackage : MAPPER_PACKAGE_SET) {
            if (clazz.isInterface() && clazz.getPackage().getName().startsWith(mapperPackage)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object getOrCreate(AnnotationConfigApplicationContext context, String name, Class<?> targetClass, Class<?>[] annotations) {

        this.addSeataConfig(context);
        Class<?> sqlSessionFactoryClass = TestClassUtil.tryGetClass("org.apache.ibatis.session.SqlSessionFactory");
        List<Class<?>> classDependencyConfigurationList = FastDynamicBeanLoadingTest.BEAN_CLASS_DEPENDENCY_CONFIGURATION_CLASSES.getOrDefault(sqlSessionFactoryClass, Collections.emptyList());
        for (Class<?> dependencyConfiguration : classDependencyConfigurationList) {
            context.register(dependencyConfiguration);
        }
        return FastDynamicBeanLoadingTest.refreshAndGet(context, name, targetClass);
    }

    private void addSeataConfig(AnnotationConfigApplicationContext context) {

        Class<?> globalTransactionScanner = TestClassUtil.tryGetClass("io.seata.spring.annotation.GlobalTransactionScanner");
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
            ((EnvironmentPostProcessor) instance).postProcessEnvironment(context.getEnvironment(), springApplication);
        }

        List<Class<?>> classDependencyConfigurationList = FastDynamicBeanLoadingTest.BEAN_CLASS_DEPENDENCY_CONFIGURATION_CLASSES.getOrDefault(globalTransactionScanner, Collections.emptyList());
        for (Class<?> dependencyConfiguration : classDependencyConfigurationList) {
            context.register(dependencyConfiguration);
        }
    }

    public static void addMapperScannerConfig(Class<?> configurationClass, Method mapperScannerConfigMethod) {

        try {
            Class<? extends Annotation> mapperScanClass = TestClassUtil.tryGetAnnotation("org.mybatis.spring.annotation.MapperScan");
            if (Objects.nonNull(mapperScanClass)) {
                Annotation mapperScan = configurationClass.getAnnotation(mapperScanClass);
                if (Objects.nonNull(mapperScan)) {
                    Class<? extends Annotation> annotationType = mapperScan.annotationType();
                    Method method = annotationType.getDeclaredMethod("basePackages");
                    String[] value = (String[]) method.invoke(mapperScan);
                    if (TestClassUtil.isArrayNotEmpty(value)) {
                        MAPPER_PACKAGE_SET.addAll(Arrays.asList(value));
                    }
                }
            }
            Class<?> mapperScannerConfigurerClass = TestClassUtil.tryGetClass("org.mybatis.spring.mapper.MapperScannerConfigurer");
            if (Objects.nonNull(mapperScannerConfigurerClass) && mapperScannerConfigMethod.getReturnType().equals(mapperScannerConfigurerClass)) {
                Object instance = configurationClass.newInstance();
                Object invoke = mapperScannerConfigMethod.invoke(instance);
                Field basePackageField = mapperScannerConfigurerClass.getDeclaredField("basePackage");
                basePackageField.setAccessible(true);
                MAPPER_PACKAGE_SET.add((String) basePackageField.get(invoke));
            }
        } catch (Throwable ignore) {
        }
    }

    public static boolean isGetBaseMapperMethod(Class<?> configClass, String methodName) {
        String baseMapperMethodName = "getBaseMapper";
        return configClass.isInterface()
                && TestClassUtil.hasInterface(configClass, "com.baomidou.mybatisplus.extension.service.impl.ServiceImpl")
                && methodName.equals(baseMapperMethodName);
    }

    public static Class<?> getBaseMapperRawFiled(Class<?> rootClass, Field field) {
        String baseMapperFiledName = "baseMapper";
        if (TestClassUtil.hasInterface(rootClass, "com.baomidou.mybatisplus.extension.service.impl.ServiceImpl")
                && field.getName().equals(baseMapperFiledName)
                && field.getType().equals(TestClassUtil.tryGetClass("com.baomidou.mybatisplus.core.mapper.BaseMapper"))) {
            Type genericSuperclass = rootClass.getGenericSuperclass();
            genericSuperclass = Objects.requireNonNull(TestClassUtil.tryGetClass(genericSuperclass.getTypeName())).getGenericSuperclass();
            return TestClassUtil.getActualClass(genericSuperclass, null);
        }
        return null;
    }

}
