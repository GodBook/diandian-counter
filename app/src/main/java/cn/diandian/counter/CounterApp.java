package cn.diandian.counter;

import android.app.Application;

public final class CounterApp extends Application {
    private CounterStore store;
    public synchronized CounterStore store() {
        if (store == null) store = new CounterStore(this);
        return store;
    }
}
