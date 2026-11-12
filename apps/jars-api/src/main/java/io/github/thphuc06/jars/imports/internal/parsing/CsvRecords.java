package io.github.thphuc06.jars.imports.internal.parsing;

import java.util.ArrayList;
import java.util.List;

/**
 * A small RFC 4180 reader: fields separated by one delimiter, optionally in double quotes, where a quoted field may
 * contain the delimiter, line breaks and doubled quotes. Statements are at most a few MB, so the whole file is parsed
 * at once; each record keeps the line number it starts on, for error messages that point at the file.
 */
public final class CsvRecords {

    public record Record(int line, List<String> fields) {

        boolean isBlank() {
            return fields.stream().allMatch(String::isBlank);
        }
    }

    private CsvRecords() {}

    /** Picks tab when the header line has a tab and no comma, else comma (a semicolon export is reported as missing columns). */
    public static char delimiterOf(String text) {
        int end = text.indexOf('\n');
        String header = end < 0 ? text : text.substring(0, end);
        return header.indexOf('\t') >= 0 && header.indexOf(',') < 0 ? '\t' : ',';
    }

    public static List<Record> parse(String text, char delimiter) {
        String content = text.startsWith("﻿") ? text.substring(1) : text;
        List<Record> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int recordLine = 1;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
            } else if (c == delimiter) {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c == '\r') {
                // dropped: CRLF and LF files read the same
            } else if (c == '\n') {
                fields.add(field.toString());
                field.setLength(0);
                add(records, recordLine, fields);
                fields = new ArrayList<>();
                line++;
                recordLine = line;
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !fields.isEmpty()) {
            fields.add(field.toString());
            add(records, recordLine, fields);
        }
        return records;
    }

    private static void add(List<Record> records, int line, List<String> fields) {
        Record record = new Record(line, List.copyOf(fields));
        if (!record.isBlank()) {
            records.add(record);
        }
    }
}
