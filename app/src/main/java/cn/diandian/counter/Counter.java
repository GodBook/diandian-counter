package cn.diandian.counter;

public final class Counter {
    public long id;
    public String name = "";
    public long value;
    public int color;
    public ResetRule rule = new ResetRule();
    public long nextReset;
    public long createdAt;

    public Counter copy() {
        Counter c = new Counter();
        c.id = id; c.name = name; c.value = value; c.color = color;
        c.rule = rule.copy(); c.nextReset = nextReset; c.createdAt = createdAt;
        return c;
    }
}
