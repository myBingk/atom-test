# atom-test
按需装载测试方法执行过程中的所需依赖，显著提升单测启动速度
#### 用法
##### 1.引入依赖
```
<dependency>
    <groupId>io.github.mybingk</groupId>
    <artifactId>atom-test</artifactId>
    <version>1.0.0</version>
</dependency>
```
##### 2.代码示例
```
/**
 * 单元测试
 *
 * @author Zhang Kangkang
 * @version 1.0
 */
@Slf4j
@DynamicBeanLoading(
        // 程序运行主类
        mainClass = Application.class,
        // 将所需的属性文件copy到test/resources/目录下即可
        properties = {        
                "application.yml"
        },
        // 是否需要读取nacos属性（非必填）
        nacosEnabled = false,
        // 静态依赖对象（非必填）
        staticClass = {}
)
public class UnitTest extends FastDynamicBeanLoadingTest {

    /**
     * 使用@DynamicResource代替@Resource，只会装载bean所需的依赖，且所有依赖都为懒加载
     * 只有实际调用时才会初始化，有效提升单元测试启动速度
     */
    @DynamicResource
    private YourService yourService;

    @Test
    public void test() {
        yourService.doSomeThing("param");        
    }
    
}
```
