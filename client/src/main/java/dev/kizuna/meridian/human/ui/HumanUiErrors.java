package dev.kizuna.meridian.human.ui;

/** 人体客户端表现的装配错误，不参与服务端身体失败码。 */
final class HumanUiErrors {
    static final String INVALID_OPENING_EFFECTS = "开脉表现无法读取人体维持条件";
    static final String INVALID_CULTIVATION = "人体周天进度消息损坏";
    static final String INVALID_CONNECTION = "人体光迹模板或权威连接不匹配：";
    static final String MISSING_ENDPOINT = "人体光迹缺少端口或绑定模型：";

    private HumanUiErrors() {
        // 错误文案集中管理，调用处保留实际校验条件。
    }
}
