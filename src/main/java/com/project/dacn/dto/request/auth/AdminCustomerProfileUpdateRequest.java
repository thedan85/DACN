package com.project.dacn.dto.request.auth;

import tools.jackson.databind.JsonNode;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Request-local strict decoder for the admin customer profile PATCH. Parsing
 * happens at the request boundary from Jackson's tree type, so only a JSON
 * object with the known fields and correctly typed values is accepted.
 *
 * <p>A null {@code name} or {@code phone} means the field was omitted; the
 * service leaves those values unchanged. {@code addressProvided} records
 * whether the {@code address} key was present in the body, because {@code
 * address == null} is itself a meaningful instruction to clear the column.
 */
public record AdminCustomerProfileUpdateRequest(
        String name,
        String phone,
        boolean addressProvided,
        String address) {

    private static final Set<String> ALLOWED_FIELDS = Set.of("name", "phone", "address");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{10,15}$");
    private static final int MAX_LENGTH = 255;

    public static AdminCustomerProfileUpdateRequest fromJson(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("request body must be a JSON object");
        }
        for (String field : body.propertyNames()) {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("unknown field: " + field);
            }
        }
        JsonNode nameNode = body.get("name");
        JsonNode phoneNode = body.get("phone");
        JsonNode addressNode = body.get("address");
        if (nameNode == null && phoneNode == null && addressNode == null) {
            throw new IllegalArgumentException(
                    "at least one of name, phone, address must be provided");
        }
        return new AdminCustomerProfileUpdateRequest(
                parseName(nameNode),
                parsePhone(phoneNode),
                addressNode != null,
                parseAddress(addressNode));
    }

    private static String parseName(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNull()) {
            throw new IllegalArgumentException("name must not be null");
        }
        if (!node.isString()) {
            throw new IllegalArgumentException("name must be a string");
        }
        String name = node.asString().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("name must be at most 255 characters");
        }
        return name;
    }

    private static String parsePhone(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNull()) {
            throw new IllegalArgumentException("phone must not be null");
        }
        if (!node.isString()) {
            throw new IllegalArgumentException("phone must be a string");
        }
        String phone = node.asString().trim();
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw new IllegalArgumentException("phone must match ^\\+?[0-9]{10,15}$");
        }
        return phone;
    }

    private static String parseAddress(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new IllegalArgumentException("address must be a string");
        }
        String address = node.asString().trim();
        if (address.isEmpty()) {
            return null;
        }
        if (address.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("address must be at most 255 characters");
        }
        return address;
    }
}
