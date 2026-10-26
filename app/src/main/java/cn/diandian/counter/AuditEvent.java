package cn.diandian.counter;

public final class AuditEvent {
    public long id, at, counterId;
    public String name, action, details;
    public Long before, after;

    public static String label(String action) {
        return switch (action) {
            case "CREATE" -> "新建计数器";
            case "INCREMENT" -> "点击计数";
            case "RENAME" -> "修改名称";
            case "RESET" -> "手动重置";
            case "AUTO_RESET" -> "自动重置";
            case "RULE" -> "修改重置周期";
            case "COLOR" -> "修改颜色";
            case "DELETE" -> "删除计数器";
            case "EXPORT" -> "导出操作记录";
            case "APP_OPEN" -> "打开应用";
            case "APP_BACKGROUND" -> "应用进入后台";
            case "VIEW_HISTORY" -> "查看操作记录";
            case "VIEW_COUNTERS" -> "查看计数器";
            case "VIEW_SETTINGS" -> "查看设置";
            case "FILTER" -> "筛选操作记录";
            case "SETTING" -> "修改设置";
            case "TIME_CHANGE" -> "调整时区或系统时间";
            case "BOOT" -> "设备重启后恢复周期";
            case "UI" -> "界面操作";
            default -> action;
        };
    }
}
