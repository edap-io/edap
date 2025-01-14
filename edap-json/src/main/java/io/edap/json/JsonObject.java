package io.edap.json;

import java.math.BigDecimal;
import java.util.Map;

public interface JsonObject extends JsonMap {

    default String getString(String key) {
        Object obj = get(key);
        if (obj instanceof String) {
            return (String)obj;
        }
        return String.valueOf(obj);
    }

    default int getIntValue(String key) {
        Object obj = get(key);
        if (obj == null) {
            return 0;
        }
        if (obj instanceof Integer) {
            return ((Integer)obj);
        } else if (obj instanceof Long) {
            return ((Long)obj).intValue();
        } else if (obj instanceof String) {
            return Integer.parseInt((String)obj);
        } else if (obj instanceof BigDecimal) {
            return ((BigDecimal)obj).intValue();
        }
        throw  new NumberFormatException("Not int value");
    }

    default boolean getBooleanValue(String key) {
        Object obj = get(key);
        if (obj == null) {
            return false;
        }
        if (obj instanceof Boolean) {
            return (Boolean)obj;
        } else if (obj instanceof Integer) {
            return (((Integer)obj) != 0);
        } else if (obj instanceof Long) {
            return (((Long)obj) != 0);
        } else if (obj instanceof String) {
            String v = (String)obj;
            return "t".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v) || "1".equals(v);
        }
        return false;
    }

    default long getLongValue(String key) {
        Object obj = get(key);
        if (obj == null) {
            return 0;
        }
        if (obj instanceof Integer) {
            return ((Integer)obj);
        } else if (obj instanceof Long) {
            return ((Long)obj);
        } else if (obj instanceof String) {
            return Long.parseLong((String)obj);
        } else if (obj instanceof BigDecimal) {
            return ((BigDecimal)obj).longValue();
        }
        throw  new NumberFormatException("Not long value");
    }

    default JsonObject getJsonObject(String key) {
        Object obj = get(key);
        if (obj == null) {
            return null;
        }
        if (obj instanceof JsonObject) {
            return (JsonObject) obj;
        } else if (obj instanceof Map) {
            JsonObjectImpl jsonObject = new JsonObjectImpl();
            jsonObject.putAll((Map<String, Object>)obj);
            return jsonObject;
        }
        return null;
    }

    default double getDoubleValue(String key) {
        Object obj = get(key);
        if (obj == null) {
            return 0;
        }
        if (obj instanceof Double) {
            return ((Double)obj);
        } else if (obj instanceof Float) {
            return ((Float)obj).doubleValue();
        } else if (obj instanceof Integer) {
            return ((Integer)obj).doubleValue();
        } else if (obj instanceof Long) {
            return ((Long)obj).doubleValue();
        } else if (obj instanceof String) {
            return Double.parseDouble((String)obj);
        } else if (obj instanceof BigDecimal) {
            return ((BigDecimal)obj).doubleValue();
        }
        throw  new NumberFormatException("Not double value");
    }

    default float getFloatValue(String key) {
        Object obj = get(key);
        if (obj == null) {
            return 0;
        }
        if (obj instanceof Float) {
            return ((Float)obj);
        } else if (obj instanceof Double) {
            return ((Double)obj).floatValue();
        } else if (obj instanceof Integer) {
            return ((Integer)obj).floatValue();
        } else if (obj instanceof Long) {
            return ((Long)obj).floatValue();
        } else if (obj instanceof String) {
            return Float.parseFloat((String)obj);
        } else if (obj instanceof BigDecimal) {
            return ((BigDecimal)obj).floatValue();
        }
        throw  new NumberFormatException("Not float value");
    }
}
