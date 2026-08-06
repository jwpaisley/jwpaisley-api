package com.jwpaisley.helpers;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LoggingHelper {
    private static final String APP_NAME = "jwpaisley-api";
    private static final String RESET = "\u001B[0m";
    private static final String BRACKET_COLOR_HEX = "#73849d";
    private static final String HIGHLIGHT_TEXT_COLOR_HEX = "#a6b4c8";
    private static final String APP_NAME_COLOR_HEX = "#86b28c";
    private static final String WHITE_HEX = "#ffffff";
    private static final String DEBUG_LEVEL_COLOR_HEX = "#a6b4c8";
    private static final String INFO_LEVEL_COLOR_HEX = "#7ca6c6";
    private static final String WARNING_LEVEL_COLOR_HEX = "#e0b66d";
    private static final String SUCCESS_LEVEL_COLOR_HEX = "#78b48a";
    private static final String ERROR_LEVEL_COLOR_HEX = "#b45d6b";
    private static final String BRACKET_COLOR = colorHex(BRACKET_COLOR_HEX);
    private static final String HIGHLIGHT_TEXT_COLOR = colorHex(HIGHLIGHT_TEXT_COLOR_HEX);
    private static final String APP_NAME_COLOR = colorHex(APP_NAME_COLOR_HEX);
    private static final String WHITE = colorHex(WHITE_HEX);

    private LoggingHelper() {
    }

    public static void debug(String message) {
        log(LogLevel.DEBUG, message);
    }

    public static void info(String message) {
        log(LogLevel.INFO, message);
    }

    public static void warning(String message) {
        log(LogLevel.WARNING, message);
    }

    public static void success(String message) {
        log(LogLevel.SUCCESS, message);
    }

    public static void error(String message) {
        log(LogLevel.ERROR, message);
    }

    public static String formatMessage(LogLevel level, String message) {
        String timestamp = formatTimestamp(LocalDateTime.now(ZoneId.systemDefault()));
        String levelColor = colorHex(level.hexColor);
        return WHITE + timestamp + RESET + " "
            + BRACKET_COLOR + "[" + RESET
            + APP_NAME_COLOR + APP_NAME + RESET
            + BRACKET_COLOR + "]" + RESET
            + " "
            + BRACKET_COLOR + "[" + RESET
            + levelColor + level.label + RESET
            + BRACKET_COLOR + "]" + RESET
            + WHITE + ": " + formatSpecialText(message) + RESET;
    }

    private static String formatSpecialText(String message) {
        String withStructuredText = formatStructuredText(message);
        return formatHighlightedText(withStructuredText);
    }

    private static String formatHighlightedText(String message) {
        return message.replaceAll("<([^>]*)>", HIGHLIGHT_TEXT_COLOR + "$1" + RESET + WHITE);
    }

    private static String formatStructuredText(String message) {
        if (message == null || message.isBlank()) {
            return message == null ? "" : message;
        }

        StringBuilder formatted = new StringBuilder();
        int index = 0;
        while (index < message.length()) {
            char current = message.charAt(index);
            if (current == '{') {
                int endIndex = findMatchingBrace(message, index);
                if (endIndex > index) {
                    formatted.append(formatStructuredObject(message.substring(index + 1, endIndex), 0));
                    index = endIndex + 1;
                    continue;
                }
            }

            formatted.append(current);
            index++;
        }

        return formatted.toString();
    }

    private static String formatStructuredObject(String content, int indentLevel) {
        if (content == null || content.isBlank()) {
            return HIGHLIGHT_TEXT_COLOR + "{}" + RESET + WHITE;
        }

        String indent = "  ".repeat(indentLevel);
        String childIndent = "  ".repeat(indentLevel + 1);
        StringBuilder formatted = new StringBuilder();
        formatted.append(HIGHLIGHT_TEXT_COLOR).append("{").append(RESET).append(WHITE);

        List<String> entries = splitTopLevelEntries(content);
        for (String entry : entries) {
            String trimmedEntry = entry.trim();
            if (trimmedEntry.isEmpty()) {
                continue;
            }

            formatted.append("\n").append(childIndent);
            int separatorIndex = findEntrySeparator(trimmedEntry);
            if (separatorIndex > 0) {
                String key = trimmedEntry.substring(0, separatorIndex).trim();
                String value = trimmedEntry.substring(separatorIndex + 1).trim();
                formatted.append(HIGHLIGHT_TEXT_COLOR)
                    .append(key)
                    .append(RESET)
                    .append(WHITE)
                    .append(": ")
                    .append(HIGHLIGHT_TEXT_COLOR)
                    .append(formatStructuredValue(value, indentLevel + 1))
                    .append(RESET)
                    .append(WHITE);
            } else {
                formatted.append(HIGHLIGHT_TEXT_COLOR).append(trimmedEntry).append(RESET).append(WHITE);
            }
        }

        formatted.append("\n").append(indent).append(HIGHLIGHT_TEXT_COLOR).append("}").append(RESET).append(WHITE);
        return formatted.toString();
    }

    private static String formatStructuredValue(String value, int indentLevel) {
        String trimmedValue = value.trim();
        if (trimmedValue.startsWith("{") && trimmedValue.endsWith("}")) {
            return formatStructuredObject(trimmedValue.substring(1, trimmedValue.length() - 1), indentLevel);
        }

        return trimmedValue;
    }

    private static List<String> splitTopLevelEntries(String content) {
        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inQuotes = false;

        for (int index = 0; index < content.length(); index++) {
            char currentChar = content.charAt(index);
            if (currentChar == '"' && (index == 0 || content.charAt(index - 1) != '\\')) {
                inQuotes = !inQuotes;
            }

            if (!inQuotes) {
                if (currentChar == '{') {
                    depth++;
                } else if (currentChar == '}') {
                    depth = Math.max(0, depth - 1);
                } else if (currentChar == ',' && depth == 0) {
                    entries.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }

            current.append(currentChar);
        }

        if (!current.isEmpty()) {
            entries.add(current.toString());
        }

        return entries;
    }

    private static int findEntrySeparator(String entry) {
        int depth = 0;
        boolean inQuotes = false;

        for (int index = 0; index < entry.length(); index++) {
            char currentChar = entry.charAt(index);
            if (currentChar == '"' && (index == 0 || entry.charAt(index - 1) != '\\')) {
                inQuotes = !inQuotes;
            }

            if (!inQuotes) {
                if (currentChar == '{') {
                    depth++;
                } else if (currentChar == '}') {
                    depth = Math.max(0, depth - 1);
                } else if (depth == 0 && (currentChar == ':' || currentChar == '=')) {
                    return index;
                }
            }
        }

        return -1;
    }

    private static int findMatchingBrace(String message, int openingIndex) {
        int depth = 0;
        boolean inQuotes = false;

        for (int index = openingIndex; index < message.length(); index++) {
            char currentChar = message.charAt(index);
            if (currentChar == '"' && (index == 0 || message.charAt(index - 1) != '\\')) {
                inQuotes = !inQuotes;
            }

            if (!inQuotes) {
                if (currentChar == '{') {
                    depth++;
                } else if (currentChar == '}') {
                    depth--;
                    if (depth == 0) {
                        return index;
                    }
                }
            }
        }

        return -1;
    }

    private static void log(LogLevel level, String message) {
        String formattedMessage = formatMessage(level, message);
        if (level == LogLevel.ERROR) {
            System.err.println(formattedMessage);
        } else {
            System.out.println(formattedMessage);
        }
    }

    private static String formatTimestamp(LocalDateTime timestamp) {
        String month = timestamp.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.US).toLowerCase(Locale.US);
        int day = timestamp.getDayOfMonth();
        String ordinal = ordinalSuffix(day);
        String time = timestamp.format(DateTimeFormatter.ofPattern("h:mma", Locale.US)).toLowerCase(Locale.US);
        int year = timestamp.getYear();
        return month + " " + day + ordinal + ", " + year + " @ " + time;
    }

    private static String ordinalSuffix(int day) {
        if (day >= 11 && day <= 13) {
            return "th";
        }

        return switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    private static String colorHex(String hex) {
        String normalized = hex.startsWith("#") ? hex.substring(1) : hex;
        int red = Integer.parseInt(normalized.substring(0, 2), 16);
        int green = Integer.parseInt(normalized.substring(2, 4), 16);
        int blue = Integer.parseInt(normalized.substring(4, 6), 16);
        return "\u001B[38;2;" + red + ";" + green + ";" + blue + "m";
    }

    public enum LogLevel {
        DEBUG("DEBUG", DEBUG_LEVEL_COLOR_HEX),
        INFO("INFO", INFO_LEVEL_COLOR_HEX),
        WARNING("WARNING", WARNING_LEVEL_COLOR_HEX),
        SUCCESS("SUCCESS", SUCCESS_LEVEL_COLOR_HEX),
        ERROR("ERROR", ERROR_LEVEL_COLOR_HEX);

        private final String label;
        private final String hexColor;

        LogLevel(String label, String hexColor) {
            this.label = label;
            this.hexColor = hexColor;
        }
    }
}
