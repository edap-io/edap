package io.edap.container.app.asm;

import io.edap.container.AppContext;
import io.edap.container.BeanWrap;
import io.edap.json.Eson;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.StringUtil;

import java.util.HashMap;
import java.util.Map;

public abstract class AbstractHandler {

    protected AppContext appContext;

    protected Logger log = LoggerManager.getLogger(this.getClass().getName());

    protected static String NOT_IMPL_MSG = "的接口还没有实现";
    protected static String BIZ_EXCEPTION_MSG = "业务处理异常";

    protected static int NO_LOGIN_CODE = 1001;

    protected static int BIZ_EXCEPTION_CODE = 10001;
    protected static int NO_IMPL_BEAN_CODE  = 10002;


    protected static byte[] NO_LOGIN_ERROR_DATA;

    static {
        Map<String, Object> data = new HashMap<>();
        data.put("code", NO_LOGIN_CODE);
        data.put("message", "用户未登录");
        NO_LOGIN_ERROR_DATA = Eson.toJsonBytes(data);
    }

    public AbstractHandler(AppContext appContext) {
        this.appContext = appContext;
    }

    public AppContext getAppContext() {
        return appContext;
    }

    public void setAppContext(AppContext appContext) {
        this.appContext = appContext;
    }

    public Object getBean(Class<?> beanType) {
        Object bean = null;
        try {
            BeanWrap beanWrap = appContext.beans().beanWrapByType(beanType);
            if (beanWrap != null) {
                bean = beanWrap.instance();
            }
        } catch (Exception e) {
            log.warn("{} get service {} error",
                    l -> l.arg(this.getClass().getName())
                            .arg(beanType.getName()).threw(e)
            );
        }

        return bean;
    }

    public int toInt(String param) {
        if (StringUtil.isEmpty(param)) {
            return 0;
        }
        try {
            return Integer.parseInt(param);
        } catch (NumberFormatException e) {
            log.warn("parse {} to long error", l -> l.arg(param).threw(e));
        }

        return 0;
    }

    public Integer toInteger(String param) {
        if (StringUtil.isEmpty(param)) {
            return 0;
        }
        try {
            return Integer.parseInt(param);
        } catch (NumberFormatException e) {
            log.warn("parse {} to long error", l -> l.arg(param).threw(e));
        }

        return 0;
    }

    public long toLong(String param) {
        if (StringUtil.isEmpty(param)) {
            return 0;
        }
        try {
            return Long.parseLong(param);
        } catch (NumberFormatException e) {
            log.warn("parse {} to long error", l -> l.arg(param).threw(e));
        }

        return 0;
    }

    public Long toLongObj(String param) {
        if (StringUtil.isEmpty(param)) {
            return 0L;
        }
        try {
            return Long.parseLong(param);
        } catch (NumberFormatException e) {
            log.warn("parse {} to long error", l -> l.arg(param).threw(e));
        }

        return 0L;
    }

    public boolean toBoolean(String param) {
        if (StringUtil.isEmpty(param)) {
            return false;
        }
        try {
            return Boolean.parseBoolean(param);
        } catch (NumberFormatException e) {
            log.warn("parse {} to boolean error", l -> l.arg(param).threw(e));
        }

        return false;
    }

    public Boolean toBooleanObj(String param) {
        if (StringUtil.isEmpty(param)) {
            return false;
        }
        try {
            return Boolean.parseBoolean(param);
        } catch (NumberFormatException e) {
            log.warn("parse {} to boolean error", l -> l.arg(param).threw(e));
        }

        return false;
    }

    public float toFloat(String param) {
        if (StringUtil.isEmpty(param)) {
            return 0;
        }
        try {
            return Float.parseFloat(param);
        } catch (NumberFormatException e) {
            log.warn("parse {} to long error", l -> l.arg(param).threw(e));
        }

        return 0;
    }

    public Float toFloatObj(String param) {
        if (StringUtil.isEmpty(param)) {
            return 0F;
        }
        try {
            return Float.parseFloat(param);
        } catch (NumberFormatException e) {
            log.warn("parse {} to long error", l -> l.arg(param).threw(e));
        }

        return 0F;
    }

    public double toDouble(String param) {
        if (StringUtil.isEmpty(param)) {
            return 0;
        }
        try {
            return Double.parseDouble(param);
        } catch (NumberFormatException e) {
            log.warn("parse {} to long error", l -> l.arg(param).threw(e));
        }

        return 0;
    }

    public Double toDoubleObj(String param) {
        if (StringUtil.isEmpty(param)) {
            return 0D;
        }
        try {
            return Double.parseDouble(param);
        } catch (NumberFormatException e) {
            log.warn("parse {} to long error", l -> l.arg(param).threw(e));
        }

        return 0D;
    }
}
