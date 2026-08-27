package cn.bitoffer.msgcenter.core.enums;

/**
 * ChannelEnum。
 *
 * @author LQH
 */
public enum ChannelEnum {
    Channel_EMAIL(1),
    Channel_SMS(2),
    Channel_LARK(3);

    ChannelEnum(int channel) {
        this.channel = channel;
    }
    private int channel;

    public int getChannel() {
        return this.channel;
    }
}
