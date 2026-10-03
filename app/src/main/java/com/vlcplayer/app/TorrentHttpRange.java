package com.vlcplayer.app;

/** Parses the single byte range supported by the local torrent HTTP server. */
final class TorrentHttpRange {
    private TorrentHttpRange() {}

    static Result parse(String rangeHeader, long size, boolean headOnly) {
        if (size < 0) throw new IllegalArgumentException("Negative file size");
        // Range applies to GET. HEAD reports the full representation's metadata.
        if (headOnly || rangeHeader == null) return new Result(200, 0, size - 1);
        String value = rangeHeader.trim();
        int equals = value.indexOf('=');
        if (equals < 0) return unsatisfiable();
        // Unrecognized range units are ignored (RFC 9110 section 14.2).
        if (!"bytes".equalsIgnoreCase(value.substring(0, equals).trim())) {
            return new Result(200, 0, size - 1);
        }
        String bounds = value.substring(equals + 1).trim();
        int dash = bounds.indexOf('-');
        if (size == 0 || dash < 0 || bounds.indexOf('-', dash + 1) >= 0
                || bounds.indexOf(',') >= 0) return unsatisfiable();
        String first = bounds.substring(0, dash);
        String last = bounds.substring(dash + 1);
        try {
            if (first.isEmpty()) {
                long suffixLength = decimal(last);
                if (suffixLength == 0) return unsatisfiable();
                return new Result(206, size - Math.min(size, suffixLength), size - 1);
            }
            long start = decimal(first);
            if (start >= size) return unsatisfiable();
            long end = last.isEmpty() ? size - 1 : decimal(last);
            if (end < start) return unsatisfiable();
            return new Result(206, start, Math.min(end, size - 1));
        } catch (NumberFormatException invalid) {
            return unsatisfiable();
        }
    }

    private static long decimal(String value) {
        if (value.isEmpty()) throw new NumberFormatException("Empty byte position");
        long number = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') throw new NumberFormatException("Invalid byte position");
            int digit = c - '0';
            // Oversized end/suffix values still have a defined meaning: EOF/all bytes.
            // Oversized starts are rejected by the caller's file size bound.
            number = number > (Long.MAX_VALUE - digit) / 10
                ? Long.MAX_VALUE : number * 10 + digit;
        }
        return number;
    }

    private static Result unsatisfiable() {
        return new Result(416, 0, -1);
    }

    static final class Result {
        final int statusCode;
        final long start;
        final long end;
        final long length;

        private Result(int statusCode, long start, long end) {
            this.statusCode = statusCode;
            this.start = start;
            this.end = end;
            this.length = end < start ? 0 : end - start + 1;
        }

        boolean satisfiable() {
            return statusCode != 416;
        }
    }
}
