package io.github.atom.test.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.junit.runners.model.InitializationError;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * 日志级别配置
 *
 * @author Zhang Kangkang
 * @version 1.0
 */
public class SpringRunnerLogInfo extends SpringJUnit4ClassRunner {

    static {
        try {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.getLogger("ROOT").setLevel(Level.INFO);
            loggerContext.getLogger("org.springframework").setLevel(Level.ERROR);
            loggerContext.getLogger("com.alibaba.nacos").setLevel(Level.ERROR);
        } catch (Exception e) {
            System.out.println("设置日志级别异常：" + e.getMessage());
        }
    }

    public SpringRunnerLogInfo(Class<?> clazz) throws InitializationError {
        super(clazz);
    }
}
