package com.jwpaisley.helpers;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TextHelper {
    public final String paisleyPhoneNumber = "+14693815707";
    private final String twilioStopMessage = "reply STOP to opt out of future messages";
    private final String accountSid;
    private final String authToken;
    private final String fromNumber;

    public TextHelper() {
        this(
            System.getenv("TWILIO_ACCOUNT_SID"),
            System.getenv("TWILIO_AUTH_TOKEN"),
            System.getenv("TWILIO_FROM_NUMBER")
        );
    }

    public TextHelper(String accountSid, String authToken, String fromNumber) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromNumber = fromNumber;
    }

    public boolean sendSms(String message, List<String> phoneNumbers) {
        if (message == null || message.isBlank() || phoneNumbers == null || phoneNumbers.isEmpty()) {
            return false;
        }

        if (isBlank(accountSid) || isBlank(authToken) || isBlank(fromNumber)) {
            System.err.println("Twilio SMS credentials are not configured.");
            return false;
        }

        Twilio.init(accountSid, authToken);

        List<String> normalizedNumbers = new ArrayList<>();
        for (String phoneNumber : phoneNumbers) {
            String normalized = normalizePhoneNumber(phoneNumber);
            if (normalized != null) {
                normalizedNumbers.add(normalized);
            }
        }

        if (normalizedNumbers.isEmpty()) {
            return false;
        }

        String smsBody = message.trim() + "\n\n" + twilioStopMessage;

        boolean allSucceeded = true;
        for (String normalizedNumber : normalizedNumbers) {
            try {
                Message.creator(
                    new PhoneNumber(normalizedNumber),
                    new PhoneNumber(fromNumber),
                    smsBody
                ).create();
            } catch (Exception e) {
                System.err.println("Unable to send SMS to " + normalizedNumber + ": " + e.getMessage());
                allSucceeded = false;
            }
        }

        return allSucceeded;
    }

    public boolean sendSms(String message, String... phoneNumbers) {
        return sendSms(message, phoneNumbers == null ? List.of() : Arrays.asList(phoneNumbers));
    }

    public String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }

        String normalized = phoneNumber.trim().replaceAll("[^\\d+]", "");
        if (normalized.isBlank()) {
            return null;
        }

        if (normalized.startsWith("+")) {
            return normalized;
        }

        if (normalized.startsWith("1") && normalized.length() == 11) {
            return "+" + normalized;
        }

        if (normalized.length() == 10) {
            return "+1" + normalized;
        }

        return normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
