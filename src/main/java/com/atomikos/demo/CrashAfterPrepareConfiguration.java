package com.atomikos.demo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;

import org.springframework.boot.jdbc.XADataSourceWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.ClassUtils;

import com.atomikos.spring.AtomikosXADataSourceWrapper;

/**
 * Demo plumbing for {@code CRASH_AFTER_PREPARE}: hands Atomikos the PostgreSQL
 * {@link XADataSource} as usual, but with its {@link XAResource} wrapped so
 * {@link CrashSwitch} gets a call right before the phase-2 commit.
 * Not something a real application needs.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!naive & !local-tx")
class CrashAfterPrepareConfiguration {

    @Bean
    XADataSourceWrapper xaDataSourceWrapper(CrashSwitch crashSwitch) {
        AtomikosXADataSourceWrapper atomikos = new AtomikosXADataSourceWrapper();
        return dataSource -> atomikos.wrapDataSource((XADataSource) intercept(dataSource, crashSwitch));
    }

    // a proxy with all the target's interfaces: PostgreSQL's XAConnection is also its XAResource
    private static Object intercept(Object target, CrashSwitch crashSwitch) {
        Class<?>[] interfaces = ClassUtils.getAllInterfacesForClass(target.getClass());
        return Proxy.newProxyInstance(target.getClass().getClassLoader(), interfaces, new Interceptor(target, crashSwitch));
    }

    private record Interceptor(Object target, CrashSwitch crashSwitch) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == XAResource.class && method.getName().equals("commit")
                    && Boolean.FALSE.equals(args[1])) {
                crashSwitch.beforePhase2Commit(); // two-phase commit, after a successful prepare
            }
            Object result;
            try {
                result = method.invoke(target, unwrap(args));
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (method.getName().equals("getXAConnection") || method.getName().equals("getXAResource")) {
                return intercept(result, crashSwitch);
            }
            return result;
        }

        // the driver must see its own objects, e.g. in isSameRM(..) and equals(..)
        private static Object[] unwrap(Object[] args) {
            if (args == null) {
                return null;
            }
            Object[] unwrapped = args.clone();
            for (int i = 0; i < unwrapped.length; i++) {
                if (unwrapped[i] != null && Proxy.isProxyClass(unwrapped[i].getClass())
                        && Proxy.getInvocationHandler(unwrapped[i]) instanceof Interceptor interceptor) {
                    unwrapped[i] = interceptor.target();
                }
            }
            return unwrapped;
        }

    }

}
