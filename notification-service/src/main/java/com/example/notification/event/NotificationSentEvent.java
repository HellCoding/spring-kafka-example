package com.example.notification.event;

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
