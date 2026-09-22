package com.soyxan.sidesubs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.soyxan.sidesubs.PlexModels.Cue;

final class SubtitleParser {
    private static final Pattern SRT_TIME = Pattern.compile(
        "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*" +
        "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})"
    );
    private static final Pattern ASS_TAG = Pattern.compile("\\{[^}]*}");

    private SubtitleParser() {}

    static List<Cue> parse(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        List<Cue> ass = parseAss(text);
        if (!ass.isEmpty()) return ass;
        return parseSrt(text);
    }

    private static List<Cue> parseAss(String text) {
        List<Cue> cues = new ArrayList<>();
        String[] lines = text.replace("\r", "").split("\n");
        for (String line : lines) {
            if (!line.startsWith("Dialogue:")) continue;
            String body = line.substring("Dialogue:".length()).trim();
            String[] fields = body.split(",", 10);
            if (fields.length < 10) continue;
            Double start = parseAssTime(fields[1]);
            Double end = parseAssTime(fields[2]);
            if (start == null || end == null || end < start) continue;
            String value = cleanAssText(fields[9]);
            if (!value.isEmpty()) cues.add(new Cue(start, end, value));
        }
        return cues;
    }

    private static String cleanAssText(String value) {
        String cleaned = value
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ");
        cleaned = ASS_TAG.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    private static Double parseAssTime(String value) {
        try {
            String[] p = value.trim().split(":");
            if (p.length != 3) return null;
            return Integer.parseInt(p[0]) * 3600.0
                + Integer.parseInt(p[1]) * 60.0
                + Double.parseDouble(p[2].replace(',', '.'));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<Cue> parseSrt(String text) {
        List<Cue> cues = new ArrayList<>();
        String[] lines = text.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = SRT_TIME.matcher(lines[i].trim());
            if (!matcher.matches()) continue;

            double start = srtSeconds(matcher, 1);
            double end = srtSeconds(matcher, 5);
            StringBuilder value = new StringBuilder();
            int j = i + 1;
            while (j < lines.length && !lines[j].trim().isEmpty()) {
                if (value.length() > 0) value.append('\n');
                value.append(lines[j].trim());
                j++;
            }
            String cleaned = value.toString()
                .replaceAll("<[^>]+>", "")
                .trim();
            if (!cleaned.isEmpty() && end >= start) {
                cues.add(new Cue(start, end, cleaned));
            }
            i = j;
        }
        return cues;
    }

    private static double srtSeconds(Matcher m, int offset) {
        int h = Integer.parseInt(m.group(offset));
        int min = Integer.parseInt(m.group(offset + 1));
        int sec = Integer.parseInt(m.group(offset + 2));
        String msText = m.group(offset + 3);
        int ms = Integer.parseInt(msText);
        if (msText.length() == 1) ms *= 100;
        else if (msText.length() == 2) ms *= 10;
        return h * 3600.0 + min * 60.0 + sec + ms / 1000.0;
    }
}
