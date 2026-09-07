package com.autofill.sms;

import android.app.Application;
import android.content.SharedPreferences;

import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 的 Application。
 * <p>
 * LSPosed 会在模块 App 启动后把 XposedService 送过来，拿到它才能写远程配置。
 */
public class App extends Application implements XposedServiceHelper.OnServiceListener {

    public interface ServiceStateListener {
        void onServiceStateChanged(XposedService service);
    }

    private static final CopyOnWriteArraySet<ServiceStateListener> LISTENERS = new CopyOnWriteArraySet<>();

    private static volatile XposedService sService;

    public static XposedService getService() {
        return sService;
    }

    /** 远程配置（可写）。未连接框架时返回 null。 */
    public static SharedPreferences getRemotePrefs() {
        XposedService service = sService;
        return service == null ? null : service.getRemotePreferences(Config.GROUP);
    }

    public static void addListener(ServiceStateListener listener, boolean notifyImmediately) {
        LISTENERS.add(listener);
        if (notifyImmediately) {
            listener.onServiceStateChanged(sService);
        }
    }

    public static void removeListener(ServiceStateListener listener) {
        LISTENERS.remove(listener);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService service) {
        sService = service;
        for (ServiceStateListener listener : LISTENERS) {
            listener.onServiceStateChanged(service);
        }
    }

    @Override
    public void onServiceDied(XposedService service) {
        sService = null;
        for (ServiceStateListener listener : LISTENERS) {
            listener.onServiceStateChanged(null);
        }
    }
}
