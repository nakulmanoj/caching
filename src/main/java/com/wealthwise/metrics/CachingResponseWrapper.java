package com.wealthwise.metrics;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * HttpServletResponse wrapper that tees the response body into an internal buffer
 * so the {@link StalenessMetricsFilter} can read V_cached from the JSON response
 * without consuming the client-bound stream.
 */
public class CachingResponseWrapper extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final PrintWriter writer;
    private final ServletOutputStream outputStream;

    public CachingResponseWrapper(HttpServletResponse response) throws IOException {
        super(response);
        this.outputStream = new TeeServletOutputStream(response.getOutputStream(), buffer);
        this.writer = new PrintWriter(new OutputStreamWriter(this.outputStream, StandardCharsets.UTF_8), true);
    }

    @Override
    public PrintWriter getWriter() {
        return writer;
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return outputStream;
    }

    /** Flushes the streams. TeeServletOutputStream writes directly to the delegate. */
    public void copyBodyToResponse() throws IOException {
        outputStream.flush();
    }

    /**
     * Extracts the V_cached value from the JSON response body.
     * Expects a field {@code "vCached": <number>} in the JSON.
     *
     * @return parsed BigDecimal, or null if the field is not found
     */
    public BigDecimal getCachedValue() {
        try {
            String body = buffer.toString(StandardCharsets.UTF_8);
            // Simple regex extraction — avoids pulling in a full JSON parser
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"vCached\"\\s*:\\s*([0-9.]+)")
                    .matcher(body);
            if (m.find()) {
                return new BigDecimal(m.group(1));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---- TeeServletOutputStream: writes to both delegate and buffer ----

    private static class TeeServletOutputStream extends ServletOutputStream {
        private final ServletOutputStream delegate;
        private final OutputStream buffer;

        TeeServletOutputStream(ServletOutputStream delegate, OutputStream buffer) {
            this.delegate = delegate;
            this.buffer = buffer;
        }

        @Override public void write(int b) throws IOException { delegate.write(b); buffer.write(b); }
        @Override public void write(byte[] b) throws IOException { delegate.write(b); buffer.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len); buffer.write(b, off, len);
        }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setWriteListener(WriteListener l) { delegate.setWriteListener(l); }
    }
}
