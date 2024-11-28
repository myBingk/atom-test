package io.github.atom.test;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.github.atom.test.annonation.DynamicBeanLoading;
import io.github.atom.test.annonation.DynamicResource;
import io.github.atom.test.loader.MyBatisContextLoader;
import io.github.atom.test.loader.NacosContextLoader;
import io.github.atom.test.loader.TestContextLoader;
import io.github.atom.test.log.SpringRunnerLogInfo;
import io.github.atom.test.utils.TestClassUtil;
import javassist.util.proxy.ProxyFactory;
import javassist.util.proxy.ProxyObject;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationImportSelector;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import sun.misc.Unsafe;

import javax.annotation.Resource;
import java.beans.Introspector;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 动态bean加载
 *
 * @author Zhang Kangkang
 * @version 1.0
 */
@Slf4j
@RunWith(SpringRunnerLogInfo.class)
public class FastDynamicBeanLoadingTest {

    /**
     * 全局上下文，主要存储全局属性
     */
    public final static AnnotationConfigApplicationContext ALL_CONTEXT = new AnnotationConfigApplicationContext();

    /**
     * bean实现的接口
     */
    private static final Map<Class<?>, List<Class<?>>> BEAN_CLASS_IMPL_MAP = new ConcurrentHashMap<>(16);

    /**
     * yaml文件加载器
     */
    private static final YamlPropertySourceLoader YAML_LOADER = new YamlPropertySourceLoader();

    /**
     * 测试运行主类
     */
    public static Class<?> TEST_MAIN_RUN_CLASS;

    /**
     * 项目主包
     */
    private static String MAIN_CLASS_PACKAGE;

    /**
     * 上下文缓存（class为key）
     */
    private static final Map<Class<?>, AnnotationConfigApplicationContext>
        CLASS_APPLICATION_MAP
        = new ConcurrentHashMap<>(16);

    /**
     * 上下文缓存（beanName为key）
     */
    private static final Map<String, AnnotationConfigApplicationContext> NAME_APPLICATION_MAP = new ConcurrentHashMap<>(
        16);

    private static final ConversionService CONVERSION_SERVICE = new DefaultConversionService();

    /**
     * configuration缓存（beanName为key）
     */
    public static final Map<String, List<Class<?>>>
        BEAN_NAME_DEPENDENCY_CONFIGURATION_CLASSES
        = new ConcurrentHashMap<>(16);

    /**
     * configuration缓存（beanClass为key）
     */
    public static final Map<Class<?>, List<Class<?>>>
        BEAN_CLASS_DEPENDENCY_CONFIGURATION_CLASSES
        = new ConcurrentHashMap<>(16);

    private static ThreadPoolExecutor MAIN_RUNNER_POOL;

    private static boolean IS_LOADED = false;

    @Before
    public void before() throws Exception {

        if (IS_LOADED) {
            agentTestField(this);
            return;
        }
        DynamicBeanLoading testDynamicBeanLoading = this.getClass().getAnnotation(DynamicBeanLoading.class);
        if (Objects.isNull(testDynamicBeanLoading)) {
            throw new IllegalArgumentException("The test class must be annotated with @DynamicBeanLoading");
        }

        // 加载全局属性对象
        ConfigurableEnvironment env = ALL_CONTEXT.getEnvironment();
        addPropertySource(testDynamicBeanLoading, env);
        ConfigurationPropertiesBindingPostProcessor.register((BeanDefinitionRegistry)ALL_CONTEXT.getBeanFactory());
        ALL_CONTEXT.refresh();

        if (testDynamicBeanLoading.nacosEnabled()) {
            NacosContextLoader.read(ALL_CONTEXT);
        } else {
            NacosContextLoader.loaded();
        }
        TEST_MAIN_RUN_CLASS = testDynamicBeanLoading.mainClass();
        MAIN_CLASS_PACKAGE = TEST_MAIN_RUN_CLASS.getPackage().getName();

        scanBeansAndAgent(TEST_MAIN_RUN_CLASS, this);
        scanSpringBeans();
        loadStaticClassDependency(testDynamicBeanLoading);
        IS_LOADED = true;
    }

    private static void loadStaticClassDependency(DynamicBeanLoading testDynamicBeanLoading) {

        if (TestClassUtil.isArrayEmpty(testDynamicBeanLoading.staticClasses())) {
            return;
        }
        for (Class<?> staticClass : testDynamicBeanLoading.staticClasses()) {
            Component staticComponent = AnnotationUtils.findAnnotation(staticClass, Component.class);
            if (Objects.nonNull(staticComponent)) {
                registerNewAndGet(getBeanName(staticClass), staticClass);
                continue;
            }
            Field[] declaredFields = staticClass.getDeclaredFields();
            for (Field declaredField : declaredFields) {
                if (!Modifier.isStatic(declaredField.getModifiers())) {
                    continue;
                }
                if (TestClassUtil.isPrimitiveOrWrapper(declaredField.getType())) {
                    continue;
                }
                try {
                    Object fieldValue = registerNewAndGet(getBeanName(declaredField), declaredField.getType());
                    declaredField.setAccessible(true);
                    declaredField.set(null, fieldValue);
                } catch (Exception ignore) {
                }
            }

        }

    }

    private static void addPropertySource(DynamicBeanLoading dynamicBeanLoading, ConfigurableEnvironment env) throws
        IOException {

        for (String property : dynamicBeanLoading.properties()) {
            if (property.contains("yaml") || property.contains("yml")) {
                List<PropertySource<?>> load = YAML_LOADER.load(property, new ClassPathResource(property));
                for (PropertySource<?> propertySource : load) {
                    env.getPropertySources().addLast(propertySource);
                }
            } else {
                ResourcePropertySource propertySource = new ResourcePropertySource(property,
                    new ClassPathResource(property));
                env.getPropertySources().addLast(propertySource);
            }
        }
    }

    private static <T> T getBean(AnnotationConfigApplicationContext context, Class<T> clazz) {

        return context.getBean(clazz);
    }

    private static Object tryGetBean(AnnotationConfigApplicationContext context, String beanName, Class<?> clazz) {

        if (ApplicationContext.class.isAssignableFrom(clazz)) {
            return context;
        }
        if (Environment.class.isAssignableFrom(clazz)) {
            return context.getEnvironment();
        }
        Object bean = tryGetBean(context, beanName);
        if (Objects.nonNull(bean)) {
            return bean;
        }
        try {
            return getBean(context, clazz);
        } catch (Exception ignore) {
        }
        return null;
    }

    private static Object tryGetBean(AnnotationConfigApplicationContext context, String beanName) {

        try {
            return context.getBean(beanName);
        } catch (Exception e) {
            return null;
        }
    }

    //    @AfterClass
    //    public static void afterClass() {
    //        ALL_CONTEXT.close();
    //        for (AnnotationConfigApplicationContext context : CLASS_APPLICATION_MAP.values()) {
    //            context.close();
    //        }
    //        for (AnnotationConfigApplicationContext context : NAME_APPLICATION_MAP.values()) {
    //            context.close();
    //        }
    //    }

    private static void scanSpringBeans() {

        // 扫描Spring依赖
        AutoConfigurationImportSelector autoConfigurationImportSelector = new AutoConfigurationImportSelector();
        autoConfigurationImportSelector.setEnvironment(ALL_CONTEXT.getEnvironment());
        String[] autoConfigurationList = autoConfigurationImportSelector.selectImports(AnnotationMetadata.introspect(
            TEST_MAIN_RUN_CLASS));
        for (String autoConfiguration : autoConfigurationList) {
            Class<?> autoConfigurationClass = TestClassUtil.tryGetClass(autoConfiguration);
            if (Objects.isNull(autoConfigurationClass)) {
                continue;
            }
            analysisConfigurationClass(autoConfigurationClass);
            analysisConfigurationComponentScan(autoConfigurationClass);
        }
    }

    private static void analysisConfigurationComponentScan(Class<?> autoConfigurationClass) {

        ComponentScan componentScan = tryGetAnnotation(autoConfigurationClass, ComponentScan.class);
        if (Objects.isNull(componentScan)) {
            return;
        }
        ClassPathScanningCandidateComponentProvider
            componentScanScanner
            = new ClassPathScanningCandidateComponentProvider(false);
        componentScanScanner.addIncludeFilter(new AnnotationTypeFilter(Configuration.class));
        for (String componentScanPath : componentScan.value()) {
            Set<BeanDefinition> candidateComponents = componentScanScanner.findCandidateComponents(componentScanPath);
            for (BeanDefinition candidateComponent : candidateComponents) {
                analysisConfigurationClass(TestClassUtil.tryGetClass(candidateComponent.getBeanClassName()));
            }
        }
    }

    private static void analysisConfigurationClass(Class<?> autoConfigurationClass) {

        if (Objects.isNull(autoConfigurationClass)) {
            return;
        }
        List<Class<?>> chainClassList = Lists.newArrayList();
        List<Class<?>> importClassList = Lists.newArrayList();
        getAllChainClassAndImportClass(autoConfigurationClass, chainClassList, importClassList);
        chainClassList.add(autoConfigurationClass);
        importClassList.add(autoConfigurationClass);
        for (Class<?> importClass : importClassList) {
            addConfigurationDependency(importClass, chainClassList.toArray(new Class[0]));
        }
    }

    private static void agent(Object target,
        Class<?> clazz,
        Map<String, Object> createdProxy,
        Map<Class<?>, Object> createdClassProxy) {

        if (Objects.isNull(clazz) || Object.class.equals(clazz)) {
            return;
        }
        Class<?> superclass = clazz.getSuperclass();
        agent(target, superclass, createdProxy, createdClassProxy);
        Field[] declaredFields = clazz.getDeclaredFields();
        if (TestClassUtil.isArrayEmpty(declaredFields)) {
            return;
        }

        Constructor<?>[] declaredConstructors = clazz.getDeclaredConstructors();
        Method[] classDeclaredMethods = clazz.getDeclaredMethods();
        Set<String> otherDependencyName = Sets.newHashSet();
        Set<Class<?>> otherDependencyClass = Sets.newHashSet();
        addConstructorDependency(declaredConstructors, otherDependencyName, otherDependencyClass);
        addMethodDependency(classDeclaredMethods, otherDependencyName, otherDependencyClass);

        for (Field declaredField : declaredFields) {

            Value valueAnno = declaredField.getAnnotation(Value.class);
            if (Objects.nonNull(valueAnno)) {
                NacosContextLoader.await();
                try {
                    declaredField.setAccessible(true);
                    String value = ALL_CONTEXT.getEnvironment().resolvePlaceholders(valueAnno.value());
                    declaredField.set(target, CONVERSION_SERVICE.convert(value, declaredField.getType()));
                } catch (Exception e) {
                    log.error("注入属性失败", e);
                }
                continue;
            }

            if (isAnnotationWithDubboReference(declaredField)) {
                String beanName = getBeanName(declaredField);
                Object enhanceProxy = createDubboEnhanceProxy(beanName,
                    declaredField.getType(),
                    createdProxy,
                    createdClassProxy);
                if (StringUtils.hasText(beanName)) {
                    createdProxy.put(beanName, enhanceProxy);
                }
                createdClassProxy.put(declaredField.getType(), enhanceProxy);
                try {
                    declaredField.setAccessible(true);
                    declaredField.set(target, enhanceProxy);
                } catch (Exception e) {
                    log.error("注入dubbo依赖失败", e);
                }
                continue;
            }

            String beanName = getBeanName(declaredField);
            if (!declaredField.isAnnotationPresent(Autowired.class)
                && !declaredField.isAnnotationPresent(Resource.class)
                && !otherDependencyName.contains(beanName)
                && !otherDependencyClass.contains(declaredField.getType())) {
                continue;
            }

            Class<?> baseMapperRawFiled = MyBatisContextLoader.getBaseMapperRawFiled(target.getClass(), declaredField);
            Object cglibProxy = createCglibProxy(beanName,
                Objects.nonNull(baseMapperRawFiled) ? baseMapperRawFiled : declaredField.getType(),
                createdProxy,
                createdClassProxy);
            try {
                declaredField.setAccessible(true);
                declaredField.set(target, cglibProxy);
            } catch (Exception ignore) {
            }
        }
    }

    private static void addConstructorDependency(Constructor<?>[] declaredConstructors,
        Set<String> constructorDependency,
        Set<Class<?>> constructorDependencyClass) {

        if (TestClassUtil.isArrayEmpty(declaredConstructors)) {
            return;
        }
        for (Constructor<?> declaredConstructor : declaredConstructors) {
            Parameter[] parameters = declaredConstructor.getParameters();
            if (TestClassUtil.isArrayEmpty(parameters)) {
                continue;
            }
            for (Parameter parameter : parameters) {
                if (TestClassUtil.isPrimitiveOrWrapper(parameter.getType())) {
                    continue;
                }
                constructorDependency.add(parameter.getName());
                constructorDependencyClass.add(parameter.getType());
            }
        }
    }

    private static void addMethodDependency(Method[] declaredMethods,
        Set<String> constructorDependency,
        Set<Class<?>> constructorDependencyClass) {

        if (TestClassUtil.isArrayEmpty(declaredMethods)) {
            return;
        }
        for (Method declaredMethod : declaredMethods) {
            if (!declaredMethod.isAnnotationPresent(Autowired.class)
                && !declaredMethod.isAnnotationPresent(Resource.class)) {
                continue;
            }
            Parameter[] parameters = declaredMethod.getParameters();
            if (TestClassUtil.isArrayEmpty(parameters)) {
                continue;
            }
            for (Parameter parameter : parameters) {
                if (TestClassUtil.isPrimitiveOrWrapper(parameter.getType())) {
                    continue;
                }
                constructorDependency.add(parameter.getName());
                constructorDependencyClass.add(parameter.getType());
            }

        }
    }

    private static void scanBeansAndAgent(Class<?> mainClass, Object testTarget) {

        SpringBootApplication springBootApplication = mainClass.getAnnotation(SpringBootApplication.class);
        if (Objects.isNull(springBootApplication)) {
            throw new IllegalArgumentException("The class must be annotated with @SpringBootApplication");
        }

        // 扫描项目内依赖
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<Class<? extends Annotation>> dubboServiceClass = getDubboServiceClass();
        for (Class<? extends Annotation> serviceClass : dubboServiceClass) {
            if (Objects.isNull(serviceClass)) {
                continue;
            }
            scanner.addIncludeFilter(new AnnotationTypeFilter(serviceClass));
        }
        Set<String> scanBasePackageSet = getScanBasePackageSet(mainClass, springBootApplication);
        for (String basePackage : scanBasePackageSet) {
            Set<BeanDefinition> candidateComponents = scanner.findCandidateComponents(basePackage);
            for (BeanDefinition beanDefinition : candidateComponents) {
                Class<?> compentClass = TestClassUtil.tryGetClass(beanDefinition.getBeanClassName());
                if (Objects.isNull(compentClass)) {
                    return;
                }
                scanInterfaceDependencies(compentClass);
                addConfigurationDependency(compentClass);
            }
        }

        agentTestField(testTarget);

    }

    private static void agentTestField(Object testTarget) {

        for (Field declaredField : testTarget.getClass().getDeclaredFields()) {
            String name = getBeanName(declaredField);
            Class<?> type = declaredField.getType();
            DynamicResource dynamicResource = declaredField.getAnnotation(DynamicResource.class);
            if (Objects.isNull(dynamicResource)) {
                continue;
            }
            try {
                declaredField.setAccessible(true);
                Map<String, Object> createdProxy = Maps.newHashMap();
                Map<Class<?>, Object> createdClassProxy = Maps.newHashMap();
                if (dynamicResource.dubboReference()) {
                    Object cglibProxy = createDubboEnhanceProxy(name, type, createdProxy, createdClassProxy);
                    declaredField.set(testTarget, cglibProxy);
                    continue;
                }
                Object cglibProxy = createCglibProxy(name, type, createdProxy, createdClassProxy);
                declaredField.set(testTarget, cglibProxy);
            } catch (Exception e) {
                throw new RuntimeException("dynamic inject bean failed:" + name, e);
            }
        }
    }

    private static void getAllChainClassAndImportClass(Class<?> configurationClass,
        List<Class<?>> chainClassList,
        List<Class<?>> importClassList) {

        AutoConfigureAfter configAutoConfigureAfter = tryGetAnnotation(configurationClass, AutoConfigureAfter.class);
        if (Objects.nonNull(configAutoConfigureAfter)
            && TestClassUtil.isArrayNotEmpty(configAutoConfigureAfter.value())) {
            for (Class<?> configAutoConfigureAfterClass : configAutoConfigureAfter.value()) {
                if (isNotConfigurationClass(configAutoConfigureAfterClass)) {
                    continue;
                }
                chainClassList.add(configAutoConfigureAfterClass);
                getAllChainClassAndImportClass(configAutoConfigureAfterClass, chainClassList, Lists.newArrayList());
            }
        }

        AutoConfiguration configAutoConfigure = tryGetAnnotation(configurationClass, AutoConfiguration.class);
        if (Objects.nonNull(configAutoConfigure) && TestClassUtil.isArrayNotEmpty(configAutoConfigure.after())) {
            for (Class<?> configAutoConfigureClass : configAutoConfigure.after()) {
                if (isNotConfigurationClass(configAutoConfigureClass)) {
                    continue;
                }
                chainClassList.add(configAutoConfigureClass);
                getAllChainClassAndImportClass(configAutoConfigureClass, chainClassList, Lists.newArrayList());
            }
        }

        Import configImport = tryGetAnnotation(configurationClass, Import.class);
        if (Objects.nonNull(configImport) && TestClassUtil.isArrayNotEmpty(configImport.value())) {
            for (Class<?> importClass : configImport.value()) {
                if (isNotConfigurationClass(importClass)) {
                    continue;
                }
                importClassList.add(importClass);
                chainClassList.add(importClass);
                getAllChainClassAndImportClass(importClass, chainClassList, importClassList);
            }
        }

        ConditionalOnClass conditionalOnClasses = tryGetAnnotation(configurationClass, ConditionalOnClass.class);
        if (Objects.nonNull(conditionalOnClasses) && TestClassUtil.isArrayNotEmpty(conditionalOnClasses.value())) {
            for (Class<?> conditionalOnClass : conditionalOnClasses.value()) {
                if (isNotConfigurationClass(conditionalOnClass)) {
                    continue;
                }
                chainClassList.add(conditionalOnClass);
                getAllChainClassAndImportClass(conditionalOnClass, chainClassList, Lists.newArrayList());
            }
        }

    }

    private static boolean isNotConfigurationClass(Class<?> configurationClass) {

        try {
            return AnnotationUtils.findAnnotation(configurationClass, Configuration.class) == null;
        } catch (Throwable ignore) {
            return true;
        }
    }

    private static void scanInterfaceDependencies(Class<?> clazz) {

        Class<?>[] interfaces = clazz.getInterfaces();
        if (TestClassUtil.isArrayNotEmpty(interfaces)) {
            for (Class<?> anInterface : interfaces) {
                BEAN_CLASS_IMPL_MAP.compute(anInterface, (key, value) -> {
                    if (Objects.isNull(value)) {
                        value = new ArrayList<>();
                    }
                    value.add(clazz);
                    return value;
                });
            }
        }
    }

    private static void addConfigurationDependency(Class<?> autoConfigurationClass,
        Class<?>... rootConfigurationClass) {

        if (Objects.isNull(autoConfigurationClass)) {
            return;
        }

        Method[] declaredMethods = new Method[0];
        try {
            declaredMethods = autoConfigurationClass.getDeclaredMethods();
        } catch (Throwable ignore) {
        }

        for (Method declaredMethod : declaredMethods) {
            if (!isBeanMethod(declaredMethod)) {
                continue;
            }
            String[] beanNames = getBeanName(declaredMethod);
            for (String beanName : beanNames) {
                BEAN_NAME_DEPENDENCY_CONFIGURATION_CLASSES.compute(beanName, (key, value) -> {
                    if (Objects.isNull(value)) {
                        value = Lists.newArrayList();
                    }
                    if (TestClassUtil.isArrayNotEmpty(rootConfigurationClass)) {
                        value.addAll(Lists.newArrayList(rootConfigurationClass));
                    } else {
                        value.add(autoConfigurationClass);
                    }
                    return value;
                });
            }
            BEAN_CLASS_DEPENDENCY_CONFIGURATION_CLASSES.compute(declaredMethod.getReturnType(), (key, value) -> {
                if (Objects.isNull(value)) {
                    value = Lists.newArrayList();
                }
                if (TestClassUtil.isArrayNotEmpty(rootConfigurationClass)) {
                    value.addAll(Lists.newArrayList(rootConfigurationClass));
                } else {
                    value.add(autoConfigurationClass);
                }
                return value;
            });

            MyBatisContextLoader.addMapperScannerConfig(autoConfigurationClass, declaredMethod);
        }
    }

    private static Set<String> getScanBasePackageSet(Class<?> mainClass, SpringBootApplication springBootApplication) {

        String[] scanBasePackages = springBootApplication.scanBasePackages();
        if (TestClassUtil.isArrayEmpty(scanBasePackages)) {
            ComponentScan componentScan = mainClass.getAnnotation(ComponentScan.class);
            if (Objects.isNull(componentScan)) {
                throw new IllegalArgumentException("The class must be annotated with @ComponentScan");
            }
            scanBasePackages = componentScan.value();
        }
        return Sets.newHashSet(scanBasePackages);
    }

    private static Object createCglibProxy(String name,
        Class<?> targetClass,
        Map<String, Object> createdProxy,
        Map<Class<?>, Object> createdClassProxy) {

        if (createdClassProxy.containsKey(targetClass)) {
            return createdClassProxy.get(targetClass);
        }
        if (ThreadPoolExecutor.class.equals(targetClass)) {
            return getMainExecPool();
        }

        if (targetClass.isInterface()) {
            List<Class<?>> dependencyClasses = BEAN_CLASS_IMPL_MAP.get(targetClass);
            if (Objects.nonNull(dependencyClasses) && !dependencyClasses.isEmpty()) {
                Class<?> dependencyClass = dependencyClasses.get(0);
                return createEnhanceProxy(getBeanName(dependencyClass),
                    dependencyClass,
                    createdProxy,
                    createdClassProxy,
                    new SimpleBean(name, targetClass));
            }
            Object enhanceProxy = Proxy.newProxyInstance(targetClass.getClassLoader(),
                new Class[] {targetClass},
                ((proxy, method, args) -> {
                    return method.invoke(registerNewAndGet(name, targetClass), args);
                }));
            if (StringUtils.hasText(name)) {
                createdProxy.put(name, enhanceProxy);
            }
            createdClassProxy.put(targetClass, enhanceProxy);
            agent(enhanceProxy, targetClass, createdProxy, createdClassProxy);
            return enhanceProxy;
        }

        return createEnhanceProxy(name, targetClass, createdProxy, createdClassProxy);
    }

    private static Object createEnhanceProxy(String name,
        Class<?> targetClass,
        Map<String, Object> createdProxy,
        Map<Class<?>, Object> createdClassProxy,
        SimpleBean... simpleBeans) {

        String packageName = targetClass.getPackage().getName();
        boolean isMainPackage = packageName.startsWith(MAIN_CLASS_PACKAGE);
        if (Modifier.isFinal(targetClass.getModifiers())) {
            Object enhanceProxy = Proxy.newProxyInstance(targetClass.getClassLoader(),
                targetClass.getInterfaces(),
                ((proxy, method, args) -> {
                    return method.invoke(registerNewAndGet(name, targetClass), args);
                }));
            addedProxy(name, targetClass, createdProxy, createdClassProxy, enhanceProxy, simpleBeans);
            return enhanceProxy;
        }

        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(targetClass);
        enhancer.setCallback((MethodInterceptor)(target, method, args, methodProxy) -> {
            Object fromRegisterContext = getFromRegisterContext(targetClass, name);
            if (Objects.nonNull(fromRegisterContext)) {
                return method.invoke(fromRegisterContext, args);
            }
            if (MyBatisContextLoader.isGetBaseMapperMethod(targetClass, method.getName())) {
                Type genericSuperclass = targetClass.getGenericSuperclass();
                Class<?> returnType = method.getReturnType();
                Class<?> actualType = null;
                actualType = TestClassUtil.getActualClass(genericSuperclass, actualType);
                return registerNewAndGet(null, Objects.nonNull(actualType) ? actualType : returnType);
            }
            if (isMainPackage) {
                return methodProxy.invokeSuper(target, args);
            } else {
                return method.invoke(registerNewAndGet(name, targetClass), args);
            }
        });
        try {
            Object enhanceProxy = enhancer.create();
            addedProxy(name, targetClass, createdProxy, createdClassProxy, enhanceProxy, simpleBeans);
            agent(enhanceProxy, targetClass, createdProxy, createdClassProxy);
            return enhanceProxy;
        } catch (Exception ignore) {
        }

        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setSuperclass(targetClass);
        Class<?> proxyClass = proxyFactory.createClass();
        try {
            // 绕过构造器初始化对象，将跳过对象初始化过程
            Object enhanceProxy = createEmptyInstance(proxyClass);
            ((ProxyObject)enhanceProxy).setHandler((self, method, proceed, args) -> {
                Object fromRegisterContext = getFromRegisterContext(targetClass, name);
                if (Objects.nonNull(fromRegisterContext)) {
                    return method.invoke(fromRegisterContext, args);
                }
                if (MyBatisContextLoader.isGetBaseMapperMethod(targetClass, method.getName())) {
                    Type genericSuperclass = targetClass.getGenericSuperclass();
                    Class<?> returnType = method.getReturnType();
                    Class<?> actualType = null;
                    actualType = TestClassUtil.getActualClass(genericSuperclass, actualType);
                    return registerNewAndGet(null, Objects.nonNull(actualType) ? actualType : returnType);
                }
                if (isMainPackage) {
                    return proceed.invoke(self, args);
                } else {
                    return method.invoke(registerNewAndGet(name, targetClass), args);
                }
            });
            addedProxy(name, targetClass, createdProxy, createdClassProxy, enhanceProxy, simpleBeans);
            agent(enhanceProxy, targetClass, createdProxy, createdClassProxy);
            return enhanceProxy;
        } catch (Exception e) {
            throw new RuntimeException(String.format("无法代理对象,name:%s，class：%s", name, targetClass.getName()), e);
        }
    }

    private static void addedProxy(String name,
        Class<?> targetClass,
        Map<String, Object> createdProxy,
        Map<Class<?>, Object> createdClassProxy,
        Object enhanceProxy,
        SimpleBean[] simpleBeans) {

        if (StringUtils.hasText(name)) {
            createdProxy.put(name, enhanceProxy);
        }
        createdClassProxy.put(targetClass, enhanceProxy);
        if (TestClassUtil.isArrayNotEmpty(simpleBeans)) {
            for (SimpleBean simpleBean : simpleBeans) {
                if (StringUtils.hasText(simpleBean.getName())) {
                    createdProxy.put(simpleBean.getName(), enhanceProxy);
                }
                createdClassProxy.put(simpleBean.getBeanClass(), enhanceProxy);
            }
        }
    }

    private static Object createDubboEnhanceProxy(String name,
        Class<?> targetClass,
        Map<String, Object> createdProxy,
        Map<Class<?>, Object> createdClassProxy) {

        try {
            Object enhanceProxy = Proxy.newProxyInstance(targetClass.getClassLoader(),
                new Class[] {targetClass},
                ((proxy, method, args) -> {
                    return method.invoke(registerNewAndGet(name,
                        targetClass,
                        TestClassUtil.tryGetClass("org.apache.dubbo.config.annotation.DubboReference")), args);
                }));
            if (StringUtils.hasText(name)) {
                createdProxy.put(name, enhanceProxy);
            }
            createdClassProxy.put(targetClass, enhanceProxy);
            return enhanceProxy;
        } catch (Exception e) {
            throw new RuntimeException(String.format("无法代理对象,name:%s，class：%s", name, targetClass.getName()), e);
        }
    }

    private static Object registerNewAndGet(String name, Class<?> targetClass, Class<?>... annotationClasses) {

        NacosContextLoader.await();
        Object fromRegisterContext = getFromRegisterContext(targetClass, name);
        if (Objects.nonNull(fromRegisterContext)) {
            return fromRegisterContext;
        }

        AnnotationConfigApplicationContext newApplicationContext = getNewApplicationContext();
        newApplicationContext.addBeanFactoryPostProcessor(new EmptyDependsOnProcessor());
        for (PropertySource<?> propertySource : ALL_CONTEXT.getEnvironment().getPropertySources()) {
            newApplicationContext.getEnvironment().getPropertySources().addLast(propertySource);
        }
        ServiceLoader<TestContextLoader> loaders = ServiceLoader.load(TestContextLoader.class);
        for (TestContextLoader loader : loaders) {
            if (loader.canHandle(newApplicationContext, name, targetClass, annotationClasses)) {
                return loader.getOrCreate(newApplicationContext, name, targetClass, annotationClasses);
            }
        }
        Set<Class<?>> registeredClasses = Sets.newHashSet();
        List<Class<?>> nameDependencyConfigurationList = BEAN_NAME_DEPENDENCY_CONFIGURATION_CLASSES.getOrDefault(name,
            Collections.emptyList());
        for (Class<?> dependencyConfiguration : nameDependencyConfigurationList) {
            if (registeredClasses.contains(dependencyConfiguration)) {
                continue;
            }
            newApplicationContext.register(dependencyConfiguration);
            registeredClasses.add(dependencyConfiguration);
        }
        List<Class<?>> classDependencyConfigurationList = BEAN_CLASS_DEPENDENCY_CONFIGURATION_CLASSES.getOrDefault(
            targetClass,
            Collections.emptyList());
        for (Class<?> dependencyConfiguration : classDependencyConfigurationList) {
            if (registeredClasses.contains(dependencyConfiguration)) {
                continue;
            }
            newApplicationContext.register(dependencyConfiguration);
            registeredClasses.add(dependencyConfiguration);
        }
        if (nameDependencyConfigurationList.isEmpty() && classDependencyConfigurationList.isEmpty()) {
            newApplicationContext.register(targetClass);
        }
        newApplicationContext.refresh();
        Object registerBean = tryGetBean(newApplicationContext, name, targetClass);
        if (StringUtils.hasText(name)) {
            NAME_APPLICATION_MAP.put(name, newApplicationContext);
        }
        CLASS_APPLICATION_MAP.put(targetClass, newApplicationContext);
        return registerBean;
    }

    public static Object refreshAndGet(AnnotationConfigApplicationContext context, String name, Class<?> targetClass) {

        NacosContextLoader.await();
        Object fromRegisterContext = getFromRegisterContext(targetClass, name);
        if (Objects.nonNull(fromRegisterContext)) {
            return fromRegisterContext;
        }

        context.addBeanFactoryPostProcessor(new EmptyDependsOnProcessor());
        for (PropertySource<?> propertySource : ALL_CONTEXT.getEnvironment().getPropertySources()) {
            context.getEnvironment().getPropertySources().addLast(propertySource);
        }

        context.refresh();
        Object registerBean = tryGetBean(context, name, targetClass);
        if (StringUtils.hasText(name)) {
            NAME_APPLICATION_MAP.put(name, context);
        }
        CLASS_APPLICATION_MAP.put(targetClass, context);
        return registerBean;
    }

    private static AnnotationConfigApplicationContext getNewApplicationContext() {

        AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext();
        ConfigurationPropertiesBindingPostProcessor.register((BeanDefinitionRegistry)applicationContext.getBeanFactory());
        return applicationContext;
    }

    private static class EmptyDependsOnProcessor implements BeanFactoryPostProcessor {

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
                String[] dependsOn = beanDefinition.getDependsOn();
                if (dependsOn != null) {
                    beanDefinition.setDependsOn();
                }
            }
        }

    }

    private static Object createEmptyInstance(Class<?> clazz) throws Exception {

        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe)unsafeField.get(null);

        // 创建对象，不调用构造方法
        return unsafe.allocateInstance(clazz);
    }

    private static String getBeanName(Class<?> clazz) {

        Qualifier qualifier = AnnotationUtils.findAnnotation(clazz, Qualifier.class);
        if (Objects.nonNull(qualifier) && StringUtils.hasText(qualifier.value())) {
            return qualifier.value();
        }
        Component annotation = AnnotationUtils.findAnnotation(clazz, Component.class);
        if (Objects.isNull(annotation) || !StringUtils.hasText(annotation.value())) {
            return Introspector.decapitalize(ClassUtils.getShortName(clazz));
        }
        return annotation.value();
    }

    private static String getBeanName(Field field) {

        Qualifier qualifier = AnnotationUtils.findAnnotation(field, Qualifier.class);
        if (Objects.nonNull(qualifier) && StringUtils.hasText(qualifier.value())) {
            return qualifier.value();
        }
        Resource resource = AnnotationUtils.findAnnotation(field, Resource.class);
        if (Objects.nonNull(resource) && StringUtils.hasText(resource.name())) {
            return resource.name();
        }
        return field.getName();
    }

    private static boolean isBeanMethod(Method method) {

        return method.isAnnotationPresent(Bean.class);
    }

    private static String[] getBeanName(Method method) {

        Bean bean = AnnotationUtils.findAnnotation(method, Bean.class);
        if (Objects.nonNull(bean) && TestClassUtil.isArrayNotEmpty(bean.value())) {
            return bean.name();
        }
        Qualifier qualifier = AnnotationUtils.findAnnotation(method, Qualifier.class);
        if (Objects.nonNull(qualifier) && StringUtils.hasText(qualifier.value())) {
            return new String[] {qualifier.value()};
        }
        return new String[] {method.getName()};
    }

    private static Object getFromRegisterContext(Class<?> clazz, String beanName) {

        if (StringUtils.hasText(beanName)) {
            for (AnnotationConfigApplicationContext app : NAME_APPLICATION_MAP.values()) {
                if (app.containsBean(beanName)) {
                    return app.getBean(beanName);
                }
            }
        }
        for (AnnotationConfigApplicationContext app : CLASS_APPLICATION_MAP.values()) {
            if (app.getBeanNamesForType(clazz).length > 0) {
                try {
                    return app.getBean(beanName);
                } catch (Exception e) {
                    return app.getBean(clazz);
                }
            }
        }
        return null;
    }

    private static <T extends Annotation> T tryGetAnnotation(Class<?> cls, Class<T> annotationCls) {

        try {
            return AnnotationUtils.findAnnotation(cls, annotationCls);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static boolean isAnnotationWithDubboReference(Field field) {

        Class<? extends Annotation> dubboReferenceClass = TestClassUtil.tryGetAnnotation(
            "org.apache.dubbo.config.annotation.DubboReference");
        if (Objects.nonNull(dubboReferenceClass) && field.isAnnotationPresent(dubboReferenceClass)) {
            return true;
        }
        Class<? extends Annotation> dubboReferenceClass2 = TestClassUtil.tryGetAnnotation(
            "org.apache.dubbo.config.annotation.Reference");
        if (Objects.nonNull(dubboReferenceClass2) && field.isAnnotationPresent(dubboReferenceClass2)) {
            return true;
        }
        Class<? extends Annotation> dubboReferenceClass3 = TestClassUtil.tryGetAnnotation(
            "com.alibaba.dubbo.config.annotation.Reference");
        return Objects.nonNull(dubboReferenceClass3) && field.isAnnotationPresent(dubboReferenceClass3);
    }

    private static List<Class<? extends Annotation>> getDubboServiceClass() {

        Class<? extends Annotation> dubboServiceClass = TestClassUtil.tryGetAnnotation(
            "org.apache.dubbo.config.annotation.DubboService");
        Class<? extends Annotation> dubboServiceClass2 = TestClassUtil.tryGetAnnotation(
            "org.apache.dubbo.config.annotation.Service");
        Class<? extends Annotation> dubboServiceClass3 = TestClassUtil.tryGetAnnotation(
            "com.alibaba.dubbo.config.annotation.Service");
        return Arrays.asList(dubboServiceClass, dubboServiceClass2, dubboServiceClass3);
    }

    @Getter
    @ToString
    static class SimpleBean {

        private final String name;

        private final Class<?> beanClass;

        SimpleBean(String name, Class<?> beanClass) {

            this.name = name;
            this.beanClass = beanClass;
        }

    }

    private static ThreadPoolExecutor getMainExecPool() {

        if (Objects.isNull(MAIN_RUNNER_POOL)) {
            synchronized (ThreadPoolExecutor.class) {
                if (Objects.isNull(MAIN_RUNNER_POOL)) {
                    MAIN_RUNNER_POOL = new ThreadPoolExecutor(0,
                        1,
                        0,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(1),
                        new ThreadPoolExecutor.CallerRunsPolicy());
                    try {
                        Field maximumPoolSize = ThreadPoolExecutor.class.getDeclaredField("maximumPoolSize");
                        maximumPoolSize.setAccessible(true);
                        maximumPoolSize.set(MAIN_RUNNER_POOL, 0);
                    } catch (Exception ignore) {
                    }

                    MAIN_RUNNER_POOL.execute(() -> {
                    });
                }
                return MAIN_RUNNER_POOL;
            }
        }
        return MAIN_RUNNER_POOL;
    }

}