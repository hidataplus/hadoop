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
package org.apache.hadoop.hdfs.tools.offlineImageViewer;

import org.apache.hadoop.thirdparty.com.google.common.base.Charsets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.hdfs.web.JsonUtil;
import org.apache.hadoop.util.StringUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Values.CLOSE;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.apache.hadoop.hdfs.server.datanode.web.webhdfs.WebHdfsHandler.APPLICATION_JSON_UTF8;
import static org.apache.hadoop.hdfs.server.datanode.web.webhdfs.WebHdfsHandler.WEBHDFS_PREFIX;
import static org.apache.hadoop.hdfs.server.datanode.web.webhdfs.WebHdfsHandler.WEBHDFS_PREFIX_LENGTH;

/**
 * Implement the read-only WebHDFS API for fsimage.
 */
class FSImageHandler extends SimpleChannelInboundHandler<HttpRequest> {
  public static final Logger LOG =
      LoggerFactory.getLogger(FSImageHandler.class);
  private final FSImageLoader image;
  private final ChannelGroup activeChannels;

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    activeChannels.add(ctx.channel());
  }

  FSImageHandler(FSImageLoader image, ChannelGroup activeChannels) throws IOException {
    this.image = image;
    this.activeChannels = activeChannels;
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpRequest request)
      throws Exception {
    if (request.getMethod() != HttpMethod.GET) {
      DefaultHttpResponse resp = new DefaultHttpResponse(HTTP_1_1,
          METHOD_NOT_ALLOWED);
      resp.headers().set(CONNECTION, CLOSE);
      ctx.write(resp).addListener(ChannelFutureListener.CLOSE);
      return;
    }

    QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());
    // check path. throw exception if path doesn't start with WEBHDFS_PREFIX
    String path = getPath(decoder);
    final String op = getOp(decoder);
    // check null op
    if (op == null) {
      throw new IllegalArgumentException("Param op must be specified.");
    }

    final String content;
    switch (op) {
    case "GETFILESTATUS":
      content = image.getFileStatus(path);
      break;
    case "LISTSTATUS":
      content = image.listStatus(path);
      break;
    case "GETACLSTATUS":
      content = image.getAclStatus(path);
      break;
    case "GETXATTRS":
      List<String> names = getXattrNames(decoder);
      String encoder = getEncoder(decoder);
      content = image.getXAttrs(path, names, encoder);
      break;
    case "LISTXATTRS":
      content = image.listXAttrs(path);
      break;
    case "GETCONTENTSUMMARY":
      content = image.getContentSummary(path);
      break;
    default:
      throw new IllegalArgumentException("Invalid value for webhdfs parameter"
          + " \"op\"");
    }

    LOG.info("op=" + op + " target=" + path);

    DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1,
        HttpResponseStatus.OK, Unpooled.wrappedBuffer(content
            .getBytes(Charsets.UTF_8)));
    resp.headers().set(CONTENT_TYPE, APPLICATION_JSON_UTF8);
    resp.headers().set(CONTENT_LENGTH, resp.content().readableBytes());
    resp.headers().set(CONNECTION, CLOSE);
    ctx.write(resp).addListener(ChannelFutureListener.CLOSE);
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
    ctx.flush();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
          throws Exception {
    Exception e = cause instanceof Exception ? (Exception) cause : new
      Exception(cause);
    final String output = JsonUtil.toJsonString(e);
    ByteBuf content = Unpooled.wrappedBuffer(output.getBytes(Charsets.UTF_8));
    final DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
            HTTP_1_1, INTERNAL_SERVER_ERROR, content);

    resp.headers().set(CONTENT_TYPE, APPLICATION_JSON_UTF8);
    if (e instanceof IllegalArgumentException) {
      resp.setStatus(BAD_REQUEST);
    } else if (e instanceof FileNotFoundException) {
      resp.setStatus(NOT_FOUND);
    } else if (e instanceof IOException) {
      resp.setStatus(FORBIDDEN);
    }
    resp.headers().set(CONTENT_LENGTH, resp.content().readableBytes());
    resp.headers().set(CONNECTION, CLOSE);
    ctx.write(resp).addListener(ChannelFutureListener.CLOSE);
  }

  private static String getOp(QueryStringDecoder decoder) {
    Map<String, List<String>> parameters = decoder.parameters();
    return parameters.containsKey("op")
        ? StringUtils.toUpperCase(parameters.get("op").get(0)) : null;
  }

  private static List<String> getXattrNames(QueryStringDecoder decoder) {
    Map<String, List<String>> parameters = decoder.parameters();
    return parameters.get("xattr.name");
  }

  private static String getEncoder(QueryStringDecoder decoder) {
    Map<String, List<String>> parameters = decoder.parameters();
    return parameters.containsKey("encoding") ? parameters.get("encoding").get(
        0) : null;
  }

  private static String getPath(QueryStringDecoder decoder)
          throws FileNotFoundException {
    String path = decoder.path();
    if (path.startsWith(WEBHDFS_PREFIX)) {
      return path.substring(WEBHDFS_PREFIX_LENGTH);
    } else {
      throw new FileNotFoundException("Path: " + path + " should " +
              "start with " + WEBHDFS_PREFIX);
    }
  }
}
