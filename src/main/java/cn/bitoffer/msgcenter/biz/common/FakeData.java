package cn.bitoffer.msgcenter.biz.common;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 业务线共用的随机数据工具。
 *
 * @author LQH
 */
public final class FakeData {

    public static final String[] CITIES =
            {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安", "南京", "重庆"};
    public static final String[] SERVICES =
            {"order-api", "user-api", "pay-api", "gateway", "search-api", "cart-api"};

    private FakeData() {
    }

    public static String maskedPhone() {
        int prefix = new int[] {138, 139, 150, 151, 176, 188, 199}[r().nextInt(7)];
        return prefix + "****" + String.format("%04d", r().nextInt(10000));
    }

    public static String fakeEmail() {
        return "user" + (r().nextInt(900000) + 100000) + "@example.com";
    }

    public static String orderNo() {
        return "SO" + (System.currentTimeMillis() % 100000000L)
                + String.format("%03d", r().nextInt(1000));
    }

    public static String amount() {
        int cents = r().nextInt(1, 500000);
        return String.format("%.2f", cents / 100.0);
    }

    public static String code() {
        return String.format("%06d", r().nextInt(1000000));
    }

    public static String pickOne(String[] arr) {
        return arr[r().nextInt(arr.length)];
    }

    public static ThreadLocalRandom r() {
        return ThreadLocalRandom.current();
    }
}
