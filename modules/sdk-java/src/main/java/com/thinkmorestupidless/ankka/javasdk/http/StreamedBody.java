package com.thinkmorestupidless.ankka.javasdk.http;

import java.io.InputStream;
import java.util.OptionalLong;

/**
 * The request body of a {@code @StreamBody} route, read as it arrives and never held whole: hand
 * {@code body} to a bucket's {@code put} with {@code length} to keep an upload of any size.
 */
public record StreamedBody(String contentType, OptionalLong length, InputStream body) {}
