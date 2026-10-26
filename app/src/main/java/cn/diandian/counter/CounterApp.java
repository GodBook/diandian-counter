package cn.diandian.counter;

import android.app.Application;

public final class CounterApp extends Application {
    private CounterStore store;
    private UpdateManager updates;
    public synchronized CounterStore store() {
        if (store == null) store = new CounterStore(this);
        return store;
    }
    synchronized UpdateManager updates() {
        if (updates == null) updates = new UpdateManager(this, store());
        return updates;
    }
}
