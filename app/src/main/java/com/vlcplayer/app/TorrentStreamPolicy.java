package com.vlcplayer.app;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Byte-based piece windows for a single selected file, including unaligned v1 files. */
final class TorrentStreamPolicy {
    static final long HEAD_BYTES = 8L * 1024L * 1024L;
    static final long TAIL_BYTES = 2L * 1024L * 1024L;
    static final long AHEAD_BYTES = 8L * 1024L * 1024L;

    interface Availability { boolean hasPiece(int piece); }

    final long fileOffset;
    final long fileSize;
    final int pieceLength;
    final int firstPiece;
    final int lastPiece;
    private final Set<Integer> startup;

    TorrentStreamPolicy(long fileOffset, long fileSize, int pieceLength) {
        if (fileOffset < 0 || fileSize <= 0 || pieceLength <= 0
                || fileOffset > Long.MAX_VALUE - fileSize
                || (fileOffset + fileSize - 1) / pieceLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid selected torrent file geometry");
        }
        this.fileOffset = fileOffset;
        this.fileSize = fileSize;
        this.pieceLength = pieceLength;
        firstPiece = pieceAt(0);
        lastPiece = pieceAt(fileSize - 1);
        TreeSet<Integer> pieces = new TreeSet<>();
        addRange(pieces, 0, Math.min(fileSize, HEAD_BYTES) - 1);
        addRange(pieces, Math.max(0, fileSize - TAIL_BYTES), fileSize - 1);
        startup = Collections.unmodifiableSet(pieces);
    }

    Set<Integer> startupPieces() { return startup; }

    boolean ready(Availability availability, boolean fileReadable) {
        if (!fileReadable) return false;
        for (int piece : startup) if (!availability.hasPiece(piece)) return false;
        return true;
    }

    Set<Integer> requestPieces(long position, long requestEnd) {
        if (position < 0 || requestEnd < position || requestEnd >= fileSize) {
            throw new IllegalArgumentException("Request outside selected torrent file");
        }
        TreeSet<Integer> pieces = new TreeSet<>();
        long end = position + Math.min(requestEnd - position, AHEAD_BYTES - 1);
        addRange(pieces, position, end);
        return pieces;
    }

    int pieceAt(long position) {
        if (position < 0 || position >= fileSize) {
            throw new IllegalArgumentException("Position outside selected torrent file");
        }
        return (int) ((fileOffset + position) / pieceLength);
    }

    int readLength(long position, long remaining, int bufferLength) {
        pieceAt(position); // Validate before calculating an absolute offset.
        if (remaining <= 0 || bufferLength <= 0) throw new IllegalArgumentException("Empty read");
        long toBoundary = pieceLength - ((fileOffset + position) % pieceLength);
        return (int) Math.min(Math.min(Math.min(toBoundary, remaining), bufferLength), fileSize - position);
    }

    private void addRange(Set<Integer> pieces, long start, long end) {
        int first = pieceAt(start);
        int last = pieceAt(end);
        for (int piece = first; ; piece++) {
            pieces.add(piece);
            if (piece == last) break;
        }
    }
}
