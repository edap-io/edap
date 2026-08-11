package io.edap.props;

import java.util.Map;
import java.util.Properties;

/**
 * 全局配置视图（key-value 树）。Edap.getProps() 返回此类型。
 *
 * 设计要点：
 *   - 不可变 snapshot：每次 child() / merge() 返回新 Props，原 Props 不被改
 *   - 优先级由低到高：系统环境变量 → container Props → build.json env → bean 结构化字段
 *     各层以 merge() 叠加，高优先级覆盖低优先级（同名 key）
 *   - 类型友好的 getter：getString / getInt / getLong / getBoolean 提供 default fallback
 *   - 子树视图：child(prefix) 把 prefix. 开头的 key 暴露成"根 key"，便于按段访问
 *
 * 当前实现是内存版（Properties + Map）；后续可替换为加载外部 edap.cfg / build.json 的实现。
 *
 * 模块归属：放在 edap-nio-server（Edap 所在模块）。Edap.getProps() 返回 Props 直接同模块 import；
 * 上层模块（edap-container / edap-component 等）通过 edap-http-server → edap-http-core → edap-nio-server
 * 传递依赖引用 Props，不会反向依赖 edap-container 模块。
 */
public final class Props {

    private final Map<String, String> values;

    public Props(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public Props(Properties props) {
        Map<String, String> map = new java.util.HashMap<>();
        for (String name : props.stringPropertyNames()) {
            map.put(name, props.getProperty(name));
        }
        this.values = Map.copyOf(map);
    }

    public Props child(String prefix) {
        String p = prefix == null ? "" : prefix;
        String withDot = p.endsWith(".") || p.isEmpty() ? p : p + ".";
        Map<String, String> sub = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey().startsWith(withDot)) {
                sub.put(e.getKey().substring(withDot.length()), e.getValue());
            }
        }
        return new Props(sub);
    }

    public Props merge(Props other) {
        Map<String, String> merged = new java.util.HashMap<>(this.values);
        if (other != null) {
            merged.putAll(other.values);
        }
        return new Props(merged);
    }

    public String getString(String key) {
        return values.get(key);
    }

    public String getString(String key, String defaultValue) {
        String v = values.get(key);
        return v != null ? v : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        String v = values.get(key);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String v = values.get(key);
        if (v == null) return defaultValue;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = values.get(key);
        if (v == null) return defaultValue;
        return Boolean.parseBoolean(v);
    }

    public boolean containsKey(String key) {
        return values.containsKey(key);
    }

    public int size() {
        return values.size();
    }
}