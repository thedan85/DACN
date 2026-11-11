package com.project.dacn.integration.shipping.ghn;

public final class GhnStatusMapper {

    public enum Event { SHIPPING, DELIVERED, CANCELLED, RETURNED, NONE }

    private GhnStatusMapper() {
    }

    public static Event map(String status) {
        if (status == null) return Event.NONE;
        return switch (status) {
            case "delivered" -> Event.DELIVERED;
            case "cancel" -> Event.CANCELLED;
            case "returned" -> Event.RETURNED;
            case "picked", "storing", "sorting", "transporting", "delivering",
                    "money_collect_delivering" -> Event.SHIPPING;
            default -> Event.NONE;
        };
    }
}
