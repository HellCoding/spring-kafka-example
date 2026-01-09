package com.example.notification.event;

/**
 * 알림 발송 기록을 Kafka로 발행하기 위한 이벤트.
 */
public class NotificationSentEvent {
    private Long orderId;
    private String message;
    private String channel;

    public NotificationSentEvent() {}

    public NotificationSentEvent(Long orderId, String message, String channel) {
        this.orderId = orderId;
        this.message = message;
        this.channel = channel;
    }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
}
