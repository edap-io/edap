package io.edap.container.app.asm;

import io.edap.container.AppContext;
import io.edap.container.BeanWrap;
import io.edap.http.HttpRequest;
import io.edap.json.Eson;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

public abstract class AbstractHandler {

    protected AppContext appContext;

    protected Logger log = LoggerManager.getLogger(this.getClass().getName());

    protected static String NOT_IMPL_MSG = "的接口还没有实现";
    protected static String BIZ_EXCEPTION_MSG = "业务处理异常";

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

    public Object parsePostReq(HttpRequest request, Class<?> reqType) {
        try {
            return Eson.parseObject(request.getBody().getBytes(), reqType);
        } catch (Exception e) {
            log.warn("解析参数失败", e);
        }

        return null;
    }

    public Object parseGetReq(HttpRequest request, Class<?> reqType) {
        return null;
    }
}
