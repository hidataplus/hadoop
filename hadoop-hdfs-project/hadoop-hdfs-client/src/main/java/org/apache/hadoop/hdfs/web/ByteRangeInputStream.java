/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdfs.web;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.io.input.BoundedInputStream;
import org.apache.hadoop.fs.FSExceptionMessages;
import org.apache.hadoop.fs.FSInputStream;

import org.apache.hadoop.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.thirdparty.com.google.common.net.HttpHeaders;

import javax.annotation.Nonnull;

/**
 * To support HTTP byte streams, a new connection to an HTTP server needs to be
 * created each time. This class hides the complexity of those multiple
 * connections from the client. Whenever seek() is called, a new connection
 * is made on the successive read(). The normal input stream functions are
 * connected to the currently active input stream.
 */
public abstract class ByteRangeInputStream extends FSInputStream {

  /**
   * This class wraps a URL and provides method to open connection.
   * It can be overridden to change how a connection is opened.
   */
  public static abstract class URLOpener {
    protected URL url;

    public URLOpener(URL u) {
      url = u;
    }

    public void setURL(URL u) {
      url = u;
    }

    public URL getURL() {
      return url;
    }

    /** Connect to server with a data offset. */
    protected abstract HttpURLConnection connect(final long offset,
        final boolean resolved) throws IOException;
  }

  static class InputStreamAndFileLength {
    final Long length;
    final InputStream in;

    InputStreamAndFileLength(Long length, InputStream in) {
      this.length = length;
      this.in = in;
    }
  }

  enum StreamStatus {
    NORMAL, SEEK, CLOSED
  }
  protected InputStream in;
  protected final URLOpener originalURL;
  protected final URLOpener resolvedURL;
  protected long startPos = 0;
  protected long currentPos = 0;
  protected Long fileLength = null;

  StreamStatus status = StreamStatus.SEEK;

  /**
   * Create with the specified URLOpeners. Original url is used to open the
   * stream for the first time. Resolved url is used in subsequent requests.
   * @param o Original url
   * @param r Resolved url
   */
  public ByteRangeInputStream(URLOpener o, URLOpener r) throws IOException {
    this.originalURL = o;
    this.resolvedURL = r;
    getInputStream();
  }

  protected abstract URL getResolvedUrl(final HttpURLConnection connection
  ) throws IOException;

  @VisibleForTesting
  protected InputStream getInputStream() throws IOException {
    switch (status) {
    case NORMAL:
      break;
    case SEEK:
      if (in != null) {
        in.close();
      }
      InputStreamAndFileLength fin = openInputStream(startPos);
      in = fin.in;
      fileLength = fin.length;
      status = StreamStatus.NORMAL;
      break;
    case CLOSED:
      throw new IOException("Stream closed");
    }
    return in;
  }

  @VisibleForTesting
  protected InputStreamAndFileLength openInputStream(long startOffset)
      throws IOException {
    if (startOffset < 0) {
      throw new EOFException("Negative Position");
    }
    // Use the original url if no resolved url exists, eg. if
    // it's the first time a request is made.
    final boolean resolved = resolvedURL.getURL() != null;
    final URLOpener opener = resolved? resolvedURL: originalURL;

    final HttpURLConnection connection = opener.connect(startOffset, resolved);
    resolvedURL.setURL(getResolvedUrl(connection));

    InputStream in = connection.getInputStream();
    final Long length;
    final Map<String, List<String>> headers = connection.getHeaderFields();
    if (isChunkedTransferEncoding(headers)) {
      // file length is not known
      length = null;
    } else {
      // for non-chunked transfer-encoding, get content-length
      final String cl = connection.getHeaderField(HttpHeaders.CONTENT_LENGTH);
      if (cl == null) {
        throw new IOException(HttpHeaders.CONTENT_LENGTH + " is missing: "
            + headers);
      }
      final long streamlength = Long.parseLong(cl);
      length = startOffset + streamlength;

      // Java has a bug with >2GB request streams.  It won't bounds check
      // the reads so the transfer blocks until the server times out
      in = new BoundedInputStream(in, streamlength);
    }

    return new InputStreamAndFileLength(length, in);
  }

  private static boolean isChunkedTransferEncoding(
      final Map<String, List<String>> headers) {
    return contains(headers, HttpHeaders.TRANSFER_ENCODING, "chunked")
        || contains(headers, HttpHeaders.TE, "chunked");
  }

  /** Does the HTTP header map contain the given key, value pair? */
  private static boolean contains(final Map<String, List<String>> headers,
      final String key, final String value) {
    final List<String> values = headers.get(key);
    if (values != null) {
      for(String v : values) {
        for(final StringTokenizer t = new StringTokenizer(v, ",");
            t.hasMoreTokens(); ) {
          if (value.equalsIgnoreCase(t.nextToken())) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private int update(final int n) throws IOException {
    if (n != -1) {
      currentPos += n;
    } else if (fileLength != null && currentPos < fileLength) {
      throw new IOException("Got EOF but currentPos = " + currentPos
          + " < filelength = " + fileLength);
    }
    return n;
  }

  @Override
  public int read() throws IOException {
    final int b = getInputStream().read();
    update((b == -1) ? -1 : 1);
    return b;
  }

  @Override
  public int read(@Nonnull byte b[], int off, int len) throws IOException {
    return update(getInputStream().read(b, off, len));
  }

  /**
   * Seek to the given offset from the start of the file.
   * The next read() will be from that location.  Can't
   * seek past the end of the file.
   */
  @Override
  public void seek(long pos) throws IOException {
    if (pos != currentPos) {
      startPos = pos;
      currentPos = pos;
      if (status != StreamStatus.CLOSED) {
        status = StreamStatus.SEEK;
      }
    }
  }

  @Override
  public int read(long position, byte[] buffer, int offset, int length)
      throws IOException {
    validatePositionedReadArgs(position, buffer, offset, length);
    if (length == 0) {
      return 0;
    }
    try (InputStream in = openInputStream(position).in) {
      return in.read(buffer, offset, length);
    }
  }

  @Override
  public void readFully(long position, byte[] buffer, int offset, int length)
      throws IOException {
    validatePositionedReadArgs(position, buffer, offset, length);
    if (length == 0) {
      return;
    }
    final InputStreamAndFileLength fin = openInputStream(position);
    try {
      if (fin.length != null && length + position > fin.length) {
        throw new EOFException("The length to read " + length
            + " exceeds the file length " + fin.length);
      }
      int nread = 0;
      while (nread < length) {
        int nbytes = fin.in.read(buffer, offset + nread, length - nread);
        if (nbytes < 0) {
          throw new EOFException(FSExceptionMessages.EOF_IN_READ_FULLY);
        }
        nread += nbytes;
      }
    } finally {
      fin.in.close();
    }
  }

  /**
   * Return the current offset from the start of the file
   */
  @Override
  public long getPos() throws IOException {
    return currentPos;
  }

  /**
   * Seeks a different copy of the data.  Returns true if
   * found a new source, false otherwise.
   */
  @Override
  public boolean seekToNewSource(long targetPos) throws IOException {
    return false;
  }

  @Override
  public void close() throws IOException {
    if (in != null) {
      in.close();
      in = null;
    }
    status = StreamStatus.CLOSED;
  }

  @Override
  public synchronized int available() throws IOException{
    getInputStream();
    if(fileLength != null){
      long remaining = fileLength - currentPos;
      return remaining <= Integer.MAX_VALUE ? (int) remaining : Integer.MAX_VALUE;
    }else {
      return Integer.MAX_VALUE;
    }
  }
}
