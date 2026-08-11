package io.edap.container;

import io.edap.container.exc.NoSuchBeanException;
import io.edap.props.Props;

/**
 * 应用环境配置视图。
 *
 * 构造期合并两层 Props（高优先级覆盖低优先级）：
 *   1. Container 全局 Props（来自 edap.getProps().child("container")）
 *   2. 应用 build.json 的 env 段
 *
 * 完整优先级（低到高）：
 *   1. 系统环境变量
 *   2. Container 全局 Props（edap.getProps().child("container")）
 *   3. 应用 build.json 的 env 段
 *   4. 应用 build.json 的 stateful.shards 等结构化字段（直接由 BeanContainer 读取）
 *
 * {@code @Value("${key}")} 与 {@code @AutoConfig} 都走 Environment。
 *
 * getBean(name, type) 委托给 AppContext.getBean——EnvironmentAware 回调时注入的 env
 * 可让 bean 通过 env.getBean(...) 在不直接持有 AppContext 的情况下查其他 bean。
 *
 * 设计要点：
 *   - Environment 是 per-app 状态（不是 Container 单例），由 AppContext 持有 → AppContext.stop
 *     销毁时整条引用链释放，符合 §3.8 防 appCL 泄漏
 *   - Props 是不可变 snapshot，merge 时高优先级覆盖低优先级同名 key
 *   - getBean 委托给 AppContext，避免 Environment 反向持有 AppContext 造成循环引用
 */
public class Environment {

    private final Props      properties;     // 继承自 Container.env，再叠加 build.json
    private final AppContext appContext;     // 用于 getBean 委托

    public Environment(AppContext appContext, Props containerProps, Props buildJsonProps) {
        this.appContext = appContext;
        this.properties = containerProps.child("").merge(buildJsonProps);
    }

    public String getProperty(String key) {
        return properties.getString(key);
    }

    public String getProperty(String key, String defaultValue) {
        return properties.getString(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return properties.getInt(key, defaultValue);
    }

    public long getLong(String key, long defaultValue) {
        return properties.getLong(key, defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return properties.getBoolean(key, defaultValue);
    }

    /**
     * 按 bean 名 + 类型查 bean（委托给 AppContext.getBean）。
     * EnvironmentAware 回调时此方法可用：bean 可在不直接持有 AppContext 的情况下查找依赖 bean。
     * @throws NoSuchBeanException 如果 beanName 未注册
     */
    public <T> T getBean(String key, Class<T> type) {
        if (appContext == null) {
            throw new IllegalStateException(
                "Environment.getBean requires an active AppContext");
        }
        return appContext.getBean(key, type);
    }

    /** 暴露底层 Props（仅用于 framework 内部使用；bean 不应直接拿到 Props）。 */
    public Props props() {
        return properties;
    }
}